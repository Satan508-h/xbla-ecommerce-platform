package com.xbla.rag.service;

/**
 * 这条引用<b>确实存在</b>（它在 {@code qa_log.references} 里），
 * 但 {@code kb_chunk} 里已经取不到它的正文了。
 *
 * <h3>★ 它和 {@link ChatReferenceNotFoundException} 是两件事，所以是两个类</h3>
 *
 * <pre>
 *   ChatReferenceNotFoundException  这次回答【没有引用过】这一片 —— 调用方拼错了 id
 *   （本类）                         引用过，但【正文没了】       —— 数据没了
 * </pre>
 *
 * <p>对前端来说两者都是 404、都只显示一句 message。但它们的成因完全不同，
 * 而排查时你只有那句 message。<b>把两种成因渲染成同一句话，就是让读到的人
 * 从零开始猜</b>（同 {@code docs/10} 坑 20：错误分支只能报告它核实过的东西）。
 *
 * <h3>★★ 正常情况下它【不可达】—— 而正是这一点决定了它必须存在</h3>
 *
 * <p>{@code KbChunkMapper.selectContentsByIds} <b>刻意不按 {@code deleted} 过滤</b>
 * （理由写在那个方法的注释里：软删的切片仍存在于那一轮评测的历史里）。
 * 所以这条路上查不到行，只可能是<b>物理删除</b> —— 而全仓没有任何地方对
 * {@code kb_chunk} 做物理删除。
 *
 * <p>★ 不处理它的后果不是 404，是 <b>500</b>：{@code List.get(0)} 抛
 * {@code IndexOutOfBoundsException} 被兜底 handler 接走。
 * <b>「一个不可达的分支」不该以「一个看起来像服务坏了的 500」的形式到达。</b>
 */
public class KbChunkNotFoundException extends ResourceNotFoundException {

    public KbChunkNotFoundException(Long chunkId) {
        super("切片正文", String.valueOf(chunkId));
    }
}
