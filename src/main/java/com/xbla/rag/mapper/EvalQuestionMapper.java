package com.xbla.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xbla.rag.entity.EvalQuestion;
import com.xbla.rag.rag.eval.QuestionGoldDocType;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * ★评测题表 Mapper
 *
 * <p>对应数据库表 {@code eval_question}。
 *
 * <p>继承 {@code BaseMapper<EvalQuestion>} 后自带 insert / selectById / updateById /
 * deleteById / selectList / selectCount 等方法，无需手写 SQL。
 * 复杂查询（阶段 4 的向量检索、阶段 5 的 MCP 工具 SQL）再往这里加方法，
 * 简单的用注解 SQL，复杂的写 XML 放 resources/mapper/ 下。
 */
public interface EvalQuestionMapper extends BaseMapper<EvalQuestion> {

    /**
     * 每道评测题的「正解切片落在哪些 doc_type」——<b>意图树验收</b>用（阶段 5.1）。
     *
     * <p>这道查询回答的是：<b>我给这道题标的意图，有没有把它该去找的文档类型写全？</b>
     *
     * <p>验收标准只有一条：
     * <pre>
     *   该题正解所属的 doc_type 集合  ⊆  该题意图声明的 doc_types 集合
     * </pre>
     * 不满足就是树画错了。这是 5.1 唯一<b>可自动验证</b>的验收标准 ——
     * 没有它，「意图树定义好了」就只是一句主观的话。
     *
     * <p><b>为什么用 {@code unnest} 而不是在 Java 里循环查</b>：那样会变成
     * 「一道题一次查询」的 N+1。这里是<b>一道题 × 一种类型 = 一行</b>的扁平结果，
     * 由调用方聚合成 {@code Map<questionNo, Set<docType>>}。
     *
     * <p><b>为什么 {@code DISTINCT}</b>：一道题可能有多条正解切片落在同一种
     * doc_type 里（比如 B-008 的三条正解全在 doc_type=2），不去重会返回重复行。
     * 对「集合是否包含」的判断没有影响，但会让日志里的数字虚高。
     *
     * <p>⚠️ 一道题的正解<b>一条都没匹配上</b>（{@code expected_chunk_ids} 为空，
     * 或切片已被删除）时，这道题<b>不会出现在结果里</b>，而不是返回一行 null。
     * 调用方必须把「结果里没有这道题」和「这道题的类型集合是空集」当成
     * 两种不同情况处理 —— 前者说明评测集本身有问题（见 {@code EvalQuestionLoader}
     * 的锚点解析），后者（空集）是兜底类题目的正常形态。
     */
    @Select("""
            SELECT DISTINCT q.question_no AS question_no,
                            q.intent      AS intent,
                            c.doc_type    AS doc_type
            FROM eval_question q,
                 unnest(q.expected_chunk_ids) AS cid
                 JOIN kb_chunk c ON c.id = cid AND c.deleted = 0
            ORDER BY q.question_no, c.doc_type
            """)
    List<QuestionGoldDocType> findGoldDocTypes();
}
