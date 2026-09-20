package com.xbla.rag.ratelimit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * 四个 Lua 脚本的加载与调用 —— 全项目<b>唯一</b>直接碰脚本的地方。
 *
 * <p>★ 第四个（{@code renew.lua}）是阶段 6.9 补上的，它修的是一个<b>实测抓到的 bug</b>：
 * 续期用普通 {@code ZADD} 会把已经释放的名额<b>复活</b>（ZADD 在成员不存在时会创建它）。
 * 完整的时间线和实测数据见 {@code renew.lua} 的头部注释。
 *
 * <h2>★★ 为什么 ARGV 全部传字符串，不传数字</h2>
 *
 * <p>{@link StringRedisTemplate} 的 key 和 value 序列化器都是
 * {@code StringRedisSerializer}，它<b>只认识 {@code String}</b>：
 *
 * <pre>
 *   redis.execute(script, keys, 1726800000000L)     // ✗ ClassCastException
 *   redis.execute(script, keys, "1726800000000")    // ✓
 * </pre>
 *
 * <p>而 {@code execute(...)} 的签名是 {@code Object... args} ——
 * <b>编译器不会拦你</b>，传 {@code long} 会被自动装箱成 {@code Long}，
 * 然后在序列化的那一刻炸。
 *
 * <p>所以这里的每个数字都显式 {@code String.valueOf(...)}。
 * 对应的，Lua 侧凡是拿去做算术的都写了 {@code tonumber(ARGV[n])} ——
 * <b>两边各做一半，缺一边就出错</b>，而错的方式是「脚本静默算出一个 nil」或者直接抛异常。
 *
 * <h2>★ 脚本文件放在 {@code resources/lua/} 而不是写成 Java 文本块</h2>
 *
 * <p>三个理由：
 * <ol>
 *   <li><b>能用 {@code redis-cli --eval} 单独跑</b>。这是 S0 的验证方式 ——
 *       脚本的原子性、位置稳定性、幂等这些性质，<b>在没有 Java 的情况下就能验完</b>。
 *       写在文本块里的话，验证它必须先起整个 Spring 上下文。</li>
 *   <li>Lua 的语法高亮在 {@code .lua} 文件里是正常的，在 Java 文本块里是一片白。</li>
 *   <li>脚本里的注释是<b>给读脚本的人</b>看的（它们在讲 Redis 侧的语义），
 *       和 Java 类的注释是两类读者。</li>
 * </ol>
 *
 * <h2>★★ 显式 UTF-8 读取 —— 不能靠默认值</h2>
 *
 * <p>四个脚本里都有<b>中文注释</b>。如果按平台默认编码读（Windows 上是 GBK），
 * 读出来的字符再按 UTF-8 序列化发往 Redis 就是<b>双重编码</b>的乱码。
 *
 * <p>★ 这种乱码<b>大概率不会报错</b>：Lua 注释里的乱码只是注释，会被忽略。
 * 所以它多半会「正常工作」，然后在某一天有人往注释里加了一个引号字符，
 * 双重编码把它变成别的字节，语法就崩了 —— 而那时没人会想到是编码问题。
 *
 * <p>同 {@code CLAUDE.md} 第八节「Windows 上中文会被 shell 破坏」是同一类问题，
 * 只是发生在 Java 这一侧。
 *
 * <h2>★ EVALSHA 是默认行为，不需要额外做</h2>
 *
 * <p>{@link DefaultRedisScript} 自己缓存 SHA1，{@code DefaultScriptExecutor}
 * 先发 {@code EVALSHA}、收到 {@code NOSCRIPT} 再回落 {@code EVAL}。
 * 所以「每次把 3KB 脚本正文发过去」这件事只发生在第一次（以及 Redis 重启后）。
 * 这也是脚本可以放心写长注释的原因。
 */
@Component
public class LuaScripts {

    /** 脚本放在 classpath 的这个目录下 */
    private static final String DIR = "lua/";

    private final StringRedisTemplate redis;

    private final DefaultRedisScript<List> acquireScript;
    private final DefaultRedisScript<Long> releaseScript;
    private final DefaultRedisScript<List> cleanupScript;
    private final DefaultRedisScript<Long> renewScript;

    public LuaScripts(StringRedisTemplate redis) {
        this.redis = redis;
        this.acquireScript = listScript("acquire.lua");
        this.releaseScript = longScript("release.lua");
        this.cleanupScript = listScript("cleanup.lua");
        this.renewScript = longScript("renew.lua");
    }

    // ============================================================
    // 脚本加载
    // ============================================================

