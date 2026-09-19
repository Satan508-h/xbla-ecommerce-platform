package com.xbla.rag.rag.prompt;

import com.xbla.rag.rag.retrieve.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG prompt 组装的单元测试。纯计算，不起 Spring。
 *
 * <p>重点验证两件容易写错、而且错了<b>不会报错只会变贵/变差</b>的事：
 * 拼接顺序（影响上下文缓存命中率）和空上下文的处理方式。
 */
@DisplayName("RagPromptBuilder · 检索上下文注入")
class RagPromptBuilderTest {

    private final RagPromptBuilder builder = new RagPromptBuilder();

    private static final String BASE = "你是电商客服助手。";

    private static RetrievedChunk chunk(long id, String headingPath, String content) {
        return new RetrievedChunk(id, 1L, (int) id, content, headingPath, 0.9);
    }

    // ============================================================
    // 一、★ 拼接顺序（省钱的那一条）
    // ============================================================

    @Nested
    @DisplayName("一、★ 拼接顺序：固定指令在前，可变上下文在后")
    class Ordering {

        @Test
        @DisplayName("★ 基础指令必须出现在检索资料【之前】")
        void basePromptComesFirst() {
            String prompt = builder.build(BASE, List.of(
                    chunk(1, "售后FAQ", "退货在三个工作日内退款")));

            int baseIndex = prompt.indexOf(BASE);
            int materialsIndex = prompt.indexOf("【知识库资料】");

            assertThat(baseIndex).as("基础指令要在").isGreaterThanOrEqualTo(0);
            assertThat(materialsIndex).as("资料块要在").isGreaterThanOrEqualTo(0);
            assertThat(baseIndex)
                    .as("★ DeepSeek 的上下文缓存是【前缀匹配】的，"
                            + "命中价 0.02 元/百万 token、未命中 1.0 元 —— 差 50 倍。"
                            + "检索结果拼在前面的话，整个 prompt 从第 1 个 token 起"
                            + "就没有任何一次能命中缓存")
                    .isLessThan(materialsIndex);
        }

        @Test
        @DisplayName("★ 对照：同样的输入反过来拼，前缀就不再是稳定的")
        void reversedOrderWouldBreakPrefixCache() {
            // 对照组：模拟「检索结果拼在前面」的错误做法
            String wrong = "【知识库资料】\n退货在三个工作日内退款\n\n" + BASE;
            String right = builder.build(BASE, List.of(
                    chunk(1, "售后FAQ", "退货在三个工作日内退款")));

            // 两次不同检索的「错误做法」输出，前 20 个字符就不同；
            // 而「正确做法」的前 20 个字符是恒定的基础指令
            String wrongOther = "【知识库资料】\n发货时效是 48 小时\n\n" + BASE;

            assertThat(wrong.substring(0, 20))
                    .as("对照组：错误顺序下，不同检索的开头不同 → 缓存前缀不成立")
                    .isNotEqualTo(wrongOther.substring(0, 20));
            assertThat(right.substring(0, 20))
                    .as("★ 正确顺序下，不同检索的开头完全相同 → 前缀可以命中缓存")
                    .isEqualTo(builder.build(BASE, List.of(
                            chunk(2, "别的", "完全不同的内容"))).substring(0, 20));
        }
    }

    // ============================================================
    // 二、★ 空上下文
    // ============================================================

    @Nested
    @DisplayName("二、★ 空上下文")
    class EmptyContext {

        @Test
        @DisplayName("★ 没有切片时不出现空标题，而是一句明确的说明")
        void noEmptyHeader() {
            String prompt = builder.build(BASE, List.of());

            assertThat(prompt)
                    .as("★ 写「以下是相关资料：（空）」比不写更糟 —— "
                            + "它把模型的注意力引向「资料在这里」这个结构，而那里什么都没有")
                    .doesNotContain("【知识库资料】");
            assertThat(prompt)
                    .as("替代方案是一句明确的说明，让模型如实告知而不是硬编")
                    .contains("未从平台知识库中检索到相关内容");
            assertThat(prompt)
                    .as("基础指令仍然要在")
                    .contains(BASE);
        }

