package com.xbla.rag.mcp.client;

import com.xbla.rag.client.dto.ToolSpec;
import com.xbla.rag.mcp.protocol.McpProtocol;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@link McpToolGateway} 的官方 SDK 实现 —— ★ <b>本包（乃至整个项目）唯一 import
 * {@code io.modelcontextprotocol.*} 的类。</b>
 *
 * <h2>一、为什么用官方 SDK，而服务端是自己手写的</h2>
 *
 * <p>这是刻意的组合，不是偷懒：
 * <ul>
 *   <li><b>Server 手写</b> —— 要能逐行解释 JSON-RPC 的报文形状、错误码去向、
 *       能力协商、会话管理。用 SDK 生成一个 Server，这些全被藏起来了</li>
 *   <li><b>Client 用 SDK</b> —— 客户端要面对的是「别人的 Server」，
 *       而别人的 Server 可能有各种实现细节。手写客户端意味着
 *       <b>每接一个新 Server 都要重新踩一遍协议</b>，而 SDK 已经踩过了</li>
 * </ul>
 *
 * <p>★ 顺带得到一个<b>免费的验收</b>：让官方 SDK 去连我们手写的 Server。
 * 它能握手、能列工具、能调通，就说明 5.7 那份实现是<b>真的合规</b>，
 * 而不是「我们自己测自己，怎么说都对」。这是 5.8 除了工具调用之外的第二个价值。
 *
 * <h2>★★ 二、为什么每次调用都重新握手（而不是维持一个长连接）</h2>
 *
 * <p>用户拍板的方案。代价是每次工具调用多两个 RTT（{@code initialize} +
 * {@code notifications/initialized}），本机回环下是毫秒级。
 *
 * <p>换来的是<b>一整类状态 bug 消失</b>：
 * <table border="1">
 *   <caption>长连接要处理、而这里不需要处理的事</caption>
 *   <tr><th>问题</th><th>长连接的写法</th><th>本实现</th></tr>
 *   <tr><td>Server 重启</td><td>会话失效，要重连 + 重试</td><td>下次调用自然是新会话</td></tr>
 *   <tr><td>会话 2 小时 TTL 到期</td><td>同上，而且是<b>一定发生</b>的</td><td>不存在</td></tr>
 *   <tr><td>连接健康检查</td><td>要自己写心跳或探活</td><td>不存在</td></tr>
 *   <tr><td>并发安全</td><td>{@code McpSyncClient} 能不能多线程共用？要查文档</td><td>每次一个客户端，天然隔离</td></tr>
 * </table>
 *
 * <p>⚠️ <b>这不是「延迟不重要」，是「现在还不重要」。</b>等工具调用变成
 * 每一步都要用（比如 5.9 的四个工具串起来调），或者 Client 指向一台
 * <b>远程</b> Server（那时一个 RTT 是几十毫秒而不是微秒），
 * 就该把连接池化 —— 到时候需要处理的就是上面那张表里的四件事，
 * 而不是「为什么工具时好时坏」。
 *
 * <h2>三、身份怎么过去的</h2>
 *
 * <p>走 {@code httpRequestCustomizer}，把 {@code X-Xbla-User-Id} 加在
 * <b>每一个</b> HTTP 请求上（握手和工具调用都算）。
 *
 * <p>★ 它是从 {@code userId} 参数<b>当场构造</b>的，不是从这个类的字段读的 ——
 * 见 {@link McpToolGateway} 类注释第二节。
 *
 * <p>⚠️ 这个头是明文未签名的，<b>不是认证</b>。见 {@code McpToolContext}。
 */
@Slf4j
@Component
public class SdkMcpToolGateway implements McpToolGateway {

    private final McpClientProperties props;

    public SdkMcpToolGateway(McpClientProperties props) {
        this.props = props;
    }

    // ============================================================
    // 两个契约方法
    // ============================================================

    @Override
    public List<ToolSpec> listTools(long userId) {
        return withClient(userId, McpGatewayException.Stage.LIST_TOOLS, client ->
                client.listTools().tools().stream()
                        .map(SdkMcpToolGateway::toSpec)
                        .toList());
    }

