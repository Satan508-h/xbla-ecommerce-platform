package com.xbla.rag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xbla.rag.config.KbProperties;
import com.xbla.rag.dto.KbBatchSubmitResponse;
import com.xbla.rag.entity.AfterSalePolicy;
import com.xbla.rag.entity.KbDocument;
import com.xbla.rag.entity.Product;
import com.xbla.rag.entity.ProductAttribute;
import com.xbla.rag.mapper.AfterSalePolicyMapper;
import com.xbla.rag.mapper.KbDocumentMapper;
import com.xbla.rag.mapper.ProductAttributeMapper;
import com.xbla.rag.mapper.ProductMapper;
import com.xbla.rag.rag.ingest.DbContentRenderer;
import com.xbla.rag.rag.ingest.DocumentIngestWorker;
import com.xbla.rag.rag.ingest.DocumentStore;
import com.xbla.rag.service.KbIngestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 文档入库服务实现。
 *
 * <h2>★ 职责边界：这里只做「登记 + 派活」，不做重活</h2>
 *
 * <p>本类的方法都在 <b>HTTP 请求线程</b>上执行，必须快速返回。所以它只做三件事：
 * <ol>
 *   <li>把原件落盘（本地磁盘 IO，毫秒级）</li>
 *   <li>写一条 {@code kb_document}（{@code status=1}）</li>
 *   <li>把 {@code documentId} 丢进 {@code ingestExecutor} 线程池</li>
 * </ol>
 *
 * <p>真正的「解析 → 切分 → 向量化 → 写库」在 {@link DocumentIngestWorker} 里，
 * 跑在后台线程上。<b>这个分工让上传接口的响应时间与文档大小无关</b> ——
 * 传一份 50 页的 PDF 和传一份 1 页的 Markdown，接口都是毫秒级返回。
 */
@Slf4j
@Service
public class KbIngestServiceImpl implements KbIngestService {

