# -*- coding: utf-8 -*-
"""
阶段 4 基线评测 —— 对 20 道题跑一遍检索，算出各配置的指标并生成报告。

★ 为什么要有这个脚本（而不是「人工看看结果对不对」）

    检索质量的直觉是极不可靠的。同一个改动，你会觉得「看起来好多了」，
    但它可能只是把 3 道原本答对的题挪到了第 6 位、同时把 2 道答错的题
    提到了第 3 位 —— 净效果是变差，而肉眼完全看不出来。
    只有固定题集 + 固定指标 + 固定的对比配置，才能回答「优化到底有没有用」。

★ 为什么跑 4 个配置

    路线图 4.8 要求「记录基线指标，用于后续 A/B 对比」。而对比的价值在于
    【能归因】—— 所以这 4 个配置是层层叠加的：

        纯向量  →  纯关键词  →  双路+RRF  →  双路+RRF+重排

    相邻两个之间的差值，就是「加了这一环」的增益。
    只报一个最终数字的话，阶段 7 优化出问题时你无法定位是哪一环退化。

★ 这个脚本【不写数据库】

    它只调检索探针，不经过 /api/chat。理由：
      ① 4.8 要的是【检索】指标，生成质量是阶段 7 的事；
      ② 20 题 × 一次生成要真金白银，而 deepseek-flash 是推理模型
         （实测推理 token 占 87%），一道题可能几十秒；
      ③ 走完整 chat 会往 chat_session / chat_message / qa_log 灌 20 条
         评测数据，把真实使用数据污染掉。

    ⚠️ 由此引出一点：CLAUDE.md 第 4 条「所有写操作要能追溯到 qa_log」
       在 4.8 这里【不适用】—— 因为基线运行根本不写库。

用法：
    python scripts/eval_baseline.py --out eval_results/baseline-20260919
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

BASE = "http://localhost:8080"

# (配置名, 在 retrieval_detail 里取哪一段, 说明)
CONFIGS = [
    ("vector", "vector_hits", "纯向量：只用 VectorRetriever"),
    ("keyword", "keyword_hits", "纯关键词：只用 KeywordRetriever（bigram + ts_rank）"),
    ("fused", "fused", "双路 + RRF 融合"),
    ("full", "reranked", "双路 + RRF + 重排序（= 线上实际链路）"),
]

K_VALUES = [1, 3, 5, 10, 20]


def post(path: str) -> dict:
    req = urllib.request.Request(BASE + path, data=b"", method="POST")
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get(path: str, params: dict) -> dict:
    query = urllib.parse.urlencode(params, encoding="utf-8")
    with urllib.request.urlopen(f"{BASE}{path}?{query}", timeout=180) as resp:
        return json.loads(resp.read().decode("utf-8"))


# ============================================================
# 指标
# ============================================================

def ids_of(section: list) -> list:
    """retrieval_detail 的每一段是 [{"chunk_id":..,"score":..}]，取 id"""
    return [item["chunk_id"] for item in (section or [])]


def hit_rate_at(ranked: list, expected: set, k: int) -> float:
    """前 k 条里【至少命中一条】算 1 分。答案跨多切片时比 recall 更稳"""
    return 1.0 if expected & set(ranked[:k]) else 0.0


def recall_at(ranked: list, expected: set, k: int) -> float:
    """前 k 条覆盖了多少比例的正确答案"""
    if not expected:
        return 0.0
    return len(expected & set(ranked[:k])) / len(expected)


def mrr_at(ranked: list, expected: set, k: int) -> float:
    """第一条正确答案的名次倒数。都没命中记 0"""
    for i, chunk_id in enumerate(ranked[:k], start=1):
        if chunk_id in expected:
            return 1.0 / i
    return 0.0


def failure_mode(detail: dict, expected: set) -> str:
    """
    ★ 正解「死在哪一步」—— 这是整份报告里最有价值的一列。

    三种死法的优化方向完全相反：
      not_recalled   → 召回/切分的问题（两路原始输出里都没有）
      fusion_dropped → RRF 参数问题（两路有，融合后掉出前 20）
      rerank_dropped → 重排模型的问题（融合里有，重排后掉出前 5）
    """
    vector = set(ids_of(detail.get("vector_hits")))
    keyword = set(ids_of(detail.get("keyword_hits")))
    fused = set(ids_of(detail.get("fused")))
    reranked = set(ids_of(detail.get("reranked")))

    if not (expected & (vector | keyword)):
        return "not_recalled"
    if not (expected & fused):
        return "fusion_dropped"
    if not (expected & reranked):
        return "rerank_dropped"
    return "ok"


# ============================================================
# 主流程
# ============================================================

def run(out_prefix: str) -> int:
    print("=" * 78)
    print("阶段 4 基线评测")
    print("=" * 78)

    # ① 加载评测集并拿回解析后的 ground truth
    print("\n[1/3] 加载评测集（会重新解析锚点）...")
    loaded = post("/api/debug/eval/reload")
    if "count" not in loaded:
        print(f"★ 加载失败：{loaded.get('message')}")
        return 1
    questions = loaded["questions"]
    print(f"      共 {loaded['count']} 题，按查询形态 {loaded['byCategory']}")

    # ② 逐题检索
    print(f"\n[2/3] 逐题检索（每题一次完整链路）...")
    per_question = []
    for i, q in enumerate(questions, start=1):
        expected = set(q["expectedChunkIds"])
        try:
            result = get("/api/debug/kb/retrieve", {"q": q["question"]})
        except urllib.error.URLError as e:
            print(f"      [{i}/{len(questions)}] {q['questionNo']} 检索失败：{e}")
            return 1

        detail = result["detail"]
        latency = result["latency"]

        metrics = {}
        for name, section, _ in CONFIGS:
            ranked = ids_of(detail.get(section))
            metrics[name] = {
                **{f"hitrate@{k}": hit_rate_at(ranked, expected, k) for k in K_VALUES},
                **{f"recall@{k}": recall_at(ranked, expected, k) for k in K_VALUES},
                "mrr@5": mrr_at(ranked, expected, 5),
                "returned": len(ranked),
            }

        per_question.append({
            "questionNo": q["questionNo"],
            "question": q["question"],
            "category": q["category"],
            "difficulty": q["difficulty"],
            "expectedChunkIds": sorted(expected),
            "stages": {name: ids_of(detail.get(section)) for name, section, _ in CONFIGS},
            "metrics": metrics,
            "latency": latency,
            "failure_mode": failure_mode(detail, expected),
            "events": detail.get("events", []),
        })
        mark = "✓" if per_question[-1]["failure_mode"] == "ok" else "✗"
        print(f"      [{i:2d}/{len(questions)}] {mark} {q['questionNo']} "
              f"{q['question'][:26]:28s} {latency['totalMs']:5d}ms")

    # ③ 聚合
    print(f"\n[3/3] 聚合指标...")
    aggregate = {}
    for name, _, label in CONFIGS:
        aggregate[name] = {
            "label": label,
            **{f"hitrate@{k}": statistics.mean(q["metrics"][name][f"hitrate@{k}"]
                                               for q in per_question) for k in K_VALUES},
            **{f"recall@{k}": statistics.mean(q["metrics"][name][f"recall@{k}"]
                                              for q in per_question) for k in K_VALUES},
            "mrr@5": statistics.mean(q["metrics"][name]["mrr@5"] for q in per_question),
        }

    latencies = [q["latency"]["totalMs"] for q in per_question]
    rerank_latencies = [q["latency"]["rerankMs"] for q in per_question]
    latency_stats = {
        "total": {"min": min(latencies), "median": statistics.median(latencies),
                  "max": max(latencies)},
        "rerank": {"min": min(rerank_latencies), "median": statistics.median(rerank_latencies),
                   "max": max(rerank_latencies)},
        "note": "★ n=20，P95 在统计上没有意义（它基本等于最大值），"
                "所以这里给 min/median/max，并在报告里显式标注样本量不足",
    }

    modes = {}
    for q in per_question:
        modes[q["failure_mode"]] = modes.get(q["failure_mode"], 0) + 1

    report = {
        "run_id": out_prefix,
        "started_at": datetime.now().isoformat(timespec="seconds"),
        "corpus": {"note": "见 /api/debug/kb/search-text-stats"},
        "question_set": {"count": loaded["count"], "by_category": loaded["byCategory"],
                         "source": "从真实语料反向构造（见 data/eval/baseline-questions.yml）"},
        "aggregate": aggregate,
        "latency": latency_stats,
        "failure_modes": modes,
        "per_question": per_question,
        "caveats": [
            "★ 20 题是从语料【反向构造】的（先看答案再写问题），用词天然贴近原文，"
            "比真实用户提问简单。这份基线只能用于【同一套题、不同配置之间】的相对比较，"
            "不能当作线上召回率。",
            "★ 20 个样本算不出 P95，延迟只给 min/median/max。",
            "★ 本次运行只调检索链路，未经过 /api/chat，因此不写数据库，"
            "也不产生 LLM 成本。",
        ],
    }

    # ④ 落盘
    import pathlib
    json_path = pathlib.Path(out_prefix + ".json")
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    md_path = pathlib.Path(out_prefix + ".md")
    md_path.write_text(render_markdown(report), encoding="utf-8")

    print(f"\n✅ 报告已生成：")
    print(f"   {json_path}  （机器可读，阶段 7 的 A/B 框架直接消费）")
    print(f"   {md_path}  （人可读）")

    print(f"\n{'=' * 78}\n各配置指标对比（n={loaded['count']}）\n{'=' * 78}")
    print(f"{'配置':<10} {'HitRate@1':>10} {'HitRate@5':>10} {'Recall@5':>10} {'MRR@5':>10}")
    for name, _, label in CONFIGS:
        a = aggregate[name]
        print(f"{name:<10} {a['hitrate@1']:>10.1%} {a['hitrate@5']:>10.1%} "
              f"{a['recall@5']:>10.1%} {a['mrr@5']:>10.4f}")
    print(f"\n未命中归因：{modes}")
    return 0


def render_markdown(report: dict) -> str:
    agg = report["aggregate"]
    lat = report["latency"]
    lines = [
        "# 阶段 4 基线报告",
        "",
        f"> 运行 ID：`{report['run_id']}`　生成时间：{report['started_at']}",
        f"> 题集：{report['question_set']['count']} 题"
        f"（{report['question_set']['by_category']}）",
        "",
        "## 一、各配置指标对比",
        "",
        "路线图 4.8 要求「记录基线指标，用于后续 A/B 对比」。",
        "下面 4 个配置是**层层叠加**的，相邻两行之差就是「加了这一环」的增益。",
        "",
        "| 配置 | 说明 | HitRate@1 | HitRate@5 | Recall@5 | MRR@5 |",
        "|---|---|---|---|---|---|",
    ]
    for name, _, label in CONFIGS:
        a = agg[name]
        lines.append(f"| `{name}` | {label} | {a['hitrate@1']:.1%} | {a['hitrate@5']:.1%} "
                     f"| {a['recall@5']:.1%} | {a['mrr@5']:.4f} |")

    lines += [
        "",
        "**指标口径**",
        "",
        "- `HitRate@K` —— 前 K 条里**至少命中一条**正确答案的题占比。"
        "答案跨多切片时比 Recall 更稳，也更接近用户感知",
        "- `Recall@K` —— 前 K 条覆盖的正确答案比例的平均值",
        "- `MRR@K` —— 第一条正确答案名次的倒数，都没命中记 0",
        "",
        "## 二、延迟",
        "",
        "| 环节 | min | median | max |",
        "|---|---|---|---|",
        f"| 检索全链路 | {lat['total']['min']}ms | {lat['total']['median']:.0f}ms "
        f"| {lat['total']['max']}ms |",
        f"| 其中重排 | {lat['rerank']['min']}ms | {lat['rerank']['median']:.0f}ms "
        f"| {lat['rerank']['max']}ms |",
        "",
        "> ⚠️ " + lat["note"],
        "",
        "## 三、★ 未命中归因",
        "",
        "这是整份报告里最有价值的一页：**正解死在哪一步，决定了该往哪里调**。",
        "",
        "| 归因 | 含义 | 该往哪调 | 题数 |",
        "|---|---|---|---|",
        f"| `ok` | 最终 Top-5 里有正解 | — | {report['failure_modes'].get('ok', 0)} |",
        "| `not_recalled` | 两路原始输出里都没有 | **召回/切分**：切分粒度、分词方案、TopK | "
        f"{report['failure_modes'].get('not_recalled', 0)} |",
        "| `fusion_dropped` | 两路有，融合后掉出前 20 | **RRF 参数**：k 值、两路权重 | "
        f"{report['failure_modes'].get('fusion_dropped', 0)} |",
        "| `rerank_dropped` | 融合里有，重排后掉出前 5 | **重排模型**：候选数、阈值 | "
        f"{report['failure_modes'].get('rerank_dropped', 0)} |",
        "",
        "## 四、逐题明细",
        "",
        "| 题号 | 形态 | 问题 | 正解 | 向量 | 关键词 | 融合 | 重排 | 归因 |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for q in report["per_question"]:
        def pos(stage_ids, expected):
            for i, cid in enumerate(stage_ids, 1):
                if cid in expected:
                    return f"#{i}"
            return "—"
        expected = set(q["expectedChunkIds"])
        lines.append(
            f"| {q['questionNo']} | {q['category']} | {q['question']} "
            f"| {q['expectedChunkIds']} "
            f"| {pos(q['stages']['vector'], expected)} "
            f"| {pos(q['stages']['keyword'], expected)} "
            f"| {pos(q['stages']['fused'], expected)} "
            f"| {pos(q['stages']['full'], expected)} "
            f"| {q['failure_mode']} |")

    lines += ["", "## 五、★ 这份报告的已知局限", ""]
    for caveat in report["caveats"]:
        lines.append(f"- {caveat}")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="阶段 4 基线评测")
    parser.add_argument("--out", default="eval_results/baseline",
                        help="输出文件前缀（会生成 .json 和 .md）")
    args = parser.parse_args()
    try:
        return run(args.out)
    except urllib.error.URLError as e:
        print(f"请求失败（应用启动了吗？）：{e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
