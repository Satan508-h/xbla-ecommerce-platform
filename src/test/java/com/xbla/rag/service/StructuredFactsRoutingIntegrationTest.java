package com.xbla.rag.service;

import com.xbla.rag.agent.intent.IntentClassification;
import com.xbla.rag.agent.intent.LlmIntentClassifier;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.rag.RetrievalPipeline;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「意图 → 结构化事实」这条路由（阶段 5.9）。
 *
 * <h3>这个测试里什么是真的、什么是桩</h3>
 *
 * <table border="1">
 *   <caption>真假对照</caption>
 *   <tr><th>组件</th><th>真假</th><th>替代成什么</th></tr>
 *   <tr><td>{@code ChatModelRouter}</td><td><b>桩</b></td>
 *       <td>★ 但把发给它的 {@code ChatRequest} <b>捕获下来</b> —— 断言就在那上面</td></tr>
 *   <tr><td>{@code LlmIntentClassifier}</td><td><b>桩</b></td>
 *       <td>按用例返回不同的意图码</td></tr>
 *   <tr><td>{@code RetrievalPipeline}</td><td><b>桩</b></td>
 *       <td>返回空 —— 本测试不关心召回，只关心 prompt 里多了什么</td></tr>
 *   <tr><td><b>意图树</b></td><td><b>真</b></td>
 *       <td>— 读的是 {@code data/agent/intent-tree.yml}，
 *           {@code structured_facts: POLICY} 就写在里面</td></tr>
 *   <tr><td><b>政策表</b></td><td><b>真</b></td>
 *       <td>— 查的是库里真实的 {@code after_sale_policy}</td></tr>
 * </table>
 *
 * <h3>★★ 为什么要专门测这一层</h3>
 *
 * <p>下层（{@code PolicyFactProvider}）和上层（{@code RagPromptBuilder}）都各有单测，
 * 但把它们接起来的那<b>一条查表路由</b>——「拿到意图码 → 查树 → 决定查不查表」
 * ——两边的测试都覆盖不到。它坏掉的方式是<b>静默</b>的：
 * prompt 里少一节，模型照样答得头头是道，只是数字可能是从散文里读错的。
 *
 * <p>所以核心断言是那个<b>正-反对照</b>：
 * <pre>
 *   同一个问题，只把分类结果从 RETURN_EXCHANGE 改成 COUPON
 *     → 前者 prompt 里有【售后政策硬数据】
 *     → 后者 prompt 里【没有】
 * </pre>
 * 只断言前者的话，「不管什么意图都无脑注入」也能让测试绿 ——
 * 而那会让每一次知识库问答都白背 7 行政策。
 */
@SpringBootTest(properties = {
        // ★ 关掉会话记忆与摘要：摘要是【异步】后台任务，
        //   它会在线程池里读写数据库，和测试方法的事务回滚撞在一起
        //   —— 症状是随机失败。同 ToolChatIntegrationTest
        "xbla.chat.history.enabled=false",
        "xbla.chat.summary.enabled=false"
})
@Transactional
@DisplayName("ChatService · 结构化事实的注入路由")
class StructuredFactsRoutingIntegrationTest {

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    private static final String HEADER = "【售后政策硬数据】";

    @Autowired
    private ChatService chatService;

    @Autowired
    private com.xbla.rag.service.AfterSalePolicyService afterSalePolicyService;

    @MockitoBean
    private ChatModelRouter router;

    @MockitoBean
    private LlmIntentClassifier intentClassifier;

    @MockitoBean
    private RetrievalPipeline retrievalPipeline;

