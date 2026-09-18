package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 知识库切片实体。对应 {@code kb_chunk} 表。
 *
 * <p><b>这是整个项目最核心的表</b>——RAG 检索的最小单位，
 * 阶段 4 的向量召回、阶段 5 的意图定向检索都直接查它。
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    /** 在文档内的序号，从 0 开始 */
    private Integer chunkIndex;

    /** 切片正文 */
    private String content;

    /** 切片内容 SHA-256。文档局部改动后只重新向量化变化的部分 */
    private String contentHash;

    /**
     * 标题层级路径，如「售后政策 &gt; 退货 &gt; 七天无理由」。
     *
     * <p>作用有两个：一是切分时保留结构信息（避免把不同章节的内容混在一起），
     * 二是检索命中后可以把路径一并塞进 prompt，给 LLM 提供上下文。
     */
    private String headingPath;

    private Integer tokenCount;

    /**
     * ★ 向量列，1024 维（对应 bge-m3 的输出维度）。数据库类型是 {@code vector(1024)}。
     *
     * <p><b>为什么这里声明成 {@code String} 而不是 {@code float[]}？</b>
     *
     * <p>因为 {@code vector} 不是 PostgreSQL 内置类型，而是 pgvector 扩展定义的，
     * JDBC 驱动不认识它。MyBatis 默认的类型处理器也没有 float[] ↔ vector 的转换规则，
     * 直接用 float[] 会报「无法转换类型」。
     *
     * <p>正确的做法是<b>自定义 TypeHandler</b>：写入时把 float[] 拼成
     * {@code [0.1,0.2,...]} 形式的字符串，再用 {@code ?::vector} 显式转换；
     * 读取时把数据库返回的字符串解析回 float[]。
     *
     * <p>阶段 1 只是建表和灌种子数据，<b>不涉及向量读写</b>，所以先用 String 占位，
     * 读写能力在阶段 3（文档入库、批量向量化）再实现。
     * 用 String 读也能工作——pgjdbc 会把 vector 值以 {@code [0.1,0.2,...]} 文本形式返回。
     */
    private String embedding;

    /** 灵活扩展字段，避免为偶然需求频繁改表 */
    private String metadata;

    /**
     * ★ 冗余自 {@link KbDocument} 的字段（下面四个）。
     *
     * <p>为什么冗余：向量检索里「先过滤再算距离」和「先算距离再过滤」性能差几十倍。
     * 阶段 5 的意图定向检索是"问售后政策 → 只在 doc_type = 2 的切片里搜"。
     * 如果这些字段只在 kb_document 上，每次检索都要 join 回表才能过滤，
     * 而 HNSW 索引在 join 场景下往往用不上，会退化成全表扫描逐条算距离。
     *
     * <p>冗余到本表后，过滤条件和向量索引可以走同一次索引扫描。
     * <b>用少量存储空间换检索性能</b>，是检索系统的标准优化手段。
     */
    private Long relatedProductId;

    /** ★ 冗余自 kb_document。用于意图定向检索 */
    private Integer docType;

    /** ★ 冗余自 kb_document。用于时效过滤 */
    private OffsetDateTime effectiveFrom;

    private OffsetDateTime effectiveTo;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Integer deleted;
}
