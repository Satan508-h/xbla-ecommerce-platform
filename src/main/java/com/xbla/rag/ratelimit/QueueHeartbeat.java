package com.xbla.rag.ratelimit;

import com.xbla.rag.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 看门狗 —— 定期给本 JVM 持有的名额续期，并清扫僵尸（阶段 6.6）。
 *
 * <h2>它凭什么能让「名额不永久泄漏」</h2>
 *
 * <p>续期不是「让名额活得久一点」的优化，它是<b>整个失效检测机制本身</b>：
 *
 * <pre>
 *   进程活着  →  每 15 秒把 score 推到 now + 45s  →  永远不会被判定过期
 *   进程被杀  →  没人推了                        →  45 秒后 score 过期 → 被扫掉
 * </pre>
 *
 * <p>★ 关键在于「<b>谁</b>该被续期」这个信息<b>只存在于进程自己的内存里</b>
 * （{@link LocalPermitRegistry}）。Redis 那边只知道存了哪些 traceId，
 * 不知道哪些进程还活着。所以「进程死了」这件事不需要任何人去检测 ——
 * 它表现为<b>沉默</b>，而沉默的后果就是过期。
 * <b>验收标准 3 成立的原因就是：这里没有任何一行代码需要被执行。</b>
 *
 * <h2>★ 为什么「续期」和「清扫」放在同一个方法里，而且顺序不能反</h2>
 *
 * <p>先续期、后清扫，这个顺序缩小了一个窗口：一个<b>活着但续期稍微晚了</b>的名额
 * （上一次心跳失败过一次、或 GC 停顿了一下），如果先被清扫掉，
 * 在「清扫」和「续期」之间它就真的不在名额表里了 ——
 * 而那个窗口里另一个请求可以合法地抢走它，于是两个请求同时在跑（超卖）。
 *
 * <p>⚠️ <b>诚实说明：这个顺序在单次执行的【最终状态】上看不出区别</b>
 * （先扫后补和先补后扫，结果都是「它还在」）。它减少的是<b>中间态</b>的窗口长度，
 * 而中间态是并发才能观察到的。所以<b>没有测试能钉住它</b> ——
 * 这里写下理由，而不是假装有个断言证明了它。
 *
 * <h2>★ 为什么用 SchedulingConfigurer，而不是 @Scheduled(fixedDelayString = ...)</h2>
 *
 * <p>因为间隔要<b>从 {@code permit-ttl} 派生</b>（取三分之一），而
 * {@code @Scheduled} 的 SpEL 引用 Bean 需要知道 Bean 名 ——
 * 而 {@code @ConfigurationPropertiesScan} 注册出来的名字是
 * {@code "xbla.ratelimit-com.xbla.rag.config.RateLimitProperties"} 这种拼接形式，
 * 不是 {@code "rateLimitProperties"}。用 SpEL 引用它会得到一个<b>启动即崩</b>的
 * 表达式错误，而且错误信息指向 Bean 名解析，不指向「你写了个想当然的名字」。
 *
 * <p>另一条路是「把间隔也做成一个配置项」，但那样
 * {@code permit-ttl} 和 {@code heartbeat-interval} 就成了两个独立的值，
 * 谁改了其中一个都会破坏「间隔 ≤ 租期/3」这条关系 —— 而破坏它的后果
 * 正是上面那个超卖窗口。<b>派生是唯一不会漂移的写法。</b>
 *
 * <h2>★ 为什么线程池锁死 1 个线程</h2>
 *
 * <p>见 {@code AsyncConfig} 里 {@code memoryExecutor} 那段的理由：
 * 心跳是「读本地集合 → 发一批 Redis 命令」的短任务，
 * 一次跑几十毫秒，本来就是串行的语义。开更多线程只会让
 * 「哪次心跳先跑」「会不会重叠」变成需要推理的问题。
 * {@code setPoolSize(1)} 让这些根本不成为问题。
 */
@Component
@ConditionalOnProperty(name = "xbla.ratelimit.enabled", havingValue = "true", matchIfMissing = true)
public class QueueHeartbeat implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(QueueHeartbeat.class);

    /**
     * 心跳间隔 = 租期的三分之一。
     *
     * <p>★ 三分之一是租约设计的通行取值，理由是<b>容忍两次连续失联</b>：
     * 间隔 t、租期 3t，那么连着两次心跳失败（GC 停顿、Redis 抖动、
     * 网络重传）之后租期还剩 t，不会误判。
     *
     * <p>取二分之一只能容忍一次；取四分之一虽然更安全，
     * 但心跳频率和数据量都上去了，而收益是边际的。
     */
    private static final int TTL_DIVISOR = 3;

    private final ChatPermitService permits;
    private final RateLimitProperties props;

    public QueueHeartbeat(ChatPermitService permits, RateLimitProperties props) {
        this.permits = permits;
        this.props = props;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        Duration interval = interval();

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        // ★ 线程名是它在 jstack / 线程转储里唯一的身份。
        //   叫 pool-1-thread-1 的话，排查时没人知道它是谁。
        scheduler.setThreadNamePrefix("ratelimit-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.initialize();

        registrar.setTaskScheduler(scheduler);
        registrar.addFixedDelayTask(this::beat, interval);

        log.info("排队限流心跳已注册：每 {} 续期一次，租期 {}", interval, props.getPermitTtl());
    }

    /**
     * 心跳间隔 —— <b>从租期派生，不是独立配置项</b>。
     *
     * <p>★ 单独暴露成方法是为了能被测试直接断言，
     * 而不是让测试去读一个「配置里写着 15s」的值 ——
     * 那样测的是「配置写对了没」，而不是「派生关系对不对」。
     */
    Duration interval() {
        return props.getPermitTtl().dividedBy(TTL_DIVISOR);
    }

    /**
     * 一次心跳：先续期，再清扫。
     *
     * <p>★ 异常必须在这里被吞掉。<b>定时任务抛出的异常不会让任务停下来</b>
     * （Spring 会记一条 ERROR 然后按 fixedDelay 继续排下一次），
     * 但它会刷一大堆堆栈 —— 而 Redis 短暂不可用是<b>预期内</b>的情况，
     * 每次都打整栈会让日志里真正的问题被淹没。
     *
     * <p>⚠️ 吞掉不等于忽略：续期失败的计数在
     * {@link ChatPermitService#heartbeatFailures()} 里，它会出现在
     * {@code /api/debug/ratelimit/state} 上。<b>日志会被淹没，计数器不会。</b>
     */
    void beat() {
        try {
            // ① 续期 —— 必须在清扫之前，见类注释第二节
            int renewed = permits.renewHeld();

            // ② 清扫
            List<Long> swept = permits.cleanup();
            long expiredPermits = swept.get(0);
            long deadWaiters = swept.get(1);

            // ★ 平时什么都不打（每 15 秒一条 INFO 会淹掉一切）；
            //   只有真的动了东西才留一条 DEBUG。
            if (renewed > 0 || expiredPermits > 0 || deadWaiters > 0) {
                log.debug("心跳：续期 {} 个名额，清扫 {} 个过期名额 / {} 个排队僵尸",
                        renewed, expiredPermits, deadWaiters);
            }
        } catch (Exception e) {
            // 不抛出 —— 但不静默：下一周期会重试，失败计数在服务里
            log.warn("心跳执行失败（下一个周期会重试）：{}", e.getMessage());
        }
    }
}
