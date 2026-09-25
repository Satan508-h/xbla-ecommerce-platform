package com.xbla.rag.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.client.dto.ToolCall;
import com.xbla.rag.client.dto.ToolSpec;
import com.xbla.rag.config.AgentProperties;
import com.xbla.rag.mcp.client.McpGatewayException;
import com.xbla.rag.mcp.client.McpToolGateway;
import com.xbla.rag.mcp.client.ToolOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具白名单的裁剪（阶段 9.3）—— {@code ToolLoop.resolveToolbox(userId, allowed)}。
 *
 * <p><b>不花一分钱</b>：模型网关和 MCP 网关都是桩。
 *
 * <h2>★ 判据是「模型实际收到的报文」，不是「内部那个 List 长什么样」</h2>
 *
 * <p>裁剪有三条性质，而它们全都只在<b>传给模型的那个 {@code tools} 数组</b>上成立才算数：
 *
 * <pre>
 *   ① 只留下白名单里的
 *   ② 【不重排】—— 顺序必须是服务端给的顺序
 *   ③ 白名单里没有的名字被丢掉，但【不能】让整次问答失败
 * </pre>
 *
 * <p>第 ② 条最容易被顺手破坏：加一句 {@code sorted()} 看起来无害，
 * 而 {@code tools} 数组是 prompt 前缀的一部分 —— 顺序一变，
 * 每次改动之后第一个请求就整段未命中（{@code cache-hit-input} 0.02 vs input 1.0，
 * 差 50 倍）。所以这里用 {@link ArgumentCaptor} 抓真实请求，
 * 断言的是「数组的字节顺序」，不是「集合的内容」。
 */
@DisplayName("ToolLoop · 工具白名单裁剪（阶段 9.3）")
class ToolLoopToolboxFilterTest {

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    private static final long USER_ID = 8L;

    private McpToolGateway gateway;
    private ChatModelRouter router;
    private ToolLoop toolLoop;

    @BeforeEach
    void setUp() {
        gateway = mock(McpToolGateway.class);
        router = mock(ChatModelRouter.class);
        toolLoop = new ToolLoop(gateway, router, new AgentProperties(), new ObjectMapper());
    }

    // ============================================================
    // 桩
    // ============================================================

    private static ToolSpec spec(String name) {
        return new ToolSpec(name, name + " 的说明", Map.of());
    }

    private void serverOffers(String... names) throws McpGatewayException {
        when(gateway.listTools(USER_ID))
                .thenReturn(Arrays.stream(names).map(ToolLoopToolboxFilterTest::spec).toList());
    }

    private void modelAnswers() throws Exception {
        when(router.chat(any(), any())).thenReturn(
                ChatResponse.text("好的", "stop", ChatUsage.unknown(), DESCRIPTOR, 5));
    }

    /**
     * 跑一轮，把「模型实际收到的那个请求」抓回来。
     *
     * @param kbContextPresent 这次是不是混合轮（system prompt 里有没有知识库切片）
     */
    private ToolLoop.Result run(String systemPrompt, boolean kbContextPresent, String... allowed)
            throws Exception {
        modelAnswers();
        return toolLoop.run(new ToolLoop.Input(
                        systemPrompt, List.of(), "用户问的话", USER_ID,
                        List.of(allowed), kbContextPresent),
                new ModelCallTrace("t-filter"));
    }

