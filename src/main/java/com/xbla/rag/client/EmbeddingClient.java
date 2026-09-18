package com.xbla.rag.client;

import com.xbla.rag.client.dto.EmbeddingResult;

import java.util.List;

/**
 * 向量化客户端契约。
 *
 * <p><b>★ 只有硅基流动提供向量化能力，DeepSeek 官方没有</b>——
 * 这是本项目采用双供应商架构的根本原因，也是为什么这里<b>没有</b>降级链：
 * 只有一家可用，没有可降级的对象。
 *
 * <p>它和 {@link LlmClient} 的另一个区别是<b>不做熔断</b>。
 * 对话是一次性的（失败就重试或降级），而向量化是批量任务
 * （阶段 3 要灌几万条切片），中途熔断会让整个批次失败 ——
 * 合理的做法是「按批重试 + 断点续传」，那是阶段 3 要解决的问题。
 *
 * @see OpenAiCompatibleEmbeddingClient
 */
public interface EmbeddingClient {

    /**
     * 批量向量化。
     *
     * <p>批量而不是逐条，是因为网络往返开销远大于模型计算开销 ——
     * 一次发 32 条和一次发 1 条，耗时差不多。
     *
     * @param texts 待向量化的文本列表
     * @return 向量结果，顺序与输入一一对应
     * @throws ModelCallException 调用失败
     */
    EmbeddingResult embed(List<String> texts);

    /**
     * 单条向量化的便捷方法。
     *
     * <p>注意它<b>不走</b>批量接口的特例逻辑，只是包一层 ——
     * 免得为「一条」和「多条」维护两套代码路径。
     */
    default EmbeddingResult embed(String text) {
        return embed(List.of(text));
    }
}
