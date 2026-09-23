package com.xbla.rag.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelCallException#of} —— <b>网络层异常到错误类型的映射</b>。
 *
 * <h2>★ 为什么这个映射值得一个专门的测试</h2>
 *
 * <p>{@link ModelErrorKind} 决定两件事：<b>要不要降级</b>、<b>要不要记熔断失败</b>。
 * 而 {@code of()} 是个长长的 {@code instanceof} 链，每一支的<b>顺序和存在性</b>
 * 都有后果 —— 而且后果不是「报错」，是「悄悄归类错了」。
 *
 * <p>最要命的是兜底那一支 {@code UNKNOWN}：它看起来像个安全的默认值，
 * 实际含义是「不知道怎么回事」—— 它<b>不</b>降级。所以任何本该被认出来的异常
 * 一旦掉进去，表现就是「一次本可以换供应商重试的失败被当成了不可重试」，
 * 而日志里只有一行「模型调用异常 [未知]」。
 */
@DisplayName("ModelCallException · 异常到错误类型的映射")
class ModelCallExceptionTest {

    // ============================================================

    @Test
    @DisplayName("★ 我们自己那道闸门的超时 → TIMEOUT")
    void ourOwnGateMapsToTimeout() {
        ModelCallException e = ModelCallException.of(
                new TimeoutException("出站调用超过 180 秒未返回，调用方主动放弃"),
                "deepseek", "deepseek-flash");

        assertEquals(ModelErrorKind.TIMEOUT, e.kind(),
                "阶段 7 起 OpenAiHttpTransport 用 sendAsync(...).get(timeout) —— "
                        + "它超时抛的是 java.util.concurrent.TimeoutException，"
                        + "而【不是】HttpTimeoutException");
    }

    @Test
    @DisplayName("★★ 它和 IOException 互不相干 —— 这一点编译器就能证明，比运行时断言更强")
    void timeoutExceptionCannotBeAnIoException() {
        // ★★ 这条本来写成一个运行时断言：
        //        assertFalse(new TimeoutException() instanceof IOException)
        //    而 javac 直接拒绝了它 —— 两个类互不相干、谁也不是谁的子类，
        //    那个 instanceof 恒为 false，按 JLS 15.20.2 属于编译期错误。
        //
        //    ★ 这比「我们测出来它不是」强：它是「它不可能是」。
        //      所以这里不再断言，而是把那个事实写下来 ——
        //      它是对 of() 的要求，也是下面那条断言存在的理由。

        // 对 of() 的后果：兜底那一支 IOException 永远接不住它。
        // 不单独给它一条分支，它就会掉进最后的 UNKNOWN。

        // ★ 反对照的另一半 —— 证明 of() 确实在【区分】，而不是什么都返回 TIMEOUT。
        //   这一条成立，上面「TimeoutException → TIMEOUT」才有意义。
        assertEquals(ModelErrorKind.UNKNOWN,
                ModelCallException.of(new RuntimeException("一个没人认识的失败"),
                        "deepseek", "deepseek-flash").kind(),
                "★ 兜底那一支还在工作。如果没有它，"
                        + "「TimeoutException → TIMEOUT」可能只是因为"
                        + "【所有异常都返回 TIMEOUT】，那样它就是恒真的");
    }

    // ============================================================

    @Test
    @DisplayName("★ 已有的三条超时判断不能被新加的那条挤掉")
    void existingTimeoutKindsSurvive() {
        // 建连超时 → CONNECT（不是 TIMEOUT）
        assertEquals(ModelErrorKind.CONNECT,
                ModelCallException.of(new HttpConnectTimeoutException("连不上"),
                        "deepseek", "deepseek-flash").kind(),
                "建连失败要查网络，响应头超时要调超时参数 —— 两者的排查方向相反，"
                        + "所以不能合并成一个「超时」");

        // 响应头超时 → TIMEOUT
        assertEquals(ModelErrorKind.TIMEOUT,
                ModelCallException.of(new HttpTimeoutException("收不到响应头"),
                        "deepseek", "deepseek-flash").kind());

        // ★★ 反对照 —— 上面两条之所以不能调换顺序，靠的是【继承关系】。
        //    把这个事实写出来，而不是写一句「顺序不能乱」的注释：
        assertTrue(new HttpConnectTimeoutException("x") instanceof HttpTimeoutException,
                "★ 建连超时【是】响应头超时的子类。所以 of() 里"
                        + " `instanceof HttpConnectTimeoutException` 必须排在"
                        + " `instanceof HttpTimeoutException` 前面 —— "
                        + "调过来的话，第一条断言会变成 TIMEOUT，"
                        + "而「连不上」和「对方太慢」对熔断器是同一个含义，"
                        + "对排查却是两件完全不同的事");
    }
}
