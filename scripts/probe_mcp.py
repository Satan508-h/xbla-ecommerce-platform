# -*- coding: utf-8 -*-
"""
MCP Server 探针（阶段 5.7）—— 验收标准 1 的落点。

    问「我的订单到哪了」→ 触发 MCP 工具调用

用法：
    python scripts/probe_mcp.py                # 完整走一遍握手 + 工具调用
    python scripts/probe_mcp.py --user-id 3    # 指定身份（默认从库里挑一个有订单的）

★ 这个脚本【不调模型，不花钱】—— 它只跟 /mcp 说话，不经过 /api/chat。

★ 为什么必须真跑一遍，而不是靠单测：
  McpServerProtocolTest 能证明「协议层返回的报文形状对」，
  但证明不了「HTTP 层把会话、身份、Origin 这几件事串对了」——
  会话忘种、头忘带、通知被回了 200 而不是 202，这些都只在真 HTTP 上才暴露。

Windows 注意：中文一律走 urllib，不经过 shell（见 probe_kb.py 头部）。
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://localhost:8080"
MCP = BASE + "/mcp"


def rpc(method: str, params: dict | None = None, *, request_id=None,
        session: str | None = None, user_id: int | None = None,
        origin: str | None = None) -> tuple[int, dict | None, dict]:
    """发一条 JSON-RPC 报文。返回 (HTTP 状态码, 响应体, 响应头)"""
    body: dict = {"jsonrpc": "2.0", "method": method}
    if request_id is not None:
        body["id"] = request_id
    if params is not None:
        body["params"] = params

    headers = {"Content-Type": "application/json",
               "Accept": "application/json, text/event-stream"}
    if session:
        headers["Mcp-Session-Id"] = session
    if user_id is not None:
        headers["X-Xbla-User-Id"] = str(user_id)
    if origin:
        headers["Origin"] = origin

    req = urllib.request.Request(
        MCP, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw.strip() else None), dict(resp.headers)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            parsed = json.loads(raw) if raw.strip() else None
        except json.JSONDecodeError:
            parsed = {"_raw": raw[:200]}
        return e.code, parsed, dict(e.headers)


def find_demo_user() -> tuple[int, str]:
    """挑一个有订单的用户 + 他的一个订单号 —— 用作演示数据"""
    import subprocess
    sql = ("SELECT o.user_id || '|' || o.order_no FROM orders o "
           "WHERE o.user_id = (SELECT user_id FROM orders GROUP BY user_id "
           "ORDER BY count(*) DESC LIMIT 1) LIMIT 1;")
    try:
        out = subprocess.run(
            ["docker", "compose", "exec", "-T", "postgres", "psql",
             "-U", "xbla", "-d", "xbla_rag", "-tAc", sql],
            capture_output=True, text=True, timeout=30, encoding="utf-8")
        uid, order_no = out.stdout.strip().split("|")
        return int(uid), order_no
    except Exception as e:                                    # noqa: BLE001
        print(f"  ⚠️ 从库里取演示数据失败（{e}），用兜底值")
        return 8, "SO202608130077"


def step(title: str) -> None:
    print()
    print("=" * 74)
    print(f"★ {title}")
    print("=" * 74)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--user-id", type=int, default=None,
                        help="身份（X-Xbla-User-Id）。默认从库里挑一个有订单的用户")
    args = parser.parse_args()

    user_id = args.user_id
    if user_id is None:
        user_id, order_no = find_demo_user()
    else:
        _, order_no = find_demo_user()

    print(f"MCP 探针 —— 端点 {MCP}")
    print(f"身份 X-Xbla-User-Id = {user_id}    演示订单号 = {order_no}")

    # ────────────────────────────────────────────────────────
    step("① 没有身份 → 401（身份缺失根本不该走到模型那一步）")
    status, body, _ = rpc("tools/list", request_id=1)
    print(f"  HTTP {status}")
    print(f"  {json.dumps(body, ensure_ascii=False)[:160]}")
    ok_no_identity = status == 401

    # ────────────────────────────────────────────────────────
    step("② 跨源 Origin → 403（规范【强制】要求的防 DNS rebinding）")
    status, body, _ = rpc("tools/list", request_id=2,
                          user_id=user_id, origin="http://evil.example.com")
    print(f"  HTTP {status}")
    print(f"  {json.dumps(body, ensure_ascii=False)[:160]}")
    ok_bad_origin = status == 403

    # ────────────────────────────────────────────────────────
    step("③ 跳过热身直接 tools/call → 400（会话是强制的）")
    status, body, _ = rpc("tools/call",
                          {"name": "query_order_status",
                           "arguments": {"order_no": order_no}},
                          request_id=3, user_id=user_id)
    print(f"  HTTP {status}")
    print(f"  {json.dumps(body, ensure_ascii=False)[:200]}")
    ok_no_session = status == 400

    # ────────────────────────────────────────────────────────
    step("④ initialize —— 版本协商 + 拿到会话 ID + 能力声明")
    status, body, headers = rpc("initialize",
                                {"protocolVersion": "2025-11-25",
                                 "capabilities": {},
                                 "clientInfo": {"name": "probe_mcp.py", "version": "1.0"}},
                                request_id=4, user_id=user_id)
    session = headers.get("Mcp-Session-Id") or headers.get("mcp-session-id")
    print(f"  HTTP {status}   会话 {session}")
    print(f"  {json.dumps(body, ensure_ascii=False, indent=2)}")
    ok_init = status == 200 and session is not None
    if not ok_init:
        print("\n✗ 握手失败，后面的步骤没法继续")
        return 1

    # ────────────────────────────────────────────────────────
    step("⑤ notifications/initialized → 【202 且没有响应体】(生命周期第三步是通知)")
    status, body, _ = rpc("notifications/initialized", session=session, user_id=user_id)
    print(f"  HTTP {status}   响应体 {body!r}")
    ok_notification = status == 202 and body is None

    # ────────────────────────────────────────────────────────
    step("⑥ tools/list —— 模型实际会看到的工具清单")
    status, body, _ = rpc("tools/list", request_id=6, session=session, user_id=user_id)
    tools = (body or {}).get("result", {}).get("tools", [])
    print(f"  HTTP {status}   共 {len(tools)} 个工具")
    for t in tools:
        print(f"\n  【{t['name']}】{t['title']}")
        print(f"    {t['description'].strip().splitlines()[0]}")
        schema = t.get("inputSchema", {})
        print(f"    inputSchema.required = {schema.get('required')}")
        print(f"    additionalProperties = {schema.get('additionalProperties')}")
    ok_tools = len(tools) > 0

    # ────────────────────────────────────────────────────────
    step(f"⑦ tools/call —— 用身份 {user_id} 查他自己的订单 {order_no}")
    status, body, _ = rpc("tools/call",
                          {"name": "query_order_status",
                           "arguments": {"order_no": order_no}},
                          request_id=7, session=session, user_id=user_id)
    result = (body or {}).get("result", {})
    print(f"  HTTP {status}   isError = {result.get('isError')}")
    print()
    print("  ── content[0].text（模型读到的）──")
    for line in (result.get("content") or [{}])[0].get("text", "").splitlines():
        print(f"    {line}")
    print()
    print("  ── structuredContent（程序读到的）──")
    print(f"    {json.dumps(result.get('structuredContent'), ensure_ascii=False)}")
    ok_call = result.get("isError") is False and order_no in str(result)

    # ────────────────────────────────────────────────────────
    step("⑧ ★★ 越权：换一个身份，拿【同一个订单号】去查")
    stranger = user_id + 1
    # ★★ 陌生人必须【自己握手】拿自己的会话。
    #
    #   一开始这里复用了上面的 session，结果拿到的是 403 —— 因为
    #   【会话和身份是绑定的】，换头复用会话会被更前面的一层挡掉，
    #   根本走不到工具的 SQL。那条路本身是个正确的防护，但它测的是
    #   「会话不能被顶替」，不是「工具会不会把别人的订单交出去」。
    #   要验后者，就得让陌生人走完整流程。
    _, _, sh = rpc("initialize",
                   {"protocolVersion": "2025-11-25", "capabilities": {},
                    "clientInfo": {"name": "probe_mcp.py/stranger", "version": "1.0"}},
                   request_id=80, user_id=stranger)
    stranger_session = sh.get("Mcp-Session-Id") or sh.get("mcp-session-id")

    status, body, _ = rpc("tools/call",
                          {"name": "query_order_status",
                           "arguments": {"order_no": order_no}},
                          request_id=8, session=stranger_session, user_id=stranger)
    stranger_text = ((body or {}).get("result", {}).get("content") or [{}])[0].get("text", "")
    print(f"  身份 {stranger}（自己的会话）查同一个订单号 → HTTP {status}")
    print(f"    {stranger_text.strip()}")
    ok_idor = "没有找到" in stranger_text and order_no in stranger_text
    if not stranger_text.strip():
        print("  ⚠️ 空回复 —— 说明请求根本没走到工具，先去看上面的 HTTP 状态码")
    print()
    print("  ⚠️ 注意它是 isError=false —— 「没有这一单」对工具来说是个【答案】，"
          "不是失败。")
    print("     标成错误的话模型会说「系统出了点问题，请稍后再试」，"
          "而用户重试一万次也变不出那个订单。")

    # ────────────────────────────────────────────────────────
    step("⑨ ★★ 对照：换一个【不存在的】订单号，回复必须逐字相同")
    status, body, _ = rpc("tools/call",
                          {"name": "query_order_status",
                           "arguments": {"order_no": "SO_NONEXISTENT_9999"}},
                          request_id=9, session=stranger_session, user_id=stranger)
    ghost_text = ((body or {}).get("result", {}).get("content") or [{}])[0].get("text", "")
    print(f"    {ghost_text.strip()}")
    # ★ 必须额外要求两边都非空 —— 否则两个空串也会「一致」，
    #   而那正是这条断言最危险的失效方式（看起来通过了，其实什么都没测）
    same = (stranger_text.strip() and ghost_text.strip()
            and ghost_text.replace("SO_NONEXISTENT_9999", order_no) == stranger_text)
    print()
    print(f"  ★ 两句话（抹掉订单号后）一致：{'✓ 是' if same else '✗ 不是 —— 存在枚举预言机！'}")

    # ────────────────────────────────────────────────────────
    step("⑩ 参数错误 → JSON-RPC 协议错误（不是 isError）")
    status, body, _ = rpc("tools/call",
                          {"name": "query_order_status", "arguments": {}},
                          request_id=10, session=session, user_id=user_id)
    err = (body or {}).get("error", {})
    print(f"  HTTP {status}（★ 仍然是 200）")
    print(f"    code    = {err.get('code')}  （-32602 = INVALID_PARAMS）")
    print(f"    message = {err.get('message')}")
    print(f"    data    = {err.get('data')}")
    ok_param_error = err.get("code") == -32602

    step("⑪ ★★ 模型想自己加 user_id → 被拒绝（这是越权的入口）")
    status, body, _ = rpc("tools/call",
                          {"name": "query_order_status",
                           "arguments": {"order_no": order_no, "user_id": 1}},
                          request_id=11, session=session, user_id=user_id)
    err = (body or {}).get("error", {})
    print(f"    message = {err.get('message')}")
    ok_unknown_param = "user_id" in str(err.get("message", ""))

    # ────────────────────────────────────────────────────────
    step("⑫ DELETE 结束会话 → 204")
    req = urllib.request.Request(MCP, method="DELETE",
                                 headers={"Mcp-Session-Id": session,
                                          "X-Xbla-User-Id": str(user_id)})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            status = resp.status
    except urllib.error.HTTPError as e:
        status = e.code
    print(f"  HTTP {status}")
    print()
    status2, body2, _ = rpc("tools/list", request_id=12, session=session, user_id=user_id)
    print(f"  再用这个会话发请求 → HTTP {status2}（应该 400，会话已失效）")
    ok_delete = status2 == 400

    # ────────────────────────────────────────────────────────
    step("★ 判定汇总")
    checks = [
        ("① 无身份 → 401", ok_no_identity),
        ("② 非法 Origin → 403", ok_bad_origin),
        ("③ 无会话 → 400", ok_no_session),
        ("④ initialize 返回会话", ok_init),
        ("⑤ 通知 → 202 且无响应体", ok_notification),
        ("⑥ tools/list 有工具", ok_tools),
        ("⑦ 查自己的订单成功", ok_call),
        ("⑧ ★★ 越权被挡", ok_idor),
        ("⑨ ★★ 与「不存在」逐字相同", same),
        ("⑩ 参数错误 → -32602", ok_param_error),
        ("⑪ ★★ 模型自加 user_id 被拒", ok_unknown_param),
        ("⑫ DELETE 后会话失效", ok_delete),
    ]
    for name, ok in checks:
        print(f"  {'✓' if ok else '✗'} {name}")
    passed = sum(1 for _, ok in checks if ok)
    print(f"\n  {passed}/{len(checks)} 通过")
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
