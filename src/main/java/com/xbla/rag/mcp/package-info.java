/**
 * ★ MCP（Model Context Protocol）工具调用。
 *
 * <p><b>Server 手写</b>（工具定义 + JSON-RPC + 参数 schema），Client 用官方 Java SDK。
 *
 * <h2>包结构</h2>
 *
 * <pre>
 *   protocol/   JSON-RPC 2.0 报文 + MCP 的版本与常量（不碰业务，也不碰 HTTP）
 *   mcp/        McpServer / McpTool / ToolField / McpArguments / McpToolResult …
 *   tool/       具体的业务工具（5.7 一个，5.9 补齐四个）
 *   controller/ McpController —— Streamable HTTP 传输层（在 controller 包下）
 * </pre>
 *
 * <h2>★ 三条不能违反的纪律</h2>
 *
 * <ol>
 *   <li><b>工具的参数名只能写一次。</b> 用 {@code static final ToolField} 常量，
 *       schema 生成和取值都走它 —— 见 {@link com.xbla.rag.mcp.ToolField}。
 *       两处各写一份的漂移方式是<b>静默</b>的：模型照着 schema 发，
 *       实现读到 null，而 schema 看起来完全正确。</li>
 *
 *   <li><b>身份只能来自 {@link com.xbla.rag.mcp.McpToolContext}，不能来自参数。</b>
 *       工具参数是<b>模型填的</b> —— 给工具加一个 {@code user_id} 参数
 *       等于把越权入口交到一段提示注入手里。见那个类的类注释。</li>
 *
 *   <li><b>{@code isError} 的判据是「工具有没有给出答案」，不是「答案是不是空的」。</b>
 *       「查无此单」是个完整的答案，标成错误会让模型去为系统故障道歉。
 *       见 {@link com.xbla.rag.mcp.McpToolResult}。</li>
 * </ol>
 *
 * <h2>协议版本</h2>
 *
 * <p>服务端实现的是 <b>2025-11-25</b> —— 不是规范仓库里更新的那个，
 * 而是<b>官方 Java SDK 认得的那一版</b>。理由和核实方法见
 * {@link com.xbla.rag.mcp.protocol.McpProtocol}。
 */
package com.xbla.rag.mcp;
