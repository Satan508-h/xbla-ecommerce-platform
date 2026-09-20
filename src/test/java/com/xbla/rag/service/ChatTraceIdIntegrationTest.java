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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@code ChatService} 的 <b>traceId 由调用方指定</b>这个重载（阶段 6 新增）。
 *
 * <h2>★ 它守的是「排队和问答对得上号」这件事</h2>
 *
 * <p>阶段 6 让请求在到达 {@code ChatService} 之前先排队，排队期间就要写
 * {@code qa_log.queue_ms}。如果排队层生成一个 id、{@code ChatService} 又生成一个，
 * 那么用户在日志里看到的会是<b>两条互不相干的记录</b>：
 *
 * <pre>
 *   「排队等了 90 秒」   ← 记在 id-A 上
 *   「回答耗时 3 秒」    ← 记在 id-B 上
 * </pre>
 *
 * <p>而这两个 id <b>长得一模一样</b>（都是 32 位十六进制），
 * 所以没人会怀疑它们不是一回事 —— <b>它会一直看起来是对的，
 * 直到某天真的需要用它们串起来。</b>
 *
 * <p>所以这里断言的是：<b>传进去的 id，就是落进 {@code qa_log.trace_id} 的那个。</b>
 *
 * <h2>★ 两条路都要测</h2>
 *
 * <p>{@code ask} 和 {@code askStream} 在本项目是<b>平行代码，不是共用实现</b>
 * （见 {@code ChatServiceImpl} 里反复出现的那段注释）。只测一条等于没测 ——
 * 本项目已经因为「两条路改了一条」踩过 ADR-047。
 *
 * <h2>不花一分钱</h2>
 * <p>{@code ChatModelRouter} / {@code LlmIntentClassifier} / {@code RetrievalPipeline}
 * 全是确定性桩。
 */
@SpringBootTest
@Transactional
@DisplayName("ChatService · traceId 由调用方指定")
class ChatTraceIdIntegrationTest {

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    /** 排队层会传进来的那个 id，格式和真实的一样：32 位十六进制 */
    private static final String GIVEN = "0123456789abcdef0123456789abcdef";

    /** 给每个用例造一个唯一的问题文本，见 {@link #uniqueQuestion} */
    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

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

    /**
     * 每个用例一个<b>唯一的问题文本</b>，用来精确定位自己那一行。
     *
     * <p>★★ 这个「唯一」不是洁癖，是踩过才写的：
     * 第一版直接用 {@code "退货要几天"} 去查，得到 <b>13 行</b> ——
     * 那个问题在库里本来就有（别的测试留下的、以及之前手工跑的数据），
     * 而 {@code @Transactional} <b>只回滚这次插入的行</b>，
     * 回滚不了别人早就写进去的。
     *
     * <p>症状很有迷惑性：断言报「expected: 1 but was: 13」，
     * 看起来像是<b>我的实现多写了几行</b>，而真相是<b>我的查询条件太宽</b>。
     */
    private static String uniqueQuestion(String base) {
        return base + "-" + SEQ.incrementAndGet();
    }

    /** 按 traceId 取那一行 qa_log */
    private QaLog logOf(String traceId) {
        QaLog row = qaLogMapper.selectOne(
                new LambdaQueryWrapper<QaLog>().eq(QaLog::getTraceId, traceId));
        assertNotNull(row, "应该有一行 trace_id = " + traceId + " 的 qa_log");
        return row;
    }

    /** 按问题文本取那一行（用于「id 是自动生成的」那个用例 —— 事先不知道 id） */
    private QaLog logOfQuestion(String question) {
        QaLog row = qaLogMapper.selectOne(
                new LambdaQueryWrapper<QaLog>().eq(QaLog::getQuestion, question));
        assertNotNull(row, "应该有一行 question = " + question + " 的 qa_log");
        return row;
    }

    /** 收集流式事件的桩 —— 目前只需要 onStart 里的 traceId */
    private static final class CapturingSink implements ChatService.ChatStreamSink {
        final AtomicReference<String> startedTraceId = new AtomicReference<>();
        final StringBuilder body = new StringBuilder();

