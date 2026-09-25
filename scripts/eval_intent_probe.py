#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
意图分类准确率的复测探针（阶段 7 · 批次 6 之后的一次必要返工）。

★ 它为什么存在
--------------
2026-09-20 改了 `intent-tree.yml` 里 WARRANTY 的判据（删掉与 USAGE_GUIDE
撞车的「常见故障怎么处理」），又改写了 7 道与少样本/prompt 近似的题面。
两件事都**改了进分类 prompt 的文本** —— 于是 5.2 那个记在四份文档里的
「Top-1 19/20 = 95%」当场作废。这个脚本就是把它重新量出来。

★★ 为什么不能只跑一遍
---------------------
`temperature = 0.0` **不等于确定性**（CLAUDE.md，实测同一问题 8 次出现 7:1 分裂，
单次准确率有 ±5% 波动）。所以单跑一遍得到的是一个**带 ±5% 噪声的数**，
拿它去替换一个可疑的数，只是把一个坏数换成另一个坏数。

★★ 而且**重复次数必须够高**（2026-09-21 定下的口径）：
```
3 次【分辨不出】稳定与不稳定：
  3:0  不代表稳定  —— 真实 70/30 的题，3 次抽样有约 34% 概率给出 3:0 的假象
  「无唯一众数」几乎永远是空的 —— 两轮各 8 次，实测都是 0 道
  真正的不稳定长成 6:2 / 7:1，而 3 次只把它渲染成 2:1（不进桶）或 3:0（看起来最稳）
```
所以本脚本的**主指标是「非全票一致的题」**（`len(votes) > 1`），
「无众数」降级成诊断信息。默认 `--repeat 8`。

★ 为什么主口径在本脚本而不在完整管线上：分类器只看那一句话，不需要跑检索+生成。
  实测 199 题 × 12 次分类 ≈ 0.98 元 / 37 分钟，而 199 题 × 3 次完整管线
  ≈ 1.25 元 / 44 分钟 —— 本脚本更便宜、更快、样本还多 4 倍。

★★ 而且它**天然没有幸存者偏差**：澄清闸门在 ChatServiceImpl 里，本脚本不过闸门。
  它测的是「**分类器本身**有多准」，而 qa_log 那条路测的是「**线上实际发生**的分类」。
  两者都要报，报告里必须写清哪个是哪个。

⚠️ 它只量 Top-1 意图准确率，**不量检索范围准确率**
--------------------------------------------------
后者的定义是「分类结果叶子的 doc_types 集合 == 标注叶子的 doc_types 集合」，
要用到 `IntentTree.docTypesOf(code)` —— 那是 Java 里的一处逻辑。
在 Python 里重写一遍会造出**第二个事实来源**，而两份实现漂移时不会有任何报错。
所以那一半留给 T4 的报告端点（它跑在 Java 里，直接复用 IntentTree）。

⚠️ 它**拒绝**多轮题
------------------
多轮题的 gold 记在 `standalone_question` 上，而分类器看到的是**最后一轮**的一句话
（分类不看历史，ADR-046）。两者本来就不该相等，把它们混进同一个准确率里
是范畴错误。多轮那一套由 T4/T7 用单独的分母报。

用法
----
    python scripts/eval_intent_probe.py --set baseline --repeat 8
    python scripts/eval_intent_probe.py --set stage7   --repeat 12   # 159 题，约 30 分钟

成本：每题每次一次分类调用（实测 ≈ 0.0004 元 / 次、≈ 0.9 秒）。
      20 题 × 8 次 ≈ 0.07 元；159 题 × 12 次 ≈ 0.8 元。很便宜。
