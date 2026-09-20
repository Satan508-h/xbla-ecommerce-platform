package com.xbla.rag.ratelimit;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 把排队过程记下来，供测试断言 —— <b>排队相关测试的共用件</b>。
 *
 * <h2>★ 为什么不各自内嵌一份</h2>
 *
 * <p>它一开始是内嵌在 {@code PermitSignalWakeupEffectTest} 里的。当第二个测试类
 * 需要同样的东西时，有两条路：复制一份，或者抽出来。
 *
 * <p>复制的问题不在于「代码重复」这个美感问题，而在于<b>两份会漂移</b>：
 * 将来有人发现「onGivenUp 也应该 countDown 一下，好让失败信息更清楚」，
 * 他只会改自己看到的那一份 —— 于是两个测试对「超时」这个情况的反应不一样，
 * 而<b>没人会发现，因为两边都是绿的</b>。
 *
 * <p>同 {@code RedisQueueKeys}「key 名只能写一次」、{@code ToolField}
 * 「参数名只能写一次」是同一条理由：<b>两处各写一份的漂移是静默的。</b>
 *
 * <h2>★ 为什么字段是 {@code volatile}</h2>
 *
 * <p>它们由 {@code queue-} 线程写、由<b>测试主线程</b>读。
 * 而 {@link CountDownLatch#await} 返回只保证「有人调过 countDown」，
 * <b>不保证写在 countDown 之前的普通字段可见</b> —— 除非有 happens-before 关系。
 * 少了 {@code volatile}，测试会偶发地读到 0，
 * 现象是「一百次里失败一次」，而那种失败最容易被当成环境问题忽略掉。
 *
 * <h2>★ 它【不是 final】的，这是给测试留的</h2>
 *
 * <p>有些用例要覆盖额外的回调（比如 {@link #isCancelled()} 返回 true 的
 * 「客户端已断开」场景），做法是就地匿名继承一层：
 *
 * <pre>{@code
 * new RecordingQueueListener() {
 *     @Override public boolean isCancelled() { return true; }
 * }
 * }</pre>
 *
 * <p>★ 加了 {@code final} 就得为每个变体写一个具名子类，而那些子类会各自
 * 演化 —— 于是「同一个监听器，两个测试看到的行为不一样」这种问题就出现了。
 */
class RecordingQueueListener implements ChatAdmissionService.QueueListener {

    /** 第一次收到位置事件（= 真的进队列了） */
    final CountDownLatch queued = new CountDownLatch(1);

    /** 拿到名额了 */
    final CountDownLatch admitted = new CountDownLatch(1);

    /** 被拒绝了（队列满 / 等太久） */
    final CountDownLatch givenUp = new CountDownLatch(1);

    volatile long queueMs;
    volatile Integer queuedAhead;
    volatile String givenUpMessage;

    @Override
    public void onQueued(int position, long waitedMs) {
        queued.countDown();
    }

    @Override
    public void onAdmitted(long queueMs, Integer initialPosition) {
        this.queueMs = queueMs;
        this.queuedAhead = initialPosition;
        admitted.countDown();
    }

    @Override
    public void onGivenUp(String message, long queueMs) {
        this.givenUpMessage = message;
        givenUp.countDown();
    }

    /**
     * 等它进队列。
     *
     * <p>★ 调用方必须等这一下，否则「释放名额」可能发生在它还没入队之前 ——
     * 那样它会直接拿到名额，而我们<b>从一次根本没排过队的运行里
     * 得出了关于排队的结论</b>。
     *
     * @return false = 超时（前置条件没成立）
     */
    boolean awaitQueued() throws InterruptedException {
        return queued.await(10, TimeUnit.SECONDS);
    }

    /** 等它拿到名额 */
    boolean awaitAdmitted() throws InterruptedException {
        return admitted.await(20, TimeUnit.SECONDS);
    }

    /** 等它被拒绝 */
    boolean awaitGivenUp() throws InterruptedException {
        return givenUp.await(20, TimeUnit.SECONDS);
    }

    boolean isQueued() {
        return queued.getCount() == 0;
    }

    boolean isAdmitted() {
        return admitted.getCount() == 0;
    }
}