    /**
     * 读一个脚本，<b>显式 UTF-8</b>。
     *
     * <p>★ 不用 {@code DefaultRedisScript(Resource)} 的构造器：
     * 那条路会走 Spring 内部的默认字符集，而「默认」在不同版本、
     * 不同平台上是会变的。这里把编码钉死在代码里。
     *
     * <p>读不到就抛 —— 脚本是<b>启动必需</b>的，和 {@code IntentTree} 缺文件
     * 「启动即崩」同一条理由：没有合理的回落值。
     * <b>一个缺少 acquire.lua 的服务，不是一个「功能弱一点」的服务，是一个会超卖的服务。</b>
     */
    private static String read(String name) {
        Resource resource = new ClassPathResource(DIR + name);
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Lua 脚本读取失败：" + DIR + name
                            + " —— 它是启动必需的，没有回落值。"
                            + "请确认它被打进了 classpath（src/main/resources/lua/）。", e);
        }
    }

    private static DefaultRedisScript<List> listScript(String name) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(read(name));
        script.setResultType(List.class);
        return script;
    }

    private static DefaultRedisScript<Long> longScript(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(read(name));
        script.setResultType(Long.class);
        return script;
    }

    // ============================================================
    // 调用
    // ============================================================

    /**
     * 尝试获取名额。空位就直接占，满了就排队并返回位置，队列也满就如实拒绝。
     *
     * @return 三元素列表 {@code [status, position, queueSize]}，见 {@link PermitState}
     * @throws IllegalStateException 脚本返回了意料之外的形状（见下）
     */
    public List<Long> acquire(RedisQueueKeys keys, String traceId,
                              long nowMs, long ttlMs, int maxPermits, int maxQueue) {
        List<Long> raw = redis.execute(
                acquireScript,
                // ★ KEYS 的顺序必须和 acquire.lua 里的 KEYS[1..4] 一一对应。
                //   顺序错了不会报错，只会让脚本去操作一组别的 key。
                keys.allKeys(),
                // ★ 全部 String.valueOf —— 见类注释第一节
                traceId,
                String.valueOf(nowMs),
                String.valueOf(ttlMs),
                String.valueOf(maxPermits),
                String.valueOf(maxQueue));

        return requireShape(raw, 3, "acquire.lua");
    }

    /**
     * 释放自己占用的名额（并把自己从队列和存活表里摘掉），然后广播。
     *
     * @return 真正释放掉的名额数：<b>1 = 确实持有过，0 = 本来就没持有</b>。
     *         ★ 0 值得记一条 WARN —— 它意味着调用方以为自己在占名额，其实没有
     *         （重复释放，或者名额因为续期失败已被回收）。
     */
    public long release(RedisQueueKeys keys, String traceId) {
        Long freed = redis.execute(
                releaseScript,
                List.of(keys.slots(), keys.queue(), keys.alive()),
                traceId,
                keys.channel());

        if (freed == null) {
            // 脚本在 Redis 侧出错时 execute 返回 null。不吞掉 ——
            // 释放失败意味着名额可能泄漏到 TTL 到期为止，这件事必须被看见。
            throw new IllegalStateException("release.lua 返回 null，traceId=" + traceId);
        }
        return freed;
    }

    /**
     * 清扫两类僵尸：名额表里过期的，和队列里停止心跳的。
     *
     * @param batchSize 单次最多清多少个<b>排队</b>僵尸（名额不受它限制，见 cleanup.lua）
     * @return 两元素列表 {@code [清扫的名额数, 清扫的排队僵尸数]}
     */
    public List<Long> cleanup(RedisQueueKeys keys, long nowMs, long aliveCutoffMs, int batchSize) {
        List<Long> raw = redis.execute(
                cleanupScript,
                List.of(keys.slots(), keys.queue(), keys.alive()),
                String.valueOf(nowMs),
                String.valueOf(aliveCutoffMs),
                String.valueOf(batchSize));

        return requireShape(raw, 2, "cleanup.lua");
    }

    /**
     * 给本机持有的名额续期 —— <b>只在它【已经存在】时延长，绝不创建</b>。
     *
     * <p>★★★ 这个「不创建」是它存在的全部理由。用普通 {@code ZADD} 续期的话，
     * 一个刚被 {@code release} 掉的名额会被心跳用<b>过期快照</b>复活 ——
     * 时间线和实测数据见 {@code renew.lua} 的头部注释。
     *
     * @return {@code 1} = 续期成功；{@code 0} = <b>它已经不在名额表里了</b>。
     *         调用方必须把 0 当成一件值得留痕的事 ——
     *         它意味着「我以为我还在占着名额，其实早就没有了」，而在那种状态下
     *         我们<b>可能正在超卖</b>（那个名额已经被回收、甚至已被别人合法抢走）。
     */
    public long renew(RedisQueueKeys keys, String traceId, long nowMs, long ttlMs) {
        Long result = redis.execute(
                renewScript,
                // ★ 只传两个 key：续期不碰 queue（持有者本来就不在队列里）
                List.of(keys.slots(), keys.alive()),
                traceId,
                String.valueOf(nowMs),
                String.valueOf(ttlMs));

        if (result == null) {
            // 同 release.lua：脚本出错时 Redis 返回 nil 而不是抛异常。
            // 吞掉它会让「续期静默失败」变成一个没人知道的事 —— 而过 TTL 之后
            // 我们就会在超卖，且完全不知情。
            throw new IllegalStateException("renew.lua 返回 null，traceId=" + traceId);
        }
        return result;
    }

    /**
     * 校验脚本返回值的形状。
     *
     * <p>★ 这一段不是防御性编程的仪式感，它挡的是一个**具体的**失效模式：
     * Lua 脚本出错（语法、类型、写错了 key）时 Redis <b>不返回错误给调用方</b> ——
     * 它返回 <b>nil</b>，而 {@code execute} 把 nil 变成 {@code null}，
     * 或者返回一个长度不对的列表。
     *
     * <p>如果我们只写 {@code raw.get(0)}，那么脚本坏掉的表现就是
     * <b>一个 {@code IndexOutOfBoundsException}，堆栈指向这里</b> ——
     * 而真正的原因在 3KB 之外的 Lua 文件里。
     * 把「形状不对」显式说出来，排查方向就不会被带偏。
     */
    private static List<Long> requireShape(List<Long> raw, int expected, String scriptName) {
        if (raw == null) {
            throw new IllegalStateException(
                    scriptName + " 返回 null —— 通常是脚本执行出错（Redis 对 Lua 错误不抛异常，返回 nil）");
        }
        if (raw.size() != expected) {
            throw new IllegalStateException(
                    scriptName + " 返回值长度是 " + raw.size() + "，期望 " + expected
                            + "。脚本被改坏了？实际内容：" + raw);
        }
        return Collections.unmodifiableList(raw);
    }
}
