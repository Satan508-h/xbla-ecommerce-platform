#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 7 · T3 —— **跑题器**：把题库里的每一道题真的走一遍 `POST /api/chat`。

★ 它和 `eval_baseline.py` 的根本区别
------------------------------------
4.8 那个脚本只调**检索探针**，不写库、不调模型 —— 因为它要的是【检索】指标。
本脚本要的是**答案侧**指标（RAGAS 的 faithfulness / answer_relevancy 等），
而那些指标的定义里就有「模型实际说出的话」。
产出答案只有一条路：`/api/chat`。所以**评测流量必然写进 `qa_log`**。

于是有了 `qa_log.eval_run_id` 这两列（V10），和本脚本最要紧的那件事 —— **对账**。

★★ 对账：不通过就整轮作废
------------------------
`eval_run_id` 要穿过 4 个 `CallContext` 构造点才落进 qa_log（T0 的洞 4）。
漏掉任何一个的后果是：那一行**伪装成真实用户的提问**，
而报告会少掉那一题，且**没有任何报错**。

所以每次提交都记下服务端回的 `traceId`，跑完去库里的 `qa_log` 逐条核对。
核对不过 → 整轮数据作废，重跑。宁可白花一次钱，也不要一份说不清来源的数字。

⚠️ 对账走的是 **psql（docker compose exec）**，不是应用的调试端点。
   自己验自己不算验 —— 那正是要检查的那条链路。

★ 一次运行 = 一套题
------------------
`--set` 必填。理由：报告里每个数字都要能写出分母，混两套会让「90%」的分母说不清。
多轮集（`stage7-multi`）本来就必须单独 run_id（洞 6）。

★★ `--repeat` 是干什么的（2026-09-21 定，见 plans/peaceful-rolling-clock.md §八）
------------------------------------------------------------------------------
**意图准确率【不在】这里报。** 它由 `scripts/eval_intent_probe.py` 用高重复单独测 ——
分类器只看那一句话，不需要跑检索+生成，实测更便宜、更快、样本还多 4 倍。

本脚本的重复衡量的是另外三个量，**只有完整管线能观测、探针测不出来**：
  ① **闸门抖动** —— 同一道题 3 次里 `status` 不一致 = 用户会时而被反问、时而被回答
  ② **检索抖动** —— 同为 `status=1` 的几次里 `final_top_k` 序列是否变化
  ③ **答案抖动** —— 同配置下答案变不变。★★ 这是 T6「自比对噪声底」的第一次直接观测
① 和 ② 由 T4 的报告端点从 qa_log 算；③ 由 T5/RAGAS 侧看。

用法
----
    # 冒烟（先跑 3 题，确认对账通过，再放全量）
    python scripts/eval_run.py --set stage7 --limit 3

    # 全量（159 题 × 3 次 ≈ 44 分钟 ≈ 1.25 元）
    python scripts/eval_run.py --set stage7

    # 多轮集必须单独一轮（洞 6）
    python scripts/eval_run.py --set stage7-multi

⚠️ **串行**且**没有 `--concurrency`**：并发会让 `queue_ms` 混进延迟分位数（R9）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timedelta, timezone

# ★ Windows 上 Python 读服务端响应可能按 GBK 解码（CLAUDE.md 坑 1）
#
# ★★ line_buffering 不是可有可无的：一轮全量要 36 分钟，而 stdout 重定向到
#    文件时 Python 默认按【块】缓冲（4~8KB）—— 40 分钟里日志可能一行都不出现，
#    看起来和「卡死了」逐字相同。（实测：跑到第 29 条时日志里还只有开头那行横幅。）
#
# ⚠️ 即便如此，**看进度最可靠的还是 qa_log**：
#      SELECT count(*) FROM qa_log WHERE eval_run_id = '<runId>';
#    它是服务端写的、和终态一致的那个数；日志是客户端的观感。
try:
    sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
except Exception:
    pass

BASE = "http://localhost:8080"

# ================================================================
# ★★ 三个头名必须与 Java 侧逐字一致 —— 而跨语言共享不了常量
# ================================================================
#    改一处忘另一处的后果【全部是静默的】：
#      H_RUN / H_NO 打错 → 那一行的 eval_run_id 是 NULL
#                          → 伪装成真实用户流量，只有在对账时才浮出来（整轮作废）
#      H_UID 打错        → 三个工具意图拿不到身份 → 必然失败
#                          而它看起来像「模型不行」（R8）
#
#    出处：
#      H_RUN / H_NO → com.xbla.rag.common.EvalMark.HEADER_RUN_ID / HEADER_QUESTION_NO
#      H_UID        → com.xbla.rag.mcp.protocol.McpProtocol.HEADER_USER_ID
H_RUN = "X-Xbla-Eval-Run"
H_NO = "X-Xbla-Eval-No"
H_UID = "X-Xbla-User-Id"

