package com.xbla.rag.client.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次对话请求 —— 领域对象。
 *
 * <p>它是「业务想表达什么」，和「协议要求怎么发」（{@link WireChatRequest}）
 * 分开。转换发生在 {@link #toWireMessages()} 和
 * {@link com.xbla.rag.client.OpenAiCompatibleLlmClient} 内部。
 *
 * <p>阶段 2 只会用到 {@code systemPrompt} + {@code userQuestion}
 * （还不接检索和会话记忆），但 {@code history} 这个字段现在就加上 ——
 * 阶段 5 做滑动窗口记忆时，它是唯一需要填的东西，
 * 接口不用改，调用方不用改。
 *
 * @param systemPrompt 系统提示词。为 null 时不发 system 消息
 * @param history      历史对话（不含本轮提问），按时间正序。可为 null 或空
 * @param userQuestion 本轮用户提问
 * @param maxTokens    覆盖默认的 max-tokens；为 null 时用配置里的默认值
 * @param temperature  覆盖默认温度；为 null 时请求体里不带这个字段
 */
public record ChatRequest(

        String systemPrompt,

        List<Turn> history,

        String userQuestion,

        Integer maxTokens,

        Double temperature

) {

    /**
     * 一条历史消息。
     *
     * <p>{@code role} 用 {@link Role} 枚举而不是字符串，
     * 避免出现 {@code "asistant"} 这种拼写错误 ——
     * 那种错误会一路传到服务端才以 400 的形式暴露出来，
     * 而且会被归类成 {@code BAD_REQUEST}（不降级），排查方向容易被带偏。
     */
    public record Turn(Role role, String content) {
    }

    /** 消息角色。取值与 OpenAI 协议的字符串一一对应 */
    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant");

        private final String wireName;

        Role(String wireName) {
            this.wireName = wireName;
        }

        /** 转成协议里的小写字符串 */
        public String wireName() {
            return wireName;
        }
    }

    /** 最简构造：只有一轮提问，没有历史 */
    public static ChatRequest of(String systemPrompt, String userQuestion) {
        return new ChatRequest(systemPrompt, List.of(), userQuestion, null, null);
    }

    /**
     * 转换成协议要求的消息列表。
     *
     * <p>顺序必须是 {@code system → 历史（正序）→ 本轮提问}，
     * 这是所有 OpenAI 兼容服务的约定，顺序错了模型会答非所问。
     */
    public List<WireChatRequest.WireMessage> toWireMessages() {
        List<WireChatRequest.WireMessage> messages = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new WireChatRequest.WireMessage(Role.SYSTEM.wireName(), systemPrompt));
        }
        if (history != null) {
            for (Turn turn : history) {
                messages.add(new WireChatRequest.WireMessage(
                        turn.role().wireName(), turn.content()));
            }
        }
        messages.add(new WireChatRequest.WireMessage(Role.USER.wireName(), userQuestion));

        return messages;
    }
}