        @Override
        public void onStart(String traceId, String sessionNo) {
            startedTraceId.set(traceId);
        }

        @Override
        public void onDelta(String delta) {
            body.append(delta);
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
        @DisplayName("★★ 传进去的 id，就是落进 qa_log.trace_id 的那个")
        void givenTraceIdLandsInQaLog() {
            chatService.ask(new ChatAskRequest(null, uniqueQuestion("退货要几天"), null), null, CallContext.fresh(GIVEN));

            assertEquals(GIVEN, logOf(GIVEN).getTraceId(),
                    "★ 这是整个阶段 6 可观测性的地基："
                            + "排队层写的 queue_ms 和这里的回答，靠这个 id 才能对上号");
        }

        /**
         * ★★ 反对照：证明上面那条不是因为「正好就生成了这个 id」。
         *
         * <p>两参版本自己生成 id。如果三参版本<b>忽略了传进来的值</b>
         * （比如内部还是调 {@code TraceId.newId()}），上面那条断言会失败 ——
         * 但没有这一条的话，当实现「永远用 GIVEN」而我们从没意识到时，
         * 我们可能以为链路通了其实没有。这里把「自己生成」和「用传进来的」
         * 并排跑一次，证明两条路<b>确实产出不同的 id</b>。
         */
        @Test
        @DisplayName("★★ 反对照：不传 id 时，落库的是一个新生成的、不等于 GIVEN")
        void generatedTraceIdIsDifferentFromGiven() {
            String question = uniqueQuestion("退货要几天");
            chatService.ask(new ChatAskRequest(null, question, null), null);

            QaLog row = logOfQuestion(question);
            assertNotEquals(GIVEN, row.getTraceId(),
                    "不传 id 时必须【新生成】一个 —— 如果它等于 GIVEN，"
                            + "说明两参版本也在用那个常量，上面那条断言就是恒真的");
            assertEquals(32, row.getTraceId().length(),
                    "生成出来的应该是 32 位十六进制（去掉连字符的 UUID）");
        }

        @Test
        @DisplayName("★ traceId 是空白时回落到自己生成 —— 观测数据缺失不该让问答失败")
        void blankTraceIdFallsBack() {
            String question = uniqueQuestion("退货要几天");

            ChatAskResponse response =
                    chatService.ask(new ChatAskRequest(null, question, null), null, CallContext.fresh("   "));

            assertNotNull(response, "空白 traceId 不该让整次问答失败");
            assertNotNull(response.traceId(), "响应里也该带着一个合法的 id");

            QaLog row = logOfQuestion(question);
            assertTrue(row.getTraceId() != null && row.getTraceId().length() == 32,
                    "空白 traceId 必须被换成新生成的，实际：" + row.getTraceId());
        }
    }

    // ============================================================
    // 二、流式
    // ============================================================

    @Nested
    @DisplayName("二、askStream(request, sink, CallContext)")
    class Streaming {

        @Test
        @DisplayName("★★ 传进去的 id，既是 onStart 推给前端的那个，也是落库的那个")
        void givenTraceIdReachesBothSinkAndQaLog() {
            CapturingSink sink = new CapturingSink();

            chatService.askStream(new ChatAskRequest(null, "退货要几天", null), sink, CallContext.fresh(GIVEN));

            assertEquals(GIVEN, sink.startedTraceId.get(),
                    "★ onStart 推给前端的 id 必须就是排队层那个 —— "
                            + "前端从 SSE 的第一个事件就拿到它，后面报错时才能对得上");
            assertEquals(GIVEN, logOf(GIVEN).getTraceId(),
                    "★ 而且落库的也必须是同一个。两个对不上的话，"
                            + "「前端说排队 90 秒」和「日志说生成 3 秒」就串不起来了");
        }

        @Test
        @DisplayName("★ 正文确实推出来了 —— 证明这条路上桩真的被走到了")
        void streamBodyActuallyProduced() {
            CapturingSink sink = new CapturingSink();

            chatService.askStream(new ChatAskRequest(null, "退货要几天", null), sink, CallContext.fresh(GIVEN));

            assertEquals("自签收之日起 7 天内。", sink.body.toString(),
                    "★ 这一条是上面两条的【前提】：如果 askStream 根本没跑起来，"
                            + "onStart 也可能没被调用（拿到 null）而断言碰巧不报错");
        }
    }
    // ============================================================
    // 三、★ 排队信息落库（V9 加的那两列）
    // ============================================================

