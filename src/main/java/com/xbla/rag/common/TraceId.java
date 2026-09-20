package com.xbla.rag.common;

import java.util.UUID;

/**
 * 链路 ID 的生成 —— <b>全项目单一出处</b>（阶段 6 抽出）。
 *
 * <h2>★ 为什么它从 ChatServiceImpl 里搬了出来</h2>
 *
 * <p>阶段 6 之前，{@code traceId} 是 {@code ChatServiceImpl} 在方法第一行自己生成的
 * （{@code newTraceId()} 是它的私有静态方法）。那时这样没问题：
 * <b>一次问答就是一个请求</b>，谁生成都一样。
 *
 * <p>阶段 6 打破了这一点：请求在到达 {@code ChatService} 之前要先<b>排队</b>，
 * 而排队期间就要往 SSE 推「你在第几位」——那个事件里得有个 id，
 * 否则用户报「我排了很久」，我们无从查起。
 *
 * <p>★★ 于是有了一个必须做的选择：排队层**再生成一个 id**，
 * 还是**沿用同一个**？
 *
 * <pre>
 *   两个 id  →  用户拿到的「排队 90 秒」和「回答耗时 3 秒」对不上号，
 *               日志里它们是两条不相干的记录。
 *               ★ 而且这两个 id 长得一模一样（都是 32 位十六进制），
 *                 没人会怀疑它们不是一回事 —— 这才是最麻烦的地方：
 *                 它会一直看起来是对的，直到某天真的需要串联起来。
 *   一个 id  →  排队、检索、生成、落库，全程同一个 trace_id，
 *               前端从 SSE 的第一个事件就有它。
 * </pre>
 *
 * <p>选了后者。所以这个类存在的意义是：<b>让「同一个 id」这件事有一个单一出处</b>，
 * 而不是两处各自 {@code UUID.randomUUID()} 然后靠约定保持一致。
 *
 * <h2>格式</h2>
 *
 * <p>32 位十六进制（去掉连字符的 UUID v4），和 {@code qa_log.trace_id VARCHAR(64)}
 * 的容量匹配，留了一倍余量。
 *
 * <p>★ 去掉连字符是为了它在 URL query、日志行、SSE 事件里都干净 ——
 * 少一类需要转义的字符。
 */
public final class TraceId {

    private TraceId() {
    }

    /**
     * 生成一个新的链路 ID。
     *
     * <p>⚠️ 它<b>不是</b> {@code requestId} 的同义词 —— 本项目里没有第二个 id。
     * 排队层持有的是它、{@code qa_log.trace_id} 存的是它、
     * SSE 的 {@code meta} 事件推的也是它。
     */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
