package com.xbla.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.agent.intent.IntentClassification;
import com.xbla.rag.agent.intent.IntentPlan;
import com.xbla.rag.agent.intent.LlmIntentClassifier;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.client.dto.ToolCall;
import com.xbla.rag.client.dto.ToolSpec;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.rag.RetrievalPipeline;
import com.xbla.rag.rag.RetrievalTrace;
import com.xbla.rag.rag.retrieve.RetrievedChunk;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>阶段 9.3 的验收</b>：一次问答里<b>检索和工具【同时】发生</b>。
 *
 * <h2>★★★ 「混合轮」是什么，以及它为什么需要一个自己的测试</h2>
 *
 * <pre>
 *   纯 KB 轮     gate=RETRIEVE  chunks 有值       tools 空
 *   纯工具轮     gate=TOOLS     检索一次没跑      tools 有值
 *   混合轮       ★ 检索【跑了】 ∧ 工具【给了】    ← 本类
 * </pre>
 *
 * <p>混合轮的用途是「既要通用建议，也想知道现在有什么」——
 * {@code SCENARIO_PICK}（挑礼物）就是标准形态：先检索导购知识，
 * 再让模型拿着 {@code search_products} 去看有哪些货。
 *
 * <p>★ 它需要单独的测试，是因为<b>它的两条证据分别落在三个不同的地方</b>：
 * 检索跑没跑要看桩的调用次数和 {@code qa_log} 的检索列，
 * 工具给没给要看<b>模型实际收到的那份报文</b>，
 * 而工具调没调要看 {@code qa_log.tool_calls}。
 * <b>任何一处「接上了一半」都不会报错</b>，只会让某一类问题答得更差。
 *
 * <h2>真假的界线</h2>
 *
 * <table border="1">
 *   <caption>这个测试里什么是真的、什么是桩</caption>
 *   <tr><th>组件</th><th>真假</th><th>说明</th></tr>
 *   <tr><td>{@code ChatModelRouter}</td><td><b>桩</b></td>
 *       <td>按剧本「先 tool_calls、后正文」。★ 不花钱</td></tr>
 *   <tr><td>{@code LlmIntentClassifier}</td><td><b>桩</b></td>
 *       <td>固定返回某个 code + 一份 JSON 计划</td></tr>
 *   <tr><td>{@code RetrievalPipeline}</td><td><b>桩</b></td>
 *       <td>真跑会调向量化接口 —— 那是要花钱的。这里只关心它有没有被调用</td></tr>
 *   <tr><td><b>意图树</b></td><td><b>真</b></td>
 *       <td>★ 读的就是 {@code data/agent/intent-tree.yml} ——
 *           工具清单来自它的声明，不是测试里写死的</td></tr>
 *   <tr><td><b>MCP 客户端 + 服务端</b></td><td><b>真</b></td>
 *       <td>走一次真实的 HTTP 回环：握手、tools/list、tools/call 全是真的</td></tr>
 *   <tr><td><b>数据库</b></td><td><b>真</b></td>
 *       <td>工具查的是库里真实的商品/订单（用户 8 = U000006）</td></tr>
 * </table>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                // ★ 18083：避开 ToolChatIntegrationTest 的 18081 和
                //   ChatStreamToolIntegrationTest 的 18082
                "server.port=18083",
                "xbla.mcp.client.base-url=http://localhost:18083",
                // ★ 关掉摘要：它是【异步】后台任务，会在别的线程上读写数据库，
                //   和测试方法的事务回滚撞在一起 → 随机失败
                "xbla.chat.history.enabled=false",
                "xbla.chat.summary.enabled=false"
        })
@Transactional
@DisplayName("ChatService · 混合轮（阶段 9.3 验收）")
class HybridRoundIntegrationTest {

    /** 库里真实存在的：用户 8 = U000006，名下 8 笔订单 */
    private static final long USER_ID = 8L;
    private static final String HIS_ORDER = "SO202609160001";

    /** ★ 桩返回的知识库切片 —— 断言「它进了工具轮的 system prompt」用的就是这段字 */
    private static final String KB_TEXT = "送礼导购：给长辈挑礼物，要注意操作简单、字大、续航长。";

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

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

    /** 模型实际收到的每一份报文（按顺序）。★ 断言「给了哪些工具」只能看它 */
    private final List<ChatRequest> seen = new ArrayList<>();

    // ============================================================
    // 剧本
    // ============================================================

    private void classifyAs(String code, boolean retrieve) {
        when(intentClassifier.classify(any(), any())).thenReturn(new IntentClassification(
                code, IntentClassification.Outcome.CLASSIFIED, code,
                DESCRIPTOR, new BigDecimal("0.0001"), 5, null,
                new IntentPlan(retrieve, List.of(), IntentPlan.Shape.JSON)));
    }

