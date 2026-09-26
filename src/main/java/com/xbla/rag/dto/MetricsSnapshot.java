package com.xbla.rag.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 在线指标快照 —— {@code GET /api/status/metrics} 的响应体（阶段 9.6b）。
 *
 * <h3>★★★ 两组数【分开报】，因为它们的历史长度不一样</h3>
 *
 * <pre>
 *   traffic / latency   qa_log + chat_session  —— 服务端一直在记，【有历史】
 *   behavior            user_event             —— 前端埋点，【从零开始】
 * </pre>
 *
 * <p>混在一个对象里会让人以为两组数一样「有底子」。分开之后，
 * {@code behavior} 那一块<strong>头几天只有一个很小的 n</strong> 是看得见的。
 *
 * <h3>★★ 「零」必须可见，不能因为「这一格没被走到」就消失</h3>
 *
 * <p>{@link Traffic#byStatus()} 与 {@link Behavior#byEventType()} 都是
 * <b>固定键集</b>：四个 status、两个事件类型，<b>值是 0 也照样出现</b>。
 *
 * <p>★ 这条是从阶段 9.6a 学来的（{@code d2386cd}：gate 六分支全填 0 再计数）：
 * 「这一支没被走到」和「这一支不存在」是两件事，而缺键会把前者
 * 渲染成后者 —— 一个<b>看起来像好消息的空</b>。
 *
 * <h3>⚠️ 率在分母为 0 时是 {@code null}，不是 0</h3>
 *
 * <p>{@link Behavior#referenceClickRate()} 在「窗口内没有任何带引用的回答」时
 * 返回 {@code null}。写 0 是在断言「一个都没被点」，而那一刻我们
 * <b>根本不知道</b> —— 没有东西可点。同 {@code TraceDetail} 那条
 * 「读不到 ≠ 没有做过」。
 *
 * @param window       <b>实际生效</b>的窗口（{@code 24h} / {@code 7d} / {@code all}）。
 *                     ★ 传了不认识的值时这里是被替换后的值，并且 {@link #notes()}
 *                     里会有一句说明 —— 不静默替换
 * @param requestedWindow 调用方<b>要</b>的窗口。默认与 {@code window} 相同；
 *                     被替换过时两者不同，一眼能看出
 * @param from         窗口下界；{@code window=all} 时是 {@code null}
 * @param generatedAt  这次快照的生成时刻（服务端时钟）
 * @param traffic      组①：流量与健康度
 * @param latency      组①：五段延迟。⚠️ 见 {@link LatencyStats} 的「五段不相加等于 total」
 * @param behavior     组②：用户行为（★ 从零开始，没有历史）
 * @param notes        跟着数字一起发的注意事项。★ 本项目的一贯做法：
 *                     口径和数字<b>必须同行</b>，否则数字会先被引用、口径才被想起来
 */
public record MetricsSnapshot(
        String window,
        String requestedWindow,
        OffsetDateTime from,
        OffsetDateTime generatedAt,
        Traffic traffic,
        LatencyStats latency,
        Behavior behavior,
        List<String> notes
) {

    /**
     * 组①：流量与健康度。
     *
     * <p>★ {@code questions} 与 {@link LatencyStats#totalN()} <b>不相等是正常的</b>：
     * 后者只数 {@code status = 1} 的行。两个数放在一起，
     * 「这次窗口里有多少问答是失败的」一眼可见。
     *
     * @param questions  窗口内的提问数（<b>所有 status</b>，不含评测）
     * @param sessions   窗口内有提问的<b>去重会话数</b>
     * @param byStatus   ★ <b>固定四个键</b>（{@code 1/2/3/4}），值为 0 也出现。
     *                   <pre>1 成功   2 模型链路失败   3 澄清反问   4 被限流</pre>
     *                   ★ 3 和 4 <b>不是失败</b>，所以这里刻意不合成一个「成功率」——
     *                   合成会让它随流量结构变化，而那看起来像质量在波动
     */
    public record Traffic(
            long questions,
            long sessions,
            Map<String, Long> byStatus
    ) {
    }

    /**
     * 组②：用户行为。★★ <b>这一块的数据从埋点上线那天才开始有</b>。
     *
     * <h3>★ 它量的不是「转化」</h3>
     *
     * <p>{@link #referenceClickRate} 的准确定义是
     * <b>「有引用的回答里，被点开过至少一次的占比」</b> ——
     * 它是<b>兴趣信号</b>，不是商业转化。
     *
     * <p>⚠️ <b>本项目里没有「转化」可测</b>：回答正文是纯文本，
     * 里面的商品号<b>点不动</b>，也没有下单链路。硬报一个
     * {@code product_click} 就是编数据。所以这个词一次都不出现。
     *
     * @param byEventType           ★ <b>固定键集</b>（{@code ref_click} / {@code feedback}），
     *                              值为 0 也出现。★ 它的第二个用途是：
     *                              将来加了新事件类型，它<b>先在这里出现</b>，
     *                              而不用等谁想起来给 {@code Behavior} 加一个具名字段
     * @param refClicks             引用被点开的总次数（含重复点同一条）
     * @param feedbackUp            👍 数
     * @param feedbackDown          👎 数
     * @param feedbackOther         ★ <b>票型读不出来的票</b>（{@code payload} 里没有 vote）。
     *                              正常情况下恒为 0；非 0 就是有坏数据，
     *                              而它<b>不该被并进上两个数里</b> —— 那会
     *                              把「读不出来」混进「用户不满意」
     * @param citedReplies          分母：窗口内有引用的回答数
     * @param clickedCitedReplies   分子：其中被点开过的（★ 构造上是分母的子集，率恒 ≤ 1）
     * @param referenceClickRate    ★ 分子 / 分母；<b>分母为 0 时是 {@code null}，不是 0</b>
     * @param clockSkewP50Ms        ★ 客户端与服务端时钟差的中位数。
     *                              <b>它是一份对上面那些数的有效性检查</b>：
     *                              偏差大得离谱 ⇒ {@code occurred_at} 是坏的 ⇒
     *                              别拿「用户什么时候点的」下任何结论。
     *                              （窗口口径本身按服务端时钟切，所以不受它影响）
     */
    public record Behavior(
            Map<String, Long> byEventType,
            long refClicks,
            long feedbackUp,
            long feedbackDown,
            long feedbackOther,
            long citedReplies,
            long clickedCitedReplies,
            Double referenceClickRate,
            Double clockSkewP50Ms
    ) {
    }
}
