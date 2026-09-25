package com.xbla.rag.service;

import com.xbla.rag.agent.intent.IntentClassification;
import com.xbla.rag.agent.intent.LlmIntentClassifier;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.ModelCallTrace;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.dto.ChatAskRequest;
import com.xbla.rag.entity.ChatSession;
import com.xbla.rag.rag.RetrievalPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 会话归属（{@code chat_session.user_id}）的四条规则 —— <b>阶段 9.1 的验收</b>。
 *
 * <h2>★★ 为什么这一列值得单独一个测试类</h2>
 *
 * <p>它上面有 <b>{@code FOREIGN KEY → app_user(id)}</b>（V5 迁移第 28 行），
 * 而写入它的身份来自 {@code X-Xbla-User-Id} —— <b>一个明文未签名的请求头</b>
 * （ADR-054 反复声明它「不是认证」）。
 *
 * <p>两件事一撞，后果很具体：任何客户端发一个
 * <pre>
 *   X-Xbla-User-Id: 999999
 * </pre>
 * 都会让<b>每一次对话在建会话那一步抛 FK 违例 → HTTP 500</b>。
 * 而这条路上没有任何东西会提示「问题出在那个头」——
 * 它看起来就是一个普通的数据库错误。
 *
 * <p>所以规则是「<b>查得到才认，查不到就如实记 NULL</b>」。
 * ★ 注意 NULL 在这里是<b>正确答案</b>而不是失败：那个头和「没带头」
 * 在语义上没有区别 —— 都是「我们不知道你是谁」。
 *
 * <h2>四条规则</h2>
 *
 * <pre>
 *   新建 + 身份有效       →  记下它
 *   新建 + 身份无效/缺席   →  NULL
 *   已有 + 该列是 NULL     →  认领（条件 UPDATE，幂等）
 *   已有 + 已是别人的      →  【不覆盖】
 * </pre>
 */
@SpringBootTest(properties = {
        "xbla.chat.history.enabled=false",
        "xbla.chat.summary.enabled=false"
})
@Transactional
@DisplayName("会话归属 · chat_session.user_id（阶段 9.1）")
class ChatSessionIdentityTest {

    /** 库里真实存在的两个用户（见 app_user：3~22） */
    private static final long USER_A = 8L;
    private static final long USER_B = 9L;

    /** ★ 一定不存在的 id —— 它就是「伪造的头」那一类 */
    private static final long GHOST = 999_999L;

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatSessionService chatSessionService;

    @MockitoBean
    private ChatModelRouter router;

    @MockitoBean
    private LlmIntentClassifier intentClassifier;

    /**
     * ★ 检索必须打桩：真跑一次会调向量化接口 —— 那是<b>要花钱</b>的。
     * 本类关心的是会话归属，检索在这里只是必经之路。
     */
    @MockitoBean
    private RetrievalPipeline retrievalPipeline;

    /** 固定分类成一个 KB 叶子 —— 本类不关心意图，只要它别走工具那条路 */
    private void classifyAsSpecQuery() {
        when(intentClassifier.classify(any(), any())).thenReturn(new IntentClassification(
                "SPEC_QUERY", IntentClassification.Outcome.CLASSIFIED,
                "SPEC_QUERY", DESCRIPTOR, new BigDecimal("0.0001"), 5, null));
    }

    /** 让模型回一句固定的正文 */
    private void scriptPlainAnswer() {
        when(router.chat(any(), any())).thenAnswer(inv -> {
            ModelCallTrace trace = inv.getArgument(1);
            ChatUsage usage = new ChatUsage(100, 10, 110, null, null, null);
            trace.succeeded(DESCRIPTOR, usage, 5);
            trace.cost(new BigDecimal("0.000100"));
            return ChatResponse.text("好的。", "stop", usage, DESCRIPTOR, 5);
        });
    }

    /** 问一次，返回它用的会话号（新建或沿用） */
    private String askWith(String sessionNo, Long userId, String question) {
        return chatService.ask(new ChatAskRequest(sessionNo, question, null), userId).sessionNo();
    }

    private ChatSession sessionOf(String sessionNo) {
        ChatSession session = chatSessionService.lambdaQuery()
                .eq(ChatSession::getSessionNo, sessionNo)
                .one();
        assertThat(session).as("会话 %s 应该存在", sessionNo).isNotNull();
        return session;
    }

    // ============================================================
    // 一、新建
    // ============================================================

    @Nested
    @DisplayName("一、新建会话时的归属")
    class Creation {

