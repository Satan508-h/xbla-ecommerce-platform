package com.xbla.rag.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一条 SSE 通道 —— 包住 {@link SseEmitter}，管住两件事：
 * <b>「客户端还在不在」</b>和<b>「推送的统一出口」</b>。
 *
 * <h2>★★ 为什么需要它：断开检测比看起来难</h2>
 *
 * <p>阶段 2 起，判断「用户关掉页面了」靠的是<b>推送失败</b>：
 * {@code emitter.send(...)} 抛异常 → 说明连接没了。
 * 对<b>正在噼里啪啦吐 token</b> 的回答来说这足够了 —— 每次 delta 都会写一次，
 * 断了马上就知道，上游的流也随之关闭、停止计费。
 *
 * <p>★ 但阶段 6 的<b>排队期</b>不成立：那时候可能<b>几十秒都不推一次东西</b>
 * （默认每 200ms 推一次位置，但如果位置没变就不推，而且队列不动的时候
 * 位置确实一直不变）。于是：
 *
 * <pre>
 *   用户关页面  →  推送失败要等到【下一次真的写】才发现
 *               →  在这之前，那个等待线程还在老老实实地轮询
 *               →  等它拿到名额，已经没有人在看这个回答了
 *               →  而名额要白占一整个问答的时间（几秒到几十秒）
 * </pre>
 *
 * <p><b>这才是「名额泄漏」最常见的形态</b>，比「进程被强杀」常见得多 ——
 * 而后者有 TTL 兜底，前者只能靠及时察觉。
 *
 * <p>所以这里注册 {@code onCompletion / onTimeout / onError} 三个回调，
 * 让断开<b>立刻</b>可见（{@link #isClosed()}），而不是等下一次写失败。
 *
 * <h2>★ 两个发送方法，用途不同，不要混用</h2>
 *
 * <ul>
 *   <li>{@link #send} —— <b>失败就抛</b>。给问答线程用：抛出去才能让调用链层层展开，
 *       关掉上游的模型流、停止计费。这是阶段 2 就定下的行为。</li>
 *   <li>{@link #sendQuietly} —— <b>失败只记日志</b>。给排队线程用：
 *       它要做的是「发现断开了就退出循环」，而不是让一个异常
 *       从轮询里穿出去。抛出去的话，异常会落进排队层的兜底 catch，
 *       被报成「排队过程出现未预期异常」—— 而归因完全是错的。</li>
 * </ul>
 */
final class SseChannel {

    private static final Logger log = LoggerFactory.getLogger(SseChannel.class);

    private final SseEmitter emitter;

    /** 客户端已断开（或我们主动关掉了）的标志。★ 一旦置位就停止一切推送 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    SseChannel(SseEmitter emitter) {
        this.emitter = emitter;

        // ★ 三个回调都要注册，它们覆盖的时机不同：
        //   onCompletion —— 正常结束（我们调了 complete()）
        //   onTimeout    —— 服务端超时（SseEmitter 构造时给的那个毫秒数）
        //   onError      —— 传输层出错
        //   只注册 onError 是不够的：超时那条路不一定走 onError。
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            log.debug("SSE 连接超时");
        });
        emitter.onError(e -> {
            closed.set(true);
            log.debug("SSE 连接出错：{}", e.getMessage());
        });
    }

    /** 客户端还在吗？—— 排队循环靠它决定要不要继续等 */
    boolean isClosed() {
        return closed.get();
    }

    /**
     * 推送，<b>失败就抛</b>。
     *
     * <p>⚠️ payload 必须是<b>对象</b> + {@code APPLICATION_JSON}，不能是纯 String。
     * 纯 String 会走 {@code StringHttpMessageConverter}，它的默认字符集不是 UTF-8
     * （中文变问号），而且正文里的换行符会破坏 SSE 的分帧结构。
     */
    void send(String event, Object payload) {
        if (closed.get()) {
            throw new IllegalStateException("SSE 连接已关闭");
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(payload, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            closed.set(true);
            throw new IllegalStateException("SSE 推送失败：" + e.getMessage(), e);
        }
    }

    /**
     * 推送，<b>失败只记日志并置位 closed</b> —— 给排队线程用。
     *
     * <p>★ 它不抛的原因见类注释第二节：排队线程要的是「发现断开 → 退出循环」，
     * 而不是让异常从轮询里穿出去被归因成「排队出错」。
     */
    void sendQuietly(String event, Object payload) {
        try {
            send(event, payload);
        } catch (Exception e) {
            log.debug("推送 {} 事件失败（客户端可能已断开）：{}", event, e.getMessage());
        }
    }

    /** 正常收尾。<b>幂等</b> —— 客户端已经断开时调它不该抛 */
    void complete() {
        if (closed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("关闭 SSE 失败（客户端可能已断开）：{}", e.getMessage());
            }
        }
    }
}
