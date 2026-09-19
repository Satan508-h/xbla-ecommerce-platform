package com.xbla.rag.service;

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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.rag.RetrievalPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「我的订单到哪了」端到端走通 MCP 工具 —— <b>阶段 5.8 的验收标准 1</b>。
 *
 * <h2>这个测试里什么是真的、什么是桩</h2>
 *
 * <table border="1">
 *   <caption>真假对照</caption>
 *   <tr><th>组件</th><th>真假</th><th>替代成什么</th></tr>
 *   <tr><td>{@code ChatModelRouter}</td><td><b>桩</b></td>
 *       <td>按剧本返回「先 tool_calls，后正文」。★ 不花钱</td></tr>
 *   <tr><td>{@code LlmIntentClassifier}</td><td><b>桩</b></td>
 *       <td>固定返回 {@code ORDER_LOGISTICS}。★ 否则它会先消费掉
 *           router 的那一次桩响应</td></tr>
 *   <tr><td>{@code RetrievalPipeline}</td><td><b>桩</b></td>
 *       <td>★ 只为了断言它<b>一次都没被调用</b></td></tr>
 *   <tr><td><b>MCP 客户端 + 服务端</b></td><td><b>真</b></td>
 *       <td>— 走一次真实的 HTTP 回环，握手、tools/list、tools/call 全是真的</td></tr>
 *   <tr><td><b>数据库</b></td><td><b>真</b></td>
 *       <td>— 查的是库里真实存在的订单（用户 8 / U000006）</td></tr>
 * </table>
 *
 * <p>★ 把 MCP 那一半留成真的，是因为<b>它才是这一阶段要验的东西</b>。
 * 把它也 mock 掉的话，这个测试只是在验证「我调了一个我自己写的接口」——
 * 而工具那条链路上真正会坏的，恰恰是模型给的参数能不能变成一次
 * 合法的 {@code tools/call}、结果能不能变回消息。
 *
 * <h2>★★ 这个测试要证明的三件事（光看返回的文本，三件都看不出来）</h2>
 *
 * <ol>
 *   <li><b>检索一次都没跑</b> —— 工具意图必须短路掉知识库检索。
 *       跑了的后果是模型拿通用规则编一个订单状态出来（ADR-044），
 *       而<b>最终回答的文本可能一模一样</b></li>
 *   <li><b>第二跳的消息序列是对的</b> —— 末尾没有重复的用户提问，
 *       而多一条 tool 消息、id 对得上。
 *       拼错的后果是模型重调工具（看起来像「模型陷入循环」）</li>
 *   <li><b>多轮的成本是累加的</b> —— 只记最后一轮会让第 1 轮的花费凭空消失</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                // ★ 18081，避开集成测试常用的 18080 和本地开发用的 8080
                "server.port=18081",
                "xbla.mcp.client.base-url=http://localhost:18081",
                // ★ 关掉会话记忆与摘要：这里的关注点是工具链路，
                //   而摘要是一个【异步】后台任务，它会在线程池里读写数据库 ——
                //   和测试方法的事务回滚撞在一起，症状是随机失败
                "xbla.chat.history.enabled=false",
                "xbla.chat.summary.enabled=false"
        })
@Transactional
@DisplayName("ChatService · 工具调用端到端（验收标准 1）")
class ToolChatIntegrationTest {

    /** 库里真实存在的：用户 8 = U000006，名下 8 笔订单 */
    private static final long USER_ID = 8L;
    private static final String HIS_ORDER = "SO202609160001";

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    /** 每轮给一个不同的用量，方便验证「累加」而不是「覆盖」 */
    private static ChatUsage usage(int prompt, int completion) {
        return new ChatUsage(prompt, completion, prompt + completion, null, null, null);
    }

    @Autowired
    private ChatService chatService;

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
    // 剧本
    // ============================================================

