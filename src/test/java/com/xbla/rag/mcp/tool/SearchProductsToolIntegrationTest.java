package com.xbla.rag.mcp.tool;

import com.xbla.rag.entity.Product;
import com.xbla.rag.mcp.McpArguments;
import com.xbla.rag.mcp.McpToolException;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.McpToolResult;
import com.xbla.rag.service.ProductService;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 「找候选」工具（阶段 9.3）—— 真连 PostgreSQL。
 *
 * <h2>★ 数据全部自己造，而且每条测试都带一个【唯一类目】</h2>
 *
 * <p>种子库里 200 个真实商品的 {@code suitable_for} 里就写着「适合送长辈」，
 * 而本工具的匹配面包含那个字段 —— 不加类目过滤的话，
 * 「命中 1 个」这类断言会被种子数据打穿（多命中几十个），
 * 症状是测试变成随机的。
 *
 * <h2>★★★ 它守的三件事</h2>
 *
 * <ol>
 *   <li><b>匹配面是三个字段</b>（名字 / 卖点 / 适用人群），
 *       这是它和 {@code query_inventory} 的分界线 ——
 *       那条线由 ADR-071 划出，见 {@link SearchProductsTool} 的类注释</li>
 *   <li><b>正文里的三笔账</b>：说清「这不是全部」、说破「这个顺序没有含义」、
 *       永远带 {@code product_no}。少了任何一笔，模型都会把一份未排序的候选
 *       <b>复述成一份推荐</b>，而回答读起来完全正常</li>
 *   <li><b>「还有更多」这句话必须在【真的有更多】时才说。</b>
 *       ★ 这条是补测试时发现并修掉的：当时判据写成了
 *       {@code returned.size() == take}（取满了就说还有更多），
 *       而真相在上一行的 {@code matched.size() > take} 里 ——
 *       于是「刚好 5 个」会收到一句「可能还有更多」，
 *       而「其实有 40 个」会收到一句「共 5 个」。
 *       两句话都错，且都不会报错。</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@DisplayName("SearchProductsTool · 找候选")
class SearchProductsToolIntegrationTest {

    /** ★ 唯一类目 —— 保证断言只看到本测试造的数据 */
    private static final String CATEGORY = "搜索测试类目";

    @Autowired
    private SearchProductsTool tool;

    @Autowired
    private ProductService productService;

    private int seq;

    // ============================================================
    // 夹具
    // ============================================================

    /** 造一个商品，返回它的商品编号 */
    private String product(String name, String sellingPoints, String suitableFor) {
        return product(name, "搜索测试品牌", new BigDecimal("1999.00"),
                sellingPoints, suitableFor);
    }

    private String product(String name, String brand, BigDecimal price,
                           String sellingPoints, String suitableFor) {
        Product p = new Product();
        p.setProductNo("SPT-" + (++seq) + "-" + name);
        p.setName(name);
        p.setCategory(CATEGORY);
        p.setBrand(brand);
        p.setPrice(price);
        p.setSellingPoints(sellingPoints);
        p.setSuitableFor(suitableFor);
        p.setStatus(1);
        p.setDeleted(0);
        productService.save(p);
        return p.getProductNo();
    }

    /** 造 n 个名字里都含同一个关键词的商品（★ 名字带序号，好定位） */
    private void products(String keyword, int count) {
        for (int i = 0; i < count; i++) {
            product(keyword + "-" + i, null, null);
        }
    }

    private McpToolResult call(Map<String, Object> args) {
        // ★ 用 of() 而不是 unchecked()：这条路径会真的走一遍 schema 校验
        return tool.call(McpArguments.of(args, tool.inputFields()), new McpToolContext(1L));
    }

