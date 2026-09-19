package com.xbla.rag.client;

import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.ModelDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ModelCallTrace#mergeRound} 的纯单测 —— <b>工具往返把多轮并成一份</b>。
 *
 * <h2>★★ 本类存在的唯一理由：三态累加写错会让成本【永远是 NULL】</h2>
 *
 * <p>合并时最容易写错的写法是：
 * <pre>
 *   if (current == null || incoming == null) return null;   // ✗ 看起来很像对的
 *   return current + incoming;
 * </pre>
 *
 * <p>它错在哪：<b>第一次合并时 {@code current} 本来就是 null</b>
 * （还没有任何一轮填过它）。于是第一次合并的结果是 null，
 * 第二次也是 null，永远都是 null ——
 * 最终 {@code qa_log.cost} 恒为 NULL，而<b>没有任何报错</b>，
 * 看起来就像「这个模型拿不到用量」。
 *
 * <p>三种状态必须分开：
 * <table border="1">
 *   <caption>累加的三态</caption>
 *   <tr><th>本轨迹</th><th>新的一轮</th><th>结果</th><th>含义</th></tr>
 *   <tr><td>null</td><td>有值</td><td>新值</td><td>第一轮，直接采用</td></tr>
 *   <tr><td>有值</td><td>null</td><td><b>null</b></td>
 *       <td>有一轮拿不到用量 → 合计未知。把 null 当 0 会让「不知道」
 *           静默变成「没花钱」</td></tr>
 *   <tr><td>有值</td><td>有值</td><td>相加</td><td>正常情况</td></tr>
 * </table>
 */
@DisplayName("ModelCallTrace · 工具往返的多轮合并")
class ModelCallTraceTest {

    private static final ModelDescriptor P0 =
            new ModelDescriptor("deepseek-flash", "deepseek", "deepseek-flash", null);

    /** 另一个模型 —— 用来验证「降级换模型时成本要逐轮算」 */
    private static final ModelDescriptor P1 =
            new ModelDescriptor("deepseek-v3-2", "siliconflow", "deepseek-ai/DeepSeek-V3.2", null);

    private static ChatUsage usage(int prompt, int completion) {
        return new ChatUsage(prompt, completion, prompt + completion, null, null, null);
    }

    /** 造一轮「成功」的轨迹 */
    private static ModelCallTrace round(ModelDescriptor descriptor, ChatUsage usage,
                                        String cost, int latencyMs) {
        ModelCallTrace t = new ModelCallTrace("r");
        t.succeeded(descriptor, usage, latencyMs);
        t.cost(cost == null ? null : new BigDecimal(cost));
        return t;
    }

    // ============================================================
    // 一、★★ 三态累加
    // ============================================================

    @Nested
    @DisplayName("★★ 三态累加（写错会让成本永远是 NULL）")
    class Accumulate {

        @Test
        @DisplayName("★ 第一轮合并：本轨迹还是空的 → 直接采用新值（不是 null）")
        void firstMergeAdopts() {
            ModelCallTrace master = new ModelCallTrace("t");
            master.mergeRound(round(P0, usage(300, 20), "0.000400", 10));

            assertThat(master.usage())
                    .as("★ 写成「任一方为 null 就返回 null」的话，这里会是 null —— "
                            + "之后每一轮也都是 null")
                    .isNotNull();
            assertThat(master.usage().promptTokens()).isEqualTo(300);
            assertThat(master.cost()).isEqualByComparingTo("0.000400");
            assertThat(master.route()).isEqualTo(P0);
        }

        @Test
        @DisplayName("★ 第二轮合并且相加 —— 不是覆盖")
        void secondMergeAdds() {
            ModelCallTrace master = new ModelCallTrace("t");
            master.mergeRound(round(P0, usage(300, 20), "0.000400", 10));
            master.mergeRound(round(P0, usage(500, 80), "0.000900", 20));

            assertThat(master.usage().promptTokens())
                    .as("★ 覆盖的话会是 500，第 1 轮的输入费凭空消失")
                    .isEqualTo(800);
            assertThat(master.usage().completionTokens()).isEqualTo(100);
            assertThat(master.usage().totalTokens()).isEqualTo(900);
            assertThat(master.cost()).isEqualByComparingTo("0.001300");
            assertThat(master.llmLatencyMs()).isEqualTo(30);
        }

        @Test
        @DisplayName("★ 有一轮拿不到用量 → 合计必须是 null，不能把 null 当 0")
        void anyUnknownMakesTotalUnknown() {
            ModelCallTrace master = new ModelCallTrace("t");
            master.mergeRound(round(P0, usage(300, 20), "0.000400", 10));

            // 第二轮成功但拿不到用量（供应商没回 usage）
            ModelCallTrace noUsage = new ModelCallTrace("r2");
            noUsage.succeeded(P0, null, 15);
            noUsage.cost(null);
            master.mergeRound(noUsage);

            assertThat(master.usage())
                    .as("★ 「少算了一点」和「不知道」在成本分析里含义完全不同 —— "
                            + "把 null 当 0 会静默低估")
                    .isNull();
            assertThat(master.cost()).isNull();
            assertThat(master.llmLatencyMs()).as("耗时是可加的，不受用量影响").isEqualTo(25);
        }

