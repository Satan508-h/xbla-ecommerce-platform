package com.xbla.rag.service;

/**
 * 「按某个键去查，但那条记录不存在」—— 会话历史、链路详情这类<b>只读回看</b>接口的公共父类。
 *
 * <h3>★ 为什么要有父类：为了只有【一个】handler</h3>
 *
 * <p>它转 HTTP 404 这件事必须显式注册（理由见下）。如果每个子类各写一个
 * {@code @ExceptionHandler}，那么「加一个新的 not-found 场景」就变成了
 * <b>「记得也加一个 handler」</b>—— 而忘了加的症状是那个接口回
 * <b>500「服务内部错误」</b>，看起来像服务坏了。
 *
 * <p>★ 这是本项目反复踩的那类坑：<b>两处各写一份、会漂移，而漂移是静默的</b>
 * （{@code QaLogMapper.selectByEvalRun} 的显式列清单坏了两次、
 * {@code McpTool.Field} 的参数名要写一次而不是两次）。
 * 所以这里让所有子类共用一个 handler：<b>新增一个子类就自动被覆盖</b>。
 *
 * <h3>★★ 为什么不能直接用 Spring 的 {@code ResponseStatusException}</h3>
 *
 * <p>因为会被吞掉。{@code GlobalExceptionHandler} 末尾有一个兜底的
 * {@code @ExceptionHandler(Exception.class)}，而 Spring 解析异常的顺序是：
 *
 * <pre>
 *   ExceptionHandlerExceptionResolver   ← @ControllerAdvice 里的 @ExceptionHandler，【先】
 *   ResponseStatusExceptionResolver     ← @ResponseStatus / ResponseStatusException，后
 * </pre>
 *
 * <p>所以抛一个自带 404 语义的 {@code ResponseStatusException}，会在到达
 * Spring 自己的解析器之前就被兜底接走 —— 结果是 500。
 * <b>兜底 handler 的代价就是：任何想要特定状态码的情况都必须显式注册。</b>
 * 同 {@code QueueRejectedException} 那条注释说的同一件事。
 *
 * <h3>★ 为什么是 404，而不是 400 或者「返回空对象」</h3>
 *
 * <pre>
 *   400           —— 不对。键的格式没问题，是那条记录不在
 *   200 + 空对象   —— ✗ 最糟：它把「没有这条记录」和「这条记录是空的」
 *                     渲染成同一个响应，而这两件事的修法完全相反
 *   404           —— ✓
 * </pre>
 *
 * @see ChatSessionNotFoundException
 * @see ChatTraceNotFoundException
 */
public abstract class ResourceNotFoundException extends RuntimeException {

    /** 中文的资源名，用来拼一句人话的错误消息（如「会话」「链路详情」） */
    private final String kind;

    /** 查不到的那个键值 */
    private final String id;

    protected ResourceNotFoundException(String kind, String id) {
        super(kind + "不存在：" + id);
        this.kind = kind;
        this.id = id;
    }

    /**
     * ★ 单独留取值口，别让调用方从 {@code getMessage()} 里反解。
     * 日志按结构化字段打，比拼接字符串可靠。
     */
    public String getKind() {
        return kind;
    }

    public String getId() {
        return id;
    }
}
