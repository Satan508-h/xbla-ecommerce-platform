package com.xbla.rag.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次工具调用的结果 —— <b>注意它不是异常，也不一定是「成功」。</b>
 *
 * <h2>★★ {@code isError} 的判据是「工具有没有给出答案」，不是「答案是不是空的」</h2>
 *
 * <p>这是整个 5.7 里第二处最容易划错的地方（第一处见 {@link McpToolException}）。
 * 规范把「Invalid input data / Business logic errors」划进 {@code isError: true}，
 * 读起来像是「查不到也算错」。<b>本项目不这么划</b>，因为那样会得到一个坏结果：
 *
 * <pre>
 *   「你有 0 张券」        → isError: false   ✅ 这是个完整的答案
 *   「用户没问任何事」      → isError: false   ✅
 *   「订单 SO123 不存在」   → isError: false   ✅ 这也是个答案：「没有这一单」
 *   「数据库连不上」        → isError: true    ❌ 工具【没能】给出答案
 *   「订单号格式不对」      → isError: true    ❌ 参数问题，让它改了重试
 * </pre>
 *
 * <p>把「查无此单」标成 {@code isError: true} 的后果是：模型看到错误标记，
 * 会倾向于说「系统出了点问题，请稍后再试」—— 而实际上它应该说的是
 * 「没找到这个订单，请核对一下订单号」。<b>一句系统故障的道歉，用户会去重试；
 * 而重试一万次也变不出那个订单。</b>
 *
 * <h2>两种内容都给：文本给模型读，结构化给程序查</h2>
 *
 * <p>MCP 2025-06-18 起支持 {@code structuredContent}。
 * 本项目两种都返回：
 * <ul>
 *   <li>{@code text} —— 模型直接读的自然语言（也是没有结构化能力的客户端的回退）</li>
 *   <li>{@code data} —— 机器可读的原始数据，配套 {@code outputSchema} 声明</li>
 * </ul>
 *
 * <p>为什么两个都要：只给文本，下游没法可靠地取出「物流单号」这种字段
 * （模型复述一遍就可能改数字）；只给结构化，模型就得自己把 JSON 翻译成人话，
 * 而它翻译时会丢上下文。
 *
 * @param isError 工具是否<b>没能</b>给出答案。见类注释
 * @param text    给模型读的正文。<b>不能为 null</b> —— 空文本会让模型以为工具什么都没说
 * @param data    机器可读的数据，可为 null（失败时通常没有）
 */
public record McpToolResult(boolean isError, String text, Map<String, Object> data) {

    public McpToolResult {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("工具结果的正文不能为空 —— 空文本会让模型以为工具什么都没说");
        }
        // ★★ 这里【不能】用 Map.copyOf / Map.of —— 它们拒绝 null 值。
        //
        //   而「key 在、值是 null」恰恰是 JSON Schema 里【可选字段】的合法形态：
        //   一笔还没发货的订单，logistics_no 就是 null，但那个 key 必须出现
        //   （下游的 data.logistics_no 在某些语言里遇到缺 key 会直接报错）。
        //
        //   Map.copyOf 遇到 null 抛的是 NPE，而且抛在【构造函数】里 ——
        //   现象是「工具查到数据了，但包装结果时崩了」，栈顶指向这里而不是
        //   那个 null 字段，排查方向很容易被带偏。
        data = data == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /**
     * 工具给出了一个答案。
     *
     * <p>★ 注意「答案是空的」（比如查无此单）也走这里 —— 见类注释。
     *
     * @param data 结构化数据，可为 null
     */
    public static McpToolResult ok(String text, Map<String, Object> data) {
        return new McpToolResult(false, text, data);
    }

    /** 工具给出了一个答案，但没有结构化数据 */
    public static McpToolResult ok(String text) {
        return new McpToolResult(false, text, null);
    }

    /**
     * 工具<b>没能</b>给出答案（数据库挂了、参数在这个语义下不可用）。
     *
     * <p>⚠️ 不要用它表达「查不到」—— 那会变成一句系统故障的道歉。见类注释。
     */
    public static McpToolResult failure(String text) {
        return new McpToolResult(true, text, null);
    }

    /** 有没有结构化数据 */
    public boolean hasData() {
        return data != null && !data.isEmpty();
    }

    /** 调试探针用的摘要，不含正文 */
    public String summary() {
        return "result[isError=" + isError + ", text=" + text.length() + "字"
                + ", data=" + (hasData() ? data.size() + "字段" : "无") + "]";
    }
}
