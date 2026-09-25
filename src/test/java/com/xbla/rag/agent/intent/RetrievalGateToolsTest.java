package com.xbla.rag.agent.intent;

import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索门控的<b>工具轴</b>（阶段 9.3）—— 「这一次能用哪些工具」。
 *
 * <p><b>不花一分钱</b>：纯函数，没有模型、没有数据库、没有 Spring。
 *
 * <h2>★★★ 门控从 9.3 起是两个【正交】的轴</h2>
 *
 * <pre>
 *   retrieve   模型可以关掉（只限 KB 类意图）
 *   tools      模型碰不到，永远来自意图树的声明
 * </pre>
 *
 * <p>这条正交性是 9.2 那个决定的直接后果：当时把 {@code tools} 从模型的计划 JSON 里
 * <b>去掉了</b>（阶段 5.9 已否决过「让意图识别先选一遍工具」）。
 * 去掉之后，模型能表达的东西只剩 {@code retrieve} ——
 * 于是「关掉检索」和「关掉工具」在类型层面就是两件事，不可能被写混。
 *
 * <p>★ 由此得到一条反直觉但正确的规则：
 * <b>「模型说不用检索」不等于「这次不调工具」</b>。
 * 一个挂着工具的 KB 叶子被关掉检索之后，仍然应该走工具轮 ——
 * 那正是「不用查资料，去查实时数据」这个合法意图。
 *
 * <h2>★ 为什么要单独一个测试类</h2>
 *
 * <p>{@code RetrievalGateTest} 的夹具是 9.2 的形态（没有 tools）。
 * 往它里面塞工具会改掉那 13 个用例的前提（比如「关掉检索 → NONE」在带工具的叶子上
 * 就变成了 TOOLS）。<b>改坏一个已经绿了的用例，比新写一个难发现得多。</b>
 */
@DisplayName("RetrievalGate · 工具轴（阶段 9.3）")
class RetrievalGateToolsTest {

    @TempDir
    Path tempDir;

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    private RetrievalGate gate;

    @BeforeEach
    void setUp() throws IOException {
        Path treeFile = tempDir.resolve("tree.yml");
        Files.writeString(treeFile, treeYaml(), StandardCharsets.UTF_8);
        gate = new RetrievalGate(new IntentTree(treeFile), new AgentProperties());
    }

