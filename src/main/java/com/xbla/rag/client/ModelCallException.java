package com.xbla.rag.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * 模型调用失败时抛出的统一异常。
 *
 * <p>它比普通异常多带了四样东西，每一样都是为了让下游能做出正确决策：
 * <ul>
 *   <li>{@link #kind()} —— 决定「要不要降级」「要不要记熔断失败」，见 {@link ModelErrorKind}</li>
 *   <li>{@link #provider()} / {@link #modelKey()} —— 出错的是哪一家，用于降级事件</li>
 *   <li>{@link #statusCode()} —— 原始 HTTP 状态码，排查时最直接的信息</li>
 *   <li>{@link #responseBody()} —— 原始响应体。<b>服务端的错误说明往往只在这里</b>，
 *       比如「余额不足」「model not found」，丢了就只能看到一句干巴巴的 400</li>
 * </ul>
 *
 * <p>继承 {@link RuntimeException} 而不是受检异常：模型调用失败在整条链路里
 * 是「常态」而非「异常」，每一层都写 {@code throws} 会污染所有方法签名。
 * 真正的错误处理集中在 {@link ChatModelRouter} 一处完成。
 */
public class ModelCallException extends RuntimeException {

    /**
     * 响应体在异常消息里的最大长度。
     *
     * <p>有些服务在报错时会返回一大段 HTML（比如网关的 502 页面），
     * 整个塞进日志会淹没真正的信息。截断到 500 字符足够看清问题。
     */
    private static final int MAX_BODY_IN_MESSAGE = 500;

    private final ModelErrorKind kind;
    private final int statusCode;
    private final String provider;
    private final String modelKey;
    private final String responseBody;

    /**
     * 包级私有（不是 private）—— 因为 {@code ModelCallException}、
     * {@code OpenAiHttpTransport}、{@code OpenAiCompatibleLlmClient} 都在
     * {@code com.xbla.rag.client} 包下，是同一条链路上的协作方。
     *
     * <p>对外仍然只暴露下面的静态工厂方法，保证异常消息的格式统一 ——
     * 直接 new 出来的异常会缺少 provider/model 上下文，日志里就定位不到是谁出的问题。
     */
    ModelCallException(ModelErrorKind kind, int statusCode, String provider,
                       String modelKey, String responseBody, String message,
                       Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = statusCode;
        this.provider = provider;
        this.modelKey = modelKey;
        this.responseBody = responseBody;
    }

    // ============================================================
    // 工厂方法 —— 按「错误是怎么发现的」区分
    // ============================================================

    /**
     * 从 HTTP 状态码构造。
     *
     * <p>调用方应先判断状态码是否为 2xx，非 2xx 才走这里。
     */
    public static ModelCallException fromHttpStatus(int status, String body,
                                                    String provider, String modelKey) {
        ModelErrorKind kind = ModelErrorKind.fromHttpStatus(status);
        String snippet = truncate(body);
        String message = "模型调用失败 [%s] provider=%s model=%s HTTP %d%s"
                .formatted(kind.reason(), provider, modelKey, status,
                        snippet.isEmpty() ? "" : " body=" + snippet);
        return new ModelCallException(kind, status, provider, modelKey, body, message, null);
    }

    /**
     * HTTP 200 但正文为空 —— 推理模型把 {@code max-tokens} 吃光的典型症状。
     *
     * @param detail 补充信息（比如 finish_reason、输出 token 数），
     *               写进消息里能大幅缩短排查时间
     */
    public static ModelCallException emptyContent(String provider, String modelKey, String detail) {
        String message = ("模型返回空内容 [%s] provider=%s model=%s —— %s。" +
                "最常见的原因是推理模型把 max-tokens 用完了" +
                "（本项目默认 2048，见 LlmProperties.Defaults#maxTokens）")
                .formatted(ModelErrorKind.EMPTY_CONTENT.reason(), provider, modelKey, detail);
        return new ModelCallException(ModelErrorKind.EMPTY_CONTENT, 200, provider, modelKey,
                null, message, null);
    }

    /**
     * 流已经开始吐字之后才中断。
     *
     * <p>这类失败<b>不能降级</b>（用户已经看到部分输出了），
     * 但<b>要</b>计入熔断失败。
     */
    public static ModelCallException partialStream(String provider, String modelKey,
                                                   int charsAlreadySent, Throwable cause) {
        String message = ("流式响应中途断开 [%s] provider=%s model=%s 已发送 %d 字符 —— " +
                "已吐字故无法降级")
                .formatted(ModelErrorKind.PARTIAL_STREAM.reason(), provider, modelKey,
                        charsAlreadySent);
        return new ModelCallException(ModelErrorKind.PARTIAL_STREAM, 200, provider, modelKey,
                null, message, cause);
    }

    /**
     * 客户端主动断开（用户关了页面 / 刷新了浏览器）。
     *
     * <p>★ 这类失败<b>不计熔断、不降级</b>，理由见
     * {@link ModelErrorKind#CLIENT_ABORTED}。
     */
    public static ModelCallException clientAborted(String provider, String modelKey,
                                                   int charsAlreadySent, Throwable cause) {
        String message = ("客户端已断开 [%s] provider=%s model=%s 已发送 %d 字符 —— " +
                "不计入熔断统计，也不降级")
                .formatted(ModelErrorKind.CLIENT_ABORTED.reason(), provider, modelKey,
                        charsAlreadySent);
        return new ModelCallException(ModelErrorKind.CLIENT_ABORTED, 200, provider, modelKey,
                null, message, cause);
    }

    /**
     * 从任意异常归类 —— <b>网络层异常到错误类型的映射就在这里</b>。
     *
     * <p>★ 判断顺序不能乱：{@link HttpConnectTimeoutException} 是
     * {@link HttpTimeoutException} 的子类，而 {@link HttpTimeoutException}
     * 又是 {@link IOException} 的子类。如果先判 {@code IOException}，
     * 所有超时都会被误判成普通连接错误 —— 而它们对熔断器的含义是一样的，
     * 但对排查来说完全不同（超时要调超时参数，连接错误要查网络）。
     */
    public static ModelCallException of(Throwable e, String provider, String modelKey) {
        if (e instanceof ModelCallException mce) {
            return mce;
        }
        ModelErrorKind kind;
        if (e instanceof HttpConnectTimeoutException) {
            // 连 TCP 都没建起来：域名解析失败、或对方限流直接丢包
            kind = ModelErrorKind.CONNECT;
        } else if (e instanceof HttpTimeoutException) {
            // 连上了，但对方迟迟不回（流式场景下 = 首字超时）
            kind = ModelErrorKind.TIMEOUT;
        } else if (e instanceof ConnectException) {
            kind = ModelErrorKind.CONNECT;
        } else if (e instanceof IOException) {
            kind = ModelErrorKind.CONNECT;
        } else if (e instanceof InterruptedException) {
            // 线程被中断通常是应用关闭或客户端断开，不当成供应商的错
            kind = ModelErrorKind.PARTIAL_STREAM;
            Thread.currentThread().interrupt();
        } else {
            kind = ModelErrorKind.UNKNOWN;
        }

        String message = "模型调用异常 [%s] provider=%s model=%s: %s"
                .formatted(kind.reason(), provider, modelKey, e.toString());
        return new ModelCallException(kind, -1, provider, modelKey, null, message, e);
    }

    /**
     * 整条降级链全部失败。
     *
     * <p>错误类型取<b>最后一家</b>的失败类型 —— 因为那是最接近「所有模型都不可用」
     * 这个结论的一次尝试，比第一家的更有参考价值。
     */
    public static ModelCallException allFailed(Object traceSummary, ModelCallException lastError) {
        String message = "所有模型均不可用。降级轨迹：%s；最后一次失败：%s"
                .formatted(traceSummary, lastError == null ? "无" : lastError.getMessage());
        ModelCallException base = lastError;
        return new ModelCallException(
                base == null ? ModelErrorKind.UNKNOWN : base.kind(),
                base == null ? -1 : base.statusCode(),
                base == null ? "?" : base.provider(),
                base == null ? "?" : base.modelKey(),
                null, message, base);
    }

    // ============================================================
    // 访问器
    // ============================================================

    public ModelErrorKind kind() {
        return kind;
    }

    /** 原始 HTTP 状态码；非 HTTP 层失败（如网络异常）时为 -1 */
    public int statusCode() {
        return statusCode;
    }

    public String provider() {
        return provider;
    }

    public String modelKey() {
        return modelKey;
    }

    public String responseBody() {
        return responseBody;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.strip();
        return trimmed.length() <= MAX_BODY_IN_MESSAGE
                ? trimmed
                : trimmed.substring(0, MAX_BODY_IN_MESSAGE) + "...(已截断)";
    }
}
