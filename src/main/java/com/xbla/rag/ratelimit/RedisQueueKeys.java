package com.xbla.rag.ratelimit;

import com.xbla.rag.config.RateLimitProperties;

import java.util.List;

/**
 * 排队限流用到的全部 Redis key 名 —— <b>单一出处</b>。
 *
 * <h2>★ 为什么要有这个类，而不是各处字符串拼一拼</h2>
 *
 * <p>三个 Lua 脚本各自声明了 {@code KEYS[1]} / {@code KEYS[2]} / {@code KEYS[3]}，
 * 而 Java 侧要按<b>同样的顺序</b>把它们传进去。如果 key 名分散在
 * 「脚本加载处」「探针」「测试」三个地方各写一遍，那么某天有人改了其中一处的前缀，
 * 症状是<b>另一个地方静默地读写了一组不存在的 key</b> ——
 * 没有报错、没有异常，只是队列永远是空的、名额永远抢得到。
 *
 * <p>同 {@code ToolField}「参数名只能写一次」的思路（ADR-057）：
 * <b>两处各写一份的漂移是静默的，所以从结构上让它不可能发生。</b>
 *
 * <h2>★★ 花括号 {@code {chat}} 是 Redis Cluster 的 hash tag</h2>
 *
 * <p>三个脚本每一个都要同时操作多个 key。Cluster 模式下
 * <b>跨 slot 的脚本会被直接拒绝</b>（{@code CROSSSLOT} 错误）——
 * 而单机模式下它没有任何作用，所以这是一个「本地全对、上集群全崩」的坑。
 * hash tag 让它从第一天起就是对的。
 *
 * <p>⚠️ 四个 key <b>必须共用同一个 tag</b>。写成 {@code xbla:rl:{chat}:slots} 和
 * {@code xbla:rl:{queue}:queue} 会让它们落到不同的 slot —— 比不加 tag 更糟，
 * 因为它看起来是加了。
 *
 * @param slots   名额表。成员 = traceId，score = <b>过期毫秒</b>（自描述，能自己清自己）
 * @param queue   排队表。成员 = traceId，score = <b>入队序号</b>（不是时间戳，见下）
 * @param alive   存活表。成员 = traceId，score = 最后心跳毫秒
 * @param seq     单调递增计数器，给 {@code queue} 分配序号
 * @param channel Pub/Sub 频道。<b>它不是 key</b>，走 Lua 的 {@code ARGV} 而不是 {@code KEYS}
 */
public record RedisQueueKeys(
        String slots,
        String queue,
        String alive,
        String seq,
        String channel) {

    /** 从配置构造。应用里唯一的构造入口。 */
    public static RedisQueueKeys from(RateLimitProperties props) {
        return new RedisQueueKeys(
                props.getKeyPrefix() + ":slots",
                props.getKeyPrefix() + ":queue",
                props.getKeyPrefix() + ":alive",
                props.getKeyPrefix() + ":seq",
                props.getChannel());
    }

    /**
     * 四个 key（<b>不含</b> channel —— 它不是 key，{@code DEL} 不动它）。
     *
     * <p>★ 给测试清理用的：Redis 没有事务回滚，
     * {@code @Transactional} 管不到它，所以测试必须自己删干净。
     */
    public List<String> allKeys() {
        return List.of(slots, queue, alive, seq);
    }
}
