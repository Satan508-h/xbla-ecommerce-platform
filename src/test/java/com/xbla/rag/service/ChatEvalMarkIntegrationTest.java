package com.xbla.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xbla.rag.agent.intent.IntentClassification;
import com.xbla.rag.agent.intent.LlmIntentClassifier;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.client.dto.StreamResult;
import com.xbla.rag.common.EvalMark;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.rag.RetrievalPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * <b>{@code CallContext.eval} 里的评测标记，必须落进 {@code qa_log} 的两列</b>（阶段 7）。
 *
 * <h2>★★★ 这个类守的是一个【编译得过、跑得通、却没有症状】的漏传</h2>
 *
 * <p>评测标记从 HTTP 头进来，一路靠<b>手工透传</b>：
 *
 * <pre>
 *   ChatController           读头 → EvalMark.of(...)
 *        ↓  Admission
 *   ChatAdmissionService     contextOf() / rateLimitedLog()
 *        ↓  CallContext
 *   ChatServiceImpl.baseLog()  → qa_log.eval_run_id / eval_question_no
 * </pre>
 *
 * <p>任何一跳漏掉，<b>没有任何东西会报错</b>：编译通过、请求成功、问答照常。
 * 唯一的后果是那一轮评测的几百行<b>伪装成真实用户流量</b> ——
 * 而它同时污染两个方向的统计：
 *
 * <pre>
 *   评测统计   少了几百行 → 「这题为什么没跑」永远查不出来
 *   真实使用   多了几百行 → 「真实用户平均等多久」被评测数据主导
 * </pre>
 *
 * <p>★ 这个类盯的是<b>最后那一跳</b>（{@code CallContext → qa_log}）。
 * 前面几跳各有自己的测试：{@code ChatControllerEvalMarkTest} 盯 HTTP 头，
 * {@code QueueHeartbeatDisabledTest} 盯「关掉排队时那一条最容易被漏掉的构造点」。
 *
 * <h2>★ 两条路都要测</h2>
 *
 * <p>{@code ask} 和 {@code askStream} 在本项目是<b>平行代码，不是共用实现</b>
 * （见 {@code ChatServiceImpl} 里反复出现的那段注释）。它们共用
 * {@code baseLog}，所以理论上只测一条就够 —— <b>但「理论上」正是这个类要检验的东西</b>：
 * 万一哪天有人在流式那条路上绕开 {@code baseLog} 手写了一个 QaLog，
 * 只有这一条会红。
 *
 * <h2>不花一分钱</h2>
 * <p>{@code ChatModelRouter} / {@code LlmIntentClassifier} / {@code RetrievalPipeline}
 * 全是确定性桩。
 */
@SpringBootTest
@Transactional
@DisplayName("ChatService · 评测标记落进 qa_log（阶段 7）")
class ChatEvalMarkIntegrationTest {

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    private static final EvalMark MARK = EvalMark.of("run-smoke-1", "X-001");

    /**
     * ★ 每个用例一个唯一的问题文本 —— 理由同 {@code ChatTraceIdIntegrationTest}：
     * {@code @Transactional} 只回滚<b>这次</b>插入的行，回滚不了库里早就有的
     * 那些「退货要几天」。
     */
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired
    private ChatService chatService;

    @Autowired
    private QaLogMapper qaLogMapper;

    @MockitoBean
    private ChatModelRouter router;

    @MockitoBean
    private LlmIntentClassifier intentClassifier;

    @MockitoBean
    private RetrievalPipeline retrievalPipeline;

    @BeforeEach
    void stubEverything() {
        when(intentClassifier.classify(anyString())).thenReturn(new IntentClassification(
                "RETURN_EXCHANGE", IntentClassification.Outcome.CLASSIFIED,
                "RETURN_EXCHANGE", DESCRIPTOR, null, 10L, null));
        when(retrievalPipeline.retrieve(anyString(), any(), any())).thenReturn(List.of());
        when(router.chat(any(), any(ModelCallTrace.class)))
                .thenReturn(ChatResponse.text("自签收之日起 7 天内。", "stop", null, DESCRIPTOR, 10));
        when(router.chatStream(any(), any(ModelCallTrace.class), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<String> onDelta = invocation.getArgument(2);
                    onDelta.accept("自签收之日起 7 天内。");
                    return new StreamResult(
                            new ChatUsage(10, 5, 15, null, null, null), "stop", 10, 100, 200);
                });
    }

    private static String uniqueQuestion(String base) {
        return base + "-" + SEQ.incrementAndGet();
    }

    /** 按【问题文本】取那一行 —— 这个类不关心 traceId，一行就够 */
    private QaLog logOfQuestion(String question) {
        QaLog row = qaLogMapper.selectOne(
                new LambdaQueryWrapper<QaLog>().eq(QaLog::getQuestion, question));
        assertNotNull(row, "应该有一行 question = " + question + " 的 qa_log");
        return row;
    }

    /** 收集流式事件的最小桩 */
    private static final class SilentSink implements ChatService.ChatStreamSink {
        @Override
        public void onStart(String traceId, String sessionNo) {
        }

