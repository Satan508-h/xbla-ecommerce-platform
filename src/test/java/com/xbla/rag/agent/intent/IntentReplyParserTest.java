package com.xbla.rag.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型回复的解析（阶段 9.2）。
 *
 * <p><b>不花一分钱</b>：本类是纯解析，没有模型、没有数据库、没有 Spring。
 *
 * <h2>★★ 它守的那件事：两种契约的切换必须【可判】</h2>
 *
 * <p>9.2 把分类的输出从「一个裸码」扩成了「一行 JSON」。而这件事最可能失败的方式
 * <b>是静默的</b>：
 *
 * <pre>
 *   prompt 改了，模型照旧吐裸码
 *   → 解析走回退 → 分类照样成功、问答照样回答
 *   → 而门控一次都没生效过
 * </pre>
 *
 * <p>所以本类的每一条断言都同时在守两件事：<b>新契约能被读懂</b>，
 * 以及<b>「模型没跟上的时候，我们能看得出来」</b>（{@link IntentPlan.Shape}）。
 */
@DisplayName("IntentReplyParser · 模型回复的解析")
class IntentReplyParserTest {

    @TempDir
    Path tempDir;

    private IntentTree.Tree tree;
    private IntentReplyParser parser;

    @BeforeEach
    void setUp() throws IOException {
        Path treeFile = tempDir.resolve("tree.yml");
        Files.writeString(treeFile, treeYaml(), StandardCharsets.UTF_8);
        tree = new IntentTree(treeFile).get();
        parser = new IntentReplyParser(new ObjectMapper());
    }

    /**
     * 一棵最小但覆盖三种 retrieval 的树。
     *
     * <p>⚠️ KB 类必须有 <b>4 个</b>顶层：加载期校验强制「业务意图恰好 5 类」，
     * 4 个 KB + 1 个 TOOL 才凑得齐。
     */
    private static String treeYaml() {
        StringBuilder yaml = new StringBuilder("version: 1\nintents:\n");
        for (int i = 1; i <= 4; i++) {
            yaml.append("  - code: TOP_").append(i).append('\n')
                .append("    name: 顶层").append(i).append('\n')
                .append("    description: 顶层").append(i).append("的判据\n")
                .append("    answer_style: 风格").append(i).append('\n')
                .append("    retrieval: KB\n")
                .append("    children:\n")
                .append("      - code: LEAF_").append(i).append('\n')
                .append("        name: 叶子").append(i).append('\n')
                .append("        description: 叶子").append(i).append("的判据\n")
                .append("        doc_types: [").append(i).append("]\n")
                .append("        examples: [树示例").append(i).append("]\n");
        }
        yaml.append("""
                  - code: TOP_5
                    name: 工具类
                    description: 需要查实时数据
                    answer_style: 只陈述查到的
                    retrieval: TOOL
                    children:
                      - code: TOOL_LEAF
                        name: 工具叶子
                        description: 某个工具
                        doc_types: []
                        examples: [树的工具示例]
                  - code: OUT_OF_SCOPE
                    name: 兜底
                    role: OUT_OF_SCOPE
                    description: 与平台无关
                    answer_style: 说明范围
                    retrieval: NONE
                    children: []
                """.replace("\n                ", "\n    "));
        return yaml.toString();
    }

    private IntentReplyParser.ParsedReply parse(String raw) {
        return parser.parse(raw, tree);
    }

    // ============================================================
    // 一、新契约（JSON）
    // ============================================================

    @Nested
    @DisplayName("一、新契约：一行 JSON")
    class JsonContract {

        @Test
        @DisplayName("★ 标准形态：四个键都读出来，shape=JSON")
        void parsesFullJson() {
            IntentReplyParser.ParsedReply reply = parse(
                    "{\"v\":1,\"intent\":\"LEAF_1\",\"retrieve\":true,\"missing\":[\"purpose\",\"budget\"]}");

            assertThat(reply.ok()).isTrue();
            assertThat(reply.code()).isEqualTo("LEAF_1");
            assertThat(reply.plan().retrieve()).isTrue();
            assertThat(reply.plan().missingSlots()).containsExactly("purpose", "budget");
            assertThat(reply.plan().shape()).isEqualTo(IntentPlan.Shape.JSON);
            assertThat(reply.note()).as("走对了契约就不该有告警").isNull();
        }

        @Test
        @DisplayName("★★ retrieve=false 被读出来 —— 这是「Agent 判断要不要检索」的载体")
        void readsRetrieveFalse() {
            IntentReplyParser.ParsedReply reply = parse(
                    "{\"intent\":\"LEAF_1\",\"retrieve\":false,\"missing\":[]}");

            assertThat(reply.ok()).isTrue();
            assertThat(reply.plan().retrieve())
                    .as("★ 这个 false 就是用户诉求「Agent 先判断要不要检索」的全部数据载体")
                    .isFalse();
        }