    private void retrievalReturnsKnowHow() {
        when(retrievalPipeline.retrieve(anyString(), any(), any(RetrievalTrace.class)))
                .thenReturn(List.of(new RetrievedChunk(1L, 1L, 0, KB_TEXT,
                        "导购指南 > 送礼", 0.9)));
    }

    private void scriptPlainAnswer(String answer) {
        when(router.chat(any(), any())).thenAnswer(inv -> {
            ModelCallTrace trace = inv.getArgument(1);
            seen.add(inv.getArgument(0));
            trace.succeeded(DESCRIPTOR, usage(300, 20), 5);
            trace.cost(new BigDecimal("0.000100"));
            return ChatResponse.text(answer, "stop", usage(300, 20), DESCRIPTOR, 5);
        });
    }

    /** 第一轮调一个工具（正文空串），之后给正文。★ 空正文是 deepseek-flash 的真实行为（ADR-060） */
    private void scriptToolThenAnswer(String toolName, String argsJson, String answer) {
        AtomicInteger round = new AtomicInteger();
        when(router.chat(any(), any())).thenAnswer(inv -> {
            ModelCallTrace trace = inv.getArgument(1);
            seen.add(inv.getArgument(0));
            if (round.incrementAndGet() == 1) {
                trace.succeeded(DESCRIPTOR, usage(300, 20), 10);
                trace.cost(new BigDecimal("0.000400"));
                return new ChatResponse("", "tool_calls", usage(300, 20), DESCRIPTOR, 10,
                        List.of(new ToolCall("call_00_hybrid", toolName, argsJson)), null);
            }
            trace.succeeded(DESCRIPTOR, usage(500, 80), 20);
            trace.cost(new BigDecimal("0.000900"));
            return ChatResponse.text(answer, "stop", usage(500, 80), DESCRIPTOR, 20);
        });
    }

    // ============================================================
    // 断言用的读库
    // ============================================================

    private QaLog qaLogOf(String traceId) {
        List<QaLog> logs = qaLogMapper.selectList(
                new LambdaQueryWrapper<QaLog>().eq(QaLog::getTraceId, traceId));
        assertThat(logs).as("这次问答应该恰好写一行 qa_log，traceId=%s", traceId).hasSize(1);
        return logs.get(0);
    }

    /** JSONB 列一律解析成对象再断言 —— 别比文本，PG 会重排键并规范化空格 */
    private List<Map<String, Object>> toolCallsOf(QaLog log) throws Exception {
        assertThat(log.getToolCalls()).as("tool_calls 不该为 null").isNotNull();
        return objectMapper.readValue(log.getToolCalls(),
                new TypeReference<List<Map<String, Object>>>() {
                });
    }

    private Map<String, Object> planOf(QaLog log) throws Exception {
        assertThat(log.getIntentPlan()).as("intent_plan 不该为 null").isNotNull();
        return objectMapper.readValue(log.getIntentPlan(),
                new TypeReference<Map<String, Object>>() {
                });
    }

    private static List<String> toolNames(ChatRequest request) {
        return request.tools() == null
                ? List.of()
                : request.tools().stream().map(ToolSpec::name).toList();
    }

    // ============================================================
    // 一、★★★ 混合轮本身
    // ============================================================

    @Nested
    @DisplayName("一、★★★ 混合轮：检索和工具同时发生")
    class TheHybridRound {

        @Test
        @DisplayName("★★★ SCENARIO_PICK：先检索、再把工具给出去，一次问答留下两样证据")
        void hybridRoundLeavesBothPiecesOfEvidence() throws Exception {
            classifyAs("SCENARIO_PICK", true);
            retrievalReturnsKnowHow();
            scriptToolThenAnswer("search_products", "{\"keyword\":\"手机\",\"limit\":2}",
                    "给长辈挑的话，操作简单最重要。");
            seen.clear();

            var response = chatService.ask(
                    new ChatAskRequest(null, "有什么适合送长辈的", null), USER_ID);

            // ── 证据一：检索真的跑了 ──
            verify(retrievalPipeline, times(1))
                    .retrieve(anyString(), any(), any(RetrievalTrace.class));
            QaLog log = qaLogOf(response.traceId());
            assertThat(log.getRetrievalDetail())
                    .as("★ 混合轮的检索列必须【有值】—— 写 NULL 会让它和「纯工具轮」"
                            + "在库里长得一模一样")
                    .isNotNull();

            // ── 证据二：工具真的被调了（在 qa_log 里，不在回答文本里）──
            assertThat(toolCallsOf(log)).singleElement().satisfies(call -> {
                assertThat(call.get("tool")).isEqualTo("search_products");
                assertThat(call.get("isError")).isEqualTo(false);
            });

            // ── 证据三：模型第一轮手上【同时】有三件工具和知识库切片 ──
            ChatRequest first = seen.get(0);
            assertThat(toolNames(first))
                    .as("★ 顺序是【注册表的顺序】（按名字升序），不是意图树里声明的顺序 —— "
                            + "它是 prompt 前缀的一部分，重排一次前缀就整段未命中"
                            + "（cache-hit-input 0.02 vs input 1.0）")
                    .containsExactly("compare_prices", "recommend_products", "search_products");
            assertThat(first.systemPrompt())
                    .as("★★★ 这一句才是混合轮的定义：工具轮手上【也】有知识库切片。"
                            + "少了它，这一次就退化成一次普通的工具调用 —— "
                            + "而回答读起来完全正常")
                    .contains(KB_TEXT);

            // ── 证据四：响应里要带上引用 ──
            assertThat(response.references())
                    .as("★★ 切片进了 prompt，模型就会在正文里写「[1]」—— "
                            + "而 references 写死 null 的话，前端「引用」那一栏是空的，"
                            + "回答里却挂着指向不存在的编号")
                    .singleElement()
                    .satisfies(ref -> assertThat(ref.get("no")).isEqualTo(1));
        }

