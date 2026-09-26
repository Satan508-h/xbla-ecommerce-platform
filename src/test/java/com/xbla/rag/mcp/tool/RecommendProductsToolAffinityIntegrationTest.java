package com.xbla.rag.mcp.tool;

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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「按需求挑几个」+ 用户偏好（阶段 9.5）—— 真连 PostgreSQL。
 *
 * <h2>★★★ 一、它盯的第一件事：偏好<b>只能重排，不能召回</b></h2>
 *
 * <p>这是本节最要紧的一条。偏好加分如果加在「过滤掉 0 分商品」<b>之前</b>，
 * 一个「买过 3 次戴森吸尘器」的人问「送长辈」会收到一台吸尘器 ——
 * 而那个列表读起来像一份很贴心的推荐，没有任何东西会报错。
 *
 * <p>所以 {@code givesNoListAtAll} 那一节是<b>单独</b>存在的：
 * 一个有强偏好的用户，问一个和偏好无关的需求，仍然必须得到「没匹配上」。
 *
 * <h2>★★ 二、每个用例都成对：同一个夹具，只换 userId</h2>
 *
 * <p>不加偏好分的话，同分商品按 id 升序 —— 所以夹具里那个
 * <b>「不该被偏好推上去」的商品一律给更小的 id</b>。
 * 于是「有偏好」和「没偏好」两种身份在同一批数据上必须给出<b>不同的顺序</b>。
 * ★ 少了这一对，断言可能只是因为「它本来就排在前面」而通过。
 *
 * <h2>⚠️ 三、为什么每条都要带一个唯一类目</h2>
 *
 * <p>候选池是「在售商品按 id 升序取前 {@code MAX_CANDIDATES}(200) 个」，
 * 而种子库里已经有近 200 个在售商品。不过滤类目的话，本类造的夹具
 * 根本进不了候选池，而每条断言都会走「没匹配上」那条路 —— 还看起来挺合理。
 */
@SpringBootTest
@Transactional
@DisplayName("RecommendProductsTool · 偏好重排（9.5）")
class RecommendProductsToolAffinityIntegrationTest {

    private static final String NEED = "送长辈";
    private static final String USER_CATEGORY = "偏好重排测试类目";
    private static final String USER_BRAND = "偏好重排测试品牌";

    @Autowired
    private RecommendProductsTool tool;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductSkuService productSkuService;

    @Autowired
    private AppUserService appUserService;

    @Autowired
    private OrdersService ordersService;

    @Autowired
    private OrderItemService orderItemService;

    private int seq;

    /** 种子库里不存在的 id —— 走的是「无偏好」那条路（现有 9 条用例用的也是它） */
    private static final long NO_PROFILE_USER = 1L;

    // ============================================================
    // 夹具
    // ============================================================

    /**
     * 造一个<b>有足够购买证据</b>的用户：3 笔有效订单，
     * 都买在同一个类目 / 同一个品牌 / 同一个价位。
     *
     * <p>★ 3 笔是 {@code xbla.agent.profile.min-orders} 的下限 ——
     * 少一笔这个人就「没有偏好」，而那样上面那些断言会全部失效。
     */
    private long userWithHistory() {
        long user = newUser();
        buy(user, USER_CATEGORY, USER_BRAND, "2000.00", 3);
        return user;
    }

    /** 一个真实存在、但没有订单的用户（有 id、没证据 ⇒ 偏好为 EMPTY） */
    private long newUser() {
        AppUser user = new AppUser();
        user.setUserNo("S-REC-" + System.nanoTime());
        user.setNickname("推荐偏好测试用户");
        user.setMemberLevel(2);
        appUserService.save(user);
        return user.getId();
    }

    /**
     * 让这位用户买 {@code times} 件 {@code category} 类目的 {@code brand} 牌商品，
     * 单价都是 {@code price}。
     *
     * <p>★ 抽出来是为了造「两类目、次数不同」和「同品牌多次」这两种历史 ——
     * 组内细排（修法 A）的第一、二层比的就是<b>次数</b>，
     * 而 {@link #userWithHistory()} 那种「一个类目 ×3」造不出次数差。
     */
    private void buy(long userId, String category, String brand, String price, int times) {
        for (int i = 0; i < times; i++) {
            Orders order = new Orders();
            order.setOrderNo("SOREC" + System.nanoTime() + "-" + (++seq));
            order.setUserId(userId);
            order.setTotalAmount(new BigDecimal(price));
            order.setDiscountAmount(BigDecimal.ZERO);
            order.setPayAmount(new BigDecimal(price));
            order.setStatus(40);
            order.setCreatedAt(OffsetDateTime.now());
            order.setDeleted(0);
            ordersService.save(order);

            Product bought = product(category, "偏好历史商品" + seq, brand, price, null, null);
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(bought.getId());
            item.setSkuId(skuIdOf(bought));
            item.setProductName(bought.getName());
            item.setSpecName("标准版");
            item.setPrice(new BigDecimal(price));
            item.setQuantity(1);
            item.setSubtotal(new BigDecimal(price));
            orderItemService.save(item);
        }
    }

