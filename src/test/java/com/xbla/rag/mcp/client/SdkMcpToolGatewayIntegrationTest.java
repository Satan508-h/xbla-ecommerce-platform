package com.xbla.rag.mcp.client;

import com.xbla.rag.client.dto.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 官方 SDK 客户端 ⇄ 手写服务端 的端到端集成测试。
 *
 * <h2>★ 这个测试有【两个】作用，第二个比第一个更重要</h2>
 *
 * <ol>
 *   <li>验证 {@link SdkMcpToolGateway} 能用</li>
 *   <li>★★ <b>验证阶段 5.7 手写的 Server 真的合规</b> ——
 *       让一个<b>第三方的、不是我们写的</b>客户端去连它。
 *       自己测自己，怎么写都能过；官方 SDK 能握手、能列工具、能调通，
 *       才说明那份实现不是「碰巧符合我们自己的假设」</li>
 * </ol>
 *
 * <h2>为什么用 {@code DEFINED_PORT} 而不是 {@code RANDOM_PORT}</h2>
 *
 * <p>{@link SdkMcpToolGateway} 通过配置里的 URL 去连服务端，
 * 而配置是<b>启动时</b>解析的 —— 随机端口要等到容器起来才知道，
 * 那时已经晚了（除非上 {@code @DynamicPropertySource}，
 * 但它也只能拿到静态可用的值）。
 *
 * <p>★ 用 18080 而不是 8080，是为了<b>不和本地正在跑的开发服务撞端口</b> ——
 * 撞了的表现是测试连上了那个开发实例，用的却是另一份数据库连接，
 * 而且结果看起来完全正常。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18080",
                "xbla.mcp.client.base-url=http://localhost:18080"
        })
@DisplayName("MCP Client（官方 SDK）⇄ 手写 Server 端到端")
class SdkMcpToolGatewayIntegrationTest {

    /** 库里的真实数据：用户 8 是 U000006，名下 8 笔订单 */
    private static final long USER_WITH_ORDERS = 8L;
    private static final String HIS_ORDER = "SO202609160001";

    /** 另一个同样有订单的真实用户 —— 用来验越权 */
    private static final long OTHER_USER = 17L;

    /** 一个库里确定不存在的单号，格式和真的长一样 */
    private static final String NEVER_EXISTED = "SO000000000000";

    @Autowired
    private McpToolGateway gateway;

    // ============================================================
    // 一、握手与能力协商
    // ============================================================

