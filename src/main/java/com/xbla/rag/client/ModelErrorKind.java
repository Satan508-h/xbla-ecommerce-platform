package com.xbla.rag.client;

/**
 * 模型调用失败的分类 —— <b>整个降级策略的灵魂</b>。
 *
 * <p>为什么要做成枚举，而不是在 {@code catch} 块里写 if-else？
 * 因为「哪类失败该降级」和「哪类失败该计入熔断器统计」是两个正交的决策，
 * 散落在代码里的话，改一处忘一处，最后没人说得清当前策略是什么。
 * 收进枚举后，策略变成一张能一眼看完的表：
 *
 * <table border="1">
 *   <caption>错误分类与处置策略</caption>
 *   <tr><th>类型</th><th>触发条件</th><th>记熔断失败</th><th>降级</th></tr>
 *   <tr><td>{@link #AUTH}</td>          <td>401 / 403</td>       <td>是</td><td>是</td></tr>
 *   <tr><td>{@link #QUOTA_EXHAUSTED}</td><td>402 余额不足</td>   <td>是</td><td>是</td></tr>
 *   <tr><td>{@link #RATE_LIMIT}</td>    <td>429</td>             <td>是</td><td>是</td></tr>
 *   <tr><td>{@link #SERVER_ERROR}</td>  <td>5xx</td>             <td>是</td><td>是</td></tr>
 *   <tr><td>{@link #TIMEOUT}</td>       <td>读/连接超时</td>       <td>是</td><td>是</td></tr>
 *   <tr><td>{@link #CONNECT}</td>       <td>IO / 连接被拒</td>     <td>是</td><td>是</td></tr>
 *   <tr><td>{@link #EMPTY_CONTENT}</td> <td>200 但正文为空</td>    <td>是</td><td>是</td></tr>
 *   <tr><td>{@link #BAD_REQUEST}</td>   <td>400 / 422</td>       <td><b>否</b></td><td><b>否</b></td></tr>
 *   <tr><td>{@link #PARTIAL_STREAM}</td><td>已吐字后中断</td>      <td>是</td><td><b>否</b></td></tr>
 * </table>
 */
public enum ModelErrorKind {

    /**
     * 认证失败（HTTP 401 / 403）。
     *
     * <p>Key 错了、过期了、或者账户欠费。
     * <b>阶段 2 验收标准第 2 条（故意改错 P0 的 Key）走的就是这条路径。</b>
     */
    AUTH(true, true, "auth_error"),

    /** 限流（HTTP 429）。换一家供应商通常立刻就能成功，是降级最典型的场景 */
    RATE_LIMIT(true, true, "rate_limit"),

    /**
     * 账户余额不足 / 配额用尽（HTTP 402 Payment Required）。
     *
     * <p>实测报文：{@code {"code":30001,"message":"Sorry, your account balance is insufficient"}}
     * —— 注意硅基流动把它包在 <b>HTTP 402</b> 里，而不是 4xx 里更常见的 400。
     *
     * <p>★ <b>这个类型是测试跑出来的，不是设计时想到的。</b>
     * 最初的实现把「其余 4xx」一律归到 {@link #BAD_REQUEST}，
     * 于是 402 被判定为「我们自己的请求有问题」——<b>既降级也不计熔断</b>，
     * 整个降级链会在这一档直接终止。
     *
     * <p>但 402 的真实含义是「这家服务不了我们」，和请求体对不对毫无关系，
     * 所以必须<b>可降级</b>：同一家供应商上不同模型的免费/收费状态可能不同，
     * 换一档很可能就好了。
     *
     * <p>计入熔断失败也是对的：402 在充值之前不会自愈，
     * 让熔断器跳闸可以避免每个请求都白白等一次超时。
     */
    QUOTA_EXHAUSTED(true, true, "quota_exhausted"),

    /** 服务端错误（HTTP 5xx）。对方挂了，降级 */
    SERVER_ERROR(true, true, "server_error"),

    /**
     * 超时。
     *
     * <p>包含连接超时和读超时。注意流式场景下，
     * 「建连之后迟迟不来第一个字」也归到这一类。
     */
    TIMEOUT(true, true, "timeout"),

    /** 网络层错误：连接被拒、DNS 失败、TLS 握手失败等 */
    CONNECT(true, true, "connect_error"),

    /**
     * HTTP 200，但正文是空的。
     *
     * <p>★ <b>这不是网络问题，是（推理）模型的特性。</b>
     * {@code deepseek-flash} 是推理模型，实测 87% 的输出 token 花在推理上。
     * 如果 {@code max-tokens} 给小了，推理会把额度全部吃光，
     * 最终 {@code content} 返回<b>空字符串</b>，而 HTTP 状态码是 200 ——
     * 现象是「AI 不说话」，日志里没有任何异常。
     *
     * <p>把它归为「可降级的失败」是个很有价值的设计：
     * P2 的 {@code Qwen/Qwen3-8B} 参数小、推理短，
     * 反而更可能在同样的 max-tokens 下给出非空回答。
     */
    EMPTY_CONTENT(true, true, "empty_content"),

