package com.xbla.rag.rag.profile;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一位用户的<b>历史购买事实</b>（阶段 9.5）—— 从订单表实时派生的，
 * <b>不是</b>一张画像表里的一行。
 *
 * <h2>★★ 一、为什么叫「事实」而不是「偏好」</h2>
 *
 * <p>名字是这一个类里最要紧的决定。它手上有的东西只是：
 *
 * <pre>
 *   这位用户买过什么（类目、品牌、成交价、几笔）
 *   —— 仅此而已
 * </pre>
 *
 * <p>而「他喜欢什么」是一个<b>推断</b>，需要两样这里没有的东西：
 * 足够的样本，以及一个「买过 ⇒ 还想要」的假设。
 *
 * <p>★ 实测样本有多小（种子库 30 人 / 120 单）：
 *
 * <pre>
 *   订单数      每人 1 ~ 7 笔（中位数 3.5）
 *   类目数      每人 1 ~ 4 个（目录里一共 6 个）
 *   品牌数      每人 1 ~ 7 个，而且【重复率极低】——
 *               用户 15 买的 5 件是 戴尔/Bose/苹果/美的/西门子，一个都不重
 *   成交价      每人 226 ~ 16702 元，跨度可达 30 倍
 * </pre>
 *
 * <p>所以本对象承载的是「<b>确有其事的几行事实</b>」，而渲染层
 * （{@code RagPromptBuilder.affinitySection}）必须把它们写成事实的样子，
 * 并且在正文里说破样本很小。★ 说成「你偏好 XX」是<b>一句听起来像事实的胡说</b>，
 * 而模型会原样转述给用户 —— 同 {@code PolicyTerm.exchangeDays} 那条
 * 「不要在这一层把 null 兜成默认值」的纪律。
 *
 * <h2>★★ 二、为什么从订单实时派生，而不是建一张画像表</h2>
 *
 * <p>画像表的收益是「查得快」，代价是引入一个<b>必须被同步的状态</b>：
 * 订单落了库但画像没更新时，系统就会拿一份过期的偏好去回答 ——
 * 而且看不出来（画像表里的那一行长得很正常）。于是要有补偿任务、
 * 要有「最后一次同步时间」、要有对账。
 *
 * <p>本项目的量级（30 人 / 120 单）让这一切都成了纯负债：
 * 一条带索引的聚合查询是<b>毫秒级</b>的。同 {@code PolicyFactProvider}
 * 「不缓存」的那段论证 —— <b>当缓存的收益小到测不出来时，它带来的状态就是纯负债。</b>
 *
 * <p>★ 这条路什么时候会不成立：订单上百万之后。那时这条查询会变慢，
 * 而<b>正确的下一步是加物化视图或增量画像表，不是加一层缓存</b> ——
 * 缓存解决不了「一致的快照」这个问题，物化视图才解决。
 *
 * <h2>三、字段的粒度与空值纪律</h2>
 *
 * <ul>
 *   <li>{@link #orderCount()} 是<b>订单</b>数（去重后的订单）；
 *       {@link #categories()} / {@link #brands()} 里的 count 是<b>商品件</b>数。
 *       实测种子库里每单恰好一件，所以两者相等 —— 但<b>这不是契约</b>，
 *       一单多件时它们会分开</li>
 *   <li>价格三项是<b>成交单价</b>（{@code order_item.price}），不是订单金额 ——
 *       后者被优惠券和数量搅过，不适合当「他一般买多少钱的东西」的证据</li>
 *   <li>★ <b>可能为 null 的字段一律保持 null，绝不兜成 0</b>：
 *       没有成交记录时三个价格是 null，{@code memberLevel} 拿不到时也是 null。
 *       兜底会把「未记录」渲染成一个具体的数，而模型分不出它和真值的区别</li>
 * </ul>
 *
 * @param orderCount  有效订单数（{@code status ∈ {20,30,40}} 且落在窗口内）。
 *                    ★ <b>它是「这一份证据有多大」的唯一度量</b> ——
 *                    渲染层要把它写出来，模型才知道该把这几行看多重
 * @param itemCount   这些订单里的<b>商品件数</b>。
 *                    ★ 它和 {@code orderCount} 并列写进 prompt 是刻意的：
 *                    类目/品牌的计数是<b>件级</b>的，两个数都写出来，
 *                    那几行「A×3、B×2」的口径才是自洽的（三行加起来等于件数）
 *                    —— 只写订单数的话，那些计数看起来对不上，而
 *                    「数字对不上」正是读的人开始怀疑整段数据的起点
 * @param windowDays  这次统计的时间窗（天）。写进来是为了让渲染出来的那段话
 *                    能自报口径（「近 180 天内」），而不是让读的人去翻配置
 * @param categories  <b>全部</b>买过的类目，按 计数降序 + 名称升序。
 *                    ⚠️ 不截断 —— 截断是渲染层的事（它才知道 prompt 有多长），
 *                    而数据层截断会让「一共几个类目」这个数字失真
 * @param brands      同上。<b>不含 brand 为空/空白的商品</b>（{@code product.brand} 可空），
 *                    所以它通常比 categories 短
 * @param priceMin    成交单价最小值；<b>没有成交记录时为 null</b>
 * @param priceMax    成交单价最大值；同上
 * @param priceAvg    成交单价平均值（已按 0 位小数 HALF_UP 定标，见 Provider）；
 *                    同上
 * @param memberLevel 会员等级 1~4（普通/银卡/金卡/钻石）；
 *                    <b>查不到这个用户时为 null</b>。
 *                    ★ 它<b>不参与</b>推荐打分 —— 它是<b>用户级常数</b>，
 *                    加进商品得分不改任何排序（详见 {@code RecommendProductsTool}）
 */
public record UserAffinity(long orderCount,
                           int itemCount,
                           int windowDays,
                           List<Count> categories,
                           List<Count> brands,
                           BigDecimal priceMin,
                           BigDecimal priceMax,
                           BigDecimal priceAvg,
                           Integer memberLevel) {

    /**
     * 「这次没有偏好可带」。
     *
     * <p>★ 用一个常量而不是到处 {@code new UserAffinity(0, ...)}：同
     * {@code StructuredFacts.EMPTY} ——<b>「没有」是一个要能一眼认出来的状态</b>。
     *
     * <p>⚠️ 它同时覆盖四种「没有」（匿名 / 用户不存在 / 订单不足 / 开关关掉）。
     * 那是刻意的：<b>这四种对下游是同一件事</b>（这一段不出现），
     * 而它们的区别属于日志，不属于数据 —— 见 {@code UserAffinityProvider}。
     */
    public static final UserAffinity EMPTY =
            new UserAffinity(0, 0, 0, List.of(), List.of(), null, null, null, null);

    public UserAffinity {
        categories = List.copyOf(categories);
        brands = List.copyOf(brands);
    }

    /** ★ 判据是订单数，不是 categories 是否为空 —— 后者只是它的一个侧面 */
    public boolean isEmpty() {
        return orderCount <= 0;
    }

    /**
     * 一个「名字 → 件数」的计数项。
     *
     * <p>类目和品牌共用它。★ <b>名字保证非空</b>（空品牌在 Provider 里就被滤掉了）——
     * 空名字渲染出来是一行「：×3」，那种东西不该被造出来。
     */
    public record Count(String name, int count) {
    }
}
