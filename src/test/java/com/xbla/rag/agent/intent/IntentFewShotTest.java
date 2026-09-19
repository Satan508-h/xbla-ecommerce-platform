package com.xbla.rag.agent.intent;

import com.xbla.rag.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 意图少样本加载器的单元测试（阶段 5.2）。
 *
 * <p>纯文件解析测试，不起 Spring、不连数据库、不花钱。
 *
 * <p>本类守的是<b>一条会让 5.2 的验收失去意义的错误路径</b>：
 * 少样本里混进了评测题。那之后准确率测的就不是「能不能分类」，
 * 而是「能不能把 prompt 里刚看过的句子照抄回来」——
 * 数字会很好看，而它不描述任何真实的东西。
 *
 * <p>所以这个类的重心不在「能读懂一份好文件」，而在
 * <b>{@link Disjointness} 那一组</b>：拿 git 里那两份真文件互相对照。
 */
@DisplayName("IntentFewShot · 意图少样本")
class IntentFewShotTest {

    @TempDir
    Path tempDir;

    // ============================================================
    // 夹具
    // ============================================================

    /**
     * 一棵最小的合法树，<b>刻意同时覆盖 {@code classificationTargets()} 的两个分支</b>：
     * 4 个 KB 顶层（目标 = 它们的叶子） + 1 个 TOOL 顶层（目标 = 顶层自己）。
     *
     * <p>这样「叶子展开」和「顶层不展开」两条规则在同一个夹具里都能被测到。
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
                .append("        examples:\n")
                .append("          - 树的示例").append(i).append('\n');
        }
        // 第 5 个顶层走工具 —— 它的三个「叶子」在真实树里不参与分类，
        // 这里用一个叶子代表那种形态
        yaml.append("""
                  - code: TOP_5
                    name: 工具类
                    description: 需要查实时数据的那一类
                    answer_style: 只陈述查到的
                    retrieval: TOOL
                    children:
                      - code: TOOL_LEAF
                        name: 工具叶子
                        description: 某一个具体工具
                        doc_types: []
                        examples:
                          - 工具的示例
                """.replace("\n                ", "\n    "));
        yaml.append("""
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

    /** 树固定写在这个路径，便于测试自己 new 一个 IntentTree 去查分类目标 */
    private static final String TREE_FILE = "tree.yml";
    private static final String SAMPLE_FILE = "fewshot.yml";

    /** 造一个指向临时文件的少样本加载器。树也是临时的，内容见 {@link #treeYaml()} */
    private IntentFewShot fewShotOf(String yaml) throws IOException {
        writeTree();
        Path sampleFile = tempDir.resolve(SAMPLE_FILE);
        Files.writeString(sampleFile, yaml, StandardCharsets.UTF_8);

        AgentProperties properties = new AgentProperties();
        properties.getIntent().setFewShotPath(sampleFile.toString());
        return new IntentFewShot(properties, new IntentTree(tempDir.resolve(TREE_FILE)));
    }

    /** 单独造一棵同内容的树 —— 用来断言「分类目标是什么」 */
    private IntentTree tree() throws IOException {
        writeTree();
        return new IntentTree(tempDir.resolve(TREE_FILE));
    }

    private void writeTree() throws IOException {
        Files.writeString(tempDir.resolve(TREE_FILE), treeYaml(), StandardCharsets.UTF_8);
    }

    /** 覆盖全部 6 个分类目标的完整样本 —— 任何一处缺了都会让加载失败 */
    private static String fullSamplesYaml() {
        return """
                samples:
                  - intent: LEAF_1
                    questions:
                      - 样本一甲
                      - 样本一乙
                  - intent: LEAF_2
                    questions:
                      - 样本二甲
                  - intent: LEAF_3
                    questions:
                      - 样本三甲
                  - intent: LEAF_4
                    questions:
                      - 样本四甲
                  - intent: TOP_5
                    questions:
                      - 样本五甲
                  - intent: OUT_OF_SCOPE
                    questions:
                      - 你好啊
                """;
    }

    // ============================================================
    // 一、正常路径
    // ============================================================

    @Nested
    @DisplayName("一、正常路径")
    class HappyPath {

        @Test
        @DisplayName("解析出每个分类目标的样本，并保持书写顺序")
        void parsesSamplesPerTarget() throws IOException {
            IntentFewShot.Samples samples = fewShotOf(fullSamplesYaml()).load();

            assertThat(samples.byIntent()).hasSize(6);
            assertThat(samples.forIntent("LEAF_1")).containsExactly("样本一甲", "样本一乙");
            assertThat(samples.forIntent("LEAF_2")).containsExactly("样本二甲");
            assertThat(samples.totalQuestions()).isEqualTo(7);
        }

