package com.xbla.rag.entity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xbla.rag.mapper.QaLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V9 —— {@code qa_log} 的排队两列 + 第四个 status 取值。
 *
 * <h2>★ 为什么这个测试值得单独存在</h2>
 *
 * <p>它验证的东西<b>全都在数据库里</b>，在 Java 代码里看不出来：
 * 列存不存在、CHECK 约束认不认 4、NULL 和 0 是不是真的不同。
 * 这三件事任何一件错了，症状都是<b>「跑到阶段 7 才发现数据不对」</b> ——
 * 而那时已经积累了几百行没法追溯的记录了。
 *
 * <h2>★★ 这里的每条断言都配了反对照</h2>
 *
 * <p>「status=4 能写进去」这句话，如果 CHECK 约束<b>根本不存在</b>，
 * 也照样成立 —— 那样的话它测的是空气。
 * 所以必须同时证明「status=5 <b>写不进去</b>」：
 * 两条一起，才能推出「约束存在，且它的边界恰好画在 4」。
 */
@SpringBootTest
@Transactional
@DisplayName("QaLog · 排队两列与第四个 status（V9）")
class QaLogQueueColumnsTest {

    /**
     * 每条测试用一个独立的 traceId，便于按 traceId 精确捞回来。
     * ★ 用纳秒而不是固定字符串：这个类用了 {@code @Transactional}（数据库会回滚），
     * 但回滚发生在方法结束时，方法内部仍需要唯一性来做精确查询。
     */
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired
    private QaLogMapper qaLogMapper;

    private static String nextTraceId() {
        return "T-QUEUE-" + SEQ.incrementAndGet();
    }

    /** 最小可用的一行 —— 只填 NOT NULL 的列 */
    private QaLog minimalRow(String traceId) {
        QaLog row = new QaLog();
        row.setTraceId(traceId);
        row.setQuestion("退货要几天");
        return row;
    }

    private QaLog reload(String traceId) {
        QaLog found = qaLogMapper.selectOne(
                new LambdaQueryWrapper<QaLog>().eq(QaLog::getTraceId, traceId));
        assertNotNull(found, "按 traceId 应该能捞回刚写的那一行");
        return found;
    }

    // ============================================================
    // 一、两列的往返
    // ============================================================

    @Nested
    @DisplayName("一、queue_ms / queue_position 的往返")
    class RoundTrip {

        @Test
        @DisplayName("★ 排过队的：两个值原样写回")
        void queuedValuesRoundTrip() {
            String traceId = nextTraceId();
            QaLog row = minimalRow(traceId);
            row.setQueueMs(1234);
            row.setQueuePosition(7);
            qaLogMapper.insert(row);

            QaLog back = reload(traceId);
            assertEquals(1234, back.getQueueMs());
            assertEquals(7, back.getQueuePosition());
        }

        @Test
        @DisplayName("★ 没排队的：两个值都是 NULL（不是 0）")
        void notQueuedStaysNull() {
            String traceId = nextTraceId();
            qaLogMapper.insert(minimalRow(traceId));

            QaLog back = reload(traceId);
            assertNull(back.getQueueMs(), "没排队必须是 NULL —— 记成 0 会让阶段 7 的"
                    + "「平均等多久」偏低，而且分不清「没开排队」和「开了但没排队」");
            assertNull(back.getQueuePosition());
        }

        /**
         * ★★ 反对照：证明「NULL 和 0 在库里确实是两个不同的值」。
         *
         * <p>上面那条断言（读取出来是 null）只有在<b>写进去的确实是 NULL</b> 时才有意义。
         * 如果 MyBatis 的某些配置把 null 悄悄变成了 0，那两条断言就会一条通过、
         * 另一条失败 —— 而失败信息会指向「值不对」，不指向「null 被吞了」。
         * 这里把两者并排写进去，直接看它们不同。
         */
        @Test
        @DisplayName("★★ 反对照：NULL 和 0 是两回事，能并排存下来")
        void nullAndZeroAreDistinct() {
            String traceNull = nextTraceId();
            String traceZero = nextTraceId();

            qaLogMapper.insert(minimalRow(traceNull));

            QaLog zero = minimalRow(traceZero);
            zero.setQueueMs(0);
            zero.setQueuePosition(0);
            qaLogMapper.insert(zero);

            assertNull(reload(traceNull).getQueueMs(), "没排队 → NULL");
            assertEquals(0, reload(traceZero).getQueueMs(),
                    "排队等了 0 毫秒（正好有空位，但确实走了排队逻辑）→ 0，不是 NULL");
            assertEquals(0, reload(traceZero).getQueuePosition(),
                    "★ position=0 是有意义的：它表示「进过队列，而且是队首」。"
                            + "和「没进过队列」（NULL）是两回事");
        }
    }

