package com.xbla.rag.service;

import com.xbla.rag.dto.ChatMessageView;
import com.xbla.rag.dto.ChatSessionSummary;

import java.util.List;

/**
 * 会话历史的<b>查询侧</b> —— 供阶段 8 前端的「历史会话列表 + 切换」使用。
 *
 * <h3>★★ 为什么类名里有 {@code Query}：因为这个项目里「历史」已经是一个被占用的词</h3>
 *
 * <p>{@code ChatHistoryIntegrationTest} 早就存在了，而它测的<b>不是这个东西</b> ——
 * 它测的是 {@code ConversationMemory} 的滑动窗口：<b>把最近几轮问答喂给模型</b>。
 *
 * <pre>
 *                         读什么                      什么时候读        给谁看
 *   ───────────────────────────────────────────────────────────────────────────
 *   ConversationMemory    当前会话的最后 N 条        每一轮问答时      模型
 *   （本类）              会话列表 + 某个会话的全部   用户点了才读      人
 * </pre>
 *
 * <p>★ 两者都是「历史」，但一个是<b>运行时的上下文</b>、一个是<b>事后的回看</b>，
 * 生命周期、数据量、调用时机全都不一样。如果这个类也叫
 * {@code ChatHistoryService}，那么「往这个接口里加一个『读最近 N 条』的方法」
 * 看上去会非常合理 —— 而那正是把两个概念合并的开始。
 *
 * <p>所以名字里那个 {@code Query} 是<b>刻意</b>的：它说的是
 * 「这是查询侧、是只读的、是给人看的」。同 CQRS 把读写分开的理由。
 *
 * <h3>★ 为什么单独一个 service，而不是往 {@code ChatSessionService} 里加方法</h3>
 *
 * <p>因为 {@code ChatSessionService} 是<b>实体服务</b>：它的方法都围绕
 * 「{@code ChatSession} 这个表的增删改查」。而这里两个方法的返回值
 * <b>都不是实体</b> —— 一个是裁剪过的 {@code ChatSessionSummary}，
 * 一个是跨了另一张表的 {@code ChatMessageView}。
 * 本项目已经有 {@code KbIngestService} 这种「不绑实体、按用途命名」的先例。
 *
 * <h3>★★ 它和写路径的 {@code ChatServiceImpl} 是两个方向</h3>
 *
 * <p>写路径（问答）<b>不读这里</b>，读路径<b>不写任何东西</b>。
 * 这条边界是刻意的：{@code docs/04} 写明 {@code chat_message} 表
 * 「只增不改不删」，所以这个 service 里不该出现任何 insert/update。
 */
public interface ChatHistoryQueryService {

    /**
     * 会话列表，最近活跃的在前。
     *
     * <p>★ <b>已排除评测流量</b> —— 判据与理由见
     * {@code ChatSessionMapper.selectRealSessions}（4022/4111 那一段）。
     *
     * @param limit 最多返回多少条。非法值会被夹到
     *              [{@code DEFAULT_LIMIT}, {@code MAX_LIMIT}]
     */
    List<ChatSessionSummary> listRealSessions(int limit);

    /**
     * 某个会话的全部消息，按写入顺序。
     *
     * <h3>★ 这里【不】排除评测会话 —— 和上面那个方法是【故意】不一致的</h3>
     *
     * <p>「排除评测流量」是<b>列表这个读模型的属性，不是会话本身的属性</b>：
     * <ul>
     *   <li>列表是<b>浏览</b> —— 不筛的话 97.8% 的位置被评测占掉，人看不到自己的东西</li>
     *   <li>这个方法拿的是<b>一个精确的 sessionNo</b> —— 不是浏览。
     *       按精确键查一个确实存在的行却回 404，会让调用方以为数据丢了</li>
     * </ul>
     *
     * <p>★ 换句话说：<b>同一个「评测流量」概念，在浏览路径上要过滤，
     * 在精确查找路径上要如实返回</b>。这和 {@code qa_log.status ≠ 1}
     * 那套的区别是：那个过滤的是<b>统计口径</b>（算指标时不能算进去），
     * 这里是<b>展示口径</b>（列出来给人看时不能占版面）。
     * 两者都会「看起来像是同一件事」，但管的东西不同。
     *
     * @throws ChatSessionNotFoundException 这个 sessionNo 在库里不存在
     */
    List<ChatMessageView> listMessages(String sessionNo);

    /** 列表的默认条数 —— 也兼作「传了非法值时的兜底」 */
    int DEFAULT_LIMIT = 30;

    /** 列表的上限。★ 存在的原因见 {@code ChatSessionMapper.selectRealSessions} 的 {@code @param limit} */
    int MAX_LIMIT = 200;
}
