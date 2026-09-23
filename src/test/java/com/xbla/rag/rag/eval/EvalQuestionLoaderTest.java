package com.xbla.rag.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.entity.EvalQuestion;
import com.xbla.rag.mapper.EvalQuestionMapper;
import com.xbla.rag.mapper.KbChunkMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EvalQuestionLoader} 的<b>「什么时候该喊」</b>（阶段 7 扩展）。
 *
 * <h2>★★ 为什么这个类值得存在：它的全部价值都在错误分支上</h2>
 *
 * <p>加载器在正常路径上做的事很简单（读 YAML、查锚点、upsert）。
 * 它真正难、也真正重要的部分是<b>在数据不对时能不能吵起来</b>：
 *
 * <pre>
 *   缺 question_set      → 这套题会被静默算进别的口径里
 *   题号重复             → upsert 会让后一道【覆盖】前一道，题数少一个，不报错
 *   缺 source            → 报告说不清这批题是怎么来的
 *   onlySet 过滤后一道题都不剩 → 报告会显示「全 0 分」而不是「没跑」
 * </pre>
 *
 * <p>而这些问题<b>在真的 {@code data/eval/} 目录里一条都跑不到</b> ——
 * 那边的文件永远是对的。所以这个类用 {@link TempDir} 造出坏的题库，
 * 一条一条地把它们逼出来。
 *
 * <h2>★ 不起 Spring</h2>
 *
 * <p>两个 Mapper 都是 mock：这个类要验的是「加载器自己的判断」，
 * 不是「SQL 写得对不对」（那是 {@code IntentTreeConsistencyTest} 的事）。
 * mock 掉之后每个用例是毫秒级的，而且不需要 docker。
 */
@DisplayName("EvalQuestionLoader · 什么时候该喊")
class EvalQuestionLoaderTest {

    @TempDir
    Path dir;

    private final KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
    private final EvalQuestionMapper evalQuestionMapper = mock(EvalQuestionMapper.class);

    private EvalQuestionLoader loader() {
        // ★ 锚点恒定解析成一条（id=1001）—— 这个类不测锚点解析
        //   （锚点不唯一的报错在下面的「锚点」一节里单独造）
        when(chunkMapper.findIdsByContentAnchor(anyString())).thenReturn(List.of(1001L));
        when(evalQuestionMapper.selectOne(any())).thenReturn(null);
        when(evalQuestionMapper.insert(any(EvalQuestion.class))).thenReturn(1);
        return new EvalQuestionLoader(chunkMapper, evalQuestionMapper);
    }

    /** 写一个题库文件 */
    private void writeFile(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    /** 一份最小的合法文件，只有 header 里的字段可以换 */
    private static String questionFile(String questionSet, String source, String annotatedBy,
                                       String questionNo, String question) {
        return """
                question_set: %s
                source: %s
                annotated_by: %s

                questions:
                  - question_no: %s
                    question: %s
                    category: colloquial
                    difficulty: 1
                    intent: RETURN_EXCHANGE
                    expected_answer: 三个工作日内发起
                    notes: 这是给测试用的最小题目
                    anchors:
                      - text: 退款在三个工作日内发起
                """.formatted(questionSet, source, annotatedBy, questionNo, question);
    }

    // ============================================================
    // 一、顶层三元组必填
    // ============================================================

    @Nested
    @DisplayName("一、顶层三元组（question_set / source / annotated_by）")
    class Header {

        @Test
        @DisplayName("★ 三样齐全 → 加载成功，且题集与来源被带进结果里")
        void happyPath() throws IOException {
            writeFile("q.yml", questionFile("stage7", "corpus_driven_manual", "Claude，用户抽查",
                    "X-001", "退货要几天"));

            EvalQuestionLoader.Result result = loader().reload(dir, null);

            assertThat(result.questions()).hasSize(1);
            assertThat(result.bySet()).containsExactly(java.util.Map.entry("stage7", 1));
            assertThat(result.questions().get(0).source()).isEqualTo("corpus_driven_manual");
            assertThat(result.questions().get(0).annotatedBy()).isEqualTo("Claude，用户抽查");
        }

        /**
         * ★★ 反对照：把三样逐个删掉，每一样都必须让加载失败。
         *
         * <p>少了这一段的话，上面的「三样齐全 → 成功」可能是恒真的
         * （比如加载器压根没检查这三样）。而「没检查」的症状是：
         * 一套题带着空白的出身进库，报告里那块数字就再也说不清来历了。
         */
        @Test
        @DisplayName("★★ 反对照：缺 question_set / source / annotated_by，各自都要失败")
        void eachHeaderFieldIsRequired() throws IOException {
            writeFile("no-set.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天")
                    .replace("question_set: stage7\n", ""));
            writeFile("no-source.yml", questionFile("stage7", "x", "y", "X-002", "退款多久到账")
                    .replace("source: x\n", ""));
            writeFile("no-annotator.yml", questionFile("stage7", "x", "y", "X-003", "保修期是多久")
                    .replace("annotated_by: y\n", ""));

            // ★ 一次报出全部三处 —— 否则改一个跑一次，来回三趟
            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no-set.yml")
                    .hasMessageContaining("缺少 question_set")
                    .hasMessageContaining("no-source.yml")
                    .hasMessageContaining("缺少 source")
                    .hasMessageContaining("no-annotator.yml")
                    .hasMessageContaining("缺少 annotated_by");
        }
    }