    /** 只带关键词的最小调用 */
    private McpToolResult search(String keyword) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", keyword);
        args.put("category", CATEGORY);
        return call(args);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(McpToolResult result) {
        assertThat(result.data()).as("结构化数据不该为空").isNotNull();
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> productsOf(McpToolResult result) {
        return (List<Map<String, Object>>) data(result).get("products");
    }

    private static List<String> namesOf(McpToolResult result) {
        return productsOf(result).stream().map(p -> (String) p.get("name")).toList();
    }

    // ============================================================
    // 一、★★ 匹配面是【三个字段】
    // ============================================================

    @Nested
    @DisplayName("一、★★ 匹配面是三个字段（它和 query_inventory 的分界线）")
    class MatchScope {

        @Test
        @DisplayName("★ 词只在【适用人群】里（商品名完全不含它）→ 找得到")
        void matchesSuitableFor() {
            product("无名款甲", null, "适合送长辈、商务人士");

            McpToolResult result = search("送长辈");

            assertThat(namesOf(result)).containsExactly("无名款甲");
            assertThat(result.text())
                    .as("★ 用户说的是【描述】而不是商品名 —— "
                            + "只匹配名字的工具在这里会一无所获（那正是 ADR-071 那条线）")
                    .contains("无名款甲");
        }

        @Test
        @DisplayName("★ 词只在【卖点】里 → 同样找得到")
        void matchesSellingPoints() {
            product("无名款乙", "续航长达 18 小时", null);

            McpToolResult result = search("续航长");

            assertThat(namesOf(result)).containsExactly("无名款乙");
        }

        @Test
        @DisplayName("★★ 反面对照：三个字段都没有这个词 → 一个都不返回")
        void noMatchReturnsNothing() {
            // ★ 先造一个【存在】的商品。没有它的话，
            //   「一个都不返回」也可能只是因为库里本来就没有东西
            product("无名款丙", "续航长达 18 小时", "适合送长辈");

            McpToolResult result = search("完全不存在的词XYZ");

            assertThat(result.isError()).as("「没找到」是个答案，不是错误").isFalse();
            assertThat(productsOf(result))
                    .as("★★ 命中了但关键词不对 —— 这时返回任何东西都是错的："
                            + "一份按 id 排的随机候选，看起来像答案（ADR-071 的原始教训）")
                    .isEmpty();
            assertThat(result.text()).doesNotContain("无名款丙");
        }
    }

    // ============================================================
    // 二、★★★ 正文里的三笔账
    // ============================================================

    @Nested
    @DisplayName("二、★★★ 正文里的三笔账")
    class ThreeAccounts {

        @Test
        @DisplayName("★★ 第一笔账前半：命中 3 个、limit 5 → 说「共 3 个」，「还有更多」一个字都不能提")
        void doesNotClaimMoreWhenThereIsNoMore() {
            products("乙组搜索词", 3);

            McpToolResult result = call(withLimit("乙组搜索词", 5));

            assertThat(data(result).get("matched_count")).isEqualTo(3);
            assertThat(result.text()).contains("共 3 个");
            assertThat(result.text())
                    .as("★★ 取满了就说「可能还有更多」，是那句假话的现场："
                            + "判据曾经写的是 returned.size() == take，"
                            + "而真相在 matched.size() > take 里")
                    .doesNotContain("个以上")
                    .doesNotContain("只列出了前");
        }

        @Test
        @DisplayName("★★ 第一笔账后半：命中 8 个、limit 5 → 「6 个以上」+「只列出了前 5 个」")
        void saysMoreWhenThereIsMore() {
            products("丙组搜索词", 8);

            McpToolResult result = call(withLimit("丙组搜索词", 5));

            Map<String, Object> data = data(result);
            assertThat(data.get("returned_count")).isEqualTo(5);
            assertThat(data.get("matched_count"))
                    .as("★ 多跑一条的意义就在这里：它是【下界】（5+1），"
                            + "而不是「我们只看见了 5 个就当成全部」")
                    .isEqualTo(6);
            assertThat(result.text())
                    .as("★★ 两个数必须【不相等】，模型才知道手上不是全部。"
                            + "写成 5 的话，用户问「就这几个吗」它会答「是的」")
                    .contains("6 个以上")
                    .contains("只列出了前 5 个");
        }

        @Test
        @DisplayName("★★ 第二笔账：顺序是编号升序（可复现），且正文说破「和匹配度无关」")
        void orderIsByIdAndSaysSo() {
            String first = product("庚组搜索词-甲", null, null);
            String middle = product("庚组搜索词-乙", null, null);
            String last = product("庚组搜索词-丙", null, null);

            McpToolResult result = search("庚组搜索词");

            assertThat(productsOf(result)).extracting(p -> p.get("product_no"))
                    .as("★ 顺序是 id 升序 —— 唯一的原因是它可复现、能 diff。"
                            + "按「匹配度」排的话，同一份数据两次调用的顺序都可能不同")
                    .containsExactly(first, middle, last);
            assertThat(result.text())
                    .as("★★ 这句话是写给【模型】看的。它才是那个转述的人 —— "
                            + "少了它，「第一个」会被当成「最推荐的」")
                    .contains("和匹配度无关")
                    .contains("不代表推荐");
        }

        @Test
        @DisplayName("★ 第三笔账：每个商品都带商品编号（正文和结构化数据都是）")
        void everyProductCarriesProductNo() {
            String a = product("辛组搜索词-甲", null, null);

            McpToolResult result = search("辛组搜索词");

            assertThat(productsOf(result)).allSatisfy(row ->
                    assertThat(row).containsKey("product_no"));
            assertThat(result.text())
                    .as("★ 模型下一步要拿这个编号去调 compare_prices / query_inventory —— "
                            + "正文里没有它，那两跳就接不上")
                    .contains(a);
        }

        private Map<String, Object> withLimit(String keyword, int limit) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("keyword", keyword);
            args.put("category", CATEGORY);
            args.put("limit", limit);
            return args;
        }
    }

    // ============================================================
    // 三、limit 的两端夹紧
    // ============================================================

    @Nested
    @DisplayName("三、limit 的两端夹紧（夹，不是报错）")
    class LimitClamp {

