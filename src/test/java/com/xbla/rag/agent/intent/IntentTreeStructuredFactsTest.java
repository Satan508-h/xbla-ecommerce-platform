package com.xbla.rag.agent.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 意图树里的 {@code structured_facts} 声明（阶段 5.9）。纯文件解析，不起 Spring。
 *
 * <h3>★★ 本类要证明的三件事</h3>
 *
 * <ol>
 *   <li><b>它是叶子粒度的</b> —— 和 {@code retrieval} 的顶层粒度刻意不同。
 *       同一个顶层的两个叶子可以一个有、一个没有</li>
 *   <li><b>它不参与分类</b> —— 加它<b>不会</b>让
 *       {@code classificationTargets()} 多出一个选项。
 *       ⚠️ 这条是本阶段最要紧的一条：如果它进了分类目标，
 *       5.2 的意图准确率基线和 5.4 的 20 题基线就都要重跑</li>
 *   <li><b>写错了会启动即崩</b> —— 尤其是「非 KB 的叶子声明它」，
 *       那是一条<b>永远不会生效</b>的声明，静默忽略它等于埋一个
 *       「我明明配了怎么没用」的坑</li>
 * </ol>
 */
@DisplayName("IntentTree · structured_facts")
class IntentTreeStructuredFactsTest {

    @TempDir
    Path tempDir;

    // ============================================================
    // 夹具
    // ============================================================

    /**
     * 一棵合法的小树：4 个 KB 顶层 + 1 个 TOOL 顶层 + 两个非业务分支。
     *
     * <p>★ 只有 5 个顶层业务意图才满足 {@code EXPECTED_BUSINESS_INTENTS}，
     * 所以夹具不能随手加减顶层 —— 加叶子才是安全的。
     */
    private static String validTree() {
        StringBuilder yaml = new StringBuilder("version: 1\nintents:\n");
        for (int i = 1; i <= 4; i++) {
            yaml.append(kbTop(i, null, null));
        }
        // 第 5 个业务意图是 TOOL 类 —— 用来验「它的叶子不能声明 structured_facts」
        yaml.append("  - code: TOP_TOOL\n")
            .append("    name: 工具类\n")
            .append("    description: 要查实时数据\n")
            .append("    answer_style: 只陈述查到的数据\n")
            .append("    retrieval: TOOL\n")
            .append("    tools: [query_order_status]\n")
            .append("    children:\n")
            .append("      - code: LEAF_TOOL\n")
            .append("        name: 工具叶子\n")
            .append("        description: 查实时数据\n")
            .append("        doc_types: []\n")
            .append("        examples:\n")
            .append("          - 我的订单到哪了\n");
        yaml.append("  - code: OUT_OF_SCOPE\n")
            .append("    name: 兜底\n")
            .append("    role: OUT_OF_SCOPE\n")
            .append("    description: 与平台无关的输入\n")
            .append("    answer_style: 说明服务范围\n")
            .append("    retrieval: NONE\n")
            .append("    children: []\n");
        yaml.append("  - code: NEEDS_CLARIFICATION\n")
            .append("    name: 信息不足\n")
            .append("    role: CLARIFY\n")
            .append("    description: 业务相关但没说清\n")
            .append("    answer_style: 反问\n")
            .append("    retrieval: NONE\n")
            .append("    children: []\n");
        return yaml.toString();
    }

