package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 浏览器行为事件（阶段 9.6b）。对应 {@code user_event} 表。
 *
 * <h3>★★★ 它和 {@link QaLog} 是【两种可信度不同的数据来源】</h3>
 *
 * <pre>
 *   qa_log      服务端观察到的「我做了什么」    —— 意图 / 检索 / 延迟 / 成本
 *   （本类）    浏览器声明的「用户做了什么」    —— 点开了哪条引用 / 投了什么票
 * </pre>
 *
 * <p>后者服务端<b>根本观察不到</b>：前端不发回来，它就永远不存在。
 * ⇒ 这张表的每一列都要按「它可能是编的」来对待，尤其是 {@code userId}。
 *
 * <h3>★ 只增不改不删</h3>
 *
 * <p>同 {@code qa_log}：没有 {@code updatedAt}，没有 {@code deleted}。
 * 一次点击发生过就是发生过 —— 软删在这里没有语义
 * （不像商品下架、切片下线那种「现在不生效了」）。
 *
 * <h3>★★ {@code receivedAt} 就是这张表的 {@code createdAt}</h3>
 *
 * <p>⚠️ 本类<b>没有</b> {@code createdAt} 字段，这不是漏了。
 * 那一列在这里叫 {@code receivedAt}，因为它和 {@code occurredAt}
 * 构成一对<b>对比语义</b>：
 *
 * <pre>
 *   occurredAt  客户端报的「他什么时候点的」  ← 时间窗口口径按它切
 *   receivedAt  服务端写的「我们什么时候收到的」
 * </pre>
 *
 * <p>两者的差 = 客户端时钟偏差 + 网络延迟，而这两件事
 * <b>在数据上分不开</b> —— 所以两列都要留（见 {@code V18} 第四节）。
 *
 * <p>★★ 它<b>不在这里赋值</b>，由数据库的 {@code DEFAULT now()} 填。
 * 理由是「一个事实不要两个时钟」：应用进程和数据库进程的时间会漂，
 * 而「这一行什么时候落库的」只有数据库自己说得准。
 * ⚠️ 这依赖 MyBatis-Plus 的 {@code NOT_NULL} 插入策略（null 字段不进 INSERT）——
 * {@code UserEventInsertTest} 会断言它，所以这条依赖不会静默失效。
 */
@Data
@TableName("user_event")
public class UserEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 幂等键，前端用 {@code crypto.randomUUID()} 生成。
     *
     * <p>★★ 唯一约束是<b>正确性</b>保证，不是调优 —— 重复计数不报错、不告警，
     * 只让每一个率都偏高一点点，而那个偏差长得像「用户真的更活跃」。
     */
    private String eventNo;

    /**
     * 事件类型。服务端<b>白名单强制</b>（不认识的在入口就被丢弃）。
     *
     * <p>当前两个：{@code ref_click} / {@code feedback}，
     * 见 {@link com.xbla.rag.dto.UserEventRequest#SUPPORTED_TYPES}。
     */
    private String eventType;

    /**
     * ★★ <b>无外键</b>，原样记 —— 与 {@code qa_log.user_id} 同侧（ADR-091）。
     *
     * <p>本表的值<b>直接来自浏览器</b>，比 {@code qa_log} 更不可信：
     * 前端那个「身份 ID」输入框可以填任何东西。
     * 加 FK 会让一次伪造打挂<b>整批</b>埋点，而埋点绝不能影响主链路。
     *
     * <p>⚠️ 代价：它可能不存在于 {@code app_user}，
     * 任何 {@code JOIN app_user} 都会<b>静默丢行</b>。
     */
    private Long userId;

    /** 会话号。可空（埋点可能在会话号拿到之前就发出去了） */
    private String sessionNo;

    /**
     * ★ 唯一把行为挂回 {@code qa_log} 的骨。
     *
     * <p>「引用点击率」的分子分母靠它对上。
     * ⚠️ <b>历史消息里的引用没有 traceId</b>（{@code chat_message} 没存那一列），
     * 所以那一格恒为 NULL —— 那是形状表的一格，不是缺数据。
     */
    private String traceId;

    /**
     * 事件各自的差异部分。
     *
     * <pre>
     *   ref_click  →  {"chunkId": 348, "no": 2}
     *   feedback   →  {"vote": "up"}
     * </pre>
     *
     * <p>⚠️ 读的时候 {@code payload ->> 'vote'} 出来是 <b>text 不是 jsonb</b>
     * （同 {@code docs/10} 坑 24 那一类）。
     */
    private String payload;

    /** ★ 客户端报的时间。<b>时间窗口口径按它切。</b> */
    private OffsetDateTime occurredAt;

    /** ★ 服务端写的时间（= 本表的 {@code createdAt}）。由数据库默认值填，见类注释 */
    private OffsetDateTime receivedAt;
}
