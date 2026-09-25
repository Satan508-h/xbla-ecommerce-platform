package com.xbla.rag.mcp.tool;

import com.xbla.rag.entity.Product;
import com.xbla.rag.mcp.McpArguments;
import com.xbla.rag.mcp.McpTool;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.McpToolResult;
import com.xbla.rag.mcp.ToolField;
import com.xbla.rag.service.ProductService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「这个价贵不贵」—— 阶段 9.3 的第二个新工具。
 *
 * <h2>★★★ 一、为什么入口是 {@code product_no} 而不是商品名</h2>
 *
 * <p>{@link QueryInventoryTool} 的入口是商品名，因为它解决的问题是
 * 「用户随手打了一个名字」。而本工具的入口<b>必须</b>是编号，理由是
 * {@code Product.name} 上<b>没有唯一约束</b>：
 *
 * <pre>
 *   实测（2026-07-11 跑真实数据）：200 个商品里 17 组、共 35 个重名
 *   华为 Magic mini  → P000001 / P000013
 * </pre>
 *
 * <p>拿名字比价，遇到重名就只能二选一 —— 而用户看到的是一句
 * 「华为 Magic mini 售价 4999」，他不知道<b>我们挑的是哪一个</b>。
 * 那是一个「答案正确、问题答错」的回答（同 ADR-072 的教训）。
 *
 * <p>★ 代价是模型可能要多调一次 {@link SearchProductsTool} 才能拿到编号。
 * 这一跳是<b>值得的</b>：它强迫模型把用户嘴里的名字先落到一个确定的对象上，
 * 而那正是重名问题唯一的解法。工具的描述里写明了这一点。
 *
 * <h2>★★ 二、「同类参照」给的是【分布】，不是几个具体商品</h2>
 *
 * <p>回答「这个价贵不贵」，最自然的做法是找三个同类商品摆出来。本项目<b>不这么做</b>：
 *
 * <pre>
 *   给 3 个具体商品  → 模型会把它们复述成「这几个也不错」—— 一份我们没打算给的推荐
 *   给一个价格分布    → 「同类目在售 2000~8000 元，中位 3499」。
 *                       它回答的正是「贵不贵」，而且模型没法把它复述成推荐
 * </pre>
 *
 * <p>这是 {@link SearchProductsTool} 那三笔账的同一个道理：
 * <b>一份看起来像答案的东西，模型一定会把它当成答案。</b>
 * 所以这里的取舍是：宁可少给信息，也不给一个会被误解的信息。
 *
 * <h2>三、不展开 SKU 价格</h2>
 *
 * <p>本工具只看 {@code product.price}（SPU 标价）。规格价是
 * {@link QueryInventoryTool} 的职责 —— 那里每个规格都带价。
 * ★ 正文里有一句话把这件事讲明白，否则模型会以为「标价」就是「所有规格的价格」。
 */
@Component
public class ComparePricesTool implements McpTool {

    /**
     * 统计同类目价格分布时，最多取多少个商品。
     *
     * <p>★ 有上限是必须的：不限的话，一个类目在真实电商里可能有几十万商品，
     * 一次「比价」就把整个类目的价格拉进内存。而那个症状是
     * <b>「平时很快，某个类目一查就慢」</b>，不是报错。
     *
     * <p>取 200 而不是「取全部再算」的代价：中位数是近似的。
     * 所以正文里会写明统计了<b>多少个</b>商品 —— 让人知道这个数有多可靠。
     */
    private static final int STAT_SAMPLE = 200;

    /** 正文里展示的商品个数上限（主商品 + compare_with） */
    private static final int MAX_SHOWN = 2;

    private static final ToolField PRODUCT_NO = ToolField.requiredString(
            "product_no",
            "商品编号，形如 P000012。"
                    + "★ 必须是编号，不是商品名 —— 商品名会重复，拿名字比价可能比错对象。"
                    + "手上只有商品名时，先用 search_products 查到编号再调本工具。");

    private static final ToolField COMPARE_WITH = ToolField.optionalString(
            "compare_with",
            "要对比的另一个商品的编号，可选。"
                    + "用户说「这两个哪个贵」时就传；只问「这个贵不贵」时不要传。");

    private final ProductService productService;

