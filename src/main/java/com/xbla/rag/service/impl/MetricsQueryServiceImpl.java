package com.xbla.rag.service.impl;

import com.xbla.rag.dto.LabeledCount;
import com.xbla.rag.dto.MetricsSnapshot;
import com.xbla.rag.dto.UserEventRequest;
import com.xbla.rag.mapper.MetricsMapper;
import com.xbla.rag.service.MetricsQueryService;
import com.xbla.rag.service.MetricsWindow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link MetricsQueryService} 的实现。
 *
 * <p>★ 这个类里<b>没有任何写操作</b>，也不调模型 —— 几条聚合 SQL，完了。
 *
 * <p>★★ 这个类里唯一需要动脑的地方是<b>「固定键集」</b>与<b>「分母为 0」</b>，
 * 两件事都是同一个纪律的变体：<b>不许让「没有数据」长得像「数据是 0」</b>。
 */
@Slf4j
@Service
public class MetricsQueryServiceImpl implements MetricsQueryService {

    /** {@code qa_log.status} 的四个合法取值。★ 见 {@code QaLog} 上的常量 */
    private static final List<String> STATUS_KEYS = List.of("1", "2", "3", "4");

    /**
     * 当前支持的两种事件。
     *
     * <p>★★ <b>引用同一份白名单，不另抄一遍</b> —— 抄一遍的漂移是静默的：
     * 加了一个新事件类型而这里没跟着加，症状是
     * 「那个事件永远不出现」，而它长得像「还没有人触发过」。
     */
    private static final List<String> EVENT_TYPE_KEYS = UserEventRequest.SUPPORTED_TYPES;

    private final MetricsMapper metricsMapper;

    public MetricsQueryServiceImpl(MetricsMapper metricsMapper) {
        this.metricsMapper = metricsMapper;
    }

    @Override
    public MetricsSnapshot snapshot(String requestedWindow) {
        MetricsWindow requested = MetricsWindow.parseOrNull(requestedWindow);
        MetricsWindow effective = requested != null ? requested : MetricsWindow.defaultWindow();

        // ★ ALL 没有下界 ⇒ from = null ⇒ SQL 里那一半恒真
        OffsetDateTime from = effective.span() == null
                ? null
                : OffsetDateTime.now().minus(effective.span());

        return new MetricsSnapshot(
                effective.code(),
                // ★ 原样回显调用方传的东西（可能是 null、可能是垃圾）——
                //   它和 window 不同就说明「被替换过」，见 buildNotes
                requestedWindow,
                from,
                OffsetDateTime.now(),
                buildTraffic(from),
                metricsMapper.latencyStats(from),
                buildBehavior(from),
                buildNotes(requestedWindow, effective));
    }

    // ============================================================
    // 组① 流量与健康度
    // ============================================================

    private MetricsSnapshot.Traffic buildTraffic(OffsetDateTime from) {
        return new MetricsSnapshot.Traffic(
                metricsMapper.countQuestions(from),
                metricsMapper.countSessions(from),
                fixedKeysWithCounts(STATUS_KEYS, metricsMapper.statusCounts(from)));
    }

    // ============================================================
    // 组② 用户行为
    // ============================================================

    /**
     * 组装行为那一组。
     *
     * <h3>★★ 率的分母为 0 时给 {@code null}，不给 0</h3>
     *
     * <p>「窗口内一条带引用的回答都没有」时，{@code 0/0} 的正确表达是
     * <b>「不知道」</b>，不是「一个都没被点」。写 0 是一次断言，
     * 而那一刻我们没有任何证据 —— 没有东西可点。
     *
     * <p>★ 这也是为什么分母（{@code citedReplies}）必须<b>原样发出去</b>：
     * 只发一个 {@code null} 的话，读的人分不清
     * 「分母是 0」和「这个功能还没做」。
     */
    private MetricsSnapshot.Behavior buildBehavior(OffsetDateTime from) {
        Map<String, Long> byType = fixedKeysWithCounts(
                EVENT_TYPE_KEYS, metricsMapper.eventTypeCounts(from));

        long up = 0;
        long down = 0;
        long other = 0;
        for (LabeledCount c : metricsMapper.voteCounts(from)) {
            String label = c.label();
            if ("up".equals(label)) {
                up += c.count();
            } else if ("down".equals(label)) {
                down += c.count();
            } else {
                // ★ label 为 null（payload 里没有 vote）或是个没见过的值。
                //   不并进 up/down —— 那会把「读不出来」混进「用户不满意」
                other += c.count();
            }
        }

        long cited = metricsMapper.countCitedReplies(from);
        long clicked = metricsMapper.countClickedCitedReplies(from);

        return new MetricsSnapshot.Behavior(
                byType,
                byType.getOrDefault("ref_click", 0L),
                up,
                down,
                other,
                cited,
                clicked,
                cited == 0 ? null : (double) clicked / cited,
                metricsMapper.clockSkewP50Ms(from));
    }

