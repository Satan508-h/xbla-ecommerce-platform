package com.xbla.rag.rag.profile;

import com.xbla.rag.entity.AppUser;
import com.xbla.rag.entity.OrderItem;
import com.xbla.rag.entity.Orders;
import com.xbla.rag.entity.Product;
import com.xbla.rag.entity.ProductSku;
import com.xbla.rag.service.AppUserService;
import com.xbla.rag.service.OrderItemService;
import com.xbla.rag.service.OrdersService;
import com.xbla.rag.service.ProductService;
import com.xbla.rag.service.ProductSkuService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 从订单派生用户偏好（阶段 9.5）—— 真连 PostgreSQL。
 *
 * <h2>★★★ 它盯的是【口径】，不是「查出来有东西」</h2>
 *
 * <p>「这个人的历史订单」看起来是个不需要定义的东西，其实有四个口径要拍：
 *
 * <pre>
 *   哪些状态算购买证据？   20/30/40 —— 【不含】10 待付款、50 已取消
 *   时间窗锚在哪？          created_at（唯一 NOT NULL 且有索引的）
 *   窗口多长？              180 天
 *   几笔才算证据够？        3 笔（不足就整块不出现）
 * </pre>
 *
 * <p>四条口径错任何一条，症状都是<b>一段读起来完全正常的 prompt</b> ——
 * 没有异常、没有日志、报告里的 faithfulness 只是有点低。
 *
 * <h2>★★ 二、每一节都配了反面对照</h2>
 *
 * <p>因为「查不到」和「查到了但是我不该要」在很多实现里长得一样。
 * 所以每一节都是「同一批夹具，只改一个条件，结果必须反过来」。
 *
 * <p>⚠️ 本类<b>不能</b>用种子库里的用户（{@code app_user.id} 是 3..32）——
 * 那些人有真实订单，断言会被它们带偏。每个用例都自己造一个新的
 * （自增 id 从 33 起，订单数为 0）。
 */
@SpringBootTest
@Transactional
@DisplayName("UserAffinityProvider · 从订单实时派生（9.5）")
class UserAffinityProviderIntegrationTest {

    @Autowired
    private UserAffinityProvider provider;

    @Autowired
    private AppUserService appUserService;

    @Autowired
    private OrdersService ordersService;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductSkuService productSkuService;

    private int seq;

    // ============================================================
    // 夹具
    // ============================================================

    private long newUser(int memberLevel) {
        AppUser user = new AppUser();
        user.setUserNo("S-AFF-" + System.nanoTime());
        user.setNickname("偏好测试用户");
        user.setMemberLevel(memberLevel);
        appUserService.save(user);
        return user.getId();
    }

    /**
     * 一件商品（类目/品牌/价格可控）。
     *
     * <p>⚠️ 顺手造一个 SKU —— {@code order_item.sku_id} 上有
     * {@code FK → product_sku(id)}，拿一个不存在的 id 会撞约束。
     * 这个 FK 和本类要测的东西无关，但它是真的存在。
     */
    private Product newProduct(String category, String brand, String price) {
        Product p = new Product();
        p.setProductNo("AFF-" + (++seq));
        p.setName("偏好测试商品" + seq);
        p.setCategory(category);
        p.setBrand(brand);
        p.setPrice(new BigDecimal(price));
        p.setStatus(1);
        p.setDeleted(0);
        productService.save(p);

        ProductSku sku = new ProductSku();
        sku.setProductId(p.getId());
        sku.setSkuNo("AFFSKU-" + p.getId());
        sku.setSpecName("标准版");
        sku.setPrice(new BigDecimal(price));
        sku.setStatus(1);
        sku.setDeleted(0);
        productSkuService.save(sku);
        return p;
    }

    private Long skuIdOf(Product product) {
        return productSkuService.lambdaQuery()
                .eq(ProductSku::getProductId, product.getId())
                .last("LIMIT 1")
                .one()
                .getId();
    }

