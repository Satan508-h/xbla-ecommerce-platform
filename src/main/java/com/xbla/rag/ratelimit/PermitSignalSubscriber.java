package com.xbla.rag.ratelimit;

import com.xbla.rag.config.RateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 订阅「有位置空出来了」这个频道，把它变成 {@link PermitSignalBus#signal()}（阶段 6.4）。
 *
 * <h2>一个公共频道，不是每请求一个频道</h2>
 *
 * <p>更直觉的做法是「谁在等，就给谁开一个专属频道」—— 那样能精确唤醒一个人。
 * 但它要求<b>动态增删监听器</b>，而 {@code RedisMessageListenerContainer} 的
 * add/remove 是<b>带锁</b>的（它要改内部的 channelMapping）：
 * 高并发下这个操作自己会变成瓶颈，而且漏删一个就是一个内存泄漏，
 * 症状是「跑得越久越慢」。
 *
 * <p>★ 代价是<b>本机惊群</b>：一次广播唤醒本实例的<b>所有</b>等待者，
 * 它们各跑一次 {@code acquire.lua}，只有一个能赢。100 个等待者是 100 个轻量 Lua，
 * Redis 处理它们是微秒级 —— 换来的是「<b>不可能泄漏监听器</b>」，
 * 因为根本没有按请求注册过任何东西。
 *
 * <h2>★★★ 这个类为什么存在：两个【实测出来的】事实</h2>
 *
 * <p>它一开始只打算做一件事：把容器藏起来，好在自己的线程上启动它。
 * 但把 Redis 停掉真的跑了一遍之后，发现的东西比预想的严重得多 ——
 * 于是它变成了现在这个「监督循环」。
 *
 * <h4>① 在主线程上启动它，会让【整个应用起不来】</h4>
 *
 * <pre>
 *   把 container.start() 放进构造函数（= Spring 建 bean 的主线程）：
 *
 *   Error creating bean with name 'permitSignalSubscriber':
 *     Constructor threw exception
 *   Caused by: RedisListenerExecutionFailedException:
 *     RedisConnectionFailureException: Unable to connect to Redis
 *   → Application run failed
 * </pre>
 *
 * <p>★ 而 Redis 在应用启动的那几秒里不可达，在容器化部署里<b>是常态</b>
 * （两个容器同时起，应用往往先就绪）。所以这不是「极端情况下的健壮性」，
 * 而是「换台机器就起不来」。<b>所以这个订阅绝不能挂在 Spring 的 lifecycle 上。</b>
 *
 * <h4>② 库的「自动重连」覆盖不了「从一开始就没连上」</h4>
 *
 * <p>{@code RedisMessageListenerContainer} 内置了
 * {@code FixedBackOff(retryInterval, Long.MAX_VALUE)}（无限重试），
 * 读起来像是「连不上会自己一直试」。<b>实测的结果不是：</b>
 *
 * <pre>
 *   Redis 关着启动应用        → 订阅抛异常，应用照常起来
 *   再把 Redis 启回来，等 20 秒 → PUBSUB NUMSUB xbla:rl:events 仍然是 【0】
 *                              日志里也没有第二次尝试
 * </pre>
 *
 * <p>★★★ 原因是：{@code start()} 一进门就把内部的 {@code started} 置成了 true，
 * <b>然后才</b>去做订阅。订阅抛异常时那个标志<b>没有被退回去</b> ——
 * 于是之后每一次 {@code start()} 都会在
 * {@code started.compareAndSet(false, true)} 那里直接短路成空操作，
 * 而 {@code isRunning()} 还一直回答 true。
 *
 * <p>换句话说：<b>订阅永久死亡，而容器认为一切正常，没有任何东西会告诉你。</b>
 * 这正是本项目在 {@code PermitSignalWiringTest} 里专门写了一条测试去守的
 * 那类失效 ——「Pub/Sub 其实没在工作」不会表现为错误，只表现为变慢。
 *
 * <h4>③ 所以：重试这件事由我们自己负责</h4>
 *
 * <p>下面这个循环是一个<b>监督者</b>，不是「启动一次就结束」的初始化：
 *
 * <pre>
 *   每 retryInterval 醒一次
 *     ├─ 订阅活着  → 什么都不做（再看一眼，继续睡）
 *     └─ 订阅没了  → ★ 建一个【全新的】容器去订，成功就换上、旧的就停掉
 * </pre>
 *
 * <p>★ 为什么是「建新的」而不是「复位旧的再 start」：复位只能靠
 * {@code stop()}（它会把 {@code started} 退回 false），
 * 但<b>复位之后 {@code start()} 能不能成，取决于容器内部那个
 * {@code state} 状态机回到了哪一格</b> —— 而那是它的私有实现，
 * 我们既读不到也测不全。换一个新的容器<b>不需要对状态机做任何假设</b>。
 *
 * <p>★ 顺带解决了「订阅活着但中途断了」：那条路我们也走「建新的」，
 * 于是<b>不需要信任库的内部恢复机制</b>，两种「没有订阅」的原因走同一条修复路径。
 *
 * <h2>★ 为什么必须有自己的线程</h2>
 *
 * <pre>
 *   主线程              :  start() → 起线程 → 立刻返回       （不等，不置中断位）
 *   ratelimit-subscribe :  监督循环 —— 首次建连 + 之后一直盯着
 *   容器内部线程         :  维持订阅（这是它本来就有的线程）
 * </pre>
 *
 * <p>★ 守护线程：应用关闭时它跟着走，不会阻止 JVM 退出 ——
 * 一个非守护线程卡在网络上会让应用<b>关不掉</b>，那是比启动慢难查得多的问题。
 *
 * <p>★ 一句话概括取舍：<b>「Pub/Sub 是优化」这条设计原则要一直贯彻到启动期</b> ——
 * 一个优化项不该有能力让整个服务起不来，不该有能力让启动变慢，
 * 更不该在它自己坏掉的时候不吭声。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "xbla.ratelimit.wakeup.enabled",
        havingValue = "true", matchIfMissing = true)
