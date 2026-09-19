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

    /**
     * 检索链路专用线程池（阶段 4 新增）。
     *
     * <p><b>解决什么问题</b>：一次检索要并行跑两路召回
     * （向量路 = 一次向量化 HTTP 调用 + 一次数据库查询；关键词路 = 一次数据库查询）。
     * 串行跑就是两次网络往返相加，并行跑取两者的最大值。
     * 而这两路都是<b>阻塞</b>操作，必须有线程承接。
     *
     * <h3>★ 为什么不用 {@code CompletableFuture.supplyAsync} 的默认池</h3>
     *
     * <p>和 {@link #sseExecutor} 同一条理由：不传 executor 会落到
     * {@code ForkJoinPool.commonPool()}，并行度只有核数-1，
     * 几个并发的阻塞调用就能让整个 JVM 卡死。
     *
     * <h3>★ 为什么不用虚拟线程</h3>
     *
     * <p>理由和 {@link #sseExecutor} 完全一致：虚拟线程是<b>无界</b>的，
     * 没有队列、没有上限，也就没有任何背压。在阶段 6 的 Redis 分布式限流到位之前，
     * 有界的平台线程池是更稳妥的选择。
     *
     * <h3>★ 参数怎么定的 —— max 的真正上限不是线程数，是连接池</h3>
     *
     * <ul>
     *   <li>{@code corePoolSize = 4} —— 稳态约 2 个并发问答 × 2 路 = 4。
     *       和 {@code ingestExecutor} 的 core=2 同源（都在等外部 HTTP），
     *       但检索是<b>交互式</b>的：排队直接等于用户多等，所以给 2 倍。</li>
     *
     *   <li>{@code maxPoolSize = 16} —— ★ <b>这个值不能随便调大。</b>
     *       真正的瓶颈在后面：{@code application.yml} 里
     *       {@code spring.datasource.hikari.maximum-pool-size: 10}。
     *       两路召回都要查数据库，16 个并发检索任务会同时抢 10 个连接，
     *       抢不到的会卡在 {@code connection-timeout: 30000}（<b>30 秒</b>）上。
     *       那比我们自己毫秒级地拒绝要糟得多 —— 用户会看到「检索卡了 30 秒然后失败」。
     *       所以 max 的实际约束是「连接数的 1.5~2 倍」，不是「想要多少就多少」。</li>
     *
     *   <li>{@code queueCapacity = 64} —— 介于 sseExecutor(16) 和 ingestExecutor(200) 之间。
     *       检索阻塞用户，队列不能太大；但一路召回可能只是几百毫秒，
     *       也不能小到动不动就拒绝。64 格约等于 32 个并发请求的全部 fan-out。</li>
     *
     *   <li>{@code AbortPolicy} —— 调用方是 Tomcat 请求线程（非流式路径）
     *       或 {@code sse-} 线程（流式路径）。
     *       <b>不能用 CallerRunsPolicy</b>：那会让它们去跑阻塞的向量化调用 ——
     *       正是 {@link #sseExecutor} 的注释里明确否决过的做法。</li>
     *
     *   <li>{@code awaitTerminationSeconds = 5} —— ★ 和 {@code ingestExecutor}
     *       的 60 秒<b>刻意相反</b>。检索是<b>只读、幂等、可重跑</b>的，
     *       掐掉就掐掉了，用户重发一次就行；而入库是<b>非幂等</b>的（写一半会留僵尸文档）。
     *       而且用户本来就在等检索结果，让他早点拿到错误比多等 55 秒更好。</li>
     * </ul>
     *
     * <p>四个池至此各自自洽：
     * <table border="1">
     *   <caption>四个线程池的取值逻辑对比</caption>
     *   <tr><th>池</th><th>队列</th><th>拒绝策略</th><th>关闭等待</th><th>一句话理由</th></tr>
     *   <tr><td>{@code sse-}</td><td>16</td><td>Abort</td><td>30s</td>
     *       <td>用户在盯着，宁可快速报错</td></tr>
     *   <tr><td>{@code ingest-}</td><td>200</td><td>CallerRuns</td><td>60s</td>
     *       <td>后台非幂等，宁可慢不可丢</td></tr>
     *   <tr><td>{@code retrieve-}</td><td>64</td><td>Abort</td><td>5s</td>
     *       <td>只读幂等，且调用方就是用户线程</td></tr>
     *   <tr><td>{@code memory-}</td><td>256</td><td>Abort</td><td>10s</td>
     *       <td><b>单线程</b>，且可丢可重做</td></tr>
     * </table>
     */
    @Bean("retrieveExecutor")
    public ThreadPoolTaskExecutor retrieveExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("retrieve-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);

        executor.initialize();
        return executor;
    }

    /**
     * 会话摘要压缩专用线程池（阶段 5.6）。
     *
     * <h3>★ 为什么是【单线程】—— 这是它唯一重要的参数</h3>
     *
     * <p>不是保守，是<b>正确性要求</b>。
     *
     * <p>压缩的一次执行是「读游标 → 调模型 → CAS 推进游标」。
     * 同一个会话如果有两个这样的执行重叠，两个都会基于<b>同一个旧游标</b>
     * 去调模型，然后一个 CAS 成功、一个失败 —— 失败的那个白花一次
     * LLM 调用（钱真的花了）。单线程让同一会话的任务天然串行，
     * 这个竞态根本不会出现。
     *
     * <p>代价是<b>跨会话也被串行化了</b>。当前规模（单机、低并发）完全可以接受：
     * 一次压缩 1–2 秒，而它<b>只有长会话（&gt; 10 轮）才触发</b>，
     * 真实数据里绝大多数会话根本到不了那个长度。
     * 真要并行，第一步是按 {@code sessionId} 哈希分片，而不是简单调大 core。
     *
     * <h3>其余参数</h3>
     * <ul>
     *   <li>{@code corePoolSize = 1} / {@code maxPoolSize = 1} —— 见上。
     *       ★ 注意线程池的增长逻辑：<b>核心线程满了先入队，队列满了才扩容</b>。
     *       所以光把 core 设成 1 是<b>不够</b>的 —— max 也必须锁死，
     *       否则队列一满它就会扩容，单线程的保证就没了。</li>
     *
     *   <li>{@code queueCapacity = 256} —— 比其它三个池都大。
     *       因为任务的<b>价值密度很低</b>：它只影响「很久以前的记忆」，
     *       排队几秒毫无感觉。宁可排队，也不要拒绝。</li>
     *
     *   <li>{@code AbortPolicy} —— 仍然不用 {@code CallerRunsPolicy}：
     *       调用方是 {@code sse-} 线程或 Tomcat 请求线程，
     *       让它们去跑一次 1–2 秒的 LLM 调用正是被反复否决的做法。
     *       队列满时抛出的异常由 {@code SessionSummarizer} 自己吞掉 ——
     *       <b>它敢丢任务，因为压缩是自愈的</b>：游标不动，下次连这段一起压。</li>
     *
     *   <li>{@code awaitTerminationSeconds = 10} —— 介于 {@code retrieve-}(5s)
     *       和 {@code ingest-}(60s) 之间。比检索长：压缩是<b>即将完成的</b>
     *       一次外部调用，掐掉的话这次的钱白花了；比入库短得多：它可重做。</li>
     * </ul>
     *
     * @see com.xbla.rag.agent.memory.SessionSummarizer
     */
    @Bean("memoryExecutor")
    public ThreadPoolTaskExecutor memoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("memory-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);

        executor.initialize();
        return executor;
    }
}
