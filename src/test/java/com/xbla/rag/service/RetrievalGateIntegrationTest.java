package com.xbla.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.agent.intent.IntentClassification;
import com.xbla.rag.agent.intent.IntentPlan;
import com.xbla.rag.agent.intent.LlmIntentClassifier;
import com.xbla.rag.agent.intent.RetrievalGate;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.dto.ChatAskRequest;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>阶段 9.2 的验收</b>：检索门控在<b>真实链路上</b>真的起作用。
 *
 * <h2>★★ 为什么需要它 —— 纯函数的测试证明不了「接上了」</h2>
 *
 * <p>{@code RetrievalGateTest} 证明了那张门控表是对的，
 * 但它证明不了<b>调用点真的用了它的结论</b>。而那正是本项目最典型的失败形态：
 *
 * <pre>
 *   判断算对了，调用点忘了用它 → 一切「看起来正常」，没有任何指标会红
 *   （同 ADR-081：标记写对了，但某个构造点漏了）
 * </pre>
 *
 * <p>所以这个测试从 {@code chatService.ask()} 进去，断言的是
 * <b>下游到底有没有跑检索</b>（{@code verify(retrievalPipeline, never())}）
 * 和 <b>库里那几列的形状</b>。
 *
 * <h2>★★★★ 那个免费的 2×2 一致性判据</h2>
 *
 * <pre>
 *   intent_plan.retrieve = true   ∧  retrieval_detail IS NOT NULL   → 正常
 *   intent_plan.retrieve = true   ∧  retrieval_detail IS NULL       → ★ 计划要检索却没发生
 *   intent_plan.retrieve = false  ∧  retrieval_detail IS NULL       → 门控生效
 *   intent_plan.retrieve = false  ∧  retrieval_detail IS NOT NULL   → ★★ 门控失效（必须恒为 0）
 * </pre>
 *
 * <p>本类的每一条断言都可以归到上面四格中的一格。
 */
