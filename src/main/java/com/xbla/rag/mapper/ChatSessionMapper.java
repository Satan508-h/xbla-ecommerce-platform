package com.xbla.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xbla.rag.entity.ChatSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 对话会话表 Mapper
 *
 * <p>对应数据库表 {@code chat_session}。
 *
 * <p>继承 {@code BaseMapper<ChatSession>} 后自带 insert / selectById / updateById /
 * deleteById / selectList / selectCount 等方法，无需手写 SQL。
 * 复杂查询（阶段 4 的向量检索、阶段 5 的 MCP 工具 SQL）再往这里加方法，
 * 简单的用注解 SQL，复杂的写 XML 放 resources/mapper/ 下。
 */
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /**
     * 会话列表 —— <b>只取「真实用户的对话」，把评测流量排除掉</b>。
     *
     * <h3>★★★ 为什么必须有这个过滤：不分的话列表会被评测流量淹没</h3>
     *
     * <p>2026-09-24 实测（阶段 8 动工前）：
     *
     * <pre>
     *   chat_session 总数                     4111
     *   其中 qa_log 带 eval_run_id 的会话      4022   ← 97.8%
     *   真实的                                  89
     * </pre>
     *
     * <p>一句朴素的 {@code ORDER BY last_active_at DESC LIMIT 20} 会得到
     * <b>一屏「商品支持七天无理由退货吗」</b> —— 阶段 7 的 159 题 × 3 次重复，
     * 每题都建了一个会话。
     *
     * <h3>★ 这是 ADR-081 那条纪律换了个地方重新出现</h3>
     *
     * <p>ADR-081 说的是「{@code eval_run_id} 会在某些写入路径上被静默丢掉，
     * 于是评测流量伪装成真实用户」。那一次修的是<b>写</b>。
     *
     * <p>这一次是<b>读</b>：标记好好地在库里，但没有任何人想到要按它过滤。
     * 两次是同一个概念的两端 —— <b>一个标记只有在「写的时候写全」+「读的时候记得用」
     * 两个条件同时成立时才有意义</b>。只做前半句，它就是一个躺在库里没人看的列。
     *
     * <h3>为什么判据是「没有【评测】行」而不是「有【真实】行」</h3>
     *
     * <p>因为还有一种会话是<b>一行 qa_log 都没有</b>的：会话建好了、
     * 用户消息也落库了，但紧接着推送失败（{@code sink.onStart} 抛，
     * 见 {@code docs/10} 阶段 7 的那次逐出口审计）。
     * 那种会话<b>应该出现在列表里</b> —— 用户确实打了字。
     * 反过来写（{@code EXISTS(非评测行)}）会把它一起挡掉。
     *
     * <p>★ 实测这条判据是够的：89 个非评测会话里 <b>0 个是空会话</b>，
     * 所以不需要再叠一个「至少有一条消息」的条件。
     *
     * <h3>★ 手写 SQL 必须自己写 {@code deleted = 0}</h3>
     *
     * <p>{@code application.yml} 配了全局逻辑删除
     * （{@code logic-delete-field: deleted}），但那是 MyBatis-Plus
     * <b>改写它自己生成的 SQL</b> 时才生效的 —— 注解 SQL 原样发给数据库，
     * 不带这个条件就会把逻辑删除的会话也查出来。
     * 同一个坑 {@code KbChunkMapper.selectIdsByDocumentId} 也记了一次。
     *
     * <h3>★ 为什么是 {@code s.*} 而不是显式列清单</h3>
     *
     * <p>因为显式列清单会漂移：{@code QaLogMapper.selectByEvalRun} 已经坏了<b>两次</b>
     * （7.5 漏 {@code question}、7.6 漏 {@code prompt_tokens}），
     * 而症状是<b>一个看起来很合法的 0</b>（见 {@code docs/10} 坑 18）。
     *
     * <p>这里的投影目标是<b>实体</b>而不是响应 DTO，所以「表里有什么」和
     * 「要取什么」本来就该一致 —— {@code s.*} 让它们不可能不一致。
     * 对外字段的裁剪由 {@code ChatSessionSummary} 负责（那才是该显式列清单的地方）。
     *
     * <h3>★ 为什么排序有两个键</h3>
     *
     * <p>{@code ORDER BY s.last_active_at DESC} <b>不够</b>，必须再跟一个
     * {@code s.id DESC}。因为 PostgreSQL 的 {@code now()} 是
     * <b>事务开始时间</b>（{@code transaction_timestamp()}），不是语句时间 ——
     * 而 {@code chat_session.last_active_at} 的默认值正是 {@code now()}。
     *
     * <pre>
     *   同一个事务里插入两行
     *       → 两行的 last_active_at 【完全相同】
     *       → 只按它排序时，这两行的先后【未定义】
     * </pre>
     *
     * <p>本项目的串行问答路径下每个请求各自一个事务，所以线上碰不到；
     * 但评测（一个事务里连建多个会话）和集成测试里会碰到。
     * 真碰到时的症状是<b>「翻页时同一行出现在两页，或者干脆不出现」</b> ——
     * 而它不报错、也不稳定复现。
     *
     * <p>★ 加一个单调键之后排序成为<b>全序</b>，上面那类问题在原理上就不可能发生。
     * 代价是一次几乎免费的比较。
     *
     * @param limit 最多返回多少条。<b>由调用方夹紧</b>（见 {@code ChatHistoryQueryServiceImpl}）——
     *              直接把它透到 {@code LIMIT} 上会让一个手写的 {@code ?limit=99999999}
     *              变成一次全表扫描
     */
    @Select("""
            SELECT s.*
            FROM chat_session s
            WHERE s.deleted = 0
              AND NOT EXISTS (
                  SELECT 1
                  FROM qa_log q
                  WHERE q.session_id = s.id
                    AND q.eval_run_id IS NOT NULL
              )
            ORDER BY s.last_active_at DESC, s.id DESC
            LIMIT #{limit}
            """)
    List<ChatSession> selectRealSessions(@Param("limit") int limit);
}