    private static final DateTimeFormatter DOC_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 语料目录里只处理这些后缀。挡掉 .DS_Store / Thumbs.db 这类系统文件 */
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".pdf", ".docx", ".doc", ".xlsx", ".xls", ".md", ".markdown", ".txt", ".html", ".htm");

    private final DocumentStore documentStore;
    private final DocumentIngestWorker ingestWorker;
    private final DbContentRenderer renderer;
    private final KbDocumentMapper documentMapper;
    private final AfterSalePolicyMapper policyMapper;
    private final ProductMapper productMapper;
    private final ProductAttributeMapper attributeMapper;
    private final KbProperties kbProperties;
    private final ThreadPoolTaskExecutor ingestExecutor;

    public KbIngestServiceImpl(DocumentStore documentStore,
                               DocumentIngestWorker ingestWorker,
                               DbContentRenderer renderer,
                               KbDocumentMapper documentMapper,
                               AfterSalePolicyMapper policyMapper,
                               ProductMapper productMapper,
                               ProductAttributeMapper attributeMapper,
                               KbProperties kbProperties,
                               @Qualifier("ingestExecutor") ThreadPoolTaskExecutor ingestExecutor) {
        this.documentStore = documentStore;
        this.ingestWorker = ingestWorker;
        this.renderer = renderer;
        this.documentMapper = documentMapper;
        this.policyMapper = policyMapper;
        this.productMapper = productMapper;
        this.attributeMapper = attributeMapper;
        this.kbProperties = kbProperties;
        this.ingestExecutor = ingestExecutor;
    }

    // ================================================================
    // ① 上传单个文件
    // ================================================================

    @Override
    public KbDocument submitUpload(InputStream in, String originalFileName, Integer docType,
                                   Long relatedProductId,
                                   OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo) throws IOException {
        DocumentStore.StoredFile stored = documentStore.save(in, originalFileName);

        KbDocument existing = findReusableByHash(stored.fileHash());
        if (existing != null) {
            log.info("文件内容相同且已成功入库，跳过向量化 file={} 复用docId={}",
                    originalFileName, existing.getId());
            return existing;
        }

        KbDocument doc = new KbDocument();
        doc.setDocNo(newDocNo());
        doc.setTitle(deriveTitle(originalFileName));
        doc.setDocType(docType == null ? KbDocument.TYPE_FAQ : docType);
        doc.setSourceType(KbDocument.SOURCE_FILE);
        doc.setFileName(originalFileName);
        doc.setFilePath(stored.filePath());
        doc.setFileSize(stored.fileSize());
        doc.setFileHash(stored.fileHash());
        doc.setRelatedProductId(relatedProductId);
        doc.setEffectiveFrom(effectiveFrom);
        doc.setEffectiveTo(effectiveTo);
        doc.setVersion(1);
        doc.setStatus(KbDocument.STATUS_PENDING);
        doc.setChunkCount(0);

        documentMapper.insert(doc);
        submit(doc.getId());
        return doc;
    }

    // ================================================================
    // ② 扫描语料目录
    // ================================================================

    @Override
    public KbBatchSubmitResponse submitCorpusScan(Integer docType) {
        Path dir = Path.of(kbProperties.getCorpusDir());
        if (!Files.isDirectory(dir)) {
            log.warn("语料目录不存在，跳过：{}（先跑 scripts/generate_corpus.py 生成）",
                    dir.toAbsolutePath());
            return KbBatchSubmitResponse.of(0, List.of(), 0);
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(KbIngestServiceImpl::isSupported)
                    // ★ 排序：文件系统的列举顺序是不保证的。
                    //   不排序的话，两次扫描的入库顺序不同，chunk 的 id 分配也不同，
                    //   阶段 7 做 A/B 对比时「同一份语料」的基线对不上
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.error("读取语料目录失败：{}", dir.toAbsolutePath(), e);
            return KbBatchSubmitResponse.of(0, List.of(), 0);
        }

        List<Long> submitted = new ArrayList<>();
        int skipped = 0;

        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                KbDocument doc = submitUpload(in, file.getFileName().toString(),
                        docType == null ? KbDocument.TYPE_FAQ : docType,
                        null, null, null);
                if (doc.getStatus() != KbDocument.STATUS_PENDING) {
                    // 命中去重，返回的是已存在的那份文档
                    skipped++;
                } else {
                    submitted.add(doc.getId());
                }
            } catch (Exception e) {
                // ★ 单个文件失败不影响其余的。
                //   批量任务最忌讳「一个坏文件让整批中止」——
                //   用户会以为全都没进去，实际上前面成功的那些已经入库了，
                //   状态不一致比直接失败更难处理
                log.error("语料文件提交失败，继续处理下一个 file={}", file.getFileName(), e);
            }
        }

        log.info("语料扫描完成 目录={} 候选={} 提交={} 跳过={}",
                dir.toAbsolutePath(), files.size(), submitted.size(), skipped);
        return KbBatchSubmitResponse.of(files.size(), submitted, skipped);
    }

    // ================================================================
    // ③ 数据库同步
    // ================================================================

    @Override
    public KbBatchSubmitResponse syncFromDatabase() {
        List<Long> submitted = new ArrayList<>();
        int scanned = 0;
        int skipped = 0;

        // ---------- 售后政策 ----------
        List<AfterSalePolicy> policies = policyMapper.selectList(
                Wrappers.<AfterSalePolicy>lambdaQuery().eq(AfterSalePolicy::getStatus, 1));
        for (AfterSalePolicy policy : policies) {
            scanned++;
            try {
                String content = renderer.renderPolicy(policy);
                KbDocument doc = submitText(content, policy.getTitle(),
                        KbDocument.TYPE_AFTER_SALE, null,
                        policy.getEffectiveFrom(), policy.getEffectiveTo(),
                        "policy-" + policy.getPolicyNo());
                if (doc.getStatus() != KbDocument.STATUS_PENDING) {
                    skipped++;
                } else {
                    submitted.add(doc.getId());
                }
            } catch (Exception e) {
                log.error("售后政策同步失败 policyNo={}", policy.getPolicyNo(), e);
            }
        }

        // ---------- 商品 ----------
        List<Product> products = productMapper.selectList(
                Wrappers.<Product>lambdaQuery().eq(Product::getStatus, 1));
        for (Product product : products) {
            scanned++;
            try {
                List<ProductAttribute> attrs = attributeMapper.selectList(
                        Wrappers.<ProductAttribute>lambdaQuery()
                                .eq(ProductAttribute::getProductId, product.getId())
                                .orderByAsc(ProductAttribute::getSortOrder));

                String content = renderer.renderProduct(product, attrs);
                KbDocument doc = submitText(content, product.getName(),
                        KbDocument.TYPE_PRODUCT, product.getId(), null, null,
                        "product-" + product.getProductNo());
                if (doc.getStatus() != KbDocument.STATUS_PENDING) {
                    skipped++;
                } else {
                    submitted.add(doc.getId());
                }
            } catch (Exception e) {
                log.error("商品同步失败 productNo={}", product.getProductNo(), e);
            }
        }

        log.info("数据库同步完成 扫描={} 提交={} 跳过={}", scanned, submitted.size(), skipped);
        return KbBatchSubmitResponse.of(scanned, submitted, skipped);
    }

    // ================================================================
    // 状态查询
    // ================================================================

    @Override
    public KbDocument getStatus(Long documentId) {
        return documentId == null ? null : documentMapper.selectById(documentId);
    }

    // ================================================================
    // 内部
    // ================================================================

    /**
     * 把一段渲染好的文本登记成文档并提交入库。
     *
     * <p>文本会<b>先落盘</b>再走标准流水线 —— 这让数据库同步和文件上传
     * 共用同一条下游链路。理由详见 {@link DocumentStore#saveText}。
     */
    private KbDocument submitText(String content, String title, Integer docType,
                                  Long relatedProductId,
                                  OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                  String baseName) throws IOException {
        DocumentStore.StoredFile stored = documentStore.saveText(content, baseName);

        KbDocument existing = findReusableByHash(stored.fileHash());
        if (existing != null) {
            // ★ 源数据没变就不重复入库。
            //   这是省钱的关键一步：一份商品文档约 5 个切片，
            //   200 个商品就是 1000 次向量化调用。
            //   每次点「同步」都重跑一遍的话，白白烧掉额度
            return existing;
        }

        KbDocument doc = new KbDocument();
        doc.setDocNo(newDocNo());
        doc.setTitle(title);
        doc.setDocType(docType);
        doc.setSourceType(KbDocument.SOURCE_DATABASE);
        doc.setFileName(stored.fileName());
        doc.setFilePath(stored.filePath());
        doc.setFileSize(stored.fileSize());
        doc.setFileHash(stored.fileHash());
        doc.setRelatedProductId(relatedProductId);
        doc.setEffectiveFrom(effectiveFrom);
        doc.setEffectiveTo(effectiveTo);
        doc.setVersion(1);
        doc.setStatus(KbDocument.STATUS_PENDING);
        doc.setChunkCount(0);

        documentMapper.insert(doc);
        submit(doc.getId());
        return doc;
    }

    /**
     * 找一份「内容摘要相同、且已经成功入库」的文档。
     *
     * <p><b>为什么只认 {@code status=3}</b>：摘要相同但状态是「失败」的文档
     * 恰恰是<b>需要重试</b>的。如果把失败的一起当成「已存在」跳过，
     * 用户点多少次「同步」都没用 —— 那份文档永远卡在失败状态，
     * 而且没有任何提示说明「你重试的是同一个坏文件」。
     * 所以失败的不算命中，会重新走一遍流水线。
     */
    private KbDocument findReusableByHash(String fileHash) {
        if (fileHash == null) {
            return null;
        }
        return documentMapper.selectOne(
                Wrappers.<KbDocument>lambdaQuery()
                        .eq(KbDocument::getFileHash, fileHash)
                        .eq(KbDocument::getStatus, KbDocument.STATUS_DONE)
                        .orderByDesc(KbDocument::getId)
                        .last("LIMIT 1"));
    }

    /**
     * 把文档 ID 提交到入库线程池。
     *
     * <p>提交失败（队列满 + 线程满）时把状态直接标成失败，
     * 而不是把异常抛回给 HTTP 调用方。理由：调用方（上传接口）此刻
     * <b>已经无法回滚</b>了（文件已落盘、记录已插入），
     * 抛异常只会让前端看到一个 500，而数据库里那条 status=1 的记录会永远卡着。
     * 标成失败之后，用户能通过状态接口看到「提交失败，请稍后重试」。
     */
    private void submit(Long documentId) {
        try {
            ingestExecutor.execute(() -> ingestWorker.process(documentId));
        } catch (TaskRejectedException e) {
            log.error("入库线程池已满，提交失败 documentId={}", documentId, e);
            KbDocument failed = new KbDocument();
            failed.setId(documentId);
            failed.setStatus(KbDocument.STATUS_FAILED);
            failed.setErrorMsg("入库任务队列已满，请稍后重试");
            failed.setUpdatedAt(OffsetDateTime.now());
            documentMapper.updateById(failed);
        }
    }

    /** 文档编号：{@code KB-20260918-a1b2c3d4}。对外展示用，不暴露自增 ID */
    private static String newDocNo() {
        return "KB-" + OffsetDateTime.now().format(DOC_NO_DATE) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 从文件名推一个初始标题，真正入库成功后会被文档元数据里的标题覆盖 */
    private static String deriveTitle(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "未命名文档";
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.length() > 200 ? base.substring(0, 200) : base;
    }

    private static boolean isSupported(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        // 隐藏文件（.开头）直接跳过：那是 .DS_Store / .gitkeep 之类的东西
        if (name.startsWith(".")) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}
