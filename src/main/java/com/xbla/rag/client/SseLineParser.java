package com.xbla.rag.client;

/**
 * SSE（Server-Sent Events）行解析器 —— <b>纯函数，零 HTTP、零 Spring</b>。
 *
 * <p>把 {@code BodyHandlers.ofLines()} 读出来的原始行，翻译成
 * 「这是一条数据 / 流结束了 / 忽略它」三种结论。
 *
 * <p>之所以单独抽出来做成纯函数类，是因为 SSE 的边界情况比想象中多，
 * 而这些边界情况在真实流里很难复现（要刚好断在某个位置）。
 * 做成纯函数后可以用单测把所有边界一次覆盖，
 * 不用真去调 API 撞运气。
 *
 * <h3>SSE 协议长什么样</h3>
 *
 * <p>服务端推给我们的是纯文本行，形如：
 * <pre>
 * data: {"id":"...","choices":[{"delta":{"content":"你"}}]}
 *
 * data: {"id":"...","choices":[{"delta":{"content":"好"}}]}
 *
 * data: [DONE]
 * </pre>
 *
 * <h3>要处理的四类行</h3>
 * <table border="1">
 *   <caption>行类型与处置</caption>
 *   <tr><th>原始行</th><th>处置</th><th>原因</th></tr>
 *   <tr><td>{@code ""}（空行）</td><td>忽略</td>
 *       <td>SSE 用空行分隔事件，不携带数据</td></tr>
 *   <tr><td>{@code ": ping"}</td><td>忽略</td>
 *       <td>以冒号开头的是<b>注释</b>，常用作保活心跳
 *           （防止中间的反向代理把空闲连接掐掉）</td></tr>
 *   <tr><td>{@code data: [DONE]}</td><td>流结束</td>
 *       <td>OpenAI 协议的约定，看到它就该停止读取并关闭连接</td></tr>
 *   <tr><td>{@code data: {...}}</td><td>数据</td>
 *       <td>正常增量，交给 Jackson 反序列化</td></tr>
 * </table>
 *
 * <p><b>为什么忽略 {@code event:} / {@code id:} / {@code retry:}？</b>
 * 那些是 SSE 协议为「自定义事件类型」和「断线重连」设计的字段。
 * 模型的流式响应不会用到它们（我们收到的 {@code event:} 只有默认的 message），
 * 而断线重连由我们自己的降级链处理，不走 SSE 的重连机制。
 * 明确忽略而不是「不认识就报错」，是因为服务端将来加字段不应该让我们的解析器崩掉 ——
 * <b>对协议的宽容度要留够</b>。
 */
public final class SseLineParser {

    /** {@code data:} 前缀，含冒号 */
    private static final String DATA_PREFIX = "data:";

    /** 流结束标记 */
    private static final String DONE_MARKER = "[DONE]";

    private SseLineParser() {
        // 工具类，不需要实例
    }

    /**
     * 一行的解析结论。
     *
     * @param kind    类型
     * @param payload 当 {@code kind == DATA} 时是 JSON 字符串；其余情况为 null
     */
    public record Parsed(Kind kind, String payload) {

        static Parsed ignore() {
            return new Parsed(Kind.IGNORE, null);
        }

        static Parsed data(String payload) {
            return new Parsed(Kind.DATA, payload);
        }

        static Parsed done() {
            return new Parsed(Kind.DONE, null);
        }

        /** 是不是一条待反序列化的数据行 */
        public boolean isData() {
            return kind == Kind.DATA;
        }

        /** 是不是流结束标记 */
        public boolean isDone() {
            return kind == Kind.DONE;
        }
    }

    /** 行的类型 */
    public enum Kind {
        /** 携带 JSON 数据 */
        DATA,
        /** 流结束（收到 {@code data: [DONE]}） */
        DONE,
        /** 空行 / 注释 / 协议其他字段 —— 跳过即可 */
        IGNORE
    }

    /**
     * 解析一行。
     *
     * <p><b>注意 {@code data:} 后面空格的处理</b>：
     * SSE 规范里 {@code data:} 之后<b>可以</b>跟一个空格，也可以不跟。
     * 实测两个供应商都带空格，但不能假设它们永远带 ——
     * 所以这里统一用 {@code trim()} 处理，两种情况都能覆盖。
     *
     * @param line 原始行（不含换行符）
     * @return 解析结论；传入 null 或空白行时返回 IGNORE
     */
    public static Parsed parse(String line) {
        if (line == null) {
            return Parsed.ignore();
        }

        // 先 trim 再判断，避免服务端在行尾带 \r（HTTP 分帧有时会残留）
        String trimmed = line.strip();

        if (trimmed.isEmpty()) {
            return Parsed.ignore();
        }

        // ★ 注释行（心跳）：以冒号开头。
        //   必须放在 data: 判断之前吗？其实不必 —— ": ping" 不以 "data:" 开头，
        //   顺序不影响正确性。放前面只是让「先处理最简单的情况」这个阅读顺序更顺。
        if (trimmed.startsWith(":")) {
            return Parsed.ignore();
        }

        if (!trimmed.startsWith(DATA_PREFIX)) {
            // event: / id: / retry: 等，明确忽略（见类注释）
            return Parsed.ignore();
        }

        // 去掉 "data:" 和可能存在的空格
        String payload = trimmed.substring(DATA_PREFIX.length()).strip();

        if (payload.isEmpty()) {
            // "data:" 后面什么都没有 —— 协议上无意义，忽略
            return Parsed.ignore();
        }

        if (DONE_MARKER.equals(payload)) {
            return Parsed.done();
        }

        return Parsed.data(payload);
    }
}