    private static String kbTop(int i, String leafExtra, String secondLeafExtra) {
        StringBuilder sb = new StringBuilder();
        sb.append("  - code: TOP_").append(i).append('\n')
          .append("    name: 顶层").append(i).append('\n')
          .append("    description: 顶层意图").append(i).append("的判据\n")
          .append("    answer_style: 回答风格").append(i).append('\n')
          .append("    retrieval: KB\n")
          .append("    children:\n");
        sb.append("      - code: LEAF_").append(i).append('\n')
          .append("        name: 叶子").append(i).append('\n')
          .append("        description: 叶子").append(i).append("的判据\n")
          .append("        doc_types: [2]\n")
          .append(leafExtra == null ? "" : "        " + leafExtra + "\n")
          .append("        examples:\n")
          .append("          - 示例问题").append(i).append('\n');
        if (secondLeafExtra != null) {
            sb.append("      - code: LEAF_").append(i).append("B\n")
              .append("        name: 叶子").append(i).append("B\n")
              .append("        description: 叶子").append(i).append("B的判据\n")
              .append("        doc_types: [2]\n")
              .append(secondLeafExtra.isEmpty() ? "" : "        " + secondLeafExtra + "\n")
              .append("        examples:\n")
              .append("          - 另一个示例问题").append(i).append('\n');
        }
        return sb.toString();
    }

    private IntentTree.Tree parse(String yaml) throws IOException {
        Path file = Files.createTempFile(tempDir, "intent-tree", ".utf8");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return IntentTree.parse(file);
    }

    // ============================================================
    // 一、正常路径
    // ============================================================

    @Test
    @DisplayName("没写 structured_facts 的叶子 → 默认 NONE")
    void defaultsToNone() throws IOException {
        IntentTree.Tree tree = parse(validTree());

        assertThat(tree.structuredFactOf("LEAF_1"))
                .isEqualTo(IntentTree.StructuredFact.NONE);
        assertThat(tree.leavesWith(IntentTree.StructuredFact.POLICY)).isEmpty();
    }

    @Test
    @DisplayName("★ 写了的叶子 → 查得到，而且只有它")
    void declaredLeafIsFound() throws IOException {
        IntentTree.Tree tree = parse(validTree().replace(
                "        doc_types: [2]\n        examples:\n          - 示例问题1",
                "        doc_types: [2]\n        structured_facts: POLICY\n"
                        + "        examples:\n          - 示例问题1"));

        assertThat(tree.structuredFactOf("LEAF_1"))
                .isEqualTo(IntentTree.StructuredFact.POLICY);
        assertThat(tree.leavesWith(IntentTree.StructuredFact.POLICY))
                .containsExactly("LEAF_1");
    }

    @Test
    @DisplayName("★ 查不到的 code → 返回 NONE，不返回 null")
    void unknownCodeGivesNone() throws IOException {
        IntentTree.Tree tree = parse(validTree());

        assertThat(tree.structuredFactOf("不存在的叶子")).isEqualTo(IntentTree.StructuredFact.NONE);
        // ★ 顶层码也返回 NONE —— 这个字段是叶子粒度的，顶层从来没有它
        assertThat(tree.structuredFactOf("TOP_1")).isEqualTo(IntentTree.StructuredFact.NONE);
        assertThat(tree.structuredFactOf(null)).isEqualTo(IntentTree.StructuredFact.NONE);
    }

    // ============================================================
    // 二、★★ 叶子粒度
    // ============================================================

    @Nested
    @DisplayName("★★ 叶子粒度：同一个顶层的两个叶子可以一个有、一个没有")
    class LeafGranularity {

        /** LEAF_1 有、LEAF_1B 没有 */
        private IntentTree.Tree tree() throws IOException {
            return parse(validTree().replace(
                    "        doc_types: [2]\n        examples:\n          - 示例问题1",
                    "        doc_types: [2]\n        structured_facts: POLICY\n"
                            + "        examples:\n          - 示例问题1"));
        }

        @Test
        @DisplayName("★ 兄弟叶子不受影响")
        void siblingUnaffected() throws IOException {
            IntentTree.Tree tree = tree();

            assertThat(tree.structuredFactOf("LEAF_1"))
                    .as("有声明的那一个").isEqualTo(IntentTree.StructuredFact.POLICY);
            assertThat(tree.structuredFactOf("LEAF_1B"))
                    .as("★ 兄弟叶子是 NONE —— 这正是它和 retrieval 的区别，"
                            + "retrieval 是「整类」的性质，查叶子时会回溯到父顶层")
                    .isEqualTo(IntentTree.StructuredFact.NONE);
        }

