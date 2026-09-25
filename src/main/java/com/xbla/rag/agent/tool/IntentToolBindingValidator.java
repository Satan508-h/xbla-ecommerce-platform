package com.xbla.rag.agent.tool;

import com.xbla.rag.agent.intent.IntentTree;
import com.xbla.rag.mcp.McpTool;
import com.xbla.rag.mcp.McpToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 意图树里声明的工具名，注册表里必须<b>真有</b>（阶段 9.3）—— 对不上就<b>启动即崩</b>。
 *
 * <h2>★★ 一、为什么不能只跑 IntentTree 的单测</h2>
 *
 * <p>{@link IntentTree} 只校验 {@code tools} 的<b>位置</b>（写在哪一层才读得到），
 * 校验不了<b>名字</b> —— 名字要拿注册表比，而注册表在 {@code mcp} 包。
 * 让意图树依赖注册表会糊掉「意图树只描述用户想干什么」这条边界，
 * 所以那一半落在这里：本类同时看得见两边，是两边的<b>接缝</b>。
 *
 * <p>⚠️ 于是「只跑 {@code IntentTreeTest}」是<b>发现不了拼错的工具名</b>的。
 * 而拼错的代价不是报错，是那个工具<b>静默地从白名单里消失</b>：
 * {@code ToolLoop} 按名字过滤，过滤不到就少一个，模型只是「没这个能力」，
 * 一切日志、指标、落库数据看起来都正常。所以这条校验必须是<b>启动期</b>的。
 *
 * <h2>★★ 二、必须用【本地注册表】，绝不能走 {@code gateway.listTools()}</h2>
 *
 * <p>这是本类最容易写错的一处。看起来走网关更「真实」—— 它验的是模型真正会拿到的那份清单。
 * 但 {@code @PostConstruct} 跑在应用启动的过程中，而 MCP Server 监听的是本机 8080，
 * <b>那时候它还没开始监听</b>。于是网关会回「一个工具都没有」，
 * 而我们的报错会变成：
 *
 * <pre>
 *   ★ 意图树声明了 6 个工具，但它们全都不存在
 * </pre>
 *
 * <p>—— 一个<b>把排查方向指反</b>的结论。真正的原因是「此刻还连不上」，不是「工具没注册」。
 * （同 {@code ToolLoop.resolveToolbox} 那条「连不上 ≠ 没有工具」的区分，
 * 只是那里是运行期、这里是启动期。）
 *
 * <p>本地注册表是 {@code List<McpTool>} 直接收进来的，启动期<b>必然就绪</b>，
 * 而且它才是「我们实现了哪些工具」的事实来源。
 */
@Component
public class IntentToolBindingValidator {

    private static final Logger log = LoggerFactory.getLogger(IntentToolBindingValidator.class);

    private final IntentTree intentTree;
    private final McpToolRegistry registry;

    public IntentToolBindingValidator(IntentTree intentTree, McpToolRegistry registry) {
        this.intentTree = intentTree;
        this.registry = registry;
    }

    /**
     * 启动时对一次账。对不上就抛 —— Spring 会因为 bean 创建失败而终止启动。
     *
     * <p>★ 同 {@link IntentTree} 那条「配置类错误没有合理的回落值」：
     * 一个指向不存在工具的白名单，唯一的「回落」就是悄悄少一个工具，
     * 而那正是我们要防的事。
     */
    @PostConstruct
    void validate() {
        IntentTree.Tree tree = intentTree.get();
        Set<String> registered = new LinkedHashSet<>();
        for (McpTool tool : registry.all()) {
            registered.add(tool.name());
        }

        List<String> problems = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();

        // ── ① 顶层声明的（retrieval=TOOL 那一类）──
        for (IntentTree.TopIntent top : tree.roots()) {
            checkAll(top.code(), top.tools(), registered, declared, problems);
        }
        // ── ② 叶子声明的（KB 类意图的混合轮）──
        for (IntentTree.Leaf leaf : tree.allLeaves()) {
            checkAll(leaf.code(), leaf.tools(), registered, declared, problems);
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "★ 意图树声明了 " + problems.size() + " 处不存在的工具：\n  "
                            + String.join("\n  ", problems)
                            + "\n注册表里现有：" + registered
                            + "\n文件：" + intentTree.path().toAbsolutePath()
                            + "\n★ 工具名是模型调用时的唯一标识，拼错不会报错 ——"
                            + "它只会让那个工具静默地从白名单里消失。");
        }

        // ── ③ 反向：注册了但没有任何意图声明它 ──
        //
        // ★ 这条是 WARN 不是崩，因为「暂时没人用」是合法的中间状态。
        //   但它值得报出来：一个没有任何意图声明的工具，只能通过
        //   /api/debug/mcp/call 调到 —— 也就是【线上不可达】。
        //   9.3 定工具绑定时正是靠这条检查发现 search_products 一开始没有归宿。
        Set<String> orphans = new LinkedHashSet<>(registered);
        orphans.removeAll(declared);
        if (!orphans.isEmpty()) {
            log.warn("★ 有 {} 个已注册的工具没有任何意图声明，只能被调试探针调到：{}",
                    orphans.size(), orphans);
        }

        log.info("意图-工具绑定校验通过：{} 个意图目标声明了 {} 个工具（注册表共 {} 个）",
                countDeclaring(tree), declared.size(), registered.size());
        for (String name : declared) {
            log.debug("  声明且已注册：{}", name);
        }
    }

    private static void checkAll(String code, List<String> tools, Set<String> registered,
                                 Set<String> declared, List<String> problems) {
        for (String tool : tools) {
            declared.add(tool);
            if (!registered.contains(tool)) {
                problems.add("[" + code + "] 声明了工具 " + tool
                        + " —— 注册表里没有这个名字（拼错了？还是那个工具没被 Spring 扫到？）");
            }
        }
    }

    /** 有多少个意图目标声明了至少一个工具。给启动日志一个人能读的数 */
    private static long countDeclaring(IntentTree.Tree tree) {
        long count = tree.roots().stream().filter(t -> !t.tools().isEmpty()).count();
        return count + tree.allLeaves().stream().filter(l -> !l.tools().isEmpty()).count();
    }
}
