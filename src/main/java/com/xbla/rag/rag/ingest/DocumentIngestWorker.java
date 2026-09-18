package com.xbla.rag.rag.ingest;

import com.xbla.rag.client.EmbeddingClient;
import com.xbla.rag.client.dto.EmbeddingResult;
import com.xbla.rag.common.util.DigestUtil;
import com.xbla.rag.config.KbProperties;
import com.xbla.rag.entity.KbChunk;
import com.xbla.rag.entity.KbDocument;
import com.xbla.rag.mapper.KbChunkMapper;
import com.xbla.rag.mapper.KbDocumentMapper;
import com.xbla.rag.rag.chunk.HeadingAwareChunker;
import com.xbla.rag.rag.chunk.TextChunk;
import com.xbla.rag.rag.chunk.TextChunkingOptions;
import com.xbla.rag.rag.parse.DocumentParser;
import com.xbla.rag.rag.parse.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档入库流水线。在 {@code ingestExecutor} 线程池里跑，调用方是
 * {@link com.xbla.rag.service.KbIngestService}。
 *
 * <h2>一、四步流水线</h2>
 * <pre>
 *   kb_document.file_path（落盘的原始文件）
 *        │
 *        ├─① 解析   DocumentParser（Tika）      → List&lt;TextBlock&gt;
 *        ├─② 切分   HeadingAwareChunker        → List&lt;TextChunk&gt;
 *        ├─③ 向量化 EmbeddingClient（bge-m3）   → List&lt;float[]&gt;
 *        └─④ 写库   KbChunkMapper              → kb_chunk
 *        │
 *   kb_document.status: 1 待处理 → 2 处理中 → 3 已入库 / 4 处理失败
 * </pre>
 *
 * <h2>二、★ 为什么整条流水线不放在一个数据库事务里</h2>
 *
 * <p>最自然的写法是给 {@code process()} 加 {@code @Transactional}，失败自动回滚。
 * <b>但那样会拖垮整个应用。</b>
 *
 * <p>因为③向量化要调外部 API，一份几百片的文档要跑几十秒到几分钟。
 * 事务开着跨越这几分钟，意味着<b>一条 HikariCP 连接被独占了这几分钟</b>。
 * 而连接池的 {@code maximum-pool-size} 只有 10 ——
 * 4 个并发的入库任务就能把池子占掉四成，再叠加正常的问答请求，
 * 整个应用的数据库访问会集体排队等待。
 *
 * <p><b>结论：长耗时的外部调用绝不能放在数据库事务里。</b>
 * 这是分布式系统里「不要用事务包裹远程调用」这条原则在单体应用里的翻版。
 *
 * <p>代价是「④写库」这一步失去了原子性。用<b>补偿删除</b>来弥补：
 * 任何一步失败，就把这次写的切片按 {@code document_id} 全部删掉，
 * 让数据库回到「这份文档什么都没有」的状态。删干净之后
 * {@code status=4}，检索侧自然不会用到它。
 *
 * <h2>三、幂等性</h2>
 *
 * <p>入库前先按 {@code document_id} 删掉已有切片，所以同一份文档重复入库
 * 得到的结果是一样的（而不是累积出两份切片）。
 * 但<b>重复入库会重复花向量化的钱</b> —— 真正的去重在入库之前靠
 * {@code kb_document.file_hash} 拦（见 {@code KbIngestServiceImpl}）。
 */
