package com.xbla.rag.agent.intent;

import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 澄清判断的单元测试（阶段 5.3）。
 *
 * <p>不花钱、不起 Spring —— 分类结果直接构造出来喂进去。
 *
 * <p>本类守的是<b>一条最容易写反的规则</b>：
 * <b>「分类失败」不等于「该澄清」</b>。
 *
 * <p>直觉上「系统没分清楚」像是该反问用户，但那样做的后果是
 * <b>把自己的故障伪装成用户的问题</b> —— 用户会以为是自己问得不好，
 * 而真正的问题（prompt 坏了 / 供应商挂了）在数据上完全看不出来。
 */
@DisplayName("ClarificationDecider · 澄清判断")
class ClarificationDeciderTest {

    @TempDir
    Path tempDir;

    private static final String CLARIFY_CODE = "NEEDS_CLARIFICATION";

    /** 一棵带澄清分支的最小树；{@code withClarify=false} 时去掉它，用于反证 */
    private static String treeYaml(boolean withClarify) {
        StringBuilder yaml = new StringBuilder("version: 1\nintents:\n");
        for (int i = 1; i <= 5; i++) {
            yaml.append("  - code: TOP_").append(i).append('\n')
                .append("    name: 顶层").append(i).append('\n')
                .append("    description: 判据").append(i).append('\n')
                .append("    answer_style: 风格").append(i).append('\n')
                .append("    retrieval: KB\n")
                .append("    children:\n")
                .append("      - code: LEAF_").append(i).append('\n')
                .append("        name: 叶子").append(i).append('\n')
                .append("        description: 叶子判据").append(i).append('\n')
                .append("        doc_types: [").append(i).append("]\n")
                .append("        examples:\n")
                .append("          - 示例").append(i).append('\n');
        }
        yaml.append("  - code: OUT_OF_SCOPE\n"
                + "    name: 兜底\n"
                + "    role: OUT_OF_SCOPE\n"
                + "    description: 无关\n"
                + "    answer_style: 说明范围\n"
                + "    retrieval: NONE\n"
                + "    children: []\n");
        if (withClarify) {
            yaml.append("  - code: ").append(CLARIFY_CODE).append('\n')
                .append("    name: 信息不足\n")
                .append("    role: CLARIFY\n")
                .append("    description: 业务相关但没说清\n")
                .append("    answer_style: 反问一句\n")
                .append("    retrieval: NONE\n")
                .append("    children: []\n");
        }
        return yaml.toString();
    }

    private ClarificationDecider decider(boolean withClarify) throws IOException {
        return decider(withClarify, true);
    }

    /**
     * @param slotsEnabled 阶段 9.4 的槽位开关。<b>关掉 = 9.3 的行为</b>，
     *                     单测里它是那个「对照组」
     */
    private ClarificationDecider decider(boolean withClarify, boolean slotsEnabled)
            throws IOException {
        Path treeFile = tempDir.resolve(withClarify ? "with.yml" : "without.yml");
        Files.writeString(treeFile, treeYaml(withClarify), StandardCharsets.UTF_8);
        AgentProperties properties = new AgentProperties();
        properties.getIntent().setClarifyText("你指的是哪款商品呢？");
        // ★ 模板在测试里定死（"问 product" 这种）—— 断言就不依赖生产文案的字面量，
        //   文案改了不该让这批测试红
        Map<String, String> questions = new LinkedHashMap<>();
        for (String slot : ClarifySlots.KNOWN) {
            questions.put(slot, "问 " + slot);
        }
        properties.getSlots().setQuestions(questions);
        properties.getSlots().setEnabled(slotsEnabled);
        return new ClarificationDecider(new IntentTree(treeFile), properties);
    }

    private static IntentClassification classified(String code) {
        return new IntentClassification(code, IntentClassification.Outcome.CLASSIFIED,
                code, null, null, 12, null);
    }

    private static IntentClassification failed(IntentClassification.Outcome outcome) {
        return new IntentClassification(null, outcome, "模型的原话", null, null, 12, "出错了");
    }

    // ============================================================
    // 一、该澄清的
    // ============================================================

    @Nested
    @DisplayName("一、该澄清")
    class ShouldClarify {

