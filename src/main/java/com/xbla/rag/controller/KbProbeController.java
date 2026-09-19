package com.xbla.rag.controller;

import com.xbla.rag.rag.RetrievalDetailBuilder;
import com.xbla.rag.rag.RetrievalPipeline;
import com.xbla.rag.rag.RetrievalTrace;
import com.xbla.rag.rag.retrieve.RetrievedChunk;
import com.xbla.rag.rag.retrieve.VectorHit;
import com.xbla.rag.rag.retrieve.VectorSearcher;
import com.xbla.rag.rag.tokenize.SearchTextIndexer;
import com.xbla.rag.rag.tokenize.SearchTextStats;
import com.xbla.rag.mapper.KbChunkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索调试探针。
 *
 * <p>⚠️ 标了 {@code @Profile("local")}，<b>只在本地开发时存在</b>。
 * 这个限制不是形式主义：这些接口可以无条件消耗 API 额度
 * （每次检索都要调一次向量化），<b>绝不能暴露到生产</b>。
 *
 * <p>和 {@code ModelProbeController} 的分工：那边验证「模型能不能调通」
 * （阶段 2），这边验证「知识库能不能查出来」（阶段 3）。
 *
 * <h3>接口</h3>
 * <pre>
 *   # 向量检索，看最相关的切片
 *   curl -s -G localhost:8080/api/debug/kb/search \
 *        --data-urlencode "q=退货要几天" --data-urlencode "topK=5"
 *
 *   # ★ 稳定性自检：同一个问题查 N 次，逐字节比对结果
 *   curl -s -G localhost:8080/api/debug/kb/stability \
 *        --data-urlencode "q=退货要几天" --data-urlencode "repeat=3"
 *
 *   # ★ 中文分词索引的覆盖率（阶段 4）。missing 必须是 0
 *   curl -s localhost:8080/api/debug/kb/search-text-stats
 *
 *   # ★ 重建 search_text。force=true 是全量重建（换分词器后用）
 *   curl -s -X POST "localhost:8080/api/debug/kb/reindex?force=false"
 * </pre>
 *
 * <p>⚠️ <b>中文参数请用 {@code scripts/probe_kb.py}，不要直接用 curl</b> ——
 * Windows + Git Bash 下有两层编码陷阱，详见该脚本的说明。
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/kb")
@Profile("local")
public class KbProbeController {

    /**
     * 分值比较的容差。
     *
     * <p>取 1e-3，是实测漂移量（约 3e-4）的 3 倍多 —— 既能容忍正常的浮点抖动，
     * 又不会把「检索结果真的变了」误判成抖动（真的变化会是 1e-2 量级）。
     */
    private static final double SCORE_TOLERANCE = 1e-3;

    private final VectorSearcher vectorSearcher;
    private final SearchTextIndexer searchTextIndexer;
    private final KbChunkMapper chunkMapper;
    private final RetrievalPipeline retrievalPipeline;
    private final RetrievalDetailBuilder detailBuilder;

    public KbProbeController(VectorSearcher vectorSearcher,
                             SearchTextIndexer searchTextIndexer,
                             KbChunkMapper chunkMapper,
                             RetrievalPipeline retrievalPipeline,
                             RetrievalDetailBuilder detailBuilder) {
        this.vectorSearcher = vectorSearcher;
        this.searchTextIndexer = searchTextIndexer;
        this.chunkMapper = chunkMapper;
        this.retrievalPipeline = retrievalPipeline;
        this.detailBuilder = detailBuilder;
    }

    /**
     * 向量检索探针。
     *
     * <p>返回里除了命中列表，还有 {@code queryVectorHead}（查询向量的前几个分量）。
     * 加这个是为了排查「检索结果不对劲」时能快速分辨是<b>向量算错了</b>
     * 还是<b>库里数据不对</b> —— 只看命中列表是分不出来的。
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String question,
                                      @RequestParam(value = "topK", required = false) Integer topK) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);

        String vectorLiteral = vectorSearcher.embedToLiteral(question);
        result.put("queryVectorDimension", countDimensions(vectorLiteral));
        result.put("queryVectorHead", head(vectorLiteral, 5));

        List<VectorHit> hits = vectorSearcher.searchByVector(vectorLiteral,
                topK == null ? VectorSearcher.DEFAULT_TOP_K : topK);
        result.put("hitCount", hits.size());
        result.put("hits", hits.stream().map(KbProbeController::describe).toList());
        return result;
    }

    /**
     * ★ <b>稳定性自检</b>：把同一个问题查 {@code repeat} 次，逐字段比对结果是否完全一致。
     *
     * <p>这条直接对应阶段 3 的验收标准 3「同一个问题检索两次，结果稳定一致」。
     * 做成接口而不是靠人工 curl 两次比对，是因为人工比对只会看「前几条像不像」，
     * 而<b>顺序的细微变化恰恰是最容易被忽略、又最能说明问题的</b>。
     *
     * <p>注意每次循环都<b>重新调用向量化接口</b>（不是复用第一次的向量）——
     * 这样才能同时验证「向量化确定性」和「排序确定性」两个环节。
     */
    @GetMapping("/stability")
    public Map<String, Object> stability(@RequestParam("q") String question,
                                         @RequestParam(value = "repeat", required = false) Integer repeat,
                                         @RequestParam(value = "topK", required = false) Integer topK) {
        int times = (repeat == null || repeat < 2) ? 3 : Math.min(repeat, 10);
        int k = topK == null ? VectorSearcher.DEFAULT_TOP_K : topK;

        List<List<Long>> idRuns = new ArrayList<>();
        List<List<Double>> scoreRuns = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            List<VectorHit> hits = vectorSearcher.search(question, k);
            idRuns.add(hits.stream().map(VectorHit::id).toList());
            scoreRuns.add(hits.stream().map(VectorHit::score).toList());
        }

