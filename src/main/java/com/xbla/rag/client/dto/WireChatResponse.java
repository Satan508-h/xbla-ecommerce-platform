package com.xbla.rag.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code POST /v1/chat/completions} 的<b>非流式</b>响应 —— 线格式。
 *
 * <p>实测的真实报文长这样（2026-09-18，{@code deepseek-flash}）：
 * <pre>{@code
 * {
 *   "id": "7b00ceda-...",
 *   "object": "chat.completion",
 *   "created": 1789733482,
 *   "model": "deepseek-flash",
 *   "choices": [
 *     {
 *       "index": 0,
 *       "message": {
 *         "role": "assistant",
 *         "content": "无法确定，能否退货取决于该商品的退货政策及购买时的约定。",
 *         "reasoning_content": "我们需要回答用户中文：..."
 *       },
 *       "finish_reason": "stop"
 *     }
 *   ],
 *   "usage": { "prompt_tokens": 39, "completion_tokens": 96, ... }
 * }
 * }</pre>
 *
 * <p>注意 {@code message} 里的 {@code reasoning_content} —— 那是 DeepSeek 的
 * 非标准字段，我们的 {@link Message} 里<b>故意不声明它</b>，
 * 靠 {@code FAIL_ON_UNKNOWN_PROPERTIES=false} 静默忽略。
 * 这就是「丢弃推理内容」这个决策的全部实现。
 */
public record WireChatResponse(

        String id,

        String model,

        /**
         * 候选回复列表。
         *
         * <p>★ 用 {@link List} 而不是数组：一是流式的 usage chunk 里
         * {@code choices} 会是<b>空数组</b>（用数组 + {@code [0]} 必崩），
         * 二是列表天然能表达「一个都没有」这个状态。
         */
        List<Choice> choices,

        /** token 用量。非流式响应里一定有 */
        WireUsage usage

) {

    /** 取第一个候选回复，没有则返回 null（不要抛异常，让调用方自己决定怎么处理） */
    public Choice firstChoice() {
        return (choices == null || choices.isEmpty()) ? null : choices.get(0);
    }

    /**
     * 一个候选回复。
     */
    public record Choice(

            Integer index,

            /** ★ 这里声明的是本类的 Message，不含 reasoning_content */
            Message message,

            /**
             * 结束原因：
             * <ul>
             *   <li>{@code stop} —— 正常结束</li>
             *   <li>{@code length} —— 达到 max_tokens 被截断
             *       ★ 看到这个且 content 为空，就是「推理把额度吃光」了</li>
             * </ul>
             */
            @JsonProperty("finish_reason") String finishReason

    ) {
    }

    /**
     * 助手回复。
     *
     * <p>★ <b>故意只声明 {@code role} 和 {@code content} 两个字段。</b>
     * 服务端还会返回 {@code reasoning_content}，这里不接 —— Jackson 会忽略它。
     * 加了它反而要在业务代码里到处判空，而我们并不打算存它。
     */
    public record Message(String role, String content) {
    }
}
