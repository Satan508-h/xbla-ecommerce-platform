package com.xbla.rag.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * MyBatis-Plus 字段自动填充。
 *
 * <p><b>解决什么问题？</b>每张表都有 {@code created_at} 和 {@code updated_at}。
 * 没有这个处理器的话，每次保存实体都要手写：
 * <pre>{@code
 * product.setCreatedAt(OffsetDateTime.now());
 * product.setUpdatedAt(OffsetDateTime.now());
 * productMapper.insert(product);
 * }</pre>
 * 17 张表、几十个保存点，只要有一个人忘了写，数据就不一致了。
 * 而且这种「忘了赋值」的 bug 不会报错，只会让某个字段悄悄是 null。
 *
 * <p>有了它，只要实体字段上标了 {@code @TableField(fill = FieldFill.INSERT)}
 * 之类的注解，MyBatis-Plus 就会在 insert/update 时自动调这里的方法填值。
 *
 * <p><b>为什么用 {@link OffsetDateTime} 而不是 {@link java.time.LocalDateTime}？</b>
 * 因为数据库列类型是 {@code TIMESTAMPTZ}（带时区）。
 * {@code LocalDateTime} 不带时区信息，写入时会被按数据库会话时区解释，
 * 跨时区部署时会出错。{@code OffsetDateTime} 携带明确的偏移量，是
 * {@code TIMESTAMPTZ} 在 Java 侧的标准对应类型。
 */
@Component
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入时填充。
     *
     * <p>用 {@code strictInsertFill} 而不是 {@code setFieldValByName}：
     * strict 版本只在字段上确实标了 {@code FieldFill.INSERT} 时才填，
     * 没标的字段不会被误填。而且它不会覆盖你手动设过的值。
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now();
        this.strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
    }

    /**
     * 更新时填充。只改 {@code updatedAt}，创建时间不动。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
    }
}
