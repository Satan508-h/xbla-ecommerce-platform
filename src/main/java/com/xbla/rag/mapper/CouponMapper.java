package com.xbla.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xbla.rag.entity.Coupon;

/**
 * 优惠券模板表 Mapper
 *
 * <p>对应数据库表 {@code coupon}。
 *
 * <p>继承 {@code BaseMapper<Coupon>} 后自带 insert / selectById / updateById /
 * deleteById / selectList / selectCount 等方法，无需手写 SQL。
 * 复杂查询（阶段 4 的向量检索、阶段 5 的 MCP 工具 SQL）再往这里加方法，
 * 简单的用注解 SQL，复杂的写 XML 放 resources/mapper/ 下。
 */
public interface CouponMapper extends BaseMapper<Coupon> {
}
