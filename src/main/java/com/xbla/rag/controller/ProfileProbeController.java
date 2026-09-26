package com.xbla.rag.controller;

import com.xbla.rag.config.AgentProperties;
import com.xbla.rag.rag.profile.UserAffinity;
import com.xbla.rag.rag.profile.UserAffinityProvider;
import com.xbla.rag.rag.prompt.RagPromptBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户偏好的调试探针（阶段 9.5）。
 *
 * <p>⚠️ {@code @Profile("local")} —— 和另外几个探针一样只在本地存在。
 * ★ <b>本接口不调模型、不写库</b>，所以随便打。
 *
 * <h3>接口</h3>
 * <pre>
 *   # 看某个人现在会拿到哪一段偏好（就是拼进 prompt 的那一段原文）
 *   curl -s "localhost:8080/api/debug/profile/affinity?userId=8" | python -m json.tool
 * </pre>
 *
 * <h3>★★ 它回答的那两个问题</h3>
 *
 * <ol>
 *   <li><b>这个人到底有没有偏好</b> —— 以及「没有」是哪一种没有：
 *       匿名 / 用户不存在 / 订单不足 {@code min-orders} / 开关关掉。
 *       这四种在 {@code qa_log.affinity} 上都写作 <b>NULL</b>（那是对的：
 *       对下游它们是同一件事），所以<b>只有在这里</b>才分得开。</li>
 *   <li><b>那一段长什么样</b> —— 模型看到的就是这个字符串，一个字都不差
 *       （渲染走的是 {@link RagPromptBuilder#affinitySection}，
 *       也就是 {@code ChatServiceImpl} 调的那同一个 public static 方法）。
 *       ★ 探针里再写一遍渲染 = 第二个事实来源，而它和 prompt 的漂移是静默的。</li>
 * </ol>
 *
 * <p>★ 为什么值得单独一个探针：偏好块的失败形态<b>全部是静默的</b> ——
 * 没有异常、没有 WARN、回答读起来完全正常，只是「个性化没生效」。
 * 而最省事的排查方式（问一句话看回答）是<b>花钱的</b>，而且看不准。
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/profile")
@Profile("local")
public class ProfileProbeController {

    private final UserAffinityProvider userAffinityProvider;
    private final AgentProperties agentProperties;

    public ProfileProbeController(UserAffinityProvider userAffinityProvider,
                                  AgentProperties agentProperties) {
        this.userAffinityProvider = userAffinityProvider;
        this.agentProperties = agentProperties;
    }

    /**
     * 看一个人现在的偏好块。
     *
     * @param userId 身份（{@code X-Xbla-User-Id} 里那个值）。
     *               <b>传 null 就是「匿名」那一格</b>，也是要能看到的一种状态
     */
    @GetMapping("/affinity")
    public Map<String, Object> affinity(@RequestParam(name = "userId", required = false) Long userId) {
        AgentProperties.Profile config = agentProperties.getProfile();
        UserAffinity affinity = userAffinityProvider.load(userId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", userId);
        response.put("hasAffinity", !affinity.isEmpty());

        // ★★ 开关与口径一起报出来：「没有偏好」这件事的四种原因里，
        //    有两种只有看配置才能排除（关掉了 / 阈值调高了）
        Map<String, Object> configView = new LinkedHashMap<>();
        configView.put("enabled", config.isEnabled());
        configView.put("minOrders", config.getMinOrders());
        configView.put("windowDays", config.getWindowDays());
        configView.put("maxOrders", config.getMaxOrders());
        response.put("config", configView);

        // ── 派生出来的事实（结构化，给程序看）──
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("orderCount", affinity.orderCount());
        facts.put("itemCount", affinity.itemCount());
        facts.put("categories", counts(affinity.categories()));
        facts.put("brands", counts(affinity.brands()));
        facts.put("priceMin", money(affinity.priceMin()));
        facts.put("priceMax", money(affinity.priceMax()));
        facts.put("priceAvg", money(affinity.priceAvg()));
        facts.put("memberLevel", affinity.memberLevel());
        response.put("facts", facts);

        // ── ★ 渲染出来的那段原文（给人和模型看）──
        //   ★★ 它是【逐字】拼进 prompt 的那一段，不是这里重新写的
        response.put("section", affinity.isEmpty()
                ? null : RagPromptBuilder.affinitySection(affinity));

        response.put("note", affinity.isEmpty()
                ? "没有偏好块。四种正常原因：① enabled=false；② userId 为空（匿名）；"
                + "③ 这个 id 在 app_user 里不存在；④ 近 window-days 天内的有效订单"
                + "（status 20/30/40）少于 min-orders。"
                + "★ 这四种在 qa_log.affinity 上都是 NULL —— 想看是哪一种，"
                + "把日志调到 DEBUG 看 UserAffinityProvider 那一行。"
                : "这就是拼进 system prompt 固定段的那一段原文（在摘要之后、硬数据之前）。"
                + "★ qa_log.affinity 存的是同一个字符串。");
        return response;
    }

    private static List<Map<String, Object>> counts(List<UserAffinity.Count> source) {
        List<Map<String, Object>> out = new ArrayList<>(source.size());
        for (UserAffinity.Count item : source) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", item.name());
            m.put("count", item.count());
            out.add(m);
        }
        return out;
    }

    private static String money(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
