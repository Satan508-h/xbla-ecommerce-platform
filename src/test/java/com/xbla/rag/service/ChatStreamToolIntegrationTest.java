package com.xbla.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.agent.intent.IntentClassification;
import com.xbla.rag.agent.intent.LlmIntentClassifier;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.client.dto.ToolCall;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;
import com.xbla.rag.entity.ChatSession;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.rag.RetrievalPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>阶段 9.1 的验收测试</b>：工具那条路在<b>流式</b>接口上也走通了。
 *
 * <h2>★★ 它要证明的那个 bug 长什么样</h2>
 *
 * <p>在阶段 9 之前，{@code ToolLoop} 只在 {@code ask()}（非流式）里被调用，
 * 而 {@code askStream()} 既没有 {@code userId} 也不挂 {@code tools}。
 * 于是「我的订单到哪了」在 SSE 上被当成一次<b>普通的 KB 问答</b>：
 *
 * <pre>
 *   检索跑了（全池召回，因为工具意图的 doc_types 是 []）→ 召回一堆「一般发货要几天」
 *   → 模型拿这些通用规则【编一个具体的订单状态】→ 用户看到一个错误答案
 *   → 而 qa_log 里 intent=ORDER_LOGISTICS、status=1、看起来完全正常
 * </pre>
 *
 * <p>★ 前端只用流式（{@code frontend/src/api.js} 的 {@code streamChat}），
 * 所以那条边界实际是「工具功能对用户完全不可见」——
 * 而它在非流式的测试里<b>全部是绿的</b>。这正是本测试存在的理由：
 * <b>平行路径上「只改了一条」编译能过、测试能绿、只有演示的时候才看得出来</b>
 * （ADR-047 记的就是这个）。
 *
 * <h2>真假对照</h2>
 *
 * <table border="1">
 *   <caption>这个测试里什么是真的、什么是桩</caption>
 *   <tr><th>组件</th><th>真假</th><th>说明</th></tr>
 *   <tr><td>{@code ChatModelRouter}</td><td><b>桩</b></td>
 *       <td>按剧本返回「先 tool_calls，后正文」。★ 不花钱</td></tr>
 *   <tr><td>{@code LlmIntentClassifier}</td><td><b>桩</b></td>
 *       <td>固定返回 {@code ORDER_LOGISTICS}（retrieval: TOOL）</td></tr>
 *   <tr><td>{@code RetrievalPipeline}</td><td><b>桩</b></td>
 *       <td>★ 只为了断言它<b>一次都没被调用</b></td></tr>
 *   <tr><td><b>MCP 客户端 + 服务端</b></td><td><b>真</b></td>
 *       <td>走一次真实的 HTTP 回环：握手、tools/list、tools/call 全是真的</td></tr>
 *   <tr><td><b>数据库</b></td><td><b>真</b></td>
 *       <td>查库里真实存在的订单（用户 8 / U000006）与会话表</td></tr>
 * </table>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                // ★ 18082：避开 ToolChatIntegrationTest 的 18081 和本地开发的 8080
                "server.port=18082",
                "xbla.mcp.client.base-url=http://localhost:18082",
                // ★ 关掉摘要：它是【异步】后台任务，会在别的线程上读写数据库，
                //   和测试方法的事务回滚撞在一起 → 随机失败
                "xbla.chat.history.enabled=false",
                "xbla.chat.summary.enabled=false"
        })
@Transactional
@DisplayName("ChatService · 流式工具调用（阶段 9.1 验收）")
class ChatStreamToolIntegrationTest {

    /** 库里真实存在的：用户 8 = U000006，名下 8 笔订单 */
    private static final long USER_ID = 8L;
    private static final String HIS_ORDER = "SO202609160001";

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    private static ChatUsage usage(int prompt, int completion) {
        return new ChatUsage(prompt, completion, prompt + completion, null, null, null);
    }

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private QaLogMapper qaLogMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatModelRouter router;

    @MockitoBean
    private LlmIntentClassifier intentClassifier;

    @MockitoBean
    private RetrievalPipeline retrievalPipeline;

    // ============================================================
    // 测试用的 sink —— 把推出来的事件原样收下来
    // ============================================================

    /** 收集 {@code onStart / onDelta / onComplete / onError} 的桩接收器 */
    private static final class CapturingSink implements ChatService.ChatStreamSink {
        final StringBuilder body = new StringBuilder();
        String startedTraceId;
        String startedSessionNo;
        ChatAskResponse done;
        String error;

        @Override
        public void onStart(String traceId, String sessionNo) {
            this.startedTraceId = traceId;
            this.startedSessionNo = sessionNo;
        }

