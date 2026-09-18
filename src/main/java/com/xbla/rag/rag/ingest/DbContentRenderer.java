package com.xbla.rag.rag.ingest;

import com.xbla.rag.entity.AfterSalePolicy;
import com.xbla.rag.entity.Product;
import com.xbla.rag.entity.ProductAttribute;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 把数据库里的业务行渲染成知识库文档。
 *
 * <h2>★ 为什么渲染成 Markdown</h2>
 *
 * <p>因为这样<b>数据库同步的文档和文件上传的文档走完全相同的下游链路</b>：
 * Markdown 里的 {@code #} 会被解析层的「策略③ 行首模式」识别成标题，
 * 于是切分、向量化、写库的代码一行都不用为数据源分叉。
 *
 * <p>渲染成纯文本（无标题）也能跑通，但那样所有内容会挤成一个大段落，
 * 切分时只能靠定长硬切 —— 商品名称、卖点、规格参数会被切进不同的切片，
 * 检索时问「这个适合送长辈吗」可能命中一段只有参数数字的切片。
 *
 * <h2>二、为什么商品描述要拆成这么多小节</h2>
 *
 * <p>看 {@link #renderProduct} 的结构 —— 基本信息 / 卖点 / 适用人群 / 规格参数
 * 是分开的四个二级标题。这不是为了好看，是为了让<b>每个小节成为独立的检索单元</b>。
 *
 * <p>体会一下这两个问题的差别：
 * <ul>
 *   <li>「这个手机多少钱」→ 应该命中「基本信息」那一节</li>
 *   <li>「这个适合送长辈吗」→ 应该命中「适用人群与场景」那一节</li>
 * </ul>
 *
 * <p>如果整段挤在一起，向量会把「价格 3999」和「适合送长辈」平均成一个模糊的点，
 * 两个问题匹配到的都是同一坨内容，谁也答不准。
 * <b>这正是 docs/04 里 {@code product.suitable_for} 字段存在的意义</b> ——
 * 用数据建模解决检索问题，比事后调 prompt 更根本。
 */
@Component
public class DbContentRenderer {

    /** 兜底文案：字段为空时不要留一个空的小节标题在那儿 */
    private static final String NOT_PROVIDED = "（暂无）";

    // ================================================================
    // 售后政策 → 文档
    // ================================================================

    /**
     * 渲染一条售后政策。
     *
     * <p>为什么把 {@code return_days} / {@code exchange_days} 这两个结构化字段
     * <b>也渲染进正文</b>：它们本来是为 MCP 工具做精确查询用的
     * （「退货几天」直接 {@code SELECT return_days}，比向量检索更准更快）。
     * 但用户也可能问「七天无理由是多长时间」，措辞和正文里的表述对不上 ——
     * 把数字显式写进正文，语义检索才有一条明确的线索。
     *
     * <p>同一条信息存两份（结构化字段 + 正文），服务两种查询方式，
     * 这是 docs/04 §七 里已经确定的设计。
     */
    public String renderPolicy(AfterSalePolicy policy) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(orDefault(policy.getTitle(), "售后政策")).append("\n\n");

        if (policy.getContent() != null && !policy.getContent().isBlank()) {
            sb.append(policy.getContent().trim()).append("\n\n");
        }

        if (policy.getReturnDays() != null || policy.getExchangeDays() != null) {
            sb.append("## 时限\n\n");
            if (policy.getReturnDays() != null) {
                sb.append("- 可退货天数：").append(policy.getReturnDays()).append(" 天\n");
            }
            if (policy.getExchangeDays() != null) {
                sb.append("- 可换货天数：").append(policy.getExchangeDays()).append(" 天\n");
            }
            sb.append('\n');
        }

        if (policy.getConditions() != null && !policy.getConditions().isBlank()) {
            sb.append("## 附加条件\n\n").append(policy.getConditions().trim()).append("\n\n");
        }

        if (policy.getCategory() != null && !policy.getCategory().isBlank()) {
            sb.append("## 适用类目\n\n").append(policy.getCategory().trim()).append("\n");
        }

        return sb.toString().trim();
    }

    // ================================================================
    // 商品 → 文档
    // ================================================================

    /**
     * 渲染一个商品。
     *
     * @param attributes 该商品的参数行，<b>可以为空</b>（不是所有商品都录了参数）
     */
    public String renderProduct(Product product, List<ProductAttribute> attributes) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(orDefault(product.getName(), "商品")).append("\n\n");

        // ---------- 基本信息 ----------
        sb.append("## 基本信息\n\n");
        appendIfPresent(sb, "商品编号", product.getProductNo());
        appendIfPresent(sb, "品牌", product.getBrand());
        appendIfPresent(sb, "类目", joinCategory(product.getCategory(), product.getSubCategory()));
        appendIfPresent(sb, "售价", formatPrice(product.getPrice(), "元"));
        appendIfPresent(sb, "原价", formatPrice(product.getOriginalPrice(), "元"));
        sb.append('\n');

        // ---------- 卖点 ----------
        if (notBlank(product.getSellingPoints())) {
            sb.append("## 卖点\n\n").append(product.getSellingPoints().trim()).append("\n\n");
        }

        // ---------- 适用人群与场景 ----------
        // ★ 这一节是阶段 5 演示题「这个适合送长辈吗」的命中目标。
        //   单独成节而不是并进描述里，就是为了让它成为一个独立的检索单元
        if (notBlank(product.getSuitableFor())) {
            sb.append("## 适用人群与场景\n\n")
              .append(product.getSuitableFor().trim()).append("\n\n");
        }

        // ---------- 商品描述 ----------
        if (notBlank(product.getDescription())) {
            sb.append("## 商品描述\n\n").append(product.getDescription().trim()).append("\n\n");
        }

        // ---------- 规格参数 ----------
        if (attributes != null && !attributes.isEmpty()) {
            sb.append("## 规格参数\n\n");
            String currentGroup = null;
            for (ProductAttribute attr : attributes) {
                // 参数按组展示。组名变化时起一个新的三级标题，
                // 这样「屏幕」相关的参数会聚成一个可检索的小单元
                String group = orDefault(attr.getAttrGroup(), "其他");
                if (!group.equals(currentGroup)) {
                    sb.append("\n### ").append(group).append("\n\n");
                    currentGroup = group;
                }
                sb.append("- ").append(orDefault(attr.getAttrName(), "参数"))
                  .append("：").append(orDefault(attr.getAttrValue(), NOT_PROVIDED)).append("\n");
            }
        }

        return sb.toString().trim();
    }

    // ================================================================
    // 小工具
    // ================================================================

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (notBlank(value)) {
            sb.append("- ").append(label).append("：").append(value.trim()).append('\n');
        }
    }

    private static String joinCategory(String category, String subCategory) {
        if (!notBlank(category)) {
            return subCategory;
        }
        if (!notBlank(subCategory)) {
            return category;
        }
        return category.trim() + " / " + subCategory.trim();
    }

    /**
     * 金额转字符串。
     *
     * <p>用 {@code toPlainString()} 而不是 {@code toString()}：
     * BigDecimal 的 {@code toString()} 在特定标度下会输出科学计数法
     * （{@code 1E+3} 而不是 {@code 1000}）。金额字段虽然现在不会是那种标度，
     * 但一旦输出了科学计数法，这段文本会被向量化并永久留在知识库里 ——
     * 用户搜「1000 元」永远搜不到。
     */
    private static String formatPrice(BigDecimal price, String unit) {
        return price == null ? null : price.toPlainString() + " " + unit;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String orDefault(String value, String fallback) {
        return notBlank(value) ? value.trim() : fallback;
    }
}
