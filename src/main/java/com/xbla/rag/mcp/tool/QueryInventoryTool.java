package com.xbla.rag.mcp.tool;

import com.xbla.rag.entity.Inventory;
import com.xbla.rag.entity.Product;
import com.xbla.rag.entity.ProductSku;
import com.xbla.rag.mcp.McpArguments;
import com.xbla.rag.mcp.McpTool;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.McpToolResult;
import com.xbla.rag.mcp.ToolField;
import com.xbla.rag.service.InventoryService;
import com.xbla.rag.service.ProductService;
import com.xbla.rag.service.ProductSkuService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 「这款还有货吗」—— 5.9 的第三个工具。
 *
 * <h2>一、★★ 它比订单工具难，难在「商品名不是主键」</h2>
 *
 * <p>{@code QueryOrderStatusTool} 的用户会报出订单号 —— 那是主键，精确匹配就完了。
 * 而库存查询的入口是<b>商品名</b>，一个用户随手打的、可能不完整、可能不准确的字符串。
 * 更麻烦的是本项目的 200 个商品名长得很像：
 *
 * <pre>
 *   华为 Magic mini    华为 Mate Ultra    华为 Find Pro Max
 *   vivo P Pro Max     vivo Find Pro Max   vivo X Pro Max
 * </pre>
 *
 * <p>所以「匹配到几个」不是一个可以绕开的实现细节，<b>它就是工具的语义</b>。
 *
 * <h2>二、★★ 只匹配商品名，<b>不</b>匹配品牌和类目</h2>
 *
 * <p>把 {@code brand} 和 {@code category} 也放进 {@code OR} 里看起来很贴心，
 * 但它会制造一个<b>非常危险</b>的假象。用户问「手机还有货吗」时：
 *
 * <pre>
 *   只匹配 name → 0 命中 → 「没有找到名称为『手机』的商品」
 *                 ✅ 正确。用户问的是品类，不是某一款商品
 *
 *   加上 brand/category → 命中 40 个 → 返回前 3 个
 *                 ❌ 灾难。那 3 个是【按 id 排序的前 3 个】，
 *                    和用户想问的东西毫无关系，但它看起来像一份答案
 * </pre>
 *
 * <p>★ 这和 {@code ADR-044} 那条「工具意图没有检索时模型会编一个订单状态出来」
 * 是<b>同一类风险</b>：我们提供了一个看起来像答案的东西，
 * 而模型会把它当成答案。<b>宁可回「没找到」，也不要回一份随机的候选。</b>
 *
 * <h2>三、★ 命中多个时<b>全部返回</b>，不替模型做裁决</h2>
 *
 * <p>「华为」会命中 7 个商品。这时有两条路：回一句「找到多个，请用户确认」，
 * 或者把候选都给它。
 *
 * <p>选后者。<b>模型知道我们不知道的东西</b> —— 用户上一轮说过
 * 「我想要个拍照好的」，而候选里有「华为 Mate Ultra」。它能做出更好的判断。
 * 这与 5.7 定下的分工一致：<b>工具负责给事实，判断留给模型。</b>
 *
 * <h2>四、库存挂在 SKU 上，所以「有没有货」必须说到规格</h2>
 *
 * <p>{@code inventory} 表的主键是 {@code sku_id}，一个商品对应 2~4 个 SKU
 * （不同颜色 / 容量）。所以「华为 Magic mini 还有货吗」这句话本身
 * <b>是没有答案的</b> —— 月光银可能没货而晨曦金有 472 件。
 *
 * <p>工具因此<b>不输出「合计库存」这一项</b>，只输出每个规格各自的库存，
 * 并让正文里那句「各规格之间不能互相顶替」把这个事实讲明白。
 * ★ 给合计会诱使模型说「还有 1249 件」—— 一个数字正确、
 * 但对「我想买月光银」这个真实问题<b>完全错误</b>的回答。
 *
 * <h2>五、★★ 商品名<b>会重复</b>，所以正文里必须带商品编号</h2>
 *
 * <p>实测（2026-09-20，跑真实数据）：200 个商品里有
 * <b>17 组、共 35 个重名</b> ——
 * {@code product.name} 上没有唯一约束（唯一的是 {@code product_no}），
 * 而名称是「品牌 + 型号」拼出来的，型号词表不够大：
 *
 * <pre>
 *   华为 Magic mini   → P000001 / P000013
 *   小米 BCD Pro      → P000177 / P000179 / P000188
 * </pre>
 *
 * <p>⚠️ <b>这不是种子数据的 bug，是真实电商的常态</b> ——
 * 同名不同款（不同年份、不同批次、渠道专供）到处都有。工具必须能表达它。
 *
 * <p>所以正文的标题里<b>总是</b>带商品编号。★ 注意「总是」这两个字：
 * 写成「只在检测到重名时才带」会让<b>正文的形状随数据变化</b>，
 * 而正文长度直接关系到 {@code ToolLoop.MAX_TOOL_RESULT_CHARS} 的截断位置、
 * 以及 prompt 前缀的稳定性 —— 那些代价远大于多打 8 个字符。
 *
 * <p>★ 顺带一个只有跑起来才会发现的事实：这个重名是
 * <b>探针第一次手动调这个工具时</b>看到的，而它当时<b>看起来像我的实现有 bug</b>
 * （「怎么返回了两个同名商品？」）。真相在数据里，不在代码里。
 */