        // ★ 两个层次分开判定，不能混为一谈
        //
        //   ① ID 序列：必须【逐位完全一致】。这是用户能感知的东西，
        //      也是「结果稳定一致」这条验收标准真正的含义。
        //      它能成立，靠的是 SQL 里 ORDER BY distance, id 的兜底键。
        //
        //   ② 分值：只要求【在容差内】一致，不能要求逐位相同。
        //      因为实测发现向量化接口本身就不是确定的（6 次调用出 2 种结果），
        //      同样的文本每次返回的向量有约 3e-4 的漂移。
        //      这不是 bug，是 GPU 并行计算里浮点归约顺序不同导致的固有性质。
        //
        //   最初这版代码用 scoreRuns.equals() 直接比 double，于是永远报「不稳定」——
        //   而实际上链路是好的。判据写错比没有判据更糟：
        //   它会让人跑去查一个根本不存在的问题。
        boolean idStable = idRuns.stream().allMatch(ids -> ids.equals(idRuns.get(0)));
        double maxDrift = maxDrift(scoreRuns);
        boolean scoreStable = maxDrift <= SCORE_TOLERANCE;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("repeat", times);
        result.put("topK", k);
        result.put("idSequenceStable", idStable);
        result.put("scoreStableWithinTolerance", scoreStable);
        result.put("maxScoreDrift", maxDrift);
        result.put("scoreTolerance", SCORE_TOLERANCE);
        // ★ 判定「稳定」只看 ID 序列。分值是次要指标，单独报告
        result.put("stable", idStable);
        result.put("idRuns", idRuns);
        result.put("scoreRuns", scoreRuns);
        result.put("note", "stable 只看 ID 序列是否逐位一致（这是用户能感知的）。"
                + "分值不会逐位相同 —— 实测向量化接口本身有约 3e-4 的漂移，"
                + "是 GPU 浮点并行归约的固有性质，不是 bug。"
                + "maxScoreDrift 给出实测的漂移量，供判断名次翻转的风险。");
        return result;
    }

    /**
     * 各次检索之间，同一个名次上的分值最大差异。
     *
     * <p>只统计共同存在的名次（ID 序列不同时这个指标没有意义，
     * 但那种情况 {@code idSequenceStable} 已经报出来了）。
     */
    private static double maxDrift(List<List<Double>> scoreRuns) {
        if (scoreRuns.size() < 2) {
            return 0.0;
        }
        List<Double> base = scoreRuns.get(0);
        double max = 0.0;
        for (int run = 1; run < scoreRuns.size(); run++) {
            List<Double> current = scoreRuns.get(run);
            int n = Math.min(base.size(), current.size());
            for (int i = 0; i < n; i++) {
                max = Math.max(max, Math.abs(base.get(i) - current.get(i)));
            }
        }
        return max;
    }

    // ================================================================
    // 输出整形
    // ================================================================

    private static Map<String, Object> describe(VectorHit hit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunkId", hit.id());
        m.put("documentId", hit.documentId());
        m.put("chunkIndex", hit.chunkIndex());
        m.put("score", round(hit.score()));
        m.put("headingPath", hit.headingPath());
        m.put("preview", preview(hit.content()));
        return m;
    }

    /** 保留 4 位小数。相似度差异往往在小数点后第三四位，全打印出来反而看不清 */
    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String preview(String content) {
        if (content == null) {
            return null;
        }
        String flat = content.replace('\n', ' ');
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }

    /** 数一个向量文本里有几个分量。返回 -1 表示格式不对 */
    private static int countDimensions(String literal) {
        if (literal == null || literal.length() < 2) {
            return -1;
        }
        int commas = 0;
        for (int i = 0; i < literal.length(); i++) {
            if (literal.charAt(i) == ',') {
                commas++;
            }
        }
        return commas + 1;
    }

    private static String head(String literal, int n) {
        int end = literal.indexOf(',');
        for (int i = 1; i < n && end >= 0; i++) {
            end = literal.indexOf(',', end + 1);
        }
        return end < 0 ? literal : literal.substring(0, end) + ",…]";
    }

    // ================================================================
    // ★ 完整检索链路（阶段 4 · 验收标准 1）
    // ================================================================

    /**
     * <b>完整召回链路的中间输出</b> —— 路线图阶段 4 验收标准第 1 条的落点。
     *
     * <p>返回的 {@code detail} 就是 {@code qa_log.retrieval_detail} 的内容
     * （两处调用的是同一个 {@link RetrievalDetailBuilder}），
     * 所以它不只是「调试信息」，而是<b>线上真正会落库的那份证据</b>。
     *
     * <p>五段依次是：
     * <ol>
     *   <li>{@code vector_hits} —— 向量路原始召回（余弦相似度）</li>
     *   <li>{@code keyword_hits} —— 关键词路原始召回（{@code ts_rank}）</li>
     *   <li>{@code fused} —— RRF 融合后（只看名次，分数与上两段不可比）</li>
     *   <li>{@code reranked} —— 重排后（bge-reranker 的相关度）</li>
     *   <li>{@code final_top_k} —— 最终送进 prompt 的切片 ID</li>
     * </ol>
     *
     * <p>另外返回 {@code finalChunks}（带正文），因为「最终喂给大模型的是哪几段话」
     * 是排查「答案为什么不对」时第一眼要看的东西。
     *
     * <p>⚠️ 这个接口会调向量化接口和新版重排接口，<b>每次调用都花钱</b>，
     * 所以整个类标了 {@code @Profile("local")}。
     */
    @GetMapping("/retrieve")
    public Map<String, Object> retrieve(@RequestParam("q") String question) {
        RetrievalTrace trace = new RetrievalTrace("probe-" + Long.toHexString(System.nanoTime()));

        long startNanos = System.nanoTime();
        List<RetrievedChunk> chunks = retrievalPipeline.retrieve(question, trace);
        long totalMs = (System.nanoTime() - startNanos) / 1_000_000;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("detail", detailBuilder.build(trace));
        result.put("latency", Map.of(
                "retrievalMs", trace.retrievalLatencyMs(),
                "rerankMs", trace.rerankLatencyMs(),
                "totalMs", totalMs));
        result.put("finalChunks", chunks.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", c.id());
            m.put("documentId", c.documentId());
            m.put("chunkIndex", c.chunkIndex());
            m.put("score", c.score());
            m.put("headingPath", c.headingPath());
            m.put("preview", c.content() == null ? null : c.content().replace('\n', ' '));
            return m;
        }).toList());
        return result;
    }

    // ================================================================
    // search_text 的覆盖率与重建（阶段 4 · 4.2）
    // ================================================================

    /**
     * {@code search_text} 覆盖率探针。
     *
     * <p><b>为什么这个接口比 {@code /reindex} 更重要</b>
     *
     * <p>关键词检索的条件是 {@code search_vector @@ query}，
     * 而 SQL 里 {@code NULL} 参与布尔运算的结果是 NULL 而不是 false，
     * 于是 {@code search_text} 为空的切片会被 {@code WHERE} <b>静默排除</b>。
     *
     * <p>也就是说：如果有人改了入库代码却忘了写 {@code search_text}，
     * 症状是「<b>新文档检索不到，老文档一切正常</b>」，而且没有任何报错。
     * 这个接口把那个静默的故障变成一行数字。
     *
     * <p>{@code healthy=true} 表示一条都没漏。
     */
    @GetMapping("/search-text-stats")
    public Map<String, Object> searchTextStats() {
        SearchTextStats stats = chunkMapper.searchTextStats();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", stats.total());
        result.put("missing", stats.missing());
        result.put("coverage", stats.total() == 0
                ? "—（库是空的）"
                : String.format("%.2f%%", 100.0 * (stats.total() - stats.missing()) / stats.total()));
        result.put("minTokens", stats.minTokens());
        result.put("avgTokens", stats.avgTokens());
        result.put("maxTokens", stats.maxTokens());
        result.put("healthy", stats.healthy());
        result.put("note", stats.healthy()
                ? "所有切片都有分词索引"
                : "★ 有切片的 search_text 为空，它们会被关键词检索静默排除。"
                        + "调 POST /api/debug/kb/reindex 回填");
        return result;
    }

    /**
     * 重建 {@code search_text}。
     *
     * <p>两个用途：<b>一次性回填</b>（列是新加的，老数据全是 NULL），
     * 以及<b>换了分词器之后的全量重建</b>（阶段 7 做分词方案 A/B 时要用）。
     *
     * <p>逐行 UPDATE、逐条提交，所以中途失败再调一次会从断点继续，
     * 已经做完的不会重做。
     *
     * @param force true = 全量重建（换分词器后用）；
     *              false = 只补空值（默认，日常回填用）
     */
    @PostMapping("/reindex")
    public Map<String, Object> reindex(
            @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {
        log.info("★ 开始重建 search_text：force={}（{}）", force,
                force ? "全量重建，换分词器后用" : "只补空值");

        SearchTextIndexer.Result r = force ? searchTextIndexer.reindexAll()
                : searchTextIndexer.reindexMissing();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", force ? "全量重建" : "只补空值");
        result.put("processed", r.processed());
        result.put("batches", r.batches());
        result.put("emptyTokenized", r.emptyTokenized());
        result.put("truncated", r.truncated());
        result.put("elapsedMs", r.elapsedMs());
        result.put("statsAfter", searchTextStats());
        return result;
    }
}
