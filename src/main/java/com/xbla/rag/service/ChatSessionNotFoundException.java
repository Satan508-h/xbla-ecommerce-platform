package com.xbla.rag.service;

/**
 * 按 {@code sessionNo} 找会话，但没找到。
 *
 * <p>转 HTTP 404 的机制（为什么必须显式注册 handler、为什么不能返回空列表）
 * 全部写在父类 {@link ResourceNotFoundException} 上 —— 这里不再重复一遍，
 * <b>因为「两处各写一份说明」和「两处各写一份代码」一样会漂移。</b>
 *
 * <p>它自己只回答一个问题：<b>是什么东西没找到</b>。
 */
public class ChatSessionNotFoundException extends ResourceNotFoundException {

    public ChatSessionNotFoundException(String sessionNo) {
        super("会话", sessionNo);
    }

    /** 找不到的那个会话号 */
    public String getSessionNo() {
        return getId();
    }
}