    @Override
    public ToolOutcome callTool(long userId, String toolName, Map<String, Object> arguments) {
        // ★★ 工具名先自己查一遍，【不能】只靠 SDK 去拒。
        //
        //   这不是「多余的一道校验」，而是本类唯一一次踩过的真实故障：
        //   模型给出的工具名在某个环节变成了 null（见 WireToolCall 的类注释），
        //   于是 SDK 的 CallToolRequest.Builder.name(null) 抛了一句
        //   IllegalArgumentException("name must not be empty") ——
        //   一个和订单毫无关系的消息，直接终结了整次问答。
        //
        //   ★ 现在的分流是：null/空 → 一句给模型看的话（它能改），
        //     而不是一个异常。**「工具名是模型填的」意味着它可能是任何东西。**
        if (toolName == null || toolName.isBlank()) {
            throw new McpGatewayException(McpGatewayException.Stage.CALL_TOOL,
                    "模型给出的工具名是空的 —— 无法调用。"
                            + "（这通常说明响应里的 tool_calls 没有被正确解析，"
                            + "见 WireToolCall 的类注释）");
        }

        // ★★ 请求的【构造】必须在 withClient 里面。
        //
        //   第一版写在外面，于是一个非常尴尬的后果：
        //   McpGatewayException 的包装网只罩住了「连上之后」的部分，
        //   而 CallToolRequest.builder() 抛的 IllegalArgumentException
        //   【直接穿了出去】—— 它没被包成 McpGatewayException，
        //   所以 ToolLoop 的兜底也接不住，整次问答 status=2。
        //   「把所有失败都收口成一种异常」这句话，只有构造也在里面才成立。
        return withClient(userId, McpGatewayException.Stage.CALL_TOOL, client -> {
            McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                    .name(toolName)
                    .arguments(arguments == null ? Map.of() : arguments)
                    .build();
            return toOutcome(client.callTool(request));
        });
    }

    // ============================================================
    // 连接的生命周期 —— 整个类只有这一个地方碰网络
    // ============================================================

    /**
     * 建连接 → 握手 → 执行 → <b>无论成败都关闭</b>。
     *
     * <p>把「一次工具调用」收敛成一个方法，是为了让「每次重新握手」
     * 这个决策只有一个落点 —— 否则 {@code listTools} 和 {@code callTool}
     * 各写一份连接代码，某天有人给其中一个加了缓存，行为就不一致了。
     *
     * @param stage  失败时用来分类。★ 三个阶段（握手 / 列工具 / 调用工具）
     *               的排查方向完全不同，混成一个「MCP 调用失败」会让人从头查
     * @param action 拿到已握手的客户端之后要做什么
     */
    private <T> T withClient(long userId, McpGatewayException.Stage stage,
                             Function<McpSyncClient, T> action) {

        McpSyncClient client = null;
        try {
            client = buildAndInitialize(userId);
            return action.apply(client);
        } catch (McpGatewayException e) {
            throw e;
        } catch (Exception e) {
            // 把 SDK 的异常统一收口。★ 必须是 wrap 而不是 replace ——
            //   SDK 的异常类型（McpError / McpTransportException / 各种
            //   CompletionException）会随版本变化，原始 cause 一定要留着
            throw new McpGatewayException(stage, describe(e), e);
        } finally {
            closeQuietly(client);
        }
    }

    /**
     * 建一个客户端并完成握手。
     *
     * <p>★ 会话（{@code Mcp-Session-Id}）由官方 SDK 自己管 ——
     * 它在 {@code initialize} 的响应里读出来，之后每个请求自己带上。
     * 我们<b>完全没有碰它</b>，这是用 SDK 换来的一部分。
     */
    private McpSyncClient buildAndInitialize(long userId) {
        // ★★ 身份头。★ 它是个 lambda 但【不是】【每个请求都会重新算】——
        //    userId 是本次调用捕获的常量，同一次 withClient 里所有请求
        //    （握手、通知、工具调用）带的都是同一个身份。
        //
        //    ⚠️ 反过来，如果哪天把 userId 换成从某个共享字段读，
        //    并发时就会出现「A 的会话带着 B 的身份」—— 而日志里一切正常。
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(props.getBaseUrl())
                .endpoint(props.getEndpoint())
                .connectTimeout(props.getConnectTimeout())
                .httpRequestCustomizer((builder, method, uri, body, ctx) ->
                        builder.header(McpProtocol.HEADER_USER_ID, Long.toString(userId)))
                .build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(props.getRequestTimeout())
                .initializationTimeout(props.getInitializationTimeout())
                .clientInfo(new McpSchema.Implementation(
                        props.getClientName(), props.getClientVersion()))
                // ★ 声明「我不支持 roots / sampling / elicitation」。
                //   本项目确实不支持 —— 一个只读工具调用不需要服务端反过来
                //   问我们要东西。★ 声明了却做不到比不声明更糟：服务端会照着发，
                //   然后我们的客户端拿到一个处理不了的请求。
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .build();

        try {
            McpSchema.InitializeResult result = client.initialize();
            if (log.isDebugEnabled()) {
                // ★ 把协商结果打出来 —— 协议版本不匹配的症状是「某些功能莫名不可用」，
                //   而这一行是唯一能一眼看到「谈成了哪一版」的地方
                log.debug("MCP 握手完成 user={} 协商版本={} server={} {}",
                        userId, result.protocolVersion(),
                        result.serverInfo() == null ? "?" : result.serverInfo().name(),
                        result.serverInfo() == null ? "?" : result.serverInfo().version());
            }
            return client;
        } catch (Exception e) {
            // ★ 握手失败时也要把 client 关掉（transport 可能已经开了连接）——
            //   交给外层 finally 处理，这里只负责把异常标成 INITIALIZE 阶段
            throw new McpGatewayException(McpGatewayException.Stage.INITIALIZE,
                    describe(e), e);
        }
    }