    @Nested
    @DisplayName("三、★★ 排队两列真的写进了 qa_log")
    class QueueFields {

        @Test
        @DisplayName("★ 排过队的：queue_ms 和 queue_position 原样落库")
        void queuedContextLandsInQaLog() {
            String question = uniqueQuestion("退货要几天");

            chatService.ask(new ChatAskRequest(null, question, null), null,
                    new CallContext(GIVEN, 1234, 7));

            QaLog row = logOf(GIVEN);
            assertEquals(1234, row.getQueueMs(),
                    "★ V9 加了这两列，而它们只有在真的被写进去时才有意义 —— "
                            + "否则那两列永远 NULL，V9 就是个死迁移");
            assertEquals(7, row.getQueuePosition(),
                    "★ 而且必须是【刚入队时】的位置。记「拿到名额时」的话它恒为 0，"
                            + "一整列 0 看起来像数据，实际不携带任何信息");
        }

        /**
         * ★★ 反对照 —— 这条是让上面那条有意义的那个。
         *
         * <p>如果实现里把 null 兜成了 0（{@code ctx.queueMs() == null ? 0 : ...}），
         * 上面那条断言照样通过。<b>必须证明「没排队」在库里确实是 NULL</b>。
         */
        @Test
        @DisplayName("★★ 反对照：没排队时两列是 NULL，不是 0")
        void notQueuedStaysNull() {
            String question = uniqueQuestion("退货要几天");

            chatService.ask(new ChatAskRequest(null, question, null), null,
                    CallContext.fresh(GIVEN));

            QaLog row = logOf(GIVEN);
            assertNull(row.getQueueMs(),
                    "★ 没排队必须是 NULL。记 0 的话「平均等多久」会被稀释到接近 0 ——"
                            + "因为绝大多数请求都不排队，那个平均值会看起来像"
                            + "【排队功能根本没生效】");
            assertNull(row.getQueuePosition());
        }

        @Test
        @DisplayName("★ 流式路径也要落 —— 两条平行路径，漏一条编译不会报错")
        void streamAlsoRecordsQueueFields() {
            String question = uniqueQuestion("退货要几天");
            CapturingSink sink = new CapturingSink();

            chatService.askStream(new ChatAskRequest(null, question, null), sink,
                    new CallContext(GIVEN, 4321, 3));

            QaLog row = logOf(GIVEN);
            assertEquals(4321, row.getQueueMs(),
                    "★ ask 和 askStream 在本项目是【平行代码】——"
                            + "只给一条路加字段是本项目踩过的坑（ADR-047）");
            assertEquals(3, row.getQueuePosition());
        }

        @Test
        @DisplayName("★ 澄清反问那条路也带着排队信息（它同样经过了排队层）")
        void clarificationAlsoCarriesQueueFields() {
            // 澄清是【短路】的：不检索、不调生成模型。但它照样是先排队、
            // 拿到名额、才进 ChatService 的 —— 所以排队时间是真花了的。
            when(intentClassifier.classify(anyString())).thenReturn(new IntentClassification(
                    "NEEDS_CLARIFICATION", IntentClassification.Outcome.CLASSIFIED,
                    "NEEDS_CLARIFICATION", DESCRIPTOR, null, 10L, "你到底想问哪款？"));

            chatService.ask(new ChatAskRequest(null, uniqueQuestion("那个怎么样"), null), null,
                    new CallContext(GIVEN, 999, 2));

            QaLog row = logOf(GIVEN);
            assertEquals(QaLog.STATUS_CLARIFY, row.getStatus());
            assertEquals(999, row.getQueueMs(),
                    "★ 澄清路径的 baseLog 和成功路径是同一个 —— 这正是"
                            + "「把 ctx 串到 baseLog 一处」而不是「给每个路径单独加参数」的价值");
        }
    }

}
