package com.xbla.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置。
 *
 * <p>阶段 2 只服务一件事：<b>SSE 流式响应的推送线程</b>。
 *
 * <h3>为什么必须有这个类（而不是随手用 CompletableFuture）</h3>
 *
 * <p>SSE 的推送是<b>阻塞式</b>的：我们要在一个后台线程里，
 * 一边阻塞读取上游模型的 HTTP 流，一边把增量写给浏览器。
 * 很自然会写成：
 * <pre>{@code
 * CompletableFuture.runAsync(() -> chatService.stream(...));   // ✗ 危险！
 * }</pre>
 *
 * <p>但 {@code runAsync} 不传 executor 时会用 <b>{@code ForkJoinPool.commonPool()}</b>。
 * 那个池的并行度是 {@code CPU 核数 - 1}（这台机器 32 核，就是 31）。
 * 只要有几个并发的流式问答把它占满，<b>整个 JVM 里所有依赖 commonPool 的东西
 * 都会一起卡死</b> —— 包括并行 Stream、包括某些第三方库的内部任务。
 *
 * <p>现象是「模型好像变慢了」，而真正的原因在完全无关的地方。
 * 这类问题排查成本极高，所以从第一天起就用专用线程池隔离。
 *
 * @see com.xbla.rag.service.impl.ChatServiceImpl 流式路径的提交方
 */
@Configuration
public class AsyncConfig {

    /**
     * SSE 流式推送专用线程池。
     *
     * <p><b>参数怎么定的</b>：
     * <ul>
     *   <li>{@code corePoolSize = 8} —— 常驻线程数。流式问答不是高频操作，
     *       8 个线程足够覆盖日常使用，不用一上来就占资源。</li>
     *   <li>{@code maxPoolSize = 32} —— 峰值上限。注意线程池的增长逻辑：
     *       <b>核心线程满了之后先入队，队列满了才扩容到 maxPoolSize</b>。
     *       所以真实的并发上限是 {@code 32 个执行中 + 16 个排队}。</li>
     *   <li>{@code queueCapacity = 16} —— ★ 这个值<b>故意设得很小</b>。
     *       对普通接口，队列大一点能削峰；但 SSE 不同：
     *       排队的请求在用户那边就是「一个空白页面」，没有任何反馈。
     *       与其让用户干等，不如快速拒绝并给出明确错误。
     *       真正需要排队时，阶段 6 会用 Redis 做排队队列 + SSE 推送排队位置，
     *       那是「可见的等待」，体验完全不同。</li>
     *   <li>{@code AbortPolicy} —— 队列满时直接抛 {@code TaskRejectedException}，
     *       由调用方捕获后给前端发一个 {@code failed} 事件。
     *       不用 {@code CallerRunsPolicy}：那会让 Tomcat 的请求线程去跑阻塞任务，
     *       反而把 Web 容器自己的线程池拖垮。</li>
     * </ul>
     *
     * <p><b>为什么不用虚拟线程？</b>
     * JDK 21 的虚拟线程确实非常适合这种「大量阻塞 I/O」的场景，
     * 代码上只要换一行 {@code Executors.newVirtualThreadPerTaskExecutor()}。
     * 但虚拟线程是<b>无界</b>的 —— 没有队列、没有上限，
     * 也就没有任何背压。在阶段 6 的 Redis 分布式限流到位之前，
     * 一个有界的平台线程池是更稳妥的选择：它会主动拒绝，而不是默默堆积。
     *
     * <p>这个取舍已记录在 {@code docs/08-技术决策记录(ADR).md}。
     */
    @Bean("sseExecutor")
    public ThreadPoolTaskExecutor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("sse-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        // 关闭时等待正在跑的流式任务结束，避免把用户晾在半截
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }
}