# 应用侧的预算：非流式排队 30 秒 + 回答 180 秒。留点余量
HTTP_TIMEOUT = 300

# ★★★ 一轮里允许的「模型链路失败」上限（status=2 或 4 的占比）。
#   超过它整轮作废 —— 见 reconcile() 里那段注释：正常轮这个数是 **0**，
#   而失败由熔断器成簇触发，活下来的行不是一个无偏的子样本。
MAX_FAILED_RATIO = 0.05

# ★ 洞 7：切分配置在 xbla.kb.chunking 下。改切分 = 换了一份语料，
#   跨越那次改动的对比全部无效。把这个前缀写在这里，并在运行时检查它
#   至少匹配到一个键 —— 匹配不到就说明前缀写错了，而**指纹会静默变成一个常量**。
CHUNK_CONFIG_PREFIX = "xbla.kb.chunking"

PSQL = ["docker", "compose", "exec", "-T", "-e", "PGCLIENTENCODING=UTF8",
        "postgres", "psql", "-U", "xbla", "-d", "xbla_rag", "-tAc"]


# ================================================================
# HTTP
# ================================================================

def _request(method: str, path: str, headers: dict, body: bytes | None,
             timeout: int) -> tuple[int, object]:
    """返回 (HTTP 状态码, 解析后的 JSON)。★ HTTP 错误码【不抛异常】——

    `503`（队列满）是一个**正常的数据点**，不是脚本故障：它会写一行
    `status=4` 的 qa_log。抛出去就等于把「系统在忙」这件事从数据里抹掉了。
    """
    req = urllib.request.Request(BASE + path, data=body, method=method)
    for key, value in headers.items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            # 非 JSON 的错误体（比如 Tomcat 的 HTML 错误页）
            return e.code, {"_raw": raw[:500]}


def reload_bank(set_name: str) -> dict:
    """① 重新解析题库。★ 它会当场校验锚点（0 或 >1 命中直接报错）——

    这一步同时是「gold 是不是还指得着」的第一道防线（洞 7）。"""
    status, payload = _request("POST", "/api/debug/eval/reload?set=" + set_name,
                               {}, b"", timeout=300)
    if status != 200 or not isinstance(payload, dict) or "count" not in payload:
        raise RuntimeError("题库加载失败（HTTP %s）：%s"
                           % (status, json.dumps(payload, ensure_ascii=False)[:600]))
    return payload


def fetch_config() -> dict:
    """② 本次运行**实际生效**的 xbla.* 配置快照。

    ★★ 不能读 application.yml —— A/B 靠环境变量覆盖跑，文件里的值和运行时的
       值不是一回事。读文件会记下「配置 A」而实际跑的是「配置 B」，且不报错。
       （端点里已经解释了这一点，见 EvalProbeController#config。）
    """
    status, payload = _request("GET", "/api/debug/eval/config", {}, None, timeout=60)
    if status != 200 or not isinstance(payload, dict) or "properties" not in payload:
        raise RuntimeError("配置快照端点不可用（HTTP %s）：%s"
                           % (status, json.dumps(payload, ensure_ascii=False)[:400]))
    return payload


def ask(question: str, question_no: str, run_id: str,
        user_id, session_no) -> tuple[int, object, int]:
    """发一次 `POST /api/chat`。返回 (HTTP 状态码, body, 客户端观测耗时)。

    ★ 中文走 JSON body 的 UTF-8 字节，**不经过 shell**（CLAUDE.md 坑 1）。
    """
    payload = {"question": question, "sessionNo": session_no}
    headers = {
        "Content-Type": "application/json; charset=utf-8",
        H_RUN: run_id,
        H_NO: question_no,
    }
    # ★★ 身份只对【声明的】工具题发。没有身份的题不能顺手塞一个 0 ——
    #    `resolveUserId` 会把 0 判成 null，但那是我在 Python 这边猜它的行为，
    #    而不是它承诺过的事。
    if user_id is not None:
        headers[H_UID] = str(user_id)

    started = time.time()
    status, body = _request("POST", "/api/chat", headers,
                            json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                            timeout=HTTP_TIMEOUT)
    return status, body, int((time.time() - started) * 1000)


