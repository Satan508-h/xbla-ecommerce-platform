package com.xbla.rag.mcp.tool;

import com.xbla.rag.entity.AppUser;
import com.xbla.rag.entity.OrderItem;
import com.xbla.rag.entity.Orders;
import com.xbla.rag.mcp.McpArguments;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.McpToolResult;
import com.xbla.rag.mcp.ToolField;
import com.xbla.rag.service.AppUserService;
import com.xbla.rag.service.OrderItemService;
import com.xbla.rag.service.OrdersService;
import com.xbla.rag.service.ProductSkuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 「查询订单状态」工具的集成测试（阶段 5.7）—— 真连 PostgreSQL。
 *
 * <h3>★★ 本类真正要证明的那一件事</h3>
 *
 * <p>同一个订单号，<b>换一个身份去查，结果必须不一样</b>——
 * 而且「查不到别人的单」和「这个单号不存在」必须返回<b>逐字相同</b>的响应。
 *
 * <p>所以核心是<b>三条一起看</b>（第一、二、三节）：
 * <pre>
 *   ① 订单主人在自己名下查  → 查得到          ← 证明订单真的存在
 *   ② 别人拿同一个单号来查  → 「没找到」       ← 证明越权被挡住了
 *   ③ 拿一个不存在的单号来查 → 「没找到」       ← 证明 ② 不是「因为它不存在」
 * </pre>
 *
 * <p>少了 ①，②③ 可能只是「数据没造对」；少了 ③，② 可能只是因为
 * 工具对所有人都返回「没找到」（那功能就是坏的）。
 *
 * <p><b>运行前提</b>：docker compose 的 postgres 必须在跑，且已灌过阶段 1 的种子数据
 * （需要一个现成的 SKU 来挂订单明细）。
 */
@SpringBootTest
@Transactional
@DisplayName("QueryOrderStatusTool · 查询订单状态")
class QueryOrderStatusToolIntegrationTest {

    private static final ToolField ORDER_NO = ToolField.requiredString("order_no", "订单号");

    @Autowired
    private QueryOrderStatusTool tool;

    @Autowired
    private OrdersService ordersService;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private AppUserService appUserService;

    @Autowired
    private ProductSkuService productSkuService;

    /** 「我」 */
    private long myUserId;
    /** 「别人」 */
    private long otherUserId;

    private String myOrderNo;
    private String otherOrderNo;

    @BeforeEach
    void setUp() {
        myUserId = newUser("我");
        otherUserId = newUser("别人");

        myOrderNo = newOrder(myUserId, 40, "顺丰速运", "SF10086", new BigDecimal("1999.00"));
        otherOrderNo = newOrder(otherUserId, 30, "中通快递", "ZT90001", new BigDecimal("8888.00"));

        // ★ 给「别人」的订单挂一条明细，用来验证越权时【连商品名都看不到】
        Long skuId = productSkuService.lambdaQuery().last("LIMIT 1").one().getId();
        Long productId = productSkuService.getById(skuId).getProductId();
        addItem(otherOrderOf(otherOrderNo).getId(), productId, skuId, "别人的机密商品", "绝密规格");
    }

    /** 走真实的参数校验路径（不是 unchecked）—— 越权防护不该绕开校验层 */
    private McpToolResult call(String orderNo, long userId) {
        McpArguments args = McpArguments.of(Map.of("order_no", orderNo), tool.inputFields());
        return tool.call(args, new McpToolContext(userId));
    }

    // ============================================================
    // 一、正常查询
    // ============================================================

    @Nested
    @DisplayName("一、正常查询（订单主人在自己名下查）")
    class HappyPath {

        @Test
        @DisplayName("★ 查得到，且状态码被翻译成中文（不把数字抛给模型）")
        void findsOwnOrder() {
            McpToolResult result = call(myOrderNo, myUserId);

            assertThat(result.isError()).isFalse();
            assertThat(result.text()).contains(myOrderNo);
            assertThat(result.text())
                    .as("★ 不把 status=40 直接给模型 —— 它可能猜成「第 40 号订单」"
                            + "或者「40% 进度」。文本里只用中文，数字放在 structuredContent 里")
                    .contains("已完成")
                    .doesNotContain("status");
            assertThat(result.data()).containsEntry("status_code", 40)
                    .containsEntry("status_text", "已完成");
        }

        @Test
        @DisplayName("★ 已发货的订单带物流信息")
        void shippedOrderHasLogistics() {
            McpToolResult result = call(otherOrderNo, otherUserId);

            assertThat(result.text()).contains("中通快递").contains("ZT90001");
            assertThat(result.data()).containsEntry("logistics_no", "ZT90001");
        }

