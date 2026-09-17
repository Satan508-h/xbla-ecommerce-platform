/**
 * ★ 模型接入层。
 *
 * <p>三个核心客户端，都是手写 HTTP 调用，不用现成的 SDK：
 * <ul>
 *   <li>LlmClient —— 对话生成（DeepSeek 官方 / 硅基流动，双供应商）</li>
 *   <li>EmbeddingClient —— 向量化（bge-m3，1024 维，只有硅基流动提供）</li>
 *   <li>RerankClient —— 重排序（bge-reranker-v2-m3，只有硅基流动提供）</li>
 * </ul>
 *
 * <p>两家供应商的接口都兼容 OpenAI 协议，所以底层 HTTP 调用逻辑可以复用。
 *
 * <p><b>硬性约定：</b>这是全项目唯一允许出现模型 HTTP 请求的地方。
 *
 * <p>本包在阶段 2（模型接入层 + 最小可用闭环）填充，是第一个里程碑。
 */
package com.xbla.rag.client;
