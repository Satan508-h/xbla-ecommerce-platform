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

    /**
     * 文档入库专用线程池（阶段 3 新增）。
     *
     * <p><b>为什么入库要异步</b>：一份文档入库要经过
     * 「解析 → 切分 → 批量向量化 → 写库」四步。其中向量化是<b>串行调外部 API</b>，
     * 一份几十页的文档可能要几百次调用，耗时从几十秒到几分钟。
     * 放在 HTTP 请求里同步跑，浏览器早就超时了。
     * 所以上传接口立刻返回 {@code docId}，真正的活在后台线程里跑，
     * 前端轮询 {@code kb_document.status} 看进度。
     *
     * <p><b>参数和 sseExecutor 完全不同，这是有意的</b>：
     * <ul>
     *   <li>{@code corePoolSize = 2} / {@code maxPoolSize = 4} —— <b>故意开得很少</b>。
     *       入库的瓶颈不在我们的 CPU，而在<b>对方的限流阈值</b>。
     *       向量化接口有 QPS 限制，开 32 个线程不会让总吞吐变高，
     *       只会更快撞上限流（429），然后整批任务一起失败重试。
     *       并发度应该匹配下游能承受的量，而不是我们想要的量。</li>
     *   <li>{@code queueCapacity = 200} —— <b>故意设得很大</b>。
     *       和 SSE 的理由正好相反：SSE 让用户干等是坏事，
     *       但入库是后台任务，用户看不到队列，任务在队列里等着完全没问题。
     *       一个批量灌语料的任务可能有几十上百份文档排队，
     *       队列太小会导致后来的直接被拒绝 —— 而它们本来只需要多等一会儿。</li>
     *   <li>{@code CallerRunsPolicy} —— 队列也满了之后，让提交任务的线程自己跑。
     *       这是<b>背压</b>：提交方会因此变慢，从而自然地降低提交速度。
     *       对后台任务来说，「变慢」远好于 {@code AbortPolicy} 的「丢失任务」。</li>
     * </ul>
     *
     * <p><b>为什么不能复用 sseExecutor</b>：两者的取值逻辑是相反的
     * （一个要队列小、要快速拒绝；一个要队列大、要慢慢排队）。
     * 混用会让任何一边的行为变得无法解释 —— 而且入库任务把 SSE 的
     * 16 格队列占满，用户就能明显感觉到「聊天变卡了」。
     *
     * @see KbProperties.Ingest 参数在 {@code xbla.kb.ingest} 下可调
     */
    @Bean("ingestExecutor")
    public ThreadPoolTaskExecutor ingestExecutor(KbProperties kbProperties) {
        KbProperties.Ingest props = kbProperties.getIngest();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getCorePoolSize());
        executor.setMaxPoolSize(props.getMaxPoolSize());
        executor.setQueueCapacity(props.getQueueCapacity());
        executor.setThreadNamePrefix("ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // ★ 关闭时一定要等入库任务跑完。
        //   入库是【非幂等】的：跑到一半被杀，数据库里会留下
        //   status=2（处理中）的僵尸文档，而且已经写进去的切片是有向量的 ——
        //   重跑会重复写入，不重跑那条文档就永远卡在「处理中」。
        //   所以宁可让关闭慢 60 秒，也不要把任务腰斩
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();
        return executor;
    }
}
