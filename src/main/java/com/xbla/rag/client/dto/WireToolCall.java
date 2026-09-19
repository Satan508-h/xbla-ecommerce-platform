package com.xbla.rag.client.dto;

/**
 * 模型返回的<b>一次工具调用</b> —— 线格式。
 *
 * <h2>★★ 它和 {@link ToolCall} 长得不一样，而这一点差点毁掉整条链路</h2>
 *
 * <p>{@link ToolCall} 是<b>领域对象</b>，三个字段是平的：
 * <pre>
 *   ToolCall(id, name, arguments)
 * </pre>
 *
 * <p>而协议里真正传过来的 JSON <b>是嵌套的</b> —— {@code name} 和
 * {@code arguments} 包在一个 {@code function} 对象里：
 * <pre>{@code
 * {
 *   "index": 0,
 *   "id": "call_00_j820x57GvND1MrHFWzrh4959",
 *   "type": "function",
 *   "function": {
 *     "name": "query_order_status",
 *     "arguments": "{\"order_no\": \"SO202607200008\"}"
 *   }
 * }
 * }</pre>
 *
 * <p>★★ <b>这不是一个「理论上可能」的差异 —— 它真的发生了。</b>
 * 5.8 第一版直接拿 {@link ToolCall} 去接响应，于是 Jackson 在顶层
 * 找不到 {@code name} 字段，{@code name} 静默变成 <b>null</b>。
 * 后果链条：
 * <pre>
 *   name=null
 *     → 官方 SDK 的 CallToolRequest.Builder.name(null) 抛
 *       IllegalArgumentException("name must not be empty")
 *     → 它不是 McpGatewayException，逃过了 ToolLoop 的兜底
 *     → 整次问答 status=2，而 qa_log 里的错误消息是
 *       一句和订单毫无关系的 "name must not be empty"
 * </pre>
 *
 * <p>⚠️ <b>而当时 486 个测试全是绿的</b> —— 因为所有测试
 * 都是直接 {@code new ToolCall(...)} 构造出来的，
 * <b>绕过了反序列化那一层</b>。见 {@code WireToolCallTest}。
 *
 * <h2>为什么是「新增一个类」而不是给 ToolCall 加注解</h2>
 *
 * <p>可以用 {@code @JsonUnwrapped} 之类的手法让一个 record 同时适配
 * 两种形状，但那会让<b>领域对象带上协议的细节</b> ——
 * 而 {@code client/dto/} 里 {@code ChatRequest}（领域）和
 * {@code WireChatRequest}（线格式）分开，本来就是这个包的纪律。
 *
 * <p>分开之后还有第二个好处：{@code type} 和 {@code index} 这两个
 * <b>只在线上有意义</b>的字段留在这里，不会污染 {@link ToolCall}。
 *
 * @param index    ★ 只在<b>流式</b>响应里有意义 —— 它标记「这个增量属于
 *                 第几个工具调用」。非流式响应里也有这个字段，但恒为 0。
 *                 <b>我们目前只做非流式</b>（见 {@code ToolLoop}），
 *                 所以它只是被原样接住，不参与任何判断
 * @param id       工具调用标识。★ 原样搬进 {@link ToolCall#id()}，一个字符都不能改
 * @param type     目前恒为 {@code "function"}。留着是为了「协议将来加了别的类型」
 *                 时我们不会把它当成 function 处理
 * @param function 见 {@link Function}。<b>可能为 null</b>（畸形响应）
 */
public record WireToolCall(

        Integer index,

        String id,

        String type,

        Function function

) {

    /**
     * 工具调用的「函数」部分。
     *
     * @param name      工具名。★ 它是<b>模型填的</b>，所以必须当成不可信输入 ——
     *                  查不到就叫「未知的工具」，不是异常
     * @param arguments 参数，<b>是一个 JSON 字符串而不是对象</b>。
     *                  ★ 正因为是字符串，它才能被逐字回声回去（见 {@link ToolCall}）
     */
    public record Function(String name, String arguments) {

        /** 参数是不是空的。{@code "{}"} 和空串都算 —— 无参工具两种情况都会出现 */
        public boolean hasNoArguments() {
            return arguments == null || arguments.isBlank() || "{}".equals(arguments.trim());
        }
    }

    /**
     * 转成领域对象。
     *
     * <p>★ 这一层转换是<b>刻意做薄</b>的：只做形状搬运，不做任何校验或兜底。
     * {@code function} 为 null 时 {@code name} 就是 null —— 由调用方
     * （{@code SdkMcpToolGateway}）去判断「一个没有名字的工具调用该怎么办」。
     *
     * <p>⚠️ 不在这里过滤掉畸形的条目，因为<b>静默丢弃一个工具调用</b>
     * 比报错更难查：模型会以为它调了，而日志里什么都没有。
     */
    public ToolCall toDomain() {
        return new ToolCall(
                id,
                function == null ? null : function.name(),
                function == null ? null : function.arguments());
    }

    /**
     * 反过来：领域对象 → 线格式。<b>回声（第二跳）时用</b>。
     *
     * <p>★★ <b>这个方法的存在，是又一次真实故障换来的。</b>
     *
     * <p>第一版让 {@code WireChatRequest.WireMessage.toolCalls} 直接收
     * {@link ToolCall}（平的），于是我们发回去的是：
     * <pre>{@code
     *   "tool_calls": [{"id":"call_00_x","name":"query_order_status","arguments":"{}"}]
     * }</pre>
     * 而协议要的是：
     * <pre>{@code
     *   "tool_calls": [{"id":"call_00_x","type":"function",
     *                   "function":{"name":"query_order_status","arguments":"{}"}}]
     * }</pre>
     *
     * <p>服务端的反应（实测 2026-09-19，DeepSeek 官方）：
     * <pre>
     *   HTTP 422  messages[1]: missing field `type`
     * </pre>
     *
     * <p>⚠️ 这两个方向（收 / 发）是<b>同一个形状问题的两个实例</b>，
     * 而它们各自被不同的盲区漏掉了：
     * <ul>
     *   <li><b>收</b>：所有测试都直接 {@code new ToolCall(...)}，<b>从没反序列化过</b></li>
     *   <li><b>发</b>：断言都下在领域对象（{@code ChatRequest.Turn}）上，
     *       <b>从没序列化过</b></li>
     * </ul>
     * ★ <b>一句话总结：DTO 的形状，只有真的序列化/反序列化过一次才算验证过。</b>
     */
    public static WireToolCall fromDomain(ToolCall call) {
        return new WireToolCall(
                null,                       // ★ index 只在流式里有意义；回声时【不能】自己编一个
                call.id(),
                "function",
                new Function(call.name(), call.arguments()));
    }
}