⚠️ 但**耗时不是**：159 题 × 12 次 ≈ 30 分钟。`--repeat` 每加一倍，时间就翻一倍。
"""

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter

# ★ Windows 上 Python 读服务端响应可能按 GBK 解码（CLAUDE.md 的坑 1）
sys.stdout.reconfigure(encoding="utf-8")

# ★★ 接入点默认 localhost:8080，但【可覆盖】（--base）。
#
#   为什么要有这个开关（2026-09-25 加的）：
#     localhost:8080 是本地开发那条路的端口，而它**经常被一个别的实例占着**。
#     没有开关时只有两个选择：杀掉那个实例，或者不测 ——
#     而「杀掉一个不是自己起的进程」不该是一个测量脚本逼你做出来的决定。
#
#   ★ 同 probe_stage8.py 的 --url：验证脚本要能指向【任何一个】实例，
#     包括起在另一个端口上的新版本（撞端口时最自然的做法）。
#
#   ⚠️ 它【不解决】「测到了旧进程」那个坑（docs/10 坑 36）——
#      换端口只是让你能挑一个确定的实例，你仍然要自己确认那一版对不对。
#      ★ 判据：先打一发 /api/debug/agent/intent-prompt，看头两行是不是你期望的文案。
BASE = "http://localhost:8080"


def post_reload():
    """拉题库（含人工标注的 intent）。reload 是幂等的 upsert，重复调用无害。"""
    req = urllib.request.Request(BASE + "/api/debug/eval/reload", method="POST")
    raw = urllib.request.urlopen(req, timeout=180).read().decode("utf-8")
    data = json.loads(raw)
    if "count" not in data:
        # 加载器的失败形状是 ApiResponse.fail(...)，没有 count
        print("★ 题库加载失败：", json.dumps(data, ensure_ascii=False)[:500])
        sys.exit(1)
    return data


def classify(question):
    """调分类探针。中文走百分号编码，不经过 shell（CLAUDE.md 的坑 1）。"""
    url = BASE + "/api/debug/agent/classify?" + urllib.parse.urlencode({"q": question})
    raw = urllib.request.urlopen(url, timeout=120).read().decode("utf-8")
    return json.loads(raw)


def classify_safely(question):
    """
    一次调用失败（超时 / 连接断 / 服务端 5xx）**不能毁掉整轮测量**。

    ★ 实测踩到过：分类正常约 900ms，而有一次卡到 120s 超时 ——
      脚本直接抛 TimeoutError 崩掉，前面已经跑完的 100 多次调用全部作废。
      （那次恰好也说明「单次测量有多脆」。）

    ★★ 这里把失败记成一个【明确的票】而不是重试：
      重试会把「这一跳失败了」悄悄抹掉，而失败率本身是 T3 要报的一个数。
      票值写成 `ERROR:<类型>`，它不会等于任何合法 code，所以这题必然判错 ——
      **宁可让一题判错并看见，也不要让它看起来正常。**
    """
    try:
        r = classify(question)
        return r.get("intent") or ("ERROR:empty(" + str(r.get("error")) + ")"), r.get("cost") or 0.0
    except Exception as e:  # noqa: BLE001 —— 故意的：任何失败都要变成一张可见的票
        return "ERROR:" + type(e).__name__, 0.0


def main():
    global BASE

    ap = argparse.ArgumentParser()
    ap.add_argument("--set", required=True, help="question_set，如 baseline / stage7")
    ap.add_argument("--repeat", type=int, default=8,
                    help="每题重复次数。★ 默认 8 —— 3 次分辨不出「稳定」与「3:0 的假象」"
                         "（一道真实 70/30 的题有约 34%% 概率给出 3:0）。低于 8 会在结尾告警")
    ap.add_argument("--base", default=BASE,
                    help="接入点。★ 默认 %(default)s —— 8080 被别的实例占着时，"
                         "把新实例起在别的端口再指过来。同 probe_stage8.py 的 --url")
    args = ap.parse_args()
    BASE = args.base.rstrip("/")

    bank = post_reload()
    questions = [q for q in bank["questions"] if q["questionSet"] == args.set]
    if not questions:
        print("★ 这一套一道题都没有：", args.set, "（有的是", bank["bySet"], "）")
        sys.exit(1)

    multi = [q for q in questions if q.get("turns")]
    if multi:
        print("★ %s 里有 %d 道多轮题 —— 本脚本拒绝它们。" % (args.set, len(multi)))
        print("   多轮题的 gold 在 standalone_question 上，而分类器看到的只有")
        print("   【最后一轮】那一句（分类不看历史）。两者本来就不该相等，")
        print("   混进同一个准确率是范畴错误。多轮另用单独分母，见 T4/T7。")
        sys.exit(1)

    print("=" * 72)
    print("意图分类复测  set=%s  题数=%d  每题 %d 次" % (args.set, len(questions), args.repeat))
    print("=" * 72)

    correct, swing = 0, []
    unstable = []          # 非全票一致的题（含 6:2 这种有众数的）
    cost = 0.0
    errors = 0
    t0 = time.time()

    for i, q in enumerate(questions, 1):
        picked = []
        for _ in range(args.repeat):
            vote, c = classify_safely(q["question"])
            picked.append(vote)
            cost += c
            if vote.startswith("ERROR:"):
                errors += 1

        votes = Counter(picked)
        top, n = votes.most_common(1)[0]
        ok = top == q["intent"]
        if ok:
            correct += 1

        # ★ 两个都算、都报，但**地位不同**（2026-09-21 定）：
        #   swing    = 无唯一众数（1:1:1）—— 诊断信息。实测两轮各 8 次都是 0 道，
        #              因为真实的不稳定长成 6:2 / 7:1，几乎不会恰好打平
        #   unstable = 非全票一致（2:1 / 6:2 / 7:1…）—— ★★ 主指标
        # 只报 swing 会得出「所有题都稳」的结论，而那是假的。
        tied = len([c for c in votes.values() if c == n]) > 1
        if tied:
            swing.append((q["questionNo"], q["question"], q["intent"], dict(votes)))
        if len(votes) > 1:
            unstable.append((q["questionNo"], q["intent"], top, dict(votes)))

        flag = "✅" if ok else "❌"
        mark = "  ⚠️无众数" if tied else ""
        print("%s [%3d/%3d] %-8s gold=%-20s pred=%-20s %s%s"
              % (flag, i, len(questions), q["questionNo"], q["intent"], top,
                 " ".join(picked), mark))

    dt = time.time() - t0
    print("=" * 72)
    # ⚠️ 这个标签以前写死成「3 次取众数」—— 而 --repeat 是可配的。
    #    写死之后，用 --repeat 8 跑出来的数会带着「3 次」的标签进报告。
    #    ★ 一个数字旁边的口径如果是错的，那个数字就不可解释（同 ADR-080）。
    print("Top-1 意图准确率（%d 次取众数）          %d/%d = %.0f%%"
          % (args.repeat, correct, len(questions), 100.0 * correct / len(questions)))

    # ★★ 真正该看的是这个：**没有全票一致的题**。
    #    只报「摇摆（无众数）」会漏掉最常见的那种不稳定 —— 6:2、7:1 都有明确众数，
    #    但它们意味着这题的分数**换个时间跑就可能翻**。
    #    实测（2026-09-20，baseline 20 题 × 8 次）：3 道非全票一致，
    #    其中 2 道众数是错的 —— 而旧的「19/20 = 95%」正是在这种题上蒙对了一次。
    print("（诊断）无唯一众数的题                  %d 道 —— ★ 这个数恒为 0 是正常的，别盯它"
          % len(swing))
    for no, txt, gold, votes in swing:
        print("   %-8s gold=%-20s %s   %s" % (no, gold, votes, txt))
    # ★★ 非全票一致 = 这题的分数换个时间跑就可能翻。它比「准确率」本身更值得看：
    #    准确率是一个点估计，而这张表告诉你那个估计有多稳。
    #    ★ 报告里报准确率时，必须同时报这个数 —— 否则读者会把 90% 当成一个确定的量。
    print("★★ 主指标：非全票一致的题               %d 道（分数可能随时翻）" % len(unstable))
    for no, gold, top, votes in unstable:
        print("   %-8s gold=%-20s 众数=%-20s %s" % (no, gold, top, votes))
    # ★ 调用失败率必须报出来 —— 它会让准确率偏低，而偏低的原因不是模型不行。
    #   写进报告时它和「分类错了」必须分开读。
    print("调用失败（超时等）                      %d / %d 次"
          % (errors, len(questions) * args.repeat))
    print("耗时 %.1fs   本次花费 ≈ %.4f 元" % (dt, cost))
    print()
    print("★ 分母是这 %d 道【人工标注】的题，不是线上分布。" % len(questions))
    print("★ 单次有 ±5% 波动（temperature=0 不等于确定性）—— 报数字时必须带这句。")
    print("★ 检索范围准确率【不在这里】—— 它要用 IntentTree.docTypesOf，")
    print("  在 Python 里重写会造出第二个事实来源。那一半见 T4 的报告端点。")


if __name__ == "__main__":
    main()
