package com.xbla.rag.controller;

import com.xbla.rag.common.ApiResponse;
import com.xbla.rag.dto.UserEventRequest;
import com.xbla.rag.service.UserEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 浏览器行为事件上报 —— {@code POST /api/events}（阶段 9.6b）。
 *
 * <h3>★★★ 它是本项目唯一的【生产可见写端点】，而这是想清楚了才开的</h3>
 *
 * <p>其余所有写路径（{@code /api/chat}、{@code /api/kb/**}）要么在问答链路上、
 * 要么有各自的守卫，而 {@code /api/debug/**} 那 10 个控制器
 * <b>在生产下根本不注册</b>。这个端点是新的：<b>公网上任何人都能往里写。</b>
 *
 * <p>★ 之所以接受它，是因为三件事同时成立：
 *
 * <pre>
 *   ① 它【不花钱】—— 不调模型、不碰问答链路
 *   ② 它【不影响任何人】—— 只往一张只增的分析表里写行
 *   ③ 它【必须存在】—— 埋点只能从浏览器来，服务端观察不到
 * </pre>
 *
 * <p>⚠️ 而它<b>刻意没有</b>限流。见 {@link UserEventService} 的类注释：
 * 把埋点塞进阶段 6 那套名额，等于让刷埋点的人把<b>问答</b>挡在门外 ——
 * 那个代价比「被刷出一堆垃圾行」大得多。
 *
 * <h3>★★ 永远回 200，即使这一条被丢弃了</h3>
 *
 * <p>{@code sendBeacon} <b>不读响应</b>（浏览器把它交给后台就完事），
 * 所以回 400 唯一的效果是在日志里制造噪声、并且让任何重试逻辑
 * 去重试一条本来就不该收的数据。
 *
 * <p>⇒ 结局通过响应体的 {@code outcome} 告诉<b>能读它的人</b>
 * （测试、探针、亲手的 curl）：
 *
 * <pre>
 *   WRITTEN     写进去了
 *   DUPLICATE   幂等命中，没重复写      ← ★ 这两种都回 200
 *   DISCARDED   被校验挡掉了（类型 / 幂等键 / 时间 / 长度 / 体积）
 * </pre>
 *
 * <h3>★ 和 {@code StatusController} 的关系</h3>
 *
 * <p>那个是<b>只读</b>的出口（{@code /api/status/**}），这个是<b>写</b>的
 * （{@code /api/events}）。刻意分成两个类、两个前缀 ——
 * 「{@code /api/status/**} 里没有一行写操作」这句话才能光看前缀就成立。
 * 同 {@code StatusController} 那条「一条有例外的规则，下次就没人照着查了」。
 */
@Slf4j
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final UserEventService userEventService;

    public EventController(UserEventService userEventService) {
        this.userEventService = userEventService;
    }

    /**
     * 记一条行为事件。
     *
     * <pre>
     * curl -s -X POST localhost:8080/api/events -H 'Content-Type: application/json' \
     *   -d '{"eventNo":"demo-1","eventType":"ref_click","traceId":"xxx","occurredAt":"2026-09-26T14:00:00+08:00"}'
     * </pre>
     *
     * <p>★ {@code @RequestBody(required = false)}：<b>空体也是合法输入</b>
     * （回 {@code DISCARDED}）。不加的话 Spring 会抛
     * {@code HttpMessageNotReadableException} → 兜底 handler → <b>400/500</b>，
     * 而一次埋点不该让服务端报错。
     *
     * <p>⚠️ 请求体解析失败（比如畸形 JSON）仍然会 400 —— 那是 Spring 在
     * 进入这个方法<b>之前</b>就抛的，拦不住。接受它：畸形 JSON 不是
     * 「我们的客户端发了点什么」，而是有人在手工打这个接口。
     *
     * @return 永远 {@code code = 0}；结局在 {@code data.outcome} 里
     */
    @PostMapping
    public ApiResponse<Map<String, String>> report(@RequestBody(required = false) UserEventRequest req) {
        UserEventService.Outcome outcome = userEventService.record(req);
        // ★ 回一个对象而不是裸字符串：将来要多带一个字段（比如收到的 eventNo）
        //   时，加键是兼容的，改标量类型不是
        return ApiResponse.ok(Map.of("outcome", outcome.name()));
    }
}