@Component
public class QueryInventoryTool implements McpTool {

    /**
     * ★ 一次最多返回几个商品。
     *
     * <p>定 3 不是随手取的：一个商品最多 4 个规格，3 × 4 = 12 行，
     * 加上表头大约 400 字 —— 在 {@code ToolLoop.MAX_TOOL_RESULT_CHARS}（4000）
     * 的十分之一以内。定得太大会让「命中很多」时正文被截断，
     * 而<b>截断发生在末尾</b>，模型看到的是「前几个商品完整、后面的凭空消失」。
     */
    private static final int MAX_PRODUCTS = 3;

    /** ★ 字段常量 —— schema 和取值都走它，见 {@code ToolField} 的类注释 */
    private static final ToolField PRODUCT_NAME = ToolField.requiredString(
            "product_name",
            "商品名称，从用户的话里原样取。形如「华为 Magic mini」「星辰X1」。"
                    + "★ 必须是具体的商品名 —— 不要传「手机」「笔记本电脑」这种品类词，"
                    + "那些不是商品名，查不到。用户只说了品类时，先问用户具体是哪一款。");

    private static final ToolField SPEC = ToolField.optionalString(
            "spec",
            "规格关键词，可选。比如「512GB」「月光银」。"
                    + "用户明确说了颜色或容量时才传；没说就别传，让工具把所有规格都列出来。");

    private final ProductService productService;
    private final ProductSkuService productSkuService;
    private final InventoryService inventoryService;

    public QueryInventoryTool(ProductService productService,
                              ProductSkuService productSkuService,
                              InventoryService inventoryService) {
        this.productService = productService;
        this.productSkuService = productSkuService;
        this.inventoryService = inventoryService;
    }

    @Override
    public String name() {
        return "query_inventory";
    }

    @Override
    public String title() {
        return "查询商品库存";
    }

    @Override
    public String description() {
        return """
                查询某个【具体商品】的实时库存：还有没有货、哪个颜色/容量有货、在哪个仓。

                什么时候用：用户在问某一款商品还有没有货 ——
                「这款还有货吗」「华为 Magic mini 什么时候补货」「512GB 的还有吗」。

                什么时候【不要】用：
                - 用户问的是品类（「手机还有货吗」）→ 先问具体是哪一款
                - 用户问的是「好不好」「值不值得买」→ 那是知识库的问题
                - 用户问的是「我买的那笔发货了吗」→ 那用 query_order_status
                """;
    }

    @Override
    public List<ToolField> inputFields() {
        return List.of(PRODUCT_NAME, SPEC);
    }

    @Override
    public List<ToolField> outputFields() {
        return List.of(
                ToolField.requiredInt("matched_count", "名称匹配到的商品数量（可能大于实际返回的）"),
                ToolField.requiredInt("returned_count", "本次实际返回的商品数量"),
                ToolField.requiredString("products", "商品清单（结构化数组，见 data.products）"));
    }

