#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""阶段 7 · T6：A/B 框架 —— 两份 run 的指标表 diff + 逐题翻转矩阵 + 配置 diff。

★ 这个脚本【不重算任何指标】。
  指标只有一个出处：`GET /api/debug/eval/report?runId=`，它直接读 `qa_log`。
  在这里重写一遍「取第一次 status=1」「众数投票」「归因桶」，
  得到的是【第二份实现】—— 两边各自都自洽，只是数不一样，
  而没有任何东西会报错。阶段 7 已经为这件事写过一次对拍
  (`eval_report_check.py`)，那是给「独立复算」用的，不是给日常取数用的。

★★ 它存在的第一理由不是「比较」，是【先量出噪声底】。
   T4 实测过：同 20 题同 prompt 两轮各 8 次 —— 准确率一样、错题一样，
   但【逐题票型变了】(一道从 6:2 变成 8:0)。
   所以「A 比 B 好 3 个百分点」这句话，在不知道噪声底之前是一句空话：
   翻转矩阵上那 3 个百分点里有多少是纯粹的抖动，没人知道。

   用法上这就是一条命令的差别：

     python scripts/eval_ab.py --a 20260921-stage7 --b 20260922-stage7-run2
                                  ↑ 同配置 = 量噪声底，输出会自己标出来

★ 三个前置检查不通过就【直接停】，不打印任何数字：
   ① 任一轮不完整 (complete / reconcileOk)  → 缺行会让分母悄悄变小
   ② 语料或 gold 指纹不同                    → 洞 7，逐题 diff 无意义
   ③ report.json 里没有「逐题」段            → 说明是旧算法生成的，翻转矩阵做不了
