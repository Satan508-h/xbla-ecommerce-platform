package com.xbla.rag.rag;

import com.xbla.rag.config.RetrievalProperties;
import com.xbla.rag.mapper.KbChunkMapper;
import com.xbla.rag.rag.fuse.RrfFuser;
import com.xbla.rag.rag.query.QueryPlan;
import com.xbla.rag.rag.query.QueryPlanner;
import com.xbla.rag.rag.rerank.ChunkReranker;
import com.xbla.rag.rag.retrieve.RetrievalOptions;
import com.xbla.rag.rag.retrieve.RetrievedChunk;
import com.xbla.rag.rag.retrieve.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ★ 检索链路的编排 —— 本阶段的核心。
 *
 * <pre>
 *   用户问题
 *     │
 *     ├─① 查询计划 QueryPlanner（重写 / 子问题拆分，默认关闭）
 *     │
 *     ├─② 意图定向范围（阶段 5.4）：范围声明 → 值不值得过滤 → 下推 doc_type
 *     │
 *     ├─③ 并行双路召回 ──┬─ VectorRetriever   （向量化 HTTP + 数据库）
 *     │                  └─ KeywordRetriever  （数据库）
 *     │        └─ 过滤后一无所获 → 回落到全池重查【一次】
 *     │
 *     ├─④ RRF 融合 RrfFuser（按名次合并，丢弃分数）
 *     │
 *     ├─⑤ 精排 ChunkReranker（bge-reranker-v2-m3，失败回落融合顺序）
 *     │
 *     └─⑥ 截断到 finalTopK → 送进 prompt
 * </pre>
 *
 * <h2>一、★ 失败语义：单路失败不整体失败</h2>
 *
 * <p>两路是<b>互补</b>的（向量擅长语义泛化、关键词擅长字面精确），
 * 所以丢一路只是候选少一份，仍然可用；而整次检索失败会让问答退化成裸聊。
 * 所以：
 *
 * <table border="1">
 *   <caption>失败处理</caption>
 *   <tr><th>情况</th><th>行为</th></tr>
 *   <tr><td>单路失败</td><td><b>继续</b>，用另一路的结果。记 {@code xxx_failed} 事件</td></tr>
 *   <tr><td>两路都失败</td><td><b>继续</b>，返回空检索结果 + 记事件。问答退化成裸聊</td></tr>
 *   <tr><td>重排失败</td><td><b>回落</b>到 RRF 顺序</td></tr>
 *   <tr><td>线程池满</td><td>该路记失败，另一路照常</td></tr>
 * </table>
 *
 * <p><b>任何情况下本类都不向上抛异常。</b>调用方拿到的永远是一个可用的
 * （哪怕是空的）结果列表。理由见 {@link RetrievalTrace#bothLegsFailed()}。
 *
 * <h2>二、★ 为什么用 {@code get(timeout)} 而不是 {@code join()}</h2>
 *
 * <p>{@code join()} 抛的是 {@code CompletionException}，它会把「部分失败」
 * 包装成「整体失败」—— 正好是本类要避免的。
 *
 * <p>而且 {@code join()} 没有超时：如果某一路的向量化调用卡住了
 * （Java 的 {@code HttpClient} 没有 per-read 超时），
 * <b>整个请求线程会一直挂着</b>，直到 Tomcat 的异步超时兜底。
 * 用 {@code get(remaining, MILLISECONDS)} 才能给出一个有界的等待。
 *
 * <h2>三、★ 两个隐蔽的失败点</h2>
 *
 * <ol>
 *   <li><b>{@code supplyAsync} 会在提交时同步抛异常。</b>
 *       线程池用 {@code AbortPolicy}，队列满时 {@code RejectedExecutionException}
 *       是在<b>调用 {@code supplyAsync} 的那一行</b>抛出来的，不在 lambda 内部 ——
 *       因为任务根本没被接受。<b>更麻烦的是：如果第一个 future 已经提交成功、
 *       第二个被拒，那第一个就成了没人等的孤儿任务。</b>
 *       所以提交动作本身必须包在 try/catch 里。</li>
 *   <li><b>{@code orTimeout} 不会取消任务。</b>它只是让 future 以超时结束，
 *       底层那个阻塞在 HTTP 读取上的线程<b>仍然在跑、仍然占着线程</b>。
 *       所以真正的超时防线是 {@code EmbeddingClient} 自己的
 *       {@code request-timeout: 15s}，本类的等待超时只是最后一道闸。</li>
 * </ol>
 *
 * <h2>四、★ 意图定向范围：为什么是「声明」而不是「命令」（阶段 5.4）</h2>
 *
 * <p>调用方（{@code ChatServiceImpl}）只把意图树里那个叶子声明的
 * {@code doc_types} 传进来，<b>要不要真的下推这个条件由本类决定</b>。
 *
 * <p>之所以要留这一层，是因为「过滤」这件事在实测里<b>不是单调的</b>：
 *
 * <ul>
 *   <li><b>窄到池子比 top-k 还小时，过滤是有害的</b> ——
 *       取出来的恒等于全池，「按相关度截断」这个动作消失了。
 *       实测意图树的 {@code USAGE_GUIDE} 声明 {@code [5]}，全库只有 5 条。</li>
 *   <li><b>宽到几乎不筛掉任何东西时，过滤是白费的</b> ——
 *       实测 {@code [1]}/{@code [1,3]}/{@code [1,5]} 的收窄倍数都是 1.0~1.1×。</li>
 * </ul>
 *
 * <p>而<b>该不该过滤这件事只有本类知道</b>：它需要
 * {@code kb_chunk} 里的实际条数（{@code KbChunkMapper.countByDocTypes}）
 * 和 {@code vector-top-k} 配置，这两样调用方都没有。
 *
 * <p>还有一条更硬的理由：<b>调试探针必须和线上走同一套规则</b>。
 * 如果让调用方各自判断，{@code /api/debug/kb/retrieve} 看到的
 * 就不是线上真正跑的东西了 —— 那正是 {@code RetrievalDetailBuilder}
 * 存在的全部理由。
 *
 * <p><b>安全边界</b>：无论过滤怎么决策，本类都<b>不减少</b>调用方拿到的召回量 ——
 * 过滤只会让候选更集中。实测这一条有保证：20 道评测题里每一题的
 * gold 切片 {@code doc_type} 都 ⊆ 它所属意图声明的集合
 * （{@code IntentTreeConsistencyTest}，20/20），
 * 所以过滤不可能把正确答案挡在外面。
 */
@Component
public class RetrievalPipeline {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPipeline.class);

    /**
     * 等待单路召回的上限。
     *
     * <p>取 20 秒，比向量化接口自己的 {@code request-timeout: 15s} 宽 5 秒 ——
     * 正常情况下永远轮不到它生效（客户端超时会先抛）。
     * 它的意义是<b>兜底</b>：万一某个阻塞点没有超时保护，
     * 也不至于让 Tomcat 的请求线程无限期挂着。
     */
    private static final long LEG_WAIT_TIMEOUT_MS = 20_000L;

    private final List<Retriever> retrievers;
    private final QueryPlanner queryPlanner;
    private final RrfFuser rrfFuser;
    private final ChunkReranker chunkReranker;
    private final RetrievalProperties properties;
    private final ThreadPoolTaskExecutor retrieveExecutor;
    private final KbChunkMapper chunkMapper;

    public RetrievalPipeline(List<Retriever> retrievers,
                             QueryPlanner queryPlanner,
                             RrfFuser rrfFuser,
                             ChunkReranker chunkReranker,
                             RetrievalProperties properties,
                             @Qualifier("retrieveExecutor") ThreadPoolTaskExecutor retrieveExecutor,
                             KbChunkMapper chunkMapper) {
        this.retrievers = retrievers;
        this.queryPlanner = queryPlanner;
        this.rrfFuser = rrfFuser;
        this.chunkReranker = chunkReranker;
        this.properties = properties;
        this.retrieveExecutor = retrieveExecutor;
        this.chunkMapper = chunkMapper;
    }

    /**
     * 执行一次完整检索，<b>不做 {@code doc_type} 范围限制</b>。
     *
     * <p>等价于 {@code retrieve(question, RetrievalOptions.unfiltered(topK), trace)} ——
     * 保留这个重载是为了让「不关心范围」的调用方（调试探针、阶段 4 的基线脚本）
     * 不用自己编一个 topK 出来。
     *
     * @param question 用户原始问题
     * @param trace    轨迹收集器，<b>由调用方创建并传入</b>（同 {@code ModelCallTrace} 的模式）
     * @return 最终送进 prompt 的切片，按相关度降序。<b>可能为空，但不会为 null</b>
     */
    public List<RetrievedChunk> retrieve(String question, RetrievalTrace trace) {
        RetrievalProperties.Retrieve cfg = properties.getRetrieve();
        // 这里给的 topK 会在 doRetrieve 里被按路覆盖（向量路和关键词路各有各的），
        // 传它的意义只是「别是 0」
        return retrieve(question, RetrievalOptions.unfiltered(cfg.getVectorTopK()), trace);
    }

    /**
     * 执行一次完整检索，可限定 {@code doc_type} 范围（阶段 5.4）。
     *
     * <p>★ <b>{@code options} 是「声明」而不是「命令」。</b>
     * 调用方说的是「这个意图的答案可能在 {2,4} 里」，
     * 至于要不要真的下推这个条件，由 {@link #resolveScope} 决定 ——
     * 它可能因为池子太小而放弃过滤，也可能在过滤后一无所获时回落到全池。
     *
     * <p>为什么这个判断放在这里而不是调用方：<b>调试探针和线上必须走同一套规则</b>。
     * 调用方各自判断的话，{@code /api/debug/kb/retrieve} 看到的就不是线上跑的东西了。
     * （同一个理由见 {@code RetrievalDetailBuilder} 的类注释。）
     *
     * @param question 用户原始问题
     * @param options  条数 + 允许的 {@code doc_type} 集合
     * @param trace    轨迹收集器
     * @return 最终送进 prompt 的切片。<b>可能为空，但不会为 null</b>
     */
    public List<RetrievedChunk> retrieve(String question, RetrievalOptions options, RetrievalTrace trace) {
        long startNanos = System.nanoTime();
        try {
            return doRetrieve(question, options, trace);
        } finally {
            trace.retrievalLatencyMs(elapsedMs(startNanos));
        }
    }

    private List<RetrievedChunk> doRetrieve(String question, RetrievalOptions options, RetrievalTrace trace) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        // ── ① 查询计划 ──────────────────────────────────────────
        QueryPlan plan = queryPlanner.plan(question);
        // ★ 关闭重写时这里是 null（不是原问题）—— 理由见 QueryPlan 的注释：
        //   否则阶段 7 分不清「没开重写」和「开了但模型没改动」
        trace.rewrittenQuestion(plan.rewrittenQuestion());
        trace.subQuestions(plan.subQuestions());
        if (plan.note() != null) {
            // 改写的失败/异常情况。★ 只记进 trace，不影响问答主流程
            trace.event(plan.note());
        }
        // 下游一律用加工后的问题；qa_log.question 永远用用户原话
        String query = plan.effectiveQuery();

        // ── ② 意图定向范围（阶段 5.4）────────────────────────────
        RetrievalProperties.Retrieve cfg = properties.getRetrieve();
        RetrievalOptions effective = resolveScope(options, cfg, trace);

        // ── ③ 并行双路召回 ──────────────────────────────────────
        runLegs(query, effective, cfg, trace);

        // 过滤之后两路都没召回 → 回落到全池重查一次。
        // ★ 这一步会【覆盖】trace 里两路的结果，而不是追加 ——
        //   因为「最终实际发生的检索」就是全池那一次，
        //   trace 的每一格都必须描述同一件事，否则这个 JSON 不自洽。
        if (effective.filtered()
                && trace.vectorHits().isEmpty()
                && trace.keywordHits().isEmpty()) {
            trace.event("doc_type_filter_fallback: 过滤后两路都没有召回，改用全池重查");
            effective = effective.withoutFilter();
            trace.filter(new RetrievalTrace.FilterScope(options.docTypes(), false,
                    trace.filter().poolSize(), RetrievalTrace.FilterScope.Reason.EMPTY_RESULT));
            runLegs(query, effective, cfg, trace);
        }

        // ── ④ RRF 融合 ──────────────────────────────────────────
        RetrievalProperties.Fuse fuseCfg = properties.getFuse();
        List<RetrievedChunk> fused = rrfFuser.fuse(List.of(
                new RrfFuser.Leg("vector", fuseCfg.getVectorWeight(), trace.vectorHits()),
                new RrfFuser.Leg("keyword", fuseCfg.getKeywordWeight(), trace.keywordHits())
        ), fuseCfg.getK());
        trace.fused(fused);

        if (fused.isEmpty()) {
            trace.event("no_candidates: 两路都没有召回任何切片");
            trace.reranked(List.of());
            trace.finalChunks(List.of());
            return List.of();
        }

        // ── ⑤ 精排 ──────────────────────────────────────────────
        RetrievalProperties.Rerank rerankCfg = properties.getRerank();
        int finalTopK = Math.max(1, cfg.getFinalTopK());

        List<RetrievedChunk> reranked;
        if (!rerankCfg.isEnabled()) {
            trace.event("rerank_disabled: 配置关闭，直接用融合顺序");
            reranked = List.copyOf(fused.subList(0, Math.min(fused.size(), finalTopK)));
        } else {
            long rerankStart = System.nanoTime();
            int topN = rerankCfg.getTopN() != null ? rerankCfg.getTopN() : finalTopK;
            ChunkReranker.Outcome outcome = chunkReranker.rerank(
                    query, fused, rerankCfg.getMaxCandidates(), topN);
            trace.rerankLatencyMs(elapsedMs(rerankStart));

            if (!outcome.applied() && outcome.failureReason() != null) {
                trace.event("rerank_failed: " + outcome.failureReason());
            }
            reranked = outcome.chunks();
        }
        trace.reranked(reranked);

        // ── ⑥ 截断 ──────────────────────────────────────────────
        List<RetrievedChunk> finalChunks = List.copyOf(
                reranked.subList(0, Math.min(reranked.size(), finalTopK)));
        trace.finalChunks(finalChunks);

        log.debug("检索完成 {}", trace.summary());
        return finalChunks;
    }

    // ================================================================
    // 意图定向范围（阶段 5.4）
    // ================================================================

    /**
     * ★ <b>过滤之后候选池还剩多少条，才值得真的下推 {@code doc_type} 条件。</b>
     *
     * <p>判据是「池子必须装得下这一路要取的条数」——
     * 也就是 {@code poolSize >= vector-top-k}。小于这个数有两个独立的坏处：
     *
     * <ol>
     *   <li><b>收益为零。</b>池子只有 5 条而 topK=20 时，取出来的恒等于全池，
     *       过滤没有筛掉任何东西 —— 它只是把同样的结果换了个说法。</li>
     *   <li><b>而且有害。</b>「取相似度最高的 K 条」这个截断动作消失了：
     *       相关度再低的切片也一律进 prompt。实测意图树里
     *       {@code USAGE_GUIDE} 声明 {@code doc_types: [5]}，而全库
     *       {@code doc_type=5} 只有 <b>5 条</b> —— 任何「怎么用」的问题
     *       都会把 5 条说明书切片全部塞进上下文，哪怕用户问的是开机键在哪。</li>
     * </ol>
     *
     * <p><b>为什么用「池子绝对大小」而不是「收窄倍数」</b>：
     * 实测收窄倍数从 1.1× 到 328× 都有，而倍数越大<b>越</b>危险 ——
     * 判据的方向和风险的方向正好相反。绝对大小没有这个毛病：
     * {@code doc_type=[1]}（1.1×，池子 1555）会被这条规则<b>放行</b>
     * （它无害，只是没用），而 {@code [5]}（328×，池子 5）会被<b>拦下</b>。
     *
     * <p>用 {@code vector-top-k} 而不是 {@code keyword-top-k} 做门槛：
     * 两路默认都是 20，而向量路是「必须先粗召回一批再让重排精排」的那一路，
     * 对池子大小的要求更高。取更严的那个。
     */
    private static boolean worthFiltering(int poolSize, RetrievalProperties.Retrieve cfg) {
        return poolSize >= cfg.getVectorTopK();
    }

    /**
     * 把调用方的「范围声明」解析成「这次实际要下推的条件」，并把决策过程记进 trace。
     *
     * <p>五种结局见 {@link RetrievalTrace.FilterScope} 的表。
     * 本方法只处理前四种，最后一种（{@code empty_result}）要到真的召回之后才知道。
     *
     * <h3>★ 为什么总开关放在这里，而不是放在 {@code ChatServiceImpl} 里</h3>
     *
     * <p>{@code ChatServiceImpl.retrievalOptions()} 的职责是「意图 → 哪几种文档」，
     * 它<b>只声明、不决定要不要真的过滤</b>（那句话就写在那里的注释里）。
     * 「要不要下推」这个决定一直是本方法的 —— 池子大小和 {@code vector-top-k}
     * 都在这一层。总开关决定的是同一件事，所以它属于同一层。
     *
     * <p>放在这一层还买到两样东西：
     * <ol>
     *   <li><b>零签名变更。</b>{@code RetrievalOptions} 是 record，
     *       给它加一个「被配置关掉了」的分量会动到 20+ 处调用点；
     *       而放在这里，调用方（聊天路径、{@code probe_kb}）一个字都不用改</li>
     *   <li><b>一个决策点。</b>「谁声明了」「要不要下推」两件事仍然只有一处交汇，
     *       不会出现「聊天路径认为关着、探针认为开着」这种两套事实</li>
     * </ol>
     *
     * <p>⚠️ 代价是 {@code probe_kb.py --docTypes} 这个调试手法在关掉时也失效。
     * 这是刻意的 —— 那个探针的价值就在于「和聊天路径跑同一条检索链路」，
     * 让它豁免就等于让它在关掉时<b>不再复现聊天路径的行为</b>。
     * 而它失效时不会报错，只会返回全池结果，所以必须靠
     * {@link RetrievalTrace.FilterScope.Reason#DISABLED_BY_CONFIG} 把这件事说明白。
     *
     * <p>⚠️ 计数查询在<b>提交两路召回之前</b>发出，它在请求线程上串行执行。
     * 一次 {@code count(*)} 走 {@code idx_kb_chunk_doc_type} 是亚毫秒级，
     * 换来的是一条能解释清楚的规则 —— 这个交换是划算的。
     * 真要省掉它，可以缓存「每个 doc_types 集合的池子大小」，
     * 但缓存失效要挂在入库流程上，那属于阶段 7 的优化。
     */
    private RetrievalOptions resolveScope(RetrievalOptions requested,
                                          RetrievalProperties.Retrieve cfg,
                                          RetrievalTrace trace) {
        // ── ⓪ 总开关（阶段 7 的 A/B 轴之一）──────────────────────
        // 放在最前面：关掉时下面几条结局【一条都够不着】。
        // docTypes 如实记录调用方的声明 —— 它确实声明了，只是没被用上。
        // ★ 不连池子计数一起跳过：那是一次亚毫秒级的 count(*)，而
        //   「关掉时池子多大」在报告里正好是有用的背景数字。
        if (!cfg.getByIntent().isEnabled()) {
            trace.filter(new RetrievalTrace.FilterScope(requested.docTypes(), false,
                    null, RetrievalTrace.FilterScope.Reason.DISABLED_BY_CONFIG));
            log.debug("范围过滤已按配置关闭：by-intent.enabled=false，docTypes={}（如实记录但不生效）",
                    requested.docTypes());
            return requested.withoutFilter();
        }

        if (!requested.filtered()) {
            // 没声明范围。trace 保持初始的 no_declaration，什么都不用做
            return requested;
        }

        int poolSize = chunkMapper.countByDocTypes(requested.docTypesLiteral());

        if (!worthFiltering(poolSize, cfg)) {
            trace.filter(new RetrievalTrace.FilterScope(requested.docTypes(), false,
                    poolSize, RetrievalTrace.FilterScope.Reason.SMALL_POOL));
            log.debug("范围过滤跳过：池子 {} 条 < vector-top-k {}，docTypes={}",
                    poolSize, cfg.getVectorTopK(), requested.docTypes());
            return requested.withoutFilter();
        }

        trace.filter(new RetrievalTrace.FilterScope(requested.docTypes(), true,
                poolSize, RetrievalTrace.FilterScope.Reason.OK));
        return requested;
    }

    // ================================================================
    // 双路召回
    // ================================================================

    /**
     * 并行跑两路并把结果写进 trace。
     *
     * <p>抽成方法是因为它<b>会被调用两次</b>：正常一次，
     * 过滤后一无所获时回落到全池再一次（见 {@code doRetrieve}）。
     * 第二次调用会覆盖 trace 里两路的结果 —— 那是刻意的，
     * 理由写在那次调用的注释里。
     */
    private void runLegs(String query, RetrievalOptions options,
                         RetrievalProperties.Retrieve cfg, RetrievalTrace trace) {
        long deadlineNanos = System.nanoTime() + LEG_WAIT_TIMEOUT_MS * 1_000_000L;

        List<CompletableFuture<List<RetrievedChunk>>> futures = retrievers.stream()
                .map(r -> submitLeg(r, query, new RetrievalOptions(topKOf(r, cfg), options.docTypes()), trace))
                .toList();

        // 按 retrievers 的声明顺序等待。两路是并行跑的，
        // 所以总等待时间取的是两者的最大值，不是相加
        for (int i = 0; i < retrievers.size(); i++) {
            Retriever retriever = retrievers.get(i);
            List<RetrievedChunk> hits = awaitLeg(futures.get(i), deadlineNanos, retriever.name(), trace);
            recordHits(retriever.name(), hits, trace);
        }
    }

    /** 按路名取该路的 topK 配置 */
    private static int topKOf(Retriever retriever, RetrievalProperties.Retrieve cfg) {
        return switch (retriever.name()) {
            case "keyword" -> cfg.getKeywordTopK();
            default -> cfg.getVectorTopK();
        };
    }

    private void recordHits(String legName, List<RetrievedChunk> hits, RetrievalTrace trace) {
        if ("keyword".equals(legName)) {
            trace.keywordHits(hits);
        } else {
            trace.vectorHits(hits);
        }
    }

    /**
     * 提交一路召回。
     *
     * <p>★ 捕获 {@link RejectedExecutionException}：线程池用 AbortPolicy，
     * 队列满时这个异常是在 {@code supplyAsync} <b>这一行同步抛出</b>的，
     * 不在任务内部（任务压根没被接受）。
     * 不接住的话会直接冒到调用方，而且如果是第二个提交失败，
     * 第一个 future 就成了没人等的孤儿任务。
     */
    private CompletableFuture<List<RetrievedChunk>> submitLeg(Retriever retriever, String query,
                                                              RetrievalOptions options, RetrievalTrace trace) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return retriever.retrieve(query, options);
                } catch (Exception e) {
                    // ★ 在这里吞掉异常，让 future 永远不「异常完成」。
                    //   这是下面能安全使用较简化的等待逻辑的前提：
                    //   失败信息已经记进 trace，返回值表达「这一路没有结果」
                    trace.legFailed(retriever.name(), describe(e));
                    return List.<RetrievedChunk>of();
                }
            }, retrieveExecutor);
        } catch (RejectedExecutionException e) {
            trace.legFailed(retriever.name(), "线程池已满，任务被拒绝");
            return CompletableFuture.completedFuture(List.of());
        }
    }

    /**
     * 等待一路的结果。
     *
     * <p>用 {@code get(remaining)} 而不是 {@code join()}：后者无超时，
     * 且抛的是 {@code CompletionException}，会把部分失败包装成整体失败。
     * 虽然 {@link #submitLeg} 已经保证 future 不会异常完成，
     * 这里仍然把异常路径写全 —— 依赖一个「上游不会抛」的隐式约定是脆弱的。
     */
    private List<RetrievedChunk> awaitLeg(CompletableFuture<List<RetrievedChunk>> future,
                                          long deadlineNanos, String legName, RetrievalTrace trace) {
        long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (remainingMs <= 0) {
            trace.legFailed(legName, "等待超时（前一路已耗尽预算）");
            return List.of();
        }
        try {
            return future.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 注意：超时【不会】取消底层任务，那个线程还在跑。
            // 真正的超时防线是 EmbeddingClient 自己的 request-timeout
            trace.legFailed(legName, "等待超时 " + LEG_WAIT_TIMEOUT_MS + "ms");
            return List.of();
        } catch (ExecutionException e) {
            trace.legFailed(legName, describe(e.getCause() != null ? e.getCause() : e));
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();     // ★ 恢复中断标志，不要吞掉
            trace.legFailed(legName, "被中断");
            return List.of();
        }
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private static int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000);
    }
}
