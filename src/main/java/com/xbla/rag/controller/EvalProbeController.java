package com.xbla.rag.controller;

import com.xbla.rag.rag.eval.EvalQuestionLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评测集调试探针。
 *
 * <p>⚠️ {@code @Profile("local")} —— 和另外两个探针一样只在本地存在。
 *
 * <h3>接口</h3>
 * <pre>
 *   # 从 data/eval/baseline-questions.yml 重新加载评测集（幂等 upsert）
 *   curl -s -X POST localhost:8080/api/debug/eval/reload
 * </pre>
 *
 * <p>返回体里带 <b>已解析出 ground truth 的完整题目列表</b> ——
 * {@code scripts/eval_baseline.py} 直接用它来算指标，
 * 不需要自己去查数据库，也不需要知道锚点是怎么解析的。
 */
@Slf4j
@RestController
@RequestMapping("/api/debug/eval")
@Profile("local")
public class EvalProbeController {

    private final EvalQuestionLoader loader;

    public EvalProbeController(EvalQuestionLoader loader) {
        this.loader = loader;
    }

    /**
     * 重新加载评测集。
     *
     * <p><b>幂等</b>：按 {@code question_no} upsert，反复调用不会产生重复数据。
     *
     * <p>★ 锚点解析不唯一时会<b>直接抛异常</b>，由
     * {@code GlobalExceptionHandler} 转成 {@code ApiResponse.fail}。
     * 这是刻意设计的 —— 基线漂移必须吵闹，不能安静地发生。
     */
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        EvalQuestionLoader.Result result = loader.reload();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", result.questions().size());
        response.put("inserted", result.inserted());
        response.put("updated", result.updated());

        // 完整返回解析后的题目 —— 评测脚本靠它拿 ground truth
        response.put("questions", result.questions().stream().map(q -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("questionNo", q.questionNo());
            m.put("question", q.question());
            m.put("category", q.category());
            m.put("difficulty", q.difficulty());
            m.put("expectedChunkIds", q.expectedChunkIds());
            return m;
        }).toList());

        // 按查询形态统计 —— 三类查询在两条召回路上的表现本来就不同，
        // 报告必须能按这个维度切片
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (EvalQuestionLoader.LoadedQuestion q : result.questions()) {
            byCategory.merge(q.category() == null ? "(未分类)" : q.category(), 1L, Long::sum);
        }
        response.put("byCategory", byCategory);
        response.put("note", "intent 列暂为 " + EvalQuestionLoader.INTENT_PLACEHOLDER
                + "，阶段 5 定义意图树后回填");

        return response;
    }
}
