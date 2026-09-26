#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
暴露面探针 —— **开隧道之前跑它，别照着清单打十几条 curl。**

## ★★★ 为什么需要它

`docs/07` §6.4 那份「开隧道前检查清单」原来是**手抄的**。
而清单这种东西有个规律：**抄到第三次就会开始漏项**，而漏掉的那一项
恰恰是你上次觉得「这还用查？」的那一项。

⇒ 把它变成一条命令。判据在代码里，不在记忆里。

## ★★ 它【不做】什么（这一节比它做什么更重要）

```
✗ 不调模型      —— 一次问答 0.0009 元，跑一次探针不该花钱
✗ 不写任何东西  —— 不上传、不 scan、不 sync、不发埋点
✗ 不改状态      —— 不 leak、不 reset
```

★ 怎么做到「验一个端点存不存在，却不执行它」：
**发一个错的 HTTP 方法**。405 = 存在，404 = 不存在。
这是 2026-09-26 那次审计的副产品 —— 而那一次顺带撞出了两个 500（§3）。

## ★★ 输出分两类，别混着读

```
  ✅/❌   判据 —— 有对错，❌ 就该当场处理
  📌     已知边界 —— 【不判对错】。把证据重新摆一遍，
                 让你在开隧道前再确认一次「我接受这些」
```

★ 第二类不是凑数：`docs/07` §6.3 的 E1/E2 是**已经被接受**的风险。
一个探针如果只报「通过」，会让人以为「没问题」—— 而实际是
「有几个已知的问题，我选择接受」。

用法：
    python scripts/probe_exposure.py                        # 本机 8080
    python scripts/probe_exposure.py --url http://localhost -u 用户:口令
    python scripts/probe_exposure.py --url https://xxx.cpolar.cn -u 用户:口令  # ★ 隧道那一侧
