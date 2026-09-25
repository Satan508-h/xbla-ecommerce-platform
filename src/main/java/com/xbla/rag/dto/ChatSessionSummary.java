package com.xbla.rag.dto;

import java.time.OffsetDateTime;

/**
 * 会话列表里的一行 —— {@code GET /api/chat/sessions} 的元素。
 *
 * <h3>★★ 为什么它和 {@code ChatSession} 实体是两个类</h3>
 *
 * <p>实体是这个读模型的<b>输入</b>，不是输出。实体上有
 * {@code id} / {@code userId} / {@code status} / {@code deleted} / {@code updatedAt}，
 * 这些字段直接透出去有三个问题：
 *
 * <ul>
 *   <li>{@code deleted} —— 逻辑删除标记。它永远是 0（查询里已经过滤过），
 *       但把它放进响应等于<b>承诺了一个我们从不打算让它非 0 的字段</b></li>
 *   <li>{@code userId} —— 在 chat 路径上<b>恒为 NULL</b>（见 {@code ChatServiceImpl.resolveSession}）。
 *       透出去会让人以为「这里能拿到用户」，而它永远拿不到</li>
 *   <li>{@code id} —— 数据库主键。对外用的是 {@code sessionNo}
 *       （一段不可猜测的随机串，见 {@code TraceId}），
 *       <b>把自增主键暴露出去等于把「一共有多少会话」也送出去了</b></li>
 * </ul>
 *
 * <p>★ 换句话说：<b>实体的字段集合是「表里有什么」，这个类的字段集合是「前端能用什么」，
 * 两者不是一回事</b>，让它们共用一个类型就是在把两件事绑在一起。
 *
 * @param sessionNo    会话编号 —— <b>对外的唯一标识</b>，前端拿它切会话
 * @param title        会话标题，由首轮问题生成（见 {@code ChatServiceImpl.truncateTitle}）
 * @param messageCount 消息条数。★ 它是 {@code touchSession} <b>每轮 +2</b> 维护的
 *                     （一条用户 + 一条助手），不是实时 count —— 见那个方法的说明
 * @param lastActiveAt 最后活跃时间。列表按它倒序 —— 「最近聊过的在最上面」
 * @param createdAt    创建时间
 */
public record ChatSessionSummary(
        String sessionNo,
        String title,
        Integer messageCount,
        OffsetDateTime lastActiveAt,
        OffsetDateTime createdAt
) {
}