        @Test
        @DisplayName("分类结果是澄清分支 → 澄清，并带上配置的话术")
        void clarifyWhenClassifiedAsClarifyBranch() throws IOException {
            ClarificationDecider.Decision d =
                    decider(true).decide("那个怎么样", classified(CLARIFY_CODE));

            assertThat(d.shouldClarify()).isTrue();
            assertThat(d.clarifyText()).isEqualTo("你指的是哪款商品呢？");
            assertThat(d.classification().code()).isEqualTo(CLARIFY_CODE);
        }

        @Test
        @DisplayName("★ 话术来自配置，不是硬编码 —— 改了配置立刻生效")
        void clarifyTextComesFromConfig() throws IOException {
            Path treeFile = tempDir.resolve("cfg.yml");
            Files.writeString(treeFile, treeYaml(true), StandardCharsets.UTF_8);
            AgentProperties properties = new AgentProperties();
            properties.getIntent().setClarifyText("换个说法试试？");

            ClarificationDecider.Decision d = new ClarificationDecider(
                    new IntentTree(treeFile), properties)
                    .decide("那个怎么样", classified(CLARIFY_CODE));

            assertThat(d.clarifyText()).isEqualTo("换个说法试试？");
        }
    }

    // ============================================================
    // 二、★ 不该澄清的（这一类才是本测试的重点）
    // ============================================================

    @Nested
    @DisplayName("二、★ 不该澄清")
    class ShouldNotClarify {

        @Test
        @DisplayName("业务意图 → 不澄清")
        void businessIntentProceeds() throws IOException {
            ClarificationDecider.Decision d =
                    decider(true).decide("退货要几天", classified("LEAF_1"));

            assertThat(d.shouldClarify()).isFalse();
            assertThat(d.clarifyText()).isNull();
        }

        @Test
        @DisplayName("★★ 分类失败 → 【不】澄清，照常走下去")
        void classificationFailureDoesNotClarify() throws IOException {
            // ★ 这是本类最重要的一条：
            //   「分类没成功」直觉上像是「系统不确定」，于是想去反问用户 ——
            //   但那样做会把自己的故障伪装成用户的问题，
            //   而真正的原因（prompt 坏了 / 供应商挂了）在数据上完全看不出来
            for (IntentClassification.Outcome outcome : List.of(
                    IntentClassification.Outcome.CALL_FAILED,
                    IntentClassification.Outcome.UNKNOWN_CODE)) {
                ClarificationDecider.Decision d =
                        decider(true).decide("退货要几天", failed(outcome));

                assertThat(d.shouldClarify())
                        .as("outcome=%s 时不该澄清 —— 那是系统的故障，不是用户没问清", outcome)
                        .isFalse();
                assertThat(d.clarifyText()).isNull();
            }
        }

        @Test
        @DisplayName("分类结果为 null（没开开关）→ 不澄清")
        void nullClassificationProceeds() throws IOException {
            ClarificationDecider.Decision d = decider(true).decide("退货要几天", null);

            assertThat(d.shouldClarify()).isFalse();
            assertThat(d.classification()).isNull();
        }

        @Test
        @DisplayName("★ 反证：树里没有澄清分支时，即使分类结果是那个 code 也不澄清")
        void withoutClarifyBranchNothingClarifies() throws IOException {
            // 正：树里有澄清分支 → 澄清
            assertThat(decider(true).decide("那个怎么样", classified(CLARIFY_CODE))
                    .shouldClarify()).isTrue();

            // 反：同一份分类结果，树里没有这个分支 → 不澄清。
            //   它证明上面那次「澄清」是真的查了树，而不是见到某个字符串就返回 true
            assertThat(decider(false).decide("那个怎么样", classified(CLARIFY_CODE))
                    .shouldClarify())
                    .as("LLM 编出来的 code 不该触发澄清 —— 树里没有它，就没有「该澄清」这回事")
                    .isFalse();
        }
    }

    // ============================================================
    // 三、★ history 参数（给 5.5 预留）
    // ============================================================

    @Nested
    @DisplayName("三、★ history 参数（给 5.5 预留）")
    class HistoryReservation {

