package com.xbla.rag.mapper;

import com.baomidou.mybatisplus.annotation.TableField;
import com.xbla.rag.entity.QaLog;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code QaLogMapper.selectByEvalRun} —— <b>显式列清单不许和实体字段漂移</b>。
 *
 * <h2>★★★ 为什么这个测试必须存在（它守的东西已经坏了两次）</h2>
 *
 * <p>这条查询手写了 <b>29</b> 个列名，而不是 {@code SELECT *}。手写是对的
 * （体积可控、读的人一眼看得到取什么），但<b>它和实体字段之间没有任何强制同步</b>。
 * ⚠️ 这个数字本身漂过：7.5 之后是 25，9.2 / 9.5 / 9.6 各加过列。
 * <b>别信这句注释里的数，信下面那段反射出来的清单</b> —— 它就是为此存在的。
 * 于是「给服务加一个新读取，忘了给查询加一列」是一个<b>必然会发生</b>的操作，
 * 而它的症状是这三种里最坏的一种：
 *
 * <pre>
 * ① 不报错 —— 查询成功返回全部行
 * ② 返回的不是 null 而是【一个看起来合法的值】——
 *    null 被服务里的 {@code != null} 判断挡掉，累加器保持初始值 0
 * ③ 数字并排印出来 —— 旁边那个数是对的，所以它看起来像「口径不同」
 * </pre>
 *
 * <p><b>实测两次，都在同一个方法上：</b>
 * <ol>
 *   <li><b>7.5</b>：加 {@code final_answer} / {@code references} 时漏了 {@code question}
 *       → RAGAS 每条报 {@code user_input cannot be empty}，15 个指标全 null。
 *       <b>症状离病因很远</b>（看起来像 RAGAS 配置不对）。</li>
 *   <li><b>7.6（T8）</b>：给 {@code costSection} 加 {@code prompt_tokens} /
 *       {@code completion_tokens} 的累加，两列都没加进查询 →
 *       {@code prompt_token合计} 和 {@code 输出token合计} 恒为 <b>0</b>，
 *       而紧挨着的 {@code token合计} 是 386591。报告把三行并排印出来，
 *       看着像「拆分粒度不同」。</li>
 * </ol>
 *
 * <p>★★★ <b>为什么 {@code EvalReportServiceTest} 抓不到它</b>：那个类是纯单测，
 * 用<b>手工 new 出来的 {@code QaLog} 对象</b>喂给算法 —— 对象里的字段是测试自己
 * set 的，所以<b>投影漏没漏列它看不见</b>。算法是对的，坏的是取数。
 * 这就是 ADR-061 那条：<b>桩造的对象验证不了它自己</b>。
 *
 * <h2>★ 这个测试的判据是【结构】而不是【当前的值】</h2>
 *
 * <p>它不写「prompt_tokens 必须在里面」（那只守住这一次），而是
 * <b>遍历实体的全部字段，逐个要求它在投影里，除非它在 {@link #NOT_SELECTED} 里
 * 显式声明过</b>。于是：
 * <ul>
 *   <li>加新字段 + 新读取 → 忘了加列 → <b>红</b>；</li>
 *   <li>加新字段但确实不需要取 → 加进 {@code NOT_SELECTED}，
 *       那是一次<b>被看见的决定</b>（同 {@code eval_report.py} 的 {@code OMITTED_PATHS}：
 *       「它是一份受审的清单，不是一句「其余都忽略」」）。</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@DisplayName("QaLogMapper · selectByEvalRun 的投影不许漂移")
class QaLogMapperProjectionTest {

    /**
     * ★★ 实体里有、但这条查询<b>刻意不取</b>的字段 —— 一份受审的清单。
     *
     * <p>每一行都要能说出「为什么不取」。说不出来就说明它该被取。
     */
    private static final Set<String> NOT_SELECTED = new LinkedHashSet<>(List.of(
            // 身份：评测报告按 eval_run_id 圈范围，不需要回溯到【谁】问的。
            // （真实用户的行这条路根本不查 —— WHERE eval_run_id = ? 已经限定了）
            //
            // ★★ 9.6 更正了一处混淆：{@code sessionId} 原先被归在这一条下面，
            //    而它和身份无关 —— 它回答的是「这几行属不属于同一次会话」。
            //    多轮的轮次边界只能靠它还原，所以 9.6 把它取出来了。
            "userId",
            // ★ 检索【内部】的重写问题。它只服务 RetrievalPipeline 的召回，
            //   从不进生成用的消息（模型看到的一直是 question）。报告侧不需要。
            "rewrittenQuestion",
            // 熔断降级事件。它服务的是「降级可归因」，报告的问题是
            // 「这一轮有没有降级」—— 那由 provider / model 两列直接回答，
            // 而它们【在】投影里（见下「按 provider 切片」那节）。
            "degradationEvents"
            // ★★ 9.6 之后这里【只剩 3 项】。原来的 5 项里有两项到期了：
            //    sessionId  —— 见上面那条更正，多轮分组要用；
            //    toolCalls  —— 原理由写的是「报告侧只要知道这题是不是工具题，
            //                  那由 intent 判」。那句在当时是对的：
            //                  报告确实只问「该不该」。9.6 开始问
            //                  「该调工具的题，模型调了没有」，它就必须进来。
            //    ⚠️ 这两个都是【移除豁免】，方向和「加一项」相反 ——
            //       加一项是让测试闭嘴，移除一项是让测试重新开始要求它。
    ));

    private static final String RUN = "PROJ-" + System.nanoTime() + "-";

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private QaLogMapper qaLogMapper;

    // ============================================================
    // 反射：把 @Select 的 SQL 和实体的字段名都拿出来
    // ============================================================

    /** 实体字段名 → 它映射的列名（认 {@code @TableField}，否则驼峰转下划线）。 */
    private static String columnOf(Field f) {
        TableField tf = f.getAnnotation(TableField.class);
        if (tf != null && !tf.value().isEmpty()) {
            return tf.value().replace("\"", "");
        }
        return f.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    /** {@code selectByEvalRun} 上那段的 SQL 文本。 */
    private static String sql() {
        for (Method m : QaLogMapper.class.getDeclaredMethods()) {
            if (m.getName().equals("selectByEvalRun")) {
                Select sel = m.getAnnotation(Select.class);
                assertNotNull(sel, "selectByEvalRun 上应该有 @Select");
                return String.join(" ", sel.value());
            }
        }
        throw new AssertionError("找不到 selectByEvalRun —— 方法被改名了？");
    }

    /** SQL 的 SELECT 段落里的列名集合（已去引号、去空白）。 */
    private static Set<String> projectedColumns() {
        String s = sql();
        Matcher m = Pattern.compile("(?is)\\bSELECT\\b(.*?)\\bFROM\\b").matcher(s);
        assertTrue(m.find(), "SQL 里找不到 SELECT ... FROM：" + s);
        Set<String> cols = new LinkedHashSet<>();
        for (String part : m.group(1).split(",")) {
            cols.add(part.trim().replace("\"", ""));
        }
        return cols;
    }

    private QaLog insertRow() {
        QaLog row = new QaLog();
        row.setTraceId("T-PROJ-" + System.nanoTime());
        row.setQuestion("PROJ-" + SEQ.incrementAndGet());
        row.setEvalRunId(RUN);
        row.setEvalQuestionNo("P-" + SEQ.get());
        qaLogMapper.insert(row);
        return row;
    }

    // ============================================================
    // 一、结构：投影必须覆盖实体字段（例外要被声明）
    // ============================================================

    @Nested
    @DisplayName("一、★ 投影 vs 实体字段")
    class ProjectionCoverage {

        @Test
        @DisplayName("★★ 每个实体字段要么在投影里，要么在 NOT_SELECTED 里声明过")
        void everyFieldIsEitherSelectedOrDeclared() {
            Set<String> cols = projectedColumns();
            List<String> offenders = new ArrayList<>();
            for (Field f : QaLog.class.getDeclaredFields()) {
                if (f.isSynthetic() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (NOT_SELECTED.contains(f.getName())) {
                    continue;
                }
                String col = columnOf(f);
                if (!cols.contains(col)) {
                    offenders.add(f.getName() + "（列名 `" + col + "`）");
                }
            }
            assertTrue(offenders.isEmpty(),
                    "★★★ 这些实体字段既不在 selectByEvalRun 的投影里，也没在 NOT_SELECTED 里声明：\n"
                            + "  " + String.join("\n  ", offenders) + "\n"
                            + "★ 两种修法，选一个：\n"
                            + "  ① 服务真的要读它 → 加进 SQL 的列清单（注意别只加一处）；\n"
                            + "  ② 确实不需要 → 加进 NOT_SELECTED 并写明为什么。\n"
                            + "★★ 不修的话症状是【静默的】：读出来是 null，被 `!= null` 挡掉，"
                            + "累加器停在 0，而那个 0 会被当成一个合法值印进报告。");
        }

        @Test
        @DisplayName("★★ 反对照：NOT_SELECTED 里声明的字段，投影里【确实】没有")
        void notSelectedFieldsAreReallyAbsent() {
            // ★ 没有这一条的话，上面那个测试是可以用「把什么都塞进 NOT_SELECTED」
            //   骗过去的 —— 它会绿，而投影可能已经被清空了。
            Set<String> cols = projectedColumns();
            List<String> offenders = new ArrayList<>();
            for (Field f : QaLog.class.getDeclaredFields()) {
                if (!NOT_SELECTED.contains(f.getName())) {
                    continue;
                }
                String col = columnOf(f);
                if (cols.contains(col)) {
                    offenders.add(f.getName() + "（列名 `" + col + "`）");
                }
            }
            assertTrue(offenders.isEmpty(),
                    "这些字段在 NOT_SELECTED 里声明了【不取】，但投影里其实有：\n  "
                            + String.join("\n  ", offenders)
                            + "\n★ 说明清单过期了 —— 删掉那几行，别让「受审的清单」变成一句空话。");
        }
    }

    // ============================================================
    // 二、★★ 反对照：不在清单里的列，读回来真的是 null
    // ============================================================

    @Nested
    @DisplayName("二、★★ 反对照：读回来的 null 是投影造成的，不是查错了行")
    class CounterControl {

        @Test
        @DisplayName("写进 rewritten_question 的值读不回来；同一行的 question 读得回来")
        void unselectedColumnReadsBackNull() {
            String rewritten = "REWRITTEN-" + System.nanoTime();
            QaLog row = new QaLog();
            row.setTraceId("T-PROJ-" + System.nanoTime());
            row.setQuestion("PROJ-" + SEQ.incrementAndGet());
            row.setEvalRunId(RUN);
            row.setEvalQuestionNo("P-" + SEQ.get());
            row.setRewrittenQuestion(rewritten);
            qaLogMapper.insert(row);

            List<QaLog> back = qaLogMapper.selectByEvalRun(RUN);
            assertEquals(1, back.size(), "应该正好一行");

            // ★★ 这一条是【反对照】，它证明的是这个测试【有能力】看见投影缺口：
            //    「读出来是 null」在这个 setup 下是可达的结果，不是恒真式。
            //    没有它的话，「prompt_tokens 读到了」可能只是因为
            //    MyBatis 其实把整行都查回来了 —— 那时测试绿着，而列清单早就是废纸。
            assertNull(back.get(0).getRewrittenQuestion(),
                    "rewritten_question 不在投影里，必须读成 null。\n"
                            + "★ 它读到了值 = 这条查询不是按列清单取的"
                            + "（那前面「漏列会读成 null」这个前提就不成立，整套测试得重来）");

            // ★ 同一行【在清单里】的列必须读得到 —— 否则上面那个 null
            //   可能只是「查错了行」的另一种表现，反对照就成了安慰剂。
            assertNotNull(back.get(0).getQuestion(), "★ 同一行的 question 必须读得到");
        }
    }

    // ============================================================
    // 三、T8 新增的三列：往返 + 那个免费的不变量
    // ============================================================

    @Nested
    @DisplayName("三、★ T8 新增的两列（token 拆分）")
    class TokenColumns {

        @Test
        @DisplayName("★ prompt / completion / total 三列都取得回来")
        void tokenColumnsRoundTrip() {
            QaLog row = new QaLog();
            row.setTraceId("T-PROJ-" + System.nanoTime());
            row.setQuestion("PROJ-" + SEQ.incrementAndGet());
            row.setEvalRunId(RUN);
            row.setEvalQuestionNo("P-" + SEQ.get());
            row.setProvider("deepseek");
            row.setModel("deepseek-flash");
            row.setPromptTokens(1000);
            row.setCompletionTokens(200);
            row.setTotalTokens(1200);
            qaLogMapper.insert(row);

            List<QaLog> back = qaLogMapper.selectByEvalRun(RUN);
            assertEquals(1, back.size());
            QaLog r = back.get(0);

            assertNotNull(r.getPromptTokens(),
                    "★★★ prompt_tokens 读成 null 的话，EvalReportService.costSection 里"
                            + "那个 `!= null` 判断会把它挡掉，累加器停在 0 —— "
                            + "于是报告印出一个【看起来合法的 0】。T8 就是这么坏的。");
            assertNotNull(r.getCompletionTokens(), "同上");
            assertEquals(1000, r.getPromptTokens());
            assertEquals(200, r.getCompletionTokens());
            assertEquals(1200, r.getTotalTokens());
        }

        @Test
        @DisplayName("★★ 不变量：prompt + completion == total（全库 17 轮实测 0 例外）")
        void splitSumsToTotal() {
            QaLog row = new QaLog();
            row.setTraceId("T-PROJ-" + System.nanoTime());
            row.setQuestion("PROJ-" + SEQ.incrementAndGet());
            row.setEvalRunId(RUN);
            row.setEvalQuestionNo("P-" + SEQ.get());
            row.setPromptTokens(777);
            row.setCompletionTokens(333);
            row.setTotalTokens(1110);
            qaLogMapper.insert(row);

            QaLog r = qaLogMapper.selectByEvalRun(RUN).get(0);
            assertEquals(r.getTotalTokens(), r.getPromptTokens() + r.getCompletionTokens(),
                    "★★ 这个等式是报告侧判断「读路径有没有坏」的免费判据：\n"
                            + "   实测全库 17 轮，只要有 total_tokens 就两列都在、且相加恰好相等。\n"
                            + "   所以 `0 + 0 != 386591` 本身就证明【读路径漏了列】，"
                            + "不可能是数据如此。\n"
                            + "★ 注意它守的是【我们写的这一行】——真数据里它是全称成立的");
        }
    }

    // ============================================================
    // 四、★ 阶段 9.2：intent_plan（同一个坑的第三次预防）
    // ============================================================

    @Nested
    @DisplayName("四、★ 阶段 9.2 的 intent_plan")
    class IntentPlanColumn {

        /**
         * ★★ 这一条存在的理由，就是「同一个坑已经踩过两次」。
         *
         * <p>前两次（7.5 漏 {@code question}、7.6 漏两个 token 列）的症状都是
         * <b>一个看起来合法的 0/null</b>，而且都不指向这条查询。
         *
         * <p>9.2 加「检索决策准确率」时，漏了这一列的后果是：
         * 报告里那一格显示 <b>0 次「不该检索却检索了」</b> ——
         * 一个看起来像好消息的数。
         */
        @Test
        @DisplayName("★★ intent_plan 取得回来 —— 它是「检索决策准确率」的唯一数据源")
        void intentPlanRoundTrip() {
            QaLog row = new QaLog();
            row.setTraceId("T-PROJ-" + System.nanoTime());
            row.setQuestion("PROJ-" + SEQ.incrementAndGet());
            row.setEvalRunId(RUN);
            row.setEvalQuestionNo("P-" + SEQ.get());
            row.setIntent("OUT_OF_SCOPE");
            row.setIntentPlan("""
                    {"v":1,"intent":"OUT_OF_SCOPE","retrieve":false,\
                    "gate":"NONE_INTENT","missing":[],"shape":"JSON"}""");
            qaLogMapper.insert(row);

            QaLog back = qaLogMapper.selectByEvalRun(RUN).get(0);

            assertNotNull(back.getIntentPlan(),
                    "★★★ 读成 null 的话，报告侧算「检索决策准确率」时"
                            + "每一行都会被 `!= null` 挡掉 —— 分子分母一起变 0，"
                            + "而 0 次「不该检索却检索了」看起来是个好消息");
            assertTrue(back.getIntentPlan().contains("NONE_INTENT"),
                    "★ JSONB 往返会重排键、改空白，所以只能断言内容，不能断言文本");
            assertTrue(back.getIntentPlan().contains("\"shape\""),
                    "★★ shape 是「门控到底有没有生效」的唯一判据，它必须读得回来");
        }

        @Test
        @DisplayName("★★ 反对照：没写 intent_plan 的行读回来是 null，不是 '{}'")
        void absentPlanStaysNull() {
            QaLog row = insertRow();

            QaLog back = qaLogMapper.selectByEvalRun(RUN).stream()
                    .filter(r -> r.getId().equals(row.getId()))
                    .findFirst().orElseThrow();

            assertNull(back.getIntentPlan(),
                    "★ 「没发生」和「发生了但是空的」必须能区分开 —— "
                            + "查询侧 `WHERE intent_plan IS NOT NULL` 靠的就是这一点");
        }
    }

    // ============================================================
    // 五、★ 阶段 9.6：tool_calls + session_id（两个「到期」的豁免）
    // ============================================================

    /**
     * ★★ 这两列和上面几列的<b>来路不同</b>：它们不是「新加的字段忘了取」，
     * 而是<b>原来被刻意排除、9.6 到期收回</b>的。
     *
     * <p>所以这里除了往返，还要证明一件事：<b>收回豁免是有后果的</b> ——
     * 如果只把 {@code NOT_SELECTED} 里那两行删掉而没往投影里加列，
     * 一、二的测试会红；反过来，够了列却没删豁免，二、的反对照会红。
     * 两个方向都有东西守着，这就是「一份受审的清单」该有的样子。
     */
    @Nested
    @DisplayName("五、★ 阶段 9.6 的 tool_calls 与 session_id")
    class ToolCallsAndSession {

        /**
         * ★★ 漏了这一列的症状是<b>一个看起来像好消息的 0</b>。
         *
         * <p>「工具选择准确率」问的是「该调工具的题，模型调了没有」。
         * 它读不到 {@code tool_calls} 时，每一行都被判成「一次都没调」——
         * 而报告上那一格显示的是<b>「工具调用率 0%」</b>。
         * 读者会去查模型为什么不会用工具，而真正坏的是取数。
         */
        @Test
        @DisplayName("★★ tool_calls 取得回来 —— 它是「工具选择准确率」的唯一数据源")
        void toolCallsRoundTrip() {
            QaLog row = new QaLog();
            row.setTraceId("T-PROJ-" + System.nanoTime());
            row.setQuestion("PROJ-" + SEQ.incrementAndGet());
            row.setEvalRunId(RUN);
            row.setEvalQuestionNo("P-" + SEQ.get());
            row.setIntent("ORDER_LOGISTICS");
            row.setToolCalls("""
                    [{"round":1,"tool":"query_order_status","isError":false,\
                    "detail":"chars=87"}]""");
            qaLogMapper.insert(row);

            QaLog back = qaLogMapper.selectByEvalRun(RUN).get(0);

            assertNotNull(back.getToolCalls(),
                    "★★★ 读成 null 的话，报告里每一行都算「一次都没调工具」——"
                            + " 那一格会显示「工具调用率 0%」，"
                            + "看起来像【模型不会用工具】，而坏的是取数");
            assertTrue(back.getToolCalls().contains("query_order_status"),
                    "★ JSONB 往返会重排键、改空白，所以只能断言内容，不能断言文本");
            assertTrue(back.getToolCalls().contains("isError"),
                    "★ isError 那一格决定「工具是答了还是失败了」，报告要数它");
        }

        /**
         * ★★ 漏了这一列的症状是<b>一个看起来像题库写错了的 0</b>。
         *
         * <p>一轮多轮题把 N 轮提交在<b>同一个题号</b>下，而 {@code qa_log} 没有轮次列。
         * 分组要靠会话：每一次重复新建一个会话，所以「同题号下同一 session 的这几行」
         * 就是一次完整的多轮尝试。这一列读成 null 时，所有行归成一组 ——
         * 报告会显示「多轮题每一道都只跑了一轮」，而真正的病因在投影。
         */
        @Test
        @DisplayName("★★ session_id 取得回来 —— 多轮的轮次边界只能靠它还原")
        void sessionIdRoundTrip() {
            long sessionId = 9_600_000L + SEQ.incrementAndGet();
            QaLog row = new QaLog();
            row.setTraceId("T-PROJ-" + System.nanoTime());
            row.setQuestion("PROJ-" + SEQ.incrementAndGet());
            row.setEvalRunId(RUN);
            row.setEvalQuestionNo("P-" + SEQ.get());
            row.setSessionId(sessionId);
            qaLogMapper.insert(row);

            QaLog back = qaLogMapper.selectByEvalRun(RUN).get(0);

            // ★ 这一列【没有外键】—— 和 chat_session.user_id 不同（那一列有 FK，
            //   因为它是身份，会因为伪造的头把会话写崩）。所以这里不需要先造一个会话。
            assertNotNull(back.getSessionId(),
                    "★★★ 读成 null 的话，多轮题的全部行会归成同一组 ——"
                            + " 报告显示「每道多轮题都只跑了一轮」，那个 0 看起来像题库写错了");
            assertEquals(sessionId, back.getSessionId().longValue(),
                    "★ 原样读回来。分组靠的是「相等的两个值」，不是「某个范围」");
        }

        @Test
        @DisplayName("★★ 反对照：没调工具的行读回来是 null，不是 []")
        void absentToolCallsStaysNull() {
            // ★ 先插、再查：反过来的话那一行还没落库，findFirst 会抛在空流上，
            //   而失败信息会指向「没有行」而不是「这一列读到了什么」。
            QaLog inserted = insertRow();
            QaLog back = qaLogMapper.selectByEvalRun(RUN).stream()
                    .filter(r -> r.getId().equals(inserted.getId()))
                    .findFirst().orElseThrow();

            assertNull(back.getToolCalls(),
                    "★ 「一次都没调」和「调了但是空的」必须能区分开 ——"
                            + " 前者是【模型没用工具】，后者是【工具循环跑了但一次都没发出】，"
                            + "两者的修法相反。同 tool_calls / references / intent_plan 那条全库约定");
        }
    }
}
