package com.xbla.rag.controller;

import com.xbla.rag.common.ApiResponse;
import com.xbla.rag.dto.KbBatchSubmitResponse;
import com.xbla.rag.dto.KbDocumentResponse;
import com.xbla.rag.entity.KbDocument;
import com.xbla.rag.service.KbIngestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;

/**
 * 知识库文档入库接口。
 *
 * <p>按 CLAUDE.md 的约定，controller 只做<b>参数校验和响应封装</b>，
 * 业务编排全部在 {@link KbIngestService} 里。
 *
 * <h3>四个接口</h3>
 * <table border="1">
 *   <caption>接口一览</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>POST</td><td>/api/kb/documents</td>
 *       <td>上传单个文件（multipart）</td></tr>
 *   <tr><td>POST</td><td>/api/kb/documents/scan</td>
 *       <td>扫描 data/corpus/ 批量入库</td></tr>
 *   <tr><td>POST</td><td>/api/kb/documents/sync</td>
 *       <td>把商品/售后政策表同步成知识库文档</td></tr>
 *   <tr><td>GET</td><td>/api/kb/documents/{id}</td>
 *       <td>查询入库状态（前端轮询这个）</td></tr>
 * </table>
 *
 * <p><b>★ 所有入库接口都是「受理」语义，不是「完成」语义。</b>
 * 返回时后台任务可能刚开始跑，真正的结果要轮询状态接口。
 * 接口文档里必须写清楚这一点，否则调用方会以为「200 就是入库成功了」。
 *
 * <h3>⚠️ 安全说明（阶段 3 为演示留的口子）</h3>
 *
 * <p>这几个接口<b>目前没有鉴权</b>，任何人都能上传文件并触发向量化
 * —— 而向量化是要花钱的。这在本地演示环境可以接受，但
 * <b>阶段 8 部署到公网之前必须加上鉴权 + 限流</b>。
 * 这一点记在 docs/07 的部署检查清单里。
 */
@Slf4j
@RestController
@RequestMapping("/api/kb")
public class KbDocumentController {

    private final KbIngestService ingestService;

    public KbDocumentController(KbIngestService ingestService) {
        this.ingestService = ingestService;
    }

    // ============================================================
    // 上传单个文件
    // ============================================================

    /**
     * 上传一份文档并触发入库。
     *
     * <p>用 {@code multipart/form-data}，文件字段名是 {@code file}。
     *
     * <pre>
     *   curl -X POST localhost:8080/api/kb/documents \
     *        -F "file=@退货政策.pdf" -F "docType=2"
     * </pre>
     *
     * @param file             文档文件，<b>必填</b>
     * @param docType          文档类型 1商品详情 2售后政策 3促销规则 4FAQ 5说明书。
     *                         不传默认 4（FAQ）
     * @param relatedProductId 关联商品 ID，可选
     * @param effectiveFrom    生效起始时间（ISO 格式），可选
     * @param effectiveTo      生效结束时间（ISO 格式），可选
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KbDocumentResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "docType", required = false) Integer docType,
            @RequestParam(value = "relatedProductId", required = false) Long relatedProductId,
            @RequestParam(value = "effectiveFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime effectiveFrom,
            @RequestParam(value = "effectiveTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime effectiveTo) {

        // ---- 参数校验（controller 的职责，不往下沉）----
        if (file == null || file.isEmpty()) {
            return ApiResponse.fail("上传文件为空");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            return ApiResponse.fail("上传文件缺少文件名");
        }

        try (InputStream in = file.getInputStream()) {
            KbDocument doc = ingestService.submitUpload(
                    in, originalName, docType, relatedProductId, effectiveFrom, effectiveTo);
            log.info("收到上传 file={} size={} docId={}", originalName, file.getSize(), doc.getId());
            return ApiResponse.ok(KbDocumentResponse.from(doc));
        } catch (IOException e) {
            // 落盘失败（磁盘满、权限不足）。这是基础设施问题，
            // 不是用户传错了文件，所以日志打 ERROR 级别
            log.error("文件落盘失败 file={}", originalName, e);
            return ApiResponse.fail("文件保存失败：" + e.getMessage());
        }
    }

    // ============================================================
    // 批量：扫描语料目录
    // ============================================================

    /**
     * 扫描 {@code xbla.kb.corpus-dir} 目录，把里面的文档批量入库。
     *
     * <p>这就是路线图 3.1 生成的仿真语料的入口。
     * 重复调用是安全的 —— 内容摘要相同的文件会被跳过，不会重复花向量化的钱。
     *
     * <pre>
     *   curl -X POST "localhost:8080/api/kb/documents/scan?docType=2"
     * </pre>
     */
    @PostMapping("/documents/scan")
    public ApiResponse<KbBatchSubmitResponse> scanCorpus(
            @RequestParam(value = "docType", required = false) Integer docType) {
        KbBatchSubmitResponse result = ingestService.submitCorpusScan(docType);
        return ApiResponse.ok(result);
    }

    // ============================================================
    // 批量：数据库同步
    // ============================================================

    /**
     * 把业务表同步成知识库文档。
     *
     * <p>同步 {@code after_sale_policy}（售后政策）和 {@code product}（商品详情）两张表。
     * 商品文档里包含「适用人群与场景」小节 ——
     * 这是阶段 5 演示题「这个适合送长辈吗」能被检索命中的关键。
     *
     * <pre>
     *   curl -X POST localhost:8080/api/kb/documents/sync
     * </pre>
     */
    @PostMapping("/documents/sync")
    public ApiResponse<KbBatchSubmitResponse> syncFromDatabase() {
        return ApiResponse.ok(ingestService.syncFromDatabase());
    }

    // ============================================================
    // 状态查询
    // ============================================================

    /**
     * 查询一份文档的入库状态。前端拿上传返回的 docId 轮询这个接口。
     *
     * <p>返回体里的 {@code finished} 字段告诉前端<b>可以停止轮询了</b>，
     * 不用让前端自己判断「3 和 4 算结束吗」这种属于后端的业务知识。
     */
    @GetMapping("/documents/{id}")
    public ApiResponse<KbDocumentResponse> status(@PathVariable("id") Long id) {
        KbDocument doc = ingestService.getStatus(id);
        if (doc == null) {
            return ApiResponse.fail("文档不存在：" + id);
        }
        return ApiResponse.ok(KbDocumentResponse.from(doc));
    }
}
