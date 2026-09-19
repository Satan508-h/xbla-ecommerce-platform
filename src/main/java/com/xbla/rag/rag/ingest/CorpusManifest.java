package com.xbla.rag.rag.ingest;

import com.xbla.rag.config.KbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 语料清单：{@code data/corpus/manifest.yml} 的读取器。
 *
 * <h2>一、它解决什么问题</h2>
 *
 * <p>扫描接口原来给<b>整个目录</b>定一个 {@code docType}
 * （{@code POST /api/kb/documents/scan?docType=2}），于是目录里
 * 18 份文件全被标成「售后政策」—— 包括促销活动规则、售后FAQ、
 * 商品说明书、商品导购指南。
 *
 * <p>这个错误<b>极其安静</b>：入库全部成功、状态全部正常、检索也能跑，
 * 只有 {@code doc_type} 一列是错的。而 {@code doc_type} 是
 * <b>阶段 5 意图定向检索的过滤条件</b>（「用户问售后 → 只在 doc_type=2 的切片里搜」）
 * —— 标错的后果是「某一类查询永远返回空」，症状和「知识库里确实没有」
 * 一模一样，极难排查。
 *
 * <h2>二、★ 为什么是显式清单，而不是文件名启发式</h2>
 *
 * <p>启发式（看到「说明书」就判 5）看着省事，但它对<b>不认识的名字会静默给默认值</b>。
 * 加一份「2026春季新品.md」，启发式认不出来 → 落进默认类型 → 又一次安静地标错。
 *
 * <p>显式清单的代价是每加一个文件要写一行，换来的是
 * <b>「这个文件没被声明」这件事本身是可见的</b> —— 扫描时会打 WARN。
 *
 * <h2>三、★ 两种失败，处理方式刻意不同</h2>
 *
 * <table border="1">
 *   <caption>失败语义</caption>
 *   <tr><th>情况</th><th>行为</th><th>理由</th></tr>
 *   <tr><td>清单文件<b>不存在</b></td>
 *       <td>打 WARN，返回空清单，回落到 API 的 {@code ?docType=} 参数</td>
 *       <td>向后兼容：删掉这个文件，扫描仍按老方式工作</td></tr>
 *   <tr><td>清单存在但<b>格式错误</b>（doc_type 越界、缺 name）</td>
 *       <td><b>抛异常</b></td>
 *       <td>写错了就该停下来。安静地忽略某个字段，等于让作者以为它生效了</td></tr>
 * </table>
 *
 * <p>和 {@code EvalQuestionLoader} 同一条原则：<b>能安静漂移的东西必须喊出来。</b>
 */
@Component
public class CorpusManifest {

    private static final Logger log = LoggerFactory.getLogger(CorpusManifest.class);

    /** 清单文件名，固定放在语料目录下 */
    public static final String FILE_NAME = "manifest.yml";

    /**
     * {@code doc_type} 的合法范围。
     *
     * <p>和 {@code kb_document.doc_type} 上的 CHECK 约束
     * （{@code ck_kb_document_doc_type}）保持一致：
     * {@code 1商品详情 2售后政策 3促销规则 4FAQ 5说明书}。
     *
     * <p>在这里也校验一遍，是为了让错误在<b>读清单时</b>就暴露，
     * 而不是等到写库时被数据库拒绝 —— 那时日志里只有一句
     * 「违反 CHECK 约束」，看不出是哪份文件写错了。
     */
    private static final int MIN_DOC_TYPE = 1;
    private static final int MAX_DOC_TYPE = 5;

    private final KbProperties kbProperties;

    public CorpusManifest(KbProperties kbProperties) {
        this.kbProperties = kbProperties;
    }

    /**
     * 一条清单记录。
     *
     * @param fileName 文件名（含后缀，和 {@code Files.list()} 给出的名字一致）
     * @param docType  文档类型 1~5
     * @param note     写给读文件的人看的备注，程序不用
     */
    public record Entry(String fileName, int docType, String note) {
    }

    /**
     * 一次加载的结果。
     *
     * @param present 清单文件是否存在。{@code false} 时 {@code entries} 必为空，
     *                调用方应回落到 API 参数
     * @param entries 文件名 → 记录。<b>保持清单里的书写顺序</b>（{@code LinkedHashMap}），
     *                这样 WARN 日志里报出的「未声明的文件」顺序是稳定的
     */
    public record Manifest(boolean present, Map<String, Entry> entries) {

        /** 查某个文件声明的类型。没声明或清单不存在都返回 empty */
        public Optional<Entry> find(String fileName) {
            return Optional.ofNullable(entries.get(fileName));
        }
    }

    /**
     * 读取清单。
     *
     * <p><b>不缓存</b>：这个方法只在扫目录时调用（人工触发的低频操作），
     * 每次读一遍的好处是改了 YAML 不用重启应用 —— 而这正是把它做成
     * 外部文件的意义。读取开销是几毫秒，不值得为它引入缓存失效逻辑。
     *
     * @throws IllegalStateException 清单存在但内容不合法
     */
    public Manifest load() {
        Path path = Path.of(kbProperties.getCorpusDir(), FILE_NAME);

        if (!Files.isRegularFile(path)) {
            log.warn("语料清单不存在，将回落到 API 的 ?docType= 参数：{}", path.toAbsolutePath());
            return new Manifest(false, Map.of());
        }

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(path)) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取语料清单失败：" + path.toAbsolutePath(), e);
        }

        if (root == null || !(root.get("files") instanceof List<?> rawFiles)) {
            throw new IllegalStateException(
                    "语料清单格式错误，顶层缺少 files 列表：" + path.toAbsolutePath());
        }

        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        for (Object item : rawFiles) {
            if (!(item instanceof Map<?, ?> node)) {
                problems.add("files 里有一项不是键值对：" + item);
                continue;
            }
            String name = trimToNull(node.get("name"));
            if (name == null) {
                problems.add("有一条记录缺少 name 字段");
                continue;
            }
            if (entries.containsKey(name)) {
                // 重复声明必须报错：两条记录谁生效取决于读的顺序，
                // 那正是「安静地不确定」
                problems.add("[" + name + "] 在清单里出现了两次");
                continue;
            }

            Object rawType = node.get("doc_type");
            if (!(rawType instanceof Number number)) {
                problems.add("[" + name + "] 缺少 doc_type 字段（或它不是数字）");
                continue;
            }
            int docType = number.intValue();
            if (docType < MIN_DOC_TYPE || docType > MAX_DOC_TYPE) {
                problems.add("[" + name + "] doc_type=" + docType + " 越界，合法范围是 "
                        + MIN_DOC_TYPE + "~" + MAX_DOC_TYPE + "（1商品详情 2售后政策 3促销规则 4FAQ 5说明书）");
                continue;
            }

            entries.put(name, new Entry(name, docType, trimToNull(node.get("note"))));
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("★ 语料清单有 " + problems.size() + " 处问题：\n  "
                    + String.join("\n  ", problems) + "\n文件：" + path.toAbsolutePath());
        }

        log.info("语料清单已加载：{} 条声明（{}）", entries.size(), path.toAbsolutePath());
        return new Manifest(true, entries);
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
