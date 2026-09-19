package com.xbla.rag.mcp.tool;

import com.xbla.rag.entity.Inventory;
import com.xbla.rag.entity.Product;
import com.xbla.rag.entity.ProductSku;
import com.xbla.rag.mcp.McpArguments;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.McpToolResult;
import com.xbla.rag.service.InventoryService;
import com.xbla.rag.service.ProductService;
import com.xbla.rag.service.ProductSkuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「查询商品库存」工具的集成测试（阶段 5.9）—— 真连 PostgreSQL。
 *
 * <h3>★ 数据全部是自己造的，不依赖种子数据</h3>
 *
 * <p>200 个真实商品的名字长得很像（华为 Magic mini / 华为 Mate Ultra / …），
 * 拿它们做断言等于把「种子的随机结果」写进了测试 ——
 * 而 {@code SeedDataFactory} 换个种子就会全红。
 *
 * <p>所以这里造一组名字带<b>唯一前缀</b>的商品，四种情况一次凑齐：
 * <pre>
 *   命中 1 个      → 精确名
 *   命中 4 个      → 只有前缀（★ 用来验「最多 3 个 + 还有更多」）
 *   规格过滤      → 同一商品下 128GB / 512GB 分开
 *   库存行缺失    → 有 SKU 却没有 inventory 行
 * </pre>
 *
 * <h3>★★ 本类真正要证明的那两件事</h3>
 *
 * <ol>
 *   <li><b>只匹配商品名，不匹配类目和品牌。</b>
 *       用户问「手机还有货吗」时，「命中 40 个然后返回前 3 个」比
 *       「没找到」<b>危险得多</b> —— 那 3 个是按 id 排序的随机结果，
 *       但它看起来像一份答案，而模型会把它当成答案。
 *       ⚠️ 这条<b>没法靠看返回值验证</b>：返回的东西看起来都正常，
 *       错的是「它本不该返回任何东西」</li>
 *   <li><b>「商品存在但没有这个规格」≠「商品不存在」。</b>
 *       两者都表现为「库存清单是空的」，但模型该说的话完全不同 ——
 *       前者是「这个颜色没有」，后者是「没这款商品」</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@DisplayName("QueryInventoryTool · 查询商品库存")
class QueryInventoryToolIntegrationTest {

    /** ★ 唯一前缀 —— 保证只会命中本测试造的数据，不会撞上 200 个真实商品 */
    private static final String PREFIX = "库存测试机X";

    @Autowired
    private QueryInventoryTool tool;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductSkuService productSkuService;

    @Autowired
    private InventoryService inventoryService;

    private Long alphaId;

    @BeforeEach
    void setUp() {
        alphaId = createProduct(PREFIX + "A", "库存测试类目", "库存测试品牌");
        createSku(alphaId, "128GB", 68);
        createSku(alphaId, "512GB", 215);

        // ★ 另外三个：只为「命中多个」那一节存在
        createProduct(PREFIX + "B", "库存测试类目", "库存测试品牌");
        createProduct(PREFIX + "C", "库存测试类目", "库存测试品牌");
        createProduct(PREFIX + "D", "库存测试类目", "库存测试品牌");
    }

    // ============================================================
    // 造数据
    // ============================================================

    /** 商品编号自增 —— 让【同名】商品能共存（真实数据里就有，见类注释） */
    private final java.util.concurrent.atomic.AtomicInteger seq =
            new java.util.concurrent.atomic.AtomicInteger();

    private long createProduct(String name, String category, String brand) {
        Product p = new Product();
        p.setProductNo("TEST-" + seq.incrementAndGet() + "-" + name);
        p.setName(name);
        p.setCategory(category);
        p.setBrand(brand);
        p.setPrice(new BigDecimal("1999.00"));
        p.setStatus(1);
        p.setDeleted(0);
        productService.save(p);
        return p.getId();
    }

    private long createSku(Long productId, String specName, Integer available) {
        ProductSku sku = new ProductSku();
        sku.setProductId(productId);
        sku.setSkuNo("TEST-" + productId + "-" + specName);
        sku.setSpecName(specName);
        sku.setPrice(new BigDecimal("2999.00"));
        sku.setStatus(1);
        sku.setDeleted(0);
        productSkuService.save(sku);

        // ★ available 为 null = 【故意不造库存行】，用来验「库存数据缺失」
        if (available != null) {
            Inventory inv = new Inventory();
            inv.setSkuId(sku.getId());
            inv.setTotalStock(available + 10);
            inv.setAvailableStock(available);
            inv.setLockedStock(10);
            inv.setWarehouse("华东仓-上海");
            inv.setVersion(0);
            inventoryService.save(inv);
        }
        return sku.getId();
    }

