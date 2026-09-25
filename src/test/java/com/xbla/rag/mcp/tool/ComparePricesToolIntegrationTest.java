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
 * 「这个价贵不贵」工具（阶段 9.3）—— 真连 PostgreSQL。
 *
 * <h2>★★★ 它守的三件事</h2>
 *
 * <ol>
 *   <li><b>入口是商品编号，不是商品名。</b>
 *       {@code product.name} 上没有唯一约束（实测 200 个商品里 17 组、35 个重名），
 *       拿名字比价遇到重名就只能二选一 —— 而用户看到的是
 *       「华为 Magic mini 售价 4999」，他不知道我们挑的是<b>哪一个</b>。
 *       那是一个「答案正确、问题答错」的回答（ADR-072）</li>
 *   <li><b>「同类参照」给的是【分布】，不是几个具体商品。</b>
 *       给三个同类商品摆出来，模型会把它复述成「这几个也不错」——
 *       一份我们没打算给的推荐</li>
 *   <li><b>「查无此商品」是 {@code isError:false}。</b>
 *       标成 true 会让模型去为系统故障道歉，而它该说的是
 *       「核对一下商品编号」（ADR-056）</li>
 * </ol>
 *
 * <p>★ 每条测试都用一个<b>唯一类目</b>，因为同类目价格分布是按类目算的 ——
 * 不隔离的话，断言会被种子数据里的价格带偏。
 */
@SpringBootTest
@Transactional
@DisplayName("ComparePricesTool · 商品比价")
class ComparePricesToolIntegrationTest {

    /** 查不到的那个编号 —— 用一个不会和真实商品撞的形态 */
    private static final String NO_SUCH_NO = "P999999";

    @Autowired
    private ComparePricesTool tool;

    @Autowired
    private ProductService productService;

    private int seq;

    // ============================================================
    // 夹具
    // ============================================================

    private String product(String name, String category, String price, String originalPrice) {
        Product p = new Product();
        p.setProductNo("CPT-" + (++seq) + "-" + name);
        p.setName(name);
        p.setCategory(category);
        p.setBrand("比价测试品牌");
        p.setPrice(new BigDecimal(price));
        p.setOriginalPrice(originalPrice == null ? null : new BigDecimal(originalPrice));
        p.setStatus(1);
        p.setDeleted(0);
        productService.save(p);
        return p.getProductNo();
    }

    private String product(String name, String category, String price) {
        return product(name, category, price, null);
    }

    private McpToolResult call(String productNo, String compareWith) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("product_no", productNo);
        if (compareWith != null) {
            args.put("compare_with", compareWith);
        }
        // ★ 用 of() 而不是 unchecked()：这条路径会真的走一遍 schema 校验
        return tool.call(McpArguments.of(args, tool.inputFields()), new McpToolContext(1L));
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> statsOf(McpToolResult result) {
        return (Map<String, Object>) data(result).get("category_stats");
    }

    // ============================================================
    // 一、入口是编号
    // ============================================================

    @Nested
    @DisplayName("一、入口是商品编号")
    class ProductNoEntry {

        @Test
        @DisplayName("按编号查到 → 名字、编号、标价都在")
        void looksUpByProductNo() {
            String no = product("比价甲", "比价测试类目一", "1999.00");

            McpToolResult result = call(no, null);

            assertThat(result.isError()).isFalse();
            assertThat(result.text()).contains("比价甲").contains(no).contains("¥1999.00");
            assertThat(productsOf(result)).singleElement()
                    .satisfies(p -> assertThat(p.get("product_no")).isEqualTo(no));
        }

        @Test
        @DisplayName("★★ 两个【同名】商品：按编号查只返回那一个，另一个的编号不出现")
        void duplicateNamesStayApart() {
            String category = "比价重名测试类目";
            String first = product("比价重名款", category, "1500.00");
            String second = product("比价重名款", category, "2500.00");

            McpToolResult result = call(first, null);

            assertThat(productsOf(result)).singleElement()
                    .satisfies(p -> assertThat(p.get("product_no")).isEqualTo(first));
            assertThat(result.text())
                    .as("★★ 拿名字比价时这两个商品是不可分的 —— 而用户看到的是"
                            + "「比价重名款 售价 2500」，他不知道我们挑的是哪一个")
                    .contains(first)
                    .doesNotContain(second);
        }

        @Test
        @DisplayName("★★ 查无此编号 → isError 为 false（这是个答案，不是故障）")
        void unknownNumberIsAnAnswer() {
            McpToolResult result = call(NO_SUCH_NO, null);

            assertThat(result.isError())
                    .as("★★ 标成 true 会让模型说「系统出了点问题，请稍后再试」—— "
                            + "而重试一万次也变不出那个商品")
                    .isFalse();
            assertThat(productsOf(result)).isEmpty();
            assertThat(result.text())
                    .as("★ 要给出【下一步】：编号长什么样、手上只有名字时该先调谁")
                    .contains("没有找到编号为「" + NO_SUCH_NO + "」")
                    .contains("商品编号形如 P000012")
                    .contains("search_products");
        }
    }

    // ============================================================
    // 二、同类目价格分布
    // ============================================================

    @Nested
    @DisplayName("二、同类目价格分布（中位数那个数会被人引用）")
    class CategoryStats {