# ================================================================
# 数据库（★ 独立来源 —— 自己验自己不算验）
# ================================================================

def psql_json(sql: str, what: str, timeout: int = 90):
    """跑一句 SQL，把结果当 JSON 读回来。没有行时返回 None。

    ★ 为什么必须走 `json_agg` 而不是制表符分隔：`qa_log.error_msg` 里
      **可以有换行**，任何按行切分的解析都会在那一行上下错位 ——
      而错位之后每个字段都还是「有值」的，只是值全错了。

    ★ 为什么走 docker exec 而不是 psycopg2：
      ① 本项目的 Python 侧刻意保持零第三方依赖（`generate_corpus.py` 是唯一例外）
      ② ★★ 对账必须是**独立来源**。走应用的调试端点 = 自己验自己。
    """
    try:
        proc = subprocess.run(PSQL + [sql], capture_output=True, timeout=timeout)
    except FileNotFoundError as e:
        raise RuntimeError("跑不了 psql（docker 在 PATH 里吗？）：%s" % e)
    if proc.returncode != 0:
        raise RuntimeError("%s：psql 失败\n%s"
                           % (what, proc.stderr.decode("utf-8", "replace")[:600]))
    raw = proc.stdout.decode("utf-8", "replace").strip()
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError as e:
        raise RuntimeError("%s：psql 的输出不是 JSON（%s）\n%.500s" % (what, e, raw))


def sql_literal(value: str) -> str:
    """单引号字符串字面量。★ 用 `''` 转义，不用反斜杠（那需要 standard_conforming_strings=off）。"""
    return "'" + str(value).replace("'", "''") + "'"


# ================================================================
# 指纹（洞 7）
# ================================================================

def short_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]


def build_fingerprint(questions: list, config: dict) -> dict:
    """语料 / 切分 / gold 三份指纹。

    ★★ 为什么必须有：切分一改，锚点要么全部悬空（Recall 掉到 0，很吵），
       要么**部分**悬空 —— 后者会让 `|gold|` 变小，而 **Recall 反而悄悄变好**，
       同时因为 `IntentTreeConsistencyTest` 是 inner join，它会**看起来全绿**。
       指纹不同 → 禁止跨轮 diff，这是唯一能拦住它的东西。
    """
    corpus = psql_json(
        "SELECT json_agg(x) FROM ("
        "  SELECT count(*) AS chunks, max(id) AS max_id FROM kb_chunk WHERE deleted = 0"
        ") x", "语料规模")
    if not corpus:
        raise RuntimeError("查不到 kb_chunk —— 数据库连上了吗？")
    corpus = corpus[0]

    # ★ 切分配置：不是「改了就不能比」，而是「改了以后那份对比要重新建立」。
    #   ⚠️ 前缀写错时它是【空的】，而空字典的哈希是个常量 —— 指纹静默失效。
    #      所以把 keys 的个数存进指纹里，让「它是空的」这件事在报告上看得见。
    chunking = {k: v for k, v in sorted(config.get("properties", {}).items())
                if k.startswith(CHUNK_CONFIG_PREFIX)}

    gold_pairs = sorted(
        "%s:%s" % (q["questionNo"], ",".join(str(i) for i in sorted(q["expectedChunkIds"])))
        for q in questions)

    return {
        "corpus": {"chunks": corpus["chunks"], "maxId": corpus["max_id"],
                   "hash": short_hash("%s|%s" % (corpus["chunks"], corpus["max_id"]))},
        "chunking": {"prefix": CHUNK_CONFIG_PREFIX, "keys": len(chunking),
                     "hash": short_hash(json.dumps(chunking, sort_keys=True))},
        "gold": {"questions": len(questions),
                 "goldIds": len({i for q in questions for i in q["expectedChunkIds"]}),
                 "hash": short_hash("|".join(gold_pairs))},
        "note": "★ 两份 run 的任一指纹不同 → 禁止逐题 diff（洞 7）。"
                "chunking.keys 为 0 表示前缀没匹配到任何配置键，"
                "此时 chunking.hash 是个常量、不提供任何保护。",
    }