        @Test
        @DisplayName("★ limit=100 → 只给 10 个（工具自己的上限），并如实说还有更多")
        void clampsDownToTen() {
            products("壬组搜索词", 12);

            McpToolResult result = call(withLimit("壬组搜索词", 100));

            assertThat(productsOf(result))
                    .as("★ 报错会让模型把这一轮浪费在改参数上；"
                            + "而 12 个商品全塞进 prompt 会把预算顶爆")
                    .hasSize(10);
            // ★ 12 个命中、上限 10 → 多取的那一条让下界是 11。
            //   ⚠️ 写「共 10 个」是假话（真相是 12 个），而这就是修掉的那处
            assertThat(result.text())
                    .contains("11 个以上")
                    .contains("只列出了前 10 个");
        }

        @Test
        @DisplayName("★ limit=0 → 至少给 1 个，不是空列表")
        void clampsUpToOne() {
            products("癸组搜索词", 2);

            McpToolResult result = call(withLimit("癸组搜索词", 0));

            assertThat(productsOf(result))
                    .as("★ 空列表会让模型说「没找到」—— 而库里有货。"
                            + "下限是 1，不是 0")
                    .hasSize(1);
        }

        @Test
        @DisplayName("★ 不给 limit → 默认 5 个")
        void defaultLimitIsFive() {
            products("子组搜索词", 6);

            McpToolResult result = search("子组搜索词");

            assertThat(productsOf(result)).hasSize(5);
        }

        private Map<String, Object> withLimit(String keyword, int limit) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("keyword", keyword);
            args.put("category", CATEGORY);
            args.put("limit", limit);
            return args;
        }
    }

    // ============================================================
    // 四、过滤条件
    // ============================================================

    @Nested
    @DisplayName("四、过滤条件（类目 / 品牌 / 价格）")
    class Filters {

        @Test
        @DisplayName("★ 三条条件都生效，且正文把生效的条件写成人话")
        void filtersApplyAndAreReported() {
            product("丑组搜索词-便宜", "搜索测试品牌A", new BigDecimal("1000.00"), null, null);
            product("丑组搜索词-中档", "搜索测试品牌B", new BigDecimal("5000.00"), null, null);
            product("丑组搜索词-贵", "搜索测试品牌A", new BigDecimal("9000.00"), null, null);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("keyword", "丑组搜索词");
            args.put("category", CATEGORY);
            args.put("brand", "搜索测试品牌A");
            args.put("max_price", 2000);
            McpToolResult result = call(args);

            assertThat(namesOf(result)).containsExactly("丑组搜索词-便宜");
            assertThat(result.text())
                    .as("★★ 模型得知道【为什么结果只有这么少】—— 它要向用户解释。"
                            + "不写的话它会说「只有这一款」（而其实是它自己加了条件）")
                    .contains("品牌=搜索测试品牌A")
                    .contains("≤2000元");
        }

        @Test
        @DisplayName("★★ 过滤后一无所获 → 说清加了什么条件，并且【不给】任何候选")
        void emptyAfterFilterSaysWhy() {
            product("寅组搜索词-甲", "搜索测试品牌A", new BigDecimal("1000.00"), null, null);

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("keyword", "寅组搜索词");
            args.put("category", CATEGORY);
            args.put("max_price", 1);
            McpToolResult result = call(args);

            assertThat(productsOf(result)).isEmpty();
            assertThat(result.text())
                    .as("★ 条件必须回显 —— 用户说的「500 块以内」和商品真实价格区间"
                            + "是两个数，模型要向他说清是哪一个把结果筛没了")
                    .contains("已经加了这些条件")
                    .contains("≤1元");
            assertThat(result.text())
                    .as("★ 但要告诉模型下一步做什么，以及【不要】自己换个词再查 —— "
                            + "那会返回一份和用户问的无关的结果")
                    .contains("请让用户换个说法")
                    .contains("不要自己换一个关键词再查一次");
            assertThat(result.text()).doesNotContain("寅组搜索词-甲");
        }
    }

    // ============================================================
    // 五、schema 契约
    // ============================================================

    @Nested
    @DisplayName("五、schema 契约")
    class Schema {

        @Test
        @DisplayName("★ 缺 keyword → 被 schema 校验拦下，而不是拿 null 去查库")
        void missingKeywordIsRejected() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("category", CATEGORY);

            assertThat(catchThrowable(() -> call(args)))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("keyword");
        }

        @Test
        @DisplayName("★★ 模型自己塞一个 user_id → 报错（身份只能来自 McpToolContext）")
        void identityCannotBePassedAsAnArgument() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("keyword", "x");
            args.put("user_id", 999999);

            assertThat(catchThrowable(() -> call(args)))
                    .as("★★ 忽略它比接受它更危险：模型会以为自己猜对了，下次继续猜 —— "
                            + "而这条调用链看起来完全正常（ADR-054）")
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("user_id");
        }
    }
}