    /** 让分类固定返回「订单物流」——它在意图树里声明的是 retrieval: TOOL */
    private void classifyAsToolIntent() {
        when(intentClassifier.classify(any())).thenReturn(new IntentClassification(
                "ORDER_LOGISTICS", IntentClassification.Outcome.CLASSIFIED,
                "ORDER_LOGISTICS", DESCRIPTOR, new BigDecimal("0.0001"), 5, null));
    }

    /**
     * 第一轮返回一个 tool_calls，之后返回正文。
     *
     * <p>★ 第一轮的 {@code content} 刻意给<b>空串</b> —— 实测这就是
     * {@code deepseek-flash} 的真实行为（连续 5 次「直接调工具不要说话」，
     * 5 次的 content 全是 {@code ''}）。桩必须复现它，
     * 否则那个「空正文被误判成失败」的坑在测试里<b>永远暴露不出来</b>。
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
                        List.of(new ToolCall("call_00_test_1", "query_order_status",
                                "{\"order_no\":\"" + orderNo + "\"}")),
                        "用户的订单号是 " + orderNo);
            }
            trace.succeeded(DESCRIPTOR, usage(500, 80), 20);
            trace.cost(new BigDecimal("0.000900"));
            return ChatResponse.text(finalAnswer, "stop", usage(500, 80), DESCRIPTOR, 20);
        });
    }

    /**
     * 按 {@code traceId} 取本次问答的 {@code qa_log}。
     *
     * <p>★★ <b>这里【不能】用 {@code selectList(null)} 再去断言「只有一行」</b>
     * —— 那是本类第一版的写法，而它失败了：库里有 <b>99 行历史数据</b>
     * （开发时攒下来的），断言当场崩掉，失败信息长得像产品有 bug。
     *
     * <p>教训是：<b>「表应该是空的」这种假设在测试里是定时炸弹</b> ——
     * 它在刚建库的机器上绿得发亮，一有真实数据就红，
     * 而红的原因和被测代码毫无关系。
     * 按 {@code traceId} 查是精确的，而且和表里有多少数据无关。
     */
    private QaLog qaLogOf(String traceId) {
        List<QaLog> logs = qaLogMapper.selectList(
                new LambdaQueryWrapper<QaLog>().eq(QaLog::getTraceId, traceId));
        assertThat(logs).as("这次问答应该恰好写一行 qa_log，traceId=%s", traceId).hasSize(1);
        return logs.get(0);
    }

    /**
     * 把 {@code qa_log.tool_calls} 解析成结构再断言 —— ★ <b>不要断言 JSON 文本</b>。
     *
     * <p>★★ 这是本类第二版踩的坑：写 {@code contains("\"isError\":false")}
     * 会失败，因为这一列是 <b>{@code JSONB}</b>，而 PostgreSQL 存进去时
     * 会<b>把键重排、并规范化空格</b>。读回来的实际长这样：
     * <pre>
     *   [{"tool": "query_order_status", "round": 1, "detail": "chars=89", "isError": false}]
     *                 ↑ 键序变了，冒号后面多了空格
     * </pre>
     *
     * <p>★ 这个事实<b>阶段 4 就已经记录过</b>（{@code RetrievalDetailBuilderTest}
     * 那条：用 {@code hasSameElementsAs} 而不是 {@code containsExactly}，
     * 「因为 JSONB 会重排键」）。这里又踩了一次 ——
     * 说明它值得在更多地方写下来。
     *
     * <p>结论：<b>凡是出过 {@code jsonb} 列的字段，断言一律解析成对象再比。</b>
     * 断言文本等于把「数据库的序列化细节」写进了测试，
     * 而那些细节不在我们的契约里，随时可能变。
     */
    private List<Map<String, Object>> toolCallsOf(QaLog log) throws Exception {
        assertThat(log.getToolCalls()).as("tool_calls 不应该为 null").isNotNull();
        return objectMapper.readValue(log.getToolCalls(),
                new TypeReference<List<Map<String, Object>>>() {
                });
    }

