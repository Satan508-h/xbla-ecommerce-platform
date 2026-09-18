package com.xbla.rag.client.dto;

/**
 * 一次<b>流式</b>对话调用的结果 —— 领域对象。
 *
 * <p><b>为什么流式的返回值里没有正文？</b>
 * 因为正文已经通过回调<b>逐字送出去了</b>（推给了浏览器）。
 * 再在返回值里带一份完整副本，等于把整个回答在内存里存两遍 ——
 * 对长回答是实打实的浪费。
 *
 * <p>所以这里的职责只有三件事：报告用量、报告结束原因、
 * 以及<b>告诉我们到底吐了多少字</b>。
 *
 * @param usage        token 用量。★ 流式场景下很可能为 null（见下）
 * @param finishReason 结束原因
 * @param contentChars 实际推送出去的<b>正文字符数</b>
 * @param ttfbMs       首字节耗时（Time To First Byte）：从发出请求到响应头到达
 * @param totalMs      整个流的持续时间
 *
 * @see #isEmptyContent()
 */
public record StreamResult(

        ChatUsage usage,

        String finishReason,

        int contentChars,

        int ttfbMs,

        int totalMs

) {

    /**
     * ★ 喂给熔断器的耗时应该用哪个？<b>用 {@link #ttfbMs}，不是 {@link #totalMs}。</b>
     *
     * <p>原因：熔断器有一个「慢调用率」阈值。如果把整个流的持续时间当作观测值，
     * 那么一次 <b>正常</b>的 60 秒长回答会被判定为慢调用 ——
     * 失败率一超阈值，熔断器就会在<b>系统最健康、正在正常干重活</b>的时候跳闸。
     * 这是最坏的反向优化：越努力服务，越容易被判成故障。
     *
     * <p>TTFB 才是真正反映「这家供应商有没有问题」的指标：
     * 正常情况下它只有 1-3 秒，超过 15 秒（配置里的
     * {@code slow-call-duration-threshold}）确实说明这家不对劲。
     *
     * <p>这个取舍已记录在 {@code docs/08-技术决策记录(ADR).md}。
     */
    public int breakerObservedMs() {
        return ttfbMs;
    }

    /**
     * 有没有真的吐出正文？
     *
     * <p>★ <b>这是流式降级决策的唯一依据。</b>
     *
     * <p>为 {@code true} 时说明「HTTP 200、状态正常，但一个字都没给用户」——
     * 典型原因是推理模型把 max-tokens 全用在推理上了。
     * 此时降级到下一家是安全的：<b>用户什么都没看到，重来一遍没有副作用</b>。
     *
     * <p>反过来，一旦已经吐过字，就<b>不能</b>降级了 ——
     * 换一家从头重来会让用户看到两段拼接起来的、前后矛盾的话。
     * 这个判断的调用点见 {@code ChatModelRouter#chatStream}。
     *
     * @return 一个字符都没吐出去时返回 true
     */
    public boolean isEmptyContent() {
        return contentChars == 0;
    }

    /**
     * 流式场景为什么可能拿不到 usage？
     *
     * <p>三个原因，都属于正常情况，不要当成 bug：
     * <ol>
     *   <li>供应商不支持 {@code stream_options.include_usage} ——
     *       本项目两个供应商实测都支持，但换供应商时可能不支持</li>
     *   <li>连接在 usage 汇总 chunk 到达之前就断了</li>
     *   <li>用户中途关闭了页面，我们把上游流掐断了（这时候本来也不该计费）</li>
     * </ol>
     *
     * <p>拿不到时成本记 NULL，<b>绝不估算</b>。
     */
    public boolean hasUsage() {
        return usage != null && usage.isPresent();
    }
}
