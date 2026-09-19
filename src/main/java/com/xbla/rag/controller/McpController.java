package com.xbla.rag.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.mcp.McpServer;
import com.xbla.rag.mcp.McpSessionStore;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.protocol.JsonRpc;
import com.xbla.rag.mcp.protocol.McpProtocol;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * MCP 的 <b>Streamable HTTP</b> 传输层 —— 协议和业务都不在这，只做 HTTP ↔ JSON-RPC 的翻译。
 *
 * <h2>一、为什么是 Streamable HTTP，不是 stdio</h2>
 *
 * <p>MCP 有两种传输。stdio 是「客户端 spawn 一个子进程，JSON-RPC 走 stdin/stdout」——
 * 最经典的形态（Claude Desktop 就是这么连的）。但本项目的 Server 是一个
 * <b>要连 PostgreSQL、要跑 Flyway、要读 Spring 配置</b>的进程，
 * 做成别人的子进程意味着数据库配置、迁移、日志全要单独处理一遍。
 *
 * <p>Streamable HTTP 则是<b>一个普通的 HTTP 端点</b>，规范从 2025-03-26 起主推它
 * （老的 HTTP+SSE 传输已废弃）。这个项目里它就是同一个 Spring 应用的一个 Controller，
 * 复用同一套 DataSource 和 Bean。
 *
 * <p>⚠️ 但要注意：<b>现在 Server 和未来的 Client（5.8）在同一个 JVM 里</b>，
 * 所以这条链路会走一次本机 HTTP 回环。这不是浪费 ——
 * 它让「协议」这件事是真的在跑，而不是一个包装成函数调用的假协议。
 *
 * <h2>二、★★ 规范强制要求的三条安全措施</h2>
 *
 * <ol>
 *   <li><b>校验 {@code Origin} 头</b>（强制）—— 防 DNS rebinding。
 *       没有它，一个恶意网页能让浏览器去访问 {@code localhost:8080/mcp} ——
 *       浏览器的同源策略管不了 POST，而请求是带着用户的浏览器网络位置的。
 *       ★ <b>但头不存在时不拦</b>：非浏览器客户端（SDK、curl）本来就不发 Origin，
 *       而 DNS rebinding 是浏览器发起的。见 {@link #originAllowed}</li>
 *   <li><b>只绑 localhost</b>（建议）—— Spring 的 {@code server.address} 配置，
 *       见 {@code application.yml} 的说明</li>
 *   <li><b>实现认证</b>（建议）—— ⚠️ 本项目<b>只做了一半</b>：
 *       身份从 {@code X-Xbla-User-Id} 头来，而那个头是<b>明文、未签名</b>的。
 *       见 {@link McpToolContext} 的类注释。这里不掩饰，写清楚。</li>
 * </ol>
 *
 * <h2>三、★★ 为什么用 {@code application/json} 回，而不是 SSE</h2>
 *
 * <p>规范允许两种：一次性 JSON，或者一个 SSE 流。SSE 存在的意义是
 * <b>服务端要主动推消息</b>（进度通知、采样请求、日志）。
 *
 * <p>本项目一条都不推 —— 工具是同步查询，没有中间进度。用 SSE 只会让
 * 客户端多一层解析、让 {@code curl} 调试变得难读，还会让人误以为有流式能力。
 *
 * <h2>四、HTTP 状态码的划分（会被问，所以说清楚）</h2>
 *
 * <table border="1">
 *   <caption>四类响应</caption>
 *   <tr><th>情况</th><th>HTTP</th><th>Body</th></tr>
 *   <tr><td>请求（有 id）</td><td>200</td><td>JSON-RPC 响应，<b>哪怕里面是 error</b></td></tr>
 *   <tr><td>通知（无 id）</td><td><b>202</b></td><td><b>空</b> —— 规范要求</td></tr>
 *   <tr><td>没有身份 / 会话失效</td><td><b>401 / 400</b></td><td>JSON-RPC error（尽力而为）</td></tr>
 *   <tr><td>报文不是合法 JSON</td><td>200</td><td>JSON-RPC {@code -32700}</td></tr>
 * </table>
 *
 * <p>★ 注意<b>业务错误仍然回 200</b> —— 200 表示「这次 HTTP 交互成功了，
 * 失败的是里面的那条 RPC」。用 4xx/5xx 表达 RPC 错误会让客户端的重试策略
 * 和指标采集全部错判（详见 {@link McpServer} 类注释第二节）。
 * 只有「身份」和「会话」例外，因为那两件事发生时<b>还没有可信的调用方</b>。
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final McpServer server;
    private final McpSessionStore sessions;
    private final ObjectMapper objectMapper;

    /**
     * 允许的 {@code Origin}。逗号分隔，配置项 {@code xbla.mcp.allowed-origins}。
     *
     * <p>默认只允许本机 —— 本地开发时浏览器页面和 MCP 端点同源或来自 localhost。
     */
    private final Set<String> allowedOrigins;

    public McpController(McpServer server,
                         McpSessionStore sessions,
                         ObjectMapper objectMapper,
                         @Value("${xbla.mcp.allowed-origins:http://localhost:8080,http://127.0.0.1:8080}")
                         String allowedOrigins) {
        this.server = server;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.allowedOrigins = Set.of(allowedOrigins.split(","));
    }

    /**
     * 接收一条 JSON-RPC 报文。
     *
     * <p>规范要求每个 JSON-RPC 消息都是一次<b>独立的 POST</b>。
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> post(@RequestBody(required = false) String body,
                                  HttpServletRequest http) {

        // ── ① Origin 校验（规范强制）──
        String origin = http.getHeader(HttpHeaders.ORIGIN);
        if (!originAllowed(origin)) {
            log.warn("拒绝来源不在白名单的 MCP 请求 Origin={} 白名单={}", origin, allowedOrigins);
            return jsonError(HttpStatus.FORBIDDEN, null, JsonRpc.INVALID_REQUEST,
                    "Origin 不在白名单内：" + origin);
        }

        // ── ② 身份（本项目唯一的「认证」，见类注释第二节）──
        Long userId = parseUserId(http.getHeader(McpProtocol.HEADER_USER_ID));
        if (userId == null) {
            log.warn("MCP 请求缺少合法的 {} 头", McpProtocol.HEADER_USER_ID);
            return jsonError(HttpStatus.UNAUTHORIZED, null, JsonRpc.INVALID_REQUEST,
                    "缺少 " + McpProtocol.HEADER_USER_ID + " 请求头（正整数）");
        }
        McpToolContext context = new McpToolContext(userId);

        // ── ③ 解析报文 ──
        Map<String, Object> message;
        try {
            message = objectMapper.readValue(body, JSON_OBJECT);
        } catch (Exception e) {
            // ★ 解析失败仍回 200 —— 见类注释第四节
            log.debug("MCP 报文解析失败：{}", e.getMessage());
            return ResponseEntity.ok(JsonRpc.error(null, JsonRpc.PARSE_ERROR,
                    "请求体不是合法的 JSON 对象"));
        }

        // ── ④ 会话（initialize 除外）──
        //
        //   ★ 会话【由控制器自己生成】，不是问协议层要的 ——
        //     「哪个会话是刚建的」在并发握手时会问错人。
        //     而且 Mcp-Session-Id 本来就是 HTTP 头，属于传输层。
        String sessionId = http.getHeader(McpProtocol.HEADER_SESSION_ID);
        boolean isInitialize = McpServer.isInitialize((String) message.get("method"));

        if (isInitialize) {
            sessionId = UUID.randomUUID().toString();
            sessions.create(sessionId, context);
        } else {
            // ★ 规范：要求会话的服务端，对不带 session 的请求回 400
            McpToolContext sessionContext = sessions.lookup(sessionId);
            if (sessionContext == null) {
                log.warn("MCP 请求的会话无效或已过期 session={} —— 客户端应重新 initialize", sessionId);
                return jsonError(HttpStatus.BAD_REQUEST, message.get("id"), JsonRpc.INVALID_REQUEST,
                        "会话无效或已过期，请重新 initialize");
            }
            // ★ 会话里存着身份，所以它同时是「这个 session 是谁」的凭据。
            //   会话属于 8 号但请求头说自己是 9 号 —— 直接拒，不静默按其中一个来
            if (sessionContext.userId() != context.userId()) {
                log.warn("MCP 会话身份与请求头不一致 session={} 会话身份={} 请求头身份={}",
                        sessionId, sessionContext.display(), context.display());
                return jsonError(HttpStatus.FORBIDDEN, message.get("id"), JsonRpc.INVALID_REQUEST,
                        "会话身份与请求头不一致");
            }
        }

        // ── ⑤ 交给协议层 ──
        Map<String, Object> response = server.handle(message, context);

        // ★ response 为 null = 这是个通知，规范要求回 202 且不响应
        if (response == null) {
            return ResponseEntity.accepted().build();
        }

        // ── ⑥ 握手成功的话，把会话 ID 种回去 ──
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (isInitialize && response.containsKey("result")) {
            builder.header(McpProtocol.HEADER_SESSION_ID, sessionId);
        }
        return builder.body(response);
    }

    /**
     * 客户端主动结束会话。
     *
     * <p>规范里有这个可选方法（HTTP DELETE）。实现它很便宜，
     * 而且让「会话是用完就还的资源」这件事在代码里有个落点 ——
     * 否则只能等两小时 TTL 过期。
     */
    @DeleteMapping
    public ResponseEntity<?> delete(HttpServletRequest http) {
        boolean removed = sessions.delete(http.getHeader(McpProtocol.HEADER_SESSION_ID));
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // ============================================================
    // 小工具
    // ============================================================

    /**
     * Origin 放行吗。
     *
     * <p>★★ <b>头不存在时放行，这是刻意的。</b>
     *
     * <p>DNS rebinding 是<b>浏览器</b>发起的攻击：攻击者的页面让用户的浏览器
     * 去请求 {@code http://localhost:8080/mcp}，而浏览器的同源策略拦不住 POST。
     * 防它的办法就是看 {@code Origin}。
     *
     * <p>而<b>浏览器在所有跨源请求上都会带 {@code Origin}</b> ——
     * 头不存在只可能是非浏览器客户端（官方 SDK、curl、服务端之间的调用），
     * 而那种客户端本来就不受同源策略约束，也就没有 rebinding 可言。
     *
     * <p>所以「缺席就拒」只会挡住合法客户端（包括 5.8 要用的官方 SDK），
     * 而攻击者根本不会因为这条被挡住。**判据要和威胁模型对齐。**
     */
    private boolean originAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        return allowedOrigins.contains(origin);
    }

    /**
     * 从请求头解出用户 ID。
     *
     * <p>⚠️ 这里<b>只查格式，不验签名</b> —— 见类注释第二节。
     * 解析失败返回 {@code null}，由调用方回 401。
     */
    private static Long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** HTTP + JSON-RPC 双层错误：状态码表达「这次交互」的问题，body 让客户端能解析 */
    private ResponseEntity<Map<String, Object>> jsonError(HttpStatus status, Object id,
                                                          int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.putAll(JsonRpc.error(id, code, message));
        return ResponseEntity.status(status).body(body);
    }
}
