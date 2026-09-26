# -*- coding: utf-8 -*-
"""
T4 的验证：把 Java 端点算出来的指标，用 Python <b>独立地</b>再算一遍，两边对拍。

★ 对拍是什么，不是什么

    它**不是**「再算一遍确认自己没错」—— 那是同一份思路走两遍，错的地方会一起错。
    它是**用第二个实现去撞第一个**。两个独立实现同时犯同一个错的概率，
    远小于一个实现犯错。

★★ 所以两边必须是**真的独立**：

    数据来源    Java 走 MyBatis，Python 走 psql —— 两条完全不同的路
    意图树      ★★ **只有一处**：Python 从 `/api/debug/agent/intent-tree` 拿 Java
                **已经解析好的** doc_types，绝不自己去读 intent-tree.yml。
                自己读一次就是第二个事实来源，而它会和第一个说不同的话 ——
                症状是「对拍失败」但没人知道该信哪边（两边看起来都对）
    分位数      两边都实现最近秩（nearest-rank），算法写在各自的注释里

★★ 反对照（`--selftest`）

    对拍脚本最危险的失败模式是**它永远说 OK** —— 一个写错的比较器
    （比如键名写错导致两边都取到 None）会安静地报告「全部一致」。
    所以这个脚本能对自己做反对照：人为改掉 Java 报告里的一个数，
    断言比较器**必须**报出差异。改一个不报差异 = 比较器坏了。

用法：
    python scripts/eval_report_check.py --run 20260921-stage7
    python scripts/eval_report_check.py --run 20260921-stage7 --selftest
"""

from __future__ import annotations

import argparse
import copy
import json
import math
import subprocess
import sys
import urllib.parse
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
except Exception:
    pass

BASE = "http://localhost:8080"

# ★ 和 eval_run.py 逐字一致。走 docker exec 而不是 psycopg2：
#   ① Python 侧刻意零第三方依赖 ② 对账必须是独立来源
PSQL = ["docker", "compose", "exec", "-T", "-e", "PGCLIENTENCODING=UTF8",
        "postgres", "psql", "-U", "xbla", "-d", "xbla_rag", "-tAc"]

# ★ 和 EvalReportService.MIN_SLICE_N 一致。两边不一致的话，
#   「可信」那一格会对不上 —— 而它恰恰是个布尔，对不上非常好发现
MIN_SLICE_N = 5


# ================================================================
# 取数
# ================================================================

def _get_json(url: str, timeout: int = 180):
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def psql_json(sql: str, what: str):
    proc = subprocess.run(PSQL + [sql], capture_output=True, timeout=120)
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
    return "'" + str(value).replace("'", "''") + "'"


def fetch_rows(run_id: str):
    """这一轮的全部 qa_log 行 + JOIN 出来的 gold。

    ★ ORDER BY 里那个 id 不是装饰：「第一次 status=1 的那次」唯一的载体
      就是写入顺序。只按题号排的话，同题几行的相对顺序由数据库自由决定 ——
      现象是「同一份数据两次跑出不同的检索指标」，且不报错。
    """
    sql = """
    SELECT coalesce(json_agg(x ORDER BY x.question_no, x.id), '[]'::json) FROM (
      SELECT q.id,
             q.eval_question_no  AS question_no,
             q.intent,
             q.status,
             q.retrieval_detail,
             -- ★ 阶段 9.6：决策那两段要的三列。
             --   session_id 是多轮分组的唯一依据；intent_plan / tool_calls
             --   是「该不该」与「做没做」的两个来源。
             q.intent_plan,
             q.tool_calls,
             q.session_id,
             q.total_latency_ms,
             q.retrieval_latency_ms,
             q.rerank_latency_ms,
             q.llm_latency_ms,
             q.queue_ms,
             e.intent                AS gold_intent,
             e.expected_chunk_ids    AS gold_ids,
             e.expect_no_retrieval,
             -- ★ 多轮题的轮数 —— 判「这次尝试完不完整」要用它
             e.turns,
             -- ★ ①「首轮该不该反问」的 gold（V17）。★ 三态：NULL = 没标注 ⇒ 不可判
             e.expect_clarify,
             -- ★ 「这道题在不在题库里」必须【显式】查出来。用
             --   gold_intent IS NULL 去推是不安全的：那是一个【恰好】
             --   成立的等价（yml 里 intent 必填），而「恰好成立」的等价
             --   在规则变一次之后就静默失效了
             (e.question_no IS NOT NULL) AS in_bank
      FROM qa_log q
      LEFT JOIN eval_question e ON e.question_no = q.eval_question_no
      WHERE q.eval_run_id = %s
    ) x
    """ % sql_literal(run_id)
    return psql_json(sql, "取 qa_log 行") or []


def fetch_chunk_doc_types():
    sql = """
    SELECT coalesce(json_agg(json_build_object('id', id, 't', doc_type)), '[]'::json)
    FROM kb_chunk WHERE deleted = 0
    """
    rows = psql_json(sql, "取 kb_chunk 对照表") or []
    return {int(r["id"]): int(r["t"]) for r in rows}


# ================================================================
# 意图树（★ 不重新解析 yml —— 只读 Java 解析好的结果）
# ================================================================

