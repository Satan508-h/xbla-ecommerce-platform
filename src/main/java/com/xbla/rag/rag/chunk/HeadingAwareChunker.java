package com.xbla.rag.rag.chunk;

import com.xbla.rag.rag.parse.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 「标题层级 + 定长 + 重叠窗口」三合一切分器。
 *
 * <h2>一、为什么要三件事组合，单独用哪一个都不行</h2>
 *
 * <p><b>只用定长切分</b>：会在章节中间下刀，把「退货流程」和「发货时效」
 * 切进同一个切片。向量把这个切片的两段不同语义「平均」在一起，
 * 结果是跟谁的相似度都不高 —— 检索时两边的问题都匹配不上。
 *
 * <p><b>只用标题切分</b>：遇到一个两千字的章节就傻眼了。
 * 整章一个向量，等于把整章内容平均成一个点，精度比定长切分还差。
 *
 * <p><b>不用重叠窗口</b>：答案横跨切分点时两边都只有半句话。
 * 详见 docs/03 的图示。
 *
 * <h2>二、算法</h2>
 * <pre>
 *   ① 按标题切段
 *      [h1 售后政策][正文A][h2 退货][正文B][正文C][h2 换货][正文D]
 *              ↓
 *      section1 path="售后政策"            bodies=[A]
 *      section2 path="售后政策 &gt; 退货"     bodies=[B, C]
 *      section3 path="售后政策 &gt; 换货"     bodies=[D]
 *
 *   ② 段内滑动窗口（窗口 = maxChars，步进 = maxChars - overlapChars）
 *      长度 ≤ mergeThreshold 的段不切，整段作为一片
 *
 *   ③ 每段的最后一片过短时，并入前一片（避免产生几十字的噪声切片）
 * </pre>
 *
 * <h2>三、★ 类不变量：段落边界永远不被跨越</h2>
 *
 * <p>滑动窗口<b>只在单个 section 内部进行</b>，绝不会跨越 section 边界。
 * 这是本类最重要的一条约束 —— 它保证了「一个切片里的内容在语义上是同源的」。
 * 反过来说，如果切片跨越了两个章节，那前面辛苦识别标题就白做了。
 *
 * <h2>四、纯计算，不依赖 Spring 容器</h2>
 *
 * <p>虽然标了 {@code @Component} 以便注入，但类里没有任何 Spring 的注入点 ——
 * 所有参数都是方法参数。这样单测可以直接 {@code new} 一个出来，
 * 毫秒级验证几十种边界情况。切分算法是整个入库链路里
 * <b>最需要密集测试</b>的一环（边界条件极多，且错了不报错，只是检索质量变差）。
 */
@Component
public class HeadingAwareChunker implements TextChunker {

    private static final Logger log = LoggerFactory.getLogger(HeadingAwareChunker.class);

    /** 标题路径的分隔符 */
    private static final String PATH_SEPARATOR = " > ";

    /**
     * 为了找断句点最多往回退的比例（相对窗口长度）。
     *
     * <p>比如窗口是 500 字，最多往回退 30% 也就是 150 字去找标点。
     * 不设上限的话，一个 500 字的窗口如果只有开头有个句号，
     * 就会退到那里，切出一个 60 字的碎片。
     */
    private static final int MAX_BACKTRACK_PERCENT = 30;

