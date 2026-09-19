package com.xbla.rag.mcp.client;

/**
 * <b>工具根本没跑成</b> —— 连不上、超时、握手失败、协议错。
 *
 * <h2>★ 它和「工具跑了但说办不到」是两回事</h2>
 *
 * <p>见 {@link ToolOutcome} 的类注释：
 * <ul>
 *   <li>{@link ToolOutcome#isError()} 为 true —— 工具跑了，它说「我没办成」</li>
 *   <li><b>本异常</b> —— 工具根本没跑</li>
 * </ul>
 *
 * <h2>★ 它【不会】让整次问答失败</h2>
 *
 * <p>这是刻意的。{@code ToolLoop} 会捕获它，然后<b>把它当成一次工具结果喂回模型</b>
 * （用户拍板的方案）。所以工具宕机时用户拿到的是
 * 「实时查询暂时不可用」，而不是「服务内部错误」。
 *
 * <p>⚠️ <b>但它也绝不能冒到 {@code ChatServiceImpl} 的外层 catch 上去。</b>
 * 那个 catch 的语义是「<b>模型链路</b>失败 → {@code qa_log.status=2}」；
 * 工具失败混进去，阶段 7 会把「工具挂了」统计成「模型挂了」——
 * <b>归因彻底错乱，而且数据上看不出来</b>。
 * 同 {@code ADR-042} 记的那次「检索异常冒到外层」。
 *
 * <p>所以它是 {@link RuntimeException} 但<b>只在一个地方被捕获</b>，
 * 而且那个地方就在 {@code ToolLoop} 里面。继承 RuntimeException 是因为
 * 它不该被逐层声明 —— 逐层声明会诱使每一层都「顺手处理一下」，
 * 而那些处理都不会是对的。
 *
 * <h2>为什么不用 {@code ModelCallException}</h2>
 *
 * <p>那个类的每一个字段（{@code provider} / {@code modelKey} / HTTP 状态码 /
 * {@code ModelErrorKind}）在工具场景里<b>全都没有意义</b> ——
 * 没有「供应商」，也没有「模型」。硬套的结果是日志里出现
 * {@code provider=mcp-model=?} 这种要花时间才能看懂的东西。
 */
public class McpGatewayException extends RuntimeException {

    /**
     * 失败发生在哪一步。★ 光有异常消息不够 —— 「连不上」和「连上了但握手被拒」
     * 的排查方向完全不同，而它们都表现为「工具没结果」。
     */
    public enum Stage {
        /** 建连接就失败了：地址写错、Server 没起来、网络不通 */
        CONNECT("连接"),
        /** 握手失败：协议不匹配、身份被拒（401）、会话无效（400） */
        INITIALIZE("握手"),
        /** 列工具失败 */
        LIST_TOOLS("列工具"),
        /** 调用工具失败（传输层面）*/
        CALL_TOOL("调用");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Stage stage;

    public McpGatewayException(Stage stage, String message, Throwable cause) {
        super("MCP " + stage.label() + "失败：" + message, cause);
        this.stage = stage;
    }

    public McpGatewayException(Stage stage, String message) {
        this(stage, message, null);
    }

    public Stage stage() {
        return stage;
    }
}
