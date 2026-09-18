package com.xbla.rag.common.handler;

import com.xbla.rag.client.ModelCallException;
import com.xbla.rag.client.ModelErrorKind;
import com.xbla.rag.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理 —— 把异常统一转成 {@link ApiResponse}。
 *
 * <p>有了它，controller 里就不用写 try-catch，保持「只做参数校验和响应封装」
 * 这个约定。
 *
 * <h3>★ 为什么要区分 HTTP 状态码</h3>
 *
 * <p>很多国内接口习惯「无论什么错都返回 HTTP 200，靠 body 里的 code 区分」。
 * 这里<b>不采用</b>那种做法，理由：
 * <ul>
 *   <li>监控告警靠 HTTP 状态码 —— 全是 200 的话，错误率永远是 0，
 *       线上炸了都不知道</li>
 *   <li>Nginx / 网关 / 熔断器的一堆机制都基于状态码，
 *       全返回 200 等于自废武功</li>
 *   <li>前端和测试工具（curl、Postman）都按状态码判断成败，
 *       全 200 会让调试变难</li>
 * </ul>
 *
 * <p>所以这里<b>既设置合理的 HTTP 状态码，又保留 {@link ApiResponse} 的 body 结构</b>——
 * 两者都要。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 模型调用失败。
     *
     * <p>这种错误要<b>分成两类</b>告诉客户端，因为客户端的应对完全不同：
     * <ul>
     *   <li><b>服务端暂时不可用</b>（限流、超时、5xx、余额不足）——
     *       返回 {@code 503}，客户端知道了应该重试</li>
     *   <li><b>请求本身有问题</b>（400）—— 返回 {@code 400}，
     *       客户端重试多少次都一样，得改请求</li>
     * </ul>
     *
     * <p>★ <b>绝不能把异常的原始消息直接返回给客户端</b> ——
     * 它可能包含上游服务的错误详情、内部模型 ID 等信息。
     * 完整信息打日志，对外只给一句可读的说明。
     */
    @ExceptionHandler(ModelCallException.class)
    public ResponseEntity<ApiResponse<Void>> handleModelCall(ModelCallException e) {
        log.error("模型调用失败: {}", e.getMessage(), e);

        ModelErrorKind kind = e.kind();
        HttpStatus status = switch (kind) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case AUTH, QUOTA_EXHAUSTED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };

        String message = switch (kind) {
            case BAD_REQUEST -> "请求参数不被模型接受，请检查输入内容";
            case AUTH -> "模型服务认证失败，请联系管理员检查密钥配置";
            case QUOTA_EXHAUSTED -> "模型服务账户余额不足，请联系管理员充值";
            case PARTIAL_STREAM -> "回答生成中途中断，请重试";
            default -> "智能助手暂时不可用，请稍后重试";
        };

        return ResponseEntity.status(status).body(ApiResponse.fail(message));
    }

    /**
     * 参数校验失败（{@code @Valid} 没通过）。
     *
     * <p>把每个字段的校验消息拼起来返回，方便调用方定位 ——
     * 只回一句「参数错误」的话，前端得自己猜是哪个字段。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));

        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, detail.isEmpty() ? "参数校验失败" : detail));
    }

    /** 业务参数非法（比如传了链路里不存在的 modelKey） */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数非法: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, e.getMessage()));
    }

    /**
     * 兜底。
     *
     * <p>★ 对外只回一句笼统的话，<b>不返回异常堆栈或原始消息</b> ——
     * 那些可能泄露内部实现细节（表名、类名、第三方服务地址）。
     * 完整信息在日志里，靠时间戳和前面的请求日志去对。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("未预期的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("服务内部错误，请稍后重试"));
    }
}
