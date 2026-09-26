package com.xbla.rag.rag.profile;

import com.xbla.rag.config.AgentProperties;
import com.xbla.rag.entity.AppUser;
import com.xbla.rag.mapper.OrdersMapper;
import com.xbla.rag.service.AppUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 从订单表<b>实时派生</b>一位用户的 {@link UserAffinity}（阶段 9.5）。
 *
 * <h2>★ 一、它是 {@code PolicyFactProvider} 的同类，但有一处关键不同</h2>
 *
 * <pre>
 *   PolicyFactProvider.load()        无参 —— 同一份库必产出逐字相同的一段
 *   UserAffinityProvider.load(id)    带参 —— per-user，且【随订单变】
 * </pre>
 *
 * <p>那一处不同正是 {@code qa_log.affinity}（V16）必须存在的理由：
 * 评测<b>无法</b>事后重建「模型当时看到的那段偏好」，所以只能在当时把它快照下来。
 *
 * <h2>★★ 二、「给不给这一块」的<b>全部</b>判断都在这里</h2>
 *
 * <p>四种「不给」都收在这个方法里，调用方只看 {@link UserAffinity#isEmpty()}：
 *
 * <pre>
 *   开关关掉               xbla.agent.profile.enabled = false  → 连查询都不发
 *   匿名                   userId == null                     → 没有「谁」可以个性化
 *   用户不存在             app_user 里查不到                   → 同上（实测有这种请求）
 *   证据不足               有效订单 &lt; min-orders              → 见 Profile#minOrders
 * </pre>
 *
 * <p>★ 收在一处的理由和 {@code RetrievalGate} 那条一样：这三条判断要是散在
 * 调用点上，<b>漏一条的症状不是报错</b>，而是「那条路上的人被个性化错了」。
 *
 * <p>⚠️ <b>这四种对下游是同一件事（这一段不出现），但对日志是四件事</b> ——
 * 所以每一种都单独打一行 DEBUG。「偏好一次都没生效」这件事在数据上
 * 本来完全看不出来，日志是唯一能分辨的地方。
 *
 * <h2>★★ 三、查不到数据不是错误，也绝不抛异常</h2>
 *
 * <p>返回 {@link UserAffinity#EMPTY} 而不是抛 —— 同 {@code PolicyFactProvider}。
 * 但这一条在<b>工具那条路上</b>还多一层：{@code RecommendProductsTool} 会调它，
 * 而工具抛异常会被 {@code ToolLoop} 包成工具结果喂回模型。
 * 那等于「个性化查不动了」升级成「推荐工具答不了」—— 一个可选功能
 * 拖垮了主功能。★ 所以这里连 SQL 异常都要吞掉。
 *
 * <p>⚠️ 本类<b>不</b>自己吞异常 —— 吞在 {@code ChatServiceImpl.affinitySafely}
 * 里，和 {@code structuredFactsSafely} 同形。理由：本类只负责「读」，
 * 「读挂了怎么办」是一次问答的策略，属于服务层。
 * 而工具那条路不经过服务层，它自己包（见那个类的注释）。
 */
@Component
public class UserAffinityProvider {

    private static final Logger log = LoggerFactory.getLogger(UserAffinityProvider.class);

    /**
     * 类目/品牌的排序规则：<b>计数降序，同数按名称升序</b>。
     *
     * <p>★ 第二个键不能省。同计数的两个类目谁在前面，如果由
     * {@code HashMap} 的迭代顺序决定，那它在<b>每次 JVM 启动时都不一样</b>
     * （JDK 9+ 的 hash 掺了一个随机 SALT，本项目为这件事踩过两次：ADR-058 / 064）。
     * 而这一段进的是<b>每一轮</b>的 prompt 前缀 —— 顺序抖一次，
     * 那一轮的上下文缓存就整段未命中（差 50 倍）。
     *
     * <p>⚠️ <b>不在这一层截断列表</b>：截断是渲染层的事（只有它知道 prompt 有多长），
     * 而数据层截断会让「一共几个类目」这个数字失真 ——
     * 那是渲染层要如实写出来的样本量。
     */
    private static final Comparator<UserAffinity.Count> BY_COUNT_THEN_NAME =
            Comparator.comparingInt(UserAffinity.Count::count).reversed()
                    .thenComparing(UserAffinity.Count::name);

    private final OrdersMapper ordersMapper;
    private final AppUserService appUserService;
    private final AgentProperties properties;

    public UserAffinityProvider(OrdersMapper ordersMapper,
                                AppUserService appUserService,
                                AgentProperties properties) {
        this.ordersMapper = ordersMapper;
        this.appUserService = appUserService;
        this.properties = properties;
    }

    /**
     * 派生一位用户的偏好事实。
     *
     * @param userId 身份。<b>null = 匿名</b>（常态），此时不查库直接返回 EMPTY
     * @return 永远不会是 null；「不该给」与「没有数据」都返回
     *         {@link UserAffinity#EMPTY}（两者的区别只在日志里）
     */
    public UserAffinity load(Long userId) {
        AgentProperties.Profile config = properties.getProfile();

        if (!config.isEnabled()) {
            // ★ 一行日志都不打：关掉是配置状态，每个请求打一行会把日志淹掉
            return UserAffinity.EMPTY;
        }
        if (userId == null) {
            log.debug("偏好：本次请求匿名 —— 不派生");
            return UserAffinity.EMPTY;
        }

        List<AffinityRow> rows = ordersMapper.selectAffinityRows(
                userId, config.getWindowDays(), config.getMaxOrders());
        if (rows == null || rows.isEmpty()) {
            log.debug("偏好：user#{} 在近 {} 天内没有有效订单（status 20/30/40）",
                    userId, config.getWindowDays());
            return UserAffinity.EMPTY;
        }

        // ★ 去重成【订单】数。⚠️ 这一步不能在 SQL 里用 count(*) 代替 ——
        //   那数的是明细行（件数），而「几笔订单」才是样本大小
        long orderCount = rows.stream().map(AffinityRow::orderId).distinct().count();
        if (orderCount < config.getMinOrders()) {
            // ★★ 这是 D6 那条阈值的落点。info 级：它是「个性化有没有生效」的
            //    主要分界线（实测 30 人里 10 人卡在这里），不该埋在 DEBUG 里
            log.info("偏好：user#{} 只有 {} 笔有效订单（阈值 {}）—— 本次不注入偏好块",
                    userId, orderCount, config.getMinOrders());
            return UserAffinity.EMPTY;
        }

        // ── 价格三项：只统计有价格的明细行 ──
        // ★ 定标到 0 位小数（HALF_UP）：这一段是【摘要】，不是账目。
        //   留着两位小数会让「484.00 ~ 15065.00」看着像精确值，
        //   而它其实是一个凭几笔订单算出来的概括
        List<BigDecimal> prices = rows.stream()
                .map(AffinityRow::price)
                .filter(Objects::nonNull)
                .toList();
        BigDecimal priceMin = null;
        BigDecimal priceMax = null;
        BigDecimal priceAvg = null;
        if (!prices.isEmpty()) {
            priceMin = round(prices.stream().min(BigDecimal::compareTo).orElseThrow());
            priceMax = round(prices.stream().max(BigDecimal::compareTo).orElseThrow());
            priceAvg = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(prices.size()), 0, RoundingMode.HALF_UP);
        }

        UserAffinity affinity = new UserAffinity(
                orderCount,
                rows.size(),
                config.getWindowDays(),
                counts(rows, AffinityRow::category),
                counts(rows, AffinityRow::brand),
                priceMin, priceMax, priceAvg,
                memberLevelOf(userId));

        log.debug("偏好：user#{} 命中 {} 笔订单 / {} 个类目 / {} 个品牌",
                userId, affinity.orderCount(),
                affinity.categories().size(), affinity.brands().size());
        return affinity;
    }

    /**
     * 按某个字段计数并排序。
     *
     * <p>★ <b>空名字直接丢掉，不合并成一个「未知」桶</b>：{@code product.brand}
     * 可空，而把几十个空品牌并成一行「未知 ×37」会渲染成
     * 「买过的品牌：未知×37」—— 一句听起来像事实的胡说，
     * 模型会照抄给用户。丢掉的代价只是品牌统计少覆盖几行，
     * 而它在正文里本来就是一个样本。
     */
    private static List<UserAffinity.Count> counts(
            List<AffinityRow> rows, Function<AffinityRow, String> key) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (AffinityRow row : rows) {
            String name = key.apply(row);
            if (name == null || name.isBlank()) {
                continue;
            }
            tally.merge(name.trim(), 1, Integer::sum);
        }
        List<UserAffinity.Count> out = new ArrayList<>(tally.size());
        tally.forEach((name, count) -> out.add(new UserAffinity.Count(name, count)));
        out.sort(BY_COUNT_THEN_NAME);
        return out;
    }

    /**
     * 会员等级。
     *
     * <p>★ 它<b>只在偏好块里出现，不参与推荐打分</b> —— 详见
     * {@code RecommendProductsTool}：它是用户级常数，加进商品得分不改任何排序。
     *
     * <p>⚠️ 查不到就返回 <b>null</b>，不兜成 1（「普通会员」）。
     * 兜底会把「我们不知道」渲染成一个具体的等级，而那是模型分不出来的假话。
     * 实测这条<b>不是</b>死代码：{@code X-Xbla-User-Id: 999999} 这类请求
     * 会让工具和偏好两条路都拿到一个 {@code app_user} 里不存在的 id。
     */
    private Integer memberLevelOf(Long userId) {
        AppUser user = appUserService.getById(userId);
        return user == null ? null : user.getMemberLevel();
    }

    /** 金额摘要统一到 0 位小数 —— 见价格那一节的注释 */
    private static BigDecimal round(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }
}
