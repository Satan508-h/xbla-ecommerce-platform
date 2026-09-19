package com.xbla.rag.mcp;

import com.xbla.rag.mcp.protocol.JsonRpc;
import com.xbla.rag.mcp.protocol.McpProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 协议形状的测试（阶段 5.7）—— 不起 HTTP，直接调协议层。
 *
 * <p>用 {@code @SpringBootTest} 是为了拿真的 {@link McpToolRegistry}
 * （它要在构造期查重和排序）。数据库无关，所以跑得快。
 *
 * <h3>★ 这一组钉的是「报文形状」而不是「业务对错」</h3>
 *
 * <p>协议层出错的特征是<b>客户端行为异常而服务端日志正常</b> ——
 * id 类型变了、通知被回了响应、未知方法静默返回空对象……
 * 这些都不会抛异常，只会让对面等不到结果或者解析失败。
 */
@SpringBootTest
@DisplayName("McpServer · 协议形状")
class McpServerProtocolTest {

    private static final McpToolContext CONTEXT = new McpToolContext(8L);

    @Autowired
    private McpServer server;

    @Autowired
    private McpToolRegistry registry;

    /** 造一条请求报文 */
    private static Map<String, Object> request(Object id, String method) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", Map.of());
        return message;
    }

    private static Map<String, Object> request(Object id, String method, Map<String, Object> params) {
        Map<String, Object> message = request(id, method);
        message.put("params", params);
        return message;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultOf(Map<String, Object> response) {
        return (Map<String, Object>) response.get("result");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(Map<String, Object> response) {
        return (Map<String, Object>) response.get("error");
    }

    // ============================================================
    // 一、★★ id 必须原样回显
    // ============================================================

    @Nested
    @DisplayName("一、★★ id 原样回显")
    class IdEcho {

        @Test
        @DisplayName("★★ 字符串 id 回成字符串，不能变数字")
        void stringIdStaysString() {
            Map<String, Object> response = server.handle(
                    request("abc-123", McpProtocol.METHOD_TOOLS_LIST), CONTEXT);

            assertThat(response.get("id"))
                    .as("★★ 客户端用 id 来配对请求和响应。把 \"1\" 回成 1 会让它的 "
                            + "pending map 查不到 —— 现象是「请求发出去了，永远等不到结果」，"
                            + "而两边的日志都是正常的。这是 JSON-RPC 最经典的静默 bug")
                    .isEqualTo("abc-123");
        }

        @Test
        @DisplayName("★ 数字 id 回成数字，类型不变")
        void numericIdStaysNumeric() {
            assertThat(server.handle(request(42, McpProtocol.METHOD_TOOLS_LIST), CONTEXT).get("id"))
                    .isEqualTo(42);
        }

        @Test
        @DisplayName("★ 对照：id 为 null 仍然要回一个 id:null 的响应（它不是通知）")
        void explicitNullIdIsStillARequest() {
            Map<String, Object> message = request(null, McpProtocol.METHOD_TOOLS_LIST);

            assertThat(server.handle(message, CONTEXT))
                    .as("★ 判据是「id 字段出现过没有」，不是「id 是不是 null」。"
                            + "把它当通知的话这条请求就永远没有响应 —— 而客户端在等")
                    .isNotNull()
                    .containsKey("id");
        }
    }

    // ============================================================
    // 二、★★ 通知不产生响应
    // ============================================================

    @Nested
    @DisplayName("二、★★ 通知不响应")
    class Notifications {

        @Test
        @DisplayName("★★ notifications/initialized 返回 null（HTTP 层会翻成 202）")
        void initializedNotificationReturnsNull() {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("method", McpProtocol.NOTIFICATION_INITIALIZED);
            // ★ 注意：没有 id 字段

            assertThat(server.handle(message, CONTEXT))
                    .as("★★ MCP 生命周期第三步就是这条通知。回一个 id:null 的响应"
                            + "会让严格的客户端直接报协议错误 —— 而服务端这边看起来"
                            + "「我明明回了个格式正确的 JSON」")
                    .isNull();
        }

        @Test
        @DisplayName("★ 未知通知也静默忽略（规范要求）")
        void unknownNotificationIsIgnored() {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("method", "notifications/something/weird");

            assertThat(server.handle(message, CONTEXT)).isNull();
        }
    }

    // ============================================================
    // 三、initialize 与版本协商
    // ============================================================

    @Nested
    @DisplayName("三、initialize 与版本协商")
    class Initialize {

        @Test
        @DisplayName("★ 客户端报的版本我们支持 → 原样回显")
        void supportedVersionIsEchoed() {
            Map<String, Object> response = server.handle(
                    request(1, McpProtocol.METHOD_INITIALIZE,
                            Map.of("protocolVersion", "2025-06-18")), CONTEXT);

            assertThat(resultOf(response)).containsEntry("protocolVersion", "2025-06-18");
        }

        @Test
        @DisplayName("★★ 不支持的版本 → 回【我们最高的那一版】，不是报错")
        void unsupportedVersionFallsBackNotErrors() {
            Map<String, Object> response = server.handle(
                    request(1, McpProtocol.METHOD_INITIALIZE,
                            Map.of("protocolVersion", "1999-01-01")), CONTEXT);

            assertThat(response)
                    .as("★★ 规范要求这里回一个自己支持的版本，由【客户端】决定要不要断开。"
                            + "回一个 error 是错的 —— 参数错了是「这次调用做不了」，"
                            + "版本不认识是「可能整个会话都做不了」，把决定权留给对方")
                    .doesNotContainKey("error");
            assertThat(resultOf(response)).containsEntry("protocolVersion",
                    McpProtocol.PROTOCOL_VERSION);
        }

        @Test
        @DisplayName("★ 客户端压根没报版本 → 也回我们最高的那一版")
        void missingVersionFallsBack() {
            Map<String, Object> response = server.handle(
                    request(1, McpProtocol.METHOD_INITIALIZE, Map.of()), CONTEXT);

            assertThat(resultOf(response)).containsEntry("protocolVersion",
                    McpProtocol.PROTOCOL_VERSION);
        }

        @Test
        @DisplayName("★★ 只声明 tools 一种能力，且 listChanged=false")
        void capabilitiesAreHonest() {
            Map<String, Object> response = server.handle(
                    request(1, McpProtocol.METHOD_INITIALIZE, Map.of()), CONTEXT);

            assertThat(resultOf(response).get("capabilities"))
                    .as("★★ 声明了却没实现的能力比不声明更糟：客户端会照着去调，"
                            + "然后拿到 METHOD_NOT_FOUND —— 而那个错误看起来像 bug，"
                            + "不像「这个服务端不支持」。listChanged=false 同理："
                            + "声明 true 就是承诺「工具列表变了我推通知给你」，"
                            + "而我们没有工具会在运行期注册或注销")
                    .isEqualTo(Map.of("tools", Map.of("listChanged", false)));
        }

        @Test
        @DisplayName("★ serverInfo 三件套齐全")
        void serverInfoIsComplete() {
            Map<String, Object> info = (Map<String, Object>) resultOf(server.handle(
                    request(1, McpProtocol.METHOD_INITIALIZE, Map.of()), CONTEXT)).get("serverInfo");

            assertThat(info).containsKeys("name", "title", "version");
        }
    }

    // ============================================================
    // 四、★ 错误的三种去向
    // ============================================================

    @Nested
    @DisplayName("四、★ 错误的三种去向")
    class Errors {

        @Test
        @DisplayName("★ 未知方法 → METHOD_NOT_FOUND，且带上支持的方法列表")
        void unknownMethod() {
            Map<String, Object> error = errorOf(server.handle(
                    request(1, "tools/destroy"), CONTEXT));

            assertThat(error).containsEntry("code", JsonRpc.METHOD_NOT_FOUND);
            assertThat(error.get("data")).asString().contains("tools/call");
        }

        @Test
        @DisplayName("★ 缺 method → INVALID_REQUEST")
        void missingMethod() {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("jsonrpc", "2.0");
            message.put("id", 1);

            assertThat(errorOf(server.handle(message, CONTEXT)))
                    .containsEntry("code", JsonRpc.INVALID_REQUEST);
        }

        @Test
        @DisplayName("★★ 未知工具名 → 协议错误，且带上可用工具列表")
        void unknownToolIsProtocolError() {
            Map<String, Object> error = errorOf(server.handle(
                    request(1, McpProtocol.METHOD_TOOLS_CALL,
                            Map.of("name", "no_such_tool", "arguments", Map.of())), CONTEXT));

            assertThat(error)
                    .as("★★ 未知工具名【不能】翻成 isError —— 那不是「工具跑了但失败」，"
                            + "是「根本没有这个工具」。而带上可用列表，模型能自己纠正")
                    .containsEntry("code", JsonRpc.INVALID_PARAMS);
            assertThat(error.get("data")).asString().contains("query_order_status");
        }

        @Test
        @DisplayName("★★ 缺必填参数 → 协议错误（不是 isError）")
        void missingRequiredParamIsProtocolError() {
            Map<String, Object> response = server.handle(
                    request(1, McpProtocol.METHOD_TOOLS_CALL,
                            Map.of("name", "query_order_status", "arguments", Map.of())), CONTEXT);

            assertThat(errorOf(response))
                    .as("★ 这是「模型这次调用写错了」，让它改了重试 —— "
                            + "而不是让用户看到一句系统故障的道歉")
                    .containsEntry("code", JsonRpc.INVALID_PARAMS);
        }
    }

    // ============================================================
    // 五、tools/list
    // ============================================================

    @Nested
    @DisplayName("五、tools/list")
    class ToolsList {

        @Test
        @DisplayName("★ 返回的是数组，且每个元素都有 name 和 inputSchema")
        @SuppressWarnings("unchecked")
        void listsTools() {
            List<Map<String, Object>> tools = (List<Map<String, Object>>) resultOf(
                    server.handle(request(1, McpProtocol.METHOD_TOOLS_LIST), CONTEXT)).get("tools");

            assertThat(tools).isNotEmpty();
            assertThat(tools).allSatisfy(tool ->
                    assertThat(tool).containsKeys("name", "title", "description", "inputSchema"));
        }

        @Test
        @DisplayName("★ 顺序稳定（按名字升序）—— 否则每次启动的字节都不一样")
        @SuppressWarnings("unchecked")
        void orderIsStable() {
            List<Map<String, Object>> tools = (List<Map<String, Object>>) resultOf(
                    server.handle(request(1, McpProtocol.METHOD_TOOLS_LIST), CONTEXT)).get("tools");

            List<String> names = tools.stream().map(t -> (String) t.get("name")).toList();
            assertThat(names)
                    .as("★ 工具列表会进模型的上下文，也是 prompt 前缀缓存的一部分。"
                            + "顺序随 Bean 顺序变的话，同一份列表每次启动的字节都不同")
                    .isSorted();
        }
    }

    // ============================================================
    // 六、注册表
    // ============================================================

    @Nested
    @DisplayName("六、注册表")
    class Registry {

        @Test
        @DisplayName("★ 5.7 交付的那个真工具在册")
        void realToolIsRegistered() {
            assertThat(registry.find("query_order_status")).isNotNull();
        }

        @Test
        @DisplayName("★ 找不到的工具返回 null（由调用方决定怎么报错）")
        void missingToolIsNull() {
            assertThat(registry.find("nope")).isNull();
            assertThat(registry.find(null)).isNull();
        }

        @Test
        @DisplayName("★★ 重名工具【启动即崩】—— 不能是「后一个覆盖前一个」")
        void duplicateToolNameFailsFast() {
            McpTool a = stubTool("dup");
            McpTool b = stubTool("dup");

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> new McpToolRegistry(List.of(a, b)))
                    .as("★★ 若只是 put 进 Map，那么这一轮启动 A 覆盖 B、下一轮 B 覆盖 A ——"
                            + "同一个工具名两次启动行为不同，而且没有任何日志。"
                            + "排查方向会被带到「模型今天怎么不稳定」上，永远查不到这里")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dup");
        }
    }

    /** 只为了测注册表的查重 —— 本类不测任何工具的实际执行 */
    private static McpTool stubTool(String name) {
        return new McpTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String title() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public List<ToolField> inputFields() {
                return List.of();
            }

            @Override
            public McpToolResult call(McpArguments args, McpToolContext context) {
                return McpToolResult.ok("x");
            }
        };
    }
}
