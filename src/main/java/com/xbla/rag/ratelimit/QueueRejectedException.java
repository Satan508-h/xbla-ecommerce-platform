package com.xbla.rag.ratelimit;

/**
 * 这个请求<b>没有拿到名额</b> —— 队列满了，或者等太久了（阶段 6.7）。
 *
 * <h2>★ 它存在的理由：让「忙」和「坏了」在 HTTP 上是两回事</h2>
 *
 * <p>不建这个类的话，被限流只能靠 {@code IllegalStateException} 或者别的通用异常 ——
 * 而它们会落到 {@code GlobalExceptionHandler} 的兜底分支，返回
 * <b>HTTP 500「服务内部错误，请稍后重试」</b>。
 *
 * <pre>
 *   HTTP 500 → 「系统坏了」   → 用户会去截图、报障、怀疑数据出问题了
 *   HTTP 503 → 「暂时忙」     → 用户知道重试就行
 * </pre>
 *
 * <p>★ 而<b>队列满这件事根本不是故障</b>：它恰恰是限流<b>正常工作</b>的证据。
 * 把正常工作报成故障，是最容易让一个可用的系统被当成不可用的那类错误。
 * 同 {@code AiConfig}/{@code ModelCallException} 里
 * 「服务端暂时不可用 → 503、请求本身有问题 → 400」的分法。
 *
 * <h2>⚠️ 它【只在非流式路径上抛】</h2>
 *
 * <p>流式路径被拒绝时<b>不抛异常</b>，而是推一个 {@code failed} 事件 ——
 * 因为那时 SSE 响应早就提交了，HTTP 状态码已经发出去，改不了了。
 * 两条路的差别在这里，但<b>「什么算被拒绝」的判据是同一个</b>：
 * 都来自 {@code ChatAdmissionService.giveUp()}。
 *
 * <p>所以这个异常是「传输层的翻译」，不是「业务上的新情况」。
 */
public class QueueRejectedException extends RuntimeException {

    /**
     * 已经等了多久（毫秒）。
     *
     * <p>★★★ <b>它【不能】用来判断「是哪一种拒绝」</b> —— 这件事本项目的注释错了三次，
     * 每次都错在同一个地方：拿一个只带<b>量级</b>的数去回答一个关于<b>原因</b>的问题。
     *
     * <pre>
     *   ① 阶段 6.5「立刻拒绝时它是 0」   → 对「队列满」错（差值，实测 2ms）
     *   ② 阶段 6.7「它不可能是 0」       → 对「池满」错（那两处传的字面量就是 0）
     *   ③ 阶段 6.8「0=池满，&gt;0=队列满」  → 全错，见下表
     * </pre>
     *
     * <p>压测实录（{@code qa_log}，{@code status=4}，2026-09-20）：
     *
     * <pre>
     *   排队线程池已满：等待 0ms    × 34
     *   队列已满：等待 2ms           × 2
     *   队列已满：等待 1ms           × 1
     *   队列已满：等待 0ms           × 1   ← ★ 判决性的一行：和「池满」撞在同一个数上
     *   排队超时：等待 3138ms        × 1
     * </pre>
     *
     * <p>★ 精确的判据是落库那一行的 {@code qa_log.error_msg}（它以
     * {@code giveUp} 的 {@code reason} 开头），不是这个字段 ——
     * ⚠️ 也<b>不是</b>本异常的 {@link #getMessage()}：那个是<b>给用户看的话</b>，
     * 而「队列满」和「池满」的用户话术刻意写得很像（都是「人太多，请稍后重试」），
     * 因为对用户来说它们确实是同一件事。
     * <b>对用户是同一件事，对运维是两个旋钮 —— 所以判据只能在数据里，不在文案里。</b>
     *
     * <p>四种拒绝各自对症一个旋钮，而它们的 {@code queueMs} 可以完全相同：
     *
     * <pre>
     *   「队列已满」        →  maxQueue
     *   「排队线程池已满」  →  queuePool.maxPoolSize
     *   「sse 线程池已满」  →  answerExecutor 的容量
     *   「排队超时」        →  permits / 容量本身
     * </pre>
     *
     * <p>★ 它<b>仍然有用</b>，只是用途窄得多 —— 只能回答<b>量级</b>：
     *
     * <pre>
     *   0 ~ 个位数     →  几乎是立刻就拒了
     *   接近排队上限   →  等满了预算才拒（只有「排队超时」长这样）
     * </pre>
     *
     * <p>⚠️ 量级可以用来<b>交叉验证</b>，不能用来定性 —— 上面那张表就是反例。
     */
    private final long queueMs;

    /**
     * @param userMessage 面向用户的说明。<b>必须是那句已经能直接展示给用户的话</b> ——
     *                    handler 不会再加工它，因为「人太多」和「等太久」
     *                    对用户的下一步动作提示不同（等一会儿 vs 稍后再来）
     * @param queueMs     已经等了多久
     */
    public QueueRejectedException(String userMessage, long queueMs) {
        super(userMessage);
        this.queueMs = queueMs;
    }

    public long queueMs() {
        return queueMs;
    }
}
