package com.xbla.rag.dto;

/**
 * 五段延迟的 p50 / p95 / <b>n</b> —— 在线指标用的那个（阶段 9.6b）。
 *
 * <h3>★★★ 为什么每一段都带 {@code n}，而且它不是凑数的</h3>
 *
 * <p>这五列各自<b>「没有发生」的比例完全不同</b>（2026-09-24 实测，{@code status=1} 的 3641 行）：
 *
 * <pre>
 *   queue_ms              3637 行是 null   ← 99.9%，限流没开就没有排队
 *   retrieval_latency_ms   272 行是 null   ← 工具轮 / 澄清轮本来就不检索
 *   rerank_latency_ms      697 行是 null   ← 没重排（关掉了，或候选为空）
 *   llm_latency_ms           0 行
 *   total_latency_ms         0 行
 * </pre>
 *
 * <p>★ 而 {@code percentile_cont} <b>会跳过 NULL</b>。所以不带 {@code n} 的话，
 * 一个「排队段 p50 = 12ms」看起来是「排队很快」，其实可能只是
 * <b>3650 行里只有 4 行排过队</b> —— 而那 4 行没有任何代表性。
 *
 * <p>★★ 这和本项目那条贯穿全库的纪律是同一条：
 * <b>「读不到」和「没有做过」不许渲染成一个样子</b>。
 * 这里是它的第三个变体：<b>「没有发生」不许渲染成「发生了而且很快」</b>。
 *
 * <h3>★★ 「未归类」不在这里，而且不能在这里</h3>
 *
 * <p>{@code unclassified = total − queue − retrieval − llm} 是<b>减出来的</b>
 * （见 {@code TraceDetail.Latency} 与 ADR-083）。它不在 {@code qa_log} 的列里，
 * 所以对一整批行算它的分位数需要一个<b>逐行的派生列</b> ——
 * 而那是另一条 SQL，不是这一条能顺手带上的。
 *
 * <p>★ 本端点<b>刻意不报它</b>：技术面板（单次回答）能看到，
 * 是因为那一行就在手上。要在窗口内报它得再写一条带派生列的查询，
 * 而它的价值不足以让这个只读端点多一次全表扫描。
 * 想看在 {@code /api/chat/trace/&lt;traceId&gt;} 里看 —— 那里的数是对的。
 *
 * <h3>⚠️ 五段不相加等于 total</h3>
 *
 * <p>两个结构性问题（ADR-083）：{@code rerank ⊂ retrieval} 是<b>包含</b>关系不是并列；
 * 而意图分类那一次 0.5~2.5 秒的模型往返进了 {@code total} 却<b>不在任何一段里</b>。
 * <b>别把五行加起来去对 total，对不上是设计如此。</b>
 *
 * @param queueP50Ms     排队段 p50（毫秒）。★ {@code n} 常常极小
 * @param queueP95Ms     排队段 p95
 * @param queueN         <b>真的排过队</b>的行数（不是总行数）
 * @param retrievalP50Ms 检索段 p50
 * @param retrievalP95Ms 检索段 p95
 * @param retrievalN     真的检索过的行数
 * @param rerankP50Ms    重排段 p50。⚠️ {@code rerank ⊂ retrieval}
 * @param rerankP95Ms    重排段 p95
 * @param rerankN        真的重排过的行数
 * @param llmP50Ms       生成段 p50
 * @param llmP95Ms       生成段 p95
 * @param llmN           生成过的行数（这一格实测【从不】为 0）
 * @param totalP50Ms     端到端 p50
 * @param totalP95Ms     端到端 p95
 * @param totalN         ★ <b>有 {@code total_latency_ms} 的 {@code status=1} 行数</b>
 *                       —— <b>不是</b>「{@code status=1} 的行数」。
 *                       <p>⚠️ 这个区别要写清楚，因为两个数<b>看起来应该是同一个</b>：
 *                       实测（2026-09-26，非评测 164 行）三个数完全相等
 *                       （{@code status=1} 164 / {@code total} 非空 164 /
 *                       {@code llm} 非空 164）—— 服务端那条写路径<b>总是</b>同时写这两列。
 *                       <p>★ 所以「{@code totalN} 少了」这件事的含义是
 *                       <b>「有 {status=1} 的行没写时序列」</b>，
 *                       而不是「有行没统计到」。它是<b>样本数</b>不是<b>行数</b>。
 *                       两者不一致时，去看写路径而不是看这条查询。
 */
public record LatencyStats(
        Double queueP50Ms, Double queueP95Ms, long queueN,
        Double retrievalP50Ms, Double retrievalP95Ms, long retrievalN,
        Double rerankP50Ms, Double rerankP95Ms, long rerankN,
        Double llmP50Ms, Double llmP95Ms, long llmN,
        Double totalP50Ms, Double totalP95Ms, long totalN
) {
}
