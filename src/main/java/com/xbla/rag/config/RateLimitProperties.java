package com.xbla.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 排队限流的参数，对应 {@code application.yml} 里的 {@code xbla.ratelimit}（阶段 6）。
 *
 * <p>注册方式：启动类上的 {@code @ConfigurationPropertiesScan} 会自动扫描
 * {@code com.xbla.rag} 包下所有带 {@code @ConfigurationProperties} 的类，
 * 不需要在这里写 {@code @Component}。
 *
 * <h2>★ 为什么新开一个前缀</h2>
 *
 * <p>{@code xbla.rag} 管「怎么把资料找出来」，{@code xbla.chat} 管「怎么把这轮对话
 * 放回上下文里」。这一组回答的是第三个问题 ——
 * <b>「同时允许多少个请求往下游走，多出来的往哪放」</b>。
 * 它作用在<b>检索和生成之前</b>，和上面两组没有共用的参数。
 *
 * <h2>★ 并发上限为什么是 8 —— 三个数同时指向它</h2>
 *
 * <p>{@link #permits} 不是拍脑袋定的，它被三处下游约束夹在中间：
 *
 * <pre>
 *   answerExecutor.corePoolSize             = 8    ← 稳态就是这个并发量
 *   retrieveExecutor.maxPoolSize         = 16   ← 一次问答 fan-out 两路检索，8 × 2 = 16
 *   spring.datasource.hikari.maximum-pool-size = 10  ← ★ 真正的下游瓶颈
 * </pre>
 *
 * <p>第三条是关键：{@code AsyncConfig} 里 {@code retrieveExecutor} 的注释已经写过
 * 「max 的真正上限不是线程数，是连接池」。同样的约束在这里也成立 ——
 * 把 {@code permits} 调到 32 不会让吞吐变高，只会让 32 个请求同时去抢 10 个连接，
 * 抢不到的卡在 {@code connection-timeout: 30000}（<b>30 秒</b>）上。
 *
 * <p>★ 它和 {@code docs/02} §23 里举例用的「限制 10 个并发」不是同一个数 ——
 * 那个 10 是随手举的例子，这里的 8 是从上面三个真实约束推出来的。
 * 两个数长得像，但只有一个有依据。
 *
 * <h2>★ 关于「可调」的边界</h2>
 *
 * <p>下面大部分字段是阶段 7 做 A/B 对比的旋钮，所以外置成配置。
 * <b>但有两项【刻意不做成配置】</b>，理由写在各自的注释里 ——
 * 它们是正确性要求，不是调优空间。
 *
 * <h2>★★ 为什么带 {@code ignoreUnknownFields = false}</h2>
 *
 * <p>Spring Boot 的默认值是 {@code true} ——
 * <b>写错的配置项会被静默忽略，然后回落到代码里的默认值</b>。
 *
 * <pre>
 *   permit-ttl: 45s      ✅ 生效
 *   permit-tt:  45s      ❌ 拼错了 → 没有任何提示 → 用的还是默认的 45s
 * </pre>
 *
 * <p>第二行尤其危险：<b>默认值恰好也是 45s，所以现象上完全看不出来</b>。
 * 等哪天有人把它改成 {@code permit-tt: 90s} 想调长租期，改了、重启了、没报错，
 * 而实际生效的仍然是 45s —— 然后他会去查「为什么改了配置没效果」，
 * 一路查到线程池、查到 Redis、查到 Lua，<b>唯独不会怀疑那一行少了个 l</b>。
 *
 * <p>★ 单元测试<b>抓不到</b>这个：断言 {@code assertEquals(Duration.ofSeconds(45), props.getPermitTtl())}
 * 在「绑定成功」和「拼错了回落默认值」两种情况下<b>都成立</b>。
 * 要抓它，只能让绑定本身报错。
 *
 * <p>同 {@code IntentTree} 缺文件「启动即崩」的理由：
 * <b>配置类错误没有合理的回落值</b>，所以宁可起不来。
 * 这是本项目唯一一个显式关掉这个默认值的配置类 —— 其它几个没关，
 * 是因为它们已经上线且没有这个需求；新加的这一个从第一天起就关掉。
 */
@Data
@ConfigurationProperties(prefix = "xbla.ratelimit", ignoreUnknownFields = false)
public class RateLimitProperties {

    /**
     * 总开关。
     *
     * <p><b>默认开。</b>留这个开关是为了阶段 7 的 A/B ——
     * 「排队限流对最终答案质量有没有影响」这个问题，只有拿开和关两组同样的题跑一遍才能回答。
     * 同 {@code xbla.chat.history.enabled}、{@code xbla.rag.rewrite.enabled} 的先例。
     *
     * <p>⚠️ 关掉时<b>什么都不做</b> —— 不进队列、不抢名额、也不写
     * {@code qa_log.queue_ms}。不是「抢了名额但不排队」。
     * 否则阶段 7 分不清「没开排队」和「开了但没排队」，
     * 而这是两个完全不同的实验条件。（同 ADR-010「拿不到就记 NULL」的原则。）
     */
    private boolean enabled = true;

    /**
     * 并发上限：同一时刻最多允许几个请求走到下游。
     *
     * <p>依据见类注释。⚠️ 调大之前先确认 Hikari 连接池跟得上。
     */
    private int permits = 8;

    /**
     * 名额的租期。
     *
     * <p><b>它不是「最多能跑多久」，是「最多能失联多久」</b> ——
     * 这是整个看门狗机制的核心。持有者每 {@code permitTtl / 3} 续期一次，
     * 只要它还在跑，score 就一直往后推，永远不会被判定过期。
     * 而进程被强杀时没人续期，{@code permitTtl} 之后名额自动回收。
     *
     * <p>所以这个值可以（也应该）<b>远小于</b>一次问答的最坏耗时 ——
     * 验收标准 3 说的是「进程被强杀后名额不会永久泄漏」，
     * 而「永久」和「45 秒」的差别就是它。
     *
     * <p>⚠️ 别调到和 {@code queueTimeout} 一个量级：续期失败时名额会被提前回收，
     * 那种情况下我们<b>正在超卖</b>而自己不知道。
     */
    private Duration permitTtl = Duration.ofSeconds(45);

    /**
     * 名额的<b>绝对持有上限</b>（阶段 7 补）—— 不管心跳怎么续，超过它就不再续期。
     *
     * <h2>★★★ 为什么 {@code permitTtl} 兜不住它</h2>
     *
     * <p>{@code permitTtl} 答的是「<b>最多能失联多久</b>」：只要持有者还在续期，
     * 名额就永不过期。这个设计对「进程被强杀」是对的（没人续 → 45 秒后回收），
     * 但它有一个**没有上界的洞**：
     *
     * <pre>
     *   线程卡在出站调用上（socket 永不返回）
     *     → 那个请求的 finally 永远不执行
     *     → 名额不释放
     *     → ★ 而看门狗【只问「本机表里还登记着吗」】，登记着就续期
     *     → 名额永不过期、永不回收，直到进程重启
     * </pre>
     *
     * <p>这就是 2026-09-21 实测到的那次：一次跑批里两个出站调用挂了 300 秒以上，
     * 两个 {@code answer-} 线程永久停在 {@code httpClient.send()} 上，
     * <b>8 个名额当场掉到 6 个，而且再也没恢复</b>。
     * 它是 ADR-076（「续期不能创建名额」）的<b>镜像形态</b>：
     * 那次是「续期把一个已释放的名额复活了」，这次是「续期把一个已死掉的持有者续到了永远」。
     * 两者的根因是同一个 —— <b>续期这个动作的边界没有写全</b>，
     * 症状也是同一个 —— <b>容量静默变少</b>。
     *
     * <h2>⚠️ 这个值同时决定「漏了多久回收」和「会不会误杀」</h2>
     *
     * <p>它不是越大越好也不是越小越好，两个方向的代价<b>不对称</b>：
     * <ul>
     *   <li><b>太小</b> → 一个正常干活、只是慢的请求会被判定超限，名额被回收，
     *       而它还在跑 → <b>真超卖</b>。超卖是阶段 6 最难修的那一类问题</li>
     *   <li><b>太大</b> → 真漏了要很久才回收。而这<b>只是慢</b>，不会算错</li>
     * </ul>
     *
     * <p>所以取值必须明显大于<b>任何一条正常路径的最坏耗时</b>。
     * 实测上界约 15 分钟（排队 120s + 分类 180s + 工具两跳 + 生成，全拉满），
     * 默认取 <b>30 分钟</b> —— 两倍余量。
     *
     * <p>★ 而且它只是<b>第二道防线</b>：根因（出站调用没有自己的超时）已经修掉了，
     * 正常情况下这个上限永远不会触发。它兜的是「还有别的什么让线程卡住」。
     */
    private Duration maxHold = Duration.ofMinutes(30);

    /**
     * 队列长度上限。满了之后<b>如实拒绝</b>（返回一个明确的失败事件），而不是无限排下去。
     *
     * <p>★ 没有这个上限的话，队列会随流量无限增长 —— 而排在第一千位的人
     * 等到天荒地老也等不到。{@code docs/02} §24⑤ 自己也写了
     * 「元素不会被自动清理，必须有超时清理机制，否则队列无限增长」。
     *
     * <p>500 是初始假设：它明显大于 {@link #permits}，让「排队」这件事有意义；
     * 又明显小于「等到崩溃」的量级。
     */
    private int maxQueue = 500;

    /**
     * 排队等待的最长时间。超过就放弃，给用户一句明确的说明。
     *
     * <p>为什么是 120 秒：SSE 连接的超时是 180 秒
     * （{@code ChatController.SSE_TIMEOUT_MS}）。如果排队超时也定 180 秒，
     * 那么「排队超时」和「SSE 被 Tomcat 掐断」会同时发生 ——
     * 用户看到的是一个<b>没有任何说明的断流</b>，而不是一句「前面人太多」。
     *
     * <p>★ 留 60 秒余量的目的就是让前者先发生。
     */
    private Duration queueTimeout = Duration.ofSeconds(120);

    /**
     * 排队等待的最长时间 —— <b>非流式</b> {@code POST /api/chat} 专用。
     *
     * <h2>★★ 为什么它和上面的 {@link #queueTimeout} 不是同一个数</h2>
     *
     * <p>因为两条路的<b>用户能看见什么</b>完全不同：
     *
     * <pre>
     *   流式：每 200ms 推一次「你前面还有 N 位」→ 等 120 秒是【有信息的等待】
     *   非流式：HTTP 请求-响应，没有任何通道 → 等 120 秒是【一个转圈的页面】
     * </pre>
     *
     * <p>★ 而 30 秒这个数不是拍的，它受一条比体验更硬的约束：
     * <b>我们的超时必须比客户端自己的超时【先】发生。</b>
     *
     * <pre>
     *   curl / 演示页 / 各种脚本        普遍 30~60 秒就放弃
     *   如果我们持 120 秒才回 503
     *        → 客户端早已断开
     *        → 用户看到的是【连接错误】，而不是我们那句「前面人太多」
     *        → ★ 那条精心写的提示【永远送不到】
     * </pre>
     *
     * <p>同 {@code queueTimeout(120s) < sseTimeout(180s)} 是同一条逻辑 ——
     * 那边留 60 秒余量，同样是为了让「排队超时」先于「连接被掐断」发生。
     *
     * <p>⚠️ 这两条路的等待者<b>排的是同一个 Redis 队列</b>。
     * 所以这里的差别是<b>体验</b>差别，不是容量差别 ——
     * 谁也不能插队，谁也不能把队列占得更久（超时了就自己摘出去）。
     */
    private Duration nonStreamQueueTimeout = Duration.ofSeconds(30);

    /**
     * 排队期间重新尝试获取名额的间隔。
     *
     * <p>★ 这个轮询<b>不是</b>「Pub/Sub 的降级方案」，它是<b>正确性来源</b>。
     * Pub/Sub 只负责把「有位置空了」这件事尽快告诉等待者（把延迟从 200ms 降到 ~1ms），
     * 而消息丢了、订阅断了、唤醒时恰好没抢到 —— 这些都不影响最终结果，
     * 因为最多 {@code pollInterval} 之后还会再试一次。
     *
     * <p>这直接回答了面试题「你用 Pub/Sub 怎么保证消息不丢」：
     * <b>不保证，所以有轮询兜底。</b>把 Pub/Sub 整个关掉，功能仍然完全正确，只是慢一点。
     *
     * <p>200ms 是「用户感觉不到」和「Redis 负担」之间的折中：
     * 100 个等待者 × 5 次/秒 = 500 QPS，对 Redis 是完全无感的量级。
     */
    private Duration pollInterval = Duration.ofMillis(200);

    /**
     * 单次 cleanup 最多清理多少个<b>排队</b>僵尸。
     *
     * <p>★ 为什么要有上限：Lua 脚本执行期间 Redis 是<b>单线程阻塞</b>的
     * （{@code docs/02} §22⑤ 自己列了这个缺点）。最坏情况真的会发生 ——
     * 一个人停止整个服务再重启，几百个等待者同时停止心跳，
     * 下一次 cleanup 就要在脚本里循环几百次。
     *
     * <p>设了上限之后剩下的会在下一次任务里被清掉，而它们已经死了 ——
     * 早 15 秒晚 15 秒没有区别。<b>「不阻塞 Redis」比「一次清干净」重要得多。</b>
     *
     * <p>⚠️ 它<b>不</b>作用于名额的清理：那一行是 {@code ZREMRANGEBYSCORE}，
     * 服务端原生操作，不经过 Lua 的逐元素循环。
     */
    private int cleanupBatch = 200;

    /**
     * Redis key 的前缀。
     *
     * <p>★★ <b>{@code {chat}} 这对花括号是 Redis Cluster 的 hash tag，不是随手加的。</b>
     * 三个 Lua 脚本每一个都要同时操作多个 key，而 Cluster 下
     * <b>跨 slot 的脚本会直接报 {@code CROSSSLOT} 错误</b>。
     * hash tag 让所有派生 key 落在同一个 slot 里。
     *
     * <p>单机部署下它没有任何作用 —— 但写上零成本，而漏了它的症状是
     * 「换到集群部署的那天突然全部报错」。
     * （同 {@code AsyncConfig} 里「从第一天起就用专用线程池隔离」的思路。）
     *
     * <p>★ 测试可以改它来做隔离：Redis <b>没有事务回滚</b>，
     * {@code @Transactional} 对 {@code ./mvnw test} 里的 Redis 测试完全无效。
     */
    private String keyPrefix = "xbla:rl:{chat}";

    /**
     * Pub/Sub 的频道名，用来广播「有位置空出来了」。
     *
     * <p>★ <b>一个公共频道，不是每个请求一个频道。</b>
     * 后者需要动态增删监听器，而 {@code RedisMessageListenerContainer} 的
     * add/remove 是<b>带锁</b>的 —— 高并发下它自己会变成瓶颈，而且漏删一个就是内存泄漏。
     *
     * <p>代价是「本机惊群」：一次广播唤醒本实例的<b>所有</b>等待者，
     * 它们各自跑一次 acquire 脚本去抢，只有一个能赢。
     * 在 100 个等待者的规模下这是 100 个轻量 Lua，Redis 处理它们是微秒级 ——
     * 换来的是「不可能泄漏监听器」。真到了上千等待者再改也不迟。
     */
    private String channel = "xbla:rl:events";

    /** 排队等待专用的线程池参数 */
    private QueuePool queuePool = new QueuePool();

    /** Pub/Sub 唤醒（阶段 6.4） */
    private Wakeup wakeup = new Wakeup();

    /**
     * Pub/Sub 唤醒 —— <b>本类里唯一一个「关掉它功能仍然完全正确」的开关</b>。
     *
     * <h2>★ 它存在的唯一目的是让一句话可验证</h2>
     *
     * <p>「Pub/Sub 是优化，轮询是正确性来源」—— 这句话如果只是一个声明，
     * 它和「我们觉得应该是这样」没有区别。有了这个开关，它才变成一个<b>能跑的实验</b>：
     *
     * <pre>
     *   开启 → 释放名额后，等待者被唤醒的时间 ≈ 1ms
     *   关闭 → 同样的一批请求，同样全部成功，只是唤醒时间 ≤ poll-interval
     *   ★ 两次的【结果集】必须完全一致 —— 不一致就说明轮询不是真正的兜底
     * </pre>
     *
     * <h2>⚠️ 关掉它【不会】让 xbla.ratelimit.enabled 变成 false</h2>
     *
     * <p>两个开关管的是完全不同的东西，别把它们混起来：
     *
     * <pre>
     *   xbla.ratelimit.enabled = false          →  整个排队限流不工作（不抢名额、不排队）
     *   xbla.ratelimit.wakeup.enabled = false   →  排队限流照常工作，只是唤醒靠轮询
     * </pre>
     *
     * <p>★ 前者的实验问题是「排队限流对答案质量有没有影响」（阶段 7 要跑的 A/B），
     * 后者的实验问题是「Pub/Sub 到底有没有在起作用」。两个都要能单独回答。
     */
    @Data
    public static class Wakeup {

        /**
         * 是否订阅 Redis 频道来唤醒等待者。
         *
         * <p>★ 这个字段被<b>两个地方</b>读，而且它们必须一致：
         * <ul>
         *   <li>{@code RedisSignalConfig} 上的 {@code @ConditionalOnProperty} —— 决定
         *       那个 Bean <b>存不存在</b>（直接从环境读，不经过本类）；</li>
         *   <li>探针的快照 —— 回答「现在生效的到底是什么」。</li>
         * </ul>
         *
         * <p>⚠️ 这看起来像「同一个值有两处来源」，但属性名完全相同、都由 Spring 绑定，
         * 所以它们不可能不一致。而且 {@code ignoreUnknownFields = false}
         * 会把「名字写错」变成启动失败，所以连「静默地不一致」的机会都没有。
         */
        private boolean enabled = true;

        /**
         * 订阅断了、或者一开始就没连上时，<b>多久重试一次</b>。
         *
         * <p>★ 这个值有<b>两个用途</b>，因为它们本来就是同一件事：
         * <ol>
         *   <li>传给容器当它内部的重连退避间隔；</li>
         *   <li>{@code PermitSignalSubscriber} 的监督循环用它当检查周期
         *       —— <b>包括「还没订阅上」时的重试间隔</b>。</li>
         * </ol>
         *
         * <p>⚠️ 后者是必要的：实测发现容器自己的「无限重试」<b>覆盖不了
         * 「从一开始就没连上」</b>那个场景（详见 {@code PermitSignalSubscriber}
         * 的类注释 ②）。所以重试这件事由我们自己负责，而这个值就是我们的节奏。
         *
         * <p>★ 它<b>不是</b>正确性参数（不像 {@code permitTtl}）——
         * 调大只是订阅恢复得慢一点，不影响任何结果。
         * 所以它是可配置的，也因此测试能把它调到 100ms 来验证重试真的在发生。
         */
        private Duration retryInterval = Duration.ofSeconds(5);
    }

    /**
     * 排队等待线程池。
     *
     * <h2>★★ 为什么必须单独一个池，而且它的容量必须大于「预期并发」</h2>
     *
     * <p>等待中的请求<b>不能</b>占着 {@code answerExecutor}：那个池是
     * core=8 / max=32 / queue=16，<b>最多只接得住 48 个请求</b>，
     * 第 49 个直接抛 {@code TaskRejectedException}。100 并发压测会有一半变成失败 ——
     * 而阶段 6 的全部目的就是「让用户有序等待，而不是随机失败」。
     *
     * <h2>★★ 这里的 {@code queueCapacity} 刻意【不做成配置】</h2>
     *
     * <p>它是 {@code 0}，而且<b>不能改</b>：
     *
     * <pre>
     *   queueCapacity &gt; 0  →  等待中的任务会静静地躺在执行器自己的队列里，
     *                         【没有任何线程在跑它们】→ 没人能推 SSE 位置
     *                         → 用户看到的是一个空白页面
     *   queueCapacity = 0  →  走 SynchronousQueue，任务立刻拿到线程
     * </pre>
     *
     * <p>而「空白页面」正是 {@code AsyncConfig} 里 {@code answerExecutor} 那段注释
     * 明确否决过的东西：<i>「排队的请求在用户那边就是『一个空白页面』，
     * 没有任何反馈。与其让用户干等，不如快速拒绝」</i>。
     * 阶段 6 之所以能反过来做，就是因为我们<b>能推位置</b>了 ——
     * 而 {@code queueCapacity} 一改，这个能力就没了，且<b>不会有任何报错</b>。
     *
     * <p>★ 所以它不出现在 {@code application.yml} 里。一个「改了会静默破坏功能」
     * 的参数，不该给出配置项。
     */
    @Data
    public static class QueuePool {

        /**
         * 常驻线程数。
         *
         * <p>32 是「稳态并发 × 若干」的估计：稳态 8 个在跑（{@link #permits}），
         * 加上若干在排队。★ 注意线程池的增长逻辑是
         * <b>核心满了先入队、队列满了才扩容</b> —— 而这里队列是 0，
         * 所以它会立刻一路扩到 {@code maxPoolSize}，core 的实际意义只是「不回收的常驻数」。
         */
        private int corePoolSize = 32;

        /**
         * 峰值上限 —— 它才是真正的「最多允许多少人在等」。
         *
         * <p>120 个等待线程，每个线程只是 {@code sleep} + 一次 Redis 往返，
         * 栈内存约 1MB（虚拟内存，不是常驻），完全可接受。
         *
         * <p>⚠️ 超过这个数会抛 {@code TaskRejectedException} ——
         * 那必须变成一句诚实的「当前排队人数过多」（由 {@code ChatController} 捕获后
         * 发 {@code failed} 事件），<b>不能</b>落到兜底的 500 JSON。
         * 因为此时 SSE 响应还没提交，用户会收到一个和流式协议无关的错误。
         */
        private int maxPoolSize = 128;
    }
}