class Tree:
    """`/api/debug/agent/intent-tree` 的运行期视图。

    ★★ 这里做的两件事都是在**镜像 Java 的语义** —— 而那是这套对拍里
       唯一一处「两边必须一样」的地方，所以把规则写出来：

         docTypesOf(code)    只看**叶子**；顶层码和未知码一律返回**空集**
         retrievalOf(code)   顶层码查自己；叶子码**继承父顶层**的 retrieval

       Java 那边改了这两条规则而这里没跟着改 → 对拍会红。
       ★ 那是**好事**：它说明这两个实现真的在互相检查，而不是各说各话。
    """

    def __init__(self, payload: dict):
        self.leaf_doc_types: dict[str, set[int]] = {}
        self.retrieval: dict[str, str] = {}
        self.role: dict[str, str] = {}
        self.business_codes: set[str] = set()
        # ★ 阶段 9.6：工具白名单。★ 端点在为空时【省掉这个键】，
        #   所以 `.get(code, [])` 同时覆盖「声明了空」和「压根没有」——
        #   而 Java 那边两者都是空列表，语义一致（见 IntentTree#toolsOf）
        self.tools_top: dict[str, list] = {}
        self.tools_leaf: dict[str, list] = {}
        # ★ 顶层码的集合 —— is_target() 要用。★ 不能靠 `retrieval` 里有没有它
        #   来判断：叶子也在那张表里（它们继承父顶层的 retrieval）
        self.top_codes: set[str] = set()
        self.top_retrieval: dict[str, str] = {}
        for top in payload["topLevel"]:
            self.top_codes.add(top["code"])
            self.top_retrieval[top["code"]] = top["retrieval"]
            self.retrieval[top["code"]] = top["retrieval"]
            self.role[top["code"]] = top["role"]
            if top.get("tools"):
                self.tools_top[top["code"]] = list(top["tools"])
            if top["role"] == "BUSINESS":
                self.business_codes.add(top["code"])
            for leaf in top["leaves"]:
                self.leaf_doc_types[leaf["code"]] = set(leaf["docTypes"] or [])
                self.retrieval[leaf["code"]] = top["retrieval"]
                if leaf.get("tools"):
                    self.tools_leaf[leaf["code"]] = list(leaf["tools"])
                if top["role"] == "BUSINESS":
                    self.business_codes.add(leaf["code"])

        self.clarify = next((t["code"] for t in payload["topLevel"]
                             if t["role"] == "CLARIFY"), None)
        self.out_of_scope = next((t["code"] for t in payload["topLevel"]
                                  if t["role"] == "OUT_OF_SCOPE"), None)

    def doc_types_of(self, code) -> set[int]:
        return self.leaf_doc_types.get(code, set())

    def retrieval_of(self, code):
        return self.retrieval.get(code)

    def tools_of(self, code) -> list:
        """镜像 `IntentTree.Tree#toolsOf`：**先查顶层，再查叶子**。

        ★ 两步都要有，而且顺序不能反：TOOL 类意图的分类落点是**顶层**
          （`ORDER_LOGISTICS`），而混合轮的 KB 叶子（`SCENARIO_PICK`）
          把 `tools` 声明在**叶子**上。
        """
        if not code:
            return []
        if code in self.tools_top:
            return self.tools_top[code]
        return self.tools_leaf.get(code, [])

    def is_target(self, code) -> bool:
        """镜像 `Tree#findTarget` —— 这条 code 是不是**模型能输出**的分类目标。

        ★ 规则只有一条「行为相同的不区分」：
            `retrieval = KB` 的顶层 ⇒ 展开到**叶子**（叶子之间 doc_types 不同）
            `retrieval ≠ KB` 的顶层 ⇒ 只算**一个**目标，用**顶层 code**

        ★ 它在这套对拍里承重：`intent_plan.resumed` 那一层判「落点对不对」时，
          gold 若不是合法目标，这一格是【不可判】而不是【错】。
        """
        if not code:
            return False
        if code in self.top_codes:
            return self.top_retrieval.get(code) != "KB"
        return code in self.leaf_doc_types and self.retrieval.get(code) == "KB"


# ================================================================
# 分位数
# ================================================================

def percentile(values, p):
    """最近秩：升序后取第 ceil(p*n) 个，**不插值**。和 Java 侧同一套。

    ★ 不插值是因为插值会算出一个**没有任何一次请求达到过**的数，
      而报告里那个数会被人当成「用户的实际体验」。
    """
    vals = sorted(v for v in values if v is not None)
    if not vals:
        return None
    rank = math.ceil(p * len(vals))
    return vals[min(len(vals) - 1, max(0, rank - 1))]


def mode_of(votes: list):
    """众数。★ 平票返回 None —— **不取第一个**，因为那两种结果各投一票时
    取谁都是错的，而「没有共识」本身是一个可以被数出来的事实。"""
    if not votes:
        return None
    counts = {}
    for v in votes:
        counts[v] = counts.get(v, 0) + 1
    best = max(counts.values())
    winners = [k for k, c in counts.items() if c == best]
    return winners[0] if len(winners) == 1 else None


def ratio(num, den):
    return {"n": den, "命中": num,
            "值": (None if den == 0 else num / den),
            "可信": den >= MIN_SLICE_N}


# ================================================================
# 独立算一遍
# ================================================================

