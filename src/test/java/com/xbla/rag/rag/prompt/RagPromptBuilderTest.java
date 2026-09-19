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
}
