package com.xbla.rag.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigRedactor} —— 配置快照的脱敏判据（阶段 7 · T3）。
 *
 * <h2>★ 它为什么值得一个专门的测试类</h2>
 *
 * <p>第一版用的是子串匹配，实测当场把两个<b>真配置</b>抹成了 {@code ***}：
 *
 * <pre>
 *   xbla.rag.fuse.keyword-weight     →  '***'   （真实值 1.0）
 *   xbla.rag.retrieve.keyword-top-k  →  '***'   （真实值 20）
 * </pre>
 *
 * <p>而这两个恰好就是阶段 7 要 A/B 的旋钮。这个 bug 的形态是
 * <b>「配置 diff 说这两轮没差别，而差别就在它们身上」</b> ——
 * 没有任何报错，结论从此不可信。同 ADR-080 那一类。
 *
 * <h2>★★ 每条断言都配了反对照</h2>
 *
 * <p>「{@code keyword-weight} 不被脱敏」这句话，如果 {@code looksSecret}
 * <b>永远返回 false</b> 也照样成立 —— 那样它测的是空气。
 * 所以每条「不脱敏」旁边都有一条「该脱敏的那一边<b>确实</b>脱了」。
 *
 * <p>而 {@link SubstringControl#theOldRuleWouldHaveRedactedTheAbaKnobs()}
 * 更进一步：它把<b>那个写出 bug 的判据本身</b>放进测试里当对照物，
 * 断言它<b>确实会</b>误伤 —— 如果哪天这条对照失效了（比如正则被「顺手改好」），
 * 所有「不脱敏」的断言就都会变成永真，而测试<b>仍然是绿的</b>。
 */
@DisplayName("ConfigRedactor · 配置快照的脱敏判据")
class ConfigRedactorTest {

    // ============================================================
    @Nested
    @DisplayName("一、该脱敏的（正）")
    class MustRedact {

        @ParameterizedTest(name = "{0} → ***")
        @DisplayName("键名里【整段】是密钥词的，值必须抹掉")
        @ValueSource(strings = {
                "xbla.llm.providers.deepseek.api-key",
                "xbla.llm.providers.siliconflow.api-key",
                "xbla.llm.providers.deepseek.apiKey",
                "xbla.datasource.password",
                "xbla.some.token",
                "xbla.mcp.credentials",
                "xbla.oss.access-key",
                "xbla.oss.accessKey",
                "xbla.oss.secret_key",
                "xbla.oss.private-key",
        })
        void secretNamesAreMasked(String name) {
            assertEquals(ConfigRedactor.MASK, ConfigRedactor.redact(name, "sk-realkey-value"),
                    name + " 是密钥，值不能出现在快照里（快照会落进报告文件）");
        }

        @Test
        @DisplayName("★ 反对照：同一个值换成普通键名就【不】抹 —— 证明上面测的是键名不是值")
        void theSameValueUnderAHarmlessNameSurvives() {
            assertEquals("sk-realkey-value",
                    ConfigRedactor.redact("xbla.rag.fuse.vector-weight", "sk-realkey-value"),
                    "判据必须是键名。若按值的样子判断，一个不带 sk- 前缀的密钥就会原样漏出去");
        }
    }

    // ============================================================
    @Nested
    @DisplayName("二、不该脱敏的（反）—— 实测被误伤过的真配置")
    class MustSurvive {

        @ParameterizedTest(name = "{0} → 原样保留")
        @DisplayName("名字里含 key 但【不是】密钥词的，值必须保留")
        @ValueSource(strings = {
                "xbla.rag.fuse.keyword-weight",
                "xbla.rag.fuse.keywordTopK",
                "xbla.rag.retrieve.keyword-top-k",
                "xbla.rag.retrieve.vector-top-k",
                "xbla.agent.intent.max-tokens",
                "xbla.rag.retrieve.max-query-tokens",
        })
        void keywordAndBudgetNamesSurvive(String name) {
            assertEquals("20", ConfigRedactor.redact(name, "20"),
                    name + " 不是密钥 —— 它抹掉之后，配置 diff 会说「这两轮没差别」，"
                            + "而差别恰恰就在这些旋钮身上");
        }

        @Test
        @DisplayName("★ 反对照：同一个名字换成密钥词就【被】抹 —— 证明上面不是恒真")
        void theSameShapeWithASecretWordIsMasked() {
            assertEquals(ConfigRedactor.MASK,
                    ConfigRedactor.redact("xbla.rag.fuse.apikey-weight", "20"),
                    "★ 这条是上面那组断言的反对照：`apikey` 是密钥整段，"
                            + "而 `keyword` 不是。两条一起才说明判据在做区分，"
                            + "而不是「永远返回 false」");
        }
    }

    // ============================================================
    @Nested
    @DisplayName("三、空值的三种形状必须分得开")
    class Emptiness {

        @Test
        @DisplayName("null 原样返回 —— 它表示「没有任何来源提供过这个名字」")
        void nullStaysNull() {
            assertNull(ConfigRedactor.redact("xbla.llm.providers.deepseek.api-key", null));
        }

        @Test
        @DisplayName("空串原样返回 —— 它表示「配了，但是空的」")
        void emptyStaysEmpty() {
            assertEquals("", ConfigRedactor.redact("xbla.llm.providers.deepseek.api-key", ""),
                    "★ 不能把它也抹成 *** —— 那样「忘了配」（API Key 为空 → 调用 401）"
                            + "和「配好了」就会长得一模一样，"
                            + "而那正是排查 401 时第一个要看的东西");
        }

        @Test
        @DisplayName("★ 反对照：非空的值在同一个键名下【是】被抹的")
        void nonEmptyIsMasked() {
            assertEquals(ConfigRedactor.MASK,
                    ConfigRedactor.redact("xbla.llm.providers.deepseek.api-key", "sk-x"),
                    "★ 上面两条的反对照：空串和 null 原样返回，"
                            + "不是因为「这个方法什么都不做」");
        }
    }

    // ============================================================
    @Nested
    @DisplayName("四、★★ 对照物：那个写出 bug 的判据本身")
    class SubstringControl {

        /**
         * ★★ 这个测试类里最有价值的一条。
         *
         * <p>它把<b>第一版真实写过的判据</b>放进测试，断言它<b>确实会</b>误伤
         * {@code keyword-weight}。这样做的意义是：
         * <b>如果哪天这条对照失效了</b>（有人「顺手把正则改好」、
         * 或者规则本身被换了别的写法），那么
         * {@link MustSurvive} 那一整组断言就会变成永真 ——
         * 而它们<b>仍然会是绿的</b>。
         *
         * <p>「一组永远不会红的断言」比没有断言更危险：它让人以为那里有保护。
         */
        @Test
        @DisplayName("★ 子串匹配【会】误伤 keyword-weight —— 这就是当初那个 bug")
        void theOldRuleWouldHaveRedactedTheAbaKnobs() {
            Pattern oldRule = Pattern.compile("(?i)(key|secret|password|token|credential)");

            assertTrue(oldRule.matcher("xbla.rag.fuse.keyword-weight").find(),
                    "★ 如果这条不成立了，说明对照物本身失效 —— "
                            + "那么「keyword 不被误伤」那组断言就退化成测空气了，"
                            + "请把它换成新的对照物");
            assertFalse(ConfigRedactor.looksSecret("xbla.rag.fuse.keyword-weight"),
                    "而现行判据必须是「不」—— 两条一起才说明这个修复真的发生了");
        }

        @Test
        @DisplayName("★ 两种判据在【真】密钥上结论一致 —— 修复没有把保护一起丢掉")
        void bothRulesAgreeOnRealSecrets() {
            Pattern oldRule = Pattern.compile("(?i)(key|secret|password|token|credential)");
            for (String name : new String[]{
                    "xbla.llm.providers.deepseek.api-key",
                    "xbla.oss.secret_key",
                    "xbla.oss.accessKey"}) {
                assertTrue(oldRule.matcher(name).find(), name + " 旧判据认为是密钥");
                assertTrue(ConfigRedactor.looksSecret(name),
                        "★ 新判据也必须是 —— 修复只该【少误伤】，"
                                + "不该连着真密钥一起放过");
            }
        }
    }
}