    private Product product(String category, String name, String brand, String price,
                            String sellingPoints, String suitableFor) {
        Product p = new Product();
        p.setProductNo("RPA-" + (++seq) + "-" + name);
        p.setName(name);
        p.setCategory(category);
        p.setBrand(brand);
        p.setPrice(new BigDecimal(price));
        p.setSellingPoints(sellingPoints);
        p.setSuitableFor(suitableFor);
        p.setStatus(1);
        p.setDeleted(0);
        productService.save(p);

        ProductSku sku = new ProductSku();
        sku.setProductId(p.getId());
        sku.setSkuNo("RPASKU-" + p.getId());
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

    private McpToolResult call(long userId, String need, String category, Integer topN) {
        return call(userId, need, category, topN, null);
    }

    /**
     * @param budgetMax ★ 组内细排那几个用例靠它<b>把候选池压小</b>：
     *                  在售商品有 189 件，而候选是「按 id 升序取前 200」——
     *                  夹具的 id 最大，池子一满它们就<b>根本进不来</b>，
     *                  而症状是「一个都没匹配上」（看起来还挺像回事）。
     *                  加一个 ≤300 元的预算之后，全库只剩 2 件种子商品落进池子
     *                  （实测），于是夹具必定在里面。
     */
    private McpToolResult call(long userId, String need, String category, Integer topN,
                               Integer budgetMax) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("need", need);
        if (category != null) {
            args.put("category", category);
        }
        if (budgetMax != null) {
            args.put("budget_max", budgetMax);
        }
        if (topN != null) {
            args.put("top_n", topN);
        }
        return tool.call(McpArguments.of(args, tool.inputFields()), new McpToolContext(userId));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(McpToolResult result) {
        assertThat(result.data()).as("结构化数据不该为空").isNotNull();
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> picksOf(McpToolResult result) {
        assertThat(result.data()).as("结构化数据不该为空").isNotNull();
        return (List<Map<String, Object>>) ((Map<String, Object>) result.data()).get("picks");
    }

    private static List<String> namesOf(McpToolResult result) {
        return picksOf(result).stream().map(p -> (String) p.get("name")).toList();
    }

    private static Map<String, Object> pickOf(McpToolResult result, String name) {
        return picksOf(result).stream()
                .filter(p -> name.equals(p.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果里没有 " + name + "：" + namesOf(result)));
    }

    // ============================================================
    // 一、★★★ 偏好只能重排，不能召回
    // ============================================================

    @Nested
    @DisplayName("一、★★★ 偏好不能把不相关的商品拉进来")
    class NeverRecalls {

        @Test
        @DisplayName("★★★ 强偏好 + 一个匹配不上的需求 → 仍然「没匹配上」，一条都不列")
        void preferenceNeverAddsAMatch() {
            long user = userWithHistory();
            // 这两件商品和需求「毫无关联」一个字都不重合，但类目/品牌/价格
            // 全部命中这位用户的偏好 —— 满分 4 分
            product(USER_CATEGORY, "偏好诱饵甲", USER_BRAND, "2000.00", null, null);
            product(USER_CATEGORY, "偏好诱饵乙", USER_BRAND, "2000.00", null, null);

            McpToolResult result = call(user, "毫无关联", USER_CATEGORY, null);

            assertThat(namesOf(result))
                    .as("★★★ 偏好如果加在「过滤掉 0 分商品」之前，这里会返回两台"
                            + "和需求毫无关系的商品 —— 而那个列表读起来像一份很贴心的推荐，"
                            + "没有任何东西会报错")
                    .isEmpty();
            assertThat(result.text()).contains("没有任何一个的描述和");
        }

        @Test
        @DisplayName("★★ 反面对照：同一个用户、同一个类目，换成匹配得上的 need → 有结果")
        void sameUserWithAMatchingNeedGetsPicks() {
            long user = userWithHistory();
            product(USER_CATEGORY, "偏好诱饵丙", USER_BRAND, "2000.00", null, "适合" + NEED);

            McpToolResult result = call(user, NEED, USER_CATEGORY, null);

            assertThat(namesOf(result))
                    .as("★ 没有这一条，上面那条「一条都不列」可能只是因为"
                            + "「这个用户查什么都是空的」而通过")
                    .containsExactly("偏好诱饵丙");
        }
    }

    // ============================================================
    // 二、★★ 类目 / 品牌重排
    // ============================================================

    @Nested
    @DisplayName("二、★★ 同分时偏好说了算")
    class Rerank {

        @Test
        @DisplayName("★★★ 同一批商品：有偏好 → 顺序变；无偏好 → 按 id 升序")
        void affinityFlipsTheOrderOfTiedProducts() {
            long user = userWithHistory();
            // ★ 先造的 id 小 —— 没有偏好时它排前面，有偏好时它必须被挤下去
            product(USER_CATEGORY, "偏好外品牌甲", "别的品牌", "2000.00", null, "适合" + NEED);
            product(USER_CATEGORY, "偏好内品牌乙", USER_BRAND, "2000.00", null, "适合" + NEED);

            // ① 无偏好（种子库里不存在的 userId）—— 同分，按 id 升序
            assertThat(namesOf(call(NO_PROFILE_USER, NEED, USER_CATEGORY, 2)))
                    .as("★ 这一半是【对照】：没有它，下面那条可能只是因为"
                            + "「它本来就排在前面」而通过")
                    .containsExactly("偏好外品牌甲", "偏好内品牌乙");

            // ② 有偏好 —— 买过的那个品牌 +1，顺序反过来
            assertThat(namesOf(call(user, NEED, USER_CATEGORY, 2)))
                    .as("★★ 两件商品的字面重合得分完全一样（都命中适用人群 3 分），"
                            + "唯一不同的是品牌是不是这位用户买过的")
                    .containsExactly("偏好内品牌乙", "偏好外品牌甲");
        }

        @Test
        @DisplayName("★★ 两个分数都进结构化数据 —— 分得出「匹配得好」还是「老习惯」")
        void bothScoresAreInTheData() {
            long user = userWithHistory();
            product(USER_CATEGORY, "偏好双分甲", USER_BRAND, "2000.00", null, "适合" + NEED);

            Map<String, Object> pick = pickOf(call(user, NEED, USER_CATEGORY, null), "偏好双分甲");

            assertThat(pick.get("match_score")).as("字面重合：适用人群 3").isEqualTo(3);
            assertThat(pick.get("affinity_score"))
                    .as("偏好：常买类目 2 + 常买品牌 1 + 价格量级相符 1")
                    .isEqualTo(4);
            assertThat(pick.get("score")).as("★ 排序用的是总分").isEqualTo(7);
            assertThat((List<String>) pick.get("affinity_reasons"))
                    .as("★ 理由也要给出来 —— 不然模型会自己编一个")
                    .containsExactly("常买类目 +2", "常买品牌 +1", "价格量级相符 +1");
        }

        @Test
        @DisplayName("★★ 正文里「你的历史」只在真的有偏好时出现")
        void historyIsOnlyMentionedWhenItExists() {
            product(USER_CATEGORY, "偏好正文甲", USER_BRAND, "2000.00", null, "适合" + NEED);

            String without = call(NO_PROFILE_USER, NEED, USER_CATEGORY, null).text();
            String with = call(userWithHistory(), NEED, USER_CATEGORY, null).text();

            assertThat(without)
                    .as("★ 这个人没有偏好（userId=1 不存在）—— 正文里提「你的历史」"
                            + "会让模型说出一件不存在的事")
                    .doesNotContain("你的历史");
            assertThat(with).contains("你的历史：常买类目 +2");
            assertThat(with)
                    .as("★★ 必须说破偏好的作用范围：不说的话模型会以为"
                            + "「他买过的东西」也能被推上来")
                    .contains("只用来在【已经匹配上的】商品之间分先后");
        }
    }

    // ============================================================
    // 三、★ 价格量级
    // ============================================================

    @Nested
    @DisplayName("三、★ 价格量级相符")
    class PriceBand {

        @Test
        @DisplayName("★★ 同分同品牌的两件商品，只有价格在量级内的那一件拿得到分")
        void onlyTheComparablePriceGetsTheBonus() {
            long user = userWithHistory();
            // 用户的历史是 2000 元 ×3 → 量级带 = [2000×0.5, 2000×2] = [1000, 4000]
            // ★ 贵的那个先造（id 小），所以没有偏好分时它排前面
            product(USER_CATEGORY, "偏好天价甲", USER_BRAND, "99999.00", null, "适合" + NEED);
            product(USER_CATEGORY, "偏好常价乙", USER_BRAND, "2000.00", null, "适合" + NEED);

            assertThat(namesOf(call(NO_PROFILE_USER, NEED, USER_CATEGORY, 2)))
                    .as("★ 对照：无偏好时按 id 升序，天价那个在前面")
                    .containsExactly("偏好天价甲", "偏好常价乙");

            McpToolResult result = call(user, NEED, USER_CATEGORY, 2);

            assertThat(namesOf(result))
                    .as("★★ 两件的字面重合、类目、品牌三项完全一样，"
                            + "唯一差别是价格在不在这位用户的量级内")
                    .containsExactly("偏好常价乙", "偏好天价甲");
            assertThat(pickOf(result, "偏好天价甲").get("affinity_score"))
                    .as("★ 天价那件只拿得到类目 2 + 品牌 1 —— 价格这一项不给分")
                    .isEqualTo(3);
        }
    }

    // ============================================================
    // 四、★★★ 组内细排（修法 A）
    // ============================================================

    /**
     * 「加分只是粗筛、组内前三名由编号决定」那笔债（`docs/05` §9.12 ⑧ 的实测）：
     * user 8 问「送长辈」时 9 件同分里 <b>4 件是甲档，一件都没进前三</b>，
     * 只因为它们的编号更大。修法是「同分时按 类目频次 → 品牌频次 →
     * 价格距离 → 编号 再排一次」。
     *
     * <p>★ 这一节三个用例各钉一层（类目频次 / 品牌频次 / 价格距离），
     * 每一层都是<b>成对</b>的：先证明「没有偏好时它排在后面」，
     * 再证明「有偏好时它排到前面」。<b>两半必须都断言</b> ——
     * 只断言后一半的话，它可能只是因为「它本来就排在前面」而通过。
     */
    @Nested
    @DisplayName("四、★★★ 组内细排（同分时按频次 / 价格距离再排一次）")
    class TieBreak {

        /**
         * ★ 需求词必须是这批夹具独有的：它命中谁、谁才进结果。
         * 用「送长辈」那种通用词的话，种子库里 28 件商品会一起进来，
         * 而它们的编号更小 —— 断言于是变成了在测种子数据。
         */
        private static final String NEED_ONLY_MINE = "细排需求";

        @Test
        @DisplayName("★★★ 第一层：买过 3 件的那一类，排在买过 2 件的那一类前面")
        void moreOftenBoughtCategoryWins() {
            String catMore = "细排类目甲";
            String catLess = "细排类目乙";
            long user = newUser();
            buy(user, catMore, null, "250.00", 3);
            buy(user, catLess, null, "250.00", 2);

            // ★ 先造的 id 小 —— 没有偏好时它按编号排前面，细排之后必须被挤下去
            product(catLess, "乙款", null, "250.00", null, "适合" + NEED_ONLY_MINE);
            product(catMore, "甲款", null, "250.00", null, "适合" + NEED_ONLY_MINE);

            assertThat(namesOf(call(NO_PROFILE_USER, NEED_ONLY_MINE, null, 2, 300)))
                    .as("★ 对照：没有偏好时按编号升序 —— 少了这一半，"
                            + "下面那条可能只是因为「它本来就排在前面」而通过")
                    .containsExactly("乙款", "甲款");

            McpToolResult result = call(user, NEED_ONLY_MINE, null, 2, 300);

            assertThat(picksOf(result)).extracting(p -> p.get("score"))
                    .as("★ 先证明它们【真的同分】—— 不同分的话，这条测的是加分而不是细排")
                    .containsExactly(6, 6);
            assertThat(namesOf(result))
                    .as("★★ 两件的类目都在他买过的那几个里、价格都在量级带内 ⇒ "
                            + "唯一分得开它们的是「他买过这个类目几次」")
                    .containsExactly("甲款", "乙款");
        }

        @Test
        @DisplayName("★★★ 第二层：买过 3 件的那个品牌，排在买过 2 件的品牌前面")
        void moreOftenBoughtBrandWins() {
            String category = "细排品牌类目";
            String brandMore = "细排品牌甲";
            String brandLess = "细排品牌乙";
            long user = newUser();
            buy(user, category, brandMore, "250.00", 3);
            buy(user, category, brandLess, "250.00", 2);

            product(category, "牌乙款", brandLess, "250.00", null, "适合" + NEED_ONLY_MINE);
            product(category, "牌甲款", brandMore, "250.00", null, "适合" + NEED_ONLY_MINE);

            assertThat(namesOf(call(NO_PROFILE_USER, NEED_ONLY_MINE, null, 2, 300)))
                    .as("★ 对照：没有偏好时按编号升序")
                    .containsExactly("牌乙款", "牌甲款");
            assertThat(namesOf(call(user, NEED_ONLY_MINE, null, 2, 300)))
                    .as("★★ 两件同在一个类目（历史里各 5 件 ⇒ 类目那一层相同）、同价 ⇒ "
                            + "只剩品牌次数能分开它们")
                    .containsExactly("牌甲款", "牌乙款");
        }

        @Test
        @DisplayName("★★★ 第三层：价格离他平均成交价更近的排在前面")
        void closerToWhatHeUsuallyPaysWins() {
            String category = "细排价格类目";
            long user = newUser();
            buy(user, category, null, "250.00", 3);   // 平均成交价 250 ⇒ 量级带 [125, 500]

            // ★ 先造的 id 小：300 元那件离 250 有 50 元 —— 细排之后要排到后面
            product(category, "差价款", null, "300.00", null, "适合" + NEED_ONLY_MINE);
            product(category, "同价款", null, "250.00", null, "适合" + NEED_ONLY_MINE);

            assertThat(namesOf(call(NO_PROFILE_USER, NEED_ONLY_MINE, null, 2, 300)))
                    .as("★ 对照：没有偏好时按编号升序")
                    .containsExactly("差价款", "同价款");
            assertThat(namesOf(call(user, NEED_ONLY_MINE, null, 2, 300)))
                    .as("★★ 两件都在量级带内（价格那一分一样）、类目和品牌那一层也完全一样 ⇒ "
                            + "只有「离他平时花的钱多远」分得开")
                    .containsExactly("同价款", "差价款");
        }

        @Test
        @DisplayName("★★ 正文措辞跟着【谁真的决定了次序】走，不是抄一句固定的")
        void theTieNoticeFollowsWhoActuallyDecided() {
            String catMore = "细排措辞类目甲";
            String catLess = "细排措辞类目乙";
            long user = newUser();
            buy(user, catMore, null, "250.00", 3);
            buy(user, catLess, null, "250.00", 2);
            product(catLess, "措辞乙", null, "250.00", null, "适合" + NEED_ONLY_MINE);
            product(catMore, "措辞甲", null, "250.00", null, "适合" + NEED_ONLY_MINE);

            String withoutProfile = call(NO_PROFILE_USER, NEED_ONLY_MINE, null, 2, 300).text();
            String withProfile = call(user, NEED_ONLY_MINE, null, 2, 300).text();

            assertThat(withoutProfile)
                    .as("★ 没有偏好时这一组的先后【就是】编号决定的，措辞必须这么写")
                    .contains("只按商品编号排")
                    .doesNotContain("再排的");
            assertThat(withProfile)
                    .as("★★ 细排生效之后还说「只按商品编号排」，就是一句【关于这个排序的假话】"
                            + "—— 而模型会原样转述给用户")
                    .contains("再排的")
                    .doesNotContain("只按商品编号排");
        }

        @Test
        @DisplayName("★★ 并列组比 top_n 大时，正文说破「这几件只是并列里的前几个」")
        void topGroupLargerThanTopNSaysSo() {
            long user = userWithHistory();          // USER_CATEGORY ×3，单价 2000
            for (int i = 0; i < 4; i++) {
                // ★ 四件的类目/品牌/价格完全一样 ⇒ 三个细排键全部相等，
                //   先后【仍然】落到编号上（细排在这一组里没得排）
                product(USER_CATEGORY, "并列款" + i, USER_BRAND, "2000.00", null, "适合" + NEED);
            }

            McpToolResult result = call(user, NEED, USER_CATEGORY, 2);

            assertThat(dataOf(result).get("top_group_size"))
                    .as("★★ 这就是「前三名有多大程度是编号巧合」的那个分母 —— "
                            + "实测 user 8 问「送长辈」时它是 9，而里面 4 件是甲档，一件都没进前三")
                    .isEqualTo(4);
            assertThat(result.text())
                    .as("★ 不说的话，模型会把这 2 件讲成「最合适的 2 件」")
                    .contains("这一组一共有 4 件并列");
            assertThat(result.text())
                    .as("★★ 反面对照：三个细排键全相等 ⇒ 次序【确实】是编号决定的，"
                            + "这时候说「按你的历史再排的」同样是假话")
                    .contains("只按商品编号排");
        }
    }
}
