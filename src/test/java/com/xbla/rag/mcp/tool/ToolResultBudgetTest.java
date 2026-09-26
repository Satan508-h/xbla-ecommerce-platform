package com.xbla.rag.mcp.tool;

import com.xbla.rag.agent.tool.ToolLoop;
import com.xbla.rag.entity.AppUser;
import com.xbla.rag.entity.OrderItem;
import com.xbla.rag.entity.Orders;
import com.xbla.rag.entity.Product;
import com.xbla.rag.entity.ProductSku;
import com.xbla.rag.mcp.McpArguments;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.McpToolResult;
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

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>商品类工具的正文预算</b>（阶段 9.3）—— 最坏形状下不许顶爆
 * {@link ToolLoop#MAX_TOOL_RESULT_CHARS}。
 *
 * <p><b>不花一分钱</b>：真连 PostgreSQL，但不调模型、不走 MCP。
 *
 * <h2>★★★ 它守的那个失败，是「静默截断」</h2>
 *
 * <pre>
 *   某天商品名变长了（真实电商的名字动辄几十个字）
 *   → 正文超过 4000 字
 *   → {@code ToolLoop.toolResultText} 在【末尾】截断，并附一句「结果过长已截断」
 *   → 模型看到的是：前 6 个商品完整，后面几个【凭空消失】
 *   → 而它不知道自己少了什么，于是照常总结：「就这几个」
 * </pre>
 *
 * <p>没有任何一处会报错、会 WARN、会有指标变化。
 * {@code SearchProductsTool} 的类注释里写着它「约 110 字/商品、10 个约 1350 字」——
 * 那是<b>在当时的种子数据上实测的</b>，而<b>种子数据不会跟着 schema 一起长</b>。
 * 所以这个测试不拿种子数据当基准，见下面。
 *
 * <h2>★★ 夹具的每一列都按 {@code information_schema} 的上限造</h2>
 *
 * <p>这是本类唯一一处设计选择，值得说清楚：
 * 如果把「最坏形状」写成字面量（{@code "测".repeat(255)}），
 * 那么<b>将来有人把 {@code product.name} 从 255 改宽到 512，这个测试会继续用 255 造数据</b> ——
 * 它静默地不再是「最坏情况」了，而绿着。
 *
 * <p>所以长度上限是<b>从库里读出来的</b>：改宽一列，这里自动变成新的最坏情况。
 * 顺带它还证明了一件事：{@code selling_points} 是 {@code TEXT}（没有上限），
 * 所以那个字段的长度只能靠<b>工具自己截断</b>，不能靠 schema ——
 * 这正是 {@code SearchProductsTool} 截断卖点与适用人群的原因。
 *
 * <h2>★ 每个断言都配一条「夹具真的够重」的判据</h2>
 *
 * <p>「正文 &lt; 4000」在一个空结果上也成立。所以每个用例都同时断言
 * <b>返回到的东西确实是满的</b>（10 个商品 / 5 条推荐 / 2 个比价对象）——
 * 否则某个上限被调小之后（比如商品数从 10 降到 3），这个测试会绿着放过它。
 */
@SpringBootTest
@Transactional
@DisplayName("工具正文预算 · 最坏形状下不超 4000 字")
class ToolResultBudgetTest {

    /** 卖点是 TEXT（无上限），这里取一个「远大于任何真实卖点」的字面量 */
    private static final int SELLING_POINTS_CHARS = 1000;

    /** 推荐工具打分的依据 —— 让每个候选都能得 3 分，从而一定渲染出 top_n 条 */
    private static final String NEED_HIT = "送长辈";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ProductService productService;

    @Autowired
    private SearchProductsTool searchProductsTool;

    @Autowired
    private ComparePricesTool comparePricesTool;

    @Autowired
    private RecommendProductsTool recommendProductsTool;

    @Autowired
    private AppUserService appUserService;

    @Autowired
    private OrdersService ordersService;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private ProductSkuService productSkuService;

    // ============================================================
    // 夹具
    // ============================================================

    /**
     * 按 schema 的上限造 {@code count} 个商品。
     *
     * @param keyword 唯一关键词，同时是商品名的前缀（工具靠它命中）
     */
    private String[] createWorstCaseProducts(String keyword, int count) {
        int nameMax = schemaMax("name");
        int categoryMax = schemaMax("category");
        int brandMax = schemaMax("brand");
        int noMax = schemaMax("product_no");
        int suitableMax = schemaMax("suitable_for");

        // ★ 类目也顶到上限，而且【整类商品共享同一个类目】——
        //   compare_prices 的同类目统计就是在这个类目上算的
        String category = keyword + fill('类', categoryMax - keyword.length());
        String brand = keyword + fill('牌', brandMax - keyword.length());

        String[] numbers = new String[count];
        for (int i = 0; i < count; i++) {
            Product p = new Product();
            // ★ 名字里带一个【每条不同】的后缀（keyword-序号），
            //   这样「命中的所有商品」和「只命中一个商品」两种最坏形状
            //   能用同一个夹具造出来 —— 它们是两种不同的超标原因
            String perProduct = keyword + "-" + i;
            p.setName(perProduct + fill('测', nameMax - perProduct.length()));
            p.setCategory(category);
            p.setBrand(brand);
            // 商品编号必须唯一（uk_product_product_no），也要顶到上限
            p.setProductNo(padTail("BG" + keyword + "-" + i, noMax));
            p.setPrice(new BigDecimal("9999999.99"));
            // ★ 原价大于标价 —— 比价工具会多渲染一段「（原价 …）」，那是最坏形状的一部分
            p.setOriginalPrice(new BigDecimal("99999999.99"));
            p.setSellingPoints(fill('点', SELLING_POINTS_CHARS));
            // ★ 适用人群顶到上限，且【以 need 开头】—— 推荐工具靠它打分
            p.setSuitableFor(NEED_HIT + fill('宜', suitableMax - NEED_HIT.length()));
            p.setStatus(1);
            p.setDeleted(0);
            productService.save(p);
            numbers[i] = p.getProductNo();
        }
        return numbers;
    }

    /**
     * 读一列在库里的长度上限。★ <b>夹具的「最坏」由 schema 定义，不由测试作者定义</b> ——
     * 见类注释。
     */
    private int schemaMax(String column) {
        String sql = "select character_maximum_length from information_schema.columns "
                + "where table_schema = current_schema() and table_name = 'product' "
                + "and column_name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("product.%s 这一列必须在库里 —— 否则下面的长度会静默退化成 0", column)
                        .isTrue();
                int max = rs.getInt(1);
                assertThat(max)
                        .as("product.%s 必须是定长文本列。★ 若它变成了 TEXT（上限为 NULL），"
                                + "那这一列的「最坏形状」就只能靠工具自己截断，"
                                + "这个测试得换一种写法", column)
                        .isGreaterThan(0);
                return max;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读 product." + column + " 的长度上限失败", e);
        }
    }

    private static String fill(char c, int n) {
        return n <= 0 ? "" : String.valueOf(c).repeat(n);
    }

    private static String padTail(String value, int length) {
        return value.length() >= length ? value.substring(0, length)
                : value + fill('9', length - value.length());
    }

    private static McpToolResult call(com.xbla.rag.mcp.McpTool tool, Map<String, Object> args) {
        return tool.call(McpArguments.of(args, tool.inputFields()), new McpToolContext(1L));
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, Object>> rows(McpToolResult result, String key) {
        assertThat(result.data()).as("结构化数据不该为空").isNotNull();
        return (java.util.List<Map<String, Object>>) ((Map<String, Object>) result.data()).get(key);
    }

    /**
     * 造一个「有购买证据」的用户：3 笔有效订单，买的都是 {@code category} / {@code brand}
     * 顶格商品（阶段 9.5 的偏好最坏形状）。
     *
     * <p>★ 3 笔是 {@code xbla.agent.profile.min-orders} 的下限 —— 少一笔
     * 这个人就没有偏好，而那样偏好的那几段正文一个字都不会出现。
     */
    private long userWithHistoryOf(String category, String brand) {
        AppUser user = new AppUser();
        user.setUserNo("S-BG-" + System.nanoTime());
        user.setNickname("预算测试用户");
        user.setMemberLevel(4);
        appUserService.save(user);

        for (int i = 0; i < 3; i++) {
            Product p = new Product();
            p.setProductNo(padTail("BGAFF" + i + System.nanoTime(), schemaMax("product_no")));
            p.setName("预算偏好历史" + i);
            p.setCategory(category);
            p.setBrand(brand);
            // 和历史成交价一致 —— 让「价格量级相符」那一分也命中
            p.setPrice(new BigDecimal("9999999.99"));
            p.setStatus(1);
            p.setDeleted(0);
            productService.save(p);

            ProductSku sku = new ProductSku();
            sku.setProductId(p.getId());
            sku.setSkuNo("BGSKU-" + p.getId());
            sku.setSpecName("标准版");
            sku.setPrice(new BigDecimal("9999999.99"));
            sku.setStatus(1);
            sku.setDeleted(0);
            productSkuService.save(sku);

            Orders order = new Orders();
            order.setOrderNo("SOBG" + System.nanoTime() + "-" + i);
            order.setUserId(user.getId());
            order.setTotalAmount(new BigDecimal("9999999.99"));
            order.setDiscountAmount(BigDecimal.ZERO);
            order.setPayAmount(new BigDecimal("9999999.99"));
            order.setStatus(40);
            order.setCreatedAt(java.time.OffsetDateTime.now());
            order.setDeleted(0);
            ordersService.save(order);

            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(p.getId());
            item.setSkuId(sku.getId());
            item.setProductName(p.getName());
            item.setSpecName("标准版");
            item.setPrice(new BigDecimal("9999999.99"));
            item.setQuantity(1);
            item.setSubtotal(new BigDecimal("9999999.99"));
            orderItemService.save(item);
        }
        return user.getId();
    }

    /** 把「正文多少字 / 上限多少」写进断言消息 —— 红了要一眼看出差多少 */
    private static void assertWithinBudget(McpToolResult result, String who) {
        assertThat(result.text().length())
                .as("%s 的正文 %d 字，上限 %d 字 —— 超了就会被 ToolLoop 在末尾静默截断",
                        who, result.text().length(), ToolLoop.MAX_TOOL_RESULT_CHARS)
                .isLessThan(ToolLoop.MAX_TOOL_RESULT_CHARS);
    }

    // ============================================================
    // 一、search_products —— 唯一一个会顶到天花板的
    // ============================================================

    @Nested
    @DisplayName("一、search_products（一次最多 10 个 × 每列顶格）")
    class SearchProducts {

        @Test
        @DisplayName("★ 10 个顶格商品 → 正文仍在预算内")
        void worstCaseStaysWithinBudget() {
            String[] numbers = createWorstCaseProducts("bg搜索预算", 10);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("keyword", "bg搜索预算");
            args.put("limit", 10);
            McpToolResult result = call(searchProductsTool, args);

            // ★ 先证明夹具真的够重：10 个全都渲染出来了。
            //   没有这一条的话，「正文 < 4000」在「只返回了 1 个商品」时也成立
            assertThat(rows(result, "products"))
                    .as("夹具必须是满的 —— 否则这个用例会在无意中变得很轻")
                    .hasSize(10);
            // ★★ 实测值：3575 字（2026-09-25）。
            //   写成【区间】而不是只写「小于 4000」，是为了让正文的长短变化都看得见：
            //     变长 → 有人在正文里加了东西，离预算更近了
            //     变短 → 有人拿掉了某个字段（那是个需要知道的决定，不是自动通过）
            //   ⚠️ 这个数由夹具（schema 上限）和正文形状共同决定，改哪一边都会动
            assertThat(result.text().length())
                    .as("★ 最坏形状的实测字数 —— 正文形状变了就该来改这个数")
                    .isBetween(3400, 3800);

            assertWithinBudget(result, "search_products");

            // ★ 每个商品的名字都顶到 255 字，是正文长度里最大的一块 ——
            //   这里顺带证明它确实被渲染过（而不是被工具悄悄丢了）
            assertThat(result.text())
                    .as("★ 最后一个商品的编号也在正文里：说明截断没有发生在正文内部")
                    .contains(numbers[9]);
        }

        @Test
        @DisplayName("★★ 关键词只命中【一条】时的最坏形状：那个商品同样是顶格长的")
        void singleProductIsShort() {
            // ★ 这一条是上面那条的【对照】，用来把两种超标原因分开：
            //   顶爆预算可能是「条数太多」，也可能是「一个字段太长」。
            //   上面量的是前者，这一条量的是后者 —— 同一个夹具、同一个字长，
            //   只是命中的条数从 10 变成 1
            createWorstCaseProducts("bg搜索单个", 10);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("keyword", "bg搜索单个-3");
            McpToolResult result = call(searchProductsTool, args);

            assertThat(rows(result, "products"))
                    .as("★ 只命中一个 —— 名字里的「-序号」后缀就是为这一条加的")
                    .hasSize(1);
            assertWithinBudget(result, "search_products（单条顶格）");
        }
    }

    // ============================================================
    // 二、compare_prices
    // ============================================================

    @Nested
    @DisplayName("二、compare_prices（2 个顶格商品 + 同类目统计）")
    class ComparePrices {

        @Test
        @DisplayName("★ 两个顶格商品 + 整类顶格商品的价格分布 → 仍在预算内")
        void worstCaseStaysWithinBudget() {
            String[] numbers = createWorstCaseProducts("bg比价预算", 10);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("product_no", numbers[0]);
            args.put("compare_with", numbers[1]);
            McpToolResult result = call(comparePricesTool, args);

            assertThat(rows(result, "products"))
                    .as("两个比价对象都要在")
                    .hasSize(2);
            assertThat(result.text())
                    .as("★ 同类目价格分布也渲染了（那是本工具最长的一段）")
                    .contains("同类目参照");

            assertWithinBudget(result, "compare_prices");
        }
    }

    // ============================================================
    // 三、recommend_products（5 条 × 顶格 + 每条的理由）
    // ============================================================

    @Nested
    @DisplayName("三、recommend_products（5 条 × 每列顶格 + 逐条理由）")
    class RecommendProducts {

        @Test
        @DisplayName("★ 5 条顶格推荐（每条都带命中理由）→ 仍在预算内")
        void worstCaseStaysWithinBudget() {
            createWorstCaseProducts("bg推荐预算", 10);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("need", NEED_HIT);
            args.put("category", "bg推荐预算" + fill('类',
                    schemaMax("category") - "bg推荐预算".length()));
            args.put("top_n", 5);
            McpToolResult result = call(recommendProductsTool, args);

            assertThat(rows(result, "picks"))
                    .as("★ 必须是满 5 条 —— 理由那一段只在有命中时才渲染，"
                            + "没命中的话这个用例测的就不是最坏形状")
                    .hasSize(5);
            assertThat(result.text()).contains("为什么是它");

            assertWithinBudget(result, "recommend_products");
        }

        /**
         * ★★ <b>阶段 9.5 的最坏形状：这个用户还有偏好</b>。
         *
         * <p>偏好会让每条推荐多一句「你的历史：…」，正文尾部还要多两段说明。
         * 实测多出来的是<b>尾巴上的一截</b> —— 而超预算的症状正是
         * 「最后的推荐被静默截掉」，所以这一条必须单独测。
         *
         * <p>⚠️ 上面那条用例（{@code userId=1}，库里不存在的人）<b>覆盖不到</b>它：
         * 没有偏好时正文里一个「你的历史」都没有。这正是本类开头那句
         * 「夹具必须真的是最坏形状」的又一次应用。
         */
        @Test
        @DisplayName("★★ 带偏好时也在预算内（正文会长出一截）")
        void worstCaseWithAffinityStaysWithinBudget() {
            String keyword = "bg推荐偏好";
            createWorstCaseProducts(keyword, 10);

            // 和 createWorstCaseProducts 用【同一套】顶格类目/品牌 ——
            // 这样偏好的三条理由全都能命中，长出来的那截才是完整的
            String category = keyword + fill('类', schemaMax("category") - keyword.length());
            String brand = keyword + fill('牌', schemaMax("brand") - keyword.length());
            long user = userWithHistoryOf(category, brand);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("need", NEED_HIT);
            args.put("category", category);
            args.put("top_n", 5);
            McpToolResult result = recommendProductsTool.call(
                    McpArguments.of(args, recommendProductsTool.inputFields()),
                    new McpToolContext(user));

            assertThat(rows(result, "picks"))
                    .as("★ 先证明夹具够重：满 5 条，且偏好真的生效了")
                    .hasSize(5);
            assertThat(result.text())
                    .as("★ 没有这一条，这个用例测的是「无偏好」那个形状")
                    .contains("你的历史")
                    .contains("只用来在【已经匹配上的】商品之间分先后");

            // ★★ 实测值：2986 字（2026-09-25，带满三条偏好理由 + 组内细排那两句话）。
            //   比不带偏好的那条多约 250 字 —— 而多出来的全在【尾巴】上，
            //   超预算的症状正是「末尾的推荐被静默截掉」
            assertWithinBudget(result, "recommend_products（带偏好）");
        }
    }
}