    @Override
    public McpToolResult call(McpArguments args, McpToolContext context) {
        String productName = PRODUCT_NAME.requireString(args).trim();
        String spec = SPEC.optionalString(args);
        String specKeyword = spec == null ? null : spec.trim();

        // ── ① 按名称模糊匹配 ──
        //
        // ★ 只匹配 name —— 理由见类注释第二节。
        //
        // ⚠️ 模型传进来的字符串里如果含 % 或 _，它们会被当作 LIKE 的通配符
        //    （MyBatis-Plus 的 like 是把值作为参数拼进 CONCAT('%', ?, '%') 的，
        //    通配符在【值】里，所以照样生效）。这里不做转义：
        //    商品目录是公开信息，最坏结果是「返回前 3 个商品」，不是越权。
        //    真正需要转义的是「用 LIKE 做权限判断」的场景，本项目没有。
        //
        // ★ 多取一条（MAX_PRODUCTS + 1）用来判断「还有更多」——
        //   这比再发一次 count 查询便宜，也不会因为两次查询之间数据变了而不一致
        List<Product> matched = productService.lambdaQuery()
                .like(Product::getName, productName)
                .eq(Product::getStatus, 1)
                .eq(Product::getDeleted, 0)
                .orderByAsc(Product::getId)
                .last("LIMIT " + (MAX_PRODUCTS + 1))
                .list();

        if (matched.isEmpty()) {
            return McpToolResult.ok(notFoundText(productName),
                    dataOf(0, List.of()));
        }

        boolean hasMore = matched.size() > MAX_PRODUCTS;
        List<Product> returned = hasMore ? matched.subList(0, MAX_PRODUCTS) : matched;

        // ── ② 取这些商品的全部 SKU ──
        List<Long> productIds = returned.stream().map(Product::getId).toList();
        List<ProductSku> allSkus = productSkuService.lambdaQuery()
                .in(ProductSku::getProductId, productIds)
                .eq(ProductSku::getStatus, 1)
                .eq(ProductSku::getDeleted, 0)
                .orderByAsc(ProductSku::getId)
                .list();

        // ── ③ 一次取回所有库存，别在循环里逐个查（N+1）──
        Map<Long, Inventory> stockBySku = allSkus.isEmpty()
                ? Map.of()
                : inventoryService.lambdaQuery()
                        .in(Inventory::getSkuId, allSkus.stream().map(ProductSku::getId).toList())
                        .list().stream()
                        .collect(Collectors.toMap(Inventory::getSkuId, Function.identity(),
                                (a, b) -> a));

        // ── ④ 组装 ──
        List<ProductStock> stocks = new ArrayList<>(returned.size());
        for (Product product : returned) {
            List<ProductSku> skus = allSkus.stream()
                    .filter(s -> s.getProductId().equals(product.getId()))
                    .toList();
            List<SkuStock> rows = new ArrayList<>(skus.size());
            for (ProductSku sku : skus) {
                Inventory inv = stockBySku.get(sku.getId());
                rows.add(new SkuStock(sku, inv,
                        inv == null ? null : inv.getAvailableStock(),
                        inv == null ? null : inv.getWarehouse()));
            }

            // ★ spec 过滤【在 Java 侧做】，因为它在 SKU 表上而不是商品表上。
            //   一个商品最多 4 个 SKU，多取几十行也远比多一次 SQL 划算
            List<SkuStock> shown = specKeyword == null || specKeyword.isBlank()
                    ? rows
                    : rows.stream().filter(r -> r.sku().getSpecName() != null
                            && r.sku().getSpecName().contains(specKeyword)).toList();
            stocks.add(new ProductStock(product, rows, shown));
        }

        return McpToolResult.ok(
                render(productName, specKeyword, stocks, hasMore),
                dataOf(matched.size(), stocks));
    }

    // ============================================================
    // 给模型读的正文
    // ============================================================

    private static String notFoundText(String productName) {
        // ★ 不猜、不给「你是不是想找…」的候选 —— 见类注释第二节。
        //   一句诚实的「没找到」+ 告诉模型下一步该做什么，比一份随机候选安全得多
        return "没有找到名称为「" + productName + "」的商品。\n"
                + "请让用户核对一下商品名称。如果用户说的是简称或品类"
                + "（比如「那款华为」「手机」），请先问清楚具体是哪一款商品，"
                + "不要自己猜一个名字再查一次。";
    }

