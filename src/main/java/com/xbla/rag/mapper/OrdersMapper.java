package com.xbla.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xbla.rag.entity.Orders;
import com.xbla.rag.rag.profile.AffinityRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单主表 Mapper
 *
 * <p>对应数据库表 {@code orders}。
 *
 * <p>继承 {@code BaseMapper<Orders>} 后自带 insert / selectById / updateById /
 * deleteById / selectList / selectCount 等方法，无需手写 SQL。
 * 复杂查询（阶段 4 的向量检索、阶段 5 的 MCP 工具 SQL）再往这里加方法，
 * 简单的用注解 SQL，复杂的写 XML 放 resources/mapper/ 下。
 */
public interface OrdersMapper extends BaseMapper<Orders> {

    /**
     * 阶段 9.5：取一位用户【最近 N 笔有效订单】的明细行，供派生偏好用。
     *
     * <h3>★★ 口径一：哪些订单算「偏好证据」</h3>
     *
     * <pre>
     *   status IN (20 待发货, 30 已发货, 40 已完成)     ← 只有这三个
     * </pre>
     *
     * <p>被排除的两个，理由<b>不对称</b>：
     *
     * <ul>
     *   <li><b>10 待付款</b> —— 还没花钱，是一份「打算」而不是一次购买。
     *       实测 8 笔</li>
     *   <li><b>50 已取消</b> —— ★ 实测这 10 笔<b>连 {@code paid_at} 都有</b>：
     *       它们是「付过又退掉」。而退款恰恰是<b>最强的一条反向证据</b>
     *       （他试过、不要了），把它算成偏好是把结论反着读。
     *       ⚠️ 光看 {@code paid_at IS NOT NULL} 会把它一起收进来 ——
     *       本查询不用那个判据，用的就是 status</li>
     * </ul>
     *
     * <p>★ 这三个状态合计 102/120 笔（实测）。剩下的 18 笔不是「数据缺失」，
     * 是「本来就不该算」。
     *
     * <h3>★★ 口径二：时间窗锚在 {@code created_at}</h3>
     *
     * <p>{@code orders} 上有四个时间列。选 {@code created_at} 的理由是：
     * 它是<b>唯一 NOT NULL</b> 的那个，而且有 {@code idx_orders_created_at}。
     * 对这三个状态来说，下单和付款相隔几分钟，用哪个都不改变结论。
     *
     * <p>⚠️ 窗口不是「越多越好」的调优参数：偏好块会进<b>每一轮</b>的
     * prompt，而时间窗决定了「两年前买过的东西还算不算数」。
     * 它由 {@code xbla.agent.profile.window-days} 控制，默认 180。
     *
     * <h3>★★ 口径三：条数上限截的是【订单】不是【行】</h3>
     *
     * <p>所以先在内层子查询里按时间取最近 {@code maxOrders} 笔订单，
     * 再在外层展开明细 —— 直接 {@code LIMIT} 外层的话，一个十件商品的订单
     * 会一口气吃掉全部额度，而「最近 200 单」缩水成「最近 20 单」。
     *
     * <p>★ {@code ORDER BY created_at DESC, id DESC} 里的第二个键不能省：
     * <b>PostgreSQL 的 {@code now()} 是事务开始时间</b>，同一批种子数据的时间戳
     * 可能完全相同，只按时间排序时顺序是未定义的（{@code docs/10} 坑 23）。
     *
     * <h3>★ 不按 {@code product.deleted / status} 过滤</h3>
     *
     * <p>他确实买过那件商品。<b>商品后来下架不改变这条事实</b> ——
     * 把下架商品的行滤掉，会让「买过 5 件」缩水成「买过 3 件」，
     * 而那个数字会被写进 prompt 当作样本大小的依据。
     *
     * @param userId     用户 id。<b>非空</b>（调用方负责先判掉匿名）
     * @param windowDays 时间窗（天）
     * @param maxOrders  最多取几笔订单
     */
    @org.apache.ibatis.annotations.Select("""
            SELECT o.id AS order_id, oi.price AS price, p.category AS category, p.brand AS brand
            FROM (
                SELECT id, created_at
                FROM orders
                WHERE user_id = #{userId}
                  AND deleted = 0
                  AND status IN (20, 30, 40)
                  AND created_at >= now() - CAST(#{windowDays} AS int) * INTERVAL '1 day'
                ORDER BY created_at DESC, id DESC
                LIMIT #{maxOrders}
            ) o
            JOIN order_item oi ON oi.order_id = o.id
            JOIN product p ON p.id = oi.product_id
            """)
    List<AffinityRow> selectAffinityRows(@Param("userId") Long userId,
                                         @Param("windowDays") int windowDays,
                                         @Param("maxOrders") int maxOrders);
}
