package com.xbla.rag.service;

import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.dto.ChatAskResponse;
import com.xbla.rag.rag.RetrievalPipeline;
import com.xbla.rag.rag.RetrievalTrace;
import com.xbla.rag.rag.retrieve.RetrievalOptions;
import com.xbla.rag.rag.retrieve.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 澄清反问的集成测试（阶段 5.3）。
 *
 * <p><b>不花一分钱</b>：{@code ChatModelRouter} 和 {@code RetrievalPipeline}
 * 都被换成了确定性桩 —— 前者本来要真的调模型，后者会真的调向量化接口。
 * （同阶段 4 的纪律：不新增任何需要真 API Key 的测试。）
 *
 * <h3>★ 本类真正要证明的那件事</h3>
 *
 * <p>不是「澄清返回了正确的话术」（那 {@code ClarificationDeciderTest} 已经测了），
 * 而是<b>「澄清真的短路了整条链路」</b>：
 *
 * <ul>
 *   <li><b>没有检索</b> —— {@code RetrievalPipeline} 一次都没被调用</li>
 *   <li><b>没有生成</b> —— {@code ChatModelRouter} 只被调用了<b>一次</b>
 *       （那一次是分类本身），而不是两次（分类 + 生成）</li>
 * </ul>
 *
 * <p>这两条只能用「调用次数」来证明，光看返回的文本看不出来 ——
 * 一个检索了、生成了一堆内容、最后被丢弃的实现，返回的文本
 * <b>和真正短路的实现一模一样</b>。
 */
@SpringBootTest
@Transactional
@DisplayName("ChatService · 澄清反问的端到端路径")
class ChatClarificationIntegrationTest {

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

    @Autowired
    private ChatService chatService;

    @MockitoBean
    private ChatModelRouter router;

    @MockitoBean
    private RetrievalPipeline retrievalPipeline;

    private static ChatResponse reply(String content) {
        return ChatResponse.text(content, "stop", null, DESCRIPTOR, 10);
    }

    // ============================================================
    // 一、★ 澄清路径必须短路
    // ============================================================

