package com.xbla.rag.service;

import com.xbla.rag.common.EvalMark;
import com.xbla.rag.common.TraceId;

/**
 * 一次问答的<b>调用上下文</b> —— 从排队层传进 {@link ChatService} 的那点信息。
 *
 * <h2>★ 为什么需要它，而不是继续加参数</h2>
 *
 * <p>阶段 6 让请求在到达 {@link ChatService} 之前先排队，于是有三样东西
 * 需要从这里传进去：
 *
 * <pre>
 *   traceId        排队层生成的链路 ID（见 TraceId 类注释）
 *   queueMs        排了多久
 *   queuePosition  刚入队时前面有几个人
 * </pre>
 *
 * <p>★ <b>阶段 7 又加了第四样（{@code eval}），而这一条正是上面那段话的兑现</b>：
 * 加一个字段没有改任何方法签名。如果当初是「继续加参数」，
 * 今天要改的就是 {@code askStream} 以及它下游那 6 个私有落库方法的签名 ——
 * 而每一次传递都是一次「少传一个」的机会。
 *
 * <p>加成三个参数的话，{@code askStream(request, sink, traceId, queueMs, queuePosition)}
 * 会变成五个参数，而这个签名还会被继续往下传给 6 个私有的落库方法 ——
 * <b>每一次传递都是一次「少传一个」的机会</b>，而少传的症状是某个路径的
 * {@code queue_ms} 恒为 NULL，从数据上完全看不出来（那正是 V9 要防的事）。
 *
 * <p>收成一个对象之后，传的是「这一个请求的全部上下文」，
 * 加字段不会改任何签名。
 *
 * <h2>★★ 阶段 9 加的第五样：{@code userId} —— 身份为什么也走这里</h2>
 *
 * <p>在它之前，身份是 {@code ask(request, userId, ctx)} 的<b>方法参数</b>，
 * 而流式那条路根本没有它（{@code askStream} 只有三个参数），
 * 症状是 {@code retrieval = TOOL} 的意图在 SSE 上<b>静默降级成普通 KB 问答</b>。
 *
 * <p>补它的方式有两种，本项目选了后者：
 *
 * <pre>
 *   ① 给 askStream 加第四个参数
 *      → 同一个请求里身份有了【两个来源】：排队层解析的那个（Admission 里带着，
 *        被限流那条路正拿它写 qa_log）和控制器传下去的那个。
 *        两者不一致时以谁为准，会变成一个新问题 —— 而 ADR-065 已经为
 *        「身份只能有一个出处」打过一次架。
 *   ② 放进本对象（采纳）
 *      → 排队层构造它的时候顺手带上，服务层直接用。
 *        一次解析、一处存放、两条路同源。
 * </pre>
 *
 * <p>★ <b>这不违反 ADR-065 那条纪律。</b>那条防的是「身份变成单例 Bean 的字段 →
 * 并发下 A 看到 B 的数据」。本对象是<b>每请求一次</b>的不可变记录，
 * 从不被存进任何字段，生命周期就是一次问答。
 *
 * <p>★★ 顺带买到的一条：本类是 {@code record}，<b>加一个分量会让所有构造点在
 * 编译期一起报错</b>。而 ADR-081 记的正是「漏一个构造点 → 编译通过、无日志、
 * 评测流量伪装成真实用户」—— record 的规范构造器就是那个坑的结构性防御。
 *
 * <h2>★★ 阶段 9.4 加的第六样：{@code clarifyResumed} —— 一个由服务层填的标记位</h2>
 *
 * <p>上面五样都是<b>排队层认识的事实</b>（谁提的、排了多久、是不是评测流量）。
 * 这一样不是 —— 它是服务层在「读了会话的待澄清状态之后」才知道的，
 * 所以它没有构造参数，只有 {@link #withClarifyResumed()} 这一个单向的 wither。
 *
 * <p>★ 那为什么还放进这里？因为它要落的那个地方（{@code qa_log.intent_plan}）
 * 藏在 {@code baseLog} 里，而 {@code baseLog} 有 <b>5 个平行的调用方</b>
 * （成功 / 流式成功 / 失败 / 澄清 / 工具）。
 * 给 {@code baseLog} 加一个参数 = 改 5 个方法 + 它们的 8 个调用点，
 * 而「改漏了一条路」正是本项目反复栽的那个坑（ADR-047 / ADR-081 / 坑 40）。
 * 放进本对象之后 {@code ctx} 本来就贯穿所有路径，漏一条在结构上不可能。
 *
 * <p>⚠️ 边界（写下来是为了防止这里变成一个杂物间）：<b>只放「这一请求的事实」，
 * 并且必须满足两条 —— ① 两条路（{@code ask} / {@code askStream}）都会读到它；
 * ② 落库或路由要用它</b>。会话级的状态（比如待澄清内容本身）<b>不属于这里</b>，
 * 它在 {@code chat_session} 上。
 *
 * <h2>★★ 什么时候它是「没有排队」</h2>
 *
 * <p>{@link #queueMs()} 和 {@link #queuePosition()} 都是 <b>Integer 而不是 int</b>，
 * 而且 {@code null} 是<b>常态，不是异常</b>：
 *
 * <pre>
 *   名额有空，第一次 acquire 就拿到了  →  queueMs = null, queuePosition = null
 *   排队了                             →  queueMs = 等待毫秒, queuePosition = 初始位置
 * </pre>
 *
 * <p>★ 关键在第一种：<b>它不该记成 0</b>。记 0 的话阶段 7 分不清
 * 「没排队」和「排队等了 0 毫秒」，而更糟的是它会把
 * 「平均等多久」这个平均值往下拉 —— 绝大多数请求都是没排队的，
 * 于是那个平均值会被稀释到接近 0，<b>看起来像「排队功能没生效」</b>。
 * （同 ADR-010「拿不到就记 NULL」、以及 {@code qa_log.queue_ms} 的列注释。）
 *
 * @param traceId       链路 ID，<b>非空</b>
 * @param queueMs       排队等待毫秒；<b>null = 没排队</b>
 * @param queuePosition 刚入队时前面有几个人（0-based）；<b>null = 没进过队列</b>
 * @param eval          评测运行标记；<b>null = 真实用户的提问</b>（常态）。
 *                      见 {@link EvalMark} —— 它只被透传进 {@code qa_log}，
 *                      不参与任何业务判断
 * @param userId        身份，来自 {@code X-Xbla-User-Id} 请求头；<b>null = 匿名</b>（常态）。
 *                      <p>★ 它<b>不是认证</b>（明文未签名，见 {@code McpToolContext}），
 *                      做到的是「身份不进模型的可控范围」。
 *                      <p>★ 它的用途有两处：① 工具那条路查「我的订单/我的券」；
 *                      ② {@code qa_log.user_id} —— 阶段 9 起才真的有值，
 *                      在那之前那一列恒为 NULL。
 * @param clarifyResumed ★ 阶段 9.4：<b>这一次分类带上了上一轮悬着的澄清状态</b>
 *                      （见 {@link com.xbla.rag.agent.intent.PendingClarify}）。
 *                      <b>false = 没有</b>（常态）。
 *                      <p>★ 它的用途只有一处：落进 {@code qa_log.intent_plan.resumed}——
 *                      那格回答的是「多轮澄清到底有没有生效」，
 *                      而没有它的话，恢复路径<b>一次都没触发</b>这件事
 *                      在数据上和「触发了但没用」长得一模一样（同 9.2 的 shape）。
 *                      <p>★ 它是<b>服务层自己发现并填上的</b>（不像上面四个来自排队层），
 *                      所以它是一个可变的标记位而不是构造参数 —— 见 {@link #withClarifyResumed()}。
 */
