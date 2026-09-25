package com.xbla.rag.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一次问答的技术细节 —— {@code GET /api/chat/trace/{traceId}}。
 *
 * <p>供前端「技术细节」面板使用：回答下面点开，能看到这次<b>到底发生了什么</b> ——
 * 识别成什么意图、检索范围下推了哪些 {@code doc_types}、五个时间段各花了多久、
 * 走的是哪个供应商、花了多少钱、调了哪些工具。
 *
 * <h3>★★★ 它存在的理由：没有它，这个项目最强的部分在演示时只能靠嘴讲</h3>
 *
 * <p>本项目的卖点是「检索质量可量化」。而聊天框只能证明「它会答」，
 * 证明不了「它知道自己是怎么答的」。这个 DTO 就是后者。
 *
 * <h3>★★ 为什么不用 {@code /api/debug/mcp/qa-log?traceId=}</h3>
 *
 * <p>因为那是 {@code @Profile("local")} 的 —— 生产 profile 下不存在。
 * 把它跟着公网一起暴露是不行的：{@code /api/debug/**} 里那些端点能无条件
 * 消耗 API 额度、能改状态。所以这里是一个<b>新的、只读的、白名单的</b>出口。
 *
 * <p>⚠️ <b>没有 {@code errorMsg}</b>，这是刻意的：那条列里可能带上游服务的
 * 错误详情和内部模型 ID。同 {@code GlobalExceptionHandler} 对
 * {@code ModelCallException} 的处理 —— <b>完整信息打日志，对外只给一句可读的</b>。
 * 所以这个端点能告诉你「这次失败了」（{@code status=2}），
 * 但「为什么失败」要去日志里看。
 *
 * @param traceId           链路 ID，也是这次请求的键
 * @param status            {@code qa_log.status}：1 成功 / 2 模型链路失败 /
 *                          3 澄清反问 / 4 被限流拒。
 *                          ★ 前端要靠它区分「这次是澄清」和「这次答了」——
 *                          否则只能靠文本猜（同 {@code ChatAskResponse.intent} 的理由）
 * @param intent            识别出的意图码。为 null 有三种含义，别合并
 * @param intentConfidence  意图置信度
 * @param provider          ★ 实际生效的供应商。<b>降级后不是 P0</b> ——
 *                          这是阶段 2 验收标准的直接证据
 * @param model             实际使用的模型
 * @param cost              本次成本（元）。拿不到用量时为 null —— 不估算
 * @param promptTokens      输入 token。<b>不含缓存命中那部分</b>（它不落库）
 * @param completionTokens  输出 token
 * @param totalTokens       合计。★ <b>恒等于上面两个之和</b>（全库实测 0 例外）——
 *                          所以它也是一个免费的「读漏了列没有」的判据
 * @param degraded          本次是否发生过降级
 * @param degradationEvents 降级轨迹。没降级时是 <b>null</b>（不是空数组）
 * @param latency           五段延迟，见 {@link Latency}
 * @param retrieval         检索范围与各段条数，见 {@link Retrieval}
 * @param toolCalls         MCP 工具调用明细。不是工具题时为 null
 */
public record TraceDetail(
        String traceId,
        Integer status,
        String intent,
        BigDecimal intentConfidence,
        String provider,
        String model,
        BigDecimal cost,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        boolean degraded,
        JsonNode degradationEvents,
        Latency latency,
        Retrieval retrieval,
        JsonNode toolCalls
) {

    /**
     * 五段延迟。
     *
     * <h3>★★★ 这五段<b>不相加等于 total</b>，而且有两个结构性的原因（ADR-083）</h3>
     *
     * <pre>
     *   ① rerank ⊂ retrieval  —— 是【包含】关系，不是并列
     *      所以把两段加起来会重复计算
     *
     *   ② 「未归类」这一段的定义是【减出来的】：
     *      unclassified = total − queue − retrieval − llm
     *      它里面【主要是意图分类那一次模型往返】
     *      —— 那是一次 0.5~2.5 秒的调用，进了 total 却不在任何一列里
     *      （p50 实测 823ms）
     * </pre>
     *
     * <p>★ 别把「检索慢」和「分类慢」并成一格 —— <b>两者的修法完全相反</b>：
     * 前者调 topK / 加索引，后者换小模型 / 加缓存。
     *
     * @param queueMs      排队时长。★ 为 null 表示<b>没有排队</b>——
     *                     实测 3641 行里有 3637 行是 null（限流大多时候是关着的），
     *                     所以 null 是常态而非异常
     * @param retrievalMs  检索段（<b>包含</b>重排）。为 null = 这次没检索
     *                     （工具意图不检索，见 ADR-067）
     * @param rerankMs     重排段。<b>⊂ retrievalMs</b>，不是与它并列。
     *                     为 null = 没重排（重排关掉了，或候选为空）
     * @param llmMs        生成段。实测从不为 null
     * @param totalMs      端到端。实测从不为 null
     * @param unclassifiedMs ★ <b>{@code total − queue − retrieval − llm}</b>，
     *                     主要为意图分类。见下面的说明：
     *                     它是<b>算出来的</b>，所以只在四格都拿得到时才给值，
     *                     否则为 <b>null</b>（= 算不出来，不是 0）
     */
    public record Latency(
            Integer queueMs,
            Integer retrievalMs,
            Integer rerankMs,
            Integer llmMs,
            Integer totalMs,
            Integer unclassifiedMs
    ) {
    }

    /**
     * 检索范围与各段条数。
     *
     * <h3>★★ {@code docTypes} 为空数组 = <b>不限制</b>，不是「什么都不匹配」</h3>
     *
     * <p>这是 ADR-044 记的那条：{@code doc_types: []} 的语义是「不过滤」。
     * 解读成「不匹配」会让三个工具意图<b>退化成裸聊</b>，
     * 而裸聊会编一个订单状态出来。
     *
     * <p>★ 所以前端渲染时要<b>先看 {@code filterApplied}</b>：
     *
     * <pre>
     *   filterApplied=false            → 「未下推」
     *   filterApplied=true  + 非空列表  → 「下推到 [2, 4]」
     *   filterApplied=true  + 空列表    → 不会出现（空声明不算声明）
     * </pre>
     *
     * <p>合起来就是那句可读的话：<b>「声明非空 + applied=false → 去看 reason」</b>。
     *
     * @param docTypes     调用方声明的 {@code doc_types}。
     *                     ⚠️ 它<b>如实记录声明</b>，即使没被用上 —— 所以它会非空，
     *                     而 {@code filterApplied} 是 false。
     *                     为 null 表示<b>这一行根本没有 filter 段</b>（老数据）
     * @param filterApplied 这次到底有没有下推。
     *                     ★ <b>{@code Boolean} 而不是 {@code boolean}</b>：为 null 表示
     *                     「读不到」（老数据的 {@code retrieval_detail} 里没有 filter 段），
     *                     而不是「没有下推」。两者渲染出来都是「未下推」，
     *                     但只有一个是事实 —— 同 {@code docs/10} 的坑 20
     * @param filterReason  没下推的原因（取自 {@code FilterScope.Reason}）。
     *                     值的清单见 {@code FilterScope} —— 阶段 7 为了 A/B
     *                     专门加过一个 {@code DISABLED_BY_CONFIG}，
     *                     就是为了不复用 {@code no_declaration}（否则
     *                     「关掉开关」和「本来就没声明」在报告里长得一样）
     * @param poolSize      过滤之后池子里有多少候选。拿不到时为 null。
     *                     ★ 判据是<b>这个绝对数 ≥ vector-top-k</b>，
     *                     不是「收窄了多少倍」—— 实测倍数 1.1×~328×，
     *                     而倍数越大越危险（ADR-043）
     * @param vectorHits    向量路召回条数
     * @param keywordHits   关键词路召回条数
     * @param fused         RRF 融合后条数
     * @param reranked      重排后条数
     * @param finalTopK     最终进 prompt 的切片数
     *
     *                      <h4>★★★ 这五个数为什么是 {@code Integer} 而不是 {@code int}</h4>
     *
     *                      <p>它们来自 {@code retrieval_detail.sizes}，而
     *                      <b>{@code sizes} 是阶段 7.4 才加进去的</b> ——
     *                      在那之前写进库的行<b>根本没有这个段</b>
     *                      （{@code docs/10} 里明确记了这件事：
     *                      「旧数据只能重跑才能对齐，重算报告没用」）。
     *
     *                      <pre>
     *                        用 int  + asInt(0) 兜底 → 老数据显示「召回 0 条」
     *                                                  ✗ 那是在【撒谎】：
     *                                                    实际是「当时没记这个数」
     *                        用 Integer + null      → 前端可以显示「不可得」
     *                                                  ✓ 读不到就是读不到
     *                      </pre>
     *
     *                      <p>★ 这就是本项目那句纪律：
     *                      <b>「读不到」（我们输入的问题）和「没有做过」（世界的事实）
     *                      是两件事，任何情况下都不许把它们渲染成一个样子。</b>
     *
     *                      <h4>★ 为什么是显式字段而不是一个 map</h4>
     *
     *                      <p>往 {@code sizes} 里加一个段时，这里必须同时加一个字段 ——
     *                      而那正是「扩结构要成为一次被看见的决定」
     *                      （{@code RetrievalDetailBuilderTest} 就是为此存在的）。
     *                      做成 map 的话，新增的段会静默出现或静默消失。
     */
    public record Retrieval(
            List<Integer> docTypes,
            Boolean filterApplied,
            String filterReason,
            Integer poolSize,
            Integer vectorHits,
            Integer keywordHits,
            Integer fused,
            Integer reranked,
            Integer finalTopK
    ) {
    }
}
