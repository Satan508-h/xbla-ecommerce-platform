package com.xbla.rag.mcp.tool;

import com.xbla.rag.entity.Product;
import com.xbla.rag.mcp.McpArguments;
import com.xbla.rag.mcp.McpTool;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.McpToolResult;
import com.xbla.rag.mcp.ToolField;
import com.xbla.rag.service.ProductService;
import org.springframework.stereotype.Component;

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
 * <h2>★★ 二、一个都匹配不上时，【不给列表】</h2>
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
 * <h2>三、候选池有上限</h2>
 *
 * <p>见 {@link #MAX_CANDIDATES}。不限的话，一个宽泛的 {@code need}
 * 会把整个商品表拉进内存算分 —— 而症状是「平时很快，某个问法一查就慢」。
 */
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

    public RecommendProductsTool(ProductService productService) {
        this.productService = productService;
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

                ★ 本工具的排序依据是【用户描述与商品卖点/适用人群的字面重合度】，
                不是销量、不是评分、不是平台推荐 —— 正文里会写明这一点，
                转述给用户时不要把它说成「最热门的」「最受欢迎的」。

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
                    dataOf(0, 0, List.of()));
        }

        // ── ② 打分 ──
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Product product : candidates) {
            scored.add(score(need, product));
        }

        List<Scored> hits = scored.stream().filter(s -> s.score() > 0).toList();
        if (hits.isEmpty()) {
            // ★★ 见类注释第二节：这里【不能】给一份列表
            return McpToolResult.ok(noMatchText(need, category, budgetMax, candidates.size()),
                    dataOf(candidates.size(), 0, List.of()));
        }

        // ── ③ 排序：得分降序，同分按 id 升序 ──
        //
        // ★ 同分必须有一个确定的次序，否则「换个时间问，排出来的顺序不一样」。
        //   用编号升序而不是「保持查询顺序」：查询顺序依赖 SQL 的执行计划
        List<Scored> ranked = new ArrayList<>(hits);
        ranked.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(s -> s.product().getId()));
        List<Scored> picks = ranked.size() > topN ? ranked.subList(0, topN) : ranked;

        List<String> filters = describeFilters(category, budgetMax);
        return McpToolResult.ok(
                render(need, filters, picks, ranked.size(), candidates.size()),
                dataOf(candidates.size(), ranked.size(), picks));
    }

    // ============================================================
    // 打分
    // ============================================================

    /**
     * 一条推荐：商品 + 得分 + 逐条理由。
     *
     * @param score  总分
     * @param hits   命中的字段与命中的那段字。★ <b>它就是要写进正文的理由</b> ——
     *               不给模型「为什么是它」，它会自己编一个理由出来
     */
    private record Scored(Product product, int score, List<Hit> hits) {

        Scored {
            hits = List.copyOf(hits);
        }

        /** 命中的那一段字，用于正文 */
        record Hit(String field, String text) {
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
        return new Scored(product, score, hits);
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
                                 int matchedCount, int candidateCount) {
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
            sb.append('\n');
        }

        // ★★ 同分说明：不说的话，模型会把「第 3 名排在前面」当成「它更好」
        if (picks.size() > 1) {
            int last = picks.get(picks.size() - 1).score();
            long tied = picks.stream().filter(p -> p.score() == last).count();
            if (tied > 1) {
                sb.append("\n★ 最后 ").append(tied)
                        .append(" 个的得分相同，它们之间的先后只按商品编号排，不代表谁更好。\n");
            }
        }

        // ★★★ 排序依据必须说破。不说的后果是模型把这几个当成「平台最推荐的」
        sb.append("\n说明：以上排序的依据是【用户描述与商品卖点/适用人群的字面重合度】，"
                + "不是销量、不是评分、也不是平台的推荐。");
        return sb.toString().stripTrailing();
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
                                              List<Scored> picks) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("candidate_count", candidateCount);
        data.put("matched_count", matchedCount);
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
        row.put("score", pick.score());
        // ★ 理由也进结构化数据：模型有时候会照着 data 复述，
        //   给它一份「理由」字段，比让它自己编一个要安全
        row.put("reasons", pick.hits().stream()
                .map(h -> h.field() + "命中「" + h.text() + "」").toList());
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
