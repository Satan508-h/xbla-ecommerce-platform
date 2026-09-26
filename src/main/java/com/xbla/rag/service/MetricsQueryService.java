package com.xbla.rag.service;

import com.xbla.rag.dto.MetricsSnapshot;

/**
 * 在线指标快照 —— {@code GET /api/status/metrics} 的业务侧（阶段 9.6b）。
 *
 * <h3>★★★ 它的纪律和 {@link ChatTraceQueryService} 是同一条</h3>
 *
 * <p><b>只读、零成本、不调模型。</b>只跑聚合 SQL，<b>不写库、不改状态</b>。
 * 所以它可以被前端反复拉，也可以被监控探针打。
 *
 * <h3>★ 它和 {@code /api/debug/**} 的区别同 {@code StatusController}</h3>
 *
 * <p>那些探针是 {@code @Profile("local")} 的，生产下不存在。
 * 这是<b>新的、只读的、白名单的</b>生产出口。
 *
 * <h3>⚠️ 它和 {@code docs/11-评测报告.md} 不是一回事</h3>
 *
 * <p>报告是<b>离线</b>的：它算的是「同一批题、同一个配置」下的检索与生成质量
 * （HitRate@5 / RAGAS / 噪声底）。本端点算的是<b>在线</b>的：
 * 「这段时间里发生了什么」（流量 / 健康度 / 延迟 / 用户行为）。
 *
 * <p>★ 两者的交集（延迟分位数）口径刻意保持一致 —— 都用
 * {@code qa_log} 那五列、都只统计 {@code status = 1}。
 * <b>但意图分布、gate 分布、检索指标【不】在这里重复</b>：
 * 报告里有，而且报告那侧有整套对拍。在线端点抄一份出来，
 * 只会多一个会漂移的第二事实来源。
 */
public interface MetricsQueryService {

    /**
     * 取一份指标快照。
     *
     * @param requestedWindow 调用方要的窗口。{@code null} / 空 → 用缺省；
     *                        <b>不认识的值 → 替换成缺省并在响应里说明</b>
     *                        （见 {@link MetricsWindow#parseOrNull}）
     */
    MetricsSnapshot snapshot(String requestedWindow);
}
