package com.xbla.rag.dto;

import java.util.List;

/**
 * 批量提交（目录扫描 / 数据库同步）的响应。
 *
 * <p><b>为什么提交接口返回的是「已受理」而不是「已完成」</b>：
 * 入库是异步的（见 {@code AsyncConfig.ingestExecutor} 的说明），
 * 接口返回时后台任务可能刚开始跑。所以这里给的是
 * 「提交了几份、跳过了几份」，而不是「成功入库了几份」。
 *
 * <p>真正的结果要通过 {@code GET /api/kb/documents/{docId}} 或者
 * {@code GET /api/kb/documents?status=3} 去查。
 * <b>接口语义必须和实际行为一致</b> —— 如果这里谎称「成功 200 份」，
 * 用户在十分钟后查库发现只有 30 份时，会完全不知道该信哪个。
 *
 * @param scanned    扫描到的候选数量。数据库同步时等于源表的行数
 * @param submitted  本次提交处理的份数（新登记 + 状态为失败需要重试的）
 * @param skipped    跳过的份数。跳过原因是<b>内容摘要相同且已经成功入库过</b> ——
 *                   重复入库会重复花向量化的钱，这是去重的意义
 * @param docIds     本次提交的文档 ID 列表，前端可以拿它轮询进度
 */
public record KbBatchSubmitResponse(int scanned, int submitted, int skipped, List<Long> docIds) {

    public static KbBatchSubmitResponse of(int scanned, List<Long> docIds, int skipped) {
        return new KbBatchSubmitResponse(scanned, docIds.size(), skipped, List.copyOf(docIds));
    }
}
