#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段 9.6b 后端验收探针 —— 埋点上报 + 在线指标，逐条验。

## ★★ 为什么这个脚本不能省（那些用例不是已经测过 service 层了吗）

单元/集成用例测的是**算得对不对**。这个脚本测的是**那条路通不通**：

```
   浏览器 → sendBeacon → POST /api/events → user_event 表
                                          ↓
                              GET /api/status/metrics → 分子分母对上
```

★ 中间任何一层断掉，用例全绿而功能不可用 —— 本项目栽过同一形态：
  `ResourceNotFoundException` 的子类静默变 500，而 service 层 12 条全绿
  （阶段 9.6b 前置，`docs/10` 记着）。

## ★★★ 第【3】段是重点，它要真的问一句

只断言「接口回 200」的话，「事件进库了但指标读不到」和「事件压根没进库」
长得**一模一样**。所以第 3 段走完整条路：

```
   问一句（真的调模型）→ 从 SSE 的 done 里取 traceId + references
                        → 给这条回答发一个 ref_click
                        → 看指标的分子【+1】而分母【不动】
```

★ 为什么不「从库里挑一条现成的 trace」：探针**拿不到**那个列表 ——
  历史消息里没有 traceId（`chat_message` 没那一列），
  而 `/api/debug/**` 是 local-only、打不了 Nginx 那一侧。
  ⇒ 唯一不绕过被验对象的办法就是**自己造一条**。

## 花钱吗

★ 只有第 3 段的一次问答（≈ 0.0002 元）。
  `--no-model` 跳过它，剩下的全免费。

用法：
    python scripts/probe_stage96.py                       # 全部
    python scripts/probe_stage96.py --no-model            # 跳过调模型那一段
    python scripts/probe_stage96.py --url http://localhost -u 用户:口令   # 打 Nginx 那一侧
