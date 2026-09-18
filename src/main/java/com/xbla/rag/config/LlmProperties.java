package com.xbla.rag.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型供应商与对话链路的配置绑定类。
 *
 * <p>对应 {@code application.yml} 里的 {@code xbla.llm.*}，共三层：
 * <pre>
 * xbla:
 *   llm:
 *     providers:   { deepseek: {base-url, api-key}, siliconflow: {...} }   ← ① 连接
 *     models:      { deepseek-flash: {provider, model-id, pricing}, ... }  ← ② 模型
 *     chat-chain:  [deepseek-flash, deepseek-v4-flash, qwen3-8b]           ← ③ 策略
 * </pre>
 *
 * <p><b>为什么分三层？</b>供应商是「连接」、模型是「计价与协议」、链路是「策略」。
 * P1 和 P2 共用同一个硅基流动连接和同一个 Key，但模型不同；
 * 将来要给 P2 换一家供应商，只改 {@code models} 里的一行 {@code provider}，
 * 链路和代码都不用动。
 *
 * <p><b>为什么不用 record？</b>嵌套 {@code Map} + 字段默认值的组合下，
 * record 的构造器绑定比 setter 绑定挑剔得多，而收益为零。
 * 用 Lombok {@code @Data} 生成无参构造 + setter 是 Spring Boot 最稳的绑定方式。
 *
 * <p><b>为什么不用 {@code @Component}？</b>启动类上加了
 * {@code @ConfigurationPropertiesScan}，会扫描 {@code com.xbla.rag} 包下所有
 * 带 {@code @ConfigurationProperties} 的类并注册成 Bean。
 * 好处是配置类不用带任何 Spring 注解，职责更单纯。
 *
 * @see com.xbla.rag.config.ChatChainConfig 启动期校验与运行时对象装配
 */
@Data
@ConfigurationProperties(prefix = "xbla.llm")
public class LlmProperties {

    /** ① 连接层：key 是供应商名（deepseek / siliconflow） */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    /** ② 模型层：key 是链路条目名（deepseek-flash / deepseek-v4-flash / qwen3-8b） */
    private Map<String, Model> models = new LinkedHashMap<>();

    /**
     * ③ 策略层：降级顺序，元素是 {@link #models} 的 key。
     *
     * <p>顺序即优先级 —— 第一个是 P0，依次降级。
     * 每个条目在运行时对应一个独立的熔断器（名字就是这个 key）。
     */
    private List<String> chatChain = new ArrayList<>();

    /** 全局默认参数 */
    private Defaults defaults = new Defaults();

    /** 向量化配置（只有硅基流动提供） */
    private Embedding embedding = new Embedding();

    /** 重排序配置（只有硅基流动提供） */
    private Rerank rerank = new Rerank();

    // ============================================================
    // ① 连接层
    // ============================================================

    /**
     * 一个供应商的连接信息。
     *
     * <p><b>key 的命名规则</b>：Spring Boot 对 {@code Map} 的 key 默认使用严格绑定
     * （不做松散转换），但要求 key 里只能出现小写字母、数字和短横线。
     * 我们的 key 正好符合，所以直接写 {@code deepseek-v4-flash} 即可。
     * 如果 key 里出现点号或方括号，就必须写成 {@code [key]} 的括号语法。
     */
    @Data
    public static class Provider {

        /**
         * 服务域名，<b>不带 {@code /v1} 后缀</b>。
         *
         * <p>例如 {@code https://api.deepseek.com}。
         * 版本号由代码统一拼成 {@code {base-url}/v1/chat/completions}——
         * 把版本放在代码里而不是配置里，是为了让「配错域名」和「配错协议版本」
         * 这两类问题在日志里能一眼分开。
         */
        private String baseUrl;

        /**
         * API Key。
         *
         * <p>在 {@code application.yml} 里写成 {@code ${DEEPSEEK_API_KEY:}} 的形式，
         * 真实值放 {@code application-local.yml}（已 gitignore）。
         * 留空时退化成空字符串 —— 启动不报错，调用时返回 401 触发降级。
         *
         * <p>★ 这是刻意设计：「故意把 P0 的 Key 改错，确认自动降级到 P1」
         * 是阶段 2 的验收标准之一。如果 Key 为空就直接启动失败，这条验收就没法做了。
         */
        private String apiKey;

        /**
         * 流式请求是否携带 {@code stream_options: {include_usage: true}}。
         *
         * <p>带上之后，服务端会在 {@code [DONE]} 之前多推一个 chunk，
         * 它的 {@code choices} 是<b>空数组</b>、{@code usage} 是整次请求的汇总。
         * 不带的话流式请求就拿不到 token 数，成本只能记 NULL。
         *
         * <p>★ 2026-09-18 实测：DeepSeek 官方和硅基流动都支持。
         * 留成配置项是因为这是供应商特性，将来换供应商时可能不支持 ——
         * 那时改配置即可，不用改代码。
         */
        private boolean streamIncludeUsage = true;
    }

    // ============================================================
    // ② 模型层
    // ============================================================