        @Test
        @DisplayName("★ 工具类意图只算一个目标 —— 它的叶子不参与分类")
        void toolIntentIsASingleTarget() throws IOException {
            IntentTree.Tree tree = tree().get();

            assertThat(tree.classificationTargets())
                    .extracting(IntentTree.ClassificationTarget::code)
                    // 1~4 展开到叶子；TOP_5 是 TOOL 所以【不展开】，用顶层 code；
                    // 兜底也是顶层 code
                    .containsExactly("LEAF_1", "LEAF_2", "LEAF_3", "LEAF_4",
                            "TOP_5", "OUT_OF_SCOPE");
            // 反证：TOOL_LEAF 确实存在于树里，只是【不是】分类目标
            assertThat(tree.findLeaf("TOOL_LEAF")).isPresent();
            assertThat(tree.classificationTargets())
                    .extracting(IntentTree.ClassificationTarget::code)
                    .doesNotContain("TOOL_LEAF");
            // 顶层作为目标时 parentName 为空，displayName 不该带前缀
            assertThat(tree.findTarget("TOP_5")).get()
                    .extracting(IntentTree.ClassificationTarget::displayName)
                    .isEqualTo("工具类");
            assertThat(tree.findTarget("LEAF_1")).get()
                    .extracting(IntentTree.ClassificationTarget::displayName)
                    .isEqualTo("顶层1 / 叶子1");
        }

        @Test
        @DisplayName("没有样本的目标返回空列表，而不是抛异常")
        void unknownTargetYieldsEmptyList() throws IOException {
            // 加载时会校验「每个目标都有样本」，所以正常路径下走不到这里；
            // 但 forIntent 的契约要明确 —— 它不该抛
            IntentFewShot.Samples samples = fewShotOf(fullSamplesYaml()).load();
            assertThat(samples.forIntent("NO_SUCH_TARGET")).isEmpty();
        }
    }

    // ============================================================
    // 二、畸形输入
    // ============================================================

    @Nested
    @DisplayName("二、畸形输入")
    class Malformed {

        @Test
        @DisplayName("文件不存在 → 抛异常（不是 WARN 回落）")
        void missingFileThrows() throws IOException {
            writeTree();

            AgentProperties properties = new AgentProperties();
            properties.getIntent().setFewShotPath(
                    tempDir.resolve("not-here.yml").toString());
            IntentFewShot fewShot = new IntentFewShot(properties,
                    new IntentTree(tempDir.resolve(TREE_FILE)));

            assertThatThrownBy(fewShot::load)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("意图少样本文件不存在");
        }

        @Test
        @DisplayName("intent 用了树里不存在的 code → 抛异常，并列出合法值")
        void rejectsUnknownIntentCode() throws IOException {
            assertThatThrownBy(() -> fewShotOf(fullSamplesYaml()
                    .replace("intent: LEAF_2", "intent: LEAF_99")).load())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不是合法的分类目标 code")
                    .hasMessageContaining("LEAF_1");
        }

        @Test
        @DisplayName("★ 工具叶子不能作为目标（它是 TOOL 类意图的子节点，不参与分类）")
        void rejectsToolLeafAsTarget() throws IOException {
            assertThatThrownBy(() -> fewShotOf(fullSamplesYaml()
                    .replace("intent: TOP_5", "intent: TOOL_LEAF")).load())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不是合法的分类目标 code");
        }

        @Test
        @DisplayName("同一句话出现在两个类别下 → 抛异常")
        void rejectsQuestionInTwoIntents() throws IOException {
            assertThatThrownBy(() -> fewShotOf(fullSamplesYaml()
                    .replace("      - 样本二甲", "      - 样本一甲")).load())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("重复出现")
                    .hasMessageContaining("样本一甲");
        }

        @Test
        @DisplayName("★ 某个分类目标没有样本 → 抛异常（不是 WARN）")
        void rejectsUncoveredTarget() throws IOException {
            // 删掉 LEAF_3 那一整块
            String missing = fullSamplesYaml().replace("""
                      - intent: LEAF_3
                        questions:
                          - 样本三甲
                    """, "");

            assertThatThrownBy(() -> fewShotOf(missing).load())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("没有任何少样本")
                    .hasMessageContaining("LEAF_3");
        }

        @Test
        @DisplayName("questions 为空 → 抛异常")
        void rejectsEmptyQuestions() throws IOException {
            assertThatThrownBy(() -> fewShotOf(fullSamplesYaml()
                    .replace("      - 样本三甲\n", "")).load())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("缺少 questions");
        }

        @Test
        @DisplayName("顶层缺少 samples 列表 → 抛异常")
        void rejectsMissingSamplesList() throws IOException {
            assertThatThrownBy(() -> fewShotOf("version: 1\n").load())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("缺少 samples 列表");
        }

        @Test
        @DisplayName("一次报出所有问题")
        void reportsAllProblemsAtOnce() throws IOException {
            String broken = fullSamplesYaml()
                    .replace("intent: LEAF_2", "intent: LEAF_99")
                    .replace("      - 样本三甲\n", "");

            assertThatThrownBy(() -> fewShotOf(broken).load())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不是合法的分类目标 code")
                    .hasMessageContaining("缺少 questions");
        }
    }

