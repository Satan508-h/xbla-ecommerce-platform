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
import java.util.List;

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
        Path treeFile = tempDir.resolve(withClarify ? "with.yml" : "without.yml");
        Files.writeString(treeFile, treeYaml(withClarify), StandardCharsets.UTF_8);
        AgentProperties properties = new AgentProperties();
        properties.getIntent().setClarifyText("你指的是哪款商品呢？");
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
            //     【已知的、有意的局限】。将来谁去改分类器的输入
            //     （让历史进分类 prompt），它会立刻变红 ——
            //     那正是需要被看见的时刻。那句话正好反过来读了。
            assertThat(d.shouldClarify())
                    .as("★ 既定行为：Decider 消解不了指代，指代消解要靠分类器看到历史。"
                            + "如果哪天这条变红了，说明有人把历史接进了分类链路 —— "
                            + "那是一件大事（5.2 的 95% 和 5.4 的 20/20 都建立在"
                            + "「分类器只看这一句话」之上），需要同步重跑那两条验收")
                    .isTrue();
        }
    }
}
