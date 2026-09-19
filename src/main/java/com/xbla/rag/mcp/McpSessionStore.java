package com.xbla.rag.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 会话表 —— <b>进程内内存实现。</b>
 *
 * <h2>一、会话在 MCP 里是干什么的</h2>
 *
 * <p>规范里 {@code Mcp-Session-Id} 是<b>可选</b>的（MAY）。本项目实现它，
 * 因为它承担一个真实职责：<b>强制握手顺序</b>。
 *
 * <p>规范要求 {@code initialize} 必须是第一次交互。没有会话表的话，
 * 一个客户端跳过握手直接 {@code tools/call} 也会成功 ——
 * 而它拿到的工具列表可能和我们实际能执行的不是一回事
 * （将来加了按会话裁剪工具的能力，这个洞就会真的漏）。
 *
 * <p>★ 会话里<b>存了身份</b>，所以它同时是「这个 session 是谁」的凭据。
 * 也就是说：会话 ID 泄露 = 身份泄露。这就是为什么它是 UUID 而不是自增数字 ——
 * 自增 ID 可枚举，攻击者换个数字就能拿到别人的会话。
 *
 * <h2>二、⚠️ 已知限制：只能单实例部署</h2>
 *
 * <p>会话表在 JVM 堆里，所以：
 *
 * <ul>
 *   <li><b>重启即失效</b> —— 所有客户端要在下一次请求时重新握手。
 *       客户端会收到 400 然后重来，功能上不算坏，但日志会很吵。</li>
 *   <li><b>多实例不通用</b> —— 实例 A 发的会话 ID，负载均衡到实例 B 就查不到。</li>
 * </ul>
 *
 * <p>要支持这些得把会话表挪到 Redis，那是<b>阶段 6（高可用）</b>的事。
 * 现在不做的理由和「不为没发生过的规模做分层摘要」一样（ADR-051）——
 * 但在文档里写明，不留一个「看起来能水平扩展」的错觉。
 *
 * <h2>三、为什么要有过期</h2>
 *
 * <p>没有过期的话，一个长期运行的服务会把每一个连过的客户端都记一辈子 ——
 * 那是一条只涨不跌的内存曲线，而且从外部完全看不出来。
 * 过期时间取得很长（见 {@link #TTL}）：MCP 客户端可能挂着几小时不动，
 * 会话过期了它要重新握手，代价是几毫秒。
 */
@Component
public class McpSessionStore {

    private static final Logger log = LoggerFactory.getLogger(McpSessionStore.class);

    /**
     * 会话存活时间。
     *
     * <p>取 2 小时而不是几分钟：MCP 客户端是<b>长连接之后长时间空闲</b>的使用模式
     * （用户打开 IDE，半小时不问一句）。会话过期本身不致命（客户端会重新握手），
     * 但每次重新握手都会打一行 WARN，日志会很吵。
     */
    private static final Duration TTL = Duration.ofHours(2);

    /**
     * 清理的触发频率门槛：表里超过这么多条才顺手清一次。
     *
     * <p>★ <b>不在每次写入时全表扫描</b> —— 那会让「建一个会话」的成本
     * 随表大小线性增长，是一个典型的 O(n²)。这里用一个粗暴但有效的门槛：
     * 表小的时候根本不清（没有内存压力），大了才清。
     */
    private static final int SWEEP_THRESHOLD = 64;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(McpToolContext context, Instant expiresAt) {
        boolean expired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    /** 握手成功后建一个会话 */
    public void create(String sessionId, McpToolContext context) {
        Instant now = Instant.now();
        sessions.put(sessionId, new Session(context, now.plus(TTL)));

        if (sessions.size() > SWEEP_THRESHOLD) {
            sweep(now);
        }
    }

    /**
     * 查会话。
     *
     * @return 会话里的身份；不存在或已过期时返回 {@code null}
     *         —— 由传输层翻成 HTTP 400，见 {@code McpController}
     */
    public McpToolContext lookup(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (session.expired(Instant.now())) {
            // ★ 过期的条目在这里顺手删掉 —— 比等到 sweep 更及时，
            //   而且读路径天然会覆盖到「正在被使用的那批会话」
            sessions.remove(sessionId);
            return null;
        }
        return session.context();
    }

    /** 主动结束一个会话（客户端发 DELETE 时） */
    public boolean delete(String sessionId) {
        return sessionId != null && sessions.remove(sessionId) != null;
    }

    public int size() {
        return sessions.size();
    }

    /** 清掉已过期的。门槛触发，或者被调试探针调用 */
    public int sweep(Instant now) {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().expired(now));
        int removed = before - sessions.size();
        if (removed > 0) {
            log.debug("清理过期 MCP 会话 {} 个，剩余 {}", removed, sessions.size());
        }
        return removed;
    }

    public int sweep() {
        return sweep(Instant.now());
    }
}
