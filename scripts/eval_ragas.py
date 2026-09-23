# -*- coding: utf-8 -*-
"""阶段 7.5 —— 用 RAGAS 给答案打质量分。

★ 设计与本项目其它脚本一致：
  · 它【不】自己算检索指标（那在 `scripts/eval_report_check.py` 和 Java 的
    `/api/debug/eval/report` 里）—— 它只回答一个问题：
    **生成出来的答案好不好**。
  · 它【不】自己去查库拼上下文（那在 `/api/debug/eval/answers` 里）——
    取数和评判分开，「改了评判口径不必重跑评测」。

◆ 用法

    # 冒烟（先跑 5 题，确认 judge 通、分数合理）
    .venv-eval/Scripts/python.exe scripts/eval_ragas.py --run 20260921-stage7 --limit 5

    # 全量
    .venv-eval/Scripts/python.exe scripts/eval_ragas.py --run 20260921-stage7

    # 反-对照：上下文故意换成别的题的，faithfulness 必须【掉】
    .venv-eval/Scripts/python.exe scripts/eval_ragas.py \
        --run 20260921-stage7 --limit 8 --contexts scrambled --tag negative-control

◆ ★★ 两个 contexts 用在哪

    faithfulness        → contexts（切片 + 结构化硬数据）—— 「模型看到的全部输入」
    context_precision   → contextsRetrievedOnly（【只】切片）—— 「检索回来的东西」
    context_recall      → 同上

  把硬数据喂给后两栏会让它们【虚高】：那段天数是注入的、不是检索来的，
  拿它证明「检索质量好」是循环论证。这个分工写在 answers 端点的 `口径` 里，
  这里照做 —— 而不是在这里另立一套。

◆ 依赖（独立 venv，不进主服务）

    python -m venv .venv-eval
    .venv-eval/Scripts/python.exe -m pip install "ragas==0.4.3" "langchain-community<0.4"

  ⚠️ `langchain-community<0.4` 那一条【不是可选的】。ragas 0.4.3 对它的依赖
     声明【无上限】，而 0.4.x 删掉了 `langchain_community.chat_models.vertexai`，
     于是 ragas 一 import 就 ModuleNotFoundError。见 `docs/10` 的坑列表。
"""

import argparse
import asyncio
import io
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ★ 默认值都是从 application.yml 的【同一个来源】推导的，不在这里另立一套。
#   改了 application.yml 而这里没改 → 下面 load_llm_config() 会去读它，读不到才用这些
DEFAULT_JUDGE_BASE = "https://api.deepseek.com/v1"
DEFAULT_EMBED_BASE = "https://api.siliconflow.cn/v1"
DEFAULT_EMBED_MODEL = "BAAI/bge-m3"

# ★★ judge 是 `deepseek-flash` = 【推理模型】（实测推理 token 占输出 87%）。
#    ragas 的 InstructorModelArgs 默认 max_tokens=1024 —— 会被推理吃光，
#    然后返回空 content，现象是「指标全是 NaN」而【不报错】。
#    ragas 自己的文档也写了「Default max_tokens=1024 may not be sufficient,
#    consider increasing to 4096+」。这里给足。
DEFAULT_MAX_TOKENS = 8192

# ★ judge 调用的重试次数。实测 3 行（~12 次调用）出 2 次 APITimeoutError（~15%），
#   不重试的话一轮下来会有几十个空值 —— 而空值在报告里只表现为「n 少了一点」。
RETRIES = 2


# ================================================================
# 配置
# ================================================================

def flat_yaml(path):
    """极简 YAML 扁平化：只取 `键: 值` 叶子。够用就行（不引 pyyaml）。"""
    out, stack = {}, []
    if not os.path.exists(path):
        return out
    for raw in io.open(path, encoding="utf-8"):
        line = raw.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        m = re.match(r"(\s*)([\w-]+):\s*(.*)$", line)
        if not m:
            continue
        key, val = m.group(2), m.group(3).strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        keys = [k for _, k in stack] + [key]
        if val:
            out[".".join(keys)] = val
        else:
            stack.append((indent, key))
    return out


