package com.xbla.rag.controller;

import com.xbla.rag.common.ApiResponse;
import com.xbla.rag.config.RateLimitProperties;
import com.xbla.rag.dto.MetricsSnapshot;
import com.xbla.rag.dto.RateLimitStatus;
import com.xbla.rag.ratelimit.ChatAdmissionService;
import com.xbla.rag.ratelimit.ChatPermitService;
import com.xbla.rag.service.MetricsQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外的只读状态端点（阶段 8）—— 供前端的「限流实况」页使用。
 *
 * <h3>★★★ 它和 {@code /api/debug/**} 的区别是【存不存在】</h3>
 *
 * <p><b>8</b> 个 {@code @Profile("local")} 的探针控制器在生产 profile 下
 * <b>不会被注册</b>。它们能无条件消耗 API 额度（{@code /api/debug/llm/chat}），
 * 或者改状态（{@code POST /api/debug/ratelimit/leak}、{@code /reset}），
 * 所以它们绝不能跟着公网隧道一起出去。
 *
 * <p>而阶段 8 要展示阶段 6 的成果（名额、队列、线程池），
 * 于是需要一个<b>生产下也存在、且只读</b>的出口。就是这个类。
 *
 * <pre>
 *   这个类里【没有】任何：
 *     ✗ 模型调用        —— 所以不花钱
 *     ✗ 写库 / 写 Redis —— 所以重复调用无副作用
 *     ✗ 第二个请求参数   —— 见下
 * </pre>
 *
 * <p>★★ <b>这一栏在 9.6b 改过一次，改动的形状值得记下来。</b>
 * 加 {@code /metrics} 之前它写的是「<b>✗ 请求参数</b> —— 所以没有『参数传错』
 * 这一类问题」，而那条在加 {@code window} 之后<b>当场变成假话</b>。
 *
 * <p>★ 它不会报错、不会有测试变红 —— 只会让下一个读到这里的人
 * 以为「这个端点没有参数要校验」。所以现在写的是<b>「✗ 第二个」</b>：
 * 一条可以逐行核对的断言，而不是一句会过期的赞美。
 *
 * <p>⚠️ 参数到了两个以上就该重新想 —— 见 {@link #metrics} 的注释。
 *
 * <h3>★ 为什么放在 {@code /api/status} 而不是 {@code /api/debug/status}</h3>
 *
 * <p>因为路径本身是有含义的：{@code /api/debug/**} 在本项目里已经等于
 * 「local profile 专用、可以花钱、可以改状态」。把一个生产端点放进去，
 * 会让那个含义失效，也会让「公网上 {@code /api/debug/**} 必须 404」
 * 这条验收判据变成一条有例外的规则。
 * <b>一条有例外的规则，下次就没人照着查了。</b>
 */
@Slf4j
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final ChatPermitService permits;
    private final ChatAdmissionService admission;
    private final RateLimitProperties rateLimitProperties;
    private final ThreadPoolTaskExecutor queueExecutor;
    private final MetricsQueryService metricsQueryService;

    public StatusController(ChatPermitService permits,
                            ChatAdmissionService admission,
                            RateLimitProperties rateLimitProperties,
                            @Qualifier("queueExecutor") ThreadPoolTaskExecutor queueExecutor,
                            MetricsQueryService metricsQueryService) {
        this.permits = permits;
        this.admission = admission;
        this.rateLimitProperties = rateLimitProperties;
        this.queueExecutor = queueExecutor;
        this.metricsQueryService = metricsQueryService;
    }

    /**
     * 排队限流的当前状态。
     *
     * <pre>
     * curl -s localhost:8080/api/status/ratelimit | python -m json.tool
     * </pre>
     *
     * <p>字段取舍（给什么、不给什么、以及为什么）写在
     * {@link RateLimitStatus} 的类注释里 —— 那是一份<b>白名单</b>。
     *
     * <p>★ 数据来源全部是<b>类型化</b>的取值口（{@code permitsInUse()} /
     * {@code queueSize()} / {@code config()}），<b>没有一个字符串键名</b>。
     * 从 map 里按键名取值的做法在这里被刻意避开了：
     * 键名改名不会有任何编译错误，只会让状态页上出现一个看起来很正常的 0。
     */
    @GetMapping("/ratelimit")
    public ApiResponse<RateLimitStatus> rateLimit() {
        ChatPermitService.ConfigSnapshot config = permits.config();

        return ApiResponse.ok(new RateLimitStatus(
                config.enabled(),
                config.permits(),
                permits.permitsInUse(),
                permits.queueSize(),
                config.maxQueue(),
                config.permitTtlMs(),
                // ★ active 而不是 poolSize，理由见 RateLimitStatus#queuePoolActive
                queueExecutor.getActiveCount(),
                rateLimitProperties.getQueuePool().getMaxPoolSize(),
                admission.givenUpTotal()));
    }

    /**
     * 在线指标快照（阶段 9.6b）。
     *
     * <pre>
     * curl -s "localhost:8080/api/status/metrics?window=7d" | python -m json.tool
     * curl -s "localhost:8080/api/status/metrics?window=all" | python -m json.tool
     * </pre>
     *
     * <h3>★ 它是这个类里【唯一】带参数的端点，而参数只有一个</h3>
     *
     * <p>那四条纪律（不调模型 / 不写库 / 参数少 / 字段白名单）在这条上
     * 仍然成立，只是「参数少」从「零个」变成「一个枚举」。
     * 拉长成十个可选参数（按意图筛、按用户筛、按 provider 筛）会让
     * 它变成一个小型的 BI 工具 —— 而那是 {@code docs/11} 那份离线报告
     * 该干的事，不是这条只读出口。
     *
     * <h3>★★ 非法 window 不回 400，替换后明说</h3>
     *
     * <p>同 {@code ChatHistoryQueryServiceImpl.clampLimit}：这是给监控和
     * 探针打的端点，参数写错不该让它整个失败。但<b>替换必须留痕</b> ——
     * 响应里 {@code requestedWindow} 与 {@code window} 会不同，
     * 而且 {@code notes} 里有一句。
     *
     * <p>★ 只留替换后的值的话，「你传错了」和「这个接口只有三个窗口」
     * 在响应里长得一模一样，而前者是调用方要修的 bug。
     *
     * @param window {@code 24h} / {@code 7d} / {@code all}。缺省 {@code 24h}。
     *               ⚠️ 本机实测流量摊在 8 天里，{@code 24h} 常常只有个位数行 ——
     *               想看全貌用 {@code all}
     */
    @GetMapping("/metrics")
    public ApiResponse<MetricsSnapshot> metrics(
            @RequestParam(value = "window", required = false) String window) {
        return ApiResponse.ok(metricsQueryService.snapshot(window));
    }
}
