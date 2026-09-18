package com.xbla.rag.client;

import com.xbla.rag.client.dto.ChatRequest;
import com.xbla.rag.client.dto.ChatResponse;
import com.xbla.rag.client.dto.ModelDescriptor;
import com.xbla.rag.client.dto.StreamResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 降级链路由器 —— <b>本阶段最核心的一个类</b>。
 *
 * <p>它按 P0 → P1 → P2 的顺序依次尝试，任何一个成功就返回；
 * 全部失败才抛异常。每次降级都会记录进 {@link ModelCallTrace}，
 * 最终由 service 写进 {@code qa_log.degradation_events}。
 *
 * <h3>三个关键设计</h3>
 *
 * <p><b>① 用 Resilience4j 的手动 API，不用 {@code executeSupplier}</b>
 *
 * <p>{@code executeSupplier} 会把「熔断器拒绝」也包装成一个异常，
 * 于是日志里分不清「这一家真的调用失败了」和「熔断器根本没让它出去」——
 * 而这两种情况对排查的含义完全不同。手动 API（{@code tryAcquirePermission} /
 * {@code onSuccess} / {@code onError}）把状态机的每一步摊在代码里，
 * 符合项目「可逐行解释」的要求。
 *
 * <p><b>② 熔断器的耗时口径用 TTFB，不用整个流的时长</b>
 *
 * <p>见 {@link StreamResult#breakerObservedMs()}。一句话：
 * 用整个流时长会让一次 60 秒的<b>正常</b>长回答被判定为慢调用，
 * 导致熔断器在系统最健康的时候跳闸。
 *
 * <p><b>③ 流式降级有硬边界：吐过字就不能降级</b>
 *
 * <p>这是 SSE 架构的固有约束，不是可以优化掉的东西。
 * 详见 {@link ModelErrorKind#PARTIAL_STREAM}。
 */
@Slf4j
public class ChatModelRouter {

    private final List<LlmClient> chain;
    private final Map<String, LlmClient> byKey;
    private final CircuitBreakerRegistry registry;
    private final ModelCostCalculator costCalculator;

    public ChatModelRouter(List<LlmClient> chain,
                           Map<String, LlmClient> byKey,
                           CircuitBreakerRegistry registry,
                           ModelCostCalculator costCalculator) {
        this.chain = List.copyOf(chain);
        this.byKey = Map.copyOf(byKey);
        this.registry = registry;
        this.costCalculator = costCalculator;
    }

    // ============================================================
    // 非流式
    // ============================================================

    /**
     * 按降级链顺序调用，返回第一个成功的结果。
     *
     * @param request 对话请求
     * @param trace   轨迹收集器，由调用方创建，本方法往里填
     * @throws ModelCallException 全链路失败，或遇到不可降级的错误（如 400）
     */
    public ChatResponse chat(ChatRequest request, ModelCallTrace trace) {
        ModelCallException lastError = null;

        for (int i = 0; i < chain.size(); i++) {
            LlmClient client = chain.get(i);
            String nextKey = nextKey(i);
            CircuitBreaker breaker = breakerFor(client);

            // ① 熔断器准入
            if (!breaker.tryAcquirePermission()) {
                // 跳闸状态：这一档连试都不试，直接跳过去。
                // ★ 和「试了但失败」区分开记录 —— 前者说明这家已经彻底不可用，
                //   后者说明它只是在抽风。两种结论的运维动作完全不同。
                log.debug("熔断器已跳闸，跳过 modelKey={}", client.modelKey());
                trace.circuitOpen(client.modelKey(), nextKey);
                continue;
            }

            long start = System.nanoTime();
            try {
                ChatResponse response = client.chat(request);
                long elapsedNanos = System.nanoTime() - start;

                // ② 成功
                breaker.onSuccess(elapsedNanos, TimeUnit.NANOSECONDS);
                trace.succeeded(client.descriptor(), response.usage(), response.latencyMs());

                finishTrace(trace, client);

                if (trace.degraded()) {
                    log.warn("对话已降级完成：{}", trace.summary());
                }
                return response;

            } catch (Exception e) {
                long elapsedNanos = System.nanoTime() - start;
                ModelCallException mce = ModelCallException.of(
                        e, client.descriptor().provider(), client.modelKey());

                recordFailure(breaker, mce, elapsedNanos, client);

                // ③ 不可降级的错误 —— 直接抛，不要浪费下一个供应商的配额
                if (!mce.kind().isFallbackWorthy()) {
                    log.warn("遇到不可降级的错误，终止链路：{}", mce.getMessage());
                    throw mce;
                }

                log.warn("模型调用失败，准备降级：{}", mce.getMessage());
                trace.degrade(client.modelKey(), nextKey, mce.kind().reason());
                lastError = mce;
            }
        }

        throw ModelCallException.allFailed(trace.summary(), lastError);
    }

    // ============================================================
    // 流式
    // ============================================================

    /**
     * 按降级链顺序做流式调用。
     *
     * <p>正文通过 {@code onDelta} 逐段推给调用方。
     *
     * <p><b>★ 降级只可能发生在「一个字节都没推给用户」之前。</b>
     * 一旦 {@code onDelta} 被调用过，后续的失败会被归类为
     * {@link ModelErrorKind#PARTIAL_STREAM}（不可降级），
     * 直接抛出 —— 换一家从头重来会让用户看到两段拼不上的话。
     *
     * @param request 对话请求
     * @param trace   轨迹收集器
     * @param onDelta 正文增量回调
     * @throws ModelCallException 全链路失败，或吐字后中断
     */
    public StreamResult chatStream(ChatRequest request, ModelCallTrace trace,
                                   Consumer<String> onDelta) {
        ModelCallException lastError = null;

        for (int i = 0; i < chain.size(); i++) {
            LlmClient client = chain.get(i);
            String nextKey = nextKey(i);
            CircuitBreaker breaker = breakerFor(client);

            if (!breaker.tryAcquirePermission()) {
                log.debug("熔断器已跳闸，跳过 modelKey={}", client.modelKey());
                trace.circuitOpen(client.modelKey(), nextKey);
                continue;
            }

            long start = System.nanoTime();
            try {
                StreamResult result = client.chatStream(request, onDelta);

                // ★ 用 TTFB 喂熔断器，不是整个流的时长 —— 见类注释
                breaker.onSuccess(result.breakerObservedMs(), TimeUnit.MILLISECONDS);

                trace.succeeded(client.descriptor(), result.usage(), result.totalMs());
                finishTrace(trace, client);

                if (trace.degraded()) {
                    log.warn("流式对话已降级完成：{}", trace.summary());
                }
                return result;

            } catch (Exception e) {
                long elapsedNanos = System.nanoTime() - start;
                ModelCallException mce = ModelCallException.of(
                        e, client.descriptor().provider(), client.modelKey());

                recordFailure(breaker, mce, elapsedNanos, client);

                // ★ 已经吐字了（PARTIAL_STREAM）或请求本身有问题（BAD_REQUEST）
                //   —— 都不能降级，直接抛。
                //   这是流式架构的硬边界：用户已经看到部分输出，
                //   换一家重来会拼接出前后矛盾的内容。
                if (!mce.kind().isFallbackWorthy()) {
                    log.warn("流式调用遇到不可降级的错误，终止链路：{}", mce.getMessage());
                    throw mce;
                }

                log.warn("流式模型调用失败（尚未吐字），准备降级：{}", mce.getMessage());
                trace.degrade(client.modelKey(), nextKey, mce.kind().reason());
                lastError = mce;
            }
        }

        throw ModelCallException.allFailed(trace.summary(), lastError);
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /**
     * 记录失败到熔断器。
     *
     * <p>★★ 这里有一个<b>极易漏掉</b>的分支：{@code releasePermission()}。
     *
     * <p>我们先用 {@code tryAcquirePermission()} 拿到了许可，如果随后决定
     * <b>不把它算作失败</b>（{@link ModelErrorKind#BAD_REQUEST} 和
     * {@link ModelErrorKind#CLIENT_ABORTED} 这两种），就<b>必须把许可还回去</b>。
     *
     * <p>不还的后果很隐蔽：熔断器处于 HALF_OPEN 状态时，
     * 允许的探测请求数是有限的（配置里是 3 个）。许可泄漏会把这些名额占满，
     * <b>熔断器就永远卡在半开状态出不来</b> —— 明明供应商已经恢复了，
     * 却再也不会被重新启用。
     *
     * <p>这类 bug 不会报错、不会打日志，只会表现为「服务莫名其妙一直在用 P2」。
     */
    private void recordFailure(CircuitBreaker breaker, ModelCallException mce,
                               long elapsedNanos, LlmClient client) {
        if (mce.kind().countsAsBreakerFailure()) {
            breaker.onError(elapsedNanos, TimeUnit.NANOSECONDS, mce);
        } else {
            // 拿到了许可但不算失败 —— 必须归还，否则 HALF_OPEN 的名额会泄漏
            breaker.releasePermission();
            log.debug("该失败不计入熔断统计（{}），已归还许可 modelKey={}",
                    mce.kind().reason(), client.modelKey());
        }
    }

    /**
     * 收尾：把成本和最终线路记进轨迹。
     *
     * <p>成本拿不到用量时会是 null，落库时存 NULL —— 不估算。
     */
    private void finishTrace(ModelCallTrace trace, LlmClient client) {
        trace.cost(costCalculator.calculate(client.descriptor(), trace.usage()));
    }

    /** 下一个链路条目的名字；已经是最后一档时返回 null */
    private String nextKey(int currentIndex) {
        int next = currentIndex + 1;
        return next < chain.size() ? chain.get(next).modelKey() : null;
    }

    private CircuitBreaker breakerFor(LlmClient client) {
        return registry.circuitBreaker(client.modelKey());
    }

    // ============================================================
    // 供探针与运维使用
    // ============================================================

    /** 按链路顺序返回所有客户端 */
    public List<LlmClient> chain() {
        return chain;
    }

    /** 按链路条目名取客户端。不存在时返回 null */
    public LlmClient byModelKey(String modelKey) {
        return byKey.get(modelKey);
    }

    /**
     * P0 是否是默认首选（即链路非空）。
     *
     * <p>给健康检查用的轻量判断。
     */
    public boolean isEmpty() {
        return chain.isEmpty();
    }

    /** 链路摘要，用于日志和探针接口 */
    public List<String> describeChain() {
        return chain.stream()
                .map(c -> {
                    ModelDescriptor d = c.descriptor();
                    CircuitBreaker cb = breakerFor(c);
                    return "%s[%s/%s] breaker=%s".formatted(
                            c.modelKey(), d.provider(), d.modelId(), cb.getState());
                })
                .toList();
    }
}