def compute(run_id: str):
    rows = fetch_rows(run_id)
    chunk_dt = fetch_chunk_doc_types()
    tree = Tree(_get_json(BASE + "/api/debug/agent/intent-tree"))

    by_q: dict[str, list] = {}
    for r in rows:
        by_q.setdefault(r["question_no"] or "(无题号)", []).append(r)
    by_q.pop("(无题号)", None)

    out = {}

    # ── 每题视图 ──────────────────────────────────────────────
    views = []
    for no in sorted(by_q):
        rs = by_q[no]
        gold_ids = [int(i) for i in (rs[0]["gold_ids"] or [])]
        votes = [r["intent"] for r in rs if r["intent"] is not None]
        first_ok = next((r for r in rs if r["status"] == 1), None)
        ordinal = next((i + 1 for i, r in enumerate(rs) if r["status"] == 1), 0)
        statuses = [r["status"] for r in rs if r["status"] is not None]
        views.append({
            "no": no,
            "rows": rs,
            "in_bank": bool(rs[0]["in_bank"]),
            "gold": rs[0]["gold_intent"],
            "gold_ids": gold_ids,
            "gold_types": {chunk_dt[i] for i in gold_ids if i in chunk_dt},
            "mode": mode_of(votes),
            "unanimous": len(set(votes)) <= 1,
            "tied": mode_of(votes) is None and bool(votes),
            "first_ok": first_ok,
            "ordinal": ordinal,
            "no_retrieval": bool(rs[0]["expect_no_retrieval"]),
            "status_modes": statuses,
        })

    # ── 意图 ──────────────────────────────────────────────────
    row_total = sum(1 for v in views for r in v["rows"] if r["intent"] is not None)
    row_hit = sum(1 for v in views for r in v["rows"]
                  if r["intent"] is not None and r["intent"] == v["gold"])
    with_gold = [v for v in views if v["gold"]]
    q_hit = sum(1 for v in with_gold if v["mode"] is not None and v["mode"] == v["gold"])

    out["意图"] = {
        "逐行": ratio(row_hit, row_total),
        "逐题取众数": ratio(q_hit, len(with_gold)),
        "摇摆数": sum(1 for v in with_gold if not v["unanimous"]),
        "摇摆题号": sorted(v["no"] for v in with_gold if not v["unanimous"]),
        "无众数数": sum(1 for v in with_gold if v["tied"]),
    }

    # ── 澄清边界 ──────────────────────────────────────────────
    clarify = tree.clarify
    should = did = missing = fp = 0
    not_should = 0
    for v in views:
        if not v["gold"]:
            continue
        wants = (v["gold"] == clarify)
        for r in v["rows"]:
            acted = (r["status"] == 3)
            if wants:
                should += 1
                did += 1 if acted else 0
                missing += 0 if acted else 1
            else:
                not_should += 1
                fp += 1 if acted else 0
    tp = fn = q_fp = tn = 0
    for v in with_gold:
        wants = (v["gold"] == clarify)
        st = v["status_modes"]
        clar = sum(1 for s in st if s == 3)
        m = None if not st or clar * 2 == len(st) else (clar * 2 > len(st))
        if wants:
            tp += 1 if m else 0
            fn += 0 if m else 1
        else:
            q_fp += 1 if m else 0
            tn += 0 if m else 1
    out["澄清边界"] = {
        "逐行": {"该反问_且反问": did, "该反问_没反问": missing, "假阳": fp,
                 "不该反问_没反问": not_should - fp,
                 "分母_该反问": should, "分母_不该反问": not_should},
        "逐题": {"该反问_且反问": tp, "该反问_没反问": fn, "假阳": q_fp,
                 "不该反问_没反问": tn, "分母": len(with_gold)},
    }

    # ── 检索范围 ──────────────────────────────────────────────
    kb_n = kb_hit = 0
    all_n = all_hit = 0
    for v in views:
        if not v["gold"] or v["mode"] is None:
            continue
        all_n += 1
        equal = tree.doc_types_of(v["gold"]) == tree.doc_types_of(v["mode"])
        all_hit += 1 if equal else 0
        if tree.retrieval_of(v["gold"]) == "KB":
            kb_n += 1
            kb_hit += 1 if equal else 0
    out["检索范围"] = {"KB题": ratio(kb_hit, kb_n), "全体": ratio(all_hit, all_n)}

    # ── 决策三段（阶段 9.6）───────────────────────────────────
    #
    # ★★ 判据【逐字镜像】Java 的 EvalReportService，三条都不能记错：
    #
    #    「实际检索了」 = retrieval_detail 非空 —— 【不是】intent_plan.retrieve。
    #                    后者是门控自己的输出，拿它当判据会漏掉
    #                    「算对了但调用点忘了用」那类只改一半的实现
    #    「算一次决策」 = status 非空且 ≠ 4 —— 被限流拒掉的那条路上检索压根没跑，
    #                    把它算成「决定不检索」会让过度检索率凭空归零
    #    「该有工具」   = retrieval_of(gold) == TOOL ∨ tools_of(gold) 非空
    #                    （前者覆盖 TOOL 类的叶子、后者覆盖混合轮的 KB 叶子）
    #
    # ★ 这三段存在的意义就是「**独立**再算一遍」。所以下面刻意不 import
    #   EvalReportService 的任何东西，也不复用上面那几段的中间量。

    def _plan(r):
        """★★ `fetch_rows` 里 jsonb 列经 `json_agg` 出来【已经是对象】，不是字符串。

        ★★★ 第一版写的是 `json.loads(r["intent_plan"])`。对 dict 调 `json.loads`
        抛的是 `TypeError`，而那个 `except` 把它吞了 ⇒ **每一行都读成「没有计划」**。
        症状是「Python 侧全是 0」—— 一个**静默的、看起来合法的 0**：
        对拍会报「25 处不一致」，但每一处都指向「两边算的数不同」，
        而真相是**这一边什么都没读到**。
        ⚠️ 所以这里【不回退】：形状不对就抛，别把一个读不到的字段说成「没有」。
        （同 ADR-092 的口径：解析失败不回退到另一条路。）
        """
        v = r.get("intent_plan")
        if v is None or v == "":
            return None
        if isinstance(v, dict):
            return v
        raise ValueError("intent_plan 的形状变了：期望 dict，拿到 %s" % type(v).__name__)

    def _plan_tools(p):
        v = (p or {}).get("tools")
        return [str(x) for x in v] if isinstance(v, list) else []

    def _tool_calls(r):
        """同 `_plan`：`tool_calls` 是 jsonb，出来就是 list（或 NULL）。"""
        v = r.get("tool_calls")
        if v is None or v == "":
            return []
        if isinstance(v, list):
            return [c for c in v if isinstance(c, dict)]
        raise ValueError("tool_calls 的形状变了：期望 list，拿到 %s" % type(v).__name__)

    def _call_names(r):
        return [str(c.get("tool")) for c in _tool_calls(r)]

    def _call_errors(r):
        return [bool(c.get("isError")) for c in _tool_calls(r)]

    def _turns(v):
        """这道题在题库里声明的轮次列表。★ 空列表 = 单轮题。

        ⚠️ `eval_question.turns` 是 jsonb ⇒ `fetch_rows` 出来**已经是 list**。
        第一版写的是 `json.loads(...)`，对 list 抛 `TypeError` 被吞掉
        ⇒ **每一道多轮题都被 `continue` 掉**，整节读成 0。
        ★ 同一个形状错在 9.6a 的这段代码里犯了**三次**（`intent_plan` /
        `tool_calls` / `turns`）—— 因为三处都照着「JSON 列是字符串」的直觉写。
        判据是**列的类型**，不是直觉。
        """
        tv = (v["rows"][0].get("turns") if v["rows"] else None)
        if tv is None or tv == "":
            return []
        if isinstance(tv, list):
            return tv
        raise ValueError("turns 的形状变了：期望 list，拿到 %s" % type(tv).__name__)

    def _decided(r):
        return r["status"] is not None and r["status"] != 4

    def _retrieved(r):
        return bool(r["retrieval_detail"])

    def _wants_tools(gold):
        return bool(gold) and (tree.retrieval_of(gold) == "TOOL" or bool(tree.tools_of(gold)))

    def _cells():
        return {"不检索_没检索": 0, "不检索_检索了": 0,
                "要检索_检索了": 0, "要检索_没检索": 0}

    def _tool_cells():
        return {"该有_调了": 0, "该有_没调": 0, "不该有_没调": 0, "不该有_调了": 0}

    # ── 检索决策 ──
    rd_row, rd_q = _cells(), _cells()
    rd_row_den = {"不检索": 0, "要检索": 0}
    rd_q_den = {"不检索": 0, "要检索": 0}
    rd_skipped = rd_no_bank = rd_nomode = 0
    rd_clarify = 0
    # ★★★ 六支 gate 先全部填 0 —— Java 侧同样这么做。不填的话
    #   「这一支没被走到」只是【缺席】，和「没有这一支」长得一样。
    #   ⚠️ 这份清单要跟着 `RetrievalGate.REASON_*` 走：Java 多一支而这里没加，
    #     对拍会报出来（那一支 Java=0 / Python=None）—— 那正是它该报的。
    rd_gate = {k: 0 for k in ("KB", "PLAN_OFF", "TOOL",
                              "NONE_INTENT", "NONE_DISABLED", "NO_CLASSIFY")}
    rd_shape = {}
    rd_ignored, rd_flipped = [], []
    for v in views:
        if not v["in_bank"]:
            rd_no_bank += len(v["rows"])
            continue
        wants_no = v["no_retrieval"]
        votes = []
        for r in v["rows"]:
            if not _decided(r):
                rd_skipped += 1
                continue
            got = _retrieved(r)
            key = "不检索" if wants_no else "要检索"
            rd_row_den[key] += 1
            rd_row["%s_%s" % (key, "没检索" if not got else "检索了")] += 1
            # ★★ 「声明要检索而没检索」里混着【对的澄清短路】和【真的关太狠】
            if not wants_no and not got and r["status"] == 3:
                rd_clarify += 1
            votes.append("检索" if got else "不检索")
            p = _plan(r)
            shape = "无计划" if p is None else str(p.get("shape") or "(缺这一格)")
            rd_shape[shape] = rd_shape.get(shape, 0) + 1
            if p is not None:
                g = str(p.get("gate") or "(缺这一格)")
                rd_gate[g] = rd_gate.get(g, 0) + 1
                if p.get("retrieve") is False and got:
                    rd_ignored.append(v["no"])
        if not votes:
            continue
        verdict = mode_of(votes)
        if verdict is None:
            rd_nomode += 1
            continue
        key = "不检索" if wants_no else "要检索"
        rd_q_den[key] += 1
        rd_q["%s_%s" % (key, "没检索" if verdict == "不检索" else "检索了")] += 1
        # ★★ 排除多轮题：它的几行是【不同的轮次】，不是重复测量。
        #    不排的话首轮「澄清短路不检索 ⇒ 不检索」和第二轮「检索」会被读成
        #    「同一句话跨次翻转」—— 实测报过 5 道纯假阳性（Java 侧同样的判据）。
        if len(set(votes)) > 1 and not _turns(v):
            rd_flipped.append(v["no"])
    out["检索决策"] = {
        "四格逐行": dict(rd_row, **{("分母_%s" % k): n for k, n in rd_row_den.items()}),
        "四格逐题": dict(rd_q, **{("分母_%s" % k): n for k, n in rd_q_den.items()}),
        "平票题数": rd_nomode,
        "gate分布": rd_gate,
        "shape分布": rd_shape,
        "算对了没用": sorted(rd_ignored),
        "翻转题号": sorted(rd_flipped),
        "没算成决策": rd_skipped,
        "题不在题库": rd_no_bank,
        "澄清短路": rd_clarify,
    }

    # ── 工具调用 ──
    tc_row, tc_q = _tool_cells(), _tool_cells()
    tc_row_den = {"该有": 0, "不该有": 0}
    tc_q_den = {"该有": 0, "不该有": 0}
    tc_silent, tc_over, tc_over_ok = [], [], []
    tc_dist, tc_err, tc_no_plan = {}, 0, 0
    for v in views:
        if not v["in_bank"]:
            continue
        wants = _wants_tools(v["gold"])
        votes = []
        for r in v["rows"]:
            names, errs = _call_names(r), _call_errors(r)
            called = bool(names)
            key = "该有" if wants else "不该有"
            tc_row_den[key] += 1
            tc_row["%s_%s" % (key, "调了" if called else "没调")] += 1
            votes.append("调了" if called else "没调")
            p = _plan(r)
            if p is None:
                tc_no_plan += 1
            offered = _plan_tools(p)
            if offered and not called:
                tc_silent.append(v["no"])
            for i, name in enumerate(names):
                tc_dist[name] = tc_dist.get(name, 0) + 1
                if i < len(errs) and errs[i]:
                    tc_err += 1
                if name not in offered:
                    tc_over.append("%s:%s" % (v["no"], name))
                    if i < len(errs) and not errs[i]:
                        tc_over_ok.append("%s:%s" % (v["no"], name))
        if not votes:
            continue
        verdict = mode_of(votes)
        if verdict is None:
            continue
        key = "该有" if wants else "不该有"
        tc_q_den[key] += 1
        tc_q["%s_%s" % (key, "调了" if verdict == "调了" else "没调")] += 1
    out["工具调用"] = {
        "四格逐行": dict(tc_row, **{("分母_%s" % k): n for k, n in tc_row_den.items()}),
        "四格逐题": dict(tc_q, **{("分母_%s" % k): n for k, n in tc_q_den.items()}),
        "静默降级行数": len(tc_silent),
        "越权条数": len(tc_over),
        "越权且成功条数": len(tc_over_ok),
        "工具分布": tc_dist,
        "isError条数": tc_err,
        "没有计划的行": tc_no_plan,
    }

    # ── 多轮澄清（三层）──
    #
    # ★ 轮次边界靠 session_id 分组还原 —— 每次重复新建一个会话，
    #   所以「同题号 + 同 session」就是一次完整尝试，组内按 id 排就是轮次顺序。
    mt = {"分母_完整的尝试": 0, "不完整": 0, "没有session": 0,
          "分母_①可判": 0, "没有gold": 0,
          "①该反问_反问了": 0, "①该反问_没反问": 0,
          "①不该反问_没反问": 0, "①不该反问_反问了": 0,
          "末轮带上状态": 0, "末轮落点": 0, "三层全过": 0,
          "没有v3": 0, "末轮命中分母": 0, "末轮命中": 0}
    for v in views:
        if not v["in_bank"]:
            continue
        turns = _turns(v)
        if not turns:
            continue
        gold = v["gold"]
        by_sess = {}
        for r in v["rows"]:
            if r.get("session_id") is None:
                mt["没有session"] += 1
                continue
            by_sess.setdefault(r["session_id"], []).append(r)
        for _sid, rs in by_sess.items():
            rs = sorted(rs, key=lambda r: r["id"])
            if len(rs) != len(turns):
                # ★ 会话断在中途 ⇒ 这次尝试【不进任何分子分母】。
                #   先加进分母再减掉是错的写法：中间那一刻它已经在分母里了，
                #   将来有人在 continue 之前插一句就会静默改变分母。
                mt["不完整"] += 1
                continue
            mt["分母_完整的尝试"] += 1
            first, last = rs[0], rs[-1]
            p = _plan(last)
            if p is not None and "resumed" not in p:
                mt["没有v3"] += 1
            asked = first["status"] == 3
            resumed = bool(p.get("resumed")) if p else False
            landed = bool(gold) and gold == last["intent"] and tree.is_target(gold)
            # ★★ ②③ 的分母是【完整的尝试】，不受 ① 有没有 gold 影响
            mt["末轮带上状态"] += 1 if resumed else 0
            mt["末轮落点"] += 1 if landed else 0

            # ★★ ① 的 gold 只认显式标注（V17）—— 多轮题的 intent 指【末轮】，
            #    拿它给首轮下结论必然错，所以【不猜】，判不可判。
            wants = rs[0].get("expect_clarify")
            if wants is None:
                mt["没有gold"] += 1
            else:
                mt["分母_①可判"] += 1
                if wants:
                    mt["①该反问_反问了" if asked else "①该反问_没反问"] += 1
                else:
                    mt["①不该反问_反问了" if asked else "①不该反问_没反问"] += 1
                # ★ 「三层全过」的 ① 那一半是【判对了】，不是「反问了」
                if asked == bool(wants) and resumed and landed:
                    mt["三层全过"] += 1
            if last["status"] == 1 and v["gold_ids"]:
                mt["末轮命中分母"] += 1
                ctx = list((last["retrieval_detail"] or {}).get("final_top_k") or [])
                if set(v["gold_ids"]) & set(int(c) for c in ctx[:5]):
                    mt["末轮命中"] += 1
    den = mt["分母_完整的尝试"]
    out["多轮澄清"] = {
        "分母": den,
        "不完整": mt["不完整"],
        "没有session": mt["没有session"],
        "分母_①可判": mt["分母_①可判"],
        "①没有gold": mt["没有gold"],
        "①该反问_反问了": mt["①该反问_反问了"],
        "①该反问_没反问": mt["①该反问_没反问"],
        "①不该反问_没反问": mt["①不该反问_没反问"],
        "①不该反问_反问了": mt["①不该反问_反问了"],
        # ★ ②③ 的分母是完整尝试；「三层全过」的是 ①可判 —— 两个分母不同是刻意的
        "末轮带上状态": ratio(mt["末轮带上状态"], den),
        "末轮落点": ratio(mt["末轮落点"], den),
        "三层全过": ratio(mt["三层全过"], mt["分母_①可判"]),
        "末轮命中": ratio(mt["末轮命中"], mt["末轮命中分母"]),
        "没有v3": mt["没有v3"],
    }

    # ── 槽位声明（`missing` 的去向）──
    #   ★ 独立复算：只读 `status` 与 `intent_plan.missing` 两样，
    #     不碰 Java 侧任何中间量（尤其**不读 gate** —— 用 gate 去分组
    #     等于把 Java 的判据抄一遍，那样对拍就成了自证）。
    sd_rows = sd_no_plan = sd_declared = sd_asked = sd_answered = 0
    sd_asked_all = sd_asked_no_missing = 0
    sd_nos = []
    for v in views:
        for r in v["rows"]:
            sd_rows += 1
            p = _plan(r)
            if p is None:
                sd_no_plan += 1
                continue
            miss = p.get("missing")
            has = isinstance(miss, list) and len(miss) > 0
            acted = r["status"] == 3
            if acted:
                sd_asked_all += 1
            if has:
                sd_declared += 1
                if acted:
                    sd_asked += 1
                else:
                    sd_answered += 1
                    if v["no"] not in sd_nos:
                        sd_nos.append(v["no"])
            elif acted:
                sd_asked_no_missing += 1
    out["槽位声明"] = {
        "分母_行": sd_rows,
        "没有计划": sd_no_plan,
        "分母_声明缺槽位": sd_declared,
        "澄清分支_反问": sd_asked,
        "业务码_作答": sd_answered,
        "分母_反问": sd_asked_all,
        "没缺槽位却反问": sd_asked_no_missing,
        # ★ 两侧都**排序**：这一侧是按题遍历独立构建的，靠「恰好遍历顺序一致」
        #   来相等 = 一个会静默失效的等价（而失效的样子是「复算不一致」，
        #   把人引向数据而不是序）。
        "声明了却没反问的题": sorted(sd_nos),
    }

    # ── 检索命中率 ────────────────────────────────────────────
    first_try = later = never = 0
    ctx_total = 0
    hit1 = hit3 = hit5 = 0
    recall_sum = 0.0
    mrr_sum = 0.0
    measured = 0
    for v in views:
        if not v["in_bank"] or v["no_retrieval"]:
            continue
        if v["first_ok"] is None:
            never += 1
            continue
        if v["ordinal"] == 1:
            first_try += 1
        else:
            later += 1
        if not v["gold_ids"]:
            continue
        ctx = list((v["first_ok"]["retrieval_detail"] or {}).get("final_top_k") or [])
        ctx = [int(c) for c in ctx]
        measured += 1
        g = set(v["gold_ids"])
        hit1 += 1 if g & set(ctx[:1]) else 0
        hit3 += 1 if g & set(ctx[:3]) else 0
        hit5 += 1 if g & set(ctx[:5]) else 0
        # ★ 按【上下文条数】数，不是按命中的 gold 条数 —— 和 Java 的
        #   `ctx.stream().limit(5).filter(gold::contains).count()` 逐字对应。
        #   ctx 里若有重复 id，两个实现必须一起算重，否则对拍会在这一格上
        #   报出一个「两边都没错」的差异
        recall_sum += sum(1 for c in ctx[:5] if c in g) / len(g)
        rr = 0.0
        for i, c in enumerate(ctx[:5], 1):
            if c in g:
                rr = 1.0 / i
                break
        mrr_sum += rr
    out["检索"] = {
        "分母": measured, "HitRate@5": ratio(hit5, measured),
        "HitRate@1": ratio(hit1, measured), "HitRate@3": ratio(hit3, measured),
        "Recall@5": (None if not measured else recall_sum / measured),
        "MRR@5": (None if not measured else mrr_sum / measured),
        "第一次": first_try, "第二三次": later, "全被挡": never,
    }

    # ── 归因 ──────────────────────────────────────────────────
    # ★ 「分母」也一起对 —— 它数的是「有多少道题进了归因」，而桶的和
    #   必须等于它。少了这一格，两边的桶即使各自都对，也可能是在
    #   【不同的题目集合】上数出来的
    buckets = {"★分母": 0, "ok": 0, "filtered_out": 0, "not_recalled": 0,
               "beyond_record_cutoff": 0, "fusion_dropped": 0,
               "rerank_dropped": 0, "gold_missing": 0}
    for v in views:
        if not v["in_bank"] or v["no_retrieval"] or v["first_ok"] is None:
            continue
        if not v["gold_ids"]:
            continue
        d = v["first_ok"]["retrieval_detail"] or {}
        if not d:
            continue
        buckets["★分母"] += 1
        ctx = [int(c) for c in (d.get("final_top_k") or [])]
        g = set(v["gold_ids"])
        legs = {int(x["chunk_id"]) for x in (d.get("vector_hits") or [])} | \
               {int(x["chunk_id"]) for x in (d.get("keyword_hits") or [])}
        fused = {int(x["chunk_id"]) for x in (d.get("fused") or [])}

        if not v["gold_types"]:
            buckets["gold_missing"] += 1
            continue

        filt = d.get("filter") or {}
        applied = bool(filt.get("applied"))
        allowed = set(filt.get("doc_types") or [])
        if applied and allowed:
            inside = len(v["gold_types"] & allowed)
            verdict = "full" if inside == 0 else (
                "partial" if inside < len(v["gold_types"]) else "none")
        else:
            verdict = "none"

        if g & set(ctx):
            buckets["ok"] += 1
        elif verdict == "full":
            buckets["filtered_out"] += 1
        elif not (g & legs):
            buckets["not_recalled"] += 1
        elif not (g & fused):
            # ★ 只有能【证明】记录完整才敢说融合丢了它 —— 见 Java 侧同一段
            sizes = d.get("sizes")
            truncated = None
            if sizes is not None and sizes.get("fused") is not None:
                truncated = int(sizes["fused"]) > len(d.get("fused") or [])
            buckets["fusion_dropped" if truncated is False
                    else "beyond_record_cutoff"] += 1
        else:
            buckets["rerank_dropped"] += 1
    total = sum(v for k, v in buckets.items() if k != "★分母")
    assert total == buckets["★分母"], (
        "归因桶的和 %d != 分母 %d —— 有题落进了漏网" %
        (total, buckets["★分母"]))
    out["归因"] = buckets

    # ── 过度检索 ──────────────────────────────────────────────
    oob_a = tot_a = oob_b = tot_b = 0
    for v in views:
        if not v["in_bank"] or v["no_retrieval"] or v["first_ok"] is None:
            continue
        d = v["first_ok"]["retrieval_detail"] or {}
        if not d:
            continue
        exp_a = tree.doc_types_of(v["gold"])
        exp_b = tree.doc_types_of(v["mode"]) if v["mode"] else set()
        for c in (d.get("final_top_k") or []):
            dt = chunk_dt.get(int(c))
            if dt is None:
                continue
            tot_a += 1
            tot_b += 1
            oob_a += 0 if dt in exp_a else 1
            oob_b += 0 if dt in exp_b else 1
    out["过度检索"] = {"标注": (oob_a, tot_a), "分类": (oob_b, tot_b)}

    # ── 延迟 ──────────────────────────────────────────────────
    ok_rows = [r for r in rows if r["status"] == 1]
    out["延迟"] = {
        "样本": len(ok_rows),
        "被截掉": len(rows) - len(ok_rows),
        "total_p50": percentile([r["total_latency_ms"] for r in ok_rows], 0.50),
        "total_p95": percentile([r["total_latency_ms"] for r in ok_rows], 0.95),
        "retrieval_p50": percentile([r["retrieval_latency_ms"] for r in ok_rows], 0.50),
        "retrieval_p95": percentile([r["retrieval_latency_ms"] for r in ok_rows], 0.95),
        "rerank_p50": percentile([r["rerank_latency_ms"] for r in ok_rows], 0.50),
        "rerank_p95": percentile([r["rerank_latency_ms"] for r in ok_rows], 0.95),
        "llm_p50": percentile([r["llm_latency_ms"] for r in ok_rows], 0.50),
    }
    return out