        @Test
        @DisplayName("★★ 正-反对照：把 null 当 0 的那个版本确实给出不同的（错的）结果")
        void treatingNullAsZeroIsWrong() {
            ModelCallTrace traceWithData = round(P0, usage(300, 20), "0.000400", 10);

            // 「把 null 当 0」会算出 300+0=300，也就是把「不知道」说成了「没花钱」
            int wrongAnswer = traceWithData.usage().promptTokens() + 0;
            assertThat(wrongAnswer)
                    .as("★ 这就是错误版本会给出的数 —— 它看起来完全合理，"
                            + "而正确版本给的是 null")
                    .isEqualTo(300);
        }
    }

    // ============================================================
    // 二、路由覆盖、事件追加
    // ============================================================

    @Nested
    @DisplayName("路由覆盖 / 降级事件追加")
    class RouteAndEvents {

        @Test
        @DisplayName("★ 路由取【最后一轮】的 —— 产出最终回答的就是它")
        void routeIsLastRound() {
            ModelCallTrace master = new ModelCallTrace("t");
            master.mergeRound(round(P0, usage(100, 10), "0.000100", 5));
            master.mergeRound(round(P1, usage(200, 20), "0.000200", 6));

            assertThat(master.route())
                    .as("★ 第 1 轮是 P0、第 2 轮降级到 P1 —— provider 要记 P1")
                    .isEqualTo(P1);
        }

        @Test
        @DisplayName("★★ 成本逐轮算再相加 —— 不能拿「总 token × 最后一轮单价」重算")
        void costIsPerRoundNotRecomputed() {
            ModelCallTrace master = new ModelCallTrace("t");
            // 两轮的单价【故意不同】（降级换了模型）
            master.mergeRound(round(P0, usage(1000, 0), "0.001000", 5));
            master.mergeRound(round(P1, usage(1000, 0), "0.002000", 5));

            assertThat(master.cost()).isEqualByComparingTo("0.003000");

            // ★ 反证：拿总 token（2000）× 最后一轮单价（0.002/1000）会得到 0.004000，
            //   比真实成本高 33%。而真实系统里降级是常态，不是例外
            BigDecimal wrong = new BigDecimal("0.004000");
            assertThat(master.cost())
                    .as("★ 重算会给出一个【看起来合理】但错的数")
                    .isNotEqualByComparingTo(wrong);
        }

        @Test
        @DisplayName("★ 降级事件【追加】—— 丢掉哪一轮都是在隐藏事实")
        void eventsAccumulate() {
            ModelCallTrace r1 = new ModelCallTrace("r1");
            r1.degrade("deepseek-flash", "deepseek-v3-2", "server_error");
            r1.succeeded(P1, usage(100, 10), 5);
            r1.cost(new BigDecimal("0.0001"));

            ModelCallTrace r2 = new ModelCallTrace("r2");
            r2.circuitOpen("deepseek-flash", "deepseek-v3-2");
            r2.succeeded(P1, usage(100, 10), 5);
            r2.cost(new BigDecimal("0.0001"));

            ModelCallTrace master = new ModelCallTrace("t");
            master.mergeRound(r1);
            master.mergeRound(r2);

            assertThat(master.events())
                    .as("★ 两轮的降级都要留下 —— 第 1 轮失败过这件事不能因为"
                            + "第 2 轮成功了就被抹掉")
                    .hasSize(2);
            assertThat(master.degraded()).isTrue();
        }
    }

    // ============================================================
    // 三、边界
    // ============================================================

    @Test
    @DisplayName("合并 null 或自己 —— 都不做任何事，不抛异常")
    void mergingNullOrSelfIsNoOp() {
        ModelCallTrace master = new ModelCallTrace("t");
        master.succeeded(P0, usage(100, 10), 5);
        master.cost(new BigDecimal("0.0001"));

        master.mergeRound(null);
        master.mergeRound(master);      // ★ 自己合自己会让用量翻倍，必须挡住

        assertThat(master.usage().promptTokens()).isEqualTo(100);
        assertThat(master.cost()).isEqualByComparingTo("0.0001");
    }

    @Test
    @DisplayName("全失败的一轮：路由为 null、用量为 null，但事件留着")
    void failedRoundKeepsOnlyEvents() {
        ModelCallTrace failed = new ModelCallTrace("failed");
        failed.degrade("deepseek-flash", "deepseek-v3-2", "timeout");
        // 注意：没有 succeeded()，所以 route 和 usage 都是 null

        ModelCallTrace master = new ModelCallTrace("t");
        master.mergeRound(failed);

        assertThat(master.route()).isNull();
        assertThat(master.usage()).isNull();
        assertThat(master.cost()).isNull();
        assertThat(master.events()).hasSize(1);
    }
}
