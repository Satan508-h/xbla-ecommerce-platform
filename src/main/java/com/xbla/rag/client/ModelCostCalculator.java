package com.xbla.rag.client;

import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 按 token 用量和配置单价计算成本。
 *
 * <p>纯函数，没有状态、没有 I/O —— 所以能脱离 Spring 上下文直接单测。
 * 这一点很重要：成本算错不会让程序崩，它只会<b>安静地污染数据</b>，
 * 等到阶段 7 做成本分析时才发现，那时候已经没法回溯了。
 *
 * <h3>为什么单价放在配置里而不是写死在代码里</h3>
 *
 * <p>因为模型价格变得太快。本项目在阶段 2 动工当天就查到：
 * DeepSeek 在 2026-09-10 刚降过一次价，而且不同来源的数字还不一致。
 * 如果写死在代码里，每次调价都要改代码、重新构建、重新部署；
 * 写成配置，改一行 yaml 重启即可。
 *
 * <h3>已知的简化</h3>
 *
 * <p>DeepSeek 采用<b>峰谷分时计价</b>（工作日 9:00-12:00、14:00-18:00 为高峰，
 * 价格翻倍）。本项目只配一套单价，按<b>空闲时段</b>计算 ——
 * 意味着在高峰时段跑批会把成本低估约一半。
 * 这是有意的取舍：阶段 2 的目标是链路打通，成本统计够用即可。
 * 详见 {@code docs/08-技术决策记录(ADR).md}。
 */
@Component
public class ModelCostCalculator {

    /** 单价的计价单位：元 / 百万 token。除以它的操作见 {@link #calculate} */
    private static final int TOKENS_PER_UNIT = 1_000_000;

    /**
     * 计算一次调用的成本。
     *
     * <p>★★ <b>拿不到用量时返回 {@code null}，绝不估算。</b>
     *
     * <p>{@code qa_log} 是只增不改不删的表，评测数据全部来源于它。
     * 编一个数字进去，会永久污染阶段 7 的所有成本指标，
     * 而且事后无法分辨「哪些是真实值、哪些是估算的」。
     * NULL 至少是诚实的：调用方看到 null 就知道「这次没拿到用量」。
     *
     * @param descriptor 模型身份（含单价）
     * @param usage      token 用量，可为 null
     * @return 成本（元），保留 6 位小数；用量不可用时返回 {@code null}
     */
    public BigDecimal calculate(ModelDescriptor descriptor, ChatUsage usage) {
        if (descriptor == null || usage == null || !usage.isPresent()) {
            return null;
        }

        LlmProperties.Pricing pricing = descriptor.pricing();
        if (pricing == null) {
            return null;
        }

        int promptTokens = nz(usage.promptTokens());
        int cacheHit = nz(usage.promptCacheHitTokens());
        int completion = nz(usage.completionTokens());

        // 缓存未命中的输入 = 总数 - 命中数。
        // ★ 优先用供应商直接给的 prompt_cache_miss_tokens（精确），
        //   没有才自己做减法，并且用 max(..., 0) 兜底 ——
        //   万一服务端返回的命中数比总数还大（数据不一致），
        //   不减到负数，否则成本会变成负值，写进库时撞上
        //   qa_log 的 CHECK (cost >= 0) 约束直接报错。
        int cacheMiss = usage.promptCacheMissTokens() != null
                ? usage.promptCacheMissTokens()
                : Math.max(promptTokens - cacheHit, 0);

        BigDecimal raw = BigDecimal.ZERO
                .add(perUnit(pricing.getInput()).multiply(BigDecimal.valueOf(cacheMiss)))
                .add(perUnit(pricing.getCacheHitInput()).multiply(BigDecimal.valueOf(cacheHit)))
                .add(perUnit(pricing.getOutput()).multiply(BigDecimal.valueOf(completion)));

        // ★ 用 movePointLeft(6) 而不是 divide(BigDecimal.valueOf(1_000_000))。
        //
        //   先澄清一个常见的误解（我自己一开始也写错了）：
        //   BigDecimal.divide 只在「除不尽」时才抛
        //       ArithmeticException: Non-terminating decimal expansion
        //   而 10^6 = 2^6 × 5^6，只含 2 和 5 这两个质因子，
        //   所以【除以一百万永远除得尽】，那种写法在当前这个除数下其实是安全的。
        //
        //   用 movePointLeft 的真正理由：
        //     1. 语义更准 —— 「移动小数点」正是「元 → 百万分之一元」这个换算的本质
        //     2. 与除数无关 —— 哪天换成「每千 token 计价」（除 1000）或者
        //        引入别的换算比例，movePointLeft/Right 依然不会抛，
        //        而 divide 就要重新论证一遍能不能除尽
        //     3. 不涉及 scale 协商，少一个需要推敲的地方
        //
        // setScale(6, HALF_UP) 是为了对齐数据库列 qa_log.cost NUMERIC(10,6)。
        // 不显式设的话 PostgreSQL 会替我们四舍五入，
        // 导致「代码里算出的值」和「从库里查出来的值」不一致，排查时非常迷惑。
        return raw.movePointLeft(6).setScale(6, RoundingMode.HALF_UP);
    }

    /** null 当 0 处理 */
    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /** 单价字段本身为 null 时当 0 处理（配置漏填的容错） */
    private static BigDecimal perUnit(BigDecimal price) {
        return price == null ? BigDecimal.ZERO : price;
    }

    /** 计价单位，供文档与测试引用 */
    public static int tokensPerUnit() {
        return TOKENS_PER_UNIT;
    }
}