    // ============================================================
    // 一、主路径
    // ============================================================

    @Test
    @DisplayName("① ★ 问「我的订单到哪了」→ 真的调了工具 → 拿到订单内容 → 模型据此作答")
    void toolCallEndToEnd() throws Exception {
        classifyAsToolIntent();
        scriptToolThenAnswer(HIS_ORDER, "您的订单 SO202609160001 状态是「已取消」。");

        ChatAskResponse response = chatService.ask(
                new ChatAskRequest(null, "我的订单到哪了", null), USER_ID);

        // 最终回答来自【第 2 轮】（模型把工具结果变成人话的那一轮）
        assertThat(response.answer()).contains("已取消");

        // ★ 工具真的被调用了 —— 证据在 qa_log.tool_calls 里，不在回答文本里
        QaLog log = qaLogOf(response.traceId());
        assertThat(log.getStatus()).isEqualTo(QaLog.STATUS_SUCCESS);

        List<Map<String, Object>> calls = toolCallsOf(log);
        assertThat(calls)
                .as("★ tool_calls 为 null 就等于「工具根本没被调用」，而回答文本可能一模一样")
                .hasSize(1);
        assertThat(calls.get(0))
                .containsEntry("tool", "query_order_status")
                .containsEntry("isError", false)
                .containsEntry("round", 1);
    }

    @Test
    @DisplayName("② ★★ 工具意图【一次都没有】检索知识库 —— 这是 ADR-044 那条坑的护栏")
    void toolIntentNeverRetrieves() {
        classifyAsToolIntent();
        scriptToolThenAnswer(HIS_ORDER, "您的订单已取消。");

        ChatAskResponse response = chatService.ask(
                new ChatAskRequest(null, "我的订单到哪了", null), USER_ID);

        // ★★ 这条断言比它看起来重要得多。
        //
        //   工具类意图在意图树里声明 doc_types: []，而 [] 的语义是
        //   【不限制】不是【什么都不匹配】（ADR-044）。所以只要检索跑起来，
        //   它就会拿【整池切片】做一次全量双路召回 —— 用户问「我的订单到哪了」，
        //   召回的全是「一般发货要几天」这类通用规则，
        //   然后模型会拿它们编一个具体的订单状态出来。
        //
        //   ⚠️ 而那个编出来的回答，和真正的工具回答【在文本上看不出区别】——
        //      都是通顺的中文、都像模像样。所以这条只能靠「调用次数」证明。
        verify(retrievalPipeline, never()).retrieve(any(), any(), any());

        // 顺带确认：检索没跑，那几列就该是 NULL（「没有发生」的诚实表达）
        QaLog log = qaLogOf(response.traceId());
        assertThat(log.getRetrievalDetail()).isNull();
        assertThat(log.getReferences()).isNull();
        assertThat(response.references())
                .as("没有检索 → references 是 null，不是空数组")
                .isNull();
    }

    @Test
    @DisplayName("③ ★★ 第二跳的消息序列：末尾没有重复的提问，且 tool 消息的 id 对得上")
    void secondRoundMessageShape() {
        classifyAsToolIntent();
        scriptToolThenAnswer(HIS_ORDER, "您的订单已取消。");

        chatService.ask(new ChatAskRequest(null, "我的订单到哪了", null), USER_ID);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, org.mockito.Mockito.times(2)).chat(captor.capture(), any());
        List<ChatRequest> requests = captor.getAllValues();

        ChatRequest second = requests.get(1);
        List<ChatRequest.Turn> history = second.history();

        // ★ 末尾不能有用户提问。有的话序列变成
        //   …[tool:结果][user:我的订单到哪了]，模型会以为用户又问了一遍，
        //   于是再调一次工具 —— 看日志像「模型陷入循环」，其实是消息拼错了
        assertThat(second.userQuestion())
                .as("★ 工具往返的第二跳【没有】新的用户提问")
                .isNull();

