package com.xbla.rag.agent.intent;

import com.xbla.rag.config.AgentProperties;
import com.xbla.rag.rag.eval.EvalQuestionLoader;
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

        /**
         * 读 {@code data/eval/} <b>整个目录</b>里全部题目的
         * <b>「会进分类器的那句话」</b>。
         *
         * <p>不依赖 {@code EvalQuestionLoader} —— 这是个纯文件检查
         * （它甚至不需要数据库在跑）。
         *
         * <p>★★ <b>必须是整个目录，不能只读某一个文件。</b> 阶段 7 把题库
         * 从 1 个文件扩到 3 个之后，只读 {@code baseline-questions.yml}
         * 的话，这条「少样本不能和考题重合」的护栏会<b>只覆盖 20 道题里的 20 道，
         * 而另外 150 道一道都不查</b> —— 测试照样是绿的，
         * 而 ADR-036 想守的那件事已经失守了。
         *
         * <h3>★★ 为什么解析 YAML 而不是匹配行文本</h3>
         *
         * <p>这里踩过<b>两次</b>同一个坑，两次都是「题库的写法变了，判据没跟着变」：
         *
         * <pre>
         *   第一次  写死 4 个空格缩进 → 多轮题的题面嵌在 turns 列表里，缩进更深
         *   第二次  写死 question: 这个字段名 → 多轮题那套【没有】question 行
         * </pre>
         *
         * <p>第二次更隐蔽：多轮题的题面写成
         * {@code turns:} 下面的列表项，一行 {@code - 那还有别的要注意的吗}，
         * 根本没有键名。按 {@code ^\s*question:} 匹配的话，
         * <b>那 20 道题一道都不会被检查到</b>，而测试照样是绿的。
         *
         * <p>★ 所以这次不再猜「题面写在哪个字段里」，而是把三种写法列全并解析结构：
         *
         * <pre>
         *   question             单轮题的题面
         *   turns                多轮题的每一轮（★ 每一轮都会【单独】送进分类器）
         *   standalone_question  追问句的「单独说」版本（跑对照时也会被送进去）
         * </pre>
         *
         * <p>⚠️ 三者都收是刻意的<b>宁可多收</b>：这条护栏问的是
         * 「有没有哪句用户话既在少样本里、又在考题里」，
         * 多收不会放过任何东西，少收会。
         *
         * <p>★★ 末尾那条断言是防<b>第三次</b>的：每题必须至少产出一句话。
         * 将来若又出现第四种写法（或者哪个字段被改名），这条会红 ——
         * 那时补上它，而不是让它第三次悄悄溜过去。
         */
        @SuppressWarnings("unchecked")
        private Set<String> evalQuestions() throws IOException {
            Path dir = Path.of(EvalQuestionLoader.EVAL_DIR);
            assertThat(dir).as("题库目录必须存在").exists();

            Set<String> questions = new LinkedHashSet<>();
            try (var stream = Files.list(dir)) {
                for (Path path : stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".yml"))
                        .sorted()
                        .toList()) {
                    Map<String, Object> root;
                    try (InputStream in = Files.newInputStream(path)) {
                        root = new Yaml().load(in);
                    }
                    if (root == null || !(root.get("questions") instanceof List<?> items)) {
                        continue;
                    }
                    for (Object item : items) {
                        Map<String, Object> node = (Map<String, Object>) item;
                        // ★★ 判据是「这道题【产出】了几句话」，【不是】「集合变大了几」。
                        //
                        //   后者会在题面重复时误报：P-001（单轮「怎么申请售后」）与
                        //   MT-006 的第一轮是同一句话，重复值不会让 Set 增长 ——
                        //   于是这个断言把一道【正常】的题判成了「解析不出来」。
                        //   ⚠️ 修的时候顺手确认了：那道题真的正常，是断言写错了。
                        int produced = addIfPresent(questions, node.get("question"))
                                + addIfPresent(questions, node.get("standalone_question"));
                        if (node.get("turns") instanceof List<?> turns) {
                            for (Object turn : turns) {
                                produced += addIfPresent(questions, turn);
                            }
                        }
                        assertThat(produced)
                                .as("★ %s 里的 %s 题目没有解析出任何一句话 —— "
                                        + "题库的写法变了而这里的判据没跟上，"
                                        + "这道题会【静默逃过】少样本重合检查",
                                        path.getFileName(), node.get("question_no"))
                                .isGreaterThan(0);
                    }
                }
            }
            assertThat(questions).as("题库里应当有题").isNotEmpty();
            return questions;
        }

        /** @return 这个字段【是不是一句非空的用户话】（1/0）—— 与「有没有被加进集合」无关 */
        private static int addIfPresent(Set<String> target, Object value) {
            if (value instanceof String s && !s.isBlank()) {
                target.add(s.trim());
                return 1;
            }
            return 0;
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

        /**
         * ★★ 评测题不得逐字出现在<b>会进分类 prompt 的</b>意图树文本里。
         *
         * <h3>为什么需要这一条：ADR-036 只守了一半</h3>
         *
         * <p>ADR-036 把意图树拆成两份文件，理由是「树里的 {@code examples}
         * 不进 prompt，所以它们可以和评测题重合」（下面那条反证测试断言的就是
         * 「确实重合」）。<b>但这不是全部事实</b> ——
         * {@code IntentPromptBuilder:81} 拼的是
         * {@code target.name()} / {@code target.description()}，
         * 而 {@code description} 是**自由文本**：写题的人把示例句抄进判据正文，
         * 那句话就进 prompt 了，而两边的护栏都不会报错。
         *
         * <p>★ 这是 2026-09-20 实测到的真事（不是假想）：
         *
         * <pre>
         *   COUPON 的 description            优惠券怎么用、能不能叠加、……
         *   评测题 B-009                      优惠券怎么用          ← 逐字相同
         *   RULES_AND_PROCESS 的 description  售后流程类问题：……、收到货发现损坏怎么办、……
         *   评测题 B-016                      收到货发现损坏怎么办     ← 逐字相同
         * </pre>
         *
         * <p>两道题的 gold 正好就是那个叶子 —— <b>prompt 把答案递到它们手上了</b>，
         * 而 5.2 的 95% 里含着它们。两道题已改写，这一条是防止再犯。
         *
         * <p>⚠️ <b>只查 name 和 description</b>，<b>不查 examples</b> ——
         * examples 不进 prompt（那是 ADR-036 的前提），把它们也拉进来会让
         * 下面那条反证测试直接失去意义。
         */
        @Test
        @DisplayName("★★ 评测题不得逐字出现在 intent-tree 的 name / description 里（它们进 prompt）")
        void evalQuestionsNeverLeakIntoPrompt() throws IOException {
            Set<String> eval = evalQuestions();
            List<String> promptTexts = promptVisibleTreeTexts();

            assertThat(promptTexts).as("树的判据文本应当被读到").isNotEmpty();

            List<String> leaks = eval.stream()
                    .filter(q -> promptTexts.stream().anyMatch(t -> t.contains(q)))
                    .toList();

            assertThat(leaks)
                    .as("这些评测题逐字出现在【会进分类 prompt 的】意图树文本里 —— "
                            + "分类器读到它们就等于拿到了答案，而那几道题的准确率"
                            + "测的是「能不能从 prompt 里照抄」。改评测题的问法，"
                            + "或者把那句话从判据正文里去掉（★ 别只删 examples，"
                            + "那不影响 prompt）")
                    .isEmpty();
        }

        /**
         * 收集 {@code intent-tree.yml} 里<b>会进 prompt</b> 的文本。
         *
         * <p>与 {@code IntentPromptBuilder} 拼进去的东西对齐：
         * {@code name} 与 {@code description}（以及 code，但 code 是标识符，
         * 不可能等于一句话）。
         *
         * <p>★ 遍历<b>全部节点</b>（顶层 + 叶子），不做「哪些是分类目标」的筛选 ——
         * 宁可多收。多收一条只会让漏检少一分；少收一条就是一次静默放行。
         */
        @SuppressWarnings("unchecked")
        private List<String> promptVisibleTreeTexts() throws IOException {
            Path path = Path.of("data/agent/intent-tree.yml");
            assertThat(path).exists();

            Map<String, Object> root;
            try (InputStream in = Files.newInputStream(path)) {
                root = new Yaml().load(in);
            }
            List<String> texts = new ArrayList<>();
            for (Object item : (List<Object>) root.get("intents")) {
                collectPromptTexts((Map<String, Object>) item, texts);
            }
            return texts;
        }

        @SuppressWarnings("unchecked")
        private void collectPromptTexts(Map<String, Object> node, List<String> into) {
            for (String key : List.of("name", "description")) {
                if (node.get(key) instanceof String s && !s.isBlank()) {
                    into.add(s);
                }
            }
            if (node.get("children") instanceof List<?> children) {
                for (Object child : children) {
                    collectPromptTexts((Map<String, Object>) child, into);
                }
            }
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
