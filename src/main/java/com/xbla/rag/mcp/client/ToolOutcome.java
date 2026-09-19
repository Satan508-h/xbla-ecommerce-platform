package com.xbla.rag.mcp.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次工具调用的结果 —— <b>工具「跑了」之后给出的东西</b>。
 *
 * <h2>★★ {@link #isError} 的判据是「工具有没有给出答案」，不是「答案是不是空的」</h2>
 *
 * <p>规范字面把「business logic errors」也算 {@code true}，但本项目的划分依据是
 * <b>「这个错误该由谁修」</b>：
 *
 * <table border="1">
 *   <caption>同一个字段，两种含义</caption>
 *   <tr><th>情况</th><th>{@code isError}</th><th>模型该说什么</th></tr>
 *   <tr><td>「订单 SO123 不存在」</td><td><b>{@code false}</b></td>
 *       <td>「没找到这个订单，请核对订单号」—— <b>这是个完整的答案</b></td></tr>
 *   <tr><td>「数据库连不上」</td><td>{@code true}</td>
 *       <td>「系统暂时查不到，请稍后再试」</td></tr>
 * </table>
 *
 * <p>把「查无此单」标成 {@code true} 的后果是：模型会去为<b>系统故障</b>道歉，
 * 而它该说的是「请核对订单号」。<b>一句系统故障的道歉，用户会去重试；
 * 而重试一万次也变不出那个订单。</b>
 *
 * <p>这条判断由服务端的 {@code QueryOrderStatusTool} 做（阶段 5.7），
 * 客户端只负责原样搬运，<b>不重新解释</b> —— 重新解释会让两端的判据有机会不一致。
 *
 * <h2>⚠️ 它和 {@link McpGatewayException} 是两个层次</h2>
 *
 * <ul>
 *   <li>{@code ToolOutcome(isError=true)} —— <b>工具跑了</b>，它说「我没办成」</li>
 *   <li>{@link McpGatewayException} —— <b>工具根本没跑</b>（握手失败、超时、协议错）</li>
 * </ul>
 *
 * <p>区分它们不是为了报错好看，而是因为<b>下一步该做什么不一样</b>：
 * 前者换一个工具可能就好了，后者说明整台 Server 都联系不上，
 * 再试别的工具也是白试。由 {@code ToolLoop} 决定怎么把这个差别转达给模型。
 *
 * @param isError 见上。{@code true} 表示<b>工具自己报告失败</b>
 * @param text    给模型看的自然语言说明。★ <b>就是最终要拼进 Prompt 的那段字</b>
 * @param data    结构化输出（工具声明的 {@code outputSchema} 对应的那份）。
 *                可能为 null —— 工具可以不声明输出结构。
 *                ★ 目前<b>不进 Prompt</b>，只给调试探针和将来的前端用
 */
public record ToolOutcome(boolean isError, String text, Map<String, Object> data) {

    public ToolOutcome {
        // ★★ 这里【不能】用 Map.copyOf / Map.of —— 它们拒绝 null 值，
        //   而「key 在、值是 null」恰恰是可选字段的合法形态
        //   （一笔待发货的订单没有物流单号，但那个 key 必须出现）。
        //   NPE 会抛在【构造函数】里，堆栈指向 ToolOutcome 而不是那个 null 字段。
        //   同 5.7 的 ADR-058，那是本项目第一次踩它。
        data = data == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /** 工具给出了一个正常答案 */
    public static ToolOutcome ok(String text, Map<String, Object> data) {
        return new ToolOutcome(false, text, data);
    }

    /** 工具报告它办不成（查无此单、参数不合法……）—— ★ 这仍然是一次成功的调用 */
    public static ToolOutcome failed(String text) {
        return new ToolOutcome(true, text, null);
    }

    /** 进日志用的一句话。★ 不打印 text 全文，它可能包含业务数据 */
    public String describe() {
        return "isError=%s chars=%d structured=%s"
                .formatted(isError, text == null ? 0 : text.length(), data == null ? "无" : "有");
    }
}
