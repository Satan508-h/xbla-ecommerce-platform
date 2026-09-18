package com.xbla.rag.client.dto;

/**
 * token 用量 —— 领域对象（从 {@link WireUsage} 转换而来，抹平供应商差异）。
 *
 * <p>为什么要从线格式再转一层，不直接到处用 {@code WireUsage}？
 * 因为线格式是「协议的样子」，会随着供应商变化；而领域对象是
 * 「业务需要的样子」。转这一层之后，如果哪天换成一家字段名完全不同的供应商，
 * 只需要改转换代码，业务层一行不用动。
 *
 * <p><b>★ 五个字段全部允许为 null</b>，这不是偷懒，是刻意的：
 * 有些供应商不返回缓存字段，有些流式响应拿不到 usage。
 * 此时<b>宁可存 NULL 也不能编数字</b> —— {@code qa_log} 是只增不改不删的表，
 * 编出来的成本会永久污染阶段 7 的评测数据。
 */
public record ChatUsage(

        /** 输入 token 总数 */
        Integer promptTokens,

        /** 输出 token 总数。<b>已包含推理 token</b> */
        Integer completionTokens,

        /** 总 token 数 */
        Integer totalTokens,

        /**
         * 推理消耗的 token 数。
         *
         * <p>★ 只用于诊断，<b>不参与计价</b>（它是 {@code completionTokens} 的子集）。
         * 实测 {@code deepseek-flash} 上它占输出的 87%，
         * 这个比例异常高时说明该换模型或调大 max-tokens。
         *
         * <p>{@code qa_log} 里没有对应的列，所以只打进日志和 SSE 的 done 事件，
         * 不落库。
         */
        Integer reasoningTokens,

        /** 缓存命中的输入 token 数（计价用便宜的那一档） */
        Integer promptCacheHitTokens,

        /** 缓存未命中的输入 token 数（计价用贵的那一档） */
        Integer promptCacheMissTokens

) {

    /**
     * 有没有拿到可用的 token 数据？
     *
     * <p>为 false 时，成本必须记 NULL。展示层可以显示「用量未知」，
     * 但不能显示 0 —— 那会让人误以为「这次调用没花钱」。
     */
    public boolean isPresent() {
        return promptTokens != null || completionTokens != null;
    }

    /** 从线格式转换。传入 null 时返回 null（而不是抛异常） */
    public static ChatUsage from(WireUsage wire) {
        if (wire == null) {
            return null;
        }
        Integer reasoning = wire.completionDetails() == null
                ? null
                : wire.completionDetails().reasoningTokens();
        return new ChatUsage(
                wire.promptTokens(),
                wire.completionTokens(),
                wire.totalTokens(),
                reasoning,
                wire.promptCacheHitTokens(),
                wire.promptCacheMissTokens());
    }

    /** 一个「拿不到用量」的实例，用于流式响应中断等场景 */
    public static ChatUsage unknown() {
        return new ChatUsage(null, null, null, null, null, null);
    }
}
