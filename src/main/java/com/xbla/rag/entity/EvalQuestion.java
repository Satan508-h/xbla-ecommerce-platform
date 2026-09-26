package com.xbla.rag.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xbla.rag.common.handler.LongArrayTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 评测题实体（人工标注的评测集）。对应 {@code eval_question} 表。
 *
 * <p>CLAUDE.md 明确要求：<b>评测的标准答案由人工标注，不用另一个模型生成</b>——
 * 否则评测就变成了「模型给自己打分」。
 */
@Data
// ★ autoResultMap = true 不能漏！
//   Long[] 字段用了自定义 TypeHandler，而自定义 Handler 要生效，
//   MyBatis-Plus 必须为这个实体生成 resultMap。
//   不开这个开关的话：写入正常（走了 Handler），但读取时不会走 Handler，
//   表现为「存进去了但查出来是 null」——这种一半能跑的 bug 最难排查。
@TableName(value = "eval_question", autoResultMap = true)
public class EvalQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String questionNo;

    private String question;

    /** ★ 人工标注的正确意图，不是模型预测的 */
    private String intent;

    /** 人工撰写的标准答案 */
    private String expectedAnswer;

    /**
     * 应当被召回的切片 ID 列表，用于计算召回命中率。
     *
     * <p>数据库列类型是 PostgreSQL 数组 {@code BIGINT[]}，
     * Java 侧用 {@code Long[]} 对应。pgjdbc 驱动原生支持数组类型的读写，
     * MyBatis 会把它当 {@code java.sql.Array} 处理。
     *
     * <p>之所以用数组而不是建关联表：这里只是「一组 ID」，
     * 不需要携带额外属性（不像 user_coupon 要记领取时间、状态）。
     * 数组足够表达，查询也直观：{@code WHERE 12 = ANY(expected_chunk_ids)}
     *
     * <p>★ 必须指定 {@code typeHandler}：MyBatis 内置了 {@code Object[]} 的处理器
     * 但<b>没有 {@code Long[]} 的</b>，不指定会报
     * "Type handler was null ... for the javaType ([Ljava.lang.Long;)"。
     */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private Long[] expectedChunkIds;

    @TableField(typeHandler = LongArrayTypeHandler.class)
    private Long[] expectedDocIds;

    private String category;

    /** 难度：1易 2中 3难 */
    private Integer difficulty;

    /**
     * 这道题属于哪一套题集（阶段 7）。
     *
     * <p>★ 取值（三套）：{@code baseline}（阶段 4 的 20 道反向构造题）/
     * {@code stage7}（阶段 7 的 ~150 道单轮题）/ {@code stage7-multi}（多轮追问题）。
     *
     * <p>★★ <b>三套的口径不同，不能混在一起算指标</b>：
     * 前两套是单轮的（一题一 gold），多轮那套的「正确答案」依赖上下文。
     * 报告里每张表都要写清它统计的是哪一套。
     *
     * <p>⚠️ 它取代了 V5 的 {@code is_baseline}（boolean，恒为 true）。
     * 取代的理由写在 V11 迁移里，一句话：<b>boolean 表达不了三套题，
     * 而一个恒为 true 的列不携带信息</b>。
     *
     * <p>★ 列上<b>没有 DEFAULT</b>：漏写会 INSERT 失败，而不是被静默填成某一套。
     */
    private String questionSet;

    /**
     * 这道题<b>是怎么造出来的</b>。
     *
     * <p>取值：{@code reverse_constructed}（先看切片、再写问题 —— 阶段 4 的 20 题）/
     * {@code corpus_driven_manual}（读语料后按用户口吻手写 —— 阶段 7 的 150 题）。
     *
     * <p>★ 它必须是一列，因为<b>两种构造方式有各自已知的偏差</b>
     * （见 {@code docs/06} §2.2），而报告里必须把偏差讲清楚。
     * 一份读起来很专业的报告如果不写「这些题是怎么来的」，
     * 它的数字可以被解释成完全不同的东西。
     */
    private String source;

    private String annotatedBy;

    /**
     * 工具类题目要用哪个用户身份去调 MCP 工具（阶段 7 批次 5）。
     *
     * <p>★★ <b>身份只能从这里读，由跑题器写进 {@code X-Xbla-User-Id} 请求头</b> ——
     * 绝不能变成工具的参数（ADR-054：工具参数是<b>模型填的</b>，
     * 加一个 {@code user_id} 参数就是模型可控的越权入口）。
     *
     * <p>⚠️ <b>可空</b>：知识库题与兜底题不需要身份，给它们填一个值反而是噪声
     * （并会让人以为「所有题都有用户」）。
     *
     * <p>★ 不加这一列的后果<b>不是报错而是静默</b>：工具收到空身份 →
     * 返回「未登录」→ 模型如实转述 → 报告显示「订单类问题 0% 正确」，
     * 而真正的原因是评测自身没带身份 —— 与「模型不行」在报告上长得一模一样。
     */
    private Long userId;

    /**
     * ★ 显式声明「这道题<b>本来就没有</b>检索目标」（工具调用 / 兜底 / 澄清）。
     *
     * <p>阶段 7 的题库里有 28 道这样的题（工具 15 + 兜底 7 + 澄清 6）——
     * 它们的正确答案不在知识库里，检索指标的分母里也不该有它们
     * （docs/06：空 gold 题单独一桶）。
     *
     * <p>★★ <b>为什么要单独一列，而不是「{@code expectedChunkIds} 为空即可」</b>：
     * 「空 gold」与「漏写锚点」在数据上长得一模一样。写题时漏一个
     * {@code anchors} 段，那道题会<b>静默退出检索指标</b> ——
     * 题数少一道看不出来、指标也不会错，只是那道题再也不测任何东西了。
     * 所以加载器要求：没有锚点的题<b>必须</b>显式声明这个字段，
     * 否则当场报错（见 {@code EvalQuestionLoader#parseOne}）。
     *
     * <p>★ 报告端点用 {@code WHERE NOT expect_no_retrieval} 排除它们，
     * 而不必去猜「空数组是声明的还是写错了」—— <b>让数据自己说清楚</b>。
     */
    private Boolean expectNoRetrieval;

    /**
     * <b>多轮题第 1 轮</b>该不该被澄清闸门反问（阶段 9.6a）。
     *
     * <p>★★ <b>三态</b>：
     *
     * <pre>
     *   null   = 没显式声明 ⇒ 报告判它「不可判」，排除出 ① 的分子分母
     *   true   = 这一轮该被反问
     *   false  = 这一轮不该被反问
     * </pre>
     *
     * <h3>★ 为什么需要它：多轮题的「首轮该不该反问」原本没有 gold</h3>
     *
     * <p>9.6 的多轮澄清判据第 ① 层是「首轮反问发生了没」，而题库里没有一个字段
     * 回答得了它 —— 因为 {@link #intent} 的语义<b>随题库类型而变</b>：
     *
     * <pre>
     *   单轮题：intent 就是这一轮的意图 ⇒ 是不是澄清码就是答案   （推得出来，不必标）
     *   多轮题：intent 描述的是【末轮】（见 standaloneQuestion） （推不出来，必须标）
     * </pre>
     *
     * <p>实测（2026-09-26）：8 道多轮澄清题的首轮反问率 12/24 = 50%，
     * 而其中 <b>3 道题的首轮不反问是对的</b>（问的是退货政策，本来就不依赖商品）。
     * <b>没有这一列就分不出「系统少反问了一次」和「我的场景前提不成立」</b> ——
     * 而这两件事一个要改闸门、一个要改题。
     *
     * <h3>★★ 为什么不是 {@code NOT NULL DEFAULT false}（同 {@link #expectNoRetrieval}）</h3>
     *
     * <p>那一列的默认值对<b>每一道题</b>都恰好成立，所以能当默认值。
     * 这一列不行 —— 它会把「<b>没标注</b>」和「<b>标注为不该反问</b>」合并成一个值，
     * 而那正是全库那条约定禁掉的事（「没有发生」和「发生了但是空的」必须能区分开）。
     * ⚠️ 而且合并出来的那个值<b>还是错的</b>：一道该反问而没标的题会静默地
     * 变成一次「不该反问却反问了」的假阳 —— 一个指向反方向的结论。
     *
     * <p>★★ <b>报告不做任何回落。</b> 唯一能回落的来源是 gold intent
     * （单轮题上恰好正确），而多轮澄清那一段只处理多轮题 —— 所以这一格
     * 必须由人<b>显式写出来</b>。
     *
     * <h3>⚠️ 为什么挂 {@code updateStrategy = ALWAYS}（全实体唯一的例外）</h3>
     *
     * <p>MyBatis-Plus 的 {@code updateById} 默认策略是 {@code NOT_NULL} ——
     * <b>null 字段被静默跳过</b>。而「把这一格从 yml 里删掉」正是这个字段的
     * <b>正常用法之一</b>（想退回回落值）。不挂 ALWAYS 的话：
     *
     * <pre>
     *   从 yml 删掉 expect_clarify → 库里的旧值【不会被清掉】
     *   → 报告继续用那个陈旧的显式值 → 文件与库不一致，而没有任何东西会报错
     * </pre>
     *
     * <p>同 9.4 的坑 41（清空一列必须显式 {@code set}，不能用 {@code updateById}）。
     * ★ 这里用注解而不是在那个 upsert 里手写 {@code lambdaUpdate}，是因为
     * <b>{@code EvalQuestionMapper.updateById} 全仓只有加载器一个调用点</b> ——
     * 注解的作用域因此是可枚举的。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Boolean expectClarify;

    /**
     * 多轮题的完整轮次（JSON 字符串数组，按时间顺序）。单轮题为 {@code null}。
     *
     * <p>数据库列是 {@code JSONB}，Java 侧是 {@code String} ——
     * 和 {@code qa_log.retrieval_detail} / {@code tool_calls} 同一条约定
     * （JSONB 存、String 进出）。
     *
     * <p>★ <b>最后一轮就是被测量那一轮</b>，加载器把它取出来填进 {@link #question} ——
     * 所以题库里<b>不写</b> {@code question} 字段（两个都写会加载失败，见
     * {@code EvalQuestionLoader#parseOne}）。
     *
     * <p><b>为什么多轮题要单独一套</b>：分类与检索<b>都</b>只拿到当前这一句
     * （{@code ChatServiceImpl:177} / {@code :213}），所以「追问轮的意图」
     * 在单轮口径下不是一个可测量的量。这一列让「上一句话建立起来的那个东西
     * 还在不在」第一次变成可测量的东西。
     */
    private String turns;

    /**
     * ★ 「把最后一轮单独说该怎么说」—— 本题的 {@code intent} / {@code anchors} /
     * {@code expectedAnswer} <b>全部按这一句标</b>。
     *
     * <p>理由：用户真正想问的是这个意思，追问句只是它在上下文里的一种省略说法。
     * 若反过来按「分类器看到的那七个字」标 gold，这一套题就退化成单轮题集的副本。
     *
     * <p>⚠️ 它是给跑题器读的 <b>gold</b>（「单独问 vs 追问」的对照），不是注释 ——
     * 所以是列，不是 {@code notes} 里的一段散文。
     *
     * <p>★ 与 {@link #turns} <b>同生同死</b>（数据库有一条 CHECK 强制）：
     * 只有 turns 没有它就是「这题没有 gold」，只有它没有 turns 就是
     * 「一道写错了字段名的单轮题」。两种半成品都不留。
     */
    private String standaloneQuestion;

    private OffsetDateTime annotatedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