    /** 模型收到的工具名。<b>没带工具时返回 null</b>（不是空列表）—— 那两件事不一样 */
    private ChatRequest requestSeenByModel() throws Exception {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).chat(captor.capture(), any());
        return captor.getValue();
    }

    private static List<String> toolNames(ChatRequest request) {
        return request.tools() == null
                ? null
                : request.tools().stream().map(ToolSpec::name).toList();
    }

    // ============================================================
    // 一、过滤
    // ============================================================

    @Nested
    @DisplayName("一、只留下白名单里的")
    class Filtering {

        @Test
        @DisplayName("服务端有 4 个，白名单 2 个 → 模型只看到那 2 个")
        void keepsOnlyAllowed() throws Exception {
            serverOffers("query_inventory", "query_my_coupons", "query_order_status",
                    "search_products");

            run("系统提示", false, "query_my_coupons", "search_products");

            assertThat(toolNames(requestSeenByModel()))
                    .containsExactly("query_my_coupons", "search_products");
        }

        @Test
        @DisplayName("★★ 白名单顺序打乱【也不重排】—— 顺序必须是服务端给的")
        void doesNotReorder() throws Exception {
            // 服务端按名字升序给（真实注册表就是这么排的）
            serverOffers("compare_prices", "query_inventory", "query_order_status");

            // 白名单是倒着写的 —— 如果实现里有任何一步排了序，下面的断言就会挂
            run("系统提示", false, "query_order_status", "compare_prices");

            assertThat(toolNames(requestSeenByModel()))
                    .as("★ 只过滤、不重排。排一次序就会让 tools 数组的字节在两次启动间变化，"
                            + "而它是 prompt 前缀的一部分")
                    .containsExactly("compare_prices", "query_order_status");
        }

        @Test
        @DisplayName("白名单里服务端没有的名字被丢掉，其余照常")
        void unknownAllowedNameIsDropped() throws Exception {
            serverOffers("query_inventory", "query_my_coupons");

            ToolLoop.Result result = run("系统提示", false, "query_inventory", "typo_tool_name");

            assertThat(toolNames(requestSeenByModel())).containsExactly("query_inventory");
            // ★ 而且整次问答【没有失败】—— 这是刻意的：
            //   一个拼错的工具名不该让用户收到「服务内部错误」
            assertThat(result.answer()).isEqualTo("好的");
        }
    }

    // ============================================================
    // 二、裁剪之后一个都不剩
    // ============================================================

    @Nested
    @DisplayName("二、裁剪之后一个都不剩")
    class EmptyAfterFilter {

        @Test
        @DisplayName("★ 全都不匹配 → 不带工具问一轮（tools 是 null，不是空数组）")
        void allNamesMissingMeansNoToolsArray() throws Exception {
            serverOffers("query_inventory");

            ToolLoop.Result result = run("系统提示", false, "totally_wrong_name");

            assertThat(requestSeenByModel().tools())
                    .as("★ 传一个【空 tools 数组】和传 null 在报文上不一样，"
                            + "而空数组会让模型以为「有工具但都不该用」")
                    .isNull();
            assertThat(result.answer()).isEqualTo("好的");
        }
    }

    // ============================================================
    // 三、★★ 工具不可用时的提示词 —— 混合轮不加
    // ============================================================

    @Nested
    @DisplayName("三、★★ 工具不可用时那段说明，混合轮【不加】")
    class UnavailableNote {

        private void gatewayIsDown() throws McpGatewayException {
            when(gateway.listTools(USER_ID)).thenThrow(
                    new McpGatewayException(McpGatewayException.Stage.LIST_TOOLS, "连不上"));
        }

        @Test
        @DisplayName("纯工具轮 + 工具挂了 → 加（这正是 ADR-044 那个坑）")
        void pureToolRoundGetsTheNote() throws Exception {
            gatewayIsDown();

            run("系统提示", false, "query_order_status");

            assertThat(requestSeenByModel().systemPrompt())
                    .as("模型手上空空如也，必须给它「不知道」这个选项")
                    .contains("实时查询服务当前不可用");
        }

        @Test
        @DisplayName("★★ 反面对照：混合轮 + 工具挂了 → 不加")
        void mixedRoundDoesNotGetTheNote() throws Exception {
            gatewayIsDown();

            run("系统提示", true, "search_products");

            assertThat(requestSeenByModel().systemPrompt())
                    .as("★★ 那段说明的最后一句是「绝对不要根据通用的售后规则去推测」，"
                            + "而混合轮里它唯一的素材【就是】通用规则 —— "
                            + "加进去等于一边把资料递给它、一边叫它别用")
                    .doesNotContain("实时查询服务当前不可用");
        }
    }

    // ============================================================
    // 四、★★★ 白名单是【强制】的，不只是写在请求里
    // ============================================================

    @Nested
    @DisplayName("四、★★★ 白名单是强制的，不只是写在请求里")
    class WhitelistEnforced {

        /** 模型第一轮叫一个工具，第二轮给正文 */
        private void modelCalls(String toolName) throws Exception {
            AtomicInteger round = new AtomicInteger();
            when(router.chat(any(), any())).thenAnswer(inv -> {
                ModelCallTrace trace = inv.getArgument(1);
                if (round.incrementAndGet() == 1) {
                    trace.succeeded(DESCRIPTOR, ChatUsage.unknown(), 5);
                    return new ChatResponse("", "tool_calls", ChatUsage.unknown(), DESCRIPTOR, 5,
                            List.of(new ToolCall("call_1", toolName, "{}")), null);
                }
                trace.succeeded(DESCRIPTOR, ChatUsage.unknown(), 5);
                return ChatResponse.text("好的", "stop", ChatUsage.unknown(), DESCRIPTOR, 5);
            });
        }

        private ToolLoop.Result runScripted(String... allowed) throws Exception {
            return toolLoop.run(new ToolLoop.Input("系统提示", List.of(), "我的订单到哪了",
                            USER_ID, List.of(allowed), false),
                    new ModelCallTrace("t-whitelist"));
        }

        @Test
        @DisplayName("★★★ 模型叫了一个不在白名单里的工具 → 【一次都没真的调它】")
        void outOfWhitelistCallIsNeverExecuted() throws Exception {
            serverOffers("query_inventory", "query_my_coupons", "query_order_status");
            modelCalls("query_my_coupons");

            ToolLoop.Result result = runScripted("query_order_status");

            // ★★★ 这条断言就是本类存在的理由：
            //   请求里那个 tools 数组只告诉模型「有什么」，
            //   服务端只拒绝【不认识的名字】—— 不在白名单里的名字它是认得的。
            //   所以少了这一道，模型一发出这个调用，「按意图裁剪」就当场失效，
            //   而且它会拿到这个用户的真实数据
            verify(gateway, never()).callTool(anyLong(), anyString(), any());
            assertThat(result.calls()).singleElement()
                    .satisfies(c -> assertThat(c.isError())
                            .as("★ isError=true 是对的：这个工具【没能】给出答案（ADR-056）")
                            .isTrue());
        }

        @Test
        @DisplayName("★★ 拒绝的理由要喂回模型 —— 告诉它【能用哪些】，而不是只说「不行」")
        void rejectionTellsTheModelWhatItCanUse() throws Exception {
            serverOffers("query_inventory", "query_my_coupons", "query_order_status");
            modelCalls("query_my_coupons");

            runScripted("query_order_status");

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(router, times(2)).chat(captor.capture(), any());
            assertThat(captor.getAllValues().get(1).history())
                    .as("★ 第二跳的报文里应当带着那条 tool 结果 —— "
                            + "模型要从里面知道自己越界了，下一轮才会改")
                    .anySatisfy(turn -> assertThat(turn.content())
                            .contains("本轮不能调用 query_my_coupons")
                            .contains("query_order_status"));
        }

        @Test
        @DisplayName("★★ 反面对照：同一个夹具、白名单里【有】那个工具 → 真的调了")
        void whitelistedCallGoesThrough() throws Exception {
            serverOffers("query_inventory", "query_my_coupons", "query_order_status");
            modelCalls("query_order_status");
            when(gateway.callTool(anyLong(), anyString(), any()))
                    .thenReturn(ToolOutcome.ok("订单已发货", Map.of()));

            ToolLoop.Result result = runScripted("query_order_status");

            // ★ 没有这一条，上面那条 verify(never) 可能只是因为
            //   「这条链路上工具从来就没被调用过」而通过
            verify(gateway, times(1)).callTool(USER_ID, "query_order_status", Map.of());
            assertThat(result.calls()).singleElement()
                    .satisfies(c -> assertThat(c.isError()).isFalse());
        }
    }

    // ============================================================
    // 五、没有身份
    // ============================================================

    @Nested
    @DisplayName("五、没有身份时短路")
    class NoIdentity {

        @Test
        @DisplayName("userId 为 null → 一次模型都不调，直接回一句实话")
        void shortCircuitsWithoutIdentity() throws Exception {
            ToolLoop.Result result = toolLoop.run(
                    new ToolLoop.Input("系统提示", List.of(), "我的订单到哪了", null,
                            List.of("query_order_status"), false),
                    new ModelCallTrace("t-no-identity"));

            // ★ 不调模型是刻意的：用户没带身份头时我们【确实】查不出「他的」订单，
            //   让模型发挥的唯一素材就是通用规则 —— 也就是编
            verify(router, never()).chat(any(), any());
            assertThat(result.rounds()).isZero();
            assertThat(result.toolsAvailable()).isFalse();
            assertThat(result.answer()).contains("X-Xbla-User-Id");
        }
    }
}
