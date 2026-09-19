package com.xbla.rag.rag.facts;

import com.xbla.rag.entity.AfterSalePolicy;
import com.xbla.rag.service.AfterSalePolicyService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 {@code after_sale_policy} 表读出退换货天数。
 *
 * <h2>一、它解决的问题，一句话</h2>
 *
 * <p>知识库里那句「自签收之日起 7 天内支持无理由退货，<b>15 天内支持换货</b>」
 * 是<b>散文</b>。模型要在两个数字、两个动作之间做映射，而实测里这类映射
 * 出错的方式非常隐蔽 —— 它会把「换货 15 天」答成「退货 15 天」，
 * <b>句子读起来完全通顺</b>，用户不会怀疑。
 *
 * <p>表里的 {@code return_days = 7} / {@code exchange_days = 15} 则是无歧义的。
 * 这个类把它搬到 prompt 里。
 *
 * <h2>★★ 二、不缓存，是有意的</h2>
 *
 * <p>这张表只有十几行、几乎不变，看起来最适合缓存。但不缓存：
 *
 * <ul>
 *   <li><b>它只在售后的一小部分问答上被调用</b>（判据是意图树的
 *       {@code structured_facts: POLICY}，目前只有一个叶子有），
 *       不是每次问答都跑</li>
 *   <li>一条带索引的十几行 SELECT 是<b>微秒级</b>的，
 *       而缓存引入的是「改了政策表但没生效，且没有任何日志」——
 *       和 {@code IntentTree} 必须做 mtime 校验是同一类问题，
 *       只不过那里代价明确（每次识别都读文件），这里代价可以忽略</li>
 * </ul>
 *
 * <p>★ 一句话：<b>当缓存的收益小到测不出来时，它带来的状态就是纯负债。</b>
 *
 * <h2>三、查不到数据是正常的，不是错误</h2>
 *
 * <p>过滤条件是「生效中 + 未删除 + 有天数」。一条都没有时返回
 * {@link StructuredFacts#EMPTY} 而不是抛异常 —— 政策表被清空、
 * 或者所有政策都还没填天数，都是可能发生的状态，
 * 而它们的正确处理方式一样：<b>这一段不出现，检索那部分照常工作。</b>
 */
@Component
public class PolicyFactProvider {

    private final AfterSalePolicyService afterSalePolicyService;

    public PolicyFactProvider(AfterSalePolicyService afterSalePolicyService) {
        this.afterSalePolicyService = afterSalePolicyService;
    }

    /**
     * 读出生效中的政策条款。
     *
     * <p>★ 过滤 {@code return_days IS NOT NULL} 而不是「全部读出来再判断」：
     * 政策表里有一半的记录是<b>没有时限的通用规则</b>
     * （价格保护、发票、优惠券返还、以旧换新、物流损坏），
     * 它们的天数列是空的。<b>把空天数的记录也带进 prompt 是纯粹的噪音</b> ——
     * 模型看到一行只有类目、没有数字的记录，只会分心。
     */
    public StructuredFacts load() {
        List<AfterSalePolicy> policies = afterSalePolicyService.lambdaQuery()
                .eq(AfterSalePolicy::getStatus, 1)
                .eq(AfterSalePolicy::getDeleted, 0)
                .isNotNull(AfterSalePolicy::getReturnDays)
                .orderByAsc(AfterSalePolicy::getId)
                .list();

        if (policies.isEmpty()) {
            return StructuredFacts.EMPTY;
        }

        List<StructuredFacts.PolicyTerm> terms = new ArrayList<>(policies.size());
        for (AfterSalePolicy policy : policies) {
            // ★ 只挑天数。conditions / content 一个字都不带 —— 见类注释
            // ★ exchangeDays 原样传 null 上去，不兜底成 0 —— 理由见 PolicyTerm 的注释
            terms.add(new StructuredFacts.PolicyTerm(
                    policy.getCategory(),
                    policy.getPolicyNo(),
                    policy.getReturnDays(),
                    policy.getExchangeDays()));
        }
        return new StructuredFacts(terms);
    }
}