        @Test
        @DisplayName("★ 待发货的订单【不】编一个物流单号出来")
        void unshippedOrderHasNoLogistics() {
            String pending = newOrder(myUserId, 20, null, null, new BigDecimal("100.00"));

            McpToolResult result = call(pending, myUserId);

            assertThat(result.text())
                    .as("★ 这里最容易出的错是模型看到「待发货」自己补一句"
                            + "「预计 3 天内发出」—— 而系统里根本没有那个信息")
                    .doesNotContain("物流单号")
                    .doesNotContain("SF");
            assertThat(result.data()).containsEntry("status_text", "待发货");
            assertThat(result.data().get("logistics_no")).isNull();
        }

        @Test
        @DisplayName("★ 商品清单来自 order_item 的【快照】字段，不是 join 商品表")
        void itemsComeFromSnapshot() {
            Long skuId = productSkuService.lambdaQuery().last("LIMIT 1").one().getId();
            Orders order = otherOrderOf(myOrderNo);
            addItem(order.getId(), productSkuService.getById(skuId).getProductId(), skuId,
                    "下单时的名字", "下单时的规格");

            McpToolResult result = call(myOrderNo, myUserId);

            assertThat(result.text())
                    .as("★ 商品改名或改规格之后，用户的订单应该显示【他当时买的是什么】——"
                            + "order_item 的 product_name / spec_name / price 三列就是为这件事存的")
                    .contains("下单时的名字")
                    .contains("下单时的规格")
                    .contains("×2");
        }

        @Test
        @DisplayName("★ 实付金额用 toPlainString —— 不走科学计数法")
        void payAmountIsPlain() {
            McpToolResult result = call(myOrderNo, myUserId);

            assertThat(result.data()).containsEntry("pay_amount", "1999.00");
        }
    }

    // ============================================================
    // 二、★★ 越权
    // ============================================================

    @Nested
    @DisplayName("二、★★ 越权防护")
    class Idor {

        @Test
        @DisplayName("★★ 拿别人的订单号来查 → 查不到，而且看不到任何内容")
        void cannotReadSomeoneElsesOrder() {
            McpToolResult result = call(otherOrderNo, myUserId);

            assertThat(result.text())
                    .as("★★ 这是本阶段最重要的一条断言。订单号是模型能填的参数，"
                            + "所以「用别人的订单号能查到别人的订单」是一个"
                            + "【模型可控】的越权入口 —— 一段提示注入就够了")
                    .contains("没有找到")
                    .contains(otherOrderNo);
            assertThat(result.text())
                    .as("★★ 连商品名都不能泄露 —— 那本身就是隐私（买了什么药、什么礼物）")
                    .doesNotContain("别人的机密商品")
                    .doesNotContain("绝密规格")
                    .doesNotContain("8888");
            assertThat(result.data()).isNull();
        }

        @Test
        @DisplayName("★★ 「不是你的单」和「这个单不存在」返回【逐字相同】的话")
        void noEnumerationOracle() {
            String strangerOrder = call(otherOrderNo, myUserId).text();
            String ghostOrder = call("SO_NONEXISTENT_9999", myUserId).text();

            assertThat(normalize(strangerOrder))
                    .as("★★ 两句话必须一样，否则攻击者就有了一个【枚举预言机】："
                            + "换个订单号，看回复是「无权查看」还是「不存在」，"
                            + "就能逐个确认哪些订单号真实存在 —— 哪怕拿不到内容，"
                            + "「这个单号存在」本身也是信息（可以用来撞单、做社工）")
                    .isEqualTo(normalize(ghostOrder).replace("SO_NONEXISTENT_9999", otherOrderNo));
        }

        @Test
        @DisplayName("★★ 越权返回的是【答案】不是【错误】—— isError=false")
        void idorReturnsAnAnswerNotAnError() {
            McpToolResult result = call(otherOrderNo, myUserId);

            assertThat(result.isError())
                    .as("★ 「没有这一单」对工具来说是一个【完整的答案】，不是失败。"
                            + "标成 isError 的话模型会说「系统出了点问题，请稍后再试」——"
                            + "而用户重试一万次也变不出那个订单。"
                            + "详见 McpToolResult 的类注释")
                    .isFalse();
        }
    }

    // ============================================================
    // 三、★ 对照组：证明「越权查不到」不是因为订单不存在
    // ============================================================

    @Nested
    @DisplayName("三、★ 对照：同一个订单号，换回主人就能查到")
    class Contrast {

