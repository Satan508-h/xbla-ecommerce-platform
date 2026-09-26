package com.xbla.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.dto.UserEventRequest;
import com.xbla.rag.entity.UserEvent;
import com.xbla.rag.mapper.UserEventMapper;
import com.xbla.rag.service.UserEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * {@link UserEventService} 的实现 —— <b>一个校验器 + 一次插入</b>。
 *
 * <h3>★★★ 校验的每一条都对应一个「不处理就会静默出错」的失败模式</h3>
 *
 * <pre>
 *   类型不在白名单   → 库里的 event_type 变成自由文本，
 *                      而在线指标按固定键集分组 ⇒ 那些行【永远不出现】
 *   缺幂等键         → ON CONFLICT 失去意义，重复计数
 *   缺 occurred_at   → 那一列会变成 NULL，而窗口口径按它切（对客户端视角而言）
 *   字段超长         → PG 抛 value too long ⇒ 兜底 handler ⇒ 500
 *   payload 过大     → 截断会造出非法 JSON ⇒ PG 拒 ⇒ 500
 * </pre>
 *
 * <p>★ 这五条的共同点是：<b>它们都不会在开发时暴露</b>（本地发的都是正常数据），
 * 而它们的症状全都长得像「用户没点」。
 *
 * <h3>★★ 丢弃记 DEBUG，不记 WARN</h3>
 *
 * <p>因为<b>正常的客户端也会偶尔被丢</b>：一个旧版前端 + 一个新版后端
 * 就会产生「类型不认识」。记 WARN 会让日志里堆满噪声，
 * 于是真正的告警被淹没 —— 同 {@code Toolbox} 空态那条教训。
 * DEBUG 级别在需要排查时能打开，平时不吵。
 */
@Slf4j
@Service
public class UserEventServiceImpl implements UserEventService {

    private final UserEventMapper userEventMapper;
    private final ObjectMapper objectMapper;

    public UserEventServiceImpl(UserEventMapper userEventMapper, ObjectMapper objectMapper) {
        this.userEventMapper = userEventMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Outcome record(UserEventRequest req) {
        // ★ req 为 null 是合法输入（请求体是空的）—— 不当异常抛
        if (req == null) {
            return discard("请求体为空");
        }

        // ① 类型白名单。★ 第一道，因为它决定这一行会不会被任何统计看见
        if (req.eventType() == null || !UserEventRequest.SUPPORTED_TYPES.contains(req.eventType())) {
            return discard("eventType 不在白名单：" + req.eventType());
        }

        // ② 幂等键。没有它就没法防重，而重复计数是最难发现的指标错误
        if (isBlank(req.eventNo())) {
            return discard("eventNo 缺失 —— 没有幂等键，这条不能收");
        }

        // ③ 发生时间。★ 服务端【不】代填 now()：这一列的全部意义是
        //    「客户端报的」，代填就等于毁掉它和 received_at 的区别（V18 第四节）
        if (req.occurredAt() == null) {
            return discard("occurredAt 缺失 —— 服务端不代填，那会毁掉它和 received_at 的区别");
        }

        // ④ 标识列超长。★ 丢弃，不截断（截断可能撞上另一条记录）
        if (tooLong(req.eventNo()) || tooLong(req.sessionNo()) || tooLong(req.traceId())) {
            return discard("标识列超过 " + UserEventRequest.MAX_ID_LENGTH + " 字符");
        }

        // ⑤ payload。★★ 这里必须把【「没有差异部分」】和【「序列化失败」】分开 ——
        //    两者的返回值都是 null，而处置完全相反：
        //
        //      没有 payload  → 合法，照常写（那一列本来就可空）
        //      序列化失败    → 丢弃（我们不知道要写什么进去）
        //
        //  ⚠️ 混起来的后果实测过一次：把「没有 payload」也当成失败 ⇒
        //     `track('ref_click', {traceId})` 那种「只带骨、不带差异」的事件
        //     会被【整条丢掉】，而症状是「那个事件一条都没有」，
        //     看起来像「还没有人点」。由 optionalFieldsMayBeAbsent 抓住。
        String payloadJson = null;
        if (req.payload() != null && !req.payload().isEmpty()) {
            payloadJson = serializePayload(req.payload());
            if (payloadJson == null) {
                return discard("payload 序列化失败");
            }
            if (payloadJson.getBytes(StandardCharsets.UTF_8).length
                    > UserEventRequest.MAX_PAYLOAD_BYTES) {
                return discard("payload 超过 " + UserEventRequest.MAX_PAYLOAD_BYTES + " 字节");
            }
        }

        UserEvent row = new UserEvent();
        row.setEventNo(req.eventNo());
        row.setEventType(req.eventType());
        row.setUserId(req.userId());
        row.setSessionNo(req.sessionNo());
        row.setTraceId(req.traceId());
        row.setPayload(payloadJson);
        row.setOccurredAt(req.occurredAt());
        // ★ receivedAt 刻意不设：由数据库的 DEFAULT now() 填（UserEvent 的类注释）

        int affected = userEventMapper.insertIgnoreDuplicate(row);
        if (affected == 0) {
            // ★ 0 = ON CONFLICT DO NOTHING 命中。不是错误，是这个键已经在了。
            //   计一条 DEBUG 而不是 INFO —— 浏览器重试时会正常走到这里
            log.debug("埋点幂等命中，未重复写入：eventNo={} type={}",
                    req.eventNo(), req.eventType());
            return Outcome.DUPLICATE;
        }
        return Outcome.WRITTEN;
    }

    // ============================================================
    // 小工具
    // ============================================================

    /**
     * 把 {@code payload} 序列化成 JSONB 列要的那段文本。
     *
     * <p>★ 形状由前端决定，前端那边已经是插入序（见 {@code track.js}）。
     * 这里只负责<b>序列化一次</b>，不再重排 —— 重排会让同一个事件的字节
     * 在两次调用间不同，而那样任何「对两次上报做 diff」的排查都失效。
     *
     * <p>⚠️ <b>调用方必须先判空</b>：本方法对 {@code null} / 空 map 返回
     * {@code null}，而那个 {@code null} 的意思是「<b>没有差异部分</b>」，
     * 不是「失败」—— 两种 null 长得一样，所以判空的责任在调用方
     * （见 {@code record()} ⑤ 那段注释，那里为这件事栽过一次）。
     */
    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // ★ 记 warn 而不是 error：它是一条埋点，不影响任何主链路
            log.warn("埋点 payload 序列化失败：{}", e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean tooLong(String s) {
        return s != null && s.length() > UserEventRequest.MAX_ID_LENGTH;
    }

    private Outcome discard(String why) {
        log.debug("埋点被丢弃：{}", why);
        return Outcome.DISCARDED;
    }
}
