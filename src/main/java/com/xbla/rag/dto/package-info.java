/**
 * 请求 / 响应对象（Data Transfer Object）。
 *
 * <p>为什么要和 entity 分开？因为「数据库长什么样」和「接口暴露什么字段」是两件事。
 * 比如实体里有成本价字段，但绝不能返回给前端——用 DTO 就能天然隔离。
 *
 * <p>本包从阶段 0 的 /api/health 之后开始逐步填充。
 */
package com.xbla.rag.dto;