    private McpToolResult call(String productName, String spec) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("product_name", productName);
        if (spec != null) {
            args.put("spec", spec);
        }
        return callRaw(args);
    }

    private McpToolResult callRaw(Map<String, Object> args) {
        // ★ 用 of() 而不是 unchecked()：这条路径会真的走一遍 schema 校验
        return tool.call(McpArguments.of(args, tool.inputFields()), new McpToolContext(1L));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(McpToolResult result) {
        assertThat(result.data()).isNotNull();
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> products(McpToolResult result) {
        return (List<Map<String, Object>>) data(result).get("products");
    }

    // ============================================================
    // 一、基本形态
    // ============================================================

    @Test
    @DisplayName("精确的商品名 → 它的所有规格都列出来，各带自己的库存")
    void exactName() {
        McpToolResult result = call(PREFIX + "A", null);

        assertThat(result.isError()).as("查到了是个答案，不是错误").isFalse();
        assertThat(result.text()).contains(PREFIX + "A");
        assertThat(result.text()).contains("128GB", "512GB");
        assertThat(result.text()).contains("可售 68 件", "可售 215 件");
        assertThat(result.text()).contains("华东仓-上海");
        assertThat(data(result).get("matched_count")).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 部分名称 → 模糊命中，四个都算匹配")
    void partialName() {
        McpToolResult result = call(PREFIX, null);

        assertThat(data(result).get("matched_count"))
                .as("★ matched_count 报的是【匹配到的总数】，不是返回的个数")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("★ 命中超过上限 → 只返回 3 个，并说明还有更多")
    void capsReturnedProducts() {
        McpToolResult result = call(PREFIX, null);

        assertThat(products(result)).hasSize(3);
        assertThat(data(result).get("returned_count")).isEqualTo(3);
        assertThat(result.text()).contains("只显示了前 3 个，还有更多");
    }

    @Test
    @DisplayName("★ 正文里【没有】「合计库存」这个数 —— 各规格不能互相顶替")
    void noAggregateStock() {
        String text = call(PREFIX + "A", null).text();

        assertThat(text).contains("各规格之间不能互相顶替");
        assertThat(text)
                .as("★★ 合计会诱使模型说「还有 1249 件」—— 数字正确，"
                        + "但对「我要买月光银」这个真实问题完全错误")
                .doesNotContain("合计");
    }

    // ============================================================
    // 二、★★ 只匹配商品名
    // ============================================================

    @Nested
    @DisplayName("★★ 匹配面只有商品名 —— 品类和品牌不参与")
    class MatchScope {

        @Test
        @DisplayName("★★ 用【类目名】去查 → 必须「没找到」，不能返回一堆积 id 排在前面的商品")
        void categoryWordFindsNothing() {
            McpToolResult result = call("库存测试类目", null);

            assertThat(result.isError())
                    .as("★ 「没找到」是个答案（isError=false），不是错误")
                    .isFalse();
            assertThat(data(result).get("matched_count"))
                    .as("★★ 类目匹配上了的话这里会是 4 —— 而那 4 个是按 id 排的，"
                            + "和用户想问的东西毫无关系")
                    .isEqualTo(0);
            assertThat(result.text()).contains("没有找到");
        }

        @Test
        @DisplayName("★★ 用【品牌名】去查 → 同样必须「没找到」")
        void brandWordFindsNothing() {
            McpToolResult result = call("库存测试品牌", null);

            assertThat(data(result).get("matched_count")).isEqualTo(0);
        }

        @Test
        @DisplayName("★ 查不到时【不猜】—— 不给「你是不是想找…」的候选清单")
        void noGuessingCandidates() {
            String text = call("完全不存在的商品名XYZ", null).text();

            assertThat(text).contains("没有找到");
            assertThat(text)
                    .as("★ 一份猜出来的候选，模型会从里面挑一个报给用户 —— "
                            + "而用户问的是另一个东西。这和 ADR-044 是同一类风险")
                    .doesNotContain(PREFIX);
            assertThat(text)
                    .as("★ 但要告诉模型下一步该做什么")
                    .contains("请先问清楚具体是哪一款");
        }
    }

    // ============================================================
    // 三、★★ 规格过滤
    // ============================================================

    @Nested
    @DisplayName("★★ 「商品没有这个规格」和「没有这个商品」必须能区分")
    class SpecFilter {

        @Test
        @DisplayName("规格命中 → 只列那一个规格")
        void specMatches() {
            McpToolResult result = call(PREFIX + "A", "512GB");

            assertThat(result.text()).contains("512GB");
            assertThat(result.text())
                    .as("★ 过滤掉了 128GB")
                    .doesNotContain("128GB");
            assertThat(products(result)).hasSize(1);
        }

        @Test
        @DisplayName("★★ 商品存在但没有这个规格 → 明说「有 N 个规格，但其中没有包含 X 的」")
        void specMissesButProductExists() {
            McpToolResult result = call(PREFIX + "A", "1TB");

            assertThat(data(result).get("matched_count"))
                    .as("★★ 商品【是】找到了 —— 这和「商品不存在」是两件事")
                    .isEqualTo(1);
            assertThat(result.text()).contains("该商品有 2 个规格，但其中【没有】规格包含「1TB」的");
            assertThat(result.text())
                    .as("★ 还要把【现有规格】列出来，否则用户无从选择")
                    .contains("现有规格：", "128GB", "512GB");
            assertThat(result.text()).doesNotContain("没有找到名称为");
        }
    }

    // ============================================================
    // 四、无货 vs 库存缺失
    // ============================================================

    @Nested
    @DisplayName("★ 「没货」和「不知道有没有货」是两回事")
    class StockStates {

        @Test
        @DisplayName("★ 可售 0 → 「暂时无货」，并且说清是哪个规格")
        void outOfStock() {
            long id = createProduct(PREFIX + "EMPTY", "库存测试类目", "库存测试品牌");
            createSku(id, "月光银 512GB", 0);

            String text = call(PREFIX + "EMPTY", null).text();
            assertThat(text).contains("【暂时无货】");
            assertThat(text).contains("全部规格都无货");
        }

        @Test
        @DisplayName("★★ 正-反对照：没有库存行 ≠ 没货")
        void missingInventoryRow() {
            long id = createProduct(PREFIX + "NOROW", "库存测试类目", "库存测试品牌");
            createSku(id, "晨曦金 256GB", null);      // ★ 故意不造库存行

            String text = call(PREFIX + "NOROW", null).text();

            assertThat(text).contains("库存数据缺失");
            assertThat(text)
                    .as("★★ 兜成 0 的话模型会说「没货」—— 而事实是【我们不知道】。"
                            + "用户会因此放弃一个其实有货的商品")
                    .doesNotContain("暂时无货");
        }

        @Test
        @DisplayName("一部分规格有货、一部分没有 → 分开说")
        void mixedStock() {
            long id = createProduct(PREFIX + "MIX", "库存测试类目", "库存测试品牌");
            createSku(id, "樱语粉 128GB", 0);
            createSku(id, "远山蓝 128GB", 42);

            String text = call(PREFIX + "MIX", null).text();
            assertThat(text).contains("【暂时无货】", "可售 42 件");
            assertThat(text).contains("其中 1 个规格暂时无货");
            assertThat(text)
                    .as("★ 不是全部都没货，就不该说「全部规格都无货」")
                    .doesNotContain("全部规格都无货");
        }
    }

    // ============================================================
    // 五、★★ 重名商品
    // ============================================================

    @Nested
    @DisplayName("★★ 同名商品必须能被区分开")
    class DuplicateNames {

        /**
         * ★★ 实测：真实库里有 <b>17 组、共 35 个重名商品</b>
         * （{@code product.name} 上没有唯一约束，唯一的是 {@code product_no}）。
         *
         * <p>这个用例把它缩到最小：两个商品同名、各自的规格完全不同。
         * 断言的是「两块正文能被区分」——而区分靠的是标题里的<b>商品编号</b>。
         */
        @Test
        @DisplayName("★★ 两个同名商品 → 两个块，各自带不同的商品编号")
        void sameNameDifferentProducts() {
            String name = PREFIX + "重名款";
            long a = createProduct(name, "库存测试类目", "库存测试品牌");
            long b = createProduct(name, "库存测试类目", "库存测试品牌");
            createSku(a, "月光银 512GB", 11);
            createSku(b, "星空黑 1TB", 22);

            McpToolResult result = call(name, null);

            assertThat(data(result).get("matched_count"))
                    .as("★ 两个都匹配到了 —— 不能只返回第一个")
                    .isEqualTo(2);
            assertThat(result.text()).contains("可售 11 件", "可售 22 件");

            // ★★ 判据：正文里出现了【两个不同的】商品编号
            assertThat(products(result).stream()
                    .map(p -> p.get("product_no")).distinct().count())
                    .as("★★ product_no 是唯一标识 —— 不带它，模型会把两个同名商品"
                            + "当成同一个，说出「这款有 2 个规格」这种把两个商品"
                            + "合并起来的错话")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("★ 商品编号【总是】出现，不管重不重名 —— 正文形状不能随数据变")
        void productNoAlwaysPresent() {
            McpToolResult result = call(PREFIX + "A", null);

            // ★ 夹具的 product_no 形如 "TEST-1-库存测试机XA"
            assertThat(result.text()).contains("· TEST-");
            assertThat(products(result).get(0))
                    .as("结构化数据里也要有 —— 正文和 data 说的是同一件事")
                    .containsKey("product_no");
        }
    }

    // ============================================================
    // 六、schema 契约
    // ============================================================

    @Test
    @DisplayName("★ 缺 product_name → 被 schema 校验拦下，而不是拿 null 去查库")
    void missingRequiredArgument() {
        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> callRaw(Map.of())))
                .isInstanceOf(com.xbla.rag.mcp.McpToolException.class)
                .hasMessageContaining("product_name");
    }

    @Test
    @DisplayName("★ 模型多传一个参数 → 报错让它知道，不静默忽略")
    void unknownArgument() {
        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> callRaw(Map.of("product_name", "x", "user_id", 999))))
                .isInstanceOf(com.xbla.rag.mcp.McpToolException.class)
                .hasMessageContaining("user_id");
    }
}
