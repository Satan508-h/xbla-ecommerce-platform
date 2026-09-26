package com.xbla.rag.service;

import java.time.Duration;
import java.util.Locale;

/**
 * {@code /api/status/metrics?window=} 的合法取值（阶段 9.6b）。
 *
 * <h3>★★ 为什么有 {@code all}，而计划里原本只有 {@code 24h|7d}</h3>
 *
 * <p>因为实测这台机器上的真实流量是<b>摊在 8 天里的</b>：
 *
 * <pre>
 *   09-18  12      09-21   1      09-25  35
 *   09-19 103      09-24   5      09-26   3   ← 今天
 *   09-20  47
 * </pre>
 *
 * <p>⇒ {@code 24h} 在演示环境里<b>几乎总是个位数</b>。这不是 bug，
 * 但只给 {@code 24h|7d} 的话，打开页面看到的是几个 0，
 * 而人会把它读成「这套东西没数据」。
 * {@code all} 让「这个库里到底有什么」可以一次看全。
 *
 * <h3>★ 不认识的值怎么办：替换 + 明说，不报错、也不静默</h3>
 *
 * <p>同 {@code ChatHistoryQueryServiceImpl.clampLimit} 那条做法 ——
 * 参数非法不该让一个只读端点回 400（那是给监控/探针打的）。
 * 但<b>替换必须留痕</b>：{@code MetricsSnapshot} 里同时有
 * {@code requestedWindow} 和 {@code window}，两者不同就说明被替换了，
 * 而且 {@code notes} 里会有一句。
 *
 * <p>★ 只留替换后的值的话，「你传错了参数」和「这个接口只有三个窗口」
 * 在响应里长得一模一样 —— 而前者是调用方要修的 bug。
 */
public enum MetricsWindow {

    /** 最近 24 小时。★ 默认值 */
    H24("24h", Duration.ofHours(24)),

    /** 最近 7 天 */
    D7("7d", Duration.ofDays(7)),

    /** 全部历史。{@code null} = 不设下界 */
    ALL("all", null);

    private final String code;
    private final Duration span;

    MetricsWindow(String code, Duration span) {
        this.code = code;
        this.span = span;
    }

    /** 对外的取值，也是响应里 {@code window} 字段的取值 */
    public String code() {
        return code;
    }

    /** 窗口跨度；{@link #ALL} 是 {@code null}（不设下界） */
    public Duration span() {
        return span;
    }

    /** 缺省窗口 —— 调用方没传 {@code window} 时用它 */
    public static MetricsWindow defaultWindow() {
        return H24;
    }

    /**
     * 解析 {@code window} 参数。
     *
     * @return 认识的窗口；<b>不认识时返回 {@code null}</b> ——
     *         ★ 这里刻意<b>不</b>返回缺省值：那样调用方就分不清
     *         「他传了 24h」和「他传了个垃圾」，也就没法把替换这件事说出来
     */
    public static MetricsWindow parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        for (MetricsWindow w : values()) {
            if (w.code.equals(v)) {
                return w;
            }
        }
        return null;
    }

    /** 三个合法取值的字符串，给错误提示用 */
    public static String supported() {
        StringBuilder sb = new StringBuilder();
        for (MetricsWindow w : values()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(w.code);
        }
        return sb.toString();
    }
}