        @Test
        @DisplayName("三参数版本存在且当前行为与两参数版本一致")
        void threeArgOverloadBehavesIdenticallyForNow() throws IOException {
            ClarificationDecider d = decider(true);
            IntentClassification c = classified(CLARIFY_CODE);

            // 5.5 接上之前，传空历史和不传历史必须完全一样 ——
            // 否则就是「接口预留」变成了「行为已经悄悄变了」
            assertThat(d.decide("那个怎么样", c, List.of()).shouldClarify())
                    .isEqualTo(d.decide("那个怎么样", c).shouldClarify())
                    .isTrue();
        }

        @Test
        @DisplayName("★ 传了非空历史，【依然】澄清 —— 这是 5.5 之后的【既定行为】，不是待办")
        void nonEmptyHistoryStillClarifiesByDesign() throws IOException {
            ClarificationDecider.Decision d = decider(true).decide(
                    "那个怎么样", classified(CLARIFY_CODE),
                    List.of(new ChatRequest.Turn(ChatRequest.Role.USER, "星辰X1 怎么样"),
                            new ChatRequest.Turn(ChatRequest.Role.ASSISTANT, "它是一款手机")));

            // ★★ 这条断言是【刻意】的，但含义和它刚写下来时【相反】了。
            //
            //   写它的时候（5.3）预设的是：「5.5 上线后，有上文时「那个」
            //   应当能被消解 —— 届时这条断言要改成 isFalse()」。
            //
            //   5.5 落地时那个预设被推翻了：本类【消解不了指代】。
            //   它是纯判断器（输入分类结果、输出澄清与否），而
            //   「那个」指什么只有【分类器】能回答。可行的链路是
            //   「历史 → 分类器 → 直接判成 SCENARIO_PICK → 走不到本类」，
            //   而 5.5 明确没有走那条路（历史只给生成）。
            //
            //   所以这条断言的现状是：
            //     · isTrue() 仍然是对的 —— 它描述的是【现在真实的行为】
            //     · 但它不再是「还没实现」，而是「这一版就这样」
            //     · 代价明确：有上下文时「那个怎么样」仍然会触发澄清反问
            //
            //   ★ 保留这条断言而不是删掉，是因为它现在钉的是一个
            //     【已知的、有意的局限】：本类不消解指代。
            //     谁要是把【自由文本历史】接进分类链路，它会立刻变红 ——
            //     那是一件大事（5.2 的 95% 和 5.4 的 20/20 都建立在
            //     「分类器只看这一句话」之上），需要同步重跑那两条验收。
            //
            //   ★★ 2026-09-25（阶段 9.4）补一句：9.4 确实改了分类器的输入，
            //     但走的【不是】这条路 —— 它补的是一小段【结构化状态】
            //     （PendingClarify：上一轮问了什么、缺哪一项），
            //     不是历史原文。所以这条断言【仍然是 isTrue()】，
            //     而「有上下文时不再重复澄清」那件事由
            //     ClarifyResumeIntegrationTest 在端到端那层守。
            //     ⇒ 这两条断言不矛盾：一条说「本类不消解指代」（仍然成立），
            //       一条说「分类器现在看得见上一轮的反问」（9.4 新增的能力）。
            assertThat(d.shouldClarify())
                    .as("★ 既定行为：Decider 消解不了指代。"
                            + "如果哪天这条变红了，说明有人把【自由文本历史】"
                            + "接进了分类链路 —— 那需要同步重跑 5.2 与 5.4 两条验收")
                    .isTrue();
        }
    }

    // ============================================================
    // 四、★ 槽位（阶段 9.4）
    // ============================================================

    @Nested
    @DisplayName("四、★ 槽位：按缺什么问什么（阶段 9.4）")
    class Slots {

        /** 带计划（因而带 missing）的分类结果 */
        private static IntentClassification withMissing(String... slots) {
            return new IntentClassification(CLARIFY_CODE,
                    IntentClassification.Outcome.CLASSIFIED,
                    "{\"intent\":\"" + CLARIFY_CODE + "\",\"missing\":[]}",
                    null, null, 12, null,
                    new IntentPlan(true, List.of(slots), IntentPlan.Shape.JSON));
        }

        @Test
        @DisplayName("① ★★ 缺 budget 就【问预算】—— 不再问「哪款商品」")
        void asksTheMissingSlot() throws IOException {
            ClarificationDecider.Decision d =
                    decider(true).decide("随便看看", withMissing("budget"));

            assertThat(d.askedSlot()).isEqualTo("budget");
            assertThat(d.clarifyText())
                    .as("★ 用的是 slots.questions 里配的那一条，不是固定的 clarify-text")
                    .isEqualTo("问 budget")
                    .isNotEqualTo("你指的是哪款商品呢？");
        }

