package com.xbla.rag.rag.chunk;

import com.xbla.rag.config.KbProperties;

/**
 * 切分参数。
 *
 * <p>做成独立的 record 而不是让切分器直接依赖 {@link KbProperties}，
 * 是为了让切分算法<b>不依赖 Spring</b> —— 单测可以直接 new 一个出来，
 * 不用起容器、不用读 YAML，毫秒级跑完。
 *
 * <p>这是「配置类负责读 YAML，领域对象负责算逻辑」的分工。
 * 从配置转换的入口见 {@link #from(KbProperties.Chunking)}。
 *
 * @param maxChars       单个切片的目标最大字符数
 * @param overlapChars   相邻切片的重叠字符数
 * @param minChars       低于这个长度尝试并入同一章节的上一篇
 * @param mergeThreshold 章节整体短于这个值时不再切分，整段作为一片
 * @param breakChars     优先在这些字符<b>之后</b>断开。空串表示不做断句优化，
 *                       一律硬切在 maxChars 处
 * @param includeHeadingInContent 是否把标题路径拼在切片正文的最前面。
 *                       <b>默认 true，这是有意的</b>，理由是检索质量：
 *                       用户问「退货政策是什么」，「退货」这个词可能<b>只出现在标题里</b>，
 *                       正文写的是「签收后 7 天内可申请」。
 *                       标题不参与向量化的话，这个切片就丢了最关键的语义线索。
 *                       代价是每个切片重复一次路径（约 4% 的长度开销）。
 *                       阶段 7 可以拿这个开关做 A/B 对比。
 */
public record TextChunkingOptions(
        int maxChars,
        int overlapChars,
        int minChars,
        int mergeThreshold,
        String breakChars,
        boolean includeHeadingInContent) {

    public TextChunkingOptions {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars 必须为正数，实际是 " + maxChars);
        }
        if (overlapChars < 0) {
            throw new IllegalArgumentException("overlapChars 不能为负数，实际是 " + overlapChars);
        }
        // ★ 这条校验不是形式主义，它挡住的是一个会让程序【死循环】的参数组合。
        //   滑动窗口的步进 = maxChars - overlapChars。
        //   两者相等时步进为 0，窗口永远停在原地，while 循环永不结束 ——
        //   现象是「入库任务卡住不动、CPU 打满、没有日志」，极难定位。
        //   在构造期拦住，比在死循环里 debug 便宜一万倍。
        if (overlapChars >= maxChars) {
            throw new IllegalArgumentException(
                    "overlapChars(" + overlapChars + ") 必须小于 maxChars(" + maxChars
                            + ")，否则滑动窗口步进为 0 会死循环");
        }
        if (minChars < 0) {
            throw new IllegalArgumentException("minChars 不能为负数");
        }
        breakChars = breakChars == null ? "" : breakChars;
    }

    /** 从配置类转换。配置项改了不用改代码，这里自动跟着变 */
    public static TextChunkingOptions from(KbProperties.Chunking props) {
        return new TextChunkingOptions(
                props.getMaxChars(),
                props.getOverlapChars(),
                props.getMinChars(),
                props.getMergeThresholdChars(),
                props.getBreakChars(),
                props.isIncludeHeadingInContent());
    }

    /** 滑动窗口的步进长度。恒 &gt; 0，由构造器里的校验保证 */
    public int step() {
        return maxChars - overlapChars;
    }
}
