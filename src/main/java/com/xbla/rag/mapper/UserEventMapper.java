package com.xbla.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xbla.rag.entity.UserEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code user_event} 的写入口（阶段 9.6b）。
 *
 * <h3>★★★ 只有一个自定义方法，而它存在的唯一理由是【幂等】</h3>
 *
 * <p>{@code BaseMapper.insert} 做不到「重复就跳过」——
 * 它会老实地插第二行，而第二行<b>看起来完全合法</b>。
 *
 * <pre>
 *   重复计数不报错、不告警、不违反任何约束，
 *   它只是让每一个率都偏高一点点，而那个偏差长得像「用户真的更活跃」。
 * </pre>
 *
 * <p>⇒ 只能让重复的那一条<b>在数据库层插不进去</b>：
 * {@code ON CONFLICT (event_no) DO NOTHING}，配合 {@code uk_user_event_no}。
 *
 * <h3>★ 为什么这条 SQL 里没有 {@code received_at}</h3>
 *
 * <p>它由列的 {@code DEFAULT now()} 填。★ 这不是省事 ——
 * 是「一个事实不要两个时钟」：应用进程和数据库进程的时间会漂，
 * 而「这一行什么时候落库的」只有数据库自己说得准。
 *
 * <p>⚠️ 这依赖 MyBatis-Plus 的 {@code NOT_NULL} 插入策略（null 字段不进 INSERT）。
 * 所以这里<b>不用</b> {@code BaseMapper.insert}，而是显式列出列名 ——
 * 显式列清单没有「策略改了但没人发现」这个失败模式。
 *
 * <h3>★ 读路径不在这里</h3>
 *
 * <p>聚合查询全在 {@link MetricsMapper} —— 那是<b>另一个概念</b>
 * （「在线指标」而不是「user_event 这个实体」），而且它跨两张表。
 * 一个 mapper 一件事。
 */
@Mapper
public interface UserEventMapper extends BaseMapper<UserEvent> {

    /**
     * 写一条事件；<b>同一个 {@code eventNo} 已经存在时静默跳过</b>。
     *
     * <p>★ 显式列名而不是 {@code BaseMapper.insert}，理由见类注释。
     * <b>列清单里刻意没有 {@code received_at}</b>。
     *
     * @return <b>1 = 真的写进去了；0 = 因为重复跳过了</b>。
     *         ★ 调用方拿得到这个区别，所以「重复」是可观测的，
     *         而不是一件只能靠推理相信的事
     */
    @Insert("""
            INSERT INTO user_event
                (event_no, event_type, user_id, session_no, trace_id, payload, occurred_at)
            VALUES
                (#{eventNo}, #{eventType}, #{userId}, #{sessionNo}, #{traceId},
                 #{payload}, #{occurredAt})
            ON CONFLICT (event_no) DO NOTHING
            """)
    int insertIgnoreDuplicate(UserEvent event);
}
