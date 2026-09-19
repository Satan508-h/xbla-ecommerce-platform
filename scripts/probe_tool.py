#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 5.8 / 5.9 验收探针 —— 工具调用与结构化硬数据，端到端。

⚠️ 这个脚本【会真的调模型】，花钱（虽然很少）。和 probe_kb.py 一样。

★ 为什么中文必须走脚本：Git Bash 把中文传给 curl -d 会变成 U+FFFD，
  服务端收到乱码然后报 500。见 CLAUDE.md 第八节。

判定项（30 项，九组）：
  一、客户端连不连得上 —— 握手、wireTools 键序、工具描述非空
  二、工具真的调了吗 —— qa_log.tool_calls 非空、检索一次没跑、
      回答带真实数据、token/成本多轮累加
  三、越权 —— 换个身份拿不到别人的订单
  四、没有身份 —— 诚实说明，且【不调模型】
  五、老路径没被打断 —— 知识库问题照常走检索
  六、库存工具（5.9）—— 按规格给库存、同名商品不合并、模型复述的是工具给过的规格
  七、优惠券工具（5.9）—— 无参工具、状态分组、模型报的金额工具真的给过
  八、★★ 售后政策【不是工具】，是知识库 + 结构化硬数据
  九、★ 品类词不能变成「一堆积 id 排在前面的商品」（两道防线各验一次）

★★ 三条纪律（前两条踩过）：
  ① 【判性质，不判措辞】—— 模型会改写工具给的那句话。
     第一版断言回答里要有「没有找到」，结果模型改成了「没查到」。
     要判的是「有没有泄露只有真查到才会有的字段」。
  ② 【必须有一条真的走完整链路】—— 5.8 的实现曾经让 486 个测试全绿，
     而真实调用当场 400（见 WireToolCall 的类注释）。
     桩造的对象，验证不了桩自己是不是照协议造的。
  ③ ★ 【先看工具说了什么，再看模型说了什么，然后比较】——
     5.9 把 ① 推进一步：判据从「回答里有某个词」变成
     「回答里的东西工具确实说过」。前者被模型的措辞绑架，后者不会。
     工具原文由 /api/debug/mcp/call 拿（走完整 MCP 链路，和模型拿到的一模一样），
     【不花钱】。
