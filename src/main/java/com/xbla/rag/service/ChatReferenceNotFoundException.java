package com.xbla.rag.service;

/**
 * 这次问答的引用里<b>没有</b>这个切片 —— 或者链路记录本身就不在了。
 *
 * <h3>★★ 「这条回答没引用过它」为什么不回一个空对象</h3>
 *
 * <pre>
 *   200 + {content: null}  —— ✗ 最糟：它把「不是这次回答的引用」
 *                             和「是引用，但正文取不到了」渲染成同一个响应。
 *                             这两件事的修法完全相反：
 *                             前者是调用方拼错了 chunkId，后者是数据没了。
 *   404                    —— ✓ 见父类的论证
 * </pre>
 *
 * <h3>★★★ 它同时是【枚举入口的封条】</h3>
 *
 * <p>没有这条校验的话，「按 chunkId 取正文」就是一个<b>知道 id 就能读任意切片</b>
 * 的接口 —— 而知识库里有 1600+ 条切片，id 是连续自增的。
 *
 * <p>加上它之后，能取到的切片<b>必须</b>是「某条真实的 {@code qa_log} 记录
 * 确实引用过的那几片」，而 {@code traceId} 是一次性、不可猜的。
 * <b>这不是把口子关小，是把它关到只剩一条缝：只有已经被回答出去的内容才读得到。</b>
 *
 * <h3>★ 前端该拿它怎么办</h3>
 *
 * <p>如实显示「这次回答的引用里没有这条切片」即可。<b>正常情况下不该发生</b> ——
 * 前端手上的 {@code chunkId} 就是从同一条 {@code references} 里来的。
 * 真发生了说明前端读错了字段（把 {@code no} 当成了 {@code chunk_id} 之类），
 * 而那正是需要被看见的错误，不该被一个空对象盖住。
 */
public class ChatReferenceNotFoundException extends ResourceNotFoundException {

    public ChatReferenceNotFoundException(String traceId, Long chunkId) {
        super("这次回答的引用", "trace=" + traceId + ", chunk=" + chunkId);
    }
}
