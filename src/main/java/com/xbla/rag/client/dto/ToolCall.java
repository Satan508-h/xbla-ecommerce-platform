package com.xbla.rag.client.dto;

/**
 * 模型请求调用的一次工具调用 —— <b>模型说「我要调这个工具，参数是这些」</b>。
 *
 * <h2>★★ 三个字段都只能【原样搬运】，一个都不能改写</h2>
 *
 * <p>这不是「最好这样」，是<b>实测出来的硬约束</b>（2026-09-19，{@code deepseek-flash}）。
 * 把 {@code id} 换成一个我们自己生成的、格式一样的字符串，再发回去时服务端会报：
 *
 * <pre>
 *   HTTP 400  The `reasoning_content` in the thinking mode must be passed back to the API.
 * </pre>
 *
 * <p>而这个 400 属于 {@link com.xbla.rag.client.ModelErrorKind#BAD_REQUEST} ——
 * <b>不降级</b>。症状是「P0 永远失败」，而日志里那个错误消息
 * 说的是 {@code reasoning_content}，会把人往「我们少发了字段」的方向带，
 * 而真相是「我们把 id 改了」。
 *
 * <p>控制变量实验（每一行只改一个字段）：
 * <table border="1">
 *   <caption>哪些改法会炸</caption>
 *   <tr><th>assistant 消息</th><th>reasoning_content</th><th>tool_call id</th><th>结果</th></tr>
 *   <tr><td>原样回传</td><td>带上</td><td>原样</td><td>✓</td></tr>
 *   <tr><td>去掉 rc</td><td>去掉</td><td><b>原样</b></td><td>✓</td></tr>
 *   <tr><td>改 id + 去掉 rc</td><td>去掉</td><td><b>改写</b></td><td><b>✗ 400</b></td></tr>
 *   <tr><td>改 id，rc 保留</td><td>带上</td><td><b>改写</b></td><td>✓</td></tr>
 * </table>
 *
 * <p>推测的机制：服务端按 {@code tool_call id} 缓存了那一段推理，
 * 请求里没带 {@code reasoning_content} 时它去查缓存 —— 查得到就放行，
 * 查不到就要求你显式回传。**我们的代码不该依赖这个推测**，
 * 结论只有一条：<b>id 原样搬运，参数原样搬运。</b>
 *
 * <h2>★ 两家的 id 格式完全不同，所以「拼一个」这条路根本不存在</h2>
 *
 * <pre>
 *   deepseek 官方    call_00_j820x57GvND1MrHFWzrh4959
 *   硅基流动          01a0b9dc02aefba5c357a564948c3afc     ← 32 位 hex，没有前缀
 * </pre>
 *
 * @param id        工具调用的唯一标识。<b>由模型的服务端生成，我们只负责原样回声。</b>
 *                  下一轮的 {@code role=tool} 消息靠它配对（{@code tool_call_id}）
 * @param name      工具名。它是<b>模型填的</b> —— 所以必须拿它去注册表里查，
 *                  查不到就是一次普通的工具错误，不是异常
 * @param arguments 参数，<b>是一个 JSON 字符串而不是对象</b>。
 *                  这是 OpenAI 协议的形状（{@code "arguments": "{\"a\":1}"}），
 *                  不是我们偷懒 —— 所以它要原样回声，解析成 Map 是下一步的事。
 *                  ⚠️ 模型偶尔会吐出畸形 JSON，解析必须当成<b>可恢复的工具错误</b>
 *                  而不是异常，见 {@link com.xbla.rag.agent.tool.ToolLoop}
 */
public record ToolCall(String id, String name, String arguments) {

    /**
     * 参数是不是空的。
     *
     * <p>无参工具（比如「我的优惠券」）的 {@code arguments} 是
     * {@code "{}"} 或空串，不是 null —— 两种都要能识别出来。
     */
    public boolean hasNoArguments() {
        return arguments == null || arguments.isBlank() || "{}".equals(arguments.trim());
    }

    /** 给日志用的一句话摘要。★ 截断参数，避免把整段订单内容打进日志 */
    public String describe() {
        String args = arguments == null ? "" : arguments;
        if (args.length() > 80) {
            args = args.substring(0, 80) + "…";
        }
        return "%s(%s) [id=%s]".formatted(name, args, id);
    }
}
