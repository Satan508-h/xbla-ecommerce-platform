package com.xbla.rag.ratelimit;

/**
 * 一次「尝试获取名额」的结果。
 *
 * <h2>★ 为什么是三个状态，不是「成功 / 失败」两个</h2>
 *
 * <p>因为「排队中」和「队列满了」对用户是<b>两件完全不同的事</b>：
 *
 * <pre>
 *   QUEUED      → 「你前面还有 3 位，请稍候」      ← 有希望，值得等，要推位置
 *   QUEUE_FULL  → 「当前排队人数过多，请稍后重试」  ← 没希望，立刻说清楚
 * </pre>
 *
 * <p>把后者混进「失败」里（比如抛异常），症状是<b>用户对着一个空白页等到超时</b>，
 * 或者收到一句和排队无关的「服务内部错误」。而把前者当成失败，
 * 就等于把整个排队功能取消了。
 *
 * <p>它们<b>共用一个 Lua 脚本的两次 return</b>（见 {@code acquire.lua}），
 * 所以「区分它们」几乎不花成本 —— 不区分的唯一原因就是没想到要区分。
 *
 * @param outcome  三种结果之一
 * @param position 排队位置。<b>仅当 {@code outcome == QUEUED} 时有意义</b>
 * @param queueSize 当前队列长度。<b>仅当 {@code outcome == QUEUE_FULL} 时有意义</b>，
 *                  用来告诉用户「现在有多少人在等」，比一句干巴巴的「请稍后」有用
 */
public record PermitState(Outcome outcome, int position, int queueSize) {

    public enum Outcome {
        /** 拿到名额了，可以往下走 */
        GRANTED,
        /** 名额已满，已进入队列，{@code position} 是它前面还有几个人 */
        QUEUED,
        /** 队列也满了，如实拒绝 */
        QUEUE_FULL
    }

    /**
     * 第一个人前面有 <b>0</b> 个人。
     *
     * <p>★ 这是 {@code ZRANK} 的原生语义（0-based），
     * <b>不在这一层 +1</b>。原因是「位置」在这个类里是给程序看的，
     * 而给<b>用户</b>看的那句话是「你前面还有 N 位」——
     * 那个 N 恰好就是 0-based 的 rank，不需要 +1。
     *
     * <p>⚠️ 一旦这里 +1 了，用户看到的就会变成「你前面还有 1 位」，
     * 而实际上他前面一个人都没有。**用户可见的 off-by-one 最难被发现** ——
     * 因为「排在第一个却说前面有 1 位」看起来只是「有点慢」，不像 bug。
     * （由 {@code ChatPermitServiceIntegrationTest} 钉住。）
     */
    public static PermitState granted() {
        return new PermitState(Outcome.GRANTED, 0, 0);
    }

    public static PermitState queued(int position, int queueSize) {
        return new PermitState(Outcome.QUEUED, position, queueSize);
    }

    public static PermitState queueFull(int queueSize) {
        return new PermitState(Outcome.QUEUE_FULL, -1, queueSize);
    }

    public boolean isGranted() {
        return outcome == Outcome.GRANTED;
    }
}
