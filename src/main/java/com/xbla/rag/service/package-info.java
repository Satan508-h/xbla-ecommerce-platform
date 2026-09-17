/**
 * 业务编排层。
 *
 * <p>放这里的类负责「把多个组件串起来完成一件事」——比如一次问答要依次调用
 * 意图识别、检索、重排、LLM 生成。controller 只管收请求，真正的流程编排在这一层。
 *
 * <p><b>硬性约定：</b>所有对 LLM / 向量 / 重排模型的调用，都必须经过 client 包的封装，
 * 不允许在这一层直接写 HTTP 请求。这样熔断、降级、计费、日志才能统一生效。
 *
 * @see com.xbla.rag.client
 */
package com.xbla.rag.service;
