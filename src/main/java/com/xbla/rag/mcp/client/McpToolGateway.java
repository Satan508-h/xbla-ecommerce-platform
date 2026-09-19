package com.xbla.rag.mcp.client;

import com.xbla.rag.client.dto.ToolSpec;

import java.util.List;
import java.util.Map;

/**
 * <b>「能列出工具」和「能调工具」</b> —— 智能体层对 MCP 的全部需求，只有这两条。
 *
 * <h2>★ 为什么要抽这个接口，而不是直接让 ToolLoop 用官方 SDK</h2>
 *
 * <p>因为 SDK 的类名长这样：{@code io.modelcontextprotocol.spec.McpSchema$CallToolResult}。
 * 让 {@code agent/} 层 import 它，就意味着：
 * <ul>
 *   <li>SDK 升一个小版本、改一个方法签名 → <b>智能体层要跟着改</b></li>
 *   <li>换一个 MCP 实现（比如自己写的、或者别的语言的 Server 通过 stdio 接）
 *       → <b>智能体层要重写</b></li>
 *   <li>写单测必须造一个真的 MCP 连接，或者 mock 一整套 SDK 类型</li>
 * </ul>
 *
 * <p>而这两条需求本身<b>和 MCP 一点关系都没有</b> —— 它们描述的是
 * 「有个东西能告诉我有哪些工具可用、能帮我调一个」。
 * 这条界线画在这里之后：
 * <pre>
 *   agent/tool/ToolLoop  ──▶  McpToolGateway  ◀──  SdkMcpToolGateway ──▶ 官方 SDK
 *      （不懂 MCP）              （两个方法）           （唯一 import SDK 的地方）
 * </pre>
 *
 * <p>同 {@code LlmClient}：那也是「业务对模型供应商的全部需求」的抽象，
 * 所以换一家供应商时 service 层一行不用改。
 *
 * <h2>★★ 身份是【方法参数】，不是构造参数</h2>
 *
 * <p>{@code userId} 出现在每一个方法上，而不是 {@code SdkMcpToolGateway}
 * 的一个字段。理由和 {@code McpToolContext} 那条完全一样：
 * <b>它是一个「每次调用都可能不同」的事实，把它变成对象状态，
 * 就为「用错身份」开了口子</b> —— 而那个错误的形态是
 * 「并发时 A 用户看到 B 用户的订单」，日志里一切正常。
 *
 * <p>实现方注意：这个值要作为 {@code X-Xbla-User-Id} 头传给服务端，
 * ⚠️ 而那个头是<b>明文未签名</b>的，不是认证。见 {@code McpToolContext}。
 */
public interface McpToolGateway {

    /**
     * 列出服务端全部工具，转成「给模型看的形状」。
     *
     * @param userId 以谁的身份去列。★ 工具列表本身不含用户数据，
     *               但服务端要求每个请求都带身份，所以这里也要传
     * @return 按服务端给的顺序。<b>不为 null</b>，没有工具时返回空列表
     * @throws McpGatewayException 连不上 / 握手失败 / 协议错
     */
    List<ToolSpec> listTools(long userId);

    /**
     * 调用一个工具。
     *
     * @param userId    以谁的身份去调。★ <b>它就是防越权的全部落点</b>
     * @param toolName  工具名。<b>由模型给出</b> —— 服务端会拒绝不认识的，
     *                  这是「模型可能编一个工具名」的正常处理路径，不是异常
     * @param arguments 参数，<b>已解析成 Map</b>。由调用方从模型给的
     *                  JSON 字符串解析 —— 因为那段字符串要原样回声，
     *                  解析是另一件事（见 {@code ToolCall}）
     * @return 工具给出的结果，可能是成功也可能是它自己报告的失败
     * @throws McpGatewayException 工具<b>根本没跑成</b>（不是「跑了但办不到」）
     */
    ToolOutcome callTool(long userId, String toolName, Map<String, Object> arguments);
}