        @Test
        @DisplayName("★★ 第二跳的 system prompt 逐字不变（前缀缓存），工具结果走【消息】")
        void systemPromptIsStableAcrossRounds() {
            classifyAs("SCENARIO_PICK", true);
            retrievalReturnsKnowHow();
            scriptToolThenAnswer("search_products", "{\"keyword\":\"手机\",\"limit\":2}",
                    "给长辈挑的话，操作简单最重要。");
            seen.clear();

            chatService.ask(new ChatAskRequest(null, "有什么适合送长辈的", null), USER_ID);

            assertThat(seen).as("这个剧本应当有两跳").hasSize(2);
            assertThat(seen.get(1).systemPrompt())
                    .as("★★ system prompt 在整个往返里一个字都不能变 —— "
                            + "工具结果是以【消息】挂上去的，不是拼进 system prompt。"
                            + "中间改一次，之后每一轮的输入都整段未命中（差 50 倍）")
                    .isEqualTo(seen.get(0).systemPrompt());
            assertThat(seen.get(1).history())
                    .as("★ 工具结果要作为一条消息喂回去，模型才知道工具说了什么")
                    .anySatisfy(turn -> assertThat(turn.content()).contains("手机"));
        }

        @Test
        @DisplayName("★ 账本里记的是【意图树声明的】工具名，报文里是【注册表顺序】的 —— 两处都对")
        void ledgerRecordsTheDeclarationNotTheWire() throws Exception {
            classifyAs("SCENARIO_PICK", true);
            retrievalReturnsKnowHow();
            scriptToolThenAnswer("search_products", "{\"keyword\":\"手机\",\"limit\":2}", "好的。");
            seen.clear();

            var response = chatService.ask(
                    new ChatAskRequest(null, "有什么适合送长辈的", null), USER_ID);

            Map<String, Object> plan = planOf(qaLogOf(response.traceId()));
            assertThat(plan.get("tools"))
                    .as("★★ 记的是【声明】（意图树里写的顺序）—— 事后要回答的问题是"
                            + "「这一次它【本该】能用哪些工具」，不是「服务端碰巧怎么排的」。"
                            + "看到两处顺序不一样时，别去「修」成一致")
                    .isEqualTo(List.of("search_products", "compare_prices", "recommend_products"));
            assertThat(plan.get("retrieve")).isEqualTo(true);
            // ★ v=3 起（9.4 加了 resumed 一格）。★ 混合轮【不是】恢复轮：
            //   它前面那一轮不是澄清，所以这一格必须是 false
            assertThat(plan.get("v")).isEqualTo(3);
            assertThat(plan.get("resumed")).isEqualTo(false);
        }
    }

    // ============================================================
    // 二、★★ 反面对照：同一顶层下的两个叶子，工具清单不一样
    // ============================================================

    @Nested
    @DisplayName("二、★★ 对照：工具清单挂在【叶子】上，不是挂在顶层上")
    class PerLeafBinding {

        @Test
        @DisplayName("★★ SPEC_QUERY（没声明工具）→ 检索照跑，但一件工具都不给")
        void kbLeafWithoutToolsGetsNone() {
            classifyAs("SPEC_QUERY", true);
            retrievalReturnsKnowHow();
            scriptPlainAnswer("屏幕 6.7 英寸，电池 5000mAh。");
            seen.clear();

            chatService.ask(new ChatAskRequest(null, "这款屏幕多大", null), USER_ID);

            verify(retrievalPipeline, times(1))
                    .retrieve(anyString(), any(), any(RetrievalTrace.class));
            assertThat(seen.get(0).tools())
                    .as("★★ 没有这一条，「混合轮有三件工具」可能只是「所有 KB 轮都有」。"
                            + "而 tools 挂在【叶子】上：同一个顶层 PURCHASE_ADVICE 下，"
                            + "SCENARIO_PICK 有三件、PURCHASE_TIMING 一件都没有")
                    .isNull();
        }

