package com.xbla.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/chat} 的请求体 —— <b>对外接口的入参</b>。
 *
 * <p>注意它和 {@code client.draft.ChatRequest} 是两个不同的东西：
 * <ul>
 *   <li>本类在顶层 {@code dto/} 包，定义<b>对外 HTTP 接口长什么样</b>，
 *       可以随便改字段名、加校验注解</li>
 *   <li>{@code client.dto.ChatRequest} 在 {@code client/} 包，
 *       定义<b>模型调用需要什么</b>，受供应商协议影响</li>
 * </ul>
 * 分开的好处：接口参数怎么演进都不会波及模型调用层。
 * 如果直接用 {@code client} 的类当接口入参，某天为了适配新供应商加个字段，
 * 就会意外变成「对外接口也多了个字段」。
 *
 * @param sessionNo    会话编号。为 null 时自动创建新会话（阶段 5 做会话记忆时会用到）
 * @param question     用户提问
 * @param systemPrompt 系统提示词。为 null 时用服务端的默认值
 */
public record ChatAskRequest(

        String sessionNo,

        @NotBlank(message = "问题不能为空")
        @Size(max = 2000, message = "问题长度不能超过 2000 字")
        String question,

        String systemPrompt

) {
}