def load_llm_config():
    """judge / embedding 的连接信息。

    ★ 来源顺序和 Spring 一致：**环境变量优先，application-local.yml 兜底**。
      两处都读不到就明确报错退出 —— 而不是拿一个空 key 去调，
      那样拿到的是 401，看起来像「网络问题」。
    """
    base = flat_yaml(os.path.join(ROOT, "src/main/resources/application.yml"))
    local = flat_yaml(os.path.join(ROOT, "src/main/resources/application-local.yml"))

    judge_base = base.get("xbla.llm.providers.deepseek.base-url", DEFAULT_JUDGE_BASE)
    if not judge_base.endswith("/v1"):
        judge_base = judge_base.rstrip("/") + "/v1"
    embed_base = base.get("xbla.llm.providers.siliconflow.base-url", DEFAULT_EMBED_BASE)
    if not embed_base.endswith("/v1"):
        embed_base = embed_base.rstrip("/") + "/v1"

    judge_key = os.environ.get("DEEPSEEK_API_KEY") or local.get("xbla.llm.providers.deepseek.api-key", "")
    embed_key = os.environ.get("SILICONFLOW_API_KEY") or local.get("xbla.llm.providers.siliconflow.api-key", "")

    judge_model = os.environ.get("XBLA_JUDGE_MODEL") or base.get(
        "xbla.llm.models.deepseek-flash.model-id", "deepseek-flash")
    embed_model = base.get("xbla.llm.embedding.model-id", DEFAULT_EMBED_MODEL)

    missing = [n for n, k in (("DEEPSEEK_API_KEY", judge_key), ("SILICONFLOW_API_KEY", embed_key))
               if not k or k.startswith("${")]
    if missing:
        sys.exit("★ 缺少密钥：%s\n"
                 "  请设成环境变量，或写进 src/main/resources/application-local.yml"
                 "（那个文件已 gitignore）" % "、".join(missing))

    return {
        "judgeBase": judge_base, "judgeKey": judge_key, "judgeModel": judge_model,
        "embedBase": embed_base, "embedKey": embed_key, "embedModel": embed_model,
    }


def redact(cfg):
    """进报告的配置 —— ★ 密钥绝不能落盘。"""
    return {
        "judge": {"baseUrl": cfg["judgeBase"], "model": cfg["judgeModel"],
                  "apiKey": "***", "maxTokens": cfg.get("maxTokens")},
        "embedding": {"baseUrl": cfg["embedBase"], "model": cfg["embedModel"],
                      "apiKey": "***"},
    }


# ================================================================
# 取数
# ================================================================

def fetch_answers(run_id, answers_path, endpoint):
    """拿 `/api/debug/eval/answers` 的结果。

    ★ 优先读本地文件：那份数据**零成本**且已落盘，重跑 RAGAS 没必要再打一次 HTTP。
      ⚠️ 但它可能过期（重跑过评测之后）。所以报告里记下来源文件与它的 mtime。
    """
    if answers_path:
        with io.open(answers_path, encoding="utf-8") as f:
            data = json.load(f)
        return data, {"来源": "文件", "路径": os.path.relpath(answers_path, ROOT),
                      "修改时间": time.strftime("%Y-%m-%d %H:%M:%S",
                                                time.localtime(os.path.getmtime(answers_path)))}

    url = "%s/api/debug/eval/answers?runId=%s" % (endpoint.rstrip("/"),
                                                  urllib.parse.quote(run_id))
    try:
        with urllib.request.urlopen(url, timeout=120) as resp:
            data = json.load(resp)
    except urllib.error.URLError as e:
        sys.exit("★ 取不到 answers（%s）：%s\n"
                 "  应用起了吗？或者用 --answers <文件> 直接读落盘的那份。" % (url, e))
    return data, {"来源": "端点", "url": url}


