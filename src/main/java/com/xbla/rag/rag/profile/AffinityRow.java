package com.xbla.rag.rag.profile;

import java.math.BigDecimal;

/**
 * 派生偏好时的<b>一行订单明细</b> —— 阶段 9.5。
 *
 * <p>它存在的理由和 {@code ChunkContent} 一样：这是「一次查询的投影」，
 * 不是某张表的实体。用 {@code OrderItem} 实体接的话，会顺手把
 * {@code subtotal} / {@code spec_name} / {@code created_at} 一起拉出来 ——
 * 而它们在这里一个都用不上。
 *
 * <p>★ <b>聚合放在 Java 里做，不放在 SQL 里。</b>SQL 只能给出「按类目分组计数」
 * 或「价格五数概括」中的<b>一个</b>结果集，而这里需要同时拿到三样
 * （类目计数、品牌计数、价格三项）。三次查询换来的是三个可能互相不一致的快照
 * —— 中间落进一笔新订单时，类目是旧的、价格是新的，而它们看起来都正常。
 * 一次查询 + 内存聚合没有这个缝隙，代价是几毫秒。
 *
 * @param orderId  订单 id。★ <b>去重就靠它</b> —— 一个订单有 N 行明细，
 *                 而「几笔订单」和「几件商品」是两个数
 * @param price    该行的成交<b>单价</b>（不是 {@code subtotal}：后者被数量和优惠搅过）
 * @param category 商品类目（{@code product.category}，非空）
 * @param brand    商品品牌。<b>可空</b> —— {@code product.brand} 上没有非空约束，
 *                 Provider 会把空品牌的行滤出品牌统计（但不滤出价格统计）
 */
public record AffinityRow(long orderId, BigDecimal price, String category, String brand) {
}