    @Test
    @DisplayName("★ 分类为信息不足时：不检索、不生成，direct 返回追问")
    void clarificationShortCircuitsEverything() {
        when(router.chat(any(), any())).thenReturn(reply("NEEDS_CLARIFICATION"));

        ChatAskResponse response = ask(
                new ChatAskRequest(null, "那个怎么样", null));

        // ① 返回的是一次追问
        assertThat(response.intent()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(response.answer())
                .as("返回的应当是澄清话术，而不是一段生成的回答")
                .isNotBlank()
                .contains("哪款商品");

        // ② 没有生成调用 —— 响应体里那些字段必须【全是 null】
        assertThat(response.provider()).isNull();
        assertThat(response.model()).isNull();
        assertThat(response.usage()).isNull();
        assertThat(response.cost()).isNull();
        assertThat(response.llmLatencyMs()).as("没有生成调用，就没有生成耗时").isZero();
        assertThat(response.references()).as("没有检索，就没有引用").isNull();

        // ③ ★ 真正的证据：调用次数
        verify(router, times(1)).chat(any(), any(ModelCallTrace.class));
        //   —— 一次是分类。如果是两次，说明它还在调模型生成回答
        verify(retrievalPipeline, never())
                .retrieve(anyString(), any(), any(RetrievalTrace.class));
        //   —— 检索压根没发生
    }

    // ============================================================
    // 二、★ 对照：业务意图必须走完整链路
    // ============================================================

    @Test
    @DisplayName("★ 对照：分类为业务意图时，检索和生成都要发生")
    void businessIntentRunsTheFullChain() {
        // 第一次调用是分类，第二次是生成 —— 按调用顺序返回不同的结果。
        // ★ 顺序依赖在这里是刻意的：它恰好验证了「先分类、后生成」这个次序
        when(router.chat(any(), any()))
                .thenReturn(reply("RETURN_EXCHANGE"))
                .thenReturn(reply("自签收之日起 7 天内可以申请无理由退货。"));
        when(retrievalPipeline.retrieve(anyString(), any(), any(RetrievalTrace.class)))
                .thenReturn(List.<RetrievedChunk>of());

        ChatAskResponse response = ask(
                new ChatAskRequest(null, "退货要几天", null));

        assertThat(response.intent()).isEqualTo("RETURN_EXCHANGE");
        assertThat(response.answer()).isEqualTo("自签收之日起 7 天内可以申请无理由退货。");

        // ★ 与上一条测试【只差一次 mock 的返回值】，结果却是「调了两次 + 检索了一次」。
        //   没有这个对照，上面那条 verify(times(1)) 可能只是因为
        //   分类压根没跑，而不是因为短路生效了
        verify(router, times(2)).chat(any(), any(ModelCallTrace.class));

        // ★ 5.4：这条同时验证了「走完整链路」和「范围真的被翻译出来了」。
        //   RETURN_EXCHANGE 在意图树里声明 doc_types: [2,4]
        ArgumentCaptor<RetrievalOptions> scopeCaptor =
                ArgumentCaptor.forClass(RetrievalOptions.class);
        verify(retrievalPipeline, times(1))
                .retrieve(anyString(), scopeCaptor.capture(), any(RetrievalTrace.class));

        assertThat(scopeCaptor.getValue().docTypesLiteral())
                .as("★ 分类结果 → doc_type 范围 这一步是纯查表，"
                        + "但它错了的话过滤就会筛掉正确答案 —— "
                        + "而且检索照样跑、答案照样生成，看起来一切正常")
                .isEqualTo("{2,4}");
    }

    @Test
    @DisplayName("★ 分类失败时【不过滤】—— 不知道范围就别乱缩")
    void failedClassificationMeansNoFilter() {
        // 模型返回一个树里不存在的 code → UNKNOWN_CODE
        when(router.chat(any(), any())).thenReturn(reply("NOT_A_REAL_INTENT_CODE"));
        when(retrievalPipeline.retrieve(anyString(), any(), any(RetrievalTrace.class)))
                .thenReturn(List.<RetrievedChunk>of());

        ask(new ChatAskRequest(null, "退货要几天", null));

        ArgumentCaptor<RetrievalOptions> scopeCaptor =
                ArgumentCaptor.forClass(RetrievalOptions.class);
        verify(retrievalPipeline).retrieve(anyString(), scopeCaptor.capture(),
                any(RetrievalTrace.class));

        assertThat(scopeCaptor.getValue().filtered())
                .as("★ 分类失败 → 空集 → 不过滤 → 等价于阶段 4 的行为。"
                        + "如果这里退化成「过滤到一个空范围」，"
                        + "doc_type = ANY('{}') 恒为 false，症状是"
                        + "「分类一失败，这个问题就再也检不到任何东西」")
                .isFalse();
    }

    // ============================================================
    // 三、★ 分类把问题原文当 user 消息发出去
    // ============================================================

    @Test
    @DisplayName("★ 分类用的是【问题原文】，不是被改写过的")
    void classificationReceivesTheRawQuestion() {
        when(router.chat(any(), any())).thenReturn(reply("NEEDS_CLARIFICATION"));

        ask(new ChatAskRequest(null, "那个怎么样", null));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).chat(captor.capture(), any(ModelCallTrace.class));

        assertThat(captor.getValue().userQuestion())
                .as("分类必须看到用户的原话 —— 改写是阶段 4 的事且默认关闭")
                .isEqualTo("那个怎么样");
        assertThat(captor.getValue().systemPrompt())
                .as("system 是分类 prompt，不是问答 prompt")
                .contains("意图分类器");
    }

    // ============================================================
    // 四、分类失败不能把问答搞挂
    // ============================================================

    @Test
    @DisplayName("★ 分类返回非法 code 时，照常检索与生成（不澄清、不报错）")
    void unknownCodeFallsBackToFullChain() {
        when(router.chat(any(), any()))
                .thenReturn(reply("SOME_MADE_UP_CODE"))
                .thenReturn(reply("这是正常生成的回答。"));
        when(retrievalPipeline.retrieve(anyString(), any(), any(RetrievalTrace.class)))
                .thenReturn(List.<RetrievedChunk>of());

        ChatAskResponse response = ask(
                new ChatAskRequest(null, "退货要几天", null));

        assertThat(response.intent())
                .as("分类失败时不填占位串 —— 写 null，阶段 7 才能分清「没分出来」和「分到了」")
                .isNull();
        assertThat(response.answer()).isEqualTo("这是正常生成的回答。");
        verify(retrievalPipeline, times(1))
                .retrieve(anyString(), any(), any(RetrievalTrace.class));
    }
}
