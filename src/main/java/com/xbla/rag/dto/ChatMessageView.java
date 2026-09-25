package com.xbla.rag.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 一条历史消息 —— {@code GET /api/chat/sessions/{sessionNo}/messages} 的元素。
 *
 * <h3>★ {@code role} 为什么是字符串而不是 1/2/3</h3>
 *
 * <p>库里 {@code chat_message.role} 是 {@code SMALLINT}：
 * {@code 1} 用户、{@code 2} 助手、{@code 3} 系统（见 V5 迁移的 COMMENT）。
 *
 * <p>透出去的时候翻成 {@code "user"} / {@code "assistant"} / {@code "system"}，
 * 理由是<b>映射应该待在「定义」的那一侧</b>：
 * <ul>
 *   <li>翻在服务端 —— 语义变了一次，只有一处</li>
 *   <li>翻在前端 —— 每个消费方都要硬编码一份 {@code 1/2/3} 的表，
 *       而且<b>漏掉一个分支不会有任何报错</b>，只会渲染出一个空白气泡</li>
 * </ul>
 *
 * <p>★ 这与「{@code qa_log.status} 的 1/2/3/4 写在文档里就够」并不矛盾：
 * 那个是<b>统计口径</b>，读它的是评测脚本；这个是<b>渲染输入</b>，读它的是界面。
 *
 * @param id               消息主键。<b>这个字段给出去是有意的</b> ——
 *                         它是前端排序与「新消息插进来了」的稳定依据
 *                         （{@code createdAt} 在同一个事务里可能同毫秒）
 * @param role             见上：{@code user} / {@code assistant} / {@code system}
 * @param content          消息正文。<b>用户消息是原始提问，助手消息是最终回答原文</b>
 * @param intent           识别出的意图码，<b>仅助手消息有</b>。为 null 有三种含义，
 *                         和 {@code ChatAskResponse.intent} 一样要分清：
 *                         没开意图识别 / 分类失败 / 这条不是助手消息
 * @param intentConfidence 意图置信度 0~1。低于阈值会触发澄清反问
 * @param references       ★ <b>已解析成 JSON 的引用列表</b>，见下面的说明
 * @param provider         实际生效的供应商。<b>降级后可能不是 P0</b> —— 这是阶段 2 验收标准的直接证据
 * @param model            实际使用的模型 ID
 * @param latencyMs        这一次回答的端到端耗时（毫秒）
 * @param createdAt        写入时间
 *
 * <h3>★★ {@code references} 为什么是 {@link JsonNode} 而不是 {@code String}</h3>
 *
 * <p>库里 {@code references} 是 <b>JSONB</b> 列，而实体类里映射成了 {@code String}
 * （{@code ChatMessage.references}）—— 那是<b>写入方向</b>的选择，见
 * {@code ChatServiceImpl.serializeReferences}。
 *
 * <p>读取方向如果<b>原样把那个字符串透出去</b>，前端拿到的会是
 * <b>「一个装着 JSON 的字符串」</b>：
 *
 * <pre>
 *   ✗ {"references": "[{\"no\":1,\"chunk_id\":15}]"}   ← 字符串，前端还得再 parse 一次
 *   ✓ {"references": [{"no":1,"chunk_id":15}]}         ← 真 JSON
 * </pre>
 *
 * <p>★ 这个错误<b>在对象 dump 里看着是对的</b>（有 references、里面也确实有 no 和 chunk_id），
 * 只有在真去取 {@code references[0].no} 的时候才会炸。所以这里显式用
 * {@code readTree} 解析回来，让它只能是对的那一种。
 */
public record ChatMessageView(
        Long id,
        String role,
        String content,
        String intent,
        BigDecimal intentConfidence,
        JsonNode references,
        String provider,
        String model,
        Integer latencyMs,
        OffsetDateTime createdAt
) {
}