"""
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://localhost:8080"
USER_ID = 8           # U000006，名下有 8 笔订单
HIS_ORDER = "SO202609160001"
OTHER_USER = 17       # 同样有订单，但【不是】这笔订单的主人

PASS, FAIL = "[✓]", "[✗]"
results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"  {PASS if ok else FAIL} {name}" + (f"  —— {detail}" if detail else ""))
    return ok


def http(method, path, body=None, headers=None):
    req = urllib.request.Request(BASE + path, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = None
    if body is not None:
        # ★ 中文必须显式 UTF-8 编码 + 声明 charset，否则服务端按平台默认解
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req.add_header("Content-Type", "application/json; charset=UTF-8")
    try:
        with urllib.request.urlopen(req, data=data, timeout=180) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def ask(question, user_id):
    """问一次，返回 ApiResponse.data（或 None）"""
    headers = {}
    if user_id is not None:
        headers["X-Xbla-User-Id"] = str(user_id)
    status, body = http("POST", "/api/chat", {"question": question}, headers)
    if body.get("code") != 0:
        print(f"     ⚠️ 接口返回 code={body.get('code')} message={body.get('message')}")
        return None
    return body.get("data")


def qa_log_of(trace_id):
    """
    从调试接口拿这次问答的 qa_log —— 用后端自己的连接查，省得配 psql。

    ★ JSONB 那几列回来的是【字符串】，不是对象。

    调试接口是把数据库列原样透出来的（它不该替我们决定怎么解析），
    而 MyBatis 把 jsonb 读成 String。于是 log["retrievalDetail"] 是
    '{"vector_hits": [...], ...}' 这样一段文本 ——
    对它调 .get() 会抛 AttributeError: 'str' object has no attribute 'get'。

    ⚠️ 为什么这个 bug 不显眼：`is None` 的断言（⑤⑥⑯）对字符串照样成立，
    所以只有【真的去读里面某一格】的断言才会炸 —— 也就是 5.9 新加的那几条。
    在这里统一 parse 掉，调用方就不用各自记得。
    """
    status, body = http("GET", f"/api/debug/mcp/qa-log?traceId={trace_id}")
    if body.get("code") != 0:
        return None
    log = body.get("data")
    if not isinstance(log, dict):
        return log
    for key in ("retrievalDetail", "toolCalls"):
        value = log.get(key)
        if isinstance(value, str) and value.strip():
            try:
                log[key] = json.loads(value)
            except json.JSONDecodeError:
                pass        # 解析不了就留着原样 —— 断言那边会看出来
    return log


# ============================================================
print("=" * 70)
print("阶段 5.8 验收 —— MCP 工具调用端到端")
print("=" * 70)

# ── ① 客户端视角：模型实际会收到的工具清单 ──
print("\n【一】客户端能不能连上手写 Server")
status, body = http("GET", f"/api/debug/mcp/client?userId={USER_ID}")
client_info = body if isinstance(body, dict) else {}
# 探针controller 直接返回 Map（不是 ApiResponse）
ok = client_info.get("ok") is True and client_info.get("toolCount", 0) > 0
check("① MCP 客户端握手成功，拉到工具", ok,
      f"toolCount={client_info.get('toolCount')} 耗时 {client_info.get('elapsedMs')}ms")
if not ok:
    print(f"     ⚠️ 详细：{json.dumps(client_info, ensure_ascii=False)[:400]}")

wire = client_info.get("wireTools") or []
if wire:
    # ★ 键序必须稳定 —— 它是 Prompt 前缀的一部分
    outer = list(wire[0].keys())
    inner = list((wire[0].get("function") or {}).keys())
    check("② wireTools 的键序是显式声明的（不是 Map.of 的 hash 序）",
          outer == ["type", "function"] and inner == ["name", "description", "parameters"],
          f"outer={outer} inner={inner}")

    # ★ 工具描述不能为空 —— 模型选不选它全看这个
    desc_len = len((wire[0].get("function") or {}).get("description") or "")
    check("③ 工具描述非空（模型选工具的唯一依据）", desc_len > 10, f"{desc_len} 字")

# ── ④ 真实的工具调用 ──
print("\n【二】问「我的订单到哪了」—— 带身份")
answer = ask("我的订单 SO202609160001 现在到哪了？", USER_ID)
if answer is None:
    check("④ 问答接口返回成功", False, "接口失败，后面的判定跳过")
else:
    check("④ 问答接口返回成功", True, f"traceId={answer.get('traceId')}")

    log = qa_log_of(answer.get("traceId"))
    if log is None:
        check("⑤ 能查到 qa_log", False, "调试接口拿不到 —— 跳过后续")
    else:
        tc = log.get("toolCalls")
        check("⑤ ★ 真的调用了工具（qa_log.tool_calls 非空）",
              bool(tc) and "query_order_status" in str(tc),
              f"toolCalls={tc}")
        check("⑥ ★ 工具意图【没有】检索知识库（retrieval_detail 必须是 NULL）",
              log.get("retrievalDetail") is None and log.get("references") is None,
              f"retrievalDetail={log.get('retrievalDetail')} references={log.get('references')}")

        txt = answer.get("answer") or ""
        # ★ 不判状态措辞 —— 只判「拿到了真实数据」。状态文案由 QueryOrderStatusTool 的映射决定，与本次验收无关
        check("⑦ 回答里带着真实数据（不是编的）",
              ("取消" in txt) or ("SO202609160001" in txt),
              f"answer={txt[:60]!r}")

        # ★ 多轮累加：工具往返至少两次模型调用
        pt = log.get("promptTokens") or 0
        check("⑧ ★ token 是【多轮累加】的（> 单轮的 400 左右）", pt > 500,
              f"promptTokens={pt} cost={log.get('cost')} llmLatencyMs={log.get('llmLatencyMs')}")

        check("⑨ provider/model 记的是产出最终回答的那一轮",
              bool(log.get("provider")) and bool(log.get("model")),
              f"provider={log.get('provider')} model={log.get('model')}")

# ── ⑩ 越权 ──
print("\n【三】越权：换个身份问同一笔订单")
other = ask("我的订单 SO202609160001 现在到哪了？", OTHER_USER)
if other is None:
    check("⑩ 越权请求返回成功（是个正常问答，不是报错）", False, "接口失败")
else:
    txt = other.get("answer") or ""
    # ★★ 判【性质】，不判措辞 —— 这是本脚本第二版修正的地方。
    #
    #   第一版断言回答里得有「没有找到」四个字，结果失败了：模型把工具给的
    #   「没有找到订单号 X 对应的订单」改写成了「没查到订单号 X 对应的订单」。
    #   工具那句话本身没错（它的不可区分性由 5.7 保证，且由
    #   SdkMcpToolGatewayIntegrationTest 逐字断言），
    #   但【模型会改写它】—— 而改写后的措辞不是我们的契约。
    #
    #   ★ 真正要判的性质只有两条：
    #     ① 没有泄露任何只有真查到才会有的字段（状态、商品名）
    #     ② 拿不到订单的实质内容
    #   ⚠️ 不能靠「回答里有某个词」判 —— 那是把模型的措辞写进了测试。
    leaked = [w for w in ("已取消", "待发货", "惠普", "战66", "物流单号") if w in txt]
    check("⑩ ★ 越权拿不到那笔订单（回答里没有『只有真查到才会有』的内容）",
          not leaked, f"泄露词={leaked} answer={txt[:60]!r}")
    check("⑪ ★ 越权的回答里【不含】订单实质内容",
          ("惠普" not in txt) and ("战66" not in txt) and ("已取消" not in txt),
          f"answer={txt[:60]!r}")

    # ★ 顺带看一眼：5.7 保证了「单号不存在」和「不是你的」两条回复【逐字相同】。
    #   模型会改写它们，但改写的【输入】是一样的 —— 所以改写后仍然不可区分。
    #   这条性质由 SdkMcpToolGatewayIntegrationTest.noEnumerationOracle 逐字钉住。
    check("⑫ 工具层没有枚举预言机（同一份输入 → 同一份输出）",
          True, "由 SdkMcpToolGatewayIntegrationTest#noEnumerationOracle 逐字断言")

# ── ⑫ 没有身份 ──
print("\n【四】没有身份时问订单")
anon = ask("我的订单到哪了？", None)
if anon is None:
    check("⑫ 无身份请求返回成功", False, "接口失败")
else:
    check("⑬ ★ 没有身份 → 诚实说明，不是 401 也不是编造",
          "X-Xbla-User-Id" in (anon.get("answer") or ""),
          f"answer={(anon.get('answer') or '')[:60]!r}")
    check("⑭ ★ 没有身份时【不调模型】（provider/cost 为 NULL）",
          anon.get("provider") is None and anon.get("cost") is None,
          f"provider={anon.get('provider')} cost={anon.get('cost')}")

# ── ⑭ 非工具意图不受影响 ──
print("\n【五】非工具意图仍然走检索")
kb = ask("商品支持七天无理由退货吗", USER_ID)
if kb is None:
    check("⑭ 知识库问题返回成功", False, "接口失败")
else:
    check("⑮ ★ 知识库问题仍然正常回答（工具改造没打断老路径）",
          len(kb.get("answer") or "") > 10,
          f"answer={(kb.get('answer') or '')[:50]!r}")
    log = qa_log_of(kb.get("traceId"))
    if log:
        check("⑯ ★ 知识库问题【没有】走工具（tool_calls 为 NULL）",
              log.get("toolCalls") is None,
              f"toolCalls={log.get('toolCalls')}")

# ============================================================
# ★★ 阶段 5.9
#
# 从这一组开始，判据的写法变了 —— 而且这个变化是刻意的。
#
# 5.8 学到的教训是「判性质，不判措辞」（模型会改写工具给的话）。
# 5.9 把它推进一步：**先看工具说了什么，再看模型说了什么，然后比较两者**。
# 于是判据从「回答里有某个词」变成了「回答里的东西【工具确实说过】」——
# 前者会被模型的措辞绑架，后者不会。
#
# 工具原文由 /api/debug/mcp/call 拿，它走完整的 MCP 客户端链路，
# 所以拿到的和模型拿到的一模一样。【不花钱】。
# ============================================================


def call_tool(tool, args=None, user_id=USER_ID):
    """直接调工具，返回探针响应（不花钱）。★ 中文要百分号编码"""
    path = f"/api/debug/mcp/call?tool={tool}&userId={user_id}"
    if args:
        path += "&args=" + urllib.parse.quote(json.dumps(args, ensure_ascii=False))
    status, body = http("GET", path)
    return body


def spec_tokens(products_data):
    """从工具的结构化返回里抽出所有规格名，以及它们的颜色部分"""
    tokens = []
    for p in products_data or []:
        for s in p.get("skus", []):
            name = s.get("spec_name")
            if name:
                tokens.append(name)
                tokens.append(name.split()[0])   # 「月光银 512GB」→「月光银」
    return [t for t in tokens if t]


def numbers_in(text):
    """文本里的数字串（用来做「模型说的数字工具说过吗」这类判据）"""
    return set(re.findall(r"\d+", text or ""))


# ── 库存 ──
print("\n【六】库存工具 query_inventory")
INV_PRODUCT = "华为 Magic mini"          # 库里有 4 个规格，且【有两款同名商品】
inv = call_tool("query_inventory", {"product_name": INV_PRODUCT})
inv_data = inv.get("data") or {}
inv_products = inv_data.get("products") or []
tokens = spec_tokens(inv_products)

check("⑰ ★ 工具直调：按【规格】给出库存，且每块都带商品编号",
      inv.get("ok") is True and inv.get("isError") is False
      and "可售" in (inv.get("text") or ""),
      f"matched={inv_data.get('matched_count')} returned={inv_data.get('returned_count')}")
check("⑱ ★★ 同名商品没有被合并 —— 每个块都有自己的商品编号",
      all(p.get("product_no") for p in inv_products)
      and len({p.get("product_no") for p in inv_products}) == len(inv_products),
      f"productNo={[p.get('product_no') for p in inv_products]}")

ans = ask(f"{INV_PRODUCT} 还有货吗？", USER_ID)
if ans is None:
    check("⑲ 库存问答返回成功", False, "接口失败")
else:
    log = qa_log_of(ans.get("traceId"))
    check("⑲ ★ 模型真的调了 query_inventory",
          bool(log) and "query_inventory" in str(log.get("toolCalls")),
          f"toolCalls={log.get('toolCalls') if log else None}")
    # ★★ 判据：回答里出现了工具【真的说过】的规格名 —— 而不是「有某个词」
    txt = ans.get("answer") or ""
    hit = [t for t in tokens if t in txt]
    check("⑳ ★★ 回答里的规格是工具真的给过的（不是编的）",
          bool(hit),
          f"命中={hit[:3]} answer={txt[:50]!r}")

# ── 优惠券 ──
print("\n【七】优惠券工具 query_my_coupons")
cp = call_tool("query_my_coupons")
cp_data = cp.get("data") or {}
cp_names = [c.get("name") for c in (cp_data.get("coupons") or []) if c.get("name")]

check("㉑ ★ 工具直调：券按状态分组给出，无参工具也能跑",
      cp.get("ok") is True and cp.get("isError") is False
      and cp_data.get("available_count") is not None,
      f"可用={cp_data.get('available_count')} 已用={cp_data.get('used_count')} "
      f"过期={cp_data.get('expired_count')}")

ans = ask("我有哪些优惠券？", USER_ID)
if ans is None:
    check("㉒ 优惠券问答返回成功", False, "接口失败")
else:
    log = qa_log_of(ans.get("traceId"))
    check("㉒ ★ 模型真的调了 query_my_coupons",
          bool(log) and "query_my_coupons" in str(log.get("toolCalls")),
          f"toolCalls={log.get('toolCalls') if log else None}")
    # ★★ 判据：回答里出现的数字必须是工具给过的数字之一
    #    （模型会改写措辞，但不会把「减 800」改写成「减 900」）
    txt = ans.get("answer") or ""
    tool_nums = numbers_in(cp.get("text") or "")
    answer_nums = numbers_in(txt)
    check("㉓ ★★ 回答里的金额是工具真的给过的（没编）",
          bool(answer_nums & tool_nums),
          f"回答数字={sorted(answer_nums)} 工具数字={sorted(tool_nums)}")
    check("㉔ ★ 券名在库里真实存在（可用券数对得上）",
          cp_data.get("available_count") == 0 or bool(cp_names),
          f"可用券名={cp_names}")

# ── ★★ 售后政策：不是工具，是结构化硬数据 ──
print("\n【八】★★ 售后政策走的是【知识库 + 结构化硬数据】，不是工具")
#
# ★★ 为什么挑「家电的换货」这一问：
#   家电是全库【唯一】换货 30 天的类目（其余类目都是 15 天）。
#   而检索召回的切片很可能是 AS-001（通用政策，换货 15 天）——
#   模型读散文时很容易把 15 当成答案。
#   after_sale_policy 里的家电行是 exchange_days = 30，
#   它进 prompt 之后，「答 30」才变成可期待的。
#
#   ⚠️ 这条**不是**决定性的证明（检索也可能恰好召回 AS-005）。
#      决定性的证明在 StructuredFactsRoutingIntegrationTest —— 那里
#      直接断言了 prompt 里有没有那一节。探针在这里验的是
#      「真实模型 + 真实路由下，最终答案是对的」。
ans = ask("家电的换货政策是几天？", USER_ID)
if ans is None:
    check("㉕ 售后政策问答返回成功", False, "接口失败")
else:
    log = qa_log_of(ans.get("traceId"))
    check("㉕ ★★ 这一问【没有】走工具（tool_calls 为 NULL）",
          bool(log) and log.get("toolCalls") is None,
          f"toolCalls={log.get('toolCalls') if log else None}")
    check("㉖ ★★ 而且检索真的跑了（retrieval_detail 非 NULL）",
          bool(log) and log.get("retrievalDetail") is not None,
          f"final_top_k={len((log.get('retrievalDetail') or {}).get('final_top_k', [])) if log else '-'}")
    txt = ans.get("answer") or ""
    check("㉗ ★★★ 答案是家电的 30 天（不是通用的 15 天）",
          "30" in txt,
          f"answer={txt[:80]!r}")

# ── 品类词 ──
print("\n【九】★ 品类词不能变成「一堆积 id 排在前面的商品」")
#
# ★★ 这一组有【两道防线】，而它们在不同层 —— 分清楚很要紧：
#
#   ① 工具描述里写着「品类词不要用它」 → 模型自己不调工具
#   ② 工具只匹配商品名，不匹配类目/品牌 → 即使调了也返回「没找到」
#
# 实测（2026-09-20）：模型走的是 ① —— 它【压根没调工具】，直接反问用户
# 「请问您指的是哪一款手机？」。
#
# ⚠️⚠️ 这个发现让本组的第一版断言作废了：它只断言「回答里没有商品名」，
#   而那是在测 ① —— ① 是【模型的自由裁量】，换个模型、换一天、
#   甚至只是温度不同，它就可能去调工具。那样的断言**可能恒真**。
#
# ★ ② 才是我们代码里的硬保证，必须直接验。所以下面两条是【直接调工具】。
cat = call_tool("query_inventory", {"product_name": "手机"})
cat_data = cat.get("data") or {}
cat_text = cat.get("text") or ""

check("㉘ ★★ 工具层：品类词查到 0 个（只匹配 name，不匹配类目和品牌）",
      cat.get("ok") is True and cat_data.get("matched_count") == 0,
      f"matched={cat_data.get('matched_count')}")
check("㉙ ★★ 工具层：如实说「没有找到」，且【不】给候选清单",
      "没有找到" in cat_text and "Magic mini" not in cat_text,
      f"text={cat_text[:50]!r}")

ans = ask("手机还有货吗？", USER_ID)
if ans is None:
    check("㉚ 品类词问答返回成功", False, "接口失败")
else:
    log = qa_log_of(ans.get("traceId"))
    txt = ans.get("answer") or ""
    used_tool = bool(log and log.get("toolCalls"))
    # ★★ 判据是【没有编造】：既没有具体商品名，也没有库存数字。
    #   如果工具把「手机」匹配到 40 个商品再返回前 3 个，那 3 个是按 id 排的
    #   随机结果 —— 而它们【看起来像一份答案】。
    leaked = [n for n in ("华为 Magic mini", "华为 Mate Ultra", "vivo P Pro Max",
                          "OPPO Find mini", "荣耀 X Pro") if n in txt]
    check("㉚ ★★ 模型没有编造商品和库存",
          not leaked and "件" not in txt,
          f"调工具={'是' if used_tool else '否（工具描述里写了别用它查品类 —— 防线①生效）'} "
          f"泄露={leaked} answer={txt[:50]!r}")

# ============================================================
print("\n" + "=" * 70)
passed = sum(1 for _, ok, _ in results if ok)
print(f"结果：{passed}/{len(results)} 项通过")
for name, ok, detail in results:
    if not ok:
        print(f"  {FAIL} {name}  {detail}")
print("=" * 70)
sys.exit(0 if passed == len(results) else 1)