    /**
     * 一个具体模型的定义：用哪个连接 + 真实模型 ID + 单价。
     *
     * <p>{@code key} 是这个模型在项目内的<b>逻辑名</b>（如 {@code deepseek-flash}），
     * 会出现在降级事件、熔断器名和日志里；
     * {@link #modelId} 是发给 API 的<b>真实 ID</b>（如 {@code deepseek-ai/DeepSeek-V4-Flash}）。
     * 两者分开是因为真实 ID 会变（CLAUDE.md 明确提醒过），
     * 而逻辑名要保持稳定，否则历史数据的降级事件就对不上了。
     */
    @Data
    public static class Model {

        /** 引用 {@link #providers} 的 key。配错会在启动期直接报错，不会拖到运行时。 */
        private String provider;

        /** 发给 API 的真实模型 ID，如 {@code deepseek-ai/DeepSeek-V4-Flash} */
        private String modelId;

        /** 单价，用于计算 {@code qa_log.cost} */
        private Pricing pricing = new Pricing();
    }

    /**
     * 模型单价，单位：<b>元 / 百万 token</b>。
     *
     * <p>口径写死在字段名和这里，配置里不加 {@code unit} 字段 ——
     * 加了就要写折算逻辑，而模型 API 的报价几乎都是按百万 token 报的。
     * 用注释把口径钉死，比加一个只有两种取值的枚举更有价值。
     *
     * <p><b>三档为什么要分开？</b>
     * <ul>
     *   <li>缓存命中的输入比未命中便宜一到两个数量级（省了 prefill 计算）</li>
     *   <li>输出比输入贵，因为要逐 token 生成</li>
     * </ul>
     * DeepSeek 的 {@code usage} 里直接给了 {@code prompt_cache_hit_tokens} 和
     * {@code prompt_cache_miss_tokens}，所以能精确区分，不用估算。
     */
    @Data
    public static class Pricing {

        /** 缓存<b>未命中</b>的输入单价 */
        private BigDecimal input = BigDecimal.ZERO;

        /** 缓存<b>命中</b>的输入单价 */
        private BigDecimal cacheHitInput = BigDecimal.ZERO;

        /**
         * 输出单价。
         *
         * <p>★ <b>已经包含推理 token</b>，不要因为 {@code reasoning_tokens} 再算一遍。
         * 实测：{@code completion_tokens=124} 里有 108 个是推理，
         * 而计费看的是 {@code completion_tokens} 这个总数。
         */
        private BigDecimal output = BigDecimal.ZERO;
    }

    // ============================================================
    // 全局默认参数
    // ============================================================

    /** 调用模型时的默认参数 */
    @Data
    public static class Defaults {

        /**
         * 单次生成的最大输出 token 数。
         *
         * <p>★★ <b>这个值不能改小</b> ★★
         *
         * <p>{@code deepseek-flash} 是推理模型，实测 124 个输出 token 里
         * 108 个花在推理上（87%）。如果 max-tokens 给小了，
         * 推理会把额度全部吃光，最终 {@code content} 返回<b>空字符串</b>——
         * 现象是「AI 不说话」，而日志里没有任何异常。
         *
         * <p>实测记录：{@code max_tokens=20} → {@code content=""}。
         *
         * <p>代码里还有两道防线：一是把「HTTP 200 但 content 为空」
         * 判定为 {@code EMPTY_CONTENT} 失败，二是此时若还没吐字就降级到下一家。
         */
        private Integer maxTokens = 2048;

        /**
         * 采样温度。
         *
         * <p>默认 {@code null} = 请求体里<b>不带</b>这个字段，用服务端默认值。
         * 写成 null 而不是删掉这个字段，是为了让「可配置但默认不启用」
         * 这件事在配置文件里可见 —— 否则看代码的人会以为忘了实现。
         */
        private Double temperature;

        /** TCP 建连超时：只覆盖连接建立阶段（目标挂了 / DNS 解析不出来） */
        private Duration connectTimeout = Duration.ofSeconds(5);

        /**
         * 请求超时：从发出到<b>收到响应头</b>（TTFB，首字节时间）。
         *
         * <p>★ 对流式请求（{@code BodyHandlers.ofLines}），这个超时
         * <b>只覆盖到响应头</b>，之后逐行读取的过程它不管
         * （JDK HttpClient 没有 per-read 超时）。所以流式场景还需要
         * {@code SseEmitter} 那层超时兜底。
         */
        private Duration requestTimeout = Duration.ofSeconds(15);

        /**
         * <b>非流式</b>请求的完整响应超时。
         *
         * <p>★ 为什么必须和 {@link #requestTimeout} 分开？
         * 因为 JDK HttpClient 的超时语义<b>取决于 body handler</b>：
         * <ul>
         *   <li>{@code ofLines}（流式）→ 超时到「响应头到达」为止</li>
         *   <li>{@code ofString}（非流式）→ 超时要覆盖<b>整个响应体接收完毕</b></li>
         * </ul>
         *
         * <p>如果非流式也复用 15 秒，任何一篇长回答都必然超时。
         * 180 秒是按「2048 输出 token ÷ 约 20 token/秒 ≈ 100 秒」留的余量。
         */
        private Duration completeTimeout = Duration.ofSeconds(180);

