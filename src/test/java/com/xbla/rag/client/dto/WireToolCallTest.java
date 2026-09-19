package com.xbla.rag.client.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用<b>真实抓到的报文</b>反序列化 —— 阶段 5.8 最该早写的一个测试。
 *
 * <h2>★★ 它为什么必须存在：486 个测试全绿，而真实调用当场挂了</h2>
 *
 * <p>5.8 第一版把 {@link ToolCall}（三个平字段）直接拿去接模型的响应。
 * 而协议里 {@code name} 和 {@code arguments} <b>嵌在 {@code function} 对象里</b>，
 * 于是 Jackson 在顶层找不到 {@code name}，它静默变成 <b>null</b>。
 * 下游的反应是一句 {@code name must not be empty}，整次问答 status=2。
 *
 * <p>⚠️ <b>跑 486 个测试一个都没红</b>，因为所有测试都是
 * {@code new ToolCall("id", "query_order_status", "...")} 这样直接构造的 ——
 * <b>恰好把那一段坏掉的代码跳过去了</b>。
 *
 * <h2>★ 教训：桩造出来的对象，验证不了「桩自己是不是照协议造的」</h2>
 *
 * <p>凡是<b>跨进程边界</b>的东西（线格式 DTO），至少要有一个测试
 * <b>喂一份真实的、抄下来的 JSON</b>。手写 JSON 也一样会写错 ——
 * 因为写 JSON 的人和写 record 的人是同一个，他脑子里的形状是同一个。
 *
 * <p>所以下面的报文是<b>从真实 API 响应里逐字抄下来的</b>，
 * 不是照着 record 编的。
 */
@DisplayName("WireToolCall · 真实报文的反序列化")
class WireToolCallTest {

    /**
     * ★★ 这个 mapper 的配置必须和生产的 {@code modelObjectMapper} <b>逐项一致</b>。
     *
     * <p>这不是「配置洁癖」—— 本类第一版用了裸的 {@code new ObjectMapper()}，
     * 于是真实报文里那个 {@code "object": "chat.completion"} 字段
     * 直接被判成错误（{@code FAIL_ON_UNKNOWN_PROPERTIES} 默认是 <b>true</b>），
     * 六个用例挂了四个，而它们挂的原因和被测代码毫无关系。
     *
     * <p>★ 换句话说：<b>用一个和线上不一样的 mapper 去测线格式 DTO，
     * 测的是另一个程序。</b>
     *
     * @see com.xbla.rag.config.HttpClientConfig#modelObjectMapper()
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * ★★ 2026-09-19 从 {@code deepseek-flash} 的真实响应里抄下来的。
     *
     * <p>注意<b>三层嵌套</b>：{@code message} → {@code tool_calls[]} → {@code function}。
     * 而 {@code ToolCall} 只对应最里面那一层。
     */
    private static final String REAL_RESPONSE = """
            {
              "id": "ac5aa9ae-6ccc-4bfc-9b1c-0adef90fcb00",
              "object": "chat.completion",
              "created": 1789824595,
              "model": "deepseek-flash",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": "",
                  "reasoning_content": "The user wants to check their order status.",
                  "tool_calls": [{
                    "index": 0,
                    "id": "call_00_j820x57GvND1MrHFWzrh4959",
                    "type": "function",
                    "function": {
                      "name": "query_order_status",
                      "arguments": "{\\"order_no\\": \\"SO202607200008\\"}"
                    }
                  }]
                },
                "finish_reason": "tool_calls"
              }],
              "usage": {"prompt_tokens": 351, "completion_tokens": 51, "total_tokens": 402}
            }
            """;

    @Test
    @DisplayName("★★ 真实报文能解出工具名 —— 第一版就是在这里静默拿到 null 的")
    void parsesRealResponse() throws Exception {
        WireChatResponse response = MAPPER.readValue(REAL_RESPONSE, WireChatResponse.class);
        WireChatResponse.Message message = response.firstChoice().message();

        assertThat(message.hasToolCalls()).isTrue();
        assertThat(message.toolCalls()).hasSize(1);

        List<ToolCall> domain = message.domainToolCalls();

        assertThat(domain).hasSize(1);
        assertThat(domain.get(0).id())
                .as("★ id 必须原样搬运 —— 改写它会 400，而且 400 不降级")
                .isEqualTo("call_00_j820x57GvND1MrHFWzrh4959");
        assertThat(domain.get(0).name())
                .as("★★ 这一条就是那个 bug：直接拿 ToolCall 接响应时，它会是 null")
                .isEqualTo("query_order_status");
        assertThat(domain.get(0).arguments())
                .as("★ 参数是 JSON 字符串，且空白也要逐字保留")
                .isEqualTo("{\"order_no\": \"SO202607200008\"}");
    }

