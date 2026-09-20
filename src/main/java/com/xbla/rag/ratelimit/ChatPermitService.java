package com.xbla.rag.ratelimit;

import com.xbla.rag.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 排队限流的门面 —— 上层只见这一个类。
 *
 * <h2>★ 它替调用方守住的三条纪律</h2>
 *
 * <ol>
 *   <li><b>本地登记表与 Redis 必须同步增删。</b>
 *       调用方不该被迫记住「拿到名额后要 markHeld」——
 *       忘一次，那个名额就永远不会被续期（提前过期 → 超卖）；
 *       或者反过来，永远被续期（名额不回收）。两种都很难查。
 *       <b>所以状态迁移只发生在这个类里。</b></li>
 *
 *   <li><b>释放时先删本地、再删 Redis。</b>见
 *       {@link LocalPermitRegistry} 类注释第二节 —— 顺序反了的话，
 *       两步之间的心跳会用一个全新的租期把名额复活，而没有任何人会来释放它。</li>
 *
 *   <li><b>关掉时什么都不做。</b>{@code enabled=false} 时不排队、不抢名额，
 *       调用方拿到的永远是 {@link PermitState#granted()}。
 *       ⚠️ 这要求调用方在关掉时<b>不要</b>去记 {@code qa_log.queue_ms} 的 0 ——
 *       要记 <b>NULL</b>。否则阶段 7 分不清「没开排队」和「开了但没排队」，
 *       而这是两个完全不同的实验条件（同 ADR-010「拿不到就记 NULL」）。</li>
 * </ol>
 *
 * <h2>★ 为什么这里直接用 StringRedisTemplate，而不用 LuaScripts</h2>
 *
 * <p>{@link LuaScripts} 装的是<b>必须原子</b>的三件事（抢名额、释放、清扫）。
 * 而心跳的续期是一串<b>互相独立</b>的 {@code ZADD} —— 每个都只改自己那个成员的
 * score，彼此没有「读-判断-写」的关系，所以不需要原子。
 *
 * <p>硬把它们也塞进 Lua 只会让脚本更长、更难读，而 Redis 的单线程执行
 * 反而会让 8 个续期串行阻塞。这里用普通命令还有额外好处：
 * 某一个续期失败不影响其余的（脚本里失败会中断整个脚本）。
 */
@Service
public class ChatPermitService {

    private static final Logger log = LoggerFactory.getLogger(ChatPermitService.class);

    private final LuaScripts lua;
    private final StringRedisTemplate redis;
    private final RateLimitProperties props;
    private final RedisQueueKeys keys;
    private final LocalPermitRegistry registry;

    /**
     * 续期失败的累计次数。
     *
     * <p>★ 为什么要暴露它：续期失败的策略是「<b>只记日志、不放弃</b>」——
     * 因为 Redis 抖一下（GC 停顿、网络抖动）是暂时的，主动放弃反而把名额真空出来。
     * 但那个策略有个代价：<b>如果 Redis 一直不通，我们会一直以为自己还持有名额，
     * 而实际上名额早就在 Redis 侧过期、被别人抢走了</b>（超卖）。
     *
     * <p>所以「续期失败」必须有一个<b>能被看见</b>的出口。日志会被淹没，
     * 这个计数器不会 —— 它出现在 {@code /api/debug/ratelimit/state} 上，
     * 长时间非 0 就意味着我们可能在超卖。
     */
    private final AtomicLong heartbeatFailures = new AtomicLong();

    public ChatPermitService(LuaScripts lua,
                             StringRedisTemplate redis,
                             RateLimitProperties props,
                             LocalPermitRegistry registry) {
        this.lua = lua;
        this.redis = redis;
        this.props = props;
        this.keys = RedisQueueKeys.from(props);
        this.registry = registry;
    }

    // ============================================================
    // 抢名额
    // ============================================================

    /**
     * 尝试获取一个名额。可能拿到、可能开始排队、可能被拒绝。
     *
     * <p>★ 它是<b>幂等</b>的：已经持有名额的 traceId 再调一次只会续期，
     * 不会把自己排进队（那个判断在 {@code acquire.lua} 里，
     * 不能放在 Java 侧 —— 否则「判断」和「占用」之间就有缝了）。
     */
    public PermitState tryAcquire(String traceId) {
        if (!props.isEnabled()) {
            return PermitState.granted();
        }

        List<Long> raw = lua.acquire(keys, traceId,
                System.currentTimeMillis(),
                props.getPermitTtl().toMillis(),
                props.getPermits(),
                props.getMaxQueue());

        long status = raw.get(0);
        int position = raw.get(1).intValue();
        int queueSize = raw.get(2).intValue();

        if (status == 1) {
            registry.markHeld(traceId);
            return PermitState.granted();
        }
        if (status == 0) {
            registry.markWaiting(traceId);
            return PermitState.queued(position, queueSize);
        }
        // status == -1
        // ★ 这一句是【防御性】的，正常流程里它无事可做 —— 这一点是实测确认的，
        //   不是推测（注入 bug 时发现的，见下）。
        //
        //   推理：只有 status == 0 才会 markWaiting，只有 status == 1 才会 markHeld。
        //   而一个「已在排队」的请求再次 tryAcquire 时，acquire.lua 的
        //   `ZSCORE queue` 判断会让它【继续返回 QUEUED】而不是 QUEUE_FULL
        //   （它已经在队列里了，队列满不满与它无关）。
        //   所以走到这个分支的请求，从来没进过登记表。
        //
        //   ★ 但还是要留着它：它把「被拒绝 ⇒ 不在登记表里」这条不变量
        //     写成了代码而不是推理。将来有人改了 Lua 或改了上面的分支，
        //     这条不变量仍然成立，而不必重新做一遍上面那段推理。
        //
        //   ⚠️ 代价是它【测不出来】—— 把这一行注释掉，所有测试照样绿。
        //      所以对应的测试里写的是「不会占住名额」，而不是
        //      「它验证了这里的 forget」。（见 rejectedRequestIsNotResurrectedByHeartbeat）
        registry.forget(traceId);
        return PermitState.queueFull(queueSize);
    }

    // ============================================================
    // 释放
    // ============================================================

    /**
     * 释放名额。
     *
     * <p>★★★ <b>删除顺序是这个方法的全部要点：先本地、再 Redis。</b>
     *
     * <pre>
     *   先删 Redis 再删本地：  ZREM ⟶ [心跳跑在这里] ⟶ forget
     *                               └─ ZADD 把名额复活，租期重置 45 秒
     *                                  而没有任何人会来释放它 → 名额永久消失
     *   先删本地再删 Redis：    forget ⟶ ZREM      ← 心跳此刻已经看不到它了，安全
     * </pre>
     *
     * <p>这段窗口只有微秒级，但它<b>每 15 秒就有一次机会</b>被命中
     * （心跳的周期），而且命中一次的后果是<b>永久的</b> ——
     * 直到应用重启。属于「概率低但代价不可逆」的一类。
     */
    public void release(String traceId) {
        if (!props.isEnabled()) {
            return;
        }

        // ① 先断掉续期的可能
        registry.forget(traceId);

        // ② 再删 Redis
        try {
            long freed = lua.release(keys, traceId);
            if (freed == 0) {
                // ★ 值得一条 WARN：调用方以为自己在占着名额，其实没有。
                //   可能是重复释放，也可能是续期失败太久、名额已被回收。
                //   后者意味着刚才那段时间我们【在超卖】—— 那件事必须留痕。
                log.warn("释放了一个并不持有的名额 traceId={}（重复释放？或续期失败已被回收？）",
                        traceId);
            }
        } catch (Exception e) {
            // 释放失败不抛给调用方：用户的问答已经结束了，不该因为收尾失败而报错。
            // 名额会在 permit-ttl 之后自然过期（看门狗已经不再续期它了）。
            log.warn("释放名额失败 traceId={}，将靠 TTL 兜底回收：{}", traceId, e.getMessage());
        }
    }

    // ============================================================
    // 看门狗
    // ============================================================

    /**
     * 续期本 JVM 持有的全部分额 —— 看门狗的一次心跳。
     *
     * <p>★ 只续 {@link LocalPermitRegistry#heldSnapshot()} 里的。
     * <b>不在这个集合里 = 没人管 = 让它过期</b>，这正是「进程被强杀后名额自动回收」
     * 的全部机制（验收标准 3）—— 它不依赖任何清理代码被执行。
     *
     * <p>⚠️ 等待中的请求<b>不在这里续期</b>：它们每 {@code poll-interval} 重试一次，
     * 而重试本身就会更新存活表。<b>等待者用自己的重试当心跳</b>，
     * 所以它掉线后不需要任何代码立即生效 —— 不重试了，心跳就停了。
     *
     * <h2>★★★ 阶段 6.9：续期走 Lua，而且<b>绝不创建</b></h2>
     *
     * <p>这一段原来就是两条普通的 {@code ZADD}。它有个真机上抓到过的 bug：
     * <b>{@code ZADD} 在成员不存在时会把它【创建】出来</b>，于是
     *
     * <pre>
     *   t0  心跳：heldSnapshot() → 快照里有 X
     *   t1  release(X)：registry.forget(X)
     *   t2  release(X)：ZREM slots X
     *   t3  心跳：ZADD slots X (now+ttl)     ← 用过期快照把 X 复活
     * </pre>
     *
     * <p>净结果：名额表里躺着一个<b>本机不认领</b>的条目 —— 没人续期、没人释放，
     * 要占着容量直到 TTL 到期。实测 12 轮 × 100 并发复现 1 次，
     * 那一轮的有效并发从 8 掉到 ~5（耗时 23.6s 而不是 15.9s），
     * <b>而且没有任何日志或指标能看出来</b>。
     *
     * <p>★ 修法见 {@code renew.lua}：先 {@code ZSCORE} 判定成员还在，才延长。
     * 这是所有租约系统的标准语义 —— <b>keepalive 不能创建租约</b>。
     *
     * <h2>★ 两种「续期失败」，处理方式【相反】，别把它们合并</h2>
     *
     * <pre>
     *   execute 抛异常（连不上 Redis）
     *       → 我们【不知道】它还在不在 → 只记日志、不放弃、下一轮重试
     *       → 因为抖动是暂时的，而放弃会把名额真空出来（那是真超卖）
     *
     *   renew 返回 0（Redis 明确说「它已经不在了」）
     *       → 我们【确切知道】自己已经不持有它了
     *       → 记一条 WARN 并计入 heartbeatFailures，让运维能看见
     *       → ★ 但【不】去重新 ZADD 把它抢回来 —— 那个名额已经被回收，
     *         甚至可能已经被别人合法抢走；抢回来才是真的超卖
     * </pre>
     *
     * <h2>⚠️ 续期返回 0 时，【不】把本地登记删掉</h2>
     *
     * <p>看着像该删（「Redis 里都没了」），但那会破坏
     * {@link ChatPermitService#cleanup()} 那条不变量的另一半：
     * <b>本地表可以比 Redis 多几条，不能少几条</b>。留着它的代价是零
     * （续期变成 no-op，不会有任何副作用），而删掉它的代价是
     * 「下次心跳不再尝试续期」—— 万一那只是一次读到的瞬时状态，
     * 我们就永久失去了自愈的可能。
     *
     * <p>真正清理它的地方只有一个：{@link #release}（请求结束时）。
     *
     * @return 成功续期的名额数（<b>不含</b>那些 Redis 侧已经不存在的）
     */
    public int renewHeld() {
        if (!props.isEnabled()) {
            return 0;
        }

        Set<String> held = registry.heldSnapshot();
        if (held.isEmpty()) {
            return 0;
        }

        long now = System.currentTimeMillis();
        long ttl = props.getPermitTtl().toMillis();
        int renewed = 0;

        for (String traceId : held) {
            try {
                if (lua.renew(keys, traceId, now, ttl) == 1) {
                    renewed++;
                } else {
                    // ★ 见方法注释第二节：这是「确定失去了它」，和「连不上」不是一回事。
                    heartbeatFailures.incrementAndGet();
                    log.warn("续期时发现名额已不在 Redis 里 traceId={}（累计 {} 次）—— "
                                    + "它已经被回收（或已被别人抢走），我们可能在这段时间里超卖过。"
                                    + "★ 不去抢回来：那个名额不属于我们了",
                            traceId, heartbeatFailures.get());
                }
            } catch (Exception e) {
                // ★ 这一支是「连不上」，处理方式【相反】：不放弃 —— 见方法注释第二节。
                //   放弃反而更糟：抖动是暂时的，而放弃把名额真空出来，
                //   让一个还在跑的请求和刚进来的请求同时跑（真超卖）。
                heartbeatFailures.incrementAndGet();
                log.warn("续期失败 traceId={}（累计 {} 次）：{}",
                        traceId, heartbeatFailures.get(), e.getMessage());
            }
        }
        return renewed;
    }

    /**
     * 清扫僵尸（定时任务调用）。
     *
     * <p>★★ <b>它【不】碰本地的登记表</b> —— 这一点是刻意的，而且很容易做反。
     *
     * <p>直觉是「Redis 里没了就说明它死了，把本地的也删掉」，但那样会造成：
     *
     * <pre>
     *   Redis 抖动 → 续期失败几次 → 名额 expire 被 cleanup 扫掉
     *      → 本地表也被删 → 心跳【不再尝试续期】
     *      → 而那个请求其实【还在跑】
     *      → 它的名额永远回不来了，而且我们不知道自己已经失去它  ← 静默超卖
     * </pre>
     *
     * <p>反过来，本地表<b>只由 {@link #release} 和「被拒绝」来清理</b>，
     * 那么抖动恢复之后心跳会把名额重新续上，一切自愈。
     * <b>「本地表可能比 Redis 多几条」是安全的，「比 Redis 少几条」才是危险的。</b>
     *
     * @return {@code [清扫的名额数, 清扫的排队僵尸数]}
     */
    public List<Long> cleanup() {
        if (!props.isEnabled()) {
            return List.of(0L, 0L);
        }

        long now = System.currentTimeMillis();
        long aliveCutoff = now - props.getPermitTtl().toMillis();

        return lua.cleanup(keys, now, aliveCutoff, props.getCleanupBatch());
    }

    // ============================================================
    // 观测量（探针用）
    // ============================================================

    /**
     * Redis 侧的权威状态 —— <b>直接读 Redis，不读本地缓存</b>。
     *
     * <p>★ 探针必须反映「Redis 里现在到底是什么样」。如果它报的是本地登记表的数字，
     * 那它就变成了「我以为的状态」，而排队限流出问题的时候，
     * <b>「我以为的」和「实际发生的」之间的差就是全部线索</b>。
     */
    public Map<String, Object> redisState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("keyPrefix", props.getKeyPrefix());
        state.put("channel", keys.channel());

        long now = System.currentTimeMillis();
        Long slots = redis.opsForZSet().zCard(keys.slots());
        Long queue = redis.opsForZSet().zCard(keys.queue());
        Long alive = redis.opsForZSet().zCard(keys.alive());

        state.put("permitsInUse", slots == null ? 0 : slots);
        state.put("permitLimit", props.getPermits());
        state.put("queueSize", queue == null ? 0 : queue);
        state.put("queueLimit", props.getMaxQueue());
        state.put("aliveSize", alive == null ? 0 : alive);
        state.put("seq", redis.opsForValue().get(keys.seq()));

        // ★ 队列成员的排行（前 10 个）—— 位置是否准确要看它，
        //   而不是看我们推给用户的那个数。两个对不上就是 bug。
        Set<String> head = redis.opsForZSet().range(keys.queue(), 0, 9);
        state.put("queueHead", head == null ? List.of() : List.copyOf(head));

        // ★ 已过期但还没被清理的名额。它必须能被看见 ——
        //   「僵尸还没清」和「真的满了」在 ZCARD 上长得一样。
        Long zombies = redis.opsForZSet().count(keys.slots(), Double.NEGATIVE_INFINITY, now);
        state.put("expiredNotYetSwept", zombies == null ? 0 : zombies);
        state.put("nowMs", now);

        return state;
    }

    /** 本 JVM 侧的登记表状态 —— 和 {@link #redisState()} 对照着看 */
    public Map<String, Object> localState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("heldHere", registry.heldCount());
        state.put("waitingHere", registry.waitingCount());
        state.put("heartbeatFailures", heartbeatFailures.get());
        state.put("enabled", props.isEnabled());
        return state;
    }

    /** 续期失败的累计次数。<b>长时间非 0 意味着我们可能在超卖</b> */
    public long heartbeatFailures() {
        return heartbeatFailures.get();
    }

    /**
     * 当前生效的配置快照。
     *
     * <p>★ 探针要把「现在生效的到底是什么」原样打出来，理由和评测报告的
     * config 快照一样（{@code RetrievalProperties} 类注释里那段）：
     * 没有它，两次运行的差异无法归因。
     *
     * <p>★ 而且这一份<b>对比 {@code application.yml} 更可信</b> ——
     * yml 是「我们写的」，这里是「绑定之后真正生效的」。
     * 两者不一致的时刻只有一种：写错了。而 {@code ignoreUnknownFields = false}
     * 会让那种时刻直接启动失败，所以正常情况下两者必然相等 ——
     * 这个快照就是那个「必然」的可检查版本。
     */
    public ConfigSnapshot config() {
        return new ConfigSnapshot(
                props.isEnabled(),
                props.getPermits(),
                props.getPermitTtl().toMillis(),
                props.getMaxQueue(),
                props.getQueueTimeout().toMillis(),
                props.getPollInterval().toMillis());
    }

    /** 配置快照 —— 见 {@link #config()} */
    public record ConfigSnapshot(boolean enabled, int permits, long permitTtlMs,
                                 int maxQueue, long queueTimeoutMs, long pollIntervalMs) {
    }
}
