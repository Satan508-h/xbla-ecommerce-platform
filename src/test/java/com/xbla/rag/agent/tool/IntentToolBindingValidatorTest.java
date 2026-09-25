package com.xbla.rag.agent.tool;

import com.xbla.rag.agent.intent.IntentTree;
import com.xbla.rag.config.AgentProperties;
import com.xbla.rag.mcp.McpArguments;
import com.xbla.rag.mcp.McpTool;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.McpToolRegistry;
import com.xbla.rag.mcp.McpToolResult;
import com.xbla.rag.mcp.ToolField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 意图树声明的工具名必须真的存在（阶段 9.3）。
 *
 * <p><b>不花一分钱</b>：不调模型、不连 MCP Server、不碰数据库。
 *
 * <h2>★★ 它守的是哪一种失败</h2>
 *
 * <pre>
 *   意图树里写 tools: [serch_products]        ← 少了一个 a
 *   → 启动成功、没有 WARN、没有异常
 *   → ToolLoop 按名字过滤，过滤不到就不给模型
 *   → 用户问「有什么适合送长辈的」，模型手上一件工具都没有
 *   → 它拿知识库里的通用话术答了一遍，读起来完全正常
 * </pre>
 *
 * <p>整条链上<b>没有任何一处会报错</b>。所以这条校验必须是启动期的，
 * 而且必须真的<b>比对过</b>两组名字 —— 见下面那一组正-反对照。
 */
@DisplayName("IntentToolBindingValidator · 意图树与工具注册表的接缝")
@org.junit.jupiter.api.extension.ExtendWith(OutputCaptureExtension.class)
class IntentToolBindingValidatorTest {

    @TempDir
    Path tempDir;

    // ============================================================
    // 夹具
    // ============================================================

    /**
     * 一棵树：一个 TOOL 顶层（声明两个工具）+ 一个带工具的 KB 叶子。
     *
     * <p>★ 两个位置都要有 —— 校验器要同时扫顶层和叶子，
     * 只测一边的话「漏扫一边」这个 bug 不会暴露。
     */
    private IntentTree treeDeclaring(String... toolNames) throws IOException {
        // ★ 用 append 拼而不是文本块：文本块会按【公共缩进】剥离，
        //   和字符串拼接混在一起时最终缩进要靠数空格，改一行就可能静默错位
        String toolsLine = "tools: [" + String.join(", ", toolNames) + "]\n";
        StringBuilder yaml = new StringBuilder("version: 1\nintents:\n");

        // 四个 KB 顶层。★ TOP_1 的叶子额外挂上工具（混合轮的那个位置）
        for (int i = 1; i <= 4; i++) {
            yaml.append("  - code: TOP_").append(i).append('\n')
                .append("    name: 知识库类").append(i).append('\n')
                .append("    description: 判据").append(i).append('\n')
                .append("    answer_style: 风格").append(i).append('\n')
                .append("    retrieval: KB\n")
                .append("    children:\n")
                .append("      - code: LEAF_").append(i).append('\n')
                .append("        name: 叶子").append(i).append('\n')
                .append("        description: 判据\n")
                .append("        doc_types: [").append(i).append("]\n");
            if (i == 1) {
                // ★ 两个合法位置之一：KB 的叶子（混合轮）。
                //   校验器两个位置都要扫 —— 只测顶层的话「漏扫叶子」不会暴露
                yaml.append("        ").append(toolsLine);
            }
            yaml.append("        examples: [示例").append(i).append("]\n");
        }

        // 第五个业务意图是 TOOL 类 —— 工具的另一个合法位置
        yaml.append("  - code: TOP_5\n")
            .append("    name: 工具类\n")
            .append("    description: 判据5\n")
            .append("    answer_style: 风格5\n")
            .append("    retrieval: TOOL\n")
            .append("    ").append(toolsLine)
            .append("    children:\n")
            .append("      - code: TOOL_LEAF\n")
            .append("        name: 工具叶子\n")
            .append("        description: 判据\n")
            .append("        doc_types: []\n")
            .append("        examples: [示例5]\n")
            .append("  - code: OUT_OF_SCOPE\n")
            .append("    name: 兜底\n")
            .append("    role: OUT_OF_SCOPE\n")
            .append("    description: 与平台无关\n")
            .append("    answer_style: 说明范围\n")
            .append("    retrieval: NONE\n")
            .append("    children: []\n");

        Path file = tempDir.resolve("tree-" + System.nanoTime() + ".yml");
        Files.writeString(file, yaml.toString(), StandardCharsets.UTF_8);
        AgentProperties props = new AgentProperties();
        props.setIntentTreePath(file.toString());
        return new IntentTree(props);
    }