    // ============================================================
    // 固定键集
    // ============================================================

    /**
     * 先把该出现的键<b>全部置 0</b>，再用查出来的数覆盖。
     *
     * <h3>★★★ 为什么不能直接拿 {@code GROUP BY} 的结果当 map</h3>
     *
     * <p>因为 {@code GROUP BY} <b>不会给没出现的值产生一行</b>。
     * 于是「这一格没被走到」在响应里就<b>整个键消失</b>，
     * 而读的人看到的是「没有这一项」—— 一个<b>看起来像好消息的空</b>。
     *
     * <p>★ 这条是从阶段 9.6a 学来的（{@code d2386cd}：gate 六分支全填 0 再计数）。
     * 那一次的实测教训是：三个 0 的含义各不相同
     * （{@code PLAN_OFF} = 没被测过、{@code NONE_DISABLED} = 开关关着、
     * {@code NO_CLASSIFY} = 分类没失败过），而<b>缺键会把它们压成一个</b>。
     *
     * <h3>★ 但那不意味着「只留固定键」</h3>
     *
     * <p>查出来一个<b>不在固定集合里</b>的标签时，<b>追加</b>而不是丢弃 ——
     * 那说明取值超纲了（比如 {@code status = 5}），而它<b>必须被看见</b>。
     * 丢弃它是这个类里最坏的一种「让数据好看」。
     *
     * <p>⚠️ 用 {@link LinkedHashMap}：这把键序钉死（固定键在前、超纲键在后），
     * 于是同一个库两次调用的 JSON <b>逐字节相同</b> —— 前端 diff 和截图对比才有意义。
     *
     * <h3>★ 为什么它是 package-private 而不是 private</h3>
     *
     * <p><b>为了能被直接测。</b>通过 HTTP 断言「值为 0 的键也在」是<b>可能恒真</b>的 ——
     * 只有在「那个窗口里恰好缺这一个键」时才失效，而那是<b>数据依赖</b>的，
     * 今天红明天绿。
     *
     * <p>而它是个<b>纯函数</b>：输入一张「固定键 + 查询结果」的表，
     * 输出一张 map。直接喂给它一组<b>我控制得了</b>的输入，
     * 「缺的那个键补成 0」才是真的被断言了
     * （{@code MetricsQueryServiceImplTest}）。
     */
    static Map<String, Long> fixedKeysWithCounts(List<String> keys,
                                                 List<LabeledCount> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String k : keys) {
            out.put(k, 0L);
        }
        for (LabeledCount row : rows) {
            // ★ NULL 标签也不能丢 —— 它会以 "null" 为键出现在超纲位置
            out.merge(String.valueOf(row.label()), row.count(), Long::sum);
        }
        return out;
    }

    // ============================================================
    // 注意事项
    // ============================================================

    /**
     * 跟着数字一起发出去的注意事项。
     *
     * <h3>★ 为什么口径要写在响应里，而不是只写在文档里</h3>
     *
     * <p>因为这个端点的响应会被<b>贴进别的地方</b>（截图、聊天记录、报告）。
     * 分开写的话，数字先被引用、口径要等有人想起来才被翻出来 ——
     * 而那时它已经脱离上下文了。同 {@code docs/11} 那份报告的做法：
     * <b>解读句是数据的函数，不是另写一段散文。</b>
     */
    private static List<String> buildNotes(String requestedWindow, MetricsWindow effective) {
        List<String> notes = new ArrayList<>();

        // ★ 只有「传了但传错了」才提示。没传（null/空）是正常的取缺省行为，
        //   为它刷一句 note 会让这一栏在正常调用下也占位
        if (requestedWindow != null && !requestedWindow.isBlank()
                && MetricsWindow.parseOrNull(requestedWindow) == null) {
            notes.add(String.format(
                    "window=%s 不是合法取值（合法：%s），已按 %s 返回。requestedWindow 里是原值。",
                    requestedWindow, MetricsWindow.supported(), effective.code()));
        }

        notes.add("延迟五段【不相加】等于 total —— rerank ⊂ retrieval 是包含关系，"
                + "而意图分类那一次模型往返（0.5~2.5 秒）进了 total 却不在任何一段里（ADR-083）。"
                + "别把五行加起来对 total。");

        notes.add("★ latency 里每一段都带自己的 n —— percentile_cont 跳过 NULL，"
                + "不带 n 的话「只排过 4 次队」会被读成「排队很快」。"
                + "queue 段的 n 尤其常为 0（限流没开就没有排队）。");

        notes.add("★★ behavior 那一组只有【埋点上线之后】的数据（2026-09-26 起）。"
                + "在那之前是零，而那是「还没有埋点」，不是「没有人点」。"
                + "同理，本端点里没有「转化」——本项目没有可点的商品，"
                + "referenceClickRate 量的是「想看原文」，不是下单。");

        return notes;
    }
}
