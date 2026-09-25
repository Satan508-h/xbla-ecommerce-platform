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
 * 检索门控（阶段 9.2）—— <b>「这一次要不要检索」的唯一判断点</b>。
 *
 * <p><b>不花一分钱</b>：纯函数，没有模型、没有数据库、没有 Spring。
 *
 * <h2>★★ 两条边界规则是这一层的全部价值</h2>
 *
 * <pre>
 *   ① 模型只能把检索【关掉】，不能把树声明不检索的意图【打开】
 *      —— 反向会复活 ADR-044 那个坑（工具意图退化成裸聊 → 编一个订单状态）
 *   ② 分类失败时【不】引入回归 —— 退化成阶段 4 的全池检索，而不是变成裸聊
 * </pre>
 *
 * <p>两条都配了<b>反对照</b>：只断言「限制生效了」是不够的，
 * 还要断言「放开限制的那一版确实会得到不同结果」，否则那些断言可能恒真。
 */
@DisplayName("RetrievalGate · 检索门控")
class RetrievalGateTest {

    @TempDir
    Path tempDir;

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    private IntentTree.Tree tree;
    private AgentProperties properties;
    private RetrievalGate gate;

    @BeforeEach
    void setUp() throws IOException {
        Path treeFile = tempDir.resolve("tree.yml");
        Files.writeString(treeFile, treeYaml(), StandardCharsets.UTF_8);
        tree = new IntentTree(treeFile).get();
        properties = new AgentProperties();
        gate = new RetrievalGate(new IntentTree(treeFile), properties);
    }

    /**
     * 三种 retrieval 都覆盖到的树。
     *
     * <p>⚠️ KB 类必须有 <b>4 个</b>顶层：意图树的加载期校验强制
     * 「业务意图恰好 5 类」（{@code EXPECTED_BUSINESS_INTENTS}，来自
     * 7.2 意图准确率的分母契约）。4 个 KB + 1 个 TOOL = 5 类业务意图，
     * 再加一个不计入的 OUT_OF_SCOPE。
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

    // ------------------------------------------------------------
    // 造分类结果的两个帮手
    // ------------------------------------------------------------

    /** 带计划（= 线上那条路的形态） */
    private static IntentClassification classified(String code, boolean retrieve, IntentPlan.Shape shape) {
        return new IntentClassification(code, IntentClassification.Outcome.CLASSIFIED,
                "raw", DESCRIPTOR, new BigDecimal("0.0001"), 5, null,
                new IntentPlan(retrieve, List.of(), shape));
    }

    /** 不带计划（= 测试构造的形态，走 9.2 之前的兼容构造器） */
    private static IntentClassification noPlan(String code) {
        return new IntentClassification(code, IntentClassification.Outcome.CLASSIFIED,
                "raw", DESCRIPTOR, new BigDecimal("0.0001"), 5, null);
    }

    // ============================================================
    // 一、门控表
    // ============================================================

    @Nested
    @DisplayName("一、门控表")
    class Table {

        @Test
        @DisplayName("KB + 模型说检索 → 检索")
        void kbWithRetrieveTrue() {
            RetrievalGate.Decision d = gate.decide(
                    classified("LEAF_1", true, IntentPlan.Shape.JSON));

            assertThat(d.path()).isEqualTo(RetrievalGate.Path.RETRIEVE);
            assertThat(d.reason()).isEqualTo(RetrievalGate.REASON_KB);
        }

        @Test
        @DisplayName("★★ KB + 模型说不用检索 → 不检索（用户诉求①的本体）")
        void kbWithRetrieveFalse() {
            RetrievalGate.Decision d = gate.decide(
                    classified("LEAF_1", false, IntentPlan.Shape.JSON));

            assertThat(d.path()).isEqualTo(RetrievalGate.Path.NONE);
            assertThat(d.reason()).isEqualTo(RetrievalGate.REASON_PLAN_OFF);
            assertThat(d.shouldRetrieve()).isFalse();
        }

        @Test
        @DisplayName("TOOL → 走工具（不检索）")
        void toolIntent() {
            RetrievalGate.Decision d = gate.decide(noPlan("TOP_5"));

            assertThat(d.path()).isEqualTo(RetrievalGate.Path.TOOLS);
            assertThat(d.shouldUseTools()).isTrue();
            assertThat(d.shouldRetrieve()).isFalse();
        }

        @Test
        @DisplayName("★★★ NONE → 不检索、也不调工具（阶段 5 到 9.1 一直没实现的短路）")
        void noneIntentSkipsRetrieval() {
            RetrievalGate.Decision d = gate.decide(noPlan("OUT_OF_SCOPE"));

            assertThat(d.path()).isEqualTo(RetrievalGate.Path.NONE);
            assertThat(d.reason()).isEqualTo(RetrievalGate.REASON_NONE_INTENT);
        }

        @Test
        @DisplayName("★ 分类失败 → 检索（退化成阶段 4 行为，不引入回归）")
        void failedClassificationFallsBackToRetrieval() {
            IntentClassification failed = new IntentClassification(null,
                    IntentClassification.Outcome.CALL_FAILED,
                    null, DESCRIPTOR, null, 5, "超时");

            RetrievalGate.Decision d = gate.decide(failed);

            assertThat(d.path()).isEqualTo(RetrievalGate.Path.RETRIEVE);
            assertThat(d.reason()).isEqualTo(RetrievalGate.REASON_NO_CLASSIFY);
        }