@Component
public class DocumentIngestWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestWorker.class);

    // 状态常量统一从实体类取，不在各处重复定义字面量
    private static final int STATUS_PENDING = KbDocument.STATUS_PENDING;
    private static final int STATUS_PROCESSING = KbDocument.STATUS_PROCESSING;
    private static final int STATUS_DONE = KbDocument.STATUS_DONE;
    private static final int STATUS_FAILED = KbDocument.STATUS_FAILED;

    private final DocumentParser documentParser;
    private final HeadingAwareChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final KbProperties kbProperties;

    public DocumentIngestWorker(DocumentParser documentParser,
                                HeadingAwareChunker chunker,
                                EmbeddingClient embeddingClient,
                                KbDocumentMapper documentMapper,
                                KbChunkMapper chunkMapper,
                                KbProperties kbProperties) {
        this.documentParser = documentParser;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.kbProperties = kbProperties;
    }

    /**
     * 处理一份已登记的文档（{@code status=1}）。
     *
     * <p><b>本方法自己吞掉所有异常</b>，绝不往外抛。理由：它跑在线程池里，
     * 抛出去的异常除了在线程池的日志里留一行之外没有任何作用 ——
     * 真正需要知道「失败了」的人在前端页面，而他们只能通过
     * {@code kb_document.status=4} 和 {@code error_msg} 得知。
     * 所以所有失败路径都必须落到数据库上。
     */
    public void process(Long documentId) {
        KbDocument doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("入库任务找不到文档，可能已被删除 documentId={}", documentId);
            return;
        }
        if (doc.getStatus() != STATUS_PENDING) {
            // 防止同一个文档被重复提交：第二次进来时状态已经不是 1 了。
            // 这道检查很重要 —— 没有它，两次提交会各自写一份切片，
            // 检索时同一段内容出现两次，白白占掉 TopK 名额
            log.warn("文档状态不是待处理，跳过 documentId={} status={}", documentId, doc.getStatus());
            return;
        }

        long startNanos = System.nanoTime();
        markStatus(doc, STATUS_PROCESSING, null);

        try {
            ParsedDocument parsed = parseFile(doc);
            List<TextChunk> chunks = chunk(parsed, doc);
            List<float[]> vectors = embed(chunks);
            writeChunks(doc, chunks, vectors);

            doc.setChunkCount(chunks.size());
            doc.setMimeType(parsed.mimeType());
            if (doc.getTitle() == null && parsed.title() != null) {
                // 上传时没给标题就用文档元数据里的。注意这一步【不改文件内容】，
                // 只是补一个展示用的标题
                doc.setTitle(parsed.title());
            }
            markStatus(doc, STATUS_DONE, null);

            log.info("入库成功 docNo={} title={} chunks={} headings={} 耗时={}ms",
                    doc.getDocNo(), doc.getTitle(), chunks.size(), parsed.headingCount(),
                    (System.nanoTime() - startNanos) / 1_000_000);

        } catch (Exception e) {
            // ★ 补偿删除：把这次（可能只写了一半的）切片全部清掉。
            //   不做这一步的话，失败文档的切片会留在库里，
            //   而检索只认 kb_chunk.deleted=0、不看文档状态 ——
            //   结果是「一份标着处理失败的文档，它的半截内容照样被检索命中」
            deleteChunksOf(doc.getId());

            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            markStatus(doc, STATUS_FAILED, truncate(message, 2000));
            log.error("入库失败 docNo={} file={} 耗时={}ms",
                    doc.getDocNo(), doc.getFileName(),
                    (System.nanoTime() - startNanos) / 1_000_000, e);
        }
    }

    // ================================================================
    // ① 解析
    // ================================================================

    private ParsedDocument parseFile(KbDocument doc) throws IOException {
        Path path = resolvePath(doc);
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("原始文件不存在或不可读：" + path);
        }

        ParsedDocument parsed;
        // ★ try-with-resources 关的是【我们自己打开的】流。
        //   解析器约定不接管流所有权，所以这里的关闭责任在我们身上
        try (InputStream in = Files.newInputStream(path)) {
            parsed = documentParser.parse(in, doc.getFileName());
        }

        if (parsed.isEmpty()) {
            throw new IllegalStateException(
                    "文档解析后没有任何文本内容（可能是扫描件或纯图片文档，需要 OCR）");
        }
        return parsed;
    }

    /**
     * 解析 {@code kb_document.file_path}。
     *
     * <p>存的是相对路径，相对于应用启动目录。
     * 用相对路径的好处是换台机器只要把 {@code data/} 目录一起搬过去就行，
     * 不会因为绝对路径里的用户名不同而失效。
     */
    private static Path resolvePath(KbDocument doc) {
        return Path.of(doc.getFilePath());
    }

    // ================================================================
    // ② 切分
    // ================================================================

    private List<TextChunk> chunk(ParsedDocument parsed, KbDocument doc) {
        TextChunkingOptions options = TextChunkingOptions.from(kbProperties.getChunking());
        List<TextChunk> chunks = chunker.chunk(parsed.blocks(), options, doc.getTitle());
        if (chunks.isEmpty()) {
            throw new IllegalStateException("切分后没有产生任何切片");
        }
        return chunks;
    }

    // ================================================================
    // ③ 向量化
    // ================================================================

    /**
     * 批量向量化所有切片。
     *
     * <p><b>为什么必须批量</b>：逐条调用的话，一份 300 片的文档要发 300 次 HTTP 请求，
     * 而 bge-m3 单次最多能处理 32 条。批量提交能把请求数压到 10 次 ——
     * <b>网络往返次数少了一个数量级，耗时和限流风险都跟着降</b>。
     * 分批逻辑在 {@code OpenAiCompatibleEmbeddingClient} 内部，这里只管提交全量。
     */
    private List<float[]> embed(List<TextChunk> chunks) {
        List<String> texts = new ArrayList<>(chunks.size());
        int maxChars = kbProperties.getEmbedding().getMaxCharsPerText();

        for (TextChunk chunk : chunks) {
            String content = chunk.content();
            if (content.length() > maxChars) {
                // 兜底截断。正常切分不会走到这里（切片远短于 maxCharsPerText），
                // 所以打了 WARN —— 它出现就说明切分参数被调得不合理了
                log.warn("切片超长已截断 index={} 长度={} 上限={}", chunk.index(), content.length(), maxChars);
                content = content.substring(0, maxChars);
            }
            texts.add(content);
        }

        EmbeddingResult result = embeddingClient.embed(texts);

        // ★ 条数必须严格相等。不等的话后面按 index 取值会错位 ——
        //   把 A 切片的向量写到 B 切片上，检索结果会变得完全不讲道理，
        //   而且没有任何报错。这种错误一旦入库就无法自动发现
        if (result.size() != chunks.size()) {
            throw new IllegalStateException(
                    "向量化返回条数与切片数不一致：期望 " + chunks.size() + "，实际 " + result.size());
        }
        return result.vectors();
    }

    // ================================================================
    // ④ 写库
    // ================================================================

    private void writeChunks(KbDocument doc, List<TextChunk> chunks, List<float[]> vectors) {
        // 先清掉这份文档已有的切片。让重复入库是幂等的（替换而不是累加）
        deleteChunksOf(doc.getId());

        int index = 0;
        for (TextChunk chunk : chunks) {
            KbChunk entity = new KbChunk();
            entity.setDocumentId(doc.getId());
            entity.setChunkIndex(chunk.index());
            entity.setContent(chunk.content());
            entity.setContentHash(DigestUtil.sha256(chunk.content()));
            entity.setHeadingPath(chunk.headingPath());
            // ★ token_count 刻意留 null，不填字符数也不做估算。
            //
            //   理由和 ADR-010「拿不到用量时记 NULL，绝不估算」是同一条：
            //   token 数和字符数【不是一回事】—— 中文 1 字约 0.7~1 token，
            //   英文 1 词约 1.3 token，代码和标点的比例又完全不同。
            //   把字符数填进 token_count 列是【错误的标签】，
            //   而阶段 7 统计「切片长度分布」时看到非空值会以为它是真的 token 数。
            //
            //   要拿到真值需要 bge-m3 的 XLM-RoBERTa sentencepiece 分词器，
            //   而 Java 侧没有它。引一个分词库只能得到别的模型的分词结果 ——
            //   仍然是估算，只是看起来更专业而已。
            //
            //   字符数已经存在 charCount 里（TextChunk），需要时现算也有：
            //   LENGTH(content)。所以 null 不会丢信息
            entity.setEmbedding(vectors.get(index));

            // ★ 冗余自文档的四个字段。阶段 5 的意图定向检索靠它们
            //   在向量扫描的同一次索引访问里完成过滤，避免 join 回表
            entity.setRelatedProductId(doc.getRelatedProductId());
            entity.setDocType(doc.getDocType());
            entity.setEffectiveFrom(doc.getEffectiveFrom());
            entity.setEffectiveTo(doc.getEffectiveTo());

            chunkMapper.insert(entity);
            index++;
        }
    }

    /**
     * 按文档 ID 删除切片。
     *
     * <p>用的是<b>物理删除</b>（自定义 SQL），不是 MyBatis-Plus 的逻辑删除。
     * 理由：这是「重新入库前清场」和「失败后补偿」，删掉的内容本来就不该被检索到，
     * 保留 {@code deleted=1} 的行只会让表无限膨胀。
     * 而逻辑删除是为「业务上删除但要留痕」设计的场景（见 docs/04 §1.2）。
     */
    private void deleteChunksOf(Long documentId) {
        int deleted = chunkMapper.physicalDeleteByDocumentId(documentId);
        if (deleted > 0) {
            log.debug("清理旧切片 documentId={} 条数={}", documentId, deleted);
        }
    }

    // ================================================================
    // 状态流转
    // ================================================================

    private void markStatus(KbDocument doc, int status, String errorMsg) {
        KbDocument update = new KbDocument();
        update.setId(doc.getId());
        update.setStatus(status);
        update.setErrorMsg(errorMsg);
        update.setUpdatedAt(OffsetDateTime.now());
        if (status == STATUS_DONE) {
            update.setChunkCount(doc.getChunkCount());
            update.setMimeType(doc.getMimeType());
            update.setTitle(doc.getTitle());
        }
        documentMapper.updateById(update);
    }

    /** 错误信息要落进 {@code error_msg TEXT} 列，但没必要存一整篇堆栈 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…（已截断）";
    }

    /**
     * 供外部（服务层）在提交前判断这个文档是否处于可处理状态。
     *
     * <p>抽出来是因为「什么状态能入库」这个判断在服务层和这里都要用，
     * 分散成两处写死的 {@code == 1} 迟早会改漏一处。
     */
    public static boolean isPending(KbDocument doc) {
        return doc != null && doc.getStatus() != null && doc.getStatus() == STATUS_PENDING;
    }
}
