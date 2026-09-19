package com.xbla.rag.client.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具调用的<b>报文形状</b> —— 四个 DTO 上那些「会被静默违反」的约定。
 *
 * <h2>★ 为什么这四个类放在一个测试里</h2>
 *
 * <p>它们单独看都很简单（三个 record），但它们的约定是<b>同一条</b>：
 * <b>「模型说了什么」和「我们发回去什么」之间不能有任何信息损失。</b>
 * 分成四个文件的话，这条线索就断了 —— 而它恰恰是本阶段最容易踩的地方。
 *
 * <h2>★★ 本类要钉死的三件事</h2>
 *
 * <ol>
 *   <li><b>空正文 + 有工具调用 = 成功</b>，不是失败。
 *       实测 {@code deepseek-flash} 连续 5 次工具决策轮的 {@code content}
 *       全是 <b>空串</b> —— 判错了整条链路当场死掉，而错误消息
 *       会把排查方向带到「max-tokens 是不是给小了」上</li>
 *   <li><b>{@code tools} 数组的字节必须稳定</b>。
 *       它是 Prompt 前缀的一部分，而前缀缓存命中价差 <b>50 倍</b>。
 *       {@code Map.of} / {@code Map.copyOf} 的迭代顺序【每次 JVM 启动都变】</li>
 *   <li><b>参数是 JSON 字符串，且要原样搬运</b>，不能解析后再序列化 ——
 *       那会改变字节，而 {@code tool_call id} 和参数的改写实测会导致 400</li>
 * </ol>
 */
@DisplayName("工具调用的报文形状")
class ToolCallingShapeTest {

    // ============================================================
    // 一、★★ 空正文 + 工具调用
    // ============================================================

    @Nested
    @DisplayName("★★ 空正文 + 有工具调用 = 成功（不是「AI 不说话」）")
    class EmptyContentVsToolCalls {

        private ChatResponse response(String content, List<ToolCall> calls) {
            return new ChatResponse(content, "tool_calls", null, null, 10, calls, null);
        }

        @Test
        @DisplayName("★ 空正文但有工具调用 → hasAnyOutput() 为 true")
        void toolCallsWithoutContentIsStillAnOutput() {
            ChatResponse r = response("", List.of(
                    new ToolCall("call_00_x", "query_order_status", "{}")));

            assertThat(r.isEmptyContent()).as("正文【确实】是空的").isTrue();
            assertThat(r.hasAnyOutput())
                    .as("★ 但它给了工具调用 —— 这一轮是成功的。"
                            + "只看 isEmptyContent 会让每次工具调用都被判成失败")
                    .isTrue();
            assertThat(r.hasToolCalls()).isTrue();
        }

        @Test
        @DisplayName("★ 正-反对照：真的什么都没给时，两个判据都是 false")
        void trulyEmptyIsAFailure() {
            ChatResponse r = response("   ", List.of());

            assertThat(r.isEmptyContent()).isTrue();
            assertThat(r.hasAnyOutput())
                    .as("★ 没有正文也没有工具调用 —— 这才是「推理把额度吃光了」")
                    .isFalse();
            assertThat(r.hasToolCalls()).isFalse();
        }

        @Test
        @DisplayName("★ 有正文没工具调用 → 正常回答，也是一次成功的输出")
        void plainAnswerIsAnOutput() {
            ChatResponse r = response("您的订单已取消。", List.of());
            assertThat(r.hasAnyOutput()).isTrue();
            assertThat(r.hasToolCalls()).isFalse();
        }

        @Test
        @DisplayName("toolCalls 为 null 时归一成空列表 —— 调用方不用到处判空")
        void nullToolCallsNormalized() {
            assertThat(response("你好", null).toolCalls()).isEmpty();
        }
    }

    // ============================================================
    // 二、★★ tools 数组的字节稳定性
    // ============================================================

    @Nested
    @DisplayName("★★ tools 数组的字节稳定（前缀缓存的生死线）")
    class WireStability {

