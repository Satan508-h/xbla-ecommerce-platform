package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xbla.rag.common.handler.VectorTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 知识库切片实体。对应 {@code kb_chunk} 表。
 *
 * <p><b>这是整个项目最核心的表</b>——RAG 检索的最小单位，
 * 阶段 4 的向量召回、阶段 5 的意图定向检索都直接查它。
 *
 * <p><b>★ 为什么类上有 {@code autoResultMap = true}</b>
 *
 * <p>因为 {@link #embedding} 用了自定义 TypeHandler。MyBatis-Plus 默认的查询
 * 走「自动生成的 ResultMap」，它<b>不认识</b>字段上标注的 typeHandler；
 * 只有开启 {@code autoResultMap} 才会把 typeHandler 注册进查询用的 ResultMap。
 *
 * <p>漏掉的症状极具迷惑性：<b>写入正常、查询也正常，就是查出来永远是 null</b>。
 * 插入不报错、SELECT 不报错，数据也在库里，只有映射那一步悄悄失败了。
 * 同类问题在 {@code EvalQuestion} 的 {@code Long[]} 字段上已经踩过一次
 * （见 docs/10 环境排障记录 坑 8）。
 */
@Data
@TableName(value = "kb_chunk", autoResultMap = true)
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
     * <p><b>为什么是 {@code float[]} 而不是 {@code String} 或 {@code double[]}？</b>
     *
     * <p><b>不用 String</b>：阶段 1 建表时这里确实用 String 占位过，因为当时只建表、
     * 不读写向量。但 String 有个致命问题 —— <b>编译器拦不住任何错误</b>。
     * 传进去一个 512 维的文本、一段 JSON、甚至一句「待填」，都要等到运行时
     * 被 PostgreSQL 拒绝才知道。换成 float[] 之后，维度不匹配能在代码层面就约束住，
     * 而 {@link VectorTypeHandler} 负责与数据库文本格式的转换。
     *
     * <p><b>不用 double[]</b>：bge-m3 输出的本来就是 32 位单精度浮点，
     * 1024 维用 double 存会白白多占一倍空间和网络带宽。
     * 而检索用的是余弦相似度，单精度约 1e-7 的表示误差
     * 远小于模型本身的语义噪声，不影响排序结果。
     *
     * <p>⚠️ <b>改这个字段的类型时，别忘了类上的 {@code autoResultMap = true}</b>，
     * 否则写入正常但读取静默返回 null。
     */
    @TableField(typeHandler = VectorTypeHandler.class)
    private float[] embedding;

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
