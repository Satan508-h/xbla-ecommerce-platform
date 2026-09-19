# -*- coding: utf-8 -*-
"""
会话记忆探针（阶段 5.5）—— 验收标准 3 的落点。

    连续 10 轮对话后，早期提到的商品偏好仍被记住

用法：
    python scripts/probe_memory.py                 # 默认 10 轮
    python scripts/probe_memory.py --rounds 4      # 少跑几轮（省钱）

★ 这个脚本【会真实调用模型并花钱】（每轮：1 次分类 + 1 次生成，
  第 1 轮还多一次向量化 + 重排）。10 轮大约几分钱。
⚠️ 它【不写库】—— 走的是 /api/chat，但那会往 chat_session/chat_message/qa_log
  灌数据。所以本脚本跑完的数据和真实使用混在一起。

★ 为什么必须真跑一遍，而不是靠单测：
  ChatHistoryIntegrationTest 能证明「历史被拼进了请求」，
  但证明不了「模型真的用上了它」—— 后者是模型行为，只能观察。

★ 判定方式：第 1 轮埋两个只有它说过的细节（预算 / 用途），
  最后一轮问一个必须同时用到这两个细节才能答好的问题。
  看最后一轮的回答里有没有它们。
  这是【人工可复核】的判定，不是断言 —— 所以脚本只打印证据，不打印 pass/fail。

Windows 注意：中文一律走 urllib 的百分号编码，不经过 shell（见 probe_kb.py 头部）。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://localhost:8080"

# ── 第 1 轮埋进去的两个细节，最后一轮必须用上 ──
SEED_BUDGET = "两千左右"
SEED_USAGE = "主要用来跟孙子视频通话"

SEED_QUESTION = f"我想给爸妈买个手机，预算{SEED_BUDGET}，{SEED_USAGE}，有什么要注意的吗"

# ── 中间几轮：故意问一些【和手机无关】的事，把第 1 轮挤出「最近一轮」 ──
FILLER = [
    "退货要几天",
    "退款多久到账",
    "优惠券怎么用",
    "拆封了还能退货吗",
    "怎么申请售后",
    "以旧换新的旧机估价有效期多久",
    "发票能开纸质的吗",
    "收到货发现损坏怎么办",
]

# ── 探针 A：★ 记忆探针 ──
#    这句话【自成一体】（分类器不该判它信息不足），
#    但【预算和用途都没提】—— 要答到点上就得用上第 1 轮的细节。
#    这是唯一能把「记忆生效了」和「澄清闸门放行了」分开的形状。
PROBE_MEMORY = "推荐一款适合老人的手机"

# ── 探针 B：★ 闸门探针 ──
#    最自然的追问方式（用「我一开始说的」指代），但【它单独拿出来不能回答】——
#    所以分类器会判它信息不足、触发澄清反问，历史压根走不到生成那一步。
#    这条是【已知局限】的现场复现，不是期望它通过。
PROBE_GATE = "按照我一开始说的预算和用途，你推荐哪款？"


def post(path: str, payload: dict) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        BASE + path, data=body,
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=180) as resp:
        return json.loads(resp.read().decode("utf-8"))


def ask(question: str, session_no: str | None) -> dict:
    result = post("/api/chat", {"sessionNo": session_no, "question": question})
    # ★ 统一响应封装里成功是 code=0（ApiResponse.CODE_SUCCESS），不是 HTTP 那个 200 ——
    #   这里写成 200 的话每一次调用都会被当成失败
    if result.get("code") != 0:
        raise RuntimeError(f"接口返回 {result.get('code')}: {result.get('message')}")
    return result["data"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rounds", type=int, default=10,
                        help="总共问几轮（默认 10，和验收标准对齐）")
    args = parser.parse_args()

    rounds = max(4, args.rounds)
    filler = (FILLER * ((rounds // len(FILLER)) + 1))[:rounds - 3]

    # 第 1 轮埋细节 → 中间轮把它挤出「最近一轮」→ 最后两轮分别是两个探针
    questions = [SEED_QUESTION] + filler + [PROBE_MEMORY, PROBE_GATE]

    print(f"会话记忆探针 —— 共 {len(questions)} 轮（第 1 轮埋细节，最后两轮各一个探针）")
    print("=" * 78)

    answers: dict[str, dict] = {}
    session_no = None
    for i, question in enumerate(questions, start=1):
        started = time.time()
        try:
            data = ask(question, session_no)
        except urllib.error.URLError as e:
            print(f"\n✗ 连不上服务（{BASE}）：{e}")
            print("  先启动应用：./mvnw spring-boot:run")
            return 1
        except RuntimeError as e:
            print(f"\n✗ 第 {i} 轮失败：{e}")
            return 1

        session_no = data.get("sessionNo") or session_no
        elapsed = (time.time() - started) * 1000

        # 只详细打第 1 轮和最后两个探针，中间的压成一行
        if i == 1 or i > len(questions) - 2:
            print(f"\n【第 {i} 轮】{question}")
            print(f"  intent : {data.get('intent')}")
            print(f"  回答   : {data.get('answer')}")
            print(f"  耗时   : {elapsed:.0f}ms  引用 {len(data.get('references') or [])} 条")
        else:
            print(f"  [{i:>2}] {question:<22} intent={str(data.get('intent')):<18} "
                  f"{elapsed:>5.0f}ms")
        answers[question] = data

    # ── 两个探针分别判定 ──
    print()
    print("=" * 78)
    print("★ 验收判定（人工复核，脚本不作断言）")
    print(f"  第 1 轮埋的细节：预算「{SEED_BUDGET}」、用途「{SEED_USAGE}」")

    mem = answers[PROBE_MEMORY]
    mem_answer = mem.get("answer") or ""
    print()
    print(f"【探针 A · 记忆】{PROBE_MEMORY}")
    print(f"  intent = {mem.get('intent')}   引用 {len(mem.get('references') or [])} 条")
    print(f"  预算被用上了吗：{'✓ 提到了' if SEED_BUDGET in mem_answer else '✗ 没提到'}")
    print(f"  用途被用上了吗：{'✓ 提到了' if SEED_USAGE in mem_answer else '✗ 没提到'}")
    print("  ⚠️ 这句话【自成一体】（分类器会放行），但【没提预算和用途】——")
    print("     答到点上就必须用上第 1 轮的细节。这是唯一能把「记忆生效」")
    print("     和「澄清闸门放行」分开的形状。")

    gate = answers[PROBE_GATE]
    print()
    print(f"【探针 B · 闸门】{PROBE_GATE}")
    print(f"  intent = {gate.get('intent')}   引用 {len(gate.get('references') or [])} 条")
    print(f"  回答   : {(gate.get('answer') or '')[:80]}")
    if gate.get("intent") == "NEEDS_CLARIFICATION":
        print("  △ 这是【已知局限】的现场复现，不是失败：")
        print("    分类器只看这一句话，判它信息不足 → 澄清短路在检索和生成【之前】→")
        print("    历史压根没被用上。要修它得让历史进分类 prompt，")
        print("    而那会动到「分类器只看这一句话」这个前提（5.2 的 95% 和")
        print("    5.4 的 20/20 都建立在它之上）。见 docs/05 §9.5 ①。")
    else:
        print("  ★ 这次没触发澄清 —— 说明分类器把它判成了可回答的问题。")

    print()
    print("  ⚠️ 「没提到」不一定是记忆失效 —— 也可能是模型选择了不提。")
    print("     要区分这两者，最直接的办法是和【关掉记忆】跑一遍对比：")
    print("       把 application.yml 的 xbla.chat.history.enabled 改成 false，")
    print("       重启，再跑一次本脚本，比对两个探针的回答。")
    print(f"\n  本轮 sessionNo = {session_no}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