        @Test
        @DisplayName("null 与空列表行为一致")
        void nullTreatedAsEmpty() {
            String fromNull = builder.build(BASE, null);
            assertThat(fromNull).doesNotContain("【知识库资料】");
            assertThat(fromNull).isEqualTo(builder.build(BASE, List.of()));
        }

        @Test
        @DisplayName("基础 prompt 为 null 也不抛异常")
        void nullBasePromptIsSafe() {
            assertThat(builder.build(null, List.of())).contains("未从平台知识库中检索到相关内容");
            assertThat(builder.build(null, List.of(chunk(1, null, "内容")))).contains("内容");
        }
    }

    // ============================================================
    // 三、资料块的格式
    // ============================================================

    @Nested
    @DisplayName("三、资料块的格式")
    class Materials {

        @Test
        @DisplayName("每条切片带 [编号]，从 1 开始递增")
        void numberedCitations() {
            String prompt = builder.build(BASE, List.of(
                    chunk(11, "A", "第一段"),
                    chunk(22, "B", "第二段"),
                    chunk(33, "C", "第三段")));

            assertThat(prompt).contains("[1]").contains("[2]").contains("[3]");
            assertThat(prompt.indexOf("[1]"))
                    .as("编号顺序要和相关度顺序一致 —— 它们是 RRF + 重排排出来的")
                    .isLessThan(prompt.indexOf("[2]"));
            assertThat(prompt.indexOf("[2]")).isLessThan(prompt.indexOf("[3]"));
        }

        @Test
        @DisplayName("标题路径被拼进资料块 —— 它给模型提供范畴信息")
        void headingPathIncluded() {
            String prompt = builder.build(BASE, List.of(
                    chunk(1, "第二章 七天无理由退货 > 2.3 退款时限与方式", "商品退回并验收合格后")));

            assertThat(prompt)
                    .as("「2.3 退款时限与方式」比正文本身更能说明这段话说的是什么范畴的事")
                    .contains("第二章 七天无理由退货 > 2.3 退款时限与方式");
        }

        @Test
        @DisplayName("标题路径为 null 时不产生空行，编号后直接跟正文")
        void nullHeadingPathIsSkipped() {
            String prompt = builder.build(BASE, List.of(chunk(1, null, "只有正文")));

            assertThat(prompt)
                    .as("★ 标题为 null 时编号后【直接】跟正文，不产生一个空行占位")
                    .contains("[1] 只有正文")
                    .doesNotContain("null")
                    .doesNotContain("[1] \n");
        }

        @Test
        @DisplayName("★ 对照：有标题路径时编号后跟路径再换行")
        void headingPathChangesLayout() {
            String prompt = builder.build(BASE, List.of(chunk(1, "某文档 > 某节", "正文内容")));

            assertThat(prompt)
                    .as("有路径时是「[1] 路径\\n正文」，和上一条的「[1] 正文」不同 —— "
                            + "这条对照证明上一条的 doesNotContain 断言真的有区分力")
                    .contains("[1] 某文档 > 某节\n正文内容");
        }

        @Test
        @DisplayName("超长切片被截断，prompt 不会被一段话撑爆")
        void overlongChunkIsTruncated() {
            String huge = "很长的内容".repeat(1000);       // 5000 字
            String prompt = builder.build(BASE, List.of(chunk(1, null, huge)));

            assertThat(prompt.length())
                    .as("防御性上限：切分器的 max-chars 是 500，正常情况永远轮不到，"
                            + "但配置改错或有人绕过切分器时，这里保证 prompt 不会爆")
                    .isLessThan(3000);
            assertThat(prompt).contains("已截断");
        }