        @Test
        @DisplayName("★ 偶数个样本 → 中位数取中间两个的平均，不是中间偏左")
        void medianOfEvenSampleIsAveraged() {
            String category = "比价分布偶数类目";
            product("比价分布甲", category, "100.00");
            product("比价分布乙", category, "200.00");
            product("比价分布丙", category, "300.00");
            String no = product("比价分布丁", category, "1000.00");

            McpToolResult result = call(no, null);
            Map<String, Object> stats = statsOf(result);

            assertThat(stats).containsEntry("min", "100.00")
                    .containsEntry("max", "1000.00")
                    .containsEntry("sample_size", 4)
                    .containsEntry("category", category);
            assertThat(stats.get("median"))
                    .as("★ 取「中间偏左」（= 200）会让 200 个样本的中位数系统性偏低，"
                            + "而它是个会被引用的数")
                    .isEqualTo("250.00");
            assertThat(result.text())
                    .as("★ 写「取样」不写「在售」：样本有上限，"
                            + "「在售 200 个」在商品更多的类目上是一句关于平台的假话")
                    .contains("取样 4 个在售商品");
            assertThat(result.text())
                    .as("★★ 这句是给模型看的：它会忍不住把这几个数当成「同类推荐」"
                            + "（而这里只给了分布，一个商品都没给）")
                    .contains("不是推荐商品");
        }

        @Test
        @DisplayName("★ 反面对照：奇数个样本 → 取正中间那个")
        void medianOfOddSampleIsTheMiddleOne() {
            String category = "比价分布奇数类目";
            product("比价分布奇数甲", category, "100.00");
            String no = product("比价分布奇数乙", category, "200.00");
            product("比价分布奇数丙", category, "300.00");

            Map<String, Object> stats = statsOf(call(no, null));

            assertThat(stats)
                    .as("★ 没有这一条，上面那个「250.00」也可能只是"
                            + "「某种算法碰巧算出来的」")
                    .containsEntry("median", "200.00")
                    .containsEntry("sample_size", 3);
        }

        @Test
        @DisplayName("★ 商品没有类目信息 → 明说无法参照，且 category_stats 是 null")
        void blankCategoryHasNoStats() {
            String no = product("比价无类目", "", "1999.00");

            McpToolResult result = call(no, null);

            assertThat(statsOf(result)).isNull();
            assertThat(result.text()).contains("无法给出同类目价格参照");
        }
    }

    // ============================================================
    // 三、两个商品
    // ============================================================

    @Nested
    @DisplayName("三、两个商品（compare_with）")
    class CompareWith {

        @Test
        @DisplayName("★ 两个都在 → 陈述价格关系，并带上原价")
        void twoProductsShowTheRelation() {
            String cheaper = product("比价便宜款", "比价对比测试类目", "1500.00");
            String dearer = product("比价昂贵款", "比价对比测试类目", "2000.00", "2599.00");

            McpToolResult result = call(dearer, cheaper);

            assertThat(productsOf(result)).hasSize(2);
            assertThat(result.text())
                    .as("★ 只说两个数的大小关系，不给「哪个更值」的判断 —— "
                            + "那是 recommend_products 或模型自己的事")
                    .contains("两者的标价：")
                    .contains("便宜 ¥500")
                    .contains("¥1500.00 vs ¥2000.00");
            assertThat(result.text())
                    .as("★ 原价只有【大于】标价时才渲染 —— 不然「原价 1500」"
                            + "会被模型说成「省了 0 元」")
                    .contains("（原价 2599.00）");
        }

        @Test
        @DisplayName("★★ 第二个查不到 → 【不】让整个调用失败：第一个的标价仍然是个答案")
        void missingSecondKeepsTheFirst() {
            String no = product("比价孤款", "比价孤款测试类目", "1999.00");

            McpToolResult result = call(no, NO_SUCH_NO);

            assertThat(result.isError())
                    .as("★★ 判据是「工具有没有给出答案」—— 它给了（第一个商品的标价），"
                            + "所以不是错误（ADR-056）")
                    .isFalse();
            assertThat(productsOf(result)).hasSize(1);
            assertThat(result.text())
                    .contains("没有找到编号为「" + NO_SUCH_NO + "」")
                    .contains("上面那个商品的标价仍然有效")
                    .contains("¥1999.00");
        }
    }

    // ============================================================
    // 四、schema 契约
    // ============================================================

    @Nested
    @DisplayName("四、schema 契约")
    class Schema {

        @Test
        @DisplayName("★ 缺 product_no → 被 schema 校验拦下")
        void missingProductNoIsRejected() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("compare_with", "P000001");

            assertThat(catchThrowable(() -> tool.call(McpArguments.of(args, tool.inputFields()),
                    new McpToolContext(1L))))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("product_no");
        }

        @Test
        @DisplayName("★★ 模型自己塞一个 user_id → 报错（身份只能来自 McpToolContext）")
        void identityCannotBePassedAsAnArgument() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("product_no", "P000001");
            args.put("user_id", 999999);

            assertThat(catchThrowable(() -> tool.call(McpArguments.of(args, tool.inputFields()),
                    new McpToolContext(1L))))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("user_id");
        }
    }
}
