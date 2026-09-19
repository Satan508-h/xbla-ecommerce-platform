package com.xbla.rag.mcp.protocol;

import java.util.List;
import java.util.Set;

/**
 * MCP 的协议常量与版本协商 —— <b>全是常量和一个纯函数。</b>
 *
 * <h2>一、★ 为什么是 2025-11-25，而不是规范仓库里更新的 2026-07-28</h2>
 *
 * <p>{@code modelcontextprotocol/modelcontextprotocol} 仓库里目前有
 * {@code 2024-11-05 / 2025-03-26 / 2025-06-18 / 2025-11-25 / 2026-07-28 / draft} 六个修订。
 *
 * <p><b>但协议版本要和客户端对齐，不是和规范仓库对齐。</b>
 * 本项目 5.8 要用的官方 Java SDK 是 {@code io.modelcontextprotocol.sdk:mcp} ——
 * 实测（2026-09-19，反编译 2.0.1 的 {@code ProtocolVersions}）它只认到
 * <b>{@code 2025-11-25}</b>，不认识 {@code 2026-07-28}。
 *
 * <p>所以服务端声明一个 SDK 不认识的版本，唯一的效果就是<b>每次握手都协商降级</b> ——
 * 功能一点没多，日志里还多一行「版本不匹配」。跟 SDK 对齐。
 *
 * <p>⚠️ <b>这张表会和 SDK 的发布脱节。</b>升级 SDK 时请重新核实它认到哪一版 ——
 * 核实方法是反编译 {@code ProtocolVersions}，不是看文档：
 * <pre>
 *   javap -p -constants mcp-core-x.y.z.jar 里的 io/modelcontextprotocol/spec/ProtocolVersions.class
 * </pre>
 * 同 CLAUDE.md 里「模型 ID 会变动，动工前先拉真实列表」是同一个纪律。
 *
 * <h2>二、★ 版本协商是【服务端】的职责，而且它不是报错分支</h2>
 *
 * <p>规范原文的意思是：客户端发它支持的版本过来，服务端<b>支持就原样回显，
 * 不支持就回一个自己支持的版本</b> —— 而不是报错。之后由客户端决定要不要断开。
 *
 * <p>这和服务端「拒绝一个不认识的参数」是完全不同的态度，理由也完全不同：
 * 参数错了是<b>这一次调用</b>做不了；版本不认识是<b>可能整个会话</b>都做不了，
 * 把决定权留给客户端比服务端单方面掐断更合理。
 *
 * <h2>三、只声明 {@code tools} 一种能力</h2>
 *
 * <p>MCP 还有 {@code prompts} / {@code resources} / {@code logging} 等能力。
 * 本项目只实现了工具，所以只声明工具 ——
 * <b>声明了却没实现的能力比不声明更糟</b>：客户端会照着去调，然后拿到
 * {@code METHOD_NOT_FOUND}，而那个错误看起来像 bug，不像「这个服务端不支持」。
 */
public final class McpProtocol {

    /**
     * 服务端实现的最高协议版本 —— 也是协商时回退到的默认值。
     *
     * @see McpProtocol 类注释第一节：为什么是它而不是更新的那个
     */
    public static final String PROTOCOL_VERSION = "2025-11-25";

    /**
     * 服务端能原样回显的其他版本。
     *
     * <p>这三个版本在 <b>{@code initialize} / {@code tools/list} / {@code tools/call}
     * 这三个方法的报文形状上是兼容的</b>，所以回显它们不会骗客户端。
     *
     * <p>⚠️ <b>不包含 {@code 2024-11-05}</b>：那一版只有 HTTP+SSE 传输
     * （Streamable HTTP 是 2025-03-26 才引入的），我们没实现 SSE，
     * 回显它就等于承诺了一个我们做不到的传输方式。
     */
    public static final Set<String> SUPPORTED_VERSIONS =
            Set.of("2025-03-26", "2025-06-18", "2025-11-25");

    /** 能按客户端顺序列出，方便打日志和写进调试探针 */
    public static final List<String> SUPPORTED_VERSIONS_ORDERED =
            List.of("2025-03-26", "2025-06-18", "2025-11-25");

    // ── 方法名 ──
    // ★ 用常量而不是散在各处的字符串字面量：拼错一个方法名的症状是
    //   「客户端说方法不存在」，而我们这边完全看不出自己拼错了

    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";

    /** 握手第三步。★ 它是<b>通知</b>（没有 id），服务端不能回响应 */
    public static final String NOTIFICATION_INITIALIZED = "notifications/initialized";

    // ── HTTP 层用到的头 ──

    /** 服务端在 initialize 的响应里种下，客户端后续每个请求都要带回来 */
    public static final String HEADER_SESSION_ID = "Mcp-Session-Id";

    /** 规范要求客户端在 initialize【之后】的请求里带上协商好的版本 */
    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

    /** 本项目的身份来源。★ 见 {@code McpToolContext} —— 它【不是】认证 */
    public static final String HEADER_USER_ID = "X-Xbla-User-Id";

    private McpProtocol() {
    }

    /**
     * 版本协商 —— <b>纯函数</b>。
     *
     * @param clientVersion 客户端在 {@code initialize} 里发的版本，可为 null
     * @return <b>永远不是 null</b>。支持就原样回显，不支持（或没发）就回服务端最高的那一版
     */
    public static String negotiate(String clientVersion) {
        if (clientVersion != null && SUPPORTED_VERSIONS.contains(clientVersion)) {
            return clientVersion;
        }
        return PROTOCOL_VERSION;
    }

    /** 客户端要的版本我们认不认 —— 只用于打日志 */
    public static boolean isSupported(String clientVersion) {
        return clientVersion != null && SUPPORTED_VERSIONS.contains(clientVersion);
    }
}
