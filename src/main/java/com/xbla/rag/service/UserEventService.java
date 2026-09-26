package com.xbla.rag.service;

import com.xbla.rag.dto.UserEventRequest;

/**
 * 浏览器行为事件的上报入口（阶段 9.6b）。
 *
 * <h3>★★★ 「失败一律静默」是这里的核心约定，而它有代价</h3>
 *
 * <p>埋点是<b>尽力而为</b>的：它绝不能影响主链路，也绝不该让用户看到错误。
 * 所以不认识的类型、缺幂等键、缺时间 —— 一律<b>丢弃并回 200</b>，不回 400。
 *
 * <p>★ 代价是「埋点坏了没人知道」。所以丢弃<b>不是真的静默</b>：
 * 三类结局由 {@link Outcome} 明确区分，调用方（和测试）拿得到，
 * 服务端也会记一条 DEBUG 日志。<b>「不打扰用户」不等于「不留痕迹」。</b>
 *
 * <h3>★ 但没有「限流」这一层，这是刻意的</h3>
 *
 * <p>不把上报塞进阶段 6 那套名额（{@code ChatPermitService}）：
 *
 * <pre>
 *   塞进去 → 埋点和【问答】抢名额 ⇒ 刷埋点能把问答挡在门外
 *   不塞   → 最坏是被刷出一堆垃圾行，而它【不花一分钱】、不调模型、不碰问答
 * </pre>
 *
 * <p>⇒ 用一个便宜的防护组合代替：类型白名单 + 体积上限 + 幂等键。
 * <b>埋点被刷的代价远小于「埋点挤掉问答」的代价。</b>
 */
public interface UserEventService {

    /** 一次上报的三种结局。★ 三态，不是「成功/失败」两态 */
    enum Outcome {
        /** 真的写进去了 */
        WRITTEN,
        /** 幂等命中：同一个 {@code eventNo} 已经在了。★ 不是错误，是设计 */
        DUPLICATE,
        /** 被校验挡掉了（类型不在白名单 / 缺幂等键 / 缺时间 / 字段超长 / payload 过大） */
        DISCARDED
    }

    /**
     * 记一条事件。
     *
     * <p>★ 这个方法<b>不抛异常</b> —— 校验失败是 {@link Outcome#DISCARDED}，不是异常。
     * 抛出去会被兜底 handler 变成 500，而一次埋点失败不该让服务端「报错」。
     *
     * @param req 请求体；{@code null} 也是合法的输入（回 {@code DISCARDED}）
     */
    Outcome record(UserEventRequest req);
}
