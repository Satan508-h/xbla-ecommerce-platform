package com.xbla.rag.mcp.tool;

import com.xbla.rag.entity.AppUser;
import com.xbla.rag.entity.Coupon;
import com.xbla.rag.entity.Orders;
import com.xbla.rag.entity.UserCoupon;
import com.xbla.rag.mcp.McpArguments;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.McpToolResult;
import com.xbla.rag.service.AppUserService;
import com.xbla.rag.service.CouponService;
import com.xbla.rag.service.OrdersService;
import com.xbla.rag.service.UserCouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「查询我的优惠券」工具的集成测试（阶段 5.9）—— 真连 PostgreSQL。
 *
 * <h3>★ 为什么数据是【这个测试自己造的】而不是用种子数据</h3>
 *
 * <p>种子数据每个用户只有 1~4 张券，状态还是随机分布的 ——
 * 想凑齐「可用 / 已使用 / 已过期 / <b>状态还没刷新的过期券</b>」四种情况
 * 得看运气。自己造 + {@code @Transactional} 回滚，四种情况都确定存在，
 * 而且不会污染真实数据。
 *
 * <h3>★★ 本类真正要证明的那一件事</h3>
 *
 * <p><b>「可用」不是 {@code status = 1} 一个条件说了算。</b>
 *
 * <p>{@code status = 1} 而 {@code expired_at} 已经过去的券是<b>存在</b>的 ——
 * 把它刷成「已过期」需要一个定时任务，而那个任务可能没跑。
 * 只信 status 的后果很具体：工具报「这张券可用」，用户拿去下单被拒。
 *
 * <p>所以第二节的核心是<b>三条一起看</b>：
 * <pre>
 *   ① status=1 且没过期   → 可用          ← 证明「可用」真的能选中东西
 *   ② status=1 但已过期   → 【不可用】     ← 证明时间条件真的在起作用
 *   ③ status=3 且已过期   → 不可用          ← 证明 ② 不是「过期就一定被排除」
 * </pre>
 * 少了 ②，第 ① 条可能只是「根本没做时间判断」；少了 ③，
 * 第 ② 条可能只是「凡是 expired_at 非空的都被排除了」。
 */
@SpringBootTest
@Transactional
@DisplayName("QueryMyCouponsTool · 查询我的优惠券")
class QueryMyCouponsToolIntegrationTest {

    /** ★ 无参工具 —— 用来证明「一个参数都没有」这条路能走通 */
    private static final McpArguments NO_ARGS = McpArguments.of(Map.of(), java.util.List.of());

    @Autowired
    private QueryMyCouponsTool tool;

    @Autowired
    private AppUserService appUserService;

    @Autowired
    private CouponService couponService;

    @Autowired
    private UserCouponService userCouponService;

    @Autowired
    private OrdersService ordersService;

    private long me;
    private long other;

    @BeforeEach
    void setUp() {
        me = createUser("COUPONTEST-ME");
        other = createUser("COUPONTEST-OTHER");
    }

    private long createUser(String tag) {
        AppUser user = new AppUser();
        user.setUserNo(tag);
        user.setNickname("测试用户");
        user.setPhone("13800000000");
        user.setMemberLevel(1);
        appUserService.save(user);
        return user.getId();
    }

    /** 建一张券模板。★ couponNo 必须唯一，所以带上用例的标记 */
    private Coupon coupon(String tag, int type, BigDecimal value, BigDecimal rate,
                          BigDecimal threshold, String category) {
        Coupon c = new Coupon();
        c.setCouponNo("TEST-" + tag);
        c.setName("测试券 " + tag);
        c.setType(type);
        c.setDiscountValue(value);
        c.setDiscountRate(rate);
        c.setThresholdAmount(threshold);
        c.setApplicableCategory(category);
        c.setTotalCount(100);
        c.setIssuedCount(0);
        c.setValidFrom(OffsetDateTime.now().minusDays(1));
        c.setValidTo(OffsetDateTime.now().plusDays(90));
        c.setStatus(1);
        couponService.save(c);
        return c;
    }

    /** 发一张券给某人 */
    private void issue(long userId, Coupon coupon, int status, OffsetDateTime expiredAt,
                       Long orderId, OffsetDateTime usedAt) {
        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setCouponId(coupon.getId());
        uc.setStatus(status);
        uc.setReceivedAt(OffsetDateTime.now().minusDays(40));
        uc.setExpiredAt(expiredAt);
        uc.setOrderId(orderId);
        uc.setUsedAt(usedAt);
        userCouponService.save(uc);
    }

    private McpToolResult call(long userId) {
        return tool.call(NO_ARGS, new McpToolContext(userId));
    }

    // ============================================================
    // 一、基本形态
    // ============================================================

    @Test
    @DisplayName("★ 无参工具：零参数能正常执行（schema 里 required 为空）")
    void runsWithoutArguments() {
        assertThat(tool.inputFields()).as("★ 一个入参都不该有 —— 理由见类注释").isEmpty();
        assertThat(tool.toToolDefinition().get("inputSchema")).isNotNull();
    }