        @Override
        public void onDelta(String delta) {
            body.append(delta);
        }

        @Override
        public void onComplete(ChatAskResponse summary) {
            this.done = summary;
        }

        @Override
        public void onError(String message, String traceId) {
            this.error = message;
        }
    }

    // ============================================================
    // 剧本
    // ============================================================

    /** 让分类固定返回「订单物流」——它在意图树里声明的是 retrieval: TOOL */
    private void classifyAsToolIntent() {
        when(intentClassifier.classify(any(), any())).thenReturn(new IntentClassification(
                "ORDER_LOGISTICS", IntentClassification.Outcome.CLASSIFIED,
                "ORDER_LOGISTICS", DESCRIPTOR, new BigDecimal("0.0001"), 5, null));
    }

    /**
     * 第一轮返回一个 tool_calls（正文空串），之后返回正文。
     *
     * <p>★ 第一轮的 {@code content} 刻意给空串 —— 实测这就是
     * {@code deepseek-flash} 的真实行为。桩不复现它的话，
     * 「空正文被误判成失败」那个坑在测试里永远暴露不出来（ADR-060）。
     */
    private void scriptToolThenAnswer(String orderNo, String finalAnswer) {
        AtomicInteger round = new AtomicInteger();
        when(router.chat(any(), any())).thenAnswer(inv -> {
            ModelCallTrace trace = inv.getArgument(1);
            ChatRequest request = inv.getArgument(0);
            int n = round.incrementAndGet();

            if (n == 1) {
                assertThat(request.hasTools())
                        .as("第 1 轮必须带着工具问 —— 否则模型根本没机会调")
                        .isTrue();
                trace.succeeded(DESCRIPTOR, usage(300, 20), 10);
                trace.cost(new BigDecimal("0.000400"));
                return new ChatResponse("", "tool_calls", usage(300, 20), DESCRIPTOR, 10,
                        List.of(new ToolCall("call_00_stream_1", "query_order_status",
                                "{\"order_no\":\"" + orderNo + "\"}")),
                        "用户的订单号是 " + orderNo);
            }
            trace.succeeded(DESCRIPTOR, usage(500, 80), 20);
            trace.cost(new BigDecimal("0.000900"));
            return ChatResponse.text(finalAnswer, "stop", usage(500, 80), DESCRIPTOR, 20);
        });
    }

    /** 按 traceId 精确取 qa_log（★ 不能 selectList(null)：表里有历史数据） */
    private QaLog qaLogOf(String traceId) {
        List<QaLog> logs = qaLogMapper.selectList(
                new LambdaQueryWrapper<QaLog>().eq(QaLog::getTraceId, traceId));
        assertThat(logs).as("这次问答应该恰好写一行 qa_log，traceId=%s", traceId).hasSize(1);
        return logs.get(0);
    }

    /** JSONB 列一律解析成对象再断言 —— 别比文本，PG 会重排键并规范化空格 */
    private List<Map<String, Object>> toolCallsOf(QaLog log) throws Exception {
        assertThat(log.getToolCalls()).as("tool_calls 不应该为 null").isNotNull();
        return objectMapper.readValue(log.getToolCalls(),
                new TypeReference<List<Map<String, Object>>>() {
                });
    }

    private ChatSession sessionOf(String sessionNo) {
        return chatSessionService.lambdaQuery()
                .eq(ChatSession::getSessionNo, sessionNo)
                .one();
    }

    // ============================================================
    // 一、主路径
    // ============================================================

    @Nested
    @DisplayName("一、流式工具路径真的走通了")
    class MainPath {

        @Test
        @DisplayName("① ★★ 问「我的订单到哪了」走 SSE → 真的调了工具 → 正文推出来了")
        void streamToolCallEndToEnd() throws Exception {
            classifyAsToolIntent();
            scriptToolThenAnswer(HIS_ORDER, "您的订单 SO202609160001 状态是「已取消」。");
            CapturingSink sink = new CapturingSink();

            chatService.askStream(new ChatAskRequest(null, "我的订单到哪了", null), sink, USER_ID,
                    CallContext.fresh("t-stream-tool-1"));

            // ① 正文确实推出来了 —— 这是下面每一条的【前提】：
            //    如果这条路根本没走到工具分支，正文会是检索那条路的产物
            assertThat(sink.body.toString())
                    .as("工具轮的正文应当被推给前端")
                    .contains("已取消");
            assertThat(sink.error).as("不该有 failed 事件").isNull();

            // ② ★★ 工具真的被调用了 —— 证据在 qa_log.tool_calls 里，不在回答文本里。
            //    「回答里有某个词」会被模型的措辞绑架；「工具确实说过」不会。
            QaLog log = qaLogOf("t-stream-tool-1");
            assertThat(log.getStatus()).isEqualTo(QaLog.STATUS_SUCCESS);
            assertThat(log.getIntent()).isEqualTo("ORDER_LOGISTICS");
            var calls = toolCallsOf(log);
            assertThat(calls).hasSize(1);
            assertThat(calls.get(0).get("tool")).isEqualTo("query_order_status");
            assertThat(calls.get(0).get("isError")).isEqualTo(false);

            // ③ 工具意图【一次都没检索】—— 跑了的话模型会拿通用规则编一个订单状态
            verify(retrievalPipeline, never()).retrieve(any(), any(), any());
            assertThat(log.getRetrievalDetail())
                    .as("工具路径的 retrieval_detail 必须是 NULL（= 检索确实没发生），"
                            + "而不是一个空对象（= 跑了但什么都没召回）")
                    .isNull();
        }

