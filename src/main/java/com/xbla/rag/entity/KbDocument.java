package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 知识库文档实体。对应 {@code kb_document} 表。
 *
 * <p>存的是<b>原始文档的元信息</b>，正文切分后存在 {@link KbChunk}。
 */
@Data
@TableName("kb_document")
public class KbDocument {

    // ================================================================
    // 常量：状态机取值
    //
    // ★ 为什么把「魔法数字」提成常量，而且放在实体类上
    //
    //   status 的四个取值要在【三个地方】使用：入库服务（登记新文档）、
    //   入库流水线（推进状态）、状态查询接口（翻译成中文）。
    //   散落成三处的字面量 1/2/3/4 有个致命问题：
    //   它们没有名字，读代码的人看到 setStatus(3) 得回去翻数据库文档
    //   才知道 3 是什么意思，而且写错了编译器也不会拦。
    //
    //   放在实体类上而不是单独建一个常量类，是因为 status 是这张表的属性，
    //   就近定义让「谁定义了这些值」一目了然。
    // ================================================================

    /** 待处理：记录已登记，还没开始跑流水线 */
    public static final int STATUS_PENDING = 1;
    /** 处理中：流水线正在跑（解析/切分/向量化/写库） */
    public static final int STATUS_PROCESSING = 2;
    /** 已入库：切片和向量都已写入，可以被检索到 */
    public static final int STATUS_DONE = 3;
    /** 处理失败：详见 {@code errorMsg} */
    public static final int STATUS_FAILED = 4;

    // ---- 文档类型 docType ----
    public static final int TYPE_PRODUCT = 1;
    public static final int TYPE_AFTER_SALE = 2;
    public static final int TYPE_PROMOTION = 3;
    public static final int TYPE_FAQ = 4;
    public static final int TYPE_MANUAL = 5;

    // ---- 来源 sourceType ----
    public static final int SOURCE_FILE = 1;
    public static final int SOURCE_DATABASE = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String docNo;

    private String title;

    /** 文档类型：1商品详情 2售后政策 3促销规则 4FAQ 5说明书 */
    private Integer docType;

    /** 来源：1文件上传 2数据库同步 */
    private Integer sourceType;

    private String fileName;

    private String filePath;

    private Long fileSize;

    /**
     * 文件内容 SHA-256。
     *
     * <p>用于<b>重复上传去重</b>：同一个文件再次上传时先算哈希查一下，
     * 命中就直接跳过，省掉一次完整的解析 + 向量化
     * （向量化要调用模型 API，是花钱又花时间的操作）。
     */
    private String fileHash;

    private String mimeType;

    private Long relatedProductId;

    private OffsetDateTime effectiveFrom;

    private OffsetDateTime effectiveTo;

    /** 版本号。文档换版时新增记录，旧版本标记 effectiveTo */
    private Integer version;

    /**
     * 处理状态：1待处理 2处理中 3已入库 4处理失败。
     *
     * <p>为什么是个状态机而不是布尔值：文档入库要经过
     * 解析 → 切分 → 批量向量化 → 写库 四步，可能耗时几十秒到几分钟，
     * 不可能在 HTTP 请求里同步完成。必须有字段跟踪"处理到哪一步了"，
     * 前端才能轮询进度，失败时也才知道是哪个环节出的问题。
     */
    private Integer status;

    /** 切分出的切片数，处理完成后回填 */
    private Integer chunkCount;

    /** 处理失败时的错误信息 */
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Integer deleted;
}
