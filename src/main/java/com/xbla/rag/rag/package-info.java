/**
 * ★ RAG 核心链路。
 *
 * <p>包含四大块：
 * <ul>
 *   <li>解析——用 Apache Tika 解析 PDF / Word / MD / Excel</li>
 *   <li>切分——标题层级 + 定长 + 重叠窗口</li>
 *   <li>召回——VectorRetriever（pgvector 余弦相似度）+ KeywordRetriever（全文检索）</li>
 *   <li>融合重排——RrfFuser（RRF 融合）+ 重排序模型</li>
 * </ul>
 *
 * <p><b>硬性约定：</b>整条链路必须手写、可逐行解释，不使用 Spring AI / LangChain4j 编排。
 * 理由见 docs/08-技术决策记录(ADR).md。
 *
 * <p>本包在阶段 3（文档入库）和阶段 4（检索链路）填充。
 */
package com.xbla.rag.rag;
