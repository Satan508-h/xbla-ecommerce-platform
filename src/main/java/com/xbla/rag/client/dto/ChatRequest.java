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
 * <h2>★ 消息序列的形状（阶段 5.8 起）</h2>
 *
 * <p>不用工具时它就是一条直线：
 * <pre>
 *   system → 历史（正序）→ 本轮提问
 * </pre>
 *
 * <p>用工具时变成这样 —— <b>中间那两条是「工具往返」，它不是历史，是本轮</b>：
 * <pre>
 *   [system]     你是电商客服助手…
 *   [user]       我的订单到哪了
 *   [assistant]  tool_calls=[{id:call_00_…, name:query_order_status, args:{…}}]
 *   [tool]       tool_call_id=call_00_…   订单 SO2026… 已取消
 *   ── 到这里为止是第一次请求（模型决定调工具）──
 *   ── 下面是第二次请求，模型把工具结果变成人话 ──
 *   （★ 末尾【没有】用户提问，见 {@link #userQuestion}）
 * </pre>
 *
 * <p>★★ 形状是<b>强制的</b>，破了就是 400（实测 2026-09-19）：
 * <blockquote>
 *   An assistant message with 'tool_calls' must be followed by tool messages
 *   responding to each tool_call_id
 * </blockquote>
 * 一条带 N 个 tool_calls 的 assistant 消息，后面必须紧跟 <b>N 条</b>
 * {@code role=tool} 消息，每个 {@code tool_call_id} 一一对应 ——
 * 少一条、多一条、id 对不上，都会 400。而 400 属于
 * {@link com.xbla.rag.client.ModelErrorKind#BAD_REQUEST}，<b>不降级</b>。
 *
 * @param systemPrompt 系统提示词。为 null 时不发 system 消息
 * @param history      历史对话（不含本轮提问），按时间正序。可为 null 或空。
 *                     ★ 工具往返的 assistant / tool 消息也走这里
 * @param userQuestion 本轮用户提问。
 *                     ★★ <b>为 null 表示「本轮没有新的用户提问」</b> ——
 *                     工具执行完之后的第二次请求就是这种，此时
 *                     {@code history} 的最后一条是 {@code role=tool}。
 *                     ⚠️ 不要为了「省事」把原问题再传一遍：
 *                     序列会变成 {@code …[tool:结果][user:我的订单到哪了]}，
 *                     模型会以为用户又问了一遍，于是<b>再调一次工具</b>，
 *                     或者对着重复的问题给出混乱的回答。
 *                     （同 {@code ADR-047} 记的那次「两条一样的用户消息」。）
 * @param maxTokens    覆盖默认的 max-tokens；为 null 时用配置里的默认值
 * @param temperature  覆盖默认温度；为 null 时请求体里不带这个字段
 * @param tools        ★ 本轮可用的工具。为 null 或空表示<b>不用工具</b> ——
 *                     此时请求体里连 {@code tools} 字段都不会出现，
 *                     行为和阶段 5.7 之前逐字一致
 */
public record ChatRequest(

        String systemPrompt,

        List<Turn> history,

        String userQuestion,

        Integer maxTokens,

        Double temperature,

        List<ToolSpec> tools

) {

    /**
     * 一条消息。
     *
     * <p>{@code role} 用 {@link Role} 枚举而不是字符串，
     * 避免出现 {@code "asistant"} 这种拼写错误 ——
     * 那种错误会一路传到服务端才以 400 的形式暴露出来，
     * 而且会被归类成 {@code BAD_REQUEST}（不降级），排查方向容易被带偏。
     *
     * @param content         正文。
     *                        ★ 带 {@code toolCalls} 的 assistant 消息这里可以是
     *                        <b>空串</b>（模型只想调工具、没有开场白）——
     *                        OpenAI 协议里 {@code content} 和 {@code tool_calls}
     *                        是<b>并列</b>的，不是二选一
     * @param toolCallId      role={@code tool} 时必填：这条结果回答的是哪个工具调用
     * @param toolCalls       role={@code assistant} 时可以非空：模型想调的工具
     * @param reasoningContent ★ <b>推理模型的思考过程，必须原样回声。</b>
     *                        见下面的详细说明
     */
    public record Turn(Role role, String content, String toolCallId,
                       List<ToolCall> toolCalls, String reasoningContent) {

        /** 最常用的构造：一条纯文本消息（system / user / assistant 都能用） */
        public Turn(Role role, String content) {
            this(role, content, null, null, null);
        }

        /**
         * 模型请求调用工具的那条 assistant 消息。
         *
         * <p>★ 这是<b>唯一</b>应该构造 {@code role=assistant} + {@code toolCalls}
         * 的地方，而它的三个参数只能来自 {@link ChatResponse#toolCalls()} 和
         * {@link ChatResponse#reasoningContent()} 的<b>原样搬运</b>。
         * 见 {@link ToolCall} 的类注释：改写 id 会 400。
         */
        public static Turn assistantToolCalls(String content, List<ToolCall> toolCalls,
                                              String reasoningContent) {
            return new Turn(Role.ASSISTANT, content, null, toolCalls, reasoningContent);
        }

        /** 一条工具执行结果。{@code toolCallId} 必须和请求它的那次调用逐字相同 */
        public static Turn tool(String toolCallId, String content) {
            return new Turn(Role.TOOL, content, toolCallId, null, null);
        }

        /**
         * 这条消息带工具调用吗。
         *
         * <p>用它而不是 {@code toolCalls != null}：单测里 {code List.of()} 和
         * {@code null} 都会出现，而它们的业务含义完全一样（没有工具调用）。
         */
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /** 消息角色。取值与 OpenAI 协议的字符串一一对应 */
    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant"),

        /**
         * 工具执行结果（阶段 5.8 新增）。
         *
         * <p>★ 「谁来当这个角色」在协议里是<b>调用方</b>，不是工具自己 ——
         * 所以一条 tool 消息长得像「用户说了句『这是结果』」。
         * 这个设计的好处是：工具永远不需要知道模型的存在。
         */
        TOOL("tool");

        private final String wireName;

        Role(String wireName) {
            this.wireName = wireName;
        }

        /** 转成协议里的小写字符串 */
        public String wireName() {
            return wireName;
        }
    }

    /** 最简构造：只有一轮提问，没有历史、不用工具 */
    public static ChatRequest of(String systemPrompt, String userQuestion) {
        return new ChatRequest(systemPrompt, List.of(), userQuestion, null, null, null);
    }

    /**
     * 带历史的构造（阶段 5.5 起使用）。
     *
     * <p>★ 不提供 {@code withTools(...)} 那种链式写法：本类的可选项只有两个
     * （history、tools），为它们引入一套 builder 惯例，后来的人就得先找
     * 「有哪些 with 方法」才能确定一个请求能配什么 ——
     * 而直接读 record 的字段列表更快。
     *
     * @param history 历史对话，按时间正序，<b>不含本轮提问</b>。
     *                传 {@code null} 等价于空列表
     */
    public static ChatRequest of(String systemPrompt, List<Turn> history, String userQuestion) {
        return new ChatRequest(systemPrompt, history, userQuestion, null, null, null);
    }

    /**
     * 带工具与历史的构造（阶段 5.8 起使用）。
     *
     * @param userQuestion 传 {@code null} 表示本轮没有新的用户提问（工具往返的第二跳）
     * @param tools        为 null 或空 = 不用工具
     */
    public static ChatRequest of(String systemPrompt, List<Turn> history, String userQuestion,
                                 List<ToolSpec> tools) {
        return new ChatRequest(systemPrompt, history, userQuestion, null, null, tools);
    }

    /** 本轮带工具吗 */
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }

    /**
     * 转换成协议要求的消息列表。
     *
     * <p>顺序必须是 {@code system → 历史（正序）→ 本轮提问}，
     * 这是所有 OpenAI 兼容服务的约定，顺序错了模型会答非所问。
     *
     * <p>★ {@code userQuestion} 为 null 时<b>末尾不加任何东西</b> ——
     * 那是工具往返的第二跳。见 {@link #userQuestion} 的说明。
     */
    public List<WireChatRequest.WireMessage> toWireMessages() {
        List<WireChatRequest.WireMessage> messages = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(WireChatRequest.WireMessage.system(systemPrompt));
        }
        if (history != null) {
            for (Turn turn : history) {
                // ⚠️ 参数顺序 = WireMessage 的字段顺序：
                //    (role, content, toolCalls, toolCallId, reasoningContent)
                //    这里 toolCalls 和 toolCallId 挨着且都可能是 null，极容易写反。
                //    本项目靠【类型不同】让编译器兜住 —— 一个是 List、一个是 String。
                //    如果哪天两个都变成 String，这个错误就会静默通过。
                messages.add(new WireChatRequest.WireMessage(
                        turn.role().wireName(),
                        turn.content(),
                        // ★ 领域对象是【平的】，线格式是【嵌套】的 —— 这一层转换不能省。
                        //   省了的话服务端回 422 missing field `type`，
                        //   而那个消息完全看不出是我们的形状错了
                        turn.hasToolCalls()
                                ? turn.toolCalls().stream()
                                        .map(WireToolCall::fromDomain).toList()
                                : null,
                        turn.toolCallId(),
                        turn.reasoningContent()));
            }
        }
        if (userQuestion != null) {
            messages.add(WireChatRequest.WireMessage.user(userQuestion));
        }

        return messages;
    }
}