# ================================================================
# 从 Java 报告里按同样的键取数
# ================================================================

def dig(obj, *keys):
    for k in keys:
        if obj is None:
            return None
        obj = obj.get(k) if isinstance(obj, dict) else None
    return obj


def from_java(report: dict):
    r = report
    out = {}
    out["意图"] = {
        "逐行": dig(r, "意图", "逐行"),
        "逐题取众数": dig(r, "意图", "逐题取众数"),
        "摇摆数": dig(r, "意图", "★非全票一致的题（主指标）", "n"),
        "摇摆题号": dig(r, "意图", "★非全票一致的题（主指标）", "题号"),
        "无众数数": dig(r, "意图", "无唯一众数的题（诊断）", "n"),
    }
    cb = dig(r, "澄清边界")
    out["澄清边界"] = {
        "逐行": {
            "该反问_且反问": dig(cb, "逐行", "★该反问_且反问了"),
            "该反问_没反问": dig(cb, "逐行", "★该反问_没反问（漏）"),
            "假阳": dig(cb, "逐行", "★不该反问_却反问了（假阳）"),
            "不该反问_没反问": dig(cb, "逐行", "不该反问_没反问"),
            "分母_该反问": dig(cb, "逐行", "分母_该反问的行"),
            "分母_不该反问": dig(cb, "逐行", "分母_不该反问的行"),
        },
        "逐题": {
            "该反问_且反问": dig(cb, "逐题（多数票）", "★该反问_且反问了"),
            "该反问_没反问": dig(cb, "逐题（多数票）", "★该反问_没反问（漏）"),
            "假阳": dig(cb, "逐题（多数票）", "★不该反问_却反问了（假阳）"),
            "不该反问_没反问": dig(cb, "逐题（多数票）", "不该反问_没反问"),
            "分母": dig(cb, "逐题（多数票）", "分母"),
        },
    }
    # ★★ 投影必须和 Python 侧【逐键对齐】。少取一个键的后果不是「少比一项」，
    #    而是那一项永远显示 Java=None / Python=X —— 也就是永远报「不一致」。
    #    反过来，如果两边都少取【同一个】键，那一项就永远不报 —— 比较器成了瞎的。
    #    ★ 这两种失败模式对拍脚本自己都看不出来，所以 --selftest 是必需的
    sc = dig(r, "检索范围")
    out["检索范围"] = {
        "KB题": {k: dig(sc, "★主数字_KB题", k) for k in ("n", "命中", "值", "可信")},
        "全体": {k: dig(sc, "全体", k) for k in ("n", "命中", "值", "可信")},
    }

    # ── 阶段 9.6：决策三段 ──
    #   ★ 这一层的键名是**内部短名**，和 Java 侧那串长键名（带 ★ 和全角括号）
    #     故意不同：长键名是给人读报告的，短名是给比较器用的。
    #     映射写在这里一处 —— 两处各写一份的话，改一个 ★ 就会让某项
    #     永远显示 Java=None / Python=X，也就是永远报「不一致」。
    rd, rdq = dig(r, "检索决策", "四格（逐行）") or {}, dig(r, "检索决策", "四格（逐题·多数票）") or {}
    out["检索决策"] = {
        "四格逐行": {
            "不检索_没检索": dig(rd, "★声明不检索_实际也没检索"),
            "不检索_检索了": dig(rd, "★声明不检索_实际检索了（过度检索）"),
            "要检索_检索了": dig(rd, "★声明要检索_实际检索了"),
            "要检索_没检索": dig(rd, "★声明要检索_实际没检索（过度关闭）"),
            "分母_不检索": dig(rd, "分母_声明不检索的行"),
            "分母_要检索": dig(rd, "分母_声明要检索的行"),
        },
        "四格逐题": {
            "不检索_没检索": dig(rdq, "★声明不检索_实际也没检索"),
            "不检索_检索了": dig(rdq, "★声明不检索_实际检索了（过度检索）"),
            "要检索_检索了": dig(rdq, "★声明要检索_实际检索了"),
            "要检索_没检索": dig(rdq, "★声明要检索_实际没检索（过度关闭）"),
            "分母_不检索": dig(rdq, "分母_声明不检索的题"),
            "分母_要检索": dig(rdq, "分母_声明要检索的题"),
        },
        "平票题数": dig(rdq, "平票的题数"),
        "gate分布": dict(dig(r, "检索决策", "gate分布") or {}),
        "shape分布": dict(dig(r, "检索决策", "shape分布") or {}),
        "算对了没用": sorted(dig(r, "检索决策", "★算对了但没用的行") or []),
        "翻转题号": sorted(x.split(" → ")[0]
                         for x in (dig(r, "检索决策", "★跨次决策翻转的题") or [])),
        "没算成决策": dig(r, "检索决策", "★没算成决策的行"),
        "题不在题库": dig(r, "检索决策", "★题不在题库里的行"),
        "澄清短路": dig(r, "检索决策", "★其中·澄清短路（不是错）"),
    }
    tc, tcq = dig(r, "工具调用", "四格（逐行）") or {}, dig(r, "工具调用", "四格（逐题·多数票）") or {}
    out["工具调用"] = {
        "四格逐行": {
            "该有_调了": dig(tc, "★该有工具_确实调了"),
            "该有_没调": dig(tc, "★该有工具_一次没调"),
            "不该有_没调": dig(tc, "★不该有工具_没调"),
            "不该有_调了": dig(tc, "★不该有工具_却调了"),
            "分母_该有": dig(tc, "分母_该有工具的行"),
            "分母_不该有": dig(tc, "分母_不该有工具的行"),
        },
        "四格逐题": {
            "该有_调了": dig(tcq, "★该有工具_确实调了"),
            "该有_没调": dig(tcq, "★该有工具_一次没调"),
            "不该有_没调": dig(tcq, "★不该有工具_没调"),
            "不该有_调了": dig(tcq, "★不该有工具_却调了"),
            "分母_该有": dig(tcq, "分母_该有工具的题"),
            "分母_不该有": dig(tcq, "分母_不该有工具的题"),
        },
        # ★ 这一层只比【条数】，不比那几串明细：明细里带 traceId / 题号拼接，
        #   两个实现的拼法本来就不必逐字相同，而逐字比它只会制造假差异。
        "静默降级行数": len(dig(r, "工具调用", "★给了工具却一次没调的行") or []),
        "越权条数": len(dig(r, "工具调用", "★越权的调用") or []),
        "越权且成功条数": len(dig(r, "工具调用", "★★越权且成功的调用") or []),
        "工具分布": dict(dig(r, "工具调用", "调用的工具分布") or {}),
        "isError条数": dig(r, "工具调用", "isError 的调用条数"),
        "没有计划的行": dig(r, "工具调用", "★没有计划的行"),
    }
    mt = dig(r, "多轮澄清") or {}
    out["多轮澄清"] = {
        "分母": dig(mt, "分母_完整的尝试"),
        "不完整": dig(mt, "★不完整的尝试"),
        "没有session": dig(mt, "★没有 session_id 的行"),
        "分母_①可判": dig(mt, "分母_①可判的尝试"),
        "①没有gold": dig(mt, "★★①没有 gold 的尝试"),
        "①该反问_反问了": dig(mt, "①该反问_反问了"),
        "①该反问_没反问": dig(mt, "①该反问_没反问（漏）"),
        "①不该反问_没反问": dig(mt, "①不该反问_没反问"),
        "①不该反问_反问了": dig(mt, "①不该反问_反问了（假阳）"),
        # ★ 只投影【数字键】—— 和上面几节同一个规矩。Java 的 ratio() 还会多一格
        #   `★`（"n<5 不作为结论"那句**给人读的注释**），Python 侧没有它。
        #   ⚠️ 整份 dict 拿去比会把那一格变成一条恒定的假差异（实测 4 处）。
        "末轮带上状态": {k: dig(mt, "②末轮带上了状态", k)
                     for k in ("n", "命中", "值", "可信")},
        "末轮落点": {k: dig(mt, "③末轮落点对了", k)
                   for k in ("n", "命中", "值", "可信")},
        "三层全过": {k: dig(mt, "★★三层全过", k)
                   for k in ("n", "命中", "值", "可信")},
        "末轮命中": {k: dig(mt, "末轮命中@5", k)
                   for k in ("n", "命中", "值", "可信")},
        "没有v3": dig(mt, "★计划里没有 resumed 这一格的尝试"),
    }
    sd = dig(r, "槽位声明") or {}
    out["槽位声明"] = {
        "分母_行": dig(sd, "分母_行"),
        "没有计划": dig(sd, "★其中·没有计划的行"),
        "分母_声明缺槽位": dig(sd, "分母_声明缺槽位的行"),
        "澄清分支_反问": dig(sd, "声明缺槽位_落在澄清分支（反问了）"),
        "业务码_作答": dig(sd, "★★声明缺槽位_落在业务码（直接作答）"),
        "分母_反问": dig(sd, "分母_反问的行"),
        "没缺槽位却反问": dig(sd, "★其中·没声明缺槽位却反问了"),
        "声明了却没反问的题": list(dig(sd, "★声明了却没反问的题") or []),
    }
    rt = dig(r, "检索")
    out["检索"] = {
        "分母": dig(rt, "分母", "★分母_测到的题数"),
        "HitRate@5": dig(rt, "HitRate@5"),
        "HitRate@1": dig(rt, "HitRate@1"),
        "HitRate@3": dig(rt, "HitRate@3"),
        "Recall@5": dig(rt, "Recall@5", "值"),
        "MRR@5": dig(rt, "MRR@5", "值"),
        "第一次": dig(rt, "★取到了第几次成功", "第一次就成功"),
        "第二三次": dig(rt, "★取到了第几次成功", "第二或第三次才成功"),
        "全被挡": dig(rt, "★取到了第几次成功", "★三次全被挡（不进任何检索分母）"),
    }
    out["归因"] = dict(dig(r, "归因", "桶") or {})
    ov = dig(r, "过度检索")
    out["过度检索"] = {
        "标注": (dig(ov, "★对标注叶子", "越界条数"),
                 dig(ov, "★对标注叶子", "上下文条数")),
        "分类": (dig(ov, "★对分类叶子", "越界条数"),
                 dig(ov, "★对分类叶子", "上下文条数")),
    }
    la = dig(r, "延迟")
    out["延迟"] = {
        "样本": dig(la, "样本_status=1的行"),
        "被截掉": dig(la, "★被status≠1截掉的条数"),
        "total_p50": dig(la, "total_latency_ms", "p50"),
        "total_p95": dig(la, "total_latency_ms", "p95"),
        "retrieval_p50": dig(la, "retrieval_latency_ms", "p50"),
        "retrieval_p95": dig(la, "retrieval_latency_ms", "p95"),
        "rerank_p50": dig(la, "rerank_latency_ms", "p50"),
        "rerank_p95": dig(la, "rerank_latency_ms", "p95"),
        "llm_p50": dig(la, "llm_latency_ms", "p50"),
    }
    return out