        /** 单次流式响应允许的最长持续时间，防止超长回答一直占着连接 */
        private Duration maxStreamDuration = Duration.ofMinutes(5);

        /** 熔断器参数 */
        private CircuitBreakerProps circuitBreaker = new CircuitBreakerProps();
    }

    /**
     * 熔断器参数，与 Resilience4j 的 {@code CircuitBreakerConfig} 一一对应。
     *
     * <p><b>★ 最关键的一个设计：观测到的「耗时」用 TTFB，不是整个流的持续时间。</b>
     *
     * <p>原因：一次 60 秒的<b>正常</b>长回答，如果把整个流的耗时喂给熔断器，
     * 会触发 {@link #slowCallRateThreshold}，导致熔断器在
     * 「系统最健康、正在正常干重活」的时候跳闸 —— 这是最坏的反向优化。
     * 所以代码里只把「建连 + 首字延迟」作为观测时长。
     *
     * <p>{@link #slowCallDurationThreshold} 因此设成 15 秒：
     * 正常 TTFB 只有 1-3 秒，15 秒确实说明这家有问题。
     */
    @Data
    public static class CircuitBreakerProps {

        /** COUNT_BASED = 按调用次数统计；TIME_BASED = 按时间窗口统计 */
        private String slidingWindowType = "COUNT_BASED";

        /** 滑动窗口大小：统计最近多少次调用 */
        private int slidingWindowSize = 10;

        /** 最少调用次数：样本不够时不做判定，避免刚启动就打错标签 */
        private int minimumNumberOfCalls = 5;

        /** 失败率阈值（百分比），超过就跳闸 */
        private float failureRateThreshold = 50f;

        /** 慢调用判定阈值，见类注释 */
        private Duration slowCallDurationThreshold = Duration.ofSeconds(15);

        /** 慢调用率阈值（百分比） */
        private float slowCallRateThreshold = 80f;

        /** 跳闸后等多久进入「半开」状态试探 */
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);

        /** 半开状态下允许放行的探测请求数 */
        private int permittedNumberOfCallsInHalfOpenState = 3;

        /** 是否自动从 OPEN 转到 HALF_OPEN（false 的话需要手工触发） */
        private boolean automaticTransitionFromOpenToHalfOpenEnabled = true;

        /**
         * 转换成 Resilience4j 的配置对象。
         *
         * <p>放在这里而不是 {@code ChatChainConfig} 里，是为了让
         * 「配置项 → 框架对象」的映射关系跟字段挨着，
         * 加新参数时不会漏掉这一处。
         */
        public CircuitBreakerConfig toResilienceConfig() {
            return CircuitBreakerConfig.custom()
                    .slidingWindowType("TIME_BASED".equalsIgnoreCase(slidingWindowType)
                            ? CircuitBreakerConfig.SlidingWindowType.TIME_BASED
                            : CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(slidingWindowSize)
                    .minimumNumberOfCalls(minimumNumberOfCalls)
                    .failureRateThreshold(failureRateThreshold)
                    .slowCallDurationThreshold(slowCallDurationThreshold)
                    .slowCallRateThreshold(slowCallRateThreshold)
                    .waitDurationInOpenState(waitDurationInOpenState)
                    .permittedNumberOfCallsInHalfOpenState(permittedNumberOfCallsInHalfOpenState)
                    .automaticTransitionFromOpenToHalfOpenEnabled(
                            automaticTransitionFromOpenToHalfOpenEnabled)
                    .build();
        }
    }

    // ============================================================
    // 向量化 / 重排序
    // ============================================================

    /**
     * 向量化配置。
     *
     * <p>★ <b>只有硅基流动提供向量化，DeepSeek 官方没有</b>——
     * 这是本项目采用双供应商架构的根本原因。
     */
    @Data
    public static class Embedding {

        /** 引用 {@link #providers} 的 key */
        private String provider = "siliconflow";

        /** 真实模型 ID */
        private String modelId = "BAAI/bge-m3";

        /**
         * 向量维度。
         *
         * <p>★ 1024 是<b>全项目的向量维度基准</b>（CLAUDE.md 第 8 条），
         * 与 {@code kb_chunk.embedding} 列的 {@code vector(1024)} 建表一致，
         * 已实测确认 bge-m3 的实际输出就是 1024 维。
         *
         * <p>改这个值必须<b>同时改表结构</b>并重建 HNSW 索引，不是纯配置改动。
         */
        private int dimension = 1024;
    }

    /**
     * 重排序配置。
     *
     * <p>和向量化一样，只有硅基流动提供。
     */
    @Data
    public static class Rerank {

        /** 引用 {@link #providers} 的 key */
        private String provider = "siliconflow";

        /** 真实模型 ID */
        private String modelId = "BAAI/bge-reranker-v2-m3";
    }
}