        private ToolSpec spec() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of("order_no", Map.of("type", "string")));
            schema.put("additionalProperties", false);
            return new ToolSpec("query_order_status", "查订单", schema);
        }

        @Test
        @DisplayName("★ 外层和 function 的键序必须是 type → function → name → description → parameters")
        void keyOrderIsExplicit() {
            Map<String, Object> wire = spec().toWireTool();

            assertThat(new ArrayList<>(wire.keySet()))
                    .as("★ 用 Map.of 的话这个顺序每次 JVM 启动都不一样")
                    .containsExactly("type", "function");

            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) wire.get("function");
            assertThat(new ArrayList<>(function.keySet()))
                    .containsExactly("name", "description", "parameters");
        }

        @Test
        @DisplayName("★★ 正-反对照：Map.of 的键序确实不稳定（证明上面那条断言不是恒真）")
        void mapOfOrderIsUnstable() {
            // ★ 「断言 A 成立」还不够 —— 必须证明「不做 A 的那个版本确实不成立」。
            //   这里用 6 个键来放大 hash 顺序和插入顺序的差异。
            //
            // ⚠️ 注意：单个 JVM 内 Map.of 的顺序是【稳定】的
            //    （SALT 在一个进程里不变），所以这个断言证明的是
            //    「顺序由 hash 决定」而不是「顺序每次调用都变」。
            //    跨 JVM 的变化是实测出来的，见 McpToolRegistry 的类注释。
            Map<String, Object> withMapOf = Map.of(
                    "type", "object",
                    "properties", "p",
                    "required", "r",
                    "additionalProperties", false,
                    "description", "d",
                    "title", "t");

            Map<String, Object> withLinked = new LinkedHashMap<>();
            withLinked.put("type", "object");
            withLinked.put("properties", "p");
            withLinked.put("required", "r");
            withLinked.put("additionalProperties", false);
            withLinked.put("description", "d");
            withLinked.put("title", "t");

            assertThat(new ArrayList<>(withLinked.keySet()))
                    .as("LinkedHashMap 保留插入顺序")
                    .containsExactly("type", "properties", "required",
                            "additionalProperties", "description", "title");
            assertThat(new ArrayList<>(withMapOf.keySet()))
                    .as("★ Map.of 不保留 —— 它的顺序由 hash 决定，"
                            + "而 hash 里掺了一个每次启动都不同的 SALT")
                    .isNotEqualTo(new ArrayList<>(withLinked.keySet()));
        }

        @Test
        @DisplayName("description 为 null 时给空串，不能给 null 也不能编一句")
        void nullDescriptionBecomesEmptyString() {
            Map<String, Object> wire = new ToolSpec("t", null, Map.of()).toWireTool();

            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) wire.get("function");
            assertThat(function.get("description"))
                    .as("★ 不能填一句「未提供说明」—— 那会被模型当成真的描述读进上下文")
                    .isEqualTo("");
        }

        @Test
        @DisplayName("工具名为空 → 构造期就抛（模型靠它选中工具）")
        void blankNameIsRejected() {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> new ToolSpec("  ", "d", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ============================================================
    // 三、原样搬运
    // ============================================================

    @Nested
    @DisplayName("ToolCall · 三个字段只能原样搬运")
    class Verbatim {

        @Test
        @DisplayName("★ 参数是 JSON 字符串，不是对象 —— 不解析、不重排")
        void argumentsStayRaw() {
            // ★ 这是 OpenAI 协议的形状（"arguments": "{\"a\":1}"）。
            //   存成字符串是为了能【逐字枚举】回去 —— 解析成 Map 再序列化
            //   会改变键序和空白，而实测 tool_call 的字段一被改写就 400
            String raw = "{\"order_no\": \"SO202609160001\"}";
            ToolCall call = new ToolCall("call_00_x", "query_order_status", raw);

            assertThat(call.arguments())
                    .as("★ 逐字相同，连那个空格都在")
                    .isEqualTo(raw);
        }

        @Test
        @DisplayName("无参工具的三种写法都要认出来")
        void noArgumentForms() {
            assertThat(new ToolCall("i", "t", null).hasNoArguments()).isTrue();
            assertThat(new ToolCall("i", "t", "").hasNoArguments()).isTrue();
            assertThat(new ToolCall("i", "t", "  {}  ").hasNoArguments()).isTrue();
            assertThat(new ToolCall("i", "t", "{\"a\":1}").hasNoArguments()).isFalse();
        }

        @Test
        @DisplayName("★ describe() 会截断参数 —— 工具参数可能带着订单内容，不该整段进日志")
        void describeTruncates() {
            String longArgs = "{\"order_no\":\"" + "9".repeat(300) + "\"}";
            ToolCall call = new ToolCall("call_00_x", "query_order_status", longArgs);

            assertThat(call.describe().length())
                    .as("★ 日志里不该出现完整的订单内容")
                    .isLessThan(150);
            assertThat(call.describe()).contains("query_order_status", "call_00_x");
        }
    }

    // ============================================================
    // 四、消息角色
    // ============================================================

    @Test
    @DisplayName("★ TOOL 是协议里的合法角色，wireName 必须是 'tool'")
    void toolRoleWireName() {
        assertThat(ChatRequest.Role.TOOL.wireName()).isEqualTo("tool");
        assertThat(ChatRequest.Role.ASSISTANT.wireName()).isEqualTo("assistant");
    }

    @Test
    @DisplayName("★ 工具往返的两条消息：assistant 带 toolCalls、tool 带 toolCallId")
    void toolRoundTripTurns() {
        List<ToolCall> calls = List.of(new ToolCall("call_00_x", "query_order_status", "{}"));

        ChatRequest.Turn assistant = ChatRequest.Turn.assistantToolCalls("", calls, "想了一下");
        ChatRequest.Turn tool = ChatRequest.Turn.tool("call_00_x", "订单已取消");

        assertThat(assistant.role()).isEqualTo(ChatRequest.Role.ASSISTANT);
        assertThat(assistant.hasToolCalls()).isTrue();
        assertThat(assistant.reasoningContent()).isEqualTo("想了一下");

        assertThat(tool.role()).isEqualTo(ChatRequest.Role.TOOL);
        assertThat(tool.toolCallId()).isEqualTo("call_00_x");
        assertThat(tool.hasToolCalls()).as("tool 消息自己不携带工具调用").isFalse();
    }

    /**
     * ★★ <b>请求方向的形状，必须【真的序列化一次】才算验证过。</b>
     *
     * <p>这是本类第一版漏掉的一层，而它对应一次真实故障（2026-09-19）：
     * 回声 tool_calls 时我们发了扁平的
     * {@code {"id":…, "name":…, "arguments":…}}，而协议要的是嵌套的
     * {@code {"id":…, "type":"function", "function":{…}}}。
     *
     * <p>服务端回的是：
     * <pre>
     *   HTTP 422  messages[1]: missing field `type`
     * </pre>
     *
     * <p>⚠️ <b>为什么没被测出来</b>：当时所有断言都下在<b>领域对象</b>
     * （{@code ChatRequest.Turn}）上，而领域对象是平的 ——
     * <b>形状的错只存在于序列化之后</b>，没序列化就永远看不见。
     */
    @Nested
    @DisplayName("★★ 线格式序列化 —— 领域对象是平的，JSON 必须是嵌套的")
    class Serialization {

        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .setSerializationInclusion(
                                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                        .configure(com.fasterxml.jackson.databind.SerializationFeature
                                .ORDER_MAP_ENTRIES_BY_KEYS, false);

        /** 从真实响应抄下来的那个 id */
        private ChatRequest withToolRoundTrip() {
            return ChatRequest.of("你是客服", List.of(
                    new ChatRequest.Turn(ChatRequest.Role.USER, "我的订单到哪了"),
                    ChatRequest.Turn.assistantToolCalls("", List.of(
                            new ToolCall("call_00_j820x57GvND1MrHFWzrh4959",
                                    "query_order_status", "{\"order_no\": \"SO1\"}")),
                            "想了一下"),
                    ChatRequest.Turn.tool("call_00_j820x57GvND1MrHFWzrh4959", "订单已取消")),
                    null);
        }

        @Test
        @DisplayName("★★ 回声的 tool_calls 必须是 {\"type\":\"function\",\"function\":{...}}")
        void toolCallsSerializesNested() throws Exception {
            String json = mapper.writeValueAsString(
                    new WireChatRequest("m", withToolRoundTrip().toWireMessages(),
                            null, null, null, null, null));

            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode assistant = root.get("messages").get(2);

            assertThat(assistant.get("role").asText()).isEqualTo("assistant");
            assertThat(assistant.get("reasoning_content").asText()).isEqualTo("想了一下");

            com.fasterxml.jackson.databind.JsonNode call = assistant.get("tool_calls").get(0);

            // ★★ 这两个断言就是那次 422 的正面形态
            assertThat(call.has("type"))
                    .as("★★ 少了它就回 HTTP 422 missing field `type` —— 真踩过")
                    .isTrue();
            assertThat(call.get("type").asText()).isEqualTo("function");
            assertThat(call.has("function"))
                    .as("★★ name/arguments 必须【嵌在 function 里】，不能在顶层")
                    .isTrue();
            assertThat(call.get("function").get("name").asText()).isEqualTo("query_order_status");
            assertThat(call.get("function").get("arguments").asText())
                    .isEqualTo("{\"order_no\": \"SO1\"}");

            // ★ 反向断言：顶层【不能】出现 name —— 出现了就是在发扁平形状
            assertThat(call.has("name"))
                    .as("顶层出现 name 说明又发成扁平的了")
                    .isFalse();

            // ★ index 是响应方向的字段，回声时不能自己编一个
            assertThat(call.has("index")).as("回声时不带 index").isFalse();
        }

        @Test
        @DisplayName("★ tool 消息带 tool_call_id，且和 assistant 的 id 逐字相同")
        void toolMessageCarriesId() throws Exception {
            String json = mapper.writeValueAsString(
                    new WireChatRequest("m", withToolRoundTrip().toWireMessages(),
                            null, null, null, null, null));

            com.fasterxml.jackson.databind.JsonNode messages =
                    mapper.readTree(json).get("messages");

            com.fasterxml.jackson.databind.JsonNode toolMsg = messages.get(3);
            assertThat(toolMsg.get("role").asText()).isEqualTo("tool");
            assertThat(toolMsg.get("tool_call_id").asText())
                    .isEqualTo(messages.get(2).get("tool_calls").get(0).get("id").asText());
        }

        @Test
        @DisplayName("★ 不用工具时请求体里【没有】tools 字段（与 5.7 之前逐字一致）")
        void noToolsFieldWhenUnused() throws Exception {
            String json = mapper.writeValueAsString(new WireChatRequest(
                    "m", ChatRequest.of("你是客服", "退货要几天").toWireMessages(),
                    null, null, null, null, null));

            assertThat(mapper.readTree(json).has("tools")).isFalse();
        }
    }

    @Test
    @DisplayName("★★ userQuestion 为 null 时末尾【不加】用户消息（工具往返的第二跳）")
    void nullQuestionAppendsNothing() {
        ChatRequest secondHop = ChatRequest.of("你是客服", List.of(
                new ChatRequest.Turn(ChatRequest.Role.USER, "我的订单到哪了"),
                ChatRequest.Turn.assistantToolCalls("", List.of(
                        new ToolCall("call_00_x", "query_order_status", "{}")), null),
                ChatRequest.Turn.tool("call_00_x", "订单已取消")),
                null);

        List<WireChatRequest.WireMessage> messages = secondHop.toWireMessages();

        assertThat(messages).hasSize(4);      // system + user + assistant + tool
        assertThat(messages.get(messages.size() - 1).role())
                .as("★ 末尾必须是 tool。多一条 user 的话模型会以为用户又问了一遍 —— "
                        + "于是再调一次工具，看起来像「模型陷入循环」")
                .isEqualTo("tool");
    }

    @Test
    @DisplayName("★ 不用工具时请求与 5.7 之前逐字一致 —— tools 字段根本不出现")
    void noToolsMeansNoField() {
        ChatRequest plain = ChatRequest.of("你是客服", "退货要几天");

        assertThat(plain.hasTools()).isFalse();
        assertThat(plain.tools()).isNull();
        assertThat(plain.toWireMessages())
                .as("system + user，两条")
                .hasSize(2);
    }
}
