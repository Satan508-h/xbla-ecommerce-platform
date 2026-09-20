package com.xbla.rag.ratelimit;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本 JVM 当前持有哪些名额、还在等哪些名额 —— 看门狗的<b>数据来源</b>。
 *
 * <h2>它为什么必须存在</h2>
 *
 * <p>续期的本质是「重复地告诉 Redis：我还在」。而<b>「我」是谁</b>这个问题
 * Redis 答不了 —— 它只知道自己存了哪些 traceId，不知道哪些进程还活着。
 * 所以「哪些名额该被续期」这个信息<b>只能由持有者自己维护</b>。
 *
 * <p>★ 反过来也成立，而且这才是关键：
 * <b>续期列表里没有的东西，就是「没人管」的东西</b> —— 它会自然过期。
 * 进程被强杀时这个集合随进程消失，于是 45 秒后那些名额自动回收。
 * <b>名额回收不依赖任何清理代码被执行</b>（验收标准 3）。
 *
 * <h2>★★★ 一个会让名额【凭空出现】的陷阱 —— 而顺序保护不了它</h2>
 *
 * <p>看门狗做的是 {@code ZADD slots <新过期时间> <traceId>} ——
 * 而 <b>ZADD 在成员不存在时会把它【创建】出来</b>。
 *
 * <p>最初的想法是靠 <b>删除顺序</b>来防：{@link ChatPermitService#release}
 * 里先 {@code forget} 再 {@code ZREM}，这样「心跳能不能看到它」和
 * 「Redis 里有没有它」不会同时为真。
 *
 * <p>★★ <b>那个保护是不完整的，实测在真机上漏了。</b>
 * 因为心跳不是一次原子操作，而是「取快照 + 逐个写」两步 ——
 * 而这两步之间可以完整地跑完一次 {@code release}：
 *
 * <pre>
 *   t0  心跳：heldSnapshot() → 快照里有 X      ← 快照是【旧的】
 *   t1  release(X)：forget(X)                  ← 本机忘了
 *   t2  release(X)：ZREM slots X               ← Redis 也删了
 *   t3  心跳：ZADD slots X (now + ttl)         ← 用旧快照把它【复活】
 * </pre>
 *
 * <p>净结果：名额表里躺着一个<b>本机不认领</b>的条目 —— 没人续期、没人释放，
 * 占着容量直到 TTL 到期。症状是「容量莫名其妙少了一个」，
 * <b>而且没有任何日志、异常或指标能看出来</b>。
 *
 * <p>（阶段 6.9 实测：12 轮 × 100 并发复现 1 次，那一轮的有效并发从 8 掉到 ~5。
 * 快照里 7 个孤儿条目的 score <b>完全相同</b>，那是「一次心跳批量写入」的指纹。）
 *
 * <p>★ 结论：<b>靠顺序保护不了跨两步的窗口，只能让那一步本身不可能做错。</b>
 * 所以续期改走 {@code renew.lua} —— 先 {@code ZSCORE} 判定成员还在，才延长。
 * 本类因此退回它本来的职责：<b>它只是「谁该被续期」的数据来源，
 * 不承担任何正确性保证</b>。
 *
 * <h2>为什么是内存态，不需要分布式</h2>
 *
 * <p>它在语义上就是「<b>本进程</b>持有的一切」。多实例部署时，
 * 每个实例各有一份，各续各的 —— 这正是我们想要的，
 * 因为「谁该续期」天然是进程局部的知识。
 * 做成分布式的反而会引入新问题：谁来清理已死实例的记录。
 */
@Component
public class LocalPermitRegistry {

    /** 已被授予名额、正在跑或即将跑的 */
    private final Set<String> held = ConcurrentHashMap.newKeySet();

    /** 已进入队列、还在等名额的 */
    private final Set<String> waiting = ConcurrentHashMap.newKeySet();

    /**
     * 标记为「已持有名额」。
     *
     * <p>★ 它会<b>同时</b>从 waiting 里移走。理由很实际：
     * 「从排队变成持有」是同一个 traceId 的状态迁移，
     * 如果两个集合各自增删，迟早会出现一个 id 同时在两边
     * （那样它会被续期，又被算进等待数）。
     * 让状态迁移只有一个入口，比让调用方记得删两次可靠。
     */
    public void markHeld(String traceId) {
        waiting.remove(traceId);
        held.add(traceId);
    }

    /** 标记为「正在排队」。同理，会从 held 里移走 */
    public void markWaiting(String traceId) {
        held.remove(traceId);
        waiting.add(traceId);
    }

    /**
     * 彻底忘掉一个 traceId（正常结束、断线、被拒绝都走这里）。
     *
     * <p>⚠️ 这个方法必须在<b>释放 Redis 名额之前</b>调用，见类注释第二节。
     */
    public void forget(String traceId) {
        held.remove(traceId);
        waiting.remove(traceId);
    }

    /**
     * 当前持有的名额（快照）。
     *
     * <p>★ 返回的是<b>拷贝</b>而不是视图：看门狗会遍历它并逐个发 Redis 命令，
     * 而遍历期间请求可能正在结束、集合正在被修改。
     * 一个并发修改的视图会让「续期」这件事的结果变得看运气。
     * 拷贝的代价是每次心跳一次数组复制 —— 元素个数就是并发数（个位数到几十），可忽略。
     */
    public Set<String> heldSnapshot() {
        return Set.copyOf(held);
    }

    public int heldCount() {
        return held.size();
    }

    public int waitingCount() {
        return waiting.size();
    }

    public boolean isHeld(String traceId) {
        return held.contains(traceId);
    }

    public boolean isWaiting(String traceId) {
        return waiting.contains(traceId);
    }

    /**
     * 清空全部本地登记。
     *
     * <h2>★ 它不是「测试专用后门」，虽然测试确实需要它</h2>
     *
     * <p><b>测试为什么需要</b>：这个类是 Spring 单例，而 {@code @AfterEach} 只能清 Redis
     * （Redis 没有回滚，Spring 的 {@code @Transactional} 管不到它）。
     * 本地登记表没人清的话，<b>上一个测试留下的 traceId 会在下一个测试
     * 调 {@code renewHeld()} 时被 ZADD 回名额表</b> ——
     * 于是「释放后名额应该是 0」的断言看到 3，而失败信息指向断言，不指向泄漏。
     * （这个症状是本类第一次跑测试时真实出现的。）
     *
     * <p><b>生产为什么可能需要</b>：留着它是因为「登记表只由 {@code release} 清理」
     * 这条规则<b>没有兜底</b>。任何一条漏掉 {@code release} 的异常路径
     * （比如某个 finally 没写全）都会留下一个<b>永久条目</b> ——
     * 心跳会一直替它续期，于是名额表里永久多一个占位者。
     * 那种情况下，能在不重启应用的前提下清掉它是有价值的。
     *
     * <p>⚠️ <b>它的语义是「放弃对本地所有名额的续期」</b>，不是「释放它们」——
     * Redis 里的名额不动，它们会在 {@code permit-ttl} 之后自然过期。
     * 调用方（探针 / 测试）负责知道自己在做什么。
     *
     * <p>★ 注意它<b>不会</b>去释放 Redis —— 那正是重点：
     * 如果这里顺手 ZREM 了，它就变成了一个「批量释放别人可能还在用的名额」的接口，
     * 而那是一个能让正在跑的请求静默失去名额的操作。
     */
    public void clearAll() {
        held.clear();
        waiting.clear();
    }
}
