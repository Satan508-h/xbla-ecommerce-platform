#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
知识库灌库 —— 部署后的**唯一手工步骤**（阶段 8）。

## ★★★ 为什么它是一条显式的命令，而不是「启动时自动灌」

```
   做成自动的代价：
       每次 `docker compose up -d` 都会【静默】产生一次向量化调用
       —— 一个「起容器」的动作不该顺手花别人的钱，
          也不该在离线的机器上失败
```

★ 所以它是一条写进 `docs/07-部署手册.md` 的命令，而不是一个初始化脚本。

## ★★ 不灌会怎样：失败得很隐蔽

问答**照样能跑**、HTTP 200、有回答 —— 因为检索失败会退化成裸聊
（`docs/10` 里记着这条设计）。而**裸聊会编一个订单状态出来**。

⇒ 所以它排在验收清单的第二条，且这个脚本自己会验「切片数 > 0」。

## 灌两样东西

```
  POST /api/kb/documents/scan    语料文件（data/corpus 下那 5 份文档）
  POST /api/kb/documents/sync    业务表 → 文档（商品详情 + 售后政策）
```

★ 第二个不能省：商品文档里那个「适用人群与场景」小节，
  是演示题「这个适合送长辈吗」能被检索命中的关键。

用法：

    python scripts/ingest.py                      # 打 localhost:8080（本地开发）
    python scripts/ingest.py --url http://localhost    # 打 Nginx（容器全栈）
    python scripts/ingest.py --url http://localhost -u xbla:口令
"""

import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8")
    except Exception:
        pass

# ★ 从 KbDocument 的常量抄来（1/2 是中间态，3/4 是终态）
FINISHED = (3, 4)
STATUS_TEXT = {1: "待处理", 2: "处理中", 3: "完成", 4: "失败"}


def call(base, path, method="GET", auth=None, timeout=60):
    hdrs = {}
    if auth:
        token = base64.b64encode(auth.encode("utf-8")).decode("ascii")
        hdrs["Authorization"] = f"Basic {token}"
    req = urllib.request.Request(base + path, headers=hdrs, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8080")
    ap.add_argument("-u", "--auth", default=None, help="user:pass（Nginx Basic Auth）")
    ap.add_argument("--timeout", type=int, default=1800,
                    help="等全部文档处理完的最长秒数")
    args = ap.parse_args()

    base = args.url.rstrip("/")

    print("=" * 62)
    print(f"灌知识库 → {base}")
    print("=" * 62)

    # ------------------------------------------------------------
    # 1. 提交两批
    # ------------------------------------------------------------
    doc_ids = []
    for label, path in (("语料文件", "/api/kb/documents/scan"),
                        ("业务表", "/api/kb/documents/sync")):
        try:
            body = call(base, path, method="POST", auth=args.auth)
        except urllib.error.HTTPError as e:
            print(f"❌ {label} 提交失败：HTTP {e.code} {e.read()[:200]!r}")
            return 1

        if body.get("code") != 0:
            print(f"❌ {label} 提交失败：{body.get('message')}")
            return 1

        d = body.get("data") or {}
        ids = d.get("docIds") or []
        doc_ids.extend(ids)
        print(f"  {label}：扫描 {d.get('scanned')} 份，"
              f"提交 {d.get('submitted')} 份，跳过 {d.get('skipped')} 份（已入库过）")

    if not doc_ids:
        # ★ 一份都没提交，不等于失败 —— 第二次跑就是全部 skipped。
        #   但那种情况下也要确认库里【确实有切片】，否则「跳过」可能是
        #   「上次其实没灌成」的伪装。下面统一查。
        print("  （没有新提交的文档 —— 可能全都已经灌过了）")

    # ------------------------------------------------------------
    # 2. 轮询到终态
    # ------------------------------------------------------------
    deadline = time.time() + args.timeout
    pending = list(doc_ids)
    failed = []

    while pending and time.time() < deadline:
        time.sleep(2)
        still = []
        for doc_id in pending:
            try:
                body = call(base, f"/api/kb/documents/{doc_id}", auth=args.auth)
            except Exception:
                still.append(doc_id)      # 瞬时失败，下一轮再看
                continue
            d = body.get("data") or {}
            status = d.get("status")
            if status not in FINISHED:
                still.append(doc_id)
            elif status == 4:
                failed.append((doc_id, d.get("errorMsg")))
        if still and len(still) != len(pending):
            done = len(pending) - len(still)
            print(f"  …又完成 {done} 份，还剩 {len(still)} 份")
        pending = still

    if pending:
        print(f"❌ 超时：还有 {len(pending)} 份没处理完")
        return 1

    if failed:
        print(f"❌ {len(failed)} 份处理失败：")
        for doc_id, msg in failed[:10]:
            print(f"     docId={doc_id}  {msg}")
        return 1

    # ------------------------------------------------------------
    # 3. ★ 真正要验的那件事：库里有切片吗
    # ------------------------------------------------------------
    #    「提交成功」和「切片进库了」是两件事 ——
    #    中间隔着切分、向量化、写 pgvector。
    #    只看提交返回的话，一个「全部 submitted 但切片 0 条」的库
    #    看起来和成功一模一样。
    try:
        probe = call(base, "/api/debug/kb/search-text-stats", auth=args.auth)
        d = probe.get("data") or {}
        missing = d.get("missing")
        print()
        print(f"  检索自检：missing = {missing}")
        if missing not in (0, None):
            print(f"  ⚠️ missing 非 0（{missing}）—— 有切片的分词没落库，检索会漏")
    except urllib.error.HTTPError as e:
        # ★ 这个端点是 @Profile("local") 的，容器里（prod）不存在 ——
        #   不是错误，只是这一项在那种环境下查不了。
        #   ⚠️ 不把它写成「通过」：查不到就是查不到。
        print(f"  （跳过检索自检：该端点在 prod profile 下不存在，HTTP {e.code}）")
    except Exception as e:
        print(f"  （跳过检索自检：{e}）")

    print()
    print("=" * 62)
    print("✅ 灌库完成")
    print("★ 下一步：问一句【需要检索才能答对】的问题，确认不是裸聊 ——")
    print("    例如「退货要几天」。若回答开始编订单信息，说明检索还是空的。")
    print("=" * 62)
    return 0


if __name__ == "__main__":
    sys.exit(main())
