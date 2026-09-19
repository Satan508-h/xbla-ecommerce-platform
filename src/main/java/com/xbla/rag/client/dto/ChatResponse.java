package com.xbla.rag.client.dto;

import java.util.List;

/**
 * 一次<b>非流式</b>对话调用的结果 —— 领域对象。
 *
 * <p>注意它带了 {@link #descriptor()} —— 「这个回答是哪家哪个模型给的」。
 * 这不是冗余信息，而是<b>阶段 2 验收标准第 2 条的证据来源</b>：
 * 故意把 P0 的 Key 改错后，看这个字段是不是变成了 P1 的供应商。
 *
 * @param content         模型返回的正文。<b>永远不会是 null</b>（空的话会给空串）。
 *                        ★ 有 {@code toolCalls} 时它<b>很可能是空串</b> ——
 *                        那不是失败，见 {@link #hasToolCalls()}
 * @param finishReason    结束原因：{@code stop}=正常，{@code length}=被 max-tokens 截断，
 *                        {@code tool_calls}=模型要调工具（★ 5.8 新增的第三种）
 * @param usage           token 用量。可能为 null（拿不到时）
 * @param descriptor      实际生效的模型身份
 * @param latencyMs       本次调用的耗时（毫秒）
 * @param toolCalls       ★ 模型请求调用的工具。空列表表示「这一轮就是想说话」。
 *                        见 {@link ToolCall} —— 里面的字段<b>只能原样搬运</b>
 * @param reasoningContent ★ 推理模型的思考过程。⚠️ <b>不要落库、不要返回给前端</b>，
 *                        它的唯一用途是在工具往返的第二跳原样发回去
 *                        （不发就 400）。见 {@link WireChatResponse} 的类注释
 */
public record ChatResponse(

        String content,

        String finishReason,

        ChatUsage usage,

        ModelDescriptor descriptor,

        int latencyMs,

        List<ToolCall> toolCalls,

        String reasoningContent

) {

    public ChatResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /**
     * 一次<b>普通的纯文本回答</b> —— 没有工具调用，也没有推理内容。
     *
     * <p>★ 它存在的理由不是「测试方便」，而是<b>这个形态本来就是最常见的</b>：
     * 不用工具的每一次调用、以及工具往返的最后一跳，产出的都是这种。
     * 让它有个名字，比在每个调用点写 {@code …, List.of(), null} 更能说明意图 ——
     * 而且那两个参数<b>挨着且都是 null</b>，有写反的空间。
     */
    public static ChatResponse text(String content, String finishReason, ChatUsage usage,
                                    ModelDescriptor descriptor, int latencyMs) {
        return new ChatResponse(content, finishReason, usage, descriptor, latencyMs,
                List.of(), null);
    }

    /**
     * 正文是不是空的？
     *
     * <p>★ 这个判断是「推理模型把 max-tokens 吃光」的探测器。
     * HTTP 状态码是 200、{@code finishReason} 是 {@code length}、正文却是空串 ——
     * 这不是网络故障，但结果对用户来说同样是「AI 不说话」，
     * 所以必须当成失败来降级，不能当成成功往下走。
     *
     * <p>⚠️ <b>但它【不能】单独用来判断「这次调用失败了」</b> ——
     * 工具决策轮的正文本来就是空的。判失败要用 {@link #isEmptyContent()} 的
     * 反面 {@link #hasAnyOutput()}，或者直接问 {@link #hasToolCalls()}。
     *
     * @return 正文为 null 或全是空白时返回 true
     */
    public boolean isEmptyContent() {
        return content == null || content.isBlank();
    }

    /**
     * 这一轮模型<b>给了点东西</b>吗 —— 正文或者工具调用。
     *
     * <p>★★ <b>这才是「调用成功了吗」的判据</b>，不是 {@link #isEmptyContent()}。
     *
     * <p>见 {@link ToolCall} 那个坑的姊妹篇：
     * 模型决定调工具时可以一个字都不说，此时 {@code content} 是空串。
     * 只看正文的话，每一次工具调用都会被误判成
     * 「推理把额度吃光了」→ 降级 → 三家全失败。
     */
    public boolean hasAnyOutput() {
        return !isEmptyContent() || hasToolCalls();
    }

    /** 这一轮模型要调工具吗 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** 给日志和错误消息用的一句话摘要 */
    public String describe() {
        return "provider=%s model=%s finish=%s chars=%d latency=%dms"
                .formatted(descriptor.provider(), descriptor.modelId(),
                        finishReason, content == null ? 0 : content.length(), latencyMs);
    }
}
