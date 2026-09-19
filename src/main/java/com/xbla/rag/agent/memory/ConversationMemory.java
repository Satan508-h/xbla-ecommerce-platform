package com.xbla.rag.agent.memory;

import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.config.ChatProperties;
import com.xbla.rag.entity.ChatMessage;
import com.xbla.rag.entity.ChatSummary;
import com.xbla.rag.service.ChatMessageService;
import com.xbla.rag.service.ChatSummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话记忆的读取端 —— 把「更早的摘要」和「最近 N 轮原文」拼成一份上下文，交给生成模型。
 *
 * <pre>
 *   ├──────────── 摘要（5.6）────────────┼──────── 窗口原文（5.5）────────┤
 *   msg 1 ....................... msg N  │  msg N+1 ............... msg M
 * </pre>
 *
 * <h2>一、★ 历史只喂给【生成】，不喂给分类</h2>
 *
 * <p>这是 5.5 的核心取舍，值得说清楚，因为它和「直觉上应该更聪明」的方向相反。
 *
 * <p>最该用历史的地方看起来是<b>意图分类</b>：有了上文，
 * 「那个怎么样」就能被消解成「星辰X1 怎么样」，于是不用澄清。
 * 5.3 也确实在 {@code ClarificationDecider} 上预留了一个 {@code history} 参数，
 * 注释写着「5.5 上来之后，这里先尝试用 history 消解指代」。
 *
 * <p><b>但那个预留的位置是错的。</b> {@code ClarificationDecider} 是一个
 * <b>纯判断器</b>（输入分类结果，输出「澄清 / 不澄清」），它<b>消解不了指代</b> ——
 * 「那个」到底指什么，只有<b>分类器</b>能回答。真正可行的链路是：
 *
 * <pre>
 *   历史里有「星辰X1 怎么样」
 *     ↓
 *   分类器看到历史 → 直接判成 SCENARIO_PICK
 *     ↓
 *   根本不是 CLARIFY 分支 → 决策器压根不会被问到
 * </pre>
 *
 * <p>5.5 <b>没有</b>走这条路（用户 2026-09-19 拍板：历史只给生成）。
 * 5.6 <b>同样没有</b>改这个决定 —— 摘要也只进 prompt，不进分类器。
 * 所以：
 * <ul>
 *   <li>{@code LlmIntentClassifier} 的签名<b>不变</b>，仍然只看本轮问题；</li>
 *   <li>{@code ClarificationDecider} 的三参数版本<b>仍然收不到历史</b>，
 *       那条预留的注释已按实情改写（见该类）；</li>
 *   <li>{@code ClarificationDeciderTest} 里那条「5.5 上线后应该变成
 *       {@code isFalse()}}」的断言<b>保持 {@code isTrue()}</b> ——
 *       那个预言被这次取舍推翻了，不是被实现了。</li>
 * </ul>
 *
 * <p>代价是明确的：<b>「那个怎么样」在有上下文时仍然会触发澄清反问</b>
 * （实测最自然的追问方式 2/3 被挡掉，见 {@code probe_memory.py}）。
 * 收益是这一项不碰分类链路 —— 5.2 的 95% 准确率和 5.4 的
 * 「20/20 可证明安全」都建立在「分类器的输入只有这一句话」之上，
 * 往它的输入里加历史会让那两条结论同时失效，而现有的评测集
 * <b>测不出</b>这个变化是好是坏。
 *
 * <h2>二、★ 为什么「读记忆」和「落用户消息」的顺序不能反</h2>
 *
 * <p>{@code ChatServiceImpl} 里的顺序<b>必须</b>是：
 *
 * <pre>
 *   resolveSession → 【读记忆】→ saveUserMessage → 分类 → 检索 → 生成 → 【丢压缩任务】
 * </pre>
 *
 * <p>{@code ChatRequest.history} 的契约是「历史对话（<b>不含本轮提问</b>）」。
 * 先把本轮问题写进库再读，读出来的窗口里就有一份本轮的提问 ——
 * 而它马上又会作为 {@code userQuestion} 再发一次。
 * 结果是模型收到<b>两条一模一样的用户消息</b>，中间夹着上一条助手回复。
 *
 * <p>这个 bug 的特征是「模型偶尔答非所问」，<b>而且从日志和落库数据上
 * 都看不出来</b> —— 两边各自都是对的，错的是它们的相对顺序。
 *
 * <p>另一种做法是「读的时候排除掉本轮那条消息」，但那需要把
 * 「本轮用户消息的 id」从 {@code saveUserMessage} 一路传回来。
 * 多一条贯穿的返回值换 1ms 的查询，不值 —— 而且顺序本身就是最好的约束。
 *
 * <h2>三、★★ 孤儿用户消息：末尾连续的都要丢，不是只丢一条</h2>
 *
 * <p>如果某一轮在 {@code saveUserMessage} 之后、{@code saveAssistantMessage}
 * 之前挂了（模型链路失败、进程被杀），库里就留下一条落单的用户消息。
 * 而且这是<b>刻意</b>的 —— {@code ChatServiceImpl} 类注释第 ① 条写明
 * 「失败时不写助手消息」，因为硬塞一句「服务异常」会污染阶段 7 的评测语料。
 *
 * <p>所以清理必须在<b>读的这一侧</b>。它必须被丢掉，因为 OpenAI 兼容协议的
 * {@code messages} 数组里<b>连续两条 user 消息是畸形输入</b> ——
 * 轻则被服务端拒绝（400），重则模型把两条拼在一起理解。
 * 而 400 在降级链里是「不降级」的那一类（见 {@code ChatModelRouter}），
 * 所以整次问答会直接失败。
 *
 * <h3>★ 只丢最新那一条是不够的 —— 这是个会让会话【永久死掉】的 bug</h3>
 *
 * <pre>
 *   第 N   轮失败 → 留下孤儿 O_N
 *   第 N+1 轮失败 → 读的时候丢掉 O_{N+1}（它是新的）
 *                   但 O_N 还在，而它现在是历史的【末尾】
 *                   → history 以 user 结尾，再拼上本轮问题
 *                   → 连续两条 user → 400 → 不降级 → 这一轮也失败
 *                   → 留下 O_{N+2} …… 每一轮都重演
 * </pre>
 *
 * <p><b>两次连续失败之后，这个会话就再也答不出话了</b>，
 * 而单次失败（最常见的形态）完全看不出来。
 *
 * <p>修法就是这一条：<b>从末尾往前，丢掉所有连续的 user 消息。</b>
 * 历史必须以 assistant 结尾，或者为空 —— 这是它能被拼进
 * {@code messages} 数组的充分必要条件。
 * （{@link #dropTrailingOrphans}，回归护栏见
 * {@code ConversationMemoryIntegrationTest} 的「三、孤儿用户消息」。）
 *
 * <h2>四、★★ 接缝锚点 W：宁可重叠，绝不空洞</h2>
 *
 * <p>{@link SessionSummarizer} 推进摘要游标时，锚点是一个叫 {@code W} 的值 ——
 * <b>原始的最新 {@code 2×maxTurns} 条里的第一条</b>（也就是不丢孤儿时窗口的起点）。
 * 摘要的 {@code end_message_id} 必须 ≤ {@code W - 1}。
 *
 * <p>★ 关键之处在于：{@code W} 是按<b>原始条数</b>算的，<b>不随孤儿的数量漂移</b>。
 * 而真正发给模型的窗口在丢掉孤儿之后，起点只可能落在 {@code W} 或更早。
 * 两者一起看：
 *
 * <pre>
 *   孤儿数 k = 0  →  窗口起点 == W        →  摘要和窗口【严丝合缝】
 *   孤儿数 k ≥ 1  →  窗口起点 &lt; W      →  摘要和窗口【重叠】若干条
 * </pre>
 *
 * <p><b>两种都不会产生空洞</b>，而重叠（模型把同一条消息看两遍）是无害的。
 * 反过来的设计 —— 让 W 跟着孤儿走 —— 会让窗口起点漂到 W 之后，
 * 于是 {@code (W, 窗口起点)} 之间那几条消息<b>既不在摘要里、也不在窗口里</b>，
 * <b>永远不会被补上</b>，而且没有任何报错。
 *
 * <p>这条「宁可重叠，绝不空洞」的取舍由
 * {@code ConversationMemoryIntegrationTest.SummaryLayer} 和
 * {@code SessionSummarizerIntegrationTest.Seam} 两侧共同钉住。
 *
 * <p>⚠️ 代价是：孤儿自己会<b>谁都不覆盖</b>（掉出了窗口，又没被压进摘要）。
 * 这是刻意的 —— 它是一次失败请求的残骸，而不是对话内容。
 *
 * <h2>五、★ 摘要和窗口必须来自同一次调用</h2>
 *
 * <p>两者是同一条时间轴上相邻的两段，接缝必须严丝合缝。分两次读的话，
 * 中间可能有新消息落库，接缝就会错位。
 *
 * <p>所以本方法一次产出 {@link MemoryContext}（摘要 + 窗口），
 * 并且<b>先读窗口、后读摘要</b> —— 这样即使真有并发写入，
 * 最坏结果也只是「摘要偏旧」（和窗口重叠，模型看到两遍），
 * 而不是「摘要偏新」（侵入窗口，{@link #loadSummarySafely} 会丢弃它）。
 * 两个方向都不会产生空洞。
 *
 * <h2>六、它是「尽力而为」的，永不抛异常</h2>
 *
 * <p>历史或摘要读不出来不该让这次问答失败 —— 退化成「没有上下文的一轮」
 * 比一个 500 好得多。同 {@code RetrievalPipeline} 的失败语义。
 */
@Component
public class ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);

    /** {@code chat_message.role} 的取值，与表上的 CHECK 约束一致 */
    private static final int ROLE_USER = 1;
    private static final int ROLE_ASSISTANT = 2;

    /** {@code chat_summary.summary_level}：1 = 段落级摘要（5.6 唯一会写的那一档） */
    private static final short LEVEL_SECTION = 1;

    /**
     * 单条历史消息拼进 prompt 的字符上限。
     *
     * <p><b>这是防御，不是策略。</b>实测真实数据里助手消息最长 352 字，
     * 而 {@code max-tokens} 是 2048 —— 正常情况下永远轮不到它。
     *
     * <p>但如果那个配置被调大、或者换了个话更多的模型，
     * <b>10 轮 × 几千字</b>会悄悄把 prompt 撑到几十 K token：
     * 不报错、只是变贵变慢，而且从「回答质量」上看不出因果。
     * 这里保证最坏情况有界。
     *
     * <p>同一个上限见 {@code RagPromptBuilder.MAX_CHARS_PER_CHUNK}。
     * 两者都<b>静默截断</b>，因为触发即代表上游出了意料之外的事 ——
     * 所以这里额外打一条 WARN（见 {@link #toTurns}）。
     */
    private static final int MAX_CHARS_PER_TURN = 1000;

    private static final String TRUNCATED_SUFFIX = "…（已截断）";

    private final ChatMessageService chatMessageService;
    private final ChatSummaryService chatSummaryService;
    private final ChatProperties properties;

    public ConversationMemory(ChatMessageService chatMessageService,
                              ChatSummaryService chatSummaryService,
                              ChatProperties properties) {
        this.chatMessageService = chatMessageService;
        this.chatSummaryService = chatSummaryService;
        this.properties = properties;
    }

    /**
     * 读某个会话的记忆（更早的摘要 + 最近若干轮原文）。
     *
     * @param sessionId 会话主键。为 {@code null} 时返回 {@link MemoryContext#EMPTY}
     * @return 摘要与窗口原文。<b>永不返回 null</b>；
     *         没有记忆、开关关闭、或读取失败时返回没有内容的 {@link MemoryContext}
     */
    public MemoryContext load(Long sessionId) {
        if (sessionId == null) {
            return MemoryContext.EMPTY;
        }
        ChatProperties.History config = properties.getHistory();
        if (!config.isEnabled()) {
            return MemoryContext.EMPTY;
        }

        int maxTurns = config.getMaxTurns();
        if (maxTurns <= 0) {
            // 配成 0 或负数 = 关掉窗口。★ 不抛异常也不当成「无限」
            log.warn("xbla.chat.history.max-turns={} 不是正数，本次不带历史", maxTurns);
            return MemoryContext.EMPTY;
        }

        try {
            return loadOrThrow(sessionId, maxTurns);
        } catch (Exception e) {
            // ★ 记忆读不出来不该让整次问答失败 —— 退化成「没有上下文的一轮」
            log.warn("读取会话记忆失败，本次问答将不带上下文 sessionId={}: {}",
                    sessionId, e.getMessage(), e);
            return MemoryContext.EMPTY;
        }
    }

    private MemoryContext loadOrThrow(Long sessionId, int maxTurns) {
        int messageLimit = maxTurns * 2;

        // 多取一条：如果最新的那条是孤儿用户消息（见类注释第三节），
        // 丢掉它之后仍然能凑满 maxTurns 轮
        List<ChatMessage> newestFirst = chatMessageService.lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                // ★ role 在 SQL 里过滤，不在 Java 里筛：
                //   少读几行，也少一类「筛的时候漏了一种角色」的可能
                .in(ChatMessage::getRole, ROLE_USER, ROLE_ASSISTANT)
                // ★ 走 idx_chat_message_session (session_id, id)，是主键范围倒序扫描。
                //   注意这里【不能】用 created_at 排序 —— 同一毫秒内的两条消息
                //   顺序会不确定，而那正好是「用户消息和助手回复谁在前」
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + (messageLimit + 1))
                .list();

        if (newestFirst.isEmpty()) {
            return MemoryContext.EMPTY;
        }

        List<ChatMessage> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);

        // ── ① 接缝锚点 W ──
        //    ★★ 调 windowStartId() 而不是就地算一遍：那个定义必须是【单一出处】。
        //       就地算的版本本身没错（就是这个方法里去掉查询、直接用手里这份列表），
        //       但摘要层要用同一个值，而它手里没有这份列表 —— 两边各写一份
        //       「碰巧一样」的表达式，正是会有消息悄悄消失的那种耦合。
        //       代价是每次问答多一次 LIMIT 1 的索引扫描，亚毫秒。
        Long anchor = windowStartId(sessionId);
        long windowFirstId = anchor == null ? oldestFirst.get(0).getId() : anchor;

        // ── ② 丢掉末尾连续的孤儿，再裁到 messageLimit 条 ──
        //    顺序是这样而不是反过来：先丢孤儿能让窗口【更满】。
        //    （先裁再丢的话，被孤儿占掉的那一格不会补回来，窗口凭空少半轮。）
        //    代价是窗口起点可能比 W 早一点 —— 那只会造成重叠，不会造成空洞
        List<ChatMessage> content = dropTrailingOrphans(oldestFirst);
        if (content.size() > messageLimit) {
            content = content.subList(content.size() - messageLimit, content.size());
        }

        // ── ③ 最后读摘要（见类注释第五节：先窗口后摘要）──
        String summary = loadSummarySafely(sessionId, windowFirstId);

        return new MemoryContext(summary, toTurns(content));
    }

    /**
     * 接缝锚点 {@code W} —— 原始最新 {@code 2×maxTurns} 条消息里的<b>第一条</b>的 id。
     *
     * <pre>
     *   ├──────── 摘要（end &le; W-1）────────┼──────── 窗口（从 W 起）────────┤
     * </pre>
     *
     * <p>★ 它是<b>整条双层记忆链路上唯一的那个「接缝在哪里」的定义</b>。
     * 摘要层（{@link SessionSummarizer}）拿它决定游标推到哪，
     * 窗口层（{@link #loadOrThrow}）拿它做越界检查，调试探针拿它算空洞 ——
     * 三处调的是同一个方法。<b>不要在任何地方就地再算一遍这个表达式</b>
     * （除了 {@code loadOrThrow} 那个「手里正好有列表」的特例，它有测试钉着）。
     *
     * <p>⚠️ 它<b>不是</b>「第一个掉出窗口的消息」（那要再加一条）。
     * 差一位的后果是摘要少推一格，而那条消息既不在摘要里也不在窗口里。
     *
     * @return 会话还不够 {@code 2×maxTurns} 条消息时返回 {@code null}（窗口没满）
     */
    public Long windowStartId(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        int maxTurns = properties.getHistory().getMaxTurns();
        if (maxTurns <= 0) {
            return null;
        }
        ChatMessage first = chatMessageService.lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .in(ChatMessage::getRole, ROLE_USER, ROLE_ASSISTANT)
                .orderByDesc(ChatMessage::getId)
                // 倒序第 (2×maxTurns) 条。不用 MyBatis-Plus 的 page() ——
                // 那会先跑一条 COUNT(*)，而这里根本不需要总数
                .last("LIMIT 1 OFFSET " + (maxTurns * 2 - 1))
                .one();
        return first == null ? null : first.getId();
    }

    /**
     * 从末尾往前丢掉所有连续的 user 消息。
     *
     * <p>判据很直接：<b>最新一条是用户消息 = 它的回答从来没落过库</b>。
     * 连着的都算 —— 两次连续失败就会连着两条（见类注释第三节）。
     *
     * <p>⚠️ 不要「优化」成只丢一条。那正是修之前的写法，
     * 而它的失败模式是「两次失败之后这个会话永久答不出话」。
     */
    private static List<ChatMessage> dropTrailingOrphans(List<ChatMessage> window) {
        int end = window.size();
        while (end > 0) {
            Integer role = window.get(end - 1).getRole();
            if (role == null || role != ROLE_USER) {
                break;
            }
            end--;
        }
        if (end == window.size()) {
            return window;
        }
        if (end == 0) {
            // 整个窗口都是孤儿（连续失败了很多轮）。退回空历史 ——
            // 比发一串连续 user 消息出去强
            return List.of();
        }
        return window.subList(0, end);
    }

    /**
     * 读摘要，并检查它<b>没有侵入窗口</b>。
     *
     * <p>摘要是「窗口之前的消息」的压缩，所以它的游标必须严格小于窗口起点。
     * 违反了就丢弃这份摘要 —— 重叠（模型看到两遍）其实无害，
     * 但这条不变式是整个双层记忆的地基，静默违反意味着别的地方也错了，
     * 而那种错在回答质量上完全看不出来。
     *
     * <p>读失败同样只记日志、返回 null。摘要层的失败不该影响问答。
     */
    private String loadSummarySafely(Long sessionId, long windowFirstId) {
        if (!properties.getSummary().isEnabled()) {
            // ★ 关掉时【不读】而不是「读了不用」——
            //   否则阶段 7 分不清「没开摘要」和「开了但没摘要」
            return null;
        }
        try {
            ChatSummary row = chatSummaryService.lambdaQuery()
                    .eq(ChatSummary::getSessionId, sessionId)
                    .eq(ChatSummary::getSummaryLevel, LEVEL_SECTION)
                    .orderByDesc(ChatSummary::getEndMessageId)
                    .last("LIMIT 1")
                    .one();

            if (row == null || row.getSummary() == null || row.getSummary().isBlank()) {
                return null;
            }
            if (row.getEndMessageId() >= windowFirstId) {
                log.warn("★ 摘要游标 {} 侵入了窗口起点 {}，丢弃这份摘要 sessionId={} —— "
                                + "双层记忆的接缝不变式被破坏了，检查 SessionSummarizer 的取数口径",
                        row.getEndMessageId(), windowFirstId, sessionId);
                return null;
            }
            return row.getSummary();
        } catch (Exception e) {
            log.warn("读取会话摘要失败，本次只用窗口原文 sessionId={}: {}",
                    sessionId, e.getMessage(), e);
            return null;
        }
    }

    private static List<ChatRequest.Turn> toTurns(List<ChatMessage> messages) {
        List<ChatRequest.Turn> turns = new ArrayList<>(messages.size());
        int truncated = 0;

        for (ChatMessage message : messages) {
            ChatRequest.Role role = toRole(message.getRole());
            if (role == null) {
                // SQL 已经限定 role ∈ {1,2}，走到这里说明有人改了那条查询
                // 却没改这里 —— 打 WARN 而不是静默跳过，因为「少一条历史」
                // 从回答质量上完全看不出来
                log.warn("会话历史里出现未预期的 role={}，已跳过 messageId={}",
                        message.getRole(), message.getId());
                continue;
            }
            String content = message.getContent();
            if (content == null) {
                continue;
            }
            if (content.length() > MAX_CHARS_PER_TURN) {
                content = content.substring(0, MAX_CHARS_PER_TURN) + TRUNCATED_SUFFIX;
                truncated++;
            }
            turns.add(new ChatRequest.Turn(role, content));
        }

        if (truncated > 0) {
            log.warn("★ 有 {} 条历史消息超过 {} 字被截断 —— 正常数据不该这么长"
                            + "（实测助手消息最长 352 字），可能是 max-tokens 被调大或换了模型",
                    truncated, MAX_CHARS_PER_TURN);
        }
        return List.copyOf(turns);
    }

    /**
     * {@code chat_message.role} → {@link ChatRequest.Role}。
     *
     * <p>不用 {@code role == 1 ? USER : ASSISTANT} 这种三元式：那样
     * 一个意料之外的 role 会被<b>静默当成助手消息</b>发给模型，
     * 表现为「模型把一句系统提示当成自己说过的话」——
     * 而那种错在 prompt 里看不出来。
     */
    private static ChatRequest.Role toRole(Integer role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case ROLE_USER -> ChatRequest.Role.USER;
            case ROLE_ASSISTANT -> ChatRequest.Role.ASSISTANT;
            default -> null;
        };
    }
}
