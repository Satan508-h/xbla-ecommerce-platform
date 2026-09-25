package com.xbla.rag.controller;

import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.common.EvalMark;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;
import com.xbla.rag.service.CallContext;
import com.xbla.rag.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * <b>两个评测头能不能变成 {@code CallContext.eval}</b>（阶段 7）。
 *
 * <h2>★★★ 这个类守的是「头名」这个约定</h2>
 *
 * <p>评测标记的整条链路是一串<b>约定</b>，而其中最松的一环是<b>头名</b>：
 *
 * <pre>
 *   Java 侧：EvalMark.HEADER_RUN_ID = "X-Xbla-Eval-Run"
 *   Python 侧：scripts/eval_run.py 里手写的字面量
 * </pre>
 *
 * <p>两处拼错任意一处（或者只改一处），<b>没有任何东西会报错</b>：
 * 请求照常成功、问答照常回答，只是那一轮 450 行数据全被当成真实用户流量。
 * 而它的表现是「报告里一行都没有」—— 你会先去怀疑报告端点，
 * 而报告端点是对的。
 *
 * <p>★ 所以这里从<b>HTTP 那一头</b>（真的发一个带头/不带头的请求）开始测，
 * 而不是直接 {@code new EvalMark(...)} 去测下游 —— 后者测不到头名。
 *
 * <h2>★ 两条路都要测</h2>
 *
 * <p>非流式和流式在 {@code ChatController} 里是<b>两个方法</b>，
 * 各自读一次头（经由同一个 {@code resolveEvalMark}）。
 * 本项目已经因为「两条平行路径只改了一条」踩过 ADR-047，
 * 而流的漏掉标记尤其难发现：它的 qa_log 是在另一个线程上写的。
 *
 * <h2>★ 桩掉 ChatService，不花钱也不碰数据库</h2>
 *
 * <p>和 {@code ChatControllerErrorMappingTest} 同一套做法：
 * {@code ChatServiceImpl} 根本不会被创建，模型调用、检索、qa_log 写入都不发生。
 * 而 {@code ChatAdmissionService} 是<b>真的</b> —— 标记要穿过它才到得了
 * {@code ChatService}，所以它必须在。
 */
