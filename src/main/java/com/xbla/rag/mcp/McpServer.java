package com.xbla.rag.mcp;

import com.xbla.rag.mcp.protocol.JsonRpc;
import com.xbla.rag.mcp.protocol.McpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手写的 MCP Server —— <b>协议语义层，不碰 HTTP，也不碰业务。</b>
 *
 * <p>传输层（{@code McpController}）把它收到的 JSON 交进来，拿到一个
 * 「要回什么」的结论；本类不知道那是 HTTP 还是 stdio。
 * 这样将来要加 stdio 传输时，这一层一行都不用改。
 *
 * <h2>一、它负责的三件事</h2>
 *
 * <ol>
 *   <li><b>生命周期</b> —— {@code initialize} 握手 + 版本协商 + 会话建立</li>
 *   <li><b>能力声明</b> —— {@code tools/list}（工具元数据，模型靠它选工具）</li>
 *   <li><b>调用分发</b> —— {@code tools/call}（参数校验 → 执行 → 包结果）</li>
 * </ol>
 *
 * <h2>二、★★ 三类「错误」的去向完全不同</h2>
 *
 * <p>这是本类里唯一需要动脑的地方，也是最容易划错的地方：
 *
 * <table border="1">
 *   <caption>错误的三种去向</caption>
 *   <tr><th>情况</th><th>去向</th><th>HTTP</th><th>模型看到什么</th></tr>
 *   <tr><td>报文不是合法 JSON</td><td>JSON-RPC {@code error}</td><td>200</td>
 *       <td>（连不上，客户端处理）</td></tr>
 *   <tr><td>方法不存在 / 参数校验不过</td><td>JSON-RPC {@code error}</td><td>200</td>
 *       <td>「我这次调用写错了，改一下重试」</td></tr>
 *   <tr><td>工具跑了但没给出答案</td><td>{@code result.isError = true}</td><td>200</td>
 *       <td>「工具失败了」</td></tr>
 *   <tr><td>查无此单（是个正常答案）</td><td>{@code result.isError = false}</td><td>200</td>
 *       <td>「没有这一单」</td></tr>
 *   <tr><td>身份缺失</td><td>HTTP 401</td><td><b>401</b></td>
 *       <td>（根本不该走到模型那一步）</td></tr>
 * </table>
 *
 * <p>★ <b>JSON-RPC 层面出错时 HTTP 仍然是 200</b>。这不是偷懒：
 * 200 表示「这次 HTTP 交互本身成功了，失败的是里面的那条 RPC」。
 * 用 4xx/5xx 表达 RPC 错误会让客户端的重试策略、指标采集全部错判 ——
 * 一个「工具名拼错了」会变成一条 5xx 告警。
 *
 * <p>★ <b>身份缺失是 401，且不进 JSON-RPC</b>。因为那时还没有可信的调用方，
 * 给它一个格式规整的 JSON-RPC 错误等于承认了这次交互。
 *
 * <h2>三、★ 它<b>不</b>管会话 —— 会话是传输层的事</h2>
 *
 * <p>{@code Mcp-Session-Id} 是一个 <b>HTTP 头</b>。把它放进协议层会让
 * 「stdio 传输」那条路莫名其妙地也要维护一个会话表，而 stdio 是单连接的、
 * 根本没有会话这个概念。
 *
 * <p>所以本类<b>不依赖</b> {@link McpSessionStore}：
 * 会话的建立、校验、失效全部在 {@code McpController} 里做。
 * 本类只回答「这条报文该回什么」。
 *
 * <p>⚠️ 其中一个直接好处：控制器<b>自己生成</b>会话 ID，所以它不需要
 * 「猜哪个会话是刚建的」—— 那种写法在并发握手时会拿错。
 */