    private long newOrder(long userId, int status, OffsetDateTime createdAt) {
        Orders order = new Orders();
        order.setOrderNo("SOAFF" + System.nanoTime() + "-" + (++seq));
        order.setUserId(userId);
        order.setTotalAmount(new BigDecimal("1000.00"));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setPayAmount(new BigDecimal("1000.00"));
        order.setStatus(status);
        order.setCreatedAt(createdAt);
        order.setDeleted(0);
        ordersService.save(order);
        return order.getId();
    }

    private void addItem(long orderId, Product product, String unitPrice) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setProductId(product.getId());
        item.setSkuId(skuIdOf(product));
        item.setProductName(product.getName());
        item.setSpecName("标准版");
        item.setPrice(new BigDecimal(unitPrice));
        item.setQuantity(1);
        item.setSubtotal(new BigDecimal(unitPrice));
        orderItemService.save(item);
    }

    /** 一笔「有效」订单 + 一件商品 */
    private void buy(long userId, int status, String category, String brand, String price) {
        long orderId = newOrder(userId, status, OffsetDateTime.now());
        addItem(orderId, newProduct(category, brand, price), price);
    }

    // ============================================================
    // 一、★★★ 哪些订单算证据
    // ============================================================

    @Nested
    @DisplayName("一、状态口径：只算 20/30/40")
    class StatusScope {

        @Test
        @DisplayName("★★★ 10 待付款与 50 已取消都不算 —— 加进去也不改变结果")
        void onlyPaidStatusesCount() {
            long user = newUser(1);
            buy(user, 40, "偏好状态下单类目", "品牌甲", "1000");
            buy(user, 30, "偏好状态下单类目", "品牌甲", "1000");
            buy(user, 20, "偏好状态下单类目", "品牌甲", "1000");

            UserAffinity three = provider.load(user);
            assertThat(three.orderCount()).as("三笔都有效").isEqualTo(3);

            // ★ 反面对照：再加两笔【不该算】的，数字必须一格不动
            buy(user, 10, "偏好状态下单类目", "品牌甲", "1000");
            buy(user, 50, "偏好状态下单类目", "品牌甲", "1000");

            UserAffinity five = provider.load(user);
            assertThat(five.orderCount())
                    .as("★★★ 50 已取消【不是】「没买过」—— 实测那 10 笔连 paid_at 都有，"
                            + "它们是「付过又退掉」。退款是最强的一条【反向】证据，"
                            + "把它算成购买证据等于把结论反着读")
                    .isEqualTo(3);
            assertThat(five.itemCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("★★ 反面对照的另一半：把一笔 10 改成 20 → 立刻算数")
        void flippingTheStatusFlipsTheResult() {
            long user = newUser(1);
            buy(user, 40, "偏好状态对照类目", "品牌甲", "1000");
            buy(user, 40, "偏好状态对照类目", "品牌甲", "1000");
            buy(user, 40, "偏好状态对照类目", "品牌甲", "1000");
            // ★ 三笔有效订单是阈值下限：少于它，下面第一条断言测到的
            //   会是「证据不足」而不是「10 不算」
            long pending = newOrder(user, 10, OffsetDateTime.now());
            addItem(pending, newProduct("偏好状态对照类目", "品牌甲", "1000"), "1000");

            assertThat(provider.load(user).orderCount()).isEqualTo(3);

            Orders order = ordersService.getById(pending);
            order.setStatus(20);
            ordersService.updateById(order);

            assertThat(provider.load(user).orderCount())
                    .as("★ 没有这一条，上面那条「10 不算」可能只是因为"
                            + "「这个查询从来就少算一笔」而通过")
                    .isEqualTo(4);
        }
    }

    // ============================================================
    // 二、★ 证据阈值
    // ============================================================

    @Nested
    @DisplayName("二、证据不足时整块不出现")
    class MinOrders {

        @Test
        @DisplayName("★★ 2 笔订单 → EMPTY（阈值是 3）")
        void twoOrdersIsNotEnough() {
            long user = newUser(1);
            buy(user, 40, "偏好阈值类目", "品牌甲", "1000");
            buy(user, 40, "偏好阈值类目", "品牌甲", "1000");

            UserAffinity affinity = provider.load(user);

            assertThat(affinity.isEmpty())
                    .as("★★ 实测种子库 30 人里 10 人卡在这一格，其中 4~5 人是【单类目单订单】—— "
                            + "对他们能说的唯一一句话是「你只买过手机」，"
                            + "而那是关于平台的一句假话（他只是刚注册）")
                    .isTrue();
        }

        @Test
        @DisplayName("★★ 反面对照：同一批夹具补到 3 笔 → 立刻有内容")
        void thirdOrderMakesItReal() {
            long user = newUser(2);
            buy(user, 40, "偏好阈值对照类目", "品牌甲", "1000");
            buy(user, 40, "偏好阈值对照类目", "品牌甲", "1000");

            assertThat(provider.load(user).isEmpty()).isTrue();

            buy(user, 40, "偏好阈值对照类目", "品牌乙", "2000");

            UserAffinity affinity = provider.load(user);
            assertThat(affinity.isEmpty())
                    .as("★ 没有这一条，上面那条可能只是因为「这个 provider 从来不返回东西」而通过")
                    .isFalse();
            assertThat(affinity.orderCount()).isEqualTo(3);
            assertThat(affinity.memberLevel()).isEqualTo(2);
        }
    }

    // ============================================================
    // 三、时间窗
    // ============================================================

    @Nested
    @DisplayName("三、时间窗（180 天）")
    class Window {

        @Test
        @DisplayName("★★ 200 天前的订单不算 —— 窗口边界是真的在起作用")
        void oldOrdersFallOutOfTheWindow() {
            long user = newUser(1);
            buy(user, 40, "偏好时间窗类目", "品牌甲", "1000");
            buy(user, 40, "偏好时间窗类目", "品牌甲", "1000");
            buy(user, 40, "偏好时间窗类目", "品牌甲", "1000");

            assertThat(provider.load(user).orderCount()).isEqualTo(3);

            long old = newOrder(user, 40, OffsetDateTime.now().minusDays(200));
            addItem(old, newProduct("偏好时间窗类目", "品牌甲", "1000"), "1000");

            UserAffinity affinity = provider.load(user);
            assertThat(affinity.orderCount())
                    .as("★★ 窗口不是「越多越好」的旋钮：这一段进的是每一轮的 prompt，"
                            + "而时间窗决定「两年前买过的东西还算不算数」。"
                            + "⚠️ 这条同时证明了夹具里的 created_at 真的写进去了 —— "
                            + "被 MetaObjectHandler 覆盖成 now() 的话这里会是 4")
                    .isEqualTo(3);
        }
    }

    // ============================================================
    // 四、★ 派生出来的形状
    // ============================================================

    @Nested
    @DisplayName("四、派生出来的形状与空值纪律")
    class Shape {

        @Test
        @DisplayName("★★ 计数降序 + 同数按名称升序；件数与订单数是两个数")
        void countsAreSortedAndItemCountIsSeparate() {
            long user = newUser(3);
            buy(user, 40, "偏好形状乙类目", "品牌甲", "1000");
            buy(user, 40, "偏好形状甲类目", "品牌甲", "2000");
            buy(user, 40, "偏好形状甲类目", "品牌乙", "3000");

            UserAffinity affinity = provider.load(user);

            assertThat(affinity.orderCount()).isEqualTo(3);
            assertThat(affinity.itemCount()).isEqualTo(3);
            assertThat(affinity.categories())
                    .as("★★ 同数的两个按名称升序 —— 第二个键不能省："
                            + "它由 HashMap 的迭代顺序决定的话，每次 JVM 启动都不一样，"
                            + "而这一段进的是每一轮的 prompt 前缀（差 50 倍）")
                    .extracting(UserAffinity.Count::name, UserAffinity.Count::count)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("偏好形状甲类目", 2),
                            org.assertj.core.groups.Tuple.tuple("偏好形状乙类目", 1));
            assertThat(affinity.brands()).extracting(UserAffinity.Count::name)
                    .containsExactly("品牌甲", "品牌乙");
        }

        @Test
        @DisplayName("★★ 一单两件 → orderCount 数订单、itemCount 数商品")
        void oneOrderTwoItems() {
            long user = newUser(1);
            // ★ 三笔订单是 D6 那条阈值的下限，所以必须凑够 —— 否则这条
            //   测的会是「证据不足」，而不是「订单数和件数不是一个数」
            long multi = newOrder(user, 40, OffsetDateTime.now());
            addItem(multi, newProduct("偏好多件类目", "品牌甲", "1000"), "1000");
            addItem(multi, newProduct("偏好多件类目", "品牌甲", "2000"), "2000");
            buy(user, 40, "偏好多件类目", "品牌甲", "3000");
            buy(user, 40, "偏好多件类目", "品牌甲", "4000");

            UserAffinity affinity = provider.load(user);

            assertThat(affinity.orderCount())
                    .as("★ 样本大小看的是「几笔订单」—— 用它去判「3 笔够不够格」，"
                            + "所以它必须是订单数而不是件数")
                    .isEqualTo(3);
            assertThat(affinity.itemCount())
                    .as("★ 而类目/品牌的计数是件级的（上面那笔订单买了 2 件），"
                            + "两个数都写进 prompt 才自洽")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("★★ 没有品牌的商品不进品牌统计，也【不】并成一个「未知」桶")
        void blankBrandIsDroppedNotBucketed() {
            long user = newUser(1);
            buy(user, 40, "偏好空品牌类目", null, "1000");
            buy(user, 40, "偏好空品牌类目", "  ", "1000");
            buy(user, 40, "偏好空品牌类目", "品牌甲", "1000");

            UserAffinity affinity = provider.load(user);

            assertThat(affinity.brands())
                    .as("★★ 并成一个「未知×2」会渲染成「买过的品牌：未知×2」——"
                            + "一句听起来像事实的胡说，而模型会照抄给用户")
                    .extracting(UserAffinity.Count::name)
                    .containsExactly("品牌甲");
            assertThat(affinity.categories())
                    .as("★ 反面对照：类目【不受影响】—— 这两个列表是各自独立统计的")
                    .extracting(UserAffinity.Count::name)
                    .containsExactly("偏好空品牌类目");
        }

        @Test
        @DisplayName("★ 价格三项取成交单价，并定标到整数")
        void priceStats() {
            long user = newUser(1);
            buy(user, 40, "偏好价格类目", "品牌甲", "1000.40");
            buy(user, 40, "偏好价格类目", "品牌甲", "2000.40");
            buy(user, 40, "偏好价格类目", "品牌甲", "3001.20");

            UserAffinity affinity = provider.load(user);

            assertThat(affinity.priceMin().toPlainString()).isEqualTo("1000");
            assertThat(affinity.priceMax().toPlainString()).isEqualTo("3001");
            assertThat(affinity.priceAvg().toPlainString())
                    .as("★ 定标到 0 位小数是刻意的：它是【摘要】不是账目，"
                            + "留着两位小数会让它看着像一个精确值")
                    .isEqualTo("2001");
        }
    }

    // ============================================================
    // 五、★★★ 查不到的时候
    // ============================================================

    @Nested
    @DisplayName("五、查不到不是错误")
    class Missing {

        @Test
        @DisplayName("★★★ 身份指向一个不存在的用户 → 空，且【不抛异常】")
        void unknownUserIsEmptyNotAnError() {
            assertThatCode(() -> assertThat(provider.load(999999L).isEmpty()).isTrue())
                    .as("★★★ X-Xbla-User-Id 是明文未签名的：任何人发一个 999999 都会走到这里。"
                            + "抛异常的话，工具那条路会把它包成「推荐工具答不了」——"
                            + "一个可选的个性化功能把主功能拖垮了")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("★★ 匿名（userId = null）→ 空，且不查库")
        void anonymousIsEmpty() {
            assertThat(provider.load(null).isEmpty()).isTrue();
        }
    }
}
