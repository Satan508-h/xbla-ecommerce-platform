package com.xbla.rag.controller;

import com.xbla.rag.common.ApiResponse;
import com.xbla.rag.service.ModelProbeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型接入层的调试探针接口 —— <b>只在 local profile 下存在</b>。
 *
 * <p>它们是阶段 2「一步一验证」策略的载体：每写完一个客户端就能立刻调通它，
 * 不用等 {@code /api/chat} 全部完成。
 *
 * <p><b>★ 安全提示</b>：这些接口可以无条件消耗你的 API 额度，
 * 所以标了 {@code @Profile("local")} —— 只有在本地开发环境
 * （{@code spring.profiles.active} 含 {@code local}）才会注册。
 * 部署到生产时改了 profile 它们就自动消失了，
 * 不需要记得手工删代码。
 *
 * <p>按 CLAUDE.md 的约定，controller 层不写业务逻辑 ——
 * 这里只做参数接收、默认值处理和响应封装，实际动作都在
 * {@link ModelProbeService} 里。
 */
@Slf4j
@RestController
@RequestMapping("/api/debug")
@Profile("local")
public class ModelProbeController {

    private final ModelProbeService probeService;
    private final ThreadPoolTaskExecutor sseExecutor;

    /**
     * 构造器注入需要 {@code @Qualifier} 指定线程池名字 ——
     * 项目里将来会有多个 TaskExecutor（比如阶段 6 的排队任务），
     * 不指名道姓的话 Spring 不知道注入哪个。
     */
    public ModelProbeController(ModelProbeService probeService,
                                @Qualifier("sseExecutor") ThreadPoolTaskExecutor sseExecutor) {
        this.probeService = probeService;
        this.sseExecutor = sseExecutor;
    }

    // ============================================================
    // 链路状态
    // ============================================================

    /** 当前降级链与各熔断器状态 */
    @GetMapping("/llm/chain")
    public ApiResponse<Map<String, Object>> chain() {
        return ApiResponse.ok(probeService.chainStatus());
    }

    // ============================================================
    // 对话
    // ============================================================

    /**
     * 非流式对话。
     *
     * @param model 指定链路条目名（如 {@code deepseek-flash}）则<b>直连</b>那一档；
     *              传 {@code auto} 或不传则走完整降级链
     */
    @GetMapping("/llm/chat")
    public ApiResponse<Map<String, Object>> chat(
            @RequestParam(value = "q") String question,
            @RequestParam(value = "model", required = false) String model) {
        return ApiResponse.ok(probeService.chat(model, question));
    }

    /**
     * 流式对话 —— 用于验证「打字机效果」。
     *
     * <p>用 {@code curl -N} 调，能看到 {@code event: delta} 一行行陆续到达。
     * 如果它们全部在最后一次性出现，说明响应被缓冲了，流式没生效。
     */
    @GetMapping(value = "/llm/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(value = "q") String question,
            @RequestParam(value = "model", required = false) String model) {

        // ★ 超时不能省：Tomcat 异步请求默认 30 秒就会掐断
        SseEmitter emitter = new SseEmitter(180_000L);

        // ★ 用专用线程池，不能图省事用 CompletableFuture.runAsync ——
        //   不传 executor 会用 ForkJoinPool.commonPool()，
        //   阻塞式 HTTP 调用会把它占满，拖垮整个 JVM。见 AsyncConfig。
        sseExecutor.execute(() -> {
            AtomicBoolean closed = new AtomicBoolean(false);
            try {
                Map<String, Object> summary = probeService.chatStream(model, question, delta -> {
                    if (closed.get()) {
                        return;
                    }
                    try {
                        // ★ payload 用 Map + APPLICATION_JSON，不用纯 String：
                        //   纯 String 走 StringHttpMessageConverter，
                        //   默认字符集不是 UTF-8 会让中文变问号，
                        //   而且文本里的 \n 会破坏 SSE 的分帧。
                        emitter.send(SseEmitter.event()
                                .name("delta")
                                .data(Map.of("v", delta), MediaType.APPLICATION_JSON));
                    } catch (Exception e) {
                        // 客户端断开（Broken pipe）—— 记下来即可，
                        // 上游流会被关闭，模型那边也停止计费
                        closed.set(true);
                        log.debug("SSE 推送失败，客户端可能已断开: {}", e.getMessage());
                    }
                });

                if (!closed.get()) {
                    emitter.send(SseEmitter.event()
                            .name("done")
                            .data(summary, MediaType.APPLICATION_JSON));
                }
                emitter.complete();

            } catch (Exception e) {
                log.warn("流式探针失败: {}", e.getMessage());
                try {
                    emitter.send(SseEmitter.event()
                            .name("failed")
                            .data(Map.of("message", String.valueOf(e.getMessage())),
                                    MediaType.APPLICATION_JSON));
                } catch (Exception ignored) {
                    // 连接可能已经断了，发不出去就算了
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    // ============================================================
    // 向量化
    // ============================================================

    /** 向量化：重点看 dimension 是不是 1024 */
    @GetMapping("/embedding")
    public ApiResponse<Map<String, Object>> embedding(@RequestParam("q") String text) {
        return ApiResponse.ok(probeService.embedding(List.of(text)));
    }

    /** 批量向量化：多条用英文逗号分隔 */
    @GetMapping("/embedding/batch")
    public ApiResponse<Map<String, Object>> embeddingBatch(@RequestParam("texts") String texts) {
        return ApiResponse.ok(probeService.embedding(splitCsv(texts)));
    }

    /**
     * 语义相似度 —— 验证向量「有语义」而不只是「有维度」。
     *
     * <p>参数名故意用 {@code a} / {@code b} 而不是 {@code q}：
     * 这不是查询，是比较。
     */
    @GetMapping("/similarity")
    public ApiResponse<Map<String, Object>> similarity(@RequestParam("a") String a,
                                                       @RequestParam("b") String b) {
        return ApiResponse.ok(probeService.similarity(a, b));
    }

    // ============================================================
    // 重排序
    // ============================================================

    /**
     * 重排序：验证相关文档的得分是否显著高于不相关的。
     *
     * @param q    查询
     * @param docs 候选文档，英文逗号分隔
     */
    @GetMapping("/rerank")
    public ApiResponse<Map<String, Object>> rerank(@RequestParam("q") String query,
                                                   @RequestParam("docs") String docs,
                                                   @RequestParam(value = "topN", required = false)
                                                   Integer topN) {
        return ApiResponse.ok(probeService.rerank(query, splitCsv(docs), topN));
    }

    // ============================================================

    private static List<String> splitCsv(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
