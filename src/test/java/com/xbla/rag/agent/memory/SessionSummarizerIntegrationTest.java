package com.xbla.rag.agent.memory;

import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.config.ChatProperties;
import com.xbla.rag.entity.ChatMessage;
import com.xbla.rag.entity.ChatSession;
import com.xbla.rag.entity.ChatSummary;
import com.xbla.rag.service.ChatMessageService;
import com.xbla.rag.service.ChatSessionService;
import com.xbla.rag.service.ChatSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话摘要压缩的集成测试（阶段 5.6）—— 真连 PostgreSQL，模型用桩。
 *
 * <h3>★ 为什么模型必须打桩</h3>
 *
 * <p>{@code ChatModelRouter} 被换成一个确定性的桩，所以 {@code ./mvnw test}
 * 仍然只需要 docker postgres、<b>不花一分钱</b>。
 * 同 {@code ChatHistoryIntegrationTest} 的做法。
 *
 * <h3>★ 为什么用同步的 {@code summarize()} 而不是 {@code maybeSummarizeAsync()}</h3>
 *
 * <p>异步入口没办法断言「跑完了没有」。{@code summarize()} 是包内可见的同步版本，
 * 逻辑完全一样 —— 线程池只是外面薄薄的一层。
 *
 * <p>⚠️ 而且 {@code @Transactional} 只在同步路径下有效：
 * 提交到线程池的任务拿的是另一条连接，看不到未提交的数据。
 * 这条注意事项见 {@code ConversationMemoryIntegrationTest} 的类注释 ——
 * 判断「能不能用 @Transactional 测」的依据是<b>被测代码是否跨线程</b>。
 *
 * <p><b>运行前提</b>：docker compose 的 postgres 必须在跑。
 */
@SpringBootTest
@Transactional
@DisplayName("SessionSummarizer —— 会话摘要压缩")
class SessionSummarizerIntegrationTest {

    private static final int ROLE_USER = 1;
    private static final int ROLE_ASSISTANT = 2;

    private static final String FAKE_SUMMARY = "用户预算两千左右，用途是和孙子视频通话";

    @Autowired
    private SessionSummarizer summarizer;

    @Autowired
    private ConversationMemory memory;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private ChatSummaryService chatSummaryService;

    @Autowired
    private ChatProperties chatProperties;

    @MockitoBean
    private ChatModelRouter router;

    private Long sessionId;

    @BeforeEach
    void setUp() {
        // 每个用例都给模型一个正常的返回值。要测异常的用例自己覆盖它
        when(router.chat(any(ChatRequest.class), any(ModelCallTrace.class)))
                .thenReturn(response(FAKE_SUMMARY));

        ChatSession session = new ChatSession();
        session.setSessionNo("S-SUM-" + System.nanoTime());
        session.setTitle("摘要压缩测试");
        session.setMessageCount(0);
        session.setStatus(1);
        session.setLastActiveAt(OffsetDateTime.now());
        chatSessionService.save(session);
        sessionId = session.getId();
    }

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    private static ChatResponse response(String content) {
        // finishReason 传 "stop"、usage 传 null —— 和 ChatClarificationIntegrationTest 一致。
        // 本类断言的是「写没写库、写成什么」，用量数据的正确性由模型接入层自己测
        return ChatResponse.text(content, "stop", null, DESCRIPTOR, 10);
    }

    private void append(int role, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        chatMessageService.save(message);
    }

    private void appendTurns(int count, int from) {
        for (int i = from; i < from + count; i++) {
            append(ROLE_USER, "问题" + i);
            append(ROLE_ASSISTANT, "回答" + i);
        }
    }

    private ChatSummary currentSummary() {
        return chatSummaryService.lambdaQuery()
                .eq(ChatSummary::getSessionId, sessionId)
                .eq(ChatSummary::getSummaryLevel, 1)
                .orderByDesc(ChatSummary::getEndMessageId)
                .last("LIMIT 1")
                .one();
    }

