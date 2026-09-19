package com.xbla.rag.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// ★ 本类的 Message 现在【声明了】reasoning_content 和 tool_calls。
//   这是对阶段 2 决策的一次有意修改，理由见类注释。

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
 * <h2>★ 关于 {@code reasoning_content} —— 阶段 2 的决策在 5.8 被修改了一半</h2>
 *
 * <p>阶段 2 的写法是：{@link Message} 里<b>故意不声明</b>这个字段，
 * 靠 {@code FAIL_ON_UNKNOWN_PROPERTIES=false} 让它被静默忽略，
 * 于是「丢弃推理内容」这个决策的实现代码量为零。
 *
 * <p>★★ <b>那个写法在工具调用场景下会炸</b>（实测 2026-09-19）：
 * {@code deepseek-flash} 在 thinking 模式下要求把 {@code reasoning_content}
 * 连同 {@code tool_calls} 一起发回去，否则 400 —— 而 400 不降级。
 * 不接这个字段，就等于每次都丢掉它，于是<b>每一次工具调用都在第二跳死掉</b>。
 *
 * <p>所以现在<b>接</b>它，但只把它当作「一次工具往返内部的暂存」：
 * <ul>
 *   <li>它<b>不进</b> {@code qa_log}，<b>不进</b> {@code chat_message}</li>
 *   <li>它<b>不会</b>返回给前端</li>
 *   <li>纯聊天路径（不用工具）拿到它之后<b>直接丢掉</b> ——
 *       行为与阶段 2 完全一致，只是多接了一个字段</li>
 * </ul>
 *
 * <p>一句话：<b>阶段 2 说「不存也不返回」是对的，5.8 补的是「但要在内存里带一程」。</b>
 * 详见 {@link WireChatRequest.WireMessage#reasoningContent()}。
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
     * <p>★ {@code content} 和 {@code toolCalls} 是<b>并列</b>关系，不是二选一：
     * 模型可以一边说「我帮你查一下」一边把工具调用发出来，
     * 也可以（实测更常见）{@code content} 是<b>空串</b>、只有 tool_calls。
     * 见 {@link #hasToolCalls()} 和 {@link #isEmptyContent()} 的配合。
     *
     * @param reasoningContent 见类注释。★ 拿到后<b>只在内存里</b>传给下一次请求，
     *                         不落库、不返回给前端
     */
    public record Message(String role, String content,
                          @JsonProperty("tool_calls") List<WireToolCall> toolCalls,
                          @JsonProperty("reasoning_content") String reasoningContent) {

        /**
         * 工具调用，转成领域对象。
         *
         * <p>★ 这一层【不能省】。{@link WireToolCall} 是嵌套的
         * （{@code name} 在 {@code function} 里面），而 {@link ToolCall} 是平的 ——
         * 直接拿 {@code ToolCall} 去接响应，{@code name} 会静默变成 null，
         * 然后在下游以一句完全不相干的错误消息炸掉。见 {@link WireToolCall} 的类注释。
         */
        public List<ToolCall> domainToolCalls() {
            return toolCalls == null ? List.of()
                    : toolCalls.stream().map(WireToolCall::toDomain).toList();
        }

        /** 正文是不是空的。<b>永远不会是 null</b>（空的话给空串） */
        public boolean isEmptyContent() {
            return content == null || content.isBlank();
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        /**
         * 这次回复<b>给了点东西吗</b> —— 正文或者工具调用，有其一即可。
         *
         * <p>★★ 这个方法的存在理由是本阶段最值得记住的一个坑：
         *
         * <p>原来的空正文判断只看 {@code content}。而工具决策轮的
         * {@code content} <b>就是空串</b>（实测：连续 5 次让模型
         * 「直接调用工具不要说话」，5 次的 {@code content} 全是 {@code ''}）。
         * 只判 content 的话，每一次工具调用都会被判成
         * 「推理模型把 max-tokens 吃光」→ 触发降级链 → 三家全失败 →
         * {@code qa_log.status=2}，错误消息写着「输出 0 token」。
         *
         * <p><b>一个指向完全错误方向的诊断</b>：真正发生的事是
         * 「模型很有礼貌地只给了工具调用，一个字都没说」。
         */
        public boolean hasAnyContent() {
            return !isEmptyContent() || hasToolCalls();
        }
    }
}
