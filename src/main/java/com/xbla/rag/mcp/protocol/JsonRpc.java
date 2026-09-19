package com.xbla.rag.mcp.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-RPC 2.0 的报文构造 —— <b>全是静态工厂，没有状态，没有依赖。</b>
 *
 * <p>MCP 的报文层就是 JSON-RPC 2.0，所以这一层和「模型上下文协议」无关 ——
 * 它只是把「怎么拼一个合法的 JSON-RPC 响应」这件事从 MCP 的语义里拆出来。
 * 这样 {@link McpServer} 里剩下的就全是协议语义（握手、工具分发、错误分类）。
 *
 * <h2>★ 只有三条规矩，但每条都能静默出错</h2>
 *
 * <ol>
 *   <li><b>{@code id} 必须原样回显，连类型都不能变。</b>
 *       JSON-RPC 允许它是字符串、数字或 null。客户端拿它来配对请求和响应 ——
 *       把 {@code "1"} 回成 {@code 1} 会让客户端的 pending map 查不到，
 *       表现为「请求发出去了，永远等不到结果」，而两边的日志都是正常的。
 *       ★ 所以本类的 {@code id} 参数类型是 {@link Object}，原样塞回去，不做任何转换。</li>
 *
 *   <li><b>成功和失败是互斥的两个字段</b>，{@code result} 和 {@code error}
 *       不能同时出现、也不能都不出现。</li>
 *
 *   <li><b>通知（notification）没有 id，也【不能】有响应。</b>
 *       见 {@link #isNotification}。</li>
 * </ol>
 */
public final class JsonRpc {

    /** 协议版本字段的固定值 */
    public static final String VERSION = "2.0";

    // ── 标准错误码（JSON-RPC 2.0 规范定义的六个）──

    /** 收到的不是合法 JSON */
    public static final int PARSE_ERROR = -32700;
    /** 是合法 JSON，但不是合法的 JSON-RPC 请求对象 */
    public static final int INVALID_REQUEST = -32600;
    /** 方法不存在（MCP 里：未知的 {@code method}） */
    public static final int METHOD_NOT_FOUND = -32601;
    /** 参数不合法（MCP 里：未知工具名、参数类型错、缺必填参数、多了未知参数） */
    public static final int INVALID_PARAMS = -32602;
    /** 服务端内部错误 */
    public static final int INTERNAL_ERROR = -32603;

    private JsonRpc() {
    }

    /**
     * 一个请求/通知是不是通知。
     *
     * <p>★ 判据是「{@code id} 字段【不存在】」，不是「{@code id} 是 null」。
     * 规范里 {@code id: null} 仍然是请求，只是无法配对 —— 而那属于客户端的用法问题。
     * MCP 里真实存在的通知有 {@code notifications/initialized}（握手第三步），
     * 对它回一个 {@code id: null} 的响应会让严格的客户端直接报协议错误。
     *
     * @param idPresent {@code id} 字段在请求报文里是否<b>出现过</b>。
     *                  ★ 注意不是「{@code id} 是不是 null」——
     *                  {@code Map.containsKey("id")} 才是判据
     */
    public static boolean isNotification(boolean idPresent) {
        return !idPresent;
    }

    /** 成功响应。{@code result} 一定要非 null —— 规范要求成功时必须有它 */
    public static Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = envelope(id);
        response.put("result", result);
        return response;
    }

    /** 失败响应 */
    public static Map<String, Object> error(Object id, int code, String message) {
        return error(id, code, message, null);
    }

    /**
     * 失败响应（带 {@code data}）。
     *
     * <p>{@code data} 是可选的附加信息。本项目用它装「哪个参数错了」这类
     * 机器可读的细节 —— {@code message} 是给人看的，{@code data} 是给程序看的。
     */
    public static Map<String, Object> error(Object id, int code, String message, Object data) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        if (data != null) {
            err.put("data", data);
        }

        Map<String, Object> response = envelope(id);
        response.put("error", err);
        return response;
    }

    /**
     * 信封：{@code {"jsonrpc":"2.0","id":...}}。
     *
     * <p>★ 用 {@link LinkedHashMap} 而不是 {@code Map.of} ——
     * {@code jsonrpc} 在最前面、{@code id} 紧随其后，是所有人肉读 JSON 时的习惯顺序。
     * 而且 {@code Map.of} 不允许 null value，而 {@code id} 恰恰可能合法地为 null。
     */
    private static Map<String, Object> envelope(Object id) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", VERSION);
        response.put("id", id);
        return response;
    }
}
