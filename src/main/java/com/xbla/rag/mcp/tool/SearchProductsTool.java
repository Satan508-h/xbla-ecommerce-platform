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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「有没有适合送长辈的」—— 阶段 9.3 的第一个新工具。
 *
 * <h2>★★★ 一、它和 ADR-071 的关系（必读，否则会以为它违反了那条禁令）</h2>
 *
 * <p>{@link QueryInventoryTool} 的类注释里有一条很硬的禁令：
 * <b>不许拿类目 / 品牌去模糊匹配「某一款商品的库存」</b>。理由是那会命中几十个，
 * 返回前 3 个 —— 一份<b>按 id 排的随机结果</b>，却<b>看起来像一份答案</b>。
 *
 * <p>那本工具和这条禁令的关系是：<b>它禁的是「用类目词回答一个具体的库存问题」，
 * 不是「列出类目下的商品」。</b>两者的区别在<b>问题是什么</b>：
 *
 * <pre>
 *   用户问「手机还有货吗」        ← 这是一个【具体问题】。给一份随机候选 = 假答案
 *   用户问「有没有适合送长辈的」  ← 这是一个【找候选】的请求。返回多个是它的目的
 * </pre>
 *
 * <p>所以本工具是合法的。但合法不等于没有代价 —— 上面那个「看起来像答案」
 * 的风险换了个形状又回来了：<b>模型很容易把一份未排序的候选，复述成一份推荐。</b>
 * 所以它必须付三笔账，一笔都不能省：
 *
 * <ol>
 *   <li><b>正文明写「共匹配 N 个，只列出前 K 个」</b> ——
 *       模型得知道这不是全部，否则用户问「就这几个吗」时它会答「是的」</li>
 *   <li><b>正文自己说破「这个顺序没有含义」</b> ——
 *       排序固定为 {@code product_no} 升序（唯一的原因是可复现、能 diff），
 *       而用户读到的「第一个」会被当成「最推荐的」。这句话是给模型看的，
 *       因为它才是那个转述的人</li>
 *   <li><b>真正的排序交给 {@link RecommendProductsTool}</b> ——
 *       这正是「三个工具各站一格」的设计依据：
 *       <b>找候选 / 排序推荐 / 查实时库存</b>，三件事三件工具，
 *       各自的正文字数预算和 isError 语义都不同</li>
 * </ol>
 *
 * <h2>★★ 二、关键词匹配【三个字段】，不是只匹配商品名</h2>
 *
 * <p>{@link QueryInventoryTool} 只匹配 {@code name}，因为用户报的是一个具体商品名。
 * 而本工具的输入是一句<b>描述</b>（「送长辈」「上网课」「续航长」），
 * 那些词<b>不在商品名里</b> —— 它们在 {@code selling_points}（卖点）和
 * {@code suitable_for}（适用人群）里。实测那两个字段就是为这类问题准备的：
 * {@code suitable_for} 里写的是「适合送长辈」这种整句。
 *
 * <p>⚠️ 代价是匹配会比名字宽很多：关键词「手机」会命中几十个。
 * 这不是 bug —— 找候选本来就该宽。但正文里的第一条账（「共匹配 N 个」）
 * 必须写清楚，否则模型不知道后面还有多少。
 *
 * <h2>三、不展开 SKU</h2>
 *
 * <p>本工具一个规格都不列。理由有两条，第二条才是要紧的：
 * <ul>
 *   <li>规格是 {@link QueryInventoryTool} 的职责，工具之间不重叠</li>
 *   <li>★ <b>规格一来，正文长度就变成数据相关的</b>（一个商品 2~4 个规格），
 *       而 {@code ToolLoop.MAX_TOOL_RESULT_CHARS} 的截断发生在末尾 ——
 *       截断位置随数据浮动，模型看到的是「前几个商品完整、后面的凭空消失」</li>
 * </ul>
 */
@Component
public class SearchProductsTool implements McpTool {