    // ============================================================
    // 二、status 的四个取值
    // ============================================================

    @Nested
    @DisplayName("二、status 的四个取值")
    class StatusValues {

        @Test
        @DisplayName("四个常量恰好是 1/2/3/4 —— 改名可以，改值不行")
        void constantsMatchTheCheckConstraint() {
            assertEquals(1, QaLog.STATUS_SUCCESS);
            assertEquals(2, QaLog.STATUS_FAILED);
            assertEquals(3, QaLog.STATUS_CLARIFY);
            assertEquals(4, QaLog.STATUS_RATE_LIMITED,
                    "★ 这个值和数据库的 CHECK 约束是【互相依赖】的："
                            + "改了常量不改约束（或反过来），"
                            + "症状是「写 qa_log 时报约束冲突」，而那时栈会指向 Mapper，"
                            + "不指向「你只改了一边」");
        }

        @Test
        @DisplayName("★ status=4（被限流）能写进去，且形状和澄清那行一样：只有问题，没有答案")
        void rateLimitedRowIsAccepted() {
            String traceId = nextTraceId();
            QaLog row = minimalRow(traceId);
            row.setStatus(QaLog.STATUS_RATE_LIMITED);
            row.setQueueMs(120_000);
            row.setQueuePosition(41);
            row.setErrorMsg("排队等待超过 120 秒");
            qaLogMapper.insert(row);

            QaLog back = reload(traceId);
            assertEquals(QaLog.STATUS_RATE_LIMITED, back.getStatus());
            assertEquals(120_000, back.getQueueMs());
            assertEquals(41, back.getQueuePosition());

            // ★ 「没有发生的事就留空」—— 和 status=3 的形状一致
            assertNull(back.getProvider(), "根本没调模型，provider 必须是 NULL");
            assertNull(back.getModel());
            assertNull(back.getFinalAnswer(), "没有答案，不是「空答案」");
            assertNull(back.getCost());
            assertNull(back.getTotalTokens());
        }

        /**
         * ★★ 反对照 —— 这条才是让上面那条有意义的那个。
         *
         * <p>如果 CHECK 约束不存在（或者 V9 忘了改它、只加了列），
         * 「status=4 写得进去」仍然成立。<b>必须证明 5 写不进去</b>，
         * 才能推出「约束存在，而且边界恰好画在 4」。
         *
         * <p>⚠️ 它同时是一条**回归护栏**：将来有人想把 V9 的 CHECK 改回 (1,2,3)，
         * 这条会红。
         */
        @Test
        @DisplayName("★★ 反对照：status=5 写不进去 —— 证明约束真的在管，不是没有约束")
        void statusFiveIsRejectedByTheDatabase() {
            QaLog row = minimalRow(nextTraceId());
            row.setStatus(5);

            DataIntegrityViolationException e = assertThrows(
                    DataIntegrityViolationException.class,
                    () -> qaLogMapper.insert(row),
                    "status=5 必须被数据库拒绝。如果它能写进去，说明 ck_qa_log_status "
                            + "这个约束根本不存在或者没被 V9 更新 —— "
                            + "那么「status=4 能写进去」那条断言就是恒真的");

            assertTrue(String.valueOf(e.getMessage()).contains("ck_qa_log_status"),
                    "报错里应该点名是哪个约束。实际：" + e.getMessage());
        }

        @Test
        @DisplayName("★ status 不写时默认是 1（V5 的 DEFAULT），不是 NULL")
        void statusDefaultsToSuccess() {
            String traceId = nextTraceId();
            qaLogMapper.insert(minimalRow(traceId));

            assertEquals(QaLog.STATUS_SUCCESS, reload(traceId).getStatus(),
                    "★ 这一点很要紧：status 有 DEFAULT 1，"
                            + "所以「忘了设置 status」和「真的成功了」在数据上一样。"
                            + "新加的路径（比如被限流那条）必须【显式】写 status");
        }
    }
}