        @Test
        @DisplayName("★ 没开意图识别（intent=null）→ 同样退化成检索")
        void nullClassificationFallsBackToRetrieval() {
            RetrievalGate.Decision d = gate.decide(null);

            assertThat(d.path()).isEqualTo(RetrievalGate.Path.RETRIEVE);
            assertThat(d.reason()).isEqualTo(RetrievalGate.REASON_NO_CLASSIFY);
        }

        @Test
        @DisplayName("★ 没有计划（测试构造的分类结果）→ 只看树，行为与 9.2 之前一致")
        void noPlanFallsBackToTree() {
            assertThat(gate.decide(noPlan("LEAF_1")).path())
                    .as("没计划 ≠ 模型说要检索。默认值不能当成模型的意见 —— "
                            + "否则「模型没按契约作答」这个信息就丢了")
                    .isEqualTo(RetrievalGate.Path.RETRIEVE);
            assertThat(gate.decide(noPlan("LEAF_1")).reason())
                    .isEqualTo(RetrievalGate.REASON_KB);
        }

        @Test
        @DisplayName("★★ shape=CODE 的默认值 true 不能被当成「模型说要检索」")
        void bareCodeShapeIsNotAModelOpinion() {
            // 回退路径给的是默认值 true。但它【不是】模型说的 ——
            // 区别在于：如果哪天有人把回退默认值改成 false，
            // 这一条会红，而「模型偷懒关掉了检索」是最难查的那类事故
            RetrievalGate.Decision d = gate.decide(
                    classified("LEAF_1", true, IntentPlan.Shape.CODE));

            assertThat(d.reason())
                    .as("★ 走回退时不该出现 PLAN_OFF —— 那意味着我们把一个默认值"
                            + "当成了模型的判断")
                    .isEqualTo(RetrievalGate.REASON_KB);
        }
    }

    // ============================================================
    // 二、★ 边界规则①：只能关，不能开
    // ============================================================

    @Nested
    @DisplayName("二、★ 边界规则①：模型只能把检索关掉，不能打开")
    class ModelCannotOpenRetrieval {

        @Test
        @DisplayName("★★★ TOOL 意图 + 模型说「要检索」→ 仍然不检索")
        void modelCannotForceRetrievalOnToolIntent() {
            RetrievalGate.Decision d = gate.decide(
                    classified("TOP_5", true, IntentPlan.Shape.JSON));

            assertThat(d.path())
                    .as("★★★ 这条路上模型说的 retrieve 是【不可表达】的，不是「读了但不采纳」。"
                            + "放开的后果是 ADR-044：工具意图退化成裸聊，"
                            + "模型拿通用规则编一个具体的订单状态出来")
                    .isEqualTo(RetrievalGate.Path.TOOLS);
        }

        @Test
        @DisplayName("★★★ NONE 意图 + 模型说「要检索」→ 仍然不检索")
        void modelCannotForceRetrievalOnNoneIntent() {
            RetrievalGate.Decision d = gate.decide(
                    classified("OUT_OF_SCOPE", true, IntentPlan.Shape.JSON));

            assertThat(d.path()).isEqualTo(RetrievalGate.Path.NONE);
        }

        @Test
        @DisplayName("★★ 反对照：同一个 retrieve=false，在 KB 上【确实】改变了结果")
        void theSameFlagDoesChangeKbBehavior() {
            // 没有这一条，上面两条可能是恒真的 ——
            // 「TOOL/NONE 不检索」也可能只是因为【任何】输入都不检索
            assertThat(gate.decide(classified("LEAF_1", false, IntentPlan.Shape.JSON)).path())
                    .as("同样的 false 在 KB 意图上是生效的 —— 证明它确实是个开关，"
                            + "而不是被无条件忽略")
                    .isEqualTo(RetrievalGate.Path.NONE);
        }
    }

    // ============================================================
    // 三、A/B 旋钮
    // ============================================================

    @Nested
    @DisplayName("三、A/B 旋钮真的能拨动行为")
    class Switches {

        @Test
        @DisplayName("★ allowModelNoRetrieve=false → 模型关不掉了（回到只看树）")
        void disablingModelControl() {
            properties.getPlan().setAllowModelNoRetrieve(false);

            RetrievalGate.Decision d = gate.decide(
                    classified("LEAF_1", false, IntentPlan.Shape.JSON));

            assertThat(d.path())
                    .as("★ 这个旋钮用来量「模型关掉检索」这笔账值不值 —— "
                            + "关掉它之后门控只看意图树")
                    .isEqualTo(RetrievalGate.Path.RETRIEVE);
            assertThat(d.reason()).isEqualTo(RetrievalGate.REASON_KB);
        }

        @Test
        @DisplayName("★ noneIntentSkipRetrieval=false → 回到那个已知不一致的状态")
        void disablingNoneShortCircuit() {
            properties.getPlan().setNoneIntentSkipRetrieval(false);

            RetrievalGate.Decision d = gate.decide(noPlan("OUT_OF_SCOPE"));

            assertThat(d.path())
                    .as("★ 关掉它就回到阶段 5~9.1 的行为：树里写着 NONE，而代码照样检索。"
                            + "留着这个旋钮是为了把那笔账（兜底题 100% 过度检索）量出来")
                    .isEqualTo(RetrievalGate.Path.RETRIEVE);
            assertThat(d.reason()).isEqualTo(RetrievalGate.REASON_NONE_DISABLED);
        }
    }
}