        // ★ assistant(tool_calls) 后面必须紧跟【恰好一条】id 相同的 tool 消息。
        //   少一条、多一条、id 对不上，服务端一律 400，而且 400 不降级
        ChatRequest.Turn assistantTurn = history.get(history.size() - 2);
        ChatRequest.Turn toolTurn = history.get(history.size() - 1);

        assertThat(assistantTurn.role()).isEqualTo(ChatRequest.Role.ASSISTANT);
        assertThat(assistantTurn.hasToolCalls()).isTrue();
        assertThat(toolTurn.role()).isEqualTo(ChatRequest.Role.TOOL);
        assertThat(toolTurn.toolCallId())
                .as("tool_call_id 必须和请求它的那条 assistant 消息逐字相同")
                .isEqualTo(assistantTurn.toolCalls().get(0).id());

        // ★ 工具结果里必须带着真实查到的内容（说明 MCP 那一跳真的通了）
        assertThat(toolTurn.content()).contains(HIS_ORDER);
    }

    @Test
    @DisplayName("④ ★ reasoning_content 必须原样带回第二跳（丢了就 400，而且不降级）")
    void reasoningContentIsEchoedBack() {
        classifyAsToolIntent();
        scriptToolThenAnswer(HIS_ORDER, "您的订单已取消。");

        chatService.ask(new ChatAskRequest(null, "我的订单到哪了", null), USER_ID);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, org.mockito.Mockito.times(2)).chat(captor.capture(), any());

        List<ChatRequest.Turn> history = captor.getAllValues().get(1).history();
        ChatRequest.Turn assistantTurn = history.get(history.size() - 2);

        assertThat(assistantTurn.reasoningContent())
                .as("★ 推理模型的思考过程要在【内存里带一程】，见 WireChatRequest 的说明")
                .isEqualTo("用户的订单号是 " + HIS_ORDER);
    }

    // ============================================================
    // 二、成本累加
    // ============================================================

    @Test
    @DisplayName("⑤ ★★ 两轮的成本和 token 必须【累加】，不能只记最后一轮")
    void costAndTokensAccumulate() {
        classifyAsToolIntent();
        scriptToolThenAnswer(HIS_ORDER, "您的订单已取消。");

        ChatAskResponse response = chatService.ask(
                new ChatAskRequest(null, "我的订单到哪了", null), USER_ID);

        // 第 1 轮 300+20，第 2 轮 500+80
        assertThat(response.usage().promptTokens())
                .as("★ 只记最后一轮（500）会让第 1 轮的输入费凭空消失")
                .isEqualTo(800);
        assertThat(response.usage().completionTokens()).isEqualTo(100);

        // 0.000400 + 0.000900
        assertThat(response.cost())
                .as("★ 成本必须逐轮相加 —— 不能拿「总 token × 最后一轮单价」重算，"
                        + "因为降级换模型时两轮的单价不一样")
                .isEqualByComparingTo(new BigDecimal("0.001300"));

        QaLog log = qaLogOf(response.traceId());
        assertThat(log.getCost()).isEqualByComparingTo(new BigDecimal("0.001300"));
        assertThat(log.getTotalTokens()).isEqualTo(900);
        assertThat(log.getLlmLatencyMs()).as("10ms + 20ms").isEqualTo(30);
    }

    // ============================================================
    // 三、失败路径 —— 它们【都不能】让问答失败
    // ============================================================

    @Test
    @DisplayName("⑥ ★ 模型编了一个不存在的工具名 → 服务端的原话喂回去 → 模型下一轮改对")
    void unknownToolIsFedBackNotThrown() throws Exception {
        classifyAsToolIntent();

        AtomicInteger round = new AtomicInteger();
        when(router.chat(any(), any())).thenAnswer(inv -> {
            ModelCallTrace trace = inv.getArgument(1);
            trace.succeeded(DESCRIPTOR, usage(100, 10), 5);
            trace.cost(new BigDecimal("0.000100"));
            int n = round.incrementAndGet();
            if (n == 1) {
                // 编一个不存在的工具
                return new ChatResponse("", "tool_calls", usage(100, 10), DESCRIPTOR, 5,
                        List.of(new ToolCall("call_00_x", "query_weather",
                                "{\"city\":\"上海\"}")),
                        null);
            }
            return ChatResponse.text("抱歉，我这边只能查订单。", "stop",
                    usage(100, 10), DESCRIPTOR, 5);
        });

        ChatAskResponse response = chatService.ask(
                new ChatAskRequest(null, "我的订单到哪了", null), USER_ID);

        // ★ 关键：整次问答【成功了】。模型编错工具名是它的输出问题，
        //   不是系统故障 —— 让它看到「未知的工具」然后自己纠正，
        //   比抛一个异常出来好得多（异常会让用户看到「服务内部错误」）
        assertThat(response.answer()).isNotBlank();

        QaLog log = qaLogOf(response.traceId());
        assertThat(log.getStatus()).isEqualTo(QaLog.STATUS_SUCCESS);

        List<Map<String, Object>> calls = toolCallsOf(log);
        assertThat(calls)
                .as("★ 这次调用要记下来 —— 否则阶段 7 分不清"
                        + "「模型乱调工具」和「工具没被调用」")
                .hasSize(1);
        assertThat(calls.get(0))
                .containsEntry("tool", "query_weather")
                .containsEntry("isError", true);
    }

    @Test
    @DisplayName("⑦ ★ 模型给的参数不是合法 JSON → 同样喂回去，不抛异常")
    void malformedArgumentsAreFedBack() {
        classifyAsToolIntent();

        AtomicInteger round = new AtomicInteger();
        when(router.chat(any(), any())).thenAnswer(inv -> {
            ModelCallTrace trace = inv.getArgument(1);
            trace.succeeded(DESCRIPTOR, usage(100, 10), 5);
            trace.cost(new BigDecimal("0.000100"));
            if (round.incrementAndGet() == 1) {
                return new ChatResponse("", "tool_calls", usage(100, 10), DESCRIPTOR, 5,
                        List.of(new ToolCall("call_00_bad", "query_order_status",
                                "{order_no: 这不是合法 JSON")),
                        null);
            }
            return ChatResponse.text("我没能查到那笔订单。", "stop",
                    usage(100, 10), DESCRIPTOR, 5);
        });

        ChatAskResponse response = chatService.ask(
                new ChatAskRequest(null, "我的订单到哪了", null), USER_ID);

        assertThat(response.answer()).isNotBlank();
        assertThat(qaLogOf(response.traceId()).getStatus()).isEqualTo(QaLog.STATUS_SUCCESS);
    }

    @Test
    @DisplayName("⑧ ★★ 没有身份 → 短路不调模型，回一句实话（不是 401，也不编）")
    void withoutIdentityShortCircuits() {
        classifyAsToolIntent();
        scriptToolThenAnswer(HIS_ORDER, "这条路不该被走到");

        // userId = null
        ChatAskResponse response = chatService.ask(
                new ChatAskRequest(null, "我的订单到哪了", null), null);

        // ★ 一次模型调用都没有 —— 分类是桩不算，工具路径上应该零调用
        verify(router, never()).chat(any(), any());

        assertThat(response.answer())
                .as("回的是一句「需要先知道你是哪位」，不是编造的订单状态")
                .contains("X-Xbla-User-Id");

        // ★ provider / cost 全 NULL —— 和澄清路径同一条约定：
        //   「没有发生生成调用」的诚实表达。填 0 或者空对象都会让它
        //   和「调了但没数据」混起来
        assertThat(response.provider()).isNull();
        assertThat(response.cost()).isNull();

        // ★ tool_calls 也是 NULL —— 一次工具都没调
        assertThat(qaLogOf(response.traceId()).getToolCalls()).isNull();
    }
}