    @Test
    @DisplayName("一张券都没有 → 如实说没有任何记录，而不是「你没有可用的券」")
    void noCouponsAtAll() {
        McpToolResult result = call(me);

        assertThat(result.isError())
                .as("★ 没有券是个【答案】，不是错误 —— 见 McpToolResult 的 isError 判据")
                .isFalse();
        assertThat(result.text()).contains("没有任何优惠券记录");
        assertThat(data(result).get("available_count")).isEqualTo(0);
    }

    // ============================================================
    // 二、★★ 「可用」的判据
    // ============================================================

    @Nested
    @DisplayName("★★ 「可用」是 status 和时间两个条件的合取")
    class Usability {

        private Coupon c1;
        private Coupon c2;
        private Coupon c3;

        @BeforeEach
        void issueThree() {
            c1 = coupon("AVAIL", 1, new BigDecimal("100"), null, new BigDecimal("1000"), null);
            c2 = coupon("STALE", 1, new BigDecimal("200"), null, new BigDecimal("2000"), null);
            c3 = coupon("MARKED", 3, new BigDecimal("300"), null, BigDecimal.ZERO, null);

            // ① 正常可用
            issue(me, c1, 1, OffsetDateTime.now().plusDays(10), null, null);
            // ② ★ status 说未使用，时间说已过期
            issue(me, c2, 1, OffsetDateTime.now().minusDays(3), null, null);
            // ③ 状态已正确刷成「已过期」
            issue(me, c3, 3, OffsetDateTime.now().minusDays(5), null, null);
        }

        @Test
        @DisplayName("★ 只有【未过期】的那一张算可用")
        void onlyUnexpiredIsAvailable() {
            McpToolResult result = call(me);

            assertThat(data(result).get("available_count"))
                    .as("★ 只判 status 的话这里会是 2 —— 那张过期的券会被报成可用")
                    .isEqualTo(1);
            assertThat(result.text()).contains("测试券 AVAIL");
            assertThat(result.text())
                    .as("★ 过期的那个不能出现在可用清单里")
                    .doesNotContain("1. 测试券 STALE");
        }

        @Test
        @DisplayName("★ 正-反对照：状态未刷新时【如实说出来】，不静默归到「已过期」")
        void staleStatusIsReported() {
            String text = call(me).text();

            assertThat(text)
                    .as("★ 第 ③ 张（status=3）不该触发这句话 —— "
                            + "它才是「正常的过期」，没有任何异常可报")
                    .contains("1 张券的状态仍显示为「未使用」，但有效期已过");
            assertThat(data(call(me)).get("expired_count"))
                    .as("两张过期的（一张状态没刷新、一张已刷新）都算已过期")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("expired_at 为 null → 视为【不过期】，不是「已过期」")
        void nullExpiryMeansNoExpiry() {
            Coupon forever = coupon("FOREVER", 3, new BigDecimal("50"), null, BigDecimal.ZERO, null);
            issue(me, forever, 1, null, null, null);

            assertThat(data(call(me)).get("available_count"))
                    .as("★ null 兜成「已过期」会把一张真券藏起来，用户根本无从发现")
                    .isEqualTo(2);
        }
    }

    // ============================================================
    // 三、三种券的文案
    // ============================================================

    @Nested
    @DisplayName("三种券类型各自的写法（value / rate 二选一）")
    class Rendering {

        @Test
        @DisplayName("★ 折扣券 0.95 → 「9.5 折」，不是「95 折」也不是「9 折」")
        void discountRate() {
            Coupon c = coupon("DISC", 2, null, new BigDecimal("0.95"),
                    new BigDecimal("500"), "智能穿戴");
            issue(me, c, 1, OffsetDateTime.now().plusDays(10), null, null);

            assertThat(call(me).text()).contains("打 9.5 折");
        }

        @Test
        @DisplayName("★ 折扣券 0.90 → 「9 折」，末尾的 0 必须去掉")
        void discountRateTrailingZero() {
            Coupon c = coupon("DISC90", 2, null, new BigDecimal("0.90"),
                    BigDecimal.ZERO, null);
            issue(me, c, 1, OffsetDateTime.now().plusDays(10), null, null);

            String text = call(me).text();
            assertThat(text).contains("打 9 折");
            assertThat(text)
                    .as("★ 「9.0 折」读起来像机器写的 —— BigDecimal.stripTrailingZeros 就是为它")
                    .doesNotContain("9.0 折");
        }