"""

import argparse
import base64
import json
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request

# ★ Windows 上 Python 读服务端响应可能按 GBK 解码而报错 —— 显式钉死 UTF-8。
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8")
    except Exception:
        pass

BASE = "http://localhost:8080"
AUTH = None

PASS, FAIL = [], []


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    print(f"  {'✅' if ok else '❌'} {name}")
    if detail:
        for line in str(detail).splitlines():
            print(f"       {line}")


def note(title, lines):
    """📌 已知边界 —— 不判对错，只把证据摆出来。"""
    print(f"\n  📌 {title}")
    for line in lines:
        print(f"       {line}")


def call(method, path, body=None, ctype="application/json", raw=None, timeout=15):
    """返回 (status, body)。★ 4xx/5xx 也读回来 —— 那正是要看的东西"""
    data = raw if raw is not None else (
        json.dumps(body).encode("utf-8") if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", ctype)
    if AUTH:
        req.add_header("Authorization", "Basic " + AUTH)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            txt = r.read().decode("utf-8", "replace")
            try:
                return r.status, json.loads(txt)
            except Exception:
                return r.status, txt[:200]
    except urllib.error.HTTPError as e:
        txt = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, txt[:200]
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"


def code_of(resp):
    return resp[0]


def preflight():
    """
    ★★★ 先确认「你打的是本应用」—— 不通过就**直接退出**，不往下跑。

    ## 为什么必须有这一道

    实测（2026-09-26）：把这个探针打到一个**根本不是本应用**的服务器上，
    它报了 **9 项通过**。原因是那台服务器对每个路径都回 200 + 一段 HTML，
    而探针里好几条判据是「**不是** 404 / **不是** 500」——
    对一个恒回 200 的目标，它们**真空通过**。

    ★★ 一份打错目标的绿色报告，比一份红报告**坏得多**：
      红报告你会去查；绿报告你会去开隧道。

    ## 判据

    `/api/health` 的响应里有 `data.application`，它的值是 `xbla-rag` ——
    那是这个应用的自报身份。★ 用**结构**判，不用「能不能连上」判：
    连得上只证明「那个端口有东西在听」。

    @return True = 是目标应用，可以继续
    """
    st, body = call("GET", "/api/health")

    if st == 401:
        print("\n❌ 401 —— 这一侧要 Basic Auth，但你没给口令。")
        print("   ★ 加上： -u 用户:口令")
        return False
    if st is None:
        print(f"\n❌ 连不上：{body}")
        return False
    if st != 200 or not isinstance(body, dict):
        print(f"\n❌ /api/health 返回 {st} —— 这不是本应用（或者它挂了）。")
        return False

    app = (body.get("data") or {}).get("application")
    if app != "xbla-rag":
        print(f"\n❌ /api/health 说自己是 {app!r}，不是 'xbla-rag'。")
        print("   ★ 【直接退出】—— 打错目标的绿色报告比红报告坏得多：")
        print("     好几条判据是「**不是** 404 / **不是** 500」，")
        print("     对一个恒回 200 的目标会【真空通过】。")
        return False

    print(f"\n✔ 目标确认：{app}（status={(body.get('data') or {}).get('status')}）")
    return True


# ============================================================
# 一、调试面必须关（★ 「不通过就得回滚」级别）
# ============================================================

DEBUG_PATHS = [
    "/api/debug/ratelimit/state",
    "/api/debug/llm/chat",
    "/api/debug/kb/search-text-stats",
    "/api/debug/agent/intent-tree",
    "/api/debug/mcp/tools",
    "/api/debug/eval/config",
    "/api/debug/profile/affinity",
]


def probe_debug_surface():
    print("\n【1】调试探针必须 404（★ 漏一个就能烧你的 API 额度 / 改状态）")

    bad = []
    for p in DEBUG_PATHS:
        st = code_of(call("GET", p))
        if st != 404:
            bad.append(f"{p} → {st}")
    check(f"七个 debug 端点全部 404（{len(DEBUG_PATHS)} 个）", not bad,
          "\n".join(bad) if bad
          else "★ 生产 profile 下这些控制器根本没注册（不是「注册了但拦住了」）")

    # actuator 白名单
    allow = {"health", "info", "circuitbreakers", "circuitbreakerevents"}
    leaked = []
    for name in ("env", "beans", "configprops", "mappings", "heapdump"):
        st = code_of(call("GET", f"/actuator/{name}"))
        if st == 200:
            leaked.append(name)
    check("actuator 没有泄露配置类端点（env/beans/configprops/mappings/heapdump）",
          not leaked, f"★ 泄露了：{leaked}" if leaked
          else f"白名单内的是 {sorted(allow)}")
    # ★ 白名单内的那几个【从隧道到不了】：nginx 只配了 /api/ /docs/ /assets/ /
    #   所以 /actuator/** 会掉进 SPA fallback。这里顺带确认一下它没被代理出去
    st = code_of(call("GET", "/actuator/health"))
    if st == 200:
        note("actuator/health 在这里是 200 —— 说明你打的【不是 nginx 那一侧】",
             ["★ 本机直连 8080 时 actuator 是可达的（开发实例本来就这样）。",
              "  走隧道（nginx:80）时它到不了：nginx 没有 /actuator 的 location，",
              "  会掉进 location / 的 try_files 返回 index.html。"])
    else:
        check("★ actuator 从这一侧【到不了】（nginx 没有它的 location）",
              st is not None, f"实际 {st}")


# ============================================================
# 二、简历讲稿不能出去
# ============================================================

def probe_private_docs():
    """
    私有文档不能出去（.gitignore 管不到 docker）。

    ## ★★★ 顺序很重要：先打【对照】，再打私有文档

    因为这一节的判据有个前提：**这一侧得真的提供 `/docs/`**。
    而 `/docs/` 是 **nginx 的活**（`alias /srv/public-docs/`）——
    直连应用（:8080）时它压根不存在，三个路径全是 404。

    ⇒ 那种情况下「私有文档 404」是**真的，但没有信息量**：
      它和「`/docs/` 整个不存在」长得一模一样。

    ★★ 所以对照不通过时，把整节降级成**不适用**，而不是报红 ——
    **假失败会训练人忽略失败**，而这一节的全部价值就是
    「真失败时你会当场停下来」。
    """
    print("\n【2】私有文档必须 404（.gitignore 管不到 docker）")

    control = "/docs/" + urllib.parse.quote("11-评测报告.md")
    st = code_of(call("GET", control))
    if st != 200:
        print(f"  ⏭  对照（评测报告）在这一侧是 {st} —— 说明这一侧【不提供 /docs/】。")
        print("      /docs/ 是 nginx 的活（alias /srv/public-docs/），应用本身没有它。")
        print("      本节只在打 Nginx / 隧道那一侧时有判据。")
        return

    check("★ 对照：评测报告【在】（先证明 /docs/ 真的被提供）", True,
          f"实际 {st}")

    # ★ docs/00 是简历讲稿、docs/09 是面试问答 —— 两个都不该出去
    bad = []
    for name in ("00-项目总览.md", "09-面试问答准备.md"):
        p = "/docs/" + urllib.parse.quote(name)
        s = code_of(call("GET", p))
        if s != 404:
            bad.append(f"{name} → {s}")
    check("★ 私有文档（00 / 09）必须 404", not bad,
          "\n".join(bad) if bad
          else "★ 白名单是【镜像构建时】生效的：Dockerfile 里逐个 COPY 两个文件，\n"
               "  目录里没有的东西，任何 URL 写法都拿不到")


# ============================================================
# 三、★★ 状态码必须对（2026-09-26 两批修复的判据）
# ============================================================

def probe_status_codes():
    print("\n【3】★ 错误的状态码 —— 曾经有【四个】是 500")

    # 405 —— 用错的动词（★ 这也是「探端点存不存在」的手法）
    st, body = call("GET", "/api/chat")
    check("用错动词 → 405（不是 500）", st == 405, f"实际 {st}")

    # ★★ 405 必须带 Allow（RFC 9110 §15.5.6）
    try:
        req = urllib.request.Request(BASE + "/api/chat", method="GET")
        if AUTH:
            req.add_header("Authorization", "Basic " + AUTH)
        with urllib.request.urlopen(req, timeout=15) as r:
            allow_hdr = r.headers.get("Allow")
    except urllib.error.HTTPError as e:
        allow_hdr = e.headers.get("Allow")
    except Exception:
        allow_hdr = None
    check("★★ 405 带 Allow 头（缺了调用方只能猜）",
          bool(allow_hdr) and "POST" in (allow_hdr or ""), f"Allow={allow_hdr}")

    # 400 —— 参数类型不对
    st, body = call("GET", "/api/chat/sessions?limit=abc")
    check("参数类型不对 → 400（不是 500）", st == 400, f"实际 {st}")

    # ★★ 400 的消息里不许出现客户端传的那个串（反射式注入）
    st, body = call("GET", "/api/chat/sessions?limit=%3Cscript%3E")
    msg = (body or {}).get("message", "") if isinstance(body, dict) else str(body)
    check("★★ 400 的消息：回了参数名、没回客户端传的值",
          st == 400 and "limit" in msg and "script" not in msg, f"message={msg!r}")

    # 400 —— 畸形 JSON
    st, _ = call("POST", "/api/chat", raw=b"{bad json")
    check("畸形 JSON → 400（不是 500）", st == 400, f"实际 {st}")

    # 415 —— 错的 Content-Type（★ 不真上传，用 json 打 multipart 端点）
    st, _ = call("POST", "/api/kb/documents", body={})
    check("错的 Content-Type → 415（不是 500）", st == 415, f"实际 {st}")

    # ★ 反例：正确的请求不该 4xx
    st, _ = call("GET", "/api/chat/sessions?limit=1")
    check("★ 反：合法请求 → 200（证明不是「什么都 4xx」）", st == 200, f"实际 {st}")


# ============================================================
# 四、端口
# ============================================================

def probe_ports():
    print("\n【4】只有 80 该对外（数据库/Redis 必须连不上）")

    host = urllib.parse.urlparse(BASE).hostname or "localhost"

    # ★★★ 本机跑时【跳过】这一节，而不是让它红。
    #     5432/6379 绑的是 127.0.0.1 ⇒ 从本机当然连得上，那是它们【该】有的样子。
    #     ⚠️ 让它红是个坏主意：**假失败会训练人忽略失败**，
    #     而这一节的全部价值就是「真失败时你会当场停下来」。
    if host in ("localhost", "127.0.0.1", "::1"):
        print("  ⏭  目标是本机 —— 5432/6379 绑的正是 127.0.0.1，从本机当然连得上。")
        print("      这一节只在打【公网地址】时有判据。")
    else:
        for port, name in ((5432, "PostgreSQL"), (6379, "Redis"), (5050, "pgAdmin")):
            s = socket.socket()
            s.settimeout(3)
            try:
                s.connect((host, port))
                open_ = True
            except Exception:
                open_ = False
            finally:
                s.close()
            check(f"{name} ({port}) 连不上", not open_,
                  "★ 连上了 —— 如果这一侧是公网，它已经暴露了")

    note("应用容器【没有】映射到宿主机",
         ["cpolar 只转发 80，所以隧道能碰到的只有 Nginx 那一层。",
          "★ 这让「不小心把应用直接暴露出去」变成一个连不上，而不是一个静默的泄露。"])


# ============================================================
# 五、★★★ 已知边界 —— 把证据再摆一次
# ============================================================

def probe_known_boundaries():
    print("\n【5】📌 已知边界 —— 不判对错，开隧道前请再确认一次「我接受这些」")

    # E1：身份头可编
    sid = _mcp_initialize(8)
    if sid:
        body = _mcp_call(sid, 8, "query_my_coupons")
        snippet = _tool_text(body)
        note("E1 · X-Xbla-User-Id 是明文未签名的 ⇒ 拿到口令 = 拿到所有人",
             ["实测（用 8 号身份开一个会话）：",
              f"  {snippet}",
              "★ 换个头值重开会话就是别人的数据。",
              "⚠️ 本探针只读了 8 号（演示数据），没有去读别人的。"])
    else:
        note("E1 · X-Xbla-User-Id 是明文未签名的 ⇒ 拿到口令 = 拿到所有人",
             ["⚠️ /mcp 从这一侧到不了（nginx 没有它的 location），所以这里没法现场演示。",
              "  但同一条路在 /api/chat 上是通的 —— 它吃同一个头。",
              "  详见 docs/07 §六 E1"])

    # E2：知识库可写
    st = code_of(call("GET", "/api/kb/documents/scan"))
    note("E2 · 知识库是【可写】的 —— 上传即入库，RAG 之后会引用它",
         [f"GET /api/kb/documents/scan → {st}（**不是 404** ⇒ 这条路径存在）",
          "★ 注意它是 400 不是 405：`scan` 在 GET 下撞上了 /api/kb/documents/{id}，",
          "  「scan」当 Long 解析失败 ⇒ 参数类型错。★ 这恰好说明"
          "「用错动词探存在性」这一步要看清拿到的是什么码。",
          "★★ 这一条比「花钱」严重得多：本项目说「引用是能区分『从知识库来的』",
          "   和『编的』的证据」—— 而一份被灌进去的文档会产生",
          "   【带着引用的、看起来完全合法的回答】。",
          "★ 演示完关隧道之前，确认知识库里没有多出不该有的文档。"])

    # 成本
    st, body = call("GET", "/api/status/metrics?window=all")
    if st == 200 and isinstance(body, dict):
        t = (body.get("data") or {}).get("traffic") or {}
        note("E3 · 每次问答都要花钱，而且没有配额",
             [f"这一侧统计到的提问数：{t.get('questions')}",
              "实测单价 ≈ 0.0009 元/次（166 次 / 0.3154 元）",
              "★ 单次可忽略，但拿到口令的人狂刷 1 万次 ≈ 9 元。"])
    else:
        note("E3 · 每次问答都要花钱，而且没有配额",
             ["实测单价 ≈ 0.0009 元/次。★ 无配额。"])

    # E4：开发实例裸奔（只在直连 8080 时有意义）
    host = urllib.parse.urlparse(BASE).hostname or "localhost"
    if host in ("localhost", "127.0.0.1") and BASE.endswith(":8080"):
        note("E4 · 你现在打的是【开发实例】——它绑 0.0.0.0 且没有 Basic Auth",
             ["auth_basic 是 nginx 的，不在 8080 身上；它跑 local profile，",
              "⇒ 七个 debug 控制器全部可达（【1】那一节在这里必然全红，这是正常的）。",
              "★ 缓解：共享网络里跑请加 --server.address=127.0.0.1"])


def _mcp_initialize(uid):
    body = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2025-11-25", "capabilities": {},
                       "clientInfo": {"name": "exposure-probe", "version": "1"}}}
    req = urllib.request.Request(BASE + "/mcp", data=json.dumps(body).encode("utf-8"),
                                 method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Xbla-User-Id", str(uid))
    if AUTH:
        req.add_header("Authorization", "Basic " + AUTH)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.headers.get("mcp-session-id")
    except Exception:
        return None


def _mcp_call(sid, uid, tool):
    body = {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
            "params": {"name": tool, "arguments": {}}}
    req = urllib.request.Request(BASE + "/mcp", data=json.dumps(body).encode("utf-8"),
                                 method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Xbla-User-Id", str(uid))
    req.add_header("mcp-session-id", sid)
    if AUTH:
        req.add_header("Authorization", "Basic " + AUTH)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.load(r)
    except Exception:
        return None


def _tool_text(body):
    try:
        t = body["result"]["content"][0]["text"]
        return t.splitlines()[0][:70]
    except Exception:
        return "（取不到）"


# ============================================================
# 六、人工确认
# ============================================================

def checklist():
    print("\n【6】★ 机器判不了的四条 —— 开隧道前请自己确认")
    print("""
       □ 口令是【强口令】，而且没发给过不该发的人 —— 它是唯一的访问控制
       □ 清楚 E1：拿到口令的人能读任意用户的订单与券
       □ 清楚 E2：拿到口令的人能往知识库灌内容
       □ 演示结束后真的把隧道关掉 —— 关客户端一条命令的事