    @Test
    @DisplayName("① 握手成功，且能列出手写 Server 注册的工具")
    void handshakeAndListTools() {
        List<ToolSpec> tools = gateway.listTools(USER_WITH_ORDERS);

        assertThat(tools).isNotEmpty();

        ToolSpec orderTool = tools.stream()
                .filter(t -> t.name().equals("query_order_status"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "没列到 query_order_status，实际拿到：" + tools.stream().map(ToolSpec::name).toList()));

        // ★ 工具描述是模型选不选它的唯一依据，不能是空的
        assertThat(orderTool.description()).isNotBlank();

        // ★ schema 必须是一个像样的 JSON Schema object ——
        //   如果 5.7 那边把它拼坏了（比如少了 "type"），这里就会露出来
        assertThat(orderTool.inputSchema())
                .containsEntry("type", "object")
                .containsKey("properties");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) orderTool.inputSchema().get("properties");
        assertThat(properties).containsKey("order_no");
    }

    // ============================================================
    // 二、工具调用
    // ============================================================

    @Test
    @DisplayName("② 调工具查到自己的订单 —— isError 必须是 false")
    void callToolFindsOwnOrder() {
        ToolOutcome outcome = gateway.callTool(USER_WITH_ORDERS, "query_order_status",
                Map.of("order_no", HIS_ORDER));

        // ★ isError=false 就是「工具给出了答案」。见 ToolOutcome 的类注释：
        //   就算答案是「查无此单」，它也是 false —— 那是一个答案，不是故障
        assertThat(outcome.isError()).isFalse();
        assertThat(outcome.text()).contains(HIS_ORDER);

        // 结构化输出也要搬过来（给探针和将来的前端用）
        assertThat(outcome.data()).isNotNull();
    }

    @Test
    @DisplayName("③ ★ 越权拿别人的订单号 —— 拿到的必须是「没找到」，而不是 403 或别人的订单")
    void cannotReadSomeoneElsesOrder() {
        ToolOutcome stranger = gateway.callTool(OTHER_USER, "query_order_status",
                Map.of("order_no", HIS_ORDER));

        // ★ 关键：这是一个【正常的答案】，不是错误。
        //   它在数据库那一层就查不到（SQL 里带着 user_id），
        //   所以工具根本不知道「这个单号存在但不属于你」
        assertThat(stranger.isError())
                .as("越权查询必须表现为「查无此单」，不能是错误 —— 否则 5.7 的 isError 语义就破了")
                .isFalse();

        // ★ 内容不能泄露。判据用【结构化输出】而不是文本 ——
        //   文本里本来就会回声调用方给的单号（那不算泄露，是他自己打的），
        //   而结构化输出是只有真查到才会有的东西
        assertThat(stranger.data())
                .as("越权时不能返回任何结构化订单数据")
                .isNull();
        assertThat(stranger.text())
                .doesNotContain("惠普")
                .doesNotContain("已取消");
    }

    @Test
    @DisplayName("④ ★★ 越权和「单号不存在」必须【不可区分】—— 否则就是个枚举预言机")
    void noEnumerationOracle() {
        ToolOutcome realButNotMine = gateway.callTool(OTHER_USER, "query_order_status",
                Map.of("order_no", HIS_ORDER));
        ToolOutcome neverExisted = gateway.callTool(OTHER_USER, "query_order_status",
                Map.of("order_no", NEVER_EXISTED));

        // ★★ 判据说清楚，因为它很容易写错（我第一版就写错了）：
        //
        //   【错】断言两条文本逐字相同 —— 工具会把调用方给的单号回声回来，
        //        所以它们必然不同。写成那样只会得到一个恒假的断言。
        //
        //   【对】把「调用方自己提供的那个单号」抹掉之后，必须逐字相同。
        //        这才是真正的安全性质：攻击者变换输入，除了他自己的输入以外
        //        学不到任何东西。
        //
        //   更要紧的是第二条：结构化输出必须【都为空】。
        //   文本一样的两个回复，完全可能在 structuredContent 里有差别 ——
        //   那才是一个真正会被忽略的泄漏面（给程序看的那份数据没人盯着）。
        assertThat(normalize(neverExisted.text(), NEVER_EXISTED))
                .as("两种失败必须不可区分，否则就是枚举预言机")
                .isEqualTo(normalize(realButNotMine.text(), HIS_ORDER));

        assertThat(realButNotMine.data())
                .as("「存在但不属于你」不能带结构化输出 —— 那是比文本更隐蔽的预言机")
                .isNull();
        assertThat(neverExisted.data()).isNull();
    }

    /** 把调用方自己提供的那个单号抹平，只留回复的「骨架」 */
    private static String normalize(String text, String echoedOrderNo) {
        return text == null ? null : text.replace(echoedOrderNo, "<单号>");
    }

    // ============================================================
    // 三、失败路径 —— 它们【必须】是可区分的
    // ============================================================

    @Test
    @DisplayName("⑤ 模型编了一个不存在的工具名 → 网关抛异常（而不是静默返回空）")
    void unknownToolName() {
        assertThatThrownBy(() -> gateway.callTool(USER_WITH_ORDERS, "query_weather",
                Map.of("city", "上海")))
                .isInstanceOf(McpGatewayException.class)
                .hasMessageContaining("调用");
    }

    @Test
    @DisplayName("⑥ ★ 非法身份 → 握手阶段就 401，而不是「查无此单」")
    void illegalIdentityFailsAtHandshake() {
        // 服务端对 user_id <= 0 回 401，SDK 在 initialize 就会失败
        assertThatThrownBy(() -> gateway.callTool(0L, "query_order_status",
                Map.of("order_no", HIS_ORDER)))
                .isInstanceOf(McpGatewayException.class)
                .extracting(e -> ((McpGatewayException) e).stage())
                .isEqualTo(McpGatewayException.Stage.INITIALIZE);
    }

    @Test
    @DisplayName("⑦ ★ Server 根本没起来 → 分类成「连接/握手」，而不是「工具说办不到」")
    void serverDownIsNotAToolFailure() {
        McpClientProperties dead = new McpClientProperties();
        dead.setBaseUrl("http://localhost:1");        // 没有任何东西在监听
        dead.setConnectTimeout(Duration.ofMillis(500));
        dead.setInitializationTimeout(Duration.ofMillis(800));

        McpToolGateway offline = new SdkMcpToolGateway(dead);

        // ★★ 这个区分是有意义的：前者说明整台 Server 都联系不上，
        //    再试别的工具也是白试；后者说明「这一把没成」，换个工具可能就好了
        assertThatThrownBy(() -> offline.listTools(USER_WITH_ORDERS))
                .isInstanceOf(McpGatewayException.class)
                .extracting(e -> ((McpGatewayException) e).stage())
                .isIn(McpGatewayException.Stage.CONNECT,
                        McpGatewayException.Stage.INITIALIZE);
    }

    // ============================================================
    // 四、每次调用重新握手 —— 这个决策的效果要能看见
    // ============================================================

    @Test
    @DisplayName("⑧ ★ 连续调用之间没有共享状态 —— 换一个身份立刻生效")
    void eachCallIsIndependent() {
        // 同一个网关实例，连着换三个身份。
        // ★ 如果实现里把身份放进了某个共享字段，这里就会串味 ——
        //   而那种 bug 在单线程测试里【看起来是对的】，只在并发下才现形
        ToolOutcome mine = gateway.callTool(USER_WITH_ORDERS, "query_order_status",
                Map.of("order_no", HIS_ORDER));
        ToolOutcome others = gateway.callTool(OTHER_USER, "query_order_status",
                Map.of("order_no", HIS_ORDER));
        ToolOutcome mineAgain = gateway.callTool(USER_WITH_ORDERS, "query_order_status",
                Map.of("order_no", HIS_ORDER));

        // ★ 判据用结构化输出，不用文本 —— 文本里两边都有那个单号（回声），
        //   区分不开。而 structuredContent 是「真查到了」才有的东西，
        //   它同时是「有没有串味」和「有没有越权」的干净信号
        assertThat(mine.data()).as("自己的订单必须查到结构化数据").isNotNull();
        assertThat(others.data()).as("★ 换身份之后必须查不到").isNull();
        assertThat(mineAgain.data())
                .as("换回原身份必须和新的一样 —— 中间那次没有污染状态")
                .isEqualTo(mine.data());
    }
}
