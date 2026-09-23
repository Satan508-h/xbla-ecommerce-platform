package com.xbla.rag.service;

import com.xbla.rag.common.EvalMark;
import com.xbla.rag.common.TraceId;

/**
 * 一次问答的<b>调用上下文</b> —— 从排队层传进 {@link ChatService} 的那点信息。
 *
 * <h2>★ 为什么需要它，而不是继续加参数</h2>
 *
 * <p>阶段 6 让请求在到达 {@link ChatService} 之前先排队，于是有三样东西
 * 需要从这里传进去：
 *
 * <pre>
 *   traceId        排队层生成的链路 ID（见 TraceId 类注释）
 *   queueMs        排了多久
 *   queuePosition  刚入队时前面有几个人
 * </pre>
 *
 * <p>★ <b>阶段 7 又加了第四样（{@code eval}），而这一条正是上面那段话的兑现</b>：
 * 加一个字段没有改任何方法签名。如果当初是「继续加参数」，
 * 今天要改的就是 {@code askStream} 以及它下游那 6 个私有落库方法的签名 ——
 * 而每一次传递都是一次「少传一个」的机会。
 *
 * <p>加成三个参数的话，{@code askStream(request, sink, traceId, queueMs, queuePosition)}
 * 会变成五个参数，而这个签名还会被继续往下传给 6 个私有的落库方法 ——
 * <b>每一次传递都是一次「少传一个」的机会</b>，而少传的症状是某个路径的
 * {@code queue_ms} 恒为 NULL，从数据上完全看不出来（那正是 V9 要防的事）。
 *
 * <p>收成一个对象之后，传的是「这一个请求的全部上下文」，
 * 加字段不会改任何签名。
 *
 * <h2>★★ 什么时候它是「没有排队」</h2>
 *
 * <p>{@link #queueMs()} 和 {@link #queuePosition()} 都是 <b>Integer 而不是 int</b>，
 * 而且 {@code null} 是<b>常态，不是异常</b>：
 *
 * <pre>
 *   名额有空，第一次 acquire 就拿到了  →  queueMs = null, queuePosition = null
 *   排队了                             →  queueMs = 等待毫秒, queuePosition = 初始位置
 * </pre>
 *
 * <p>★ 关键在第一种：<b>它不该记成 0</b>。记 0 的话阶段 7 分不清
 * 「没排队」和「排队等了 0 毫秒」，而更糟的是它会把
 * 「平均等多久」这个平均值往下拉 —— 绝大多数请求都是没排队的，
 * 于是那个平均值会被稀释到接近 0，<b>看起来像「排队功能没生效」</b>。
 * （同 ADR-010「拿不到就记 NULL」、以及 {@code qa_log.queue_ms} 的列注释。）
 *
 * @param traceId       链路 ID，<b>非空</b>
 * @param queueMs       排队等待毫秒；<b>null = 没排队</b>
 * @param queuePosition 刚入队时前面有几个人（0-based）；<b>null = 没进过队列</b>
 * @param eval          评测运行标记；<b>null = 真实用户的提问</b>（常态）。
 *                      见 {@link EvalMark} —— 它只被透传进 {@code qa_log}，
 *                      不参与任何业务判断
 */
public record CallContext(String traceId, Integer queueMs, Integer queuePosition, EvalMark eval) {

    /**
     * 没经过排队层的调用（测试、探针、以及 {@code xbla.ratelimit.enabled=false}）。
     *
     * <p>★ 排队两列都是 null —— 这正是 {@code enabled=false} 时该有的样子。
     * 如果这里填 0，阶段 7 就分不清「没开排队」和「开了但没排队」了。
     */
    public static CallContext fresh(String traceId) {
        return new CallContext(traceId, null, null, null);
    }

    /**
     * 带评测标记、但没经过排队层的调用。
     *
     * <p>★ 它存在的场景是<b>测试</b>：走 {@code chatService.ask(...)} 直接验证
     * 「评测标记落不落库」，不必把排队层拉起来。
     */
    public static CallContext fresh(String traceId, EvalMark eval) {
        return new CallContext(traceId, null, null, eval);
    }

    /** 自动生成一个 traceId 的「没排队」上下文 */
    public static CallContext fresh() {
        return fresh(TraceId.newId());
    }
}
