package com.xbla.rag.rag.eval;

/**
 * 一行「某道评测题的正解切片，落在哪个 doc_type」。
 *
 * <p>这是<b>意图树验收</b>的原料，不是给人读的结果：一道题的正解可能有 3 条切片、
 * 横跨 2~3 种 doc_type，所以数据库返回的是<b>多行</b>
 * （一道题 × 一个 doc_type = 一行），由调用方聚合成集合再比对。
 *
 * <p><b>为什么不直接在 SQL 里 {@code array_agg}</b>：PostgreSQL 的
 * {@code smallint[]} 映射回 Java {@code List<Integer>} 需要额外配 TypeHandler，
 * 而这里的数据量是「几十道题 × 最多 5 种类型」——
 * 把聚合放在 Java 里做，省掉一个 TypeHandler，可读性还更好。
 * （同一个取舍的另一个例子：{@code SearchTextStats} 是唯一返回裸统计结构的查询，
 * 因为它压根不映射任何一张表的完整行。）
 *
 * @param questionNo 题目编号，如 {@code B-011}
 * @param intent     该题人工标注的意图叶子码；未标注时是 {@code UNCLASSIFIED} 哨兵值
 * @param docType    该题某一条正解切片所属的 {@code kb_chunk.doc_type}，取值 1~5
 */
public record QuestionGoldDocType(String questionNo, String intent, int docType) {
}
