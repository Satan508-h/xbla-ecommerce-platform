package com.xbla.rag.common;

/**
 * 统一响应封装。
 *
 * <p><b>为什么需要它？</b>如果每个接口各自返回各自的结构，前端就得为每个接口
 * 写一套解析逻辑。统一成 {@code {code, message, data}} 之后，前端只需要写一次
 * 拦截器，就能处理所有接口的「成功 / 失败 / 提示」。
 *
 * <p><b>为什么用 record 而不是 class？</b>record 是 Java 16 引入的语法，
 * 编译器自动生成构造器、getter、{@code equals()}、{@code hashCode()}、
 * {@code toString()}。响应对象是纯数据载体、不需要可变性，用 record 最合适。
 * Java 21 是我们的固定版本，可以放心用。
 *
 * @param code    业务状态码，0 表示成功，非 0 表示失败
 * @param message 提示信息，成功时通常是 "ok"，失败时是给用户看的错误原因
 * @param data    业务数据，失败时为 null
 * @param <T>     业务数据的类型
 */
public record ApiResponse<T>(int code, String message, T data) {

    /** 成功码。抽成常量，避免代码里到处散落魔法数字 0。 */
    public static final int CODE_SUCCESS = 0;

    /** 通用失败码，具体业务错误码后续在阶段 1 细化。 */
    public static final int CODE_FAILURE = 500;

    /**
     * 成功响应，带数据。
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(CODE_SUCCESS, "ok", data);
    }

    /**
     * 成功响应，不带数据。适用于删除、更新这类只关心成功与否的操作。
     */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(CODE_SUCCESS, "ok", null);
    }

    /**
     * 失败响应。
     */
    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(CODE_FAILURE, message, null);
    }

    /**
     * 失败响应，可自定义业务错误码。
     */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
