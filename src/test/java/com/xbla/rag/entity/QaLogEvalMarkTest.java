package com.xbla.rag.entity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xbla.rag.mapper.QaLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * V10 —— {@code qa_log} 的评测标记两列（阶段 7）。
 *
 * <h2>★ 为什么这个测试值得单独存在</h2>
 *
 * <p>它验证的东西<b>全都在数据库里</b>，在 Java 代码里看不出来：
 * 两列到底存不存得住、NULL 和空串是不是真的不同、
 * 以及最要紧的 —— <b>「只统计真实使用」那个筛子能不能真的把它们分开</b>。
 *
 * <p>最后这条是阶段 7 一切统计的地基：分不开的话，
 * 每跑一轮评测就往「真实用户数据」里掺几百行，
 * 而那个错误<b>没有症状</b> —— 它只会让报表上的数字慢慢变成评测数据的数字。
 *
 * <h2>★ 为什么查询要带一个唯一前缀</h2>
 *
 * <p>{@code qa_log} 里本来就有几百行真实数据（跑测试、演示、手工调试留下的），
 * 而 {@code @Transactional} <b>只回滚这个测试插入的行</b>。
 * 直接查 {@code WHERE eval_run_id IS NULL} 会捞到它们全部 ——
 * 那时断言会报「expected: 1 but was: 137」，看起来像实现多写了行，
 * 实际是<b>查询条件太宽</b>。（同 {@code ChatTraceIdIntegrationTest} 的教训。）
 */
@SpringBootTest
@Transactional
@DisplayName("QaLog · 评测标记两列（V10）")
class QaLogEvalMarkTest {

    /**
     * ★ 这个类自己的一批行，用问题文本前缀圈起来。
     * 用纳秒而不是固定串：同一个 JVM 里重跑时不会互相撞上。
     */
    private static final String PREFIX = "EVALMARK-" + System.nanoTime() + "-";

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private QaLogMapper qaLogMapper;

    private static String nextQuestion() {
        return PREFIX + SEQ.incrementAndGet();
    }

    /** 最小可用的一行 —— 只填 NOT NULL 的列 */
    private QaLog row(String question) {
        QaLog row = new QaLog();
        row.setTraceId("T-EVAL-" + System.nanoTime());
        row.setQuestion(question);
        return row;
    }

    /** 本测试自己造的、带评测标记的行 */
    private List<QaLog> selectEvalRows(String runId) {
        return qaLogMapper.selectList(new LambdaQueryWrapper<QaLog>()
                .likeRight(QaLog::getQuestion, PREFIX)
                .eq(QaLog::getEvalRunId, runId));
    }

    /** 本测试自己造的、被「真实使用」筛子捞到的行 */
    private List<QaLog> selectRealRows() {
        return qaLogMapper.selectList(new LambdaQueryWrapper<QaLog>()
                .likeRight(QaLog::getQuestion, PREFIX)
                .isNull(QaLog::getEvalRunId));
    }

    // ============================================================
    // 一、两列的往返
    // ============================================================

    @Nested
    @DisplayName("一、两列的往返")
    class RoundTrip {

        @Test
        @DisplayName("★ 评测行：两个值原样写回")
        void evalValuesRoundTrip() {
            String question = nextQuestion();
            QaLog row = row(question);
            row.setEvalRunId("run-20260920-a");
            row.setEvalQuestionNo("X-042");
            qaLogMapper.insert(row);

            List<QaLog> back = selectEvalRows("run-20260920-a");
            assertEquals(1, back.size(), "应该正好一行");
            assertEquals("X-042", back.get(0).getEvalQuestionNo());
        }

        @Test
        @DisplayName("★ 真实用户的行：两列都是 NULL（不是空串）")
        void realUserRowsHaveNoMark() {
            String question = nextQuestion();
            qaLogMapper.insert(row(question));

            List<QaLog> back = selectRealRows();
            assertEquals(1, back.size(), "★ 这一行的 eval_run_id 必须是 NULL —— "
                    + "写成空串的话，它不会出现在这个查询里，"
                    + "而「真实使用统计」会<b>静默漏掉它</b>");
            assertNull(back.get(0).getEvalQuestionNo());
        }
    }

