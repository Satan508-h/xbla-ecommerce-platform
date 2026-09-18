package com.xbla.rag.client;

import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.config.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ModelCostCalculator} 的单元测试。
 *
 * <p><b>为什么这个类值得单独测？</b>
 * 因为成本算错<b>不会让程序崩溃</b>，它只会安静地把错误的数字写进
 * {@code qa_log}。而那张表是只增不改不删的，等到阶段 7 做成本分析时
 * 才发现问题，已经无法回溯哪些数字是错的。
 *
 * <p>纯函数、无 Spring 依赖，毫秒级跑完。
 */
@DisplayName("ModelCostCalculator —— 成本计算")
class ModelCostCalculatorTest {

    private final ModelCostCalculator calculator = new ModelCostCalculator();

    /** 构造一个测试用模型：输入 1 元 / 缓存命中 0.02 元 / 输出 4 元（每百万 token） */
    private static ModelDescriptor descriptor(String input, String cacheHit, String output) {
        LlmProperties.Pricing pricing = new LlmProperties.Pricing();
        pricing.setInput(new BigDecimal(input));
        pricing.setCacheHitInput(new BigDecimal(cacheHit));
        pricing.setOutput(new BigDecimal(output));
        return new ModelDescriptor("test-model", "test-provider", "test-model-id", pricing);
    }

    private static ModelDescriptor standardDescriptor() {
        return descriptor("1.0", "0.02", "4.0");
    }

    // ============================================================
    // ★ 拿不到用量时必须返回 null —— 这是最重要的约定
    // ============================================================

    @Nested
    @DisplayName("拿不到用量时返回 null（绝不估算）")
    class WhenUsageUnavailable {

        @Test
        @DisplayName("usage 为 null → cost 为 null")
        void nullUsage() {
            assertThat(calculator.calculate(standardDescriptor(), null)).isNull();
        }

        @Test
        @DisplayName("usage 里三个 token 字段全为 null → cost 为 null")
        void usageWithoutTokens() {
            ChatUsage empty = ChatUsage.unknown();

            assertThat(calculator.calculate(standardDescriptor(), empty)).isNull();
        }

        @Test
        @DisplayName("descriptor 为 null → cost 为 null")
        void nullDescriptor() {
            ChatUsage usage = new ChatUsage(100, 50, 150, null, 0, 100);

            assertThat(calculator.calculate(null, usage)).isNull();
        }

        @Test
        @DisplayName("pricing 为 null → cost 为 null")
        void nullPricing() {
            ModelDescriptor noPricing = new ModelDescriptor("k", "p", "m", null);
            ChatUsage usage = new ChatUsage(100, 50, 150, null, 0, 100);

            assertThat(calculator.calculate(noPricing, usage)).isNull();
        }
    }

    // ============================================================
    // 基本计算
    // ============================================================

    @Test
    @DisplayName("★ 输入 + 输出：单价单位是「元/百万 token」")
    void basicCalculation() {
        // 1000 个未命中输入 + 500 个输出
        // = 1.0 × 1000 + 4.0 × 500 = 3000  →  3000 ÷ 1e6 = 0.003
        ChatUsage usage = new ChatUsage(1000, 500, 1500, null, 0, 1000);

        BigDecimal cost = calculator.calculate(standardDescriptor(), usage);

        assertThat(cost).isEqualByComparingTo("0.003");
    }

    @Test
    @DisplayName("★ 缓存命中走便宜的档位（差两个数量级，不能混）")
    void cacheHitUsesCheaperTier() {
        // 200 个未命中(1.0) + 800 个命中(0.02)
        // = 200 + 16 = 216  →  0.000216
        ChatUsage usage = new ChatUsage(1000, 0, 1000, null, 800, 200);

        BigDecimal cost = calculator.calculate(standardDescriptor(), usage);

        assertThat(cost).isEqualByComparingTo("0.000216");
    }