@SpringBootTest(properties = {
        // ★ 名额给够，让请求【不排队】直接跑到 work —— 这个类测的不是排队
        "xbla.ratelimit.permits=8",
        "xbla.ratelimit.key-prefix=xbla:rl:{eval-mark}",
        "xbla.ratelimit.channel=xbla:rl:eval-mark-events"
})
@AutoConfigureMockMvc
@DisplayName("ChatController · 评测头 → CallContext")
class ChatControllerEvalMarkTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chatService;

    /** 非流式返回什么不重要，别抛异常就行 */
    private static ChatAskResponse plainAnswer() {
        return new ChatAskResponse("t-1", "s-1", "自签收之日起 7 天内。", "deepseek",
                "deepseek-flash", new ChatUsage(10, 5, 15, null, null, null),
                null, 10, 20, false, List.of(), List.of(), "RETURN_EXCHANGE");
    }

    // ============================================================
    // 一、非流式
    // ============================================================

    @Nested
    @DisplayName("一、POST /api/chat")
    class NonStreaming {

        @Test
        @DisplayName("★★ 带上两个头 → CallContext 里就有标记")
        void headersBecomeAMark() throws Exception {
            when(chatService.ask(any(ChatAskRequest.class), any(), any(CallContext.class)))
                    .thenReturn(plainAnswer());

            mvc.perform(post("/api/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"退货要几天\"}")
                            .header(EvalMark.HEADER_RUN_ID, "run-http-1")
                            .header(EvalMark.HEADER_QUESTION_NO, "X-001"))
                    .andReturn();

            CallContext ctx = capturedAskContext();
            assertNotNull(ctx.eval(),
                    "★★ 头带了、却没有标记 —— 说明头名对不上（Java 侧常量 vs 测试/脚本里的字面量），"
                            + "或者 Controller 忘了把它传进 Admission。"
                            + "两种都是【编译通过、请求成功、数据错】的形态");
            assertEquals("run-http-1", ctx.eval().runId());
            assertEquals("X-001", ctx.eval().questionNo());
        }

        @Test
        @DisplayName("★★ 反对照：不带头 → 标记必须是 null（不是空对象）")
        void noHeadersMeansNoMark() throws Exception {
            when(chatService.ask(any(ChatAskRequest.class), any(), any(CallContext.class)))
                    .thenReturn(plainAnswer());

            mvc.perform(post("/api/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"退货要几天\"}"))
                    .andReturn();

            assertNull(capturedAskContext().eval(),
                    "★★ 真实用户的请求必须【完全没有】标记。"
                            + "如果这里是个「字段全空的对象」，"
                            + "`WHERE eval_run_id IS NULL` 这个筛子就失效了 —— "
                            + "而那正是最需要它准的那个查询");
        }

        @Test
        @DisplayName("★ 只带一个头 → 半标记原样保留（报告端点负责把它报出来）")
        void halfMarkIsPassedThroughUnchanged() throws Exception {
            when(chatService.ask(any(ChatAskRequest.class), any(), any(CallContext.class)))
                    .thenReturn(plainAnswer());

            mvc.perform(post("/api/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"退货要几天\"}")
                            .header(EvalMark.HEADER_RUN_ID, "run-half"))
                    .andReturn();

            EvalMark mark = capturedAskContext().eval();
            assertNotNull(mark, "只带 run 也是评测流量，不能当成真实用户放过去");
            assertEquals("run-half", mark.runId());
            assertNull(mark.questionNo(),
                    "★ 它会落库成一个归不了题的行 —— 报告端点要把这种行数出来报，"
                            + "所以这里【不能】替它编一个题号");
        }
    }

    // ============================================================
    // 二、流式
    // ============================================================

    @Nested
    @DisplayName("二、GET /api/chat/stream")
    class Streaming {

        /**
         * ★★ 桩必须<b>把流走完</b>（调一次 {@code sink.onComplete}），否则
         * {@code asyncDispatch} 会一直等到异步超时。
         *
         * <p>踩过：{@code @MockitoBean} 的默认行为是「什么都不做」，
         * 于是 SSE 的 emitter <b>永远不被完成</b>，而 async 超时是
         * {@code sseTimeoutMs()}（排队预算 + 回答预算，180 秒）——
         * 两个流式用例加起来就是六分钟的静默等待，
         * 看起来像「测试卡死了」，实际是在等一个永远不会来的回调。
         */
        @BeforeEach
        void stubStreamToFinish() {
            doAnswer(invocation -> {
                ChatService.ChatStreamSink sink = invocation.getArgument(1);
                sink.onStart("t-1", "s-1");
                sink.onComplete(plainAnswer());
                return null;
            }).when(chatService).askStream(any(ChatAskRequest.class), any(), any(), any(CallContext.class));
        }

        @Test
        @DisplayName("★★ 带上两个头 → 流式那条路也拿到标记")
        void streamHeadersBecomeAMark() throws Exception {
            MvcResult result = mvc.perform(get("/api/chat/stream")
                            .param("question", "退货要几天")
                            .header(EvalMark.HEADER_RUN_ID, "run-http-2")
                            .header(EvalMark.HEADER_QUESTION_NO, "X-002"))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            // ★ 先推进异步请求，让 work 真的跑起来（否则 answer- 线程还没被提交）
            mvc.perform(asyncDispatch(result)).andReturn();

            CallContext ctx = capturedStreamContext();
            assertNotNull(ctx.eval(),
                    "★★ 流式漏掉标记是最难发现的一种：它的 qa_log 在另一个线程上写，"
                            + "而且整条路看起来完全正常");
            assertEquals("run-http-2", ctx.eval().runId());
            assertEquals("X-002", ctx.eval().questionNo());
        }

        @Test
        @DisplayName("★★ 反对照：流式不带头 → null")
        void streamWithoutHeadersMeansNoMark() throws Exception {
            MvcResult result = mvc.perform(get("/api/chat/stream")
                            .param("question", "退货要几天"))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            mvc.perform(asyncDispatch(result)).andReturn();

            assertNull(capturedStreamContext().eval());
        }
    }

    // ============================================================
    // 取回 ChatService 收到的那个 CallContext
    // ============================================================

    /**
     * 非流式那条路抓到的上下文。
     *
     * <p>★ 用 {@code verify(timeout)} 而不是直接抓：{@code ChatService}
     * 是在 {@code answer-} 线程上被调用的（非流式走的是
     * {@code CompletableFuture}），调用方线程回到测试里时它<b>可能还没发生</b>。
     * 不加 timeout 的话，这个测试会偶发地抓到 {@code null}
     * —— 而那种失败最容易被当成环境问题忽略掉。
     */
    private CallContext capturedAskContext() {
        ArgumentCaptor<CallContext> captor = ArgumentCaptor.forClass(CallContext.class);
        verify(chatService, timeout(5_000))
                .ask(any(ChatAskRequest.class), any(), captor.capture());
        return captor.getValue();
    }

    /** 流式那条路抓到的上下文 —— 它走的是 {@code askStream}，另一个方法 */
    private CallContext capturedStreamContext() {
        ArgumentCaptor<CallContext> captor = ArgumentCaptor.forClass(CallContext.class);
        verify(chatService, timeout(5_000))
                .askStream(any(ChatAskRequest.class), any(), any(), captor.capture());
        return captor.getValue();
    }
}