    @Test
    @DisplayName("★★ 正-反对照：把 ToolCall 当成顶层形状去解，name 确实是 null")
    void flatShapeLosesName() throws Exception {
        // ★ 「断言 A 成立」还不够 —— 必须证明「不做 A 的那个版本确实不成立」。
        //
        //   这里直接拿【嵌套的】那份 JSON 去解【平的】ToolCall：
        //   Jackson 找不到顶层的 name，于是给 null，而且【不报错】。
        //   （FAIL_ON_UNKNOWN_PROPERTIES 是关的，所以 function 被静默忽略。）
        //
        //   ⚠️ 这正是第一版的真实行为 —— 它不是「理论上可能」。
        ToolCall naive = MAPPER.readValue("""
                {"index": 0, "id": "call_00_x", "type": "function",
                 "function": {"name": "query_order_status", "arguments": "{}"}}
                """, ToolCall.class);

        assertThat(naive.id()).as("id 是顶层字段，所以它幸存了").isEqualTo("call_00_x");
        assertThat(naive.name())
                .as("★★ name 是 null —— 静默的，没有任何异常。"
                        + "这就是那句 name must not be empty 的来源")
                .isNull();
        assertThat(naive.arguments()).isNull();
    }

    @Test
    @DisplayName("★ 空正文 + 有工具调用 —— 真实报文里 content 就是空串")
    void emptyContentIsNormalForToolCalls() throws Exception {
        WireChatResponse response = MAPPER.readValue(REAL_RESPONSE, WireChatResponse.class);
        WireChatResponse.Message message = response.firstChoice().message();

        assertThat(message.content()).as("真实响应里它是空串，不是缺失字段").isEmpty();
        assertThat(message.hasAnyContent())
                .as("★ 但它给了工具调用 —— 这一轮成功了。"
                        + "只判正文的话每次工具调用都会被误判成「推理吃光了 max-tokens」")
                .isTrue();
        assertThat(response.firstChoice().finishReason()).isEqualTo("tool_calls");
    }

    @Test
    @DisplayName("★ reasoning_content 被接住了（第二跳要原样发回去，丢了就 400）")
    void reasoningContentIsCaptured() throws Exception {
        WireChatResponse response = MAPPER.readValue(REAL_RESPONSE, WireChatResponse.class);
        assertThat(response.firstChoice().message().reasoningContent())
                .isEqualTo("The user wants to check their order status.");
    }

    @Test
    @DisplayName("★ 畸形响应：没有 function 对象 → name 为 null，但【不抛异常】")
    void malformedToolCallDoesNotThrow() throws Exception {
        // ★ 为什么要求「不抛」：这个值会被一路带到 gateway，
        //   由它转成一句给模型看的话（「工具名是空的」）——
        //   模型有机会改。而在反序列化时抛异常，得到的是一个
        //   谁也看不懂的解析错误，且模型没有任何改正的机会。
        WireChatResponse response = MAPPER.readValue("""
                {"choices":[{"message":{"role":"assistant","content":"",
                 "tool_calls":[{"id":"call_x","type":"function"}]},
                 "finish_reason":"tool_calls"}]}
                """, WireChatResponse.class);

        List<ToolCall> domain = response.firstChoice().message().domainToolCalls();

        assertThat(domain).hasSize(1);
        assertThat(domain.get(0).id()).isEqualTo("call_x");
        assertThat(domain.get(0).name()).isNull();
    }

    @Test
    @DisplayName("★ 没有 tool_calls 字段时 → 空列表，不是 null")
    void noToolCallsFieldGivesEmptyList() throws Exception {
        WireChatResponse response = MAPPER.readValue("""
                {"choices":[{"message":{"role":"assistant","content":"你好"},
                 "finish_reason":"stop"}]}
                """, WireChatResponse.class);

        assertThat(response.firstChoice().message().domainToolCalls()).isEmpty();
        assertThat(response.firstChoice().message().hasToolCalls()).isFalse();
    }
}