    /**
     * 关掉连接。
     *
     * <p>★ <b>关闭失败不能影响调用结果。</b>
     * 我们已经拿到（或已经抛出）要返回的东西了，此时一个关闭异常
     * 会把一次<b>成功的</b>工具调用变成失败 —— 用户看到「查不到订单」，
     * 而订单数据其实已经在我们手里了。所以只记日志。
     */
    private void closeQuietly(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            // 优先优雅关闭（会发 HTTP DELETE 告诉服务端会话可以回收了，
            // 见 McpController 的 delete 方法）；SDK 自己带超时兜底
            if (!client.closeGracefully()) {
                log.debug("MCP 客户端优雅关闭未在超时内完成，已强制关闭");
            }
        } catch (Exception e) {
            log.debug("MCP 客户端关闭时出错（已忽略）：{}", e.getMessage());
        }
    }

    // ============================================================
    // SDK 类型 → 本项目的类型
    // ============================================================

    /**
     * SDK 的 {@code Tool} → {@link ToolSpec}。
     *
     * <p>三个字段直接搬，唯一要做判断的是 {@code description}：
     * 它在协议里是<b>可选</b>的（{@code Tool.description()} 可以是 null），
     * 而 {@link ToolSpec} 允许 null。★ <b>不要在这里填一句兜底文案</b> ——
     * 「没有描述」和「描述是『未提供说明』」是两回事，
     * 而后者会被模型当成真的描述读进上下文。
     */
    private static ToolSpec toSpec(McpSchema.Tool tool) {
        return new ToolSpec(tool.name(), tool.description(), tool.inputSchema());
    }

    /**
     * SDK 的 {@code CallToolResult} → {@link ToolOutcome}。
     *
     * <p>★ 这里是「结构化输出」和「自然语言输出」的汇合点：
     * 5.7 的工具多数返回 {@code content} 里的一段中文（给模型看），
     * 同时用 {@code structuredContent} 给一份 JSON（给程序看）。
     * 两者都搬，不在这里做取舍 —— 谁来用是下游的事。
     *
     * <p>⚠️ {@code isError()} 返回的是 {@code Boolean}（可空），不是 {@code boolean}。
     * 协议里它是可选字段，服务端可以不发。null 必须当成 {@code false}
     * （「没报错」）—— 当成 true 会让一次正常查询被模型描述成系统故障。
     */
    private static ToolOutcome toOutcome(McpSchema.CallToolResult result) {
        boolean isError = Boolean.TRUE.equals(result.isError());
        return new ToolOutcome(isError, extractText(result), extractData(result));
    }

    /**
     * 把 {@code content} 数组拼成一段文本。
     *
     * <p>★ 协议允许 {@code content} 里放图片、音频、内嵌资源 —— 本项目一个都不用，
     * 所以只取文本。**遇到非文本块时把它显式写成一行占位说明，
     * 而不是静默跳过**：静默跳过会让模型拿到一段「缺了一块」的文字，
     * 而它无法知道自己缺了什么。
     */
    private static String extractText(McpSchema.CallToolResult result) {
        List<McpSchema.Content> content = result.content();
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content item : content) {
            if (item instanceof McpSchema.TextContent text) {
                sb.append(text.text());
            } else {
                sb.append("[服务端返回了非文本内容：").append(item.type()).append("]");
            }
        }
        return sb.toString();
    }

    /** 结构化输出。它是个 {@code Object} —— 按 Map 搬，不是就不搬（不猜也不强转） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractData(McpSchema.CallToolResult result) {
        Object structured = result.structuredContent();
        if (structured instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (structured != null) {
            // 协议说它应该是个 object，但没强制。★ 记一行日志而不是抛异常 ——
            //   结构化输出是我们的【附加收益】，它形状不对不该让工具调用失败
            log.warn("工具的结构化输出不是 JSON 对象，已忽略：{}", structured.getClass().getName());
        }
        return null;
    }

    // ============================================================
    // 小工具
    // ============================================================

    /**
     * 把异常压成一句话。
     *
     * <p>★ 必须带上<b>异常类名</b>，不能只用 {@code getMessage()}。
     * SDK 抛的异常里有不少 {@code message} 是 null 或空
     * （比如某些 {@code CompletionException} 的包装），
     * 那样日志里会变成「MCP 调用失败：null」，排查时无从下手。
     */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getName();
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    /** 给调试探针用：本实现当前连的是哪个地址 */
    public Map<String, Object> describeTarget() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("enabled", props.isEnabled());
        info.put("url", props.getBaseUrl() + props.getEndpoint());
        info.put("requestTimeoutMs", props.getRequestTimeout().toMillis());
        info.put("connectionMode", "每次调用重新握手（见类注释第二节）");
        return info;
    }
}
