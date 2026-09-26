package com.xbla.rag.dto;

/**
 * 一条引用的<b>原文</b> —— 供阶段 8 前端的「点开引用看原文」用。
 *
 * <h3>★ 它回答的是「你说的这句话出处到底是哪一段」</h3>
 *
 * <p>{@code references} 里只有 {@code no / chunk_id / document_id / score /
 * heading_path} —— 那是<b>指针</b>，不是内容。用户看到引用列表时最自然的下一步
 * 就是「点开看看原文」，而这个接口就是那一步。
 *
 * <h3>★★ 四个字段来自【两个】地方，这是刻意的</h3>
 *
 * <pre>
 *   headingPath / documentId  ←  qa_log.references 里那一条【原样】
 *   content                   ←  kb_chunk 现读
 * </pre>
 *
 * <p>为什么标题不一起从 {@code kb_chunk} 读：那样同一条引用会在
 * <b>列表里和弹窗里显示两个不同的标题</b>（切片被重新切分、文档被改名之后
 * 它们会分叉），而「同一条数据在两处显示不一致」是最容易被读成 bug 的现象。
 * {@code references} 是<b>这次回答当时引用了什么</b>的权威记录，
 * 所以标题以它为准。
 *
 * <p>而正文<b>只能</b>现读 —— {@code references} 里根本没有正文
 * （同 {@code retrieval_detail} 的约定：正文在 {@code kb_chunk} 里，按 id 取回，
 * 见 {@code KbChunkMapper.selectContentsByIds}）。
 *
 * <p>★ 正文<b>不截断</b>：截断规则属于 {@code RagPromptBuilder}
 * （它决定模型看到什么），在这里再写一遍就是第二个事实来源。
 * 用户点开原文就是想看原文。
 *
 * @param chunkId     {@code kb_chunk.id}
 * @param documentId  切片所属文档（取自 {@code references}，不是现查）
 * @param headingPath 切片在文档里的标题路径；★ 可能为 {@code null}
 *                    —— 库里那一列本身就可空，null 的诚实表达是 null，
 *                    不是「(无标题路径)」那种界面文案
 * @param content     切片正文（未截断）
 */
public record ReferenceDetail(
        Long chunkId,
        Long documentId,
        String headingPath,
        String content
) {
}
