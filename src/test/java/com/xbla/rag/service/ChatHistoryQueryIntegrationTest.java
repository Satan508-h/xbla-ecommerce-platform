package com.xbla.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.dto.ChatMessageView;
import com.xbla.rag.dto.ChatSessionSummary;
import com.xbla.rag.entity.ChatMessage;
import com.xbla.rag.entity.ChatSession;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.ChatSessionMapper;
import com.xbla.rag.mapper.QaLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话历史查询侧（阶段 8）—— <b>列表要排除评测流量、消息要能反序列化回来</b>。
 *
 * <h2>★★★ 这个类守的第一件事：97.8% 的会话是评测跑出来的</h2>
 *
 * <p>动手前实测（2026-09-24）：
 *
 * <pre>
 *   chat_session 总数                     4111
 *   其中 qa_log 带 eval_run_id 的会话      4022   ← 97.8%
 *   真实的                                  89
 * </pre>
 *
 * <p>所以 {@code listRealSessions} 里那句 {@code NOT EXISTS} 不是优化、是<b>功能本身</b>：
 * 去掉它，用户打开「历史会话」看到的是一屏「商品支持七天无理由退货吗」。
 *
 * <h2>★★ 正-反对照是怎么做的</h2>
 *
 * <p>只断言「评测会话不在列表里」是<b>不够的</b> —— 那个断言在
 * 「过滤写对了」和「那行数据根本没插进去」两种情况下都会通过。
 * 所以 {@link EvalTrafficFilter#theRowIsStillThereTheFilterIsWhatRemovedIt}
 * 额外断言：<b>同一个 sessionNo 用不过滤的查询能找到</b>。
 *
 * <p>★ 两条一起，「是过滤器把它拿掉的」才真的被测到。
 *
 * <h2>★ 为什么这批用例不花钱</h2>
 *
 * <p>全部直接操作表，<b>不经过 {@code ChatService}</b> ——
 * 没有模型调用、没有检索、没有向量化。它们是纯读写测试。
 */
@SpringBootTest
@Transactional
@DisplayName("ChatHistoryQueryService · 会话历史查询侧")
class ChatHistoryQueryIntegrationTest {

    /**
     * 本类自己造的那批行，用会话标题前缀圈起来。
     * ★ 用纳秒而不是固定串：同一个 JVM 里重跑时不会互相撞上。
     */
    private static final String PREFIX = "HISTQ-" + System.nanoTime() + "-";

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private ChatHistoryQueryService queryService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private QaLogMapper qaLogMapper;

    /** ★ 用它来【演示】双重编码：把那个字符串原样序列化出来长什么样 */
    @Autowired
    private ObjectMapper objectMapper;

    // ============================================================
    // 造数据
    // ============================================================

    /**
     * 造一个会话。
     *
     * @param lastActiveAt 显式指定活跃时间。<b>必须显式给</b> ——
     *        默认值 {@code now()} 是<b>事务开始时间</b>，同一个测试方法里
     *        建的多个会话会拿到<b>完全相同</b>的时间戳，于是排序退化成未定义。
     *        （这正是 {@code selectRealSessions} 的 ORDER BY 要带第二个键的原因。）
     */
    private ChatSession newSession(OffsetDateTime lastActiveAt) {
        ChatSession s = new ChatSession();
        s.setSessionNo(PREFIX + SEQ.incrementAndGet());
        s.setTitle(PREFIX + "标题");
        s.setMessageCount(0);
        s.setStatus(1);
        s.setLastActiveAt(lastActiveAt);
        chatSessionService.save(s);
        return s;
    }

    /** 造一条消息。{@code refsJson} 是<b>写进库里的那种形状</b>（JSON 文本 / null） */
    private void newMessage(Long sessionId, int role, String content, String refsJson) {
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setReferences(refsJson);
        chatMessageService.save(m);
    }

    /**
     * 造一行 qa_log —— {@code evalRunId} 为 null 就是「真实用户」那一类。
     *
     * <p>它唯一的用途是让 {@code NOT EXISTS} 子查询有东西可判。
     */
    private void newQaLog(Long sessionId, String evalRunId) {
        QaLog row = new QaLog();
        row.setTraceId("T-HISTQ-" + System.nanoTime());
        row.setSessionId(sessionId);
        row.setQuestion(PREFIX + "问题");
        row.setEvalRunId(evalRunId);
        qaLogMapper.insert(row);
    }

    /** 本类自己造的所有会话，不过滤 —— 正-反对照里的「反」那一侧要用 */
    private List<ChatSession> allMySessions() {
        return chatSessionMapper.selectList(
                Wrappers.<ChatSession>lambdaQuery()
                        .likeRight(ChatSession::getSessionNo, PREFIX));
    }

    private boolean listContains(String sessionNo) {
        return queryService.listRealSessions(ChatHistoryQueryService.MAX_LIMIT).stream()
                .map(ChatSessionSummary::sessionNo)
                .anyMatch(sessionNo::equals);
    }

    // ============================================================
    // 一、★★★ 评测流量过滤
    // ============================================================

    @Nested
    @DisplayName("一、★★★ 评测流量过滤")
    class EvalTrafficFilter {

        @Test
        @DisplayName("真实用户的会话【在】列表里")
        void realSessionAppears() {
            ChatSession s = newSession(OffsetDateTime.now());
            newMessage(s.getId(), 1, "退货要几天", null);
            newQaLog(s.getId(), null);          // ★ eval_run_id 为 null = 真实流量

            assertThat(listContains(s.getSessionNo()))
                    .as("有真实 qa_log 行、没有评测标记 → 必须在列表里")
                    .isTrue();
        }

        @Test
        @DisplayName("★★ 评测会话【不在】列表里")
        void evalSessionIsFilteredOut() {
            ChatSession s = newSession(OffsetDateTime.now());
            newMessage(s.getId(), 1, "商品支持七天无理由退货吗", null);
            newQaLog(s.getId(), "run-stage7-eval");

            assertThat(listContains(s.getSessionNo()))
                    .as("★ 这一条就是 4022/4111 那个数字的直接后果："
                            + "评测会话不排掉，历史列表 97.8% 是评测题的复读")
                    .isFalse();
        }

        @Test
        @DisplayName("★★★ 对照：那行数据【确实存在】，是过滤器把它拿掉的")
        void theRowIsStillThereTheFilterIsWhatRemovedIt() {
            ChatSession s = newSession(OffsetDateTime.now());
            newQaLog(s.getId(), "run-stage7-eval");

            // 反：不过滤的查询能查到它 —— 证明数据在，不是「没插进去」
            assertThat(allMySessions())
                    .as("★ 少了这条断言，上面那条「不在列表里」可能是恒真的："
                            + "插失败了、或者 sessionNo 写错了，它一样会通过")
                    .extracting(ChatSession::getSessionNo)
                    .contains(s.getSessionNo());

            // 正：过滤后的查询查不到它
            assertThat(listContains(s.getSessionNo())).isFalse();
        }

        @Test
        @DisplayName("★ 一个会话里【混着】评测行和真实行 → 整条不出现")
        void mixedSessionIsAlsoFilteredOut() {
            ChatSession s = newSession(OffsetDateTime.now());
            newQaLog(s.getId(), null);              // 先来一次真实提问
            newQaLog(s.getId(), "run-then-eval");   // 后来被评测工具复用

            assertThat(listContains(s.getSessionNo()))
                    .as("★ 判据是「有没有【过】评测行」，不是「是不是【全是】评测行」。"
                            + "同一个 sessionNo 被评测复用之后，它就不再是纯粹的『我的对话』了 —— "
                            + "而这条边界要选一侧并说清楚，选的是更保守的那一侧")
                    .isFalse();
        }

        @Test
        @DisplayName("★ 一行 qa_log 都没有的会话【仍然在】列表里")
        void sessionWithoutAnyQaLogStillAppears() {
            ChatSession s = newSession(OffsetDateTime.now());
            newMessage(s.getId(), 1, "问出去但没答上", null);
            // 故意不插 qa_log

            assertThat(listContains(s.getSessionNo()))
                    .as("★ 这条守的是判据的方向。写成 EXISTS(非评测行) 的话，"
                            + "「用户打了字但那一轮没写成 qa_log」（推送失败那条路，"
                            + "见 docs/10 阶段 7 的逐出口审计）的会话会被一起挡掉 ——"
                            + "而用户确实说过话")
                    .isTrue();
        }
    }

    // ============================================================
    // 二、★ 消息与 references
    // ============================================================

    @Nested
    @DisplayName("二、★ 消息与 references")
    class Messages {

        @Test
        @DisplayName("★ 消息按写入顺序返回，role 翻成字符串")
        void messagesInWriteOrderWithRoleNames() {
            ChatSession s = newSession(OffsetDateTime.now());
            newMessage(s.getId(), 1, "退货要几天", null);
            newMessage(s.getId(), 2, "自签收之日起 7 天内。", null);
            newMessage(s.getId(), 1, "那退款呢", null);

            List<ChatMessageView> messages = queryService.listMessages(s.getSessionNo());

            assertThat(messages).extracting(ChatMessageView::content)
                    .as("★ 用 id 升序而不是 created_at —— 同一个事务里写的两条可能同毫秒")
                    .containsExactly("退货要几天", "自签收之日起 7 天内。", "那退款呢");
            assertThat(messages).extracting(ChatMessageView::role)
                    .as("★ 翻成字符串：让 1/2/3 的映射只存在于服务端一处。"
                            + "放前端的话，漏掉一个分支不会报错，只会渲染出一个空白气泡")
                    .containsExactly("user", "assistant", "user");
        }

        @Test
        @DisplayName("★★ references 出来是【JSON 数组】，不是装着 JSON 的字符串")
        void referencesComeBackAsJsonNotAString() throws Exception {
            ChatSession s = newSession(OffsetDateTime.now());
            String stored = "[{\"no\":1,\"chunk_id\":15,\"document_id\":2,"
                    + "\"score\":0.9276,\"heading_path\":\"售后FAQ\"}]";
            newMessage(s.getId(), 2, "回答", stored);

            // 库里那一列读出来是什么：一个【装着 JSON 的字符串】
            ChatMessage raw = chatMessageService.list(
                            Wrappers.<ChatMessage>lambdaQuery()
                                    .eq(ChatMessage::getSessionId, s.getId()))
                    .get(0);
            String rawText = raw.getReferences();

            // ★★ 反：把这个字符串【原样】交给 Jackson，序列化出来是【一个带引号的字符串】
            //
            //    这才是「原样透传」真正会产生的响应体：
            //        {"references": "[{\"no\":1,...}]"}
            //    前端拿到的 references 是一个 String，不是数组。
            assertThat(objectMapper.writeValueAsString(rawText))
                    .as("★★ 开头是【引号】然后才是方括号 —— 这就是双重编码的样子。"
                            + "★ 它在 devtools 的对象 dump 里看着是对的"
                            + "（确实有 references、展开也确实看得到 no 和 chunk_id），"
                            + "只有真去取 references[0].no 的时候才会炸")
                    .startsWith("\"[");

            // ★ 正：接口出来的是真数组
            JsonNode refs = queryService.listMessages(s.getSessionNo())
                    .get(0).references();

            assertThat(refs.isArray()).isTrue();
            assertThat(refs.isTextual())
                    .as("★ 对照：它【不是】一个文本节点")
                    .isFalse();
            assertThat(objectMapper.writeValueAsString(refs))
                    .as("★ 对照：这次开头是方括号，没有那层引号")
                    .startsWith("[");
            assertThat(refs.get(0).get("no").asInt()).isEqualTo(1);
            assertThat(refs.get(0).get("chunk_id").asLong()).isEqualTo(15L);
            assertThat(refs.get(0).get("heading_path").asText()).isEqualTo("售后FAQ");
        }

        @Test
        @DisplayName("★★ JSONB 会把键序和空白都改掉 —— 所以字节比较是【非法】的判据")
        void jsonbDoesNotPreserveBytes() throws Exception {
            ChatSession s = newSession(OffsetDateTime.now());
            // 写进去的顺序：no, chunk_id, document_id, score, heading_path
            String stored = "[{\"no\":1,\"chunk_id\":15,\"document_id\":2,"
                    + "\"score\":0.9276,\"heading_path\":\"售后FAQ\"}]";
            newMessage(s.getId(), 2, "回答", stored);

            String rawText = chatMessageService.list(
                            Wrappers.<ChatMessage>lambdaQuery()
                                    .eq(ChatMessage::getSessionId, s.getId()))
                    .get(0)
                    .getReferences();

            // ★ 实测（2026-09-24，psql 直接验过一次）：
            //     写进去 [{"no":1,"chunk_id":15,...}]
            //     读回来 [{"no": 1, "score": 0.9276, "chunk_id": 15, ...}]
            //   ① 冒号后多了空格 —— 空白被规范化
            //   ② 键序变了 —— Postgres 按【键名长度升序】重排
            //      (no=2, score=5, chunk_id=8, document_id=11, heading_path=12)
            //
            // ★★★ 这和阶段 7 那个 Set.copyOf 的 SALT 坑是【同一类】：
            //   我们以为自己控制着顺序，其实没有 —— 只不过这次重排的是数据库。
            //   ⇒ 推论：凡是进 JSONB 列再读出来的东西，
            //     「用 LinkedHashMap 保证顺序」这条纪律【在往返之后就失效了】。
            //     本项目当前没有「JSONB 往返 → 拼进 prompt 前缀」的路径，
            //     所以还没有缓存命中率的问题；但这是一条该被写下来的边界。
            //
            // ★ 所以这里断言的是【语义】而不是字节：
            //   字节比较会把「Postgres 的实现细节」当成「我们的契约」，
            //   而那正是坑 19（把 JVM 的内部桶序当成自己的承诺）的另一种形态。
            assertThat(objectMapper.readTree(rawText))
                    .as("★ 只承诺语义：读回来的 JSON【等价于】写进去的那个")
                    .isEqualTo(objectMapper.readTree(stored));

            // ⚠️ 这里【刻意不写】assertThat(rawText).isNotEqualTo(stored)。
            //    那会把「字节确实不同」变成一个要求 —— 而它不是我们的契约，
            //    是 Postgres 当前的实现。哪天它改成保留原始字节，
            //    这条断言就会红，而那个红不表示任何东西坏了。
            //    ★ 坑 19 的判据：只能断言我们承诺过的东西，不是观察到的东西。
        }

        @Test
        @DisplayName("没有引用时是 null —— 不是空数组")
        void noReferencesIsNull() {
            ChatSession s = newSession(OffsetDateTime.now());
            newMessage(s.getId(), 1, "你好", null);

            assertThat(queryService.listMessages(s.getSessionNo()).get(0).references())
                    .as("★ 沿用 serializeReferences 那条约定的反方向："
                            + "没有引用就是 NULL。空数组会让人分不清"
                            + "「没检索」和「检索了但零召回」")
                    .isNull();
        }

        @Test
        @DisplayName("★★ 会话不存在 → 抛异常，【不】回空列表")
        void unknownSessionThrows() {
            assertThatThrownBy(() -> queryService.listMessages(PREFIX + "根本不存在"))
                    .as("★ 回空列表是最糟的一种：它把「没有这个会话」和"
                            + "「这个会话没有消息」渲染成同一个响应，"
                            + "而这两件事的修法完全相反")
                    .isInstanceOf(ChatSessionNotFoundException.class)
                    .hasMessageContaining(PREFIX + "根本不存在");
        }

        @Test
        @DisplayName("★ 评测会话的消息【照常返回】—— 和列表故意不一致")
        void evalSessionMessagesAreNotFiltered() {
            ChatSession s = newSession(OffsetDateTime.now());
            newMessage(s.getId(), 1, "商品支持七天无理由退货吗", null);
            newQaLog(s.getId(), "run-stage7-eval");

            assertThat(queryService.listMessages(s.getSessionNo()))
                    .as("★ 排除评测流量是【列表这个读模型】的属性，不是会话的属性。"
                            + "这是按精确键查找、不是浏览 —— "
                            + "一个确实存在的行却被回 404，会让人以为数据丢了")
                    .hasSize(1);
        }
    }

    // ============================================================
    // 三、limit 的夹紧
    // ============================================================

    @Nested
    @DisplayName("三、limit 的夹紧")
    class LimitClamping {

        /**
         * 造两个「一定排在最前面」的会话。
         *
         * <p>★ 用<b>未来时间</b>是为了让断言不依赖「库里现在有多少会话」——
         * 否则 {@code limit=1} 返回的是哪一条就成了「当前数据」的函数，
         * 而这个库里有 4000 多个会话，其中大多数的时间戳是「当时」。
         */
        private ChatSession[] twoFutureSessions() {
            OffsetDateTime base = OffsetDateTime.now().plusYears(1);
            ChatSession older = newSession(base);
            ChatSession newer = newSession(base.plusDays(1));
            newMessage(older.getId(), 1, "较早", null);
            newMessage(newer.getId(), 1, "较晚", null);
            return new ChatSession[]{older, newer};
        }

        @Test
        @DisplayName("★ limit=1 返回【最近活跃】的那一个")
        void limitOneReturnsTheMostRecent() {
            ChatSession[] pair = twoFutureSessions();

            List<ChatSessionSummary> top = queryService.listRealSessions(1);

            assertThat(top).hasSize(1);
            assertThat(top.get(0).sessionNo())
                    .as("★ 这条同时验了两件事：LIMIT 真的生效了，"
                            + "而且 ORDER BY 的【方向】是对的（倒序，不是正序）")
                    .isEqualTo(pair[1].getSessionNo());
        }

        @Test
        @DisplayName("★ limit=0（非法）当【没指定】处理，与默认条数得到同一批")
        void zeroLimitFallsBackToDefault() {
            twoFutureSessions();

            List<String> asZero = queryService.listRealSessions(0).stream()
                    .map(ChatSessionSummary::sessionNo).toList();
            List<String> asDefault = queryService
                    .listRealSessions(ChatHistoryQueryService.DEFAULT_LIMIT).stream()
                    .map(ChatSessionSummary::sessionNo).toList();

            assertThat(asZero)
                    .as("★ 断言的是「0 和默认得到同一批」，而不是「0 得到 30 条」—— "
                            + "后者把 DEFAULT_LIMIT 这个常量抄进了测试，"
                            + "改常量时测试会红，但那个红不表示任何东西坏了")
                    .isEqualTo(asDefault)
                    .isNotEmpty();
        }

        @Test
        @DisplayName("★ limit=3 就是 3 条 —— 不是「永远返回默认条数」")
        void smallLimitIsHonoured() {
            twoFutureSessions();

            assertThat(queryService.listRealSessions(3))
                    .as("★ 对照：上面那条只证明了「0 等于默认」，"
                            + "一个把 limit 全部忽略的实现也能通过它")
                    .hasSize(3);
        }
    }
}