def assert_gold_alive(questions: list) -> list:
    """★ 洞 7 的「报告前断言每个 gold id 仍存在」——**挪到跑之前**。

    跑完 44 分钟才发现 gold 指空，是最贵的一种失败。
    返回查不到的 id 列表（空列表 = 全都在）。
    """
    ids = sorted({i for q in questions for i in q["expectedChunkIds"]})
    if not ids:
        return []
    alive = psql_json(
        "SELECT json_agg(id) FROM kb_chunk WHERE deleted = 0 AND id IN (%s)"
        % ",".join(str(i) for i in ids), "gold 存活检查") or []
    return sorted(set(ids) - set(alive))


# ================================================================
# 对账
# ================================================================

def reconcile(run_id: str, submissions: list, question_nos: list,
              started_at: datetime) -> dict:
    """★★ 本次运行的核心断言：提交的每一条都真的落进了 qa_log。

    ★ 两边都要查，缺一不可：
      - 提交 → 库：**硬失败**。拿到的 traceId 在库里找不到，说明它走的某条
        路径把 `eval_run_id` 丢了（洞 4），那行数据正在冒充真实用户。
      - 库 → 提交：多出来的行是「幽灵」。它进了本轮统计，但我们不知道它是谁。
        ⚠️ 但**其中有一类是可解释的**：请求在 HTTP 层就失败（503 队列满 /
        连接断）时，我们拿不到 traceId —— 而服务端可能照样写了行。
        所以判据是「幽灵行数 > 没拿到 traceId 的提交数」，不是「幽灵行数 > 0」。

    ★ 第三个哨兵：**有题号但没有 runId 的行**（半标记）。EvalMark 的 javadoc 说
      报告端点必须把它们数出来 —— 这里数，因为这里是唯一还知道
      「本轮有哪些题号」的地方。
    """
    submitted = [s for s in submissions if s.get("traceId")]
    no_trace = [s for s in submissions if not s.get("traceId")]

    since = (started_at - timedelta(minutes=1)).astimezone(timezone.utc)
    nos = ",".join(sql_literal(n) for n in sorted(set(question_nos)))
    rows = psql_json(
        "SELECT json_agg(x) FROM ("
        "  SELECT trace_id, eval_run_id, eval_question_no, status, intent, cost,"
        "         queue_ms, total_latency_ms"
        "  FROM qa_log"
        "  WHERE created_at >= TIMESTAMPTZ %s"
        "    AND (eval_run_id = %s OR eval_question_no IN (%s))"
        ") x" % (sql_literal(since.strftime("%Y-%m-%dT%H:%M:%S%z")),
                 sql_literal(run_id), nos), "对账查询") or []

    clean, leaked, foreign = [], [], []
    for row in rows:
        if row["eval_run_id"] == run_id:
            clean.append(row)
        elif row["eval_run_id"] is None:
            leaked.append(row)
        else:
            foreign.append(row)

    found = {r["trace_id"]: r for r in clean}
    submitted_ids = {s["traceId"] for s in submitted}
    missing = sorted(submitted_ids - set(found))
    # 库里有、但我们没提交过 —— 幽灵
    ghost = [r for r in clean if r["trace_id"] not in submitted_ids]
    # ★ 只有「比可解释的那一类还多」才叫问题
    ghost_unexplained = max(0, len(ghost) - len(no_trace))
    # 标记不符：traceId 在，但题号对不上（我发的和它记的不是同一题）
    mismatched = [r for r in clean
                  if r["trace_id"] in submitted_ids
                  and r["eval_question_no"] != next(
                      s["questionNo"] for s in submitted if s["traceId"] == r["trace_id"])]

    # ★★ 判据里【没有】foreign。理由：foreign 按定义就是「别的 runId 的行」，
    #    它在我们按题号 + 时间窗捞出来的结果里出现，只说明**另有一轮**（同一个
    #    题集在一分钟内又跑了一次，或者两轮并行）落在了同一个窗口里。
    #    它和我们的数据没有任何关系，把它算成失败就是**造误报**——
    #    而误报的代价是让人学会忽略这个红灯，那比没有红灯更糟。
    #    ⚠️ leaked 不同：runId 是 NULL 而题号是我们的，**无法区分**
    #       「我们的行丢掉了 runId」和「别人的行丢掉了 runId」——
    #       那是洞 4 的哨兵，必须红。
    ok = not missing and not mismatched and not leaked and ghost_unexplained == 0

    # ★★★ 第四个哨兵：**这一轮根本没跑成**。
    #
    #   2026-09-23 实测：轴 2b 那轮，477 条提交里 **408 条是 HTTP 503**
    #   （`UnresolvedAddressException` → DNS 挂了 → 三个熔断器全跳闸），
    #   只有 69 条真的答了 —— 而 `ok` 仍然是 **True**，
    #   因为上面三条判据问的全是「行有没有丢」，**没有一条问「行有没有成」**。
    #   那一轮的 `complete=True` + `reconcileOk=True`，报告生成器的前置检查
    #   会放它进去，然后拿 69 道题的样本去算「159 题的指标」。
    #
    #   ★ 阈值为什么是 5%：正常轮的这个数是 **0**（三次基线轮全是 0）。
    #     而失败**不是随机的** —— 它由熔断器成簇触发，所以「活下来的行」
    #     在时间上、在题目顺序上都偏向某一端，那不是「小一点的全体」。
    #     留 5% 只是为了让一次偶发的网络抖动不至于废掉 26 分钟的工作。
    #
    #   ⚠️ `status=3`（澄清反问）**不算失败** —— 它是设计好的行为，逐题明细里照常进分母。
    by_status = Counter(str(r["status"]) for r in clean)
    bad_rows = by_status.get("2", 0) + by_status.get("4", 0)
    bad_ratio = (bad_rows / len(clean)) if clean else 0.0
    too_many_failed = bad_ratio > MAX_FAILED_RATIO
    ok = ok and not too_many_failed

    return {
        "ok": ok,
        "badRows": bad_rows,
        "badRatio": round(bad_ratio, 4),
        "tooManyFailed": too_many_failed,
        "submitted": len(submissions),
        "submittedWithTrace": len(submitted),
        "submittedNoTrace": len(no_trace),
        "rowsInRun": len(clean),
        "missingTraces": missing[:20],
        "missingCount": len(missing),
        "mismatchedMark": [r["eval_question_no"] for r in mismatched][:20],
        "mismatchedCount": len(mismatched),
        "leakedRows": len(leaked),
        "leakedSample": [r["eval_question_no"] for r in leaked][:20],
        "foreignRows": len(foreign),
        "ghostRows": len(ghost),
        "ghostUnexplained": ghost_unexplained,
        "rowsByStatus": dict(sorted(by_status.items())),
        "rowsByQuestion": len({r["eval_question_no"] for r in clean}),
        "costInDb": round(sum(float(r["cost"] or 0) for r in clean), 4),
        "note": "★ 对账不通过 = 整轮数据作废，重跑。"
                "leakedRows > 0 是洞 4 的哨兵：有行带着题号但没有 runId，"
                "它既进不了评测统计、又混在真实数据里。",
    }


