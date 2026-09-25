package com.xbla.rag.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallException;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 意图分类器的单元测试（阶段 5.2）。
 *
 * <p><b>不花一分钱</b>：{@code ChatModelRouter} 被换成了桩，
 * 所以跑测试不需要 API Key，也不会碰真实供应商
 * （和阶段 4 用 {@code @MockitoBean} 换掉 EmbeddingClient 是同一条纪律）。
 *
 * <p>本类守的是两件事：
 * <ol>
 *   <li><b>推理模型把 max_tokens 吃光</b>这个坑能不能被抓住 ——
 *       HTTP 200、finish_reason=length、正文是空串。
 *       抓不住的症状是「分类永远失败」而日志里没有任何异常</li>
 *   <li>模型返回的包装（引号、反引号、代码围栏、尾标点）能不能被正确清洗，
 *       以及<b>清洗该在哪里停手</b></li>
 * </ol>
 */
@DisplayName("LlmIntentClassifier · 意图分类")
class LlmIntentClassifierTest {

    @TempDir
    Path tempDir;

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("qwen3-5-9b", "siliconflow", "Qwen/Qwen3.5-9B", null);

    // ------------------------------------------------------------
    // 夹具：一棵 6 个分类目标的树 + 覆盖全部目标的少样本
    // ------------------------------------------------------------

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
                .append("          - 树示例").append(i).append('\n');
        }
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
                          - 树的工具示例
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

    private static final String FEWSHOT_YAML = """
            samples:
              - intent: LEAF_1
                questions: [样本一]
              - intent: LEAF_2
                questions: [样本二]
              - intent: LEAF_3
                questions: [样本三]
              - intent: LEAF_4
                questions: [样本四]
              - intent: TOP_5
                questions: [样本五]
              - intent: OUT_OF_SCOPE
                questions: [你好]
            """;

    private record Fixture(LlmIntentClassifier classifier, ChatModelRouter router) {
    }

    private Fixture fixture() throws IOException {
        Path treeFile = tempDir.resolve("tree.yml");
        Files.writeString(treeFile, treeYaml(), StandardCharsets.UTF_8);
        Path sampleFile = tempDir.resolve("fewshot.yml");
        Files.writeString(sampleFile, FEWSHOT_YAML, StandardCharsets.UTF_8);

        AgentProperties properties = new AgentProperties();
        properties.getIntent().setFewShotPath(sampleFile.toString());

        IntentTree tree = new IntentTree(treeFile);
        IntentPromptBuilder promptBuilder =
                new IntentPromptBuilder(tree, new IntentFewShot(properties, tree), properties);
        ChatModelRouter router = mock(ChatModelRouter.class);

        return new Fixture(
                new LlmIntentClassifier(router, tree, promptBuilder, properties,
                        new IntentReplyParser(new ObjectMapper())),
                router);
    }

    private static ChatResponse reply(String content, String finishReason) {
        return ChatResponse.text(content, finishReason, null, DESCRIPTOR, 15);
    }

    // ============================================================
    // 一、正常分类
    // ============================================================

    @Nested
    @DisplayName("一、正常分类")
    class HappyPath {

        @Test
        @DisplayName("模型回一个合法 code → CLASSIFIED")
        void classifiesValidCode() throws IOException {
            Fixture f = fixture();
            when(f.router().chat(any(), any())).thenReturn(reply("LEAF_2", "stop"));

            IntentClassification result = f.classifier().classify("随便问一句");

            assertThat(result.outcome()).isEqualTo(IntentClassification.Outcome.CLASSIFIED);
            assertThat(result.code()).isEqualTo("LEAF_2");
            assertThat(result.isClassified()).isTrue();
            assertThat(result.descriptor()).isEqualTo(DESCRIPTOR);
            assertThat(result.error()).isNull();
        }

        @Test
        @DisplayName("工具类目标用的是顶层 code")
        void classifiesToolTopLevel() throws IOException {
            Fixture f = fixture();
            when(f.router().chat(any(), any())).thenReturn(reply("TOP_5", "stop"));

            assertThat(f.classifier().classify("我的订单到哪了").code()).isEqualTo("TOP_5");
        }

        @Test
        @DisplayName("把【问题原文】作为 user 消息发出去，system 是分类 prompt")
        void sendsQuestionAsUserMessage() throws IOException {
            Fixture f = fixture();
            when(f.router().chat(any(), any())).thenReturn(reply("LEAF_1", "stop"));

            f.classifier().classify("退货要几天");

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(f.router()).chat(captor.capture(), any(ModelCallTrace.class));
            ChatRequest sent = captor.getValue();

            assertThat(sent.userQuestion()).isEqualTo("退货要几天");
            // ★ 这里只断言「用的确实是那份分类 prompt」，不断言它的格式要求 ——
            //   格式是 9.2 的 A/B 变量（IntentPromptBuilderTest 专门管那一件事），
            //   两边都断言会让「改格式」这个动作连红两个测试。
            assertThat(sent.systemPrompt()).contains("LEAF_1").contains("## 候选意图");
            assertThat(sent.history()).isEmpty();
        }

        @Test
        @DisplayName("★ 不覆盖 max_tokens（默认 null）—— 给小的后果见类注释")
        void doesNotCapMaxTokensByDefault() throws IOException {
            Fixture f = fixture();
            when(f.router().chat(any(), any())).thenReturn(reply("LEAF_1", "stop"));

            f.classifier().classify("随便问一句");

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(f.router()).chat(captor.capture(), any(ModelCallTrace.class));
            assertThat(captor.getValue().maxTokens())
                    .as("默认必须留空 —— P0 是推理模型，max_tokens 给小了会把额度吃光，"
                            + "content 返回空串，表现为「分类永远失败」")
                    .isNull();
            assertThat(captor.getValue().temperature()).isEqualTo(0.0);
        }
    }

    // ============================================================
    // 二、★ 推理模型的坑
    // ============================================================

    @Nested
    @DisplayName("二、★ 空正文（推理把额度吃光）")
    class EmptyContent {

        @Test
        @DisplayName("HTTP 200 + finish=length + 空正文 → CALL_FAILED，不是 CLASSIFIED")
        void emptyContentIsFailure() throws IOException {
            Fixture f = fixture();
            // 这正是 CLAUDE.md 记着的那个坑：状态码 200、没有异常、
            // 日志里也看不出原因，只是「AI 不说话」
            when(f.router().chat(any(), any())).thenReturn(reply("", "length"));

            IntentClassification result = f.classifier().classify("退货要几天");

            assertThat(result.outcome())
                    .as("空正文必须当失败处理 —— 当成成功往下走的话，"
                            + "分类会永远失败且没有任何异常")
                    .isEqualTo(IntentClassification.Outcome.CALL_FAILED);
            assertThat(result.code()).isNull();
            assertThat(result.error()).contains("空正文").contains("length");
        }

        @Test
        @DisplayName("对照：只把正文改成非空，结果就从失败变成成功")
        void nonEmptyContentSucceeds() throws IOException {
            Fixture f = fixture();
            when(f.router().chat(any(), any())).thenReturn(reply("LEAF_1", "stop"));

            // 与上一条只差 content 的内容 —— 证明「空正文判定」不是恒真的
            assertThat(f.classifier().classify("退货要几天").outcome())
                    .isEqualTo(IntentClassification.Outcome.CLASSIFIED);
        }

        @Test
        @DisplayName("纯空白也算空正文")
        void blankContentIsFailure() throws IOException {
            Fixture f = fixture();
            when(f.router().chat(any(), any())).thenReturn(reply("   \n  ", "length"));

            assertThat(f.classifier().classify("退货要几天").outcome())
                    .isEqualTo(IntentClassification.Outcome.CALL_FAILED);
        }
    }

    // ============================================================
    // 三、模型返回非法 code
    // ============================================================

    @Nested
    @DisplayName("三、返回非法 code")
    class UnknownCode {

        @Test
        @DisplayName("编了个树里没有的 code → UNKNOWN_CODE，并把原话带出来")
        void unknownCodeIsReported() throws IOException {
            Fixture f = fixture();
            when(f.router().chat(any(), any())).thenReturn(reply("RETURN_POLICY", "stop"));

            IntentClassification result = f.classifier().classify("退货要几天");

            assertThat(result.outcome()).isEqualTo(IntentClassification.Outcome.UNKNOWN_CODE);
            assertThat(result.code()).isNull();
            assertThat(result.rawReply())
                    .as("必须保留模型原话 —— 排查「它到底答了什么」时只有它有用")
                    .isEqualTo("RETURN_POLICY");
        }

        @Test
        @DisplayName("★ 不在回复里「搜索像 code 的单词」—— 那是把胡说当成功")
        void doesNotSalvageCodeFromRambling() throws IOException {
            Fixture f = fixture();
            // 模型说了一堆话，中间碰巧提到 LEAF_1。
            // 宽松解析会把它当成成功，而它其实是一次跑偏的回复
            when(f.router().chat(any(), any()))
                    .thenReturn(reply("我不确定这是 LEAF_1 还是别的，请再说明一下", "stop"));

            IntentClassification result = f.classifier().classify("退货要几天");

            assertThat(result.outcome())
                    .as("模型的回复里【确实出现了】合法 code，但它是散文不是答案。"
                            + "只有【无害清洗后精确匹配】才算成功")
                    .isEqualTo(IntentClassification.Outcome.UNKNOWN_CODE);
        }
    }

    // ============================================================
    // 四、调用失败
    // ============================================================

    @Nested
    @DisplayName("四、调用失败")
    class CallFailed {

        @Test
        @DisplayName("抛 ModelCallException → CALL_FAILED，且【不抛给调用方】")
        void callFailureIsSwallowed() throws IOException {
            Fixture f = fixture();
            when(f.router().chat(any(), any()))
                    .thenThrow(ModelCallException.emptyContent("deepseek", "deepseek-flash", "全链路失败"));

            IntentClassification result = f.classifier().classify("退货要几天");

            assertThat(result.outcome()).isEqualTo(IntentClassification.Outcome.CALL_FAILED);
            assertThat(result.code()).isNull();
            assertThat(result.error()).isNotBlank();
        }

        @Test
        @DisplayName("空问题直接抛 IllegalArgumentException（这是调用方的 bug，不该被吞）")
        void blankQuestionThrows() throws IOException {
            Fixture f = fixture();

            assertThatThrownBy(() -> f.classifier().classify("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能为空");
        }
    }

    // ============================================================
    // 五、★ 返回值的清洗（normalize）
    // ============================================================

    @Nested
    @DisplayName("五、★ 返回值清洗")
    class Normalize {

        @Test
        @DisplayName("只做无害清洗：去围栏 / 取首行 / 去两端引号与尾标点 / 转大写")
        void stripsHarmlessWrappers() {
            // 每一条都只差一层包装，语义完全没变
            assertThat(IntentReplyParser.normalize("LEAF_1")).isEqualTo("LEAF_1");
            assertThat(IntentReplyParser.normalize("  LEAF_1  ")).isEqualTo("LEAF_1");
            assertThat(IntentReplyParser.normalize("`LEAF_1`")).isEqualTo("LEAF_1");
            assertThat(IntentReplyParser.normalize("\"LEAF_1\"")).isEqualTo("LEAF_1");
            assertThat(IntentReplyParser.normalize("```\nLEAF_1\n```")).isEqualTo("LEAF_1");
            assertThat(IntentReplyParser.normalize("LEAF_1。")).isEqualTo("LEAF_1");
            assertThat(IntentReplyParser.normalize("leaf_1")).isEqualTo("LEAF_1");
            // 首行是答案，后面是解释 —— 取首行，丢弃其余
            assertThat(IntentReplyParser.normalize("LEAF_1\n因为用户在问规格"))
                    .isEqualTo("LEAF_1");
        }

        @Test
        @DisplayName("★ 清洗在「改变语义」之前停手 —— 中间的文字不动")
        void doesNotTouchInnerText() {
            // 中间带标点/空格说明这多半不是我们要的格式，应当原样报出去，
            // 由 Java 侧判定 UNKNOWN_CODE，而不是猜模型想说什么
            assertThat(IntentReplyParser.normalize("分类结果：LEAF_1"))
                    .isEqualTo("分类结果：LEAF_1");
            assertThat(IntentReplyParser.normalize("LEAF_1 或 LEAF_2"))
                    .isEqualTo("LEAF_1 或 LEAF_2");
        }

        @Test
        @DisplayName("null 返回 null（不抛）")
        void nullStaysNull() {
            assertThat(IntentReplyParser.normalize(null)).isNull();
        }

        @Test
        @DisplayName("对照：清洗后的结果确实能被树查到，清洗前的不能")
        void cleaningActuallyEnablesLookup() throws IOException {
            Fixture f = fixture();
            IntentTree tree = new IntentTree(tempDir.resolve("tree.yml"));

            // 反证「清洗不是多此一举」：原始串查不到，清洗后能查到
            assertThat(tree.get().findTarget("```LEAF_1```")).isEmpty();
            assertThat(tree.get().findTarget(IntentReplyParser.normalize("```LEAF_1```")))
                    .isPresent();
        }
    }
}