    /**
     * ★ 一次最多返回几个商品。
     *
     * <p>正文长度有三处来源，各自的界不一样 —— <b>算预算时按最宽的那个算</b>：
     *
     * <pre>
     *   商品名 / 类目 / 品牌 / 编号   schema 定长（VARCHAR 64~255），靠 {@link #FIELD_MAX_CHARS} 收口
     *   卖点 / 适用人群              TEXT 无上限，只能靠截断
     *   回显的关键词                 来自模型，实测在 20 字以内
     * </pre>
     *
     * <p>实测两种口径：
     * <ul>
     *   <li>种子数据（真实形状，名字约 10~25 字）≈ <b>110 字/商品</b>，
     *       10 个 ≈ 1350 字 —— 在预算的三分之一以内</li>
     *   <li>★ <b>schema 上限</b>（每一列都顶格、10 个商品）实测 <b>3575 字</b> ——
     *       这是【保证】不会超过的数（2026-09-25 量的，改正文形状要重新量）</li>
     * </ul>
     *
     * <p>★★ 第二种口径由 {@code ToolResultBudgetTest} 真的跑出来断言，
     * 而且它的夹具长度是<b>从 {@code information_schema} 读的</b> ——
     * 所以「最坏情况」由 schema 定义，不由某个人的记忆定义。
     * 把某一列改宽、或者往正文里多加一个字段，那里会红。
     */
    private static final int MAX_PRODUCTS = 10;

    /** 模型没给 limit 时的默认条数 */
    private static final int DEFAULT_LIMIT = 5;

    /**
     * 商品名 / 卖点 / 适用人群在正文里各自截断到多少字。
     *
     * <p>★ <b>名字也在这个上限里</b>，这一点曾经漏掉过：卖点和适用人群截了，
     * 名字没截 —— 而 {@code name} 是 {@code VARCHAR(255)}，
     * 10 个顶格名字光名字就是 2550 字，整个正文会到 5734 字
     * （实测，{@code ToolResultBudgetTest} 抓到的那次），
     * 于是 {@code ToolLoop} 在【末尾】截断，模型看到的是
     * 「前几个完整、后面的凭空消失」。
     *
     * <p>⚠️ 名字被截断时【一定要看得出来】（带省略号）——
     * 而且它旁边永远跟着商品编号，所以「这是哪一款」不会因为截断而失去答案。
     */
    private static final int FIELD_MAX_CHARS = 40;

    // ★ 字段常量 —— schema 和取值都走它，见 ToolField 的类注释
    private static final ToolField KEYWORD = ToolField.requiredString(
            "keyword",
            "用户想要什么样的商品，用他话里的关键词。"
                    + "形如「送长辈」「上网课」「续航长」「华为」。"
                    + "★ 会同时匹配商品名、卖点和适用人群，所以描述性的词也能找到东西。");

    private static final ToolField CATEGORY = ToolField.optionalString(
            "category",
            "类目，可选。只在用户【明确说了】是手机/笔记本这类大类时才传，"
                    + "而且要传和系统里一致的名字。不确定就别传。");

    private static final ToolField BRAND = ToolField.optionalString(
            "brand",
            "品牌，可选。用户明确说了品牌（「华为」「小米」）时才传。");

    private static final ToolField MIN_PRICE = ToolField.optionalInt(
            "min_price",
            "最低价（元），可选。用户说了价格下限时才传。");

    private static final ToolField MAX_PRICE = ToolField.optionalInt(
            "max_price",
            "最高价（元），可选。用户说了「XX 块以内」时就传这个数。"
                    + "★ 只给整数，不要带「元」字。");

    private static final ToolField LIMIT = ToolField.optionalInt(
            "limit",
            "最多返回几个商品，可选，默认 5，上限 10。"
                    + "用户说「给我看几个」时按他说的传，没说就别传。");

    private final ProductService productService;

