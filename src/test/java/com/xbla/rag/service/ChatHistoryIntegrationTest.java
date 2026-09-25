package com.xbla.rag.service;

import com.xbla.rag.agent.intent.IntentClassification;
import com.xbla.rag.agent.intent.LlmIntentClassifier;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.config.ChatProperties;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;
import com.xbla.rag.rag.RetrievalPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

/**
 * 滑动窗口记忆接进 {@code ChatService} 的集成测试（阶段 5.5）。
 *
 * <p><b>不花一分钱</b>：{@code LlmIntentClassifier}、{@code ChatModelRouter}、
 * {@code RetrievalPipeline} 全是确定性桩。分类被换掉之后，
 * {@code router.chat} <b>每次 ask 只被调用一次</b>（生成那一次）——
 * 这让「抓哪个请求」这件事变得没有歧义。
 *
 * <h3>★ 本类真正要证明的三件事</h3>
 *
 * <ol>
 *   <li><b>历史真的被拼进了请求</b> —— 不是「读了但忘了传」。
 *       这个错从返回值上完全看不出来：回答照样生成、库照样落，
 *       只是模型没看到上文。</li>
 *   <li><b>★ 本轮的提问【不在】历史里</b> —— 这是 5.5 最容易踩的坑。
 *       {@code saveUserMessage} 和「读历史」的相对顺序错了，
 *       模型就会收到两条一模一样的用户消息，而两边各自看都是对的。</li>
 *   <li><b>历史约束只在真的有历史时出现</b> —— 一轮问答配一句
 *       「历史仅供参考」是纯粹的噪音。</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@DisplayName("ChatService · 滑动窗口会话记忆")
class ChatHistoryIntegrationTest {

    /**
     * 调一次问答，<b>不带身份</b>。
     *
     * <p>★ 这些用例测的是意图分类和会话记忆，和「我是谁」无关 ——
     * 所以统一传 {@code null}。写成帮手是为了让这个选择<b>只出现一次</b>，
     * 而不是在二十个调用点各写一个 {@code , null}（那样既嘈杂，
     * 又让人以为是随手补的参数）。
     *
     * <p>⚠️ 带身份的工具路径用例<b>不在这里</b> —— 见
     * {@code ToolChatIntegrationTest}，那边会传真实的 userId。
     * 两条路径的分工写在各自的类注释里，不要在这里顺手加一个重载。
     */
    private ChatAskResponse ask(ChatAskRequest request) {
        return chatService.ask(request, null);
    }


    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    private static final String BASE_ANSWER = "自签收之日起 7 天内可以申请无理由退货。";

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatProperties chatProperties;

    @MockitoBean
    private ChatModelRouter router;

    @MockitoBean
    private LlmIntentClassifier intentClassifier;

    @MockitoBean
    private RetrievalPipeline retrievalPipeline;

    /**
     * 抓<b>最后一次</b>发给生成模型的请求。
     *
     * <p>⚠️ <b>不能用 {@code verify(router, times(1))}</b>：Mockito 的调用次数是
     * <b>累计</b>的，而本类里好几个用例在同一个测试方法内调了多次 {@code ask()}
     * （第一轮 + 第二轮）—— 于是第二次抓取会看到 <b>2</b> 次调用而失败。
     *
     * <p>取「最后一次」也正是需要的那一个：前面的 ask 都是为最后那一轮铺垫历史的。
     */
    private ChatRequest lastGenerationRequest() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router, atLeastOnce()).chat(captor.capture(), any(ModelCallTrace.class));
        return captor.getValue();
    }

    /** 让分类器固定返回一个业务意图（不澄清、不过滤） */
    private void stubBusinessIntent() {
        when(intentClassifier.classify(anyString(), any())).thenReturn(new IntentClassification(
                "RETURN_EXCHANGE", IntentClassification.Outcome.CLASSIFIED,
                "RETURN_EXCHANGE", DESCRIPTOR, null, 10L, null));
        when(router.chat(any(), any(ModelCallTrace.class)))
                .thenReturn(ChatResponse.text(BASE_ANSWER, "stop", null, DESCRIPTOR, 10));
        when(retrievalPipeline.retrieve(anyString(), any(), any()))
                .thenReturn(List.of());
    }

    // ============================================================
    // 一、★ 第一轮没有历史
    // ============================================================

    @Nested
    @DisplayName("一、★ 第一轮没有历史")
    class FirstTurn {

        @Test
        @DisplayName("新会话第一轮：history 为空，system prompt 里【没有】历史约束")
        void noHistoryOnFirstTurn() {
            stubBusinessIntent();

            ask(new ChatAskRequest(null, "退货要几天", null));

            ChatRequest request = lastGenerationRequest();

            assertThat(request.history())
                    .as("★ 空历史必须是空列表，不是 null —— toWireMessages 对 null 和空列表"
                            + "的处理碰巧一样，但「契约是空列表」这件事应当在类型上可见")
                    .isNotNull()
                    .isEmpty();
            assertThat(request.systemPrompt())
                    .as("★ 第一轮加一句「历史回答仅供参考」是纯噪音，"
                            + "还会稀释其他指令的权重")
                    .doesNotContain("关于对话历史");
        }
    }

    // ============================================================
    // 二、★★ 第二轮带上一轮
    // ============================================================

    @Nested
    @DisplayName("二、★★ 第二轮带上一轮")
    class SecondTurn {

        @Test
        @DisplayName("★ 第二轮：history 是上一轮的一问一答，且顺序是 user → assistant")
        void secondTurnCarriesPreviousTurn() {
            stubBusinessIntent();
            String sessionNo = null;            // 第一轮没有会话号

            ChatAskResponse first = ask(
                    new ChatAskRequest(null, "退货要几天", null));

            // 用第一轮返回的会话号问第二轮
            ask(new ChatAskRequest(first.sessionNo(), "那退款呢", null));

            ChatRequest request = lastGenerationRequest();

            assertThat(request.history())
                    .as("★ 这条是「历史真的被拼进请求」的核心证据。"
                            + "读了但忘了传的话，回答照样生成、库照样落，"
                            + "返回值上看不出任何异常 —— 只是模型没看到上文")
                    .containsExactly(
                            new ChatRequest.Turn(ChatRequest.Role.USER, "退货要几天"),
                            new ChatRequest.Turn(ChatRequest.Role.ASSISTANT, BASE_ANSWER));

            assertThat(request.systemPrompt())
                    .as("★ 有历史时才加约束 —— 它和上面的 history 是配套的："
                            + "回放给模型的只有问答文本，当时的检索资料不在里面，"
                            + "所以必须明确「事实以本次资料为准」")
                    .contains("关于对话历史");
        }

        @Test
        @DisplayName("★★ 本轮的提问【不在】历史里 —— 顺序护栏")
        void currentQuestionIsNotInHistory() {
            stubBusinessIntent();

            ChatAskResponse first = ask(
                    new ChatAskRequest(null, "退货要几天", null));
            ask(new ChatAskRequest(first.sessionNo(), "那退款呢", null));

            ChatRequest request = lastGenerationRequest();

            assertThat(request.history())
                    .extracting(ChatRequest.Turn::content)
                    .as("★★ 这条断言守的是「读历史必须在 saveUserMessage 之前」这个顺序。"
                            + "反过来的话，历史里会有一份本轮的提问，"
                            + "而它马上又作为 userQuestion 再发一次 —— "
                            + "模型收到【两条一模一样的用户消息】。"
                            + "日志和落库数据两边各自都是对的，"
                            + "所以这个 bug 只能靠「形状」来抓")
                    .doesNotContain("那退款呢");
            assertThat(request.userQuestion())
                    .as("★ 对照：本条提问只出现在 userQuestion 这一处")
                    .isEqualTo("那退款呢");
            assertThat(request.history())
                    .as("★ 而且历史必须以【助手回复】结尾 —— 那是 OpenAI 兼容协议"
                            + "对多轮消息的基本要求")
                    .last()
                    .extracting(ChatRequest.Turn::role)
                    .isEqualTo(ChatRequest.Role.ASSISTANT);
        }

        @Test
        @DisplayName("★ 端到端的消息序列形状正确：system → u,a → u,a → 本轮 user")
        void wireMessagesShapeIsValid() {
            stubBusinessIntent();

            ChatAskResponse first = ask(
                    new ChatAskRequest(null, "退货要几天", null));
            ask(new ChatAskRequest(first.sessionNo(), "那退款呢", null));

            assertThat(lastGenerationRequest().toWireMessages())
                    .extracting(com.xbla.rag.client.dto.WireChatRequest.WireMessage::role)
                    .as("★ 这条验的是【下游真正会发出去的东西】。"
                            + "只看 history 列表的内容看不出「两条连续 user」这种形状问题，"
                            + "而那正是顺序写反的后果")
                    .containsExactly("system", "user", "assistant", "user");
        }

        @Test
        @DisplayName("★ 第四轮时历史里有前三轮（窗口够大就不截断）")
        void multipleTurnsAccumulate() {
            stubBusinessIntent();

            String sessionNo = ask(
                    new ChatAskRequest(null, "问题一", null)).sessionNo();
            sessionNo = ask(
                    new ChatAskRequest(sessionNo, "问题二", null)).sessionNo();
            sessionNo = ask(
                    new ChatAskRequest(sessionNo, "问题三", null)).sessionNo();
            ask(new ChatAskRequest(sessionNo, "问题四", null));

            assertThat(lastGenerationRequest().history())
                    .extracting(ChatRequest.Turn::content)
                    .containsExactly("问题一", BASE_ANSWER,
                            "问题二", BASE_ANSWER,
                            "问题三", BASE_ANSWER);
        }
    }

    // ============================================================
    // 三、★ 澄清轮也进历史
    // ============================================================

    @Test
    @DisplayName("★ 澄清反问那一轮，下一轮看得到（它不是「没发生过」）")
    void clarificationTurnEntersHistory() {
        when(intentClassifier.classify(anyString(), any())).thenReturn(new IntentClassification(
                "NEEDS_CLARIFICATION", IntentClassification.Outcome.CLASSIFIED,
                "NEEDS_CLARIFICATION", DESCRIPTOR, null, 10L, null));
        when(retrievalPipeline.retrieve(anyString(), any(), any())).thenReturn(List.of());

        ChatAskResponse first = ask(
                new ChatAskRequest(null, "那个怎么样", null));

        // 第二轮是正常业务意图
        stubBusinessIntent();
        ask(new ChatAskRequest(first.sessionNo(), "退货要几天", null));

        assertThat(lastGenerationRequest().history())
                .as("★ 澄清的反问确实被落库了（saveAssistantClarification），"
                        + "所以它就是这一轮助手说过的话 —— 不放进历史的话，"
                        + "用户下一轮说「就那个手机」，模型完全不知道"
                        + "系统刚刚问过他「你指的是哪款商品」")
                .hasSize(2)
                .first()
                .extracting(ChatRequest.Turn::role)
                .isEqualTo(ChatRequest.Role.USER);
    }

    // ============================================================
    // 四、★ 开关
    // ============================================================

    @Test
    @DisplayName("★ 开关关掉：第二轮也没有历史，且 system prompt 里没有约束")
    void disabledMeansNoHistoryAtAll() {
        stubBusinessIntent();
        ChatAskResponse first = ask(
                new ChatAskRequest(null, "退货要几天", null));

        chatProperties.getHistory().setEnabled(false);
        try {
            ask(new ChatAskRequest(first.sessionNo(), "那退款呢", null));

            ChatRequest request = lastGenerationRequest();

            assertThat(request.history())
                    .as("★ 关掉 = 什么都不读（不是「读了不拼」）—— "
                            + "阶段 7 要靠这个区分「没开记忆」和「开了但历史是空的」")
                    .isEmpty();
            assertThat(request.systemPrompt())
                    .as("没有历史就不该有历史约束 —— 否则 prompt 在说谎")
                    .doesNotContain("关于对话历史");
        } finally {
            chatProperties.getHistory().setEnabled(true);
        }
    }
}
