package com.xbla.rag.controller;

import com.xbla.rag.agent.memory.ConversationMemory;
import com.xbla.rag.agent.memory.MemoryContext;
import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.config.ChatProperties;
import com.xbla.rag.entity.ChatMessage;
import com.xbla.rag.entity.ChatSession;
import com.xbla.rag.entity.ChatSummary;
import com.xbla.rag.service.ChatMessageService;
import com.xbla.rag.service.ChatSessionService;
import com.xbla.rag.service.ChatSummaryService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话记忆的调试探针（阶段 5.6）。
 *
 * <p>⚠️ {@code @Profile("local")} —— 和其它几个探针一样只在本地存在。
 * 它只读，不调模型，所以不像 {@code /classify} 那样花钱。
 *
 * <h2>为什么需要它（{@code chat_summary} 那一行本身不够）</h2>
 *
 * <p>直接 {@code SELECT * FROM chat_summary} 看到的是
 * {@code start_message_id / end_message_id / summary} 三个值 ——
 * <b>但它们对不对，单看是看不出来的</b>。判据是<b>接缝</b>：
 *
 * <pre>
 *   ├──────── 摘要 ────────┼──────── 窗口原文 ────────┤
 *   msg 1 ......... msg N  │  msg N+1 ......... msg M
 *                          ↑
 *              摘要的游标必须正好停在这里
 * </pre>
 *
 * <p>要验这条，得<b>同时</b>拿到摘要的游标和窗口的起点 —— 那正是本接口拼的东西，
 * 而且两个值都来自线上同一套代码（{@link ConversationMemory#windowStartId}），
 * 不是探针自己再算一遍。同 {@code /intent-tree} 之于 5.1。
 *
 * <h2>★ 接缝那一块的数字怎么读</h2>
 *
 * <p>先记住整条链路上唯一的那个判据：
 *
 * <pre>
 *   摘要覆盖 [start, coveredEnd]  ‖  窗口覆盖 [windowStartId, 最新]
 * </pre>
 *
 * <p>两者之间<b>不允许有谁都不覆盖的消息</b>。于是：
 *
 * <ul>
 *   <li>{@code pending = windowStartId - coveredEnd - 1}
 *       —— 已掉出窗口、但还没被压进摘要的条数。<b>它非零是正常的</b>，
 *       而且必然非零：压缩是<b>异步</b>的，还要走一次 1–2 秒的模型调用，
 *       这期间对话早就往前走了。压缩每成功一次，游标就追到当时的窗口起点。</li>
 *
 *   <li>{@code tight = (coveredEnd == windowStartId - 1)}
 *       —— 严丝合缝。压缩任务跑完、且没有新消息进来的那一刻就是它。</li>
 *
 *   <li>{@code overlap} —— 模型把几条消息看两遍。<b>无害</b>，
 *       由窗口末尾的孤儿用户消息造成（见 {@code ConversationMemory} 类注释第四节）。
 *       注意它和 {@code pending} 是<b>两个方向</b>：
 *       {@code pending} 是「摘要落后」欠着的，{@code overlap} 是「摘要多吃了」。</li>
 *
 *   <li>{@code verdict} —— 把 {@code pending} 翻译成一句话，判据是
 *       <b>积压有没有超过一整个窗口（{@code 2×max-turns} 条）</b>。
 *       超过就意味着「摘要兜不住的那一段，已经比窗口本身还长了」——
 *       那时候模型看到的历史里有一个比窗口还大的洞。</li>
 * </ul>
 *
 * <p>⚠️ 别把 {@code pending} 当成「空洞」。它在正常运行时<b>必然非零</b>，
 * 而且下一次压缩就会把它吃掉。真正要警惕的是它<b>持续变大</b>：
 * 游标一旦冻住，每聊一轮 pending 就 +2，一路涨上去。
 */
@RestController
@RequestMapping("/api/debug/agent")
@Profile("local")
public class MemoryProbeController {

    private static final int ROLE_USER = 1;
    private static final int ROLE_ASSISTANT = 2;
    private static final short LEVEL_SECTION = 1;

    private final ConversationMemory conversationMemory;
    private final ChatMessageService chatMessageService;
    private final ChatSummaryService chatSummaryService;
    private final ChatSessionService chatSessionService;
    private final ChatProperties properties;

    public MemoryProbeController(ConversationMemory conversationMemory,
                                 ChatMessageService chatMessageService,
                                 ChatSummaryService chatSummaryService,
                                 ChatSessionService chatSessionService,
                                 ChatProperties properties) {
        this.conversationMemory = conversationMemory;
        this.chatMessageService = chatMessageService;
        this.chatSummaryService = chatSummaryService;
        this.chatSessionService = chatSessionService;
        this.properties = properties;
    }

    /**
     * 看某个会话的记忆状态。
     *
     * @param sessionNo 会话号。和 {@code sessionId} 二选一
     * @param sessionId 会话主键。方便直接拿 psql 查到的 id 来看
     */
    @GetMapping("/memory")
    public Map<String, Object> memory(
            @RequestParam(value = "sessionNo", required = false) String sessionNo,
            @RequestParam(value = "sessionId", required = false) Long sessionId) {

        Map<String, Object> response = new LinkedHashMap<>();

        ChatSession session = resolveSession(sessionNo, sessionId);
        if (session == null) {
            response.put("error", "找不到这个会话。传 sessionNo 或 sessionId");
            return response;
        }
        Long id = session.getId();
        response.put("sessionId", id);
        response.put("sessionNo", session.getSessionNo());

        // ── 配置（读的时候以配置为准，不是以写的时候）──
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("historyEnabled", properties.getHistory().isEnabled());
        config.put("maxTurns", properties.getHistory().getMaxTurns());
        config.put("summaryEnabled", properties.getSummary().isEnabled());
        config.put("triggerMessages", properties.getSummary().getTriggerMessages());
        config.put("maxChars", properties.getSummary().getMaxChars());
        response.put("config", config);

        // ── 底账 ──
        long totalMessages = chatMessageService.lambdaQuery()
                .eq(ChatMessage::getSessionId, id)
                .in(ChatMessage::getRole, ROLE_USER, ROLE_ASSISTANT)
                .count();
        response.put("totalMessages", totalMessages);

        // ── 摘要 ──
        ChatSummary summary = chatSummaryService.lambdaQuery()
                .eq(ChatSummary::getSessionId, id)
                .eq(ChatSummary::getSummaryLevel, LEVEL_SECTION)
                .orderByDesc(ChatSummary::getEndMessageId)
                .last("LIMIT 1")
                .one();

        Long coveredEnd = summary == null ? null : summary.getEndMessageId();

        Map<String, Object> summarySection = new LinkedHashMap<>();
        summarySection.put("exists", summary != null);
        if (summary != null) {
            summarySection.put("startMessageId", summary.getStartMessageId());
            summarySection.put("endMessageId", summary.getEndMessageId());
            summarySection.put("chars", summary.getSummary() == null
                    ? 0 : summary.getSummary().length());
            // ★ token_count 恒为 NULL：它那一列的含义是「这段摘要占多少 prompt 预算」，
            //   而 completionTokens 含推理 token（实测占输出 87%），存进去会系统性偏大。
            //   见 SessionSummarizer.write 的注释
            summarySection.put("tokenCount", summary.getTokenCount());
            summarySection.put("summary", summary.getSummary());
            summarySection.put("updatedAt", String.valueOf(summary.getCreatedAt()));
        }
        response.put("summary", summarySection);

        // ── 接缝：锚点来自线上同一套代码 ──
        Long windowStartId = conversationMemory.windowStartId(id);
        Map<String, Object> seam = new LinkedHashMap<>();
        seam.put("windowStartId", windowStartId);
        seam.put("coveredEnd", coveredEnd);
        if (windowStartId == null) {
            seam.put("verdict", "窗口还没满（消息数 < 2×max-turns），没有任何东西掉出来");
        } else {
            long pending = coveredEnd == null
                    ? countBetween(id, null, windowStartId)
                    : countBetween(id, coveredEnd, windowStartId);
            int windowMessages = properties.getHistory().getMaxTurns() * 2;

            seam.put("pending", pending);
            seam.put("tight", coveredEnd != null && coveredEnd == windowStartId - 1);
            if (coveredEnd != null) {
                // overlap > 0 无害（同一条消息看两遍），见类注释
                seam.put("overlap", Math.max(0, (int) (coveredEnd - windowStartId + 1)));
            }
            seam.put("verdict", pending == 0
                    ? "严丝合缝：摘要和窗口正好接上"
                    : pending < windowMessages
                            ? "正常：这 " + pending + " 条是压缩还没来得及处理的，下次压缩会吃掉"
                            : "★ 积压：未压缩的已经超过一整个窗口（" + windowMessages
                                    + " 条）—— 查日志里的「会话摘要生成失败」"
                                    + "和「摘要任务被线程池拒绝」");
        }
        response.put("seam", seam);

        // ── 模型实际会看到的（走线上同一条读路径）──
        MemoryContext ctx = conversationMemory.load(id);
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("summaryUsed", ctx.hasSummary() ? ctx.sessionSummary() : null);
        history.put("turnCount", ctx.history().size());
        List<Map<String, Object>> preview = new ArrayList<>();
        for (ChatRequest.Turn turn : ctx.history()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", turn.role().name());
            m.put("content", abridge(turn.content()));
            preview.add(m);
        }
        history.put("turns", preview);
        response.put("whatTheModelSees", history);

        return response;
    }

    private ChatSession resolveSession(String sessionNo, Long sessionId) {
        if (sessionId != null) {
            return chatSessionService.getById(sessionId);
        }
        if (sessionNo != null && !sessionNo.isBlank()) {
            return chatSessionService.lambdaQuery()
                    .eq(ChatSession::getSessionNo, sessionNo)
                    .last("LIMIT 1")
                    .one();
        }
        // 都没传 —— 给最后一个活跃会话，正好是「刚跑完探针想看那个」
        return chatSessionService.lambdaQuery()
                .orderByDesc(ChatSession::getLastActiveAt)
                .last("LIMIT 1")
                .one();
    }

    /**
     * 数 (fromExclusive, toExclusive) 之间的消息条数。
     *
     * @param fromExclusive 为 null 表示从会话开头算起
     */
    private long countBetween(Long sessionId, Long fromExclusive, long toExclusive) {
        return chatMessageService.lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .in(ChatMessage::getRole, ROLE_USER, ROLE_ASSISTANT)
                .gt(fromExclusive != null, ChatMessage::getId, fromExclusive)
                .lt(ChatMessage::getId, toExclusive)
                .count();
    }

    /** 单条预览的截断长度 —— 摘要正文可能几百字，全打出来会把接口输出淹掉 */
    private static String abridge(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= 60 ? content : content.substring(0, 60) + "…";
    }
}