# ================================================================
# 比较
# ================================================================

def walk(path, java, py, diffs):
    if isinstance(java, dict) and isinstance(py, dict):
        for k in sorted(set(java) | set(py)):
            walk(f"{path}.{k}", java.get(k), py.get(k), diffs)
        return
    if isinstance(java, list) or isinstance(py, list):
        if java != py:
            diffs.append((path, java, py))
        return
    if isinstance(java, float) or isinstance(py, float):
        if java is None or py is None:
            if java != py:
                diffs.append((path, java, py))
            return
        if abs(java - py) > 1e-9:
            diffs.append((path, java, py))
        return
    if java != py:
        diffs.append((path, java, py))


def compare(java: dict, py: dict):
    diffs = []
    walk("", java, py, diffs)
    return diffs


# ================================================================
# 主流程
# ================================================================

def selftest(java: dict, py: dict) -> bool:
    """★★ 反对照：证明这个比较器**能报错**。

    对拍脚本最危险的失败模式是它永远说 OK —— 一个键名写错的比较器
    会两边都取到 None，然后安静地报告「全部一致」。
    所以这里人为改掉 Java 报告里的几个数，断言比较器必须抓到。
    """
    cases = [
        ("意图.逐行.命中", lambda r: r["意图"]["逐行"].__setitem__("命中",
                                                          (r["意图"]["逐行"]["命中"] or 0) + 1)),
        ("归因.桶.ok", lambda r: r["归因"]["桶"].__setitem__(
            "ok", (r["归因"]["桶"].get("ok") or 0) + 1)),
        ("检索.HitRate@5.n", lambda r: r["检索"]["HitRate@5"].__setitem__(
            "n", (r["检索"]["HitRate@5"]["n"] or 0) + 1)),
        ("延迟.total_p50", lambda r: r["延迟"]["total_latency_ms"].__setitem__(
            "p50", (r["延迟"]["total_latency_ms"]["p50"] or 0) + 1)),
    ]
    ok = True
    for name, mutate in cases:
        broken = copy.deepcopy(java)
        mutate(broken)
        diffs = compare(from_java(broken), py)
        if diffs:
            print("  ✓ 改坏 %-24s → 比较器抓到 %d 处差异" % (name, len(diffs)))
        else:
            print("  ✗ 改坏 %-24s → 比较器【没抓到】—— 它对这一项是瞎的" % name)
            ok = False
    return ok


