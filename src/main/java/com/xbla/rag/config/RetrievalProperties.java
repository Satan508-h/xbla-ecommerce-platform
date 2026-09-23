package com.xbla.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索链路的可调参数，对应配置前缀 {@code xbla.rag}。
 *
 * <p>注册方式：启动类上的 {@code @ConfigurationPropertiesScan} 会自动扫描
 * {@code com.xbla.rag} 包下所有带 {@code @ConfigurationProperties} 的类，
 * 不需要在这里写 {@code @Component}。
 *
 * <h2>为什么这些值全部外置成配置</h2>
 *
 * <p>它们都是<b>阶段 7 要做 A/B 对比的旋钮</b>：召回条数、RRF 的 k 值、
 * 两路权重、重排候选集大小。而 A/B 对比的前提是
 * <b>「同一份代码、只改配置」</b> —— 如果把某个值硬编码在类里，
 * 对比实验就要改代码、重新编译、重新部署，
 * 而且没法在评测报告里说清「这次和上次到底差在哪」。
 *
 * <p>这份配置会被<b>快照进每一份评测报告</b>，见 {@code RetrievalDetailBuilder} 与基线报告格式。
 *
 * <h2>默认值的依据</h2>
 *
 * <p>全部是<b>初始假设，不是调优结果</b>。每一处都写了理由，
 * 但真正的结论要等阶段 7 的指标出来才作数 —— 这一点必须说清楚，
 * 否则后来的人会把它们当成「经过验证的最优值」。
 */
@Data
@ConfigurationProperties(prefix = "xbla.rag")
public class RetrievalProperties {

    private Retrieve retrieve = new Retrieve();
    private Fuse fuse = new Fuse();
    private Rerank rerank = new Rerank();
    private Rewrite rewrite = new Rewrite();

    /**
     * 召回阶段的参数。
     */
    @Data
    public static class Retrieve {

        /**
         * 向量路召回条数。
         *
         * <p>初始假设 20。定这个数是因为它要明显大于最终送进 prompt 的条数（5），
         * 给融合和重排留出筛选空间；但也不能太大 ——
         * 候选集越大，重排的调用成本越高（重排要对每个候选做一次模型前向计算）。
         */
        private int vectorTopK = 20;

        /** 关键词路召回条数。理由同上 */
        private int keywordTopK = 20;

        /**
         * 最终送进 prompt 的切片数。
         *
         * <p>初始假设 5。既要有足够上下文，又不能把 prompt 塞满 ——
         * 切片越多，无关内容的干扰越大，而且 <b>LLM 的输入 token 是花钱的</b>。
         */
        private int finalTopK = 5;

        /**
         * 查询分词后的词元数上限。
         *
         * <p>用于<b>限制 OR 查询链的长度</b>：{@code ChatAskRequest} 允许 2000 字的问题，
         * 那会拼出上千项的 OR 链，候选集爆炸、{@code ts_rank} 糊成一团。
         *
         * <p>注意这个上限<b>只作用于查询，不作用于文档</b> ——
         * 一个 500 字的切片有约 499 个 bigram，按这个数截断会直接毁掉索引。
         * 两者需求相反，所以在 {@code CjkTokenizer} 里就是两个方法。
         */
        private int maxQueryTokens = 64;

