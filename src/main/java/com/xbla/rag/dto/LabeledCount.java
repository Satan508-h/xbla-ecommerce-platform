package com.xbla.rag.dto;

/**
 * <b>{@code GROUP BY 某个标签 → count}</b> —— 本端点里所有分组统计的统一形状。
 *
 * <h3>★ 为什么一个 record 服务好几个查询</h3>
 *
 * <p>在线指标里有四处是同一个形状：
 *
 * <pre>
 *   qa_log.status           → 成功 / 失败 / 澄清 / 被限流
 *   user_event.event_type   → ref_click / feedback
 *   payload ->> 'vote'      → up / down
 * </pre>
 *
 * <p>它们唯一的差别是<b>标签从哪一列来</b>，而那是 SQL 里的事。
 * 为每一个各写一个 {@code XxxCount} 只是把同一张形状抄三遍 ——
 * 而抄三遍的代价已经在 `QaLogMapper.selectByEvalRun` 上付过两次了
 * （漏一列不报错，只给一个看起来很合法的 0，见坑 18）。
 *
 * <p>⚠️ 代价是 <b>{@code label} 的含义随调用点变</b>。所以这里刻意
 * <b>不</b>叫 {@code status} / {@code eventType} —— 一个叫得具体、
 * 却被三处复用的字段名，比一个诚实的泛称更容易被读错。
 * 每个调用点自己的 SQL 里写着它是什么。
 *
 * @param label 分组标签。★ 可能是 {@code null} —— {@code GROUP BY} 会把
 *              NULL 分到「NULL 那一组」，而那一组**有含义**
 *              （比如 {@code payload ->> 'vote'} 读不出来的行）。
 *              <b>不合并、不丢弃</b>，原样报出来
 * @param count 这一组的行数
 */
public record LabeledCount(String label, long count) {
}