        @Test
        @DisplayName("★★ ② 流式工具轮的 done.answer 必须是 null，且正文只推了一次")
        void doneAnswerStaysNullOnToolRound() {
            classifyAsToolIntent();
            scriptToolThenAnswer(HIS_ORDER, "您的订单已取消。");
            CapturingSink sink = new CapturingSink();

            chatService.askStream(new ChatAskRequest(null, "我的订单到哪了", null), sink, USER_ID,
                    CallContext.fresh("t-stream-tool-2"));

            // ★★ 这一条防的是「一个字段三种含义」：
            //   非流式=正文、流式=刻意 null、而如果工具轮偷懒复用了
            //   buildToolResponse，工具轮就会变成「流式但 answer 有正文」——
            //   前端那套「收到 done 就收尾」的写法会为它长出特例，
            //   或者干脆在工具轮上把正文渲染两遍。
            assertThat(sink.done).as("应该收到 done 事件").isNotNull();
            assertThat(sink.done.answer())
                    .as("★★ 流式契约：正文只在 delta 里，done.answer 一律为 null —— 工具轮也是流式轮")
                    .isNull();

            // 反对照：正文确实在 delta 里存在过（否则上面那条断言恒真）
            assertThat(sink.body.toString()).isNotEmpty();

            assertThat(sink.done.references())
                    .as("★ 纯工具轮没有检索，就没有引用 —— "
                            + "写成空数组会让「没检索」和「检索了但没召回」分不开（ADR-041），"
                            + "所以这里必须是 null")
                    .isNull();
        }
    }

    // ============================================================
    // 二、身份贯通
    // ============================================================

    @Nested
    @DisplayName("二、身份在流式路上也活着")
    class Identity {

        @Test
        @DisplayName("★★ ③ qa_log.user_id 不再是 NULL —— 它和会话归属是两列，都该有值")
        void identityLandsInQaLogAndSession() {
            classifyAsToolIntent();
            scriptToolThenAnswer(HIS_ORDER, "您的订单已取消。");
            CapturingSink sink = new CapturingSink();

            chatService.askStream(new ChatAskRequest(null, "我的订单到哪了", null), sink, USER_ID,
                    CallContext.fresh("t-stream-tool-3"));

            QaLog log = qaLogOf("t-stream-tool-3");
            assertThat(log.getUserId())
                    .as("★ 阶段 9 之前这一列恒为 NULL，因为 baseLog 读的是 session.getUserId()，"
                            + "而 resolveSession 把它写死成了 null")
                    .isEqualTo(USER_ID);

            // ★ 会话归属（会话粒度、write-once）—— 和上面那条是【两个东西】
            assertThat(sink.startedSessionNo).as("onStart 应该推出 sessionNo").isNotNull();
            assertThat(sessionOf(sink.startedSessionNo).getUserId())
                    .as("新建的会话应当记下归属")
                    .isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("★★ ④ 反对照：身份缺席时是 NULL —— 匿名是合法的，不是失败")
        void anonymousStaysNull() {
            classifyAsToolIntent();
            scriptToolThenAnswer(HIS_ORDER, "您的订单已取消。");
            CapturingSink sink = new CapturingSink();

            // userId 传 null：这条路上工具会回一句「需要先知道你是哪位」
            chatService.askStream(new ChatAskRequest(null, "我的订单到哪了", null), sink, null,
                    CallContext.fresh("t-stream-tool-4"));

            assertThat(qaLogOf("t-stream-tool-4").getUserId())
                    .as("没有身份时留 NULL —— 填 0 或 -1 会让 "
                            + "「有多少比例的问答是匿名的」这个问题永远算错")
                    .isNull();
            assertThat(sessionOf(sink.startedSessionNo).getUserId()).isNull();
        }
    }
}
