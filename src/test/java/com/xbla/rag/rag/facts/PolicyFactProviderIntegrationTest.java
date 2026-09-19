package com.xbla.rag.rag.facts;

import com.xbla.rag.entity.AfterSalePolicy;
import com.xbla.rag.service.AfterSalePolicyService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 政策硬数据的读取（阶段 5.9）—— 真连 PostgreSQL，只读。
 *
 * <h3>★★ 本类真正要证明的那一件事</h3>
 *
 * <p><b>没有时限的政策不能被带进 prompt。</b>
 *
 * <p>{@code after_sale_policy} 里有一半的记录是<b>没有天数的通用规则</b>
 * （价格保护、发票、优惠券返还、以旧换新、物流损坏）——
 * 它们的 {@code return_days} 是 NULL，因为「保价 15 天」这种数字
 * 写在 {@code content} 正文里，不在结构化字段里。
 *
 * <p>把它们也带进 prompt，模型会看到一行只有类目、没有数字的记录。
 * 那不产生错误答案，只产生<b>分心</b> —— 而分心的代价是
 * 它可能把「价格保护」那行的空白理解成「价格保护没有时限」。
 *
 * <p>所以断言分两条，缺一不可：
 * <pre>
 *   ① 结果里每一条都有非空天数   ← 证明过滤条件在
 *   ② AS-007（价格保护）【不在】结果里 ← 证明 ① 不是「碰巧」
 * </pre>
 * 只写 ① 的话，实现里哪怕不过滤、只是「渲染时跳过空天数」也照样绿 ——
 * 而那两件事对下游是不同的（前者 prompt 更短，后者下游还得再判一次）。
 */
@SpringBootTest
@Transactional
@DisplayName("PolicyFactProvider · 政策硬数据")
class PolicyFactProviderIntegrationTest {

    /** 一条没有天数的通用政策。见类注释 */
    private static final String NO_DAYS_POLICY_NO = "AS-007";

    @Autowired
    private PolicyFactProvider provider;

    @Autowired
    private AfterSalePolicyService afterSalePolicyService;

    @BeforeEach
    void requireSeedData() {
        Assumptions.assumeTrue(afterSalePolicyService.count() > 0,
                "库里没有售后政策，跳过（需要阶段 1 的种子数据）");
    }

    @Test
    @DisplayName("★ 只返回有退货天数的政策 —— 没有时限的通用规则必须被排掉")
    void onlyPoliciesWithDays() {
        StructuredFacts facts = provider.load();

        assertThat(facts.isEmpty()).as("种子数据里有 7 条带天数的政策").isFalse();

        // ① 每一条都有天数
        assertThat(facts.policies())
                .allSatisfy(term -> assertThat(term.returnDays())
                        .as("① 过滤条件失效了")
                        .isPositive());

        // ② ★ 正-反对照：AS-007 价格保护在库里存在，但它没有天数
        assertThat(afterSalePolicyService.lambdaQuery()
                .eq(AfterSalePolicy::getPolicyNo, NO_DAYS_POLICY_NO).exists())
                .as("拿它做对照的前提是它确实在库里")
                .isTrue();
        assertThat(facts.policies())
                .as("★★ 把没有天数的政策带进 prompt，只会让模型分心 —— "
                        + "它可能把空白理解成「这条没有时限」")
                .noneMatch(term -> NO_DAYS_POLICY_NO.equals(term.policyNo()));
    }

    @Test
    @DisplayName("★ 通用政策的 category 是 null，不在这里被改写成「通用」字符串")
    void genericPolicyKeepsNullCategory() {
        StructuredFacts facts = provider.load();

        assertThat(facts.policies())
                .as("★ 数据层保留 null，「通用」是渲染时的事 —— 见 PolicyTerm 的注释")
                .anyMatch(term -> term.category() == null);
        assertThat(facts.policies())
                .as("★ 而具体类目的政策 category 不能被抹成 null")
                .anyMatch(term -> term.category() != null);
    }

    @Test
    @DisplayName("★ 顺序稳定（按 id 升序）—— 两次读出来必须逐条相同")
    void orderIsStable() {
        assertThat(provider.load().policies())
                .as("★ 顺序不稳会让 prompt 前缀每次都变，"
                        + "而这一段是【半固定】的、本该能命中缓存")
                .isEqualTo(provider.load().policies());
    }

    @Test
    @DisplayName("★ 家电的换货天数是 30，不是 15 —— 各品类确实不一样")
    void categoriesDiffer() {
        StructuredFacts facts = provider.load();

        // ★ 这一条挡的是「所有类目读成同一行」这种实现错误 ——
        //   它会让 prompt 看起来正常（数字都是 7/15），但把家电的 30 天弄丢
        assertThat(facts.policies())
                .anyMatch(t -> "家电".equals(t.category()) && t.exchangeDays() == 30);
        assertThat(facts.policies())
                .anyMatch(t -> "手机".equals(t.category()) && t.exchangeDays() == 15);
    }
}