public class PermitSignalSubscriber implements SmartLifecycle {

    /** 监督线程的名字。★ 线程名是它在 jstack 里唯一的身份 */
    private static final String SUPERVISOR_THREAD = "ratelimit-subscribe";

    private final RedisConnectionFactory connectionFactory;
    private final PermitSignalBus bus;
    private final RateLimitProperties props;

    /**
     * 当前那个「正在用的」容器 —— <b>为 null 表示还没订阅成功过</b>。
     *
     * <p>★ 它会在监督循环里被替换（旧的就停掉），所以是 {@code volatile}。
     */
    private volatile RedisMessageListenerContainer container;

    /** 本 Lifecycle 是否已启动。★ 见 {@link #isRunning()} 的说明 */
    private volatile boolean running;

    /**
     * 累计尝试订阅的次数。
     *
     * <p>★★ 这个计数器是<b>被这次踩坑逼出来的</b>：上面那个 bug 之所以能藏住，
     * 就是因为「一直没订阅上」和「订阅上过但断了」在外部看起来完全一样 ——
     * 都只是「没有信号」。
     *
     * <p>有了它，探针上就能看到：这个数一直不涨 = 我们放弃了；
     * 一直涨 = Redis 有问题而我们还在试。<b>这两句话指向完全不同的排查方向。</b>
     */
    private final AtomicLong subscriptionAttempts = new AtomicLong();

    public PermitSignalSubscriber(RedisConnectionFactory connectionFactory,
                                  PermitSignalBus bus,
                                  RateLimitProperties props) {
        this.connectionFactory = connectionFactory;
        this.bus = bus;
        this.props = props;
    }

    /**
     * 造一个<b>全新的、还没启动的</b>容器。
     *
     * <p>★ 每次重试都造一个新的，见类注释 ③ —— 这样就不必对容器内部的状态机
     * 做任何假设。
     *
     * <p>★ {@code afterPropertiesSet()} 是手动调的：它不是 Spring bean，没人替我们调。
     * 而它<b>必须被调</b>（它负责建内部的 Subscriber 和执行器）。
     * ⚠️ 它内部有 {@code Assert.state(!afterPropertiesSet)}，所以只能调一次 ——
     * 每次造新对象，也就不会有第二次。
     *
     * <p>★ 它<b>不碰 Redis</b>（只建对象），所以它快也失败不了。真正的连接发生在
     * {@code start()} 里。
     */
    private RedisMessageListenerContainer buildContainer() {
        RedisMessageListenerContainer candidate = new RedisMessageListenerContainer();
        candidate.setConnectionFactory(connectionFactory);
        candidate.setRecoveryInterval(props.getWakeup().getRetryInterval().toMillis());
        candidate.addMessageListener(new PermitSignalListener(bus),
                new ChannelTopic(props.getChannel()));

        candidate.afterPropertiesSet();
        return candidate;
    }