        @Test
        @DisplayName("★★★ ORDER_LOGISTICS → 拿到的是【它自己那三件】，不是搜索那三件")
        void toolIntentGetsItsOwnThree() throws Exception {
            // ★ 故意让模型说「要检索」——工具类意图根本不读这个开关
            classifyAs("ORDER_LOGISTICS", true);
            scriptToolThenAnswer("query_order_status", "{\"order_no\":\"" + HIS_ORDER + "\"}",
                    "您的订单已取消。");
            seen.clear();

            var response = chatService.ask(
                    new ChatAskRequest(null, "我的订单到哪了", null), USER_ID);

            assertThat(toolNames(seen.get(0)))
                    .as("★★★ 注册表从 3 件长到 6 件之后，这里是【最容易静默变坏】的一处："
                            + "把 tools 写成「全都给」的话，挑礼物那一轮就能查订单和券 —— "
                            + "而回答读起来依然正常，没有任何指标会红")
                    .containsExactly("query_inventory", "query_my_coupons", "query_order_status");
            assertThat(toolNames(seen.get(0))).doesNotContain("search_products");

            // ★ 工具意图不检索 —— 模型说的 retrieve=true 不算数（ADR-044）
            verify(retrievalPipeline, never())
                    .retrieve(anyString(), any(), any(RetrievalTrace.class));
            QaLog log = qaLogOf(response.traceId());
            assertThat(log.getRetrievalDetail())
                    .as("★ 必须写 NULL（= 检索确实没发生），不能是空对象")
                    .isNull();
            assertThat(toolCallsOf(log)).singleElement()
                    .satisfies(call -> assertThat(call.get("tool")).isEqualTo("query_order_status"));
            assertThat(response.references())
                    .as("★ 纯工具轮【没有】引用 —— 这是上面那条「混合轮有引用」的对照组："
                            + "两处之差只有「有没有检索」，所以 references 的判据"
                            + "只有一种解释：chunks 是不是空的")
                    .isNull();
        }
    }

    // ============================================================
    // 三、★ 流式路径（前端只走这一条）
    // ============================================================

    @Nested
    @DisplayName("三、★ 流式路径上的混合轮")
    class StreamingHybrid {

        /** 收下 onStart / onDelta / onComplete / onError */
        private final class CapturingSink implements ChatService.ChatStreamSink {
            final StringBuilder body = new StringBuilder();
            ChatAskResponse done;

            @Override
            public void onStart(String traceId, String sessionNo) {
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
            }
        }

        @Test
        @DisplayName("★★ 流式混合轮：正文推出来了、检索跑了、引用也在 done 里")
        void streamHybridKeepsReferences() throws Exception {
            classifyAs("SCENARIO_PICK", true);
            retrievalReturnsKnowHow();
            scriptToolThenAnswer("search_products", "{\"keyword\":\"手机\",\"limit\":2}",
                    "给长辈挑的话，操作简单最重要。");
            seen.clear();
            CapturingSink sink = new CapturingSink();

            chatService.askStream(new ChatAskRequest(null, "有什么适合送长辈的", null), sink,
                    USER_ID, CallContext.fresh("t-hybrid-stream"));

            assertThat(sink.body.toString()).contains("操作简单最重要");
            assertThat(sink.done).as("应该收到 done 事件").isNotNull();
            // ★ 流式那两条老规矩不变：正文只在 delta 里，done.answer 是 null
            assertThat(sink.done.answer()).as("★ 流式契约").isNull();

            // ★★ 这一条是本类新增的：两个 builder 是平行代码，
            //    「只改了非流式那一条」编译能过、测试能绿（这正是 9.1 栽过的跟头）
            assertThat(sink.done.references())
                    .as("★★ 混合轮的引用在【流式】路径上也必须带出来 —— "
                            + "前端只走流式，漏了这里等于前端永远看不到引用")
                    .hasSize(1);

            QaLog log = qaLogOf("t-hybrid-stream");
            assertThat(log.getRetrievalDetail())
                    .as("★ 流式混合轮的账本形状和非流式一致：检索列有值 ∧ tool_calls 非空")
                    .isNotNull();
            assertThat(toolCallsOf(log)).singleElement()
                    .satisfies(call -> assertThat(call.get("tool")).isEqualTo("search_products"));
        }
    }
}