    @BeforeEach
    void stubModel() {
        // ★ 模型只回一句正文 —— 本测试不关心它答了什么
        when(router.chat(any(), any())).thenAnswer(inv -> {
            var trace = (com.xbla.rag.client.ModelCallTrace) inv.getArgument(1);
            ChatUsage usage = new ChatUsage(100, 20, 120, null, null, null);
            trace.succeeded(DESCRIPTOR, usage, 8);
            trace.cost(new BigDecimal("0.000200"));
            return ChatResponse.text("好的。", "stop", usage, DESCRIPTOR, 8);
        });
        when(retrievalPipeline.retrieve(any(), any(), any())).thenReturn(List.of());
    }

    private void classifyAs(String code) {
        when(intentClassifier.classify(any())).thenReturn(new IntentClassification(
                code, IntentClassification.Outcome.CLASSIFIED,
                code, DESCRIPTOR, new BigDecimal("0.0001"), 5, null));
    }

    /** 跑一次问答，把发给模型的 system prompt 拿回来 */
    private String systemPromptFor(String intentCode) {
        classifyAs(intentCode);
        chatService.ask(new ChatAskRequest(null, "退货要几天", null), 8L);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, atLeastOnce()).chat(captor.capture(), any());
        return captor.getValue().systemPrompt();
    }

    @BeforeEach
    void requireSeedData() {
        // 政策表是空的就没有可注入的东西 —— 那时 contains(HEADER) 的断言
        // 会以一个和被测量行为无关的原因失败
        Assumptions.assumeTrue(afterSalePolicyService.count() > 0,
                "库里没有售后政策，跳过（需要阶段 1 的种子数据）");
    }

    // ============================================================
    // 一、★ 正-反对照
    // ============================================================

    @Test
    @DisplayName("★★ 退换货意图 → prompt 里有政策硬数据")
    void returnExchangeGetsFacts() {
        String prompt = systemPromptFor("RETURN_EXCHANGE");

        assertThat(prompt)
                .as("★★ 这一段就是 5.9 的交付物：让「退货几天」不再靠模型从散文里读")
                .contains(HEADER);
        assertThat(prompt).contains("退货 7 天");
    }

    @Test
    @DisplayName("★★ 正-反对照：同一个问题、只把意图换成优惠券 → prompt 里【没有】硬数据")
    void couponIntentGetsNoFacts() {
        String prompt = systemPromptFor("COUPON");

        assertThat(prompt)
                .as("★★ 无脑注入的话这一条也会含 HEADER —— "
                        + "而那意味着每一次知识库问答都白背 7 行无关政策")
                .doesNotContain(HEADER);
        // ★ 但检索照常跑 —— 注入与否不该影响路由
        assertThat(prompt).contains("本次未从平台知识库中检索到相关内容");
    }

    @Test
    @DisplayName("★ 兄弟叶子不受影响：退款（同在售后服务下）没有硬数据")
    void refundHasNoFacts() {
        assertThat(systemPromptFor("REFUND"))
                .as("★ REFUND 也是售后服务的一个叶子 —— "
                        + "如果硬数据是【顶层】粒度，这一条会红。"
                        + "它是叶子粒度的，而只有 RETURN_EXCHANGE 声明了 POLICY")
                .doesNotContain(HEADER);
    }

    // ============================================================
    // 二、★ 分类失败 / 工具意图时不注入
    // ============================================================

    @Test
    @DisplayName("★ 分类失败（模型编了个不存在的 code）→ 不注入，也不抛异常")
    void unclassifiedGetsNoFacts() {
        when(intentClassifier.classify(any())).thenReturn(new IntentClassification(
                "LEAF_THAT_DOES_NOT_EXIST", IntentClassification.Outcome.UNKNOWN_CODE,
                "LEAF_THAT_DOES_NOT_EXIST", DESCRIPTOR, new BigDecimal("0.0001"), 5, null));

        chatService.ask(new ChatAskRequest(null, "退货要几天", null), 8L);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, atLeastOnce()).chat(captor.capture(), any());
        assertThat(captor.getValue().systemPrompt())
                .as("查询一个不存在的 code 会返回 NONE —— 不能拿它当 POLICY")
                .doesNotContain(HEADER);
    }
}