        @Override
        public void onDelta(String delta) {
        }

        @Override
        public void onComplete(ChatAskResponse summary) {
        }

        @Override
        public void onError(String message, String traceId) {
        }
    }

    // ============================================================
    // 一、非流式
    // ============================================================

    @Nested
    @DisplayName("一、ask(request, userId, CallContext)")
    class NonStreaming {

        @Test
        @DisplayName("★★ 标记传进去了，qa_log 的两列就有值")
        void markLandsInQaLog() {
            String question = uniqueQuestion("退货要几天");
            chatService.ask(new ChatAskRequest(null, question, null), null,
                    CallContext.fresh("t-eval-1", MARK));

            QaLog row = logOfQuestion(question);
            assertEquals("run-smoke-1", row.getEvalRunId(),
                    "★ 没有这个标记，这一行就和真实用户的提问永远分不开 —— "
                            + "而「分不开」没有任何症状，只会让两边的统计都错");
            assertEquals("X-001", row.getEvalQuestionNo(),
                    "★ 题号也必须在：只有 run 的话报告端点归不了题，"
                            + "算不出这行的 gold 是什么");
        }

        /**
         * ★★ 反对照。
         *
         * <p>上面那条断言「两列有值」只有在<b>另一条路上它们确实为 NULL</b> 时才有意义。
         * 如果 {@code baseLog} 被写成「无论如何都填一个默认值」
         * （比如 {@code ""}、{@code "none"}、或从别处抄一个），
         * 上面那条<b>照样通过</b> —— 而那时
         * {@code WHERE eval_run_id IS NULL} 这个「只统计真实使用」的筛子
         * 就会<b>静默漏掉真实行</b>，正是它最不能被漏掉的时候。
         */
        @Test
        @DisplayName("★★ 反对照：不带标记的请求，两列必须是 NULL（不是空串）")
        void withoutMarkBothColumnsStayNull() {
            String question = uniqueQuestion("退货要几天");
            chatService.ask(new ChatAskRequest(null, question, null), null);

            QaLog row = logOfQuestion(question);
            assertNull(row.getEvalRunId(),
                    "★ 普通请求 → NULL。填 \"\" 或 \"none\" 会让「真实使用」的筛子失效，"
                            + "而那是一个看起来有值、实际不携带信息的值");
            assertNull(row.getEvalQuestionNo());
        }
    }

    // ============================================================
    // 二、流式（平行代码，必须单独证明）
    // ============================================================

    @Nested
    @DisplayName("二、askStream —— 平行代码，不能靠「共用 baseLog」推断")
    class Streaming {

        @Test
        @DisplayName("★★ 流式路径同样把标记落进 qa_log")
        void markLandsInQaLogOnStreamPath() {
            String question = uniqueQuestion("退货要几天");
            chatService.askStream(new ChatAskRequest(null, question, null), new SilentSink(), null,
                    CallContext.fresh("t-eval-2", MARK));

            QaLog row = logOfQuestion(question);
            assertEquals("run-smoke-1", row.getEvalRunId(),
                    "★ 两条路共用 baseLog，所以它【应该】自动是对的 —— "
                            + "而这条断言存在的意义就是不让「应该」停留在推理上");
            assertEquals("X-001", row.getEvalQuestionNo());
        }

        @Test
        @DisplayName("★★ 反对照：流式不带标记时也是 NULL")
        void withoutMarkStreamPathStaysNull() {
            String question = uniqueQuestion("退货要几天");
            chatService.askStream(new ChatAskRequest(null, question, null), new SilentSink());

            QaLog row = logOfQuestion(question);
            assertNull(row.getEvalRunId());
            assertNull(row.getEvalQuestionNo());
        }
    }

    // ============================================================
    // 三、半标记：能存下来，且【报告端点要能看见】
    // ============================================================

    @Nested
    @DisplayName("三、半标记（只有 run 没有题号）")
    class HalfMark {

        /**
         * ★ 这条不给「半标记是好事」背书，它固定的是<b>数据形状</b>：
         * 半标记能写进去、且和完整标记**在数据上分得开**。
         *
         * <p>报告端点的义务（阶段 7 的 T4）：把「有 run_id 没 question_no」
         * 的行**单独数出来报**。它们归不了题、又不在真实使用里 ——
         * 两头都不属于，所以必须显式可见，而不是被当成完整标记悄悄丢掉。
         */
        @Test
        @DisplayName("★ 只有 run 没有题号：能落库，题号是 NULL")
        void runWithoutQuestionNoIsStorable() {
            String question = uniqueQuestion("退货要几天");
            chatService.ask(new ChatAskRequest(null, question, null), null,
                    CallContext.fresh("t-eval-3", EvalMark.of("run-half", null)));

            QaLog row = logOfQuestion(question);
            assertEquals("run-half", row.getEvalRunId());
            assertNull(row.getEvalQuestionNo(),
                    "★ 题号是 NULL 而不是空串 —— 报告端点靠「有没有题号」"
                            + "把这批行单独数出来；空串会让它们混进完整标记里");
        }
    }
}