    /**
     * 一棵把两个位置都占上的树。
     *
     * <pre>
     *   TOP_1..TOP_3  KB，叶子不带工具        → 纯知识库
     *   TOP_4         KB，LEAF_4 带两个工具   → ★ 混合轮
     *   TOP_5         TOOL，顶层带两个工具     → 工具轮
     *   OUT_OF_SCOPE  NONE                    → 都不
     * </pre>
     *
     * <p>★ 4 个 KB + 1 个 TOOL = 5 类业务意图 —— 加载期强制这个数
     * （{@code EXPECTED_BUSINESS_INTENTS}）。
     */
    private static String treeYaml() {
        StringBuilder yaml = new StringBuilder("version: 1\nintents:\n");
        for (int i = 1; i <= 3; i++) {
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
                  - code: TOP_4
                    name: 知识库 + 工具
                    description: 既要通用建议，也想知道现在有什么
                    answer_style: 结合资料和实时数据
                    retrieval: KB
                    children:
                      - code: LEAF_4
                        name: 混合轮叶子
                        description: 描述
                        doc_types: [4]
                        tools: [search_products, recommend_products]
                        examples: [树示例4]
                  - code: TOP_5
                    name: 工具类
                    description: 要查实时数据
                    answer_style: 只陈述查到的
                    retrieval: TOOL
                    tools: [query_order_status, query_my_coupons]
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

    private static IntentClassification classified(String code, boolean retrieve) {
        return new IntentClassification(code, IntentClassification.Outcome.CLASSIFIED,
                "raw", DESCRIPTOR, new BigDecimal("0.0001"), 5, null,
                new IntentPlan(retrieve, List.of(), IntentPlan.Shape.JSON));
    }

    /** 不带计划（= 9.2 之前那种分类结果，走兼容构造器） */
    private static IntentClassification noPlan(String code) {
        return new IntentClassification(code, IntentClassification.Outcome.CLASSIFIED,
                "raw", DESCRIPTOR, new BigDecimal("0.0001"), 5, null);
    }

    // ============================================================
    // 一、两个轴各自的取值
    // ============================================================

    @Nested
    @DisplayName("一、两个轴各自的取值")
    class TwoAxes {

        @Test
        @DisplayName("★★ 混合轮：KB 叶子带工具 + 模型说要检索 → 检索【且】带工具")
        void kbLeafWithTools() {
            RetrievalGate.Decision d = gate.decide(classified("LEAF_4", true));

            assertThat(d.shouldRetrieve()).as("知识库照常检索").isTrue();
            assertThat(d.hasTools()).as("★ 同时把工具给出去 —— 这就是混合轮").isTrue();
            assertThat(d.tools()).containsExactly("search_products", "recommend_products");
            assertThat(d.path()).isEqualTo(RetrievalGate.Path.RETRIEVE);
        }

        @Test
        @DisplayName("纯 KB：叶子没声明工具 → 检索、不带工具")
        void pureKbLeaf() {
            RetrievalGate.Decision d = gate.decide(classified("LEAF_1", true));

            assertThat(d.shouldRetrieve()).isTrue();
            assertThat(d.hasTools()).isFalse();
            assertThat(d.tools()).isEmpty();
        }

        @Test
        @DisplayName("TOOL 顶层 → 不检索、带【顶层】声明的工具")
        void toolTop() {
            RetrievalGate.Decision d = gate.decide(noPlan("TOP_5"));

            assertThat(d.shouldRetrieve()).isFalse();
            assertThat(d.hasTools()).isTrue();
            // ★ 顺序保持声明顺序，不是字母序 —— 它是数组字节的一部分
            assertThat(d.tools()).containsExactly("query_order_status", "query_my_coupons");
            assertThat(d.path()).isEqualTo(RetrievalGate.Path.TOOLS);
        }

        @Test
        @DisplayName("NONE 顶层 → 两个轴都是「不」")
        void noneTop() {
            RetrievalGate.Decision d = gate.decide(noPlan("OUT_OF_SCOPE"));

            assertThat(d.shouldRetrieve()).isFalse();
            assertThat(d.hasTools()).isFalse();
            assertThat(d.path()).isEqualTo(RetrievalGate.Path.NONE);
        }
    }

    // ============================================================
    // 二、★★★ 模型关掉的是【检索】，不是【工具】
    // ============================================================

    @Nested
    @DisplayName("二、★★★ 模型关掉的是检索，不是工具")
    class ModelTurnsOffRetrievalOnly {

        @Test
        @DisplayName("★ 挂着工具的叶子 + retrieve=false → 不检索，但工具【还在】")
        void keepsToolsWhenRetrievalTurnedOff() {
            RetrievalGate.Decision d = gate.decide(classified("LEAF_4", false));

            assertThat(d.shouldRetrieve()).isFalse();
            assertThat(d.hasTools())
                    .as("★★ 工具白名单来自意图树，模型说的话里根本没有这一项 —— "
                            + "把它一起关掉会让这类叶子退化成裸聊（ADR-044）")
                    .isTrue();
            assertThat(d.path()).isEqualTo(RetrievalGate.Path.TOOLS);
        }

        @Test
        @DisplayName("★★ 反面对照：同一个 retrieve=false，在不带工具的叶子上 → 什么都不做")
        void withoutToolsItIsNone() {
            RetrievalGate.Decision d = gate.decide(classified("LEAF_1", false));

            // ★ 这一条是上一条的对照组：两处的 retrieve=false 完全相同，
            //   唯一的差别是叶子有没有声明工具。不写这一条的话，
            //   「工具还在」有可能只是「代码根本没读 retrieve」
            assertThat(d.shouldRetrieve()).isFalse();
            assertThat(d.hasTools()).isFalse();
            assertThat(d.path()).isEqualTo(RetrievalGate.Path.NONE);
        }
    }

    // ============================================================
    // 三、分类失败
    // ============================================================

    @Nested
    @DisplayName("三、分类失败时工具清单必须是空的")
    class NoClassify {

        @Test
        @DisplayName("★★ 分类失败 → 检索（阶段 4 行为），但【不带】任何工具")
        void noClassifyHasNoTools() {
            RetrievalGate.Decision d = gate.decide(null);

            assertThat(d.shouldRetrieve())
                    .as("退化成阶段 4 的全池检索，而不是裸聊")
                    .isTrue();
            assertThat(d.tools())
                    .as("★★ 分类都没成，我们不知道该给哪一份白名单 —— "
                            + "给一份「全都行」等于把按意图裁剪整个作废，而没有任何指标会红")
                    .isEmpty();
            assertThat(d.path()).isEqualTo(RetrievalGate.Path.RETRIEVE);
        }

        @Test
        @DisplayName("模型编了一个不存在的 code → 同上（不检索会放大成裸聊）")
        void unknownCodeIsAlsoNoClassify() {
            RetrievalGate.Decision d = gate.decide(classified("MODEL_MADE_THIS_UP", true));

            assertThat(d.shouldRetrieve()).isTrue();
            assertThat(d.tools()).isEmpty();
        }
    }

    // ============================================================
    // 四、★ 恒等式
    // ============================================================

    @Nested
    @DisplayName("四、★ 恒等式 TOOLS ⟺ 不检索 ∧ 有工具")
    class Invariant {

        /**
         * ★★ 三个 {@code Path} 值不是三个独立的开关，而是一张 2×2 表的两格加一行 ——
         * 只要 {@link RetrievalGate.Path#TOOLS} 和 {@code hasTools()} 能各说各话，
         * 调用方就会在两处判据里挑一个，而挑错的那个只会在某一条路上静默走错。
         *
         * <p>所以这里把门控表<b>整个扫一遍</b>，而不是逐个用例断言 ——
         * 逐个断言只能证明「我想到的那几种组合自洽」。
         */
        @Test
        @DisplayName("★★ 门控表的每一行都满足 TOOLS ⟺ !shouldRetrieve() ∧ hasTools()")
        void allRowsSatisfyTheInvariant() {
            List<RetrievalGate.Decision> all = List.of(
                    gate.decide(classified("LEAF_1", true)),
                    gate.decide(classified("LEAF_1", false)),
                    gate.decide(classified("LEAF_4", true)),
                    gate.decide(classified("LEAF_4", false)),
                    gate.decide(noPlan("TOP_5")),
                    gate.decide(noPlan("OUT_OF_SCOPE")),
                    gate.decide(null),
                    gate.decide(classified("MODEL_MADE_THIS_UP", true)));

            for (RetrievalGate.Decision d : all) {
                boolean isToolsPath = d.path() == RetrievalGate.Path.TOOLS;
                assertThat(isToolsPath)
                        .as("path=%s reason=%s tools=%s", d.path(), d.reason(), d.tools())
                        .isEqualTo(!d.shouldRetrieve() && d.hasTools());
            }
            // ★ 反面：这张表里至少有 2 行是 TOOLS —— 否则上面那个循环是空转
            assertThat(all.stream().filter(d -> d.path() == RetrievalGate.Path.TOOLS).count())
                    .as("样本里必须真的有 TOOLS 行，不然恒等式恒真")
                    .isGreaterThanOrEqualTo(2);
        }
    }
}