    public SearchProductsTool(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public String name() {
        return "search_products";
    }

    @Override
    public String title() {
        return "搜索商品";
    }

    @Override
    public String description() {
        return """
                按关键词【找商品候选】：用户描述了想要什么样的东西，但还没说出具体是哪一款。

                什么时候用：用户在找东西 ——
                「有没有适合送长辈的」「学生上网课用的平板有哪些」「5000 块以内的手机」。

                什么时候【不要】用：
                - 用户已经说出了具体商品名，只是想知道它好不好 / 还有没有货
                  → 那是 query_inventory（库存）或知识库（参数）
                - 用户问「这几个哪个更适合我」→ 那是 recommend_products（它负责排序）
                - 用户问「我的订单 / 我的券」→ 那是另外两个工具

                ⚠️ 本工具【只负责找候选，不负责排序】：返回的顺序是商品编号升序，
                和匹配度无关。★ 不要把前几个当成推荐结果复述给用户，
                正文里会写明这一点。要排序请用 recommend_products。
                """;
    }

    @Override
    public List<ToolField> inputFields() {
        return List.of(KEYWORD, CATEGORY, BRAND, MIN_PRICE, MAX_PRICE, LIMIT);
    }

    @Override
    public List<ToolField> outputFields() {
        return List.of(
                // ★ 语义要说清是【下界】：取满时它是 limit + 1，不是总数
                ToolField.requiredInt("matched_count",
                        "命中的商品数。★ 为了判断「还有更多」本次只多取了一条，"
                                + "所以当它等于 limit + 1 时是【下界】，不是总数 —— "
                                + "正文里相应地写成「N 个以上」"),
                ToolField.requiredInt("returned_count", "本次实际返回的商品数量"),
                ToolField.requiredString("products", "商品清单（结构化数组，见 data.products）"));
    }

    @Override
    public McpToolResult call(McpArguments args, McpToolContext context) {
        String keyword = KEYWORD.requireString(args).trim();
        String category = trimmedOrNull(CATEGORY.optionalString(args));
        String brand = trimmedOrNull(BRAND.optionalString(args));
        Integer minPrice = MIN_PRICE.optionalInt(args);
        Integer maxPrice = MAX_PRICE.optionalInt(args);
        Integer limit = LIMIT.optionalInt(args);

        // ★ limit 夹在 [1, MAX_PRODUCTS]。★ 夹而不是报错：
        //   模型多要几个不是「它的错」，而报错会让它把这一轮浪费在改参数上
        int take = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_PRODUCTS, limit));

        // ── 关键词匹配【三个字段】── 见类注释第二节
        //
        // ⚠️ 和 QueryInventoryTool 一样不转义 LIKE 通配符：商品目录是公开信息，
        //    最坏结果是「多返回几个」，不是越权
        var query = productService.lambdaQuery()
                .eq(Product::getStatus, 1)
                .eq(Product::getDeleted, 0)
                .and(w -> w.like(Product::getName, keyword)
                        .or().like(Product::getSellingPoints, keyword)
                        .or().like(Product::getSuitableFor, keyword));

        if (category != null) {
            query = query.eq(Product::getCategory, category);
        }
        if (brand != null) {
            query = query.eq(Product::getBrand, brand);
        }
        if (minPrice != null) {
            query = query.ge(Product::getPrice, minPrice);
        }
        if (maxPrice != null) {
            query = query.le(Product::getPrice, maxPrice);
        }

        // ★ 多取一条用来判断「还有更多」—— 比再发一次 count 便宜，
        //   也不会因为两次查询之间数据变了而不一致（同 QueryInventoryTool）
        List<Product> matched = query.orderByAsc(Product::getId)
                .last("LIMIT " + (take + 1))
                .list();

        List<Product> returned = matched.size() > take ? matched.subList(0, take) : matched;
        List<String> filters = describeFilters(category, brand, minPrice, maxPrice);

