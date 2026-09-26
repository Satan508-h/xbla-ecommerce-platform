#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 7 · 评测报告生成器 —— scripts/eval_report.py

把 `eval_results/` 下的 run.raw.json / report.json / ragas-*.json 编译成
`docs/11-评测报告.md` + `docs/11-附录-逐题.md`。

★★★ 读这个文件之前先读这三条设计纪律。它们各自都对应一个会静默出错的地方。

 ① **报告里一个硬编码的数字都不许有。**
    所有数字走 f-string 从数据取；解读句可以硬编码，但必须写成**判据的函数**
    —— 数据变了判据变，句子跟着变。
    ⚠️ 反例：在散文里写死「命中率 97%」。下一轮跑完数字变了、句子没变，
    报告会说一句和它自己的表格矛盾的话，**而没有任何报错**。

 ② **比较逻辑一律 import eval_ab，不重写。**
    报告里的「噪声底」必须和 `python scripts/eval_ab.py --a X --b Y` 单独跑出来的
    那一份**逐字一致**。同一个事实有两个实现 = 迟早漂移，而且漂移是静默的。

 ③ **逐题表「只标差异」：两轮一致就印一个值，不一致才印 `A→B`。**
    全印 `A→B` 会把 159 行变成 318 个值，真正的差异反而淹在重复里。

用法：
    python scripts/eval_report.py                       # 全部默认
    python scripts/eval_report.py --main 20260922-stage7-run2 --out -
    python scripts/eval_report.py --selftest            # 口径自检，不读数据