    // ============================================================
    // 三、★ 与评测集的不相交性（本类存在的理由）
    // ============================================================

    @Nested
    @DisplayName("三、★ 与评测集不相交")
    class Disjointness {

        private static final Pattern QUESTION_LINE =
                Pattern.compile("^\\s{4}question: (.+?)\\s*$");

        /** 读评测集 YAML 里的全部 question 字段。不依赖 EvalQuestionLoader —— 这是个纯文件检查 */
        private Set<String> evalQuestions() throws IOException {
            Path path = Path.of("data/eval/baseline-questions.yml");
            assertThat(path).exists();

            Set<String> questions = new LinkedHashSet<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                var m = QUESTION_LINE.matcher(line);
                if (m.matches()) {
                    questions.add(m.group(1).trim());
                }
            }
            return questions;
        }

        /** 读少样本 YAML 里的全部问题 */
        @SuppressWarnings("unchecked")
        private List<String> fewShotQuestions() throws IOException {
            Path path = Path.of("data/agent/intent-fewshot.yml");
            assertThat(path).exists();

            Map<String, Object> root;
            try (InputStream in = Files.newInputStream(path)) {
                root = new Yaml().load(in);
            }
            List<String> questions = new ArrayList<>();
            for (Object item : (List<Object>) root.get("samples")) {
                questions.addAll((List<String>) ((Map<String, Object>) item).get("questions"));
            }
            return questions;
        }

        @Test
        @DisplayName("★★ 少样本里的每一句都不等于任何一道评测题（重复 = 准确率虚高）")
        void fewShotNeverOverlapsEvalSet() throws IOException {
            Set<String> eval = evalQuestions();
            List<String> samples = fewShotQuestions();

            assertThat(eval).as("评测集应当有题").isNotEmpty();
            assertThat(samples).as("少样本应当有内容").isNotEmpty();

            List<String> collisions = samples.stream()
                    .filter(eval::contains)
                    .toList();

            assertThat(collisions)
                    .as("少样本与评测题逐字重合 —— 这会让 5.2 的准确率测的是"
                            + "「模型能不能照抄刚看过的句子」。换掉这些句子，"
                            + "换成语料里别的问法（不是从评测集里挑的另一句）")
                    .isEmpty();
        }

        @Test
        @DisplayName("同一份真文件：覆盖全部 15 个分类目标，且每个目标至少 2 句")
        void realFewShotCoversEveryTarget() {
            AgentProperties properties = new AgentProperties();
            IntentTree tree = new IntentTree(Path.of(properties.getIntentTreePath()));
            IntentFewShot fewShot = new IntentFewShot(properties, tree);

            // 加载本身就是一次完整校验（文件存在 / code 合法 / 无重复 / 每个目标都有样本）
            IntentFewShot.Samples samples = fewShot.load();

            List<IntentTree.ClassificationTarget> targets = tree.get().classificationTargets();
            assertThat(targets)
                    .as("真实树应当是 13 个业务叶子 + 订单物流 + 兜底 + 澄清 = 16 个分类目标")
                    .hasSize(16);

            assertThat(samples.byIntent().keySet())
                    .containsExactlyInAnyOrderElementsOf(
                            targets.stream().map(IntentTree.ClassificationTarget::code).toList());

            // ★ 「至少 2 句」不是硬性要求（加载器只要求 ≥1），
            //   但它是对小模型的提醒：只有一个样本的目标容易在相邻类别间摇摆。
            //   这里报出来，方便加新类别时对照
            List<String> thin = targets.stream()
                    .filter(t -> samples.forIntent(t.code()).size() < 2)
                    .map(IntentTree.ClassificationTarget::code)
                    .toList();
            assertThat(thin)
                    .as("这些目标的样本少于 2 句，模型容易在它们和相邻类别之间摇摆")
                    .isEmpty();
        }

        @Test
        @DisplayName("★ 树里的 examples 与评测集【确实重合】—— 这正是不能拿它做 few-shot 的原因")
        void treeExamplesDoOverlapEvalSet() throws IOException {
            // 这条测试是【反证】：它证明「拆成两份文件」不是多此一举。
            // 如果哪天树里的示例被清理得不重合了，这条会红 ——
            // 那时可以重新讨论要不要合并，而不是让它悄悄变成陈旧的注释
            AgentProperties properties = new AgentProperties();
            IntentTree tree = new IntentTree(Path.of(properties.getIntentTreePath()));

            Set<String> eval = evalQuestions();
            List<String> treeExamples = tree.get().allLeaves().stream()
                    .flatMap(leaf -> leaf.examples().stream())
                    .toList();

            long overlap = treeExamples.stream().filter(eval::contains).count();

            assertThat(overlap)
                    .as("树里的 examples 与评测题的重合数。当初实测是 18/20，"
                            + "所以它只能当文档，不能当 few-shot。"
                            + "若这个数变成 0，说明示例被重写过了 —— 那时再决定要不要合并两份文件")
                    .isGreaterThan(0);
        }
    }
}