        @Test
        @DisplayName("满减券写「减 N 元」，立减券写「立减 N 元」")
        void thresholdVsCut() {
            Coupon threshold = coupon("TH", 1, new BigDecimal("600"), null,
                    new BigDecimal("5000"), "手机");
            Coupon cut = coupon("CUT", 3, new BigDecimal("50"), null,
                    BigDecimal.ZERO, "耳机");
            issue(me, threshold, 1, OffsetDateTime.now().plusDays(10), null, null);
            issue(me, cut, 1, OffsetDateTime.now().plusDays(10), null, null);

            String text = call(me).text();
            // ★ 数据库是 numeric(10,2)，取回来是 "600.00"/"5000.00" ——
            //   而这几个断言里的数字【必须是去尾零的】。见 money() 的注释
            assertThat(text).contains("减 600 元", "仅限「手机」类目", "订单满 5000 元可用");
            assertThat(text).contains("立减 50 元", "仅限「耳机」类目");
            assertThat(text)
                    .as("★ 立减券门槛是 0，写「满 0 元可用」是废话")
                    .doesNotContain("订单满 0");
            assertThat(text)
                    .as("★ 去尾零必须去干净 —— 「减 600.00 元」读起来像机器写的")
                    .doesNotContain("600.00", "5000.00");
        }

        @Test
        @DisplayName("★★ 整千的金额不能渲染成科学计数法（stripTrailingZeros 的经典坑）")
        void thousandsNotScientific() {
            // ★ BigDecimal("1000.00").stripTrailingZeros() 得到的是 1E+3（scale = -3），
            //   而 toString() 会把它原样打出来 —— 于是券面额变成「减 1E+3 元」。
            //   这个坑只在【末尾有 0 且整数部分以 0 结尾】时出现，
            //   用 600 试是试不出来的，所以这个用例必须用整千
            Coupon c = coupon("THOUSAND", 1, new BigDecimal("100"), null,
                    new BigDecimal("2000"), null);
            issue(me, c, 1, OffsetDateTime.now().plusDays(10), null, null);

            String text = call(me).text();
            assertThat(text).contains("订单满 2000 元可用");
            assertThat(text)
                    .as("★★ 科学计数法 —— toPlainString 漏了")
                    .doesNotContain("E+", "e+");
        }

        @Test
        @DisplayName("无类目限制的券写「全场通用」")
        void globalCoupon() {
            Coupon c = coupon("GLOBAL", 1, new BigDecimal("100"), null,
                    new BigDecimal("1000"), null);
            issue(me, c, 1, OffsetDateTime.now().plusDays(10), null, null);

            assertThat(call(me).text()).contains("全场通用");
        }
    }

    // ============================================================
    // 四、已使用的券
    // ============================================================

    @Test
    @DisplayName("★ 已使用的券要有 order_id 和 used_at（数据库 CHECK 强制），不混进可用清单")
    void usedCoupon() {
        // ★ 借一笔真实订单 —— status=2 的 CHECK 要求 order_id 非空且外键有效
        Orders order = ordersService.lambdaQuery().last("LIMIT 1").one();
        org.junit.jupiter.api.Assumptions.assumeTrue(order != null,
                "库里没有任何订单，跳过（需要阶段 1 的种子数据）");

        Coupon c = coupon("USED", 1, new BigDecimal("100"), null, BigDecimal.ZERO, null);
        issue(me, c, 2, OffsetDateTime.now().plusDays(10),
                order.getId(), OffsetDateTime.now().minusDays(3));

        McpToolResult result = call(me);
        assertThat(data(result).get("used_count")).isEqualTo(1);
        assertThat(data(result).get("available_count")).isEqualTo(0);
        assertThat(result.text()).contains("已使用 1 张");
    }

    // ============================================================
    // 五、★ 越权
    // ============================================================

    @Test
    @DisplayName("★★ 换一个身份，拿到的是【他那份】券 —— 不是我的")
    void identityComesFromContext() {
        Coupon mine = coupon("MINE", 1, new BigDecimal("999"), null, BigDecimal.ZERO, null);
        Coupon theirs = coupon("THEIRS", 1, new BigDecimal("111"), null, BigDecimal.ZERO, null);
        issue(me, mine, 1, OffsetDateTime.now().plusDays(10), null, null);
        issue(other, theirs, 1, OffsetDateTime.now().plusDays(10), null, null);

        String myText = call(me).text();
        String theirText = call(other).text();

        assertThat(myText).contains("测试券 MINE").doesNotContain("测试券 THEIRS");
        assertThat(theirText).contains("测试券 THEIRS").doesNotContain("测试券 MINE");

        // ★ 这个工具连参数都没有，所以「越权」在这里连入口都不存在 ——
        //   但断言还是要写：将来若有人给它加个 userId 参数，这条会立刻红
        assertThat(tool.inputFields())
                .as("★ 无参是越权的【结构性】防线，不是运气")
                .noneMatch(f -> f.name().toLowerCase().contains("user"));
    }

    @Test
    @DisplayName("★ 无参工具的 schema 里没有 required，也没有 properties")
    void schemaShape() {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tool.toToolDefinition().get("inputSchema");
        assertThat(schema).containsEntry("type", "object");
        assertThat(schema).doesNotContainKey("required");
        assertThat((Map<?, ?>) schema.get("properties")).isEmpty();
        assertThat(schema).containsEntry("additionalProperties", false);
    }

    // ============================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(McpToolResult result) {
        assertThat(result.data()).as("这个工具必须给出结构化数据").isNotNull();
        return (Map<String, Object>) result.data();
    }
}