# ================================================================
# 主流程
# ================================================================

def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def main() -> int:
    ap = argparse.ArgumentParser(
        description="阶段 7 跑题器：逐题走完整 /api/chat，含对账")
    ap.add_argument("--set", required=True,
                    help="题集名（baseline / stage7 / stage7-multi）。★ 必填且一次只跑一套")
    ap.add_argument("--repeat", type=int, default=3,
                    help="每题重复次数。★ 默认 3 —— 它衡量闸门/检索/答案的抖动，"
                         "【不是】用来算意图准确率的（那由 eval_intent_probe.py 高重复单独测）")
    ap.add_argument("--run-id", default=None,
                    help="运行 ID。不传则用 时间戳-题集名")
    ap.add_argument("--limit", type=int, default=0,
                    help="只跑前 N 题（冒烟用）。★ 用了它本轮【不能】跨轮 diff")
    ap.add_argument("--out", default="eval_results", help="输出根目录")
    args = ap.parse_args()

    if args.repeat < 1:
        print("★ --repeat 至少要 1")
        return 2

    run_id = args.run_id or "%s-%s" % (datetime.now().strftime("%Y%m%d-%H%M"), args.set)
    out_dir = pathlib.Path(args.out) / run_id
    if out_dir.exists():
        print("★ 这个 run id 已经用过了：%s" % out_dir)
        print("  对账靠 run_id 唯一 —— 复用会让两轮的行走进同一个筛子。换一个。")
        return 2

    print("=" * 78)
    print("阶段 7 跑题器   run_id = %s   题集 = %s   每个题重复 %d 次"
          % (run_id, args.set, args.repeat))
    print("=" * 78)

    started_at = datetime.now().astimezone()

    # ── ① 题库 ────────────────────────────────────────────────
    print("\n[1/5] 重新解析题库（锚点 0 或 >1 命中会当场报错）...")
    bank = reload_bank(args.set)
    questions = bank["questions"]
    # ★ 不客户端再过滤一遍 —— 那会造出第二个「该跑哪些题」的事实来源。
    #   改成断言：端点说它只加载了这一套，那就必须真的只有这一套。
    intruders = sorted({q["questionSet"] for q in questions} - {args.set})
    if intruders:
        print("★ reload?set=%s 返回了别的题集：%s" % (args.set, intruders))
        return 1
    if not questions:
        print("★ 这一套一道题都没有：%s（题库里有 %s）" % (args.set, bank.get("bySet")))
        return 1
    if args.limit:
        questions = questions[:args.limit]
        print("      ⚠️ --limit %d：本轮只跑前 %d 题，【不能】用于跨轮 diff"
              % (args.limit, len(questions)))
    print("      %d 题   形态 %s" % (len(questions), bank.get("byCategory")))
    print("      意图 %s" % bank.get("byIntent"))
    print("      声明不检索（工具/兜底/澄清）%s 道，多轮 %s 道"
          % (bank.get("emptyGold"), bank.get("multiTurn")))
    if bank.get("missingNotes"):
        print("      ⚠️ 有 %s 道题没写标注理由（不是错误，只是可见）" % bank["missingNotes"])

    # ── ② 配置快照 + 指纹 ──────────────────────────────────────
    print("\n[2/5] 配置快照 + 语料/gold 指纹 ...")
    config = fetch_config()
    fingerprint = build_fingerprint(questions, config)
    print("      xbla.* 生效配置 %d 项" % config.get("count", 0))
    print("      语料  %s 片 / max(id)=%s / %s"
          % (fingerprint["corpus"]["chunks"], fingerprint["corpus"]["maxId"],
             fingerprint["corpus"]["hash"]))
    print("      切分  %d 个键 / %s" % (fingerprint["chunking"]["keys"],
                                       fingerprint["chunking"]["hash"]))
    print("      gold  %d 题 / %d 个切片 / %s"
          % (fingerprint["gold"]["questions"], fingerprint["gold"]["goldIds"],
             fingerprint["gold"]["hash"]))
    if fingerprint["chunking"]["keys"] == 0:
        print("      ★★ 警告：前缀 %s 没匹配到任何配置键 —— 指纹里的切分部分是"
              "个常量，不提供保护。" % CHUNK_CONFIG_PREFIX)
        print("          先跑 curl -s localhost:8080/api/debug/eval/config 看看真实的键名。")

    dead = assert_gold_alive(questions)
    if dead:
        print("      ★★ 有 %d 个 gold 切片在库里的【不存在】（前 10 个：%s）"
              % (len(dead), dead[:10]))
        print("          锚点解析成功但 id 变了，说明切分已经改过。"
              "本轮作废 —— 现在停下比跑完 44 分钟再发现便宜得多。")
        return 1

    # ── ③ 跑 ──────────────────────────────────────────────────
    # ★ 提交数【不是】「题数 × repeat」—— 一道多轮题一次要发 N 轮。
    #   拿题数当分母的话，多轮那一轮的进度会走到 340% 然后停住
    #   （单轮集里这条永远暴露不出来：每题的轮数都是 1，两个算法恰好相等）。
    calls_per_repeat = sum(len(q["turns"] or [q["question"]]) for q in questions)
    total_submissions = calls_per_repeat * args.repeat
    print("\n[3/5] 逐题发问（串行，%d 次提交 = %d 轮次 × %d 遍）..."
          % (total_submissions, calls_per_repeat, args.repeat))
    submissions: list = []
    skipped = 0
    client_cost = 0.0
    done = 0
    t0 = time.time()
    interrupted = False

    try:
        for q in questions:
            turns = q["turns"] or [q["question"]]
            for rep in range(1, args.repeat + 1):
                session_no = None
                for turn_i, text in enumerate(turns, start=1):
                    done += 1
                    rec = {
                        "questionNo": q["questionNo"],
                        "set": q["questionSet"],
                        "repeat": rep,
                        "turn": turn_i,
                        "turnCount": len(turns),
                        "question": text,
                        "sessionNo": session_no,
                        "traceId": None,
                        "httpStatus": None,
                        "apiCode": None,
                        "intent": None,
                        "error": None,
                        "elapsedMs": None,
                    }
                    try:
                        status, body, elapsed = ask(text, q["questionNo"], run_id,
                                                    q.get("userId"), session_no)
                        rec["httpStatus"] = status
                        rec["elapsedMs"] = elapsed
                        if isinstance(body, dict):
                            rec["apiCode"] = body.get("code")
                            data = body.get("data") or {}
                            if isinstance(data, dict):
                                rec["traceId"] = data.get("traceId")
                                rec["intent"] = data.get("intent")
                                rec["sessionNo"] = data.get("sessionNo") or session_no
                                if data.get("cost") is not None:
                                    client_cost += float(data["cost"])
                            if body.get("code") != 0:
                                rec["error"] = str(body.get("message"))[:200]
                    except Exception as e:  # noqa: BLE001 —— 任何失败都要变成一条可见的记录
                        rec["error"] = "%s: %s" % (type(e).__name__, e)
                    submissions.append(rec)

                    # ★ 每条【都】打，包括失败的那条 —— 它恰恰是最该被看见的。
                    #   所以这行在 break 之前：断在第 2 轮时，第 2 轮那行不能消失。
                    # ★★ 缩进必须在这一层（轮次），不是上一层（重复）——
                    #   放到重复层时，行里显示的 `轮turn_i/turnCount` 会是
                    #   「循环结束时的那个值」（= 最后一轮），而 done 会一次跳好几格。
                    #   单轮集上这两个位置**打印结果完全一样**，所以这个 bug
                    #   在多轮冒烟里才现形。
                    flag = "✓" if not rec["error"] else "✗"
                    print("  [%4d/%4d] %s %-9s rep%d/%d 轮%d/%d %6sms http=%s intent=%-20s %s"
                          % (done, total_submissions, flag,
                             q["questionNo"], rep, args.repeat, turn_i, len(turns),
                             rec["elapsedMs"], rec["httpStatus"], rec["intent"],
                             rec["traceId"] or (rec["error"] or "")))
                    # ★ 全量一轮要 40 分钟以上。每 25 条报一次进度和剩余时间 ——
                    #   不报的话，跑了一半才发现「按这个速度要两个小时」就太晚了。
                    if done % 25 == 0 and done < total_submissions:
                        avg = (time.time() - t0) / done
                        print("      —— %d/%d，均 %.0fms，预计还需 %.1f 分钟 ——"
                              % (done, total_submissions, avg * 1000,
                                 (total_submissions - done) * avg / 60))

                    # ★ 会话断了就别再往下问：后面几轮各自开新会话，
                    #   产生的数据【看起来正常但语义全乱】—— 这是最坏的一种脏数据。
                    if rec["error"] or rec["traceId"] is None:
                        skipped += len(turns) - turn_i
                        break
                    session_no = rec["sessionNo"]
    except KeyboardInterrupt:
        interrupted = True
        print("\n★★ 被中断。已提交的部分会照常落盘并做对账 —— "
              "44 分钟的调用不该白丢。但本轮 complete=false，【不能】用于跨轮 diff。")

    # ── ④ 对账 ────────────────────────────────────────────────
    print("\n[4/5] 对账（以 qa_log 为准，走 psql 这个独立来源）...")
    rec = reconcile(run_id, submissions, [q["questionNo"] for q in questions], started_at)

    print("      提交 %d 条：拿到 traceId %d，没拿到 %d（HTTP 层就失败了）"
          % (rec["submitted"], rec["submittedWithTrace"], rec["submittedNoTrace"]))
    print("      ✅ 在 qa_log 里找到且标记相符    %d" % (rec["submittedWithTrace"] - rec["missingCount"]))
    print("      ❌ 找不到                        %d" % rec["missingCount"])
    if rec["missingCount"]:
        # ★ 这一条就是洞 4 的哨兵。缺行 = eval_run_id 在某条路径上丢了。
        print("         前 20 个：%s" % rec["missingTraces"])
        print("         ★★ 说明 eval_run_id 在某条 CallContext 路径上丢了 ——"
              "那些行正在冒充真实用户流量。查 ChatAdmissionService:242 / :765、"
              "ChatServiceImpl:1435 / :379。")
    print("      库里属于本轮的行走                %d（覆盖 %d 道题）"
          % (rec["rowsInRun"], rec["rowsByQuestion"]))
    print("         返回码分布 %s" % rec["rowsByStatus"])
    print("      ⚠️ 归不了题的幽灵行              %d（可解释 %d 条：没拿到 traceId 的提交）"
          % (rec["ghostRows"], min(rec["ghostRows"], rec["submittedNoTrace"])))
    print("      ★ 泄漏行（有题号但没 runId）      %d" % rec["leakedRows"])
    if rec["foreignRows"]:
        # ★ 不是失败项 —— 它按定义就不是我们的行（见 reconcile 里的说明）。
        #   报出来只是让人知道「这一分钟里还有另一轮在跑」。
        print("      （参考）别的 runId 的行            %d —— 同一时间窗里还有另一轮，"
              "与本轮无关，不计入判定" % rec["foreignRows"])
    print("      库里本轮花费 ≈ %.4f 元（客户端观测 %.4f 元）"
          % (rec["costInDb"], client_cost))
    # ★★★ 第四个哨兵：这一轮到底跑成了没有。
    #   上面那三条判据问的全是「行有没有丢」，没有一条问「行有没有成」——
    #   所以一轮 85% 的行是 503 的时候，对账照样说「通过」。
    #   ★ 无论好坏都要打印这一行。只在坏的时候打印的话，读的人**分不出**
    #     「检查跑了、结果是 0」和「检查根本没跑」—— 那正是本项目一直在防的形状。
    print("      %s 模型链路失败的行                %d / %d = %.1f%%（上限 %.0f%%）"
          % ("✅" if not rec["tooManyFailed"] else "⚠️",
             rec["badRows"], rec["rowsInRun"], rec["badRatio"] * 100,
             MAX_FAILED_RATIO * 100))
    if rec["tooManyFailed"]:
        print("         ★★★ **这一轮不可用** —— 失败的行不是随机丢的，"
              "是熔断器成簇跳闸时整段丢的；")
        print("             活下来的那些行在时间上和题号顺序上都偏向某一端，"
              "**不是一个无偏的子样本**。")
        print("             先查为什么跳闸（`UnresolvedAddressException` = DNS / 网络；"
              "`curl /actuator/circuitbreakers` 看状态），再重跑。")
        print("             ⚠️ 别拿它去比 —— 报告里那 159 题的分母会静默变成几十题。")
    if interrupted:
        print("\n      ⚠️ 本轮被中断，对账只覆盖已提交的部分。")
    print("\n" + ("      ★★ 对账通过" if rec["ok"] else "      ★★ 对账【不通过】—— 整轮数据作废，重跑"))
    if not rec["ok"] and not interrupted:
        print("         不通过的原因见上面几行。先修，再重跑。"
              "现在的这份数据说不清来源，用它算出来的任何数字都不可信。")

    # ── ⑤ 落盘 ────────────────────────────────────────────────
    complete = (not interrupted) and rec["ok"]
    report = {
        "runId": run_id,
        "complete": complete,
        "interrupted": interrupted,
        "reconcileOk": rec["ok"],
        "startedAt": started_at.isoformat(timespec="seconds"),
        "finishedAt": now_iso(),
        "questionSet": args.set,
        "repeat": args.repeat,
        "limitedTo": args.limit or None,
        "base": BASE,
        "config": config,
        "fingerprint": fingerprint,
        "bank": {k: bank.get(k) for k in
                 ("count", "inserted", "updated", "bySet", "byCategory",
                  "byIntent", "emptyGold", "multiTurn", "missingNotes")},
        "submissions": submissions,
        "skippedTurns": skipped,
        "clientCost": round(client_cost, 4),
        "reconcile": rec,
        "caveats": [
            "★ 本文件只记【调度侧的事实】：我发了什么、服务端回了我什么、"
            "对账结论。**指标本体不在这里** —— 它在 qa_log 里，由 T4 的"
            "/api/debug/eval/report 从库里算。同一份数据存两处的漂移是静默的。",
            "★★ 意图准确率【不来自本文件】。分类器只看那一句话，"
            "由 scripts/eval_intent_probe.py 高重复单独测（更便宜、样本更多、"
            "而且天然没有澄清闸门带来的幸存者偏差）。",
            "★ 检索/答案侧指标取「第一次 status=1 的那次」——"
            "不是第 1 次，也不是任取一次（那会让被闸门随机挡掉的题丢失）。",
            "★ 多轮题的每一轮都带同一个题号（只有末轮是 gold 那一轮），"
            "轮次靠 traceId 还原（submissions 里逐条记了）。",
            "★ 真值以 qa_log 为准。本文件里的 httpStatus / elapsedMs 是"
            "客户端观测，和 qa_log 的 total_latency_ms 不是一回事。",
        ],
    }

    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "run.raw.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n[5/5] ✅ %s" % path)
    print("      complete=%s  （★ complete=false 的 run 不能进 T6 的 A/B 对比）" % complete)
    print("      总耗时 %.1f 分钟" % ((time.time() - t0) / 60))
    return 0 if complete else 1


if __name__ == "__main__":
    sys.exit(main())
