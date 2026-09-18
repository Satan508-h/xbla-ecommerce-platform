package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 商品表实体（SPU，即"某款商品"；具体可购买的型号在 {@link ProductSku}）。
 *
 * <p>对应迁移脚本 {@code V1__user_and_product.sql} 里的 {@code product} 表。
 *
 * <p><b>实体类怎么写，判断标准只有一个：字段和数据库表一一对应。</b>
 * 不要在这里加业务方法（那是 Service 的事），也不要加 DTO 专用的字段（那是 dto 包的事）。
 */
@Data
@TableName("product")
public class Product {

    /**
     * {@code @TableId} 标记主键。
     *
     * <p>★ {@code type = IdType.AUTO} 必须显式写。MyBatis-Plus 默认是
     * {@code ASSIGN_ID}（雪花算法），会自己生成一个 19 位数字当主键，
     * 和我们的 {@code BIGSERIAL} 自增序列冲突——虽然也能插入成功，
     * 但自增序列就被架空了，之后手工 INSERT 时不指定 id 会主键冲突。
     *
     * <p>（application.yml 里也配了 {@code id-type: auto} 作为全局默认，
     * 这里再写一次是为了让实体自身可读——看类就知道主键怎么来的。）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 商品编号，对外展示用 */
    private String productNo;

    private String name;

    /** 类目，如"手机"、"笔记本电脑" */
    private String category;

    private String subCategory;

    private String brand;

    /**
     * 售价。
     *
     * <p>★ 用 {@link BigDecimal} 而不是 {@code double}/{@code float}。
     * 浮点数存不下 0.1 这类十进制小数，算钱会出 0.30000000000000004 这种结果。
     * 数据库列是 {@code NUMERIC(12,2)}，Java 侧对应的就是 BigDecimal。
     */
    private BigDecimal price;

    /** 划线价（原价） */
    private BigDecimal originalPrice;

    private String description;

    /** 卖点，如"续航18小时/重量1.2kg"。为 RAG 语义检索准备 */
    private String sellingPoints;

    /** 适用人群/场景，如"适合送长辈"。解决"这个能送老人吗"类问题的检索泛化 */
    private String suitableFor;

    /** 上架状态：1上架 0下架 */
    private Integer status;

    /**
     * 创建时间。
     *
     * <p>{@code fill = FieldFill.INSERT} 表示「插入时由
     * {@link com.xbla.rag.config.MybatisPlusMetaObjectHandler} 自动填值」，
     * 不用手写 {@code product.setCreatedAt(...)}。
     */
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    /** 更新时间。INSERT_UPDATE 表示插入和更新时都会自动填 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    /**
     * 软删除标记：0正常 1已删除。
     *
     * <p>这里没写 {@code @TableLogic}，因为 application.yml 里配了全局的
     * {@code logic-delete-field: deleted}，MyBatis-Plus 会自动识别同名字段。
     *
     * <p>生效后：查询会自动追加 {@code WHERE deleted = 0}；
     * 调用 {@code removeById()} 会变成 {@code UPDATE ... SET deleted = 1}。
     */
    private Integer deleted;
}