def main() -> int:
    # ★ global 必须在函数的最前面 —— 放到下面去的话，`default=BASE`
    #   那一行已经先把它当【全局】读过了，Python 会直接报
    #   SyntaxError: name 'BASE' is used prior to global declaration。
    #   而这行代码看起来完全正常。
    global BASE

    ap = argparse.ArgumentParser(description="T4 指标对拍：Java 端点 vs Python 独立实现")
    ap.add_argument("--run", required=True, help="评测运行 ID")
    ap.add_argument("--selftest", action="store_true",
                    help="★ 反对照：人为改坏 Java 报告，断言比较器能抓到")
    ap.add_argument("--base", default=BASE)
    args = ap.parse_args()

    BASE = args.base

    print("=" * 78)
    print("T4 指标对拍 · run=%s" % args.run)
    print("=" * 78)

    url = "%s/api/debug/eval/report?%s" % (
        BASE, urllib.parse.urlencode({"runId": args.run}, encoding="utf-8"))
    java_report = _get_json(url)
    print("Java 报告已取到（%d 个顶层小节）" % len(java_report))

    py = compute(args.run)
    java = from_java(java_report)

    print()
    print("-" * 78)
    for section in ("意图", "澄清边界", "槽位声明", "检索范围", "检索", "归因",
                    "过度检索", "延迟"):
        for line in render(section, java.get(section), py.get(section)):
            print(line)
    print("-" * 78)

    diffs = compare(java, py)
    print()
    if diffs:
        print("★★ 对拍失败：%d 处不一致" % len(diffs))
        for path, j, p in diffs:
            print("   %-52s Java=%-14s Python=%s" % (path, j, p))
        print()
        print("   两边都是独立实现，所以【没有任何一边自动是对的】——"
              "要查的是它们为什么读出了不同的数。")
        return 1

    print("★ 对拍通过：Python 独立实现的每一个数都和 Java 端点逐字一致。")
    print("  数据来源不同（MyBatis vs psql）、算法分别实现 —— 两条路走到同一个数。")

    if args.selftest:
        print()
        print("★ 反对照（比较器能不能报错）：")
        if not selftest(java_report, py):
            print()
            print("★★ 反对照失败 —— 上面打 ✗ 的那些项，比较器是瞎的。"
                  "「对拍通过」在这种情况下【不构成任何证据】。")
            return 1
        print("  所有项都被抓到 → 上面那个「通过」是有内容的。")
    return 0


def render(section, java, py):
    """把一个节两边并排打出来。★ 打出来比只报『一致』有用 ——
    数字摆在眼前，人也能看出「这个数明显不对」这类机器测不出的问题。"""
    lines = ["%-10s %-34s %-16s %-16s %s" % (section, "项", "Java", "Python", ""),
             "%-10s %-34s %-16s %-16s %s" % ("", "-" * 34, "-" * 16, "-" * 16, "")]

    def fmt(v):
        if isinstance(v, float):
            return "%.4f" % v
        return str(v)

    def rec(prefix, j, p):
        if isinstance(j, dict) and isinstance(p, dict):
            for k in sorted(set(j) | set(p)):
                rec(prefix + "." + k if prefix else k, j.get(k), p.get(k))
            return
        same = (j == p) if not isinstance(j, float) else (
            j is not None and p is not None and abs(j - p) <= 1e-9)
        lines.append("%-10s %-34s %-16s %-16s %s"
                     % ("", prefix, fmt(j), fmt(p), "OK" if same else "◄◄ 不一致"))

    rec("", java, py)
    return lines


if __name__ == "__main__":
    sys.exit(main())
