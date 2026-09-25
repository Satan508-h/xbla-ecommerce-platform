package com.xbla.rag.mcp.tool;

import com.xbla.rag.entity.Product;
import com.xbla.rag.mcp.McpArguments;
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

/**
 * 「按需求挑几个」工具（阶段 9.3）—— 真连 PostgreSQL。
 *
 * <h2>★★ 为什么每条测试都必须带一个【唯一类目】</h2>
 *
 * <p>这个工具的候选池是「在售商品按 id 升序取前 {@code MAX_CANDIDATES}(200) 个」，
 * 而种子库里正好有 200 个商品 —— 它们<b>排在我们造的数据前面</b>。
 * 不过滤类目的话，本测试造的夹具根本进不了候选池，
 * 于是每条断言都会走「没匹配上」那条路，而看起来还挺合理。
 * ★ 这也是这个工具在真实环境里的行为：商品表长到 200 行以上之后，
 * 「候选池只有前 200 个」这件事就开始影响结果了 —— 正文里会写明候选池多大。
 *
 * <h2>★★★ 它守的三件事</h2>
 *
 * <ol>
 *   <li><b>排序依据是那三个权重</b>（适用人群 3 &gt; 卖点 2 &gt; 商品名 1）。
 *       这是全项目唯一一处「哪个更适合」的实现，而它<b>没有</b>用销量、评分、热度 ——
 *       因为本项目没有那些数据。假造一个排序依据比诚实地说
 *       「我按字面重合度排的」危险得多</li>
 *   <li><b>一个都匹配不上时【不给列表】。</b>给一份按编号排的候选，
 *       用户会收到「推荐：星辰X1、华为 Magic mini…」而它们和「送长辈」毫无关系</li>
 *   <li><b>两种「没有」要分开说</b>：「候选池里没有这类商品」和
 *       「商品资料的描述里没有这个词」—— 混起来说，用户会以为平台真没有他要的东西，然后走掉</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@DisplayName("RecommendProductsTool · 按需求挑几个")
class RecommendProductsToolIntegrationTest {

    /** ★ 打分用的需求词。命中它的商品一定会被排上来 */
    private static final String NEED = "送长辈";

    @Autowired
    private RecommendProductsTool tool;

    @Autowired
    private ProductService productService;

    private int seq;

    // ============================================================
    // 夹具
    // ============================================================

    private String product(String category, String name, String sellingPoints, String suitableFor) {
        Product p = new Product();
        p.setProductNo("RPT-" + (++seq) + "-" + name);
        p.setName(name);
        p.setCategory(category);
        p.setBrand("推荐测试品牌");
        p.setPrice(new BigDecimal("1999.00"));
        p.setSellingPoints(sellingPoints);
        p.setSuitableFor(suitableFor);
        p.setStatus(1);
        p.setDeleted(0);
        productService.save(p);
        return p.getProductNo();
    }

    private McpToolResult call(String need, String category, Integer topN) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("need", need);
        if (category != null) {
            args.put("category", category);
        }
        if (topN != null) {
            args.put("top_n", topN);
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
    private static List<Map<String, Object>> picksOf(McpToolResult result) {
        return (List<Map<String, Object>>) data(result).get("picks");
    }

    private static List<String> namesOf(McpToolResult result) {
        return picksOf(result).stream().map(p -> (String) p.get("name")).toList();
    }

    // ============================================================
    // 一、★★★ 排序依据
    // ============================================================

    @Nested
    @DisplayName("一、★★★ 排序依据是那三个权重")
    class Ranking {

        @Test
        @DisplayName("★★ 三个商品各命中一个字段 → 顺序就是权重本身（3 > 2 > 1）")
        void threeFieldsThreeWeights() {
            String category = "推荐排序测试类目";
            // 甲只命中【适用人群】→ 3 分
            product(category, "推荐排序甲", null, "适合" + NEED);
            // 乙只命中【卖点】→ 2 分
            product(category, "推荐排序乙", NEED + "优选好物", null);
            // 丙只命中【商品名】→ 1 分
            product(category, NEED + "礼盒丙", null, null);

            McpToolResult result = call(NEED, category, null);

            assertThat(namesOf(result))
                    .as("★★ 把任意两个权重对调，这个顺序就会变 —— "
                            + "所以这一条钉住的是权重本身，不是「排了个序」")
                    .containsExactly("推荐排序甲", "推荐排序乙", NEED + "礼盒丙");
            assertThat(picksOf(result)).extracting(p -> p.get("score"))
                    .containsExactly(3, 2, 1);
        }

        @Test
        @DisplayName("★★ 得 0 分的商品不进结果 —— 也不计入 matched_count")
        void zeroScoreIsExcluded() {
            String category = "推荐零分测试类目";
            product(category, "推荐零分甲", null, "适合" + NEED);
            product(category, "推荐零分丁", "测试乙卖点", "适合甲用");

            McpToolResult result = call(NEED, category, null);

            assertThat(namesOf(result)).containsExactly("推荐零分甲");
            assertThat(data(result).get("matched_count"))
                    .as("★ 候选池是 2 个，命中的只有 1 个 —— 两个数不一样，"
                            + "模型才知道「其余的不是被排下去了，是压根不相干」")
                    .isEqualTo(1);
            assertThat(data(result).get("candidate_count")).isEqualTo(2);
        }

        @Test
        @DisplayName("★ 同分时按商品编号升序（确定的次序），且正文说破这一点")
        void tiesAreBrokenByIdAndSaidOutLoud() {
            String category = "推荐同分测试类目";
            String first = product(category, "推荐同分戊", null, "适合" + NEED);
            String second = product(category, "推荐同分己", null, "适合" + NEED);

            McpToolResult result = call(NEED, category, 2);

            assertThat(picksOf(result)).extracting(p -> p.get("product_no"))
                    .as("★ 同分必须有一个确定的次序，否则「换个时间问，排出来的顺序不一样」"
                            + "（而两条都是一样的解释，用户会以为平台在随机推荐）")
                    .containsExactly(first, second);
            assertThat(result.text())
                    .as("★★ 不说的话，模型会把「第 3 名排在前面」当成「它更好」")
                    .contains("得分相同")
                    .contains("不代表谁更好");
        }
    }

    // ============================================================
    // 二、★★★ 一个都匹配不上
    // ============================================================

    @Nested
    @DisplayName("二、★★★ 一个都匹配不上时不给列表")
    class NoMatch {

        /** ★ 夹具里没有一个字和 {@code "毫无关联"} 连着重合 */
        private void threeUnrelatedProducts(String category) {
            product(category, "推荐测试庚", "测试乙卖点", "适合甲用");
            product(category, "推荐测试辛", "测试丙卖点", "适合乙用");
            product(category, "推荐测试壬", "测试丁卖点", "适合丙用");
        }

        @Test
        @DisplayName("★★★ 匹配不上 → 说没匹配上，且【一条都不列】")
        void givesNoListAtAll() {
            String category = "推荐无匹配测试类目";
            threeUnrelatedProducts(category);

            McpToolResult result = call("毫无关联", category, null);

            assertThat(result.isError()).as("「没匹配上」是个答案，不是错误").isFalse();
            assertThat(picksOf(result)).isEmpty();
            assertThat(data(result).get("matched_count")).isEqualTo(0);
            assertThat(data(result).get("candidate_count"))
                    .as("★ 候选池是满的 —— 所以这不是「没有商品」，是「匹配不上」")
                    .isEqualTo(3);
            assertThat(result.text())
                    .as("★★★ 列表的形状是这个工具最危险的东西：一份按编号排的候选，"
                            + "用户会把它读成「平台给我推荐的这三个」。"
                            + "宁可说「没匹配上」——它至少没有撒谎")
                    .doesNotContain("1. 【")
                    .doesNotContain("推荐测试庚");
            assertThat(result.text())
                    .as("★★ 两种「没有」必须分开说：不是「平台没有这类商品」，"
                            + "而是「商品的资料里没有这样描述」。混起来说，"
                            + "用户会以为平台真没有他要的东西，然后走掉")
                    .contains("不等于")
                    .contains("平台没有这类商品");
        }

        @Test
        @DisplayName("★★ 反面对照：同一个夹具、换一个匹配得上的 need → 有结果")
        void sameFixtureWithAMatchingNeedReturnsPicks() {
            String category = "推荐无匹配对照类目";
            threeUnrelatedProducts(category);
            // ★ 只多加了这一个：它和 other 三个的唯一差别是描述里含需求词
            product(category, "推荐测试癸", null, "适合" + NEED);

            McpToolResult result = call(NEED, category, null);

            assertThat(namesOf(result))
                    .as("★ 没有这一条，上面那条「不给列表」可能只是因为"
                            + "「这个工具从来不返回东西」而通过")
                    .containsExactly("推荐测试癸");
        }
    }

    // ============================================================
    // 三、边界
    // ============================================================

    @Nested
    @DisplayName("三、边界（重合门槛 / top_n / 两种空）")
    class Boundary {

        @Test
        @DisplayName("★★ 只重合 1 个字 → 不算命中（MIN_HIT_CHARS 的端到端证据）")
        void oneCharacterOverlapIsNotAHit() {
            String category = "推荐单字测试类目";
            // ★ 「长送辈」和「送长辈」的字全都对得上，但【没有一个 2 字的连续段】——
            //   判据写成「按字拆开看有没有」的话，这里会得 3 分
            product(category, "推荐单字子", null, "长送辈");

            McpToolResult result = call(NEED, category, null);

            assertThat(picksOf(result)).isEmpty();
            assertThat(result.text()).contains("没有任何一个的描述和");
            // ★ 对照：纯函数那一侧的同一条 —— 两处必须说的是同一件事
            assertThat(RecommendProductsTool.longestCommonSubstring(NEED, "长送辈")).hasSize(1);
        }

        @Test
        @DisplayName("★ top_n 的两端都夹住：要 99 个也只给 5 个，要 0 个也给 1 个")
        void topNIsClamped() {
            String category = "推荐条数测试类目";
            for (int i = 0; i < 6; i++) {
                product(category, "推荐条数" + i, null, "适合" + NEED);
            }

            assertThat(picksOf(call(NEED, category, 99))).hasSize(5);
            assertThat(picksOf(call(NEED, category, 0))).hasSize(1);
            assertThat(picksOf(call(NEED, category, null)))
                    .as("不给就用默认值 3")
                    .hasSize(3);
        }

        @Test
        @DisplayName("★★ 候选池本身是空的 → 和「匹配不上」【不是同一句话】")
        void emptyCandidatePoolSaysSomethingElse() {
            McpToolResult result = call(NEED, "这个类目一个商品都没有", null);

            assertThat(picksOf(result)).isEmpty();
            assertThat(result.text())
                    .as("★★ 用户听到「平台没有这类商品」会走掉；"
                            + "听到「描述里没有这个词」会换个说法。"
                            + "两句话对应两个不同的下一步")
                    .contains("没有任何在售商品满足这些条件")
                    .contains("不是匹配度的问题");
            assertThat(result.text()).doesNotContain("没有任何一个的描述和");
        }
    }

    // ============================================================
    // 四、理由与「说破」
    // ============================================================

    @Nested
    @DisplayName("四、每条推荐都必须带理由")
    class Reasons {

        @Test
        @DisplayName("★★ 正文逐条写「为什么是它」，结构化数据里也有一份")
        void everyPickCarriesItsReason() {
            String category = "推荐理由测试类目";
            product(category, "推荐理由甲", null, "适合" + NEED);

            McpToolResult result = call(NEED, category, null);

            assertThat(result.text())
                    .as("★★ 不给模型「为什么是它」，它会自己编一个理由出来 —— "
                            + "而那个理由读起来比这个更有说服力")
                    .contains("为什么是它")
                    .contains("适用人群命中「" + NEED + "」");
            assertThat(picksOf(result)).singleElement().satisfies(pick ->
                    assertThat((List<?>) pick.get("reasons")).isNotEmpty());
        }

        @Test
        @DisplayName("★★★ 「不是销量、不是评分、也不是平台的推荐」必须写进正文")
        void rankingBasisIsStatedOutLoud() {
            String category = "推荐依据测试类目";
            product(category, "推荐依据甲", null, "适合" + NEED);

            String text = call(NEED, category, null).text();

            assertThat(text)
                    .as("★★★ 不写这一句，模型会把它转述成「最热门的」「最受欢迎的」—— "
                            + "而那是一个我们【没有数据】支撑的判断。"
                            + "假造一个排序依据，比诚实地说按字面重合度排的危险得多")
                    .contains("不是销量")
                    .contains("不是评分")
                    .contains("字面重合度");
        }
    }
}