    // ============================================================
    // 二、★ 题号在【全部文件之间】唯一
    // ============================================================

    @Nested
    @DisplayName("二、题号唯一性")
    class QuestionNoUniqueness {

        /**
         * ★★ 这条守的是一个**静默**的数据损失。
         *
         * <p>upsert 是按 {@code question_no} 做的：两个文件里出现同一个题号时，
         * 后加载的那道会<b>覆盖</b>前一道。数据库不会报错（唯一约束被满足了），
         * 日志也不会报错 —— 只是题数比预期少一个。
         *
         * <p>而「少了的那道题是哪一道」在报告里完全看不出来：
         * 你会看到一个 149 题的评测，而你以为自己写了 150 道。
         */
        @Test
        @DisplayName("★★ 两个文件里出现同一个题号 → 失败，并指出撞在哪个文件")
        void duplicateQuestionNoAcrossFilesIsRejected() throws IOException {
            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天"));
            writeFile("b.yml", questionFile("stage7", "x", "y", "X-001", "换个说法问退货"));

            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("题号重复")
                    .hasMessageContaining("X-001")
                    .hasMessageContaining("a.yml");
        }

        /** ★ 反对照：题号不同时不该误报 —— 否则上面那条可能只是「任何两题都报错」 */
        @Test
        @DisplayName("★ 反对照：不同题号的两个文件正常加载")
        void distinctQuestionNosAreFine() throws IOException {
            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天"));
            writeFile("b.yml", questionFile("stage7", "x", "y", "X-002", "退款多久到账"));

            assertThat(loader().reload(dir, null).questions()).hasSize(2);
        }
    }

    // ============================================================
    // 三、文件级过滤与「一套都不能剩」
    // ============================================================

    @Nested
    @DisplayName("三、--set 过滤")
    class Filtering {

        @Test
        @DisplayName("★ 只加载指定的一套")
        void filtersBySet() throws IOException {
            writeFile("a.yml", questionFile("baseline", "reverse_constructed", "阶段4", "B-001", "退货要几天"));
            writeFile("b.yml", questionFile("stage7", "corpus_driven_manual", "Claude", "X-001", "退款多久到账"));

            EvalQuestionLoader.Result result = loader().reload(dir, "stage7");

            assertThat(result.questions()).hasSize(1);
            assertThat(result.questions().get(0).questionNo()).isEqualTo("X-001");
            assertThat(result.bySet()).containsExactly(java.util.Map.entry("stage7", 1));
        }

