package com.xbla.rag.dto;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 浏览器上报的一条行为事件 —— {@code POST /api/events} 的请求体。
 *
 * <h3>★★★ 为什么身份在【请求体】里，而不是 {@code X-Xbla-User-Id} 头</h3>
 *
 * <p>因为 <b>{@code navigator.sendBeacon} 不支持自定义请求头</b>。
 * 它只能发一个 POST + 一段 body（唯一的头是 body 的 Content-Type）。
 *
 * <p>★ 这条限制是硬约束，不是设计偏好 —— 记下来是为了让下一个人
 * 不要「顺手把它改成走 header」：那样改完<b>编译通过、单测通过</b>，
 * 而浏览器发出去的身份会变成 {@code null}，症状是
 * 「user_event.user_id 全是空」——看起来像用户都是匿名的。
 *
 * <p>★★ 而这个改动<b>不引入新的信任问题</b>：那个请求头本来就是
 * 明文未签名的（ADR-054 的注释里写死了这件事），body 和它一样不可信。
 * 本端点里根本不存在「认证过的身份」这个东西，只有一个<b>声明</b>。
 * 也正是因为这样，{@code user_id} 那一列<b>不加外键</b>（见 {@code V18}）。
 *
 * <h3>★ 每个字段缺失时的处置（服务端在 {@code UserEventServiceImpl} 里执行）</h3>
 *
 * <pre>
 *   eventNo    缺失 → 【丢弃整条】。没有幂等键就没法防重，
 *                      而重复计数是最难发现的指标错误
 *   eventType  不在白名单 → 【丢弃整条】。★ 丢弃不是报错，见下
 *   occurredAt 缺失 → 【丢弃整条】。★ 服务端【不】代填 now() ——
 *                      这一列的全部意义是「客户端报的」，
 *                      代填就等于毁掉它和 received_at 的区别（见 V18 第四节）
 *   payload    超 4KB → 【丢弃整条】，不是截断（截断会造出非法 JSONB）
 *   userId / sessionNo / traceId  可缺。它们各自都有「没有」的诚实含义
 * </pre>
 *
 * <h3>★★ 为什么「不认识的类型」是丢弃而不是 400</h3>
 *
 * <p>因为埋点是<b>尽力而为</b>的：它绝不能影响主链路，也绝不该让用户看到错误。
 * 一次 {@code sendBeacon} 打到一个旧版后端（还没认识这个新事件）时，
 * 回 400 只是在日志里制造噪声 —— 浏览器不读那个响应，用户更看不到。
 *
 * <p>★ 所以失败一律静默：<b>能写就写，不能写就丢</b>，两种都回 200。
 * 唯一被区分的是「写进去了」和「因为重复没写」—— 那由响应体告诉调用方。
 *
 * @param eventNo    幂等键，前端用 {@code crypto.randomUUID()} 生成
 * @param eventType  事件类型，必须在服务端白名单里
 * @param userId     浏览器<b>声明</b>的身份。⚠️ 不是认证，可能不存在于 {@code app_user}
 * @param sessionNo  会话号，可空
 * @param traceId    关联 {@code qa_log} 的骨，可空（历史消息那一格必然是空）
 * @param payload    事件差异部分（{@code {chunkId, no}} / {@code {vote}}），可空
 * @param occurredAt 客户端报的发生时间，<b>必填</b>
 */
public record UserEventRequest(
        String eventNo,
        String eventType,
        Long userId,
        String sessionNo,
        String traceId,
        Map<String, Object> payload,
        OffsetDateTime occurredAt
) {

    /**
     * 服务端接受的<b>全部</b>事件类型。★ 白名单，不是黑名单。
     *
     * <p>★★ <b>只能有一处定义</b>（同 {@code McpTool.Field} 那条纪律：
     * 「参数名只能写一次」）。写入侧（校验）和读取侧（在线指标的固定键集）
     * 各写一份的话，漂移是<b>静默</b>的 —— 症状是「新加的事件类型永远不出现」，
     * 而它会看起来像「还没有人触发过」。
     *
     * <p>★ 当前两个，都对应一个<b>真实发生过</b>的用户动作：
     *
     * <pre>
     *   ref_click  点开了某条引用看原文
     *   feedback   对一条回答投了 👍 / 👎
     * </pre>
     *
     * <p>⚠️ 加新的之前先问一句「它的语义钉得死吗」——
     * 会话切换那类动作（切过去可能只是想找回上一句）埋了也难解释。
     */
    public static final java.util.List<String> SUPPORTED_TYPES =
            java.util.List.of("ref_click", "feedback");

    /**
     * {@code sessionNo} / {@code traceId} 这些标识列的上限，对齐库里的
     * {@code VARCHAR(64)}。
     *
     * <p>★★ 超长<b>丢弃整条</b>，不截断 —— 截断会把一个 id 变成
     * <b>另一个合法形状的值</b>，而它可能<b>真的撞上另一条记录</b>。
     * 那比丢掉这一条坏得多：丢掉是「少一个数」，撞上是「一个数算到了别人头上」。
     *
     * <p>⚠️ 不处理的话，PG 会抛 {@code value too long for type character varying(64)}
     * → 兜底 handler → <b>500</b>。一个埋点不该让服务端报 500。
     */
    public static final int MAX_ID_LENGTH = 64;

    /**
     * {@code payload} 序列化后的字节上限。
     *
     * <p>★ 超限<b>丢弃整条</b>，不是截断 —— <b>截断一个 JSON 会造出非法 JSONB</b>，
     * 而 PG 会在写入时直接拒（{@code invalid input syntax for type json}），
     * 于是「体积防护」自己变成了一次 500。
     *
     * <p>当前两个事件的 payload 都只有几十字节，4KB 是三个数量级的余量。
     */
    public static final int MAX_PAYLOAD_BYTES = 4096;
}