public record CallContext(String traceId, Integer queueMs, Integer queuePosition,
                          EvalMark eval, Long userId, boolean clarifyResumed) {

    /**
     * 没经过排队层的调用（测试、探针、以及 {@code xbla.ratelimit.enabled=false}）。
     *
     * <p>★ 排队两列都是 null —— 这正是 {@code enabled=false} 时该有的样子。
     * 如果这里填 0，阶段 7 就分不清「没开排队」和「开了但没排队」了。
     */
    public static CallContext fresh(String traceId) {
        return new CallContext(traceId, null, null, null, null, false);
    }

    /**
     * 带评测标记、但没经过排队层的调用。
     *
     * <p>★ 它存在的场景是<b>测试</b>：走 {@code chatService.ask(...)} 直接验证
     * 「评测标记落不落库」，不必把排队层拉起来。
     */
    public static CallContext fresh(String traceId, EvalMark eval) {
        return new CallContext(traceId, null, null, eval, null, false);
    }

    /** 自动生成一个 traceId 的「没排队」上下文 */
    public static CallContext fresh() {
        return fresh(TraceId.newId());
    }

    /**
     * 换一个身份，其余分量一个字不动。
     *
     * <p>★★ <b>{@code null} 是「不改」，不是「清空」。</b>这两个语义都说得通，
     * 但选后者的代价是：任何一处写了 {@code ctx.withUserId(someNullableValue)}
     * 都能把上游解析好的身份<b>悄悄擦掉</b>，而症状是「工具题答不出我的订单」，
     * 日志里一切正常。选前者之后，擦除这个动作<b>在本类型里不可表达</b>。
     *
     * <p>★ 它<b>不</b>判断新旧哪个对 —— 那是调用点的事。
     * {@code ChatServiceImpl} 那边会比对方法参数与上下文里的身份，
     * 不一致时打 WARN（见那个收敛方法）。
     */
    public CallContext withUserId(Long userId) {
        return userId == null
                ? this
                : new CallContext(traceId, queueMs, queuePosition, eval, userId, clarifyResumed);
    }

    /**
     * 标记「这一次问答带上了上一轮的澄清状态」（阶段 9.4）。
     *
     * <p>★ <b>只能从 false 变 true</b>，<b>没有带参数的版本</b> ——
     * 「清空」这个动作在本类型里不可表达（同 {@link #withUserId} 那条纪律：
     * 一个能把它悄悄擦掉的方法，症状是一条路上的 {@code intent_plan.resumed}
     * 恒为 false，而数据上看起来只是「这次没恢复」）。
     *
     * <p>★ 为什么它走 {@code CallContext} 而不是给 {@code baseLog} 加一个参数：
     * 落库那条路有 <b>5 个平行的私有方法</b>（成功/流式成功/失败/澄清/工具），
     * 每个都要往下传一次，而「只改了一条路」正是本项目反复栽的那个坑
     * （ADR-047、ADR-081、坑 40）。放进本对象之后，
     * {@code ctx} 本来就贯穿所有路径，<b>漏一条在结构上不可能</b>。
     */
    public CallContext withClarifyResumed() {
        return clarifyResumed
                ? this
                : new CallContext(traceId, queueMs, queuePosition, eval, userId, true);
    }
}
