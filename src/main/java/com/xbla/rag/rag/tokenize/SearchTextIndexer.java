package com.xbla.rag.rag.tokenize;

import com.xbla.rag.mapper.KbChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 给已有切片补写 / 重建 {@code search_text}。
 *
 * <h2>一、为什么需要它</h2>
 *
 * <p>两个场景，同一个动作：
 *
 * <ol>
 *   <li><b>一次性回填</b> —— {@code search_text} 是阶段 4 才加的列，
 *       已有的一千多条切片全是 NULL，而它们会被关键词检索<b>静默排除</b>；</li>
 *   <li><b>换分词器后重建</b> —— 阶段 7 要做「bigram vs 词级分词」的 A/B 对比，
 *       换实现类之后索引必须整体重算。<b>这个需求一定会发生</b>，
 *       所以不能把回填写成一次性的迁移脚本。</li>
 * </ol>
 *
 * <p>关键设计：<b>分词逻辑只有一份</b>，就在 {@link CjkTokenizer} 里。
 * 如果把 bigram 切分写成 SQL 函数塞进迁移文件，Java 和 SQL 各一份实现，
 * 迟早会不一致 —— 而不一致的后果是「索引里存的和检索时切的对不上」，
 * <b>一条都命中不了且不报错</b>。
 *
 * <h2>二、为什么是逐行 UPDATE 而不是批量</h2>
 *
 * <p>更快的做法是攒一批然后 {@code UPDATE ... FROM (VALUES ...)}，或者用
 * PostgreSQL 的 {@code COPY}。这里<b>故意选了最慢但最简单的逐行</b>，理由是：
 *
 * <ul>
 *   <li>每条 UPDATE <b>独立提交</b>，所以中途失败或超时后，
 *       重新调用会从断点继续（keyset 分页天然可续跑），已经做完的不会重做；</li>
 *   <li>批量更新一旦失败，整批回滚，前面几秒白跑；</li>
 *   <li>这个操作<b>一天最多跑几次</b>，不是热路径。
 *       在本项目的数据量下（一千多条）总耗时是秒级，优化它没有意义。</li>
 * </ul>
 *
 * <p>顺带说明：每条 UPDATE 会连带触发 {@code search_vector} 生成列的重算
 * 和 GIN 索引项的更新，所以单条比普通 UPDATE 略慢 —— 这是生成列方案的固有代价，
 * 换来的是「应用永远不用手工维护 tsvector」。
 *
 * <h2>三、不是热路径，所以不加线程池</h2>
 *
 * <p>本类<b>同步执行</b>，由调试接口调用。没有放到 {@code ingestExecutor} 上，
 * 因为：入库是用户上传后立刻排队的后台任务，而重建索引是运维动作，
 * 调用方（curl / 运维脚本）本来就在等结果，同步返回进度反而更有用。
 */
@Component
public class SearchTextIndexer {

    private static final Logger log = LoggerFactory.getLogger(SearchTextIndexer.class);

    /** 每批处理条数。太大则单批耗时长、失败重跑代价高；太小则往返次数多 */
    public static final int DEFAULT_BATCH_SIZE = 200;

    /**
     * 单次调用的处理上限。
     *
     * <p>兜底用：防止有人在千万级的库上误调一次接口把数据库拖住。
     * 真到了那个量级，应该做成带进度上报的后台作业，而不是一个同步接口。
     */
    public static final int DEFAULT_MAX_ROWS = 100_000;

    private final KbChunkMapper chunkMapper;
    private final CjkTokenizer tokenizer;

    public SearchTextIndexer(KbChunkMapper chunkMapper, CjkTokenizer tokenizer) {
        this.chunkMapper = chunkMapper;
        this.tokenizer = tokenizer;
    }

    /**
     * 重建结果。
     *
     * @param processed     本次实际处理的切片数
     * @param batches       批次数
     * @param emptyTokenized 分词后得到<b>零个词元</b>的切片数。
     *                       这些切片的 {@code search_text} 会是空串，
     *                       关键词检索查不到它们 —— 对于纯英文/纯符号的切片
     *                       这是正常的，但如果数值很高就说明分词器有问题
     * @param truncated     是否因为撞到 {@code maxRows} 上限而提前结束
     * @param elapsedMs     耗时
     */
    public record Result(int processed, int batches, int emptyTokenized,
                         boolean truncated, long elapsedMs) {
    }

    /** 只补 {@code search_text} 为空的行 —— 日常回填用这个 */
    public Result reindexMissing() {
        return reindex(true, DEFAULT_BATCH_SIZE, DEFAULT_MAX_ROWS);
    }

    /** 全量重建 —— <b>换了分词器之后用这个</b> */
    public Result reindexAll() {
        return reindex(false, DEFAULT_BATCH_SIZE, DEFAULT_MAX_ROWS);
    }

    /**
     * 执行重建。
     *
     * @param onlyMissing true 只处理空值；false 全量重建
     * @param batchSize   每批条数
     * @param maxRows     本次处理上限，防止误操作拖垮库
     */
    public Result reindex(boolean onlyMissing, int batchSize, int maxRows) {
        long startNanos = System.nanoTime();
        int batch = Math.max(1, batchSize);
        int limit = Math.max(1, maxRows);

        long lastId = 0L;
        int processed = 0;
        int batches = 0;
        int emptyTokenized = 0;

        while (processed < limit) {
            int size = Math.min(batch, limit - processed);
            List<SearchTextSource> rows = chunkMapper.selectForReindex(lastId, onlyMissing, size);
            if (rows.isEmpty()) {
                break;
            }

            for (SearchTextSource row : rows) {
                SearchText text = tokenizer.tokenize(row.content());
                if (text.isEmpty()) {
                    emptyTokenized++;
                }
                chunkMapper.updateSearchText(row.id(), text.tokenText());
                lastId = row.id();
            }

            processed += rows.size();
            batches++;

            // 取回的条数少于请求条数 = 已经到底了
            if (rows.size() < size) {
                break;
            }
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        boolean truncated = processed >= limit;

        if (emptyTokenized > 0) {
            log.warn("重建 search_text：有 {} 条切片分词后为零词元，这些切片关键词检索不到", emptyTokenized);
        }
        log.info("重建 search_text 完成：onlyMissing={} 处理={} 批次={} 零词元={} 截断={} 耗时={}ms",
                onlyMissing, processed, batches, emptyTokenized, truncated, elapsedMs);

        return new Result(processed, batches, emptyTokenized, truncated, elapsedMs);
    }
}