    private static String render(String productName, String specKeyword,
                                 List<ProductStock> stocks, boolean hasMore) {
        StringBuilder sb = new StringBuilder(768);

        sb.append("名称包含「").append(productName).append("」的商品：")
                .append(stocks.size()).append(" 个");
        if (hasMore) {
            sb.append("（只显示了前 ").append(stocks.size()).append(" 个，还有更多）");
        }
        sb.append("。\n");

        for (ProductStock stock : stocks) {
            Product product = stock.product();
            sb.append('\n').append("【").append(product.getName()).append("】")
                    .append('（').append(product.getCategory());
            if (product.getBrand() != null && !product.getBrand().isBlank()) {
                sb.append(" · ").append(product.getBrand());
            }
            // ★★ 商品编号【总是】带上，尽管它看起来像噪音。
            //   理由不是「怕重名」，见下面那段 —— 是「正文的形状不能随数据变」。
            sb.append(" · ").append(nullToDash(product.getProductNo()));
            sb.append("）\n");

            List<SkuStock> shown = stock.shown();
            if (shown.isEmpty()) {
                // ★★ 这是「商品存在，但没有匹配的规格」—— 和「商品不存在」
                //    是完全不同的两件事，必须让模型能区分
                sb.append("  该商品有 ").append(stock.all().size())
                        .append(" 个规格，但其中【没有】规格包含「")
                        .append(specKeyword).append("」的。\n");
                sb.append("  现有规格：").append(specNames(stock.all())).append("\n");
                continue;
            }

            for (SkuStock row : shown) {
                sb.append("  ").append(nullToDash(row.sku().getSpecName()));
                sb.append(" —— ").append(stockText(row));
                if (row.warehouse() != null && !row.warehouse().isBlank()) {
                    sb.append('（').append(row.warehouse()).append('）');
                }
                sb.append('\n');
            }

            long outOfStock = shown.stream().filter(r -> isOutOfStock(r)).count();
            if (outOfStock == shown.size()) {
                sb.append("  ↑ 该商品当前【全部规格都无货】\n");
            } else if (outOfStock > 0) {
                sb.append("  ↑ 其中 ").append(outOfStock).append(" 个规格暂时无货\n");
            }
        }

        // ★ 这句话是给模型的【事实陈述】，不是指令 —— 见类注释第四节
        sb.append("\n说明：库存按规格（颜色/容量）分别计算，各规格之间不能互相顶替。");
        return sb.toString().stripTrailing();
    }

    private static String stockText(SkuStock row) {
        if (row.availableStock() == null) {
            // 有 SKU 却没有库存行 —— 数据不完整。★ 如实说，别填 0：
            //   填 0 模型会说「没货」，而实际上是「我们不知道」
            return "库存数据缺失";
        }
        if (row.availableStock() <= 0) {
            return "【暂时无货】";
        }
        return "可售 " + row.availableStock() + " 件";
    }

    private static boolean isOutOfStock(SkuStock row) {
        return row.availableStock() != null && row.availableStock() <= 0;
    }

    private static String specNames(List<SkuStock> rows) {
        return rows.stream()
                .map(r -> nullToDash(r.sku().getSpecName()))
                .collect(Collectors.joining("、"));
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "（未命名规格）" : value;
    }

    // ============================================================
    // 给程序读的结构化数据
    // ============================================================

    private static Map<String, Object> dataOf(int matchedCount, List<ProductStock> stocks) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("matched_count", matchedCount);
        data.put("returned_count", stocks.size());
        data.put("products", stocks.stream().map(QueryInventoryTool::productEntry).toList());
        return data;
    }

    private static Map<String, Object> productEntry(ProductStock stock) {
        Map<String, Object> entry = new LinkedHashMap<>();
        Product product = stock.product();
        entry.put("product_no", product.getProductNo());
        entry.put("name", product.getName());
        entry.put("category", product.getCategory());
        entry.put("brand", product.getBrand());
        entry.put("skus", stock.shown().stream().map(QueryInventoryTool::skuEntry).toList());
        return entry;
    }

    private static Map<String, Object> skuEntry(SkuStock row) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sku_no", row.sku().getSkuNo());
        entry.put("spec_name", row.sku().getSpecName());
        entry.put("price", row.sku().getPrice() == null
                ? null : row.sku().getPrice().toPlainString());
        entry.put("available_stock", row.availableStock());
        entry.put("warehouse", row.warehouse());
        return entry;
    }

    // ============================================================
    // 内部记录
    // ============================================================

    /**
     * 一个商品的库存。
     *
     * @param all  该商品的<b>全部</b>在售规格
     * @param shown 本次实际展示的规格。<b>和 {@code all} 不同</b>当且仅当
     *              传了 {@code spec} 且过滤掉了东西 —— 此时正文里要说明
     *              「有 N 个规格，但没有包含 X 的」
     */
    private record ProductStock(Product product, List<SkuStock> all, List<SkuStock> shown) {
    }

    /**
     * 一个规格的库存。
     *
     * @param availableStock {@code null} 表示<b>查不到库存行</b>，
     *                       和 {@code 0}（真的没货）是两回事
     * @param warehouse      仓库。{@code null} 时正文里不显示仓库那一格
     */
    private record SkuStock(ProductSku sku, Inventory inventory,
                            Integer availableStock, String warehouse) {
    }
}
