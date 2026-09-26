# -*- coding: utf-8 -*-
"""
推荐排序的 A/B 探针（阶段 9.5）—— 「个性化有没有让推荐变好」的可复现判据。

    # ① 开个性化（默认）起服务，跑一轮存下来
    python scripts/probe_recommend.py --out eval_results/recommend-profile-on.json

    # ② 关掉开关【重启】服务（开关是启动时读的），再跑一轮
    #    XBLA_AGENT_PROFILE_ENABLED=false ./mvnw spring-boot:run
    python scripts/probe_recommend.py --out eval_results/recommend-profile-off.json

    # ③ 两张表 —— 结论在这里，不在上面任何一次里
    python scripts/probe_recommend.py --compare eval_results/recommend-profile-off.json \
                                              eval_results/recommend-profile-on.json

★ 【不花钱】：只走 /api/debug/mcp/call，这条路径上没有任何模型调用。
★ 【确定性自检】：--repeat 3 会把每个组合连调 3 次并断言逐字节相同 ——
  「同分商品的先后由商品编号兜底」这件事一旦退化（比如引入 HashMap 迭代序），
  症状正是「同一个问题两次问，排出来的顺序不一样」。

★★ 判据在 `data/eval/recommend-gold.yml`（人工标注，`status: reviewed`）。
   一个 need 下的候选**字面重合分完全相同**（`suitable_for` 是同一句话），
   所以排序**完全**由「偏好加分 + tie-breaker + 商品编号」决定 ——
   没有这一份与实现正交的标注，A/B 只能证明「输出变了」，证明不了「变好了」。

★★★ 四个指标的口径（抄自 gold 第四节，别在这里另立一套）：

    hit@3      top_n 里落在【甲档】的件数
    reject@3   top_n 里落在【丙档】的件数   ← 主指标：明显不合适的不能进来
    changed    picks 序列和基线是否不同      ← 机制：偏好有没有生效
    tie_group  ★★ 最高分组有几件 + 返回的几件在不在里面  ← 【必报】

   ★ 不报总分 / 平均分：**同一个 need 对不同用户，好坏方向可以相反**，
     一个平均值会把「对 A 变好、对 B 变差」抹成「基本持平」。

⚠️ `tie_group` 里的甲/丙计数是【返回的那几件】的，不是整个并列组的 ——
   工具只返回 top_n 件，并列组里还有多少件甲档**看不见**。
   所以脚本报的是「并列 N 件（返回 k 件全在其中）」+ 一个 `⚠ 并列` 标记，
   标记的含义是：**换一条 tie-breaker 规则，这 3 件就可能不是这 3 件**。
   把它读成「编号恰好排到了甲档」或「个性化生效了」都是误读。

Windows 注意：中文一律走 urllib 的 JSON + 百分号编码，不经过 shell
（见 probe_kb.py 头部）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GOLD = os.path.join(ROOT, "data", "eval", "recommend-gold.yml")
BASE = "http://localhost:8080"

TOOL = "recommend_products"

# 甲/丙档的档位名（gold 里的键名）—— 判据只用这两个，乙档是【中性】的，不进指标
JIA, BING = "jia_list", "bing_list"


# ============================================================
# 一、读 gold
#
# ★ 这里刻意【不引 PyYAML】（同 eval_ragas.flat_yaml 的立场：依赖越少越好，
#   而这个文件是我们自己写的、结构固定）。代价是必须【读得窄、错得响】：
#     · 只认 `- P000123` 这种列表项，别的写法一律不算
#     · 读完拿 *_count 两个校验和断言一遍（改了一边忘了另一边 → 当场失败）
#     · 把解析结果原样打印出来 —— 解析错了要能一眼看见
# ============================================================

P_LINE = re.compile(r"^\s*-\s*(P\d+)\s*(?:#.*)?$")


def _strip_comment(line: str) -> str:
    """去掉行尾注释。★ YAML 的内联注释要求前面有空格，所以只切 ` #`。"""
    idx = line.find(" #")
    return line if idx < 0 else line[:idx]


def _value(text: str) -> str:
    """`key: value` 里的 value（去空白与引号）"""
    v = text.strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
        v = v[1:-1]
    return v.strip()


def parse_gold(path: str) -> dict:
    """解析 gold 里【机器可读】的那几项。解析不了的一律忽略，不猜。"""
    with open(path, encoding="utf-8") as fh:
        lines = fh.readlines()

    gold = {"path": os.path.relpath(path, ROOT), "needs": [], "combos": [],
            "status": "", "annotated_at": "", "version": ""}
    section = None
    need = None
    combo = None
    call_indent = None   # `call:` 那一行的缩进；比它更深 = call 的子键
    list_key = None

    for raw in lines:
        line = _strip_comment(raw).rstrip("\n")
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        body = line.strip()

        # ★ 注释行【不参与】任何状态判断：gold 里两个 need 之间、
        #   combos 之前都有一大段顶格的 `# ---` 说明，把它们当成「顶层键」
        #   会把 section 清掉 —— 于是第二个 need 整个被丢掉，
        #   而症状是「只跑了一个 need」，不报错
        if body.startswith("#"):
            continue

        # 顶层键 = 顶格、不是列表项、带冒号
        if indent == 0 and not body.startswith("-") and ":" in body:
            list_key = None
            call_indent = None
            section = None
            if body.startswith("needs:"):
                section = "needs"
            elif body.startswith("combos:"):
                section = "combos"
            elif body.startswith("version:"):
                gold["version"] = _value(body.split(":", 1)[1])
            elif body.startswith("status:"):
                gold["status"] = _value(body.split(":", 1)[1])
            elif body.startswith("annotated_at:"):
                gold["annotated_at"] = _value(body.split(":", 1)[1])
            continue

        if section == "needs":
            if body.startswith("- need:"):
                need = {"need": _value(body.split(":", 1)[1]), "call": {},
                        "jia_list": [], "bing_list": [], "counts": {}}
                gold["needs"].append(need)
                call_indent = None
                list_key = None
            elif need is None:
                continue
            elif body.startswith("call:"):
                # ★ 用【缩进】判断谁是谁的子键，别用一个布尔量：
                #   布尔量一旦忘了复位，`candidate_count` / `jia_count` 会全部
                #   落进 `call` 里 —— 而症状是「校验和字段不见了」这种间接报错
                #   （第一版就是这么错的，是 *_count 那两个校验和把它逼出来的）
                call_indent = indent
                list_key = None
            elif body.startswith(JIA + ":"):
                call_indent = None
                list_key = JIA
            elif body.startswith(BING + ":"):
                call_indent = None
                list_key = BING
            elif P_LINE.match(line) and list_key:
                need[list_key].append(P_LINE.match(line).group(1))
            elif ":" in body:
                key, _, rest = body.partition(":")
                key, rest = key.strip(), _value(rest)
                if call_indent is not None and indent > call_indent:
                    need["call"][key] = rest
                else:
                    call_indent = None
                    need["counts"][key] = rest
                    list_key = None

        elif section == "combos":
            if body.startswith("- user:"):
                combo = {"user": int(_value(body.split(":", 1)[1])), "need": "", "why": ""}
                gold["combos"].append(combo)
            elif combo is not None and ":" in body:
                key, _, rest = body.partition(":")
                key = key.strip()
                if key in combo:
                    combo[key] = _value(rest)

    # ── 校验和：gold 里写了 `jia_count` / `bing_count`，必须和列表长度对上 ──
    #    ★ 这是「改了一边忘了另一边」的唯一防线：没有它，往 jia_list 里加一件
    #      但没改注释里的数字，报告上的「甲档 9 件」就悄悄变成了 10 件
    for n in gold["needs"]:
        for key, label in ((JIA, "jia_count"), (BING, "bing_count")):
            want = n["counts"].get(label)
            if want is None:
                raise SystemExit(
                    f"✗ gold 里 {n['need']} 缺 `{label}:` —— 它是 {key} 的校验和，"
                    f"脚本靠它确认自己没解析错。请补上（= {len(n[key])}）")
            if int(want) != len(n[key]):
                raise SystemExit(
                    f"✗ gold 里 {n['need']} 的 `{label}: {want}` 与 "
                    f"{key} 实际有 {len(n[key])} 项对不上 —— 改了一边忘了另一边？")
        if not n[JIA] and not n[BING]:
            raise SystemExit(f"✗ gold 里 {n['need']} 一条档位都没解析出来 —— "
                             f"列表项必须写成 `- P000123`（见脚本头部）")

    gold["hash"] = gold_hash(gold)
    return gold


def gold_hash(gold: dict) -> str:
    """档位内容的指纹 —— 两张快照必须是【同一版 gold】才比得了"""
    payload = json.dumps(
        [[n["need"], n[JIA], n[BING]] for n in gold["needs"]],
        ensure_ascii=False, sort_keys=True)
    return hashlib.sha1(payload.encode("utf-8")).hexdigest()[:12]


# ============================================================
# 二、调工具（不花钱）
# ============================================================

def get_json(path: str, timeout: int = 60) -> dict:
    try:
        with urllib.request.urlopen(BASE + path, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise SystemExit(
            f"✗ {path} 返回 HTTP {e.code} —— 服务起了吗？"
            f"（探针只在 `@Profile(\"local\")` 下存在，见 CLAUDE.md）")


def call_tool(user_id: int, args: dict) -> dict:
    """直调工具。★ 走完整 MCP 链路，拿到的和模型拿到的一模一样"""
    path = (f"/api/debug/mcp/call?tool={TOOL}&userId={user_id}"
            f"&args={urllib.parse.quote(json.dumps(args, ensure_ascii=False))}")
    result = get_json(path)
    if result.get("ok") is not True:
        raise SystemExit(f"✗ 工具调用失败：{json.dumps(result, ensure_ascii=False)[:400]}")
    if result.get("isError") is True:
        # ★ 判据是「工具有没有给出答案」，不是「答案是不是空的」（ADR-056）。
        #   「一个都没匹配上」是 ok，这里是真失败
        raise SystemExit(f"✗ 工具报错：{(result.get('text') or '')[:200]}")
    return result


def picks_of(result: dict) -> list[dict]:
    data = result.get("data") or {}
    return data.get("picks") or []


# ============================================================
# 三、跑一个组合
# ============================================================

def run_combo(gold: dict, combo: dict, repeat: int) -> dict:
    need = next((n for n in gold["needs"] if n["need"] == combo["need"]), None)
    if need is None:
        raise SystemExit(f"✗ combos 里的 `{combo['need']}` 在 needs 里没有对应的一条")

    args = {"need": need["call"].get("need", need["need"]),
            "top_n": int(need["call"].get("top_n", 3))}

    results, raw = [], None
    for _ in range(max(1, repeat)):
        raw = call_tool(combo["user"], args)
        results.append(json.dumps(raw.get("data"), ensure_ascii=False, sort_keys=True))

    picks = picks_of(raw)
    jia, bing = set(need[JIA]), set(need[BING])
    numbers = [p.get("product_no") for p in picks]
    data = raw.get("data") or {}
    tie_size = data.get("top_group_size")
    in_picks = None
    if isinstance(tie_size, int) and tie_size > 0:
        # ★ 前 k 件的总分相同 ⇒ 它们都在最高分组里（组是「总分相同」的连续段）
        top_score = picks[0].get("score") if picks else None
        in_picks = sum(1 for p in picks if p.get("score") == top_score)

    return {
        "user": combo["user"],
        "need": combo["need"],
        "picks": [{"product_no": p.get("product_no"), "name": p.get("name"),
                   "score": p.get("score"), "match_score": p.get("match_score"),
                   "affinity_score": p.get("affinity_score")} for p in picks],
        "numbers": numbers,
        "hit": sum(1 for n in numbers if n in jia),
        "reject": sum(1 for n in numbers if n in bing),
        "tie": {"size": tie_size, "in_picks": in_picks},
        "candidate_count": data.get("candidate_count"),
        "matched_count": data.get("matched_count"),
        "any_affinity": any((p.get("affinity_score") or 0) > 0 for p in picks),
        "deterministic": len(set(results)) == 1,
        "repeat": max(1, repeat),
    }


# ============================================================
# 四、打印
# ============================================================

def tie_text(row: dict) -> str:
    """★★ 这一列是防误读的，措辞不能省 —— 见文件头部那段"""
    tie = row.get("tie") or {}
    size = tie.get("size")
    if not size:
        return "-"
    text = f"{size} 件并列"
    if tie.get("in_picks") == len(row["numbers"]) and size > len(row["numbers"]):
        # 返回的几件全在并列组里，而且组里还有没返回的 ⇒ 这几件是「并列里的前几个」
        text += "，返回的全在其中 ⚠"
    return text


def print_single(gold: dict, rows: list[dict], header: str) -> None:
    print("\n" + "=" * 78)
    print(header)
    print("=" * 78)
    print(f"gold: {gold['path']}  status={gold['status']}  "
          f"annotated_at={gold['annotated_at']}  hash={gold['hash']}")
    for n in gold["needs"]:
        print(f"      需要「{n['need']}」命中 {n['counts'].get('matched_count', '?')} 件，"
              f"标注甲 {len(n[JIA])} / 丙 {len(n[BING])}")
    print("-" * 78)
    print(f"{'user':>4}  {'need':<8} {'甲/丙':<7} {'并列组':<30} picks")
    for row in rows:
        print(f"{row['user']:>4}  {row['need']:<8} "
              f"{str(row['hit']) + '/' + str(row['reject']):<7} "
              f"{tie_text(row):<30} {' '.join(row['numbers'])}")

    # ── 开关自检：没有它，两张一模一样的表会被当成「个性化没效果」 ──
    print("-" * 78)
    on = [r for r in rows if r["any_affinity"]]
    if on:
        print(f"★ 开关自检：{len(on)}/{len(rows)} 个组合里有商品拿到了偏好加分 "
              f"⇒ 这一次跑的是【个性化开】那一侧")
    else:
        print("★ 开关自检：没有任何商品拿到偏好加分 ⇒ 这一次跑的是【个性化关】那一侧"
              "（或所有组合的用户都没有足够订单）")
    bad = [r for r in rows if not r["deterministic"]]
    if bad:
        print(f"✗ 确定性自检失败：{len(bad)} 个组合连续几次调用结果不同 —— "
              f"排序里混进了不确定的东西（HashMap 迭代序？SQL 行序？）")
    elif rows and rows[0]["repeat"] > 1:
        print(f"★ 确定性自检：每个组合连调 {rows[0]['repeat']} 次，逐字节相同")
    print("⚠️ 判据是【集合成员】不是名次；同一 need 对不同用户好坏方向可以相反，"
          "所以只看逐组合，不看总分")


def print_compare(gold_a: dict, gold_b: dict, rows_a: list[dict], rows_b: list[dict]) -> int:
    if gold_a["hash"] != gold_b["hash"]:
        print("✗ 两张表的 gold 不是同一版（hash 不同）—— 改了档位就得两边都重跑，"
              "否则比的不是同一个判据")
        return 2
    on_a = any(r["any_affinity"] for r in rows_a)
    on_b = any(r["any_affinity"] for r in rows_b)
    if on_a == on_b:
        print(f"✗ 两边都是【个性化{'开' if on_a else '关'}】—— 这不是一个 A/B。"
              f"其中一侧要用 `XBLA_AGENT_PROFILE_ENABLED="
              f"{'false' if on_a else 'true'}'` 重启服务再跑")
        return 2
    if on_a:
        rows_a, rows_b = rows_b, rows_a   # 统一成「基线 → 现在」
        print("⚠️ 参数顺序反了（第一份是个性化开的）—— 已自动换过来")

    print("\n" + "=" * 78)
    print("个性化 A/B —— 基线（profile.enabled=false） → 开")
    print("=" * 78)
    print(f"gold hash={gold_a['hash']}（两边一致 ✓）")
    print("★ 基线那一侧的排序【就是 id 升序】，它没有任何语义 ——")
    print("  所以这个 A/B 比的不是「两种排序哪个好」，而是「【没有 tie-breaker】vs 有」。")
    print("-" * 78)
    print(f"{'组合':<22} {'基线 甲/丙':<12} {'现在 甲/丙':<12} {'变化':<14} {'并列组（现在）'}")

    better = worse = same = 0
    for ra, rb in zip(rows_a, rows_b):
        d_hit = rb["hit"] - ra["hit"]
        d_rej = rb["reject"] - ra["reject"]
        changed = ra["numbers"] != rb["numbers"]
        if d_hit > 0 and d_rej <= 0:
            verdict, better = "✔ 变好", better + 1
        elif d_rej > 0 or d_hit < 0:
            verdict, worse = "✘ 变差", worse + 1
        else:
            verdict, same = "· 持平", same + 1
        delta = f"{verdict} 甲{d_hit:+d} 丙{d_rej:+d}" if changed else f"{verdict}（没变）"
        label = f"user {ra['user']} × {ra['need']}"
        print(f"{label:<22} "
              f"{str(ra['hit']) + '/' + str(ra['reject']):<12} "
              f"{str(rb['hit']) + '/' + str(rb['reject']):<12} "
              f"{delta:<14} {tie_text(rb)}")

    print("-" * 78)
    print(f"⇒ {len(rows_a)} 个组合：{better} 个变好、{worse} 个变差、{same} 个持平")
    print("⚠️ 必须跟着一起念的三条：")
    print("  ① gold 的档位是先写后测、与实现正交，但【正交 ≠ 客观正确】；")
    print("  ② 组合只有几个，甲档的召回本来就不是必要条件（top_n 装不下那么多件）；")
    print("  ③ 带 ⚠ 的组合里，返回的几件只是【并列组】的前几个 ——"
          "换一条 tie-breaker 规则，结果就可能不同。")
    return 0


# ============================================================
# 五、入口
# ============================================================

def main() -> int:
    global BASE

    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=BASE, help="服务地址（默认本机 8080）")
    parser.add_argument("--gold", default=GOLD, help="标注文件（默认 data/eval/recommend-gold.yml）")
    parser.add_argument("--out", help="把这一轮存成快照（--compare 要用）")
    parser.add_argument("--compare", nargs=2, metavar=("基线.json", "现在.json"),
                        help="比两张快照 —— 结论只在这里")
    parser.add_argument("--repeat", type=int, default=1,
                        help="每个组合连调几次并断言结果相同（确定性自检，默认 1）")
    args = parser.parse_args()

    BASE = args.base.rstrip("/")

    if args.compare:
        snaps = [json.load(open(p, encoding="utf-8")) for p in args.compare]
        golds = [parse_gold(os.path.join(ROOT, s["gold"]["path"])) for s in snaps]
        return print_compare(golds[0], golds[1], snaps[0]["combos"], snaps[1]["combos"])

    gold = parse_gold(args.gold)
    print(f"读 gold：{gold['path']}（status={gold['status']}）")
    for n in gold["needs"]:
        print(f"  · 「{n['need']}」调用参数 {n['call']}；"
              f"甲 {len(n[JIA])} 件、丙 {len(n[BING])} 件")

    rows, mismatched = [], []
    for combo in gold["combos"]:
        row = run_combo(gold, combo, args.repeat)
        rows.append(row)
        need = next(n for n in gold["needs"] if n["need"] == combo["need"])
        # ★★ 判据是 matched_count（字面重合分 > 0 的商品数），不是 candidate_count
        #   （全部在售商品）—— 档位清单盖的是前者。
        #   第一版比错了数，症状是每一行都报「对不上」而人开始习惯忽略它。
        want = need["counts"].get("matched_count")
        if want and str(row["matched_count"]) != str(want):
            # ★ 池子变了（商品上下架/价格改了）⇒ 档位清单可能已经盖不住。
            #   不是硬错误，但必须报出来 —— 档位是按当时的池子标的
            mismatched.append(
                f"{combo['need']}：gold 写 {want}，实测 {row['matched_count']}"
                f"（池子变了 ⇒ 档位清单可能漏了新进的商品，hit/reject 会偏小）")

    print_single(gold, rows, f"推荐排序探针 —— {len(rows)} 个组合 × {args.repeat} 次")
    if mismatched:
        print("\n⚠️ 候选池和 gold 里记的对不上（商品上下架会让它变）—— 档位可能已经过时：")
        for line in mismatched:
            print(f"    {line}")

    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        snapshot = {
            "probe": "recommend",
            "generated_at": datetime.now().isoformat(timespec="seconds"),
            "base": BASE,
            "gold": {"path": gold["path"], "status": gold["status"],
                     "annotated_at": gold["annotated_at"], "hash": gold["hash"]},
            "needs": {n["need"]: {"call": n["call"], "jia": n[JIA], "bing": n[BING],
                                  "matched_count": n["counts"].get("matched_count")}
                      for n in gold["needs"]},
            "combos": rows,
        }
        with open(args.out, "w", encoding="utf-8") as fh:
            json.dump(snapshot, fh, ensure_ascii=False, indent=2)
            fh.write("\n")
        print(f"\n已存快照：{args.out}")
        print("★ 另一半要关掉开关【重启】之后再跑：")
        print("    XBLA_AGENT_PROFILE_ENABLED=false ./mvnw spring-boot:run")
    return 0


if __name__ == "__main__":
    sys.exit(main())