        @Test
        @DisplayName("① 身份有效 → 记下它（阶段 9 之前这一列恒为 NULL）")
        void validIdentityIsRecorded() {
            classifyAsSpecQuery();
            scriptPlainAnswer();

            String sessionNo = askWith(null, USER_A, "这款手机的参数是什么");

            assertThat(sessionOf(sessionNo).getUserId()).isEqualTo(USER_A);
        }

        @Test
        @DisplayName("② 身份缺席 → NULL（匿名是合法的，不是失败）")
        void absentIdentityStaysNull() {
            classifyAsSpecQuery();
            scriptPlainAnswer();

            String sessionNo = askWith(null, null, "这款手机的参数是什么");

            assertThat(sessionOf(sessionNo).getUserId()).isNull();
        }

        /**
         * ★★ 这个测试防的是「伪造的头让每一次对话 500」。
         *
         * <p>它是本类存在的<b>主要原因</b>：{@code chat_session.user_id} 上有 FK，
         * 而那个头是明文未签名的 —— 不做存在性校验的话，
         * 一个 {@code X-Xbla-User-Id: 999999} 就是一发不用任何权限的拒绝服务。
         */
        @Test
        @DisplayName("★★ ③ 身份指向不存在的人 → 照常建会话（不 500），user_id 记 NULL")
        void unknownIdentityDoesNotBlowUp() {
            classifyAsSpecQuery();
            scriptPlainAnswer();

            String[] sessionNo = new String[1];
            assertThatCode(() -> sessionNo[0] = askWith(null, GHOST, "这款手机的参数是什么"))
                    .as("★ 伪造的身份不能让问答失败 —— chat_session.user_id 上有 FK，"
                            + "而 X-Xbla-User-Id 是明文未签名的")
                    .doesNotThrowAnyException();

            assertThat(sessionOf(sessionNo[0]).getUserId())
                    .as("查不到就如实记 NULL：那个头和【没带头】在语义上没有区别，"
                            + "都是「我们不知道你是谁」")
                    .isNull();
        }
    }

    // ============================================================
    // 二、沿用一个已有会话
    // ============================================================

    @Nested
    @DisplayName("二、沿用一个已有会话时的归属")
    class Reuse {

        @Test
        @DisplayName("★ ④ 已有会话是匿名的 → 被认领")
        void anonymousSessionIsClaimed() {
            classifyAsSpecQuery();
            scriptPlainAnswer();

            // 先匿名开一个会话
            String sessionNo = askWith(null, null, "这款手机的参数是什么");
            assertThat(sessionOf(sessionNo).getUserId()).isNull();

            // 同一个人带上身份再问一次
            askWith(sessionNo, USER_A, "那它的续航呢");

            assertThat(sessionOf(sessionNo).getUserId())
                    .as("★ 认领让 4111 个历史匿名会话有机会归属到人，"
                            + "否则它们永远停在 NULL")
                    .isEqualTo(USER_A);
        }

        @Test
        @DisplayName("★★ ⑤ 反对照：已有会话是别人的 → 【不覆盖】")
        void foreignSessionIsNotReassigned() {
            classifyAsSpecQuery();
            scriptPlainAnswer();

            String sessionNo = askWith(null, USER_A, "这款手机的参数是什么");
            assertThat(sessionOf(sessionNo).getUserId()).isEqualTo(USER_A);

            // 换一个身份、沿用同一个会话号
            askWith(sessionNo, USER_B, "另一个人的问题");

            assertThat(sessionOf(sessionNo).getUserId())
                    .as("★★ 覆盖会让这个会话的历史归属【随最后一个请求漂移】——"
                            + " 而那种漂移没有任何日志能看出来。先声明的赢（同 ADR-055）")
                    .isEqualTo(USER_A);
        }

        @Test
        @DisplayName("★★ ⑥ 反对照：匿名请求沿用别人的会话 → 同样不覆盖")
        void anonymousRequestDoesNotClearOwner() {
            classifyAsSpecQuery();
            scriptPlainAnswer();

            String sessionNo = askWith(null, USER_A, "这款手机的参数是什么");

            // ★ 不带身份地沿用 —— 这是演示页清空 userId 输入框之后的真实形态
            askWith(sessionNo, null, "接着问一句");

            assertThat(sessionOf(sessionNo).getUserId())
                    .as("★ 「身份缺席」绝不能被解释成「把归属清空」——"
                            + " 那会让任何人凭一个 sessionNo 就抹掉别人的会话归属")
                    .isEqualTo(USER_A);
        }
    }
}
