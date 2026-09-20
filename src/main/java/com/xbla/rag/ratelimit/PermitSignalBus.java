package com.xbla.rag.ratelimit;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本机「有位置空出来了」的广播总线（阶段 6.4）。
 *
 * <h2>它在整条链路里的位置</h2>
 *
 * <pre>
 *   别的实例/本实例 释放名额
 *        → release.lua PUBLISH xbla:rl:events
 *        → 【本类的订阅者】收到  →  bus.signal()
 *        → 叫醒本机所有正在排队的线程
 *        → 它们各自去跑一次 acquire.lua，只有一个能赢
 * </pre>
 *
 * <h2>★★★ 它是【优化】，不是【正确性】—— 这是本节最重要的一句话</h2>
 *
 * <p>没有它，等待者每 {@code poll-interval}（200ms）自己重试一次，功能完全正确。
 * 有了它，那个「迟早会重试」变成「立刻重试」，延迟从均值 100ms 降到 ~1ms。
 *
 * <p>★ 而下面这条性质是<b>结构性</b>的，不是靠小心维持的：
 *
 * <blockquote>
 *   {@link #await} 的等待时间<b>无论发生什么都不会超过传入的上限</b>。
 *   信号丢了、订阅断了、信号在「抢」和「睡」之间到达 —— 都只是睡满上限，
 *   和没有 Pub/Sub 时<b>一模一样</b>。
 * </blockquote>
 *
 * <p>推理只有两步：等待者<b>总是先跑一次 acquire、再来 await</b>；
 * 而 {@code monitor.wait(timeout)} 天然同时覆盖「被唤醒」和「超时」。
 * 所以<b>Pub/Sub 只能让等待变短，永远不可能让它变长</b>。
 *
 * <p>这直接回答了那道面试题 ——「你用 Pub/Sub 怎么保证消息不丢？」
 * <b>不保证。所以让它丢了也不影响正确性。</b>把 Pub/Sub 整个关掉，
 * 系统只是慢一点（{@code xbla.ratelimit.wakeup.enabled: false} 就是那条开关，
 * 存在的唯一目的是让这句话可验证而不是一句声明）。
 *
 * <h2>★ 为什么是「共享监视器 + notifyAll」，不是别的</h2>
 *
 * <p><b>不用 Semaphore</b>：它的 {@code release()} 只放行<b>指定个数</b>的等待者，
 * 而「本机现在有几个在等」这个数我们不知道。想「唤醒全部」只能
 * {@code release(Integer.MAX_VALUE)} —— 那会让许可累积起来，
 * 之后每一次 {@code tryAcquire} 都立刻成功，循环退化成<b>忙等</b>，
 * 把 Redis 打满，而现象是「排队功能突然不排队了」。
 *
 * <p><b>不用每个等待者一个队列</b>：那需要一张「登记表」，
 * 于是就有了「登记了忘了注销」这个失效模式 —— 而它正是
 * {@link LocalPermitRegistry} 那个「复活陷阱」的同一类问题。
 * 共享监视器<b>没有任何需要登记的状态</b>，所以没有任何东西会泄漏。
 *
 * <h2>★ 为什么消息体（那个 traceId）被【刻意忽略】</h2>
 *
 * <p>释放者 {@code PUBLISH} 的内容是它自己的 traceId。
 * 一个很自然的想法是「只唤醒队列里的下一个人」—— <b>那是错的，而且错得很贵</b>：
 *
 * <pre>
 *   释放者并不能可靠地知道「下一个人是谁」（那需要读队列、还要处理它已经死了的情况）
 *   → 一旦它「提拔」了一个已断线的等待者，那个名额就被幽灵占住
 *   → 直到 TTL 过期为止，后面所有人卡住
 * </pre>
 *
 * <p>这正是本项目在设计阶段就否决掉的「释放 + 提拔队首」方案。
 * 我们采用的替代方案是<b>「释放只广播，分配只在 acquire.lua 里发生」</b> ——
 * 于是「分给死人」在结构上不可能发生，因为死人根本不会来参加竞争。
 *
 * <p>所以这里<b>故意不看消息体</b>：谁来抢、谁抢得到，
 * 由 {@code acquire.lua} 的原子判断决定，不由广播方指定。
 *
 * <h2>★ 为什么这个类完全不认识 Redis</h2>
 *
 * <p>它只有一个 {@code Object} 和几个计数器 —— 订阅 Redis 频道那件事
 * 在 {@code RedisSignalConfig} 里。这样分开之后：
 * <ul>
 *   <li>{@code PermitSignalBusTest} 能<b>不起 Spring、不起 Redis</b> 就验证
 *       「信号叫醒全部等待者」「超时不会超过上限」这些核心性质；</li>
 *   <li>「订阅真的接上了没有」由一条单独的集成测试验证 —— 而那是<b>另一件事</b>，
 *       混在一起会让「总线逻辑错了」和「订阅没接上」长得一模一样。</li>
 * </ul>
 */
@Component
public class PermitSignalBus {

    /**
     * 所有等待者共用的监视器。
     *
     * <p>★ 用 {@code Object} 而不是 {@code this}：锁的是<b>这个对象</b>的语义，
     * 不是「这个类的实例」。用 {@code this} 会让外部任何一段
     * {@code synchronized (bus)} 意外参与进来。
     */
    private final Object monitor = new Object();

    /**
     * 唤醒代次 —— <b>由 {@link #monitor} 保护</b>。
     *
     * <p>等待者进去前记下它、醒来后再读一次：变了就是「被 signal 叫醒的」，
     * 没变就是「自己睡满的」。{@code Object.wait} 本身<b>不告诉你</b>是哪种，
     * 而这两种情况的比值是这个机制唯二的可观测信号之一。
     *
     * <p>★ <b>溢出不是问题</b>：它是 {@code long}，而且比较的是
     * 「两次读之间有没有变」，哪怕真要绕一圈回来也得 2^63 次广播。
     */
    private long generation;

    /** 收到的广播次数（含本机没有等待者时收到的那些 —— 那是正常情况，不要以为它应该等于被唤醒数） */
    private final AtomicLong signalsReceived = new AtomicLong();

    /** await 的总次数 */
    private final AtomicLong waitsTotal = new AtomicLong();

    /** 其中【确实被信号叫醒】的次数 —— 其余的是自己睡满超时 */
    private final AtomicLong waitsWokenBySignal = new AtomicLong();

    /**
     * 等一个唤醒信号，<b>最多等 {@code maxMs} 毫秒</b>。
     *
     * <h3>★★ 它是「有上限的等待」，不是「条件等待」—— 这个区别是关于正确性的</h3>
     *
     * <p>{@link Object#wait(long)} 的 Javadoc 明确说它<b>可能在没有 notify 的情况下
     * 自己返回</b>（伪唤醒 / spurious wakeup）。教科书结论是「永远写在循环里」，
     * 因为绝大多数用法是「等到条件成立」—— 那时伪唤醒意味着<b>条件其实不成立</b>。
     *
     * <p>★ 这里<b>不需要补一个条件循环</b>，因为本方法的语义就是
     * 「最多睡这么久」，返回值只是「是不是被叫醒的」这个观测信息。
     * 伪唤醒的后果是「调用方多跑一次 acquire」—— 而那个 acquire 本来就是它
     * 每次循环都要跑的。换句话说：<b>伪唤醒在这里不是一个需要防御的错误，
     * 它是被允许提前返回这件事的另一种形式。</b>
     *
     * <p>⚠️ 反过来说，<b>调用方不可以</b>把返回值当成「名额有了」——
     * 它只表示「有人释放了某个名额」。到底有没有我的份，只有
     * {@code acquire.lua} 说了算。
     *
     * <h3>★ 为什么要把 0 挡掉</h3>
     *
     * <p>{@code Object.wait(0)} 的语义是<b>无限等待</b>，和 {@code Thread.sleep(0)}
     * 的「让一下 CPU」完全相反。而 {@code maxMs} 来自配置项
     * {@code xbla.ratelimit.poll-interval} —— 谁把它写成 {@code 0s}
     * （一个看起来很像「不等待、立刻重试」的值），排队线程就会
     * <b>永久挂死在这个 wait 上</b>，而现象是「请求全部卡在排队中直到超时」。
     *
     * <p>所以这里强制至少 1 毫秒：<b>配置写 0 的后果是「变成忙轮询」，
     * 而不是「整个服务卡死」</b> —— 前者吵闹、看得见、可恢复，后者不是。
     *
     * @param maxMs 最多等多少毫秒（&lt;= 0 会被当成 1）
     * @return {@code true} = 是被 {@link #signal()} 叫醒的；
     *         {@code false} = 自己睡满的（或伪唤醒）
     * @throws InterruptedException 应用正在关闭（线程池 {@code shutdownNow}）——
     *                              ★ 调用方必须恢复中断位，不能吞掉
     */
    public boolean await(long maxMs) throws InterruptedException {
        long waitMs = Math.max(1L, maxMs);
        boolean woken;

        synchronized (monitor) {
            long before = generation;
            monitor.wait(waitMs);
            woken = generation != before;
        }

        // ★ 计数放在【锁外】。AtomicLong 自己就是并发安全的，而放进 synchronized 里
        //   会让 100 个刚被一起叫醒的线程【排着队】去加计数 ——
        //   那正好是在惊群里最不该再加一次串行化的地方。
        waitsTotal.incrementAndGet();
        if (woken) {
            waitsWokenBySignal.incrementAndGet();
        }
        return woken;
    }

    /**
     * 叫醒本机所有正在 {@link #await} 的线程。
     *
     * <p><b>由订阅者调用</b>（收到 {@code xbla:rl:events} 的一条消息时）。
     *
     * <p>★ 它<b>不区分</b>「这个信号跟我有没有关系」—— 见类注释第四节。
     * 本机惊群的代价是：一次广播唤醒 N 个等待者，它们各跑一次 Lua，
     * 只有一个能赢。N=100 时是 100 个轻量 Lua，Redis 处理它们是微秒级，
     * 换来的是「不可能把名额分给一个已经死掉的等待者」。
     *
     * <p>★ 没有任何等待者时调用它<b>完全正常</b>：广播是给「所有订阅者」的，
     * 而本实例此刻恰好没人在等，是常态而不是错误。
     */
    public void signal() {
        // ★ 计数在拿锁【之前】—— 这样「广播到了」这件事不会因为
        //   有人在 wait 而延迟可见。锁里只做两件事：推进代次、叫醒。
        signalsReceived.incrementAndGet();
        synchronized (monitor) {
            generation++;
            monitor.notifyAll();
        }
    }

    // ============================================================
    // 观测量
    // ============================================================

    /** 收到的广播次数。<b>含本机没人在等时收到的</b>，所以它 ≥ {@link #waitsWokenBySignal()} 没有必然关系 */
    public long signalsReceived() {
        return signalsReceived.get();
    }

    /** {@link #await} 被调用的总次数 */
    public long waitsTotal() {
        return waitsTotal.get();
    }

    /** 其中被信号叫醒的次数。★ 和 {@link #waitsTotal()} 的差就是「睡满超时」的次数 */
    public long waitsWokenBySignal() {
        return waitsWokenBySignal.get();
    }

    /**
     * <b>一次广播平均叫醒几个人</b> —— 本机惊群的放大系数。
     *
     * <p>★ 这个数直接对应「Pub/Sub 到底有没有在起作用」：
     * <pre>
     *   ≈ 0        →  广播收到了但没人被叫醒（订阅接上了，只是队列空着）
     *   1 ~ 2      →  正常：一次释放 ≈ 唤起队首那一个
     *   几十       →  拥挤：100 个人在等，每释放一个名额就全员惊醒一次
     *                  ★ 这时候 Redis 的 QPS 会随等待人数线性上涨，
     *                    是要扩容的信号，而不是 bug
     * </pre>
     *
     * <p>⚠️ 分母是 {@code signalsReceived}，而它包含「本机没人在等时收到的广播」，
     * 所以本值<b>会被那些广播拉低</b>。这是刻意的：那个数才反映真实的广播量。
     * 想只看「有等待者时的效率」，用 {@code waitsWokenBySignal / waitsTotal}。
     */
    public double amplification() {
        long signals = signalsReceived.get();
        if (signals == 0) {
            return 0.0;
        }
        return (double) waitsWokenBySignal.get() / signals;
    }

    /** 探针用的快照 —— 见 {@code ChatPermitService#redisState()} 同一条理由：LinkedHashMap 保序 */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("signalsReceived", signalsReceived());
        snapshot.put("waitsTotal", waitsTotal());
        snapshot.put("waitsWokenBySignal", waitsWokenBySignal());
        snapshot.put("amplification", amplification());
        return snapshot;
    }

    /**
     * 清空计数器（测试用）。
     *
     * <p>★ 和 {@link LocalPermitRegistry#clearAll()} 一样，它存在的理由是
     * <b>这个类是 Spring 单例</b>：上一个测试留下的计数会让下一个测试的断言
     * 看到一个「凭空多出来的」数，而失败信息指向断言、不指向残留。
     *
     * <p>⚠️ <b>它不动 {@code generation}</b>，所以不会意外唤醒任何人 ——
     * 清计数器不该有唤醒这个副作用。
     */
    public void resetCounters() {
        signalsReceived.set(0);
        waitsTotal.set(0);
        waitsWokenBySignal.set(0);
    }
}
