package com.xbla.rag.agent.memory;

import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.WireChatRequest;
import com.xbla.rag.config.ChatProperties;
import com.xbla.rag.entity.ChatMessage;
import com.xbla.rag.entity.ChatSession;
import com.xbla.rag.service.ChatMessageService;
import com.xbla.rag.service.ChatSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 滑动窗口会话记忆的集成测试（阶段 5.5）—— 真连 PostgreSQL。
 *
 * <h3>★ 为什么这些用例必须连真库</h3>
 *
 * <p>它们验的都是「只有跑了真 SQL 才会暴露」的东西：
 * 排序是不是真的按 {@code id}、{@code LIMIT} 是不是真的截断在正确的一侧、
 * 窗口边界的取舍会不会把最新一轮丢掉。
 *
 * <p>{@code @Transactional} 让每个测试方法跑在事务里、结束自动回滚
 * （同 {@code EntityMappingTest}）。
 *
 * <p><b>★ 这里没有「异步线程看不到未提交数据」的问题</b> ——
 * 和 5.4 的 {@code RetrievalPipelineScopeTest} 不同，本类走的
 * {@code ConversationMemory.load} 是在<b>请求线程上同步执行</b>的，
 * 用的就是测试事务那条连接。这是个容易搞混的地方，值得点一句：
 * 「能不能用 @Transactional 测」取决于被测代码是否跨线程。
 *
 * <p><b>运行前提</b>：docker compose 的 postgres 必须在跑。
 */
@SpringBootTest
@Transactional
@DisplayName("ConversationMemory —— 滑动窗口会话记忆")
class ConversationMemoryIntegrationTest {

    private static final int ROLE_USER = 1;
    private static final int ROLE_ASSISTANT = 2;
    private static final int ROLE_SYSTEM = 3;

    @Autowired
    private ConversationMemory memory;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private com.xbla.rag.service.ChatSummaryService chatSummaryService;

    @Autowired
    private ChatProperties chatProperties;

    private Long sessionId;

    @BeforeEach
    void setUp() {
        ChatSession session = new ChatSession();
        session.setSessionNo("S-MEM-" + System.nanoTime());
        session.setTitle("会话记忆测试");
        session.setMessageCount(0);
        session.setStatus(1);
        session.setLastActiveAt(OffsetDateTime.now());
        chatSessionService.save(session);
        sessionId = session.getId();
    }

    /** 追加一条消息。id 由序列给出，所以调用顺序 = 时间顺序 */
    private void append(int role, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        chatMessageService.save(message);
    }

    /**
     * 只取窗口原文那部分。
     *
     * <p>{@code load()} 从 5.6 起返回 {@link MemoryContext}（摘要 + 窗口）。
     * 本类的前四节只关心窗口，用这个助手把噪音挡掉 ——
     * 免得每个断言都写成 {@code load(...).history()}。
     */
    private List<ChatRequest.Turn> history(Long sessionId) {
        return memory.load(sessionId).history();
    }

    /** 写一行摘要。{@code summary_level=1} 是 5.6 唯一会写的那一档 */
    private void saveSummary(long startMessageId, long endMessageId, String text) {
        com.xbla.rag.entity.ChatSummary row = new com.xbla.rag.entity.ChatSummary();
        row.setSessionId(sessionId);
        row.setSummaryLevel(1);
        row.setStartMessageId(startMessageId);
        row.setEndMessageId(endMessageId);
        row.setSummary(text);
        chatSummaryService.save(row);
    }

