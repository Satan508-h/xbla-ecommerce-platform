package com.xbla.rag.rag.retrieve;

import com.xbla.rag.config.RetrievalProperties;
import com.xbla.rag.mapper.KbChunkMapper;
import com.xbla.rag.rag.tokenize.CjkTokenizer;
import com.xbla.rag.rag.tokenize.SearchText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 关键词召回这一路：中文双字切分 + PostgreSQL 全文检索 + {@code ts_rank} 排序。
 *
 * <h2>一、和向量路的互补关系</h2>
 *
 * <p>向量路擅长语义泛化，但对<b>「符号」不敏感</b> ——
 * 型号 {@code 星辰X1}、SKU 编码、订单号这类东西在向量空间里没有「语义」，
 * 可能和一堆无关内容挤在一起。
 *
 * <p>关键词路正好相反：它对字面精确匹配极强
 * （实测查询「星辰X1 电池」能把「星辰 X1 智能手机用户手册 &gt; 充电与电池」排到第一，
 * 而且<b>对空格不敏感</b> —— 型号中间有没有空格都命中），
 * 但对同义改写完全无能为力（问「退货要几天」而文档写「7 天内」时，
 * 只有「退货」这一个 bigram 能对上）。
 *
 * <p>两路的强弱项不重叠，这才是双路召回存在的理由。
 *
 * <h2>二、★ 三个必须显式处理的坑</h2>
 *
 * <p>这三个都不是「写得优雅一点」的问题，每一个都会导致<b>静默的错误结果</b>：
 *
 * <ol>
 *   <li><b>零词元必须短路。</b>单字符查询（「退」）或纯符号查询会产出 0 个词元，
 *       拼出空串。实测 {@code to_tsquery('simple','')} <b>不抛异常</b> ——
 *       它只发一个 NOTICE，返回空 tsquery 而 {@code @@} 恒为 false，
 *       于是<b>静默返回 0 行</b>，和「真的没有匹配」完全无法区分。</li>
 *   <li><b>AND 语义会命中 0 行。</b>必须手工拼 {@code |}。
 *       {@code plainto_tsquery} 把中文查询拆成 {@code 退 & 货 & 要 & 几 & 天}，
 *       要求五个字全部出现在同一个切片里 —— 实测命中 0 行。
 *       拼装由 {@link SearchText#orQuery()} 负责。</li>
 *   <li><b>词元里不能有操作符。</b>{@code to_tsquery} 把
 *       {@code + & ! : ( )} 当操作符解析，用户输入 {@code A&B} 或型号 {@code C++}
 *       会让它直接语法报错、整条路挂掉。这条约束由 {@link SearchText}
 *       的构造器<b>在结构上</b>保证 —— 词元只可能含 {@code [a-z0-9]} 和 CJK。</li>
 * </ol>
 *
 * <h2>三、这一路的分值和向量路不可比</h2>
 *
 * <p>{@code ts_rank} 的值在 0.01 ~ 0.1 量级，而余弦相似度在 0.5 ~ 0.7 ——
 * <b>两把尺子量出来的数不能直接比大小</b>。所以融合用的是 RRF
 * （只看名次不看分数），而不是加权求和。理由见 {@code RrfFuser}。
 */
@Component
public class KeywordRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(KeywordRetriever.class);

    /** 本路在日志和 trace 里的名字 */
    public static final String NAME = "keyword";

    private final CjkTokenizer tokenizer;
    private final KbChunkMapper chunkMapper;
    private final RetrievalProperties properties;

    public KeywordRetriever(CjkTokenizer tokenizer,
                            KbChunkMapper chunkMapper,
                            RetrievalProperties properties) {
        this.tokenizer = tokenizer;
        this.chunkMapper = chunkMapper;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, RetrievalOptions options) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        SearchText searchText = tokenizer.tokenizeQuery(
                query, properties.getRetrieve().getMaxQueryTokens());

        // ★ 坑①：零词元必须短路。
        //   放过去的话，to_tsquery('simple','') 会静默返回 0 行 ——
        //   不报错，但结果和「真的没匹配」无法区分。
        //   而且这一步顺带省掉了一次无意义的数据库查询。
        if (searchText.isEmpty()) {
            log.debug("关键词召回跳过：查询切不出任何词元 query={}", query);
            return List.of();
        }

        List<RetrievedChunk> hits = chunkMapper
                .searchByKeyword(searchText.orQuery(), options.topK(), options.docTypesLiteral())
                .stream()
                .map(RetrievedChunk::from)
                .toList();

        log.debug("关键词召回 topK={} docTypes={} 词元数={} 命中={}",
                options.topK(), options.docTypesLiteral(), searchText.size(), hits.size());
        return hits;
    }
}
