package com.xbla.rag.mapper;

import com.xbla.rag.dto.LabeledCount;
import com.xbla.rag.dto.LatencyStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 在线指标的<b>全部聚合 SQL</b>（阶段 9.6b）—— {@code /api/status/metrics} 的数据源。
 *
 * <h3>★★★ 它是【一个】mapper，而且只有【只读】方法 —— 这是刻意的边界</h3>
 *
 * <p>{@code StatusController} 那四条纪律（不调模型 / 不写库 / 参数少 / 字段白名单）
 * 在 SQL 这一层的对应物就是：<b>「这个接口读了什么」是一个文件能读完的事。</b>
 *
 * <p>★ 所以本类<b>跨两张表</b>（{@code qa_log} 与 {@code user_event}）而没被拆开 ——
 * 边界判据是「一次指标读取」，不是「一张表一个 mapper」。拆开之后
 * 「这次统计碰了哪些数据」就要去三个文件里拼，而那正是要防的事。
 *
 * <h3>★★★ 两个时钟：窗口口径的分子分母必须同侧</h3>
 *
 * <p>本类里有两张表，而它们各有两套时间：
 *
 * <pre>
 *   qa_log      created_at   服务端写的
 *   user_event  received_at  服务端写的   ← 与上面同侧
 *               occurred_at  客户端报的
 * </pre>
 *
 * <p>★★ <b>「率」的分子和分母用同一侧：都用服务端时钟</b>（{@code received_at} /
 * {@code created_at}）。理由是如果不一致，窗口边界上会出现
 * 「分子里的那次点击，其对应的回答还没发生」这种组合 ——
 * 而那会让率<b>超过 1</b>，一个不可能的值。
 *
 * <p>⚠️ 而 {@code occurred_at} <b>不是没用</b>：它和 {@code received_at} 的差
 * 正是「客户端时钟偏了多少」的唯一证据（{@link #clockSkewP50Ms}）。
 * ★ 两列都留，是因为这两个用途要的是不同的东西（见 {@code V18} 第四节）。
 *
 * <h3>★ 窗口怎么写：{@code CAST(#{from} AS timestamptz) IS NULL}</h3>
 *
 * <p>那层 {@code CAST} 唯一的用途是给 PostgreSQL 类型线索 ——
 * 不写的话它会报 {@code could not determine data type of parameter $N}
 * （同 {@code #{docTypes} IS NULL} 那个坑）。{@code null} 表示
 * {@code window=all}，即不设下界。
 */
@Mapper
public interface MetricsMapper {

    // ============================================================
    // 组①「问答本身」—— 全部来自 qa_log
    // ============================================================

    /**
     * 窗口内的提问数。
     *
     * <p>★ 判据是 {@code eval_run_id IS NULL}（ADR-086 推广到每一个聚合）——
     * 不排的话，跑过一轮评测的库里 96% 的行是评测流量，
     * 而「提问数」会是一个<b>关于跑题器的数</b>。
     */
    @Select("""
            SELECT count(*)
            FROM qa_log
            WHERE eval_run_id IS NULL
              AND (CAST(#{from} AS timestamptz) IS NULL OR created_at >= #{from})
            """)
    long countQuestions(@Param("from") OffsetDateTime from);

    /**
     * 窗口内的<b>活跃会话数</b>（去重）。
     *
     * <p>★ 数的是 {@code qa_log.session_id} 的去重，不是 {@code chat_session} 的行数 ——
     * 因为窗口是「问答」这个概念上的，而 {@code chat_session} 没有窗口
     * （一个会话可以横跨好几天）。用会话表的行数会让「今天活跃 3 个」
     * 变成「历史上建过 145 个会话」。
     */
    @Select("""
            SELECT count(DISTINCT session_id)
            FROM qa_log
            WHERE eval_run_id IS NULL
              AND session_id IS NOT NULL
              AND (CAST(#{from} AS timestamptz) IS NULL OR created_at >= #{from})
            """)
    long countSessions(@Param("from") OffsetDateTime from);

    /**
     * 按 {@code status} 分组的行数。★ {@code label} 是 {@code status::text}。
     *
     * <pre>
     *   1 成功生成   2 模型链路失败   3 澄清反问   4 被限流
     * </pre>
     *
     * <p>★ 报的是<b>四个数</b>而不是一个成功率：3 和 4 都<b>不是失败</b>
     * （一个是主动追问，一个是我们自己挡掉的），把它们混进分母，
     * 「成功率」会随流量结构变化 —— 而那看起来像质量在波动。
     */
    @Select("""
            SELECT status::text AS label, count(*) AS count
            FROM qa_log
            WHERE eval_run_id IS NULL
              AND (CAST(#{from} AS timestamptz) IS NULL OR created_at >= #{from})
            GROUP BY status
            ORDER BY status
            """)
    List<LabeledCount> statusCounts(@Param("from") OffsetDateTime from);

    /**
     * 五段延迟的 p50 / p95 / n。
     *
     * <p>★★ <b>只统计 {@code status = 1}</b> —— 这是本项目的硬约定
     * （评测指标只统计成功行）。这里还要多一条理由：{@code status=2/3/4} 的行
     * 那些延迟列本来就是 NULL，放进来只会稀释分母而不会改变分位数，
     * 但会让 {@code total_n} 与「提问数」对不上，从而<b>看起来像丢数据</b>。
     *
     * <p>★ {@code percentile_cont} <b>跳过 NULL</b>，而 {@code count(列)} 也只数非 NULL ——
     * 所以每一段都自带自己的 {@code n}。见 {@link LatencyStats} 的类注释：
     * 不带 {@code n} 的分位数会把「只排过 4 次队」渲染成「排队很快」。
     *
     * <p>⚠️ 没有行时这一条<b>仍然返回一行</b>（p50/p95 是 NULL、n 全是 0）——
     * 聚合查询的固定行为，不是特例。
     */
    @Select("""
            SELECT
                percentile_cont(0.5)  WITHIN GROUP (ORDER BY queue_ms)             AS queueP50Ms,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY queue_ms)             AS queueP95Ms,
                count(queue_ms)                                                    AS queueN,
                percentile_cont(0.5)  WITHIN GROUP (ORDER BY retrieval_latency_ms) AS retrievalP50Ms,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY retrieval_latency_ms) AS retrievalP95Ms,
                count(retrieval_latency_ms)                                        AS retrievalN,
                percentile_cont(0.5)  WITHIN GROUP (ORDER BY rerank_latency_ms)    AS rerankP50Ms,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY rerank_latency_ms)    AS rerankP95Ms,
                count(rerank_latency_ms)                                           AS rerankN,
                percentile_cont(0.5)  WITHIN GROUP (ORDER BY llm_latency_ms)       AS llmP50Ms,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY llm_latency_ms)       AS llmP95Ms,
                count(llm_latency_ms)                                              AS llmN,
                percentile_cont(0.5)  WITHIN GROUP (ORDER BY total_latency_ms)     AS totalP50Ms,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY total_latency_ms)     AS totalP95Ms,
                count(total_latency_ms)                                            AS totalN
            FROM qa_log
            WHERE status = 1
              AND eval_run_id IS NULL
              AND (CAST(#{from} AS timestamptz) IS NULL OR created_at >= #{from})
            """)
    LatencyStats latencyStats(@Param("from") OffsetDateTime from);

    /**
     * 「有引用的回答数」—— <b>引用点击率的分母</b>。
     *
     * <p>★ 判据是「这一行真的有引用」，不是「这次检索了」：
     * 检索了但零召回也是没有引用可点。三种形状里只有一种算数：
     *
     * <pre>
     *   references IS NULL        → 没检索（工具轮 / 澄清轮）或零召回
     *   references = '[]'::jsonb  → ★ 今天【不存在】这一种（见下）
     *   非空数组                   → 算进分母
     * </pre>
     *
     * <p>⚠️ 那个 {@code <> '[]'::jsonb} 现在<b>一行都过滤不掉</b> ——
     * 实测非评测流量 206 行只有 SQL NULL(80) 与非空数组(126) 两种形状，
     * 因为 {@code buildReferences} 对空列表返回的是 {@code null}、
     * 序列化出来就是 SQL NULL，产不出 {@code []}。
     * <b>留着它是因为它是一句正确的定义，不是一道防线</b> ——
     * 定义写对了，将来谁改了序列化也不会让分母悄悄变大。
     * ★ 而且它有测试（{@code MetricsQueryIntegrationTest} 会插一行 {@code []}），
     * 所以它不是「只被写过、没被跑过」的那种代码。
     */
    @Select("""
            SELECT count(*)
            FROM qa_log
            WHERE eval_run_id IS NULL
              AND "references" IS NOT NULL
              AND "references" <> '[]'::jsonb
              AND (CAST(#{from} AS timestamptz) IS NULL OR created_at >= #{from})
            """)
    long countCitedReplies(@Param("from") OffsetDateTime from);

    // ============================================================
    // 组②「用户行为」—— 全部来自 user_event
    // ============================================================

    /**
     * 按事件类型分组的条数。★ {@code label} 是 {@code event_type}。
     *
     * <p>⚠️ 窗口用的是 {@code received_at}（服务端时钟），不是 {@code occurred_at} ——
     * 因为这里要和 {@link #countCitedReplies} 在同一个窗口里对齐算率，
     * 而那个用的是 {@code created_at}。见类注释「两个时钟」那一节。
     */
    @Select("""
            SELECT event_type AS label, count(*) AS count
            FROM user_event
            WHERE (CAST(#{from} AS timestamptz) IS NULL OR received_at >= #{from})
            GROUP BY event_type
            ORDER BY event_type
            """)
    List<LabeledCount> eventTypeCounts(@Param("from") OffsetDateTime from);

    /**
     * {@code feedback} 按票型分组。★ {@code label} 是 {@code payload ->> 'vote'}。
     *
     * <p>⚠️ {@code payload ->> 'vote'} 出来是 <b>text</b>，不是 jsonb
     * （同坑 24 那一类）。这里是 {@code GROUP BY} 的标签，不做数值比较，所以安全。
     *
     * <p>★ 坏行（{@code payload} 里没有 vote）会进 {@code label = NULL} 那一组，
     * <b>不合并、不丢弃</b> —— 它会显示成「一张读不出票型的票」，
     * 而那正是需要被看见的东西。三条票数加起来对不上总票数时，就是它在。
     */
    @Select("""
            SELECT payload ->> 'vote' AS label, count(*) AS count
            FROM user_event
            WHERE event_type = 'feedback'
              AND (CAST(#{from} AS timestamptz) IS NULL OR received_at >= #{from})
            GROUP BY payload ->> 'vote'
            ORDER BY 1
            """)
    List<LabeledCount> voteCounts(@Param("from") OffsetDateTime from);

    /**
     * 「被点开过至少一次的回答数」—— <b>引用点击率的分子</b>。
     *
     * <h3>★★★ 为什么带一个 {@code EXISTS} 而不是直接数 {@code trace_id}</h3>
     *
     * <p>直接 {@code count(DISTINCT trace_id)} 的话，分子可能包含
     * <b>不在分母里的</b>回答（比如跨越窗口边界的那一次），
     * 于是<b>率会大于 1</b> —— 一个不可能的值，而它会一路印到界面上。
     *
     * <p>★ 带上这个 {@code EXISTS} 之后，<b>分子在构造上就是分母的子集</b>，
     * 率恒 ≤ 1。这不是「顺手多写一个条件」，是把一条不变式
     * <b>做成 SQL 的形状</b>而不是留给读者去相信。
     *
     * <p>★ 那个 {@code EXISTS} 还顺带排除了「评测回答的点击」——
     * 而那道筛子在 {@code qa_log} 那一侧（{@code eval_run_id IS NULL}）。
     * 评测流量本来不产生事件（它不经过浏览器），这里只是不依赖那个前提。
     */
    @Select("""
            SELECT count(DISTINCT ue.trace_id)
            FROM user_event ue
            WHERE ue.event_type = 'ref_click'
              AND ue.trace_id IS NOT NULL
              AND (CAST(#{from} AS timestamptz) IS NULL OR ue.received_at >= #{from})
              AND EXISTS (
                    SELECT 1 FROM qa_log q
                    WHERE q.trace_id = ue.trace_id
                      AND q.eval_run_id IS NULL
                      AND q."references" IS NOT NULL
                      AND q."references" <> '[]'::jsonb
              )
            """)
    long countClickedCitedReplies(@Param("from") OffsetDateTime from);

    /**
     * 客户端时钟与服务端时钟之差的 <b>p50</b>（毫秒）。
     *
     * <p>★★★ 这一个数是为了让「两列时间」这件事<b>挣回它的成本</b>。
     *
     * <p>它同时是一份<b>对旁边那些数的有效性检查</b>：窗口口径按服务端时钟切，
     * 所以这个偏差<b>不</b>影响任何统计。但如果它大得离谱（几小时），
     * 那么 {@code occurred_at} 就是坏的 —— 而那是「他什么时候点的」
     * 这个语义唯一的来源。<b>偏差大 ⇒ 别用 occurred_at 下任何结论。</b>
     *
     * <p>★ 正值 = 服务端比客户端晚（正常的网络+处理延迟）。
     * 负很多 = 用户设备的时钟慢了。
     *
     * <p>⚠️ 只在<b>两列都有值</b>的行上算；没有事件时返回 {@code null}
     * （不是 0）—— 0 是一个「两个时钟完全一致」的断言，
     * 而没有数据时我们不知道那件事。
     */
    @Select("""
            SELECT percentile_cont(0.5) WITHIN GROUP (
                       ORDER BY EXTRACT(EPOCH FROM (received_at - occurred_at)) * 1000)
            FROM user_event
            WHERE (CAST(#{from} AS timestamptz) IS NULL OR received_at >= #{from})
            """)
    Double clockSkewP50Ms(@Param("from") OffsetDateTime from);
}