def stratify(rows, per_intent):
    """按【标注意图】分层抽样：每个叶子取前 N 题。

    ★ 判据用 `goldIntent`（题库标注）而不是 `intent`（模型预测）——
      我们要覆盖的是【题库的类别】，让预测结果决定取谁会让
      「分类错了的题」被系统性漏掉，而它们恰好是最该被看一眼的。

    ★★ 不用随机数：**按 questionNo 排序后取前 N**，完全确定。
      Python 的 `hash()` 对字符串是【每次进程加盐】的（同 JDK 的
      String.hashCode 在 JDK9+ 的行为）—— 拿它选样本会让
      「同一份数据两次跑出不同的题」，且不会有任何报错。
      症状是「两次 RAGAS 的均值差了一点」，而那会被当成噪声。

    ★★ 而且【等距取】而不是取前 N：取前 N 的话抽中的永远是 `-001/-002/-003`，
      那是每一批里最先写的那几道，问法风格可能成系统 ——
      样本会「看起来覆盖了 15 个叶子」，实际覆盖的是 15 个叶子的【同一个角落】。
      等距取（首、尾、中间均匀散布）在不引入随机数的前提下铺满整个叶子。

    ★ 返回体里同时给出【实际构成的表】，让读的人自己判断这个样本能不能代表全体。
    """
    if not per_intent:
        return list(rows), None

    groups = {}
    for r in rows:
        groups.setdefault(r.get("goldIntent") or "(无标注意图)", []).append(r)

    picked, composition = [], {}
    for intent in sorted(groups):
        g = sorted(groups[intent], key=lambda r: r["questionNo"])
        n = min(per_intent, len(g))
        if n == len(g):
            idx = list(range(len(g)))                    # 全取，不用摆
        elif n == 1:
            idx = [0]
        else:
            # 首尾都取到，中间均匀 —— round 而不是 int()：int() 会挤在头部
            idx = sorted({round(i * (len(g) - 1) / (n - 1)) for i in range(n)})
        take = [g[i] for i in idx]
        picked.extend(take)
        composition[intent] = {"题库里": len(g), "抽中": len(take),
                               "题号": [r["questionNo"] for r in take]}
    picked.sort(key=lambda r: r["questionNo"])
    return picked, composition


def pick_rows(data, limit, context_mode, seed, stratified):
    """按模式准备每个题目的 contexts。

    ★ `scrambled` 是【反-对照】：把每题的上下文换成【另一道题】的。
      如果 faithfulness 不因此掉下来，那这个指标就是恒真的 ——
      而恒真的指标看起来和「模型很好」一模一样。

    ★★ 借的必须是【不同意图】的题的上下文，不能只要求「不是同一道题」。
      实测（2026-09-21）：C-007 借到了 C-011 —— 【同是 COUPON】的题，
      上下文高度重叠，于是 faithfulness 纹丝不动地停在 1.000。
      那一次「对照通过」是假的：它证明的是「同类题的上下文很像」，
      不是「这个指标会动」。★ 对照物选得太近，对照就成了安慰剂。
    """
    rows, _ = stratified
    if limit:
        rows = rows[:limit]

    if context_mode == "scrambled":
        rnd = random.Random(seed)
        pool = list(data["rows"])
        out = []
        for row in rows:
            same = row.get("goldIntent")
            others = [r for r in pool
                      if r["questionNo"] != row["questionNo"]
                      and (r.get("goldIntent") or "") != (same or "")]
            if not others:                       # 整个样本只有一个意图时的兜底
                others = [r for r in pool if r["questionNo"] != row["questionNo"]]
            donor = rnd.choice(others) if others else row
            copy = dict(row)
            copy["contexts"] = list(donor["contextsRetrievedOnly"])
            copy["contextsRetrievedOnly"] = list(donor["contextsRetrievedOnly"])
            copy["scrambledFrom"] = donor["questionNo"]
            copy["scrambledFromIntent"] = donor.get("goldIntent")
            out.append(copy)
        return out

    if context_mode == "without-facts":
        return [dict(r, contexts=list(r["contextsRetrievedOnly"])) for r in rows]

    return rows