@Component
public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    private static final String SERVER_NAME = "xbla-rag-mcp";
    private static final String SERVER_TITLE = "休伯利安电商导购与售后（XBLA）";
    private static final String SERVER_VERSION = "0.1.0";

    private final McpToolRegistry registry;

    public McpServer(McpToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 处理一条 JSON-RPC 报文。
     *
     * @param message 已经解析成 Map 的请求（连 id 是不是存在都要看它）
     * @param context 身份，由传输层从 HTTP 头解出来。<b>永远非 null</b> ——
     *                没有身份时传输层就 401 了，走不到这里
     * @return 要回的报文。<b>通知返回 {@code null}</b>（不响应）
     */
    public Map<String, Object> handle(Map<String, Object> message, McpToolContext context) {
        // ★ 判据是「id 字段出现过没有」，不是「id 是不是 null」—— 见 JsonRpc.isNotification
        boolean idPresent = message.containsKey("id");
        Object id = message.get("id");

        String method = asString(message.get("method"));
        if (method == null || method.isBlank()) {
            // 连方法都没有，那这个请求没法配对，只能当成请求回一个错误
            return JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "缺少 method 字段");
        }

        if (JsonRpc.isNotification(idPresent)) {
            return handleNotification(method);
        }

        try {
            return switch (method) {
                case McpProtocol.METHOD_INITIALIZE -> initialize(id, params(message), context);
                case McpProtocol.METHOD_TOOLS_LIST -> toolsList(id);
                case McpProtocol.METHOD_TOOLS_CALL -> toolsCall(id, params(message), context);
                default -> JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND,
                        "不支持的方法：" + method,
                        Map.of("supported", List.of(
                                McpProtocol.METHOD_INITIALIZE,
                                McpProtocol.METHOD_TOOLS_LIST,
                                McpProtocol.METHOD_TOOLS_CALL)));
            };
        } catch (McpToolException e) {
            // 参数问题 —— 让模型改了重试
            log.debug("工具参数不合法 method={} id={}: {}", method, id, e.getMessage());
            return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, e.getMessage(), e.detail());
        } catch (Exception e) {
            // ★ 兜底：绝不让异常穿透到 HTTP 层变成 5xx ——
            //   那会让「一次工具执行失败」看起来像「整个服务挂了」
            log.error("处理 MCP 请求时发生未预期异常 method={} id={}", method, id, e);
            return JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, "服务内部错误："
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /**
     * 通知 —— <b>只认一个，而且不产生任何响应。</b>
     *
     * <p>MCP 的生命周期是三步：{@code initialize} 请求 → 响应 →
     * {@code notifications/initialized} 通知。<b>第三步是通知，服务端不能回。</b>
     * 回一个 {@code id: null} 的响应会让严格的客户端直接报协议错误。
     *
     * <p>未知的通知<b>静默忽略</b> —— 规范要求如此，而且通知本来就不该有回执。
     */
    private Map<String, Object> handleNotification(String method) {
        if (McpProtocol.NOTIFICATION_INITIALIZED.equals(method)) {
            log.debug("客户端握手完成（notifications/initialized）");
        } else {
            log.debug("收到未知通知，按规范静默忽略：{}", method);
        }
        return null;
    }

    // ============================================================
    // initialize
    // ============================================================

    private Map<String, Object> initialize(Object id, Map<String, Object> params,
                                           McpToolContext context) {
        String clientVersion = asString(params.get("protocolVersion"));
        String negotiated = McpProtocol.negotiate(clientVersion);

        if (!McpProtocol.isSupported(clientVersion)) {
            // ★ 不是错误分支 —— 规范要求回一个自己支持的版本，由客户端决定要不要断开。
            //   WARN 而不是 ERROR：旧客户端来连是正常现象
            log.warn("客户端请求的协议版本 {} 不在支持列表 {} 里，已回退到 {}",
                    clientVersion, McpProtocol.SUPPORTED_VERSIONS_ORDERED, negotiated);
        }

        log.info("MCP 握手完成 版本={}（客户端报 {}）身份={}",
                negotiated, clientVersion, context.display());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", negotiated);
        result.put("capabilities", capabilities());
        result.put("serverInfo", Map.of(
                "name", SERVER_NAME,
                "title", SERVER_TITLE,
                "version", SERVER_VERSION));
        return JsonRpc.success(id, result);
    }

    /** 握手成功的话，传输层要种一个会话 ID 回去 —— 由它自己生成，见类注释第三节 */
    public static boolean isInitialize(String method) {
        return McpProtocol.METHOD_INITIALIZE.equals(method);
    }

    /**
     * ★ <b>只声明 {@code tools}</b>，而且 {@code listChanged} 是 {@code false}。
     *
     * <p>见 {@link McpProtocol} 类注释第三节：声明了却没实现的能力比不声明更糟。
     * {@code listChanged} 同理 —— 声明 {@code true} 就是在承诺
     * 「工具列表变了我会主动推通知给你」，而我们没有任何工具会在运行期注册或注销。
     */
    private Map<String, Object> capabilities() {
        return Map.of("tools", Map.of("listChanged", false));
    }

    // ============================================================
    // tools/list
    // ============================================================

    /**
     * 列出全部工具。
     *
     * <p>规范支持分页（{@code cursor} / {@code nextCursor}）。
     * ★ <b>本项目不分页</b>：工具只有个位数，一次性返回比「来回三次才拿全」
     * 对模型友好得多。规范说分页是 MAY —— 不实现它是合规的。
     * 什么时候该翻案：工具数上百，或者单个 schema 大到把上下文撑爆。
     */
    private Map<String, Object> toolsList(Object id) {
        List<Map<String, Object>> tools = registry.all().stream()
                .map(McpTool::toToolDefinition)
                .toList();
        return JsonRpc.success(id, Map.of("tools", tools));
    }

    // ============================================================
    // tools/call
    // ============================================================

    private Map<String, Object> toolsCall(Object id, Map<String, Object> params,
                                          McpToolContext context) {
        String name = asString(params.get("name"));

        McpTool tool = registry.find(name);
        if (tool == null) {
            // ★ 未知工具名是【协议错误】而不是 isError —— 那不是「工具跑了但失败」，
            //   是「根本没有这个工具」。而且带上可用列表，模型能自己纠正
            return JsonRpc.error(id, JsonRpc.INVALID_PARAMS,
                    "未知的工具：" + name,
                    Map.of("availableTools", registry.all().stream().map(McpTool::name).toList()));
        }

        McpArguments args = McpArguments.of(asMap(params.get("arguments")), tool.inputFields());

        long start = System.nanoTime();
        McpToolResult result;
        try {
            result = tool.call(args, context);
        } catch (McpToolException e) {
            throw e;                        // 参数问题 → 交给外层翻成协议错误
        } catch (Exception e) {
            // ★ 工具自己抛了非预期异常 = "工具没能给出答案"，不是协议错误。
            //   翻成 isError 让模型能读到「这个查询失败了」，而不是整次调用报错
            log.error("工具执行抛异常 name={} 身份={}", name, context.display(), e);
            result = McpToolResult.failure(
                    "查询「" + tool.title() + "」时发生内部错误，未能取得数据。");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        log.info("MCP 工具调用 {} name={} isError={} {}ms",
                context.display(), name, result.isError(), elapsedMs);

        return JsonRpc.success(id, toolResultPayload(result));
    }

    /**
     * {@link McpToolResult} → 规范的 {@code result} 结构。
     *
     * <pre>
     *   {"content":[{"type":"text","text":"..."}], "isError":false, "structuredContent":{...}}
     * </pre>
     *
     * <p>★ {@code isError} <b>恒定出现</b>，不省略。规范说它是可选的、默认 false ——
     * 但「这个工具跑成功了没有」是<b>每次调用的固有属性</b>，
     * 让它靠「字段在不在」来表达会让客户端写出
     * {@code if (!result.has("isError") || !result.get("isError"))} 这种双重否定的判空。
     * 同 {@code retrieval_detail.filter} 恒定存在的理由。（ADR-045）
     */
    private static Map<String, Object> toolResultPayload(McpToolResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", List.of(Map.of(
                "type", "text",
                "text", result.text())));
        payload.put("isError", result.isError());
        if (result.hasData()) {
            payload.put("structuredContent", result.data());
        }
        return payload;
    }

    // ============================================================
    // 报文取值的小工具
    // ============================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> message) {
        Object params = message.get("params");
        if (params instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        // params 是【可选】的（tools/list 就没有）。缺失时给空 Map 而不是报错
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
