package com.xbla.rag.mcp;

/**
 * 工具的<b>参数</b>不合法 —— 会被翻译成一个 JSON-RPC 协议错误。
 *
 * <h2>★ 什么算「参数不合法」，什么不算</h2>
 *
 * <p>这条界线是整个 5.7 里最容易划错的地方，因为规范本身说得含糊。
 * 本项目的划分依据是<b>「这个错误该由谁修」</b>：
 *
 * <table border="1">
 *   <caption>两类错误的划分</caption>
 *   <tr><th>情况</th><th>抛什么</th><th>对模型意味着</th></tr>
 *   <tr><td>缺必填参数 / 类型不对 / 多了未知参数</td>
 *       <td><b>本类</b> → JSON-RPC error</td>
 *       <td>「我这次调用写错了，改一下参数重试」</td></tr>
 *   <tr><td>订单号查不到 / 数据库连不上 / 业务规则拒绝</td>
 *       <td>{@link McpToolResult#failure} → {@code isError: true}</td>
 *       <td>「工具跑了，但没能给出答案」</td></tr>
 * </table>
 *
 * <p><b>为什么「查不到」不算参数错误</b>：如果它算，那么每一次「这个订单号不存在」
 * 都会变成一个协议层的报错，而模型分不清「我参数写错了」和「查无此单」——
 * 前者应该重试，后者应该告诉用户核对订单号。划到 {@code isError} 里，
 * 模型至少能读到那句话。
 *
 * <p>⚠️ 但注意 {@code isError: true} 的语义是「<b>工具失败</b>」而不是「答案是空的」。
 * 一个「你有 0 张券」的回答是<b>成功</b>的。详见 {@link McpToolResult} 的类注释。
 */
public class McpToolException extends RuntimeException {

    /**
     * 机器可读的错误细分，进 JSON-RPC error 的 {@code data}。
     *
     * <p>格式约定成 {@code 错误类型:字段名[:补充]}，比如
     * {@code "missing_required:order_no"}、{@code "unknown_param:user_id"}。
     * 让下游能写 {@code data.startsWith("unknown_param:")} 这样的判断，
     * 而不是去正则匹配一句中文。
     */
    private final String detail;

    /**
     * @param message ★ <b>给模型看的完整句子</b>：说清楚「哪里错了」和
     *                「怎么改」。它会被原样放进 JSON-RPC 的 {@code error.message}，
     *                而模型是能看到这一段并据此自我纠正的 ——
     *                所以只写「缺参数」而不写「缺哪个、该长什么样」是浪费了一次纠错机会
     * @param detail  给程序看的短码，见 {@link #detail}
     */
    public McpToolException(String message, String detail) {
        super(message);
        this.detail = detail;
    }

    public String detail() {
        return detail;
    }
}