"""

import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone

# ★ Windows 上 Python 读服务端响应可能按 GBK 解码而报错 —— 显式钉死 UTF-8。
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8")
    except Exception:
        pass

BASE = "http://localhost:8080"
AUTH = None
USER_ID = "8"

PASS, FAIL = [], []


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    print(f"  {'✅' if ok else '❌'} {name}")
    if detail:
        for line in str(detail).splitlines():
            print(f"       {line}")


def call(method, path, body=None, ctype="application/json", raw=None, user_id=USER_ID):
    """返回 (status, parsed_json_or_bytes)。★ 4xx/5xx 的 body 也要读回来 —— 那正是要看的东西"""
    data = raw if raw is not None else (
        json.dumps(body).encode("utf-8") if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", ctype)
    if user_id:
        req.add_header("X-Xbla-User-Id", user_id)
    if AUTH:
        req.add_header("Authorization", "Basic " + AUTH)
    try:
        with urllib.request.urlopen(req) as r:
            txt = r.read()
            try:
                return r.status, json.loads(txt.decode("utf-8"))
            except Exception:
                return r.status, txt
    except urllib.error.HTTPError as e:
        txt = e.read()
        try:
            return e.code, json.loads(txt.decode("utf-8"))
        except Exception:
            return e.code, txt


def new_event_no():
    return "probe-" + uuid.uuid4().hex


def now_iso():
    """
    ★★★ 客户端时间戳 —— **必须是【现在】**，不能写死。

    ## 写死的后果实测过一次（2026-09-26）

    本脚本原来把它写成 `"2026-09-26T15:00:00+08:00"` 这个常量。
    跑了几轮之后，库里 10 条事件里 **7 条的偏差是几十万毫秒**
    （我探针写的假时间），只有 3 条是浏览器报的真时间（8~11ms）。

    于是 `clockSkewP50` 报的是 **176130 ms** ——
    而这个指标的全部用途是「检查客户端时钟准不准」。
    **探针在污染它自己要验的那个指标**，而且症状是
    「偏差三百秒」这种**看起来像真发现了问题**的数字。

    ⇒ 一个自称「检查时钟」的指标，被自己写的假时间喂成了
      「时钟有毛病」。修法就是这一行：取现在。

    ⚠️ 顺带记住：探针会**真的往 `user_event` 写几行**（`eventNo` 前缀 `probe-`）。
    那是有意的 —— 它要验的就是写入路径。但这也意味着
    **跑完探针之后，在线指标里会多出几条探针造的事件**。
    """
    return datetime.now(timezone.utc).isoformat()


def post_event(body, ctype="application/json", raw=None, user_id=USER_ID):
    return call("POST", "/api/events", body, ctype, raw, user_id)


def outcome_of(resp):
    _, payload = resp
    if isinstance(payload, dict):
        return (payload.get("data") or {}).get("outcome")
    return None


def metrics(window="all"):
    _, resp = call("GET", f"/api/status/metrics?window={window}")
    return (resp or {}).get("data") or {}


# ============================================================
# 一、上报端点：三种结局
# ============================================================

def probe_ingest():
    print("\n【1】POST /api/events —— 三种结局")

    body = {
        "eventNo": new_event_no(),
        "eventType": "ref_click",
        "userId": 8,
        "sessionNo": "probe-sess",
        "traceId": "probe-trace-" + uuid.uuid4().hex[:8],
        "payload": {"chunkId": 1, "no": 1},
        "occurredAt": now_iso(),
    }

    r = post_event(body)
    check("合法事件 → 200 + WRITTEN",
          r[0] == 200 and outcome_of(r) == "WRITTEN",
          f"HTTP={r[0]} outcome={outcome_of(r)}")

    # ★★ 幂等：同一个 eventNo 再发一次
    r = post_event(body)
    check("★★ 同一个 eventNo 再发 → 200 + DUPLICATE（不是 WRITTEN，也不是 400）",
          r[0] == 200 and outcome_of(r) == "DUPLICATE",
          f"HTTP={r[0]} outcome={outcome_of(r)}  ← 重复计数是最难发现的指标错误")

    r = post_event({**body, "eventNo": new_event_no(), "eventType": "product_click"})
    check("★ 白名单外的类型 → 200 + DISCARDED（不是 400）",
          r[0] == 200 and outcome_of(r) == "DISCARDED",
          f"HTTP={r[0]} outcome={outcome_of(r)}  ← 埋点尽力而为，不该让服务端报错")

    r = post_event(None)
    check("★ 空请求体 → 200 + DISCARDED（@RequestBody(required=false) 的作用）",
          r[0] == 200 and outcome_of(r) == "DISCARDED",
          f"HTTP={r[0]} outcome={outcome_of(r)}")

    r = post_event({**body, "eventNo": new_event_no(), "occurredAt": None})
    check("★ 缺 occurredAt → DISCARDED（服务端【不】代填 now()）",
          r[0] == 200 and outcome_of(r) == "DISCARDED",
          f"HTTP={r[0]} outcome={outcome_of(r)}")

    # ★★★ 探针自己的时间戳必须是【现在】—— 它曾经是个写死的常量
    skew_self = abs(
        (datetime.now(timezone.utc)
         - datetime.fromisoformat(now_iso().replace("Z", "+00:00"))).total_seconds())
    check("★★ 探针打的时间戳是【现在】（不是写死的常量）", skew_self < 5,
          f"自检偏差 {skew_self:.3f}s"
          "  ← ★ 写死的后果实测过：clockSkewP50 报到 176130ms，"
          "而那个指标的全部用途就是检查时钟准不准")

    r = post_event(None, raw=b"{bad json")
    check("⚠️ 畸形 JSON → 400（★ 记录事实，不是要求）", r[0] == 400, f"HTTP={r[0]}")

    r = post_event(body, ctype="text/plain")
    check("⚠️ Content-Type: text/plain → 415",
          r[0] == 415, f"HTTP={r[0]}  ← ★ 这条就是 track.js 里那个 Blob 约束的判据")


# ============================================================
# 二、指标端点：形状与窗口
# ============================================================

def probe_metrics():
    print("\n【2】GET /api/status/metrics —— 形状与窗口")

    st, resp = call("GET", "/api/status/metrics")
    d = (resp or {}).get("data") or {}
    check("默认窗口 → 200 + window=24h",
          st == 200 and d.get("window") == "24h", f"HTTP={st} window={d.get('window')}")

    check("★ 三块数据都在（traffic / latency / behavior）",
          all(k in d for k in ("traffic", "latency", "behavior")),
          f"键={sorted(k for k in d if not k.startswith('generated'))}")

    bs = (d.get("traffic") or {}).get("byStatus") or {}
    check("★★ byStatus 四个键恒在（值为 0 也出现）",
          set(bs.keys()) >= {"1", "2", "3", "4"},
          f"键={sorted(bs.keys())}  ← 缺键会把「没被走到」渲染成「不存在」")

    be = (d.get("behavior") or {}).get("byEventType") or {}
    check("★★ byEventType 两个键恒在",
          set(be.keys()) >= {"ref_click", "feedback"}, f"键={sorted(be.keys())}")

    lat = d.get("latency") or {}
    check("★ 每一段都带自己的 n（percentile_cont 跳过 NULL）",
          all(k in lat for k in ("queueN", "retrievalN", "rerankN", "llmN", "totalN")),
          f"totalN={lat.get('totalN')} queueN={lat.get('queueN')}")

    st2, resp2 = call("GET", "/api/status/metrics?window=99y")
    d2 = (resp2 or {}).get("data") or {}
    check("★★ 非法 window → 200（不是 400），window 换缺省、requestedWindow 留原值",
          st2 == 200 and d2.get("window") == "24h" and d2.get("requestedWindow") == "99y",
          f"HTTP={st2} window={d2.get('window')} requested={d2.get('requestedWindow')}")

    detail, ok = [], True
    for w in ("24h", "7d", "all"):
        s, r = call("GET", f"/api/status/metrics?window={w}")
        got = ((r or {}).get("data") or {}).get("window")
        detail.append(f"{w}→{got}")
        ok = ok and s == 200 and got == w
    check("★ 24h / 7d / all 三个窗口都认", ok, " ".join(detail))

    notes = " ".join(d.get("notes") or [])
    check("★★ notes 里带着 ADR-083 那句「五段不相加」", "不相加" in notes,
          "口径必须和数字同行 —— 数字会被贴到别处去")
    check("★★ notes 里明说 behavior 没有历史、也没有「转化」",
          "埋点上线" in notes and "转化" in notes)


# ============================================================
# 三、★★★ 端到端：一次点击，分子分母各自涨了多少
# ============================================================

def probe_end_to_end():
    print("\n【3】★★★ 端到端 —— 问一句、点它的引用、看指标怎么动")

    b0 = metrics("all").get("behavior") or {}
    cited0, clicked0 = b0.get("citedReplies", 0), b0.get("clickedCitedReplies", 0)
    print(f"       基线：分母={cited0} 分子={clicked0} 率={b0.get('referenceClickRate')}")

    # ★ 不传 raw：body 走 JSON，响应那边因为我们回的是 SSE（非 JSON）
    #   会自动落到 bytes 分支 —— 正好是我们要读的原始字节
    st, raw = call("POST", "/api/chat/stream",
                   body={"sessionNo": None, "question": "退货要几天", "systemPrompt": None},
                   user_id=USER_ID)
    if st != 200 or not isinstance(raw, bytes):
        check("问一句（走流式）→ 200", False, f"HTTP={st}")
        return
    check("问一句（走流式）→ 200", True, "(调了一次模型，≈0.0002 元)")

    done = extract_done_event(raw)
    trace_id = (done or {}).get("traceId")
    refs = (done or {}).get("references") or []
    if not trace_id:
        check("从 done 事件里取到 traceId", False, f"done={str(done)[:200]}")
        return
    check("从 done 事件里取到 traceId", True, f"traceId={trace_id[:16]}…")
    check("这次回答【有引用】（没有的话这一段的判据不成立）", bool(refs),
          f"引用 {len(refs)} 条")
    if not refs:
        return

    chunk_id = refs[0].get("chunk_id")
    r = post_event({
        "eventNo": new_event_no(), "eventType": "ref_click", "userId": 8,
        "traceId": trace_id, "payload": {"chunkId": chunk_id, "no": refs[0].get("no")},
        "occurredAt": now_iso(),
    })
    check("给这条回答发一个 ref_click → WRITTEN",
          outcome_of(r) == "WRITTEN", f"HTTP={r[0]} outcome={outcome_of(r)}")

    a = metrics("all").get("behavior") or {}
    cited1, clicked1 = a.get("citedReplies", 0), a.get("clickedCitedReplies", 0)

    check("★★ 分母 +1（刚问了一句，它带来了 1 条有引用的回答）",
          cited1 == cited0 + 1, f"{cited0} → {cited1}")
    check("★★★ 分子 +1 —— 这次点击真的被算进去了",
          clicked1 == clicked0 + 1,
          f"{clicked0} → {clicked1}  ← ★ 这一条才是端到端通了的证据")

    rate = a.get("referenceClickRate")
    check("★★ 率恒 ≤ 1（分子在构造上是分母的子集）",
          rate is None or rate <= 1.0,
          f"率={rate:.4f}" if isinstance(rate, float) else f"率={rate}")

    # ★★ 反面：点一个【不在分母里】的 trace，分子不该动
    post_event({
        "eventNo": new_event_no(), "eventType": "ref_click", "userId": 8,
        "traceId": "probe-uncited-" + uuid.uuid4().hex[:8],
        "payload": {"chunkId": 1, "no": 1},
        "occurredAt": now_iso(),
    })
    clicked2 = (metrics("all").get("behavior") or {}).get("clickedCitedReplies")
    check("★★★ 反：点一条【没有引用】的回答 → 分子不动（否则率会 > 1）",
          clicked2 == clicked1, f"{clicked1} → {clicked2}")

    # ★★ 反面：给这条回答投一个 👎，看它进的是哪个桶
    up0 = (metrics("all").get("behavior") or {}).get("feedbackUp", 0)
    post_event({
        "eventNo": new_event_no(), "eventType": "feedback", "userId": 8,
        "traceId": trace_id, "payload": {"vote": "up"},
        "occurredAt": now_iso(),
    })
    post_event({
        "eventNo": new_event_no(), "eventType": "feedback", "userId": 8,
        "traceId": trace_id, "payload": {},
        "occurredAt": now_iso(),
    })
    b2 = metrics("all").get("behavior") or {}
    check("★ 正常的 👍 进 feedbackUp", b2.get("feedbackUp", 0) == up0 + 1,
          f"{up0} → {b2.get('feedbackUp')}")
    check("★★ 票型读不出来的进 feedbackOther，不并进 👍/👎",
          b2.get("feedbackOther", 0) >= 1,
          f"feedbackOther={b2.get('feedbackOther')}  ← 并进去会把「读不出来」混成「用户不满意」")


def extract_done_event(raw: bytes):
    """
    从 SSE 原始字节里取 `done` 事件的 payload。

    ★ 按 `\\n\\n` 分帧、再找 `event:done` —— 与 `frontend/src/api.js` 的解析器
      **同一套规则**，所以这一段顺带证明「前端解析器依据的那个格式」没变。
    """
    text = raw.decode("utf-8", "replace")
    for block in text.split("\n\n"):
        name, data_lines = None, []
        for line in block.split("\n"):
            if line.startswith("event:"):
                name = line[len("event:"):].strip()
            elif line.startswith("data:"):
                v = line[len("data:"):]
                data_lines.append(v[1:] if v.startswith(" ") else v)
        if name == "done" and data_lines:
            try:
                return json.loads("\n".join(data_lines))
            except Exception:
                return None
    return None


# ============================================================
# 四、★★ 前端页面的字段契约
# ============================================================

# ★ `MetricsView.vue` 里读的每一个路径，一条不多一条不少地列在这里。
#   ⚠️ 加新字段时两处都要改 —— 而这条判据就是用来抓「改了一处」的。
FRONTEND_PATHS = [
    "generatedAt", "from", "notes", "requestedWindow", "window",
    "traffic.questions", "traffic.sessions", "traffic.byStatus",
    "latency.queueP50Ms", "latency.queueP95Ms", "latency.queueN",
    "latency.retrievalP50Ms", "latency.retrievalP95Ms", "latency.retrievalN",
    "latency.rerankP50Ms", "latency.rerankP95Ms", "latency.rerankN",
    "latency.llmP50Ms", "latency.llmP95Ms", "latency.llmN",
    "latency.totalP50Ms", "latency.totalP95Ms", "latency.totalN",
    "behavior.refClicks", "behavior.feedbackUp", "behavior.feedbackDown",
    "behavior.feedbackOther", "behavior.byEventType", "behavior.referenceClickRate",
    "behavior.clickedCitedReplies", "behavior.citedReplies", "behavior.clockSkewP50Ms",
]


def probe_frontend_contract():
    """
    ★★ 页面读的每个字段，响应里都得真的有。

    ## 为什么这条判据值得单独一段

    字段名写错的症状是**一片「—」**，而它看起来**完全正常** ——
    读的人会以为「这段还没有数据」，不会想到「模板里的键名打错了」。

    ★ 这是本项目反复防的那一类（`QaLogMapper.selectByEvalRun` 的显式列清单
      坏过两次，症状都是「一个看起来合法的空」）。区别只是这次发生在
      前端和 JSON 之间，而不是 Java 和 SQL 之间。
    """
    print("\n【4】★★ 前端页面的字段契约（MetricsView.vue 读的每一个路径）")

    d = metrics("all")
    missing = []
    for p in FRONTEND_PATHS:
        cur, ok = d, True
        for k in p.split("."):
            if isinstance(cur, dict) and k in cur:
                cur = cur[k]
            else:
                ok = False
                break
        if not ok:
            missing.append(p)

    check(f"页面读的 {len(FRONTEND_PATHS)} 个字段全部存在", not missing,
          ("缺失：" + ", ".join(missing)) if missing
          else "★ 少一个的症状是那格显示「—」，而它看起来像「没有数据」")

    # ★ 顺带：固定键集里的键，页面是当表头用的 —— 少一个就是少一列
    bs = (d.get("traffic") or {}).get("byStatus") or {}
    be = (d.get("behavior") or {}).get("byEventType") or {}
    check("★ 页面当表头用的固定键集也在（byStatus 四格 / byEventType 两格）",
          set(bs) >= {"1", "2", "3", "4"} and set(be) >= {"ref_click", "feedback"},
          f"byStatus={sorted(bs)} byEventType={sorted(be)}")


def main():
    global BASE, AUTH
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=BASE)
    ap.add_argument("-u", "--user", help="用户:口令（打 Nginx 那一侧时用）")
    ap.add_argument("--no-model", action="store_true", help="跳过第 3 段（会调模型）")
    args = ap.parse_args()

    BASE = args.url.rstrip("/")
    if args.user:
        AUTH = base64.b64encode(args.user.encode("utf-8")).decode("ascii")

    print("=" * 62)
    print(f"阶段 9.6b 探针 · {BASE} · {time.strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 62)

    probe_ingest()
    probe_metrics()
    probe_frontend_contract()
    if args.no_model:
        print("\n【3】已跳过（--no-model）")
    else:
        probe_end_to_end()

    print("\n" + "=" * 62)
    print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    if FAIL:
        print("失败清单：")
        for f in FAIL:
            print(f"  ✗ {f}")
    print("=" * 62)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
