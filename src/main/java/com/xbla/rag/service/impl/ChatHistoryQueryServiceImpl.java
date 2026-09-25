package com.xbla.rag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.dto.ChatMessageView;
import com.xbla.rag.dto.ChatSessionSummary;
import com.xbla.rag.entity.ChatMessage;
import com.xbla.rag.entity.ChatSession;
import com.xbla.rag.mapper.ChatSessionMapper;
import com.xbla.rag.service.ChatHistoryQueryService;
import com.xbla.rag.service.ChatMessageService;
import com.xbla.rag.service.ChatSessionNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link ChatHistoryQueryService} 的实现。
 *
 * <p>★ 这个类里<b>没有任何写操作</b> —— 它是只读的。理由见接口注释。
 */
@Slf4j
@Service
public class ChatHistoryQueryServiceImpl implements ChatHistoryQueryService {

    /** {@code chat_message.role} 的取值，与 V5 迁移里的 CHECK 约束一致 */
    private static final int ROLE_USER = 1;
    private static final int ROLE_ASSISTANT = 2;
    private static final int ROLE_SYSTEM = 3;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;

    public ChatHistoryQueryServiceImpl(ChatSessionMapper chatSessionMapper,
                                       ChatMessageService chatMessageService,
                                       ObjectMapper objectMapper) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageService = chatMessageService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ChatSessionSummary> listRealSessions(int limit) {
        int safeLimit = clampLimit(limit);
        List<ChatSession> sessions = chatSessionMapper.selectRealSessions(safeLimit);

        log.debug("会话列表：请求 limit={} 实际 limit={} 命中 {} 条",
                limit, safeLimit, sessions.size());

        return sessions.stream()
                .map(ChatHistoryQueryServiceImpl::toSummary)
                .toList();
    }

    @Override
    public List<ChatMessageView> listMessages(String sessionNo) {
        ChatSession session = chatSessionMapper.selectOne(
                Wrappers.<ChatSession>lambdaQuery()
                        .eq(ChatSession::getSessionNo, sessionNo));

        if (session == null) {
            // ★ 找不到就抛，不回空列表 —— 见 ChatSessionNotFoundException 的说明
            throw new ChatSessionNotFoundException(sessionNo);
        }

        // ★ 按 (session_id, id) 取，正是 idx_chat_message_session 那个索引的顺序，
        //   所以这是索引扫描而不是排序。★ 用 id 而不是 created_at 排序：
        //   同一个事务里写进去的两条消息可能是同一毫秒，那时 created_at 就不够用了。
        List<ChatMessage> messages = chatMessageService.list(
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getSessionId, session.getId())
                        .orderByAsc(ChatMessage::getId));

        return messages.stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 把 limit 夹到合法区间。
     *
     * <pre>
     *   ≤ 0      → 默认 30（视为「没指定」）
     *   1 ~ 200  → 原样
     *   &gt; 200  → 夹到 200
     * </pre>
     *
     * <p>★ 两种夹法都是<b>刻意的、并且写在这里</b>的：{@code ?limit=0} 回 30 条
     * 而不是 0 条，是因为「给我 0 条」不是一个有意义的请求；
     * 而 {@code ?limit=99999999} 被夹到 200，是因为那个值会被直接放进
     * {@code LIMIT}，等于把一次全表扫描变成一条不需要任何权限的 HTTP 请求。
     */
    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static ChatSessionSummary toSummary(ChatSession s) {
        return new ChatSessionSummary(
                s.getSessionNo(),
                s.getTitle(),
                s.getMessageCount(),
                s.getLastActiveAt(),
                s.getCreatedAt());
    }

    private ChatMessageView toView(ChatMessage m) {
        return new ChatMessageView(
                m.getId(),
                roleName(m.getRole()),
                m.getContent(),
                m.getIntent(),
                m.getIntentConfidence(),
                parseReferences(m.getReferences()),
                m.getProvider(),
                m.getModel(),
                m.getLatencyMs(),
                m.getCreatedAt());
    }

    /**
     * {@code role} 的数字 → 名字。
     *
     * <p>★ 用 {@code switch} 而不是 {@code role == 1 ? "user" : "assistant"}：
     * 三元式在将来多一个角色时会<b>静默地把新角色渲染成 assistant</b>，
     * 而 switch 会走进 {@code default}。同 {@code ConversationMemory} 里那条注释。
     *
     * <p>{@code default} 这一支<b>按约束不可达</b> —— {@code chat_message.role}
     * 上有 {@code CHECK (role IN (1, 2, 3))} 且 {@code NOT NULL}。
     * 留着它是为了「不可达」和「没人想过」这两件事能被区分开：
     * 真出现 {@code unknown} 时，说明约束被改过，而不是说明有人漏写了一个分支。
     */
    private static String roleName(Integer role) {
        if (role == null) {
            return "unknown";
        }
        return switch (role) {
            case ROLE_USER -> "user";
            case ROLE_ASSISTANT -> "assistant";
            case ROLE_SYSTEM -> "system";
            default -> "unknown";
        };
    }

    /**
     * 把 {@code references} 列里的 JSON 文本解析回 JSON 对象。
     *
     * <p>★ 为什么不直接透传那个字符串：见 {@link ChatMessageView#references()}
     * —— 透传出去会让前端收到「一个装着 JSON 的字符串」（双重编码），
     * 而那在 dump 里看着是对的。
     *
     * <p>★ 空值返回 <b>null</b> 而不是空数组，沿用 {@code serializeReferences}
     * 那条约定的反方向：<b>没有引用就是 NULL</b>，不是 {@code []}。
     * （写入方向已在 {@code ChatServiceImpl.serializeReferences} 保证这一点。）
     *
     * <h3>★ 为什么这个 catch 分支不可达 —— 说清楚，免得它看着像「可能的降级」</h3>
     *
     * <p>{@code chat_message.references} 在库里是 <b>JSONB</b>，
     * 而 PostgreSQL 在<b>写入时</b>就校验 JSONB 的合法性 ——
     * 非法 JSON 根本存不进这个列。所以从库读出来的值必然是合法 JSON，
     * {@code readTree} 不会抛。
     *
     * <p>真走进了这个分支，含义只有一种：<b>这个列已经不是 JSONB 了</b>
     * （迁移改过类型、或者有人手工 {@code ALTER}）。
     * 所以这里是 {@code log.error} 而不是 {@code warn}，并且返回 null ——
     * 让「读不出来」在一列 {@code null} 上可见，而不是编一个空的引用列表冒充成功。
     */
    private JsonNode parseReferences(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.error("references 反序列化失败 —— 该列应当永远是合法的 JSONB，"
                    + "走到这里说明列类型被改过。长度={} 异常={}",
                    raw.length(), e.getMessage());
            return null;
        }
    }
}
