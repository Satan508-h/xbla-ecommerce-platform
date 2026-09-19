package com.xbla.rag.rag.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RetrievalOptions} 的纯单测 —— 不连库、不花钱、毫秒级。
 *
 * <h3>本类真正要钉死的是「集合 → SQL 字面量」这条路</h3>
 *
 * <p>{@link RetrievalOptions#docTypesLiteral()} 的产物会被<b>直接拼进 SQL</b>，
 * 而它唯一的安全保证就是「只可能含数字、逗号和花括号」。
 * 所以这里的用例全部围绕这一点：越界的值进不来、{@code null} 进不来、
 * 顺序是确定的。
 *
 * <p>按项目惯例，每一条正向断言都配一条<b>对照</b> ——
 * 只断言「过滤后没问题」的测试是恒真的，因为把输入全部丢掉也能通过。
 */
@DisplayName("RetrievalOptions —— 范围声明与 SQL 字面量")
class RetrievalOptionsTest {

    // ============================================================
    // 一、字面量格式
    // ============================================================

    @Nested
    @DisplayName("一、字面量格式")
    class Literal {

        @Test
        @DisplayName("正常集合转成 PostgreSQL 的 int[] 字面量")
        void normal() {
            assertThat(new RetrievalOptions(20, List.of(2, 4)).docTypesLiteral())
                    .isEqualTo("{2,4}");
        }

        @Test
        @DisplayName("★ 单个元素也是合法字面量，不是裸数字")
        void singleElementKeepsBraces() {
            assertThat(new RetrievalOptions(20, List.of(3)).docTypesLiteral())
                    .as("★ 这是本类最容易写错的一处：返回 \"3\" 而不是 \"{3}\" 的话，"
                            + "CAST('3' AS int[]) 会报错；而如果谁为了「兼容」"
                            + "在 SQL 里写 CASE 去区分两种形状，就等于把格式约定"
                            + "散到了两个地方 —— 迟早有一处漏改")
                    .isEqualTo("{3}");
        }

        @Test
        @DisplayName("★ 顺序被规范化成升序 —— 两次运行的 JSON 才能直接 diff")
        void orderIsNormalized() {
            assertThat(new RetrievalOptions(20, List.of(4, 2)).docTypesLiteral())
                    .as("传进去是 [4,2]，出来必须是 {2,4}")
                    .isEqualTo("{2,4}");
            assertThat(new RetrievalOptions(20, List.of(2, 4)).docTypesLiteral())
                    .as("★ 对照：两种传法的字面量必须逐字节相同，否则"
                            + "「这次和上次的筛选条件一样吗」就得靠眼睛比对")
                    .isEqualTo(new RetrievalOptions(20, List.of(4, 2)).docTypesLiteral());
        }
    }

    // ============================================================
    // 二、★ 清洗：只允许 1~5
    // ============================================================

    @Nested
    @DisplayName("二、★ 清洗 —— 越界的值在构造期就被丢掉")
    class Sanitizing {

        @Test
        @DisplayName("★ 越界的 doc_type 被剔除，不留进字面量")
        void outOfRangeIsDropped() {
            assertThat(new RetrievalOptions(20, List.of(1, 22, 99, -3, 0, 5)).docTypesLiteral())
                    .as("doc_type 的词表是 1~5（和 kb_document 的 CHECK 约束一致）。"
                            + "★ 一个拼错的类型（比如把「售后」的 2 写成 22）"
                            + "在 SQL 里不会报错，只会变成「过滤掉一切」——"
                            + "而那种失败在检索结果上完全看不出原因")
                    .isEqualTo("{1,5}");
        }

        @Test
        @DisplayName("★ 对照组：全部越界 → 空集 → 退化成「不限制」，而不是「匹配不到」")
        void allOutOfRangeBecomesUnfiltered() {
            RetrievalOptions options = new RetrievalOptions(20, List.of(22, 99));

            assertThat(options.docTypesLiteral())
                    .as("★ 关键：清洗掉之后是【空集】，而空集的语义是「不限制」。"
                            + "如果这里返回 \"{}\"，doc_type = ANY('{}') 恒为 false，"
                            + "症状是「这个问题永远检索不到东西」—— 静默、且没有报错")
                    .isNull();
            assertThat(options.filtered())
                    .as("空集 = 不限制")
                    .isFalse();
        }

        @Test
        @DisplayName("null 元素被剔除（YAML 里写错缩进会产生 null）")
        void nullElementsAreDropped() {
            assertThat(new RetrievalOptions(20, Arrays.asList(2, null, 4)).docTypesLiteral())
                    .isEqualTo("{2,4}");
        }

        @Test
        @DisplayName("重复值被去重")
        void duplicatesAreDropped() {
            assertThat(new RetrievalOptions(20, List.of(2, 2, 4, 4)).docTypesLiteral())
                    .isEqualTo("{2,4}");
        }

        @Test
        @DisplayName("★ 字面量里只可能出现数字、逗号、花括号")
        void literalCharsetIsSafe() {
            // 这条是「不需要在拼 SQL 时做转义」这个结论的依据。
            // 只要本类成立，调用方就不必再校验一次 —— 而重复的校验
            // 一旦有一处漏了，就是 SQL 注入或语法错误
            RetrievalOptions options = new RetrievalOptions(20, List.of(1, 2, 3, 4, 5));
            assertThat(options.docTypesLiteral()).matches("^\\{[0-9]+(,[0-9]+)*}$");
        }
    }

    // ============================================================
    // 三、「不限制」的两种构造方式必须等价
    // ============================================================

    @Nested
    @DisplayName("三、不限制")
    class Unfiltered {

        @Test
        @DisplayName("unfiltered() / null / 空列表 三种写法等价")
        void threeWaysAreEquivalent() {
            RetrievalOptions fromFactory = RetrievalOptions.unfiltered(20);
            RetrievalOptions fromNull = new RetrievalOptions(20, null);
            RetrievalOptions fromEmpty = new RetrievalOptions(20, List.of());
            RetrievalOptions fromMutableEmpty = new RetrievalOptions(20, new ArrayList<>());

            for (RetrievalOptions o : List.of(fromFactory, fromNull, fromEmpty, fromMutableEmpty)) {
                assertThat(o.filtered()).as("都不该过滤").isFalse();
                assertThat(o.docTypesLiteral()).as("都不该产生 SQL 条件").isNull();
                assertThat(o.docTypes()).as("都归一成空列表，不是 null").isEqualTo(List.of());
            }
        }

        @Test
        @DisplayName("★ withoutFilter() 保留 topK，只去掉范围")
        void withoutFilterKeepsTopK() {
            RetrievalOptions filtered = new RetrievalOptions(37, List.of(2, 4));
            RetrievalOptions reverted = filtered.withoutFilter();

            assertThat(reverted.topK())
                    .as("★ 回落到全池时条数不能跟着变 —— 变了的话"
                            + "「过滤后一无所获」的回落会顺带改变召回量，"
                            + "而那会让 retrieval_detail 里的两段结果不可比")
                    .isEqualTo(37);
            assertThat(reverted.filtered()).isFalse();
            assertThat(filtered.filtered())
                    .as("原对象不受影响（record 不可变）")
                    .isTrue();
        }

        @Test
        @DisplayName("topK 被夹到至少 1（防止配置写成 0 导致 LIMIT 0）")
        void topKIsAtLeastOne() {
            assertThat(new RetrievalOptions(0, List.of(2)).topK()).isEqualTo(1);
            assertThat(new RetrievalOptions(-5, List.of(2)).topK()).isEqualTo(1);
            assertThat(new RetrievalOptions(20, List.of(2)).topK())
                    .as("★ 对照：正常值不被改动，否则上面两条断言可以靠「永远返回 1」通过")
                    .isEqualTo(20);
        }
    }

    // ============================================================
    // 四、不可变性
    // ============================================================

    @Test
    @DisplayName("★ 传入的 List 之后被改动，不影响已构造的 options")
    void defensiveCopy() {
        List<Integer> mutable = new ArrayList<>(List.of(2, 4));
        RetrievalOptions options = new RetrievalOptions(20, mutable);

        mutable.add(5);

        assertThat(options.docTypesLiteral())
                .as("★ 这个对象会被交给【并行的两条召回路】使用。"
                        + "如果它持有的是调用方的可变列表，调用方在别处的改动"
                        + "就会以一种「偶发、看不出因果」的方式改变检索范围")
                .isEqualTo("{2,4}");
    }
}
