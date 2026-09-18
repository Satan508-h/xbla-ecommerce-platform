package com.xbla.rag.client.dto;

/**
 * 一次<b>非流式</b>对话调用的结果 —— 领域对象。
 *
 * <p>注意它带了 {@link #descriptor()} —— 「这个回答是哪家哪个模型给的」。
 * 这不是冗余信息，而是<b>阶段 2 验收标准第 2 条的证据来源</b>：
 * 故意把 P0 的 Key 改错后，看这个字段是不是变成了 P1 的供应商。
 *
 * @param content      模型返回的正文。<b>永远不会是 null</b>（空的话会给空串）
 * @param finishReason 结束原因：{@code stop}=正常，{@code length}=被 max-tokens 截断
 * @param usage        token 用量。可能为 null（拿不到时）
 * @param descriptor   实际生效的模型身份
 * @param latencyMs    本次调用的耗时（毫秒）
 */
public record ChatResponse(

        String content,

        String finishReason,

        ChatUsage usage,

        ModelDescriptor descriptor,

        int latencyMs

) {

    /**
     * 正文是不是空的？
     *
     * <p>★ 这个判断是「推理模型把 max-tokens 吃光」的探测器。
     * HTTP 状态码是 200、{@code finishReason} 是 {@code length}、正文却是空串 ——
     * 这不是网络故障，但结果对用户来说同样是「AI 不说话」，
     * 所以必须当成失败来降级，不能当成成功往下走。
     *
     * @return 正文为 null 或全是空白时返回 true
     */
    public boolean isEmptyContent() {
        return content == null || content.isBlank();
    }

    /** 给日志和错误消息用的一句话摘要 */
    public String describe() {
        return "provider=%s model=%s finish=%s chars=%d latency=%dms"
                .formatted(descriptor.provider(), descriptor.modelId(),
                        finishReason, content == null ? 0 : content.length(), latencyMs);
    }
}