    @Override
    public List<TextChunk> chunk(List<TextBlock> blocks, TextChunkingOptions options, String documentTitle) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }

        List<Section> sections = splitIntoSections(blocks, documentTitle);

        List<TextChunk> result = new ArrayList<>();
        for (Section section : sections) {
            // 章节内先按 standalone 边界分组，每组独立做滑动窗口
            for (List<String> group : groupByStandalone(section.bodies())) {
                String text = String.join("\n", group);
                if (text.isBlank()) {
                    continue;
                }
                for (String piece : splitSection(text, options)) {
                    String content = decorate(piece, section.headingPath(), options);
                    result.add(new TextChunk(result.size(), content, section.headingPath(), content.length()));
                }
            }
        }

        log.debug("切分完成 sections={} chunks={} avgChars={}",
                sections.size(), result.size(),
                result.isEmpty() ? 0 : result.stream().mapToInt(TextChunk::charCount).sum() / result.size());
        return result;
    }

    // ================================================================
    // ① 按标题切段
    // ================================================================

    /**
     * 一个章节：一条标题路径 + 该路径下所有正文块。
     *
     * <p>存 {@link TextBlock} 而不是 {@code String}，是因为块上带着
     * {@code standalone} 标记 —— 切分时需要靠它决定哪些块不能合并。
     * 提前把块拍平成字符串的话，这个信息就永久丢了。
     *
     * @param headingPath 标题层级路径。可能是 null（整份文档一个标题都没有，也没传文档标题）
     */
    private record Section(String headingPath, List<TextBlock> bodies) {
    }

    /** 标题栈里的一项 */
    private record HeadingEntry(int level, String text) {
    }

    /**
     * 把文本块序列按标题拆成若干 section。
     *
     * <p>用<b>栈</b>维护标题层级：遇到 level=2 的标题时，
     * 把栈里所有 level ≥ 2 的弹掉，再压入新的。
     * 这样栈里剩下的就是当前标题的<b>全部祖先</b>，拼起来正好是路径。
     *
     * <p>举例 —— 依次遇到 h1(A)、h2(B)、h3(C)、h2(D)：
     * <pre>
     *   h1(A) → 栈 [A]              path = "A"
     *   h2(B) → 栈 [A, B]           path = "A &gt; B"
     *   h3(C) → 栈 [A, B, C]        path = "A &gt; B &gt; C"
     *   h2(D) → 弹掉 C 和 B（level ≥ 2），栈 [A, D]   path = "A &gt; D"  ← 回到二级
     * </pre>
     */
    private List<Section> splitIntoSections(List<TextBlock> blocks, String documentTitle) {
        Deque<HeadingEntry> stack = new ArrayDeque<>();

        // ★ 文档标题作为路径的第一层预置进栈。
        //   如果正文里有真正的 h1，它会被「弹掉 level ≥ 1」的规则替换掉，
        //   所以不会出现正文标题被压在文档标题下面的情况。
        //   而如果正文一个标题都没有，路径至少还有文档名 ——
        //   比 null 多给 LLM 一点上下文
        if (documentTitle != null && !documentTitle.isBlank()) {
            stack.push(new HeadingEntry(1, documentTitle.trim()));
        }

        List<Section> sections = new ArrayList<>();
        List<TextBlock> currentBodies = new ArrayList<>();
        String currentPath = pathOf(stack);

        for (TextBlock block : blocks) {
            if (block.isHeading()) {
                // 收尾上一个 section
                if (!currentBodies.isEmpty()) {
                    sections.add(new Section(currentPath, List.copyOf(currentBodies)));
                    currentBodies.clear();
                }
                // 维护标题栈
                while (!stack.isEmpty() && stack.peek().level() >= block.level()) {
                    stack.pop();
                }
                stack.push(new HeadingEntry(block.level(), block.text()));
                currentPath = pathOf(stack);
            } else {
                currentBodies.add(block);
            }
        }

        if (!currentBodies.isEmpty()) {
            sections.add(new Section(currentPath, List.copyOf(currentBodies)));
        }
        return sections;
    }

    /**
     * 把章节内的正文块按 {@code standalone} 边界分组。
     *
     * <pre>
     *   输入: [段落A][段落B][表格行1][表格行2][段落C]
     *   输出: [A,B] [行1] [行2] [C]
     * </pre>
     *
     * <p>规则很简单：<b>standalone 的块自成一派，两边的普通块也不能和它合并。</b>
     *
     * <p>为什么需要这一步：同一个章节里的散文段落合并起来是好事（上下文更完整），
     * 但表格行不是 —— 每一行是一个自包含的记录。
     * 20 行 FAQ 合并成一个切片的话，那个切片的向量会是 20 个不同主题的「平均值」，
     * 用户问其中任何一个问题，相似度都被稀释。
     */
    private static List<List<String>> groupByStandalone(List<TextBlock> bodies) {
        List<List<String>> groups = new ArrayList<>();
        List<String> buffer = new ArrayList<>();

        for (TextBlock block : bodies) {
            if (block.standalone()) {
                flush(groups, buffer);              // 先把攒着的普通块收尾
                groups.add(List.of(block.text()));  // 自己单独成一组
            } else {
                buffer.add(block.text());
            }
        }
        flush(groups, buffer);
        return groups;
    }

    private static void flush(List<List<String>> groups, List<String> buffer) {
        if (!buffer.isEmpty()) {
            groups.add(List.copyOf(buffer));
            buffer.clear();
        }
    }

    /** 把标题栈自底向上拼成路径。栈空时返回 null */
    private static String pathOf(Deque<HeadingEntry> stack) {
        if (stack.isEmpty()) {
            return null;
        }
        // ArrayDeque 的迭代顺序是「栈顶 → 栈底」（因为 push 是 addFirst），
        // 而路径要「祖先 → 后代」，所以用 descendingIterator 反转
        StringBuilder sb = new StringBuilder();
        var it = stack.descendingIterator();
        while (it.hasNext()) {
            if (sb.length() > 0) {
                sb.append(PATH_SEPARATOR);
            }
            sb.append(it.next().text());
        }
        return sb.toString();
    }

    // ================================================================
    // ② 段内滑动窗口
    // ================================================================

    /**
     * 把一个 section 的正文切成若干片。
     *
     * @return 切片文本，已去除首尾空白，<b>顺序与原文一致</b>
     */
    private List<String> splitSection(String text, TextChunkingOptions options) {
        // 整段都装得下就不切。避免切出「500 字 + 100 字」这种尾巴，
        // 那 100 字单独占一个向量，性价比很低
        if (text.length() <= options.mergeThreshold()) {
            return List.of(text.trim());
        }

        // 先在字符下标层面算出所有窗口，再做合并 ——
        // 直接生成字符串的话，最后那片要「并入前一片」就得反过来拼字符串，很难写对
        List<int[]> windows = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int hardEnd = Math.min(start + options.maxChars(), text.length());
            int end = (hardEnd == text.length())
                    ? hardEnd
                    : findBreakPoint(text, start, hardEnd, options.breakChars());

            windows.add(new int[]{start, end});
            if (end >= text.length()) {
                break;
            }

            start = end - options.overlapChars();
            // ★ 第二道防死循环保险。
            //   理论上 step() > 0 已经保证了窗口一定前进，但这里再加一层：
            //   万一 findBreakPoint 因为某些畸形输入返回了比 start 还小的值，
            //   没有这道保险就是无限循环。宁可切出来的片有点重叠，也不能卡死
            if (windows.size() >= 2 && start <= windows.get(windows.size() - 2)[0]) {
                start = windows.get(windows.size() - 2)[0] + 1;
            }
        }

        // ★ 最后一片过短 → 并入前一片。
        //   过短的片段在检索里是纯噪声：十几字的文本跟谁的相似度都不低，
        //   容易挤进 TopK 却毫无信息量
        if (windows.size() >= 2) {
            int[] last = windows.get(windows.size() - 1);
            if (last[1] - last[0] < options.minChars()) {
                windows.remove(windows.size() - 1);
                windows.get(windows.size() - 1)[1] = last[1];
            }
        }

        List<String> pieces = new ArrayList<>(windows.size());
        for (int[] w : windows) {
            String piece = text.substring(w[0], w[1]).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
        }
        return pieces;
    }

    /**
     * 在 {@code (start, hardEnd]} 区间里往回找一个断句点，尽量不把句子拦腰截断。
     *
     * <p>优先切在标点<b>之后</b>（如「…可申请。<b>|</b>退货需保持…」），
     * 这样断点落在句末，读者不会看到半句话。
     *
     * @return 实际的结束下标（不含）。找不到合适标点时就是 {@code hardEnd}
     */
    private static int findBreakPoint(String text, int start, int hardEnd, String breakChars) {
        if (breakChars.isEmpty()) {
            return hardEnd;
        }
        // 回溯下限：为了找一个标点，最多牺牲窗口长度的 30%
        int backtrack = Math.max(1, (hardEnd - start) * MAX_BACKTRACK_PERCENT / 100);
        int minEnd = Math.max(start + 1, hardEnd - backtrack);

        for (int i = hardEnd - 1; i >= minEnd; i--) {
            if (breakChars.indexOf(text.charAt(i)) >= 0) {
                return i + 1;   // 切在标点之后
            }
        }
        return hardEnd;
    }

    // ================================================================
    // ③ 装饰：把标题路径拼到正文前面
    // ================================================================

    /**
     * 把标题路径拼在正文最前面。
     *
     * <p><b>为什么值得这么做</b>：倾斜一点 token 预算，换来的是检索质量的提升 ——
     * 用户问「退货政策是什么」，「退货」这个词可能只出现在标题里。
     * 正文「签收后 7 天内可申请」里根本没有「退货」二字，
     * 标题不参与向量化的话，这个切片跟问题的相似度会低得莫名其妙。
     *
     * <p>格式上用换行分隔而不是直接连在一起，是为了让嵌入模型
     * 能区分「这段是标题、下面才是正文」。
     */
    private static String decorate(String body, String headingPath, TextChunkingOptions options) {
        if (!options.includeHeadingInContent() || headingPath == null || headingPath.isBlank()) {
            return body;
        }
        return headingPath + "\n" + body;
    }
}
