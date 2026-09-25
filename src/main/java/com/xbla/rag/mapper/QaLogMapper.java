package com.xbla.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xbla.rag.entity.QaLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * ★问答全链路日志 Mapper。阶段 7 评测指标从这里统计
 *
 * <p>对应数据库表 {@code qa_log}。
 *
 * <p>继承 {@code BaseMapper<QaLog>} 后自带 insert / selectById / updateById /
 * deleteById / selectList / selectCount 等方法，无需手写 SQL。
 * 复杂查询（阶段 4 的向量检索、阶段 5 的 MCP 工具 SQL）再往这里加方法，
 * 简单的用注解 SQL，复杂的写 XML 放 resources/mapper/ 下。
 */
public interface QaLogMapper extends BaseMapper<QaLog> {

    /**
     * 某一轮评测的<b>全部</b> {@code qa_log} 行，按题号、再按写入顺序排。
     *
     * <h3>★★ 为什么必须显式列出列，不用 {@code SELECT *}</h3>
     *
     * <p>两个理由，第二个是硬的：
     *
     * <ol>
     *   <li>这条查询会一次把整轮的行读进内存（stage7 是 475 行，
     *       其中 {@code retrieval_detail} 是个几十 KB 的 JSONB）——
     *       列清单让「读了多少东西」在代码里一眼可见，而不是藏在一个星号里</li>
     *   <li>★ <b>{@code references} 是 SQL 保留字</b>，必须写成
     *       {@code "references"}（实体那边靠 {@code @TableField} 加引号）。
     *       ⚠️ 漏了引号不会报「你写错了」，会报一个语法错误说
     *       {@code near "references"} —— 而这行加引号是<b>被测试盖住的</b></li>
     * </ol>
     *
     * <h3>★★ 阶段 7.5 起多读三列（{@code question} / {@code final_answer} / {@code references}）</h3>
     *
     * <p>它们只服务 RAGAS 那条路（{@code EvalAnswerService}）：
     * 判词要拿<b>模型当时看到的全部输入</b>去判，而 {@code retrieval_detail}
     * 里<b>没有正文</b>（只有 id），答案也只在 {@code final_answer} 里。
     *
     * <p>★ <b>为什么不另开一条查询</b>：两条并行的取数会<b>静默漂移</b> ——
     * 将来有一列加给了报告却忘了加给 RAGAS，症状是「RAGAS 的某个数偏低」
     * 而不是任何报错。多出来的体积（约 0.5 MB/轮）对调试端点无所谓。
     *
     * <p>⚠️⚠️ <b>这个坑当场就踩了一次</b>：加 {@code final_answer} 和
     * {@code references} 的那一次<b>漏了 {@code question}</b>，于是 RAGAS
     * 每一条都报 {@code user_input cannot be empty} —— 15 个指标全部为
     * {@code null}。症状离病因很远（看起来像「RAGAS 配置不对」）。
     * <b>「取数少一列」不会编译失败，也不会在报告里留下任何痕迹</b> ——
     * 所以这条查询的列清单值得和 {@code EvalAnswerService} 的用到的字段
     * 一起复核，而不是靠记忆。
     *
     * <p>★ 为什么 {@code user_input} 用 {@code question} 而不是
     * {@code rewritten_question}：重写是<b>检索内部</b>的优化
     * （{@code RetrievalPipeline} 用它去召回），它<b>从不进生成用的消息</b> ——
     * 全项目只有一处读它，就是落库那一句。所以模型看到的那句话
     * 自始至终是 {@code question}。
     *
     * <h3>★ 排序里的 {@code id}</h3>
     *
     * <p>{@code ORDER BY eval_question_no, id} —— 第二个键不是装饰。
     * 报告要按「第几次重复」取数（第一次 {@code status=1} 的那次，见
     * {@code EvalReportService}），而「第几次」唯一的载体就是<b>写入顺序</b>。
     * 只按题号排的话，同一题的三行相对顺序由数据库自由决定 ——
     * 现象是「同一份数据两次跑出不同的检索指标」，且<b>不会报错</b>。
     *
     * <p>⚠️ 它依赖 {@code eval_question_no} 可空这一事实：真实用户的行
     * 这里是 NULL，而 {@code WHERE eval_run_id = ?} 已经把范围限定在评测行上了。
     *
     * <h3>★★★ 阶段 7.6 又踩了一次 —— 同一个坑，同一个方法</h3>
     *
     * <p>T8 为了让<b>缓存命中率可反推</b>，给 {@code costSection} 加了
     * {@code prompt_tokens} / {@code completion_tokens} 的累加。累加代码是对的，
     * <b>列清单没跟着加</b> —— 于是每一行的这两个字段都是 {@code null}，
     * 被 null 判断挡掉，{@code prompt_token合计} 和 {@code 输出token合计}
     * 恒为 <b>0</b>。
     *
     * <p>★★★ <b>它比上一次更难发现</b>：
     * <ol>
     *   <li><b>不报错</b> —— 查询成功，返回 460 行；</li>
     *   <li><b>返回的不是 null 而是 0</b> —— 「少读一列」被 null 判断
     *       渲染成「这一轮真的没有 token」，而后者是一个合法值；</li>
     *   <li><b>键在、格式对、旁边那个数还对</b> ——
     *       {@code token合计} 是 386591（正常），紧挨着的两行是 0，
     *       而报告把三行并排印出来，看着像「拆分粒度不同」而不是「读漏了」。</li>
     * </ol>
     *
     * <p>★★ 判据（免费且精确，全库 17 轮实测 <b>0 例外</b>）：
     * {@code prompt_tokens + completion_tokens == total_tokens}。
     * 只要有 {@code total_tokens}，这两列就都在，且相加恰好相等。
     * 所以 {@code 0 + 0 ≠ 386591} <b>本身就证明是读路径坏了</b>，
     * 不可能是数据如此。
     *
     * <p>★★ <b>为什么这两次都是同一个方法</b>：显式列清单和
     * {@code EvalReportService} / {@code EvalAnswerService} 读的字段之间
     * <b>没有任何东西强制同步</b>。改这个查询时，<b>请把两边的 getter 清单
     * 拉出来逐个对照</b>，不要靠记忆 —— 两次都是「加新读取忘了加列」，
     * 而两次的症状都不指向这条查询。
     *
     * <h3>★★ 阶段 9.2 加了 {@code intent_plan}（第三次的预防，不是第三次）</h3>
     *
     * <p>9.2 要给报告加「<b>检索决策准确率</b>」：这道题该不该检索、
     * 实际检索了没有。前者只有 {@code intent_plan.retrieve} 说得清 ——
     * 光看 {@code retrieval_detail IS NULL} 分不清
     * 「模型主动关掉」和「这个意图本来就不检索」。
     *
     * <p>★ 所以这一列<b>必须在</b>这份清单里。漏了它的症状和上两次同型：
     * 报告里那一格显示 <b>0 次「不该检索却检索了」</b> ——
     * 而 0 是个看起来像好消息的数，没有任何东西会红。
     *
     * <p>⚠️ 另外注意：{@code tool_calls} <b>不在这份清单里</b>，这是刻意的 ——
     * 报告目前不用它。哪天真要按「有没有调工具」切片，记得连它一起加。
     *
     * @param runId 评测运行 ID（{@code qa_log.eval_run_id}）
     */
    @Select("""
            SELECT id, trace_id, eval_question_no, eval_run_id,
                   question,
                   intent, intent_confidence, status, error_msg,
                   queue_ms, queue_position,
                   retrieval_latency_ms, rerank_latency_ms, llm_latency_ms, total_latency_ms,
                   retrieval_detail, intent_plan, final_answer, "references",
                   provider, model, prompt_tokens, completion_tokens, total_tokens, cost,
                   created_at
            FROM qa_log
            WHERE eval_run_id = #{runId}
            ORDER BY eval_question_no, id
            """)
    List<QaLog> selectByEvalRun(@Param("runId") String runId);
}