        @Test
        @DisplayName("★★ 它【不参与】分类 —— 分类目标数和没写它时完全一样")
        void doesNotAffectClassificationTargets() throws IOException {
            IntentTree.Tree withFact = tree();
            IntentTree.Tree without = parse(validTree());

            assertThat(withFact.classificationTargets())
                    .as("★★ 加它不能让分类 prompt 多出一个选项 —— "
                            + "否则 5.2 的准确率和 5.4 的 20 题基线都要重跑")
                    .hasSameSizeAs(without.classificationTargets());
            assertThat(withFact.classificationTargets().stream()
                    .map(IntentTree.ClassificationTarget::code).toList())
                    .isEqualTo(without.classificationTargets().stream()
                            .map(IntentTree.ClassificationTarget::code).toList());
            assertThat(withFact.classificationTargets())
                    .noneMatch(t -> t.code().equals("STRUCTURED_FACT"));
        }
    }

    // ============================================================
    // 三、★★ 写错了要启动即崩
    // ============================================================

    @Nested
    @DisplayName("★★ 畸形声明必须让应用起不来")
    class FailsLoudly {

        @Test
        @DisplayName("★★ 非 KB 的叶子声明它 → 报错（那是一条永远不会生效的声明）")
        void toolLeafCannotDeclareIt() {
            String yaml = validTree().replace(
                    "        doc_types: []\n        examples:\n          - 我的订单到哪了",
                    "        doc_types: []\n        structured_facts: POLICY\n"
                            + "        examples:\n          - 我的订单到哪了");

            assertThatThrownBy(() -> parse(yaml))
                    .as("★★ 走工具那条路根本不组装 prompt —— 配了也不会生效，"
                            + "而症状是「我明明在树里配了，怎么没用」，"
                            + "没有日志、没有异常")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("LEAF_TOOL")
                    .hasMessageContaining("永远不会生效");
        }

        @Test
        @DisplayName("★ 认不出的枚举值 → 报错，并把所有合法值列出来")
        void unknownValueRejected() {
            String yaml = validTree().replace(
                    "        doc_types: [2]\n        examples:\n          - 示例问题1",
                    "        doc_types: [2]\n        structured_facts: COUPONS\n"
                            + "        examples:\n          - 示例问题1");

            assertThatThrownBy(() -> parse(yaml))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("COUPONS")
                    .hasMessageContaining("NONE")
                    .hasMessageContaining("POLICY");
        }

        @Test
        @DisplayName("★ 正-反对照：同一棵树上只把 structured_facts 改成合法的值，就能解析成功")
        void contrastSameTreeWithValidValue() throws IOException {
            // ★ 和上面两个用例只差一个词 —— 证明失败的【确实是那个字段】，
            //   而不是夹具本身就有问题
            String yaml = validTree().replace(
                    "        doc_types: [2]\n        examples:\n          - 示例问题1",
                    "        doc_types: [2]\n        structured_facts: POLICY\n"
                            + "        examples:\n          - 示例问题1");

            assertThat(parse(yaml).structuredFactOf("LEAF_1"))
                    .isEqualTo(IntentTree.StructuredFact.POLICY);
        }

        @Test
        @DisplayName("★ 大小写和空格被容忍（和 retrieval / role 的读法一致）")
        void caseInsensitive() throws IOException {
            String yaml = validTree().replace(
                    "        doc_types: [2]\n        examples:\n          - 示例问题1",
                    "        doc_types: [2]\n        structured_facts: \" policy \"\n"
                            + "        examples:\n          - 示例问题1");

            assertThat(parse(yaml).structuredFactOf("LEAF_1"))
                    .isEqualTo(IntentTree.StructuredFact.POLICY);
        }
    }
}
