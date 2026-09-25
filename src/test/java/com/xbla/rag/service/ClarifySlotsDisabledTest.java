package com.xbla.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.entity.ChatSession;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.rag.RetrievalPipeline;
import com.xbla.rag.rag.RetrievalTrace;
import com.xbla.rag.rag.retrieve.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>阶段 9.4 的对照组</b>：槽位功能关掉时，一切必须回到 9.3。
 *
 * <h2>★★★ 这一类为什么必须单独存在</h2>
 *
 * <p>它不是「顺手也测一下开关」——<b>它是那个 A/B 的另一半</b>。
 * 没有它，「多轮澄清有没有让追问不再被驳回」这个结论就<b>无法归因</b>：
 * 两组的差别到底是什么，只能靠「关掉之后一切逐字节回到从前」来证明。
 *
 * <p>三条判据（缺一条这个对照组就不成立）：
 *
 * <pre>
 *   ① 反问文案 = 9.3 那一句固定的（不是按槽位选的）
 *   ② chat_session.pending_clarify 【不写】
 *   ③ 分类 prompt 里【没有】那一段；qa_log.intent_plan.resumed 恒为 false
 * </pre>
 *
 * <p>★ 第三条尤其容易漏：开关只写一半的话（比如只关了写入，
 * 没关「标 resumed」），库里会出现「resumed=true 而 prompt 里根本没那段」的行 ——
 * 而那一格的全部用途就是统计恢复路径触发了几次。
 */
@SpringBootTest(properties = {
        "xbla.chat.history.enabled=false",
        "xbla.chat.summary.enabled=false",
        // ★★ 这就是被测量的那一个开关
        "xbla.agent.slots.enabled=false"
})
@Transactional
@DisplayName("ChatService · 槽位开关关掉 = 9.3 的行为（阶段 9.4 对照组）")
class ClarifySlotsDisabledTest {

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    /** 9.3 那句固定文案里的特征词（{@code AgentProperties.Intent#clarifyText}） */
    private static final String FIXED_TEXT_MARK = "哪款商品";

    /**
     * 分类契约：模型报缺 budget。
     *
     * <p>★ 这一句是关键 —— <b>开关关掉时，模型报什么都不会改变反问的内容</b>。
     * 用一句「缺 budget」去问，而回答里出现的是「哪款商品」，
     * 这才是「按槽位选文案」那一半确实被关掉了的证据。
     */
    private static final String PLAN_JSON =
            "{\"v\":1,\"intent\":\"NEEDS_CLARIFICATION\",\"retrieve\":true,"
                    + "\"missing\":[\"budget\"]}";

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
    private RetrievalPipeline retrievalPipeline;

    private static ChatResponse reply(String content) {
        return ChatResponse.text(content, "stop", null, DESCRIPTOR, 10);
    }

    private ChatSession sessionOf(String sessionNo) {
        return chatSessionService.lambdaQuery()
                .eq(ChatSession::getSessionNo, sessionNo)
                .one();
    }

    private QaLog qaLogOf(String traceId) {
        List<QaLog> rows = qaLogMapper.selectList(
                new LambdaQueryWrapper<QaLog>().eq(QaLog::getTraceId, traceId));
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private Map<String, Object> planOf(QaLog log) {
        try {
            return objectMapper.readValue(log.getIntentPlan(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new AssertionError("intent_plan 不是合法 JSON：" + e.getMessage(), e);
        }
    }

    @Test
    @DisplayName("★★★ ①② 关掉时：反问仍是固定文案，且不写 pending_clarify")
    void disabledKeepsTheOldBehaviour() {
        when(router.chat(any(), any())).thenReturn(reply(PLAN_JSON));

        ChatAskResponse response = chatService.ask(
                new ChatAskRequest("s-off-1", "那个怎么样", null), null);

        assertThat(response.intent()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(response.answer())
                .as("★ 关掉时用的是 9.3 那句固定的 —— 模型说缺 budget 也不理会")
                .contains(FIXED_TEXT_MARK)
                .doesNotContain("预算");
        assertThat(sessionOf("s-off-1").getPendingClarify())
                .as("★★ 不写状态：只关一半的话，chat_session 会攒下永远没人读的值")
                .isNull();
    }

    @Test
    @DisplayName("★★★ ③ 关掉时：分类 prompt 里没有那一段，resumed 恒为 false")
    void disabledNeverResumes() {
        when(router.chat(any(), any())).thenReturn(reply(PLAN_JSON));
        chatService.ask(new ChatAskRequest("s-off-2", "那个怎么样", null), null);

        // 手工塞一份状态进去（模拟：开关曾经开着，留下的残留）
        chatSessionService.lambdaUpdate()
                .eq(ChatSession::getId, sessionOf("s-off-2").getId())
                .set(ChatSession::getPendingClarify,
                        "{\"v\":1,\"question\":\"那个怎么样\",\"slots\":[\"budget\"],"
                                + "\"asked\":\"budget\"}")
                .update();

        when(router.chat(any(), any())).thenReturn(
                reply("{\"v\":1,\"intent\":\"SPEC_QUERY\",\"retrieve\":true,\"missing\":[]}"),
                reply("这一款是……"));
        when(retrievalPipeline.retrieve(anyString(), any(), any(RetrievalTrace.class)))
                .thenReturn(List.<RetrievedChunk>of());

        ChatAskResponse response = chatService.ask(
                new ChatAskRequest("s-off-2", "送长辈", null), null);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, org.mockito.Mockito.atLeast(2)).chat(captor.capture(), any(ModelCallTrace.class));
        String prompt = captor.getAllValues().stream()
                .map(ChatRequest::systemPrompt)
                .filter(p -> p != null && p.startsWith("你是电商问答平台的意图分类器"))
                .reduce((a, b) -> b)                       // ★ 最后一次分类
                .orElseThrow();

        assertThat(prompt)
                .as("★★ 「关掉 = 与 9.3 逐字节相同」—— 即使库里有一份状态也不许注入")
                .doesNotContain("## 上一轮的澄清");
        assertThat(planOf(qaLogOf(response.traceId())))
                .as("★★★ 这一格尤其容易写漏：只关了注入、没关标 resumed 的话，"
                        + "库里会出现「resumed=true 而 prompt 里没有那一段」的行")
                .containsEntry("resumed", false);

        assertThat(sessionOf("s-off-2").getPendingClarify())
                .as("★ 残留仍然会被清掉（housekeeping）—— 不然开关重新打开的那天它会突然生效")
                .isNull();
    }
}
