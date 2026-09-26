package com.xbla.rag.mcp.tool;

import com.xbla.rag.entity.Product;
import com.xbla.rag.mcp.McpArguments;
import com.xbla.rag.mcp.McpTool;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.McpToolResult;
import com.xbla.rag.mcp.ToolField;
import com.xbla.rag.rag.profile.UserAffinity;
import com.xbla.rag.rag.profile.UserAffinityProvider;
import com.xbla.rag.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「这几款哪个更适合送长辈」—— 阶段 9.3 的第三个新工具，
 * 也是<b>三个工具里唯一负责排序的那一个</b>。
 *
 * <h2>★★★ 一、它凭什么排序 —— 以及为什么不是「按销量」</h2>
 *
 * <p>{@link SearchProductsTool} 的类注释里写着三笔账，第三笔是
 * 「真正的排序交给 {@code recommend_products}」。这个类就是来还那笔账的：
 * 没有它，那两个工具返回的都只是<b>按商品编号排的列表</b>。
 *
 * <p>打分规则只有一条，而且刻意做得很笨：
 *
 * <pre>
 *   把 {@code need}（用户的描述）和商品的三个文本字段求【最长公共子串】：
 *     适用人群 suitable_for   命中 ≥2 字 → +3 分   ← 这个字段就是为这类问题存在的
 *     卖点     selling_points 命中 ≥2 字 → +2 分
 *     商品名   name           命中 ≥2 字 → +1 分
 * </pre>
 *
 * <p>★ <b>不用「按销量」「按评分」「按热度」</b>，因为本项目<b>没有那些数据</b>。
 * 假造一个排序依据，比诚实地说「我按字面重合度排的」危险得多 ——
 * 前者会让用户以为这是平台的专业推荐。
 *
 * <p>★★ 而且这条规则是<b>确定性的、可复算的、每条理由都写进正文的</b>。
 * 每一条推荐后面都跟着「为什么是它」，那正是「可解释」在工具层的含义。
 *
 * <h2>★★ 二、排序的第二层：这位用户自己的历史（阶段 9.5）</h2>
 *
 * <p>9.5 给排序加了<b>第二层</b>，来源是这位用户自己的订单：
 *
 * <pre>
 *   买过的类目     +2
 *   买过的品牌     +1
 *   价格量级相符   +1   （落在 [他买过的最低×0.5, 他买过的最高×2] 之内）
 * </pre>
 *
 * <p>★★★ <b>它只加在【已经匹配上】的商品之间</b>（{@code score > 0} 那一步之后）。
 * 这一条是本节最要紧的：偏好<b>永远不能把一件和需求无关的商品拉进结果</b>。
 * 加上去再过滤的话，一个「买过 3 次戴森吸尘器」的用户问「送长辈」，
 * 会收到一台吸尘器 —— 而它读起来像一份很贴心的推荐。
 *
 * <p>★ <b>会员等级不在上面那张表里，这是刻意的。</b>
 * {@code app_user.member_level} 是<b>用户级常数</b>：给每个商品都加同一个数
 * 不改变任何两个商品的先后。写一个「按会员等级排序」的加分项，
 * 效果是零，而正文里那句话会变成<b>一句假话</b>——
 * 模型会照着它转述成「按你的会员等级推荐了这几款」。
 * 它出现在<b>偏好块</b>里（{@code RagPromptBuilder.affinitySection}），
 * 那里它是一个事实；在这里它会是一个不生效的旋钮。
 *
 * <p>★★★ <b>但加分只是【粗筛】，它分不开同一组内的高下</b>（2026-09-25 实测）：
 * 收窄之后组内<b>仍然同分</b>（3~9 件），前三名由<b>商品编号</b>决定 ——
 * user 8 问「送长辈」时 9 件同分里有 <b>4 件是甲档，一件都没进前三</b>，
 * 只因为它们的编号更大。⇒ 所以还有<b>第三层：组内细排</b>（同分时依次比）：
 *
 * <pre>
 *   ① 买过这个类目的【次数】  降序  （「买过 3 件家电」 &gt; 「买过 2 台笔记本」）
 *   ② 买过这个品牌的【次数】  降序
 *   ③ 价格离他平均成交价的【距离】 升序
 *   ④ 商品编号                升序  ← 兜底，必须存在（否则次序不确定）
 * </pre>
 *
 * <p>★ ①②③ 用的是<b>同一份 {@code UserAffinity}</b>，没有新的事实来源。
 * 没有偏好（{@code UserAffinity.EMPTY}）时它们<b>全部相等</b>，排序于是退回
 * 「总分降序 + 编号升序」—— <b>与 9.4 逐字节相同</b>。这正是「基线 =
 * id 升序、没有任何语义」那个性质仍然成立的原因，<b>别把它弄丢</b>。
 *
 * <p>★★ 而正文也必须跟着改口：细排生效之后，「同分的只按编号排」就成了一句假话。
 * 说破的判据是「<b>这一组的先后到底是不是历史决定的</b>」——
 * 不是「有没有偏好」（有偏好但组内三项全相等时，仍然是编号说了算）。
 *
 * <p>⚠️ 它<b>修不了</b>「收窄本身推错方向」那种错（见 {@code docs/05} §9.12 ⑧）：
 * 细排只在<b>同一个同分组之内</b>分先后，而「谁进了这个组」是加分那一步决定的。
 *
 * <p>⚠️ 本工具的候选池上限 {@link #MAX_CANDIDATES} 意味着
 * <b>偏好只作用于池内商品</b>。真实电商里池子是截断过的，
 * 这是排序偏好的固有限制 —— 正文里的「候选池 N 个」就是这件事的凭据。
 *
 * <h2>★★★ 三、一个都匹配不上时，【不给列表】</h2>
 *
 * <p>这是本类最要紧的一条纪律。得分为 0 时有两种做法：
 *
 * <pre>
 *   给一份按编号排的列表          ❌ 用户会收到「推荐：星辰X1、华为 Magic mini…」
 *                                    而它们和「送长辈」毫无关系
 *   如实说「没匹配上」            ✅ 并把原因说清楚（字面重合度为 0）
 * </pre>
 *
 * <p>后者看起来「没帮上忙」，但它至少<b>没有撒谎</b>。同
 * {@link QueryInventoryTool} 那条「宁可回『没找到』，也不要回一份随机的候选」——
 * 这是本项目在工具层反复出现的那条线。
 *
 * <p>★ 而且要把两种「没有」分开说：
 * <blockquote>不是「平台没有这类商品」，而是「商品资料里没有这样描述」。</blockquote>
 * 混起来说，用户会以为平台真的没有他要的东西，然后走掉。
 *
 * <h2>四、候选池有上限</h2>
 *
 * <p>见 {@link #MAX_CANDIDATES}。不限的话，一个宽泛的 {@code need}
 * 会把整个商品表拉进内存算分 —— 而症状是「平时很快，某个问法一查就慢」。
 */
@Slf4j
@Component
public class RecommendProductsTool implements McpTool {

    /**
     * 参与打分的候选上限。
     *
     * <p>★ 真实电商的商品表是几百万行，而这个工具每次都要对候选取样打分。
     * 有上限时最坏情况是「只在前 N 个里挑」—— 一个可以被接受的偏差，
     * 而且正文里会写明它。
     */
    private static final int MAX_CANDIDATES = 200;

    private static final int DEFAULT_TOP_N = 3;
    private static final int MAX_TOP_N = 5;

    /**
     * 命中判定与展示的最短/最长公共子串。
     *
     * <p>★ 最短 2 而不是 1：单个汉字的偶然重合太多了（「的」「大」「好」），
     * 1 字命中会让几乎所有商品都得一分，排序退化成按编号。
     */
    private static final int MIN_HIT_CHARS = 2;
    private static final int MAX_REASON_CHARS = 6;

    /**
     * 偏好加分的三个权重（阶段 9.5）。
     *
     * <p>★ 类目 &gt; 品牌：实测每位用户买过 1~4 个类目（目录里一共 6 个），
     * 而品牌<b>重复率极低</b>（用户 15 买的 5 件品牌一个都不重）。
     * 所以「买过这个类目」比「买过这个品牌」是<b>更常成立</b>的信号，
     * 而少见的那一个给更小的分值 —— 反过来会让偶尔的品牌重合压过类目。
     *
     * <p>⚠️ 三个分值加起来是 <b>4</b>，也就是最多能越过
     * 一档字面重合（3 → 2）。这是刻意的上限：<b>偏好不该盖过「这句话问的是什么」</b>。
     */
    private static final int AFFINITY_CATEGORY_BONUS = 2;
    private static final int AFFINITY_BRAND_BONUS = 1;
    private static final int AFFINITY_PRICE_BONUS = 1;

    /**
     * 价格「量级相符」的容差：商品价落在
     * {@code [他买过的最低 × 0.5, 他买过的最高 × 2]} 之内算相符。
     *
     * <p>★★ 这个容差是<b>被数据逼出来的，不是调出来的</b>：实测每位用户
     * 的历史成交价跨度极大（用户 8：484 ~ 15065 元，30 倍）。
     * 用「均价 ±30%」那种紧的口径，会把他<b>真的买过</b>的两头都判成不相符 ——
     * 一个把用户自己买过的东西排除在外的规则，不可能是对的。
     *
     * <p>★ 所以这里只做「差不太多」这个量级的粗判，不做精算。
     * 它的分量（+1）也对应这个粗糙程度。
     */
    private static final BigDecimal PRICE_BAND_LOW_FACTOR = new BigDecimal("0.5");
    private static final BigDecimal PRICE_BAND_HIGH_FACTOR = new BigDecimal("2");

    private static final ToolField NEED = ToolField.requiredString(
            "need",
            "用户想要什么，用他自己话里的描述。形如「送长辈」「学生上网课」「续航长一点」。"
                    + "★ 直接把用户的说法搬过来，不要自己改写成「高性价比」这种抽象词 ——"
                    + "本工具是按字面重合度打分的，改写过就对不上了。");

    private static final ToolField CATEGORY = ToolField.optionalString(
            "category",
            "类目，可选。用户明确说了要哪一类（手机 / 笔记本电脑）时才传。");

    private static final ToolField BUDGET_MAX = ToolField.optionalInt(
            "budget_max",
            "预算上限（元），可选。用户说「XX 块以内」时就传这个数。"
                    + "★ 只给整数，不要带「元」字。");

    private static final ToolField TOP_N = ToolField.optionalInt(
            "top_n",
            "推荐几个，可选，默认 3，上限 5。用户说「给我看两个」时按他说的传。");

    private final ProductService productService;

    /**
     * 用户偏好（阶段 9.5）。
     *
     * <p>★ 身份来自 {@code McpToolContext}（ADR-054），<b>不是工具参数</b> ——
     * 本工具一个身份相关的字段都没有往 schema 里加。
     * 模型再怎么被诱导也换不了「这是谁的偏好」。
     */
    private final UserAffinityProvider userAffinityProvider;

    public RecommendProductsTool(ProductService productService,
                                 UserAffinityProvider userAffinityProvider) {
        this.productService = productService;
        this.userAffinityProvider = userAffinityProvider;
    }

    @Override
    public String name() {
        return "recommend_products";
    }

    @Override
    public String title() {
        return "按需求推荐商品";
    }

    @Override
    public String description() {
        return """
                按用户描述的需求，从在售商品里【挑几个并排序】，告诉模型为什么是这几个。

                什么时候用：用户在问「哪个适合我」——
                「这个适合送长辈吗」「学生买平板要注意什么」「5000 块以内哪个好」。

                什么时候【不要】用：
                - 用户只是想知道有哪些 → 那是 search_products（它只列候选，不排序）
                - 用户问某一款的具体价格或库存 → 那是 compare_prices / query_inventory
                - 用户问「买的时候要注意什么」这类通用建议 → 那是知识库（导购指南里有）

                ★ 本工具的排序依据有两层：① 【用户描述与商品卖点/适用人群的字面重合度】
                （主）；② 【这位用户自己的历史购买记录】（次，只在已经匹配上的商品之间
                分先后）。不是销量、不是评分、不是平台推荐 —— 正文里会写明这一点，
                转述给用户时不要把它说成「最热门的」「最受欢迎的」。
                ★ 第 ② 层只有在这位用户【有身份、且订单足够多】时才有；
                没有的时候正文里不会出现它，你也别提「根据你的购买记录」。
                ★ 转述第 ② 层时要说清它是「你以前买过这个类目/品牌」，
                不要升级成「你最喜欢」「你偏好的」。

                ★ 如果一个都匹配不上，本工具会如实说「没匹配上」而【不给列表】——
                那时候请让用户补充场景或预算，不要自己替它编一个推荐。
                """;
    }

    @Override
    public List<ToolField> inputFields() {
        return List.of(NEED, CATEGORY, BUDGET_MAX, TOP_N);
    }

    @Override
    public List<ToolField> outputFields() {
        return List.of(
                ToolField.requiredInt("candidate_count", "参与打分的候选商品数（已按类目/预算过滤）"),
                ToolField.requiredInt("matched_count", "得分大于 0 的商品数"),
                ToolField.requiredString("picks", "推荐结果（含理由，结构化数组，见 data.picks）"));
    }

    @Override
    public McpToolResult call(McpArguments args, McpToolContext context) {
        String need = NEED.requireString(args).trim();
        String category = trimmedOrNull(CATEGORY.optionalString(args));
        Integer budgetMax = BUDGET_MAX.optionalInt(args);
        Integer topNArg = TOP_N.optionalInt(args);
        int topN = topNArg == null ? DEFAULT_TOP_N : Math.max(1, Math.min(MAX_TOP_N, topNArg));

        // ── ① 取候选 ──
        var query = productService.lambdaQuery()
                .eq(Product::getStatus, 1)
                .eq(Product::getDeleted, 0);
        if (category != null) {
            query = query.eq(Product::getCategory, category);
        }
        if (budgetMax != null) {
            query = query.le(Product::getPrice, budgetMax);
        }
        List<Product> candidates = query.orderByAsc(Product::getId)
                .last("LIMIT " + MAX_CANDIDATES)
                .list();

        if (candidates.isEmpty()) {
            return McpToolResult.ok(emptyCandidatesText(category, budgetMax),
                    dataOf(0, 0, List.of(), 0));
        }

        // ── ② 打分 ──
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Product product : candidates) {
            scored.add(score(need, product));
        }

        // ★ 判据是【字面重合】那一分，不是总分 —— 此刻偏好还没贴上去（见第 ③ 步），
        //   而且就算贴了也不该用它：偏好不能让人进候选（类注释第二节）
        List<Scored> hits = scored.stream().filter(s -> s.matchScore() > 0).toList();
        if (hits.isEmpty()) {
            // ★★ 见类注释第三节：这里【不能】给一份列表
            return McpToolResult.ok(noMatchText(need, category, budgetMax, candidates.size()),
                    dataOf(candidates.size(), 0, List.of(), 0));
        }

        // ── ③ 偏好层（阶段 9.5）——★ 只在【已经匹配上】的商品之间加 ──
        //
        // ★★ 这一步放在 hits 过滤【之后】是本节最要紧的一条：偏好
        //   永远不能把一件和需求无关的商品拉进结果（见类注释第二节）。
        //   把它挪到过滤之前，症状是「买过戴森的人问送长辈会收到一台吸尘器」，
        //   而那个列表读起来像一份很贴心的推荐。
        UserAffinity affinity = loadAffinity(context);
        List<Scored> ranked = new ArrayList<>(hits.size());
        for (Scored hit : hits) {
            ranked.add(hit.withAffinity(affinityOf(affinity, hit.product())));
        }

        // ── ④ 排序：总分降序 → 同分按他自己的历史细排 → 最后按编号 ──
        //
        // ★ 同分必须有一个确定的次序，否则「换个时间问，排出来的顺序不一样」。
        //   用编号升序而不是「保持查询顺序」：查询顺序依赖 SQL 的执行计划
        // ★★ 中间那两层是「组内细排」（见类注释第二节）：加分只是把候选收窄成
        //   一组同分的商品，而组内前三名原先【完全】由编号决定 —— 实测 9 件同分
        //   里 4 件是甲档，一件都没进前三，只因为它们的编号更大。
        ranked.sort(rankingOf(affinity));
        List<Scored> picks = ranked.size() > topN ? ranked.subList(0, topN) : ranked;
        int topGroupSize = countTopGroup(ranked);

        List<String> filters = describeFilters(category, budgetMax);
        return McpToolResult.ok(
                render(need, filters, picks, ranked.size(), candidates.size(), affinity, topGroupSize),
                dataOf(candidates.size(), ranked.size(), picks, topGroupSize));
    }

    /**
     * 读偏好，<b>并保证永不让推荐失败</b>（阶段 9.5）。
     *
     * <p>★★ 为什么这一层必须自己吞异常：工具抛出去会被 {@code ToolLoop}
     * 包成<b>工具结果</b>喂回模型（那是它的设计），于是「订单表读挂了」
     * 会变成「推荐工具答不了」—— 一个<b>可选</b>的个性化功能把
     * 主功能拖垮了。而少了偏好，这个工具剩下的一切照常工作。
     *
     * <p>★ 注意这里的吞异常和 {@code ChatServiceImpl.structuredFactsSafely}
     * 是同一个分工：Provider 只负责读，怎么处置读挂了是<b>调用方</b>的策略 ——
     * 而工具不经过服务层，所以它自己包一层。
     *
     * <p>⚠️ 无身份时 {@code McpToolContext} 根本构造不出来（见那个类），
     * 所以这里拿到的一定是一个合法 id —— 但它<b>可能指向一个不存在的人</b>
     * （{@code X-Xbla-User-Id: 999999} 是明文未签名的）。
     * 那种情况下 Provider 会查出 0 笔订单，如实返回 EMPTY ——
     * <b>不抛异常</b>。同工具层那条「查无此单是 isError:false」的纪律（ADR-056）。
     */
    private UserAffinity loadAffinity(McpToolContext context) {
        try {
            return userAffinityProvider.load(context.userId());
        } catch (Exception e) {
            log.warn("★ 读用户偏好失败，本次按【无偏好】排序（推荐本身不受影响）"
                    + "user={}: {}", context.userId(), e.getMessage(), e);
            return UserAffinity.EMPTY;
        }
    }

    /**
     * 一件商品能拿到的偏好加分。
     *
     * <p>规则见类注释第二节。★ <b>三条判断互相独立</b>：一个常买类目、
     * 又买过这个品牌、价格还在他平时的量级里的商品拿满分 4。
     *
     * <p>⚠️ 品牌为空的商品（{@code product.brand} 可空）拿不到品牌那一分，
     * 这是对的 —— <b>「买过这个品牌」需要两边都有品牌才成立</b>。
     * 把它当成「没有品牌 ⇒ 不算买过」而不是「未知 ⇒ 可能买过」，
     * 是这条规则里唯一安全的方向。
     */
    private static AffinityBonus affinityOf(UserAffinity affinity, Product product) {
        if (affinity.isEmpty()) {
            return AffinityBonus.NONE;
        }

        int score = 0;
        List<String> reasons = new ArrayList<>(3);

        if (containsName(affinity.categories(), product.getCategory())) {
            score += AFFINITY_CATEGORY_BONUS;
            reasons.add("常买类目 +" + AFFINITY_CATEGORY_BONUS);
        }
        if (containsName(affinity.brands(), product.getBrand())) {
            score += AFFINITY_BRAND_BONUS;
            reasons.add("常买品牌 +" + AFFINITY_BRAND_BONUS);
        }
        if (priceInBand(affinity, product.getPrice())) {
            score += AFFINITY_PRICE_BONUS;
            reasons.add("价格量级相符 +" + AFFINITY_PRICE_BONUS);
        }

        return score == 0 ? AffinityBonus.NONE : new AffinityBonus(score, reasons);
    }

    /**
     * 这位用户买过这个名字<b>几次</b>（没买过 = 0）。
     *
     * <p>★ 它和「有没有买过」是<b>同一个判据</b>（见 {@link #containsName}）——
     * 两个定义分家过一次：症状是一件商品拿到了「常买类目 +2」，
     * 却在组内细排时被当成「没买过这个类目」，而两处都不报错。
     */
    private static int countOf(List<UserAffinity.Count> counts, String name) {
        if (name == null || name.isBlank()) {
            return 0;
        }
        // ★ 两边都 trim 再比：数据库里 category 是 NOT NULL 的，但没人保证它没空格，
        //   而「因为一个尾随空格而没匹配上」是一种完全静默的失败
        String key = name.trim();
        for (UserAffinity.Count item : counts) {
            if (key.equals(item.name())) {
                return item.count();
            }
        }
        return 0;
    }

    private static boolean containsName(List<UserAffinity.Count> counts, String name) {
        return countOf(counts, name) > 0;
    }

    /**
     * 这件商品的价格<b>离他平时花的钱有多远</b>（越近越靠前）。
     *
     * <p>★ 参照点是<b>平均成交单价</b>，不是价格带的中心：价格带回答的是
     * 「算不算相符」（×0.5 ~ ×2 的粗判），而这一个要在<b>已经相符</b>的商品之间
     * 分远近。用带子中心的话，「买过 484 ~ 15065」的人会觉得一台 7000 的
     * 比一台 500 的更「像他买的」—— 而两者都在带内、都不该被这条规则判出局。
     *
     * <p>⚠️ 任一边缺（他还没有成交记录 / 商品没填价）→ <b>没有信号</b>，
     * 返回 {@code null} 并由比较器排到最后。★ 不能当成「距离 0」——
     * 那会让一件<b>不知道价格</b>的商品赢过所有已知价格的商品。
     */
    private static BigDecimal priceDistance(UserAffinity affinity, Product product) {
        if (affinity.priceAvg() == null || product.getPrice() == null) {
            return null;
        }
        return product.getPrice().subtract(affinity.priceAvg()).abs();
    }

    /**
     * 组内细排（「修法 A」，见类注释第二节）—— <b>同分商品之间的先后</b>。
     *
     * <p>★ 抽成独立的方法是为了让排序和正文<b>读同一个判据</b>：
     * 正文要说的那句「它们之间的先后是……排的」只有拿它才判得准。
     * 分成两份实现的话，正文会在一组其实由编号决定的商品上说「按你的历史排的」。
     */
    private static Comparator<Scored> historyOrder(UserAffinity affinity) {
        Comparator<Scored> byCategoryCount = Comparator.comparingInt(
                (Scored s) -> countOf(affinity.categories(), s.product().getCategory()));
        Comparator<Scored> byBrandCount = Comparator.comparingInt(
                (Scored s) -> countOf(affinity.brands(), s.product().getBrand()));
        Comparator<Scored> byPriceDistance = Comparator.comparing(
                (Scored s) -> priceDistance(affinity, s.product()),
                Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()));
        return byCategoryCount.reversed()
                .thenComparing(byBrandCount.reversed())
                .thenComparing(byPriceDistance);
    }

    /**
     * 完整的排序键。
     *
     * <p>⚠️ 主键必须是<b>总分</b>（= 字面重合 + 偏好加分），不能拆成
     * 「先比字面重合、再比偏好」—— 后者会让一件「完全匹配但没买过」
     * 的商品压过一件「匹配得差一点、但他一直在买」的商品，
     * 而那正是加分制要避免的方向。
     */
    private static Comparator<Scored> rankingOf(UserAffinity affinity) {
        return Comparator.comparingInt(Scored::total).reversed()
                .thenComparing(historyOrder(affinity))
                .thenComparing(s -> s.product().getId());
    }

    /** 总分最高的那一组有几件 —— 它比 {@code top_n} 大时，返回的只是「并列里的前几个」 */
    private static int countTopGroup(List<Scored> ranked) {
        if (ranked.isEmpty()) {
            return 0;
        }
        int top = ranked.get(0).total();
        int n = 0;
        for (Scored scored : ranked) {
            if (scored.total() != top) {
                break;
            }
            n++;
        }
        return n;
    }

    /**
     * 这一组同分商品的先后<b>是不是</b>历史决定的。
     *
     * <p>★★ 判据不是「他有没有偏好」：有偏好、但组内三项全相等时，
     * 先后<b>仍然</b>是编号说了算 —— 那时候说「按你的历史再排的」，
     * 就是一句<b>关于这个排序的假话</b>，而模型会原样转述。
     */
    private static boolean differentiatedByHistory(List<Scored> group, UserAffinity affinity) {
        if (affinity.isEmpty() || group.size() < 2) {
            return false;
        }
        Comparator<Scored> order = historyOrder(affinity);
        for (int i = 1; i < group.size(); i++) {
            if (order.compare(group.get(i - 1), group.get(i)) != 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean priceInBand(UserAffinity affinity, BigDecimal price) {
        if (price == null || affinity.priceMin() == null || affinity.priceMax() == null) {
            return false;
        }
        // ★ 上下界都用【他买过的实价】乘一个系数得到，不是拿平均值去套 ——
        //   实测区间跨度能到 30 倍，套平均会把两头（他真买过的）判出去
        BigDecimal low = affinity.priceMin().multiply(PRICE_BAND_LOW_FACTOR);
        BigDecimal high = affinity.priceMax().multiply(PRICE_BAND_HIGH_FACTOR);
        return price.compareTo(low) >= 0 && price.compareTo(high) <= 0;
    }

    // ============================================================
    // 打分
    // ============================================================

    /**
     * 一条推荐：商品 + <b>两个分数</b> + 逐条理由。
     *
     * <p>★★ <b>分数刻意是两个而不是一个</b>：{@code matchScore} 是「这句话问的是什么」，
     * {@code affinity} 是「这个人买过什么」。合成一个数就再也分不出
     * 「它排在前面是因为匹配得好，还是因为是他的老习惯」——
     * 而正文里那句「为什么是它」正是靠这个区分才写得出来的。
     *
     * <p>★ 排序用的是 {@link #total()}（两者相加）。把它写成一个方法而不是
     * 一个字段：**字段会有两个事实来源**（构造时算一次、改分数时忘改一次）。
     *
     * @param matchScore 字面重合得分（适用人群 3 / 卖点 2 / 商品名 1）
     * @param affinity   偏好加分（阶段 9.5），见 {@link AffinityBonus}
     * @param hits       命中的字段与命中的那段字。★ <b>它就是要写进正文的理由</b> ——
     *                   不给模型「为什么是它」，它会自己编一个理由出来
     */
    private record Scored(Product product, int matchScore, AffinityBonus affinity, List<Hit> hits) {

        Scored {
            hits = List.copyOf(hits);
        }

        /** ★ 排序键。两个部分都要保留在结构化数据里，见那个字段的注释 */
        int total() {
            return matchScore + affinity.score();
        }

        /**
         * 打上偏好分。
         *
         * <p>★ 用 wither 而不是在 {@code score()} 里直接读一遍偏好：
         * 那样每一次打分都要查一次偏好表（200 个候选 200 次查询）——
         * 而这个方法把「算一次、贴到每个候选上」写成了结构。
         */
        Scored withAffinity(AffinityBonus bonus) {
            return new Scored(product, matchScore, bonus, hits);
        }

        /** 命中的那一段字，用于正文 */
        record Hit(String field, String text) {
        }
    }

    /**
     * 偏好加分（阶段 9.5）。
     *
     * <p>★ {@code NONE} 是一个常量而不是 {@code new AffinityBonus(0, List.of())}：
     * 「没有偏好」是最常见的那一格（匿名提问、订单不足、或者就是没命中），
     * 而它要能一眼认出来 —— 正文里就是靠 {@code score() == 0} 决定
     * 要不要多写那一段的。
     *
     * @param score   加分（0~4）
     * @param reasons 逐条理由，如 {@code "常买类目 +2"}。★ 它<b>要写进正文</b>：
     *                「为什么排前面」对模型是必要的，不然它会自己编一个
     */
    private record AffinityBonus(int score, List<String> reasons) {

        static final AffinityBonus NONE = new AffinityBonus(0, List.of());

        AffinityBonus {
            reasons = List.copyOf(reasons);
        }

        boolean isEmpty() {
            return score == 0;
        }
    }

    /**
     * 打分。规则见类注释第一节，三个字段三个权重。
     *
     * <p>★ 用<b>最长公共子串</b>而不是「按字拆开看有没有」：
     * 「送长辈」按字拆开会和「送朋友」「长辈」都各命中一半，
     * 而它想表达的是一个整体意思。子串至少要求那两个字<b>连在一起出现过</b>。
     */
    private static Scored score(String need, Product product) {
        int score = 0;
        List<Scored.Hit> hits = new ArrayList<>(3);

        String bySuitable = longestCommonSubstring(need, product.getSuitableFor());
        if (bySuitable.length() >= MIN_HIT_CHARS) {
            score += 3;
            hits.add(new Scored.Hit("适用人群", bySuitable));
        }

        String bySelling = longestCommonSubstring(need, product.getSellingPoints());
        if (bySelling.length() >= MIN_HIT_CHARS) {
            score += 2;
            hits.add(new Scored.Hit("卖点", bySelling));
        }

        String byName = longestCommonSubstring(need, product.getName());
        if (byName.length() >= MIN_HIT_CHARS) {
            score += 1;
            hits.add(new Scored.Hit("商品名", byName));
        }

        // ★ 得分和【理由】一起返回。分开返回过一次，症状是理由列表为 null ——
        //   而它只在有命中时才会被读到，所以「没有命中的那些调用」一切正常
        //
        // ★ 这一层【只算字面重合】：偏好要等 hits 过滤之后才贴（见 call 的第 ③ 步）
        return new Scored(product, score, AffinityBonus.NONE, hits);
    }

    /**
     * 两个串的最长公共子串（连续）。任一为空时返回空串。
     *
     * <p>★ 用滚动数组的 DP，{@code O(n*m)} 时间。这里的 n、m 都是几十个字，
     * 而候选上限 {@link #MAX_CANDIDATES} 是 200 —— 一次调用最多两百万次比较，
     * 微秒级。不要为它引入更复杂的结构。
     */
    static String longestCommonSubstring(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return "";
        }
        int[] prev = new int[b.length() + 1];
        int bestLength = 0;
        int bestEnd = 0;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > bestLength) {
                        bestLength = cur[j];
                        bestEnd = i;
                    }
                }
            }
            prev = cur;
        }
        return a.substring(bestEnd - bestLength, bestEnd);
    }

    // ============================================================
    // 给模型读的正文
    // ============================================================

    private static String emptyCandidatesText(String category, Integer budgetMax) {
        List<String> filters = describeFilters(category, budgetMax);
        return "没有任何在售商品满足这些条件"
                + (filters.isEmpty() ? "" : "：" + String.join("、", filters))
                + "。\n"
                + "★ 这是【确实没有符合条件的商品】，不是匹配度的问题。"
                + "请让用户放宽条件（比如提高预算、换个类目）后再来。";
    }

    private static String noMatchText(String need, String category, Integer budgetMax, int count) {
        List<String> filters = describeFilters(category, budgetMax);
        return "在 " + count + " 个候选商品里，没有任何一个的描述和「" + need + "」匹配"
                + (filters.isEmpty() ? "" : "（候选已按 " + String.join("、", filters) + " 过滤）")
                + "。\n"
                + "★ 注意：这不等于「平台没有这类商品」，而是"
                + "「商品的资料里没有这样描述」—— 本工具是按字面重合度找的。\n"
                + "请让用户补充一下：想用在什么场景、大概什么价位、有没有偏好的品类或品牌。"
                + "★ 不要自己换一个需求词再试，也不要凭已有信息编一个推荐。";
    }

    private static String render(String need, List<String> filters, List<Scored> picks,
                                 int matchedCount, int candidateCount, UserAffinity affinity,
                                 int topGroupSize) {
        StringBuilder sb = new StringBuilder(1100);

        sb.append("按「").append(need).append("」找到 ").append(matchedCount).append(" 个相关商品");
        if (!filters.isEmpty()) {
            sb.append("（已按 ").append(String.join("、", filters)).append(" 过滤）");
        }
        sb.append("，候选池 ").append(candidateCount).append(" 个。下面是前 ")
                .append(picks.size()).append(" 个：\n");

        for (int i = 0; i < picks.size(); i++) {
            Scored pick = picks.get(i);
            Product product = pick.product();
            sb.append('\n').append(i + 1).append(". 【").append(product.getName()).append("】")
                    .append('（').append(product.getCategory());
            if (notBlank(product.getBrand())) {
                sb.append(" · ").append(product.getBrand());
            }
            sb.append(" · ").append(dash(product.getProductNo()));
            sb.append("）").append(priceText(product));
            sb.append('\n');
            sb.append("   为什么是它：");
            for (int k = 0; k < pick.hits().size(); k++) {
                if (k > 0) {
                    sb.append("；");
                }
                Scored.Hit hit = pick.hits().get(k);
                sb.append(hit.field()).append("命中「").append(clip(hit.text())).append("」");
            }
            // ★ 偏好那几条也写进「为什么是它」—— 不给理由，模型会自己编一个
            //   （同 hits 那条注释）。★ 逗号而不是分号：它和上面那几条不同源
            if (!pick.affinity().isEmpty()) {
                sb.append("；你的历史：").append(String.join("、", pick.affinity().reasons()));
            }
            sb.append('\n');
        }

        appendTieNotes(sb, picks, affinity, topGroupSize);

        // ★★★ 排序依据必须说破。不说的后果是模型把这几个当成「平台最推荐的」
        sb.append("\n说明：以上排序的依据是【用户描述与商品卖点/适用人群的字面重合度】");
        if (affinity.isEmpty()) {
            // ★ 没有偏好时【只】说明一层 —— 多写一句「还有你的历史」
            //   就是一句假话，而模型会照着它解释一遍
            sb.append("，不是销量、不是评分、也不是平台的推荐。");
            return sb.toString().stripTrailing();
        }

        sb.append("（主）；其次看【这位用户自己的历史购买记录】"
                + "（次：常买类目 +2、常买品牌 +1、价格量级相符 +1）。")
                .append("★ 不是销量、不是评分、也不是平台在推荐。");
        // ★★ 这一句是本节最要紧的：偏好【只在已经匹配上的商品之间】重排。
        //   不说破的话，模型会以为「他买过的东西」也能被推上来，
        //   于是把一份按需求挑出来的列表讲成一份按习惯挑出来的列表
        sb.append("第 ② 层只用来在【已经匹配上的】商品之间分先后，"
                + "不会把和这个需求匹配不上的商品拉进来。");
        return sb.toString().stripTrailing();
    }

    /**
     * 「同分」这件事必须说破 —— 而且要说得<b>准</b>。
     *
     * <p>★★ 不说破的后果（9.5 之前）：模型把「第 3 名排在前面」当成「它更好」，
     * 于是把一份<b>按编号截出来的</b>列表讲成一份按质量挑出来的列表。
     *
     * <p>★★ 说得不准的后果（组内细排之后新出现的）：细排生效了，
     * 但这句话还写着「只按商品编号排」—— 那是一句<b>关于这个排序的假话</b>。
     * 所以每一组的措辞由 {@link #differentiatedByHistory} 现算，不是抄一句固定的。
     *
     * <p>★ 「最高那一组有 N 件并列」单独说：这一句才是「上面这几件谁先谁后
     * 没有意义」的完整凭据（实测 9 件并列时，前 3 件的编号恰好都更大）。
     */
    private static void appendTieNotes(StringBuilder sb, List<Scored> picks,
                                       UserAffinity affinity, int topGroupSize) {
        if (topGroupSize > picks.size()) {
            sb.append("\n★ 上面这 ").append(picks.size())
                    .append(" 件是从【总分最高的那一组】里取的 —— 这一组一共有 ")
                    .append(topGroupSize).append(" 件并列，谁先谁后不代表谁更好。\n");
        }

        // ★ 走【所有】相邻的同分组，不只是末尾那一组：
        //   前三名同分（都从最高组里取）时，末尾那组可能只有一件，
        //   而读者最容易被误导的恰恰是【最前面】那几件的先后
        int i = 0;
        while (i < picks.size()) {
            int j = i;
            while (j + 1 < picks.size() && picks.get(j + 1).total() == picks.get(i).total()) {
                j++;
            }
            if (j > i) {
                appendOneTieNote(sb, picks.subList(i, j + 1), i + 1, j + 1, affinity);
            }
            i = j + 1;
        }
    }

    private static void appendOneTieNote(StringBuilder sb, List<Scored> group, int from, int to,
                                         UserAffinity affinity) {
        sb.append("\n★ 第 ").append(from).append('~').append(to).append(" 个总分相同（")
                .append(group.get(0).total()).append(" 分）；它们之间的先后");
        if (differentiatedByHistory(group, affinity)) {
            sb.append("是按【你买过这个类目/品牌的次数】和【价格离你平时消费的远近】再排的，"
                    + "不代表这几款之间谁更好。\n");
        } else {
            sb.append("只按商品编号排，不代表谁更好。\n");
        }
    }

    /** 理由里的那段字截断 —— 命中「续航18小时/重量1.2kg」时正文会很长 */
    private static String clip(String text) {
        return text.length() <= MAX_REASON_CHARS ? text : text.substring(0, MAX_REASON_CHARS) + "…";
    }

    private static List<String> describeFilters(String category, Integer budgetMax) {
        List<String> filters = new ArrayList<>(2);
        if (category != null) {
            filters.add("类目=" + category);
        }
        if (budgetMax != null) {
            filters.add("≤" + budgetMax + "元");
        }
        return filters;
    }

    private static String priceText(Product product) {
        // ★ 不填 0：填 0 模型会说「这个不要钱」，而实际上是我们不知道
        return product.getPrice() == null ? "价格未知" : "¥" + product.getPrice().toPlainString();
    }

    // ============================================================
    // 给程序读的结构化数据
    // ============================================================

    private static Map<String, Object> dataOf(int candidateCount, int matchedCount,
                                              List<Scored> picks, int topGroupSize) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("candidate_count", candidateCount);
        data.put("matched_count", matchedCount);
        // ★★ 并列组的大小（阶段 9.5 的细排）：它是「前 3 件是从几件并列里取的」。
        //   没有它，读数据的人会把「编号恰好排在前面」当成「它更相关」——
        //   而这正是 9.5 的 A/B 里最容易被误读的一格（见 recommend-gold.yml）
        //   ⚠️ 0 = 没有排序发生（候选为空 / 一个都没匹配上），不是「并列 0 件」
        data.put("top_group_size", topGroupSize);
        data.put("picks", picks.stream().map(RecommendProductsTool::entry).toList());
        return data;
    }

    private static Map<String, Object> entry(Scored pick) {
        Map<String, Object> row = new LinkedHashMap<>();
        Product product = pick.product();
        row.put("product_no", product.getProductNo());
        row.put("name", product.getName());
        row.put("category", product.getCategory());
        row.put("brand", product.getBrand());
        row.put("price", product.getPrice() == null ? null : product.getPrice().toPlainString());
        // ★★ score 是【总分】（= 排序用的那个数），另外两个是它的组成部分。
        //   三个都给：只给总分的话，读数据的人分不出「它靠匹配上去的」
        //   还是「它靠这个人的老习惯上去的」—— 而那正是 9.5 之后
        //   最容易被误读的一格
        row.put("score", pick.total());
        row.put("match_score", pick.matchScore());
        row.put("affinity_score", pick.affinity().score());
        // ★ 理由也进结构化数据：模型有时候会照着 data 复述，
        //   给它一份「理由」字段，比让它自己编一个要安全
        row.put("reasons", pick.hits().stream()
                .map(h -> h.field() + "命中「" + h.text() + "」").toList());
        // ★ 偏好理由单独一组：它们和上面那几条的来源不同（一个是这句话，
        //   一个是这个人的历史），混在一起模型说不清哪条是哪条
        row.put("affinity_reasons", pick.affinity().reasons());
        return row;
    }

    // ============================================================
    // 小工具
    // ============================================================

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String dash(String value) {
        return notBlank(value) ? value : "（无编号）";
    }
}