        /**
         * 「按意图下推 {@code doc_type} 范围」这个机制的总开关（阶段 7 新增）。
         *
         * <h3>★ 它存在是为了让一句话变成一次能跑的实验</h3>
         *
         * <p>{@code docs/05} 里写着「意图识别把检索范围收窄到该意图声明的
         * {@code doc_type}」—— 这是一个<b>声明</b>，不是一次<b>测量</b>。
         * 关掉它，同一套题、同一份配置跑两遍，两轮的差异就是这个机制的全部收益
         * （或者全部代价）。这是阶段 7 的 A/B 轴之一。
         *
         * <h3>★ 关掉它【只】影响范围过滤，不影响别的东西</h3>
         *
         * <p>意图分类照跑、{@code qa_log.intent} 照写、澄清闸门照跑、工具路由照跑 ——
         * 变的只有「检索时要不要 {@code WHERE doc_type IN (...)}」这一件事。
         *
         * <p>刻意如此的第二个理由：分类那一侧因此成了 A/B 的<b>不变量检查</b>。
         * 两轮的「意图准确率」应当逐字相同（同题同 prompt 同模型），
         * <b>若不同，说明这一轮有噪声或代码真的动到了分类</b> —— 这比读噪声划算。
         *
         * <h3>⚠️ 关掉时 {@code probe_kb.py --docTypes} 也一起失效</h3>
         *
         * <p>因为开关在 {@code RetrievalPipeline.resolveScope} 里，是<b>所有</b>
         * 范围下推的唯一出口。所以那个「手敲 {@code docTypes} 看检索」的调试手法
         * 在关掉时是个空操作 —— 而它<b>不会报错，只会返回全池的结果</b>。
         *
         * <p>★ 这正是 {@code FilterScope.Reason} 必须新增
         * {@code DISABLED_BY_CONFIG} 而不能复用 {@code NO_DECLARATION} 的原因：
         * 复用的话 trace 会说「调用方没声明范围」，而调用方明明声明了。
         * 你会去查那个调用方，而问题在配置里。
         */
        private ByIntent byIntent = new ByIntent();

        /**
         * 见 {@link Retrieve#byIntent}。
         */
        @Data
        public static class ByIntent {

            /** 默认开。关掉 = 检索不做范围过滤，全部走全池 */
            private boolean enabled = true;
        }
    }

    /**
     * RRF 融合的参数。
     */
    @Data
    public static class Fuse {

        /**
         * RRF 公式里的常数 k：{@code score = Σ weight / (k + rank)}。
         *
         * <p>60 是 RRF 原论文（Cormack et al. 2009）的推荐值，也是业界默认值。
         *
         * <p>它的作用是<b>削弱名次差异</b>：k 越大，第 1 名和第 10 名的得分越接近。
         * k=60 时第 1 名得 {@code 1/61}、第 10 名得 {@code 1/70}，
         * 只差 13% —— 这让「在某一路排第 1」不至于压倒「在两路都排第 5」。
         * 换句话说，RRF 奖励的是<b>被多路共同认可</b>，而不是单路的极端高分。
         */
        private int k = 60;

        /**
         * 向量路的权重。
         *
         * <p>默认 1.0（两路等权）。之所以<b>保留可调</b>：
         * 阶段 7 的 A/B 很可能发现某一路明显更弱（比如关键词路在口语化问题上几乎没用），
         * 那时靠权重就能压下去，不必删掉整条路。
         */
        private double vectorWeight = 1.0;

        /** 关键词路的权重 */
        private double keywordWeight = 1.0;
    }

    /**
     * 重排序的参数。
     */
    @Data
    public static class Rerank {

        /**
         * 是否启用重排序。
         *
         * <p>默认开。重排是本项目检索质量的最大单点提升来源，
         * 而且它<b>失败时会自动回落成 RRF 顺序</b>，不会让链路挂掉。
         */
        private boolean enabled = true;

        /**
         * 送进重排模型的最大候选数。
         *
         * <p>重排要对<b>每一个 (问题, 候选) 对</b>做一次完整的模型前向计算，
         * 所以候选数直接决定延迟和成本。50 是初始假设。
         */
        private int maxCandidates = 50;

        /** 重排后保留条数。留空或为 0 时用 {@code retrieve.final-top-k} */
        private Integer topN = null;
    }

    /**
     * 查询重写与子问题拆分（4.3 / 4.4）的参数。
     */
    @Data
    public static class Rewrite {

        /**
         * ★ <b>默认关闭</b>（用户 2026-09-19 拍板）。
         *
         * <p>理由不是为了省事，而是为了<b>让基线干净</b>：
         * 如果默认开启，阶段 4 记录下来的基线就带着改写，
         * 阶段 7 的 A/B 就无法回答「提升来自改写还是来自别的优化」。
         *
         * <p>关闭时 {@code qa_log.rewritten_question} 写 <b>null</b>，
         * 而不是写原文 —— 否则阶段 7 分不清「没开重写」和「重写后跟原文一样」，
         * 而这是两个不同的实验条件。（同 ADR-010「拿不到就记 NULL」的原则。）
         */
        private boolean enabled = false;
    }
}