        @Test
        @DisplayName("★ 前后带解释文字也能取到那个对象（花括号配对，不是取最后一个 }）")
        void toleratesSurroundingProse() {
            IntentReplyParser.ParsedReply reply = parse("""
                    好的，我的判断是：
                    {"intent":"LEAF_1","retrieve":true,"missing":[]}
                    补充说明：因为用户在问规格。
                    """);

            assertThat(reply.ok()).isTrue();
            assertThat(reply.code()).isEqualTo("LEAF_1");
        }

        @Test
        @DisplayName("★ 带代码围栏也能读（模型很爱包 ```）")
        void toleratesFences() {
            IntentReplyParser.ParsedReply reply = parse(
                    "```json\n{\"intent\":\"LEAF_1\",\"retrieve\":false,\"missing\":[]}\n```");

            assertThat(reply.ok()).isTrue();
            assertThat(reply.plan().retrieve()).isFalse();
        }

        @Test
        @DisplayName("★ 少写 retrieve → 取保守值 true（不判失败，也不让行为变化）")
        void missingRetrieveDefaultsToTrue() {
            IntentReplyParser.ParsedReply reply = parse("{\"intent\":\"LEAF_1\",\"missing\":[]}");

            assertThat(reply.ok()).isTrue();
            assertThat(reply.plan().retrieve())
                    .as("缺了它只是少了点信息，不该把整次分类判死 —— "
                            + "分类失败的代价是退化成全池检索")
                    .isTrue();
        }

        @Test
        @DisplayName("★ retrieve 类型不对（字符串 \"false\"）→ 同样取保守值，不猜")
        void wrongRetrieveTypeFallsBack() {
            IntentReplyParser.ParsedReply reply =
                    parse("{\"intent\":\"LEAF_1\",\"retrieve\":\"false\",\"missing\":[]}");

            assertThat(reply.ok()).isTrue();
            assertThat(reply.plan().retrieve())
                    .as("★ 不做宽松转换（同 ADR-057 那条「类型不做静默转换」）。"
                            + "把字符串 \"false\" 当 false 会让我们在一个模型写错类型的时候"
                            + "静默改变检索行为")
                    .isTrue();
        }

        @Test
        @DisplayName("★ missing 里混进非字符串 → 丢掉它们，其余保留")
        void ignoresNonStringSlots() {
            IntentReplyParser.ParsedReply reply =
                    parse("{\"intent\":\"LEAF_1\",\"retrieve\":true,\"missing\":[\"budget\",3,null,\"  \",\"purpose\"]}");

            assertThat(reply.plan().missingSlots()).containsExactly("budget", "purpose");
        }
    }

    // ============================================================
    // 二、老契约（裸码）的回退
    // ============================================================

    @Nested
    @DisplayName("二、老契约：模型没跟上时的回退")
    class BareCode {

        @Test
        @DisplayName("★★ 裸码照样能分类 —— shape 是 CODE，retrieve 取保守值")
        void bareCodeStillClassifies() {
            IntentReplyParser.ParsedReply reply = parse("LEAF_1");

            assertThat(reply.ok())
                    .as("★ 回退路径必须【能跑】—— 否则契约一变，线上全挂")
                    .isTrue();
            assertThat(reply.code()).isEqualTo("LEAF_1");
            assertThat(reply.plan().shape()).isEqualTo(IntentPlan.Shape.CODE);
            assertThat(reply.plan().retrieve())
                    .as("★ 保守值 true：模型偷个懒不该让那一次的检索行为发生变化")
                    .isTrue();
            assertThat(reply.plan().missingSlots()).isEmpty();
        }

        @Test
        @DisplayName("★★ shape=CODE 时【必须】留下一条告警 —— 那是「新契约没生效」的唯一线索")
        void bareCodeLeavesANote() {
            IntentReplyParser.ParsedReply reply = parse("LEAF_1");

            assertThat(reply.note())
                    .as("★ 没有这条告警的话，「模型照旧吐裸码 → 门控一次都没生效」"
                            + "这件事在日志、指标、落库数据上都看不出来")
                    .isNotNull();
        }

        @Test
        @DisplayName("★ 带包装的裸码（围栏/引号/尾标点）照样回退成功")
        void wrappedBareCodeStillWorks() {
            assertThat(parse("```\nLEAF_1\n```").code()).isEqualTo("LEAF_1");
            assertThat(parse("\"LEAF_1\"").code()).isEqualTo("LEAF_1");
            assertThat(parse("LEAF_1。").code()).isEqualTo("LEAF_1");
            assertThat(parse("leaf_1").code()).isEqualTo("LEAF_1");
        }
    }

    // ============================================================
    // 三、★★★ 失败的分界：什么时候【不】回退
    // ============================================================

    @Nested
    @DisplayName("三、★★★ 失败的分界")
    class FailureBoundary {

