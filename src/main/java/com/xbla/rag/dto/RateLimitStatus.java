package com.xbla.rag.dto;

/**
 * 排队限流的对外状态快照 —— {@code GET /api/status/ratelimit}。
 *
 * <h3>★★★ 它不是 {@code /api/debug/ratelimit/state} 的复制品，是它的【白名单子集】</h3>
 *
 * <p>阶段 8 要做「限流实况」页给公网看，而那两个 debug 端点是
 * {@code @Profile("local")} 的 —— 生产 profile 下<b>根本不存在</b>
 * （这正是我们想要的，见 {@code application-prod.yml} 的注释）。
 *
 * <p>所以需要一个生产下也存在的端点。但<b>不能</b>把 debug 那个 map 直接搬过来，
 * 它里面有几样东西不该出现在公网上：
 *
 * <pre>
 *   ✗ queueHead      —— ★★ 队列头部的 traceId 列表。那是【别人的】标识符
 *   ✗ keyPrefix      —— Redis 键名前缀，基础设施细节
 *   ✗ channel        —— Pub/Sub 频道名，同上
 *   ✗ seq            —— 内部单调序号
 *   ✗ aliveSize      —— 只在排查僵尸名额时有意义
 * </pre>
 *
 * <p>★ 判据是<b>白名单</b>而不是黑名单：这个 record 上写出来的才给出去。
 * 将来 debug 的 map 里加一个字段，<b>不会自动出现在这里</b> ——
 * 同 {@code eval_report.py} 的 {@code OMITTED_PATHS} 和
 * {@code QaLogMapperProjectionTest.NOT_SELECTED}：
 * <b>「给出去的」和「不给的」都要是被看见的决定。</b>
 *
 * <h3>★ 这个端点是只读的，而且【零成本】</h3>
 *
 * <p>它不调模型、不写库、不改任何状态 —— 只读 Redis 上的两个 {@code ZCARD}
 * 和几个内存里的数。所以它可以被前端每隔几秒轮询一次。
 *
 * <p>⚠️ 唯一的代价是 {@code ZCARD} 会打到 Redis。它有 Basic Auth 挡着；
 * 前端轮询间隔取 3 秒，比那个宽松得多。
 *
 * @param enabled        限流总开关。为 false 时下面几个数都是「没在跑」的样子
 * @param permits        名额总数（并发上限）
 * @param permitsInUse   ★ <b>当前在用的名额数</b>。它是「有没有超卖」的判据 ——
 *                       阶段 6 的压测就是每 20ms 采一次这个数并取最大值，
 *                       最大值 &gt; {@code permits} 就是超卖
 * @param queueSize      当前排队人数。⚠️ 含已过期未清理的成员
 * @param maxQueue       队列上限，超了就【同步拒绝】（不排队）
 * @param permitTtlMs    名额租期。持有者必须在这个时间内续期，否则名额被回收
 * @param queuePoolActive 排队线程池<b>正在忙</b>的线程数。
 *                       ★ 看 active 而不是 poolSize：core 线程默认不回收，
 *                       空闲时 poolSize 仍然拉满，用它会永远显示「忙」
 * @param queuePoolMax   排队线程池上限
 * @param givenUpTotal   累计「放弃排队」的次数（队列满 / 等太久）。
 *                       单调递增，重启归零
 */
public record RateLimitStatus(
        boolean enabled,
        int permits,
        long permitsInUse,
        long queueSize,
        int maxQueue,
        long permitTtlMs,
        int queuePoolActive,
        int queuePoolMax,
        long givenUpTotal
) {
}
