package com.xbla.rag.client.dto;

import java.util.List;

/**
 * 重排序结果 —— 领域对象。
 *
 * <p>结果<b>已按相关度降序</b>，调用方直接按顺序取前 N 条即可。
 *
 * <p>注意它返回的是「原始下标 + 得分」，而不是重新排列后的文档列表。
 * 调用方需要自己用 {@link Item#index()} 回原始候选列表取值 ——
 * 这样设计省掉了把长文档再传一遍的带宽。
 *
 * @param items 排序后的结果列表
 */
public record RerankResult(

        List<Item> items

) {

    /**
     * 单条排序结果。
     *
     * @param index 对应请求里 {@code documents} 列表的下标
     * @param score 相关度得分
     * @param document 原文档内容；本项目请求时不开 {@code return_documents}，
     *                 所以这里通常是 null，靠 index 回查即可
     */
    public record Item(int index, double score, String document) {
    }

    /** 结果条数 */
    public int size() {
        return items == null ? 0 : items.size();
    }

    /**
     * 取前 N 条。
     *
     * <p>重排序的典型用法：向量检索召回 50 条 → 重排 → 只留前 5 条喂给大模型。
     * 阶段 4 会频繁用到这个方法。
     *
     * @param n 需要的条数；超过实际数量时返回全部
     */
    public List<Item> topN(int n) {
        if (items == null) {
            return List.of();
        }
        return items.size() <= n ? items : items.subList(0, n);
    }

    /**
     * 得分最高的那一条，没有则返回 null。
     */
    public Item best() {
        return items == null || items.isEmpty() ? null : items.get(0);
    }
}
