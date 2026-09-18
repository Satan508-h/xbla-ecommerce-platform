package com.xbla.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.client.ChatModelRouter;
import com.xbla.rag.client.LlmClient;
import com.xbla.rag.client.ModelCostCalculator;
import com.xbla.rag.client.OpenAiCompatibleLlmClient;
import com.xbla.rag.client.OpenAiHttpTransport;
import com.xbla.rag.client.dto.ModelDescriptor;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code xbla.llm} 的三层配置装配成运行期对象。
 *
 * <p>产出三样东西：熔断器注册表、有序列的模型客户端、降级路由器。
 * 外加一次<b>启动期校验</b>（见 {@link #buildChain}）。
 *
 * @see LlmProperties 配置结构
 */
@Slf4j
@Configuration
public class ChatChainConfig {

    /**
     * 熔断器注册表。
     *
     * <h3>★★ 为什么自己 {@code @Bean} 一个，而不用 Resilience4j 的自动配置？</h3>
     *
     * <p>因为 {@code resilience4j-spring-boot3} 的自动配置类
     * {@code AbstractCircuitBreakerConfigurationOnMissingBean} 上挂着：
     * <pre>
     * &#64;Condition(AspectJOnClasspathCondition.class)
     * </pre>
     * 也就是「类路径上得有 AspectJ 才装配」。
     *
     * <p>问题在于这个注解挂在<b>父类</b>上，而 {@code @Conditional} 不是
     * {@code @Inherited} 的 —— 它到底生不生效，取决于 Spring 处理元数据注解继承的
     * 具体策略。这种「不确定性」本身就是风险：一旦不生效，
     * 表现为 <b>{@code CircuitBreakerRegistry} Bean 不存在</b>，
     * 而日志里只有一行 WARN 说「Aspects are not activated」。
     *
     * <p>配置全都写对了、依赖也加了，Bean 就是没有 —— 这类静默失败排查成本极高。
     * 所以这里干脆自己建一个：<b>配置源统一来自 {@code xbla.llm}，
     * 不依赖任何条件装配</b>。
     *
     * <p>自动配置那边有 {@code @ConditionalOnMissingBean}，看到我们已经提供了
     * 就会自动退让，不会冲突。而 {@code /actuator/circuitbreakers}
     * 端点并不关心 registry 是谁建的，照样能列出所有实例。
     *
     * <p>（{@code pom.xml} 里仍然引了 {@code spring-boot-starter-aop} 作为保险，
     * 也为了让熔断器的健康指标能挂进 {@code /actuator/health}。）
     *
     * @param props 读取 {@code xbla.llm.defaults.circuit-breaker.*}
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(LlmProperties props) {
        CircuitBreakerConfig config = props.getDefaults().getCircuitBreaker().toResilienceConfig();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);

        // ★ 预热：为链路里每个条目立刻创建一个熔断器。
        //   不预热的话，熔断器是「第一次用到时才创建」的，
        //   启动后马上去看 /actuator/circuitbreakers 会是一片空白，
        //   让人误以为配置没生效。预热之后一启动就能看到三个 CLOSED。
        props.getChatChain().forEach(registry::circuitBreaker);

        log.info("熔断器注册表已建立，共 {} 个实例：{}",
                props.getChatChain().size(), props.getChatChain());
        return registry;
    }

    /**
     * 降级路由器 —— 本阶段的核心组件。
     */
    @Bean
    public ChatModelRouter chatModelRouter(LlmProperties props,
                                           CircuitBreakerRegistry registry,
                                           OpenAiHttpTransport transport,
                                           @Qualifier("modelObjectMapper") ObjectMapper mapper,
                                           ModelCostCalculator costCalculator) {
        List<LlmClient> chain = buildChain(props, transport, mapper);

        // Map.copyOf 会丢掉顺序，这里用 LinkedHashMap 保持链路顺序 ——
        // 虽然查找是按 key，但打印出来时顺序一致更好读
        Map<String, LlmClient> byKey = new java.util.LinkedHashMap<>();
        chain.forEach(c -> byKey.put(c.modelKey(), c));

        log.info("对话降级链已装配：{}", chain.stream()
                .map(c -> "%s(%s/%s)".formatted(c.modelKey(),
                        c.descriptor().provider(), c.descriptor().modelId()))
                .toList());

        return new ChatModelRouter(chain, byKey, registry, costCalculator);
    }

    /**
     * 按配置顺序装配客户端列表，并做<b>启动期校验</b>。
     *
     * <h3>为什么要校验，而不是让它运行时才炸</h3>
     *
     * <p>配置里写错一个模型名（比如把 {@code deepseek-v4-flash} 拼成
     * {@code deepseek-v4flash}），运行时会被当成「这家供应商挂了」，
     * 触发降级 —— 于是请求还是成功了，只是莫名其妙慢了一档。
     * 这种问题在日志里看起来像是网络抖动，能藏很久。
     *
     * <p>启动时直接失败，比运行时静默降级好得多。
     *
     * <h3>★ 为什么 api-key 为空只警告、不阻止启动</h3>
     *
     * <p>因为阶段 2 的验收标准第 2 条是
     * <b>「故意把 P0 的 API Key 改错，确认自动降级到 P1」</b>。
     * 如果 key 为空就直接启动失败，这条验收根本没法做。
     *
     * <p>把 key 的问题留给运行时（401 → 降级），
     * 而把结构性错误（引用了不存在的模型/供应商）留在启动期 ——
     * 这个分界线是刻意划的。
     */
    private List<LlmClient> buildChain(LlmProperties props,
                                       OpenAiHttpTransport transport,
                                       ObjectMapper mapper) {
        List<String> chainKeys = props.getChatChain();
        if (chainKeys == null || chainKeys.isEmpty()) {
            throw new IllegalStateException(
                    "xbla.llm.chat-chain 不能为空 —— 至少要有一个可用的模型");
        }

        List<LlmClient> chain = new ArrayList<>(chainKeys.size());
        List<String> missingKeys = new ArrayList<>();

        for (String key : chainKeys) {
            LlmProperties.Model model = props.getModels().get(key);

            // 校验 ①：链路引用的模型必须存在
            if (model == null) {
                missingKeys.add(key);
                continue;
            }

            // 校验 ②：模型引用的供应商必须存在
            LlmProperties.Provider provider = props.getProviders().get(model.getProvider());
            if (provider == null) {
                throw new IllegalStateException(
                        "模型 '%s' 引用了不存在的供应商 '%s'，可用的供应商：%s"
                                .formatted(key, model.getProvider(), props.getProviders().keySet()));
            }

            // 校验 ③：域名必须有，且不能带 /v1 后缀或尾部斜杠
            String baseUrl = provider.getBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException(
                        "供应商 '%s' 的 base-url 未配置".formatted(model.getProvider()));
            }
            if (baseUrl.endsWith("/")) {
                throw new IllegalStateException(
                        ("供应商 '%s' 的 base-url 不能以斜杠结尾（当前 '%s'）—— " +
                                "代码会自动拼上 /v1，否则会变成双斜杠")
                                .formatted(model.getProvider(), baseUrl));
            }
            if (baseUrl.endsWith("/v1")) {
                throw new IllegalStateException(
                        ("供应商 '%s' 的 base-url 不要带 /v1（当前 '%s'）—— " +
                                "版本号由代码统一拼接，配在配置里会导致 /v1/v1/...")
                                .formatted(model.getProvider(), baseUrl));
            }

            // 校验 ④：模型 ID 必须有
            if (model.getModelId() == null || model.getModelId().isBlank()) {
                throw new IllegalStateException(
                        "模型 '%s' 的 model-id 未配置".formatted(key));
            }

            // ★ 校验 ⑤：api-key 为空【只警告，不阻止启动】——
            //   见方法注释里的理由（验收标准 2 需要 key 是错的但应用能起来）
            if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
                log.warn("供应商 '{}' 的 api-key 为空（模型 '{}'）—— " +
                                "运行时调用会返回 401 并触发降级。" +
                                "请检查 application-local.yml 或环境变量",
                        model.getProvider(), key);
            }

            ModelDescriptor descriptor = new ModelDescriptor(
                    key, model.getProvider(), model.getModelId(), model.getPricing());

            OpenAiHttpTransport.Endpoint endpoint = new OpenAiHttpTransport.Endpoint(
                    model.getProvider(), baseUrl, provider.getApiKey());

            chain.add(new OpenAiCompatibleLlmClient(
                    transport, mapper, descriptor, endpoint,
                    props.getDefaults(), provider.isStreamIncludeUsage()));
        }

        if (!missingKeys.isEmpty()) {
            throw new IllegalStateException(
                    "xbla.llm.chat-chain 引用了不存在的模型：%s，可用的模型：%s"
                            .formatted(missingKeys, props.getModels().keySet()));
        }

        return chain;
    }
}