# ================================================================
# 指标
# ================================================================

def build_metrics(cfg):
    """构造 0.4 的 collections 指标。

    ★ 这几个 import 路径是【读装好的源码核实过的】，不是照文档抄的 ——
      ragas 0.1/0.2/0.3/0.4 每一版 API 都改过（计划 R6）。
      核实方法见 `eval_results/_probe_ragas_api.py`。
    """
    from openai import AsyncOpenAI
    from ragas.embeddings import OpenAIEmbeddings
    from ragas.llms import llm_factory
    from ragas.metrics.collections import (
        AnswerCorrectness, AnswerRelevancy, ContextPrecision, ContextRecall, Faithfulness)

    judge_client = AsyncOpenAI(base_url=cfg["judgeBase"], api_key=cfg["judgeKey"])
    embed_client = AsyncOpenAI(base_url=cfg["embedBase"], api_key=cfg["embedKey"])

    llm = llm_factory(cfg["judgeModel"], provider="openai", client=judge_client,
                      max_tokens=cfg["maxTokens"])
    emb = OpenAIEmbeddings(client=embed_client, model=cfg["embedModel"])

    return {
        "faithfulness": Faithfulness(llm=llm),
        "answer_relevancy": AnswerRelevancy(llm=llm, embeddings=emb),
        "context_precision": ContextPrecision(llm=llm),
        "context_recall": ContextRecall(llm=llm),
        "answer_correctness": AnswerCorrectness(llm=llm, embeddings=emb),
    }


def args_for(name, row, context_mode):
    """每个指标要喂哪些字段 —— ★ 这里的键名是读 `ascore` 签名得到的。"""
    q, a, ref = row["question"], row["answer"], row["expectedAnswer"]
    if name == "faithfulness":
        # 「模型看到的全部输入」= 切片 + 结构化硬数据
        return {"user_input": q, "response": a, "retrieved_contexts": row["contexts"]}
    if name == "answer_relevancy":
        return {"user_input": q, "response": a}
    if name == "context_precision":
        # ★★ 「检索回来的东西」—— 【不带】硬数据。它量的是检索质量
        return {"user_input": q, "reference": ref,
                "retrieved_contexts": row["contextsRetrievedOnly"]}
    if name == "context_recall":
        return {"user_input": q, "retrieved_contexts": row["contextsRetrievedOnly"],
                "reference": ref}
    if name == "answer_correctness":
        return {"user_input": q, "response": a, "reference": ref}
    raise KeyError(name)


async def score_metric(metric, name, row, context_mode, sem):
    """一个指标一次 —— ★ 信号量【加在这里】，不是加在整行上。

    实测（2026-09-21，deepseek-flash 当 judge）：
        一行五个指标【串行】 = 241 秒（faithfulness 29 / answer_relevancy 62 /
        context_precision 66 / context_recall 14 / answer_correctness 70）。
        135 题按 4 并发要 2.3 小时 —— 而它们本来毫无依赖关系。

    ★ 加在整行上是【我的失误】，不是指标本身慢：一行里五个指标互相独立，
      串起来跑等于白白把并发度除以 5。症状是「跑得慢」，看起来像模型慢。
    """
    async with sem:
        last = None
        for attempt in range(RETRIES + 1):
            try:
                res = await metric.ascore(**args_for(name, row, context_mode))
                # ★★ `res.reason` 在 ragas 0.4.3 里【恒为 None】——
                #    四个 collections 指标的源码都是 `return MetricResult(value=float(score))`，
                #    压根不填 reason（读装好的源码核实的，不是猜的）。
                #    ⚠️ 那根「要有判词才解释得清」的线因此断了：报告里只能报分，
                #    「为什么这题 0.389」要另找依据（本项目用的是 T4 的归因桶 ——
                #    filtered_out / not_recalled / rerank_dropped 那一套）。
                #    要拿到 statement 级的判定，得实现一个记录 `_create_verdicts`
                #    返回值的子类，并把指标【每次调用新建一个】（共用一个实例
                #    会让并发互相踩 self.last_verdicts）—— 记为可做的改进，本阶段不做
                return name, float(res.value), (res.reason or None), None
            except Exception as e:                   # noqa: BLE001
                last = e
                if attempt < RETRIES:
                    # ★ 重试是必要的，不是保险：实测 3 行（~12 次调用）里就出了
                    #   2 次 APITimeoutError，约 15%。不重试的话 43 题会有
                    #   ~70 个空值 —— 而那些空值在报告里只是「n 少了一点」，
                    #   看不出是网络问题还是指标算不出来
                    await asyncio.sleep(2 * (attempt + 1))
        # ★ 单个指标炸掉不能让整轮作废 —— 但【必须】记下来。
        #   把失败当 0 分是最坏的做法：它看起来像「模型答得很差」。
        return name, None, None, "%s: %s" % (type(last).__name__, str(last)[:300])