    public ComparePricesTool(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public String name() {
        return "compare_prices";
    }

    @Override
    public String title() {
        return "商品比价";
    }

    @Override
    public String description() {
        return """
                查某个商品的【标价】，并给出同类目在售商品的价格分布，用来回答「这个价贵不贵」。

                什么时候用：用户在问价格 ——
                「星辰X1 多少钱」「这个价贵吗」「星辰X1 和华为 Magic mini 哪个贵」。

                什么时候【不要】用：
                - 用户问「有没有货」「哪个颜色有货」→ 那是 query_inventory
                - 用户问「哪个更值得买」「适合我吗」→ 那是 recommend_products（本工具只给价格，不给判断）
                - 用户问「满减怎么算」「券能不能叠」→ 那是知识库（规则问题，不是某一款的价格）

                ★ 入口是【商品编号】不是商品名。手上只有名字时，先用 search_products 查到编号 ——
                商品名会重复（同名不同款很常见），拿名字比价可能比错对象。
                """;
    }

    @Override
    public List<ToolField> inputFields() {
        return List.of(PRODUCT_NO, COMPARE_WITH);
    }

    @Override
    public List<ToolField> outputFields() {
        return List.of(
                ToolField.requiredString("products", "参与比价的商品（结构化数组，见 data.products）"),
                ToolField.requiredString("category_stats",
                        "同类目价格分布（min / median / max / sample_size），类目未知时为 null"));
    }

    @Override
    public McpToolResult call(McpArguments args, McpToolContext context) {
        String productNo = PRODUCT_NO.requireString(args).trim();
        String compareWith = trimmedOrNull(COMPARE_WITH.optionalString(args));

        List<Product> shown = new ArrayList<>(MAX_SHOWN);
        Map<String, Product> byNo = new LinkedHashMap<>();

        Product main = findByNo(productNo);
        if (main == null) {
            return McpToolResult.ok(notFoundText(productNo), dataOf(List.of(), null));
        }
        shown.add(main);
        byNo.put(productNo, main);

        Product other = null;
        if (compareWith != null) {
            other = findByNo(compareWith);
            if (other == null) {
                // ★ 第二个商品没找到【不】让整个调用失败：第一个商品的价格已经是
                //   一个完整答案了。如实说一句，把能给的给出去
                //   （同 ADR-056：isError 的判据是「工具有没有给出答案」）
                //
                // ★★ 但【必须把第一个商品的正文先渲染出来】。
                //    这里原来直接 return 了那句「上面那个商品的标价仍然有效」，
                //    而正文里根本没有那个商品 —— 模型读到「上面那个」往下看，
                //    上面什么都没有。（补测试时抓到的，见
                //    ComparePricesToolIntegrationTest#missingSecondKeepsTheFirst）
                CategoryStats stats = statsOf(main);
                return McpToolResult.ok(
                        render(shown, null, stats) + "\n\n"
                                + notFoundText(compareWith)
                                + "\n（上面那个商品的标价仍然有效，可以用。）",
                        dataOf(shown, stats));
            }
            shown.add(other);
        }

        CategoryStats stats = statsOf(main);
        return McpToolResult.ok(render(shown, other, stats), dataOf(shown, stats));
    }

    // ============================================================
    // 查询
    // ============================================================

    private Product findByNo(String productNo) {
        return productService.lambdaQuery()
                .eq(Product::getProductNo, productNo)
                .eq(Product::getStatus, 1)
                .eq(Product::getDeleted, 0)
                .one();
    }

    /**
     * 主商品所在类目的价格分布。查不到类目时返回 {@code null}。
     *
     * <p>★ 只取 {@code price} 一列 + 一个上限，见 {@link #STAT_SAMPLE}。
     */
    private CategoryStats statsOf(Product product) {
        String category = product.getCategory();
        if (category == null || category.isBlank()) {
            return null;
        }
        List<Product> rows = productService.lambdaQuery()
                .select(Product::getPrice)
                .eq(Product::getCategory, category)
                .eq(Product::getStatus, 1)
                .eq(Product::getDeleted, 0)
                .orderByAsc(Product::getId)
                .last("LIMIT " + STAT_SAMPLE)
                .list();

        List<BigDecimal> prices = new ArrayList<>(rows.size());
        for (Product row : rows) {
            if (row.getPrice() != null) {
                prices.add(row.getPrice());
            }
        }
        if (prices.isEmpty()) {
            return null;
        }
        Collections.sort(prices);
        // ★ 中位数：偶数个取中间两个的平均。取「中间偏左」会让 200 个样本的
        //   中位数系统性偏低，而它是个会被人引用的数
        BigDecimal median;
        int size = prices.size();
        if (size % 2 == 1) {
            median = prices.get(size / 2);
        } else {
            median = prices.get(size / 2 - 1).add(prices.get(size / 2))
                    .divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
        }
        return new CategoryStats(category, prices.get(0), median, prices.get(size - 1), size);
    }

    // ============================================================
    // 给模型读的正文
    // ============================================================

    private static String notFoundText(String productNo) {
        return "没有找到编号为「" + productNo + "」的在售商品。\n"
                + "★ 商品编号形如 P000012。如果手上只有商品名，"
                + "请先用 search_products 查出编号 —— 商品名会重复，不能直接拿来比价。";
    }

    private static String render(List<Product> shown, Product other, CategoryStats stats) {
        StringBuilder sb = new StringBuilder(640);

        for (int i = 0; i < shown.size(); i++) {
            Product product = shown.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("【").append(product.getName()).append("】")
                    .append('（').append(product.getCategory());
            if (notBlank(product.getBrand())) {
                sb.append(" · ").append(product.getBrand());
            }
            sb.append(" · ").append(dash(product.getProductNo()));
            sb.append("）\n");

            sb.append("  标价 ").append(priceText(product.getPrice()));
            if (product.getOriginalPrice() != null
                    && product.getPrice() != null
                    && product.getOriginalPrice().compareTo(product.getPrice()) > 0) {
                sb.append("（原价 ").append(product.getOriginalPrice().toPlainString()).append("）");
            }
            sb.append('\n');
        }

        if (other != null && shown.size() == MAX_SHOWN) {
            // ★ 只陈述两个数的大小关系，不给「哪个更值」的判断 ——
            //   那是 recommend_products 或者模型自己的事
            sb.append("\n两者的标价：").append(priceRelation(shown.get(0), shown.get(1))).append('\n');
        }

        if (stats != null) {
            // ★ 写「取样 N 个」而不是「在售 N 个」：样本上限是 STAT_SAMPLE，
            //   所以当类目里商品比它多时，「在售 200 个」是一句【关于平台的假话】——
            //   而它恰恰是模型会原样报给用户的那个数
            sb.append("\n同类目参照（").append(stats.category()).append("，取样 ")
                    .append(stats.sampleSize()).append(" 个在售商品）：")
                    .append("最低 ¥").append(stats.min().toPlainString())
                    .append("、中位 ¥").append(stats.median().toPlainString())
                    .append("、最高 ¥").append(stats.max().toPlainString())
                    .append('\n');
            sb.append("说明：上面这是【同类商品的价格分布】，不是推荐商品 —— ")
                    .append("它只回答「这个价位在同类里算高还是低」，不能用来判断哪个商品更好。");
        } else {
            sb.append("\n（这个商品没有类目信息，无法给出同类目价格参照。）");
        }

        sb.append("\n★ 以上是商品的【标价】。不同规格（颜色/容量）的价格可能不同 ——")
                .append("用户问某个具体规格的价格或库存时，用 query_inventory。");
        return sb.toString().stripTrailing();
    }

    private static String priceRelation(Product a, Product b) {
        if (a.getPrice() == null || b.getPrice() == null) {
            return "其中一个的价格未知，无法比较。";
        }
        int cmp = a.getPrice().compareTo(b.getPrice());
        if (cmp == 0) {
            return "两者标价相同，都是 ¥" + a.getPrice().toPlainString() + "。";
        }
        Product cheaper = cmp < 0 ? a : b;
        Product dearer = cmp < 0 ? b : a;
        return cheaper.getName() + " 便宜 ¥"
                + dearer.getPrice().subtract(cheaper.getPrice()).toPlainString()
                + "（¥" + cheaper.getPrice().toPlainString()
                + " vs ¥" + dearer.getPrice().toPlainString() + "）。";
    }

    private static String priceText(BigDecimal price) {
        // ★ 不填 0：填 0 模型会说「这个不要钱」，而实际上是我们不知道
        return price == null ? "（未知）" : "¥" + price.toPlainString();
    }

    // ============================================================
    // 给程序读的结构化数据
    // ============================================================

    private static Map<String, Object> dataOf(List<Product> shown, CategoryStats stats) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("products", shown.stream().map(ComparePricesTool::entry).toList());
        data.put("category_stats", stats == null ? null : stats.toMap());
        return data;
    }

    private static Map<String, Object> entry(Product product) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("product_no", product.getProductNo());
        row.put("name", product.getName());
        row.put("category", product.getCategory());
        row.put("brand", product.getBrand());
        row.put("price", product.getPrice() == null ? null : product.getPrice().toPlainString());
        row.put("original_price", product.getOriginalPrice() == null
                ? null : product.getOriginalPrice().toPlainString());
        return row;
    }

    /** 同类目的价格分布。{@code null} 表示这个商品没有类目信息 */
    private record CategoryStats(String category, BigDecimal min, BigDecimal median,
                                 BigDecimal max, int sampleSize) {

        Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", category);
            row.put("min", min.toPlainString());
            row.put("median", median.toPlainString());
            row.put("max", max.toPlainString());
            row.put("sample_size", sampleSize);
            return row;
        }
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