    private List<Long> messageIds() {
        return chatMessageService.lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getId)
                .list()
                .stream().map(ChatMessage::getId).toList();
    }

    /**
     * 抓<b>最后一次</b>发给模型的摘要请求。
     *
     * <p>⚠️ 不能写 {@code times(1)}：Mockito 的调用次数是<b>累计</b>的，
     * 而本类里好几个用例在同一个测试方法内压缩了两次（第一次 + 增量那次）——
     * 第二次抓取会看到 2 次调用而失败。
     * 取「最后一次」也正是需要的那一个。同 {@code ChatHistoryIntegrationTest}。
     */
    private ChatRequest captureSummaryRequest() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, atLeastOnce()).chat(captor.capture(), any(ModelCallTrace.class));
        return captor.getValue();
    }

    // ============================================================
    // 一、什么时候【不】压
    // ============================================================

    @Nested
    @DisplayName("一、什么时候不压")
    class NoOp {

        @Test
        @DisplayName("★ 窗口还没满 → 一次模型都不调")
        void windowNotFull() {
            appendTurns(5, 1);          // 5 轮 = 10 条，窗口是 20 条

            summarizer.summarize(sessionId);

            assertThat(currentSummary()).isNull();
            verify(router, never()).chat(any(ChatRequest.class), any(ModelCallTrace.class));
        }

        @Test
        @DisplayName("★ 正好 10 轮（窗口装满）→ 仍然不压：没有任何东西掉出来")
        void exactlyWindowSize() {
            appendTurns(10, 1);         // 正好 20 条

            summarizer.summarize(sessionId);

            assertThat(currentSummary())
                    .as("★ 边界不能多压一轮 —— 多压意味着把【还在窗口里】的消息"
                            + "也写进了摘要，于是它被模型看到两遍，而且摘要游标"
                            + "从此和窗口重叠，接缝再也对不上")
                    .isNull();
            verify(router, never()).chat(any(ChatRequest.class), any(ModelCallTrace.class));
        }

        @Test
        @DisplayName("★ 溢出不满 trigger-messages 条 → 不压（攒着）")
        void belowTriggerThreshold() {
            appendTurns(10, 1);
            append(ROLE_USER, "第 11 轮的提问");     // 溢出 1 条，阈值是 2

            summarizer.summarize(sessionId);

            assertThat(currentSummary()).isNull();
            verify(router, never()).chat(any(ChatRequest.class), any(ModelCallTrace.class));
        }

        @Test
        @DisplayName("★ 摘要已经覆盖到位 → 不重复生成（游标是幂等的）")
        void alreadyCovered() {
            appendTurns(11, 1);
            List<Long> ids = messageIds();
            // 溢出的是 ids[0]（第 1 轮的两条），窗口从 ids[2] 开始
            saveSummaryRow(ids.get(0), ids.get(1), "已覆盖第 1 轮");

            summarizer.summarize(sessionId);

            assertThat(currentSummary().getSummary()).isEqualTo("已覆盖第 1 轮");
            verify(router, never()).chat(any(ChatRequest.class), any(ModelCallTrace.class));
        }

        @Test
        @DisplayName("开关关掉 → 一次模型都不调")
        void disabled() {
            chatProperties.getSummary().setEnabled(false);
            try {
                appendTurns(12, 1);

                // 走异步入口才是真实调用点 —— 开关的判断在那边
                summarizer.maybeSummarizeAsync(sessionId);
                summarizer.summarize(sessionId);
            } finally {
                chatProperties.getSummary().setEnabled(true);
            }

            verify(router, never()).chat(any(ChatRequest.class), any(ModelCallTrace.class));
        }
    }

    // ============================================================
    // 二、★ 接缝：游标必须正好停在窗口起点的前一条
    // ============================================================

    @Nested
    @DisplayName("二、★ 接缝：摘要覆盖到哪，窗口从哪开始")
    class Seam {

        @Test
        @DisplayName("★★ 11 轮 → 游标 = 窗口起点 - 1，严丝合缝")
        void cursorStopsRightBeforeTheWindow() {
            appendTurns(11, 1);                     // 22 条
            List<Long> ids = messageIds();

            summarizer.summarize(sessionId);

            ChatSummary summary = currentSummary();
            assertThat(summary).isNotNull();
            assertThat(summary.getStartMessageId()).isEqualTo(ids.get(0));
            assertThat(summary.getEndMessageId())
                    .as("★ 溢出的是第 1 轮（ids[0], ids[1]），而窗口是最近 20 条"
                            + "（ids[2] .. ids[21]）。所以游标必须正好停在 ids[1] —— "
                            + "差一条就会有一条消息【既不在摘要里也不在窗口里】，"
                            + "而且永远不会被补上")
                    .isEqualTo(ids.get(1));

            // 最强的那条断言：读出来的记忆必须自洽
            MemoryContext ctx = memory.load(sessionId);
            assertThat(ctx.sessionSummary()).isEqualTo(FAKE_SUMMARY);
            assertThat(ctx.history()).hasSize(20);
            assertThat(ctx.history().get(0).content())
                    .as("★ 窗口的第一条必须紧跟在摘要覆盖的最后一条【之后】")
                    .isEqualTo("问题2");
        }

        @Test
        @DisplayName("★ 增量：第二轮压缩把游标继续往前推，起点不动")
        void secondRunAdvancesTheCursorAndKeepsTheStart() {
            appendTurns(11, 1);
            summarizer.summarize(sessionId);
            ChatSummary first = currentSummary();

            // 再聊 3 轮 —— 又掉出 3 轮
            appendTurns(3, 12);
            summarizer.summarize(sessionId);

            ChatSummary second = currentSummary();
            assertThat(second.getId())
                    .as("★ 仍然是【同一行】—— 单行滚动，不是追加。"
                            + "多行的话读取端要拼几行、拼出来多长都不可控")
                    .isEqualTo(first.getId());
            assertThat(second.getStartMessageId())
                    .as("★ 起点不动：摘要是对 [起点, 游标] 这一整段的累积压缩")
                    .isEqualTo(first.getStartMessageId());
            assertThat(second.getEndMessageId())
                    .as("★ 游标往前推了。而且仍然是「窗口起点 - 1」——"
                            + "每次压缩都重新对齐到窗口，不是按固定条数往前挪")
                    .isGreaterThan(first.getEndMessageId());
        }

        @Test
        @DisplayName("★★ 增量喂给模型的是【旧摘要 + 新增对话】，不是重压全部原文")
        void incrementalPromptCarriesTheOldSummary() {
            appendTurns(11, 1);
            summarizer.summarize(sessionId);
            appendTurns(3, 12);
            summarizer.summarize(sessionId);

            ChatRequest request = captureSummaryRequest();

            assertThat(request.userQuestion())
                    .as("★ 第二次压缩的输入必须包含【已有摘要】。"
                            + "不含的话就是把新对话单独压成一段，然后把旧摘要丢掉 —— "
                            + "摘要会退化成「只有最近几轮」")
                    .contains("【已有摘要】")
                    .contains(FAKE_SUMMARY)
                    .contains("【新增对话】");
            assertThat(request.userQuestion())
                    .as("★ 而且必须只带【新掉出来】的那一段。"
                            + "第一次已压到第 1 轮（游标 = ids[1]），"
                            + "再聊 3 轮后窗口退到「问题5」，"
                            + "所以新增的是第 2-4 轮 —— 从这里开始，不是从第 1 轮重来")
                    .contains("问题2")
                    .contains("回答4")
                    .doesNotContain("问题1");
        }

        @Test
        @DisplayName("★ 第一次压缩时【没有】「已有摘要」那一块 —— 不留空标题")
        void firstRunHasNoStaleSummaryBlock() {
            appendTurns(11, 1);

            summarizer.summarize(sessionId);

            assertThat(captureSummaryRequest().userQuestion())
                    .as("★ 写「（无）」会把模型的注意力引向一个空的位置 —— "
                            + "同 RagPromptBuilder 对空资料的处理")
                    .doesNotContain("【已有摘要】")
                    .contains("【新增对话】");
        }
    }

    // ============================================================
    // 三、★ 失败语义：永不抛，永不覆盖好数据
    // ============================================================

    @Nested
    @DisplayName("三、★ 失败语义")
    class Failure {

        @Test
        @DisplayName("★ 模型调用失败 → 不抛异常，游标不动，旧摘要保留")
        void modelFailureKeepsOldSummary() {
            appendTurns(11, 1);
            summarizer.summarize(sessionId);
            ChatSummary good = currentSummary();

            appendTurns(3, 12);
            when(router.chat(any(ChatRequest.class), any(ModelCallTrace.class)))
                    .thenThrow(new RuntimeException("上游 429"));

            assertThatCode(() -> summarizer.summarize(sessionId))
                    .as("★ 摘要失败该退化成「记忆里少一块」，不该让一次成功的问答变成 500")
                    .doesNotThrowAnyException();

            assertThat(currentSummary().getEndMessageId())
                    .as("★ 游标【不动】。这正是「自愈」的来源：下次触发会把这一段"
                            + "连同新掉出来的一起压进去")
                    .isEqualTo(good.getEndMessageId());
            assertThat(currentSummary().getSummary()).isEqualTo(FAKE_SUMMARY);
        }

        @Test
        @DisplayName("★★ 模型返回空正文 → 不覆盖旧摘要（推理模型吃光额度的探测器）")
        void emptyContentDoesNotClobber() {
            appendTurns(11, 1);
            summarizer.summarize(sessionId);
            ChatSummary good = currentSummary();

            appendTurns(3, 12);
            when(router.chat(any(ChatRequest.class), any(ModelCallTrace.class)))
                    .thenReturn(response(""));

            summarizer.summarize(sessionId);

            assertThat(currentSummary().getSummary())
                    .as("★ 空串是推理模型把 max_tokens 吃光后的典型症状（HTTP 200、"
                            + "日志无异常）。它对问答是「少一块上下文」，"
                            + "对摘要是【把一份本来好用的旧摘要抹掉】—— 严重得多")
                    .isEqualTo(FAKE_SUMMARY);
            assertThat(currentSummary().getEndMessageId()).isEqualTo(good.getEndMessageId());
        }

        @Test
        @DisplayName("★ 摘要超长 → 截断 + 不抛（失控的增长必须有个界）")
        void overlongSummaryIsTruncated() {
            appendTurns(11, 1);
            when(router.chat(any(ChatRequest.class), any(ModelCallTrace.class)))
                    .thenReturn(response("长".repeat(5000)));

            int maxChars = chatProperties.getSummary().getMaxChars();
            summarizer.summarize(sessionId);

            assertThat(currentSummary().getSummary())
                    .as("★ 硬上限是目标上限的两倍。没有界的话，一段无限膨胀的摘要"
                            + "每一轮都要进 prompt —— 那是一笔慢性的 token 开销，"
                            + "而且从回答质量上完全看不出来")
                    .hasSizeLessThanOrEqualTo(maxChars * 2 + 20)
                    .endsWith("（摘要过长已截断）");
        }

        @Test
        @DisplayName("★ 返回全是空白 → 当成失败，不写库")
        void blankSummaryIsTreatedAsFailure() {
            appendTurns(11, 1);
            when(router.chat(any(ChatRequest.class), any(ModelCallTrace.class)))
                    .thenReturn(response("   \n  "));

            summarizer.summarize(sessionId);

            assertThat(currentSummary()).isNull();
        }
    }

    // ============================================================
    // 四、★ 发给模型的形状
    // ============================================================

    @Nested
    @DisplayName("四、发给模型的形状")
    class RequestShape {

        @Test
        @DisplayName("★★ 待压缩的对话走 userQuestion，不是 history —— 摘要不是一次对话")
        void transcriptGoesInUserQuestionNotHistory() {
            appendTurns(11, 1);

            summarizer.summarize(sessionId);
            ChatRequest request = captureSummaryRequest();

            assertThat(request.history())
                    .as("★ 把对话原样当成多轮 messages 发过去，模型会以为"
                            + "【自己正在这场对话里】—— 它可能不回摘要，"
                            + "而是接着回答最后那个问题。拿回来的是一段通顺的中文，"
                            + "只是【根本不是摘要】。这种失败模式极难发现")
                    .isEmpty();
            assertThat(request.userQuestion())
                    .contains("用户：")
                    .contains("助手：");
            assertThat(request.systemPrompt())
                    .as("★ 规则在 system，素材在 user —— 角色分工要明确")
                    .isNotBlank();
        }

        @Test
        @DisplayName("★ 消息按【正序】渲染 —— 倒叙会让摘要里的因果反过来")
        void transcriptIsChronological() {
            // 15 轮：窗口退到「问题5」，所以一次要压的是第 1-4 轮（8 条）
            appendTurns(15, 1);

            summarizer.summarize(sessionId);

            String transcript = captureSummaryRequest().userQuestion();
            assertThat(transcript.indexOf("问题1")).isNotNegative();
            assertThat(transcript.indexOf("问题4")).isNotNegative();
            assertThat(transcript.indexOf("问题1"))
                    .as("★ 倒序渲染的话，模型看到的是「先聊了预算、再问的用途」—— "
                            + "摘要里的因果关系会整个反过来，而且读起来毫无破绽")
                    .isLessThan(transcript.indexOf("问题2"));
            assertThat(transcript.indexOf("问题2")).isLessThan(transcript.indexOf("问题3"));
            assertThat(transcript.indexOf("问题3")).isLessThan(transcript.indexOf("问题4"));
        }

        @Test
        @DisplayName("★ temperature 用的是摘要自己的配置，不是问答的")
        void usesSummaryTemperature() {
            appendTurns(11, 1);

            summarizer.summarize(sessionId);

            assertThat(captureSummaryRequest().temperature())
                    .isEqualTo(chatProperties.getSummary().getTemperature());
        }
    }

    /** 直接写一行摘要（不经过压缩流程）—— 用来构造「已覆盖」之类的起始状态 */
    private void saveSummaryRow(long startMessageId, long endMessageId, String text) {
        ChatSummary row = new ChatSummary();
        row.setSessionId(sessionId);
        row.setSummaryLevel(1);
        row.setStartMessageId(startMessageId);
        row.setEndMessageId(endMessageId);
        row.setSummary(text);
        chatSummaryService.save(row);
    }
}
