package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 用户实体。对应 {@code app_user} 表。
 *
 * <p><b>类名为什么不叫 User？</b>两个原因：
 * <ol>
 *   <li>数据库表名不能叫 {@code user}——{@code USER} 是 SQL 标准保留字
 *       （等价于 {@code CURRENT_USER}），用它做表名以后每条 SQL 都得加引号。</li>
 *   <li>类名如果叫 {@code User}，会很容易和 Spring Security 的
 *       {@code org.springframework.security.core.userdetails.User} 撞名。
 *       叫 {@code AppUser} 一眼就知道是「本项目的用户」。</li>
 * </ol>
 */
@Data
@TableName("app_user")
public class AppUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户编号，对外展示用，不暴露自增 ID */
    private String userNo;

    private String nickname;

    /** 手机号，脱敏存储（如 138****8888） */
    private String phone;

    /** 会员等级：1普通 2银卡 3金卡 4钻石 */
    private Integer memberLevel;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Integer deleted;
}