"""

import argparse
import io
import json
import math
import os
import re
import sys
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RESULTS = os.path.join(ROOT, "eval_results")

DEFAULT_BASE = "http://localhost:8080"

# ★ 切片纪律：n < 5 的切片不作为结论。
#   ⚠️ 真身是 Java 的 `EvalReportService.MIN_SLICE_N`；Python 侧只有【这一份】拷贝，
#      `eval_report.py` 从这里 import（它原来是第二份，已合并）。
#      值对不上时，报告会给一个 n<5 的切片打上「可信」——
#      所以 `eval_report.py --selftest` 有一条断言它 == report.json 里的
#      `★切片可信度纪律.MIN_SLICE_N`。
MIN_SLICE_N = 5

# ★★★ 一轮里允许的「模型链路失败」占比上限 —— 超过它整轮不可用。
#   背景：2026-09-23 轴 2b 那轮 477 条里 408 条是 503（DNS 挂了 → 熔断器全跳闸），
#   而 `complete` 与 `reconcileOk` **都是 True** —— 因为那两条判据问的是
#   「行有没有丢」，没有一条问「行有没有成」。
#   ⚠️ 这是第二份拷贝（`eval_run.MAX_FAILED_RATIO` 是生产者那份）。
#      新轮次由生产者把结论 `tooManyFailed` 直接写进 run.raw.json，
#      这里只对【旧文件】兜底重算 —— 所以两份值必须一致。
RECONCILE_MAX_FAILED_RATIO = 0.05


def run_usability(raw):
    """一轮到底跑成了没有 → (可用?, 失败行数, 失败率, 说明)。

    ★ 优先读生产者盖的章（`reconcile.tooManyFailed`）；
      旧的 run.raw.json 没有那个字段，就从 `rowsByStatus` 现算。
    ★★ `status=3`（澄清反问）**不算失败** —— 它是设计好的行为。
    """
    rec = raw.get("reconcile") or {}
    by = rec.get("rowsByStatus") or {}
    total = sum(int(v) for v in by.values())
    bad = int(by.get("2", 0)) + int(by.get("4", 0))
    if "tooManyFailed" in rec:
        return (not rec["tooManyFailed"], bad, rec.get("badRatio", 0.0),
                "生产者盖章")
    if not total:
        return True, 0, 0.0, "没有 status 分布可判（旧文件？）"
    ratio = bad / total
    return (ratio <= RECONCILE_MAX_FAILED_RATIO, bad, ratio,
            "按 %.0f%% 阈值现算" % (RECONCILE_MAX_FAILED_RATIO * 100))

# ================================================================
# 逐题判据的分类
# ================================================================

# 分类量：只能判「翻没翻」。★ 顺序就是输出的顺序
CATEGORICAL = [
    "意图正确",
    "命中@5",
    "归因",
    "闸门",
    "取的是第几次成功",
    "跨次序列变了",
    "意图全票一致",
]

# 连续量：翻转矩阵对它没有意义（0.667 → 0.666 算翻吗？）
# 报的是总变化与均值变化。★ 不做「翻转计数」是刻意的：
# 一个连续量的每一格都能「翻」，把它混进翻转速率的表里会淹掉真正的信号
CONTINUOUS = [
    "recall@5",
    "mrr@5",
    "越界切片数",
    "上下文条数",
    "总耗时ms",
]

# 指标表 diff 时不该被当成「数字」的键
NOT_A_NUMBER = ("runId", "口径", "note", "说明", "判据", "★怎么用", "★三态字段")


# ================================================================
# 读
# ================================================================

def resolve_dir(spec):
    """`--a` 既接受 runId（到 eval_results/ 下找），也接受一个目录路径。"""
    for cand in (spec, os.path.join(RESULTS, spec)):
        if os.path.isdir(cand):
            return cand
    raise SystemExit("找不到这一轮：%s（也不是 %s）" % (spec, os.path.join(RESULTS, spec)))


def load(dirpath, refresh, base):
    raw_path = os.path.join(dirpath, "run.raw.json")
    rep_path = os.path.join(dirpath, "report.json")
    if not os.path.isfile(raw_path):
        raise SystemExit("缺 run.raw.json：%s" % raw_path)
    raw = json.load(io.open(raw_path, encoding="utf-8"))

    if refresh:
        import urllib.request
        url = "%s/api/debug/eval/report?runId=%s" % (base, raw["runId"])
        print("  重新生成 report.json：%s" % url)
        with urllib.request.urlopen(url, timeout=300) as resp:
            body = resp.read()
        with open(rep_path, "wb") as f:
            f.write(body)
    if not os.path.isfile(rep_path):
        raise SystemExit(
            "缺 report.json：%s\n"
            "  ★ 生成它：curl -s \"%s/api/debug/eval/report?runId=%s\" -o %s"
            % (rep_path, base, raw["runId"], rep_path))
    report = json.load(io.open(rep_path, encoding="utf-8"))
    return raw, report


# ================================================================
# 前置检查 —— 不通过就停，一个数字都不打印
# ================================================================

def fingerprint_check(raw_a, raw_b):
    """洞 7：语料或 gold 一变，跨轮逐题 diff 就不再描述同一件事。"""
    fa, fb = raw_a.get("fingerprint", {}), raw_b.get("fingerprint", {})
    problems = []
    for part in ("corpus", "gold", "chunking"):
        ha = (fa.get(part) or {}).get("hash")
        hb = (fb.get(part) or {}).get("hash")
        if ha != hb:
            problems.append("  ✗ %-9s  %s  vs  %s" % (part, ha, hb))
    return problems


# ================================================================
# 配置 diff
# ================================================================

def config_diff(raw_a, raw_b):
    """三分类，不两类。

    ★ 「只在一边出现」和「两边都有但值不同」必须分开。
      配置快照是【跑那一轮时的 application.yml + 环境变量】。所以一轮跑在
      「这个键还没被加进 yml」的时候，它的快照里就没有这个键 ——
      但那一刻生效的是代码里的默认值，而默认值很可能就是另一轮写的那个。
      把这种情况算成「配置不同」，会让【自比对】（同配置两轮）谎报差异，
      而自比对的全部意义就是「配置一字未改，翻的都是噪声」。
    """
    pa = (raw_a.get("config") or {}).get("properties") or {}
    pb = (raw_b.get("config") or {}).get("properties") or {}
    keys = sorted(set(pa) | set(pb))
    differ, only_a, only_b = [], [], []
    for k in keys:
        va, vb = pa.get(k), pb.get(k)
        if k not in pa:
            only_b.append((k, vb))
        elif k not in pb:
            only_a.append((k, va))
        elif va != vb:
            differ.append((k, va, vb))
    return differ, only_a, only_b, len(keys)


# ================================================================
# 指标表 diff
# ================================================================

def leaves(obj, prefix=""):
    """把 report 铺平成 {路径: 标量}。

    ★ 列表整体当一个值，不展开 —— 展开会让 `三次status` 变成三个格，
      而它在指标表里不该出现（那是逐题的事）。

    ★★ 逐题的东西【跳过】，它们不属于指标表。
      `归因.逐题.ok` 是「哪些题归到 ok」，`逐题命中集合.B-001` 是单题的命中集合 ——
      这些一变，指标表就多出几十行，把真正的差异淹掉。
      它们该由翻转矩阵逐格处理，那才是能读出「翻了几道」的地方。
    """
    out = {}
    if isinstance(obj, dict):
        for k, v in obj.items():
            if any(s in str(k) for s in NOT_A_NUMBER):
                continue
            if is_per_question(str(k)):
                continue
            out.update(leaves(v, "%s.%s" % (prefix, k) if prefix else str(k)))
    elif isinstance(obj, list):
        out[prefix] = obj
    else:
        out[prefix] = obj
    return out


def is_per_question(key):
    """逐题的键：题号本身，或者装着逐题数据的容器。

    ★ 题号的形状是 `B-001` / `MT-017` / `SQ-005` —— 这个正则就是它的定义。
      写成「匹配大写字母 + 连字符 + 数字」而不是列举，是为了新题集不用改这里。
    """
    if key in ("逐题", "逐题命中集合") or key.startswith("逐题"):
        return True
    return bool(re.fullmatch(r"[A-Z]{1,4}-\d{2,4}", key))


def metric_diff(report_a, report_b):
    """只比两边都有的路径。★ 单边独有的路径单独列 —— 它意味着
    两份 report 的【结构】不一样，通常是算法版本不同，而不是数据不同。"""
    a = leaves({k: v for k, v in report_a.items() if k != "逐题"})
    b = leaves({k: v for k, v in report_b.items() if k != "逐题"})

    only_a = sorted(set(a) - set(b))
    only_b = sorted(set(b) - set(a))

    changed = []
    same = 0
    for path in sorted(set(a) & set(b)):
        if a[path] == b[path]:
            same += 1
            continue
        # δ 只对真的数算得出来；字符串/布尔只报「变了」
        delta = None
        if isinstance(a[path], (int, float)) and isinstance(b[path], (int, float)) \
                and not isinstance(a[path], bool) and not isinstance(b[path], bool):
            delta = b[path] - a[path]
        changed.append((path, a[path], b[path], delta))
    return changed, same, only_a, only_b


def denominator_of(report, path):
    """找同一节里的 `n` —— 报告里每个比率旁边都有分母，diff 时一并带出来。
    ★ 不带分母的 diff 是本项目最反对的那种：一个数旁边挂着错的口径。"""
    parts = path.split(".")
    for cut in range(len(parts) - 1, 0, -1):
        node = report
        try:
            for p in parts[:cut]:
                node = node[p]
        except (KeyError, TypeError):
            continue
        if isinstance(node, dict):
            for key in ("n", "★分母", "★分母_测到的题数", "分母", "分母_不该反问的行"):
                if key in node and isinstance(node[key], (int, float)):
                    return node[key]
    return None


# ================================================================
# 翻转矩阵
# ================================================================

def common_rows(report_a, report_b):
    ra = {r["题号"]: r for r in report_a["逐题"]["行"]}
    rb = {r["题号"]: r for r in report_b["逐题"]["行"]}
    common = sorted(set(ra) & set(rb))
    return ra, rb, common, sorted(set(ra) - set(rb)), sorted(set(rb) - set(ra))


def flip_table(ra, rb, common):
    """每个判据一行：共同可比几道、翻了几道、翻的是哪些题。

    ★★ 三态的处理是整个函数的要害。
      `意图正确` 和 `命中@5` 可以是 None —— 那是「这一格不可判」，
      **不是 false**。把 None 当成 false：
        · 那 15 道工具题的「意图正确」在每一轮里都是 false
        · 于是它们一格都不翻，矩阵看起来完全正常
        · 而「可比口径 93% vs 全体口径 84%」这 9.4 个百分点被摊平进噪声
      所以共同可比集 = 【两侧都非 None】的那些题 —— 不是题库总题数。
    """
    rows = []
    details = {}
    for metric in CATEGORICAL:
        comparable, flips = [], []
        for q in common:
            va, vb = ra[q].get(metric), rb[q].get(metric)
            if va is None or vb is None:
                continue
            comparable.append(q)
            if va != vb:
                flips.append((q, va, vb))
        rows.append((metric, len(comparable), len(flips)))
        details[metric] = flips
    return rows, details


def continuous_table(ra, rb, common):
    rows = []
    details = {}
    for metric in CONTINUOUS:
        vals = []
        for q in common:
            va, vb = ra[q].get(metric), rb[q].get(metric)
            if va is None or vb is None:
                continue
            vals.append((q, float(vb) - float(va)))
        if not vals:
            rows.append((metric, 0, None, None))
            details[metric] = []
            continue
        total = sum(d for _, d in vals)
        moved = [(q, d) for q, d in vals if abs(d) > 1e-12]
        rows.append((metric, len(vals), total / len(vals), len(moved)))
        details[metric] = sorted(moved, key=lambda t: -abs(t[1]))
    return rows, details


# ================================================================
# ★ 按 category 切片（T8 新增）
# ================================================================

SLICE_FIELD = "类别"

# 切片里要报的连续量。★ 顺序就是输出的顺序。
# ⚠️ 这里【不含】延迟：延迟那一段两轮被 status≠1 截掉的条数不同，
#    比的不是同一个总体（见 §2.5）。切片只会把这个毛病放大，不会修好它。
SLICE_CONTINUOUS = ["recall@5", "mrr@5", "上下文条数", "越界切片数"]

# `归因` 是分类量，切片里要报的是【分布】不是一个均值 ——
# 「filtered_out 从 3 道涨到 9 道」是一个句子，「归因均值 1.7」不是
ATTRIBUTION_BUCKETS = ["ok", "filtered_out", "not_recalled", "rerank_dropped", "fusion_dropped"]

SLICE_LABEL = {
    "colloquial": "colloquial（口语句）",
    "keyword": "keyword（关键词句）",
    "model": "model（型号/规格句）",
}


def slice_of(row, field=SLICE_FIELD):
    """一题属于哪一片。

    ★ 缺字段返回 None，让它【落不进任何一片】——
      「没标类别」和「类别恰好是某一类」是两件事，把它们混进同一个桶里，
      桶的 n 会变大而没人看得出来。
    """
    v = row.get(field)
    return v if isinstance(v, str) and v else None


def binom_tail(k, n, p):
    """P(X >= k | X ~ Binomial(n, p)) —— 纯 stdlib 的单侧尾概率。

    ★★ 为什么不直接比「Δ率 和 噪声率 哪个大」：
      两只率各自带着自己的样本量。21 道题上翻 1 次 = 4.8%，
      88 道题上翻 1 次 = 1.1% —— 看起来差四倍，而那纯粹是分母不同。
      先把两者放进同一个二项模型，把 n 的影响算进去，才谈得上「超过」。
    """
    if n <= 0 or k <= 0:
        return 1.0
    if p <= 0:
        return 0.0
    if p >= 1:
        return 1.0
    return sum(math.comb(n, i) * p ** i * (1 - p) ** (n - i) for i in range(k, n + 1))


def noise_rate(flips, n):
    """噪声底率 —— ★ 0 次翻转时用「规则三」当上界。

    ★★ 这是本函数存在的唯一理由：噪声对在某一片上恰好 0 次翻转时，
      直接拿 0 当 p 会让 `binom_tail` 恒为 0，于是【任何】翻转
      —— 哪怕就 1 道 —— 都被判成「显著超过噪声」。那是假的。
      规则三：n 次观测里 0 次发生 ⇒ 真实率 95% 单侧上界约 3/n。
      它保守（让「超过噪声」更难成立），而保守正是这里要的方向。
    """
    if n is None or n <= 0:
        return None
    return 3.0 / n if flips == 0 else float(flips) / n


def _slice_questions(ra, common, c):
    return [q for q in common if slice_of(ra[q]) == c]


def slice_categorical(ra, rb, common):
    """每片 × 每个分类判据：可比数 + 翻转数。

    ★★ 三态纪律与 `flip_table` 逐字相同：分母 = **两侧都非 None** 的题。
      把 None 当 false 会让那 15 道工具题的 `意图正确` 在每一轮里都恒为 false
      —— 它们一格都不翻，于是矩阵看起来完全正常，而真实的口径差异被摊平。
    """
    out = {}
    for c in {slice_of(ra[q]) for q in common} - {None}:
        qs = _slice_questions(ra, common, c)
        per = {}
        for metric in CATEGORICAL:
            comp = [q for q in qs
                    if ra[q].get(metric) is not None and rb[q].get(metric) is not None]
            per[metric] = (len(comp), [q for q in comp
                                       if ra[q].get(metric) != rb[q].get(metric)])
        out[c] = per
    return out


def slice_continuous(ra, rb, common):
    """每片 × 每个连续量：可比数 + A 均值 + B 均值 + Δ。

    ★ 两侧的均值都要报。只报 Δ 会让人读不出「0.97 → 0.80 掉了 17 个点」
      和「0.10 → 0.00 掉了 10 个点」哪个严重 —— Δ 一样大，处境完全不同。
      本项目已经因为「一个数旁边挂着错的口径」返工过一次（T4 的 8.78 个点）。
    """
    out = {}
    for c in {slice_of(ra[q]) for q in common} - {None}:
        qs = _slice_questions(ra, common, c)
        per = {}
        for metric in SLICE_CONTINUOUS:
            vals = [(float(ra[q][metric]), float(rb[q][metric])) for q in qs
                    if ra[q].get(metric) is not None and rb[q].get(metric) is not None]
            if not vals:
                per[metric] = (0, None, None, None)
                continue
            va = sum(a for a, _ in vals) / len(vals)
            vb = sum(b for _, b in vals) / len(vals)
            per[metric] = (len(vals), va, vb, vb - va)
        out[c] = per
    return out


def slice_attribution(ra, rb, common):
    """归因桶在两边的分布。★ 只数非 None 的 —— 那 28 道非检索题没有归因，
    它们进分母会把每一桶的比例机械地压小。"""
    out = {}
    for c in {slice_of(ra[q]) for q in common} - {None}:
        qs = _slice_questions(ra, common, c)
        a = Counter(ra[q].get("归因") for q in qs if ra[q].get("归因") is not None)
        b = Counter(rb[q].get("归因") for q in qs if rb[q].get("归因") is not None)
        out[c] = (a, b, len([q for q in qs if ra[q].get("归因") is not None]))
    return out


def slice_verdict(flips, noise_flips, n, noise_n, alpha=0.05, min_flips=3):
    """★ 判据：这一片的翻转数有没有【明显】超过同一片里噪声对的翻转数。

    两道闸门缺一不可：
      · `min_flips` —— 绝对下限。p 值在 n 小的时候没有分辨力：
        5 道题翻 1 道，p 可以很小，而它可能就是一格抖动
      · `alpha` —— 二项尾概率。把两边不同的 n 折算到同一个模型里

    ⚠️ 返回的是【三态】：True（超过）/ False（没超过）/ None（这一片没法判
      —— 没给噪声对照，或者噪声对照在这一片上一道题都没有）。
      **不许把 None 当 False** —— 那是「不可判」，不是「没超过」。
    """
    if noise_n is None or noise_n <= 0 or n <= 0:
        return None, None
    f = noise_rate(noise_flips, noise_n)
    if f is None:
        return None, None
    p = binom_tail(flips, n, f)
    return (flips >= min_flips and p < alpha), p


# ================================================================
# 渲染
# ================================================================

def fmt(v):
    if v is None:
        return "—"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float):
        return "%.6g" % v
    return str(v)


def selftest():
    """★ 比较器自己的反对照。

    ★★ 为什么必须有：一个瞎掉的比较器**输出全绿**。
      「翻转 0 道」既可能是「真的没翻」，也可能是「它什么都没比」——
      两者在屏幕上逐字相同。所以先证明它抓得到人为改坏的东西，
      再读它对真实数据的结论。

    ★ 同样的理由写在 `eval_report_check.py --selftest` 里：
      那次对拍抓到 3 处不一致，全是【投影的键对不上】而不是指标算错 ——
      而「两边都少同一个键」那种瞎法，只有自检能发现。
    """
    import copy

    checks = []

    def chk(label, ok, detail=""):
        checks.append((label, ok, detail))

    # 拿一份真的 run 当样本 —— 合成数据测不出「真实形状」带来的问题
    dirs = sorted(d for d in os.listdir(RESULTS)
                  if os.path.isfile(os.path.join(RESULTS, d, "run.raw.json"))
                  and os.path.isfile(os.path.join(RESULTS, d, "report.json")))
    if not dirs:
        raise SystemExit("selftest 需要至少一轮已经生成 report.json 的 run")
    sample = os.path.join(RESULTS, dirs[0])
    raw = json.load(io.open(os.path.join(sample, "run.raw.json"), encoding="utf-8"))
    rep = json.load(io.open(os.path.join(sample, "report.json"), encoding="utf-8"))
    print("样本：%s（%d 行逐题）" % (dirs[0], len(rep["逐题"]["行"])))
    print()

    # ── ① 比它自己：必须一格都不差 ──────────────────────────────
    d, oa, ob, _ = config_diff(raw, raw)
    chk("配置 diff：自己比自己 = 0", not d and not oa and not ob,
        "得到 %d 个差异" % len(d))
    changed, same, _oa, _ob = metric_diff(rep, rep)
    chk("指标 diff：自己比自己 = 0", not changed,
        "得到 %d 格差异；若不为 0，说明比较器把【格式】读成了【差异】" % len(changed))
    ra = {r["题号"]: r for r in rep["逐题"]["行"]}
    rows, _ = flip_table(ra, ra, sorted(ra))
    total = sum(nf for _, _, nf in rows)
    chk("翻转矩阵：自己比自己 = 0 翻转", total == 0,
        "得到 %d 道翻转" % total)

    # ── ② 改一格逐题判据 → 恰好 1 道翻转，且只有那一个判据动 ──────
    rep2 = copy.deepcopy(rep)
    victim = rep2["逐题"]["行"][0]["题号"]
    before = rep2["逐题"]["行"][0]["命中@5"]
    if before is None:      # 挑一道真的可判的
        rep2["逐题"]["行"][0]["命中@5"] = True
        before = True
    rep2["逐题"]["行"][0]["命中@5"] = not before
    ra2 = {r["题号"]: r for r in rep2["逐题"]["行"]}
    rows2, det2 = flip_table(ra, ra2, sorted(ra))
    hit_flips = len(det2["命中@5"])
    other_flips = sum(len(v) for k, v in det2.items() if k != "命中@5")
    chk("改 1 格 命中@5 → 恰好 1 道翻转（%s）" % victim, hit_flips == 1,
        "得到 %d" % hit_flips)
    chk("  且其它判据【一道都不该翻】", other_flips == 0,
        "得到 %d 道 —— ★ 不为 0 说明判据之间串了" % other_flips)

    # ── ③ 把 1 格变成 None（不可判）→ 共同可比 -1，翻转 0 ────────
    rep3 = copy.deepcopy(rep)
    idx = next(i for i, r in enumerate(rep3["逐题"]["行"])
               if r["命中@5"] is not None)
    rep3["逐题"]["行"][idx]["命中@5"] = None
    ra3 = {r["题号"]: r for r in rep3["逐题"]["行"]}
    rows3, det3 = flip_table(ra, ra3, sorted(ra))
    n0 = dict((m, n) for m, n, _ in rows)["命中@5"]
    n3 = dict((m, n) for m, n, _ in rows3)["命中@5"]
    f3 = len(det3["命中@5"])
    chk("★★ 把 1 格改成 None → 共同可比 %d → %d，翻转仍为 0" % (n0, n3),
        n3 == n0 - 1 and f3 == 0,
        "可比 %d、翻转 %d —— ★★ 这一条是三态的要害："
        "若把 None 当 false，这里会变成【1 道翻转】，"
        "而那意味着每一轮里那些恒为 None 的格都在制造假翻转" % (n3, f3))

    # ── ④ 改一个配置键 → 恰好 1 个配置差异 ─────────────────────
    raw2 = copy.deepcopy(raw)
    key = next(iter(raw2["config"]["properties"]))
    raw2["config"]["properties"][key] = (raw2["config"]["properties"][key] or "") + "-SELFTEST"
    d2, oa2, ob2, _ = config_diff(raw, raw2)
    chk("改 1 个配置键 → 恰好 1 个差异（%s）" % key, len(d2) == 1,
        "得到 %d：%s" % (len(d2), d2))
    chk("  且不被算成「只在一边出现」", not oa2 and not ob2,
        "only_a=%s only_b=%s —— ★ 三分类写错的话，配置 diff 会漏报" % (oa2, ob2))

    # ── ⑤ 删掉一个配置键 → 进「只在一边」，不算值差异 ────────────
    raw3 = copy.deepcopy(raw)
    key3 = next(iter(raw3["config"]["properties"]))
    del raw3["config"]["properties"][key3]
    d3, oa3, ob3, _ = config_diff(raw, raw3)
    chk("★ 删 1 个配置键 → 值差异 0、只在一边 1", not d3 and len(oa3) == 1,
        "差异 %d、only_a=%d、only_b=%d —— ★★ 这一条守的是「自比对不谎报配置改了」："
        "把「键不存在」算成差异，同配置两轮会被读成「配置不同」"
        % (len(d3), len(oa3), len(ob3)))

    # ── ⑥ 改一个聚合指标 → 能被指标表抓到 ───────────────────────
    rep4 = copy.deepcopy(rep)
    rep4["检索"]["HitRate@5"]["命中"] = rep4["检索"]["HitRate@5"]["命中"] - 1
    changed4, _, _, _ = metric_diff(rep, rep4)
    chk("改 1 个聚合指标 → 指标表恰好抓到 1 格", len(changed4) == 1,
        "得到 %d 格：%s" % (len(changed4), [c[0] for c in changed4]))

    # ── ⑦ 逐题的键必须被指标表【排除】 ──────────────────────────
    per_q = [p for p in leaves({k: v for k, v in rep.items() if k != "逐题"})
             if is_per_question(p.split(".")[-1])]
    chk("★ 指标表里没有逐题路径", not per_q,
        "残留 %d 个：%s" % (len(per_q), per_q[:3]))

    # ── ⑧ 切片的规则三（T8）────────────────────────────────────
    # ★★ 这一条守的是一类很容易写反的 bug：
    #    噪声对照对在某片 0 次翻转时，拿 0 当 p 会让二项尾概率恒为 0，
    #    于是【任何】翻转都被判成「显著超过噪声」。
    nr = noise_rate(0, 26)
    chk("★★ 规则三：噪声 0/26 次翻转 → 率取 3/n = %.4f，不是 0" % nr,
        nr is not None and nr > 0,
        "得到 %r —— ★ 取 0 的话下面那条会连带失效" % (nr,))
    chk("  且它让「26 道题里翻 1 道」【不】显著",
        binom_tail(1, 26, nr) > 0.05,
        "p=%.4g —— ★ 若 p<0.05，说明这把尺子太松，"
        "小切片上的一格抖动会被念成结论" % binom_tail(1, 26, nr))
    chk("  反对照：同一批数用 p=0 就会判成显著（所以不能取 0）",
        binom_tail(1, 26, 0.0) == 0.0,
        "得到 %.4g —— ★★ 这一条是【故意让错的那版成立】："
        "它证明上面那两条断言不是恒真的" % binom_tail(1, 26, 0.0))

    # ── ⑨ 切片的判据是【三态】（T8）─────────────────────────────
    ok_none, p_none = slice_verdict(9, 0, 88, 0)
    chk("★★ 没给噪声对照 → 判据是 None（不可判），不是 False",
        ok_none is None and p_none is None,
        "得到 %r —— ★★ 把「不可判」当「没超过」的话，"
        "报告会印出一句「没超过噪声」，而那一轮根本没测过噪声" % (ok_none,))
    ok_f, _ = slice_verdict(1, 1, 26, 26)
    chk("  且「给过、比较后没过」是 False —— 与 None 可区分",
        ok_f is False,
        "得到 %r —— ★ 三态塌成两态就分不出这两件事了" % (ok_f,))
    ok_t, _ = slice_verdict(30, 1, 88, 88)
    chk("  88 道里翻 30 道 vs 噪声 1/88 → 判成超过", ok_t is True,
        "得到 %r" % (ok_t,))
    ok_m, _ = slice_verdict(2, 0, 1000, 1000)
    chk("★ 绝对下限：1000 道里翻 2 道 → 不判（p 可能很小但样本太小）",
        ok_m is False,
        "得到 %r —— ★ min_flips 那道闸门被人拿掉的话这里会变成 True" % (ok_m,))

    # ── ⑩ 切片的分母也是三态（T8）───────────────────────────────
    rep5 = copy.deepcopy(rep)
    idx5 = next(i for i, r in enumerate(rep5["逐题"]["行"]) if r["命中@5"] is not None)
    qid5 = rep5["逐题"]["行"][idx5]["题号"]
    cat5 = rep5["逐题"]["行"][idx5]["类别"]
    rep5["逐题"]["行"][idx5]["命中@5"] = None
    ra5 = {r["题号"]: r for r in rep5["逐题"]["行"]}
    n_cat_a = slice_categorical(ra, ra, sorted(ra))[cat5]["命中@5"][0]
    n_cat_b = slice_categorical(ra, ra5, sorted(ra))[cat5]["命中@5"][0]
    f_cat_b = len(slice_categorical(ra, ra5, sorted(ra))[cat5]["命中@5"][1])
    chk("★★ 切片分母也是三态：`%s` 这一片 %d → %d（-1），翻转仍 0"
        % (cat5, n_cat_a, n_cat_b),
        n_cat_b == n_cat_a - 1 and f_cat_b == 0,
        "得到 %d / 翻转 %d —— ★ 这是 ③ 的切片版。"
        "两处分母算法不一致时，指标表说「可比 130」而各片加起来是 131，"
        "而没有任何东西会报错" % (n_cat_b, f_cat_b))

    # ── ⑪ 缺 `类别` 的题不许落进任何一片（T8）────────────────────
    rep6 = copy.deepcopy(rep)
    idx6 = next(i for i, r in enumerate(rep6["逐题"]["行"]) if r.get("类别"))
    rep6["逐题"]["行"][idx6]["类别"] = None
    ra6 = {r["题号"]: r for r in rep6["逐题"]["行"]}
    sum_before = sum(v["命中@5"][0]
                     for v in slice_categorical(ra, ra, sorted(ra)).values())
    sum_after = sum(v["命中@5"][0]
                    for v in slice_categorical(ra6, ra6, sorted(ra6)).values())
    chk("★ `类别` 为 None 的题不进任何切片（各片可比合计 %d → %d，恰好少 1）"
        % (sum_before, sum_after),
        sum_after == sum_before - 1,
        "得到 %d → %d —— ★ 若把它塞进某个兜底片，那一片的 n 会悄悄变大，"
        "而屏幕上看不出多了谁" % (sum_before, sum_after))

    print("=" * 72)
    bad = 0
    for label, ok, detail in checks:
        print("%s %s" % ("✅" if ok else "❌", label))
        if not ok:
            bad += 1
            print("      %s" % detail)
    print("=" * 72)
    print("%d 项检查，%d 项失败" % (len(checks), bad))
    if bad:
        print()
        print("★★ 比较器坏了。**上面所有真实数据上的结论都不可信** ——")
        print("   一个瞎掉的比较器输出全绿，而「翻转 0 道」和「什么都没比」")
        print("   在屏幕上逐字相同。")
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser(
        description="阶段 7 · A/B：两份 run 的指标 diff + 逐题翻转矩阵")
    # ★ 不是 required：--selftest 不需要它们。缺了由下面显式报错，
    #   比 argparse 那句「the following arguments are required」清楚得多
    ap.add_argument("--a", help="runId 或目录（基线轮）")
    ap.add_argument("--b", help="runId 或目录（对比轮）")
    ap.add_argument("--refresh", action="store_true",
                    help="先从 /api/debug/eval/report 重新生成两份 report.json。"
                         "★ report 的算法改过之后【必须】用它，否则比的是两个版本")
    ap.add_argument("--base", default=DEFAULT_BASE, help="服务地址")
    ap.add_argument("--noise", default=None,
                    metavar="A,B",
                    help="★ 噪声对照对的 runId，形如 `噪声甲,噪声乙`（同配置两轮）。"
                         "给了它，§3.5 的按 category 切片才带【该片自己的噪声底】"
                         "与判据列。⚠️ 不给不是「没有噪声」——是【不可判】，"
                         "表里那一列会印成 `—` 并写明原因")
    ap.add_argument("--out", default=None, help="把 markdown 写进文件（默认只打屏）")
    ap.add_argument("--top", type=int, default=15,
                    help="连续量里最多列几道变化最大的题")
    ap.add_argument("--selftest", action="store_true",
                    help="★ 只跑比较器自己的反对照（人为改坏 7 处，全部必须被抓到）。"
                         "★ 一个瞎掉的比较器输出全绿，所以先证明它不是瞎的")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.a or not args.b:
        ap.error("--a 和 --b 都要给（除非用 --selftest）")

    dir_a, dir_b = resolve_dir(args.a), resolve_dir(args.b)
    lines = []
    w = lines.append

    print("读取：%s" % dir_a)
    raw_a, rep_a = load(dir_a, args.refresh, args.base)
    print("读取：%s" % dir_b)
    raw_b, rep_b = load(dir_b, args.refresh, args.base)

    run_a, run_b = raw_a["runId"], raw_b["runId"]
    w("# A/B：%s  →  %s" % (run_a, run_b))
    w("")

    # ── 0. 前置检查 ────────────────────────────────────────────
    w("## 0. 前置检查")
    w("")
    checks = []

    def check(tag, ok, msg):
        checks.append((tag, ok, msg))
        w("- %s **%s** —— %s" % ("✅" if ok else "❌", tag, msg))

    for tag, raw, rep in ((run_a, raw_a, rep_a), (run_b, raw_b, rep_b)):
        check(tag, bool(raw.get("complete")), "完整 complete=%s" % raw.get("complete"))
        check(tag, bool(raw.get("reconcileOk")),
              "对账 reconcileOk=%s" % raw.get("reconcileOk"))
        # ★★★ 「行有没有丢」和「行有没有成」是两件事。
        #   上面两条全绿，一轮仍可能 85% 的行是 503 —— 实测过。
        usable, bad, ratio, how = run_usability(raw)
        check(tag, usable,
              "跑成了（模型链路失败 %d 行 = %.1f%%，%s）" % (bad, ratio * 100, how))
        has_detail = "逐题" in rep and "行" in rep.get("逐题", {})
        check(tag, has_detail, "report.json 有「逐题」段（%s）"
              % ("%d 行" % len(rep["逐题"]["行"]) if has_detail else "缺，--refresh 重新生成"))

    fp = fingerprint_check(raw_a, raw_b)
    check("指纹一致", not fp,
          "语料/切分/gold 指纹相同" if not fp else "★★ 不同 —— 逐题 diff 无意义（洞 7）")
    for p in fp:
        w("  %s" % p)

    if not all(ok for _, ok, _ in checks):
        w("")
        w("## ⛔ 停止")
        w("")
        w("前置检查不通过，**一个数字都不打印**。")
        w("★ 这不是保守 —— 分母悄悄变小的报告会印出一个漂亮的百分比，")
        w("  而报告是拿来下结论的。")
        emit(lines, args.out)
        return 2

    q_only_a, q_only_b = [], []
    if not fp:
        ra, rb, common, q_only_a, q_only_b = common_rows(rep_a, rep_b)
    else:
        common, ra, rb = [], {}, {}

    # ── 噪声对照对（可选，T8）───────────────────────────────────
    # ★★ 只在【切片】里用得着：每一片要跟【同一片】的噪声比，而不是跟全集的比。
    #    它是额外两轮同配置 run，所以是可选的 —— 不给就是「不可判」，不是「没噪声」。
    nz_cat = nz_cont = None
    nz_common = []
    if args.noise:
        if not common:
            print("⚠️ 前置检查没过，噪声对照对不加载")
        else:
            parts = [p.strip() for p in args.noise.split(",") if p.strip()]
            if len(parts) != 2:
                ap.error("--noise 要写成 `甲,乙` 两个 runId（收到 %d 个）" % len(parts))
            dn_a, dn_b = resolve_dir(parts[0]), resolve_dir(parts[1])
            print("读取噪声对照：%s" % parts[0])
            nraw_a, nrep_a = load(dn_a, args.refresh, args.base)
            print("读取噪声对照：%s" % parts[1])
            nraw_b, nrep_b = load(dn_b, args.refresh, args.base)
            nfp = fingerprint_check(nraw_a, nraw_b)
            if nfp or not (nraw_a.get("complete") and nraw_b.get("complete")
                           and nraw_a.get("reconcileOk") and nraw_b.get("reconcileOk")):
                print("⚠️ 噪声对照对自身的前置检查没过，**当作没给**")
                for p in nfp:
                    print("   %s" % p)
            elif config_diff(nraw_a, nraw_b)[0]:
                print("⚠️ 噪声对照对【不是同配置】—— 它就不是噪声底了，**当作没给**")
            else:
                nra, nrb, ncommon, _, _ = common_rows(nrep_a, nrep_b)
                # ★ 必须与 A/B 的交集再求交：否则同一片里，
                #   噪声底的分母和 Δ 的分母是两批题，两个比例不可比
                nz_common = [q for q in ncommon if q in set(common)]
                nz_ra = {q: nra[q] for q in nz_common}
                nz_rb = {q: nrb[q] for q in nz_common}
                nz_cat = slice_categorical(nz_ra, nz_rb, nz_common)
                nz_cont = slice_continuous(nz_ra, nz_rb, nz_common)
                print("  噪声对照：%s ↔ %s，与 A/B 的共同可比 %d 道"
                      % (nraw_a["runId"], nraw_b["runId"], len(nz_common)))

    # ── 1. 配置 diff ───────────────────────────────────────────
    w("")
    w("## 1. 配置 diff")
    w("")
    cfg_rows, cfg_only_a, cfg_only_b, cfg_total = config_diff(raw_a, raw_b)
    w("两边各 %d 个 `xbla.*` 配置键，**值不同的有 %d 个**。" % (cfg_total, len(cfg_rows)))
    w("")
    if cfg_rows:
        w("| 配置键 | A | B |")
        w("|---|---|---|")
        for k, va, vb in cfg_rows:
            w("| `%s` | `%s` | `%s` |" % (k, fmt(va), fmt(vb)))
        w("")
    if cfg_only_a or cfg_only_b:
        w("⚠️ 还有 **%d / %d 个键只在一边的配置快照里**。"
          % (len(cfg_only_a), len(cfg_only_b)))
        w("")
        w("**它们不算「配置不同」** —— 配置快照是【跑那一轮时】的 yml + 环境变量，")
        w("所以跑在「这个键还没被加进 yml」的那一轮，快照里就没有它，")
        w("而那一刻生效的是代码里的默认值（很可能就是另一轮写的那个）。")
        w("★ 把它们算成差异的后果很具体：**自比对会谎报「配置改了」**，")
        w("而自比对的全部意义就是「配置一字未改，翻的都是噪声」。")
        w("")
        for k, v in cfg_only_a[:8]:
            w("- 只在 A：`%s` = `%s`" % (k, fmt(v)))
        for k, v in cfg_only_b[:8]:
            w("- 只在 B：`%s` = `%s`" % (k, fmt(v)))
        w("")
    if not cfg_rows:
        w("★★ **两轮配置的【共有键】逐字相同。**")
        w("")
        w("那么下面每一格翻转都是 **纯噪声** —— 这就是噪声底，")
        w("后面任何一次 A/B 的差异都必须拿它当尺子。")
        w("★ 它也是「这个数换个时间跑会不会翻」的唯一直接证据：")
        w("  意图探针测的是分类器本身，测不到这条链路上的抖动。")
    w("")

    # ── 2. 指标表 diff ─────────────────────────────────────────
    w("## 2. 指标表 diff")
    w("")
    changed, same, only_a, only_b = metric_diff(rep_a, rep_b)
    lat = [c for c in changed if c[0].startswith("延迟.")]
    changed = [c for c in changed if not c[0].startswith("延迟.")]
    w("逐格比较（**不含延迟**，它单独一节）：**相同 %d 格，不同 %d 格**。"
      % (same, len(changed)))
    w("")
    w("★ 逐题的东西（`归因.逐题.*`、`逐题命中集合.B-001` 之类）**已经排掉了** ——")
    w("  它们一变就是几十行，会把真正的差异淹掉。它们由下面的翻转矩阵逐格处理。")
    w("")
    if only_a or only_b:
        w("⚠️ **两份 report 的结构不同** —— 只在一边出现的路径各有 "
          "%d / %d 个。" % (len(only_a), len(only_b)))
        w("这通常意味着两轮的报告由**不同版本的算法**生成。")
        w("重跑 `--refresh` 之后再解读下面的差异。")
        w("")
        for p in only_a[:10]:
            w("- 只在 A：`%s`" % p)
        for p in only_b[:10]:
            w("- 只在 B：`%s`" % p)
        w("")
    # ★★ 数据【架构版本】不同 —— 它不是噪声，也不是配置的效果，是第三种东西
    sizes_a = rep_a.get("归因", {}).get("★截断可判定性", {})
    sizes_b = rep_b.get("归因", {}).get("★截断可判定性", {})
    if sizes_a.get("无sizes段的行") != sizes_b.get("无sizes段的行"):
        w("★★ **⚠️ 两轮的 `retrieval_detail` 架构版本不同**：")
        w("`无sizes段的行` A=%s / B=%s。" % (sizes_a.get("无sizes段的行"),
                                            sizes_b.get("无sizes段的行")))
        w("")
        w("   `sizes` 段是 2026-09-21（T4）才加进 `retrieval_detail` 的。")
        w("   跑在它之前的那些行**没有这个段**，于是那一轮里")
        w("   「融合/重排有没有被【记录截断】」是**判不了**的 ——")
        w("   正解在记录里找不到时，判决会落到 `beyond_record_cutoff` 而不是")
        w("   `fusion_dropped`。★ 也就是说：**归因那一节的 diff 有一部分是")
        w("   「两轮的数据形状不同」，不是「数据变了」。**")
        w("")
        w("   ⚠️ 旧数据要重跑才能对齐 —— 不能靠重算报告修，")
        w("      因为缺的那个段**当时就没写进 `qa_log`**。")
        w("")

    if not changed:
        w("没有一格不同。")
    else:
        w("| 指标 | A | B | Δ | 分母 |")
        w("|---|---|---|---|---|")
        for path, va, vb, delta in changed:
            den = denominator_of(rep_a, path) or denominator_of(rep_b, path)
            w("| `%s` | %s | %s | %s | %s |"
              % (path, fmt(va), fmt(vb),
                 ("%+.6g" % delta) if delta is not None else "—",
                 ("%d" % den) if den is not None else "—"))
        w("")
        w("★★ **跨配置读这张表之前，先问一句：这个旋钮【可能】影响这一栏吗？**")
        w("")
        w("  实测例子（本次）：`final-top-k` 只决定「几条切片进 prompt」，")
        w("  **它碰不到分类器**。可是同一批 20 道题、同一个分类器，")
        w("  `意图.逐题取众数` 从 17/20 变成 19/20 —— **2 道题、10 个百分点**。")
        w("")
        w("  ⚠️ 但这不是干净的噪声底：它是【跨进程、跨时间】的一对 run。")
        w("  干净的噪声底要等自比对（同配置两轮）。")
        w("  ★ 它证明的是一件更基本的事：**「配置不可能影响这一栏」")
        w("  不等于「这一栏不会变」** —— 所以任何一栏的移动都要先跟噪声比大小。")
    w("")

    # ── 2.5 延迟单独一节 ───────────────────────────────────────
    w("## 2.5 延迟（单独看，**别拿它当差异证据**）")
    w("")
    w("| 指标 | A | B | Δ |")
    w("|---|---|---|---|")
    for path, va, vb, delta in lat:
        w("| `%s` | %s | %s | %s |"
          % (path, fmt(va), fmt(vb),
             ("%+.6g" % delta) if delta is not None else "—"))
    w("")
    w("★★ 延迟几乎每一格都会动，**这是它的性质，不是发现**。")
    w("  它受本机负载、GC、供应商侧波动、并发邻居影响，")
    w("  而这几样每一轮都不同。判据只能是【噪声底之上的差异】：")
    w("  先跑自比对拿到两轮同配置的延迟抖动幅度，再看这一轮的 Δ 有没有超出它。")
    w("")
    w("⚠️ 还有一条口径问题：两轮的样本不同（`被status≠1截掉的条数` 可能不同），")
    w("  所以这里比的【不是同一个总体】。")
    w("")

    if fp:
        w("## ⛔ 逐题部分跳过")
        w("")
        w("指纹不同（洞 7）：重切之后锚点可能**部分悬空**，那时")
        w("`|gold|` 变小 → Recall **反而悄悄变好**，而逐题 diff 会把这读成「改善了」。")
        w("**指纹不同就禁止跨轮 diff。**")
        emit(lines, args.out)
        return 3

    # ── 3. 翻转矩阵 ────────────────────────────────────────────
    w("## 3. 逐题翻转矩阵")
    w("")
    tail = ("；只在 A 的 %d 道，只在 B 的 %d 道（可能有人改了题库）"
            % (len(q_only_a), len(q_only_b))) if (q_only_a or q_only_b) \
        else "（两轮的题库完全相同）"
    w("题号交集 **%d** 道%s" % (len(common), tail))
    w("")
    w("")
    rows, details = flip_table(ra, rb, common)
    w("| 判据 | 共同可比 | 翻转 | 翻转率 |")
    w("|---|---|---|---|")
    for metric, n, nf in rows:
        w("| `%s` | %d | **%d** | %s |"
          % (metric, n, nf, ("%.1f%%" % (100.0 * nf / n)) if n else "—"))
    w("")
    w("★ 「共同可比」**不是**题库总题数。两侧有一侧是 `null` 的题被摘掉了 ——")
    w("  那是「这一格不可判」，不是 false。把它们算成 false，")
    w("  15 道工具题会在每一轮里都恒为 false、一格都不翻，")
    w("  而「可比口径 93% vs 全体 84%」这 9.4 个百分点就被摊平进了噪声里。")
    w("")
    # ── 交叉表：翻转是不是同一个原因造成的 ──────────────────────
    touched = sorted({q for flips in details.values() for q, _, _ in flips})
    if touched:
        metrics_with_flips = [m for m in CATEGORICAL if details[m]]
        w("### ★★ 交叉表：翻了的题 × 翻了的判据")
        w("")
        w("★ 光有「翻转几道」看不出这些翻转【是不是同一个原因】。")
        w("  把同一道题上翻了的判据摆在一行，pattern 就出来了。")
        w("")
        w("| 题号 | " + " | ".join("`%s`" % m for m in metrics_with_flips) + " |")
        w("|---" * (len(metrics_with_flips) + 1) + "|")
        multi, single = 0, 0
        for q in touched:
            flipped_here = [m for m in metrics_with_flips
                            if any(fq == q for fq, _, _ in details[m])]
            if len(flipped_here) > 1:
                multi += 1
            else:
                single += 1
            w("| %s | " % q
              + " | ".join("**✓**" if m in flipped_here else "—"
                           for m in metrics_with_flips) + " |")
        w("")
        w("**%d 道只翻了一个判据，%d 道翻了多个。**" % (single, multi))
        w("")
        # ★ 最有用的一条：命中@5 翻了，是【分类也翻了】还是【检索自己抖了】
        hit_q = {q for q, _, _ in details.get("命中@5", [])}
        intent_q = {q for q, _, _ in details.get("意图正确", [])}
        if hit_q:
            both = hit_q & intent_q
            rest = hit_q - intent_q
            w("★★ **`命中@5` 翻了的 %d 道里，%d 道【意图正确也翻了】**"
              % (len(hit_q), len(both)))
            if both:
                w("  → 那几道的命中变化**能顺着解释**：分类变了 → `docTypes` 变了 → "
                  "过滤范围跟着变 → 召回跟着变。它们不是「检索自己不稳」。")
            w("  → 剩下 **%d 道**没有这个解释。" % len(rest))
            if cfg_rows:
                w("    这一轮是【真 A/B】（配置有 %d 处不同），所以这一组正是那个旋钮的"
                  % len(cfg_rows))
                w("    **作用面** —— 拿它和 `## 1 配置 diff` 对照读：")
                w("    如果旋钮是检索侧的，翻转集中在这里才对；")
                w("    如果它跑到 `意图正确` 上去了，那说明**动的东西比你以为的多**。")
            else:
                w("    这一轮是【自比对】（配置一字未改），所以这一组就是")
                w("    **检索侧自己的抖动** —— 它与分类无关，换个时间跑还会再翻一次。")
                w("    ★ T8 调检索参数时的收益，**必须明显高于这个数**才算数。")
            w("")
            if rest:
                w("    题号：%s" % ", ".join(sorted(rest)))
                w("")
        elif intent_q:
            w("★ 这一轮 `命中@5` 一道都没翻，但 `意图正确` 翻了 %d 道 —— "
              % len(intent_q))
            w("  说明那些分类变化**没有传导到检索**（多半是分到了 `docTypes` 相同的兄弟叶子）。")
            w("")

    for metric, n, nf in rows:
        flips = details[metric]
        if not flips:
            continue
        w("### `%s` 翻转的 %d 道" % (metric, len(flips)))
        w("")
        w("| 题号 | A | B |")
        w("|---|---|---|")
        for q, va, vb in flips:
            w("| %s | `%s` | `%s` |" % (q, fmt(va), fmt(vb)))
        w("")

    # ── 3.5 按 category 切片（T8）──────────────────────────────
    w("## 3.5 按 category 切片")
    w("")
    if not common:
        w("（前置检查没过，跳过）")
        w("")
    else:
        cat = slice_categorical(ra, rb, common)
        con = slice_continuous(ra, rb, common)
        att = slice_attribution(ra, rb, common)
        # 按命中@5 的可比数降序 —— 最大的那片排最前，它是结论的主要承重者
        order = sorted(cat, key=lambda c: -cat[c]["命中@5"][0])
        has_noise = nz_cat is not None

        w("★★ **为什么这一节必须存在**：合计指标会把「重排救了口语句、完全没影响关键词句」")
        w("   平摊成一个中间数 —— 于是「重排值不值」这个问题在合计里根本没有答案。")
        w("")
        w("★ 分母 = 每一片里【该判据两侧都非 None】的题，与 §3 同一套三态纪律。")
        w("")

        # ── 命中@5 ──
        w("### 3.5.1 `命中@5`（主判据）")
        w("")
        if not has_noise:
            w("⚠️ **没有噪声对照（没给 `--noise`）** —— 所以下面【没有判据列】。")
            w("")
            w("★ 这不是「没有噪声」，是**不可判**：每一片的翻转率必须跟**同一片**的")
            w("  噪声比才有意义。拿 159 题的 14.5% 去判 26 道题的一片，")
            w("  等于用一把刻度比被测物还粗的尺子 —— 读出来的是分辨率，不是信号。")
            w("")
        w("| category | n | A 命中 | B 命中 | A 率 | B 率 | Δ率 | 翻转 | "
              "%s判据 |" % ("噪声翻转 | 噪声率 | p | " if has_noise else ""))
        w("|---|---|---|---|---|---|---|---|%s" % ("---|---|---|---|" if has_noise else ""))
        for c in order:
            n, flips = cat[c]["命中@5"]
            if n == 0:
                continue
            hit_a = sum(1 for q in _slice_questions(ra, common, c) if ra[q].get("命中@5") is True)
            hit_b = sum(1 for q in _slice_questions(ra, common, c) if rb[q].get("命中@5") is True)
            ra_rate, rb_rate = hit_a / n, hit_b / n
            cells = "| %s | %d | %d | %d | %.4f | %.4f | **%+.4f** | %d |" % (
                SLICE_LABEL.get(c, c), n, hit_a, hit_b, ra_rate, rb_rate,
                rb_rate - ra_rate, len(flips))
            if has_noise:
                nn, nflips = nz_cat.get(c, {}).get("命中@5", (0, []))
                ok, p = slice_verdict(len(flips), len(nflips), n, nn)
                nr = noise_rate(len(nflips), nn) if nn else None
                verdict = ("— **不可判**" if ok is None
                           else ("★★ **超过噪声**" if ok else "没超过噪声"))
                if nn < MIN_SLICE_N:
                    verdict += "（噪声 n=%d < %d）" % (nn, MIN_SLICE_N)
                cells += " %d | %s | %s | %s |" % (
                    len(nflips), ("%.4f" % nr) if nr is not None else "—",
                    ("%.4g" % p) if p is not None else "—", verdict)
            w(cells)
        w("")
        w("★ `Δ率` 为负 = B 的命中率更低。判据只问「超过噪声了吗」，**不问好坏** ——")
        w("  「命中变多」也不构成变好的证据（洞 1：换一个同样合法的 gold 命中也是 ok/ok）。")
        w("")

        # ── 连续量 ──
        w("### 3.5.2 连续量")
        w("")
        w("| category | n | recall@5 A | B | Δ | mrr@5 A | B | Δ | 上下文条数 A | B |")
        w("|---|---|---|---|---|---|---|---|---|---|")
        for c in order:
            n, _, _, _ = con[c]["recall@5"] or (0, None, None, None)
            if not n:
                continue
            def pair(metric, digits=4):
                _, va, vb, d = con[c][metric]
                return ("%.*f" % (digits, va), "%.*f" % (digits, vb),
                        "**%+.*f**" % (digits, d))
            ra1, rb1, d1 = pair("recall@5")
            ra2, rb2, d2 = pair("mrr@5")
            ra3, rb3, _ = pair("上下文条数", 2)
            w("| %s | %d | %s | %s | %s | %s | %s | %s | %s | %s |" % (
                SLICE_LABEL.get(c, c), n, ra1, rb1, d1, ra2, rb2, d2, ra3, rb3))
        w("")

        # ── 归因桶 ──
        w("### 3.5.3 `归因` 桶分布")
        w("")
        w("★ 这一节回答「**为什么**变了」—— 命中率掉了是没召回到（`not_recalled`）、")
        w("  被范围过滤掉了（`filtered_out`）、还是被重排+截断挤掉了（`rerank_dropped`）。")
        w("  ⚠️ 三者**修法完全相反**，所以它们必须分开数。")
        w("")
        w("| category | n | A | B |")
        w("|---|---|---|---|")
        for c in order:
            a, b, n = att[c]
            if n == 0:
                continue
            fmtc = lambda cnt: " / ".join(  # noqa: E731
                "%s %d" % (k, cnt[k]) for k in ATTRIBUTION_BUCKETS if cnt.get(k))
            w("| %s | %d | %s | %s |" % (
                SLICE_LABEL.get(c, c), n, fmtc(a) or "—", fmtc(b) or "—"))
        w("")
        if has_noise:
            w("### 3.5.4 噪声对照对在切片里的形状")
            w("")
            w("★ 判据列拿的是**这里**的数 —— 把它印出来，是为了让「超过噪声」")
            w("  可以复核，而不是一个从黑箱里冒出来的结论。")
            w("")
            w("| category | n | 噪声对 `命中@5` 翻转 | 噪声率 | 用的是哪条规则 |")
            w("|---|---|---|---|---|")
            for c in order:
                nn, nflips = nz_cat.get(c, {}).get("命中@5", (0, []))
                if nn == 0:
                    continue
                rule = ("规则三上界 3/n（该片 0 次翻转）" if not nflips
                        else "实测 %.0f/%d" % (len(nflips), nn))
                w("| %s | %d | %d | %.4f | %s |" % (
                    SLICE_LABEL.get(c, c), nn, len(nflips), noise_rate(len(nflips), nn), rule))
            w("")
            w("★★ **规则三**：n 次观测里 0 次发生 ⇒ 真实率 95% 单侧上界约 `3/n`。")
            w("  直接拿 0 当 p 会让二项尾概率恒为 0，于是**任何**翻转都被判成显著 ——")
            w("  那是假的。3/n 保守（让「超过噪声」更难成立），而保守正是这里要的方向。")
            w("")
        # 落不进任何一片的题
        loose = [q for q in common if slice_of(ra[q]) is None]
        if loose:
            w("⚠️ **%d 道题没有 `类别` 字段，落不进任何一片**（%s）——"
              % (len(loose), "、".join(loose[:8])))
            w("  它们【不参与】上面任何一格，所以各片的 n 加起来小于 %d。"
              % len(common))
            w("")
        small = [c for c in order if cat[c]["命中@5"][0] < MIN_SLICE_N]
        if small:
            w("⚠️ **n < %d 的切片不作为结论**：%s"
              % (MIN_SLICE_N, "、".join("%s(n=%d)" % (SLICE_LABEL.get(c, c), cat[c]["命中@5"][0])
                                       for c in small)))
            w("")

    # ── 4. 连续量 ──────────────────────────────────────────────
    w("## 4. 连续量（不做翻转计数）")
    w("")
    w("★ 连续量每一格都能「翻」（0.667 → 0.666 算吗？），")
    w("  把它混进上面的翻转率里会淹掉真正的信号。这里报的是总和与均值。")
    w("")
    crows, cdetails = continuous_table(ra, rb, common)
    w("| 判据 | 可比 | 均值 Δ | 变了的题数 |")
    w("|---|---|---|---|")
    for metric, n, mean_delta, moved in crows:
        w("| `%s` | %d | %s | %s |"
          % (metric, n, ("%+.6g" % mean_delta) if mean_delta is not None else "—",
             moved if moved is not None else "—"))
    w("")
    for metric, _, _, _ in crows:
        movers = cdetails[metric][:args.top]
        if not movers:
            continue
        w("### `%s` 变化最大的 %d 道" % (metric, len(movers)))
        w("")
        w("| 题号 | Δ |")
        w("|---|---|")
        for q, d in movers:
            w("| %s | %+.6g |" % (q, d))
        w("")

    # ── 5. 结论 ────────────────────────────────────────────────
    w("## 5. 怎么读这张表")
    w("")
    if not cfg_rows:
        w("★★ **这是自比对（噪声底）。** 配置一字未改，所以上面**每一格翻转都是噪声**。")
        w("")
        w("它的用法是当尺子：后面任何一次真 A/B 的翻转数，")
        w("只有**明显高于**这个数才值得解释。")
        w("")
        w("⚠️ 而且这个数还是**下界的一部分** —— 它只覆盖了两轮的随机性，")
        w("不覆盖「换了机器」「换了供应商侧版本」这类更慢的漂移。")
    else:
        w("配置有 **%d 处不同**，所以翻转里混着两样东西：配置的效果，和噪声。" % len(cfg_rows))
        w("")
        w("★ **先跑一次自比对（同配置两轮），拿到噪声底，再回来读这张表。**")
        w("  没有那个数的话，「翻转 5 道」这句话既不能说是改善也不能说是变差。")
    w("")
    w("## ⚠️ 本工具测不出来的东西")
    w("")
    w("- **答案质量**：RAGAS 那一套跑完要几小时，不在这里。")
    w("  意图/检索翻了多少道 ≠ 答得更好或更差 —— 检索指标上升**不构成**「变好了」的证据")
    w("  （洞 1：换一个同样合法的 gold 命中也是 ok/ok）")
    w("- **延迟的口径**：`延迟` 那一段的两轮样本不同（`status≠1` 的行数可能不同），")
    w("  比的不是同一个总体")
    w("- **成本**：`成本` 是服务端账本，不含意图分类与摘要压缩的花费")

    emit(lines, args.out)
    return 0


def emit(lines, out):
    text = "\n".join(lines) + "\n"
    sys.stdout.write(text)
    if out:
        with io.open(out, "w", encoding="utf-8") as f:
            f.write(text)
        sys.stderr.write("\n已写入 %s\n" % out)


if __name__ == "__main__":
    sys.exit(main())
