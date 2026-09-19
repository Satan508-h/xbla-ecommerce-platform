package com.xbla.rag.service;

import com.xbla.rag.dto.KbBatchSubmitResponse;
import com.xbla.rag.entity.KbDocument;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;

/**
 * 知识库文档入库服务。
 *
 * <p><b>三个入口，一条流水线</b>：
 * <pre>
 *   ① submitUpload()      用户上传单个文件
 *   ② submitCorpusScan()  扫描 data/corpus/ 批量灌仿真语料
 *   ③ syncFromDatabase()  把 after_sale_policy / product 表同步成文档
 *              ↓
 *        全部落成 kb_document(status=1) + 提交到 ingestExecutor
 *              ↓
 *        DocumentIngestWorker.process()  ← 解析 → 切分 → 向量化 → 写库
 * </pre>
 *
 * <p><b>三个入口共用同一条流水线</b>，这是刻意的设计。
 * 如果每个来源各写一套「解析+切分+向量化」，就会出现三份长得差不多、
 * 但边界处理略有差异的代码 —— 而这类差异在检索质量上表现为
 * 「某些文档的切片莫名其妙地差」，几乎无法排查。
 */
public interface KbIngestService {

    /**
     * 提交一份上传的文件。
     *
     * <p>方法返回时文件已经落盘、{@code kb_document} 已经写入（{@code status=1}）、
     * 后台任务已经提交。<b>但入库还没完成</b>，调用方要拿返回的 docId 去轮询状态。
     *
     * @param in               文件流。<b>本方法不关闭它</b>
     * @param originalFileName 原始文件名
     * @param docType          文档类型 1商品详情 2售后政策 3促销规则 4FAQ 5说明书
     * @param relatedProductId 关联商品 ID，可为 null
     * @param effectiveFrom    生效起始时间，可为 null
     * @param effectiveTo      生效结束时间，可为 null
     * @return 已登记的文档（含 id）
     */
    KbDocument submitUpload(InputStream in, String originalFileName, Integer docType,
                            Long relatedProductId,
                            OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo) throws IOException;

    /**
     * 扫描 {@code xbla.kb.corpus-dir} 目录，把里面的文件批量入库。
     *
     * <p>会跳过子目录和隐藏文件。每个文件独立提交 ——
     * 其中一个文件格式不认识导致失败，不影响其余的。
     *
     * <h4>★ 文档类型来自目录里的 {@code manifest.yml}，不是 {@code docType} 参数</h4>
     *
     * <p>每份文件的 {@code doc_type} 由语料清单声明
     * （见 {@code CorpusManifest}）。{@code docType} 参数只在
     * <b>清单里没声明这个文件时</b>兜底 —— 且会打一条 WARN。
     *
     * <p>为什么不能用「整个目录一个类型」：那样促销规则、FAQ、说明书、
     * 导购指南会被统统标成售后政策，而 {@code doc_type} 是阶段 5
     * 意图定向检索的过滤条件，标错的后果是「某一类查询永远返回空」。
     *
     * <h4>★ 重复扫描会校正 doc_type</h4>
     *
     * <p>命中去重（内容没变）时，如果清单声明的类型与库里不一致，
     * 会把 {@code kb_document} 和冗余的 {@code kb_chunk.doc_type} 一起改过来。
     * <b>不重新向量化</b> —— {@code doc_type} 不参与 embedding 和分词。
     *
     * @param docType 清单未声明时的兜底类型。可为 null（默认 4 FAQ）
     */
    KbBatchSubmitResponse submitCorpusScan(Integer docType);

    /**
     * 把数据库里的业务数据同步成知识库文档。
     *
     * <p>目前同步两张表：
     * <ul>
     *   <li>{@code after_sale_policy} → 每行一份文档（docType=2 售后政策）</li>
     *   <li>{@code product} → 每个商品一份文档（docType=1 商品详情），
     *       含 {@code suitable_for}（适用人群）—— 这是阶段 5 演示题的命中目标</li>
     * </ul>
     *
     * <p>用内容摘要去重：源表没改动的行不会被重复入库，不会重复花向量化的钱。
     */
    KbBatchSubmitResponse syncFromDatabase();

    /**
     * 查询文档的入库状态。文档不存在时返回 null。
     */
    KbDocument getStatus(Long documentId);
}