""")


def main():
    global BASE, AUTH
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=BASE)
    ap.add_argument("-u", "--user", help="用户:口令（走 Nginx / 隧道时必须给）")
    args = ap.parse_args()

    BASE = args.url.rstrip("/")
    if args.user:
        AUTH = base64.b64encode(args.user.encode("utf-8")).decode("ascii")

    print("=" * 66)
    print(f"暴露面探针 · {BASE}" + ("  （带 Basic Auth）" if AUTH else "  （无认证）"))
    print("=" * 66)

    # ★★★ 打开发实例时，把话说在前面
    #     它是 local profile ⇒ 【1】的七个 debug 端点必然可达。
    #     ⚠️ 这不是「探针坏了」，而是【这一侧本来就不能暴露】。
    #     不提前说的话，跑的人会去查探针，而不是去看那个真正的问题。
    host = urllib.parse.urlparse(BASE).hostname or "localhost"
    if host in ("localhost", "127.0.0.1", "::1") and ":8080" in BASE:
        print("""
  ⚠️⚠️ 你打的是【开发实例】（:8080）。
       —— 它跑 local profile，所以【1】那一节【必然全红】，
          这是它本来的样子，不是探针坏了。
       ★ 要验暴露面，请打 Nginx 那一侧：
           python scripts/probe_exposure.py --url http://localhost -u 用户:口令
           python scripts/probe_exposure.py --url https://xxx.cpolar.cn -u 用户:口令
""")

    # ★★★ 先确认目标 —— 不通过就直接退出，不产出任何「通过 N 项」
    if not preflight():
        print("=" * 66)
        return 1

    probe_debug_surface()
    probe_private_docs()
    probe_status_codes()
    probe_ports()
    probe_known_boundaries()
    checklist()

    print("\n" + "=" * 66)
    print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    if FAIL:
        print("★ 失败清单（公网之前必须处理）：")
        for f in FAIL:
            print(f"  ✗ {f}")
    else:
        print("★ 判据全过 —— 但【5】那几条已知边界仍然成立，别把它们读成「没问题」。")
    print("=" * 66)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