        @Test
        @DisplayName("② ★ 缺多个只问一个，按固定优先级（purpose 先于 budget）")
        void picksByPriority() throws IOException {
            ClarificationDecider.Decision d =
                    decider(true).decide("随便看看", withMissing("budget", "purpose"));

            // ★ 顺序反着给（budget 在前），仍然问 purpose ——
            //   证明挑的是【我们的优先级】，不是模型报的顺序
            assertThat(d.askedSlot()).isEqualTo("purpose");
            assertThat(d.clarifyText()).isEqualTo("问 purpose");
        }

        @Test
        @DisplayName("③ ★ 模型自创的槽位被丢掉 → 回落固定文案；混在里面的真槽位仍然生效")
        void inventedSlotsFallBack() throws IOException {
            ClarificationDecider.Decision onlyInvented =
                    decider(true).decide("随便看看", withMissing("颜色", "尺寸"));
            assertThat(onlyInvented.askedSlot())
                    .as("认不出的槽位一个都不该被问 —— 我们没有问它的模板")
                    .isNull();
            assertThat(onlyInvented.clarifyText())
                    .as("★ 兜底：回落到 9.3 那句固定文案，而不是不问")
                    .isEqualTo("你指的是哪款商品呢？");

            // 反对照：同一个"自创槽位"场景里混一个真槽位 —— 它必须生效
            ClarificationDecider.Decision mixed =
                    decider(true).decide("随便看看", withMissing("颜色", "product"));
            assertThat(mixed.askedSlot()).isEqualTo("product");
            assertThat(mixed.clarifyText()).isEqualTo("问 product");
        }

        @Test
        @DisplayName("★★ ④ 落库的那一份保留【模型的原话】—— 过滤只发生在消费端")
        void pendingKeepsRawSlots() throws IOException {
            ClarificationDecider.Decision d =
                    decider(true).decide("那个怎么样", withMissing("颜色", "product"));

            assertThat(d.pending()).as("槽位开着时应当产出待澄清状态").isNotNull();
            assertThat(d.pending().question()).isEqualTo("那个怎么样");
            assertThat(d.pending().slots())
                    .as("★ 原话（含自创的「颜色」）—— 过滤掉它，「模型开始编槽位」"
                            + "这个信号就永远看不见了")
                    .containsExactly("颜色", "product");
            assertThat(d.pending().asked())
                    .as("★ 实际问的那一项是过滤之后的结论，两者【刻意不同】")
                    .isEqualTo("product");
        }

        @Test
        @DisplayName("★ ⑤ 没有计划（裸码回退）时仍产出状态 —— 锚是原问题，它比槽位更值钱")
        void worksWithoutPlan() throws IOException {
            ClarificationDecider.Decision d =
                    decider(true).decide("那个怎么样", classified(CLARIFY_CODE));

            assertThat(d.pending()).as("没有 missing 也要记 —— 下一轮至少知道上一轮问过什么")
                    .isNotNull();
            assertThat(d.pending().slots()).isEmpty();
            assertThat(d.askedSlot()).isNull();
            assertThat(d.clarifyText()).isEqualTo("你指的是哪款商品呢？");
        }

        @Test
        @DisplayName("★★★ ⑥ 开关关掉 → 固定文案 且【不产出状态】（对照组）")
        void disabledProducesNothing() throws IOException {
            ClarificationDecider off = decider(true, false);
            ClarificationDecider.Decision d = off.decide("随便看看", withMissing("budget"));

            assertThat(d.shouldClarify()).isTrue();
            assertThat(d.clarifyText())
                    .as("★ 关掉时与 9.3 逐字节相同的那一句")
                    .isEqualTo("你指的是哪款商品呢？");
            assertThat(d.pending())
                    .as("★★ 连状态都不产出 —— 只关一半的话，"
                            + "chat_session.pending_clarify 会攒下永远没人读的值")
                    .isNull();

            // 反对照：同一个输入，开着的时候确实不一样（否则上面三条可能恒真）
            ClarificationDecider on = decider(true, true);
            assertThat(on.decide("随便看看", withMissing("budget")).pending()).isNotNull();
        }
    }
}