"""

import argparse
import io
import json
import os
import random
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RESULTS = os.path.join(ROOT, "eval_results")

sys.path.insert(0, HERE)
import eval_ab  # noqa: E402  ★ 纪律 ②：比较逻辑只有这一份

# ================================================================
# 默认的四轮 + 一份 RAGAS
#
# ★ 这些是【角色】，不是【路径】。角色决定了数字在报告里的位置，
#   换一期只要换这里的 runId，报告结构不用动。
# ================================================================

DEFAULT_ROLES = {
    # 主体：数据形状最完整的那一轮（有 sizes 段 → 归因可判定）
    "main": "20260922-stage7-run2",
    # 噪声底：与 main 同配置、早一轮（T6 的产出）
    "noise": "20260921-stage7",
    # 对比基线：阶段 4 的 20 题
    "baseline": "20260921-baseline",
    # 假配置：baseline + final-top-k=1（证明矩阵抓得到已知的差）
    "baseline_bad": "20260922-baseline-bad",
    # 多轮集：单独一套题、单独分母
    "multi": "20260921-stage7-multi",
}

# ★ T8 迭代实验：噪声【链】+ 三条轴。
#   链上相邻两轮同配置 ⇒ 每对给一个噪声底样本；链尾当三条轴的基线。
#   ★★ 不写「轴 1 = 重排开关」这种名字 —— 标题由 config_diff 现算，
#      所以换一期做别的旋钮，这里不用改。
DEFAULT_T8_NOISE = "20260922-stage7-run2,20260923-t8-noise,20260923-t8-noise2"
DEFAULT_T8_AXES = ("20260923-t8-rerank-off,20260923-t8-topk8,20260924-t8-mc20")

DEFAULT_RAGAS = os.path.join("20260921-stage7", "ragas-sample43.json")
DEFAULT_RAGAS_NEG = os.path.join("20260921-stage7", "ragas-negative-control.json")

RAGAS_METRICS = ["faithfulness", "answer_relevancy", "context_recall", "answer_correctness"]

# ★ bootstrap 的迭代次数与种子。种子固定 = 报告可复算。
BOOTSTRAP_ITERS = 2000
BOOTSTRAP_SEED = 20260923

# ★ 切片纪律（真身是 Java 的 EvalReportService.MIN_SLICE_N）。
#   ⚠️ Python 侧原来这里是【第二份】拷贝。T8 把它并进了 eval_ab ——
#      切片的判据和切片的渲染从此只有一个出处，`eval_ab.slice_verdict`
#      与报告里的「⚠️ 不作为结论」不会再各自漂移。
#      值对不上时，报告会给一个 n<5 的切片打上「可信」，
#      所以 selftest 里有一条断言它 == report.json 里的 ★切片可信度纪律.MIN_SLICE_N。
MIN_SLICE_N = eval_ab.MIN_SLICE_N

JAVA_REPORT_SERVICE = os.path.join(
    ROOT, "src", "main", "java", "com", "xbla", "rag", "rag", "eval", "EvalReportService.java")


def _java_min_slice_n(path=None):
    """Java 侧 `MIN_SLICE_N` 的值 —— **直接从源码里读**，不靠记忆。

    返回 `(值, 错误说明)`。★ 读不到时第一位是 `None` 且第二位非 `None`：
    **调用方必须把「读不到」当成失败**，不能回落成一个默认值 ——
    那样这个守卫就成了一个恒真的装饰品（同 docs/10 坑 19 那条纪律）。
    """
    target = path or JAVA_REPORT_SERVICE
    try:
        with io.open(target, encoding="utf-8") as f:
            text = f.read()
    except Exception as e:
        return None, "读不到 %s：%s" % (target, e)
    m = re.search(r"MIN_SLICE_N\s*=\s*(\d+)", text)
    if not m:
        return None, "在 %s 里找不到 `MIN_SLICE_N = <数字>` —— 常量被改名了？" % target
    return int(m.group(1)), None

DASH = "—"


# ================================================================
# 小工具
# ================================================================

def num(v, digits=None):
    """渲染一个数。★ None 一律渲染成 DASH —— 那是「不可判」，不是 0。"""
    if v is None:
        return DASH
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float):
        return ("%." + str(digits) + "f") % v if digits is not None else ("%.6g" % v)
    return str(v)


def rat(v, digits=4):
    """比例。★ 一律印成 x.xxxx —— 报告里不同小节的小数位数必须一致，
    否则读者会以为 0.97 和 0.9692 是不同的精度而不是不同的写法。"""
    if v is None:
        return DASH
    return ("%." + str(digits) + "f") % float(v)


def signed(v, digits=4):
    if v is None:
        return DASH
    return ("%+." + str(digits) + "f") % float(v)


def signed_auto(v):
    """Δ 的位数按量级选。★ 一张表里同时有 `recall@5`（±0.008）和 `总耗时ms`（±114），
    固定 4 位会让后者变成 `-114.1634` —— 那个精度是假的，会让读者以为延迟被精确量到了 0.1 毫秒。"""
    if v is None:
        return DASH
    return signed(v, 1 if abs(float(v)) >= 10 else 4)


def n_of(node):
    return node.get("n") if isinstance(node, dict) else None


def is_ratio(node):
    return isinstance(node, dict) and "值" in node and "n" in node


def ratio_identity(node):
    """两轮「一不一致」的判据。

    ★★ 先比 (命中, n)，没有 命中 才退到 (值, n)。
      只比 `值` 会漏掉一类：分子分母同时变、比值恰好相同 ——
      那两轮的总体不是同一个，但报告会打勾。
      本项目最反对的就是「一个数旁边挂着错的口径」。
    """
    if not is_ratio(node):
        return None
    if "命中" in node:
        return ("命中/n", node.get("命中"), node.get("n"))
    return ("值/n", node.get("值"), node.get("n"))


def pair_ratio(a, b):
    """两轮并排渲染一个 {n,命中,值} 节点。

    返回 (markdown 单元格, 是否一致)。
    ★ 分母也印出来 —— 报告里每个比率旁边有分母是本项目的硬要求。
    """
    if a is None and b is None:
        return DASH, True
    # ★ 不是 `{n,命中,值}` 形状的（比如 `兜底.★兜底误判率` 就是个裸 float）
    #   一律退到标量比较 —— 这比抛异常好，也比强行当比率读好。
    if not is_ratio(a) or not is_ratio(b):
        return pair_scalar(a, b)
    ia, ib = ratio_identity(a), ratio_identity(b)
    same = (ia == ib)
    if same:
        node = a if a is not None else b
        if "命中" in node:
            body = "%s/%s = %s" % (num(node.get("命中")), num(node.get("n")), rat(node.get("值")))
        else:
            body = "%s (n=%s)" % (rat(node.get("值")), num(node.get("n")))
        return body + " ✅", True
    def one(node):
        if node is None:
            return DASH
        if "命中" in node:
            return "%s/%s = %s" % (num(node.get("命中")), num(node.get("n")), rat(node.get("值")))
        return "%s (n=%s)" % (rat(node.get("值")), num(node.get("n")))
    return "%s / %s ⚠️" % (one(a), one(b)), False


def pair_scalar(a, b, digits=None):
    if a is None and b is None:
        return DASH, True
    if a == b:
        return num(a, digits) + " ✅", True
    return "%s / %s ⚠️" % (num(a, digits), num(b, digits)), False


def pair_custom(a, b, render, identity):
    """给形状不统一的节点用：自己给「怎么印」和「怎么判相等」。

    ★ 需要它的原因是 `过度检索` 那两个节点的分母叫 `上下文条数`，而且**越大越好**的
      语义是反的（它是越界率）。硬套 `命中/n` 会印出一句读起来完全相反的话。
    """
    if a is None and b is None:
        return DASH, True
    if identity(a) == identity(b):
        return render(a if a is not None else b) + " ✅", True
    return "%s / %s ⚠️" % (render(a), render(b)), False


def pair_int(a, b):
    return pair_scalar(a, b)


# ----------------------------------------------------------------
# bootstrap
# ----------------------------------------------------------------

def bootstrap_ci(values, iters=BOOTSTRAP_ITERS, seed=BOOTSTRAP_SEED):
    """百分位 bootstrap 95% 区间。

    ★ 种子固定 —— 报告必须可复算，否则同一份数据两次生成会给出两个区间，
      而「哪个对」这个问题没有答案（都对，只是抽样不同）。
    ★ values 的顺序必须在调用前定下来（按题号排序），否则 seed 一样结果也会变。

    ⚠️ 它只反映【抽样】不确定性（n 个样本换一批会怎样）。
      它【不含】judge 侧噪声、生成侧噪声。报告里必须写清这一条 ——
      否则读者会把一个窄区间读成「这个数很准」。
    """
    vals = sorted(float(v) for v in values if v is not None)
    n = len(vals)
    if n == 0:
        return None, None
    if n == 1:
        return vals[0], vals[0]
    rnd = random.Random(seed)
    means = []
    for _ in range(iters):
        s = 0.0
        for _ in range(n):
            s += vals[rnd.randrange(n)]
        means.append(s / n)
    means.sort()
    lo = means[int(0.025 * iters)]
    hi = means[min(iters - 1, int(0.975 * iters))]
    return lo, hi


def mean_of(values):
    vals = [float(v) for v in values if v is not None]
    return (sum(vals) / len(vals)) if vals else None


# ----------------------------------------------------------------
# markdown
# ----------------------------------------------------------------

def table(headers, rows):
    out = ["| " + " | ".join(headers) + " |",
           "|" + "|".join(["---"] * len(headers)) + "|"]
    for r in rows:
        out.append("| " + " | ".join("" if c is None else str(c) for c in r) + " |")
    return out


def md_text(s):
    """表格单元格里的自由文本：竖线会拆表。"""
    return str(s).replace("|", "\\|").replace("\n", " ")


# ----------------------------------------------------------------
# 代码版本
# ----------------------------------------------------------------

def git_version():
    """报告头要能回答「这一串数字对应哪一版代码」。

    ★★ 阶段 7 的全部改动都还没提交。所以只写 HEAD 是不诚实的 ——
      它指向阶段 6 的里程碑，而数字来自工作区。
      工作区有改动时必须写出来，并把改动文件数报出来。
    """
    def run(args):
        try:
            p = subprocess.run(args, cwd=ROOT, capture_output=True, text=True,
                               encoding="utf-8", errors="replace")
            return (p.stdout or "").strip() if p.returncode == 0 else ""
        except OSError:
            return ""
    head = run(["git", "rev-parse", "--short", "HEAD"]) or "(拿不到)"
    subject = run(["git", "log", "-1", "--format=%s"])
    dirty = [ln for ln in run(["git", "status", "--porcelain"]).splitlines() if ln.strip()]
    return head, subject, dirty


# ================================================================
# 读
# ================================================================

def load_run(spec, refresh=False, base=eval_ab.DEFAULT_BASE):
    if spec is None:
        return None
    d = eval_ab.resolve_dir(spec)
    raw, report = eval_ab.load(d, refresh, base)
    if raw.get("runId") != report.get("runId"):
        raise SystemExit("★ runId 对不上：raw=%s report=%s —— 这两个文件不是同一轮的"
                         % (raw.get("runId"), report.get("runId")))
    rows = {r["题号"]: r for r in report["逐题"]["行"]}
    return {"dir": d, "raw": raw, "report": report, "rows": rows, "runId": raw["runId"]}


def load_ragas(rel, label):
    if rel is None:
        return None
    path = rel if os.path.isabs(rel) else os.path.join(RESULTS, rel)
    if not os.path.isfile(path):
        raise SystemExit("找不到 RAGAS 结果：%s" % path)
    with io.open(path, encoding="utf-8") as f:
        d = json.load(f)
    d["_path"] = path
    d["_label"] = label
    return d


def ragas_rows_with_scores(rag):
    """把 RAGAS 逐题里 【每个指标都成功】 的那些挑出来。

    ★★ 一条题的四个指标 n 不一样（answer_relevancy 少了 3 条、answer_correctness 少了 2 条）。
      「全部成功的 43 题」这个子集是不存在的 —— 硬凑会悄悄改掉每一列的 n。
      所以：**每个指标各算各的分母**，报告里每一列都印它自己的 n。
    """
    out = {}
    for m in RAGAS_METRICS:
        out[m] = {r["questionNo"]: r["scores"].get(m) for r in rag["逐题"]
                  if r.get("scores", {}).get(m) is not None}
    return out


# ================================================================
# 路径取值
# ================================================================

_ACCESSED = set()


def get_path(report, path):
    """★ 顺手记下「报告访问过哪些路径」。

    ★★ 这是 coverage 检查的数据来源。**用「访问记录」而不是「文本匹配」** ——
      文本匹配会漏（`兜底.分母_标注为兜底的题` 在正文里写成一整句话，
      逐字比对就对不上），而漏掉的后果是**这个检查慢慢变成噪声，然后被无视**。
      访问记录是精确的，而且**跟着代码自动更新**。
    """
    _ACCESSED.add(path)
    node = report
    for p in path.split("."):
        if not isinstance(node, dict) or p not in node:
            return None
        node = node[p]
    return node


def mark_accessed(path):
    _ACCESSED.add(path)


def ratio_row(label, path, ra, rb, note="", indent=0):
    """一行两轮并排的比率。★ 口径说明是可选的，但空着的时候要问自己一遍
    「读者知道这个分母是怎么来的吗」。"""
    cell, _same = pair_ratio(get_path(ra, path), get_path(rb, path))
    return ["　" * indent + label, cell, note]


def add_ratio_section(out, title, ra, rb, spec, intro=None, col="指标"):
    out.append("### %s" % title)
    out.append("")
    if intro:
        out.extend(intro)
        out.append("")
    rows = [ratio_row(lbl, path, ra, rb, note, ind) for lbl, path, note, ind in spec]
    out.extend(table([col, "值（噪声底轮 / 主体轮）", "口径"], rows))
    out.append("")
    return out


def section_paths(report):
    """report.json 里【除逐题以外】的全部叶子路径。用于报告覆盖率自检。"""
    flat = eval_ab.leaves({k: v for k, v in report.items() if k != "逐题"})
    return set(flat)


# ================================================================
# §0 报告头
# ================================================================

def render_header(runs):
    main = runs["main"]
    out = []
    head, subject, dirty = git_version()

    out.append("- **主体轮**：`%s`（%s）" % (main["runId"], main["raw"].get("questionSet")))
    out.append("- **代码版本**：`%s` %s" % (head, subject))
    if dirty:
        out.append("  - ⚠️ **工作区有 %d 项未提交改动** —— 上面那个 SHA 指向的是**阶段 6 的里程碑**，"
                   "而这一串数字来自工作区。复现要对上工作区，不是 `git checkout %s`。" % (len(dirty), head))
    else:
        out.append("  - ✅ 工作区干净 —— 上面那个 SHA 就是产生这些数字的代码")

    out.append("")
    out.append("**这一期的四轮**")
    out.append("")
    rows = []
    for role, label in [("main", "主体"), ("noise", "噪声底"), ("baseline", "阶段 4 基线"),
                        ("baseline_bad", "假配置"), ("multi", "多轮集")]:
        r = runs.get(role)
        if r is None:
            continue
        raw = r["raw"]
        qs = raw.get("questionSet") or []
        rows.append([
            "%s（%s）" % (label, role),
            "`%s`" % r["runId"],
            qs if isinstance(qs, str) else ",".join(str(x) for x in qs),
            num(raw.get("repeat")),
            "✅" if raw.get("complete") else "❌",
            "✅" if raw.get("reconcileOk") else "❌",
            num(len(raw.get("submissions") or [])),
            num(raw.get("clientCost")),
        ])
    out.extend(table(["角色", "runId", "题集", "重复", "完整", "对账", "提交数", "客户端成本(元)"], rows))

    out.append("")
    out.append("**语料 / 切分 / 标注集指纹**（洞 7：任一不同 → 禁止跨轮逐题 diff）")
    out.append("")
    seen = []
    for role in ["main", "noise", "baseline", "baseline_bad", "multi"]:
        r = runs.get(role)
        if r is None:
            continue
        fp = r["raw"].get("fingerprint") or {}
        seen.append((role, r["runId"], fp))
    rows = []
    for role, runId, fp in seen:
        c, ch, g = fp.get("corpus", {}), fp.get("chunking", {}), fp.get("gold", {})
        rows.append(["`%s`" % runId,
                     "%s 片 / maxId %s" % (num(c.get("chunks")), num(c.get("maxId"))),
                     "`%s`" % num(c.get("hash")),
                     "%s 键" % num(ch.get("keys")),
                     "`%s`" % num(ch.get("hash")),
                     "%s 题 / %s 片" % (num(g.get("questions")), num(g.get("goldIds"))),
                     "`%s`" % num(g.get("hash"))])
    out.extend(table(["runId", "语料片数", "语料指纹", "切分键", "切分指纹", "标注集", "标注集指纹"], rows))

    out.append("")
    # ★★ 判据必须是【逐对】的，不是【全体一致】。
    #    20 题的 baseline 和 159 题的 stage7 的【标注集指纹本来就应该不同】——
    #    它们跑的是不同的题库。用「全体一致」当判据会让报告自己喊「不成立」。
    #    真正要守的是：**报告里真的做了逐题 diff 的那两对**，指纹必须一致。
    diffs = [("noise", "main", "噪声底（§1、§2、§8）"),
             ("baseline_bad", "baseline", "假配置验证（§7）")]
    bad_pairs = []
    for x, y, _why in diffs:
        if runs.get(x) is None or runs.get(y) is None:
            continue
        problems = eval_ab.fingerprint_check(runs[x]["raw"], runs[y]["raw"])
        if problems:
            bad_pairs.append((runs[x]["runId"], runs[y]["runId"], problems))
    out.append("**逐对判据**（只有真的做了逐题 diff 的那几对需要指纹一致）：")
    out.append("")
    for x, y, why in diffs:
        if runs.get(x) is None or runs.get(y) is None:
            continue
        probs = eval_ab.fingerprint_check(runs[x]["raw"], runs[y]["raw"])
        out.append("- %s：`%s` ↔ `%s` —— %s"
                   % (why, runs[x]["runId"], runs[y]["runId"],
                      "✅ 语料/切分/标注集指纹一致" if not probs else "❌ " + "；".join(probs)))
    out.append("")
    others = [r for r in ("main", "baseline", "multi") if runs.get(r)]
    if len(others) > 1:
        out.append("★ 其余轮次之间**没有做逐题 diff**（题库不同、或只是纵向对比），"
                   "所以标注集指纹不同**是正常的**，不构成问题。")
    if bad_pairs:
        out.append("")
        out.append("❌ **上面有某一对指纹不一致 → 那一节的跨轮 diff 不成立，不该被引用。**")
    return out


# ================================================================
# §1 噪声底
# ================================================================

def render_noise_floor(runs, rag=None):
    """★★★ 报告的第一节就是它。理由：后面每一个「变了 / 没变」都要先跟这个数比。"""
    a, b = runs["noise"], runs["main"]
    out = []
    out.append("同配置两轮。**这一节是下面所有差异的标尺。**")
    out.append("")

    problems = eval_ab.fingerprint_check(a["raw"], b["raw"])
    if problems:
        out.append("❌ **指纹不一致，噪声底不成立**：" + "；".join(problems))
        return out

    differ, only_a, only_b, total = eval_ab.config_diff(a["raw"], b["raw"])
    out.append("- 两轮共有的 `xbla.*` 配置键：**%d** 个" % total)
    if differ:
        out.append("- ❌ **值不同的键 %d 个** —— 这两轮不是同配置，下面的数不是噪声底：" % len(differ))
        out.extend(["  - `%s`：%s → %s" % (k, v1, v2) for k, v1, v2 in differ])
    else:
        out.append("- ✅ **值不同的键 0 个** —— 共有键逐字相同")
    if only_a or only_b:
        out.append("- ⚠️ 只在一边出现的键 %d 个（**不算配置差异** —— 快照记录的是跑那一轮时 yml 里有什么，"
                   "缺席时生效的是代码默认值；算成差异会让自比对谎报「配置改了」）" % (len(only_a) + len(only_b)))

    ra, rb, common, only_ra, only_rb = eval_ab.common_rows(a["report"], b["report"])
    out.append("- 逐题交集：**%d** 道" % len(common))
    if only_ra or only_rb:
        out.append("  - ⚠️ 只在一边：%s / %s" % (only_ra, only_rb))

    rows, details = eval_ab.flip_table(ra, rb, common)
    rows2, det2 = eval_ab.continuous_table(ra, rb, common)

    out.append("")
    out.append("### 1.1 分类判据：翻了几格")
    out.append("")
    out.extend(table(["判据", "共同可比", "翻转", "翻转率"],
                     [[m, num(c), num(f), rat(f / c if c else None)]
                      for m, c, f in rows]))
    flipped = set()
    for m, _c, _f in rows:
        for q, _va, _vb in details[m]:
            flipped.add(q)
    out.append("")
    out.append("★ **至少翻一格的题：%s / %s = %s**（这是这一轮「一个数字能有多稳」的直接答案）"
               % (num(len(flipped)), num(len(common)),
                  rat(len(flipped) / len(common) if common else None)))

    out.append("")
    out.append("### 1.2 连续量：变了多少")
    out.append("")
    out.extend(table(["判据", "共同可比", "均值 Δ（噪声底 → 主体）", "变了的题数"],
                     [[m, num(c), signed_auto(d), num(mv)] for m, c, d, mv in rows2]))

    out.append("")
    out.append("### 1.3 ★★ 怎么读这两个表")
    out.append("")
    # ★★ 判据句：数据变了，说的就不一样。
    d = dict((m, (c, f)) for m, c, f in rows)
    churn = d.get("跨次序列变了", (0, 0))
    hit = d.get("命中@5", (0, 0))
    gate = d.get("闸门", (0, 0))
    intent = d.get("意图正确", (0, 0))
    if churn[1] > 0 and hit[1] * 2 < churn[1]:
        out.append("① ★★ **`跨次序列变了` 是最大的噪声源**：%s/%s = %s。"
                   "但 `命中@5` 只翻了 %s/%s —— 同配置两轮的序列 churn "
                   "**基本不改变「有没有命中」，只改变「命中了哪几条、什么顺序」**。"
                   % (num(churn[1]), num(churn[0]), rat(churn[1] / churn[0] if churn[0] else None),
                      num(hit[1]), num(hit[0])))
        st = ragas_overlap_stats(runs, rag)
        if st is not None and not st["gold"] and not st["ctxlen"]:
            out.append("   - ★★ **直接推下去会得到一个错误的结论**，所以去看 §4.6："
                       "在 RAGAS 抽样的 %d 道上，**正解命中集合与上下文条数一道都没变**（只有 %d 道顺序变了）。"
                       "⇒ 对「命中了哪几条」敏感的指标，**在这一期的证据下检索侧波动是零** —— "
                       "它的波动（如果有）来自别处（生成侧 / judge 侧，那两个都没有数）。"
                       % (len(st["common"]), len(st["seq"])))
        elif st is not None:
            out.append("   - ★★ **§4.6 实测到了这条推论**：%d 道题的正解命中集合变了。"
                       % len(st["gold"]))
        else:
            out.append("   - ⚠️ **推论**：任何对「命中了哪几条」敏感、对「有没有命中」不敏感的指标"
                       "（比如按陈述级匹配的上下文召回），它的波动带**可能比 `命中@5` 大**。"
                       "★ 这只是一个推论 —— §4.6 用 RAGAS 的抽样去检验它。")
    elif churn[1] > 0:
        out.append("① ⚠️ **序列 churn 与命中翻转同量级**（%s vs %s）—— "
                   "这意味着序列抖动会改变命中结果，和上一期不同，值得追。"
                   % (num(churn[1]), num(hit[1])))
    if gate[1] == 0 and gate[0] > 0:
        out.append("② **闸门 %s/%s 翻转 = 0** —— 澄清闸门是**跨轮稳定**的。"
                   "⚠️ 这和「同一次 run 内 3 次重复之间的闸门抖动」是**两个口径**，不能混着说。"
                   % (num(gate[1]), num(gate[0])))
    if intent[1] and hit[1] and intent[1] != hit[1]:
        out.append("③ `意图正确` 翻 %s 道而 `命中@5` 翻 %s 道 —— **不矛盾**。"
                   "`意图正确` 用**3 次的众数**，`命中@5`/`归因` 用**第一次 status=1 的那次**。"
                   "重复之间不一致时两者会各走各的 —— **这两族指标不是完全耦合的**。"
                   % (num(intent[1]), num(hit[1])))
    out.append("")
    out.append("**因此：下面任何一格「变了」，先跟 `%s`（%d 道）和这张表比大小。**"
               % (rat(len(flipped) / len(common) if common else None), len(flipped)))
    return out


# ================================================================
# §2 主指标表（主体轮 / 噪声底轮 并排）
# ================================================================

def _flip_rate(runs):
    ra, rb, common, _oa, _ob = eval_ab.common_rows(runs["noise"]["report"], runs["main"]["report"])
    rows, details = eval_ab.flip_table(ra, rb, common)
    flipped = set()
    for m, _c, _f in rows:
        for q, _va, _vb in details[m]:
            flipped.add(q)
    return len(flipped) / len(common) if common else 0.0


def ratio_rows(spec, ra, rb):
    """spec = [(标签, 路径, 口径, 缩进)] → markdown 行。"""
    out = []
    for lbl, path, note, ind in spec:
        cell, _s = pair_ratio(get_path(ra, path), get_path(rb, path))
        out.append(["　" * ind + lbl, cell, note])
    return out


# ----------------------------------------------------------------
# §2.10 / §2.11 的四格（阶段 9.6）
#
# ★ 键名是【字面量】，和 Java 侧 EvalReportService 里那两个 put 一一对应。
#   Java 侧改一个 ★ 或一个全角括号，这里就静默印成 `—`（见 docs/10 坑 46），
#   所以这些字符串集中放在一处，而不是散在渲染代码里。
# ----------------------------------------------------------------

RD_CELLS = [
    ("★声明不检索_实际也没检索", "对"),
    ("★声明不检索_实际检索了（过度检索）", "★ 修法：**收窄**"),
    ("★声明要检索_实际检索了", "对"),
    ("★声明要检索_实际没检索（过度关闭）", "★ 修法：**放宽**"),
]
RD_DENOM_QUESTIONS = ["分母_声明不检索的题", "分母_声明要检索的题", "平票的题数"]
RD_DENOM_ROWS = ["分母_声明不检索的行", "分母_声明要检索的行"]

TC_CELLS = [
    ("★该有工具_确实调了", "对"),
    ("★该有工具_一次没调", "★★ 9.1 修掉的那个形状"),
    ("★不该有工具_没调", "对"),
    ("★不该有工具_却调了", "★ 串味 —— 工具给错了轮"),
]
TC_DENOM_QUESTIONS = ["分母_该有工具的题", "分母_不该有工具的题"]
TC_DENOM_ROWS = ["分母_该有工具的行", "分母_不该有工具的行"]

# ★ 阶段 9.6a：槽位声明（`missing`）的去向。见 `_render_slot_declaration`。
SD_KEYS = [
    ("分母_行", "本轮的全部行"),
    ("★其中·没有计划的行", "★ 非 0 = `intent_plan` 没落库或解析不了 —— 先修它"),
    ("分母_声明缺槽位的行", "★ 模型自己说「还缺什么」的行（`missing` 是数组且非空）"),
    ("声明缺槽位_落在澄清分支（反问了）", "⚠️ **定义性**的一格，当对照用，不是发现"),
    ("★★声明缺槽位_落在业务码（直接作答）", "★★ **模型的这个输出没被消费**"),
    ("分母_反问的行", "★ 不筛 `missing`；分母是**有计划的行**（没计划的在上面单独数了）"),
    ("★其中·没声明缺槽位却反问了", "★ 非 0 = 走了回落文案（缺 `missing`/缺模板）"),
]


def render_metrics(runs):
    a, b = runs["noise"], runs["main"]     # A = 噪声底轮，B = 主体轮
    ra, rb = a["report"], b["report"]
    out = []
    out.append("两轮并排。**左值是噪声底轮，右值是主体轮**（`✅` = 两轮逐字相同，`⚠️` = 不同）。")
    out.append("")
    out.append("⚠️ **`⚠️` 不等于「配置改坏了」** —— §1 量过：同配置下就有 **%s** 的题至少翻一格。"
               % rat(_flip_rate(runs)))
    out.append("")

    # ---------- 2.0 数据完整性 ----------
    out.append("### 2.0 数据完整性（★ 先确认有东西可算）")
    out.append("")
    ia, ib = get_path(ra, "数据完整性") or {}, get_path(rb, "数据完整性") or {}
    rows = [
        ["跑过的题数", num(ia.get("跑过的题数")), num(ib.get("跑过的题数"))],
        ["qa_log 行数", num(ia.get("行数")), num(ib.get("行数"))],
    ]
    sa, sb = ia.get("按status") or {}, ib.get("按status") or {}
    for k in sorted(set(sa) | set(sb), key=str):
        rows.append(["　其中 `status=%s`" % k, num(sa.get(k)), num(sb.get(k))])
    rows.append(["　其中 声明要检索的", num(ia.get("  其中 声明要检索的")), num(ib.get("  其中 声明要检索的"))])
    rows.append(["　其中 声明不检索的", num(ia.get("  其中 声明不检索的")), num(ib.get("  其中 声明不检索的"))])
    rows.append(["★ 在题库里但这一轮没跑",
                 _codes(ia.get("★在题库里但这一轮没跑的题号") or []) or "**无**",
                 _codes(ib.get("★在题库里但这一轮没跑的题号") or []) or "**无**"])
    rows.append(["★ 跑了但题库里没有",
                 _codes(ia.get("★跑了但题库里没有的题号") or []) or "**无**",
                 _codes(ib.get("★跑了但题库里没有的题号") or []) or "**无**"])
    out.extend(table(["", "噪声底轮", "主体轮"], rows))
    out.append("")
    out.append("★★ **两个都必须是「无」** —— 对账（跑题器的提交数 vs `qa_log` 行数）已经保证过一次，"
               "这里是**第二次**：它比的是「题库」与「实际跑了的题」。")
    out.append("")
    out.append("★ `status` 的语义：`1` 成功 / `2` 模型链路失败 / `3` 澄清反问 / `4` 被限流拒。"
               "**指标只统计 `status=1`**（`3` 是「没生成也没失败」，不是错误）。")
    out.append("")

    # ---------- 2.1 意图 ----------
    out.append("### 2.1 意图")
    out.append("")
    rows = ratio_rows([
        ("逐行准确率", "意图.逐行", "分母 = `intent` 非空的行（含被闸门挡掉的）", 0),
        ("逐题取众数", "意图.逐题取众数", "★ 含 gold 不是合法分类目标的题（恒错）", 0),
        ("★★ 可比口径", "意图.★★可比口径的准确率",
         "★ 剔除 gold 不是合法分类目标的题 —— **这是该引用的那个数**", 0),
    ], ra, rb)
    n_all = get_path(rb, "意图.逐题取众数.n")
    cnt_a = get_path(ra, "意图.★非全票一致的题（主指标）.n")
    cnt_b = get_path(rb, "意图.★非全票一致的题（主指标）.n")
    denom_b = get_path(rb, "意图.★非全票一致的题（主指标）.分母")
    rows.append(["★ 非全票一致的题（主指标）",
                 ("%s（分母 %s）" % (num(cnt_b), num(denom_b))) if cnt_a == cnt_b
                 else ("%s / %s（分母 %s）⚠️" % (num(cnt_a), num(cnt_b), num(denom_b))),
                 "6:2 / 7:1 这种**有明确众数、但换个时间跑就可能翻**的题"])
    rows.append(["无唯一众数的题（诊断）",
                 pair_int(get_path(ra, "意图.无唯一众数的题（诊断）.n"),
                          get_path(rb, "意图.无唯一众数的题（诊断）.n"))[0],
                 "⚠️ **几乎永远是 0，别盯着它** —— 真正的不稳定长成 6:2，不是平票"])
    rows.append(["★★ gold 不是合法分类目标的题",
                 pair_int(get_path(ra, "意图.★★gold不是合法分类目标的题.n"),
                          get_path(rb, "意图.★★gold不是合法分类目标的题.n"))[0]
                 + "（分母 %s）" % num(n_all),
                 "工具叶子码 —— 模型**永远不可能输出**，见 §2.1.1"])
    if get_path(rb, "意图.★一次都没分类成功的题"):
        rows.append(["★ 一次都没分类成功的题",
                     _codes(get_path(rb, "意图.★一次都没分类成功的题")), "3 次全都没拿到意图"])
    tool = get_path(rb, "意图.★工具题判到顶层这一级.误路由的题") or []
    if tool:
        rows.append(["★ 工具题的误路由（单列）", "%d 道" % len(tool),
                     "见下面那条 * —— 它量的是这 15 道题**真正在测的东西**"])
    out.extend(table(["指标", "值（噪声底轮 / 主体轮）", "口径"], rows))
    if tool:
        out.append("")
        out.append("\\* **工具题判到顶层这一级**：%s" % "；".join("`%s`" % md_text(t) for t in tool))
    for role, rep in (("噪声底轮", ra), ("主体轮", rb)):
        ns = get_path(rep, "意图.★非全票一致的题（主指标）.题号") or []
        out.append("")
        out.append("★ **%s「非全票一致」的题（%d 道）**：%s"
                   % (role, len(ns), _codes(ns) or "无"))
    out.append("")
    out.append("★ 这些题**有明确众数、分数也不低**，但它们标记的是「**换个时间跑就可能翻**」—— "
               "它们是本报告里「哪些数字别引用得太死」的直接答案。")
    n_tool = get_path(rb, "意图.★★gold不是合法分类目标的题.n")
    if tool and n_tool:
        out.append("")
        out.append("★ 上表那 %d 道工具题在「逐题取众数」里**恒错**，但它们**不是没法测** —— "
                   "把它们降一级到顶层码再看，得到的才是「模型有没有把工具意图路由对」。"
                   "**两个数都在，才构成完整的意图图景。**" % n_tool)

    # ★★ 两套粒度的接缝
    out.append("")
    out.append("#### 2.1.1 ★★ 为什么意图准确率有两个数（差的就是那几道工具题）")
    out.append("")
    out.append("`IntentTree.classificationTargets()` 的规则是「行为相同的不区分」：")
    out.append("")
    out.append("```")
    out.append("retrieval = KB   →  展开到【叶子】")
    out.append("retrieval ≠ KB   →  只算【一个】目标，用【顶层 code】")
    out.append("```")
    out.append("")
    n_unreach = get_path(rb, "意图.★★gold不是合法分类目标的题.n")
    unreach = get_path(rb, "意图.★★gold不是合法分类目标的题.题号") or []
    row_all = get_path(rb, "意图.逐题取众数") or {}
    row_cmp = get_path(rb, "意图.★★可比口径的准确率") or {}
    if n_unreach and row_all.get("n") and row_cmp.get("n"):
        gap = (row_cmp.get("值") or 0) - (row_all.get("值") or 0)
        codes = sorted({str(t).split("（gold=")[-1].rstrip("）") for t in unreach if "（gold=" in str(t)})
        out.append("所以 `ORDER_LOGISTICS` 是合法输出，而 **`%s` 这几个工具叶子码模型永远不可能输出** "
                   "—— 而题库里那 **%s 道**工具题的 gold 恰恰是它们。"
                   % ("` / `".join(codes) or "工具叶子", num(n_unreach)))
        out.append("")
        out.append("```")
        out.append("逐题取众数      %s/%s = %s    ← 含 %s 道恒错的题"
                   % (num(row_all.get("命中")), num(row_all.get("n")), rat(row_all.get("值")), num(n_unreach)))
        out.append("★★ 可比口径     %s/%s = %s    ← 剔除 gold 不是合法分类目标的题"
                   % (num(row_cmp.get("命中")), num(row_cmp.get("n")), rat(row_cmp.get("值"))))
        out.append("差              %s 个百分点，与分类质量无关" % num(round(gap * 100, 2)))
        out.append("```")
        out.append("")
        out.append("★ 这不是 bug，是「两套粒度」必然的接缝。**但一个数字旁边挂着错的口径就不可解释** —— "
                   "所以两个数都报，并写清哪个是哪个。")
        out.append("")
        out.append("★★ **它有可测量的质量后果，不只是记账问题** —— 见 §4.3："
                   "那 %s 道题的 `context_recall` 是 **0**。" % num(len(unreach)))

    # ---------- 2.2 澄清边界 ----------
    out.append("")
    out.append("### 2.2 澄清边界（混淆矩阵）")
    out.append("")
    tp = pair_int(get_path(ra, "澄清边界.逐行.★该反问_且反问了"),
                  get_path(rb, "澄清边界.逐行.★该反问_且反问了"))[0]
    fn = pair_int(get_path(ra, "澄清边界.逐行.★该反问_没反问（漏）"),
                  get_path(rb, "澄清边界.逐行.★该反问_没反问（漏）"))[0]
    fp = pair_int(get_path(ra, "澄清边界.逐行.★不该反问_却反问了（假阳）"),
                  get_path(rb, "澄清边界.逐行.★不该反问_却反问了（假阳）"))[0]
    tn = pair_int(get_path(ra, "澄清边界.逐行.不该反问_没反问"),
                  get_path(rb, "澄清边界.逐行.不该反问_没反问"))[0]
    out.extend(table(["（噪声底轮 / 主体轮）", "该反问（标注）", "不该反问（标注）"], [
        ["**反问了**", "TP %s" % tp, "FP %s ← 假阳" % fp],
        ["**没反问**", "FN %s ← 漏" % fn, "TN %s" % tn],
    ]))
    out.append("")
    out.append("边际和：**该反问的行 %s / %s**、**不该反问的行 %s / %s**（噪声底 / 主体）。"
               % (num(get_path(ra, "澄清边界.逐行.分母_该反问的行")),
                  num(get_path(rb, "澄清边界.逐行.分母_该反问的行")),
                  num(get_path(ra, "澄清边界.逐行.分母_不该反问的行")),
                  num(get_path(rb, "澄清边界.逐行.分母_不该反问的行"))))
    out.append("★ **没有边际和的混淆矩阵是半个矩阵** —— 4 个格子能算出比例，"
               "但「%s 个假阳」是多是少，取决于分母。")
    out.append("")
    out.extend(table(["指标", "值（噪声底轮 / 主体轮）", "口径"], ratio_rows([
        ("放行率", "澄清边界.放行率",
         "分母 = 逐题（3 次里**任一次**被放行就算放行）", 0),
    ], ra, rb) + [
        ["★ 闸门抖动的题",
         pair_int(get_path(ra, "澄清边界.★闸门抖动的题.n"),
                  get_path(rb, "澄清边界.★闸门抖动的题.n"))[0],
         "★ **同一次 run 内 3 次之间** status 不一致 —— 探针测不出来的那一类"],
    ]))
    qa_ = get_path(ra, "澄清边界.反问了哪些题") or []
    qb_ = get_path(rb, "澄清边界.反问了哪些题") or []
    out.append("")
    out.append("- 噪声底轮反问了（%d 道）：%s" % (len(qa_), _codes(qa_) or "无"))
    out.append("- 主体轮反问了（%d 道）：%s" % (len(qb_), _codes(qb_) or "无"))
    gate_a = get_path(ra, "澄清边界.★闸门抖动的题.题号") or []
    gate_b = get_path(rb, "澄清边界.★闸门抖动的题.题号") or []
    out.append("")
    out.append("- 噪声底轮抖动：%s" % (_codes(gate_a) or "无"))
    out.append("- 主体轮抖动：%s" % (_codes(gate_b) or "无"))
    uncounted = get_path(rb, "澄清边界.逐行.标注为澄清但未计的行")
    if uncounted:
        out.append("- ⚠️ **标注为澄清但未计入的行**：%s —— 它们在 3 次里既没成功也没反问"
                   "（通常是 `status=2`），所以两种判据都用不上。" % num(uncounted))

    # ---------- 2.3 检索范围 ----------
    out.append("")
    out.append("### 2.3 检索范围")
    out.append("")
    out.extend(table(["指标", "值（噪声底轮 / 主体轮）", "口径"], ratio_rows([
        ("★ 主数字（KB 题）", "检索范围.★主数字_KB题",
         "★ 只在 `retrieval=KB` 的题上算 —— 非检索题两侧都是空集，恒真", 0),
        ("全体", "检索范围.全体", "含非检索题", 0),
        ("非 KB 题（单列）", "检索范围.非KB题_单列",
         "⚠️ 它量的是「工具题有没有被判成别的顶层码」，不是「范围选对了没有」", 0),
    ], ra, rb)))
    out.append("")
    out.append("- 噪声底轮不一致：%s" % (_codes(get_path(ra, "检索范围.不一致的题") or []) or "无"))
    out.append("- 主体轮不一致：%s" % (_codes(get_path(rb, "检索范围.不一致的题") or []) or "无"))
    fake = get_path(rb, "检索范围.★模型编了不存在的码的题") or []
    out.append("- ★ **模型编了不存在的码的题**：%s" % (_codes(fake) if fake else "**无**（这一项必须是 0 —— 非 0 就是分类器的输出没被约束住）"))

    # ---------- 2.4 兜底 ----------
    out.append("")
    out.append("### 2.4 兜底误判率")
    out.append("")
    n_fallback = get_path(rb, "兜底.分母_标注为兜底的题")
    out.extend(table(["指标", "值（噪声底轮 / 主体轮）", "口径"], [
        ["★ 兜底误判率",
         pair_scalar(get_path(ra, "兜底.★兜底误判率"), get_path(rb, "兜底.★兜底误判率"), 4)[0],
         "★ 分母 = %s（标注为兜底的题）—— 该兜底的题被判成**业务意图**的比例"
         % num(n_fallback)],
        ["被判成业务意图的题",
         pair_int(get_path(ra, "兜底.★被判成业务意图的题"),
                  get_path(rb, "兜底.★被判成业务意图的题"))[0], "分子"],
        ["可信（n ≥ %d）" % MIN_SLICE_N,
         pair_scalar(get_path(ra, "兜底.可信"), get_path(rb, "兜底.可信"))[0],
         "★ 切片纪律同样管它"],
    ]))
    out.append("")
    out.append("⚠️ **`兜底误判率` 和 `过度检索率` 名字很像，修法完全相反** —— "
               "前者调分类器的边界，后者调意图树的 `doc_types`。别合起来看。")
    out.append("")
    out.append("★★ **但 n = %s 的切片本来就该谨慎读** —— 「0 个误判」最乐观的解释是「一个都没错」，"
               "最悲观的解释是「样本太少，还没轮到出错」。**这 %s 个样本支持两个解释。**"
               % (num(n_fallback), num(n_fallback)))

    # ---------- 2.5 检索 ----------
    out.append("")
    out.append("### 2.5 检索质量")
    out.append("")
    rows = ratio_rows([
        ("HitRate@1", "检索.HitRate@1", "", 0),
        ("HitRate@3", "检索.HitRate@3", "", 0),
        ("HitRate@5", "检索.HitRate@5", "", 0),
        ("Recall@5", "检索.Recall@5", "★ 不四舍五入，逐题行能精确重算它", 0),
        ("MRR@5", "检索.MRR@5", "", 0),
    ], ra, rb)
    rows.append(["★ 跨重复序列变了的题",
                 pair_int(get_path(ra, "检索.★跨重复送进prompt的序列变了的题.n"),
                          get_path(rb, "检索.★跨重复送进prompt的序列变了的题.n"))[0],
                 "★★ **同一次 run 内**多次 `status=1` 之间，送进 prompt 的切片序列不一致"])
    rows.append(["三次全被挡（不进任何检索分母）",
                 pair_int(get_path(ra, "检索.★取到了第几次成功.★三次全被挡（不进任何检索分母）"),
                          get_path(rb, "检索.★取到了第几次成功.★三次全被挡（不进任何检索分母）"))[0],
                 "检索**确实没发生** —— 它不在检索分母里（它在澄清矩阵里）"])
    out.extend(table(["指标", "值（噪声底轮 / 主体轮）", "口径"], rows))
    out.append("")
    out.append("★ 分母 **%s / %s**：排除**声明不检索**、**三次全被挡**、**正解切片已不在库里**、"
               "**`retrieval_detail` 缺失**四类；空 gold 题另有 %s 道被排除。"
               % (num(get_path(ra, "检索.分母.★分母_测到的题数")),
                  num(get_path(rb, "检索.分母.★分母_测到的题数")),
                  num(get_path(rb, "检索.分母.空gold题（被排除）"))))
    miss_a = get_path(ra, "检索.分母.  其中 正解切片已不在库里的题号") or []
    miss_b = get_path(rb, "检索.分母.  其中 正解切片已不在库里的题号") or []
    out.append("★ **正解切片已不在库里的题**（洞 7 的直接检查）：噪声底轮 %s，主体轮 %s"
               % (_codes(miss_a) if miss_a else "**无**", _codes(miss_b) if miss_b else "**无**"))
    out.append("  - 这一项**必须一直是「无」** —— 非空意味着语料重切之后标注集悬空了，"
               "而那会让 Recall **悄悄变好**（`|gold|` 变小，分母跟着小）")
    seq_a = get_path(ra, "检索.★跨重复送进prompt的序列变了的题.题号") or []
    seq_b = get_path(rb, "检索.★跨重复送进prompt的序列变了的题.题号") or []
    out.append("")
    out.append("- 噪声底轮序列不稳（%d 道）：%s" % (len(seq_a), _codes(seq_a) or "无"))
    out.append("- 主体轮序列不稳（%d 道）：%s" % (len(seq_b), _codes(seq_b) or "无"))
    out.append("")
    out.append("**★ 检索指标取的是「第几次成功」的那一次**（不是「第 1 次」，也不是「任取一次」）：")
    out.append("")
    pick = get_path(rb, "检索.★取到了第几次成功") or {}
    out.append("- 第一次就成功：**%s** 道" % num(pick.get("第一次就成功")))
    out.append("- 第二或第三次才成功：**%s** 道" % num(pick.get("第二或第三次才成功")))
    out.append("- 三次全被挡：**%s** 道（%s）—— **不进检索分母**，检索确实没发生"
               % (num(pick.get("★三次全被挡（不进任何检索分母）")),
                  _codes(pick.get("三次全被挡的题号") or []) or "无"))
    out.append("")
    out.append("★ 为什么不取「第 1 次」：一道本该放行的题第一次恰好被闸门挡掉时，直接丢弃会让检索指标"
               "**随机丢样本**。「被挡了几次」由上面的闸门抖动单独报。")

    # ---------- 2.6 归因 ----------
    out.append("")
    out.append("### 2.6 归因（正解为什么没进 prompt）")
    out.append("")
    ba = get_path(ra, "归因.桶") or {}
    bb = get_path(rb, "归因.桶") or {}
    keys = sorted([k for k in (set(ba) | set(bb)) if not str(k).startswith("★")],
                  key=lambda k: (k != "ok", k))
    rows = []
    for k in keys:
        cell, _s = pair_int(ba.get(k), bb.get(k))
        rows.append(["`%s`" % k, cell, ("★ 分母 %s" % num(bb.get("★分母"))) if k == "ok" else ""])
    out.extend(table(["桶", "值（噪声底轮 / 主体轮）", "它量的是"], rows))
    out.append("")
    out.append("| 桶 | 含义 | 量的是 |")
    out.append("|---|---|---|")
    out.append("| `ok` | 正解切片出现在最终 prompt 里 | — |")
    out.append("| `filtered_out` | 正解的 `doc_type` 不在本次生效的 `doc_types` 里 | **范围选错了** |")
    out.append("| `rerank_dropped` | 召回拿到了，重排 + 截断那一步丢了 | **排序 / 深度** |")
    out.append("| `not_recalled` | 两路召回都没拿到 | **召回** |")
    out.append("| `fusion_dropped` | 召回到了、融合那一步丢了 | **RRF** |")
    out.append("| `gold_missing` | 正解切片已经不在库里 | **标注集失效** |")
    out.append("| `beyond_record_cutoff` | 无法判定（记录被截断） | **证据不足** |")
    out.append("")
    out.append("★ 三个桶和 `检索` 那一节的关系：`ok + filtered_out + gold_missing = 检索分母`；"
               "剩下的是「进了分母但没命中」，`beyond_record_cutoff` 是其中的**不可判定**部分。")
    out.append("")
    cut_a = get_path(ra, "归因.★截断可判定性") or {}
    cut_b = get_path(rb, "归因.★截断可判定性") or {}
    out.append("**★★ `beyond_record_cutoff` 必须和这张表一起读：**")
    out.append("")
    out.extend(table(["", "噪声底轮", "主体轮"], [
        ["有 `sizes` 段（可判定）", num(cut_a.get("有sizes段的行")), num(cut_b.get("有sizes段的行"))],
        ["无 `sizes` 段（**不可判定**）", num(cut_a.get("无sizes段的行")), num(cut_b.get("无sizes段的行"))],
    ]))
    out.append("")
    if (cut_a.get("无sizes段的行") or 0) > 0:
        out.append("⚠️ **噪声底轮有 %s 行没有 `sizes` 段**（`sizes` 是 2026-09-21 才加进 `retrieval_detail` 的）。"
                   "那一轮里「融合有没有丢正解」是**证不了的** —— `fusion_dropped = 0` "
                   "**不构成「RRF 没丢东西」的证据**，它只是「没有证据说它丢了」。"
                   % num(cut_a.get("无sizes段的行")))
        out.append("")
        out.append("★★ 这是**两轮的数据形状不同**，不是数据变了。缺的那个段当时就没写进库 —— "
                   "**只能重跑才能对齐，重算报告没用。**")
    else:
        out.append("✅ 两轮都有 `sizes` 段，归因桶是可判定的。")
    if get_path(rb, "归因.正解切片已不在库里的题"):
        out.append("")
        out.append("⚠️ **有 %s 道题的正解切片已经不在库里** —— 那是标注集失效（洞 7），"
                   "它们的 gold 不再指向任何东西。" % num(get_path(rb, "归因.正解切片已不在库里的题")))
    partial = get_path(rb, "归因.部分被过滤的题")
    if partial:
        out.append("")
        out.append("★ **部分被过滤的题：%s 道** —— 正解里有**一部分**切片的类型不在生效的 `doc_types` 里。"
                   "它们归到 `ok`（正解确实进了 prompt），但**上下文是残缺的**。"
                   "★ 这一栏存在的意义：`filtered_out` 是全有全无的判据，"
                   "而真实情况经常是「来了 3 条里的 1 条」。" % num(partial))

    # ---------- 2.7 过度检索 ----------
    out.append("")
    out.append("### 2.7 过度检索率")
    out.append("")

    def over_render(node):
        if not isinstance(node, dict):
            return num(node)
        return "%s/%s = %s" % (num(node.get("越界条数")), num(node.get("上下文条数")), rat(node.get("值")))

    def over_identity(node):
        if not isinstance(node, dict):
            return node
        return (node.get("越界条数"), node.get("上下文条数"), node.get("可信"))

    over_rows = []
    for lbl, path, note in [("★ 对标注叶子", "过度检索.★对标注叶子",
                             "越界切片数 / 全部上下文条数 —— **这是该引用的那个数**"),
                            ("★ 对分类叶子", "过度检索.★对分类叶子",
                             "★ 对**模型判出来的**叶子。两者之差 = 分类错误额外带进来的噪声")]:
        cell, _s = pair_custom(get_path(ra, path), get_path(rb, path), over_render, over_identity)
        over_rows.append([lbl, cell, note])
    out.extend(table(["指标", "越界/总数（噪声底轮 / 主体轮）", "口径"], over_rows))
    v_a = get_path(rb, "过度检索.★对标注叶子.值")
    v_b = get_path(rb, "过度检索.★对分类叶子.值")
    if v_a is not None and v_b is not None:
        out.append("")
        out.append("★ 两个口径的差：**%s 个百分点**（%s vs %s）—— "
                   "它衡量的是「分类器判歪一次，会往上下文里塞进多少不属于这道题的切片」。"
                   % (num(round((v_a - v_b) * 100, 2)), rat(v_a), rat(v_b)))
    stale = get_path(rb, "过度检索.★切片已不在库里的条目数")
    if stale is not None:
        out.append("")
        out.append("★ **切片已不在库里的条目数 = %s** —— 越界判定要排掉那些**已经不在库里**的切片 id，"
                   "否则「标注集失效」会被误读成「检索越界」。这一栏是那件事的计数器。" % num(stale))

    # ---------- 2.8 延迟 ----------
    out.append("")
    out.append("### 2.8 延迟（★ 单独一节，不要和上面任何一节做 diff）")
    out.append("")
    cols = [("total_latency_ms", "**total** 总耗时"),
            ("queue_ms", "queue 排队"),
            ("retrieval_latency_ms", "retrieval 召回"),
            ("rerank_latency_ms", "rerank 重排"),
            ("llm_latency_ms", "llm 生成"),
            ("★未归类_ms", "★未归类 = total − queue − retrieval − llm")]
    rows = []
    for key, label in cols:
        na = get_path(ra, "延迟.%s" % key) or {}
        nb = get_path(rb, "延迟.%s" % key) or {}
        rows.append([label,
                     "%s / %s" % (num(na.get("p50")), num(nb.get("p50"))),
                     "%s / %s" % (num(na.get("p95")), num(nb.get("p95"))),
                     "%s / %s" % (num(na.get("n")), num(nb.get("n")))])
    out.extend(table(["列", "p50 ms（噪声底 / 主体）", "p95 ms（噪声底 / 主体）", "n"], rows))
    out.append("")
    out.append("⚠️⚠️ **四段不相加等于 total** —— `rerank ⊂ retrieval` 是包含关系，"
               "而**意图分类（一次 0.5~2.5 秒的模型往返）进了 total 却不在任何一列里**。"
               "所以有一列叫「未归类」。")
    out.append("")
    out.append("★ **这一节不要拿来做 diff**：两轮被 `status≠1` 截掉的条数不同"
               "（噪声底轮 %s / 主体轮 %s），**比的不是同一个总体**。"
               "被截掉的条数：噪声底轮 `status=1` 的 %s 条 / 全部 %s 条；"
               "主体轮 %s / %s。"
               % (num(get_path(ra, "延迟.★被status≠1截掉的条数")),
                  num(get_path(rb, "延迟.★被status≠1截掉的条数")),
                  num(get_path(ra, "延迟.样本_status=1的行")), num(get_path(ra, "延迟.样本_全部行")),
                  num(get_path(rb, "延迟.样本_status=1的行")), num(get_path(rb, "延迟.样本_全部行"))))
    un_b = get_path(rb, "延迟.★未归类_ms") or {}
    if un_b.get("p50"):
        out.append("")
        out.append("★★ `未归类` 的 p50 = **%s ms**、p95 = **%s ms** —— "
                   "**这一块在阶段 7 之前从来没被量过**，而它的量级和「召回」相当。"
                   % (num(un_b.get("p50")), num(un_b.get("p95"))))

    # ---------- 2.9 成本 ----------
    out.append("")
    out.append("### 2.9 成本")
    out.append("")
    out.extend(table(["指标", "值（噪声底轮 / 主体轮）", "口径"], [
        ["token 合计", pair_int(get_path(ra, "成本.token合计"), get_path(rb, "成本.token合计"))[0], "输入 + 输出"],
        ["数据库账本合计（元）",
         pair_scalar(get_path(ra, "成本.数据库里的成本合计"),
                     get_path(rb, "成本.数据库里的成本合计"), 6)[0], ""],
        ["有成本的行", pair_int(get_path(ra, "成本.有成本的行"), get_path(rb, "成本.有成本的行"))[0], ""],
    ]))
    out.append("")
    out.append("⚠️ **服务端账本不含意图分类与摘要压缩** —— 它们各自是一次模型调用，但**不进 `qa_log`**。"
               "RAGAS 判官那一轮的花费也不在里面。所以这不是「这一期花了多少」。")

    # ---------- 2.10 检索决策（阶段 9.6）----------
    #
    # ★ 为什么编号接在 2.9 后面而不是插到 2.2 / 2.3 旁边：
    #   2.x 是**硬编码字符串**，插在中间要把 2.5~2.9 全部重编号 ——
    #   而 ADR-083 等文档里点名引用的是「2.8 延迟」。重编号会让那些引用
    #   静默指到别的节，且**没有任何东西会报错**。所以新的接在后面。
    out.append("")
    out.append("### 2.10 检索决策（该不该检索）")
    out.append("")
    out.append("★★ **四格是四件事，不是一个「准确率」。** 两个错方向的修法**完全相反**：")
    out.append("")
    out.append("- 「声明要检索 → 实际没检索」= **过度关闭** ⇒ 要**放宽**")
    out.append("- 「声明不检索 → 实际检索了」= **过度检索** ⇒ 要**收窄**")
    out.append("")
    out.append("并成一个数之后，它掉下来你**不知道该往哪边调**。")
    out.append("")
    out.extend(table(["格（逐题 · 多数票）", "值（噪声底轮 / 主体轮）", "口径"], [
        [k, pair_int((get_path(ra, "检索决策.四格（逐题·多数票）") or {}).get(k),
                     (get_path(rb, "检索决策.四格（逐题·多数票）") or {}).get(k))[0], note]
        for k, note in RD_CELLS] + [
        [k, pair_int((get_path(ra, "检索决策.四格（逐题·多数票）") or {}).get(k),
                     (get_path(rb, "检索决策.四格（逐题·多数票）") or {}).get(k))[0],
         "★ **分母**，不是分子 —— 两个格共用它"]
        for k in RD_DENOM_QUESTIONS]))
    out.append("")
    out.append("**逐行**（每次重复都算一次决策；分母 = 题数 × 重复次数）：")
    out.append("")
    out.extend(table(["格（逐行）", "值（噪声底轮 / 主体轮）", "口径"], [
        [k, pair_int((get_path(ra, "检索决策.四格（逐行）") or {}).get(k),
                     (get_path(rb, "检索决策.四格（逐行）") or {}).get(k))[0], ""]
        for k in RD_DENOM_ROWS + [k for k, _ in RD_CELLS]]))
    out.append("")
    out.append("★ 口径三件事：")
    out.append("")
    out.append("1. 判据是**实际发生的检索**（`retrieval_detail` 非空），**不是** `intent_plan.retrieve`。"
               "后者是门控自己的输出，拿它当判据会漏掉「**算对了但调用点忘了用**」那类只改一半的实现。"
               "两者不一致的行单列在 `★算对了但没用的行` 里，**应恒 0**。")
    out.append("2. **被限流拒掉的行（`status=4`）不算一次决策** —— 那条路上检索压根没跑。"
               "把它们算成「决定不检索」会让过度检索率**归零**，一个看起来像好消息的 0，"
               "而它百分之百是容量造成的。它们被单独数出来。")
    out.append("3. 「声明不检索」那 %s 道题（工具 15 / 兜底 7 / 澄清 6）走的是 `TOOL` / `NONE` 两条路，"
               "**代码根本不读模型那一格** —— 它们的「没检索」是设计保证的，"
               "只有**分类判错**时才会翻成「检索了」。"
               "**换句话说：这一格测的是分类质量，不是门控质量。**"
               % num(get_path(rb, "数据完整性.  其中 声明不检索的")))
    out.append("")
    bad = (get_path(rb, "检索决策.★算对了但没用的行") or [])
    out.append("- ★ **算对了但没用的行**：%s"
               % (_codes(bad) if bad else "**0 行** ✅（门控的结论与实际行为处处一致）"))
    n_skipped = get_path(rb, "检索决策.★没算成决策的行")
    out.append("- ★ 没算成决策的行（被限流拒掉，四个格子都不含它们）：%s" % num(n_skipped))
    out.append("")
    out.append("★ **`shape` 分布是「门控到底有没有生效」的唯一判据** —— "
               "JSON 占比接近 100% 才算生效。掉下来说明模型没跟上契约、走了裸码回退，"
               "而回退路径的 `retrieve` 是**默认值**，不是模型说的。")
    out.append("")
    out.extend(table(["shape", "噪声底轮", "主体轮"], [
        [k, pair_int((get_path(ra, "检索决策.shape分布") or {}).get(k),
                     (get_path(rb, "检索决策.shape分布") or {}).get(k))[0], ""]
        for k in sorted(set((get_path(ra, "检索决策.shape分布") or {}))
                        | set((get_path(rb, "检索决策.shape分布") or {})))]))
    out.append("")
    out.append("★ `gate` 分布（谁下的决定）—— **没有它就无法把 `TOOL`（该走工具）和 "
               "`PLAN_OFF`（模型主动关掉）分开**，而两者都是「没检索」：")
    out.append("")
    out.extend(table(["gate", "噪声底轮", "主体轮"], [
        [k, pair_int((get_path(ra, "检索决策.gate分布") or {}).get(k),
                     (get_path(rb, "检索决策.gate分布") or {}).get(k))[0], ""]
        for k in sorted(set((get_path(ra, "检索决策.gate分布") or {}))
                        | set((get_path(rb, "检索决策.gate分布") or {})))]))
    n_plan_off = (get_path(rb, "检索决策.gate分布") or {}).get("PLAN_OFF")
    if not n_plan_off:
        out.append("")
        out.append("⚠️ **`PLAN_OFF` = %s —— 这条分支一次都没被走到过。**"
                   "它是「业务相关但不用检索」那条路（`KB` 意图 ∧ 模型主动关掉检索），"
                   "已知在阶段 9.2 的 11 条样本里也是 0。**它不是「这条分支没问题」，"
                   "是「它没被测过」。**" % num(n_plan_off))
    flip = get_path(rb, "检索决策.★跨次决策翻转的题") or []
    out.append("")
    out.append("- ★ **跨次决策翻转的题**（同一题重复跑，决策不一致）：%s"
               % (_codes(flip) if flip else "无"))

    # ---------- 2.11 工具调用（阶段 9.6）----------
    out.append("")
    out.append("### 2.11 工具调用（该不该有工具）")
    out.append("")
    out.append("★★ **这一段回答的是 9.1 那个 bug 有没有复发。** "
               "工具意图静默降级成普通知识库问答时，`intent` / `status` / `provider` / "
               "`final_answer` **逐字与正常轮相同**，而模型会拿通用规则**编一个订单状态出来**。"
               "旧数据上这件事**完全看不出来**。")
    out.append("")
    out.extend(table(["格（逐题 · 多数票）", "值（噪声底轮 / 主体轮）", "口径"], [
        [k, pair_int((get_path(ra, "工具调用.四格（逐题·多数票）") or {}).get(k),
                     (get_path(rb, "工具调用.四格（逐题·多数票）") or {}).get(k))[0], note]
        for k, note in TC_CELLS] + [
        [k, pair_int((get_path(ra, "工具调用.四格（逐题·多数票）") or {}).get(k),
                     (get_path(rb, "工具调用.四格（逐题·多数票）") or {}).get(k))[0],
         "★ **分母**，不是分子"]
        for k in TC_DENOM_QUESTIONS]))
    out.append("")
    out.append("**逐行**：")
    out.append("")
    out.extend(table(["格（逐行）", "值（噪声底轮 / 主体轮）", "口径"], [
        [k, pair_int((get_path(ra, "工具调用.四格（逐行）") or {}).get(k),
                     (get_path(rb, "工具调用.四格（逐行）") or {}).get(k))[0], ""]
        for k in TC_DENOM_ROWS + [k for k, _ in TC_CELLS]]))
    out.append("")
    out.append("★ **「该有工具」由 gold 意图经意图树推出**（`retrievalOf(gold) == TOOL`，"
               "或该叶子自己声明了 `tools`）——**和运行时那一条不是同一个来源**，"
               "所以它才能发现「该有工具却没有」。")
    out.append("")
    out.append("★★ **「越权且成功的调用」应恒 0 —— 而它不是一个恒空式。** "
               "白名单拒绝**也会留下调用记录**（`isError=true`），所以「越权的调用」非 0 "
               "**不代表数据泄露**（服务端拒了），它代表「模型叫了一个没给它的名字」。"
               "而「拒了没有」是**可观测的**：被拒那条必然是 `isError=true`。")
    out.append("")
    over = get_path(rb, "工具调用.★★越权且成功的调用") or []
    out.append("- ★★ **越权且成功的调用**：%s"
               % (_codes(over) if over else "**0 条** ✅（ADR-093 那条不变式在数据上成立）"))
    over_try = get_path(rb, "工具调用.★越权的调用") or []
    out.append("- ★ 越权的调用（尝试）：%s" % (_codes(over_try) if over_try else "无"))
    drop = get_path(rb, "工具调用.★给了工具却一次没调的行") or []
    out.append("- ★★ **给了工具却一次没调的行**：%s"
               % (_codes(drop) if drop
                  else "**0 行** ✅（门控给了工具，模型就真的用了）"))
    out.append("")
    out.append("★ 判据是「**调了没有**」，不是「答对了没有」—— 工具答对内容由 "
               "`probe_tool.py` 那 30 项负责，这里只管**工具链有没有走通**。"
               "★ 这个数**不区分**「真降级了」和「工具链压根没跑」，"
               "所以它非 0 时先看 `数据完整性` 和 `★没有计划的行`。")
    out.append("")
    out.append("★★ **`该有工具_一次没调` 那一格不下「静默降级」的结论。** 实测"
               "（2026-09-26）`MC-002`「我的优惠券什么时候过期」的三次都是"
               "「用通用规则回答 + **明确说『我这边看不到你账户里具体券的到期时间』**」，"
               "**那不是编造**。要判「是不是降级」必须读那几行 `final_answer` ——"
               "判据是「它在谈通用规则，还是在**编一个具体值**」。"
               "★ 把「如实说查不到」和「编一个数」并成一个名字，会让修法指错方向。")
    called = get_path(rb, "工具调用.调用的工具分布") or {}
    if called:
        out.append("")
        out.append("- 调用的工具分布（主体轮）：%s"
                   % "、".join("`%s` ×%s" % (k, num(v)) for k, v in sorted(called.items())))
    out.append("- `isError` 的调用条数（主体轮）：%s"
               % num(get_path(rb, "工具调用.isError 的调用条数")))
    out.extend(_render_slot_declaration(ra, rb))
    return out


def _render_slot_declaration(ra, rb):
    """§2.12 槽位声明（`missing` 的去向）—— 阶段 9.6a 的第三笔账。

    ★★ 这一节记的是【形状】，不是判据：模型自己说「这句话还缺什么」的那些行，
    只有落在澄清分支上的才反问了（而那是**定义性**的 —— `status=3` 就是那条分支）。
    落业务码的一次都没反问。⇒ `missing` 非空 ≠ 会反问。
    """
    out = []
    out.append("")
    out.append("### 2.12 槽位声明（`missing` 的去向）★ 阶段 9.6a")
    out.append("")
    out.append("★★ **模型说「这句话还缺什么」，和「系统会不会反问」，是两条独立的链。** "
               "当前实现里 `intent_plan.missing` 非空**不会**触发反问 —— "
               "反问完全由**落点**决定（落点 == 树里 `role: CLARIFY` 的分支 ⟺ `status=3`）。"
               "`missing` 唯一的消费方是**澄清文案**（缺哪个槽位、问哪一句）。")
    out.append("")
    out.extend(table(["格", "值（噪声底轮 / 主体轮）", "口径"], [
        [k, pair_int((get_path(ra, "槽位声明") or {}).get(k),
                     (get_path(rb, "槽位声明") or {}).get(k))[0], note]
        for k, note in SD_KEYS]))
    out.append("")
    out.append("★★ **中间那两格是这条账的全部内容** —— 同一批「模型自己说缺槽位」的行，"
               "落在澄清分支的**全部反问了**、落在业务码的**一次都没反问**。"
               "★ 分裂的依据是**落点**，而落点是模型自己给的**另一个**输出。")
    out.append("")
    out.append("⚠️ **第一格是定义性的，不是发现** —— `status=3` 与「落点是澄清分支」"
               "是同一个判据。它在这里的用途只有一个：给第二格当**对照**。"
               "★ 也正因为如此，**这里不做「落点 × 反问」的 2×2** —— "
               "那张表有一整轴恒真。")
    out.append("")
    nos = get_path(rb, "槽位声明.★声明了却没反问的题") or []
    if not nos:
        out.append("- ★ **声明了却没反问的题**：无")
    elif len(nos) <= 20:
        out.append("- ★ **声明了却没反问的题**（%d 道）：%s" % (len(nos), _codes(nos)))
    else:
        # ★ 截断必须留下痕迹（坑 45）—— 读的人得知道这份清单不是全部。
        #   report.json 里是完整的 25 道，这里只印前 20。
        out.append("- ★ **声明了却没反问的题**（%d 道，下面列前 20）：%s，"
                   "**（其余 %d 道略）**"
                   % (len(nos), _codes(nos[:20]), len(nos) - 20))
    out.append("")
    out.append("★★ **这一格不是缺陷清单。** 落业务码的行**本就不该反问**"
               "（系统答得对）；真正的「漏反问」只是其中的**样本**。"
               "多轮集上①那一格的漏，成因在这里能看见 —— 见 §6。")
    out.append("")
    out.append("★ **要不要让 `missing` 参与闸门是一个产品决策**"
               "（代价是「让模型自己决定要不要反问」，而它和 5.2 的分类基线同源）。"
               "2026-09-26 拍板：**先记账、不改行为** —— 与 9.5「停手不改」同源，"
               "先把它变成可复算的数，再决定改不改。")
    return out


def _codes(items):
    return ", ".join("`%s`" % md_text(t) for t in items)


# ================================================================
# §3 切片（带 n，n < MIN_SLICE_N 不作为结论）
# ================================================================

SLICE_GROUPS = [
    ("按标注意图切片", "意图", "标注的叶子码"),
    ("按类别切片", "意图", "题面的写法（口语 / 关键词 / 书面）"),
    ("按难度切片", "意图", "标注难度 1 / 2 / 3"),
]


def render_slices(runs):
    rb = runs["main"]["report"]
    out = ["主语是**主体轮**（切片的分母太小，两轮并排会变成两列都不足信）。", ""]
    out.append("★★ **每一个切片都印 n；n < %d 一律标注「不作为结论」。**" % MIN_SLICE_N)
    out.append("★ 这条纪律的理由很具体：n=3 的切片里，**一道题翻转就是 33 个百分点**。"
               "那样的数字看起来和 n=20 的切片一样精确。")
    out.append("")
    for key, parent, why in SLICE_GROUPS:
        node = get_path(rb, "%s.%s" % (parent, key))
        if not isinstance(node, dict):
            continue
        out.append("### 3.%d %s（%s）" % (SLICE_GROUPS.index((key, parent, why)) + 1, key, why))
        out.append("")
        rows = []
        for name in sorted(node):
            cell = node[name]
            if not is_ratio(cell):
                continue
            mark_accessed("%s.%s.%s" % (parent, key, name))
            n = cell.get("n") or 0
            flag = "" if n >= MIN_SLICE_N else "⚠️ **不作为结论**"
            rows.append(["`%s`" % name, num(n),
                         "%s/%s = %s" % (num(cell.get("命中")), num(n), rat(cell.get("值"))),
                         "✅" if cell.get("可信") else "⚠️", flag])
        rows.sort(key=lambda r: (not r[4][:1] == "⚠", r[0]))
        out.extend(table(["切片", "n", "命中 / n", "可信", ""], rows))
        out.append("")
    return out


# ================================================================
# §4 RAGAS
# ================================================================

def find_ragas_source():
    """找到 ragas 的指标源码目录。Windows 是 Lib/，Linux 是 lib/python3.x/。"""
    import glob
    pats = [os.path.join(ROOT, ".venv-eval", "Lib", "site-packages", "ragas",
                         "metrics", "collections"),
            os.path.join(ROOT, ".venv-eval", "lib", "python*", "site-packages", "ragas",
                         "metrics", "collections")]
    for p in pats:
        hits = glob.glob(p)
        if hits:
            return hits[0]
    return None


def ragas_metric_inputs():
    """★★ 从 ragas 的源码里读出四个指标各自吃哪些输入。

    ⚠️ **这一节不许写死。** 它是一条关于「库的当前实现」的事实，而库会升级。
       ragas 0.1 → 0.4 的 API 全改过一遍（本项目已在 T5 撞过）。
       写死的后果：升级之后报告继续用旧的输入表解释新的分数，
       而**四个数的含义已经变了**。
    """
    base = find_ragas_source()
    if not base:
        return None
    fields = ["user_input", "response", "retrieved_contexts", "reference"]
    out = {}
    for m in RAGAS_METRICS:
        path = os.path.join(base, m, "metric.py")
        if not os.path.isfile(path):
            out[m] = None
            continue
        with io.open(path, encoding="utf-8") as f:
            src = f.read()
        sig = re.search(r"async def ascore\(\s*self,([^)]*)\)", src, re.S)
        params = sig.group(1) if sig else ""
        out[m] = {f: bool(re.search(r"\b%s\b" % f, params)) for f in fields}
    return out


def render_ragas(runs, rag, negctl):
    out = []
    if rag is None:
        out.append("⚠️ **没有提供 RAGAS 结果文件** —— 本节为空。")
        return out

    rid = rag.get("runId")
    owner_role = None
    for role in ("main", "noise", "baseline", "baseline_bad", "multi"):
        r = runs.get(role)
        if r and r["runId"] == rid:
            owner_role = role
    owner = runs.get(owner_role) if owner_role else None

    out.append("来源：`%s`" % os.path.relpath(rag["_path"], ROOT).replace("\\", "/"))
    out.append("")
    out.append("- ragas 版本：`%s`；judge：`%s`（%s）；embedding：`%s`"
               % (num(rag.get("ragas 版本")),
                  num((rag.get("judge 与 embedding") or {}).get("judge", {}).get("model")),
                  num((rag.get("judge 与 embedding") or {}).get("judge", {}).get("baseUrl")),
                  num((rag.get("judge 与 embedding") or {}).get("embedding", {}).get("model"))))
    out.append("- 样本：**%s 题**（%s）"
               % (num((rag.get("样本") or {}).get("题数")),
                  num((rag.get("口径") or {}).get("★ 抽样方式"))))

    # ★★ 这一节的口径说明
    out.append("")
    out.append("### 4.0 ★★ 先说清四个数各自吃的是什么")
    out.append("")
    out.append("**判据不是「文档这么说」，是「源码里 `ascore` 的签名」** —— 下面这张表是读出来的：")
    out.append("")
    inputs = ragas_metric_inputs()
    if inputs is None:
        out.append("⚠️ 找不到 ragas 源码（`.venv-eval/`），这一节**拿不到实证**。")
    else:
        rows = []
        for m in RAGAS_METRICS:
            d = inputs.get(m)
            if not d:
                rows.append(["`%s`" % m, "?", "?", "?", "?"])
                continue
            rows.append(["`%s`" % m] + ["✅" if d[f] else "—" for f in
                                        ("response", "retrieved_contexts", "reference", "user_input")])
        out.extend(table(["指标", "`response`", "`retrieved_contexts`", "`reference`", "`user_input`"], rows))
        out.append("")
        no_ctx = [m for m in RAGAS_METRICS if inputs.get(m) and not inputs[m]["retrieved_contexts"]]
        only_ctx = [m for m in RAGAS_METRICS if inputs.get(m) and not inputs[m]["response"]]
        if no_ctx:
            out.append("★★ **`%s` 根本看不到检索结果** —— 它们测不到检索质量，只在测「答案贴不贴题 / 对不对」。"
                       "把它们当成检索指标读是本项目最反对的那种误读。" % "` / `".join(no_ctx))
            out.append("")
        if only_ctx:
            out.append("★★ **`%s` 是唯一不含 `response` 的** —— 而 `response` 是模型每次重新生成的。"
                       "⇒ 它的波动**只来自检索侧**；其余三个的波动**主要来自生成侧**。"
                       "这两个源的量级和测法是两回事。" % "` / `".join(only_ctx))
    out.append("")
    out.append("**contexts 的两种口径**（本项目自己定的，不是 ragas 的）")
    out.append("")
    q = rag.get("口径") or {}
    out.append("- `faithfulness` 用 **%s**" % num(q.get("faithfulness 用")))
    out.append("- `context_recall` 用 **%s**" % num(q.get("context_recall 用")))
    out.append("")
    out.append("★★ 一个 contexts 满足不了两件事：`faithfulness` 判的是「**模型看到的全部输入**」"
               "（结构化硬数据确实进了 prompt），`context_recall` 判的是「**检索**回来的东西准不准」"
               "—— 拿注入的硬数据证明「检索好」是**循环论证**。")

    # ---- 4.1 全量 ----
    out.append("")
    out.append("### 4.1 全量：四个数 + bootstrap 95% 区间")
    out.append("")
    by_metric = ragas_rows_with_scores(rag)
    rows = []
    for m in RAGAS_METRICS:
        scores = by_metric[m]
        vals = [scores[k] for k in sorted(scores)]
        lo, hi = bootstrap_ci(vals)
        rows.append(["`%s`" % m, num(len(vals)),
                     rat(mean_of(vals)),
                     "[%s, %s]" % (rat(lo), rat(hi)),
                     rat(min(vals)) if vals else DASH,
                     rat(max(vals)) if vals else DASH])
    out.extend(table(["指标", "n", "均值", "95% bootstrap 区间", "最小", "最大"], rows))
    out.append("")
    out.append("★★ **这个区间只反映【抽样】不确定性**（换一批题会怎样），"
               "**不含** judge 侧噪声、**不含**生成侧噪声。见 §4.5 —— 几个源的测法完全不同。")

    # ---- 4.2 三层 ----
    out.append("")
    out.append("### 4.2 ★★★ 按分类正确性分层（这一节是本期分量最重的发现）")
    out.append("")
    if owner is None:
        out.append("⚠️ RAGAS 的 runId `%s` 不在本次报告的四轮里，**无法分层**。" % num(rid))
    else:
        out.extend(_ragas_layers(owner, rag, by_metric))

    # ---- 4.3 逐叶子 ----
    out.append("")
    out.append("### 4.3 逐叶子（★ n=%d，按本项目纪律【不作为结论】）" % 3)
    out.append("")
    comp = (rag.get("样本") or {}).get("★ 分层构成") or {}
    rows = []
    for leaf in sorted(comp):
        picked = comp[leaf].get("题号") or []
        cells = []
        for m in RAGAS_METRICS:
            vals = [by_metric[m].get(t) for t in picked if by_metric[m].get(t) is not None]
            cells.append("%s (n=%d)" % (rat(mean_of(vals)), len(vals)) if vals else DASH)
        rows.append(["`%s`" % leaf, num(len(picked))] + cells)
    out.extend(table(["gold 叶子", "n", "faithfulness", "answer_relevancy",
                      "context_recall", "answer_correctness"], rows))
    out.append("")
    # ★ 哪些叶子是「工具叶子」由数据决定（数据里 gold 不可达的那几个），不写死名单 ——
    #   写死的话，换了题库报告会指着一行已经不存在的叶子说「这个要这么读」。
    tool_leaves = []
    if owner is not None:
        for r in owner["report"]["逐题"]["行"]:
            if r.get("gold是合法分类目标") is False and r.get("标注叶子") in comp:
                tool_leaves.append(r["标注叶子"])
    if tool_leaves:
        out.append("★★ **其中 %s 的数字读法完全不同** —— 它们**不可能被分类成自己**"
                   "（是工具叶子码，模型永远输出不了），能进 RAGAS 的前提就是"
                   "「**被分类成了别的 KB 意图**」。"
                   % "、".join("`%s`" % md_text(t) for t in sorted(set(tool_leaves))))
        out.append("")
        out.append("   所以那几个数字量的是**误分类之后的答案质量**，不是这些意图本身的质量 —— "
                   "把它们读成「`MY_COUPON` 这个意图的检索质量」会得到完全相反的结论。")
    else:
        out.append("★ 这一轮的抽样里没有「gold 不可达」的题。")

    # ---- 4.4 负对照 ----
    if negctl is not None:
        out.append("")
        out.append("### 4.4 ★★ 负对照：故意喂不含答案的上下文")
        out.append("")
        neg = negctl.get("指标") or {}
        rows = []
        for m in RAGAS_METRICS:
            happy = mean_of(list(by_metric[m].values()))
            cur = neg.get(m) or {}
            lo, hi = bootstrap_ci([r["scores"][m] for r in negctl["逐题"]
                                   if r.get("scores", {}).get(m) is not None])
            rows.append(["`%s`" % m,
                         "%s (n=%s)" % (rat(happy), num(len(by_metric[m]))),
                         "%s (n=%s)" % (rat(cur.get("均值")), num(cur.get("n"))),
                         "[%s, %s]" % (rat(lo), rat(hi)),
                         _neg_verdict(cur.get("均值"), happy)])
        out.extend(table(["指标", "正常 (n)", "打乱上下文 (n)", "打乱组的 95% 区间", "判定"], rows))
        out.append("")
        out.append("★ **对照物必须借【不同意图】的上下文** —— 第一版借的是同意图的题（C-007 借 C-011），"
                   "上下文高度重叠，`faithfulness` 纹丝不动停在 1.000。"
                   "**那一次「对照通过」是假的**：它证明的是「同类题的上下文很像」，"
                   "不是「这个指标会动」。对照物选得太近，对照就成了安慰剂。")
        out.append("")
        # ★ 把「没动」的那两行的实际位移也印出来 —— 说「纹丝不动」而不给数，
        #   读者没法判断那是「完全没动」还是「动了一点但没到阈值」。
        moved = []
        for m in RAGAS_METRICS:
            cur = (neg.get(m) or {}).get("均值")
            happy = mean_of(list(by_metric[m].values()))
            if cur is None or happy is None:
                continue
            d = cur - happy
            if abs(d) < 0.3:
                moved.append("%s **%s**（%s）" % ("`%s`" % m, signed(d), rat(cur)))
        if moved:
            out.append("★★ **关键是不依赖 contexts 的那几行没跟着掉** —— 说明这个扰动是**定向的**，"
                       "不是把整条流水线搞坏了：")
            out.append("")
            for x in moved:
                out.append("- %s" % x)
            out.append("")
            out.append("★ 注意它们**不是纹丝不动** —— 上面的 Δ 不是零。"
                       "所以准确的读法是「**没有系统地、大幅度地掉**」，"
                       "而不是「这两个指标完全不受扰动影响」。")

    # ---- 4.5 波动带 ----
    out.append("")
    out.append("### 4.5 ★★★ 波动带：四个源，哪个有数、哪个没有")
    out.append("")
    out.append("这几个数的不确定性**不是一件事**。分开列，因为它们的测法完全不同：")
    out.append("")
    out.extend(table(["来源", "这一期有没有测到", "怎么测的 / 为什么没测"], [
        ["**抽样**（换一批题）", "✅ 有数", "§4.1 的 bootstrap 区间 —— 免费，直接在分数上重抽"],
        ["**检索侧**（换一轮跑）", "✅ 有数（§4.6）", "用 T6 的噪声底那两轮，逐题比对上下文集合"],
        ["**生成侧**（模型这次说了什么）", "❌ **没有**", "要重跑 `/api/chat` —— 那是另一期的工作量"],
        ["**judge 侧**（同一个 prompt 判两次）", "❌ **没有**", "要重跑 RAGAS；已知 LLM-as-judge 必然有此噪声"],
    ]))
    out.append("")
    others = [m for m in RAGAS_METRICS if m != "context_recall"]
    out.append("★ 由 §4.0 的输入表可以推出一件确定的事：")
    out.append("")
    out.append("- **`context_recall` 的波动只来自检索侧**（它不吃 `response`）→ §4.6 把它钉住了")
    out.append("- **`%s` 都吃 `response`** → 它们的波动**主要来自生成侧**，而那一栏**没有数**"
               % "` / `".join(others))
    out.append("")
    out.append("★★⚠️ **必须避免的一个误读**：重跑 RAGAS **测不出生成侧波动**。"
               "`answers` 来自 `qa_log`，`response` 是**固定的** —— 重跑只会换 judge，"
               "拿到的差异是 **judge 侧噪声**。这两件事经常被混为一谈。")

    # ---- 4.6 跨轮重叠（免费的结构性证据）----
    out.append("")
    out.append("### 4.6 ★★ 检索侧波动带的实证（不花钱：用噪声底那两轮的重叠算）")
    out.append("")
    out.extend(_ragas_retrieval_overlap(runs, rag))
    return out


def _neg_verdict(cur, happy):
    """负对照的判定。

    ★★ 这里【绝对不能】写 `cur or 1 < happy - 0.3` ——
      `0.0` 是**假值**，`0.0 or 1` 得到的是 `1`，于是**掉到 0 的那两个指标
      会被判成「没动」**。这个 bug 不报错、不异常，只是把最成功的那一行
      渲染成最平淡的一句。本项目踩过它的近亲好几回了（`Map.of` 拒 null、
      `Set.copyOf` 的顺序）。**拿数字做布尔判断前先问一句「0 在这里合法吗」。**
    """
    if cur is None or happy is None:
        return DASH
    if cur < happy - 0.3:
        return "✅ **掉下去了**"
    if abs(cur - happy) < 1e-9:
        return "— 没动（★ 这正是要观察的：它不依赖 contexts）"
    return "— 没动"


def _ragas_layers(owner, rag, by_metric):
    """★★ 把 43 题按【分类是否正确】切三层。

    ⚠️⚠️ 分层用的判据必须是 T4 的 `意图正确`（**三次众数**），
      **不是** RAGAS 文件里的 `intent` 字段 —— 后者是【一次抽样】的意图。
      两者在一道摇摆题上会给出不同答案（实测 SP-012：众数判对、单次抽样判错），
      用一次抽样分层会把「这道题三次里投了不同的票」渲染成「模型分类错了」。

    ★ 第三层（gold 不可达）单独立桶，不和「分类错误」混 ——
      它们的原因、修法、责任方全都不一样。
    """
    rows_map = owner["rows"]
    layers = {"A 分类正确": [], "B 分类错误": [], "C gold 不可达": []}
    for r in sorted(rag["逐题"], key=lambda x: x["questionNo"]):
        q = r["questionNo"]
        row = rows_map.get(q)
        if row is None:
            continue
        if row.get("gold是合法分类目标") is False:
            layers["C gold 不可达"].append(r)
        elif row.get("意图正确") is True:
            layers["A 分类正确"].append(r)
        elif row.get("意图正确") is False:
            layers["B 分类错误"].append(r)

    out = []
    out.append("三层。**分层判据 = T4 的 `意图正确`（三次众数）**，"
               "不是 RAGAS 文件里的 `intent` 字段（那是**一次抽样**的意图）。")
    out.append("")
    rows = []
    detail = {}
    for name in ["A 分类正确", "B 分类错误", "C gold 不可达"]:
        items = layers[name]
        cells = []
        for m in RAGAS_METRICS:
            vals = [it["scores"].get(m) for it in items if it.get("scores", {}).get(m) is not None]
            if not vals:
                cells.append(DASH)
                continue
            lo, hi = bootstrap_ci(vals)
            cells.append("%s [%s, %s] (n=%d)" % (rat(mean_of(vals)), rat(lo), rat(hi), len(vals)))
        detail[name] = items
        rows.append(["**%s**" % name, num(len(items))] + cells)
    out.extend(table(["层", "题数", "faithfulness", "answer_relevancy",
                      "context_recall", "answer_correctness"], rows))
    out.append("")

    # ★★★ T5 报出去的数是「两层」，而且口径和这里不一样。必须显式修订，
    #     否则同一件事会有两个数在流传，而「哪个对」没有答案。
    sample = {q: r for q, r in ((x["questionNo"], x) for x in rag["逐题"])}
    old_ok = [r for r in rag["逐题"]
              if rows_map.get(r["questionNo"], {}).get("标注叶子") == r.get("intent")]
    old_bad = [r for r in rag["逐题"]
               if rows_map.get(r["questionNo"], {}).get("标注叶子") != r.get("intent")]
    n_bad_mode = len(layers["B 分类错误"]) + len(layers["C gold 不可达"])
    if len(old_bad) != n_bad_mode:
        moved = []
        for r in old_bad:
            row = rows_map.get(r["questionNo"], {})
            if row.get("意图正确") is True:
                moved.append((r["questionNo"], "一次抽样判错 → 三次众数判对"))
            elif row.get("gold是合法分类目标") is False:
                moved.append((r["questionNo"], "一次抽样判错 → gold 本就不可达"))
        out.append("★★★ **本节修订了 T5 报出去的那张分层表。** T5 用的是 **RAGAS 文件里的 `intent` 字段**"
                   "（那是**一次抽样**的意图），这里用的是 **T4 的 `意图正确`（三次众数）**。")
        out.append("")
        out.append("```")
        out.append("T5 口径（一次抽样）：正确 %d / 错误 %d" % (len(old_ok), len(old_bad)))
        out.append("本节口径（三次众数）：正确 %d / 错误 %d / gold 不可达 %d"
                   % (len(layers["A 分类正确"]), len(layers["B 分类错误"]), len(layers["C gold 不可达"])))
        out.append("```")
        out.append("")
        for q, why in moved:
            out.append("- `%s`：%s" % (q, why))
        out.append("")
        out.append("★ 两种口径**都对**，但它们答的不是同一个问题：一个说「**这一次**判成什么」，"
                   "一个说「**三次里**最常判成什么」。★ 差的是**摇摆题**（本报告 §2.1 的「非全票一致」那一栏）。")
        out.append("")
        out.append("★★ **T5 那句「分类错误 9 道」把两件性质完全不同的事合在了一起**："
                   "「分类器判歪了」和「gold 本来就不可达」。它们的**修法完全相反** —— "
                   "前者改 prompt，后者改意图树的粒度。分成三层之后，"
                   "「**分类错误只有 %d 道**」这件事才第一次看得出来。"
                   % len(layers["B 分类错误"]))
        out.append("")

    a = [it["scores"].get("context_recall") for it in detail["A 分类正确"]
         if it["scores"].get("context_recall") is not None]
    b = [it["scores"].get("context_recall") for it in detail["B 分类错误"]
         if it["scores"].get("context_recall") is not None]
    c = [it["scores"].get("context_recall") for it in detail["C gold 不可达"]
         if it["scores"].get("context_recall") is not None]
    out.append("**这一节读出来的东西：**")
    out.append("")
    if c and len(set(c)) == 1:
        out.append("① ★★★ **`C gold 不可达` 的 `context_recall` 全是 %s**（%d/%d 道）—— "
                   "这不是「答得不好」，是**检索范围整个错了**：模型输出的是某个 KB 意图，"
                   "于是检索按那个意图的 `doc_types` 过滤，而正解切片的类型不在里面。"
                   % (rat(c[0]), len(c), len(c)))
        out.append("")
        rb = owner["report"]
        n_bank = get_path(rb, "意图.★★gold不是合法分类目标的题.n")
        gap = None
        _all = get_path(rb, "意图.逐题取众数") or {}
        _cmp = get_path(rb, "意图.★★可比口径的准确率") or {}
        if _all.get("值") is not None and _cmp.get("值") is not None:
            gap = round((_cmp["值"] - _all["值"]) * 100, 2)
        out.append("   ★★ **这就是 §2.1.1 那 %s 道恒错题的真正代价** —— 全题库里有 %s 道，"
                   "RAGAS 抽到了其中的 %s 道。"
                   % (num(n_bank or "?"), num(n_bank or "?"), num(len(layers["C gold 不可达"]))))
        if gap is not None:
            out.append("")
            out.append("   ★ T4 当时只能说「准确率差 **%s 个百分点**是口径造成的」；"
                       "到这一层可以说：**那 %s 个百分点的代价是上下文召回率归零、"
                       "答案正确率掉到 %s。**"
                       % (num(gap), num(gap), rat(mean_of([it['scores'].get('answer_correctness')
                                                           for it in detail["C gold 不可达"]]))))
        out.append("")
    if a and b:
        out.append("② **`B 分类错误` 的代价比 C 小得多**：`context_recall` %s → %s"
                   "（A → B）。分类错了不等于答不了 —— **只有那些「gold 根本不可达」的错才是致命的**。"
                   % (rat(mean_of(a)), rat(mean_of(b))))
        out.append("")
    fa = [it["scores"].get("faithfulness") for it in detail["A 分类正确"]
          if it["scores"].get("faithfulness") is not None]
    fb = [it["scores"].get("faithfulness") for it in detail["B 分类错误"]
          if it["scores"].get("faithfulness") is not None]
    if fa and fb and mean_of(fb) is not None and mean_of(fa) is not None:
        if mean_of(fb) >= mean_of(fa):
            out.append("③ ⚠️ **`B 分类错误` 的 `faithfulness` 反而【更高】**（%s vs %s）—— "
                       "**这不违反直觉**：分类错了 → 检索回来的是无关切片 → 模型没东西可编 → "
                       "只好贴着那堆无关切片说。**「忠于上下文」在这条路上是坏消息，不是好消息。**"
                       % (rat(mean_of(fb)), rat(mean_of(fa))))
        else:
            out.append("③ `B 分类错误` 的 `faithfulness` 是 %s，低于正确的 %s。"
                       % (rat(mean_of(fb)), rat(mean_of(fa))))
        out.append("")
    bad = detail["C gold 不可达"]
    if bad:
        out.append("④ 掉得最狠的题：%s" % _codes([it["questionNo"] for it in bad]))
        out.append("")
        out.append("   ★ 它们的 `answer_correctness` 是 %s —— "
                   "和 T5 报的「用户问『**我的**券怎么用』，模型答『优惠券的通用规则』」是同一件事。"
                   % rat(mean_of([it["scores"].get("answer_correctness") for it in bad])))
    return out


def ragas_overlap_stats(runs, rag):
    """RAGAS 抽样的那 43 题，在【噪声底两轮之间】的检索侧差异。

    ★★ 它免费，而且它把 `context_recall` 的检索侧波动带钉死 ——
      见 §4.6 和 §1.3（两处用同一个函数，不写两遍）。

    返回 None（拿不到两轮）或者一个 dict。
    """
    if rag is None:
        return None
    rid = rag.get("runId")
    owner = other = None
    for role in ("main", "noise"):
        r = runs.get(role)
        if r is None:
            continue
        if r["runId"] == rid:
            owner = r
        else:
            other = r
    if owner is None or other is None:
        return None
    q43 = [r["questionNo"] for r in rag["逐题"]]
    common = [q for q in q43 if q in owner["rows"] and q in other["rows"]]
    pair = [(q, owner["rows"][q], other["rows"][q]) for q in common]

    def diff_field(field):
        return [q for q, x, y in pair if x.get(field) != y.get(field)]

    return {
        "owner": owner, "other": other, "n43": len(q43), "common": common,
        "gold": diff_field("命中的正解切片"),
        "ctxlen": diff_field("上下文条数"),
        "seq": diff_field("跨次序列变了"),
        "attr": diff_field("归因"),
    }


def _ragas_retrieval_overlap(runs, rag):
    """RAGAS 只跑在其中一轮上；用另一轮做检索侧的对照。

    ★ 这一段**不花钱**，而且它把 `context_recall` 的检索侧波动带钉死了。
    """
    out = []
    st = ragas_overlap_stats(runs, rag)
    if st is None:
        out.append("⚠️ 拿不到两轮，跳过。")
        return out
    owner, other, common = st["owner"], st["other"], st["common"]
    q43 = st["n43"]
    gold_diff, len_diff, seq_diff, attr_diff = st["gold"], st["ctxlen"], st["seq"], st["attr"]
    out.append("RAGAS 跑在 `%s` 上；这一节把它和 `%s`（**同配置**）逐题比对。"
               % (owner["runId"], other["runId"]))
    out.append("")
    out.extend(table(["比什么", "变了的题数", "题号"], [
        ["两轮都有的题", "%d / %d" % (len(common), q43), DASH],
        ["`命中的正解切片` 变了", num(len(gold_diff)), _codes(gold_diff) or "**无**"],
        ["`上下文条数` 变了", num(len(len_diff)), _codes(len_diff) or "**无**"],
        ["`归因` 桶变了", num(len(attr_diff)), _codes(attr_diff) or "**无**"],
        ["`跨次序列变了`（顺序）", num(len(seq_diff)), _codes(seq_diff) or "**无**"],
    ]))
    out.append("")
    if not gold_diff and not len_diff:
        out.append("★★ **结论：`context_recall` 的检索侧波动带在这个抽样上是【零】** —— "
                   "同配置两轮，%d 道题的正解命中集合与上下文条数**一道都没变**。" % len(common))
        out.append("")
        out.append("★ 注意 `跨次序列变了` 有 %s 道，但序列的**顺序**不是 `context_recall` 的输入 —— "
                   "它按陈述是否被上下文支持计分，**不看位置**（看位置的是 `context_precision`，本期没跑）。"
                   % num(len(seq_diff)))
        out.append("")
        out.append("⚠️ 这是 n=%d 的**实证**，不是恒真式。它说「这一期没测到」，"
                   "不说「它永远为零」。" % len(common))
    else:
        out.append("⚠️ **有题的正解命中集合变了** —— `context_recall` 的检索侧波动带**非零**，"
                   "上面那个数不能当成「稳」。")
    return out


# ================================================================
# §5 对比基线（阶段 4 的 20 题）
# ================================================================

def render_baseline(runs, rag):
    base, main = runs.get("baseline"), runs["main"]
    if base is None:
        return []
    out = ["★ **这不是 A/B** —— 两轮跑的是**不同的题库**（20 题 vs 159 题），"
           "总体不同，**差值不能归因于任何配置**。它的用途是回答一个问题："
           "**范围扩大 8 倍之后，数字掉了吗？**"]
    out.append("")
    ra, rb = base["report"], main["report"]
    spec = [
        ("意图 逐题取众数", "意图.逐题取众数", "★ 20 题那套构造方式不同，混着读要小心"),
        ("意图 ★★ 可比口径", "意图.★★可比口径的准确率", ""),
        ("检索范围（KB 题）", "检索范围.★主数字_KB题", ""),
        ("HitRate@5", "检索.HitRate@5", ""),
        ("Recall@5", "检索.Recall@5", ""),
        ("MRR@5", "检索.MRR@5", ""),
        ("过度检索 对标注叶子", "过度检索.★对标注叶子", ""),
        ("延迟 p50 total（ms）", "延迟.total_latency_ms.p50", ""),
    ]
    def over_render(node):
        if not isinstance(node, dict):
            return num(node)
        return "%s/%s = %s" % (num(node.get("越界条数")), num(node.get("上下文条数")), rat(node.get("值")))

    def over_identity(node):
        if not isinstance(node, dict):
            return node
        return (node.get("越界条数"), node.get("上下文条数"), node.get("可信"))

    rows = []
    for lbl, path, note in spec:
        if "过度检索" in path:
            cell, _same = pair_custom(get_path(ra, path), get_path(rb, path),
                                      over_render, over_identity)
        elif path.endswith(".p50"):
            cell, _same = pair_scalar(get_path(ra, path), get_path(rb, path))
        else:
            cell, _same = pair_ratio(get_path(ra, path), get_path(rb, path))
        rows.append([lbl, cell, note])
    out.extend(table(["指标", "阶段 4 的 20 题 / 主体轮 159 题", "口径"], rows))
    out.append("")
    out.append("★★ **定义性的差异必须先说**：这 20 题的 gold 是**多答案**的，"
               "构造方法是**反向构造**（先看检索结果再定 gold）。"
               "阶段 7 的 159 题是**先写完题、reload 之后才跑第一次**，"
               "纪律就写在 `docs/06` §2.2.1 —— 两套的可比性到此为止。")
    out.append("")
    out.append("⚠️ 因为这套题是**反向构造**的，它的数字**系统性偏好当前链路**。"
               "两个数字并排时，**不必为 20 题那边「更好」找原因**。")
    return out


# ================================================================
# §6 多轮集（单独分母）
# ================================================================

def render_multi(runs):
    multi = runs.get("multi")
    if multi is None:
        return []
    out = ["★★ **多轮题单独一套、单独分母、单独报告。** 理由是范畴性的，不是谨慎："]
    out.append("")
    out.append("```")
    out.append("分类器的输入【按决策不含历史】—— 所以「追问轮的意图」不是一个可测量的量。")
    out.append("```")
    out.append("")
    out.append("把多轮题混进单轮的分母，会让「意图准确率」这个数字**同时描述两件不同的事**。")
    out.append("")
    out.append("★ 纪律要求：多轮**单独 runId**、每题标一个「把它单独说该怎么说」的 gold、"
               "repeat 用**全新会话**（防串味）、摘要开关记进配置快照。")
    out.append("")
    rm = multi["report"]
    out.extend(table(["指标", "值", "口径"], [
        ["意图 逐行", _one(rm, "意图.逐行"), "分母 = `intent` 非空的行"],
        ["意图 逐题取众数", _one(rm, "意图.逐题取众数"), ""],
        ["意图 ★★ 可比口径", _one(rm, "意图.★★可比口径的准确率"), ""],
        ["**无唯一众数的题（诊断）**", num(get_path(rm, "意图.无唯一众数的题（诊断）.n")),
         "★★ 这一栏在单轮是**恒为 0** 的诊断项，在这里**不是**"],
        ["★ 非全票一致的题（主指标）", num(get_path(rm, "意图.★非全票一致的题（主指标）.n")), ""],
        ["检索 HitRate@5", _one(rm, "检索.HitRate@5"), ""],
        ["检索 Recall@5", _one(rm, "检索.Recall@5"), ""],
        ["归因 `ok`", _one(rm, "归因.桶.ok", "命中"), "★ 分母 %s" % num(get_path(rm, "归因.桶.★分母"))],
        ["归因 `rerank_dropped`", num(get_path(rm, "归因.桶.rerank_dropped")), ""],
        ["归因 `not_recalled`", num(get_path(rm, "归因.桶.not_recalled")), ""],
        ["过度检索 对标注叶子", _over_one(rm, "过度检索.★对标注叶子"), ""],
    ]))
    out.append("")
    n_nomode = get_path(rm, "意图.无唯一众数的题（诊断）.n")
    n_all = get_path(rm, "意图.逐题取众数.n")
    if n_nomode:
        out.append("★★ **`无唯一众数的题` = %s / %s** —— 在单轮集里这个数一直是 0，"
                   "因为在单轮里「三次刷同一句话」总会有一个众数；"
                   "而在多轮里，**追问轮的那句话被单独拿去分类，同一句话的 3 次分类会真的分裂**。"
                   % (num(n_nomode), num(n_all)))
        out.append("")
        out.append("★ 这正是 §6 开头那条范畴性理由的**实证** —— "
                   "不是「多轮更难」，是「追问轮的意图本来就不是一个可测量的量」。")
        out.append("")
    out.append("⚠️ **多轮集的数字不进本报告的任何汇总** —— 它们的唯一去处是这一节。")

    # ---------- 澄清恢复：三层（阶段 9.6）----------
    mcl = get_path(rm, "多轮澄清") or {}
    if mcl:
        out.append("")
        out.append("#### 澄清恢复（阶段 9.4 的机制在不在）")
        out.append("")
        out.append("★★ **分三层，是因为坏掉时得知道坏在哪一层** —— 三层的修法完全不同：")
        out.append("")
        out.append("1. **首轮反问发生了**（`status=3`）⇒ 澄清闸门把住了吗")
        out.append("2. **末轮带上了状态**（`intent_plan.resumed`）⇒ 9.4 的「读后即清」生效了吗")
        out.append("3. **末轮落点对了**（`intent` == gold）⇒ 补全之后判对了吗")
        out.append("")
        out.append("★ **前提是「三层全过」** —— 前两层是机制，第三层才是结果；"
                   "它们不是三个独立的率，而是同一个任务的三道关。")
        out.append("")
        n_judged = num(mcl.get("分母_①可判的尝试"))
        out.append("**① 首轮该不该反问 —— 2×2**（★ 不是「反问率」）")
        out.append("")
        out.extend(table(["格", "值", "口径"], [
            ["① 该反问_反问了", num(mcl.get("①该反问_反问了")), "对"],
            ["① 该反问_没反问（漏）", num(mcl.get("①该反问_没反问（漏）")),
             "★ **漏** ⇒ 修法是**调宽**"],
            ["① 不该反问_没反问", num(mcl.get("①不该反问_没反问")), "对"],
            ["① 不该反问_反问了（假阳）", num(mcl.get("①不该反问_反问了（假阳）")),
             "★ **假阳** ⇒ 修法是**调窄**（用户被无谓地打断）"],
            ["分母_①可判的尝试", n_judged, "★ 完整 × 标了 `expect_clarify`"],
            ["★★ ① 没有 gold 的尝试", num(mcl.get("★★①没有 gold 的尝试")),
             "★★ 多轮题必须显式标 `expect_clarify`（V17）—— 没标的判**不可判**，不猜"],
        ]))
        out.append("")
        out.append("★★ **反问得越多，「反问率」越高** —— 而「不该反问却反问了」是另一种错，"
                   "修法**相反**。所以这一层也是 2×2，不是一个比率。")
        out.append("")
        out.append("**②③ 与三层全过**")
        out.append("")
        out.extend(table(["层", "值", "口径"], [
            ["② 末轮带上了状态", _one(mcl, "②末轮带上了状态"),
             "分母 = 完整的多轮尝试（%s 次）；★ 只在 `intent_plan` ≥v3 上有这一格" % num(mcl.get("分母_完整的尝试"))],
            ["③ 末轮落点对了", _one(mcl, "③末轮落点对了"),
             "同样分母；`intent` == `standalone_question` 标的那一个"],
            ["**★★ 三层全过**", _one(mcl, "★★三层全过"),
             "★ 分母 = **①可判的尝试**（%s）；这才是「这个任务解决了」" % n_judged],
            ["末轮命中@5", _one(mcl, "末轮命中@5"), "★ 末轮只有 `status=1` 且有正解切片时才进分母"],
            ["★ 不完整的尝试", num(mcl.get("★不完整的尝试")),
             "★★ 会话断在中途 —— **不进任何分子分母**"],
            ["★ 计划里没有 resumed 这一格的尝试",
             num(mcl.get("★计划里没有 resumed 这一格的尝试")),
             "★ 「0% 带上了状态」有两种成因：功能没生效 / 数据是旧版本"],
            ["★ 没有 session_id 的行", num(mcl.get("★没有 session_id 的行")),
             "★★ 非 0 = 投影漏了这一列，多轮分组会全部塌成一组"],
        ]))
        out.append("")
        out.append("★★ **三层全过的 ① 那一半是「判对了」而不是「反问了」** ——"
                   "「不该反问而没反问」同样算过。写成 `asked && …` 会让这个指标变成"
                   "「反问得越多越好」，而那不是它的意思。")
        # ★★ 阶段 9.6a：① 漏掉的那些，成因**不在闸门松紧**，在「落点」—— 见 §2.12。
        sd = get_path(rm, "槽位声明") or {}
        if sd:
            out.append("")
            out.append("**① 漏掉的那些去了哪 —— `missing` 的去向**（★ 与 §2.12 同一笔账）")
            out.append("")
            out.extend(table(["格", "值", "口径"], [
                ["分母_声明缺槽位的行", num(sd.get("分母_声明缺槽位的行")),
                 "★ 模型自己说「还缺什么」的行"],
                ["声明缺槽位_落在澄清分支（反问了）",
                 num(sd.get("声明缺槽位_落在澄清分支（反问了）")),
                 "⚠️ **定义性**的一格（`status=3` 就是那条分支），当对照用"],
                ["★★ 声明缺槽位_落在业务码（直接作答）",
                 num(sd.get("★★声明缺槽位_落在业务码（直接作答）")),
                 "★★ **模型的这个输出没被消费**"],
            ]))
            out.append("")
            out.append("★★ **`missing` 非空 ≠ 会反问。** 落点是澄清分支才反问，"
                       "而落点是模型**另一个**输出 —— `missing` 唯一的消费方是澄清文案"
                       "（缺哪个槽位、问哪一句）。⇒ 想让 ① 的**漏**变少，"
                       "要改的不是 `missing` 的解析，是**落点怎么定的**"
                       "（分类 prompt 或闸门输入）。")
            out.append("")
            out.append("⚠️ **别把这一格读成「闸门太窄」。** 落业务码的那些行"
                       "**多数答得对**（终点是对的，只是没走反问那条路）；"
                       "把「声明了却没反问」整批算成缺陷，会让修法指向"
                       "「反问得越多越好」，而那是错的。")
        n_incomplete = mcl.get("★不完整的尝试") or 0
        if n_incomplete:
            out.append("")
            out.append("⚠️ **不完整的尝试 %s 次** —— 它们被**排除在每一个分母之外**。"
                       "混进去的话，一次半途断掉的会话会被读成「模型答不对」，"
                       "而真相是那一轮压根没跑完。明细：" % num(n_incomplete))
            out.append("")
            for item in (mcl.get("不完整的尝试明细") or []):
                out.append("- %s" % md_text(item))
        n_nogold = mcl.get("★★①没有 gold 的尝试") or 0
        if n_nogold:
            out.append("")
            out.append("⚠️⚠️ **%s 次尝试的 ① 没有 gold** —— 这些题是多轮题，"
                       "而**没有标 `expect_clarify`**。它们**不进 ① 和「三层全过」的"
                       "分子分母**（分母是 %s）。"
                       "★ 为什么不猜：唯一能猜的来源是 `intent`，而多轮题的 `intent` "
                       "指**末轮** ⇒ 猜出来必然是「每一道都不该反问」——"
                       "一个恒为 0 的「反问率」，而它与真值长得一模一样地印在报告里。"
                       % (num(n_nogold), num(n_judged)))
            out.append("")
            for item in (mcl.get("①没有 gold 的尝试明细") or []):
                out.append("- %s" % md_text(item))
        n_v3 = mcl.get("★计划里没有 resumed 这一格的尝试") or 0
        if n_v3:
            out.append("")
            out.append("⚠️ **%s 次尝试的 `intent_plan` 里没有 `resumed` 这一格** —— "
                       "那份数据跑在 9.4 之前。**这时的「② = 0」是数据版本造成的，"
                       "不是功能坏了**；判据就是这一栏。" % num(n_v3))
        out.append("")
        out.append("★★ **轮次边界靠 `session_id` 还原，不靠位置推算。** "
                   "一轮多轮题把 N 轮提交在**同一个题号**下，而 `qa_log` 没有轮次列。"
                   "「第 r 次重复的第 t 轮 = 第 r×T+t 行」依赖的是**客户端的两层循环顺序**，"
                   "客户端一改循环就静默错位；而每次重复**新建一个会话**，"
                   "所以「同题号 + 同 session」是**服务端看得见的事实**。")
    return out


def _over_one(report, path):
    """过度检索的节点分母叫 `上下文条数`，不是 `n` —— 单独一个渲染器。
    ★ 硬套通用的 `命中/n` 会印出一句语义相反的话（它是越界率，越小越好）。"""
    node = get_path(report, path)
    if not isinstance(node, dict):
        return num(node)
    return "%s/%s = %s" % (num(node.get("越界条数")), num(node.get("上下文条数")), rat(node.get("值")))


def _one(report, path, key="值"):
    node = get_path(report, path)
    if not isinstance(node, dict):
        return num(node)
    if "命中" in node and key == "命中":
        return "%s/%s" % (num(node.get("命中")), num(node.get("n")))
    if "命中" in node:
        return "%s/%s = %s" % (num(node.get("命中")), num(node.get("n")), rat(node.get("值")))
    return "%s (n=%s)" % (rat(node.get("值")), num(node.get("n")))


# ================================================================
# §7 方法验证：这个工具不是瞎的
# ================================================================

def architecture_diff(report_a, report_b):
    """★ 两轮是不是跑在**不同的代码版本**上？

    ★★ 判据用 `归因.★截断可判定性` —— 它数的是 `retrieval_detail` 里**有没有 `sizes` 段**，
      而 `sizes` 是 2026-09-21 才加进那个冻结结构的。所以它是一条**代码版本代理指标**。

    ⚠️ 为什么必须查这个：一轮跑在改代码之后、另一轮在之前，那两轮的差异就有
      **两个成因**（配置 + 代码），而报告只会把功劳 / 责任算给配置。
      这一条不查是查不出来的 —— `run.raw.json` 里没有代码版本字段。
    """
    ca = get_path(report_a, "归因.★截断可判定性") or {}
    cb = get_path(report_b, "归因.★截断可判定性") or {}
    ha, hb = ca.get("有sizes段的行"), cb.get("有sizes段的行")
    na, nb = ca.get("无sizes段的行"), cb.get("无sizes段的行")
    if None in (ha, hb, na, nb):
        return None
    return (ha, na, hb, nb)


def render_method_check(runs):
    base, bad = runs.get("baseline"), runs.get("baseline_bad")
    if base is None or bad is None:
        return []
    out = ["★ 一个**瞎掉的比较器输出全绿**。「翻转 0 道」和「什么都没比」在屏幕上逐字相同。"
           "所以这里主动制造一个**已知会变差**的配置，看矩阵抓不抓得到。",
           ""]
    differ, only_a, only_b, total = eval_ab.config_diff(bad["raw"], base["raw"])
    out.append("**假配置**（正常 → 假配置）：%s"
               % (", ".join("`%s`：%s → %s" % (k, v2, v1) for k, v1, v2 in differ)
                  or "（没有差异 —— 这次验证不成立）"))
    out.append("")
    # ★★ 混淆因子检查：这两轮除了配置，代码版本一样吗？
    arch = architecture_diff(bad["report"], base["report"])
    if arch and arch[0] != arch[2]:
        out.append("⚠️⚠️ **这两轮还差了【代码版本】，不只是配置** —— "
                   "`有 sizes 段的行` 是 %s vs %s（假配置 vs 正常）。"
                   "`sizes` 是 2026-09-21 才加进 `retrieval_detail` 的，"
                   "所以「正常」那一轮跑在更早的代码上。"
                   % (num(arch[0]), num(arch[2])))
        out.append("")
        out.append("★★ **后果**：下面的差异有**两个成因**（配置 + 代码），而这张表把它们**全算给了配置**。"
                   "★ 判据是 `归因.★截断可判定性` —— 它数的就是「`retrieval_detail` 里有没有 `sizes` 段」，"
                   "所以它同时是一条**代码版本代理指标**。")
        out.append("")
        out.append("★ 这个混淆里**没有被掩盖的结论**：`final-top-k 5 → 1` 直接作用于检索深度，"
                   "而 `sizes` 只影响「记录够不够判**归因桶**」—— 它碰不到检索本身。"
                   "所以「矩阵抓得到这个假配置」成立；**但归因桶那两行不能单独引用**。")
        out.append("")
        out.append("★ 要消掉它只能**重跑一轮「正常」基线**（用当前代码）。**重算报告没用** —— "
                   "缺的那个段当时就没写进库。")
    else:
        out.append("✅ **两轮的代码版本一致**（`有 sizes 段的行` 都是 %s）—— "
                   "下面的差异只有一个成因：配置。" % num(arch[0] if arch else "?"))
    out.append("")
    changed, same, oa, ob = eval_ab.metric_diff(bad["report"], base["report"])
    # ★ 延迟不参与这里的 diff —— 它的两轮样本数不同（`status≠1` 的行数不一样），
    #   比的不是同一个总体。把它混进来会让这张表里 30 行是噪声，真信号被埋掉。
    changed = [c for c in changed if not c[0].startswith("延迟.")]
    rows = []
    for path, va, vb, delta in changed:
        rows.append(["`%s`" % path, num(va), num(vb),
                     signed_auto(delta) if delta is not None else DASH])
    if rows:
        out.extend(table(["指标", "假配置", "正常", "Δ"], rows))
        out.append("")
        out.append("★ **延迟的每一格都动了，但没有列进来** —— 两轮被 `status≠1` 截掉的条数不同，"
                   "总体都不一样。延迟的口径见 §2.8。")
        out.append("")
    else:
        out.append("⚠️ **指标表一格都没变** —— 那这个假配置选得不好，或者比较器有问题。")
        out.append("")

    ra, rb, common, oa2, ob2 = eval_ab.common_rows(bad["report"], base["report"])
    flip_rows, flip_details = eval_ab.flip_table(ra, rb, common)
    out.append("**逐题翻转矩阵**")
    out.append("")
    out.extend(table(["判据", "共同可比", "翻转", "翻的是哪些题"],
                     [[m, num(c), num(f), _codes([q for q, _x, _y in flip_details[m]]) or "—"]
                      for m, c, f in flip_rows]))
    out.append("")

    # ★★ 交叉表：配置的作用面 vs 噪声面
    hit = {q for q, _a, _b in flip_details.get("命中@5", [])} | \
          {q for q, _a, _b in flip_details.get("归因", [])}
    intent = {q for q, _a, _b in flip_details.get("意图正确", [])}
    if hit and intent:
        both, only_hit, only_intent = hit & intent, hit - intent, intent - hit
        out.append("**★★ 交叉表：翻转的成因分得开吗**")
        out.append("")
        out.extend(table(["组", "题号", "读法"], [
            ["`命中@5` / `归因` 翻", _codes(sorted(only_hit)) or "—", "← **旋钮的作用面**"],
            ["`意图正确` 翻", _codes(sorted(only_intent)) or "—", "← **噪声**（这个旋钮碰不到分类器）"],
            ["两组都翻", _codes(sorted(both)) or "—", "⚠️ 需要逐题看"],
        ]))
        out.append("")
        if not (only_hit & only_intent):
            out.append("★★ **两组【零交集】** —— 这是「成因分得开」的直接证据，"
                       "不是靠推理得出的。")
            out.append("")
            out.append("★★★ 而 `final-top-k` **碰不到分类器**，可 "
                       "`意图正确` 还是翻了 %s 道 —— **%s 个百分点**。"
                       % (num(len(intent)),
                          num(round(100.0 * len(intent) / len(common), 1)) if common else DASH))
            out.append("")
            out.append("★ 它证明的是一件更基本的事：**「配置不可能影响这一栏」不等于「这一栏不会变」。**")
            out.append("")
    else:
        out.append("⚠️ 只翻了一组，交叉表不成立。")
        out.append("")
    return out


# ================================================================
# §8 红绿线：哪些差异是信号
# ================================================================

def render_redgreen(runs):
    a, b = runs["noise"], runs["main"]
    ra, rb, common, _x, _y = eval_ab.common_rows(a["report"], b["report"])
    flip_rows, details = eval_ab.flip_table(ra, rb, common)
    out = ["把两轮之间**实际存在**的差异按成因分类。判据只有一条："
           "**这个差异能不能被一个已证伪的成因解释掉。**", ""]
    out.append("| 类 | 判据 | 这一期的数 |")
    out.append("|---|---|---|")
    churn = {q for q, _a, _b in details.get("跨次序列变了", [])}
    hit = {q for q, _a, _b in details.get("命中@5", [])}
    attr = {q for q, _a, _b in details.get("归因", [])}
    intent = {q for q, _a, _b in details.get("意图正确", [])}
    gate = {q for q, _a, _b in details.get("闸门", [])}
    out.append("| 🔴 **序列 churn**（顺序变了，命中没变） | `跨次序列变了` | %s 道 |" % num(len(churn)))
    out.append("| 🟡 **命中变化**（真的换了切片） | `命中@5` | %s 道 |" % num(len(hit)))
    out.append("| 🟡 **归因变化** | `归因` | %s 道 |" % num(len(attr)))
    out.append("| 🟢 **分类变化**（含漂移，不代表变好变坏） | `意图正确` | %s 道 |" % num(len(intent)))
    out.append("| 🟢 **闸门变化** | `闸门` | %s 道 |" % num(len(gate)))
    out.append("")
    out.append("**读法**")
    out.append("")
    out.append("- 🔴 序列 churn **不构成**「变好 / 变坏」的证据 —— 它只改顺序，不改命中集合")
    out.append("- 🟡 **命中 / 归因的变化才有资格被叫作信号**，但**仍然不是「变好」** —— "
               "换一个同样合法的 gold 命中也是 `ok → ok`（洞 1）")
    out.append("- 🟢 分类与闸门的变化是**漂移**：换一个时间跑就会换一批题。"
               "**要声称「分类变好了」，只有换配置 / 换 prompt 之后的重跑才算**")
    out.append("")
    out.append("⚠️⚠️ **这一期没有做任何迭代**（那是下一步的事）。"
               "所以上面**没有一格是「改了 X 之后变好了」** —— 全部是噪声。")
    return out


# ================================================================
# §9 已知局限 / §10 未解释的现象
# ================================================================

def render_limits(runs, rag):
    out = []
    out.append("**标注与构造**")
    out.append("")
    out.append("- **标注者 = Claude，审核 = 你抽查。** 不写「人工标注集」这种含糊说法")
    out.append("- ★ **阶段 4 的 20 题是反向构造的**（先看检索结果再定 gold）—— "
               "它的数字**系统性偏好当时的链路**。那份基线在本项目里被**自己推翻过一次**："
               "实测 4 道「重排弄坏」的题里 3 道是 gold 标错了")
    out.append("- ★ 题库里存在**两个已知的配额偏差**（`MEMBERSHIP` 3 道、`PURCHASE_TIMING` 6 道，"
               "比原计划少）—— 原因与去向写在 `stage7-questions.yml` 的文件头")
    out.append("")
    out.append("**评测本身的局限**")
    out.append("")
    out.append("- ★★ **噪声底只覆盖两轮的随机性** —— 不覆盖换机器、换供应商侧版本这类更慢的漂移")
    out.append("- ★★ **A1 那轮的 `qa_log` 行没有 `sizes` 段**（是后加的），"
               "所以归因一节有一部分是「两轮数据形状不同」，不是「数据变了」。"
               "**只能重跑才能对齐，重算报告没用**")
    out.append("- ★ **延迟比的不是同一个总体**（两轮被 `status≠1` 截掉的条数不同）")
    out.append("- ★★ **RAGAS 的生成侧与 judge 侧波动没有任何数据**（见 §4.5）")
    out.append("- ★ **n < %d 的切片一律不作为结论** —— 报告里每一处都标了" % MIN_SLICE_N)
    out.append("- ★ 成本只统计了**进 `qa_log` 的那一部分**；意图分类、摘要压缩、RAGAS 判官都不在里面")
    out.append("")
    out.append("**证明标准**")
    out.append("")
    out.append("- ★★ **在 20 题上，任何「X 比 Y 好」都不可信**（`docs/06` §3.2）—— "
               "0.025 的差距恰好等于一道题从第 1 名掉到第 2 名，而**符号能双向翻转**")
    out.append("- ★★ **检索指标上升不构成「变好了」的证据** —— "
               "它看不见「命中了哪几条」（洞 1）。有资格定胜负的是答案侧指标")
    out.append("- ★ **阈值红线**：只有差异**超过 §1 那个噪声底**、且**在答案侧也同向**，才叫结论")
    return out


def render_unexplained(runs, rag):
    out = []
    out.append("以下现象**曾经**没解释清。★ 10.1 已于 2026-09-24 **结案**，"
               "其余仍未解释 —— 留着比删掉好，它们标记的是「这条链路里还有我们不知道的东西」。")
    out.append("")
    out.append("### 10.1 ★ 已结案：`Set.copyOf` 的渲染顺序")
    out.append("")
    out.append("**现象**：同一份数据、同一份 `intent-tree.yml`（`doc_types: [1, 5]`），"
               "旧一轮 `report.json` 印 `[1, 5]`、新一轮印 `[5, 1]`。"
               "当时用**同一个 JDK** 单独跑 `Set.copyOf(List.copyOf(new ArrayList<>(List.of(1,5))))` "
               "得到的是 `[1, 5]` —— **复现不出来，也就没解释清**。")
    out.append("")
    out.append("**★★★ 2026-09-24 解释清楚了**：")
    out.append("")
    out.append("```")
    out.append("Set.copyOf 走的是 ImmutableCollections.SetN（不是保留插入顺序的 Set12），")
    out.append("它的迭代顺序是 floorMod(hash ^ SALT, tableSize) ——")
    out.append("而 SALT 【每次 JVM 启动都随机】（JDK 9+）。")
    out.append("")
    out.append("实测 20 个独立 JVM，Set.copyOf(List.of(4, 2)).toString()：")
    out.append("    [4, 2] 14 次        [2, 4] 6 次")
    out.append("换成 3 个元素，20 个 JVM 跑出了【5 种不同顺序】。")
    out.append("```")
    out.append("")
    out.append("★ 所以「同一个 JDK 复现不出来」**正是这个机制的表现**：")
    out.append("**在【单个 JVM 内】它是确定的**（同一个 SALT），换个 JVM 就变 —— "
               "而当时的复现尝试全都在一个 JVM 里。")
    out.append("")
    out.append("★★ 同一个 SALT 还让一个**测试**变成 flaky（约 50% 概率红）："
               "`EvalReportServiceTest` 里断言 `Set.copyOf([4,2]).toString() == \"[4, 2]\"`，"
               "而 `n≥3` 时盲区**不随元素个数下降**（稳定在约 1/4）。"
               "处置见 `docs/10` 坑 19 —— 对照物换成顺序可控的 `LinkedHashSet`。")
    out.append("")
    out.append("★ 处置不变，而且现在**有理由**了：不再依赖它 —— "
               "`Set` 的迭代顺序**根本不是它承诺的东西**。"
               "渲染前排序之后，那两个字符串由我们决定（`EvalReportService.sorted()`）。")
    out.append("")
    out.append("★ **数字不受影响** —— `Set.equals` 与顺序无关，实测两轮检索范围准确率的"
               "**分子分母逐字相同**（§2.3 那一行的 `✅` 就是这个证据）。")
    out.append("")
    out.append("### 10.2 RAGAS 的 `MetricResult.reason` 恒为 `None`")
    out.append("")
    out.append("`ragas 0.4.3` 四个 collections 指标的源码就是 `return MetricResult(value=float(score))`，"
               "**压根不填 reason**。")
    out.append("")
    out.append("★ 所以「为什么这道题 0.389」**不能从 RAGAS 自己的输出问**，"
               "只能用 T4 的归因桶做替代依据。要拿 statement 级判定得实现一个记录 "
               "`_create_verdicts` 返回值的子类，且**每次调用新建指标实例**"
               "（共用一个实例会让并发互相踩 `self.last_verdicts`）—— 本期不做。")
    out.append("")
    out.append("### 10.3 出站调用的超时没有触发")
    out.append("")
    out.append("阶段 6 的跑批里发现两个线程在 `httpClient.send()` 上挂了 **60+ 分钟**，"
               "而 `.timeout(180s)` 已经设在 `HttpRequest` 上。")
    out.append("")
    out.append("★ 处置：换成 `sendAsync(...).get(timeout)` —— **我们自己的闸门**，不依赖 JDK 内部的定时器。"
               "★ **没做**：查清 JDK 的 `.timeout()` 为什么没触发。绕开它需要一次定向复现，"
               "收益是「知道 JDK 的行为」而不是「修好我们的东西」。")
    out.append("")
    out.append("### 10.4 部分完成的结果不是「小一点的全体」")
    out.append("")
    out.append("RAGAS 跑到 9 行时报的是 `faithfulness 0.978`，跑完 43 行是 **0.786**。")
    out.append("")
    out.append("```")
    out.append("完成顺序 = asyncio.gather 的顺序 = 题号字母序 = 意图前缀序")
    out.append("  C-/F-/FR-/IN-/MB-... 先完成（faith 高）")
    out.append("  TI-/UG-/W- 最后完成（faith 低）")
    out.append("```")
    out.append("")
    out.append("★★ **在报告里引用未跑完的数字，等于系统性挑了一个乐观的子集** —— 而且没有任何迹象。")
    return out


# ================================================================
# 附录：逐题
# ================================================================

APPENDIX_COLS = [
    ("题号", "题号"),
    ("类别", "类别"),
    ("难度", "难度"),
    ("标注叶子", "标注叶子"),
    ("进检索分母", "进检索分母"),
    ("三次status", "三次status"),
    ("取成功次", "取的是第几次成功"),
    ("闸门", "闸门"),
    ("分类众数", "分类众数"),
    ("意图正确", "意图正确"),
    ("命中@5", "命中@5"),
    ("recall@5", "recall@5"),
    ("mrr@5", "mrr@5"),
    ("归因", "归因"),
    ("上下文", "上下文条数"),
    ("越界", "越界切片数"),
    ("序列变", "跨次序列变了"),
    ("耗时ms", "总耗时ms"),
]


# ================================================================
# §11 迭代实验（T8）
# ================================================================

def _parse_ts(s):
    """`2026-09-22T21:59:31+08:00` → datetime。拿不到就 None（不猜）。"""
    if not s:
        return None
    try:
        import datetime
        return datetime.datetime.fromisoformat(s)
    except ValueError:
        return None


def _gap_label(raw_a, raw_b):
    """两轮之间隔了多久 —— ★ 这个数字是噪声表里唯一能解释
    「为什么这一对能当噪声底」的东西：同一会话里隔十几分钟，
    和隔一天，不是同一类对照。

    ⚠️ 「跨日」必须按【日历日】判，不能按小时数 ——
    22:22 跑完、次日 21:29 再跑，相差 23 小时（不到一天），
    但它跨了一个日历日，而且中间隔了一整夜的应用重启。
    按小时数分段会把这一对说成「同日」，而它恰恰是那组跨日对照。
    """
    ta, tb = _parse_ts(raw_a.get("finishedAt")), _parse_ts(raw_b.get("startedAt"))
    if ta is None or tb is None:
        return "—"
    mins = abs((tb - ta).total_seconds()) / 60.0
    if mins < 1:
        return "接续（< 1 分钟）"
    if mins < 120:
        return "**同会话**（%.0f 分钟）" % mins
    if ta.date() != tb.date():
        return "**跨日**（%.1f 小时）" % (mins / 60.0)
    return "同日不同会话（%.1f 小时）" % (mins / 60.0)


def _cache_hit_estimate(report, raw):
    """从「成本 + token」反推缓存命中的输入 token 数 → (命中数, 命中率, 模型, 失败原因)。

    ★★ 为什么要反推：`prompt_cache_hit_tokens` **参与计费但不落库** ——
      `ModelCostCalculator.costOf` 用它算钱，算完就丢掉，`qa_log` 里没有这一列。
      于是「成本为什么涨了 9.5%」这个问题，除了反推没有别的办法。
      ★ 这本身就是一条该修的东西：一个进了计费公式却查不到的量。

    ★ 公式来自 `ModelCostCalculator`（已核实，不是猜的）：
      cost = miss×input + hit×cacheHitInput + completion×output   （三项都除以百万）

    ⚠️ **它假定整轮只用了一个模型。** 有降级行（P1/P2）时这个数不成立 ——
      所以下面有一段自检：解出来的命中数落在 [0, 输入 token] 之外就拒绝出数，
      而不是印一个看起来像模像样的百分比。
    """
    props = (raw.get("config") or {}).get("properties") or {}
    chain = props.get("xbla.llm.chat-chain[0]")
    if not chain:
        return None, None, None, "配置快照里没有 chat-chain[0]"
    pre = "xbla.llm.models.%s.pricing." % chain
    try:
        p_in = float(props[pre + "input"])
        p_hit = float(props[pre + "cache-hit-input"])
        p_out = float(props[pre + "output"])
    except (KeyError, TypeError, ValueError):
        return None, None, chain, "拿不到 `%s` 的三档定价" % chain
    if p_in == p_hit:
        return None, None, chain, "input 与 cache-hit-input 同价，反推不出命中数"

    cost = (report.get("成本") or {}).get("数据库里的成本合计")
    if not isinstance(cost, (int, float)):
        return None, None, chain, "没有成本合计"
    # token合计 是 prompt + completion，得把两者拆开 —— 报告里没直接给，用延迟那节的样本数反推不行。
    # ★ 所以这一项【必须】由成本节点给出；给不出就拒绝出数（不出一个凑出来的百分比）
    for k in ("prompt_token合计", "输出token合计"):
        if k not in (report.get("成本") or {}):
            return None, None, chain, "成本节点里没有 `%s`（加它要到 EvalReportService）" % k
    pt = report["成本"]["prompt_token合计"]
    ct = report["成本"]["输出token合计"]
    # ★★★ 退化输入先判。不判的话公式会解出一个**负数**，而负数的成因
    #   和下面那句「有降级行」长得完全不像 —— 读者会被指向错误的方向。
    #   实测撞到过：Java 侧 `selectByEvalRun` 的列清单漏了 `prompt_tokens` /
    #   `completion_tokens` → 两列都读成 0 → 解出 −372263，
    #   而那一轮实测 100% deepseek-flash，**一行降级都没有**。
    #   ★ 证据当时就在那句报错里（上界印的是 `[0, 0]`），只是没人按它去读。
    if not isinstance(pt, (int, float)) or not isinstance(ct, (int, float)) or pt <= 0:
        return None, None, chain, (
            "`prompt_token合计` = %r —— **反推的前提就不成立**（它必须是正数）。"
            "★ 这不是「有降级行」，是这一轮**读不到**那两个数："
            "去查 `QaLogMapper.selectByEvalRun` 的列清单里有没有 "
            "`prompt_tokens` / `completion_tokens`" % (pt,))
    hit = (pt * p_in / 1e6 + ct * p_out / 1e6 - cost) / ((p_in - p_hit) / 1e6)
    if not (0 <= hit <= pt):
        # ★ 只陈述观察，不指认成因 —— 「落在范围外」至少有两个不互斥的可能
        #   （降级行 / 上游 token 数与定价对不上），而原来那句话写死了前者。
        return None, None, chain, ("解出命中数 %.0f，落在 [0, %d] 之外 —— "
                                   "★ 成因未定，**这一条不下结论**" % (hit, pt))
    return hit, hit / pt, chain, None


def _parse_hits(s):
    """`"[5, 25]/[5, 25]"` → (命中的切片 id 集, 该题的全部正解集)。

    ⚠️ 这个字段是**字符串**不是数组 —— 直接当集合用会得到一个逐字符迭代的
      结果（`"[5, 25]"` → `{ '[', '5', ',', ...`），而它**不报错**。
    """
    if not isinstance(s, str) or "/" not in s:
        return None, None
    a, b = s.split("/", 1)
    grab = lambda t: {int(x) for x in re.findall(r"\d+", t)}  # noqa: E731
    return grab(a), grab(b)


def _containment(ra, rb, common):
    """命中集合在 B 里相对 A 的包含关系 —— ★★ **结构性论证的实证版**。

    `final-top-k` 调大 ⇒ contexts 是超集 ⇒ `context_recall` / `faithfulness`
    **结构性不可能下降**；`max-candidates` 调小 ⇒ 重排看到的是子集 ⇒
    召回**结构性不可能上升**。这两句都是原理，不是数据。

    ★ 但「原理上成立」和「这一轮真成立」是两件事：重排分数有约 3e-4 的漂移，
      所以边界的切片偶尔会进出。**把这些违例数出来，比宣示一句「超集」有用得多。**
    """
    got_a, got_b = {}, {}
    for q in common:
        ha, _ = _parse_hits(ra[q].get("命中的正解切片"))
        hb, _ = _parse_hits(rb[q].get("命中的正解切片"))
        if ha is None or hb is None:
            continue
        got_a[q], got_b[q] = ha, hb
    sup = [q for q in got_a if got_b[q] >= got_a[q]]     # B ⊇ A
    sub = [q for q in got_a if got_b[q] <= got_a[q]]     # B ⊆ A
    eq = [q for q in got_a if got_b[q] == got_a[q]]
    lost = [q for q in got_a if got_a[q] - got_b[q]]     # A 有、B 没有的
    return {"n": len(got_a), "sup": sup, "sub": sub, "eq": eq, "lost": lost}


def _overrides(raw):
    """这一轮真正被【运行时覆盖】的配置键 → 值。

    ★★ 判据必须**正面**写：yml 来源的 origin 一律长成 `Config resource ...`，
      覆盖不长这样。反着写（`'application' not in origin`）会把
      `spring.application.json` **自己**滤掉 —— 它含 `application` ——
      于是「覆盖生效了」被判成「没生效」。
      ★ 本项目已经踩过三次同形的坑：`keyword-weight` 含 `key`（脱敏误伤）、
      `Map.of` 拒 null、`Set.copyOf` 迭代顺序。**子串匹配不是判据。**
    """
    cfg = raw.get("config") or {}
    origin, props = cfg.get("origin") or {}, cfg.get("properties") or {}
    return {k: props.get(k) for k, v in origin.items()
            if not str(v).startswith("Config resource")}


def _t8_pairs(noise_runs):
    """把噪声链拆成相邻两对。★ 链而不是「一对」：
    N 轮同配置给出 N-1 个独立的噪声底样本，而**底稳不稳定**本身就是要报的事 ——
    只有一个样本时，「这个数换个时间跑还会不会是这样」没有答案。"""
    out = []
    for i in range(len(noise_runs) - 1):
        a, b = noise_runs[i], noise_runs[i + 1]
        probs = eval_ab.fingerprint_check(a["raw"], b["raw"])
        differ = eval_ab.config_diff(a["raw"], b["raw"])[0]
        # ★ 噪声链上任一轮没跑成，这一对就量不出噪声 —— 它量的是「熔断器什么时候跳的」
        for r in (a, b):
            u, bad, ratio, _h = eval_ab.run_usability(r["raw"])
            if not u:
                probs.append("  ✗ %s 有 %.1f%% 的行是模型链路失败（%d 行）"
                             % (r["runId"], ratio * 100, bad))
        ra, rb, common, _, _ = eval_ab.common_rows(a["report"], b["report"])
        rows, details = eval_ab.flip_table(ra, rb, common)
        # ★ `rows` 里第三项是【计数】，题号在 `details` 里 —— 两个都不一样，
        #   混起来会得到一个永远算不出来的数（或一个静默错掉的数）
        n_any = len({q for flips in details.values() for q, _, _ in flips})
        out.append({
            "a": a, "b": b, "probs": probs, "differ": differ,
            "common": len(common), "rows": rows, "flipped_any": n_any,
            "gap": _gap_label(a["raw"], b["raw"]),
        })
    return out


def render_iterations(t8):
    """§11 迭代实验（T8）。

    ★★★ 这一节和报告前半的差别在于：前半是「我们现在在哪」，
      这一节是「**动了什么、然后去了哪**」。路线图的验收标准里
      「体现迭代过程」指的就是它。

    ★★ 纪律①在这里的具体形态：**没有任何一句「重排很重要」是写死的** ——
      每一句都从 `slice_verdict` 的返回值里长出来。数据换了句子跟着换。
    """
    out = []
    if not t8 or not t8.get("axes"):
        # ★★★ 三态，不是两态。原来把「没给」和「给错了」压成同一句话，
        #   而后者会**静默删掉一整节交付物**（一个写错的 runId 就够了）。
        reason = (t8 or {}).get("unreadable")
        if reason:
            out.append("⚠️⚠️ **本节读不到输入 —— 这不等于「没有做过迭代」。**")
            out.append("")
            out.append("报告生成器拿不到这几轮，原因是：")
            out.append("")
            out.append("```")
            out.append(reason)
            out.append("```")
            out.append("")
            out.append("★ **「读不到」是关于我们输入的事实，「没有做过」是关于世界的事实。**"
                       "把它们渲染成同一句话，会让一个写错的 runId 静默删掉一整节。"
                       "修法是改正上面的 runId（或 `--t8-noise` / `--t8-axes`）然后重跑。")
        else:
            out.append("（这一期没有迭代实验 —— `--t8-noise` / `--t8-axes` 都是空的，"
                       "**不是**「给了但读不到」）")
        return out

    axes = t8["axes"]
    noise_runs = t8.get("noise_runs") or []
    # ★ 基线 = 【链尾】。它不是随便挑的：三条轴紧跟在它后面跑，
    #   所以它和三条轴之间的时间间隔最小 —— 拿链首当基线会白白多算一段漂移。
    base = noise_runs[-1] if noise_runs else None
    if base is None:
        out.append("⛔ **没有噪声链 ⇒ 没有基线 ⇒ 这一节不成立。**")
        return out

    # ── 11.0 这一节在回答什么 ──────────────────────────────────
    out.append("### 11.0 三条轴与它们的基线")
    out.append("")
    out.append("每一次 A/B 都是**只动一个配置键**，其余一字不改（用 "
               "`SPRING_APPLICATION_JSON` 覆盖，不改 yml 文件 —— 改文件的话"
               "「这轮跑的到底是哪份配置」就没有证据）。")
    out.append("")
    out.append("| 轴 | 配置键 | A | B | 轮次 |")
    out.append("|---|---|---|---|---|")
    for ax in axes:
        differ = eval_ab.config_diff(base["raw"], ax["raw"])[0]
        if not differ:
            out.append("| %s | ⚠️ **无配置差异** | — | — | `%s` |"
                       % (ax["label"], ax["runId"]))
            continue
        for k, va, vb in differ:
            out.append("| %s | `%s` | `%s` | `%s` | `%s` |"
                       % (ax["label"], k, eval_ab.fmt(va), eval_ab.fmt(vb), ax["runId"]))
    out.append("")
    out.append("★★ **配置差异从哪来** —— 每一轮的配置快照都记了每个键的**来源**，")
    out.append("所以「这一轮真的跑在它的覆盖下」是**证据**，不是信任：")
    out.append("")
    out.append("| 轮 | 运行时覆盖的键（来源非文件） |")
    out.append("|---|---|")
    for r in (noise_runs + axes):
        ov = _overrides(r["raw"])
        out.append("| `%s` | %s |" % (
            r["runId"],
            "、".join("`%s`=`%s`" % (k, eval_ab.fmt(v)) for k, v in sorted(ov.items()))
            or "（无 —— 全走 yml）"))
    out.append("")
    out.append("★ 判据是**正面**的：yml 来源的 origin 一律长成 `Config resource ...`，"
               "覆盖不长这样。反着写会把 `spring.application.json` 自己滤掉。")
    out.append("")

    # ── 11.1 噪声底：同会话 vs 跨日 ────────────────────────────
    out.append("### 11.1 噪声链：**先把尺子做出来**")
    out.append("")
    out.append("★★★ 这不是脚注，是第一张表。三条轴上任何一个「变了」的结论，"
               "都必须**先跟同一片里的噪声比大小** —— 否则读的是分辨率，不是信号。")
    out.append("")
    if noise_runs:
        out.append("噪声链是 **%d 轮同配置**：%s。相邻两对各给出一个噪声底样本。"
                   % (len(noise_runs),
                      " → ".join("`%s`" % r["runId"] for r in noise_runs)))
    else:
        out.append("⚠️ **没给噪声链（`--t8-noise`）** —— 下面所有轴的结论都"
                   "**没有判据**，只能当参考。")
    out.append("")
    pairs = _t8_pairs(noise_runs)
    if pairs:
        out.append("| 对 | 相隔 | 配置差异 | 共同可比 | 至少翻一格 | 翻转率 | 可用 |")
        out.append("|---|---|---|---|---|---|---|")
        for p in pairs:
            rate = (p["flipped_any"] / p["common"]) if p["common"] else 0.0
            out.append("| `%s` ↔ `%s` | %s | %d | %d | **%d** | **%.1f%%** | %s |"
                       % (p["a"]["runId"], p["b"]["runId"], p["gap"],
                          len(p["differ"]), p["common"], p["flipped_any"], rate * 100,
                          "✅" if not p["probs"] else "⛔ **不可用**"))
        out.append("")
        broken = [p for p in pairs if p["probs"]]
        if broken:
            out.append("⚠️ **上面标 ⛔ 的那些对量不出噪声** —— 它们里面有一轮没跑成。")
            out.append("  「翻转率」那一列对它没有意义：它量的是熔断器什么时候跳的。")
            for p in broken:
                for line in p["probs"]:
                    out.append("  %s" % line)
            out.append("")
            out.append("★ 本节所有的判据都应当改用**没被标 ⛔ 的那一对**。")
            out.append("")
        out.append("**逐判据的翻转率（合成一张表，因为要横向比）**")
        out.append("")
        out.append("| 判据 | 共同可比 | " + " | ".join(
            "`%s`↔`%s`" % (p["a"]["runId"].split("-")[-1], p["b"]["runId"].split("-")[-1])
            for p in pairs) + " |")
        out.append("|---|---|" + "---|" * len(pairs))
        for metric in eval_ab.CATEGORICAL:
            cells, ns = [], None
            for p in pairs:
                hit = next(r for r in p["rows"] if r[0] == metric)
                ns = hit[1] if ns is None else ns
                cells.append("**%.1f%%**（%d）" % (100.0 * hit[2] / hit[1], hit[2])
                             if hit[1] else "—")
            out.append("| `%s` | %d | %s |" % (metric, ns or 0, " | ".join(cells)))
        out.append("")
        out.append("★ 括号里是**翻转的题数**，不是比例的分母 —— 分母在第二列。")
        out.append("")
        # ★ 用判据说话，不用形容词
        rates = [(p["flipped_any"] / p["common"] * 100.0) if p["common"] else 0.0
                 for p in pairs]
        if len(rates) >= 2:
            spread = max(rates) - min(rates)
            out.append("★★ **两组的「至少翻一格」相差 %.1f 个百分点**（%.1f%% vs %.1f%%）—— "
                       % (spread, rates[0], rates[-1]))
            if spread < 5:
                out.append("  这是**噪声底不随日期漂移**的正面证据。它的用处很具体：")
                out.append("  拿昨天那对当标尺，和拿今天这对当标尺，会得到同一个判断。")
            else:
                out.append("  ⚠️ **差别不小** —— 说明「噪声底」本身有个慢漂移的分量，")
                out.append("  跨日拿旧的底当标尺会低估噪声。")
            out.append("")
        seq = next((r for r in pairs[-1]["rows"] if r[0] == "跨次序列变了"), None)
        hit = next((r for r in pairs[-1]["rows"] if r[0] == "命中@5"), None)
        if seq and hit and hit[1]:
            out.append("★ 最大的噪声源仍然是 `跨次序列变了`（%d/%d = %.1f%%），"
                       % (seq[2], seq[1], 100.0 * seq[2] / seq[1]))
            out.append("  而 `命中@5` 只翻 %d 道 —— **序列在churn，「有没有命中」几乎不变**。"
                       % hit[2])
            out.append("")
        out.append("★★ **检索侧的噪声底只有 `命中@5` / `归因` 那两个数**（本次 %d/%d）。"
                   "轴 1、轴 2 都在动检索，所以它们的效果必须高过这个量级。"
                   % (hit[2] if hit else 0, hit[1] if hit else 0))
        out.append("")

    # ── 11.2 逐轴 ──────────────────────────────────────────────
    for ax in axes:
        out.extend(_render_one_axis(ax, base, noise_runs))

    return out


def _render_one_axis(ax, base, noise_runs):
    """一条轴一节。★★ 所有数字从数据取；所有判据走 eval_ab 的唯一那份实现。"""
    out = []
    differ = eval_ab.config_diff(base["raw"], ax["raw"])[0]
    title = "、".join("`%s` `%s` → `%s`" % (k, eval_ab.fmt(v1), eval_ab.fmt(v2))
                      for k, v1, v2 in differ) or "⚠️ 无配置差异"
    out.append("### 11.2.%s %s" % (ax["ordinal"], title))
    out.append("")

    # 前置检查
    problems = eval_ab.fingerprint_check(base["raw"], ax["raw"])
    usable, bad, ratio, _how = eval_ab.run_usability(ax["raw"])
    ok = (ax["raw"].get("complete") and ax["raw"].get("reconcileOk")
          and usable and not problems)
    if not ok:
        out.append("⛔ **这一轴不成立**：complete=%s reconcileOk=%s "
                   "跑成了=%s（模型链路失败 %d 行 = %.1f%%）指纹问题=%s"
                   % (ax["raw"].get("complete"), ax["raw"].get("reconcileOk"),
                      usable, bad, ratio * 100, problems or "无"))
        if not usable:
            out.append("")
            out.append("★★★ **「对账通过」不等于「这一轮能用」** —— 对账问的是")
            out.append("「行有没有丢」，它问不出「行有没有成」。失败的行由熔断器成簇触发，")
            out.append("所以活下来的那些**不是一个无偏的子样本**，拿它算的每个数都偏。")
        out.append("")
        return out

    ra, rb, common, qa_only, qb_only = eval_ab.common_rows(base["report"], ax["report"])
    out.append("- 共同可比 **%d** 道（基线独有 %d、本轮独有 %d）"
               % (len(common), len(qa_only), len(qb_only)))
    out.append("- 基线 `%s` 与本轮 `%s`：语料/切分/gold 指纹一致 ✅"
               % (base["runId"], ax["runId"]))
    out.append("")

    # ── 延迟（五段）───────────────────────────────────────────
    out.append("#### 延迟（五段，**别当成相加等于 total**）")
    out.append("")
    out.append("| 段 | 基线 n | 基线 p50 | 本轮 n | 本轮 p50 | Δ p50 | 口径 |")
    out.append("|---|---|---|---|---|---|---|")
    seg_spec = [
        ("检索（含重排）", "retrieval_latency_ms", "★ 这里面【含】重排，不是并列项"),
        ("重排", "rerank_latency_ms", "★★ ⊂ 上面那一行"),
        ("生成", "llm_latency_ms", "生成那一跳"),
        ("总计", "total_latency_ms", ""),
        ("未归类", "★未归类_ms", "★ 含意图分类那一次模型往返（它在【任何一段里都没有】）"),
    ]
    for name, path, note in seg_spec:
        na = get_path(base["report"], "延迟." + path)
        nb = get_path(ax["report"], "延迟." + path)
        if not isinstance(na, dict) or not isinstance(nb, dict):
            continue
        pa, pb = na.get("p50"), nb.get("p50")
        if pb is None and pa is not None:
            delta = "**—（本轮没有发生）**"
        elif pa is None and pb is None:
            delta = "—"
        elif pa is None:
            delta = "—（基线没有）"
        else:
            delta = "**%+d ms**" % (pb - pa)
        out.append("| %s | %d | %s | %d | %s | %s | %s |"
                   % (name, na.get("n") or 0, ("%s" % pa) if pa is not None else "—",
                      nb.get("n") or 0, ("%s" % pb) if pb is not None else "—", delta, note))
    out.append("")
    out.append("⚠️ **各段的 n 不同**（被 `status≠1` 截掉的条数不同），"
               "所以这不是同一个总体 —— 逐段的 Δ 只能作方向性参考。")
    out.append("")

    # ── 按 category 的检索侧效果 ──────────────────────────────
    out.append("#### 检索侧效果，按 `category` 切片")
    out.append("")
    out.append("★★ **为什么必须切**：合计指标会把「只影响口语句、完全不动关键词句」"
               "平摊成一个中间数。而重排**预期**的收益恰恰集中在口语句 —— "
               "关键词句靠词面就能召回，语义重排对它没有增量。")
    out.append("")
    cat = eval_ab.slice_categorical(ra, rb, common)
    con = eval_ab.slice_continuous(ra, rb, common)
    att = eval_ab.slice_attribution(ra, rb, common)
    nz = {}
    nz_con = {}
    nz_q_hit, nz_hit_a, nz_hit_b = set(), {}, {}
    if len(noise_runs) >= 2:
        na, nb = noise_runs[-2], noise_runs[-1]
        nra, nrb, ncommon, _, _ = eval_ab.common_rows(na["report"], nb["report"])
        ncommon = [q for q in ncommon if q in set(common)]
        nz = eval_ab.slice_categorical({q: nra[q] for q in ncommon},
                                       {q: nrb[q] for q in ncommon}, ncommon)
        # ★★ 连续量也要尺子。命中率有翻转矩阵当判据，**均值没有** ——
        #    只印一个 Δ 而不印同片噪声的 Δ，读的人会把 0.007 的抖动当结论。
        nz_con = eval_ab.slice_continuous({q: nra[q] for q in ncommon},
                                          {q: nrb[q] for q in ncommon}, ncommon)
        # 总体的尺子：噪声对在共同可比题上的 `命中@5`。
        # ⚠️ 分母同样守三态 —— 只数【两侧都非 None】的题，
        #    否则 29 道非检索题会被当成「没翻」摊进分母，把噪声率压低
        nz_q_hit = {q for q in ncommon
                    if nra[q].get("命中@5") is not None
                    and nrb[q].get("命中@5") is not None}
        nz_hit_a = {q: nra[q]["命中@5"] for q in nz_q_hit}
        nz_hit_b = {q: nrb[q]["命中@5"] for q in nz_q_hit}

    order = sorted(cat, key=lambda c: -cat[c]["命中@5"][0])
    has_noise = bool(nz)
    out.append("| category | n | 基线命中 | 本轮命中 | 基线率 | 本轮率 | Δ率 | 翻转 |"
               + (" 噪声翻转 | 噪声率 | p | 判据 |" if has_noise else ""))
    out.append("|---|---|---|---|---|---|---|---|" + ("---|---|---|---|" if has_noise else ""))
    verdicts = []
    for c in order:
        n, flips = cat[c]["命中@5"]
        if n == 0:
            continue
        qs = [q for q in common if eval_ab.slice_of(ra[q]) == c]
        ha = sum(1 for q in qs if ra[q].get("命中@5") is True)
        hb = sum(1 for q in qs if rb[q].get("命中@5") is True)
        cells = "| %s | %d | %d | %d | %.4f | %.4f | **%+.4f** | %d |" % (
            eval_ab.SLICE_LABEL.get(c, c), n, ha, hb, ha / n, hb / n,
            (hb - ha) / n, len(flips))
        if has_noise:
            nn, nfl = nz.get(c, {}).get("命中@5", (0, []))
            v, p = eval_ab.slice_verdict(len(flips), len(nfl), n, nn)
            nr = eval_ab.noise_rate(len(nfl), nn)
            verdicts.append((c, n, (hb - ha) / n, v, p, nfl))
            cells += " %d | %s | %s | %s |" % (
                len(nfl), ("%.4f" % nr) if nr is not None else "—",
                ("%.4g" % p) if p is not None else "—",
                "— **不可判**" if v is None else ("★★ **超过噪声**" if v else "没超过噪声"))
        out.append(cells)
    out.append("")

    # ── 判据句（纪律①：写判据的函数，不写死数字）──────────────
    sig = [x for x in verdicts if x[3] is True]
    if not has_noise:
        out.append("⚠️ **没有噪声链 ⇒ 没有判据列。** 这一轴看不出「变了」和「抖了」的区别。")
    else:
        # ★★★ 总体判据。**没有它，上面那张表会误导**：
        #     切到 87/26/17 之后每片的 n 都不够，于是三片全「没超过噪声」，
        #     而合计起来是显著的 —— 读的人会得出「这个旋钮没效果」的**错结论**。
        #   这是一个很常见也很危险的形状：**显著的东西被切碎之后不显著**。
        overall_n = sum(cat[c]["命中@5"][0] for c in order)
        overall_f = sum(len(cat[c]["命中@5"][1]) for c in order)
        # ⚠️⚠️ 尺子必须在【同一批题】上取。两个陷阱：
        #   ① 拿某一片的翻转数当总体 —— 分母不是一回事
        #   ② 用噪声对的全部可比题 —— 它和上面 overall_n 的题集可能差几道
        #      （不同的对，两侧有一侧是 None 的题不同）
        #   它们只差一两道，但那一道会直接挪动 p 值在 0.05 附近的位置。
        overall_q = {q for c in order
                     for q in [x for x in common if eval_ab.slice_of(ra[x]) == c]
                     if ra[q].get("命中@5") is not None and rb[q].get("命中@5") is not None}
        nz_same = [q for q in overall_q if q in set(nz_q_hit)]
        floor = eval_ab.noise_rate(
            sum(1 for q in nz_same if nz_hit_a[q] != nz_hit_b[q]), len(nz_same))
        p_all = eval_ab.binom_tail(overall_f, overall_n, floor) if floor else None
        need = None
        if floor:
            need = next((i for i in range(1, overall_n + 1)
                         if eval_ab.binom_tail(i, overall_n, floor) < 0.05), None)
        nz_f_same = sum(1 for q in nz_same if nz_hit_a[q] != nz_hit_b[q])
        out.append("★★★ **总体判据（把三片合起来看）**：`命中@5` 翻转 **%d / %d**，"
                   "噪声链在**两边的交集**上（%d 道）翻 %d 道（率 %.4f），"
                   "**p = %.4g** → %s"
                   % (overall_f, overall_n, len(nz_same), nz_f_same, floor or 0,
                      p_all if p_all is not None else float("nan"),
                      "★★ **超过噪声**" if (p_all is not None and p_all < 0.05)
                      else "没超过噪声"))
        out.append("")
        if overall_n != len(nz_same):
            out.append("★ 两个 n 差 %d 道：那是噪声对上不可比（两侧有一侧是 `—`）的题，"
                       "**不是算错了**。p 值只在交集上算。"
                       % abs(overall_n - len(nz_same)))
            out.append("")
        if need is not None:
            out.append("★ 门槛：在这个噪声率下，合计要**至少翻 %d 道**才到 p<0.05。" % need)
            out.append("")
        if sig:
            for c, n, d, v, p, nfl2 in sig:
                out.append("★★ **`%s` 这一片超过噪声**：命中率 Δ **%+.4f**，"
                           "本片翻转 %d 道，而噪声链在同片上只翻 %d 道（p=%.4g，n=%d）。"
                           % (eval_ab.SLICE_LABEL.get(c, c), d,
                              len(cat[c]["命中@5"][1]), len(nfl2), p, n))
            out.append("")
        elif p_all is not None and p_all < 0.05:
            out.append("★★★ **形状是「总体显著、逐片都不显著」** —— 这不是矛盾：")
            out.append("  切到 87/26/17 之后每一片的 n 都不够，二项检验没有分辨力，")
            out.append("  而合起来看它是显著的。**正确的读法是：这个旋钮确实有效果，")
            out.append("  但效果小到按 `category` 切完就指不出它集中在哪一类。**")
            out.append("  ⚠️ 反过来说：任何「效果集中在某一类」的说法在这一轮**都没有证据**。")
            out.append("")
        else:
            out.append("★ **总体与逐片都没超过噪声** —— 以本轮的样本量和噪声水平，")
            out.append("  这个旋钮的效果**分辨不出来**。⚠️ 这不等于「没有效果」。")
            out.append("")

    # 连续量
    out.append("| category | n | recall@5 Δ | recall@5 噪声 Δ | mrr@5 Δ | mrr@5 噪声 Δ | "
               "上下文条数 | 越界切片数 Δ | 越界噪声 Δ |")
    out.append("|---|---|---|---|---|---|---|---|---|")
    for c in order:
        n, va, vb, d = con[c]["recall@5"]
        if not n:
            continue
        _, ca0, cb0, _ = con[c]["上下文条数"]
        _, oa0, ob0, od = con[c]["越界切片数"]
        nrec = (nz_con.get(c) or {}).get("recall@5", (0, None, None, None))[3]
        nmrr = (nz_con.get(c) or {}).get("mrr@5", (0, None, None, None))[3]
        novr = (nz_con.get(c) or {}).get("越界切片数", (0, None, None, None))[3]
        f = lambda v: ("**%+.4f**" % v) if v is not None else "—"  # noqa: E731
        out.append("| %s | %d | %s | %s | %s | %s | %.2f → %.2f | %s | %s |"
                   % (eval_ab.SLICE_LABEL.get(c, c), n, f(d), f(nrec),
                      f(con[c]["mrr@5"][3]), f(nmrr), ca0, cb0, f(od), f(novr)))
    out.append("")
    out.append("★★ **`Δ` 旁边那一列就是该片的尺子**（噪声链在同一片上的 Δ）。")
    out.append("  命中率有翻转矩阵当判据，**均值没有** —— 只印 Δ 而不印同片噪声的 Δ，")
    out.append("  读的人会把 0.007 的抖动当成结论。")
    out.append("")
    out.append("★ `越界切片数` 是**过度检索**的切片版：进 prompt 的切片里，")
    out.append("  `doc_type` 不在该题标注范围里的条数。它越大 = 塞进越多无关内容。")
    out.append("  截断到更少的条数（`final-top-k` 调小）会**同时**降低召回和过度检索 ——")
    out.append("  这两件事在合计里相互抵消，只有在切片里才分得开。")
    out.append("")

    # 归因桶
    out.append("| category | n | 基线归因 | 本轮归因 |")
    out.append("|---|---|---|---|")
    for c in order:
        a, b2, n = att[c]
        if n == 0:
            continue
        f = lambda cnt: " / ".join("%s %d" % (k, cnt[k])  # noqa: E731
                                   for k in eval_ab.ATTRIBUTION_BUCKETS if cnt.get(k))
        out.append("| %s | %d | %s | %s |"
                   % (eval_ab.SLICE_LABEL.get(c, c), n, f(a) or "—", f(b2) or "—"))
    out.append("")

    # ── 结构性方向：命中集合的包含关系 ────────────────────────
    ct = _containment(ra, rb, common)
    if ct["n"]:
        out.append("#### 结构性方向：命中的正解切片集合怎么变")
        out.append("")
        out.append("| 关系 | 题数 | 占比 |")
        out.append("|---|---|---|")
        out.append("| B ⊇ A（**只增不减**） | %d | %.1f%% |"
                   % (len(ct["sup"]), 100.0 * len(ct["sup"]) / ct["n"]))
        out.append("| B ⊆ A（**只减不增**） | %d | %.1f%% |"
                   % (len(ct["sub"]), 100.0 * len(ct["sub"]) / ct["n"]))
        out.append("| B = A（一点没动） | %d | %.1f%% |"
                   % (len(ct["eq"]), 100.0 * len(ct["eq"]) / ct["n"]))
        out.append("| **丢了命中的题**（A 有、B 没有） | %d | %.1f%% |"
                   % (len(ct["lost"]), 100.0 * len(ct["lost"]) / ct["n"]))
        out.append("")
        if ct["lost"]:
            out.append("丢了的题号：%s" % "、".join(ct["lost"][:20]))
            out.append("")
        out.append("★★ 用法：**先看原理允许哪个方向，再看这一轮是不是那个方向。**")
        out.append("")
        out.append("- `final-top-k` 调**大** ⇒ contexts 是超集 ⇒ 期望 `B ⊇ A` 占压倒多数")
        out.append("  （`context_recall` / `faithfulness` 因此**结构性不可能下降**）")
        out.append("- `rerank.max-candidates` 调**小** ⇒ 重排候选是子集 ⇒ 期望 `B ⊆ A`")
        out.append("  （召回因此**结构性不可能上升**）")
        out.append("- `rerank.enabled` 开关 ⇒ 两条完全不同的排序 ⇒ **两个方向都不该压倒**")
        out.append("")
        out.append("⚠️ 但这个字段是 `report.json` 里的**字符串**（形如 `[5, 25]/[5, 25]`），")
        out.append("  解析失败时整段静默跳过 —— 所以 n 必须印出来。")
        out.append("")

    # 成本
    ca = get_path(base["report"], "成本")
    cb = get_path(ax["report"], "成本")
    if isinstance(ca, dict) and isinstance(cb, dict):
        out.append("**成本**（服务端账本，不含意图分类与摘要压缩）")
        out.append("")
        out.append("| 项 | 基线 | 本轮 | Δ |")
        out.append("|---|---|---|---|")
        for k in sorted(set(ca) & set(cb)):
            va, vb = ca[k], cb[k]
            if not isinstance(va, (int, float)) or isinstance(va, bool) \
                    or not isinstance(vb, (int, float)) or isinstance(vb, bool):
                continue
            out.append("| %s | %s | %s | **%+g** |" % (k, va, vb, vb - va))
        out.append("")
        # ── 缓存命中率（反推）──────────────────────────────────
        ha, ra_, m_a, e_a = _cache_hit_estimate(base["report"], base["raw"])
        hb, rb_, m_b, e_b = _cache_hit_estimate(ax["report"], ax["raw"])
        if ha is not None and hb is not None:
            out.append("★★ **缓存命中率（反推）**：`prompt_cache_hit_tokens` "
                       "**参与计费但不落库**，所以只能从上面那两行 + 三档定价算回来：")
            out.append("")
            out.append("| 轮 | 模型 | 反推命中的输入 tok | 输入 tok | 命中率 |")
            out.append("|---|---|---|---|---|")
            for r, h, rr, m in ((base, ha, ra_, m_a), (ax, hb, rb_, m_b)):
                pt = get_path(r["report"], "成本.prompt_token合计")
                out.append("| `%s` | `%s` | %.0f | %s | **%.1f%%** |"
                           % (r["runId"], m, h, pt, rr * 100))
            out.append("")
            out.append("| | 命中率 Δ | 元/行 基线 | 元/行 本轮 |")
            out.append("|---|---|---|---|")
            ra_cost = ca.get("数据库里的成本合计")
            rb_cost = cb.get("数据库里的成本合计")
            na_, nb_ = ca.get("有成本的行"), cb.get("有成本的行")
            out.append("| | **%+.1f 个百分点** | %.6f | %.6f |"
                       % ((rb_ - ra_) * 100,
                          ra_cost / na_ if na_ else 0, rb_cost / nb_ if nb_ else 0))
            out.append("")
            out.append("⚠️ 反推**假定整轮只用一个模型**（用 `chat-chain[0]` 的定价）。")
            out.append("  有降级行时它不成立 —— 要连同 provider/model 切片一起读。")
        else:
            out.append("（缓存命中率反推不出来：基线 %s；本轮 %s）"
                       % (e_a or "—", e_b or "—"))
        out.append("")
    return out


def render_appendix(runs):
    a, b = runs["noise"], runs["main"]
    ra, rb = a["report"], b["report"]
    ma, mb = a["rows"], b["rows"]
    rt = runs["main"]["report"].get("逐题") or {}
    out = []

    out.append("两轮：`%s`（噪声底轮） / `%s`（主体轮）。" % (a["runId"], b["runId"]))
    out.append("")
    out.append("★ 主体表只印**主体轮**的值，**每行末尾的 `⚠ n` 是这一行两轮不一致的格数**")
    out.append("  （`✅` = 一格都没变）。两轮的具体差异在**表 B**，一格一行 —— "
               "这样表 A 不被噪声淹没，表 B 又能精确核对。")
    out.append("")
    out.append("★★ **`总耗时ms` 不计入那个数字**。它在 %s/%s 道题上都变（§2.8 量过），"
               "算进来的后果是**每一行都至少 `⚠ 1`**，这一列就再也不区分任何东西了。"
               % (num(_always_moves(ma, mb)), num(len(mb))))
    out.append("")
    out.append("★ 三态：`—` 是**不可判**（不是 `false`）。"
               "`意图正确` 为 `—` = gold 不是合法分类目标；"
               "`命中@5` 为 `—` = 这道题不进检索分母。")
    out.append("")
    out.append("★ `进检索分母` / `三次status` / `取成功次` / `闸门` 是**解读其余各格的前提** —— "
               "不看它们就不知道一格 `—` 是「没检索」还是「检索了没命中」。")
    out.append("")

    # ---- 表 A ----
    out.append("## 表 A：主体轮逐题")
    out.append("")
    rows = []
    for q in sorted(mb):
        r = mb[q]
        cells = []
        for _lbl, key in APPENDIX_COLS:
            v = r.get(key)
            if isinstance(v, bool):
                cells.append("T" if v else "F")
            elif v is None:
                cells.append("—")
            elif isinstance(v, list):
                cells.append(md_text(",".join(str(x) for x in v)) or "—")
            elif isinstance(v, float):
                cells.append("%.6g" % v)
            else:
                cells.append(md_text(v))
        # 这一行两轮不一致的格数
        # ★★ `总耗时ms` 【不计入】。它在 153/153 道题上都变（主报告 §2.8 已经量过），
        #    算进来的后果是**每一行都至少 ⚠ 1**，于是这一列不再区分任何东西 ——
        #    和纪律 ③ 要避免的是同一个毛病，只是发生在行一级。
        bad = 0
        if q in ma:
            for _lbl, key in APPENDIX_COLS:
                if key in ("题号", "总耗时ms"):
                    continue
                x, y = ma[q].get(key), r.get(key)
                if isinstance(x, float) and isinstance(y, float):
                    if abs(x - y) > 1e-12:
                        bad += 1
                elif x != y:
                    bad += 1
        else:
            bad = -1
        cells.append("不在噪声底轮" if bad < 0 else ("⚠ %d" % bad if bad else "✅"))
        rows.append(cells)
    out.extend(table([c[0] for c in APPENDIX_COLS] + ["两轮"], rows))
    out.append("")
    out.append("- `/api/debug/eval/report?runId=%s` 的 `逐题.行` 是这张表的机器可读版" % b["runId"])
    out.append("- ★ **表 A 的汇总必须逐字等于主报告各节的数字** —— "
               "这一点由 `EvalReportServiceTest` 在 Java 侧重算断言，不是靠人工核对")
    out.append("")

    # ---- 表 B ----
    out.append("## 表 B：两轮差异清单")
    out.append("")
    _ra, _rb, common, only_a, only_b = eval_ab.common_rows(ra, rb)
    if only_a or only_b:
        out.append("⚠️ 只在一边的题——%s / %s" % (_codes(only_a), _codes(only_b)))
        out.append("")
    flip_rows, details = eval_ab.flip_table(_ra, _rb, common)
    cont_rows, cont_details = eval_ab.continuous_table(_ra, _rb, common)
    if not any(f for _m, _c, f in flip_rows) and not any(mv for _m, _c, _d, mv in cont_rows):
        out.append("**两轮逐题完全一致**（这在真实运行里不会发生）。")
        return out
    for m, _c, f in flip_rows:
        if not f:
            continue
        out.append("### `%s`（%s 道）" % (m, f))
        out.append("")
        out.extend(table(["题号", "噪声底轮", "%s（主体轮）" % m, "标注叶子", "分类众数", "三次status"],
                         [[q, _fmt_cell(x), _fmt_cell(y),
                           md_text(mb[q].get("标注叶子")), md_text(mb[q].get("分类众数")),
                           md_text(str(mb[q].get("三次status")))]
                          for q, x, y in details[m]]))
        out.append("")
    out.append("### 连续量的差异（只列真的动了的）")
    out.append("")
    deep = []
    for m, _c, _d, mv in cont_rows:
        for q, d in cont_details[m][:40]:
            deep.append([m, q, "%.6g" % (_num(ma, q, m)), "%.6g" % (_num(mb, q, m)), "%+.4g" % d])
    out.extend(table(["判据", "题号", "噪声底轮", "主体轮", "Δ"], deep))
    out.append("")
    if cont_rows:
        moved = dict((m, mv) for m, _c, _d, mv in cont_rows)
        out.append("★ `总耗时ms` 在 **%s/%s** 道上都动了 —— 延迟**每一格都变**，"
                   "所以它在逐题层面**没有判别力**（主报告 §2.8 也是这个结论）。"
                   % (num(moved.get("总耗时ms")),
                      num(sum(1 for q in common if mb[q].get("总耗时ms") is not None
                              and ma.get(q, {}).get("总耗时ms") is not None))))
        out.append("")
    out.append("★ `跨次序列变了` 只是**顺序**变了，`命中@5` 才是**命中集合**变了 —— "
               "两者在表 B 里的位置不同，读的时候别混。")
    out.append("")
    out.append("要点回主报告：`docs/11-评测报告.md`")
    return out


def _num(rows, q, key):
    v = rows.get(q, {}).get(key)
    return float(v) if v is not None else 0.0


def _always_moves(ma, mb):
    """两轮里 `总耗时ms` 真的变了的题数 —— 上面那句注释里的数字必须自己算出来。"""
    n = 0
    for q, r in mb.items():
        x, y = ma.get(q, {}).get("总耗时ms"), r.get("总耗时ms")
        if x is not None and y is not None and abs(float(x) - float(y)) > 1e-12:
            n += 1
    return n


def _fmt_cell(v):
    if isinstance(v, bool):
        return "T" if v else "F"
    if v is None:
        return "—"
    return md_text(v)


# ================================================================
# 一页纸
# ================================================================

def render_onepager(runs, rag):
    main, noise = runs["main"], runs["noise"]
    rb, ra = main["report"], noise["report"]
    out = []
    rate = _flip_rate(runs)
    out.append("- **同配置两轮，至少翻一格的题：%s** —— 这是本页每一个数字的标尺" % rat(rate))
    row_all = get_path(rb, "意图.逐题取众数") or {}
    row_cmp = get_path(rb, "意图.★★可比口径的准确率") or {}
    if row_all.get("n") and row_cmp.get("n"):
        out.append("- **意图准确率**：%s/%s = **%s**（可比口径 %s/%s = **%s**）—— "
                   "两个数差 %s 个百分点，差的是 %s 道「gold 不可达」的工具题（§2.1.1）"
                   % (num(row_all.get("命中")), num(row_all.get("n")), rat(row_all.get("值")),
                      num(row_cmp.get("命中")), num(row_cmp.get("n")), rat(row_cmp.get("值")),
                      num(round(((row_cmp.get("值") or 0) - (row_all.get("值") or 0)) * 100, 2)),
                      num(get_path(rb, "意图.★★gold不是合法分类目标的题.n"))))
    for m, label in [("HitRate@5", "HitRate@5"), ("Recall@5", "Recall@5"), ("MRR@5", "MRR@5")]:
        node = get_path(rb, "检索.%s" % m) or {}
        if node.get("命中") is not None:
            out.append("- **检索 %s**：%s/%s = **%s**" % (label, num(node.get("命中")), num(node.get("n")), rat(node.get("值"))))
        else:
            out.append("- **检索 %s**：**%s** (n=%s)" % (label, rat(node.get("值")), num(node.get("n"))))
    out.append("- **过度检索**：对标注叶子 %s，对分类叶子 %s —— 两者之差就是「分类歪一次多塞进来的切片」"
               % (rat(get_path(rb, "过度检索.★对标注叶子.值")), rat(get_path(rb, "过度检索.★对分类叶子.值"))))
    if rag is not None:
        by = ragas_rows_with_scores(rag)
        parts = []
        for m in RAGAS_METRICS:
            v = list(by[m].values())
            lo, hi = bootstrap_ci(v)
            parts.append("%s **%s** [%s, %s]" % (m, rat(mean_of(v)), rat(lo), rat(hi)))
        out.append("- **RAGAS（n=%s，挂 `%s`）**：%s" % (num((rag.get("样本") or {}).get("题数")),
                                                      num(rag.get("runId")), "；".join(parts)))
    out.append("- **延迟 p50**：total %s ms，其中检索 %s、重排 %s、生成 %s、**未归类 %s**"
               % (num(get_path(rb, "延迟.total_latency_ms.p50")),
                  num(get_path(rb, "延迟.retrieval_latency_ms.p50")),
                  num(get_path(rb, "延迟.rerank_latency_ms.p50")),
                  num(get_path(rb, "延迟.llm_latency_ms.p50")),
                  num(get_path(rb, "延迟.★未归类_ms.p50"))))
    present = [r for r in runs.values() if r]
    out.append("- **成本**：这一期 %d 轮合计 **%s 元**（服务端账本）；"
               "**不含**意图分类、摘要压缩、RAGAS 判官那三笔"
               % (len(present), num(round(sum((r["raw"].get("clientCost") or 0) for r in present), 4))))
    return out


# ================================================================
# selftest
# ================================================================

def undefined_global_names(src, filename="<报告生成器>"):
    """★★ 本文件里**被引用、却从未定义**的全局名 —— 一类静态可查、却没人查的错。

    ★★★ 为什么需要它（9.6a 真撞到的）：`render_metrics` 里写了 `_RD_CELLS`，
    而常量叫 `RD_CELLS` ⇒ 一渲染到 §2.10 就 `NameError`。
    **而 `--selftest` 35 项全绿** —— 因为它按设计「不读数据」，
    「这份脚本到底能不能渲染」从来不在它里面。
    名字打错在**导入期查不出来**（Python 只在执行到那一行时才解析全局名），
    所以它一路躲过了 `py_compile`、`--selftest`，直到真跑一次渲染。

    ★ 用 `symtable` 而不是正则：正则要自己复现 Python 的作用域规则
    （内建、局部、推导式变量、`except as` 名字、`global`/`nonlocal`……），
    而**拼错的方向恰好是漏报** —— 一个只报喜的检查比没有检查更糟。
    `symtable` 是编译器自己用的那张表。

    ⚠️ 跳过 `__x__`：`__file__` / `__name__` 由解释器提供，**不在**模块符号表里，
    不排掉它们会得到一条恒红的断言（而那会让人把整个检查关掉）。
    """
    import builtins
    import symtable

    table = symtable.symtable(src, filename, "exec")
    # ⚠️⚠️ 【不能】用 `table.get_identifiers()` 当「定义过的名字」——
    #   它返回的是「这张表里出现过的**所有**名字」，**包括那个打错的**。
    #   ⇒ 拿它当白名单，判据会被自己盖住，检查恒真。
    #   ★ 实测过：第一版就是这个写法，`X = _FOO` 返回 []（而 `def g(): return _BAR` 能报）。
    #   判据只能是符号自己的 flag：「在这张表里被绑定过」（赋值过 / import 过）。
    defined = {s.get_name() for s in table.get_symbols()
               if s.is_assigned() or s.is_imported()}
    bad = set()

    def walk(t):
        for sym in t.get_symbols():
            name = sym.get_name()
            if name.startswith("__") and name.endswith("__"):
                continue
            if sym.is_referenced() and sym.is_global() and not sym.is_assigned():
                if name not in defined and not hasattr(builtins, name):
                    bad.add(name)
        for child in t.get_children():
            walk(child)

    walk(table)
    return sorted(bad)


def selftest():
    ok = 0
    bad = []

    def check(name, cond):
        nonlocal ok
        if cond:
            ok += 1
        else:
            bad.append(name)

    # ① 两轮一致 / 不一致 的判据
    X = {"n": 10, "命中": 5, "值": 0.5}
    check("① 完全相同 → 打勾", pair_ratio(dict(X), dict(X))[1])
    check("① 分子不同 → 标差异", not pair_ratio(dict(X), {"n": 10, "命中": 6, "值": 0.6})[1])
    # ★★ 最要紧的一条：分子分母同时变、比值不变 —— 只比 `值` 会漏掉
    check("① 比值相同但分母不同 → 必须标差异",
          not pair_ratio({"n": 10, "命中": 5, "值": 0.5},
                         {"n": 20, "命中": 10, "值": 0.5})[1])
    check("① 只比 `值` 的那个版本【确实会漏】—— 反对照",
          {"n": 10, "命中": 5, "值": 0.5}.get("值") == {"n": 20, "命中": 10, "值": 0.5}.get("值"))

    # ② 三态
    check("② 一边有值一边 None → 不能打勾", not pair_ratio({"n": 1, "命中": 0, "值": 0.0}, None)[1])
    check("② None 不当成 False", num(None) == DASH)
    check("② False 渲染成 false 而不是 —", num(False) == "false")
    check("② false 与 — 在报告里可区分", num(False) != num(None))

    # ③ bootstrap 确定性
    vals = [0.1, 0.4, 0.6, 0.9, 1.0, 0.2, 0.3, 0.7, 0.5, 0.8]
    a1 = bootstrap_ci(vals)
    a2 = bootstrap_ci(list(reversed(vals)))     # ★ 顺序不该影响：它是抽样，不是前缀
    check("③ 同输入两次 → 同一个区间", a1 == bootstrap_ci(vals))
    check("③ 均值落在区间里", a1[0] <= mean_of(vals) <= a1[1])
    # ★ 反对照：不同的种子【必须】给出不同的区间，否则「种子固定」是句空话
    check("③ 不同种子 → 不同区间（反对照）",
          bootstrap_ci(vals, seed=1) != bootstrap_ci(vals, seed=2))
    check("③ n=0 → (None, None)", bootstrap_ci([]) == (None, None))

    # ④ 切片纪律
    #
    # ★★ 这一条原来写的是 `MIN_SLICE_N == 5` —— 一个【假守卫】：
    #    它断言的是一个字面量，而上面第 90 行的注释声称它断言的是
    #    「== report.json 里的 ★切片可信度纪律.MIN_SLICE_N」。
    #    ⇒ 把 Java 侧的 5 改成 6，这条照样绿，而报告会给 n=5 的切片打上「可信」。
    #    ★ 现在真的去读 Java 源码里那个常量 —— 读不到就【报错】，
    #      不是静默通过（读不到时放行等于把守卫换成了装饰品）。
    java_min, java_err = _java_min_slice_n()
    check("④ MIN_SLICE_N 与 Java 侧同值（真读源码，Java=%s）" % java_min,
          java_err is None and java_min == MIN_SLICE_N)
    # ★ 反对照：证明这个读法【真的在读】—— 给它一个不存在的路径必须失败，
    #   否则「读到了」可能只是函数恒返回一个默认值。
    check("④ 反对照：源码路径不存在时必须报错，而不是回落成 5",
          _java_min_slice_n(path="/nonexistent/EvalReportService.java")[1] is not None)

    # ⑤ 路径取值
    probe = {"a": {"b": {"n": 1, "值": 0.5}}}
    check("⑤ get_path 取得到", get_path(probe, "a.b.值") == 0.5)
    check("⑤ get_path 取不到返回 None（不抛）", get_path(probe, "a.zzz.值") is None)
    check("⑤ 中途是标量也不抛", get_path(probe, "a.b.值.更深") is None)

    # ⑥ 表格渲染不炸
    t = table(["a", "b"], [[1, None], ["x|y", True]])
    check("⑥ 表头 + 分隔行 + 数据行", len(t) == 4 and t[1] == "|---|---|")
    check("⑥ 列数守恒（None 不吞列）", t[2].count("|") == 3)
    check("⑥ None 渲染成空单元格而不是 `None`", "None" not in t[2])

    # ⑦ eval_ab 的复用确实接上了
    check("⑦ eval_ab.flip_table 可调用",
          callable(getattr(eval_ab, "flip_table", None)))
    check("⑦ eval_ab.common_rows 可调用",
          callable(getattr(eval_ab, "common_rows", None)))

    # ⑧ T8：噪声链的「相隔」必须按【日历日】判，不能按小时数
    # ★★ 反对照：22:22 跑完、次日 21:29 再跑，相差 23.1 小时（**不到一天**）。
    #    按小时数分段会说「同日」，而它恰恰是那组跨日对照。
    cross = _gap_label({"finishedAt": "2026-09-22T22:22:35+08:00"},
                       {"startedAt": "2026-09-23T21:29:00+08:00"})
    same = _gap_label({"finishedAt": "2026-09-23T21:50:00+08:00"},
                      {"startedAt": "2026-09-23T22:05:00+08:00"})
    check("⑧ 隔夜那对判成「跨日」（23.1 小时，但跨了日历日）",
          "跨日" in cross)
    check("⑧ 同日那对【不】判成跨日（反对照）",
          "跨日" not in same and "同会话" in same)

    # ⑨ T8：切片判据的三态在报告侧也成立
    # ★ 没有噪声链时判据必须印成「不可判」，不是「没超过噪声」——
    #   后者是一个结论，而当时根本没测过噪声
    check("⑨ 没噪声对照 → eval_ab.slice_verdict 返回 (None, None)，不是 (False, …)",
          eval_ab.slice_verdict(9, 0, 88, 0) == (None, None))

    # ⑩ T8：`命中的正解切片` 是【字符串】，不是数组
    # ★★ 反对照：直接拿它当集合用会得到一个逐字符的垃圾集（`'[5, 25]'` → 6 个字符），
    #    而且**不报错** —— 包含关系会变成一句看起来有数的胡话
    ha, hall = _parse_hits("[5, 25]/[5, 25]")
    check("⑩ `[5, 25]/[5, 25]` → ({5,25}, {5,25})", ha == {5, 25} and hall == {5, 25})
    check("⑩ 单元素 + 不等长也能解析", _parse_hits("[6]/[5, 25]") == ({6}, {5, 25}))
    check("⑩ None 进来 → (None, None)，不抛",
          _parse_hits(None) == (None, None))
    check("⑩ 反对照：裸字符串当集合用会得到 6 个字符（所以必须先解析）",
          len(set("[5, 25]")) == 6 and len(set("[5, 25]")) != len({5, 25}))

    # ── ⑪ 缓存命中反推 + ★★★ 退化输入的守卫 ────────────────────
    # ★ 定价故意取整，好让期望值一眼能验算：
    #   pt=1000, ct=100, 命中 500 → cost = (500×1.0 + 500×0.1 + 100×2.0)/1e6 = 0.00075
    _cfg = {"config": {"properties": {
        "xbla.llm.chat-chain[0]": "t-model",
        "xbla.llm.models.t-model.pricing.input": 1.0,
        "xbla.llm.models.t-model.pricing.cache-hit-input": 0.1,
        "xbla.llm.models.t-model.pricing.output": 2.0,
    }}}

    def _rep(pt, ct, cost):
        return {"成本": {"数据库里的成本合计": cost,
                         "prompt_token合计": pt, "输出token合计": ct}}

    # ⚠️ 这里【不能】写 `h == 500` —— 全 float 运算给的是 500.00000000000006。
    #    第一版就是这么红的。凡是从浮点算出来的数，判据必须带容差。
    h, rate, model, err = _cache_hit_estimate(_rep(1000, 100, 0.00075), _cfg)
    check("⑪ 正：反推出命中 500、命中率 50%%",
          h is not None and abs(h - 500) < 1e-6 and abs(rate - 0.5) < 1e-9 and err is None)

    # ★★★ 这条是 T8 真撞到的：Java 侧列清单漏了 prompt_tokens / completion_tokens，
    #     于是两列都读成 0 —— 而公式会解出一个【负数】，原来那句报错说
    #     「多半是这一轮有降级行」，把读者指向了完全错误的方向（实测一行降级都没有）。
    dh, drate, _, derr = _cache_hit_estimate(_rep(0, 0, 0.00075), _cfg)
    check("⑪ ★ 退化输入（prompt=0）→ 拒绝出数，不印一个凑出来的百分比",
          dh is None and drate is None and derr)
    # ⚠️⚠️ 判据【只能看正向】。第一版写的是 `"降级行" not in derr` —— 而那条消息
    #     正文里就写着「这不是「有降级行」」，子串匹配把它判成命中了。
    #     ★ 这是子串匹配在本项目第四次咬人（前三次：`key` 撞 `keyword-weight`、
    #       `application` 撞 `spring.application.json`、裸字符串当集合用）。
    #     **要问的是「它有没有指向正确的去处」，不是「它有没有出现某个词」。**
    check("⑪ ★★ 理由要点名【读路径】—— 读者能据此去修",
          "selectByEvalRun" in derr and "prompt_tokens" in derr)
    # ★ 反对照：守卫不能把健康输入也拒掉 —— 否则「拒绝出数」本身变成一句恒真的话，
    #   报告从此永远不印命中率，而没有任何迹象。
    h2, _, _, err2 = _cache_hit_estimate(_rep(1000, 100, 0.00075), _cfg)
    check("⑪ 反对照：守卫不误伤健康输入",
          h2 is not None and abs(h2 - 500) < 1e-6 and err2 is None)

    # 超出范围（真·成因未定）时：拒绝出数，且【只陈述观察】
    oh, _, _, oerr = _cache_hit_estimate(_rep(1000, 100, 5.0), _cfg)
    check("⑪ 解出的命中数越界 → 拒绝出数", oh is None and oerr)
    check("⑪ ★ 越界时只陈述观察（正向判据：说了「不下结论」）",
          "不下结论" in oerr)

    # ⑫ ★★ 名字打错 —— 静态可查，而**这一项之前不存在**
    #
    # ★★★ 9.6a 实测：`render_metrics` 里写了 `_RD_CELLS`（常量叫 `RD_CELLS`）⇒
    #   一渲染到 §2.10 就 NameError，而**35 项全绿**。
    #   ⇒ 判据必须是「**这份脚本能不能渲染**」，而不是「它的口径对不对」。
    #   ⚠️ 这一项只覆盖「名字」，覆盖不了「这一行有没有被执行到」——
    #     所以**改完 eval_report.py 必须真跑一次渲染**（写到临时路径即可）。
    with io.open(__file__, encoding="utf-8") as _f:
        _own = _f.read()
    _undef = undefined_global_names(_own)
    check("⑫ 本文件里没有「被引用但从未定义」的全局名（%s）"
          % (", ".join(_undef) if _undef else "无"), not _undef)
    # ★ 反对照：守卫必须真的会咬 —— 不喂一个打错的名字，上面那条可能恒真
    check("⑫ 反对照：故意打错的名字必须被数出来",
          undefined_global_names(_own + "\nX = _NOT_A_REAL_NAME\n")
          == ["_NOT_A_REAL_NAME"])
    # ★ 反对照：解释器提供的名字不能被误报
    check("⑫ 反对照：`__file__` 这类不算未定义",
          "_file__" not in "".join(undefined_global_names(_own)))

    print("selftest: %d 项通过, %d 项失败" % (ok, len(bad)))
    for b in bad:
        print("  ✗ %s" % b)
    return 0 if not bad else 1


# ================================================================
# main
# ================================================================

def build(runs, rag, negctl, t8=None):
    out = []
    out.append("# 阶段 7 · 评测报告")
    out.append("")
    out.append("> 由 `scripts/eval_report.py` 生成。**所有数字都可从 `eval_results/` 下的原始文件重算。**")
    out.append("> ★ 改这个文件没有意义 —— 下次生成会覆盖它。要改结论，改脚本里对应的判据。")
    out.append("")

    out.append("## 0 一页纸")
    out.append("")
    out.extend(render_onepager(runs, rag))
    out.append("")
    out.append("★★ **读下面任何一节之前先读 §1** —— 它给出「一个数字能有多稳」的标尺。")
    out.append("")

    out.append("## 报告头：这一期测的是什么")
    out.append("")
    out.extend(render_header(runs))
    out.append("")

    out.append("## 1 ★ 噪声底（同配置两轮）")
    out.append("")
    out.extend(render_noise_floor(runs, rag))
    out.append("")

    out.append("## 2 主指标表（两轮并排）")
    out.append("")
    out.extend(render_metrics(runs))
    out.append("")

    out.append("## 3 切片（带 n）")
    out.append("")
    out.extend(render_slices(runs))
    out.append("")

    out.append("## 4 RAGAS（答案质量）")
    out.append("")
    out.extend(render_ragas(runs, rag, negctl))
    out.append("")

    out.append("## 5 对比基线：阶段 4 的 20 题")
    out.append("")
    out.extend(render_baseline(runs, rag))
    out.append("")

    out.append("## 6 多轮集（单独分母）")
    out.append("")
    out.extend(render_multi(runs))
    out.append("")

    out.append("## 7 方法验证：这个工具不是瞎的")
    out.append("")
    out.extend(render_method_check(runs))
    out.append("")

    out.append("## 8 红绿线：哪些差异是信号")
    out.append("")
    out.extend(render_redgreen(runs))
    out.append("")

    out.append("## 9 已知局限")
    out.append("")
    out.extend(render_limits(runs, rag))
    out.append("")

    out.append("## 10 未被解释的现象")
    out.append("")
    out.extend(render_unexplained(runs, rag))
    out.append("")

    out.append("## 11 迭代实验：动了什么，然后去了哪")
    out.append("")
    out.extend(render_iterations(t8))
    out.append("")

    out.append("---")
    out.append("")
    out.append("- 逐题全量：`docs/11-附录-逐题.md`")
    out.append("- 指标算法：`EvalReportService`（纯函数）+ `EvalReportServiceTest`（单测）"
               "+ `scripts/eval_report_check.py`（Python 独立实现对拍）")
    out.append("- A/B 框架：`scripts/eval_ab.py`（含 `--selftest`）")
    out.append("- 评测设计：`docs/06-评测体系设计.md`")
    return out


# ★ 明知而【不】收进报告的路径，每一条都要写清为什么。
#   它是一份【受审的清单】，不是一句「其余都忽略」——
#   `EvalReportService` 加了新键时，新键不在这个表里，coverage 就会喊。
#   ★★ 这正是这个检查存在的唯一理由：**新指标不许静默地不进报告**。
OMITTED_PATHS = {
    "★切片可信度纪律.MIN_SLICE_N":
        "它是【渲染规则】本身（n<5 标不可信），不是一项指标 —— 正文里以规则的形式出现",
    "★切片可信度纪律.说明":
        "同一句话的机器可读版，正文里用人话说了",
    "兜底.明细":
        "误判题号的列表，非空时由 `★被判成业务意图的题` 那一行渲染",
    "数据完整性.questionSet":
        "报告头那张「这一期的四轮」表里有",
    "意图.无唯一众数的题（诊断）.说明":
        "那句「别盯着它」，正文里用同样的话说了",
    "意图.无唯一众数的题（诊断）.题号":
        "这一栏**恒为空**（两轮各 8 次实测都是 0 道）—— 报一张永远空的表只会分散注意力。"
        "★ 多轮集里它非空，那个数在 §6 单列",
    "意图.★★gold不是合法分类目标的题.题号":
        "§2.1.1 只报数量与叶子码 —— 逐题在附录表 A 里查得到",
}


def coverage(runs):
    """★ 自检：report.json 里的哪些叶子路径**报告从来没访问过**。

    ⚠️ 它【不报错】。因为「报告该收哪些指标」是一个人的取舍，不是数据决定的。
      它的用途是：`EvalReportService` 加了新键时，这里会提示「你还没决定要不要写它」。
      ★★ 没有这个检查，新指标会**静默地不进报告** —— 而报告看起来一切正常。
    """
    rb = runs["main"]["report"]
    paths = section_paths(rb)
    missing = []
    for p in sorted(paths):
        if p in OMITTED_PATHS:
            continue
        # ★ 访问的是父节点（如 `意图.逐行`），叶子是 `意图.逐行.值` —— 前缀算覆盖
        if any(p == a or p.startswith(a + ".") for a in _ACCESSED):
            continue
        missing.append(p)
    return missing


def main(argv=None):
    ap = argparse.ArgumentParser(description="阶段 7 评测报告生成器")
    for role in DEFAULT_ROLES:
        ap.add_argument("--" + role.replace("_", "-"), dest=role, default=DEFAULT_ROLES[role],
                        help="%s 轮的 runId 或目录（默认 %s）" % (role, DEFAULT_ROLES[role]))
    ap.add_argument("--t8-noise", default=DEFAULT_T8_NOISE,
                    help="★ 噪声【链】：同配置 N 轮的 runId，逗号分隔。"
                         "相邻两对各给一个噪声底样本（NN-1 个）。"
                         "链尾轮自动当三条轴的基线 —— 它离三条轴最近")
    ap.add_argument("--t8-axes", default=DEFAULT_T8_AXES,
                    help="★ 三条轴的 runId，逗号分隔。每条轴与链尾的配置 diff "
                         "就是它的标题（**不写死轴名** —— 标题从数据长出来）")
    ap.add_argument("--ragas", default=DEFAULT_RAGAS, help="RAGAS 结果 JSON（相对 eval_results/ 或绝对路径）")
    ap.add_argument("--ragas-negctl", default=DEFAULT_RAGAS_NEG, help="RAGAS 负对照 JSON")
    ap.add_argument("--out", default=os.path.join(ROOT, "docs", "11-评测报告.md"))
    ap.add_argument("--appendix-out", default=os.path.join(ROOT, "docs", "11-附录-逐题.md"))
    ap.add_argument("--base", default=eval_ab.DEFAULT_BASE)
    ap.add_argument("--refresh", action="store_true",
                    help="从端点重新生成 report.json（★ 只读：不写 docs/11-*）")
    ap.add_argument("--selftest", action="store_true", help="只跑口径自检，不读数据")
    ap.add_argument("--coverage", action="store_true", help="打印未被报告收录的指标路径")
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()

    runs = {}
    for role, spec in DEFAULT_ROLES.items():
        spec = getattr(args, role)
        if spec in ("", "-", "none"):
            runs[role] = None
            continue
        runs[role] = load_run(spec, args.refresh, args.base)
    if runs.get("main") is None or runs.get("noise") is None:
        raise SystemExit("★ --main 与 --noise 都是必需的 —— 没有噪声底，报告里的数字就没有标尺")

    rag = load_ragas(args.ragas, "主") if args.ragas else None
    negctl = load_ragas(args.ragas_negctl, "负对照") if args.ragas_negctl else None

    # ── T8：噪声链 + 三条轴 ────────────────────────────────────
    # ★ 某一轮还没跑 / 目录不在时**不当成错误** —— 整节退化成一句「这一期没有」，
    #   而不是让整个报告生成失败。报告的主体（§0~§10）不该被 T8 拖死。
    t8 = None
    t8_noise_specs = [s.strip() for s in (args.t8_noise or "").split(",") if s.strip()]
    t8_axis_specs = [s.strip() for s in (args.t8_axes or "").split(",") if s.strip()]
    if t8_noise_specs and t8_axis_specs:
        try:
            noise_runs = [load_run(s, args.refresh, args.base) for s in t8_noise_specs]
            axes = [load_run(s, args.refresh, args.base) for s in t8_axis_specs]
            for i, ax in enumerate(axes, 1):
                ax["ordinal"] = str(i)
                ax["label"] = "轴 %d" % i
            t8 = {"noise_runs": noise_runs, "axes": axes}
        except SystemExit as e:
            # ★★★ 把【真实原因】带进报告，不要换成一个猜测。
            #   原来这里写 `t8 = None`，§11 就渲染成「这一期没有迭代实验」——
            #   那是一句关于【世界】的断言，而我们实际知道的只有
            #   「报告生成器读不到这几个目录」。两者都可能为真，但**不是同一件事**。
            #   实测代价：`DEFAULT_T8_AXES` 里一个 runId 写错（指向了已删的废轮），
            #   `--refresh` 就把 §11 整节静默换成了那句话，而 stderr 那行被 `| tail` 吃掉了。
            print("⚠️ T8 那几轮读不到，§11 退化成一句话：%s" % e)
            t8 = {"unreadable": str(e)}

    lines = build(runs, rag, negctl, t8)
    text = "\n".join(lines) + "\n"

    # ★★ `--coverage` 是【只读诊断】，不许有副作用。
    #    它曾经把 docs/11-*.md 覆盖掉 —— 而且是在 T8 那几轮还没跑完时，
    #    用一份退化到「（这一期没有迭代实验）」的稿子覆盖。
    #    症状：跑一下「看看有没有漏指标」，回来发现交付物没了。
    #    同 T4 那条「别信 HTTP 200，看返回值」：一个只读命令写文件是静默的。
    #
    # ★★★ `--refresh` 是同一个形状，2026-09-24 咬了一次：
    #    它的说明是「从端点重新生成 report.json」，**没说要写交付物**，
    #    但它和普通运行走同一条写盘路径。当时 `DEFAULT_T8_AXES` 里有个
    #    runId 指向了已删的目录 → §11 整节退化成一句话 →
    #    「刷一下缓存」回来发现交付物被换成了一份缺一整节的稿子。
    #    ★ 按坑 16 的先例统一：**带标志的诊断/维护命令一律不写交付物**，
    #      只有【不带标志】的那一次执行才写。这样「我改了文件吗」永远可预测。
    if args.coverage or args.refresh:
        sys.stdout.write(text)     # 让人仍然看得到正文
        if args.coverage:
            miss = coverage(runs)
            sys.stderr.write("\n未被报告收录的指标路径 %d 条：\n" % len(miss))
            for p in miss:
                sys.stderr.write("  · %s\n" % p)
            if not miss:
                sys.stderr.write("（report.json 的每一个指标路径都在报告里，"
                                 "或已在 OMITTED_PATHS 里写了理由）\n")
        sys.stderr.write("★ 只读模式：**没有**写 `%s` / `%s`\n"
                         "  ★ 要更新交付物，跑不带标志的那一次\n"
                         % (args.out, args.appendix_out))
        return 0

    if args.out == "-":
        sys.stdout.write(text)
    else:
        with io.open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
        sys.stderr.write("已写入 %s（%d 行 / %d 字节）\n" % (args.out, len(lines), len(text.encode("utf-8"))))

    # ★ 附录只跟主体轮 + 噪声底轮有关，但它自己一份文件
    if args.appendix_out:
        alines = ["# 阶段 7 · 逐题附录", "",
                  "> 由 `scripts/eval_report.py` 生成。**这份表是「数字怎么来的」的最后一站。**", ""]
        alines.extend(render_appendix(runs))
        atext = "\n".join(alines) + "\n"
        with io.open(args.appendix_out, "w", encoding="utf-8") as f:
            f.write(atext)
        sys.stderr.write("已写入 %s（%d 行 / %d 字节）\n"
                         % (args.appendix_out, len(alines), len(atext.encode("utf-8"))))



if __name__ == "__main__":
    sys.exit(main())
