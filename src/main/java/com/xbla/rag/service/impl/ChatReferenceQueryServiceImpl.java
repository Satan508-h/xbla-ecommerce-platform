package com.xbla.rag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbla.rag.dto.ReferenceDetail;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.KbChunkMapper;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.rag.eval.ChunkContent;
import com.xbla.rag.service.ChatReferenceNotFoundException;
import com.xbla.rag.service.ChatReferenceQueryService;
import com.xbla.rag.service.ChatTraceNotFoundException;
import com.xbla.rag.service.KbChunkNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link ChatReferenceQueryService} 的实现。
 *
 * <p>★ 这个类里<b>没有任何写操作</b>，也不调模型 —— 两次查库，完了。
 */
@Slf4j
@Service
public class ChatReferenceQueryServiceImpl implements ChatReferenceQueryService {

    private final QaLogMapper qaLogMapper;
    private final KbChunkMapper kbChunkMapper;
    private final ObjectMapper objectMapper;

    public ChatReferenceQueryServiceImpl(QaLogMapper qaLogMapper,
                                         KbChunkMapper kbChunkMapper,
                                         ObjectMapper objectMapper) {
        this.qaLogMapper = qaLogMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReferenceDetail referenceDetail(String traceId, Long chunkId) {
        QaLog row = loadQaLog(traceId);

        // ★★ 第一步是【校验这片真的被引用过】，不是直接去取正文。
        //    少了这一步，这个接口就是「知道 chunkId 就能读任意切片」——
        //    而 kb_chunk.id 是 BIGSERIAL，连续自增，枚举成本约等于零。
        //    见 ChatReferenceNotFoundException 的类注释。
        JsonNode ref = findReference(traceId, row.getReferences(), chunkId);

        String content = loadContent(chunkId);

        return new ReferenceDetail(
                chunkId,
                // ★ 这两个字段取自 references 那一条【原样】，不是现查 kb_chunk。
                //   现查的话，切片被重新切分 / 文档被改名之后，
                //   同一条引用会在列表里和弹窗里显示两个不同的标题 ——
                //   而「同一份数据在两处不一致」是最容易被读成 bug 的现象。
                longOrNull(ref.get("document_id")),
                textOrNull(ref.get("heading_path")),
                content);
    }

    // ============================================================
    // 两次查库
    // ============================================================

    /**
     * 按 traceId 取那一行 {@code qa_log}。
     *
     * <p>★ 用 {@code selectOne} 读<b>整个实体</b>，不是手写列清单 —— 同
     * {@code ChatTraceQueryServiceImpl} 的理由（{@code selectByEvalRun} 那种
     * 显式列清单在本项目坏了两次，症状是一个看起来很合法的 0，见坑 18）。
     *
     * <p>★ {@code trace_id} 上<b>没有唯一约束</b>，但「一个 traceId 一行」是
     * 事实上的不变量。所以这里也不 {@code selectList().get(0)} ——
     * 真出现两行时让它抛 {@code TooManyResultsException}，那是<b>不变量坏了</b>，
     * 该大声。静默挑一行会把数据问题伪装成一次正常的显示。
     */
    private QaLog loadQaLog(String traceId) {
        QaLog row = qaLogMapper.selectOne(
                Wrappers.<QaLog>lambdaQuery().eq(QaLog::getTraceId, traceId));
        if (row == null) {
            throw new ChatTraceNotFoundException(traceId);
        }
        return row;
    }

    /**
     * 取切片正文。
     *
     * <p>★ 复用 {@code KbChunkMapper.selectContentsByIds} —— 它是阶段 7.5 给
     * RAGAS 还原 {@code retrieved_contexts} 写的，形状正好是「一批 id → 正文」。
     * <b>不新写一条 SQL</b>：同一份数据两条查询，迟早会有一条忘了改
     * （比如以后给 {@code kb_chunk} 加过滤条件）。
     *
     * <p>⚠️ 那个方法<b>要求 ids 非空</b>（空列表会生成 {@code IN ()} 语法错误），
     * 这里 {@code List.of(chunkId)} 恒为一条，构造上满足。
     */
    private String loadContent(Long chunkId) {
        List<ChunkContent> found = kbChunkMapper.selectContentsByIds(List.of(chunkId));
        if (found.isEmpty()) {
            // 不可达（kb_chunk 只软删，而那个查询不过滤 deleted），
            // 但不可达 ≠ 可以不管 —— 见 KbChunkNotFoundException 的类注释
            throw new KbChunkNotFoundException(chunkId);
        }
        return found.get(0).content();
    }

    // ============================================================
    // references 解析
    // ============================================================

    /**
     * 在 {@code references} 里找 {@code chunk_id == chunkId} 的那一条。
     *
     * <p>形状（{@code ChatAskResponse} 的 javadoc 里钉着）：
     *
     * <pre>
     *   [{"no":1,"chunk_id":15,"document_id":2,"score":0.9276,"heading_path":"售后FAQ"}]
     * </pre>
     *
     * <p>★ 找不到就抛 —— <b>不回一个空对象</b>。理由见
     * {@link ChatReferenceNotFoundException}。
     *
     * <p>★ <b>坏 JSON 的处置和「没找到」不同</b>：{@code references} 是 JSONB 列，
     * PostgreSQL 在写入时就校验合法性，所以解析失败意味着列类型被改过 ——
     * 那是 {@code log.error}（同 {@code ChatTraceQueryServiceImpl.parseJson}），
     * 然后按「没有这条引用」处理。★ 这里不抛 500 是刻意的：一次读不动
     * 远古数据的 JSON 不该让整个弹窗炸掉，而 error 日志已经把它喊出来了。
     */
    private JsonNode findReference(String traceId, String referencesJson, Long chunkId) {
        JsonNode arr = parse(referencesJson);
        if (arr == null || !arr.isArray()) {
            throw new ChatReferenceNotFoundException(traceId, chunkId);
        }

        for (JsonNode item : arr) {
            // ★ 用 asLong 之前先判 isNumber：chunk_id 缺失（get 返回 null）
            //   时 asLong() 会给 0，而 0 是一个【合法的 id 形状】——
            //   于是「这条没有 chunk_id」会被读成「它引用的是 0 号切片」。
            JsonNode idNode = item.get("chunk_id");
            if (idNode != null && idNode.isNumber() && idNode.asLong() == chunkId) {
                return item;
            }
        }
        throw new ChatReferenceNotFoundException(traceId, chunkId);
    }

    // ============================================================
    // JSON 小工具
    // ============================================================

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.error("qa_log.references 反序列化失败 —— 该列是 JSONB，"
                    + "走到这里说明列类型被改过。长度={} 异常={}", raw.length(), e.getMessage());
            return null;
        }
    }

    private static Long longOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asLong() : null;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
