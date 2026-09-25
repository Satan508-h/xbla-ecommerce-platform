package com.xbla.rag.agent.intent;

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
 * 分类 prompt 构建器的单元测试（阶段 5.2）。
 *
 * <p>不起 Spring、不连数据库、不调模型 —— {@code build()} 是纯函数。
 *
 * <p>本类守的不是「prompt 拼得对不对」（那肉眼可见），
 * 而是<b>几件写错了也照样能跑、但会让分类悄悄失效的事</b>：
 *
 * <ol>
 *   <li>把 {@code doc_types} 放进了 prompt → 分类体系从「用户视角」漂移成
 *       「实现视角」，而准确率上看不出来</li>
 *   <li>把树里的 {@code examples} 当成了 few-shot → 与评测题重合 18/20，
 *       准确率虚高</li>
 *   <li>「拿不准」与「信息不足」没分开 → 见下</li>
 * </ol>
 *
 * <h3>★ 关于第三条：一个被实测修正过的理由</h3>
 *
 * <p>阶段 5.2 写这些测试时，理由是「{@code OUT_OF_SCOPE} 兼作『没把握』，
 * 会让 5.3 的澄清反问失去信号」。<b>阶段 5.3 的实测推翻了这个理由</b> ——
 * 澄清反问根本不依赖「分类置信度」（logprobs / 采样一致度 / 模型自报
 * 三个信号与「该不该澄清」都不相关，详见 docs/05 §9.3）。
 *
 * <p>但<b>结论没变，理由换了</b>：不把两者分开，用户问「那个怎么样」时
 * 会收到「我只处理商品导购与售后问题」—— <b>这是把「答不了」冒充成了
 * 「不该答」</b>，用户不知道是系统没听懂。
 *
 * <p>所以 5.3 给它开了一个正式的选项 {@code NEEDS_CLARIFICATION}，
 * 而 prompt 里那三条边界说明是它能不能被<em>恰当</em>使用的前提 ——
 * 尤其是「拿不准 ≠ 信息不足」那句，防的是模型<b>滥用</b>澄清。
 */
@DisplayName("IntentPromptBuilder · 分类 prompt")
class IntentPromptBuilderTest {

    @TempDir
    Path tempDir;