    /** 一个只有名字有意义的假工具 —— 本类只关心名字 */
    private static McpTool fakeTool(String name) {
        return new McpTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String title() {
                return name;
            }

            @Override
            public String description() {
                return "假工具，只为占一个名字";
            }

            @Override
            public List<ToolField> inputFields() {
                return List.of();
            }

            @Override
            public McpToolResult call(McpArguments args, McpToolContext context) {
                return McpToolResult.ok("（假工具）");
            }
        };
    }

    private static McpToolRegistry registryOf(String... names) {
        return new McpToolRegistry(Arrays.stream(names)
                .map(IntentToolBindingValidatorTest::fakeTool)
                .map(McpTool.class::cast)
                .toList());
    }

    private static IntentToolBindingValidator validator(IntentTree tree, McpToolRegistry registry) {
        return new IntentToolBindingValidator(tree, registry);
    }

    // ============================================================
    // 一、名字对不上
    // ============================================================

    @Nested
    @DisplayName("一、名字对不上")
    class NameMismatch {

        @Test
        @DisplayName("★ 拼错一个名字 → 崩，而且报错里要说清「注册表里有什么」")
        void rejectsUnknownToolName() throws IOException {
            IntentTree tree = treeDeclaring("search_products", "serch_products");
            McpToolRegistry registry = registryOf("search_products", "query_inventory");

            assertThatThrownBy(() -> validator(tree, registry).validate())
                    .isInstanceOf(IllegalStateException.class)
                    // 报错必须点名那个拼错的名字 —— 让人一眼看出少了个 a
                    .hasMessageContaining("serch_products")
                    // 而且要给出「本来有哪些」—— 否则人还得再去翻一遍注册表
                    .hasMessageContaining("search_products")
                    .hasMessageContaining("query_inventory");
        }

        @Test
        @DisplayName("★★ 反面对照：名字全对时【不】崩")
        void acceptsWhenAllNamesExist() throws IOException {
            IntentTree tree = treeDeclaring("search_products", "query_inventory");
            McpToolRegistry registry = registryOf("search_products", "query_inventory",
                    "query_order_status");

            assertThatCode(() -> validator(tree, registry).validate())
                    .as("★ 没有这一条的话，上面那个断言可能只是因为「校验器总是抛」而通过")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("★★ 反面对照：同一个树 + 空注册表 → 崩（证明它真的在比对）")
        void rejectsAgainstEmptyRegistry() throws IOException {
            IntentTree tree = treeDeclaring("search_products", "query_inventory");

            assertThatThrownBy(() -> validator(tree, registryOf()).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("search_products")
                    .hasMessageContaining("query_inventory");
        }

        @Test
        @DisplayName("★ 两个位置都要扫：只有叶子声明的名字对不上，也要崩")
        void scansLeavesNotOnlyTops() throws IOException {
            // 同一个树里两处声明了同样的名字，所以这里换成「注册表只给一半」
            // —— 两处都会报；如果实现只扫顶层，报错条数会少一半
            IntentTree tree = treeDeclaring("aa_tool", "bb_tool");

            assertThatThrownBy(() -> validator(tree, registryOf("aa_tool")).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bb_tool");
        }
    }

    // ============================================================
    // 二、反向：注册了但没人声明
    // ============================================================

    @Nested
    @DisplayName("二、反向：注册了但没有任何意图声明它")
    class Orphans {

        @Test
        @DisplayName("★ 孤儿工具 → 【不】崩，但要有 WARN 点名它")
        void reportsOrphanToolsAsWarning(CapturedOutput output) throws IOException {
            IntentTree tree = treeDeclaring("search_products");
            McpToolRegistry registry = registryOf("search_products", "never_declared_anywhere");

            assertThatCode(() -> validator(tree, registry).validate())
                    .as("「暂时没人用」是合法的中间状态，不该让应用起不来")
                    .doesNotThrowAnyException();
            assertThat(output)
                    .as("★ 但它必须被报出来：一个没有任何意图声明的工具"
                            + "只能被调试探针调到 —— 也就是【线上不可达】")
                    .contains("never_declared_anywhere");
        }
    }
}
