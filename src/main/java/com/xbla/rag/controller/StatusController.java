package com.xbla.rag.controller;

import com.xbla.rag.common.ApiResponse;
import com.xbla.rag.config.RateLimitProperties;
import com.xbla.rag.dto.RateLimitStatus;
import com.xbla.rag.ratelimit.ChatAdmissionService;
import com.xbla.rag.ratelimit.ChatPermitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
 *     ✗ 请求参数        —— 所以没有「参数传错」这一类问题
 * </pre>
 *
 * <p>★ 这句「没有」是可以被逐行核对的，不是一句承诺 ——
 * 整个类只有下面一个方法，而它只做构造。
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

    public StatusController(ChatPermitService permits,
                            ChatAdmissionService admission,
                            RateLimitProperties rateLimitProperties,
                            @Qualifier("queueExecutor") ThreadPoolTaskExecutor queueExecutor) {
        this.permits = permits;
        this.admission = admission;
        this.rateLimitProperties = rateLimitProperties;
        this.queueExecutor = queueExecutor;
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
}
