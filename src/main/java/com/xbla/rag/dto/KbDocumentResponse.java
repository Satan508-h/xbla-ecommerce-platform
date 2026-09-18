package com.xbla.rag.dto;

import com.xbla.rag.entity.KbDocument;

import java.time.OffsetDateTime;

/**
 * 文档入库状态响应。
 *
 * <p>前端拿到 {@code docId} 之后轮询这个接口看进度。
 *
 * @param docId       文档 ID
 * @param docNo       文档编号（对外展示用，不暴露自增 ID）
 * @param title       文档标题
 * @param fileName    原始文件名
 * @param docType     文档类型：1商品详情 2售后政策 3促销规则 4FAQ 5说明书
 * @param sourceType  来源：1文件上传 2数据库同步
 * @param status      <b>状态机</b>：1待处理 2处理中 3已入库 4处理失败
 * @param statusText  状态的中文说明。★ 由后端给出而不是让前端查表 ——
 *                    状态码的含义属于后端领域知识，散到前端会导致
 *                    「后端加了一个状态、前端显示空白」这种问题
 * @param chunkCount  切分出的切片数。<b>status=3 时才有意义</b>，
 *                    其它状态下是 0
 * @param errorMsg    处理失败时的错误信息。<b>status=4 时才有值</b>，
 *                    其它状态下是 null
 * @param createdAt   提交时间。前端可以据此算「已经等了多久」
 * @param finished    是否已经结束（成功或失败都算）。
 *                    ★ <b>必须是 record 组件，不能只是普通方法</b> ——
 *                    Jackson 序列化 record 时<b>只输出 record 组件</b>，
 *                    写在 record 体里的普通方法不会被当成属性输出。
 *                    这一点是实测发现的：最初把它写成 {@code public boolean finished()}
 *                    并以为 Jackson 会当成 getter，结果响应 JSON 里压根没有这个字段，
 *                    而前端要靠它判断「该不该停止轮询」。
 *
 *                    <p>把它由后端算好而不是让前端自己判断
 *                    「3 和 4 算不算结束」，是因为<b>状态码的含义属于后端领域知识</b>。
 *                    散到前端之后，后端加一个新状态就会出现「前端永远在轮询」
 *                    这种查起来很费劲的问题。
 */
public record KbDocumentResponse(
        Long docId,
        String docNo,
        String title,
        String fileName,
        Integer docType,
        Integer sourceType,
        Integer status,
        String statusText,
        Integer chunkCount,
        String errorMsg,
        OffsetDateTime createdAt,
        boolean finished) {

    public static KbDocumentResponse from(KbDocument doc) {
        return new KbDocumentResponse(
                doc.getId(),
                doc.getDocNo(),
                doc.getTitle(),
                doc.getFileName(),
                doc.getDocType(),
                doc.getSourceType(),
                doc.getStatus(),
                textOf(doc.getStatus()),
                doc.getChunkCount(),
                doc.getErrorMsg(),
                doc.getCreatedAt(),
                isFinished(doc.getStatus()));
    }

    /**
     * 状态码 → 中文。
     *
     * <p>写成 switch 而不是 Map 查表：状态码是有限的几个常量，
     * switch 能让编译器在新增状态时提醒「这里没处理」——
     * Map 查表拿不到这个保护，漏了就是返回 null。
     */
    private static String textOf(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 1 -> "待处理";
            case 2 -> "处理中";
            case 3 -> "已入库";
            case 4 -> "处理失败";
            default -> "未知状态(" + status + ")";
        };
    }

    /** 是否已经结束。成功（3）和失败（4）都算结束，前端可以停止轮询 */
    private static boolean isFinished(Integer status) {
        return status != null && (status == 3 || status == 4);
    }
}
