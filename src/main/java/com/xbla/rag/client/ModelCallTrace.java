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

    /**
     * 把<b>另一轮</b>调用的轨迹并入本轨迹 —— 阶段 5.8 的工具往返用。
     *
     * <h2>为什么需要它</h2>
     *
     * <p>不用工具时，一次问答 = 一次模型调用。用工具时是<b>至少两次</b>：
     * <pre>
     *   第 1 轮：模型决定调工具     → 产出 tool_calls，没有正文
     *   第 2 轮：模型把结果变成人话 → 产出正文
     * </pre>
     *
     * <p>而 {@code qa_log} 只有<b>一行</b>（它的语义是「一次问答」，
     * 不是「一次模型调用」）。所以多轮的轨迹必须合到一处，
     * 否则第 1 轮的花费<b>凭空消失</b> —— 而那一轮在推理模型上并不便宜。
     *
     * <h2>★ 五个字段的合并规则【不一样】，这是本方法唯一容易写错的地方</h2>
     *
     * <table border="1">
     *   <caption>逐字段的合并规则</caption>
     *   <tr><th>字段</th><th>规则</th><th>为什么</th></tr>
     *   <tr><td>降级事件</td><td><b>追加</b></td>
     *       <td>每一轮的降级都发生过，丢掉哪一轮都是在隐藏事实</td></tr>
     *   <tr><td>路由（provider/model）</td><td><b>覆盖</b></td>
     *       <td>★ 用<b>最后一轮</b>的 —— 产出最终回答的就是它。
     *           前面几轮如果降过级，{@code degradation_events} 里看得见</td></tr>
     *   <tr><td>token 用量</td><td><b>累加</b></td>
     *       <td>★ 覆盖会严重低估。用户付的是每一轮的账</td></tr>
     *   <tr><td>耗时</td><td><b>累加</b></td>
     *       <td>同上</td></tr>
     *   <tr><td>成本</td><td><b>累加</b></td>
     *       <td>★★ <b>不能拿「总 token × 最后一轮单价」重算</b> ——
     *           降级时不同轮的单价不一样（P0 输出 4 元/百万、P1 是 3 元）。
     *           只能逐轮算好再加</td></tr>
     * </table>
     *
     * <p>⚠️ 任一轮的用量拿不到（{@code null}），合计就必须是 <b>null</b>，
     * 不能把已知的那几轮加起来充数 —— 「少算了一点」和「不知道」
     * 在成本分析里的含义完全不同（同 {@code ADR-010} 的 NULL 原则）。
     *
     * @param round 另一轮的轨迹。为 null 时什么都不做
     */
    public void mergeRound(ModelCallTrace round) {
        if (round == null || round == this) {
            return;
        }

        // ① 降级事件：追加
        events.addAll(round.events());

        // ② 路由：覆盖（用最后一轮的）
        ModelDescriptor roundRoute = round.route();
        if (roundRoute != null) {
            route.set(roundRoute);
        }

        // ③ 用量：累加
        this.usage.set(accumulate(this.usage.get(), round.usage(), ModelCallTrace::sum));

        // ④ 耗时：累加
        this.llmLatencyMs += round.llmLatencyMs();

        // ⑤ 成本：累加
        this.cost = accumulate(this.cost, round.cost(), BigDecimal::add);
    }

    /**
     * 累加两份值 —— ★ <b>三态逻辑，不是简单的「null 当 0」</b>。
     *
     * <p>第一次合并时，本轨迹的对应字段<b>本来就是 null</b>（还没有任何一轮填过它）。
     * 如果写成「任一方为 null 就返回 null」，那么第一次合并的结果永远是 null，
     * 之后的每一次合并也永远是 null —— 最终 {@code qa_log.cost} 恒为 NULL，
     * 而<b>没有任何报错</b>，看起来就像「用量拿不到」。
     *
     * <p>三种状态必须分开：
     * <table border="1">
     *   <caption>累加的三态</caption>
     *   <tr><th>本轨迹</th><th>新的一轮</th><th>结果</th><th>含义</th></tr>
     *   <tr><td>null</td><td>有值</td><td>新值</td><td>第一轮，直接采用</td></tr>
     *   <tr><td>有值</td><td>null</td><td><b>null</b></td>
     *       <td>★ 有一轮拿不到用量 → 合计<b>未知</b>。
     *           把 null 当 0 会把「不知道」静默变成「没花钱」，成本被低估且无迹可循</td></tr>
     *   <tr><td>有值</td><td>有值</td><td>相加</td><td>正常情况</td></tr>
     * </table>
     *
     * @param current 本轨迹当前的值，可为 null
     * @param incoming 新来一轮的值，可为 null
     * @param summer 两者都非 null 时怎么相加
     */
    private static <T> T accumulate(T current, T incoming, java.util.function.BinaryOperator<T> summer) {
        if (current == null) {
            return incoming;
        }
        if (incoming == null) {
            return null;
        }
        return summer.apply(current, incoming);
    }

    /** 两份用量相加。★ 只会被 {@link #accumulate} 在两份都不为 null 的情况下调用 */
    private static ChatUsage sum(ChatUsage a, ChatUsage b) {
        return new ChatUsage(
                add(a.promptTokens(), b.promptTokens()),
                add(a.completionTokens(), b.completionTokens()),
                add(a.totalTokens(), b.totalTokens()),
                add(a.reasoningTokens(), b.reasoningTokens()),
                add(a.promptCacheHitTokens(), b.promptCacheHitTokens()),
                add(a.promptCacheMissTokens(), b.promptCacheMissTokens()));
    }

    /** 单个 token 字段相加。★ 这里 null 当 0 是安全的：外层已经保证两份用量都不为 null */
    private static Integer add(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + b;
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