async def score_row(metrics, names, row, context_mode, sem, results, done, total, sink):
    out = {"questionNo": row["questionNo"], "attempt": row.get("attempt"),
           "intent": row.get("intent"), "provider": row.get("provider"),
           "model": row.get("model"), "factsInjected": row.get("factsInjected"),
           "scores": {}, "reasons": {}, "errors": {}}
    if row.get("scrambledFrom"):
        # ★ 追溯信息必须带上：只说「借了 C-011 的上下文」不够，
        #   还要能看出【它是不是同类题】—— 同类题借来的上下文
        #   和原文高度重叠，那样的对照是安慰剂（实测踩过一次）
        out["scrambledFrom"] = row["scrambledFrom"]
        out["scrambledFromIntent"] = row.get("scrambledFromIntent")

    # ★ 五个指标【并发】跑，不串行 —— 见 score_metric 的注释
    for name, value, reason, err in await asyncio.gather(
            *[score_metric(metrics[n], n, row, context_mode, sem) for n in names]):
        out["scores"][name] = value
        if reason:
            out["reasons"][name] = reason
        if err:
            out["errors"][name] = err

    results.append(out)
    done[0] += 1
    # ★ 每行【立刻】追加落盘。第一轮冒烟因为超时被杀，15 分钟的工作全丢了 ——
    #   而丢的原因只是「没有中途写过盘」。这一个 write 就是那 15 分钟的保险
    if sink:
        sink.write(json.dumps(out, ensure_ascii=False) + "\n")
        sink.flush()
    # ★ flush=True 不是装饰：stdout 走管道时是【块缓冲】的，
    #   不 flush 的话一整轮跑完之前什么都看不到 —— 而「看不到进度」
    #   会让一次正常的慢跑看起来像卡死（实测等了 10 分钟才开始怀疑）
    print("  [%3d/%3d] %-10s %s" % (done[0], total, out["questionNo"],
                                    " ".join("%s=%s" % (k, "ERR" if v is None else "%.3f" % v)
                                             for k, v in out["scores"].items())),
          flush=True)


# ================================================================
# 汇总
# ================================================================

def summarize(rows, names):
    out = {}
    for name in names:
        vals = [r["scores"][name] for r in rows if r["scores"].get(name) is not None]
        errored = sum(1 for r in rows if name in r.get("errors", {}))
        out[name] = {
            "n": len(vals),
            "均值": round(sum(vals) / len(vals), 4) if vals else None,
            "最小": round(min(vals), 4) if vals else None,
            "最大": round(max(vals), 4) if vals else None,
            # ★★ 失败数必须单独报。把失败当 0 或干脆不报，
            #    会让「judge 挂了」看起来像「模型答得很差」
            "judge 失败": errored,
        }
    return out


def summarize_by(rows, names, key):
    """按 provider/model 切片 —— 降级到 P1/P2 的行答案质量不同，混在一起是平均数陷阱。"""
    groups = {}
    for r in rows:
        groups.setdefault(r.get(key) or "(空)", []).append(r)
    if len(groups) <= 1:
        return None
    return {k: summarize(v, names) for k, v in sorted(groups.items())}


