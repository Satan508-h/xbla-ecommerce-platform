package com.xbla.rag.common;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 给配置快照脱敏 —— <b>按【键名】判断，不按键值的样子</b>（阶段 7 · T3）。
 *
 * <h2>★ 为什么按名字不按值</h2>
 *
 * <p>按值判断（「看起来像 {@code sk-...} 就抹掉」）两边都错：
 * <ul>
 *   <li>值里出现 {@code sk-} 的普通字符串会被误伤</li>
 *   <li>一个不带任何特征前缀的密钥会<b>原样漏出去</b> —— 而漏出去是没有症状的</li>
 * </ul>
 * 键名是配置作者<b>声明过的意图</b>，它才是可靠的那一半。
 *
 * <h2>★★ 为什么是「整段等于」而不是「键名包含」</h2>
 *
 * <p>第一版用子串匹配（正则 {@code (?i)(key|secret|...)}），实测当场误伤两个：
 *
 * <pre>
 *   xbla.rag.fuse.keyword-weight     →  '***'   （真实值 1.0）
 *   xbla.rag.retrieve.keyword-top-k  →  '***'   （真实值 20）
 * </pre>
 *
 * <p>因为 {@code keyword} 里含 {@code key} 三个字母。而这两个<b>恰好就是
 * 阶段 7 要 A/B 的两个旋钮</b> —— 配置快照会说「这两轮没差别」，
 * 而差别就在它们身上。<b>不报错</b>，只是结论从此不可信。
 *
 * <p>★ 两类误判的代价是不对称的：漏脱敏泄露密钥，多脱敏只是少一个数。
 * 所以词表宁可宽（{@code apikey} / {@code secretkey} 这些粘在一起的写法也列上），
 * 但<b>匹配方式必须是整段</b> —— 子串匹配制造的不是「少一个数」，
 * 是「一个看起来正常的错数」。
 *
 * <p>★ 刻意<b>不</b>把 {@code tokens} 收进来：{@code xbla.agent.intent.max-tokens}
 * 和 {@code xbla.rag.retrieve.max-query-tokens} 是预算，不是凭据。
 */
public final class ConfigRedactor {

    private ConfigRedactor() {
    }

    /** 键名里出现这些<b>整段</b>词的，值一律不返回。 */
    private static final Set<String> SECRET_SEGMENTS = Set.of(
            "key", "keys", "secret", "secrets", "password", "passwd",
            "token", "credential", "credentials",
            "apikey", "secretkey", "accesskey", "privatekey");

    /**
     * 把配置键切成段：分隔符（{@code . _ -}）+ 驼峰边界。
     *
     * <pre>
     *   xbla.rag.fuse.keyword-weight  →  [xbla, rag, fuse, keyword, weight]
     *   xbla.llm.providers.x.api-key  →  [xbla, llm, providers, x, api, key]
     *   xbla.llm.providers.x.apiKey   →  [xbla, llm, providers, x, api, Key]
     * </pre>
     */
    private static final Pattern SEGMENT_SPLIT =
            Pattern.compile("[.\\-_]|(?<=[a-z0-9])(?=[A-Z])");

    /** 脱敏后放进去的字面量。★ 与「空串」「字段缺席」三者刻意分得开。 */
    public static final String MASK = "***";

    /**
     * 脱敏 —— 三种结果刻意分得开：
     * <pre>
     *   字段缺席  →  调用方根本不会调到这个方法（没有任何属性来源提供过它）
     *   ""        →  提供了，但是空串（= 没配）
     *   "***"     →  配了，且是非空的值
     * </pre>
     * 如果统一成 {@code "***"}，「忘了配」和「配了」就会看起来一样 ——
     * 而那正是排查「模型调用 401」时第一个要看的东西。
     */
    public static String redact(String name, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return looksSecret(name) ? MASK : value;
    }

    /** 键名按 {@link #SEGMENT_SPLIT} 切开后，有没有哪一段<b>整个</b>落在密钥词表里。 */
    public static boolean looksSecret(String name) {
        for (String segment : SEGMENT_SPLIT.split(name)) {
            if (SECRET_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