    /** 本轮所有消息的 id，按时间正序 —— 用来算「窗口起点应该是哪一条」 */
    private List<Long> messageIds() {
        return chatMessageService.lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getId)
                .list()
                .stream().map(ChatMessage::getId).toList();
    }

    /** 追加一轮问答 */
    private void appendTurn(String question, String answer) {
        append(ROLE_USER, question);
        append(ROLE_ASSISTANT, answer);
    }

    // ============================================================
    // 一、基本读取
    // ============================================================

    @Nested
    @DisplayName("一、基本读取")
    class Basic {

        @Test
        @DisplayName("空会话 → 空列表（不是 null，也不抛）")
        void emptySession() {
            assertThat(history(sessionId)).isEmpty();
        }

        @Test
        @DisplayName("多轮历史按【时间正序】返回 —— 顺序反了模型会读成倒叙")
        void returnsOldestFirst() {
            appendTurn("退货要几天", "七天");
            appendTurn("那退款呢", "三个工作日");

            List<ChatRequest.Turn> turns = history(sessionId);

            assertThat(turns).hasSize(4);
            assertThat(turns).extracting(ChatRequest.Turn::content)
                    .as("★ containsExactly 是有序断言 —— 用 containsExactlyInAnyOrder "
                            + "就测不出顺序，而 ChatRequest.toWireMessages 拼的是 "
                            + "「history 正序」，顺序错了模型会把对话读成倒叙")
                    .containsExactly("退货要几天", "七天", "那退款呢", "三个工作日");
            assertThat(turns).extracting(ChatRequest.Turn::role)
                    .containsExactly(ChatRequest.Role.USER, ChatRequest.Role.ASSISTANT,
                            ChatRequest.Role.USER, ChatRequest.Role.ASSISTANT);
        }

        @Test
        @DisplayName("★ role=3（系统消息）不进历史 —— 历史只回放「我问了什么、你答了什么」")
        void systemMessagesAreExcluded() {
            append(ROLE_SYSTEM, "这条是系统消息");
            appendTurn("退货要几天", "七天");

            assertThat(history(sessionId))
                    .extracting(ChatRequest.Turn::content)
                    .containsExactly("退货要几天", "七天")
                    .doesNotContain("这条是系统消息");
        }

        @Test
        @DisplayName("sessionId 为 null → 空列表，不抛异常")
        void nullSessionId() {
            assertThat(history(null)).isEmpty();
        }
    }

    // ============================================================
    // 二、★ 窗口截断
    // ============================================================

    @Nested
    @DisplayName("二、★ 窗口截断：丢的是【最早的】，不是最新的")
    class Windowing {

        @Test
        @DisplayName("★ 12 轮 → 只返回最近 10 轮，且是最新的那 10 轮")
        void keepsTheNewestTurns() {
            for (int i = 1; i <= 12; i++) {
                appendTurn("问题" + i, "回答" + i);
            }

            List<ChatRequest.Turn> turns = history(sessionId);

            assertThat(turns).hasSize(20);      // 10 轮 × 2 条
            assertThat(turns.get(0).content())
                    .as("★ 第 1、2 轮被丢掉了（另外两轮的 4 条）—— "
                            + "窗口留的是最新的。反过来的话（丢掉最新的）"
                            + "会让「上一轮说了什么」这个最常用的上下文恰好缺失，"
                            + "而且现象极难发现：模型只是偶尔不接话")
                    .isEqualTo("问题3");
            assertThat(turns.get(turns.size() - 1).content())
                    .as("最后一条必须是最新那轮的回答")
                    .isEqualTo("回答12");
        }

        @Test
        @DisplayName("★ 对照：正好 10 轮时不丢任何东西 —— 边界不能多砍一轮")
        void exactlyMaxTurnsKeepsEverything() {
            for (int i = 1; i <= 10; i++) {
                appendTurn("问题" + i, "回答" + i);
            }

            List<ChatRequest.Turn> turns = history(sessionId);

            assertThat(turns).hasSize(20);
            assertThat(turns.get(0).content())
                    .as("★ 对照上一条：上一条丢了两轮，这一条一轮都不该丢。"
                            + "两条一起看，才能排除「窗口写死了丢 4 条」这种实现")
                    .isEqualTo("问题1");
        }

        @Test
        @DisplayName("窗口大小可配：改成 2 轮立刻生效")
        void maxTurnsIsConfigurable() {
            int original = chatProperties.getHistory().getMaxTurns();
            chatProperties.getHistory().setMaxTurns(2);
            try {
                for (int i = 1; i <= 5; i++) {
                    appendTurn("问题" + i, "回答" + i);
                }

                assertThat(history(sessionId))
                        .extracting(ChatRequest.Turn::content)
                        .containsExactly("问题4", "回答4", "问题5", "回答5");
            } finally {
                chatProperties.getHistory().setMaxTurns(original);
            }
        }
    }

    // ============================================================
    // 三、★ 孤儿用户消息
    // ============================================================

    @Nested
    @DisplayName("三、★ 孤儿用户消息：有问无答的那一条")
    class Orphan {

        @Test
        @DisplayName("★ 末尾是用户消息 → 丢掉它，不让它进历史")
        void trailingUserMessageIsDropped() {
            appendTurn("退货要几天", "七天");
            // 模拟：上一轮在 saveUserMessage 之后、saveAssistantMessage 之前挂了
            append(ROLE_USER, "那退款呢");

            List<ChatRequest.Turn> turns = history(sessionId);

            assertThat(turns).extracting(ChatRequest.Turn::content)
                    .containsExactly("退货要几天", "七天");
            assertThat(turns.get(turns.size() - 1).role())
                    .as("★ 历史必须以助手回复结尾。不丢的话，"
                            + "toWireMessages 会拼出【连续两条 user 消息】—— "
                            + "那在 OpenAI 兼容协议里是畸形输入，轻则 400、"
                            + "重则模型把两条拼在一起理解。而 400 在降级链里"
                            + "属于「不降级」那一类，整次问答会直接失败")
                    .isEqualTo(ChatRequest.Role.ASSISTANT);
        }

        @Test
        @DisplayName("★ 对照：末尾是助手回复时【不】丢")
        void trailingAssistantMessageIsKept() {
            appendTurn("退货要几天", "七天");

            assertThat(history(sessionId))
                    .as("★ 对照上一条：证明丢的是「末尾那条 user」，"
                            + "而不是「无条件砍掉最后一条」")
                    .hasSize(2);
        }

        @Test
        @DisplayName("★ 只有一条孤儿用户消息（新会话第一次就挂了）→ 空历史")
        void onlyAnOrphanMeansEmptyHistory() {
            append(ROLE_USER, "第一次提问就失败了");

            assertThat(history(sessionId))
                    .as("★ 这一条如果漏掉，新会话第一次请求失败之后的每一次提问"
                            + "都会带着一条没有回答的历史 —— 而且历史里"
                            + "只有一条 user 消息，模型会把它当成「我刚才说过的话」")
                    .isEmpty();
        }

        @Test
        @DisplayName("★ 取数时多要一条，所以丢掉孤儿之后仍然凑得满窗口")
        void extraRowCompensatesForTheOrphan() {
            // 10 轮正常问答 + 1 条孤儿 = 21 条消息，而窗口是 10 轮 = 20 条
            for (int i = 1; i <= 10; i++) {
                appendTurn("问题" + i, "回答" + i);
            }
            append(ROLE_USER, "孤儿");

            List<ChatRequest.Turn> turns = history(sessionId);

            assertThat(turns)
                    .as("★ 窗口仍然是【满的】10 轮。"
                            + "只取 20 条再丢孤儿的话只剩 19 条（9.5 轮）—— "
                            + "窗口会无声地少半轮，而少的那半轮是【最早】的那条："
                            + "表现为「模型偶尔想不起上上轮说过什么」")
                    .hasSize(20);
            assertThat(turns.get(0).content())
                    .as("★ 开头是「问题1」正是补偿生效的证据。"
                            + "不补偿的话这里会是「问题2」—— "
                            + "窗口整体往前挪了半轮，而外部完全看不出来")
                    .isEqualTo("问题1");
            assertThat(turns.get(turns.size() - 1).content()).isEqualTo("回答10");
        }
    }

    // ============================================================
    // 四、开关与防御
    // ============================================================

    @Nested
    @DisplayName("四、开关与防御")
    class SwitchAndGuards {

        @Test
        @DisplayName("★ 开关关掉 → 空历史（什么都不读，而不是读了不拼）")
        void disabledReturnsNothing() {
            appendTurn("退货要几天", "七天");
            chatProperties.getHistory().setEnabled(false);
            try {
                assertThat(history(sessionId))
                        .as("★ 「关了 = 什么都不读」和「关了 = 读了不拼」"
                                + "在阶段 7 是【两个不同的实验条件】："
                                + "前者能证明「没开记忆」，后者证明不了")
                        .isEmpty();
            } finally {
                chatProperties.getHistory().setEnabled(true);
            }
        }

        @Test
        @DisplayName("max-turns 配成 0 或负数 → 空历史，打 WARN，不抛异常")
        void nonPositiveMaxTurns() {
            appendTurn("退货要几天", "七天");
            chatProperties.getHistory().setMaxTurns(0);
            try {
                assertThatCode(() -> assertThat(history(sessionId)).isEmpty())
                        .as("★ 配置写错了不该让问答失败 —— 退化成「没有上下文的一轮」"
                                + "比 500 好得多")
                        .doesNotThrowAnyException();
            } finally {
                chatProperties.getHistory().setMaxTurns(10);
            }
        }

        @Test
        @DisplayName("★ 超长消息被截断 —— 防止 prompt 被悄悄撑爆")
        void overlongMessageIsTruncated() {
            String huge = "长".repeat(3000);
            appendTurn("短问题", huge);

            List<ChatRequest.Turn> turns = history(sessionId);

            assertThat(turns.get(1).content())
                    .as("★ 截断本身就是防线，末尾那个标记是为了让「被截断了」"
                            + "这件事在 prompt 里也看得见 —— 否则模型拿到一句"
                            + "戛然而止的话，会把它当成完整的")
                    .hasSizeLessThanOrEqualTo(1000 + 6)
                    .endsWith("（已截断）");
            assertThat(turns.get(1).content())
                    .as("★ 对照：确实是从 3000 字裁下来的，不是本来就短")
                    .startsWith("长长长");
        }

        @Test
        @DisplayName("★ 正常长度的消息【不】被截断（阈值不能误伤）")
        void normalLengthIsUntouched() {
            String normal = "自签收之日起 7 天内可以申请无理由退货，".repeat(10);   // 200 字
            appendTurn("退货要几天", normal);

            assertThat(history(sessionId).get(1).content())
                    .as("★ 实测助手消息最长 352 字，阈值是 1000 —— 正常数据永远碰不到它")
                    .isEqualTo(normal);
        }
    }

    // ============================================================
    // 五、★★ 连续失败：只有丢【一条】孤儿是不够的
    // ============================================================

    @Nested
    @DisplayName("五、★★ 连续失败之后这个会话必须还能用")
    class ConsecutiveFailures {

        @Test
        @DisplayName("★ 两条连续孤儿都要丢 —— 只丢最新那条会让会话【永久死掉】")
        void bothTrailingOrphansAreDropped() {
            appendTurn("退货要几天", "七天");
            // 连续两轮都在 saveUserMessage 之后挂了：
            //   catch 分支【不写】助手消息是刻意的（写「服务异常」会污染评测语料），
            //   所以清理只能在读的这一侧做
            append(ROLE_USER, "第一次失败");
            append(ROLE_USER, "第二次失败");

            List<ChatRequest.Turn> turns = history(sessionId);

            assertThat(turns).extracting(ChatRequest.Turn::content)
                    .as("★ 只丢最新那条的话，历史会以「第一次失败」结尾 ——"
                            + "而它是一条 user 消息。拼上本轮问题就是【连续两条 user】，"
                            + "在 OpenAI 兼容协议里是畸形输入（400），"
                            + "而 400 在降级链里属于「不降级」。")
                    .containsExactly("退货要几天", "七天");
        }

        @Test
        @DisplayName("★ 端到端形状：两条孤儿 + 本轮问题，拼出来仍然合法")
        void wireShapeSurvivesConsecutiveFailures() {
            appendTurn("退货要几天", "七天");
            append(ROLE_USER, "第一次失败");
            append(ROLE_USER, "第二次失败");

            ChatRequest request = ChatRequest.of("你是客服", history(sessionId), "本轮的问题");

            assertThat(request.toWireMessages())
                    .extracting(WireChatRequest.WireMessage::role)
                    .as("★ 修之前这里会是 system,user,assistant,user,user,user ——"
                            + "【三条连续 user】。而它的后果不是「这一轮答得差」，是："
                            + "这一轮失败 → 又留一条孤儿 → 下一轮更畸形 →"
                            + "【这个会话再也答不出任何话】，而日志里每一条都只是"
                            + "一次孤立的 400")
                    .containsExactly("system", "user", "assistant", "user");
        }

        @Test
        @DisplayName("★ 边界：整个窗口都是孤儿（连续失败超过一个窗口）→ 空历史")
        void allOrphansMeansEmptyHistory() {
            append(ROLE_USER, "失败1");
            append(ROLE_USER, "失败2");
            append(ROLE_USER, "失败3");

            assertThat(history(sessionId))
                    .as("★ 全丢完就是空历史。这时候只剩本轮问题一条 user 消息 ——"
                            + "那是合法的，模型把它当成一次全新的提问")
                    .isEmpty();
        }
    }

    // ============================================================
    // 六、★ 摘要层（阶段 5.6）
    // ============================================================

    @Nested
    @DisplayName("六、★ 摘要层：和窗口的接缝")
    class SummaryLayer {

        @Test
        @DisplayName("★ 摘要和窗口一起返回 —— 两者是同一次读的两个部分")
        void summaryComesBackWithHistory() {
            for (int i = 1; i <= 12; i++) {
                appendTurn("问题" + i, "回答" + i);
            }
            List<Long> ids = messageIds();
            // 窗口是最近 20 条 = ids[4..23]，所以窗口起点是 ids[4]，
            // 摘要要覆盖到它【前一条】为止 —— 严丝合缝
            saveSummary(ids.get(0), ids.get(3), "用户问了 1 到 2 轮的问题");

            MemoryContext ctx = memory.load(sessionId);

            assertThat(ctx.sessionSummary()).isEqualTo("用户问了 1 到 2 轮的问题");
            assertThat(ctx.hasSummary()).isTrue();
            assertThat(ctx.history()).hasSize(20);
            assertThat(ctx.history().get(0).content())
                    .as("★ 摘要覆盖到 ids[3]，窗口从 ids[4] 开始 —— 两段严丝合缝。"
                            + "这条和上一条断言一起，钉的是【同一次读】这件事："
                            + "分两次读的话，中间落库的新消息会让接缝错位")
                    .isEqualTo("问题3");
        }

        @Test
        @DisplayName("★★ 摘要游标侵入窗口 → 丢弃摘要（接缝不变式被破坏时不静默使用）")
        void staleSummaryIsDropped() {
            for (int i = 1; i <= 12; i++) {
                appendTurn("问题" + i, "回答" + i);
            }
            List<Long> ids = messageIds();
            // 故意写一个【侵入窗口内部】的游标：窗口起点是 ids[4]，
            // 这里让它覆盖到 ids[10] —— 明明已经压进了窗口里
            saveSummary(ids.get(0), ids.get(10), "这份摘要越界了");

            MemoryContext ctx = memory.load(sessionId);

            assertThat(ctx.sessionSummary())
                    .as("★ 重叠（模型看到两遍）其实无害，但这条不变式是整个双层记忆的"
                            + "地基：它被破坏说明取数口径错了，而别处可能同时出现了"
                            + "【空洞】—— 那种错在回答质量上完全看不出来。"
                            + "所以宁可少一份摘要，也不静默使用一个越界的游标")
                    .isNull();
            assertThat(ctx.history())
                    .as("★ 对照：丢的只是摘要，窗口原文一条不少")
                    .hasSize(20);
        }

        @Test
        @DisplayName("★ 关掉摘要开关 → 不读摘要，但窗口照常")
        void disabledSummaryDoesNotRead() {
            appendTurn("退货要几天", "七天");
            saveSummary(messageIds().get(0), messageIds().get(0), "不该被读到");

            chatProperties.getSummary().setEnabled(false);
            try {
                MemoryContext ctx = memory.load(sessionId);

                assertThat(ctx.sessionSummary())
                        .as("★ 「关了 = 不读」和「关了 = 读了不用」在阶段 7 是"
                                + "两个不同的实验条件 —— 同 history.enabled 的理由")
                        .isNull();
                assertThat(ctx.hasHistory()).isTrue();
            } finally {
                chatProperties.getSummary().setEnabled(true);
            }
        }

        @Test
        @DisplayName("★ 有摘要没原文也算「有上下文」—— 摘要也是历史")
        void summaryAloneCountsAsContext() {
            MemoryContext withSummary = new MemoryContext("用户预算两千左右", List.of());
            MemoryContext nothing = MemoryContext.EMPTY;

            assertThat(withSummary.hasAny())
                    .as("★ hasAny() 决定 prompt 里要不要加「历史以本次资料为准」那条约束。"
                            + "只看 hasHistory() 的话，【只有摘要的那一轮】会漏掉它 ——"
                            + "而摘要恰恰是【有损、最容易被当成精确事实】的那种历史")
                    .isTrue();
            assertThat(nothing.hasAny()).isFalse();
        }
    }

    // ============================================================
    // 七、★ 交给 ChatRequest 之后仍然是合法的
    // ============================================================

    @Test
    @DisplayName("★ 端到端形状：load 的结果直接能用，且拼出来的消息序列合法")
    void producedTurnsAreWireReady() {
        for (int i = 1; i <= 3; i++) {
            appendTurn("问题" + i, "回答" + i);
        }
        append(ROLE_USER, "孤儿");

        List<ChatRequest.Turn> turns = history(sessionId);
        ChatRequest request = ChatRequest.of("你是客服", turns, "本轮的问题");

        assertThat(request.toWireMessages())
                .extracting(WireChatRequest.WireMessage::role)
                .as("★ 这条断言的价值在于：它验的是【下游真正会发出去的东西】。"
                        + "只看 turns 的列表内容，看不出「两条连续 user 消息」"
                        + "这种形状问题 —— 而那正是孤儿消息的后果")
                .containsExactly("system", "user", "assistant",
                        "user", "assistant", "user", "assistant", "user");
    }
}
