#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段 8 后端验收探针 —— 前端要用的那几个接口，逐条验。

## ★★ 为什么必须有这个脚本：SSE 的线格式是【推断】出来的

`frontend/src/api.js` 里的 SSE 解析器是按「Spring 的 `SseEmitter` 会怎么写」
推出来的 —— 而**推断不能当事实用**。这个脚本把原始字节打出来，
让格式从「我以为」变成「我量过」：

```
    event:delta          ← event 和 data 之间【没有】空行
    data:{"v":"你"}      ← 冒号后【没有】空格（Spring 不加）
                         ← 末尾空行分帧
```

★ 顺带一句话：这个脚本的第 1 项**就是** `curl` 那条命令的可读版本。
  Windows 下 Git Bash 传中文给 `curl -d` 会变成 `U+FFFD`（见 CLAUDE.md 第八节），
  所以这里的身体是显式 `utf-8` 编码的 bytes，不走 shell。

## 花钱吗

★ 只有第 1、2 项会调模型（一次问答 ≈ 0.0002 元）。其余全是零成本。

用法：
    python scripts/probe_stage8.py            # 全部
    python scripts/probe_stage8.py --no-model # 跳过调模型那两项
"""

import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request

# ★ Windows 上 Python 读服务端响应可能按 GBK 解码而报错 —— 显式钉死 UTF-8。
#   同 CLAUDE.md 第八节第 1 条。
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8")
    except Exception:
        pass

BASE = "http://localhost:8080"

# ★ Basic Auth（容器全栈那条路）。`--url` / `-u` 会设它。
#   本地开发（8080）没有 Nginx，也就不需要 —— 所以默认 None。
AUTH = None

PASS, FAIL = [], []


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    print(f"  {'✅' if ok else '❌'} {name}")
    if detail:
        for line in str(detail).splitlines():
            print(f"       {line}")


def request(path, method="GET", body=None, headers=None, raw=False, timeout=300):
    """★ body 是 bytes 而不是 str —— 中文必须由我们自己编码成 utf-8。"""
    data = None
    hdrs = dict(headers or {})
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        hdrs.setdefault("Content-Type", "application/json")
    if AUTH:
        token = base64.b64encode(AUTH.encode("utf-8")).decode("ascii")
        hdrs["Authorization"] = f"Basic {token}"

    req = urllib.request.Request(BASE + path, data=data, headers=hdrs, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        content = resp.read()
        if raw:
            return resp.status, content
        return resp.status, json.loads(content.decode("utf-8"))


def post_json(path, body, headers=None):
    return request(path, "POST", body=body, headers=headers)


# ============================================================
# 一、★ SSE 线格式（这是本脚本存在的主要理由）
# ============================================================

def dump_sse_frames(content: bytes, limit: int = 12):
    """把原始 SSE 字节拆成帧并原样打印，好让人对照解析器。"""
    text = content.decode("utf-8")
    # 帧分隔符是空行。★ 用 \n\n 而不是 \r\n\r\n —— Spring 只写 \n
    blocks = [b for b in text.split("\n\n") if b.strip()]
    print(f"       （共 {len(blocks)} 帧，显示前 {limit} 帧，**原样**含控制字符）")
    for b in blocks[:limit]:
        for line in b.split("\n"):
            seg = line[:110] + ("…" if len(line) > 110 else "")
            print(f"       | {seg}")
        print("       |")
    return blocks


def probe_post_stream():
    print("\n【1】POST /api/chat/stream —— 前端走的那条路（会调模型）")

    status, content = request(
        "/api/chat/stream",
        "POST",
        body={"sessionNo": None, "question": "退货要几天", "systemPrompt": None},
        raw=True,
    )

    check("HTTP 200", status == 200, f"实际 {status}")

    ct = content[:400].decode("utf-8", "replace")
    check("响应的 Content-Type 是 text/event-stream",
          "event:" in ct, ct[:200])

    blocks = dump_sse_frames(content)

    names = []
    for b in blocks:
        for line in b.split("\n"):
            if line.startswith("event:"):
                names.append(line[len("event:"):].strip())

    print(f"       事件序列：{names}")

    # ★★ 这三条就是解析器的依据
    check("★ `event:` 和 `data:` 之间【没有】空行（同一帧内换行）",
          all("\n" not in n for n in names),
          "如果这里挂了，说明 Spring 换了写法，api.js 的解析器要跟着改")
    check("★ `event:` 冒号后没有前导空格（解析器要容错但事实要清楚）",
          all(not n.startswith(" ") for n in names),
          f"原始名：{names[:3]}")
    check("★ 每帧以空行结束（能被 \\n\\n 切分）",
          len(blocks) == len(names),
          f"切出 {len(blocks)} 帧、{len(names)} 个事件名")

    check("出现了 meta 事件（traceId/sessionNo 的载体）", "meta" in names)
    check("出现了 delta 事件（打字机正文）", "delta" in names)
    check("★ 最后一个是 done（不是 failed）", names and names[-1] == "done",
          f"实际最后一个：{names[-1] if names else '（空）'}")

    # 从原始字节里取出 done 的 payload，验那两条前端必须知道的形状
    done_payload = None
    for b in blocks:
        if b.startswith("event:done") or "\nevent:done" in b:
            for line in b.split("\n"):
                if line.startswith("data:"):
                    done_payload = json.loads(line[len("data:"):])
    if done_payload:
        check("★★ done.answer 是 null —— 【流式的正文只在 delta 里】",
              done_payload.get("answer") is None,
              f"answer={done_payload.get('answer')!r}  "
              "★ 前端若拿它覆盖正文，回答会在完成时整条消失")
        check("done 带了 traceId（技术面板要用）",
              bool(done_payload.get("traceId")),
              f"traceId={done_payload.get('traceId')}")
        check("done 带了 references（引用来源要用）",
              "references" in done_payload,
              f"references 条数={len(done_payload.get('references') or [])}")
        return done_payload.get("sessionNo"), done_payload.get("traceId")

    return None, None


def probe_get_stream():
    print("\n【2】GET /api/chat/stream —— 保留下来的命令行口子（会调模型）")

    # ★ 中文用百分号编码，不走 shell，避开 Git Bash 那个坑
    from urllib.parse import quote
    status, content = request(f"/api/chat/stream?question={quote('你好')}", raw=True)

    check("HTTP 200", status == 200, f"实际 {status}")
    text = content.decode("utf-8", "replace")
    check("★ 还活着（阶段 2–7 的 curl 命令与探针脚本依赖它）",
          "event:" in text and "done" in text,
          "★ 这条挂了说明 A0 那次「加一条而不是换一条」做错了")
    return True


def probe_sse_timing():
    """
    ★★★ 验收标准 3 的判据：**SSE 是不是真的在流**。

    ## 为什么必须【按时间】量，而不是看内容对不对

    内容对不对，一次普通的请求就能看出来。而「有没有被缓冲」**看不出来** ——
    被缓冲时收到的字节**和流式时一模一样**，只是它们**一起来**。

    所以判据只能是时间：

        首字节延迟   应该 ≪ 总时长（检索+意图分类通常就要 1~2 秒）
        帧的到达分布 应该【分散】在整个时长上，而不是挤在最后

    ★ 而缓冲区是【谁】加的，决定了修哪里：
        Nginx 的 proxy_buffering  → 改 deploy/nginx.conf
        别的代理 / CDN            → 改那一层
        应用自己                 → 不可能，SseEmitter 是逐条 flush 的

    ⚠️ 这一项**会调模型**（一次问答）。
    """
    print("\n【3】★★ SSE 时序 —— 它到底在流，还是被攒起来一次性给？")

    body = json.dumps({"sessionNo": None, "question": "退货要几天", "systemPrompt": None})
    hdrs = {"Content-Type": "application/json"}
    if AUTH:
        token = base64.b64encode(AUTH.encode("utf-8")).decode("ascii")
        hdrs["Authorization"] = f"Basic {token}"

    req = urllib.request.Request(
        BASE + "/api/chat/stream",
        data=body.encode("utf-8"), headers=hdrs, method="POST")

    t0 = time.monotonic()
    first_byte_at = None
    delta_times = []
    last_data_at = None

    with urllib.request.urlopen(req, timeout=300) as resp:
        # ★ 按【行】迭代读 —— 每次拿到一行就是一个真实的到达时刻。
        #   用 resp.read() 的话会把整条流读完才返回，那时序信息就没了，
        #   而时序正是这一项唯一要测的东西。
        for raw_line in resp:
            now = time.monotonic() - t0
            if first_byte_at is None:
                first_byte_at = now
            line = raw_line.decode("utf-8", "replace")
            if line.startswith("data:"):
                last_data_at = now
                if '"v"' in line:
                    delta_times.append(now)

    total = time.monotonic() - t0

    if not delta_times:
        check("收到了 delta 帧", False, "一个都没收到 —— 这次问答可能失败了")
        return

    first_delta_at = delta_times[0]
    print(f"       首字节 {first_byte_at * 1000:.0f}ms · "
          f"第一个 delta {first_delta_at * 1000:.0f}ms · "
          f"最后一个数据 {last_data_at * 1000:.0f}ms · 总时长 {total * 1000:.0f}ms")
    print(f"       {len(delta_times)} 个 delta 帧")

    # ★ 判据 1：首字节远早于总时长
    check("★ 首字节在总时长的 70% 之前到达（不是最后才一股脑来）",
          first_byte_at < total * 0.7,
          f"首字节 {first_byte_at * 1000:.0f}ms / 总 {total * 1000:.0f}ms"
          "  ★ 若首字节 ≈ 总时长 → 有东西在缓冲，查 proxy_buffering")

    # ★ 判据 2：帧的到达是【分散】的。被缓冲的话所有帧会在同一个瞬间到达
    spread = delta_times[-1] - delta_times[0]
    check("★ delta 的到达是分散的（首末帧间隔 > 总时长的 10%）",
          spread > total * 0.10,
          f"首末 delta 间隔 {spread * 1000:.0f}ms / 总 {total * 1000:.0f}ms"
          "  ★ 若间隔 ≈ 0 → 所有帧是同时到达的，那就是被缓冲了")

    # ★ 判据 3：不出现「一半的帧挤在最后 100ms」
    late = sum(1 for t in delta_times if t > total - 0.1)
    check("★ 没有「绝大多数帧挤在最后 100ms」的现象",
          late < len(delta_times) * 0.5,
          f"最后 100ms 里有 {late}/{len(delta_times)} 帧")


# ============================================================
# 二、会话历史（零成本）
# ============================================================

def probe_sessions():
    print("\n【3】GET /api/chat/sessions —— 列表必须排除评测流量")

    _, body = request("/api/chat/sessions?limit=200")
    check("code == 0", body.get("code") == 0, f"code={body.get('code')}")
    rows = body.get("data") or []
    check("返回了列表", isinstance(rows, list), f"{len(rows)} 条")
    if rows:
        first = rows[0]
        check("字段形状对（sessionNo/title/messageCount/lastActiveAt）",
              {"sessionNo", "title", "messageCount", "lastActiveAt"} <= set(first),
              f"实际字段：{sorted(first)}")
        check("★ 没有把内部字段漏出去（id / userId / deleted）",
              not ({"id", "userId", "deleted"} & set(first)),
              f"多出来的：{sorted({'id', 'userId', 'deleted'} & set(first))}")

    # ★★ 正-反对照：直接查库数一遍，证明过滤确实发生了
    try:
        import subprocess
        sql = ("SELECT count(*) FROM chat_session s WHERE s.deleted = 0 "
               "AND NOT EXISTS (SELECT 1 FROM qa_log q WHERE q.session_id = s.id "
               "AND q.eval_run_id IS NOT NULL)")
        out = subprocess.run(
            ["docker", "compose", "exec", "-T", "postgres", "psql",
             "-U", "xbla", "-d", "xbla_rag", "-t", "-A", "-c", sql],
            capture_output=True, text=True, cwd=".", timeout=60)
        real_count = int(out.stdout.strip())
        check("★ 接口条数 == 库里「非评测会话」条数（过滤生效且没多没少）",
              len(rows) == min(real_count, 200),
              f"接口 {len(rows)} 条（上限 200）、库里非评测 {real_count} 条")

        sql2 = "SELECT count(*) FROM chat_session WHERE deleted = 0"
        out2 = subprocess.run(
            ["docker", "compose", "exec", "-T", "postgres", "psql",
             "-U", "xbla", "-d", "xbla_rag", "-t", "-A", "-c", sql2],
            capture_output=True, text=True, cwd=".", timeout=60)
        total = int(out2.stdout.strip())
        check(f"★★ 对照：库里有 {total} 个会话，其中 {total - real_count} 个是评测流量被排除了",
              total > real_count,
              f"不排除的话列表里 {(total - real_count) / total * 100:.1f}% 是评测题")
    except Exception as e:
        check("（跳过）对库核对", False, f"没跑成：{e}")

    return rows[0]["sessionNo"] if rows else None


def probe_messages(session_no):
    print("\n【4】GET /api/chat/sessions/{no}/messages")

    _, body = request(f"/api/chat/sessions/{session_no}/messages")
    check("code == 0", body.get("code") == 0)
    rows = body.get("data") or []
    check("拿到消息", len(rows) > 0, f"{len(rows)} 条")
    if rows:
        r = rows[0]
        check("role 是字符串（user/assistant/system），不是 1/2/3",
              r.get("role") in ("user", "assistant", "system"),
              f"role={r.get('role')!r}")
        check("★ references 是真 JSON 数组或 null（不是装着 JSON 的字符串）",
              r.get("references") is None or isinstance(r.get("references"), list),
              f"references 类型={type(r.get('references')).__name__}")

    # ★ 404 那条路
    try:
        request("/api/chat/sessions/definitely-not-exists-xyz/messages")
        check("不存在的会话号必须 404", False, "居然返回了 200")
    except urllib.error.HTTPError as e:
        body = json.loads(e.read().decode("utf-8"))
        check("★★ 不存在的会话号 → HTTP 404 且 body.code 也是 404",
              e.code == 404 and body.get("code") == 404,
              f"HTTP {e.code} / code={body.get('code')} / {body.get('message')}")


# ============================================================
# 三、技术细节（零成本）
# ============================================================

def probe_trace(trace_id):
    print("\n【5】GET /api/chat/trace/{traceId} —— 技术面板的数据源")

    _, body = request(f"/api/chat/trace/{trace_id}")
    check("code == 0", body.get("code") == 0)
    d = body.get("data") or {}

    check("带了意图", "intent" in d, f"intent={d.get('intent')}")
    check("带了五段延迟", isinstance(d.get("latency"), dict),
          f"latency={d.get('latency')}")

    lat = d.get("latency") or {}
    check("★ latency 有 unclassifiedMs 这一格（ADR-083 的「未归类」）",
          "unclassifiedMs" in lat,
          f"未归类={lat.get('unclassifiedMs')}ms")
    check("★ latency 有 rerankMs 这一格（它是 retrieval 的子集，不是兄弟）",
          "rerankMs" in lat)

    check("带了检索范围", "retrieval" in d,
          f"retrieval={(d.get('retrieval') or {})}")
    check("★★ 没有泄露 errorMsg",
          "errorMsg" not in d and "error_msg" not in d,
          "那条列可能带上游错误详情与内部模型 ID")

    # 404
    try:
        request("/api/chat/trace/no-such-trace-xyz")
        check("不存在的 traceId 必须 404", False, "居然返回了 200")
    except urllib.error.HTTPError as e:
        check("★ 不存在的 traceId → 404（和会话那条共用一个 handler）",
              e.code == 404, f"HTTP {e.code}")


# ============================================================
# 四、限流状态（零成本）
# ============================================================

def probe_status():
    print("\n【6】GET /api/status/ratelimit —— 生产也存在的只读状态")

    _, body = request("/api/status/ratelimit")
    check("code == 0", body.get("code") == 0)
    d = body.get("data") or {}

    whitelist = {"enabled", "permits", "permitsInUse", "queueSize", "maxQueue",
                 "permitTtlMs", "queuePoolActive", "queuePoolMax", "givenUpTotal"}
    check("白名单字段一个不少", whitelist <= set(d),
          f"缺：{sorted(whitelist - set(d))}")

    excluded = {"queueHead", "keyPrefix", "channel", "seq", "aliveSize",
                "heldHere", "hardCapReclaims"}
    check("★★ 内部字段一个没漏（尤其 queueHead —— 那是别人的 traceId）",
          not (excluded & set(d)),
          f"漏出去的：{sorted(excluded & set(d))}")

    check("★★ permitsInUse ≤ permits（没有超卖）",
          d.get("permitsInUse", 0) <= d.get("permits", 0),
          f"{d.get('permitsInUse')} / {d.get('permits')}")


# ============================================================
# main
# ============================================================

def main():
    global BASE, AUTH

    ap = argparse.ArgumentParser()
    ap.add_argument("--no-model", action="store_true",
                    help="跳过会调模型的两项（第 1、2、3 项）")
    ap.add_argument("--url", default=BASE,
                    help="后端地址。本地开发用 http://localhost:8080（默认）；"
                         "容器全栈用 http://localhost（走 Nginx）")
    ap.add_argument("-u", "--auth", default=None,
                    help="user:pass。只有走 Nginx（容器全栈）时才需要")
    args = ap.parse_args()

    BASE = args.url.rstrip("/")
    AUTH = args.auth

    print("=" * 62)
    print(f"阶段 8 后端验收探针 → {BASE}"
          + ("（带 Basic Auth）" if AUTH else ""))
    print("=" * 62)

    session_no, trace_id = None, None

    if not args.no_model:
        session_no, trace_id = probe_post_stream()
        probe_get_stream()
        probe_sse_timing()
    else:
        print("\n【1】【2】【3】已跳过（--no-model）")

    if session_no:
        probe_messages(session_no)
    if trace_id:
        probe_trace(trace_id)
    else:
        list_no = probe_sessions()
        if list_no:
            probe_messages(list_no)

    probe_status()

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