@SpringBootTest(properties = {
        "xbla.chat.history.enabled=false",
        "xbla.chat.summary.enabled=false"
})
@Transactional
@DisplayName("ChatService · 检索门控（阶段 9.2 验收）")
class RetrievalGateIntegrationTest {

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    private static ChatUsage usage() {
        return new ChatUsage(300, 20, 320, null, null, null);
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

    /**
     * ★ 检索必须打桩：真跑一次会调向量化接口 —— <b>那是要花钱的</b>。
     * 本类关心的不是检索质量，而是「检索到底有没有被调用」。
     */
    @MockitoBean
    private RetrievalPipeline retrievalPipeline;

    // ------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------

    /**
     * 让分类返回指定的 code 与计划。
     *
     * @param plan null = 「没有计划」（走 9.2 之前的兼容构造器）
     */
    private void classifyAs(String code, IntentPlan plan) {
        when(intentClassifier.classify(any(), any())).thenReturn(new IntentClassification(
                code, IntentClassification.Outcome.CLASSIFIED, code,
                DESCRIPTOR, new BigDecimal("0.0001"), 5, null, plan));
    }

    private void scriptPlainAnswer() {
        when(router.chat(any(), any())).thenAnswer(inv -> {
            ModelCallTrace trace = inv.getArgument(1);
            trace.succeeded(DESCRIPTOR, usage(), 5);
            trace.cost(new BigDecimal("0.000100"));
            return ChatResponse.text("好的。", "stop", usage(), DESCRIPTOR, 5);
        });
    }

    /** 一次 JSON 契约的计划 */
    private static IntentPlan jsonPlan(boolean retrieve) {
        return new IntentPlan(retrieve, List.of(), IntentPlan.Shape.JSON);
    }

    private QaLog qaLogOf(String traceId) {
        List<QaLog> logs = qaLogMapper.selectList(
                new LambdaQueryWrapper<QaLog>().eq(QaLog::getTraceId, traceId));
        assertThat(logs).as("这次问答应该恰好写一行 qa_log").hasSize(1);
        return logs.get(0);
    }

    /** 把 {@code intent_plan} 解析成结构 —— ★ JSONB 不能比文本 */
    private Map<String, Object> planOf(QaLog log) throws Exception {
        assertThat(log.getIntentPlan()).as("intent_plan 不该为 null").isNotNull();
        return objectMapper.readValue(log.getIntentPlan(),
                new TypeReference<Map<String, Object>>() {
                });
    }

    // ============================================================
    // 一、★★★ 兜底意图：那个记了两个阶段的已知不一致
    // ============================================================

    @Nested
    @DisplayName("一、★★★ OUT_OF_SCOPE 终于真的不检索了")
    class NoneIntent {

        @Test
        @DisplayName("★★★ 「你好」不再跑检索 —— 而回复仍然由模型生成")
        void outOfScopeSkipsRetrieval() throws Exception {
            classifyAs("OUT_OF_SCOPE", jsonPlan(true));   // ★ 即便模型说「要检索」
            scriptPlainAnswer();

            var response = chatService.ask(new ChatAskRequest(null, "你好呀", null), null);

            // ★★ 最硬的那条证据：检索管道一次都没被调用
            verify(retrievalPipeline, never()).retrieve(any(), any(), any());

            QaLog log = qaLogOf(response.traceId());
            assertThat(log.getRetrievalDetail())
                    .as("★ 必须写 NULL（= 检索确实没发生），不能是一个空对象"
                            + "（那和「检索跑了但两路都没召回」逐字相同）")
                    .isNull();
            assertThat(log.getRetrievalLatencyMs()).isNull();
            assertThat(log.getReferences()).isNull();

            // ★ 但它仍然【答了】：这不是失败，是「不检索地答」
            assertThat(log.getStatus()).isEqualTo(QaLog.STATUS_SUCCESS);
            assertThat(log.getProvider()).as("生成确实发生了").isEqualTo("deepseek");
            assertThat(response.answer()).isEqualTo("好的。");

            // ★★ 2×2 的第三格
            Map<String, Object> plan = planOf(log);
            assertThat(plan.get("retrieve")).isEqualTo(false);
            assertThat(plan.get("gate")).isEqualTo(RetrievalGate.REASON_NONE_INTENT);
        }

        @Test
        @DisplayName("★★ intent_plan 的八格全都在，且 shape 被记下来")
        void planHasAllEightFields() throws Exception {
            classifyAs("OUT_OF_SCOPE", jsonPlan(true));
            scriptPlainAnswer();

            var response = chatService.ask(new ChatAskRequest(null, "帮我写首诗", null), null);
            Map<String, Object> plan = planOf(qaLogOf(response.traceId()));

            // ★ 八格一个都不能少 —— 每一格都对应一个事后必须能回答的问题
            //   （第 7 格 tools 是阶段 9.3 加的、第 8 格 resumed 是 9.4 加的，
            //     v 跟着从 1 → 2 → 3）
            assertThat(plan)
                    .containsKeys("v", "intent", "retrieve", "gate", "tools", "missing",
                            "shape", "resumed")
                    .containsEntry("v", 3)
                    .containsEntry("intent", "OUT_OF_SCOPE")
                    .containsEntry("gate", RetrievalGate.REASON_NONE_INTENT);
            // ★ 阶段 9.4：这一轮【没有】上一轮悬着的澄清（第一次提问），
            //   所以 resumed 必须是 false。它恒为 true 的话，
            //   「恢复路径到底触发过几次」就答不出来了 —— 而那是这一格唯一的用途
            assertThat(plan.get("resumed"))
                    .as("★ 第一次提问没有上一轮，resumed 必须是 false")
                    .isEqualTo(false);
            // ★ OUT_OF_SCOPE 的工具清单必须是空的：它既不检索也不调工具。
            //   非空会让「按意图裁剪」在这一类上静默失效
            assertThat(plan.get("tools"))
                    .as("NONE 类意图的工具清单必须是空数组，不能是 null 也不能有东西")
                    .isEqualTo(List.of());
            // ★ 模型说的是 true（我们就是这么桩的），而生效的是 false ——
            //   这一对差别正是「记结论而不是记原话」的证据
            assertThat(plan.get("retrieve"))
                    .as("★★ 记的是【生效的结论】。记模型的原话会得到 true，"
                            + "而那会让「retrieve=true 却没检索」看起来像 bug")
                    .isEqualTo(false);
            assertThat(plan.get("shape"))
                    .as("★★★ 「门控到底有没有生效」的唯一判据 —— "
                            + "契约没生效时门控一次都不会生效，而没有任何别的东西会异常")
                    .isEqualTo("JSON");
        }
    }

    // ============================================================
    // 二、★★ KB 意图：模型说不用检索
    // ============================================================

    @Nested
    @DisplayName("二、★★ KB 意图 + 模型说「不用检索」")
    class ModelTurnsItOff {

        @Test
        @DisplayName("★★ retrieve=false → 不检索（这就是「Agent 先判断要不要检索」）")
        void modelCanTurnRetrievalOff() throws Exception {
            classifyAs("SPEC_QUERY", jsonPlan(false));
            scriptPlainAnswer();

            var response = chatService.ask(new ChatAskRequest(null, "谢谢你", null), null);

            verify(retrievalPipeline, never()).retrieve(any(), any(), any());

            QaLog log = qaLogOf(response.traceId());
            assertThat(log.getRetrievalDetail()).isNull();
            assertThat(log.getStatus())
                    .as("★ 不检索 ≠ 失败。这一格证明「没有检索」和「模型挂了」是两件事")
                    .isEqualTo(QaLog.STATUS_SUCCESS);

            Map<String, Object> plan = planOf(log);
            assertThat(plan.get("retrieve")).isEqualTo(false);
            assertThat(plan.get("gate")).isEqualTo(RetrievalGate.REASON_PLAN_OFF);
        }

        @Test
        @DisplayName("★★ 反对照：同样的 KB 意图，retrieve=true → 真的会检索")
        void kbWithRetrieveTrueDoesRetrieve() throws Exception {
            classifyAs("SPEC_QUERY", jsonPlan(true));
            scriptPlainAnswer();

            var response = chatService.ask(new ChatAskRequest(null, "这款手机怎么样", null), null);

            // ★★ 这一条是上面那条的【前提】：没有它，
            //    「retrieve=false 时不检索」也可能只是因为这条链路从来不检索
            verify(retrievalPipeline, times(1)).retrieve(any(), any(), any());

            Map<String, Object> plan = planOf(qaLogOf(response.traceId()));
            assertThat(plan.get("retrieve")).isEqualTo(true);
            assertThat(plan.get("gate")).isEqualTo(RetrievalGate.REASON_KB);
        }
    }

    // ============================================================
    // 三、★★★ 落库的形状（那四格 2×2 的前两格）
    // ============================================================

    @Nested
    @DisplayName("三、★★★ intent_plan 的四格一致性")
    class Consistency {

        @Test
        @DisplayName("★★★ retrieve=false ∧ retrieval_detail IS NULL（门控生效）")
        void offMeansNoRetrieval() throws Exception {
            classifyAs("SPEC_QUERY", jsonPlan(false));
            scriptPlainAnswer();

            var response = chatService.ask(new ChatAskRequest(null, "好的谢谢", null), null);
            QaLog log = qaLogOf(response.traceId());

            assertThat(planOf(log).get("retrieve")).isEqualTo(false);
            assertThat(log.getRetrievalDetail()).isNull();
        }

        @Test
        @DisplayName("★★★ retrieve=true ∧ retrieval_detail IS NOT NULL（正常）")
        void onMeansRetrieval() throws Exception {
            classifyAs("SPEC_QUERY", jsonPlan(true));
            scriptPlainAnswer();

            var response = chatService.ask(new ChatAskRequest(null, "这款手机怎么样", null), null);
            QaLog log = qaLogOf(response.traceId());

            assertThat(planOf(log).get("retrieve")).isEqualTo(true);
            assertThat(log.getRetrievalDetail())
                    .as("★ 检索跑了就必须有 detail —— 这一格反过来就是「计划要检索却没发生」，"
                            + "而那说明调用点忘了用门控的结论")
                    .isNotNull();
        }

        @Test
        @DisplayName("★ 分类失败 → 退化成全池检索，且 intent_plan 为 NULL（没有计划可言）")
        void failedClassificationStillRetrieves() {
            when(intentClassifier.classify(any(), any())).thenReturn(new IntentClassification(
                    null, IntentClassification.Outcome.CALL_FAILED,
                    null, DESCRIPTOR, null, 5, "超时"));
            scriptPlainAnswer();

            var response = chatService.ask(new ChatAskRequest(null, "随便问一句", null), null);

            verify(retrievalPipeline, times(1)).retrieve(any(), any(), any());

            QaLog log = qaLogOf(response.traceId());
            assertThat(log.getIntentPlan())
                    .as("★ 分类失败 = 没有产出计划。写 '{}' 会让"
                            + "「没发生」和「发生了但是空的」分不开")
                    .isNull();
            assertThat(log.getIntent()).isNull();
            assertThat(log.getRetrievalDetail()).as("★ 但检索照跑，不引入回归").isNotNull();
        }
    }
}
