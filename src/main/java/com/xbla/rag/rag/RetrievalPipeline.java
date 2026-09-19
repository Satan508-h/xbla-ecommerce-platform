package com.xbla.rag.rag;

import com.xbla.rag.config.RetrievalProperties;
import com.xbla.rag.rag.fuse.RrfFuser;
import com.xbla.rag.rag.query.QueryPlan;
import com.xbla.rag.rag.query.QueryPlanner;
import com.xbla.rag.rag.rerank.ChunkReranker;
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
 *     ├─② 并行双路召回 ──┬─ VectorRetriever   （向量化 HTTP + 数据库）
 *     │                  └─ KeywordRetriever  （数据库）
 *     │
 *     ├─③ RRF 融合 RrfFuser（按名次合并，丢弃分数）
 *     │
 *     ├─④ 精排 ChunkReranker（bge-reranker-v2-m3，失败回落融合顺序）
 *     │
 *     └─⑤ 截断到 finalTopK → 送进 prompt
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

    public RetrievalPipeline(List<Retriever> retrievers,
                             QueryPlanner queryPlanner,
                             RrfFuser rrfFuser,
                             ChunkReranker chunkReranker,
                             RetrievalProperties properties,
                             @Qualifier("retrieveExecutor") ThreadPoolTaskExecutor retrieveExecutor) {
        this.retrievers = retrievers;
        this.queryPlanner = queryPlanner;
        this.rrfFuser = rrfFuser;
        this.chunkReranker = chunkReranker;
        this.properties = properties;
        this.retrieveExecutor = retrieveExecutor;
    }

    /**
     * 执行一次完整检索。
     *
     * @param question 用户原始问题
     * @param trace    轨迹收集器，<b>由调用方创建并传入</b>（同 {@code ModelCallTrace} 的模式）
     * @return 最终送进 prompt 的切片，按相关度降序。<b>可能为空，但不会为 null</b>
     */
    public List<RetrievedChunk> retrieve(String question, RetrievalTrace trace) {
        long startNanos = System.nanoTime();
        try {
            return doRetrieve(question, trace);
        } finally {
            trace.retrievalLatencyMs(elapsedMs(startNanos));
        }
    }

    private List<RetrievedChunk> doRetrieve(String question, RetrievalTrace trace) {
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

        // ── ② 并行双路召回 ──────────────────────────────────────
        RetrievalProperties.Retrieve cfg = properties.getRetrieve();
        long deadlineNanos = System.nanoTime() + LEG_WAIT_TIMEOUT_MS * 1_000_000L;

        List<CompletableFuture<List<RetrievedChunk>>> futures = retrievers.stream()
                .map(r -> submitLeg(r, query, topKOf(r, cfg), trace))
                .toList();

        // 按 retrievers 的声明顺序等待。两路是并行跑的，
        // 所以总等待时间取的是两者的最大值，不是相加
        for (int i = 0; i < retrievers.size(); i++) {
            Retriever retriever = retrievers.get(i);
            List<RetrievedChunk> hits = awaitLeg(futures.get(i), deadlineNanos, retriever.name(), trace);
            recordHits(retriever.name(), hits, trace);
        }

        // ── ③ RRF 融合 ──────────────────────────────────────────
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

        // ── ④ 精排 ──────────────────────────────────────────────
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

        // ── ⑤ 截断 ──────────────────────────────────────────────
        List<RetrievedChunk> finalChunks = List.copyOf(
                reranked.subList(0, Math.min(reranked.size(), finalTopK)));
        trace.finalChunks(finalChunks);

        log.debug("检索完成 {}", trace.summary());
        return finalChunks;
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
                                                              int topK, RetrievalTrace trace) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return retriever.retrieve(query, topK);
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