# ================================================================
# main
# ================================================================

def main():
    ap = argparse.ArgumentParser(description="阶段 7.5 · RAGAS 答案质量评测")
    ap.add_argument("--run", required=True, help="评测运行 ID")
    ap.add_argument("--answers", help="直接读本地的 answers.json（不打端点）")
    ap.add_argument("--endpoint", default="http://localhost:8080", help="应用地址")
    ap.add_argument("--out", help="结果写哪（默认 eval_results/<run>/ragas<tag>.json）")
    ap.add_argument("--tag", default="", help="文件名后缀，如 negative-control")
    ap.add_argument("--limit", type=int, help="只跑前 N 题（冒烟用）")
    ap.add_argument("--metrics", default="faithfulness,answer_relevancy,"
                                        "context_recall,answer_correctness",
                    help="默认不含 context_precision：它每行要对【每条上下文】各判一次"
                         "（一行 ~5 次调用，是最大的单个开销），而检索质量已经由 "
                         "T4 用 gold chunk id 精确量过（HitRate/Recall/MRR）。"
                         "⚠️ 两者量的不是同一件事：T4 问「命中标注的切片了吗」，"
                         "它问「这段上下文里有没有能推出参考答案的信息」。"
                         "要跑就显式加回来")
    ap.add_argument("--stratify", type=int, metavar="N",
                    help="按【标注意图】分层，每个叶子取 N 题（不传 = 全跑）")
    ap.add_argument("--contexts", default="with-facts",
                    choices=["with-facts", "without-facts", "scrambled"],
                    help="scrambled = 反-对照：上下文换成别的题的")
    ap.add_argument("--concurrency", type=int, default=8,
                    help="同时在飞的【指标调用】数（不是题数）。实测一行五个指标"
                         "并发比串行快 3~5 倍，见 score_metric 的注释")
    ap.add_argument("--resume", action="store_true",
                    help="接着上次跑（读同目录的 ragas<tag>.rows.jsonl）")
    ap.add_argument("--seed", type=int, default=20260921)
    args = ap.parse_args()

    # ★ 不要写成模块级 global —— `--out` 的默认值依赖它，而「先读后声明 global」
    #   会直接 SyntaxError（T4 踩过一次）。局部变量没有这个问题。
    base_dir = os.path.join(ROOT, "eval_results", args.run)

    data, source = fetch_answers(args.run, args.answers, args.endpoint)
    names = [n.strip() for n in args.metrics.split(",") if n.strip()]

    sampled, composition = stratify(data["rows"], args.stratify)
    rows = pick_rows(data, args.limit, args.contexts, args.seed, (sampled, composition))
    if not rows:
        sys.exit("★ 这道 run 里没有一行能进 RAGAS。先看 `统计.被排除`：%s"
                 % json.dumps(data.get("统计", {}), ensure_ascii=False))

    cfg = load_llm_config()
    cfg["maxTokens"] = DEFAULT_MAX_TOKENS

    print("=" * 74)
    print("RAGAS  ·  run=%s  ·  题数=%d  ·  contexts=%s" % (args.run, len(rows), args.contexts))
    print("judge = %s @ %s (max_tokens=%d)" % (cfg["judgeModel"], cfg["judgeBase"], cfg["maxTokens"]))
    print("embed = %s @ %s" % (cfg["embedModel"], cfg["embedBase"]))
    print("指标  = %s" % ", ".join(names))
    print("=" * 74)

    import ragas
    metrics = build_metrics(cfg)

    # ★ 增量落盘 + 断点续跑。一轮 135 题、每次调用几十秒，
    #   「跑到 80% 被杀掉然后从头再来」是真实的成本，不是理论问题
    jsonl_path = os.path.join(base_dir, "ragas%s.rows.jsonl" % (("-" + args.tag) if args.tag else ""))
    os.makedirs(base_dir, exist_ok=True)

    already = {}
    if args.resume and os.path.exists(jsonl_path):
        for line in io.open(jsonl_path, encoding="utf-8"):
            line = line.strip()
            if line:
                rec = json.loads(line)
                already[rec["questionNo"]] = rec
        print("★ --resume：已有 %d 行，跳过它们" % len(already))

    todo = [r for r in rows if r["questionNo"] not in already]
    results, done = [], [0]
    started = time.time()

    async def go():
        sem = asyncio.Semaphore(args.concurrency)
        mode = "a" if args.resume else "w"
        with io.open(jsonl_path, mode, encoding="utf-8") as sink:
            await asyncio.gather(*[score_row(metrics, names, r, args.contexts, sem,
                                             results, done, len(todo), sink)
                                   for r in todo])

    asyncio.run(go())

    # ★ 续跑时把之前那些行也并回来 —— 否则报出来的均值只覆盖「这一次跑的题」，
    #   而那是一次【静默的样本缩小】：数字看着正常，分母却悄悄少了一截
    results.extend(already.values())
    results.sort(key=lambda r: (r["questionNo"], r.get("attempt") or 0))

    report = {
        "runId": args.run,
        "生成时间": time.strftime("%Y-%m-%d %H:%M:%S"),
        "耗时秒": round(time.time() - started, 1),
        "ragas 版本": ragas.__version__,
        "★ 依赖约束": "ragas==0.4.3 + langchain-community<0.4 —— "
                  "后者不是可选的，0.4.x 删了 ragas 要 import 的 vertexai 模块",
        "judge 与 embedding": redact(cfg),
        "数据来源": source,
        "口径": {
            "contexts 模式": args.contexts,
            "faithfulness 用": "contexts（切片 + 结构化硬数据）—— 模型看到的全部输入",
            "context_recall 用": "contextsRetrievedOnly（只切片）—— 它量的是检索质量，"
                             "注入的硬数据算进去是循环论证",
            "answer_relevancy / answer_correctness": "不用 contexts",
            "★ 跑了的指标": names,
            "★ 没跑的指标及原因": ("context_precision 不在这一轮里：它每行对【每条上下文】"
                              "各判一次（~5 次调用，单项最大开销），而检索质量已由 T4 "
                              "用 gold chunk id 精确量过。⚠️ 要跑就 --metrics 显式加回来 —— "
                              "但那时要与 T4 的数字并排读，两者量的不是同一件事")
                             if "context_precision" not in names else "无，全跑了",
            "抽题": data.get("口径", {}).get("选题"),
            "★ 抽样方式": ("按【标注 goldIntent】分层，每叶子取前 %d 题（按题号排序，"
                       "无随机数 —— 随机选样本会让两次跑的数字不一样且不报错）"
                       % args.stratify) if args.stratify else "全量，未抽样",
            "排除": "见 answers 的 统计.被排除；工具题/兜底题不进 RAGAS",
        },
        "样本": {
            "题数": len(results),
            "带硬数据的题数": sum(1 for r in rows if r.get("factsInjected")),
            "★ 分层构成": composition,
            "answers 统计": data.get("统计"),
        },
        "指标": summarize(results, names),
        "按模型切片": summarize_by(results, names, "model"),
        "按 provider 切片": summarize_by(results, names, "provider"),
        "逐题": results,
    }

    out_path = args.out or os.path.join(base_dir, "ragas%s.json" % (("-" + args.tag) if args.tag else ""))
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with io.open(out_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print()
    print("=" * 74)
    for name in names:
        s = report["指标"][name]
        print("  %-20s n=%-4d 均值=%-8s  [%s, %s]   judge失败=%d"
              % (name, s["n"], s["均值"], s["最小"], s["最大"], s["judge 失败"]))
    print("=" * 74)
    print("→ %s" % os.path.relpath(out_path, ROOT))

    # ★ 退出码反映「这一轮能不能用来下结论」：
    #   有 judge 失败就不是一次干净的测量，CI/脚本据此能自动发现
    if any(v["judge 失败"] > 0 for v in report["指标"].values()):
        sys.exit(2)


if __name__ == "__main__":
    main()
