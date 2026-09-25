# -*- coding: utf-8 -*-
"""
多轮澄清探针（阶段 9.4）—— 「最自然的追问方式被澄清闸门挡掉」那笔债的验收。

    python scripts/probe_clarify.py                  # 3 个场景 × 1 次
    python scripts/probe_clarify.py --repeat 3       # 每个场景跑 3 次
    python scripts/probe_clarify.py --base http://localhost:8080

★★ 它量的是【两个数】：
     第一轮  那个模糊问题有没有被反问（"那个怎么样" → NEEDS_CLARIFICATION）
     第二轮  用户的回答有没有【又被挡回去】（9.4 要修的正是这个）
     ⇒ 「打破率」= 第二轮不再是澄清的比例

★ 为什么必须真跑：本探针量的是【模型行为】——
  「送长辈」这句话进到分类器时会不会被带上上下文影响，只有真模型能回答。
  单测能证明「prompt 里带了那一段」（ClarifyResumeIntegrationTest），
  但证明不了「带了之后模型确实改了判断」。

★★ 怎么量「9.4 之前是多少」：把开关关掉，跑同一个脚本 ——
      XBLA_AGENT_SLOTS_ENABLED=false ./mvnw spring-boot:run
   两条命令的输出就是这个 A/B 的两半。★ 文档里记着 5.5 那次的实测是 2/3
   （三次运行「澄清 / 放行 / 澄清」），但那是另一批问法、另一个日子 ——
   所以报告里两个数要【各写各的出处】，别混着比。

⚠️ 它会真实调用模型并花钱（每轮 1 次分类，第二轮还多一次生成），
   3 个场景一轮下来约 0.01 元。它【会写库】（chat_session / qa_log）。

Windows 注意：中文一律走 urllib 的 JSON body，不经过 shell（见 probe_kb.py 头部）。
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://localhost:8080"

# ── 场景：(模糊问题, 用户的回答) ──
#    第一个是 docs/05 §9.5 ② 记下的那个原样复现（「那个怎么样」）。
#    另外两个是同一族的其它说法（换一个模糊点、换一个槽位）。
SCENARIOS = [
    ("那个怎么样", "送长辈用的"),
    ("哪款好一点", "想给学生买个平板"),
    ("这个多少钱", "华为 MatePad"),
]


def post(path: str, payload: dict) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        BASE + path, data=body,
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=180) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get(path: str) -> dict:
    with urllib.request.urlopen(BASE + path, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def ask(question: str, session_no: str | None) -> dict:
    """★ 成功是 code=0（ApiResponse.CODE_SUCCESS），不是 HTTP 的 200"""
    result = post("/api/chat", {"sessionNo": session_no, "question": question})
    if result.get("code") != 0:
        raise RuntimeError(f"接口返回 {result.get('code')}: {result.get('message')}")
    return result["data"]


def qa_log(trace_id: str) -> dict:
    """读那一行的 intent_plan（★ 它是「恢复有没有生效」的唯一落库判据）

    ★ 这个端点的 data 是【一个对象】不是数组（一个 traceId 一行），
      写成 rows[0] 会 KeyError: 0 —— 第一次跑就是这么挂的。
    """
    try:
        result = get(f"/api/debug/mcp/qa-log?traceId={trace_id}")
    except urllib.error.HTTPError:
        return {}
    if result.get("code") != 0:
        return {}
    data = result.get("data")
    return data if isinstance(data, dict) else {}


def short(text: str, n: int = 46) -> str:
    flat = " ".join((text or "").split())
    return flat if len(flat) <= n else flat[:n] + "…"


def main() -> int:
    global BASE

    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=BASE, help="服务地址（默认本机 8080）")
    parser.add_argument("--repeat", type=int, default=1, help="每个场景跑几次（默认 1）")
    args = parser.parse_args()

    BASE = args.base.rstrip("/")

    print("=" * 72)
    print(f"多轮澄清探针 —— {len(SCENARIOS)} 个场景 × {args.repeat} 次")
    print("=" * 72)

    clarified = 0        # 第一轮被反问的场景数
    broken = 0           # 第二轮【不再】被挡回去的
    rows = []

    for vague, answer in SCENARIOS:
        for i in range(args.repeat):
            tag = f"{vague} → {answer}" + (f"  #{i + 1}" if args.repeat > 1 else "")
            first = ask(vague, None)
            if first.get("intent") != "NEEDS_CLARIFICATION":
                print(f"\n⚠️  {tag}")
                print(f"    第一轮没被反问（intent={first.get('intent')}）—— 这个场景本轮不计入")
                print(f"    回答：{short(first.get('answer'))}")
                continue

            clarified += 1
            session_no = first.get("sessionNo")
            print(f"\n▶ {tag}")
            print(f"    ① 反问：{short(first.get('answer'), 60)}")

            second = ask(answer, session_no)
            intent2 = second.get("intent")
            ok = intent2 != "NEEDS_CLARIFICATION"
            broken += 1 if ok else 0

            plan = qa_log(second.get("traceId")).get("intentPlan")
            resumed = None
            if plan:
                try:
                    resumed = json.loads(plan).get("resumed")
                except (TypeError, ValueError):
                    resumed = None

            print(f"    ② {'✅ 放行' if ok else '❌ 又被挡回去'}  intent={intent2}"
                  f"  resumed={resumed}")
            print(f"       回答：{short(second.get('answer'))}")
            rows.append((tag, intent2, resumed))

    print("\n" + "=" * 72)
    if clarified == 0:
        print("★ 一次澄清都没触发 —— 多半是模型把它们都判成业务意图了，本轮无结论")
        return 1
    print(f"★ 打破率（第二轮不再被澄清）：{broken}/{clarified}"
          f" = {broken / clarified:.0%}")
    print("★ 对照：把 xbla.agent.slots.enabled 关掉跑同一个脚本，就是 9.3 的样子")
    print("⚠️ 单次样本很小（每个场景 1 次），别把它当结论 —— 见 docs/06 的噪声口径")
    return 0


if __name__ == "__main__":
    sys.exit(main())
