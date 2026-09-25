package com.xbla.rag.agent.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 意图树加载器的单元测试。
 *
 * <p>纯文件解析测试，不起 Spring、不连数据库。
 *
 * <p>这个类守的是一条<b>会一路影响到阶段 7</b> 的错误路径：
 * 意图树画错了，5.2 会把问题分到错误的叶子 → 5.4 过滤错范围 →
 * 7.2 的「Top-1 意图准确率」在量一个错误的分类体系。
 * 而这一切在运行时<b>不会报任何错</b>，只会答得不对。
 *
 * <p>所以「能读懂一棵好树」不是重点，<b>「读不懂时会不会安静地继续」</b>才是。
 * 本类的重心在后者。
 *
 * <p>★ 每个「应当失败」的用例都配了一个「只差一处、应当成功」的对照，
 * 见 {@link Contrasts}。没有对照的断言很容易恒真 ——
 * 比如「doc_types 越界要报错」如果实现里压根没解析那个字段，测试照样是绿的。
 *
 * <p><b>关于夹具的写法</b>：{@link #validTree()} 用 {@code StringBuilder} 拼字符串，
 * 测试里的替换用显式的 {@code \n} 锚点而不是 text block。
 * 因为 Java 的 text block 会剥掉「公共缩进」，而 YAML 恰恰<b>靠缩进表达结构</b> ——
 * 用 text block 写带缩进的 YAML 片段，缩进会在不知不觉间被吃掉。
 */
@DisplayName("IntentTree · 分层意图树")
class IntentTreeTest {

    @TempDir
    Path tempDir;

    /** 每个叶子声明的 doc_type，**刻意让 LEAF_1 和 LEAF_2 都含 2** —— 用来验证「允许重叠」 */
    private static final int[][] LEAF_DOC_TYPES = {{1, 2}, {2}, {3}, {4}, {5}};

    // ============================================================
    // 夹具
    // ============================================================

    /**
     * 一棵合法的树：5 类业务意图 + 1 个兜底分支。
     *
     * <p>刻意<b>长得不像</b>真实的意图树（code 是 TOP_1 / LEAF_1 这种）。
     * 这样一旦某条断言意外依赖了真实的业务语义，会立刻暴露 ——
     * 单测该验的是加载器的行为，不是业务分类本身。
     */
    private static String validTree() {
        StringBuilder yaml = new StringBuilder("version: 1\nintents:\n");
        for (int i = 1; i <= 5; i++) {
            String docTypes = java.util.Arrays.stream(LEAF_DOC_TYPES[i - 1])
                    .mapToObj(String::valueOf)
                    .reduce((a, b) -> a + ", " + b)
                    .orElseThrow();
            yaml.append("  - code: TOP_").append(i).append('\n')
                .append("    name: 顶层").append(i).append('\n')
                .append("    description: 顶层意图").append(i).append("的判据\n")
                .append("    answer_style: 回答风格").append(i).append('\n')
                .append("    retrieval: KB\n")
                .append("    children:\n")
                .append("      - code: LEAF_").append(i).append('\n')
                .append("        name: 叶子").append(i).append('\n')
                .append("        description: 叶子").append(i).append("的判据\n")
                .append("        doc_types: [").append(docTypes).append("]\n")
                .append("        examples:\n")
                .append("          - 示例问题").append(i).append("之一\n")
                .append("          - 示例问题").append(i).append("之二\n");
        }
        yaml.append(OUT_OF_SCOPE_BLOCK).append(CLARIFY_BLOCK);
        return yaml.toString();
    }

    private static final String OUT_OF_SCOPE_BLOCK =
            "  - code: OUT_OF_SCOPE\n"
            + "    name: 兜底\n"
            + "    role: OUT_OF_SCOPE\n"
            + "    description: 与平台无关的输入\n"
            + "    answer_style: 说明服务范围\n"
            + "    retrieval: NONE\n"
            + "    children: []\n";

    /** 澄清分支 —— 夹具里带上它，好让「三类 role 都齐全」成为常态 */
    private static final String CLARIFY_BLOCK =
            "  - code: NEEDS_CLARIFICATION\n"
            + "    name: 信息不足\n"
            + "    role: CLARIFY\n"
            + "    description: 业务相关但没说清\n"
            + "    answer_style: 反问一句最关键的缺失信息\n"
            + "    retrieval: NONE\n"
            + "    children: []\n";

    /** LEAF_1 那一整棵子树，用于「删掉它会让顶层没有子意图」这类替换 */
    private static final String LEAF_1_SUBTREE =
            "    children:\n"
            + "      - code: LEAF_1\n"
            + "        name: 叶子1\n"
            + "        description: 叶子1的判据\n"
            + "        doc_types: [1, 2]\n"
            + "        examples:\n"
            + "          - 示例问题1之一\n"
            + "          - 示例问题1之二\n";

    private IntentTree.Tree parse(String yaml) throws IOException {
        Path file = Files.createTempFile(tempDir, "intent-tree", ".yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return IntentTree.parse(file);
    }

    // ============================================================
    // 一、正常路径
    // ============================================================

    @Nested
    @DisplayName("一、正常路径")
    class HappyPath {

        @Test
        @DisplayName("★ 解析出 5 类业务意图 + 2 个非业务分支，后者不计入 5 类")
        void parsesBusinessIntentsAndNonBusinessRoles() throws IOException {
            IntentTree.Tree tree = parse(validTree());

            assertThat(tree.version()).isEqualTo(1);
            assertThat(tree.roots()).hasSize(7);
            // ★ 「5 类意图」这个数字是 docs/10 与 7.2 指标分母的共同契约。
            //   两个非业务分支在这里被排除，正是它们与「业务意图」的分界线
            assertThat(tree.businessIntents()).hasSize(5);
            assertThat(tree.outOfScope()).isPresent()
                    .get().extracting(IntentTree.TopIntent::code).isEqualTo("OUT_OF_SCOPE");
            assertThat(tree.clarify()).isPresent()
                    .get().extracting(IntentTree.TopIntent::code).isEqualTo("NEEDS_CLARIFICATION");
            assertThat(tree.businessIntents())
                    .extracting(IntentTree.TopIntent::code)
                    .doesNotContain("OUT_OF_SCOPE", "NEEDS_CLARIFICATION");
        }

        @Test
        @DisplayName("★ 兜底和澄清是两个角色，不能混（混了用户会拿到错误的回答）")
        void clarifyIsDistinctFromOutOfScope() throws IOException {
            IntentTree.Tree tree = parse(validTree());

            assertThat(tree.outOfScope().orElseThrow().role()).isEqualTo(IntentTree.Role.OUT_OF_SCOPE);
            assertThat(tree.clarify().orElseThrow().role()).isEqualTo(IntentTree.Role.CLARIFY);
            // 两者的 retrieval 都是 NONE（都不检索），所以【不能靠 retrieval 区分它们】
            assertThat(tree.outOfScope().orElseThrow().retrieval())
                    .isEqualTo(tree.clarify().orElseThrow().retrieval())
                    .isEqualTo(IntentTree.Retrieval.NONE);
            // 但它们在分类目标里是两个不同的选项 —— 这才是区分生效的地方
            assertThat(tree.classificationTargets())
                    .extracting(IntentTree.ClassificationTarget::code)
                    .contains("OUT_OF_SCOPE", "NEEDS_CLARIFICATION");
        }

        @Test
        @DisplayName("叶子的 doc_types 和 examples 按声明原样解析")
        void parsesLeafFields() throws IOException {
            IntentTree.Tree tree = parse(validTree());

            assertThat(tree.findLeaf("LEAF_3")).isPresent()
                    .get().extracting(IntentTree.Leaf::docTypes).isEqualTo(java.util.List.of(3));
            assertThat(tree.findLeaf("LEAF_3"))
                    .get().extracting(IntentTree.Leaf::examples)
                    .as("示例问题要全部保留 —— 5.2 靠它做少样本")
                    .isEqualTo(java.util.List.of("示例问题3之一", "示例问题3之二"));
        }

        @Test
        @DisplayName("顶层的 docTypesUnion 是它所有叶子 doc_types 的并集")
        void rollsUpDocTypesUnion() throws IOException {
            IntentTree.Tree tree = parse(validTree());

            assertThat(tree.findTop("TOP_1")).isPresent()
                    .get().extracting(IntentTree.TopIntent::docTypesUnion)
                    .isEqualTo(Set.of(1, 2));
            assertThat(tree.findTop("TOP_2")).isPresent()
                    .get().extracting(IntentTree.TopIntent::docTypesUnion)
                    .isEqualTo(Set.of(2));
            // 并集保持「书写顺序 + 去重」
            assertThat(tree.docTypesByTop()).containsKeys(
                    "TOP_1", "TOP_2", "TOP_3", "TOP_4", "TOP_5",
                    "OUT_OF_SCOPE", "NEEDS_CLARIFICATION");
        }

        @Test
        @DisplayName("doc_types 允许重叠 —— 同一个类型被多个叶子引用")
        void allowsOverlappingDocTypes() throws IOException {
            // ★ 这是「答案全集」语义的直接体现：真实树里 doc_type=4 同时被
            //   COUPON / PRICE_PROTECTION / RETURN_EXCHANGE / RULES_AND_PROCESS 引用。
            //   如果哪天有人想把它改成「每个叶子一个主类型」，这条会红
            IntentTree.Tree tree = parse(validTree());

            assertThat(tree.docTypesOf("LEAF_1")).contains(2);
            assertThat(tree.docTypesOf("LEAF_2")).contains(2);
            // 反过来也确认一下两个叶子确实不同 —— 否则上面那两条断言
            // 可能只是「所有叶子都含 2」这种平凡情况
            assertThat(tree.docTypesOf("LEAF_1")).contains(1);
            assertThat(tree.docTypesOf("LEAF_2")).doesNotContain(1);
        }

        @Test
        @DisplayName("docTypesOf 查不到叶子时返回空集而不是抛异常")
        void unknownLeafYieldsEmptySet() throws IOException {
            IntentTree.Tree tree = parse(validTree());

            // 5.4 的真实场景：分类结果是兜底分支（本来就不检索），
            // 或者模型编了一个不存在的 code。两者都表现为「没有可过滤的类型」，
            // 处理方式相同 —— 所以这里返回空集而不是抛
            assertThat(tree.docTypesOf("NO_SUCH_LEAF")).isEmpty();
            assertThat(tree.findLeaf("NO_SUCH_LEAF")).isEmpty();
        }

        @Test
        @DisplayName("走工具的意图叶子声明空 doc_types 是合法的")
        void toolLeavesMayDeclareEmptyDocTypes() throws IOException {
            // 真实树里 ORDER_LOGISTICS 的三个叶子写的是 doc_types: []
            String yaml = validTree()
                    .replace("    retrieval: KB\n" + LEAF_1_SUBTREE,
                             "    retrieval: TOOL\n    tools: [query_order_status]\n"
                                     + LEAF_1_SUBTREE)
                    .replace("        doc_types: [1, 2]\n", "        doc_types: []\n");

            IntentTree.Tree tree = parse(yaml);
            assertThat(tree.docTypesOf("LEAF_1")).isEmpty();
            assertThat(tree.findTop("TOP_1")).isPresent()
                    .get().extracting(IntentTree.TopIntent::retrieval)
                    .isEqualTo(IntentTree.Retrieval.TOOL);
        }
    }

    // ============================================================
    // 二、文件不存在
    // ============================================================

    @Nested
    @DisplayName("二、文件不存在")
    class MissingFile {

        @Test
        @DisplayName("直接抛异常，且错误里带绝对路径（而不是静默返回空树）")
        void missingFileThrows() {
            Path missing = tempDir.resolve("not-here.yml");

            // ★ 与 CorpusManifest 刻意相反：清单缺少时打 WARN 回落（老路径 ?docType= 还在，
            //   有真兜底），而意图树没有任何合理的兜底值 ——
            //   「回落」只能等于把所有问题判成同一类
            assertThatThrownBy(() -> IntentTree.parse(missing))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("意图树文件不存在")
                    .hasMessageContaining(missing.toAbsolutePath().toString());
        }
    }

    // ============================================================
    // 三、畸形输入
    // ============================================================

    @Nested
    @DisplayName("三、畸形输入")
    class Malformed {

        @Test
        @DisplayName("version 对不上要报错，而不是静默忽略新字段")
        void rejectsUnsupportedVersion() {
            assertThatThrownBy(() -> parse(validTree().replace("version: 1", "version: 2")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("version 必须是 1");
        }

        @Test
        @DisplayName("code 必须是大写下划线")
        void rejectsBadCodeFormat() {
            assertThatThrownBy(() -> parse(validTree()
                    .replace("      - code: LEAF_1\n", "      - code: leaf-1\n")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("code 格式不合法");
        }

        @Test
        @DisplayName("全树 code 必须唯一（重名会让 7.1 的标注分不清是哪一类）")
        void rejectsDuplicateCode() {
            // 把 LEAF_2 改成和 LEAF_1 同名 —— 两棵不同子树下各有一个 LEAF_1
            assertThatThrownBy(() -> parse(validTree()
                    .replace("      - code: LEAF_2\n", "      - code: LEAF_1\n")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("出现了两次");
        }

        @Test
        @DisplayName("doc_types 越界要报错，并列出合法词表")
        void rejectsOutOfRangeDocType() {
            assertThatThrownBy(() -> parse(validTree().replace("doc_types: [3]", "doc_types: [6]")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("越界")
                    .hasMessageContaining("1商品详情");
        }

        @Test
        @DisplayName("★ 叶子没有 description 要报错（5.2 的分类 prompt 靠它判据）")
        void rejectsLeafWithoutDescription() {
            // 阶段 5.2 起 description 是必填的：分类 prompt 里每个候选都要有一句判据，
            // 缺了它，模型只能靠 examples 猜 —— 而 examples 恰恰不能进 prompt
            // （和评测题重合 18/20，见 ADR-036）
            assertThatThrownBy(() -> parse(validTree()
                    .replace("        description: 叶子1的判据\n", "")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("LEAF_1")
                    .hasMessageContaining("description");
        }

        @Test
        @DisplayName("KB 意图的叶子没有 doc_types 要报错")
        void rejectsKbLeafWithoutDocTypes() {
            assertThatThrownBy(() -> parse(validTree().replace("        doc_types: [1, 2]\n", "")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("缺少 doc_types");
        }

        @Test
        @DisplayName("叶子没有 examples 要报错（5.2 靠它做少样本）")
        void rejectsLeafWithoutExamples() {
            assertThatThrownBy(() -> parse(validTree().replace(
                    "        examples:\n"
                    + "          - 示例问题5之一\n"
                    + "          - 示例问题5之二\n", "")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("缺少 examples");
        }

        @Test
        @DisplayName("retrieval=KB 却没有子意图要报错（这类问题会永远检索不到东西）")
        void rejectsKbIntentWithoutChildren() {
            assertThatThrownBy(() -> parse(validTree()
                    .replace("    retrieval: KB\n" + LEAF_1_SUBTREE,
                             "    retrieval: KB\n    children: []\n")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("没有任何子意图");
        }

        @Test
        @DisplayName("走工具的意图声明 doc_types 要报错（自相矛盾）")
        void rejectsToolIntentWithDocTypes() {
            // 只把 TOP_1 改成 TOOL，它的叶子还留着 doc_types: [1, 2]
            assertThatThrownBy(() -> parse(validTree()
                    .replace("    retrieval: KB\n" + LEAF_1_SUBTREE,
                             "    retrieval: TOOL\n    tools: [query_order_status]\n"
                                     + LEAF_1_SUBTREE)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("自相矛盾");
        }

        @Test
        @DisplayName("业务意图不是 5 类要报错，并指出改哪里")
        void rejectsWrongBusinessIntentCount() {
            String four = validTree().replace(
                    "  - code: TOP_5\n"
                    + "    name: 顶层5\n"
                    + "    description: 顶层意图5的判据\n"
                    + "    answer_style: 回答风格5\n"
                    + "    retrieval: KB\n"
                    + "    children:\n"
                    + "      - code: LEAF_5\n"
                    + "        name: 叶子5\n"
                    + "        description: 叶子5的判据\n"
                    + "        doc_types: [5]\n"
                    + "        examples:\n"
                    + "          - 示例问题5之一\n"
                    + "          - 示例问题5之二\n", "");
            assertThat(four).doesNotContain("TOP_5");

            assertThatThrownBy(() -> parse(four))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("4 类业务意图")
                    // 错误信息要能让读的人知道去哪改，而不是只说「不对」
                    .hasMessageContaining("docs/10")
                    .hasMessageContaining("EXPECTED_BUSINESS_INTENTS");
        }

        @Test
        @DisplayName("非业务分支声明了 retrieval 要报错（兜底和澄清都要拦）")
        void rejectsNonBusinessRoleWithRetrieval() {
            // ★ 用【整块替换】，而不是只替换 "retrieval: NONE" 那一行 ——
            //   两块里都有那一行，只替那一行会同时改到两个块（String.replace 替全部），
            //   于是分不清报的是哪一块的错
            for (String block : new String[]{OUT_OF_SCOPE_BLOCK, CLARIFY_BLOCK}) {
                String broken = validTree().replace(block,
                        block.replace("retrieval: NONE", "retrieval: KB"));
                assertThat(broken).isNotEqualTo(validTree());   // 确认真的改到了

                assertThatThrownBy(() -> parse(broken))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("不该去检索任何东西");
            }
        }

        @Test
        @DisplayName("非业务分支带了子意图要报错")
        void rejectsNonBusinessRoleWithChildren() {
            // 子节点刻意写成【不带 doc_types】的形式 ——
            // 否则会同时触发「NONE 类意图不该声明 doc_types」，两个错误混在一起，
            // 就分不清测试验的是哪一条了
            String broken = validTree().replace(CLARIFY_BLOCK,
                    CLARIFY_BLOCK.replace("    children: []\n",
                            "    children:\n"
                            + "      - code: EXTRA_LEAF\n"
                            + "        name: 多余的叶子\n"
                            + "        description: 不该存在\n"
                            + "        examples:\n"
                            + "          - 示例\n"));

            assertThatThrownBy(() -> parse(broken))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("个子意图");
        }

        @Test
        @DisplayName("★ 一次报出所有问题，而不是遇到第一个就停")
        void reportsAllProblemsAtOnce() {
            // 同时制造三处【互相独立】的问题
            String broken = validTree()
                    .replace("version: 1", "version: 9")
                    .replace("      - code: LEAF_2\n", "      - code: LEAF_1\n")
                    .replace("doc_types: [3]", "doc_types: [7]");

            assertThatThrownBy(() -> parse(broken))
                    .isInstanceOf(IllegalStateException.class)
                    // 三个问题全部出现在同一条消息里 ——
                    // 改配置的人希望一轮把该修的修完，而不是修一个报一个
                    .hasMessageContaining("version 必须是 1")
                    .hasMessageContaining("出现了两次")
                    .hasMessageContaining("越界");
        }

        @Test
        @DisplayName("YAML 语法错误要报错，并指出是语法问题而不是内容问题")
        void rejectsBrokenYaml() {
            assertThatThrownBy(() -> parse("version: 1\nintents:\n  - code: [未闭合\n"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("YAML 语法错误");
        }

        @Test
        @DisplayName("空文件要报错")
        void rejectsEmptyFile() {
            assertThatThrownBy(() -> parse(""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("空文件");
        }

        @Test
        @DisplayName("缺少 intents 列表要报错")
        void rejectsMissingIntents() {
            assertThatThrownBy(() -> parse("version: 1\n"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("缺少非空的 intents 列表");
        }
    }

    // ============================================================
    // 四、★ 正-反对照（本项目的强制风格）
    // ============================================================

    @Nested
    @DisplayName("四、正-反对照")
    class Contrasts {

        @Test
        @DisplayName("同一棵树：声明了 doc_types 查得到；把那一行删掉就加载失败")
        void docTypesIsActuallyRead() throws IOException {
            // 正
            assertThat(parse(validTree()).docTypesOf("LEAF_1")).isEqualTo(Set.of(1, 2));

            // 反 —— 只删这一行。它证明上面那个「查得到」是真的解析了这个字段，
            // 而不是在某个默认值上碰巧成立
            assertThatThrownBy(() -> parse(validTree().replace("        doc_types: [1, 2]\n", "")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("缺少 doc_types");
        }

        @Test
        @DisplayName("同一棵树：业务意图计数排除了非业务角色，而不是简单数 roots 的长度")
        void businessIntentCountExcludesNonBusinessRoles() throws IOException {
            IntentTree.Tree tree = parse(validTree());
            assertThat(tree.roots()).hasSize(7);
            assertThat(tree.businessIntents()).hasSize(5);

            // 反：再加一个【同样 role=OUT_OF_SCOPE】的分支，业务意图数不该变。
            //   它会被另一个规则（同角色最多一个）拦下 —— 而错误信息必须是那一条，
            //   不能是「业务意图数不对」。这恰好证明两个计数是分开算的
            String secondOutOfScope = validTree() + """
                      - code: SMALLTALK
                        name: 闲聊
                        role: OUT_OF_SCOPE
                        description: 问候
                        answer_style: 简短回应
                        retrieval: NONE
                        children: []
                    """.replace("\n            ", "\n    ");

            assertThatThrownBy(() -> parse(secondOutOfScope))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("最多只能有一个")
                    .hasMessageNotContaining("类业务意图");
        }

        @Test
        @DisplayName("同一棵树：漏掉 role 就默认 BUSINESS，于是数量校验会拦下它")
        void omittedRoleDefaultsToBusiness() throws IOException {
            // 正：带 role 的两块不计入业务意图
            assertThat(parse(validTree()).businessIntents()).hasSize(5);

            // 反：把 CLARIFY_BLOCK 的 role 行删掉，它就变成第 6 个业务意图 ——
            //   被数量校验拦下。证明「默认 BUSINESS」这件事真的生效，
            //   而不是默默把它当成某个非业务角色
            String noRole = validTree().replace(CLARIFY_BLOCK,
                    CLARIFY_BLOCK.replace("    role: CLARIFY\n", ""));

            assertThatThrownBy(() -> parse(noRole))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("6 类业务意图");
        }

        @Test
        @DisplayName("同一个 code：合法格式能查到；小写连字符被拒绝")
        void codeFormatIsActuallyChecked() throws IOException {
            assertThat(parse(validTree()).findLeaf("LEAF_1")).isPresent();

            assertThatThrownBy(() -> parse(validTree()
                    .replace("code: OUT_OF_SCOPE", "code: out-of-scope")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("code 格式不合法");
        }

        @Test
        @DisplayName("同一处 doc_types：在合法范围内通过；改成 6 被拒绝")
        void docTypeRangeIsActuallyEnforced() throws IOException {
            assertThat(parse(validTree()).docTypesOf("LEAF_3")).isEqualTo(Set.of(3));

            assertThatThrownBy(() -> parse(validTree().replace("doc_types: [3]", "doc_types: [6]")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("越界");
        }
    }

    // ============================================================
    // 五、★ 真实的那份意图树
    // ============================================================

    @Nested
    @DisplayName("五、真实的意图树")
    class RealTree {

        /**
         * ★ 这条测试跑的是 <b>git 里那一份真的</b> {@code data/agent/intent-tree.yml}。
         *
         * <p>它不是「再验一遍解析器」（上面已经做了），而是守住
         * <b>5.2 / 5.4 / 7.1 都建立在这份文件上的几个结构性事实</b>：
         * 哪些叶子必须存在、哪些 {@code doc_type} 必须被声明。
         * 这些一旦被误删，程序不会报错，只会少回答一类问题。
         */
        @Test
        @DisplayName("data/agent/intent-tree.yml 可解析，且关键叶子与声明齐全")
        void realIntentTreeIsValid() {
            Path real = Path.of("data/agent/intent-tree.yml");
            // 意图树是 git 跟踪的，正常克隆后一定在。
            // 这里不 assumeTrue —— 缺了就是真问题，应该失败
            assertThat(real).exists();

            IntentTree.Tree tree = IntentTree.parse(real);

            assertThat(tree.businessIntents()).hasSize(5);
            assertThat(tree.outOfScope()).isPresent();
            assertThat(tree.businessIntents())
                    .extracting(IntentTree.TopIntent::code)
                    .containsExactlyInAnyOrder("PRODUCT_INQUIRY", "PURCHASE_ADVICE",
                            "PROMOTION", "AFTER_SALE", "ORDER_LOGISTICS");

            // ★ PRICE_PROTECTION 是最容易被「顺手改窄」的一个：
            //   它的 gold 跨 {2,3,4}，声明里少一个都会让
            //   IntentTreeConsistencyTest 变红 —— 这里先钉一道更早的防线
            assertThat(tree.docTypesOf("PRICE_PROTECTION"))
                    .as("价格保护的答案在三份文档里：促销规则 / 价格保护政策 / 售后FAQ")
                    .containsExactlyInAnyOrder(2, 3, 4);

            // ★ doc_type=4（售后FAQ）是跨业务域的：它同时答售后和促销的问题。
            //   谁要是把下面任一个改成「只声明 3」，这条会红
            assertThat(tree.docTypesOf("COUPON"))
                    .as("优惠券的答案在促销规则和售后FAQ 里")
                    .containsExactlyInAnyOrder(3, 4);
            assertThat(tree.docTypesOf("FULL_REDUCTION"))
                    .as("秒杀问题在售后FAQ 里有条目（「秒杀没付款会怎样」）")
                    .contains(4);

            // MEMBERSHIP 是排查「树有没有漏掉语料内容」时补上的 ——
            // 售后FAQ 有「会员等级有什么用」，而当时树上没有任何叶子能接住它
            assertThat(tree.findLeaf("MEMBERSHIP"))
                    .as("会员权益必须有一个叶子，否则 7.1 标注时它无处可去")
                    .isPresent();

            // 走工具的意图不声明 doc_types —— 它们不检索知识库
            assertThat(tree.docTypesOf("ORDER_STATUS")).isEmpty();
            assertThat(tree.docTypesOf("INVENTORY")).isEmpty();

            // ★ 澄清分支（5.3）：它和兜底是两个角色，混了用户会拿到错误的回答 ——
            //   问「那个怎么样」时收到「我只处理商品导购与售后问题」
            assertThat(tree.clarify())
                    .as("信息不足必须有一个独立的分类目标，否则模型会把它塞进兜底")
                    .isPresent();
            assertThat(tree.clarify().orElseThrow().role())
                    .isEqualTo(IntentTree.Role.CLARIFY);
            assertThat(tree.outOfScope().orElseThrow().role())
                    .isEqualTo(IntentTree.Role.OUT_OF_SCOPE);

            // 「怎么查物流」这类【通用流程】问题由知识库答，
            // 「我的订单到哪了」这类【具体数据】问题才走工具。
            // 两者的区别是通用 vs 具体，不是物流 vs 售后
            assertThat(tree.docTypesOf("RULES_AND_PROCESS")).isNotEmpty();

            // 所有示例问题都不能为空 —— 5.2 的 prompt 直接拼它们
            assertThat(tree.allLeaves())
                    .allSatisfy(leaf -> assertThat(leaf.examples())
                            .as("叶子 %s 的示例问题", leaf.code())
                            .isNotEmpty());
        }

        // ========================================================
        // ★ retrievalOf（阶段 5.8 新增的查表方法）
        // ========================================================

        /**
         * ★★ 它是 {@code ChatServiceImpl.isToolIntent()} 的唯一判据，
         * 而那个判据决定「这次问答走检索还是走工具」——
         * 判错的后果是<b>模型拿通用规则编一个订单状态出来</b>（ADR-044）。
         */
        @Test
        @DisplayName("★★ retrievalOf：顶层、叶子、不存在的 code 三种输入")
        void retrievalOfHandlesAllThreeInputs() {
            IntentTree.Tree tree = IntentTree.parse(Path.of("data/agent/intent-tree.yml"));

            // ① ★【顶层】—— TOOL / NONE 类意图是以顶层 code 参与分类的
            //    （见 classificationTargets 那条「行为相同的不区分」）,
            //    所以模型返回的会是 ORDER_LOGISTICS，而不是它的某个子叶子
            assertThat(tree.retrievalOf("ORDER_LOGISTICS"))
                    .as("★ 这是「我的订单到哪了」分类结果的真实形态 —— "
                            + "只查叶子的话它会落到 else 分支")
                    .isEqualTo(IntentTree.Retrieval.TOOL);

            assertThat(tree.retrievalOf("OUT_OF_SCOPE"))
                    .isEqualTo(IntentTree.Retrieval.NONE);

            // ② ★【叶子】—— KB 类意图分类到叶子，它的 retrieval 继承自父顶层
            assertThat(tree.retrievalOf("RETURN_EXCHANGE"))
                    .as("★ 叶子自己不声明 retrieval，是继承的 —— "
                            + "「检索还是调工具」是整类的性质")
                    .isEqualTo(IntentTree.Retrieval.KB);
            assertThat(tree.retrievalOf("ORDER_STATUS"))
                    .as("★ 工具类的【子叶子】也必须查到 TOOL，不能因为「它不是顶层」就返回 null")
                    .isEqualTo(IntentTree.Retrieval.TOOL);

            // ③ ★ 模型编了一个不存在的 code → null，【不是 NONE】
            assertThat(tree.retrievalOf("NOT_A_REAL_CODE")).isNull();
            assertThat(tree.retrievalOf(null)).isNull();
        }

        @Test
        @DisplayName("★ 对照：TOOL 类和 KB 类的 doc_types 声明互斥（这正是 retrievalOf 能查表的前提）")
        void toolIntentsDeclareNoDocTypes() {
            IntentTree.Tree tree = IntentTree.parse(Path.of("data/agent/intent-tree.yml"));

            // ★ 这条是「叶子继承父顶层」这个设计的直接推论：
            //   如果某个 TOOL 类的叶子自己声明了 doc_types，那就意味着
            //   它想「既查实时数据又检索知识库」—— 而 5.8 的实现做不到，
            //   因为它一旦认出 retrieval=TOOL 就【短路掉整条检索】。
            //   IntentTree 的校验规则本来就禁止这种声明，
            //   这里从使用方的角度再钉一道
            for (IntentTree.Leaf leaf : tree.allLeaves()) {
                IntentTree.Retrieval retrieval = tree.retrievalOf(leaf.code());
                if (retrieval == IntentTree.Retrieval.TOOL) {
                    assertThat(tree.docTypesOf(leaf.code()))
                            .as("工具类叶子 %s 不该声明 doc_types —— "
                                    + "工具路径【完全不做检索】，声明了也不会被用上", leaf.code())
                            .isEmpty();
                }
            }
        }
    }

    // ============================================================
    // ★ 阶段 9.3：tools 的【合法位置】
    // ============================================================

    /**
     * ★★ 这一组测的不是「tools 怎么解析」，而是<b>它能写在哪一层</b>。
     *
     * <p>规则只有一条，而它完全由「分类落在哪一层」决定：
     *
     * <pre>
     *   retrieval=TOOL  分类落点是【顶层】→ tools 写在顶层
     *   retrieval=KB    分类落点是【叶子】→ tools 写在叶子
     *   写在别处 = 那一格【永远读不到】 = 启动即崩
     * </pre>
     *
     * <p>为什么是崩而不是 WARN：一个读不到的声明<b>没有任何可观测的症状</b>。
     * 用户看到的是「我明明在树里配了，模型怎么不用」——
     * 而那时候已经过了最容易排查的阶段（配置加载）。
     * 同 {@code structured_facts} 那条「声明了一个永远不生效的东西就启动即崩」。
     */
    @Nested
    @DisplayName("★ 阶段 9.3 · tools 只能写在【分类的落点】上")
    class ToolPlacement {

        /** 把 TOP_1 整个改成 TOOL 类。{@code toolsLine} 为 null 表示不写 tools */
        private String withToolTop(String toolsLine) {
            return validTree()
                    .replace("    retrieval: KB\n" + LEAF_1_SUBTREE,
                            "    retrieval: TOOL\n"
                                    + (toolsLine == null ? "" : toolsLine)
                                    + LEAF_1_SUBTREE)
                    .replace("        doc_types: [1, 2]\n", "        doc_types: []\n");
        }

        /** 在 LEAF_1 的 doc_types 后面插一行 —— 用来把 tools 放到【叶子】上 */
        private String withLeafTools(String toolsLine) {
            return validTree().replace("        doc_types: [1, 2]\n",
                    "        doc_types: [1, 2]\n" + toolsLine);
        }

        // ---------- 合法：两个位置各一个 ----------

        @Test
        @DisplayName("✅ TOOL 的顶层写 tools → 通过，toolsOf 读得到")
        void acceptsToolsOnToolTop() throws IOException {
            IntentTree.Tree tree = parse(withToolTop("    tools: [query_order_status]\n"));

            assertThat(tree.toolsOf("TOP_1")).containsExactly("query_order_status");
            // ★ 同一个 TOOL 类意图的【叶子】上读不到 ——
            //   那是对的：那一类不按叶子分类，叶子这一格本来就没人读
            assertThat(tree.toolsOf("LEAF_1")).isEmpty();
        }

        @Test
        @DisplayName("✅ KB 的叶子写 tools → 通过（这就是【混合轮】）")
        void acceptsToolsOnKbLeaf() throws IOException {
            IntentTree.Tree tree = parse(withLeafTools(
                    "        tools: [search_products, recommend_products]\n"));

            assertThat(tree.toolsOf("LEAF_1"))
                    .containsExactly("search_products", "recommend_products");
            // ★ 反面：它的兄弟叶子没写，就必须是空的 ——
            //   否则「按意图裁剪」会变成「整个顶层一起裁剪」
            assertThat(tree.toolsOf("LEAF_2")).isEmpty();
            // ★ 而顶层码上读不到东西：KB 类意图没有顶层工具清单
            assertThat(tree.toolsOf("TOP_1")).isEmpty();
        }

        // ---------- 非法：三处写错位置 ----------

        @Test
        @DisplayName("❌ KB 的顶层写 tools → 崩（KB 类分类落在叶子，这一格读不到）")
        void rejectsToolsOnKbTop() {
            assertThatThrownBy(() -> parse(withTopToolsOnKbTop()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("分类落点是【叶子】");
        }

        private String withTopToolsOnKbTop() {
            return validTree().replace("    retrieval: KB\n    children:\n",
                    "    retrieval: KB\n    tools: [search_products]\n    children:\n");
        }

        /**
         * ★★ 这一条<b>同时覆盖两个非业务分支</b>（{@code OUT_OF_SCOPE} 和
         * {@code CLARIFY}）—— 它们写的是同一段 YAML，所以 {@code replace} 一次改两个。
         *
         * <p>⚠️ 断言里那句「role != BUSINESS 的两个分支都属于这一类」不是装饰：
         * 最初这里写了两条独立的检查（一条判 role、一条判 retrieval），
         * 而 role 那条总是先命中，于是「NONE 顶层不许带工具」这条<b>永远走不到</b>。
         * 是这条测试挂掉才发现的 —— 一段看起来有意义的死代码。
         */
        @Test
        @DisplayName("❌ NONE 的顶层写 tools → 崩（两个非业务分支都属于这一类）")
        void rejectsToolsOnNoneTop() {
            String yaml = validTree().replace(
                    "    retrieval: NONE\n    children: []\n",
                    "    retrieval: NONE\n    tools: [query_my_coupons]\n    children: []\n");

            // ★ 前-后对照：改之前这两块都合法，改之后两块都要被拒
            assertThat(yaml).isNotEqualTo(validTree());
            assertThatThrownBy(() -> parse(yaml))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("NONE 类意图不调任何工具")
                    .hasMessageContaining("role != BUSINESS 的两个分支都属于这一类");
        }

        @Test
        @DisplayName("❌ TOOL 的【叶子】写 tools → 崩（那类意图的分类落点是顶层）")
        void rejectsToolsOnLeafUnderToolTop() {
            String yaml = withToolTop("    tools: [query_order_status]\n")
                    .replace("        doc_types: []\n",
                            "        doc_types: []\n        tools: [query_inventory]\n");

            assertThatThrownBy(() -> parse(yaml))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("那类意图的分类落点是【顶层】");
        }

        @Test
        @DisplayName("❌ TOOL 的顶层【不写】tools → 崩（那类问题的答案全部来自工具）")
        void rejectsToolTopWithoutTools() {
            // ★ 和上面「TOOL 顶层写 tools → 通过」只差那一行 —— 正-反对照
            assertThatThrownBy(() -> parse(withToolTop(null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("却没有可用的 tools");
        }

        // ---------- 形状 ----------

        @Test
        @DisplayName("❌ tools 不是列表 → 崩")
        void rejectsToolsThatIsNotAList() {
            assertThatThrownBy(() -> parse(withLeafTools("        tools: search_products\n")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tools 必须是列表");
        }

        @Test
        @DisplayName("❌ tools 里有重复 → 崩（复制粘贴留下的痕迹）")
        void rejectsDuplicateTools() {
            assertThatThrownBy(() -> parse(withLeafTools(
                    "        tools: [search_products, search_products]\n")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("出现了两次");
        }

        @Test
        @DisplayName("toolsOf 对不存在的 code 返回空表，不抛也不返回 null")
        void toolsOfUnknownCodeIsEmpty() throws IOException {
            IntentTree.Tree tree = parse(validTree());

            assertThat(tree.toolsOf("MODEL_MADE_THIS_UP")).isEmpty();
            assertThat(tree.toolsOf(null)).isEmpty();
        }
    }
}