    /**
     * 起一个监督线程，<b>立刻返回</b>。
     *
     * <p>★ 这一行「立刻返回」正是本类存在的第一层价值：主线程绝不等订阅。
     */
    @Override
    public void start() {
        // ★★ 先置位、再起线程 —— 顺序不能反。
        //   监督循环的退出条件是 `!interrupted && running`，
        //   所以如果线程先跑起来、而 running 还是 false，它会【立刻退出】，
        //   于是订阅永远建立不起来。这个顺序曾经是反的（running = true 在 start() 之后），
        //   加那个判断时才暴露出来 —— 而它的症状会是「Pub/Sub 静默地从不工作」。
        this.running = true;

        Thread supervisor = new Thread(this::supervise, SUPERVISOR_THREAD);
        supervisor.setDaemon(true);
        supervisor.start();
    }

    /**
     * 监督循环 —— <b>订阅没了就重建，永远不放弃</b>。
     *
     * <p>★ 判定「订阅活着没有」用 {@link RedisMessageListenerContainer#isListening()}，
     * 而不是「{@code start()} 有没有抛异常」。这两者在 Redis 不通时<b>恰好相反</b>：
     * 拿「没抛异常」当成功证据，日志里会写着「订阅已建立」而实际上一直在纯轮询。
     *
     * <p>⚠️ 这个循环<b>只在关闭时才退出</b>。它不「成功一次就收工」——
     * 因为「订阅中途断了」和「一开始没连上」需要的是同一个修复动作，
     * 让它们走同一条路径，就不必去信任库的内部恢复机制。
     *
     * <h3>★★ 退出条件里的 {@code running} 是后补的，补的理由是实测出来的</h3>
     *
     * <p>原来只判 {@code isInterrupted()}，而 {@link #stop()} <b>刻意不中断</b>这个线程
     * （理由见那个方法的注释）。两者合起来的效果是：<b>{@code stop()} 之后循环仍在跑。</b>
     *
     * <p>生产环境无所谓 —— {@code stop()} 只在进程退出时被调一次。
     * 但<b>测试和任何要重复创建 Spring 上下文的场景都不行</b>：实测每个上下文关闭后
     * 都会留下一个守护线程，对着已经销毁的 {@code LettuceConnectionFactory}
     * 每 {@code retry-interval} 重试一次，日志里一路刷到
     * 「第 134 次订阅尝试失败：… was destroyed and cannot be used anymore」。
     *
     * <p>★ 修法刻意选了最弱的那个：<b>只读一个 volatile 标志，不中断线程</b>。
     * {@code stop()} 注释里否决的是「加 interrupt」—— 那条否决仍然成立，
     * 因为 interrupt 会和 {@code sleepQuietly} 抢，而读一个标志不会。
     */
    private void supervise() {
        long retryMs = props.getWakeup().getRetryInterval().toMillis();

        while (!Thread.currentThread().isInterrupted() && running) {
            if (isListening()) {
                // ★ 健康。这一支什么都不做 —— 它存在的意义只是「过一会儿再看一眼」。
                //   订阅会不会自己断，不归我们判断：断了就在这里被发现、然后被重建。
                sleepQuietly(retryMs);
                continue;
            }

            long attempt = subscriptionAttempts.incrementAndGet();
            RedisMessageListenerContainer candidate = buildContainer();
            try {
                candidate.start();
                if (candidate.isListening()) {
                    RedisMessageListenerContainer previous = this.container;
                    this.container = candidate;
                    stopQuietly(previous);
                    log.info("排队限流 Pub/Sub 订阅已建立（第 {} 次尝试）—— 释放名额会立刻唤醒本机等待者",
                            attempt);
                    continue;
                }
                // ★ 走到这里说明 start() 【正常返回了】但没在监听 —— 那是它内部的
                //   「等订阅注册完成」超时了（默认 2 秒）。不记日志的话，
                //   这就又是一次静默失败，而静默失败正是本类要消灭的东西。
                log.warn("第 {} 次订阅尝试结束，但没有进入监听状态", attempt);
            } catch (Exception e) {
                // ★ 这个 catch 就是「主线程不会被它带崩」的落点。见类注释 ①。
                log.warn("第 {} 次订阅尝试失败：{}", attempt, e.getMessage());
            }

            stopQuietly(candidate);
            sleepQuietly(retryMs);
        }
    }

    /**
     * 退订并丢弃一个容器。<b>吞掉异常</b>。
     *
     * <p>★ 调用它的两个地方都是「这个容器没用了，收拾一下」——
     * 收拾失败不该中断修复流程。而且 Redis 不通时 {@code stop()} 本来就<b>可能</b>抛
     * （它内部要退订），那是预期内的。
     */
    private static void stopQuietly(RedisMessageListenerContainer target) {
        if (target == null) {
            return;
        }
        try {
            target.stop();
        } catch (Exception e) {
            log.debug("停止一个订阅容器时出错（它本来就已经没用了）：{}", e.getMessage());
        }
    }