        /**
         * ★★ 这是本类最重要的一条。
         *
         * <p>「JSON 里的 intent 不合法」和「模型按老格式答的」是两件事：
         * 前者说明<b>模型没跟上契约</b>，要回去改 prompt；后者只是模型偷懒，
         * 而且<b>不影响正确性</b>。
         *
         * <p>回退会把前者伪装成后者 —— 于是「模型在乱答」这个信号就没了。
         */
        @Test
        @DisplayName("★★★ JSON 里 intent 非法 → 整体失败，【不】回退到裸码路径")
        void illegalIntentInJsonDoesNotFallBack() {
            IntentReplyParser.ParsedReply reply =
                    parse("{\"intent\":\"NOT_A_CODE\",\"retrieve\":true,\"missing\":[]}");

            assertThat(reply.ok())
                    .as("★★★ 有花括号 = 模型在尝试新契约，那就必须按新契约的规则判它 —— "
                            + "回退会把这个失败伪装成一个成功")
                    .isFalse();
            assertThat(reply.plan()).isNull();
            assertThat(reply.note()).contains("NOT_A_CODE");
        }

        @Test
        @DisplayName("★★ 半截 JSON（花括号没闭合）→ 失败，不猜")
        void halfJsonFails() {
            IntentReplyParser.ParsedReply reply = parse("{\"intent\":\"LEAF_1\",\"retrieve\":true");

            assertThat(reply.ok()).isFalse();
            assertThat(reply.note()).contains("半截");
        }

        @Test
        @DisplayName("★★★ 反对照：半截 JSON 里【恰好】有一个合法码的裸词 —— 仍然失败")
        void halfJsonWithEmbeddedCodeStillFails() {
            // 这一条防的是「用宽松正则去捞一个像 code 的单词」那种实现：
            // 它会把这条判成成功，而实际上模型一个字段都没给对
            IntentReplyParser.ParsedReply reply =
                    parse("{\"intent\":\"LEAF_1\",\"retrieve\":true  ← LEAF_1 这一类的意思");

            assertThat(reply.ok())
                    .as("★★★ 花括号开了没闭合 = 格式错误。"
                            + "拿正则去捞 LEAF_1 是靠猜，而猜错的形态是静默的")
                    .isFalse();
        }

        @Test
        @DisplayName("JSON 里没有 intent 字段 → 失败")
        void jsonWithoutIntentFails() {
            IntentReplyParser.ParsedReply reply = parse("{\"retrieve\":true,\"missing\":[]}");

            assertThat(reply.ok()).isFalse();
            assertThat(reply.note()).contains("intent");
        }

        @Test
        @DisplayName("JSON 里 intent 不是字符串 → 失败")
        void jsonWithNonStringIntentFails() {
            assertThat(parse("{\"intent\":123,\"retrieve\":true}").ok()).isFalse();
            assertThat(parse("{\"intent\":null,\"retrieve\":true}").ok()).isFalse();
        }

        @Test
        @DisplayName("既不是 JSON 也不是合法 code → 失败")
        void garbageFails() {
            IntentReplyParser.ParsedReply reply = parse("我不确定这是什么问题");

            assertThat(reply.ok()).isFalse();
            assertThat(reply.plan()).isNull();
        }

        @Test
        @DisplayName("空回复 → 失败（不是异常）")
        void blankFails() {
            assertThat(parse("").ok()).isFalse();
            assertThat(parse("   ").ok()).isFalse();
            assertThat(parse(null).ok()).isFalse();
        }
    }

    // ============================================================
    // 四、花括号配对本身
    // ============================================================

    @Nested
    @DisplayName("四、花括号的配对")
    class Extraction {

        @Test
        @DisplayName("★ 字符串【里面】的花括号不算配对 —— 否则会提前截断")
        void ignoresBracesInsideStrings() {
            String json = IntentReplyParser.extractJsonObject(
                    "{\"intent\":\"LEAF_1\",\"note\":\"a } b\",\"retrieve\":true}");

            assertThat(json)
                    .as("★ 不跳过字符串字面量的话，值里一个 } 就会让配对提前结束，"
                            + "症状是「多数时候正常、偶尔解析失败」")
                    .isEqualTo("{\"intent\":\"LEAF_1\",\"note\":\"a } b\",\"retrieve\":true}");
        }

        @Test
        @DisplayName("★ 转义的引号不会让字符串状态提前结束")
        void handlesEscapedQuotes() {
            String json = IntentReplyParser.extractJsonObject(
                    "{\"intent\":\"LEAF_1\",\"note\":\"他说 \\\"} \\\" 了\",\"retrieve\":true}");

            assertThat(json).isNotNull();
            assertThat(json).endsWith("}");
        }

        @Test
        @DisplayName("★ 后面还有第二个对象时，只取第一个（不粘成一段非法 JSON）")
        void takesOnlyTheFirstObject() {
            String json = IntentReplyParser.extractJsonObject(
                    "{\"intent\":\"LEAF_1\"} 比如 {\"intent\":\"LEAF_2\"}");

            assertThat(json).isEqualTo("{\"intent\":\"LEAF_1\"}");
        }

        @Test
        @DisplayName("没有花括号 → null（交给裸码路径）")
        void noBracesReturnsNull() {
            assertThat(IntentReplyParser.extractJsonObject("LEAF_1")).isNull();
        }
    }
}