        @Test
        @DisplayName("回答要求出现在资料之后")
        void requirementsComeLast() {
            String prompt = builder.build(BASE, List.of(chunk(1, null, "内容")));

            assertThat(prompt.indexOf("回答要求"))
                    .as("要求是固定的，放在最后不影响前缀缓存；"
                            + "放在资料前反而会把可变内容挤到中间")
                    .isGreaterThan(prompt.indexOf("内容"));
        }
    }

    // ============================================================
    // 四、★ 会话历史约束（阶段 5.5）
    // ============================================================

    @Nested
    @DisplayName("四、★ 会话历史约束")
    class HistoryCaveat {

        @Test
        @DisplayName("★ 有历史时才出现，没有历史时一个字都不该有")
        void onlyWhenThereIsHistory() {
            String withHistory = builder.build(BASE, List.of(chunk(1, null, "内容")), true);
            String withoutHistory = builder.build(BASE, List.of(chunk(1, null, "内容")), false);

            assertThat(withHistory)
                    .as("回放给模型的只有问答文本，当时的检索资料不在里面 —— "
                            + "所以必须明确「事实以本次资料为准」")
                    .contains("关于对话历史")
                    .contains("以【本次】检索到的资料为准");
            assertThat(withoutHistory)
                    .as("★ 一轮问答配一句「历史仅供参考」是纯噪音，"
                            + "还会稀释其他指令的权重。这条是【反证】—— "
                            + "只断言 withHistory 含约束的话，"
                            + "一个「永远加」的实现也能通过")
                    .doesNotContain("关于对话历史");
        }

        @Test
        @DisplayName("★ 约束必须在【可变资料之前】—— 它属于可缓存的固定前缀")
        void caveatStaysInTheCacheablePrefix() {
            String prompt = builder.build(BASE, List.of(chunk(1, null, "这段资料每次都不同")), true);

            assertThat(prompt.indexOf("关于对话历史"))
                    .as("★ 放进可变资料之后就永远缓存不到，而且位置一旦跟着资料走，"
                            + "不同请求的前缀就不再一致 —— 那正是本节要防的事")
                    .isLessThan(prompt.indexOf("【知识库资料】"));
        }

        @Test
        @DisplayName("★ 检索为空但【有历史】时，约束仍然要在")
        void caveatSurvivesEmptyRetrieval() {
            String prompt = builder.build(BASE, List.of(), true);

            assertThat(prompt)
                    .as("★ 这条最容易漏：空检索是走另一条 return 分支的。"
                            + "没有资料恰恰是「历史里的数字最可能被当真」的时候 —— "
                            + "模型手边什么都没有，只有上文")
                    .contains("关于对话历史")
                    .contains("本次未从平台知识库中检索到相关内容");
        }

        @Test
        @DisplayName("2 参数重载等价于 hasHistory=false")
        void twoArgOverloadMeansNoHistory() {
            List<RetrievedChunk> chunks = List.of(chunk(1, null, "内容"));

            assertThat(builder.build(BASE, chunks))
                    .as("★ 保留 2 参数重载是为了让不关心历史的调用方少写一个 false；"
                            + "它必须等价于 false，否则两个入口的行为会悄悄分叉")
                    .isEqualTo(builder.build(BASE, chunks, false));
        }
    }

    // ============================================================
    // 五、★ 更早对话的摘要（阶段 5.6）
    // ============================================================

    @Nested
    @DisplayName("五、★ 更早对话的摘要")
    class SessionSummary {

        private static final String SUMMARY = "用户预算两千左右，用途是和孙子视频通话";