    /**
     * 请求本身有问题（HTTP 400 / 422）。
     *
     * <p>★★ <b>这一类既不计入熔断失败，也不降级</b>，理由是整个策略里最值得讲的一条：
     * <ul>
     *   <li><b>不降级</b>：400 说明<b>我们自己的请求体有问题</b>
     *       （比如某个模型不接受 {@code temperature} 这个字段）。
     *       换一家供应商会以同样的方式失败 —— 降级不解决问题，只是把错误延迟暴露。</li>
     *   <li><b>不计入熔断</b>：更关键。如果记成失败，连试三个供应商就是三次失败，
     *       会迅速把<b>所有</b>熔断器推到 OPEN。结果是
     *       「一个我们自己写错的参数，让整个模型层看起来全挂了」——
     *       真正的 bug 被掩盖成「所有模型都不可用」，排查方向完全被带偏。</li>
     * </ul>
     */
    BAD_REQUEST(false, false, "bad_request"),

    /**
     * 流已经开始吐字之后才中断。
     *
     * <p>★ 计入熔断失败（这一家确实有问题），但<b>不能降级</b>。
     * 原因见 {@link #isFallbackWorthy()}。
     */
    PARTIAL_STREAM(true, false, "partial_stream"),

    /**
     * <b>客户端主动断开</b>——用户关了页面 / 刷新了浏览器。
     *
     * <p>★ <b>既不计入熔断失败，也不降级。</b>
     *
     * <p>为什么要单独分一类？因为「往浏览器推送时抛 IOException（Broken pipe）」
     * 和「模型供应商出问题」在异常类型上长得一模一样，都是 IOException。
     * 如果不区分，后果是：
     * <ul>
     *   <li><b>污染熔断统计</b>：用户每关一次页面就记一次供应商失败。
     *       几个人关几次标签页，一个完全健康的供应商就被熔断打开了 ——
     *       这是最恶劣的一类 bug：系统自己把好服务判成坏的。</li>
     *   <li><b>误报降级</b>：明明是我们自己断开的上游流，却去降级重试，
     *       白白多花一次钱。</li>
     * </ul>
     *
     * <p>顺带一提，用户断开时我们<b>会主动关闭上游流</b>（见
     * {@code ChatServiceImpl} 的 SSE 推送逻辑）—— 上游一关，
     * 模型那边就不再产生 token，<b>计费也就停了</b>。
     * 否则用户点了关闭，我们还在为一个 4000 token 的回答付钱。
     */
    CLIENT_ABORTED(false, false, "client_aborted"),

    /** 兜底：没能归类的异常。按最保守的方式处理 —— 计失败 + 降级*/
    UNKNOWN(true, true, "unknown");

    private final boolean countsAsBreakerFailure;
    private final boolean fallbackWorthy;
    private final String reason;

    ModelErrorKind(boolean countsAsBreakerFailure, boolean fallbackWorthy, String reason) {
        this.countsAsBreakerFailure = countsAsBreakerFailure;
        this.fallbackWorthy = fallbackWorthy;
        this.reason = reason;
    }

    /**
     * 这次失败要不要计入熔断器的失败统计？
     *
     * <p>只有 {@link #BAD_REQUEST} 返回 false —— 那是我们自己的问题，
     * 不该让对方供应商的「健康度」背锅。
     */
    public boolean countsAsBreakerFailure() {
        return countsAsBreakerFailure;
    }

    /**
     * 这次失败要不要降级到下一家供应商？
     *
     * <p>两类不降级：
     * <ul>
     *   <li>{@link #BAD_REQUEST} —— 换一家也一样错（见该常量的说明）</li>
     *   <li>{@link #PARTIAL_STREAM} —— <b>流式架构的固有约束</b>：
     *       用户已经看到前面吐出来的字了，如果换一家从头重来，
     *       用户看到的是两段拼接起来的、前后矛盾的话。
     *       这种情况只能中断并如实报错，不能假装无事发生。</li>
     * </ul>
     */
    public boolean isFallbackWorthy() {
        return fallbackWorthy;
    }

    /**
     * 写进 {@code qa_log.degradation_events} 里 {@code reason} 字段的取值。
     *
     * <p>★ 这是<b>持久化契约</b>，阶段 7 的评测脚本会按这些字符串做聚合统计，
     * 改名等于破坏历史数据。所以用固定的英文短横线命名，不用中文、不用枚举名。
     */
    public String reason() {
        return reason;
    }

    /**
     * 把 HTTP 状态码映射成错误类型。
     *
     * @param status HTTP 状态码
     * @return 对应的类型；200/2xx 不该走到这里（调用方应先判成功）
     */
    public static ModelErrorKind fromHttpStatus(int status) {
        if (status == 401 || status == 403) {
            return AUTH;
        }
        // ★ 402 必须单独判，不能落到下面的「其余 4xx」分支。
        //   它表示「余额不足/配额用尽」，是可降级的服务端状态，
        //   不是「我们请求写错了」。见 QUOTA_EXHAUSTED 的说明。
        if (status == 402) {
            return QUOTA_EXHAUSTED;
        }
        if (status == 429) {
            return RATE_LIMIT;
        }
        if (status == 400 || status == 422) {
            return BAD_REQUEST;
        }
        if (status >= 500) {
            return SERVER_ERROR;
        }
        // 其余 4xx（404 模型不存在、405 方法不对等）都归到请求问题，
        // 因为它们同样属于「换一家也不会好」的类别
        if (status >= 400) {
            return BAD_REQUEST;
        }
        return UNKNOWN;
    }
}