    /** 睡一会儿。★ 被中断就恢复中断位并让循环退出 —— 那是应用正在关闭 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 关闭时退订。
     *
     * <p>★ <b>这个方法必须存在，因为 {@code afterPropertiesSet()} 是我们手动调的</b>
     * —— 容器不是 bean，Spring 的 {@code DisposableBean} 那套自动收尾管不到它。
     * 少写这个方法不会报错，只会让订阅连接一直挂着到进程结束。
     *
     * <p>★ 由 {@code DefaultLifecycleProcessor.onClose()} 调用
     * （本类自己是 {@code SmartLifecycle} bean，而 {@link #isRunning()} 返回 true，
     * 所以它会被 stop）。{@code SmartLifecycle} 的 {@code stop(Runnable)} 默认实现
     * 会转发到这里，所以不用额外实现。
     *
     * <p>⚠️ <b>它不中断那个监督线程</b>：加一个 {@code interrupt()} 会在
     * 「还没订阅成功就关闭」这个瞬间制造一个和 {@link #sleepQuietly} 的竞争 ——
     * 收益为零，风险不为零。
     *
     * <p>★ 那它怎么停下来？靠 {@code running = false}：监督循环的退出条件里
     * 有这一项（见 {@link #supervise()} 里那段说明）。<b>读一个 volatile 标志
     * 没有那个竞争</b> —— 这正是当初否决 interrupt 之后该走的补法。
     */
    @Override
    public void stop() {
        this.running = false;
        RedisMessageListenerContainer current = this.container;
        this.container = null;
        stopQuietly(current);
    }

    /**
     * ★ 必须是<b>这个类自己的</b>启动状态，不是容器的。
     *
     * <p>{@code DefaultLifecycleProcessor} 用它来判断「这个 bean 要不要被 stop」。
     * 如果这里返回容器的 {@code isRunning()}，那么一个「线程已经起了、
     * 但还没订阅上」的瞬间会答 false —— 于是关闭时<b>跳过 stop</b>。
     * 而且容器的 {@code isRunning()} 在订阅失败后<b>仍然答 true</b>
     * （见类注释 ②），所以它连「活着」都表示不准。
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 订阅现在到底活着没有 —— <b>探针用它</b>。
     *
     * <p>★★ 它比「启动时有没有抛异常」有价值得多，因为它会<b>随现实变化</b>：
     * 断线期间答 false、重建成功之后自动答 true。
     * 一个启动时记下的布尔值回答不了「现在呢」，而运维关心的永远是「现在」。
     */
    public boolean isListening() {
        RedisMessageListenerContainer current = this.container;
        return current != null && current.isListening();
    }

    /** 累计尝试订阅的次数。★ 见 {@link #subscriptionAttempts} 的说明 */
    public long subscriptionAttempts() {
        return subscriptionAttempts.get();
    }

    /**
     * 收到广播 → 叫醒本机所有等待者。
     *
     * <p>★★ <b>消息体（释放者的 traceId）被刻意丢弃。</b>
     *
     * <p>用它去「只唤醒队列里的下一个人」听起来更高效，但那是本项目
     * <b>在设计阶段就否决掉的「释放 + 提拔队首」方案</b>：
     *
     * <pre>
     *   释放者并不知道「下一个人」是不是还活着
     *      → 它提拔了一个已断线的等待者 → 名额被幽灵占住
     *      → 直到租期到期，后面所有人卡住   ← 直接威胁验收标准 1（无死锁）
     * </pre>
     *
     * <p>我们的方案是「<b>释放只广播，分配只在 acquire.lua 里发生</b>」——
     * 死人根本不会来参加竞争，所以「分给死人」在结构上不可能发生。
     * 这个参数被忽略，正是那条设计的实现。
     *
     * <p>★ 所以方法体只有一行：<b>不要基于消息内容做任何判断</b>。
     * 一旦有人往这里加「如果是某某就……」，上面那条不变量就没了。
     */
    private static final class PermitSignalListener implements MessageListener {

        private final PermitSignalBus bus;

        private PermitSignalListener(PermitSignalBus bus) {
            this.bus = bus;
        }

        @Override
        public void onMessage(Message message, byte[] pattern) {
            // ★ 一行。参数看着没用，是【故意的】—— 见类注释。
            bus.signal();
        }
    }
}
