package com.xbla.rag.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 —— 把所有 {@link McpTool} Bean 收进一张 {@code name → tool} 的表。
 *
 * <h2>★ 重复的工具名必须是【启动即崩】，不能是「后一个覆盖前一个」</h2>
 *
 * <p>Spring 注入进来的是一个 {@code List<McpTool>}，顺序不保证。
 * 如果两个工具重名而我们只是 {@code put} 进 Map，那么：
 *
 * <pre>
 *   这一轮启动 → A 覆盖 B → 模型调到的是 A
 *   下一轮启动 → B 覆盖 A → 模型调到的是 B
 * </pre>
 *
 * <p>同一个工具名，两次启动的行为不一样，<b>而且没有任何日志</b>。
 * 排查方向会被带到「模型今天怎么不稳定」上，永远查不到这里。
 *
 * <p>所以构造期就查重，撞了就抛 —— 宁可起不来。
 * 同 {@code IntentTree} 缺文件「启动即崩」的理由：<b>配置类错误没有合理的回落值</b>。
 *
 * <h2>★ 为什么按名字排序 —— 以及「排完序」还不够</h2>
 *
 * <p>{@code tools/list} 的返回顺序会进模型的上下文，也会进快照对比。
 * 按名字排序让它在多次启动之间稳定 —— 否则「同一份工具列表」
 * 每次启动的字节都不一样，任何基于 prompt 前缀缓存的优化都会失效。
 * （同 {@code RagPromptBuilder} 的「固定在前」是同一类考虑。）
 *
 * <p>★★ <b>但 5.7 的实现把排好的顺序又弄丢了</b>（5.8 发现并修掉）：
 * 最后一步写的是 {@code Map.copyOf(sorted)}，而它返回的是
 * {@code ImmutableCollections.MapN} —— <b>迭代顺序由 hash 决定，
 * 且 JDK 9+ 用一个每次启动随机生成的 SALT 来扰动 hash</b>。
 *
 * <p>实测（三个独立 JVM，同一份已排序的 LinkedHashMap）：
 * <pre>
 *   run 1: [query_my_coupons, query_order_status, query_inventory, search_after_sale_policy]
 *   run 2: [search_after_sale_policy, query_my_coupons, query_order_status, query_inventory]
 *   run 3: [query_order_status, query_inventory, search_after_sale_policy, query_my_coupons]
 * </pre>
 *
 * <p>5.7 只有<b>一个</b>工具，所以<b>怎么测都测不出来</b>（一个元素的顺序没有反例）。
 * 5.9 加到三个之后，真实启动日志是：
 * <pre>
 *   MCP 工具注册完成，共 3 个：[query_inventory, query_my_coupons, query_order_status]
 * </pre>
 * —— 严格按名字升序。★ 而回归护栏 {@code McpToolRegistryTest} 用的是
 * <b>四个</b>工具名（从上面那份实测里抄的），因为键的集合不同，
 * hash 顺序和字典序的差异更容易暴露 —— 用三个真实工具名反而可能「碰巧对上」。
 *
 * <p>代价不是「不好看」：tools 数组是 Prompt 前缀的一部分，
 * 顺序一变，每次重启后第一个请求就整段未命中 ——
 * {@code cache-hit-input: 0.02} 和 {@code input: 1.0} 差 <b>50 倍</b>。
 *
 * <p>⚠️ 这是本项目第二次踩 {@code Map.copyOf}（第一次是
 * {@link McpToolResult} 的「拒绝 null 值」）。两次的共同点是：
 * <b>「防御性拷贝」是个看起来无害、实际上会悄悄改掉一个你依赖的性质的写法。</b>
 * 用它可以，但要先问一句「我还依赖这个 Map 的什么？」
 */
@Component
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    /**
     * 工具表。<b>必须保持插入顺序</b>，见类注释第二节。
     *
     * <p>⚠️ 这里<b>不能用 {@code Map.copyOf}</b> —— 理由和 {@link McpToolResult}
     * 那次是同一个，但后果不一样：那一次是拒绝 null，这一次是
     * <b>把排好的顺序打乱</b>。见 {@link #all()}。
     */
    private final Map<String, McpTool> tools;

    public McpToolRegistry(List<McpTool> discovered) {
        Map<String, McpTool> byName = new LinkedHashMap<>();

        for (McpTool tool : discovered) {
            McpTool existing = byName.putIfAbsent(tool.name(), tool);
            if (existing != null) {
                throw new IllegalStateException(
                        "MCP 工具名重复：" + tool.name()
                                + " —— 同时来自 " + existing.getClass().getName()
                                + " 和 " + tool.getClass().getName()
                                + "。工具名是模型调用时的唯一标识，重名会让"
                                + "「调到的是哪一个」随 Bean 顺序变化，且没有任何日志。"
                                + "请改掉其中一个的 name()");
            }
        }

        // 按名字排序 —— 见类注释
        Map<String, McpTool> sorted = new LinkedHashMap<>();
        byName.keySet().stream().sorted().forEach(name -> sorted.put(name, byName.get(name)));

        // ★★ 【不能用 Map.copyOf(sorted)】，那会把上面排好的顺序打乱。
        //
        //   实测（三个独立 JVM 各跑一次，含四个工具名的同一份 LinkedHashMap）：
        //     run 1: [query_my_coupons, query_order_status, query_inventory, ...]
        //     run 2: [search_after_sale_policy, query_my_coupons, query_order_status, ...]
        //     run 3: [query_order_status, query_inventory, search_after_sale_policy, ...]
        //
        //   三个都不一样。原因是 JDK 9+ 的 ImmutableCollections 用了一个
        //   【每次 JVM 启动随机生成】的 SALT 来扰动 hash —— 它对「防 hash 碰撞攻击」
        //   是好事，但它让 Map.copyOf 的迭代顺序在【每次重启后】都变。
        //   Map.copyOf 的 javadoc 自己也写着「iteration order is unspecified
        //   and may change between calls」，只是太容易被当成套话。
        //
        //   后果不只是「不好看」：tools 数组是 Prompt 前缀的一部分，
        //   而 DeepSeek 的上下文缓存是【前缀匹配】。顺序一变，
        //   每次重启后的第一个请求就整段未命中 —— cache-hit-input 0.02
        //   和 input 1.0 差 50 倍（见 application.yml 的定价说明）。
        //
        //   Collections.unmodifiableMap 保留顺序，且同样是不可写的。
        this.tools = Collections.unmodifiableMap(sorted);

        log.info("MCP 工具注册完成，共 {} 个：{}", sorted.size(), sorted.keySet());
        for (McpTool tool : sorted.values()) {
            log.debug("  {}", tool.display());
        }
    }

    /** 按名字找工具。找不到返回 {@code null} —— 由调用方决定怎么报错 */
    public McpTool find(String name) {
        return name == null ? null : tools.get(name);
    }

    /** 全部工具，按名字升序 */
    public List<McpTool> all() {
        return List.copyOf(tools.values());
    }

    public int size() {
        return tools.size();
    }
}