        @Test
        @DisplayName("★★ 同一个 order_no：别人查不到，主人查得到")
        void sameOrderNoDifferentIdentity() {
            McpToolResult asOwner = call(otherOrderNo, otherUserId);
            McpToolResult asStranger = call(otherOrderNo, myUserId);

            assertThat(asOwner.text())
                    .as("★★ 这一对必须一起看，缺一条就得不出结论："
                            + "只有 asStranger 的话，「查不到」可能只是因为"
                            + "订单号写错了、或者工具整个是坏的")
                    .contains("中通快递")
                    .contains("别人的机密商品");
            assertThat(asStranger.text()).contains("没有找到");

            assertThat(asOwner.text()).isNotEqualTo(asStranger.text());
        }
    }

    // ============================================================
    // 四、outputSchema 和实际返回对得上
    // ============================================================

    @Nested
    @DisplayName("四、输出契约")
    class OutputContract {

        @Test
        @DisplayName("★★ 声明了的输出字段，成功返回时必须一个不少")
        void declaredOutputsAreAllPresent() {
            McpToolResult result = call(myOrderNo, myUserId);

            for (ToolField field : tool.outputFields()) {
                if (!field.required()) {
                    continue;   // 可选的（如 logistics_no）允许为 null
                }
                assertThat(result.data())
                        .as("★ outputSchema 声明了 " + field.name()
                                + " 但实际没返回 —— 下游按 schema 取值会拿到 null，"
                                + "而 schema 看起来完全正确")
                        .containsKey(field.name());
            }
        }

        @Test
        @DisplayName("★ 未发货时选填的物流字段是 null，但 key 还在")
        void optionalFieldsArePresentButNull() {
            String pending = newOrder(myUserId, 20, null, null, new BigDecimal("1.00"));

            assertThat(call(pending, myUserId).data())
                    .as("★ key 缺失和值为 null 在 JSON Schema 里是两件事 ——"
                            + "声明了却整个不出现，下游的 `data.logistics_no` 在某些语言里会直接报错")
                    .containsKey("logistics_no")
                    .containsKey("shipped_at");
        }
    }

    // ============================================================
    // 五、防御
    // ============================================================

    @Nested
    @DisplayName("五、防御")
    class Guards {

        @Test
        @DisplayName("★ 订单号为空串 → 当成查不到，不抛异常")
        void blankOrderNoIsHandled() {
            assertThatCode(() -> call("", myUserId))
                    .as("★ 空串会走到 SQL 里变成 WHERE order_no = '' —— 恒不命中。"
                            + "不该 500")
                    .doesNotThrowAnyException();
            assertThat(call("", myUserId).text()).contains("没有找到");
        }

        @Test
        @DisplayName("★ 上下文里的 userId 是越权的唯一依据 —— 换个 id 结果就变")
        void identityIsTheOnlyLever() {
            McpToolResult mine = call(myOrderNo, myUserId);
            McpToolResult theirs = call(myOrderNo, otherUserId);

            assertThat(mine.text()).contains("已完成");
            assertThat(theirs.text())
                    .as("★ 用别人的身份查【我的】单号，同样查不到 —— 两个方向都要挡")
                    .contains("没有找到");
        }
    }

    // ============================================================
    // 造数据
    // ============================================================

    private long newUser(String nickname) {
        AppUser user = new AppUser();
        user.setUserNo("S-MCP-" + System.nanoTime());
        user.setNickname(nickname);
        user.setMemberLevel(1);
        appUserService.save(user);
        return user.getId();
    }

    private String newOrder(long userId, int status, String company, String logisticsNo,
                            BigDecimal payAmount) {
        Orders order = new Orders();
        order.setOrderNo("SOMCP" + System.nanoTime());
        order.setUserId(userId);
        order.setTotalAmount(payAmount);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setPayAmount(payAmount);
        order.setStatus(status);
        order.setLogisticsCompany(company);
        order.setLogisticsNo(logisticsNo);
        order.setCreatedAt(OffsetDateTime.now());
        if (logisticsNo != null) {
            order.setShippedAt(OffsetDateTime.now());
        }
        order.setDeleted(0);
        ordersService.save(order);
        return order.getOrderNo();
    }

    private Orders otherOrderOf(String orderNo) {
        return ordersService.lambdaQuery().eq(Orders::getOrderNo, orderNo).one();
    }

    private void addItem(Long orderId, Long productId, Long skuId, String name, String spec) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setProductId(productId);
        item.setSkuId(skuId);
        item.setProductName(name);
        item.setSpecName(spec);
        item.setPrice(new BigDecimal("999.50"));
        item.setQuantity(2);
        item.setSubtotal(new BigDecimal("1999.00"));
        orderItemService.save(item);
    }

    /** 把具体订单号抹掉再比 —— 两句话的差别只应该在订单号本身 */
    private static String normalize(String text) {
        return text.replaceAll("SO\\w+", "<NO>");
    }
}
