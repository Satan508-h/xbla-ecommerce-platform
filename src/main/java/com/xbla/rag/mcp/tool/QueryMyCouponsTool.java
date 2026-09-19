package com.xbla.rag.mcp.tool;

import com.xbla.rag.entity.Coupon;
import com.xbla.rag.entity.UserCoupon;
import com.xbla.rag.mcp.McpArguments;
import com.xbla.rag.mcp.McpTool;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.McpToolResult;
import com.xbla.rag.mcp.ToolField;
import com.xbla.rag.service.CouponService;
import com.xbla.rag.service.UserCouponService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 「我有哪些券」—— 5.9 的第二个工具，<b>也是唯一一个无参工具</b>。
 *
 * <h2>一、★★ 为什么一个参数都不收</h2>
 *
 * <p>最自然的冲动是加一个 {@code status} 参数（"只看可用的" / "只看过期的"），
 * 因为用户确实会问「我有几张过期的券」。但那是个陷阱：
 *
 * <ul>
 *   <li><b>模型会填错。</b> 「我的券怎么用不了」该传哪个 status？
 *       传 {@code 1}（未使用）就正好把「过期的券」滤掉了 ——
 *       而那恰恰是用户问的东西。传 {@code 3} 又会漏掉「满减不够」的情况</li>
 *   <li><b>分组是「呈现」，不是「查询」。</b> 三个状态的券加起来也就几条，
 *       一次全查回来分组呈现，比让模型猜过滤条件稳得多</li>
 * </ul>
 *
 * <p>所以：<b>零参数，全查，分组呈现。</b> 模型拿到全部事实自己判断重点讲哪个。
 * 这和 {@code QueryOrderStatusTool} 只收一个 {@code order_no} 是同一条思路 ——
 * <b>入参只放「模型真的知道、而我们不可能知道」的东西</b>（订单号、
 * 商品名），其余一律我们自己查。
 *
 * <h2>二、★★ 「可用」的判据是两个条件的合取，不是 {@code status} 一个</h2>
 *
 * <p>直觉写法是 {@code status == 1}。但真实系统里「{@code status = 1}
 * 而 {@code expired_at} 已经过去」是<b>存在</b>的 —— 把过期券的状态刷成 3
 * 需要一个定时任务，而那个任务可能有延迟，或者压根没跑（本地库就没有）。
 *
 * <p>只信 status 的后果很具体：工具把一张过期的券报成「可用」，
 * 用户拿去下单被拒 —— <b>那时他不会再信这个助手</b>。
 * 而反过来（status=3 但 expired_at 还没到）不会发生，不用管。
 *
 * <p>所以判据是 {@code status == 1 && (expired_at == null || expired_at > now)}。
 * ★ 顺带发现不一致时<b>如实说</b>，不悄悄归到「已过期」里 ——
 * 见 {@link #render} 里那句「系统尚未标记」。
 *
 * <h2>三、★ 三种券的文案不写死在一处</h2>
 *
 * <p>{@code coupon.type} 是 1 满减 / 2 折扣 / 3 立减，三种的写法完全不同，
 * 而且 {@code discount_value} 和 {@code discount_rate} 是<b>二选一</b>
 * （数据库上有 CHECK：type ∈ (1,3) 用 value，type = 2 用 rate）。
 * 渲染时必须按 type 分派，不能写「{@code value != null ? … : …}」——
 * 那在 type=2 且 value 恰好非空时（数据脏了）会静默渲染成错的。
 */
@Component
public class QueryMyCouponsTool implements McpTool {

    /** 券类型。★ 和 {@code ck_coupon_type} 以及 {@code SeedDataFactory.createCoupons} 对齐 */
    private static final int TYPE_THRESHOLD = 1;    // 满减
    private static final int TYPE_DISCOUNT = 2;     // 折扣
    private static final int TYPE_CUT = 3;          // 立减

    /** 券状态。★ 和 {@code ck_user_coupon_status} 对齐 */
    private static final int STATUS_UNUSED = 1;
    private static final int STATUS_USED = 2;
    private static final int STATUS_EXPIRED = 3;

    /** 可用券最多列几张。超出的只报数量 —— 见 {@link #render} */
    private static final int MAX_LISTED_AVAILABLE = 10;
    /** 已使用 / 已过期各最多点名几张 */
    private static final int MAX_NAMED_OTHERS = 3;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UserCouponService userCouponService;
    private final CouponService couponService;

    public QueryMyCouponsTool(UserCouponService userCouponService, CouponService couponService) {
        this.userCouponService = userCouponService;
        this.couponService = couponService;
    }

    @Override
    public String name() {
        return "query_my_coupons";
    }

    @Override
    public String title() {
        return "查询我的优惠券";
    }

    @Override
    public String description() {
        return """
                查询【当前用户自己的】优惠券账户：有哪几张券、能不能用、什么时候过期、
                为什么用不了（未达门槛 / 品类不符 / 已过期）。

                什么时候用：用户在问「我的」券 ——
                「我有哪些优惠券」「我的券什么时候过期」「我的券怎么用不了」
                「我有几张过期的券」。

                什么时候【不要】用：
                - 问「优惠券怎么领」「满减规则是什么」这类通用规则 → 那是知识库的问题
                - 用户没说是「我的」，只是在讨论优惠活动 → 那也是知识库的问题
                """;
    }

    /** ★ 无参 —— 理由见类注释第一节 */
    @Override
    public List<ToolField> inputFields() {
        return List.of();
    }

    @Override
    public List<ToolField> outputFields() {
        return List.of(
                ToolField.requiredInt("available_count", "当前可用的券数"),
                ToolField.requiredInt("used_count", "已使用的券数"),
                ToolField.requiredInt("expired_count", "已过期的券数"),
                ToolField.requiredString("coupons", "券清单（结构化数组，见 data.coupons）"));
    }

    @Override
    public McpToolResult call(McpArguments args, McpToolContext context) {

        // ★★ 身份来自 context，不来自 args —— 而这个工具连 args 都没有，
        //   所以「越权」在这里连入口都不存在。这是无参工具的一个额外好处
        List<UserCoupon> held = userCouponService.lambdaQuery()
                .eq(UserCoupon::getUserId, context.userId())
                .orderByAsc(UserCoupon::getId)
                .list();

        if (held.isEmpty()) {
            return McpToolResult.ok(
                    "你的账户下没有任何优惠券记录（可用的、已使用的、已过期的都没有）。",
                    dataOf(0, 0, 0, List.of()));
        }

        // ── 取券模板。★ 一次批量查，不要在循环里逐个查 ──
        List<Long> couponIds = held.stream().map(UserCoupon::getCouponId).distinct().toList();
        Map<Long, Coupon> templates = couponService.listByIds(couponIds).stream()
                .collect(Collectors.toMap(Coupon::getId, Function.identity()));

        OffsetDateTime now = OffsetDateTime.now();
        List<Held> available = new ArrayList<>();
        List<Held> used = new ArrayList<>();
        List<Held> expired = new ArrayList<>();
        // ★ status 说「未使用」但时间说「已过期」的那些。见类注释第二节
        List<Held> staleStatus = new ArrayList<>();

        for (UserCoupon uc : held) {
            Coupon template = templates.get(uc.getCouponId());
            if (template == null) {
                // 外键保证了不会发生。真发生了说明数据被绕过外键改过 ——
                // ★ 如实报告，不要静默丢掉它（丢掉了用户会问「我明明有一张券」）
                expired.add(new Held(uc, null));
                continue;
            }
            Held h = new Held(uc, template);
            if (uc.getStatus() != null && uc.getStatus() == STATUS_USED) {
                used.add(h);
            } else if (isTimeExpired(uc, now)) {
                expired.add(h);
                if (uc.getStatus() != null && uc.getStatus() == STATUS_UNUSED) {
                    staleStatus.add(h);
                }
            } else {
                available.add(h);
            }
        }

        return McpToolResult.ok(
                render(available, used, expired, staleStatus, now),
                dataOf(available.size(), used.size(), expired.size(), available));
    }

    // ============================================================
    // 判据
    // ============================================================

    /**
     * 时间上过期了吗。
     *
     * <p>{@code expired_at} 为 null 时视为<b>不过期</b> —— 数据库上这一列可空，
     * 而「null = 永不过期」比「null = 已过期」安全得多：
     * 前者最坏是把一张实际上不存在的券当成可用（用户下单时会被拦），
     * 后者是把一张真券藏起来（用户根本无从发现）。
     */
    private static boolean isTimeExpired(UserCoupon uc, OffsetDateTime now) {
        return uc.getExpiredAt() != null && !uc.getExpiredAt().isAfter(now);
    }

    // ============================================================
    // 给模型读的正文
    // ============================================================

    private static String render(List<Held> available, List<Held> used,
                                 List<Held> expired, List<Held> staleStatus,
                                 OffsetDateTime now) {
        StringBuilder sb = new StringBuilder(512);

        if (available.isEmpty()) {
            sb.append("你目前没有可用的优惠券。");
        } else {
            sb.append("你有 ").append(available.size()).append(" 张可用的优惠券：\n");
            int listed = Math.min(available.size(), MAX_LISTED_AVAILABLE);
            for (int i = 0; i < listed; i++) {
                Held h = available.get(i);
                sb.append(i + 1).append(". ").append(h.template().getName())
                        .append(" —— ").append(conditionsOf(h.template()))
                        .append("，").append(formatDate(h.uc().getExpiredAt())).append(" 到期\n");
            }
            if (available.size() > listed) {
                sb.append("（还有 ").append(available.size() - listed)
                        .append(" 张可用券未列出）\n");
            }
        }

        // ★ 「已使用 / 已过期」只报数量 + 点名几张。它们通常不是用户问的重点，
        //   但「为什么我的券不见了」这类问题必须能从这句话里得到答案
        String others = describeOthers("已使用", used) + describeOthers("已过期", expired);
        if (!others.isEmpty()) {
            sb.append("\n另有：").append(others).append("。\n");
        }

        // ★★ 数据不一致要说出来，见类注释第二节
        if (!staleStatus.isEmpty()) {
            sb.append("\n⚠️ 其中 ").append(staleStatus.size())
                    .append(" 张券的状态仍显示为「未使用」，但有效期已过，实际不可用：")
                    .append(namesOf(staleStatus)).append("。\n");
        }

        sb.append("\n（查询时间：").append(formatDate(now)).append("）");
        return sb.toString().stripTrailing();
    }

    private static String describeOthers(String label, List<Held> list) {
        if (list.isEmpty()) {
            return "";
        }
        return label + " " + list.size() + " 张（" + namesOf(list) + "）";
    }

    private static String namesOf(List<Held> list) {
        int named = Math.min(list.size(), MAX_NAMED_OTHERS);
        String names = list.subList(0, named).stream()
                .map(h -> h.template() == null ? "（券模板已失效）" : h.template().getName())
                .collect(Collectors.joining("、"));
        return list.size() > named ? names + " 等 " + list.size() + " 张" : names;
    }

    /**
     * 一张券的「使用条件」那一句。
     *
     * <p>★ 三个 type 各自分派，<b>不写「value 非空就用 value」</b> —— 见类注释第三节。
     */
    private static String conditionsOf(Coupon coupon) {
        StringBuilder sb = new StringBuilder();

        Integer type = coupon.getType();
        if (type != null && type == TYPE_DISCOUNT && coupon.getDiscountRate() != null) {
            sb.append("打 ").append(discountText(coupon.getDiscountRate())).append(" 折");
        } else if (type != null && type == TYPE_CUT && coupon.getDiscountValue() != null) {
            sb.append("立减 ").append(money(coupon.getDiscountValue())).append(" 元");
        } else if (coupon.getDiscountValue() != null) {
            sb.append("减 ").append(money(coupon.getDiscountValue())).append(" 元");
        } else {
            sb.append("（这张券的优惠信息不完整）");
        }

        if (coupon.getApplicableCategory() != null && !coupon.getApplicableCategory().isBlank()) {
            sb.append("，仅限「").append(coupon.getApplicableCategory()).append("」类目");
        } else {
            sb.append("，全场通用");
        }

        if (coupon.getThresholdAmount() != null
                && coupon.getThresholdAmount().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("，订单满 ").append(money(coupon.getThresholdAmount())).append(" 元可用");
        }

        return sb.toString();
    }

    /**
     * 金额 → 人话。
     *
     * <p>★★ <b>和 {@code QueryOrderStatusTool} 的 {@code pay_amount} 刻意不同。</b>
     * 那边用的是 {@code toPlainString()}，因为它渲染的是<b>算出来的实付金额</b> ——
     * 7362.24 这种值，两位小数是它的精度，去掉就是丢信息。
     *
     * <p>券的面额和门槛不一样：它们是<b>平台设定的营销数字</b>，
     * 而 {@code numeric(10,2)} 只是存它的容器。数据库给回来的是
     * 「600.00」「5000.00」，直接渲染成「减 600.00 元」「订单满 5000.00 元可用」——
     * <b>没有一条真实的优惠券文案是这样写的</b>。
     *
     * <p>⚠️ {@code stripTrailingZeros} 之后必须走 {@code toPlainString}：
     * 它对 {@code 1000.00} 返回的是 {@code 1E+3}（scale 变成 -3），
     * 而 {@code toString()} 会把这个科学计数法原样打出来 ——
     * 于是「满 1000 减 100」变成「满 1E+3 减 100」。
     * 这个坑只在<b>末尾有 0 且整数部分以 0 结尾</b>时出现，
     * 用 600 试是试不出来的。
     */
    private static String money(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    /**
     * 折扣率 → 中文「折」。
     *
     * <p>★ 不是简单的 {@code ×10}：0.90 要写「9 折」而不是「9.0 折」。
     * 用 {@code stripTrailingZeros} 而不是 {@code setScale(1)}，
     * 因为将来可能出现 0.88（八八折）这种两位小数。
     */
    private static String discountText(BigDecimal rate) {
        return rate.multiply(BigDecimal.TEN).stripTrailingZeros().toPlainString();
    }

    private static String formatDate(OffsetDateTime time) {
        return time == null ? "长期有效" : DATE.format(time);
    }

    // ============================================================
    // 给程序读的结构化数据
    // ============================================================

    /**
     * ★ 用 {@link LinkedHashMap} 逐个 put，<b>不用 {@code Map.of}</b> ——
     * 与 {@code QueryOrderStatusTool} 一致：键序稳定才让两次查询的 JSON 能直接 diff。
     */
    private static Map<String, Object> dataOf(int available, int used, int expired,
                                              List<Held> listed) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available_count", available);
        data.put("used_count", used);
        data.put("expired_count", expired);
        data.put("coupons", listed.stream().map(QueryMyCouponsTool::couponEntry).toList());
        return data;
    }

    private static Map<String, Object> couponEntry(Held h) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("coupon_no", h.template() == null ? null : h.template().getCouponNo());
        entry.put("name", h.template() == null ? null : h.template().getName());
        entry.put("status_text", statusText(h.uc().getStatus()));
        entry.put("discount_text", h.template() == null ? null : conditionsOf(h.template()));
        entry.put("applicable_category",
                h.template() == null ? null : h.template().getApplicableCategory());
        entry.put("threshold_amount",
                h.template() == null || h.template().getThresholdAmount() == null
                        ? null : money(h.template().getThresholdAmount()));
        entry.put("expired_at", formatDate(h.uc().getExpiredAt()));
        return entry;
    }

    private static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case STATUS_UNUSED -> "可用";
            case STATUS_USED -> "已使用";
            case STATUS_EXPIRED -> "已过期";
            default -> "未知状态(" + status + ")";
        };
    }

    /** 一张「用户持有的券」= 持有记录 + 它的模板。★ 模板可能为 null（数据异常） */
    private record Held(UserCoupon uc, Coupon template) {
    }
}