    // ============================================================
    // 二、★★ 隔离：两个筛子必须互斥
    // ============================================================

    @Nested
    @DisplayName("二、★★ 评测与真实必须分得开")
    class Isolation {

        /**
         * ★★ 这是整个类的核心断言，也是一条<b>正-反对照</b>。
         *
         * <p>两行数据并排写进去，差别只有一个字段：一行有 {@code eval_run_id}，
         * 一行没有。然后<b>两个筛子各查一次</b>：
         *
         * <pre>
         *   WHERE eval_run_id = 'run-x'    →  只能捞到评测那一行
         *   WHERE eval_run_id IS NULL      →  只能捞到真实那一行
         * </pre>
         *
         * <p>只证明其中一个是不够的：如果两行都能被两个筛子捞到
         * （比如 {@code eval_run_id} 被写成了空串而不是 NULL），
         * 那么「筛子能分开」这句话就是假的，而<b>单独看任何一个查询都看不出来</b>。
         */
        @Test
        @DisplayName("★★ 正-反对照：评测行进不了「真实使用」的筛子，反之亦然")
        void theTwoFiltersAreMutuallyExclusive() {
            String evalQuestion = nextQuestion();
            String realQuestion = nextQuestion();

            QaLog evalRow = row(evalQuestion);
            evalRow.setEvalRunId("run-isolation");
            evalRow.setEvalQuestionNo("X-007");
            qaLogMapper.insert(evalRow);

            qaLogMapper.insert(row(realQuestion));

            // 正：按 runId 查，只捞到评测那一行
            List<QaLog> evalHits = selectEvalRows("run-isolation");
            assertEquals(1, evalHits.size(),
                    "按 runId 查询应该【正好】捞到评测那一行。" +
                            "多了 → 有别的行被误标成了这一轮；"
                            + "少了 → 标记根本没写进去");
            assertEquals(evalQuestion, evalHits.get(0).getQuestion());

            // 反：按「真实使用」查，只捞到真实那一行
            List<QaLog> realHits = selectRealRows();
            assertEquals(1, realHits.size(),
                    "★★ 「真实使用」筛子捞到了 " + realHits.size() + " 行，应该只有 1 行。\n"
                            + "   多出来的那些就是【伪装成真实用户的评测流量】—— \n"
                            + "   而它们会让「真实用户平均等多久 / 真实召回率多少」"
                            + "被评测数据主导，且没有任何症状");
            assertEquals(realQuestion, realHits.get(0).getQuestion());
        }

        /**
         * ★ 半标记（只有 run 没有题号）必须能被单独查出来。
         *
         * <p>报告端点的义务：把这种行**数出来报**。它们归不了题（算不出 gold），
         * 又带着 {@code eval_run_id} 因而不在真实使用里 —— <b>两头都不属于</b>。
         * 不显式可见的话，它们只会表现为「提交了 150 题，报告里只有 148 行」，
         * 而那个差值的来源没有任何地方能查。
         */
        @Test
        @DisplayName("★ 半标记能被单独查出来 —— 报告端点靠这个把它们数出来报")
        void halfMarkedRowsAreQueryable() {
            String question = nextQuestion();
            QaLog half = row(question);
            half.setEvalRunId("run-half");
            qaLogMapper.insert(half);

            List<QaLog> halfMarked = qaLogMapper.selectList(new LambdaQueryWrapper<QaLog>()
                    .likeRight(QaLog::getQuestion, PREFIX)
                    .isNotNull(QaLog::getEvalRunId)
                    .isNull(QaLog::getEvalQuestionNo));

            assertEquals(1, halfMarked.size(),
                    "「有 run 没题号」的行应该是可查的 —— 阶段 7 的报告里"
                            + "要把它当成一个【必须解释】的数，而不是悄悄丢掉");
            assertNotNull(halfMarked.get(0).getEvalRunId());
        }
    }
}