        /**
         * ★★ 「过滤之后一道题都不剩」必须失败。
         *
         * <p>不失败的话，报告会显示<b>一整套 0 分</b>：
         * 每个指标的分子分母都是 0，跑出来的东西看起来像「检索全废了」，
         * 而真相是「题集名字写错了」。★ 这两种情况的排查方向完全不同 ——
         * 空题目列表必须当场喊，而不是让下游去猜。
         */
        @Test
        @DisplayName("★★ 过滤后一道题都不剩 → 失败（不是「成功加载 0 题」）")
        void emptyAfterFilteringIsAnError() throws IOException {
            writeFile("a.yml", questionFile("baseline", "reverse_constructed", "阶段4", "B-001", "退货要几天"));

            assertThatThrownBy(() -> loader().reload(dir, "stage7"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("没有加载到任何题目");

            // ★ 反对照：同一批文件，换一个存在的 set 就能加载出来
            assertThat(loader().reload(dir, "baseline").questions()).hasSize(1);
        }

        @Test
        @DisplayName("★ 没有 question_set 的文件，在 --set 模式下也要报错（不能被静默跳过）")
        void fileWithoutSetIsStillReported() throws IOException {
            writeFile("ok.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天"));
            writeFile("broken.yml", questionFile("stage7", "x", "y", "X-002", "退款多久到账")
                    .replace("question_set: stage7\n", ""));

            assertThatThrownBy(() -> loader().reload(dir, "stage7"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("broken.yml");
        }
    }

    // ============================================================
    // 四、锚点
    // ============================================================

    @Nested
    @DisplayName("四、锚点必须唯一命中")
    class Anchors {

        @Test
        @DisplayName("★★ 锚点在库里查不到 → 失败（语料变了 / 切分改了）")
        void missingAnchorIsAnError() throws IOException {
            EvalQuestionLoader loader = loader();
            // ★ 顺序要紧：`anyString()` 那条桩能匹配一切，而 Mockito 是
            //   【后打的桩赢】—— 写在 loader() 前面的话会被它盖掉，
            //   于是这个用例会变成「锚点找到了」，测试报「期待抛异常但没有」
            when(chunkMapper.findIdsByContentAnchor(ArgumentMatchers.contains("退款在三个工作日内发起")))
                    .thenReturn(List.of());
            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天"));

            assertThatThrownBy(() -> loader.reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("锚点在全库里找不到任何切片")
                    .hasMessageContaining("X-001");
        }

        /**
         * ★★ 两条锚点落在<b>同一片</b>切片上时，gold 必须只有一条。
         *
         * <p>这是合法的、而且很常见：一道题想问「退款时限」和「余额到账」，
         * 而这两句话写在同一个切片里（售后政策汇编 2.3 就是）。
         *
         * <p>⚠️ 不去重的话 gold 变成 {@code [55, 55]}，而
         * {@code Recall@K = |命中 ∩ gold| / |gold|} 的<b>分母被算成 2</b> ——
         * 「正解被完整召回」只能得 0.5 分。不报错、不漏题，
         * 只是让某些题的召回率永久偏低，而报告上看不出是哪几道题、为什么。
         *
         * <p>★ 这个缺陷阶段 4 就存在（{@code addAll} 一模一样），
         * 只是那 20 题恰好都只有一条锚点，所以从没暴露 ——
         * 阶段 7 第一次写多锚点题就撞上了（F-001）。
         */
        @Test
        @DisplayName("★★ 两条锚点解析到同一片 → gold 只有一条（否则 Recall 分母虚高）")
        void anchorsInTheSameChunkAreDeduplicated() throws IOException {
            EvalQuestionLoader loader = loader();
            when(chunkMapper.findIdsByContentAnchor(anyString())).thenReturn(List.of(1001L));

            writeFile("a.yml", """
                    question_set: stage7
                    source: x
                    annotated_by: y

                    questions:
                      - question_no: X-001
                        question: 退货要几天
                        category: keyword
                        difficulty: 1
                        intent: RETURN_EXCHANGE
                        notes: 两条锚点故意落在同一片
                        anchors:
                          - text: 锚点甲
                          - text: 锚点乙
                    """);

            List<Long> gold = loader.reload(dir, null).questions().get(0).expectedChunkIds();

            assertThat(gold)
                    .as("两条锚点都命中 1001 —— gold 应该是 [1001]，不是 [1001, 1001]")
                    .containsExactly(1001L);
        }

        /** ★ 反对照：两条锚点落在不同切片时，两条都要在 —— 否则上面那条可能只是「永远只留一条」 */
        @Test
        @DisplayName("★ 反对照：两条锚点命中不同切片 → gold 两条都在")
        void differentChunksBothSurvive() throws IOException {
            EvalQuestionLoader loader = loader();
            when(chunkMapper.findIdsByContentAnchor(ArgumentMatchers.contains("锚点乙")))
                    .thenReturn(List.of(2002L));

            writeFile("a.yml", """
                    question_set: stage7
                    source: x
                    annotated_by: y

                    questions:
                      - question_no: X-001
                        question: 退货要几天
                        category: keyword
                        difficulty: 1
                        intent: RETURN_EXCHANGE
                        notes: 两条锚点落在不同片
                        anchors:
                          - text: 锚点甲
                          - text: 锚点乙
                    """);

            assertThat(loader.reload(dir, null).questions().get(0).expectedChunkIds())
                    .containsExactly(1001L, 2002L);
        }

        @Test
        @DisplayName("★★ 锚点命中多条 → 失败；加 allow_multiple: true 才接受")
        void ambiguousAnchorNeedsOptIn() throws IOException {
            EvalQuestionLoader loader = loader();
            // ★ 同 missingAnchorIsAnError：必须在 loader() 之后再覆盖，
            //   否则会被它那条 anyString() 的桩盖掉
            when(chunkMapper.findIdsByContentAnchor(anyString())).thenReturn(List.of(1001L, 1002L));

            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天"));
            assertThatThrownBy(() -> loader.reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不够独特")
                    .hasMessageContaining("allow_multiple");

            // ★ 正-反对照：同一个文件，只加一行 allow_multiple: true 就通过
            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天")
                    .replace("    notes: 这是给测试用的最小题目",
                            "    notes: 这是给测试用的最小题目\n    allow_multiple: true"));

            EvalQuestionLoader.Result result = loader.reload(dir, null);
            assertThat(result.questions().get(0).expectedChunkIds()).containsExactly(1001L, 1002L);
        }
    }

    // ============================================================
    // 五、★ 没写 notes 只统计、不报错
    // ============================================================

    @Nested
    @DisplayName("五、notes（标注理由）")
    class Notes {

        /**
         * ★ 这条固定的是<b>一个刻意的取舍</b>：{@code notes} 缺失不是错误。
         *
         * <p>理由：它是写给人看的（为什么标这个叶子、哪些候选被否掉了），
         * 缺了不会让任何数字变错。阶段 4 的那 20 道题就没有它 ——
         * 强行必填意味着为了让老文件通过而去补 20 段空洞的理由。
         *
         * <p>★ 但它必须<b>可见</b>：{@code missingNotes} 会出现在加载日志和
         * {@code /api/debug/eval/reload} 的响应里。150 题里悄悄少了 20 条理由，
         * 是没人会发现的事 —— 所以它至少得有个数。
         */
        @Test
        @DisplayName("★ 没写 notes → 不报错，但 missingNotes 要数出来")
        void missingNotesIsCountedNotRejected() throws IOException {
            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天")
                    .replace("    notes: 这是给测试用的最小题目\n", ""));

            EvalQuestionLoader.Result result = loader().reload(dir, null);

            assertThat(result.questions()).as("缺 notes 不该让加载失败").hasSize(1);
            assertThat(result.missingNotes()).isEqualTo(1);

            // ★ 反对照：写了 notes 的同一道题 → 计数为 0
            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天"));
            assertThat(loader().reload(dir, null).missingNotes()).isZero();
        }
    }

    // ============================================================
    // 六、★★ 空 gold 的三态校验（阶段 7 批次 5 新增）
    // ============================================================

    /**
     * 「这道题本来就没有检索目标」（工具题 / 兜底 / 澄清）必须<b>显式声明</b>。
     *
     * <p>这一组的三条测试合起来守的是<b>一件事的三种状态</b>：
     *
     * <pre>
     *   有 anchors                              → 正常题，必须有 gold
     *   没有 anchors + expect_no_retrieval: true → 工具题，gold 为空（正常）
     *   没有 anchors + 什么都没有                → 【写漏了】→ 必须失败
     * </pre>
     *
     * <p>★★ 为什么第三条必须失败：它和第二条在数据上<b>长得一模一样</b>
     * （都是空数组）。放过去的话，一道漏写 {@code anchors} 的题会
     * <b>静默退出检索指标</b> —— 题数少一道看不出来，指标也不会算错，
     * 只是那道题从此再也不测任何东西了（同 ADR-036 的静默漏跑）。
     */
    @Nested
    @DisplayName("六、★★ 空 gold 与工具题")
    class EmptyGold {

        /** 一道最小的「不检索」题 —— 没有 anchors 段，只有那一行声明 */
        private static String noRetrievalFile(String extra) {
            return """
                    question_set: stage7
                    source: corpus_driven_manual
                    annotated_by: Claude

                    questions:
                      - question_no: T-001
                        question: 我的订单到哪了
                        category: colloquial
                        difficulty: 1
                        intent: ORDER_STATUS
                    %s""".formatted(extra);
        }

        @Test
        @DisplayName("★ 声明 expect_no_retrieval: true → 加载成功，gold 为空")
        void declaredNoRetrievalLoads() throws IOException {
            writeFile("a.yml", noRetrievalFile("    expect_no_retrieval: true\n"
                    + "    notes: 答案来自 MCP 工具，不在知识库里"));

            EvalQuestionLoader.Result result = loader().reload(dir, null);

            assertThat(result.questions()).hasSize(1);
            assertThat(result.questions().get(0).expectedChunkIds()).isEmpty();
            assertThat(result.questions().get(0).expectNoRetrieval()).isTrue();
            assertThat(result.questions().get(0).hasRetrievalGold()).isFalse();
            // ★ 锚点一次都没去查库 —— 不检索的题不该触发任何查询
            verify(chunkMapper, never()).findIdsByContentAnchor(anyString());
        }

        /**
         * ★★ 反对照：同样的题，去掉那一行声明 → 必须失败。
         *
         * <p>少了这条，「声明了就通过」可能是恒真的（比如加载器压根
         * 没区分这两种情况，只是碰巧都不报错）。
         */
        @Test
        @DisplayName("★★ 反对照：没有 anchors 也没声明 → 失败，并告诉写题的人有那条声明")
        void undeclaredEmptyAnchorsFails() throws IOException {
            writeFile("a.yml", noRetrievalFile("    notes: 忘了写锚点"));

            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("缺少 anchors")
                    .hasMessageContaining("expect_no_retrieval")
                    .hasMessageContaining("静默退出检索指标");
        }

        /** ★★ 声明「不检索」却又给了锚点 → 自相矛盾，也要失败 */
        @Test
        @DisplayName("★★ 同时有 anchors 和 expect_no_retrieval: true → 失败")
        void bothDeclaredIsContradiction() throws IOException {
            writeFile("a.yml", noRetrievalFile("    expect_no_retrieval: true\n"
                    + "    anchors:\n"
                    + "      - text: 退款在三个工作日内发起\n"));

            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("自相矛盾");
        }

        /**
         * ★ emptyGold 计数要能看见。
         *
         * <p>它不是错误，但必须<b>可见</b>：150 题里悄悄有 30 道不进
         * 检索指标的分母，是没人会发现的事。
         */
        @Test
        @DisplayName("★ emptyGold 只数声明的那些题（有锚点的题不计入）")
        void emptyGoldIsCounted() throws IOException {
            // ★ 一整个文件手写，而不是拼两段字符串 ——
            //   YAML 对缩进敏感，拼字符串会让第二道题的缩进跟着第一段的
            //   结尾走，报出「mapping values are not allowed here」这种
            //   和真实原因无关的错（写这个测试时就踩了一次）
            writeFile("a.yml", """
                    question_set: stage7
                    source: corpus_driven_manual
                    annotated_by: Claude

                    questions:
                      - question_no: T-001
                        question: 我的订单到哪了
                        category: colloquial
                        difficulty: 1
                        intent: ORDER_STATUS
                        expect_no_retrieval: true
                        user_id: 8
                      - question_no: X-001
                        question: 退货要几天
                        category: colloquial
                        difficulty: 1
                        intent: RETURN_EXCHANGE
                        expected_answer: 三个工作日内发起
                        anchors:
                          - text: 退款在三个工作日内发起
                    """);

            EvalQuestionLoader.Result result = loader().reload(dir, null);

            assertThat(result.questions()).hasSize(2);
            // ★ 反对照：两题都在，但只有一题是空 gold ——
            //   否则这个计数可能只是「所有题都算空 gold」
            assertThat(result.emptyGold()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ user_id：写了就读出来，没写就是 null（不是 0）")
        void userIdIsOptionalAndNullable() throws IOException {
            writeFile("a.yml", noRetrievalFile("    expect_no_retrieval: true\n"
                    + "    user_id: 8\n"));

            assertThat(loader().reload(dir, null).questions().get(0).userId()).isEqualTo(8L);

            // ★ 反对照：去掉 user_id → null。★ 用 0 当「没有」是错的：
            //   0 可能是一个合法 id，而「没写」必须与「写了 0」区分开
            writeFile("a.yml", noRetrievalFile("    expect_no_retrieval: true\n"));
            assertThat(loader().reload(dir, null).questions().get(0).userId()).isNull();
        }

        @Test
        @DisplayName("★ 知识库题的 expectNoRetrieval 恒为 false（不会被误标）")
        void knowledgeQuestionsAreNotMarked() throws IOException {
            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天"));

            EvalQuestionLoader.Result result = loader().reload(dir, null);
            assertThat(result.questions().get(0).expectNoRetrieval()).isFalse();
            assertThat(result.questions().get(0).hasRetrievalGold()).isTrue();
            assertThat(result.emptyGold()).isZero();
        }
    }

    // ============================================================
    // 七、★★ 多轮题（V13）
    //
    // 这一节的每一条都对应一种【不报错但错】的写法。它们比前六节更要紧，
    // 因为多轮题一旦混错，坏掉的不是一道题，是【一整套题的解读方式】。
    // ============================================================

    @Nested
    @DisplayName("七、★★ 多轮题（V13）")
    class MultiTurn {

        /** 一道最小的合法多轮题 —— turns 两轮，末尾要补 standalone_question 等字段 */
        private static String multiTurnFile(String set, String extra) {
            return """
                    question_set: %s
                    source: corpus_driven_manual
                    annotated_by: Claude

                    questions:
                      - question_no: MT-001
                        turns:
                          - 小米手环 8 防水吗
                          - 那它游泳能戴吗
                    %s""".formatted(set, extra);
        }

        /** 除了 turns 之外的必备字段，按 4 空格缩进拼进去 */
        private static final String REST =
                "    standalone_question: 小米手环 8 能戴着游泳吗\n"
                        + "    category: colloquial\n"
                        + "    difficulty: 2\n"
                        + "    intent: SPEC_QUERY\n"
                        + "    expected_answer: 可以，5ATM 防水；但不建议游泳\n"
                        + "    notes: 追问句里的「它」只能从上一轮解出来\n"
                        + "    anchors:\n"
                        + "      - text: 5ATM 防水等级\n";

        @Test
        @DisplayName("★★ 合法多轮题：question 自动取最后一轮，turns 完整保留")
        void multiTurnLoads() throws IOException {
            writeFile("m.yml", multiTurnFile("stage7-multi", REST));

            EvalQuestionLoader.LoadedQuestion q = loader().reload(dir, null).questions().get(0);

            // ★ question 是【推导】出来的，不是写进去的 —— 它是 turns 的最后一轮
            assertThat(q.question()).isEqualTo("那它游泳能戴吗");
            assertThat(q.turns()).containsExactly("小米手环 8 防水吗", "那它游泳能戴吗");
            assertThat(q.standaloneQuestion()).isEqualTo("小米手环 8 能戴着游泳吗");
            assertThat(q.isMultiTurn()).isTrue();
        }

        @Test
        @DisplayName("★ 反对照：单轮题 isMultiTurn 为 false，且 turns 是空列表（不是 null）")
        void singleTurnIsNotMulti() throws IOException {
            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天"));

            EvalQuestionLoader.LoadedQuestion q = loader().reload(dir, null).questions().get(0);

            assertThat(q.isMultiTurn()).isFalse();
            // ★ 空列表而不是 null：调用方可以无条件 .stream() / .size()
            assertThat(q.turns()).isEmpty();
            assertThat(q.standaloneQuestion()).isNull();
        }

        /**
         * ★★ 同时写 turns 和 question → 失败。
         *
         * <p>这是本次扩展里最要命的一条：同样的文本有两个来源时，
         * 改了一处忘了另一处<b>不会报错</b>，只会让报告开始描述错的东西。
         */
        @Test
        @DisplayName("★★ 同时写 turns 和 question → 失败（同一个事实的两个来源）")
        void explicitQuestionIsContradiction() throws IOException {
            writeFile("m.yml", multiTurnFile("stage7-multi",
                    "    question: 那它游泳能戴吗\n" + REST));

            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("同时写了 turns 和 question")
                    .hasMessageContaining("两个来源");
        }

        /** ★★ 多轮题写进了单轮集 → 失败。混进去只会让单轮指标里混进「答案依赖上一句」的题 */
        @Test
        @DisplayName("★★ 多轮题放进 stage7（单轮集）→ 失败")
        void turnsInSingleTurnSetFails() throws IOException {
            writeFile("m.yml", multiTurnFile("stage7", REST));

            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("多轮题必须放进以")
                    .hasMessageContaining("不再描述任何东西");
        }

        /** ★★ 对偶方向：单轮题写进了多轮集 → 也要失败（它会稀释多轮的数字） */
        @Test
        @DisplayName("★★ 反对照：单轮题放进 stage7-multi → 失败")
        void singleTurnInMultiSetFails() throws IOException {
            writeFile("a.yml", questionFile("stage7-multi", "x", "y", "X-001", "退货要几天"));

            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("这一套是多轮题集")
                    .hasMessageContaining("被稀释");
        }

        /** ★★ 只有一轮 → 失败。它不是多轮题，是一道写法绕了点儿的单轮题 */
        @Test
        @DisplayName("★★ turns 只有一轮 → 失败（本轮该写进单轮题库）")
        void oneTurnIsNotMultiTurn() throws IOException {
            writeFile("m.yml", """
                    question_set: stage7-multi
                    source: corpus_driven_manual
                    annotated_by: Claude

                    questions:
                      - question_no: MT-001
                        turns:
                          - 小米手环 8 防水吗
                        standalone_question: 小米手环 8 防水吗
                        intent: SPEC_QUERY
                        anchors:
                          - text: 5ATM 防水等级
                    """);

            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("至少要两轮")
                    .hasMessageContaining("单轮题库");
        }

        /** ★★ 多轮题缺 standalone_question → 失败：没有它就不知道该按哪句话标 gold */
        @Test
        @DisplayName("★★ 多轮题缺 standalone_question → 失败（该按哪句话标 gold？）")
        void multiTurnWithoutStandaloneFails() throws IOException {
            writeFile("m.yml", multiTurnFile("stage7-multi", """
                        category: colloquial
                        intent: SPEC_QUERY
                        anchors:
                          - text: 5ATM 防水等级
                    """));

            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("缺少 standalone_question")
                    .hasMessageContaining("省略说法");
        }

        /** ★★ 对偶：单轮题写了 standalone_question → 失败（那等于同一个事实的第二个来源） */
        @Test
        @DisplayName("★★ 反对照：单轮题写了 standalone_question → 失败")
        void standaloneOnSingleTurnFails() throws IOException {
            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天")
                    + "    standalone_question: 退货要几天\n");

            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("单轮题不该有 standalone_question")
                    .hasMessageContaining("第二个来源");
        }

        @Test
        @DisplayName("★ 空的轮次 → 失败（跑题器会发一句空问题，那个失败会被记成「模型不行」）")
        void blankTurnFails() throws IOException {
            writeFile("m.yml", """
                    question_set: stage7-multi
                    source: corpus_driven_manual
                    annotated_by: Claude

                    questions:
                      - question_no: MT-001
                        turns:
                          - 小米手环 8 防水吗
                          -
                        standalone_question: 小米手环 8 能戴着游泳吗
                        intent: SPEC_QUERY
                        anchors:
                          - text: 5ATM 防水等级
                    """);

            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("第 2 轮不是一句非空的提问");
        }

        /**
         * ★★ 写进数据库的是 JSON —— 这一条验的是<b>序列化本身</b>。
         *
         * <p>为什么必须验：{@code turns} 是写进 JSONB 列的，
         * 而调试接口返回的是内存里的 {@code List<String>} ——
         * <b>序列化这段代码在进程内没有任何读者</b>。写错了（漏引号、
         * 拼错分隔符）不会让任何测试变红，只会让数据库里躺着一串
         * 解析不出来的东西，等阶段 8 或某个脚本去读它时才炸。
         * （同 ADR-061：DTO 的形状只有真的序列化过才算验证过。）
         */
        @Test
        @DisplayName("★★ turns 真的被序列化成合法 JSON 数组（进程内没有第二个读者，只能在这里验）")
        void turnsAreSerializedAsJson() throws IOException {
            writeFile("m.yml", multiTurnFile("stage7-multi", REST));

            EvalQuestionLoader.Result result = loader().reload(dir, null);
            assertThat(result.questions()).hasSize(1);

            ArgumentCaptor<EvalQuestion> captor = ArgumentCaptor.forClass(EvalQuestion.class);
            verify(evalQuestionMapper).insert(captor.capture());

            String json = captor.getValue().getTurns();
            assertThat(json).isNotNull();
            assertThat(new ObjectMapper().readValue(json, new TypeReference<List<String>>() {
            })).containsExactly("小米手环 8 防水吗", "那它游泳能戴吗");
            assertThat(captor.getValue().getStandaloneQuestion())
                    .isEqualTo("小米手环 8 能戴着游泳吗");
        }

        /** ★ 对偶：单轮题的两列必须写 null —— 数据库有 CHECK 要求「同生同死」 */
        @Test
        @DisplayName("★ 反对照：单轮题写进库的两列都是 null（CHECK 要求同生同死）")
        void singleTurnWritesNulls() throws IOException {
            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天"));
            loader().reload(dir, null);

            ArgumentCaptor<EvalQuestion> captor = ArgumentCaptor.forClass(EvalQuestion.class);
            verify(evalQuestionMapper).insert(captor.capture());
            assertThat(captor.getValue().getTurns()).isNull();
            assertThat(captor.getValue().getStandaloneQuestion()).isNull();
        }
    }

    // ============================================================
    // 八、★ 字段白名单
    // ============================================================

    @Nested
    @DisplayName("八、★ 字段白名单（未知字段直接失败）")
    class KnownFields {

        @Test
        @DisplayName("★★ 拼错的字段名 → 失败，并列出允许的字段")
        void typoIsRejected() throws IOException {
            // difficlty（少了一个 u）—— 宽松模式下它会静默变 null，
            // 而题目从数据上看完全正常
            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天")
                    .replace("difficulty: 1", "difficlty: 1"));

            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不认识的字段 [difficlty]")
                    .hasMessageContaining("difficulty");
        }

        /**
         * ★★ {@code session_no} 值得一条专门的解释。
         *
         * <p>它是唯一一个「写进去之后功能看起来还在工作」的字段：
         * 跑题器会照着它复用会话 → repeat 之间串味 → 多轮指标变好。
         * 一句通用的「不认识的字段」拦得住错误，但拦不住<b>下次再犯</b>。
         */
        @Test
        @DisplayName("★★ session_no 有自己的解释（它会让 repeat 串味，症状像好消息）")
        void sessionNoGetsItsOwnExplanation() throws IOException {
            writeFile("m.yml", """
                    question_set: stage7-multi
                    source: corpus_driven_manual
                    annotated_by: Claude

                    questions:
                      - question_no: MT-001
                        turns:
                          - 小米手环 8 防水吗
                          - 那它游泳能戴吗
                        session_no: s-001
                        standalone_question: 小米手环 8 能戴着游泳吗
                        intent: SPEC_QUERY
                        anchors:
                          - text: 5ATM 防水等级
                    """);

            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("session_no 尤其不能写")
                    .hasMessageContaining("串味")
                    .hasMessageContaining("看起来像好消息");
        }

        /** ★ 锚点那一层的白名单是另一张表；{@code doc_hint} 必须被放行（阶段 4 用了 39 次） */
        @Test
        @DisplayName("★ doc_hint 放行（它是给人看的注释），但锚点层别的不认识也报错")
        void anchorLevelWhitelist() throws IOException {
            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天")
                    .replace("- text: 退款在三个工作日内发起",
                            "- text: 退款在三个工作日内发起\n        doc_hint: 售后政策汇编 2.3"));
            assertThat(loader().reload(dir, null).questions()).hasSize(1);

            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天")
                    .replace("- text: 退款在三个工作日内发起",
                            "- text: 退款在三个工作日内发起\n        txt: 打错了"));
            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("锚点出现了不认识的字段")
                    .hasMessageContaining("doc_hint");
        }

        /**
         * ★★ 只写 {@code doc_hint} 不写 {@code text} → 失败。
         *
         * <p>原来这里会走 {@code String.valueOf(null)}，得到字符串 {@code "null"}，
         * 然后拿它去全库搜「null」—— 报错说的是「找不到任何切片: "null"」，
         * 与真正的原因（锚点没写 text）无关。
         */
        @Test
        @DisplayName("★★ 锚点只写 doc_hint 不写 text → 失败，且报的是真原因")
        void anchorWithoutTextFails() throws IOException {
            writeFile("a.yml", questionFile("stage7", "x", "y", "X-001", "退货要几天")
                    .replace("- text: 退款在三个工作日内发起", "- doc_hint: 写漏了 text"));

            assertThatThrownBy(() -> loader().reload(dir, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("锚点缺少 text")
                    // ★ 反对照：不能再是那句误导人的「找不到任何切片: "null"」
                    .hasMessageNotContaining("找不到任何切片");
        }
    }
}
