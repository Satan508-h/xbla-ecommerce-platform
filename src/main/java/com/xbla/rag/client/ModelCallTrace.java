package com.xbla.rag.client;

import com.xbla.rag.client.dto.ChatUsage;
import com.xbla.rag.client.dto.DegradationEvent;
import com.xbla.rag.client.dto.ModelDescriptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次模型调用的<b>轨迹收集器</b> —— 记录「这次请求到底经历了什么」。
 *
 * <p><b>为什么要有这个类？</b>
 * 降级链会产生一些「过程信息」：从哪一档退到了哪一档、为什么退、
 * 最后实际用了哪家。这些信息必须传到 service 层，才能写进
 * {@code qa_log.degradation_events} —— 而那是阶段 2 验收标准第 2 条的证据。
 *
 * <p>传这个信息有三种做法，选了第三种：
 * <table border="1">
 *   <caption>降级事件的传递方式对比</caption>
 *   <tr><th>做法</th><th>为什么不用</th></tr>
 *   <tr><td>ThreadLocal</td>
 *       <td>★ 流式场景下会直接失效：请求线程和推送线程是<b>两个不同的线程</b>，
 *           ThreadLocal 不继承。而且它让数据流变得不可见 ——
 *           看方法签名完全不知道有这回事</td></tr>
 *   <tr><td>塞进异常</td>
 *       <td>异常只能表达「失败了」，表达不了「成功了但降过级」。
 *           而后者恰恰是验收标准 2 要证明的场景（用户拿到了正确答案，
 *           只是来自 P1）</td></tr>
 *   <tr><td><b>显式当参数传（本类）</b></td>
 *       <td>—</td></tr>
 * </table>
 *
 * <p><b>生命周期</b>：
 * <pre>
 * ChatService 创建 → 传给 ChatModelRouter → router 往里填 → service 读出来落库
 * </pre>
 *
 * <p><b>线程安全</b>：字段用了 volatile / CopyOnWriteArrayList。
 * 实际使用中写入都发生在同一个工作线程上，但流式场景下
 * 「写」在推送线程、「读」在主流程或超时回调里，
 * 加一层保险比事后排查可见性问题便宜得多。
 */
public class ModelCallTrace {

    private final String traceId;

    /** 降级事件列表，按发生顺序。用 CopyOnWriteArrayList 是为了读多写少且跨线程可见 */
    private final List<DegradationEvent> events = new CopyOnWriteArrayList<>();

    /** 最终实际生效的模型。请求全失败时为 null */
    private final AtomicReference<ModelDescriptor> route = new AtomicReference<>();

    /** token 用量。拿不到时为 null（★ 此时成本必须记 NULL，不能编） */
    private final AtomicReference<ChatUsage> usage = new AtomicReference<>();

    /** LLM 环节的耗时（毫秒） */
    private volatile int llmLatencyMs;

    /** 本次调用的成本（元）。拿不到 usage 时为 null */
    private volatile BigDecimal cost;

    public ModelCallTrace(String traceId) {
        this.traceId = traceId;
    }

    // ============================================================
    // 写入（由 ChatModelRouter 调用）
    // ============================================================

    /**
     * 记录一次降级。
     *
     * @param from   降级前的链路条目名
     * @param to     降级后的链路条目名；已经是最后一档时传 null
     * @param reason 原因，取 {@link ModelErrorKind#reason()} 或 {@code "circuit_open"}
     */
    public void degrade(String from, String to, String reason) {
        events.add(new DegradationEvent(from, to, reason, OffsetDateTime.now().toString()));
    }

    /**
     * 记录「熔断器已跳闸，本轮直接跳过这一档」。
     *
     * <p>和 {@link #degrade} 的区别在于原因不同：
     * 一个是「刚试了，失败了」，一个是「没试，因为它已经处于跳闸状态」。
     * 区分开才能回答「P0 到底是时不时抽风，还是已经彻底不可用了」。
     */
    public void circuitOpen(String from, String to) {
        degrade(from, to, "circuit_open");
    }

    /** 记录调用成功。 */
    public void succeeded(ModelDescriptor descriptor, ChatUsage usage, int latencyMs) {
        this.route.set(descriptor);
        this.usage.set(usage);
        this.llmLatencyMs = latencyMs;
    }

    /** 记录最终成本。传 null 表示「拿不到用量，成本未知」 */
    public void cost(BigDecimal cost) {
        this.cost = cost;
    }

    // ============================================================
    // 读取（由 ChatService 调用）
    // ============================================================

    public String traceId() {
        return traceId;
    }

    /** 降级事件列表（不可变副本） */
    public List<DegradationEvent> events() {
        return List.copyOf(events);
    }

    /** 这次调用发生过降级吗？ */
    public boolean degraded() {
        return !events.isEmpty();
    }

    /** 最后一次降级事件，没有则返回 null */
    public DegradationEvent lastEvent() {
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    /** 最终生效的模型。全失败时为 null */
    public ModelDescriptor route() {
        return route.get();
    }

    /** token 用量。可能为 null */
    public ChatUsage usage() {
        return usage.get();
    }

    public int llmLatencyMs() {
        return llmLatencyMs;
    }

    /** 成本。为 null 表示「用量未知，成本未计算」—— 落库时存 NULL */
    public BigDecimal cost() {
        return cost;
    }

    /**
     * 给日志用的一行摘要。
     *
     * <p>降级链出问题时，这行日志能直接回答「走到第几档、为什么停在那里」，
     * 不用去翻三家的调用记录。
     */
    public String summary() {
        ModelDescriptor r = route.get();
        String routeText = (r == null)
                ? "无（全部失败）"
                : "%s@%s(%s)".formatted(r.modelKey(), r.provider(), r.modelId());

        if (events.isEmpty()) {
            return "trace=%s 未降级 route=%s".formatted(traceId, routeText);
        }

        StringBuilder sb = new StringBuilder()
                .append("trace=").append(traceId)
                .append(" 降级").append(events.size()).append("次")
                .append(" route=").append(routeText)
                .append(" 轨迹[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                sb.append(" → ");
            }
            DegradationEvent e = events.get(i);
            sb.append(e.from()).append('-').append(e.reason());
        }
        return sb.append(']').toString();
    }
}