        @Test
        @DisplayName("★ 有摘要时出现，且排在【可变资料之前】")
        void summarySitsBeforeTheMaterials() {
            String prompt = builder.build(BASE,
                    List.of(chunk(1, null, "这段资料每次都不同")), false, SUMMARY);

            assertThat(prompt)
                    .contains("【更早的对话摘要】")
                    .contains(SUMMARY);
            assertThat(prompt.indexOf("【更早的对话摘要】"))
                    .as("★ 摘要是【半固定】的 —— 它由 SessionSummarizer 在窗口溢出后生成，"
                            + "之后一直不变，直到游标再次推进。所以它比资料稳定得多，"
                            + "排在资料之前能让这段缓存前缀在大多数轮次里命中。"
                            + "放到资料之后就等于它每轮都是新的，一点都缓存不到")
                    .isLessThan(prompt.indexOf("【知识库资料】"));
        }

        @Test
        @DisplayName("★ 摘要在历史约束【之后】—— 规则在前，内容在后")
        void caveatComesBeforeTheSummary() {
            String prompt = builder.build(BASE, List.of(chunk(1, null, "资料")), false, SUMMARY);

            assertThat(prompt.indexOf("关于对话历史"))
                    .as("★ 两条都是「关于历史」的，但分工不同：约束是【规则】"
                            + "（固定文本，可缓存），摘要是【内容】（半固定）。"
                            + "规则在前，模型读到内容时已经知道该怎么对待它")
                    .isLessThan(prompt.indexOf("【更早的对话摘要】"));
        }

        @Test
        @DisplayName("★★ 只给摘要、不给历史原文时，历史约束也必须出现")
        void summaryAloneStillTriggersTheCaveat() {
            String prompt = builder.build(BASE, List.of(chunk(1, null, "资料")), false, SUMMARY);

            assertThat(prompt)
                    .as("★ 摘要【也是历史】，而且是【有损、最容易被模型当成精确事实】的那种。"
                            + "如果这里只判 hasHistory，那么「窗口空了但摘要还在」的那一轮 ——"
                            + "恰恰是模型手边只有压缩信息的一轮 —— 会缺掉这条约束")
                    .contains("关于对话历史");
        }

        @Test
        @DisplayName("★ 对照：没有摘要时一个字都不该有")
        void noSummaryMeansNoSection() {
            assertThat(builder.build(BASE, List.of(chunk(1, null, "资料")), true, null))
                    .as("★ 和 hasHistory 同理：给一轮没有摘要的问答加一个空的摘要标题，"
                            + "会把模型的注意力引向一个空的位置")
                    .doesNotContain("更早的对话摘要");

            assertThat(builder.build(BASE, List.of(chunk(1, null, "资料")), true, "   "))
                    .as("★ 全空白等于没有 —— 判断要看内容，不能只看 null")
                    .doesNotContain("更早的对话摘要");
        }

        @Test
        @DisplayName("★ 3 参数重载等价于「没有摘要」")
        void threeArgOverloadMeansNoSummary() {
            List<RetrievedChunk> chunks = List.of(chunk(1, null, "内容"));

            assertThat(builder.build(BASE, chunks, true))
                    .as("★ 同 2 参数重载的理由：入口的行为必须一致")
                    .isEqualTo(builder.build(BASE, chunks, true, null));
        }

        @Test
        @DisplayName("★ 检索为空但有摘要时，摘要和约束都要在")
        void summarySurvivesEmptyRetrieval() {
            String prompt = builder.build(BASE, List.of(), false, SUMMARY);

            assertThat(prompt)
                    .contains("关于对话历史")
                    .contains(SUMMARY)
                    .contains("本次未从平台知识库中检索到相关内容");
        }

        @Test
        @DisplayName("★ 摘要后面跟一句「有损」的说明")
        void summaryCarriesTheLossyNote() {
            String prompt = builder.build(BASE, List.of(chunk(1, null, "资料")), false, SUMMARY);

            assertThat(prompt)
                    .as("★ 摘要是压缩的，而模型读到「用户说过 X」时会当成精确事实。"
                            + "这句让它需要引用具体数值时倾向于以本轮资料为准")
                    .contains("只保留要点，细节可能不全");
        }
    }
}
