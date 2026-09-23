package com.xbla.rag.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link EvalMark} —— 评测标记的构造规则（阶段 7）。
 *
 * <h2>★ 为什么纯单测就够了，为什么不需要起 Spring</h2>
 *
 * <p>这里验证的全是<b>纯函数</b>的判断：什么时候返回 null、空白怎么处理、
 * 超长怎么办。它们和数据库、和 HTTP 都无关 —— 起 Spring 只会让这个测试慢十倍。
 *
 * <p>★ 真正需要起 Spring 的那一半（标记落不落进 {@code qa_log}）
 * 在 {@code QaLogEvalMarkTest} 里。
 *
 * <h2>★★ 每条断言都配了反对照</h2>
 *
 * <p>「两个头都为空时返回 null」这句话，如果这个方法<b>永远返回 null</b>
 * 也照样成立 —— 那样它测的是空气。所以每条断言旁边都有一条
 * 「该返回非 null 的那一边<b>确实</b>返回了非 null」。
 */
@DisplayName("EvalMark · 评测标记的构造规则")
class EvalMarkTest {

    @Nested
    @DisplayName("一、什么时候算「没有标记」")
    class Absence {

        @Test
        @DisplayName("两个头都没传 → null")
        void bothNull() {
            assertNull(EvalMark.of(null, null));
        }

        @Test
        @DisplayName("★ 两个头都是空白串 → null（不是「带了一个空标记」）")
        void bothBlank() {
            assertNull(EvalMark.of("", "   "), "空白串和「没传」是同一件事 —— "
                    + "HTTP 头可以合法地是空串（curl -H 'X-Xbla-Eval-Run:'）");
            assertNull(EvalMark.of("  \t ", null), "只传了空白也一样");
        }

        /**
         * ★★ 这条是上面两条的反对照。
         *
         * <p>如果 {@code of()} 实现成「永远返回 null」（比如把 return 写反了），
         * 上面两条会全绿。必须有至少一条<b>要求它返回非 null</b> 的断言，
         * 才能证明「返回 null」是<b>判断的结果</b>而不是<b>唯一的行为</b>。
         */
        @Test
        @DisplayName("★★ 反对照：真的传了值就必须返回非 null")
        void presentValuesProduceAMark() {
            EvalMark mark = EvalMark.of("run-1", "X-001");
            assertNotNull(mark, "传了值却拿到 null —— 那说明这个方法【永远】返回 null，"
                    + "上面两条「空白 → null」测的就是空气");
            assertEquals("run-1", mark.runId());
            assertEquals("X-001", mark.questionNo());
        }
    }

    @Nested
    @DisplayName("二、值的规整")
    class Normalization {

        @Test
        @DisplayName("★ 前后空白被去掉 —— 否则「run-1」和「run-1 」是两轮不同的评测")
        void valuesAreTrimmed() {
            EvalMark mark = EvalMark.of("  run-1  ", "\tX-001\n");
            assertNotNull(mark);
            assertEquals("run-1", mark.runId(),
                    "★ 不 trim 的后果不是报错，而是对账时行数对不上："
                            + "跑题器记的是 'run-1'，库里存的是 'run-1 '，"
                            + "而两者在人眼看来一模一样");
            assertEquals("X-001", mark.questionNo());
        }

        @Test
        @DisplayName("★★ 超长值【截断】而不是抛异常 —— 用户请求不能因为一个头而 500")
        void overlongValuesAreClampedInsteadOfThrowing() {
            String tooLong = "r".repeat(200);
            EvalMark mark = EvalMark.of(tooLong, tooLong);

            assertNotNull(mark, "超长不应该让构造失败");
            assertEquals(64, mark.runId().length(),
                    "run_id 截到 VARCHAR(64) —— 不截的话那一行会 INSERT 失败，"
                            + "而 writeQaLog 把异常吞成一条 ERROR 日志，"
                            + "于是【整行消失】（包括本该记下的真实问答）");
            assertEquals(32, mark.questionNo().length(), "question_no 截到 VARCHAR(32)");
        }

        /**
         * ★★ 反对照：证明「截断」是<span>真的发生了</span>，不是「反正都塞得下」。
         *
         * <p>如果 {@code qa_log} 的那两列其实没有长度限制（或者限制很远），
         * 上面那条断言就变得毫无意义。这条用一个<b>刚好不超长</b>的值证明
         * 边界画在哪：64 位的原样保留，65 位的被截。
         */
        @Test
        @DisplayName("★★ 反对照：恰好 64 位不截，65 位才截 —— 边界画在列宽上")
        void theBoundaryIsExactlyTheColumnWidth() {
            String exactly = "r".repeat(64);
            assertEquals(exactly, EvalMark.of(exactly, null).runId(),
                    "64 位正好装得下，不该动它");

            String oneMore = "r".repeat(65);
            assertEquals(exactly, EvalMark.of(oneMore, null).runId(),
                    "65 位才截 —— 说明上面那条测的是【列宽】这个边界，"
                            + "不是「随便截一下」");
        }
    }

    @Nested
    @DisplayName("三、半标记（只有一半）")
    class HalfMark {

        @Test
        @DisplayName("★ 只有 run 没有题号 → 原样保留，【不】补齐成 null")
        void runWithoutQuestionNo() {
            EvalMark mark = EvalMark.of("run-1", null);
            assertNotNull(mark, "只带 run 的请求仍然是评测流量，不能被丢掉");
            assertEquals("run-1", mark.runId());
            assertNull(mark.questionNo(),
                    "★ 题号是 NULL，不是空串 —— 报告端点要靠「有没有题号」"
                            + "把这批行单独数出来（它们归不了题，两头都不属于）");
        }

        @Test
        @DisplayName("★ 只有题号没有 run 也一样保留")
        void questionNoWithoutRun() {
            EvalMark mark = EvalMark.of(null, "X-001");
            assertNotNull(mark);
            assertNull(mark.runId());
            assertEquals("X-001", mark.questionNo());
        }
    }

    @Nested
    @DisplayName("四、头名的单一出处")
    class HeaderNames {

        @Test
        @DisplayName("★ 两个头名各只有一处定义 —— 改名字时改这里和 Python 脚本")
        void headerNamesArePinned() {
            assertEquals("X-Xbla-Eval-Run", EvalMark.HEADER_RUN_ID);
            assertEquals("X-Xbla-Eval-No", EvalMark.HEADER_QUESTION_NO,
                    "★ 它们和 scripts/eval_run.py 里的字面量是【约定】关系，"
                            + "跨语言共享不了常量。改名必须同时改两边，"
                            + "而漏改的症状是：评测流量被当成真实用户流量，且不报错");
        }
    }
}