    @Test
    @DisplayName("供应商没给 miss 字段时，自己用 prompt - hit 反推")
    void derivesMissFromPromptMinusHit() {
        // promptCacheMissTokens 传 null，靠 prompt(1000) - hit(800) = 200 推出
        ChatUsage usage = new ChatUsage(1000, 0, 1000, null, 800, null);

        BigDecimal cost = calculator.calculate(standardDescriptor(), usage);

        assertThat(cost).isEqualByComparingTo("0.000216");
    }

    @Test
    @DisplayName("★ 命中数比总数还大时不能算出负数（否则会撞 qa_log 的 CHECK 约束）")
    void clampsNegativeMissToZero() {
        // 数据不一致的极端情况：hit(1500) > prompt(1000)
        // 不夹到 0 的话 miss = -500，成本会变成负数，
        // 写库时撞上 qa_log 的 CHECK (cost >= 0) 直接报错，整条日志丢失
        ChatUsage inconsistent = new ChatUsage(1000, 0, 1000, null, 1500, null);

        BigDecimal cost = calculator.calculate(standardDescriptor(), inconsistent);

        assertThat(cost).isEqualByComparingTo("0.00003");   // 0.02 × 1500 ÷ 1e6
    }

    @Test
    @DisplayName("推理 token 不单独计费 —— 它是 completion_tokens 的子集")
    void reasoningTokensAreNotDoubleCounted() {
        // 500 个输出里有 400 个是推理，但计费看的是 500 这个总数
        ChatUsage withReasoning = new ChatUsage(1000, 500, 1500, 400, 0, 1000);
        ChatUsage withoutReasoning = new ChatUsage(1000, 500, 1500, null, 0, 1000);

        assertThat(calculator.calculate(standardDescriptor(), withReasoning))
                .isEqualByComparingTo(calculator.calculate(standardDescriptor(), withoutReasoning));
    }

    // ============================================================
    // 精度与健壮性
    // ============================================================

    @Test
    @DisplayName("★ 结果精度必须是 6 位小数，对齐 qa_log.cost NUMERIC(10,6)")
    void scaleMatchesDatabaseColumn() {
        ChatUsage usage = new ChatUsage(7, 13, 20, null, 0, 7);

        BigDecimal cost = calculator.calculate(standardDescriptor(), usage);

        // 不显式 setScale 的话，PostgreSQL 会替我们四舍五入，
        // 导致「代码算出的值」和「查出来的值」不一致
        assertThat(cost.scale()).isEqualTo(6);
    }

    @Test
    @DisplayName("免费模型（单价全 0）算出 0.000000 而不是 null")
    void freeModelCostsZero() {
        ChatUsage usage = new ChatUsage(1000, 500, 1500, null, 0, 1000);

        BigDecimal cost = calculator.calculate(descriptor("0", "0", "0"), usage);

        assertThat(cost).isNotNull();               // ★ 非 null —— 确实花了 0 元
        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("单价字段漏配（null）时当 0 处理，不抛异常")
    void toleratesNullPricingFields() {
        LlmProperties.Pricing partial = new LlmProperties.Pricing();
        partial.setOutput(new BigDecimal("4.0"));    // input / cacheHitInput 留 null
        ModelDescriptor descriptor = new ModelDescriptor("k", "p", "m", partial);

        ChatUsage usage = new ChatUsage(1000, 500, 1500, null, 0, 1000);

        assertThatCode(() -> calculator.calculate(descriptor, usage))
                .doesNotThrowAnyException();
        assertThat(calculator.calculate(descriptor, usage))
                .isEqualByComparingTo("0.002");      // 只有输出部分：4.0 × 500 ÷ 1e6
    }

    @Test
    @DisplayName("大数不溢出：百万级 token 也能算对")
    void handlesLargeTokenCounts() {
        // 1,000,000 输入 + 1,000,000 输出 = 1.0 + 4.0 = 5 元
        ChatUsage usage = new ChatUsage(1_000_000, 1_000_000, 2_000_000, null, 0, 1_000_000);

        assertThat(calculator.calculate(standardDescriptor(), usage))
                .isEqualByComparingTo("5.0");
    }
}