        if (returned.isEmpty()) {
            return McpToolResult.ok(notFoundText(keyword, filters), dataOf(0, List.of()));
        }
        // ★★ matched_count 是【那一行多取的意义】：
        //   没取满 = 命中总数（确切）；取满了 = take + 1，那是【下界】不是总数。
        //   ⚠️ 它和 returned_count 不相等，正是「还有更多」那个信号 ——
        //      两个数写成一样的话，模型会以为手上就是全部
        return McpToolResult.ok(
                render(keyword, filters, returned, matched.size(), take),
                dataOf(matched.size(), returned));
    }

    // ============================================================
    // 给模型读的正文
    // ============================================================

    private static String notFoundText(String keyword, List<String> filters) {
        // ★ 不要在这里「放松条件再试一次」—— 那会让模型收到一份和它问的无关的结果，
        //   而它无从分辨。实话实说 + 告诉它下一步该做什么
        return "没有找到与「" + keyword + "」相关的商品"
                + (filters.isEmpty() ? "" : "（已经加了这些条件：" + String.join("、", filters) + "）")
                + "。\n"
                + "请让用户换个说法，或者让他补充一下品类 / 预算。"
                + "★ 不要自己换一个关键词再查一次 —— 那会返回一份和用户问的无关的结果。";
    }

    /**
     * @param products     本次要渲染的商品（≤ {@code take} 个）
     * @param matchedCount 查到的一共多少个。★ 它可能是<b>下界</b>（见 {@link #MAX_PRODUCTS}）
     * @param take         本次最多渲染几个
     */
    private static String render(String keyword, List<String> filters,
                                 List<Product> products, int matchedCount, int take) {
        StringBuilder sb = new StringBuilder(1400);

        sb.append("与「").append(keyword).append("」相关的商品");
        if (!filters.isEmpty()) {
            sb.append("（已加条件：").append(String.join("、", filters)).append("）");
        }
        // ★★ 第一笔账：说清「这不是全部」。
        //
        //   ⚠️ 这里的判据必须是 matchedCount > take，【不能】是
        //      「渲染了几个 == 上限」—— 后者在「刚好取满」和「其实还有 40 个」
        //      两种情况下都成立，于是会说两句假话：
        //        「共 5 个（只列出了前 5 个，可能还有更多）」← 前一句和括号互相打脸
        //        「共 5 个」                                ← 真相是 40 个
        //      而两句都不会报错（这一个 bug 是补测试时抓到的）
        boolean more = matchedCount > take;
        // ★ 取满时写【下界】（多取的那条让我们知道至少还有一条），
        //   没取满时写确切数。★ 下界是 matchedCount（= take + 1），
        //   不是渲染了几个 —— 写成 "共 5 个以上" 是句废话，
        //   它没有告诉模型「第 6 个存在」
        sb.append("：共 ").append(more ? matchedCount : products.size()).append(" 个");
        if (more) {
            sb.append("以上（本次只列出了前 ").append(take).append(" 个）");
        }
        sb.append("。\n");

        for (Product product : products) {
            // ★ 名字要截断（见 FIELD_MAX_CHARS）：它是 VARCHAR(255)，
            //   10 个顶格名字自己就 2550 字，会把整个预算顶爆
            sb.append('\n').append("【").append(truncate(product.getName())).append("】")
                    .append('（').append(product.getCategory());
            if (notBlank(product.getBrand())) {
                sb.append(" · ").append(product.getBrand());
            }
            // ★ 商品编号总是带上 —— 同 QueryInventoryTool：正文形状不能随数据变，
            //   而且模型下一步要拿它去调 compare_prices / query_inventory
            sb.append(" · ").append(dash(product.getProductNo()));
            sb.append("）¥").append(priceText(product));
            sb.append('\n');
            appendLine(sb, "卖点", product.getSellingPoints());
            appendLine(sb, "适用", product.getSuitableFor());
        }

        // ★★ 第二笔账：说破「这个顺序没有含义」
        sb.append("\n说明：以上顺序是按商品编号排的，【和匹配度无关】，"
                + "不代表推荐 —— 本工具只负责找出候选，不负责排序。");
        return sb.toString().stripTrailing();
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (notBlank(value)) {
            sb.append("  ").append(label).append("：").append(truncate(value)).append('\n');
        }
    }

    private static String priceText(Product product) {
        if (product.getPrice() == null) {
            // ★ 不填 0 —— 填 0 模型会说「这个不要钱」，而实际上是我们不知道
            return "价格未知";
        }
        return product.getPrice().toPlainString();
    }

    /** 超过 {@link #FIELD_MAX_CHARS} 就截断。★ 截断要看得出来，不能静默 */
    private static String truncate(String value) {
        String text = value.strip().replace('\n', ' ');
        return text.length() <= FIELD_MAX_CHARS
                ? text
                : text.substring(0, FIELD_MAX_CHARS) + "…";
    }

    /** 把生效的过滤条件写成人话 —— 模型得知道结果为什么少，才能向用户解释 */
    private static List<String> describeFilters(String category, String brand,
                                                Integer minPrice, Integer maxPrice) {
        List<String> filters = new ArrayList<>(4);
        if (category != null) {
            filters.add("类目=" + category);
        }
        if (brand != null) {
            filters.add("品牌=" + brand);
        }
        if (minPrice != null) {
            filters.add("≥" + minPrice + "元");
        }
        if (maxPrice != null) {
            filters.add("≤" + maxPrice + "元");
        }
        return filters;
    }

    // ============================================================
    // 给程序读的结构化数据
    // ============================================================

    private static Map<String, Object> dataOf(int matchedCount, List<Product> products) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("matched_count", matchedCount);
        data.put("returned_count", products.size());
        data.put("products", products.stream().map(SearchProductsTool::entry).toList());
        return data;
    }

    private static Map<String, Object> entry(Product product) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("product_no", product.getProductNo());
        row.put("name", product.getName());
        row.put("category", product.getCategory());
        row.put("brand", product.getBrand());
        row.put("price", product.getPrice() == null ? null : product.getPrice().toPlainString());
        row.put("selling_points", product.getSellingPoints());
        row.put("suitable_for", product.getSuitableFor());
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