    /**
     * 夹具的关键设计：<b>同一个类别的「树示例」和「少样本」内容不同</b>，
     * 于是「prompt 里出现的是哪一个」可以被直接断言。
     *
     * <p>如果两边写成一样的，第 ② 条测试就恒真了。
     */
    private static final String LEAF_1_TREE_EXAMPLE = "树的示例一";
    private static final String LEAF_1_FEWSHOT = "少样本一甲";

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
                .append("          - ").append(i == 1 ? LEAF_1_TREE_EXAMPLE
                        : "树的示例" + i).append('\n');
        }
        yaml.append("""
                  - code: TOP_5
                    name: 工具类
                    description: 需要查实时数据的那一类
                    answer_style: 只陈述查到的
                    retrieval: TOOL
                    tools: [query_order_status]
                    children:
                      - code: TOOL_LEAF
                        name: 工具叶子
                        description: 某一个具体工具
                        doc_types: []
                        examples:
                          - 工具的树示例
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
        // ★ 夹具里必须有澄清分支 —— 「拿不准 ≠ 信息不足」那两条测试要断言
        //   它的 code 出现在 prompt 里，不加的话断言恒假
        yaml.append("""
                  - code: NEEDS_CLARIFICATION
                    name: 信息不足
                    role: CLARIFY
                    description: 业务相关但没说清
                    answer_style: 反问一句最关键的缺失信息
                    retrieval: NONE
                    children: []
                """.replace("\n                ", "\n    "));
        return yaml.toString();
    }

    private static String fewShotYaml() {
        return """
                samples:
                  - intent: LEAF_1
                    questions:
                      - %s
                      - 少样本一乙
                  - intent: LEAF_2
                    questions:
                      - 少样本二甲
                  - intent: LEAF_3
                    questions:
                      - 少样本三甲
                  - intent: LEAF_4
                    questions:
                      - 少样本四甲
                  - intent: TOP_5
                    questions:
                      - 少样本五甲
                  - intent: OUT_OF_SCOPE
                    questions:
                      - 你好啊
                  - intent: NEEDS_CLARIFICATION
                    questions:
                      - 那个怎么样
                """.formatted(LEAF_1_FEWSHOT);
    }

    private static IntentPromptBuilder builder(Path dir) throws IOException {
        // 默认走阶段 9.2 的新契约（JSON 计划）
        return builder(dir, true);
    }

    /**
     * @param planEnabled ★ 阶段 9.2 的 A/B 开关。关掉时 prompt 必须与 9.2 之前
     *                    <b>逐字节相同</b> —— 否则「关掉新契约」这个对照组就没意义了
     */
    private static IntentPromptBuilder builder(Path dir, boolean planEnabled) throws IOException {
        return builder(dir, planEnabled, true);
    }

    /**
     * @param slotsEnabled ★ 阶段 9.4 的槽位开关。关掉时即使传了
     *                     {@code pending} 也不许插入那一段 ——
     *                     「关掉 = 与 9.3 逐字节相同」是靠它成立的
     */
    private static IntentPromptBuilder builder(Path dir, boolean planEnabled, boolean slotsEnabled)
            throws IOException {
        Path treeFile = dir.resolve("tree.yml");
        Files.writeString(treeFile, treeYaml(), StandardCharsets.UTF_8);
        Path sampleFile = dir.resolve("fewshot.yml");
        Files.writeString(sampleFile, fewShotYaml(), StandardCharsets.UTF_8);

        AgentProperties properties = new AgentProperties();
        properties.getIntent().setFewShotPath(sampleFile.toString());
        properties.getPlan().setEnabled(planEnabled);
        properties.getSlots().setEnabled(slotsEnabled);

        IntentTree tree = new IntentTree(treeFile);
        return new IntentPromptBuilder(tree, new IntentFewShot(properties, tree), properties);
    }

    // ============================================================
    // 一、基本结构
    // ============================================================

    @Nested
    @DisplayName("一、基本结构")
    class Structure {

        @Test
        @DisplayName("每个分类目标的 code 与判据都出现在 prompt 里")
        void containsEveryTarget() throws IOException {
            String prompt = builder(tempDir).build(null);

            assertThat(prompt)
                    .contains("LEAF_1", "LEAF_2", "LEAF_3", "LEAF_4")
                    // ★ 工具类用的是【顶层】code —— 它的叶子不参与分类
                    .contains("TOP_5")
                    .doesNotContain("TOOL_LEAF")
                    .contains("OUT_OF_SCOPE");

            assertThat(prompt)
                    .contains("叶子1的判据")
                    .contains("需要查实时数据的那一类")
                    .contains("与平台无关");
        }

        @Test
        @DisplayName("同一个顶层的叶子聚在一组，组标题只出现一次")
        void groupsLeavesUnderParent() throws IOException {
            String prompt = builder(tempDir).build(null);

            assertThat(prompt).contains("【顶层1】");
            // 每个顶层只在切换时打一次标题 —— 重复打会让 prompt 变长且更乱
            assertThat(countOccurrences(prompt, "【顶层1】")).isEqualTo(1);
        }

        @Test
        @DisplayName("同样输入产出同样字符串（阶段 7 的 A/B 要靠它 diff）")
        void isDeterministic() throws IOException {
            IntentPromptBuilder b = builder(tempDir);
            assertThat(b.build(null)).isEqualTo(b.build(null));
        }

        @Test
        @DisplayName("★ 默认走新契约：输出要求里写明那四键 JSON 的格式")
        void statesPlanFormat() throws IOException {
            String prompt = builder(tempDir).build(null);

            assertThat(prompt)
                    .contains("只输出一行 JSON")
                    .contains("\"intent\"")
                    .contains("\"retrieve\"")
                    .contains("\"missing\"")
                    // ★ 示例里的 code 必须是占位符，不能是一个真的 code ——
                    //   示例是 prompt 里最强的信号，写真的会诱导模型偏向那一类
                    //   （同 IntentFewShot 存在的理由）
                    .doesNotContain("\"intent\":\"LEAF_1\"");
        }

        /**
         * ★★ 反对照 —— 这条是让上面那条有意义的那个。
         *
         * <p>只断言「新契约在」是不够的：一个把新旧两套要求<b>都</b>写进去的
         * prompt 也能通过上面那条，而那种 prompt 会让模型无所适从
         * （「到底输出 code 还是 JSON？」），症状是 shape 大量落到 CODE。
         */
        @Test
        @DisplayName("★★ 关掉开关 → 回到老契约，且【不再】出现计划相关的字眼")
        void disabledFallsBackToBareCode() throws IOException {
            String prompt = builder(tempDir, false).build(null);

            assertThat(prompt)
                    .as("关掉时必须是 9.2 之前那份 prompt")
                    .contains("只输出一个 code")
                    .doesNotContain("retrieve")
                    .doesNotContain("missing")
                    .doesNotContain("JSON");
        }

        @Test
        @DisplayName("★ 两个契约共用同一段边界规则（防的是两份措辞漂移）")
        void boundaryRulesShared() throws IOException {
            String on = builder(tempDir, true).build(null);
            String off = builder(tempDir, false).build(null);

            String boundary = "三个非业务选项的边界：";
            assertThat(on).contains(boundary);
            assertThat(off).contains(boundary);
            // 两边的那一段必须一模一样 —— 否则两个实验组不止差一个变量
            assertThat(segmentFrom(on, boundary)).isEqualTo(segmentFrom(off, boundary));
        }

        private static String segmentFrom(String text, String marker) {
            int at = text.indexOf(marker);
            return at < 0 ? null : text.substring(at);
        }
    }

    // ============================================================
    // 二、★ 三件写错了也照样能跑的事
    // ============================================================

    @Nested
    @DisplayName("二、★ 三条纪律")
    class Discipline {

        @Test
        @DisplayName("★ 不放 doc_types —— 它是检索策略，不是用户意图")
        void neverLeaksDocTypes() throws IOException {
            String prompt = builder(tempDir).build(null);

            // 放进去会诱导模型按「答案在哪几个文档里」分类，
            // 于是分类体系从用户视角漂移成实现视角 —— 而准确率上看不出来
            assertThat(prompt)
                    .as("prompt 里出现了 doc_type —— 分类会退化成「按答案位置分类」，"
                            + "而这件事大部分时候给出同一个答案，所以准确率看不出问题")
                    .doesNotContain("doc_type")
                    .doesNotContain("docTypes");
        }

        @Test
        @DisplayName("★ 用少样本文件的内容，不用意图树里的 examples")
        void usesFewShotNotTreeExamples() throws IOException {
            String prompt = builder(tempDir).build(null);

            assertThat(prompt)
                    .as("少样本应当来自 intent-fewshot.yml")
                    .contains(LEAF_1_FEWSHOT);

            // 反证 —— 这一条是本测试的重点：树里的示例【确实存在】于树里，
            // 但【不能出现在 prompt 里】。没有这个反证，
            // 上面那句「包含少样本」可能只是碰巧成立
            assertThat(prompt)
                    .as("prompt 里出现了意图树的 examples —— 它们与评测题有 18/20 逐字重合，"
                            + "用它做 few-shot 会让 5.2 的准确率测的是「照抄能力」")
                    .doesNotContain(LEAF_1_TREE_EXAMPLE)
                    .doesNotContain("树的示例");

            // 再确认一次「树里真的有这个示例」—— 否则上面那句 doesNotContain 是空断言
            IntentTree tree = new IntentTree(tempDir.resolve("tree.yml"));
            assertThat(tree.get().findLeaf("LEAF_1")).get()
                    .extracting(IntentTree.Leaf::examples)
                    .as("树里必须真的有这个示例，否则上一条断言恒真")
                    .asList().contains(LEAF_1_TREE_EXAMPLE);
        }

        @Test
        @DisplayName("★ 不诱导模型用兜底表示「不确定」")
        void neverSuggestsFallbackForUncertainty() throws IOException {
            String prompt = builder(tempDir).build(null);

            // 「拿不准时输出 OUT_OF_SCOPE」是很自然但错误的一句：
            // 兜底的含义是「与平台业务无关」，不是「我分不清售后下面哪一类」。
            // 混起来之后，用户问「那个怎么样」会收到「我只处理商品导购与售后问题」——
            // 把「答不了」冒充成了「不该答」
            assertThat(prompt)
                    .as("prompt 里暗示了「不确定就用兜底」—— 这会把"
                            + "「系统没听懂」冒充成「系统不该回答」")
                    .doesNotContain("拿不准时输出 OUT_OF_SCOPE")
                    .doesNotContain("不确定就用")
                    .doesNotContain("分不清就");

            // 正面：兜底的判据写的是「都无关」这个正确条件
            assertThat(prompt).contains("都无关");
        }

        @Test
        @DisplayName("★ 写明了「拿不准 ≠ 信息不足」—— 防的是模型滥用澄清")
        void distinguishesUncertaintyFromInsufficientInfo() throws IOException {
            String prompt = builder(tempDir).build(null);

            // 加了 NEEDS_CLARIFICATION 之后最大的风险是模型【滥用】它：
            // 把「退货要几天」这种正常问题也判成信息不足。
            // 必须显式说明这两件事不是一回事
            assertThat(prompt)
                    .as("prompt 没有说明「拿不准」和「信息不足」的区别 —— "
                            + "模型会把正常问题也判成需要澄清，而使用者只会觉得系统很烦")
                    .contains("拿不准")
                    .contains("不是「信息不足」");

            // 而且要说清「拿不准时该怎么办」—— 选更接近的，不是去反问用户
            assertThat(prompt).contains("选更接近的那一个");
        }

        @Test
        @DisplayName("★ 三个非业务选项的边界写清楚了")
        void explainsNonBusinessBoundary() throws IOException {
            String prompt = builder(tempDir).build(null);

            assertThat(prompt)
                    .contains("NEEDS_CLARIFICATION")
                    .contains("OUT_OF_SCOPE")
                    .contains("单独拿出来")
                    .contains("都无关");
        }
    }

    // ============================================================
    // 四、★★★ 上一轮的澄清（阶段 9.4）
    // ============================================================

    /**
     * 这一段的存在方式是<b>纯增量</b>：没有待澄清时一个字都不出现。
     *
     * <p>★★★ 这就是「改了分类 prompt，但 5.2 的准确率基线<b>不用重测</b>」的<b>唯一依据</b>
     * —— 那 20 道基线题都是单轮的，永远没有 pending。
     * 所以下面这几条断言不是格式洁癖，它们守的是那件事。
     */
    @Nested
    @DisplayName("四、★★★ 上一轮的澄清只在这些轮次出现（阶段 9.4）")
    class ResumeSection {

        private static PendingClarify pending() {
            return PendingClarify.of("那个怎么样", List.of("product"), "product");
        }

        /** 把注入那一段剪掉 —— 用于断言「它之外的部分一个字没变」 */
        private static String withoutResumeSection(String prompt) {
            int from = prompt.indexOf("## 上一轮的澄清");
            int to = prompt.indexOf("## 输出要求");
            return from < 0 ? prompt : prompt.substring(0, from) + prompt.substring(to);
        }

        @Test
        @DisplayName("① ★★★ 没有待澄清 → 一个字都不插入（与 9.3 逐字节相同）")
        void absentWhenNothingPending() throws IOException {
            String prompt = builder(tempDir).build(null);

            // ⚠️ 判据必须是【那一段的标题】，不能是「上一轮」三个字 ——
            //   9.2 的 retrieve 契约里本来就写着「对你上一轮反问的确认」，
            //   所以「不出现上一轮」这条断言从第一天起就是错的
            //   （实测：第一次跑它红了，而代码是对的）。
            assertThat(prompt)
                    .doesNotContain("## 上一轮的澄清")
                    .doesNotContain("如果现在这句话是在回答");

            // 三条都不出现 = 那一段整段不存在。★ 判据是「不出现」，不是「出现但为空」
            assertThat(withoutResumeSection(prompt)).isEqualTo(prompt);
        }

        @Test
        @DisplayName("② ★★ 有待澄清 → 原问题 / 缺的槽位 / 实际问的那项都写进去")
        void containsTheAnchorAndSlots() throws IOException {
            String prompt = builder(tempDir).build(pending());

            assertThat(prompt)
                    .contains("## 上一轮的澄清")
                    .contains("那个怎么样")
                    .contains("product")
                    .as("★ 原问题是锚：只说「缺 product」模型不知道在问哪件事")
                    .contains("如果现在这句话是在回答上面那个问题");
        }

        @Test
        @DisplayName("③ ★★★ 纯增量：剪掉那一段之后，与不带 pending 的逐字节相同")
        void isPurelyAdditive() throws IOException {
            IntentPromptBuilder b = builder(tempDir);

            String base = b.build(null);
            String withPending = b.build(pending());

            assertThat(withPending).isNotEqualTo(base);
            assertThat(withoutResumeSection(withPending))
                    .as("★★★ 插入之外的部分一个字都不能变 —— 变了的话，"
                            + "「基线不用重测」这个结论就不成立了")
                    .isEqualTo(base);
        }

        @Test
        @DisplayName("④ ★★ 措辞是弱的：写明「如果是在回答…否则忽略这一段」")
        void wordingIsHedged() throws IOException {
            String prompt = builder(tempDir).build(pending());

            // ★ 我们不可能在分类之前知道用户是不是在回答上一个问题 ——
            //   他完全可能换了话题。所以判断权必须交给模型，而不是替它断定。
            assertThat(prompt)
                    .contains("如果")
                    .contains("忽略这一段")
                    .as("★ 不能写成「用户正在回答」—— 那是一句我们没核实过的话")
                    .doesNotContain("用户正在回答");
        }

        @Test
        @DisplayName("⑤ ★★ 开关关掉 → 即使传了 pending 也不插入（对照组）")
        void disabledNeverInserts() throws IOException {
            IntentPromptBuilder off = builder(tempDir, true, false);

            assertThat(off.build(pending()))
                    .as("★★ 「关掉 = 与 9.3 逐字节相同」这句话要由【产出 prompt 的地方】保证，"
                            + "不能只靠调用方记得不要传")
                    .doesNotContain("上一轮的澄清");

            // 反对照：同一个 builder 开着的时候确实会插入（否则上面那条可能恒真）
            assertThat(builder(tempDir, true, true).build(pending()))
                    .contains("上一轮的澄清");
        }

        @Test
        @DisplayName("★ ⑥ 插入位置在【输出要求之前】—— 契约必须是最后一句")
        void insertedBeforeOutputContract() throws IOException {
            String prompt = builder(tempDir).build(pending());

            assertThat(prompt.indexOf("## 上一轮的澄清"))
                    .as("★ prompt 里最后的指令是模型最不容易忽略的，"
                            + "而「只输出一行 JSON」那条契约就是最后的指令")
                    .isLessThan(prompt.indexOf("## 输出要求"));
        }

        @Test
        @DisplayName("★ ⑦ 原问题里的换行被压平 —— 否则它会把这一段的排版打乱")
        void multilineQuestionIsFlattened() throws IOException {
            String prompt = builder(tempDir).build(
                    PendingClarify.of("第一行\n第二行", List.of("product"), "product"));

            assertThat(prompt).contains("第一行 第二行");
            assertThat(prompt.indexOf("## 上一轮的澄清"))
                    .as("★ 注入仍然在契约之前（也就是没被换行搞乱）")
                    .isLessThan(prompt.indexOf("## 输出要求"));
        }

        @Test
        @DisplayName("★ ⑧ 老契约（plan.enabled=false）下也插入 —— 锚不依赖新契约")
        void worksWithBareCodeContract() throws IOException {
            String prompt = builder(tempDir, false).build(pending());

            assertThat(prompt)
                    .as("★ 关掉新契约只是让模型不再报 missing，"
                            + "而「上一轮问过什么」这件事照样有用")
                    .contains("## 上一轮的澄清")
                    .doesNotContain("\"missing\"");
        }

        @Test
        @DisplayName("★★★ ⑨ 槽位词表：prompt 里写的 == ClarifySlots.KNOWN（两个方向）")
        void slotVocabularyMatchesTheCode() throws IOException {
            String prompt = builder(tempDir).build(null);

            // ★ 模型只从 prompt 里学得到槽位名，而代码按 KNOWN 过滤 ——
            //   两者漂移的症状是「模型报了个我们认不出的槽位，于是永远回落固定文案」
            for (String slot : ClarifySlots.KNOWN) {
                assertThat(prompt)
                        .as("KNOWN 里的 %s 必须写在 prompt 的 missing 说明里，"
                                + "否则模型永远不会报它", slot)
                        .contains(slot);
            }
            // 反向：prompt 里列了几条就是几条 —— 多一条 = 模型会报一个我们认不出的槽位，
            // 少一条 = 那个槽位永远不会被报出来（两种都是静默的）
            String missingSection = prompt.substring(prompt.indexOf("- missing："),
                    prompt.indexOf("★ 它只是一条记录"));
            long bullets = missingSection.lines().filter(line -> line.trim().startsWith("·")).count();
            assertThat(bullets)
                    .as("★ 词表里有 %d 个槽位，prompt 里就该列 %d 条 —— "
                            + "两个方向都是静默失败", ClarifySlots.KNOWN.size(),
                            ClarifySlots.KNOWN.size())
                    .isEqualTo(ClarifySlots.KNOWN.size());
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
