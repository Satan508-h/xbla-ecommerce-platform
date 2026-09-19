# -*- coding: utf-8 -*-
"""
知识库检索探针的命令行客户端。

★ 为什么需要这个脚本（而不是直接 curl）

Windows + Git Bash 下有**两层**中文编码陷阱，直接用 curl 会得到看起来
「像乱码又像 bug」的结果，白白浪费排查时间：

  ① 命令行参数被 shell 破坏
     Git Bash 把中文参数按本地编码传给 curl，服务端收到的是 U+FFFD
     替换字符 —— 也就是「问题本身就是乱的」，检索结果自然毫无意义。
     （这个坑记在 docs/10 开发路线图里，阶段 2 踩过一次）

  ② 响应被 Python 按错误编码读取
     Windows 上 Python 的 stdin 默认按控制台代码页（GBK）解码，
     而 HTTP 响应是 UTF-8 —— 传进来的中文会变成一串看不懂的字符，
     看起来像是「数据库里的数据坏了」，其实只是显示问题。

  这个脚本用 urllib.parse.quote 做百分号编码绕开 ①，
  用 sys.stdout.reconfigure(encoding='utf-8') 绕开 ②。

用法：
    python scripts/probe_kb.py search 退货要几天
    python scripts/probe_kb.py search 退货要几天 --topk 5
    python scripts/probe_kb.py retrieve 送长辈合适吗      ← 完整链路的 5 段中间输出
    python scripts/probe_kb.py stability 退货要几天
    python scripts/probe_kb.py status 1
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.parse
import urllib.request

# ★ 绕开陷阱 ②：把标准输出切成 UTF-8。
#   Windows 控制台默认是 GBK，不改的话中文会变成乱码或直接抛
#   UnicodeEncodeError: 'gbk' codec can't encode character
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

BASE = "http://localhost:8080"


def get(path: str, params: dict) -> dict:
    # ★ 绕开陷阱 ①：用 urllib 做百分号编码，中文不经过 shell
    query = urllib.parse.urlencode(params, encoding="utf-8")
    url = f"{BASE}{path}?{query}"
    with urllib.request.urlopen(url, timeout=120) as resp:
        return json.loads(resp.read().decode("utf-8"))


def cmd_search(args: list) -> int:
    question = args[0]
    topk = 5
    if "--topk" in args:
        topk = int(args[args.index("--topk") + 1])

    data = get("/api/debug/kb/search", {"q": question, "topK": topk})
    print(f"问题      : {data['question']}")
    print(f"查询向量  : {data['queryVectorDimension']} 维  前几个分量 {data['queryVectorHead']}")
    print(f"命中      : {data['hitCount']} 条")
    print("-" * 78)
    for i, hit in enumerate(data["hits"]):
        print(f"[{i}] 相似度 {hit['score']:.4f}  chunk_id={hit['chunkId']}  "
              f"doc={hit['documentId']}#{hit['chunkIndex']}")
        print(f"    路径: {hit['headingPath']}")
        print(f"    内容: {hit['preview']}")
        print()
    return 0


def cmd_stability(args: list) -> int:
    question = args[0]
    repeat = 3
    if "--repeat" in args:
        repeat = int(args[args.index("--repeat") + 1])

    data = get("/api/debug/kb/stability", {"q": question, "repeat": repeat, "topK": 5})
    print(f"问题            : {data['question']}")
    print(f"重复次数        : {data['repeat']}")
    print(f"ID 顺序稳定     : {data['idSequenceStable']}   ← 判定依据")
    print(f"分值在容差内稳定: {data['scoreStableWithinTolerance']}")
    print(f"最大分值漂移    : {data['maxScoreDrift']:.3e}  (容差 {data['scoreTolerance']:.0e})")
    print(f"★ 总体稳定      : {'✅ 是' if data['stable'] else '❌ 否'}")
    print()
    for i, ids in enumerate(data["idRuns"]):
        print(f"  第 {i + 1} 次 chunk_id 序列: {ids}")
    print()
    for i, scores in enumerate(data["scoreRuns"]):
        print(f"  第 {i + 1} 次 分值序列: {[round(s, 6) for s in scores]}")
    print()
    print(f"说明: {data['note']}")
    return 0 if data["stable"] else 1


def cmd_status(args: list) -> int:
    doc_id = int(args[0])
    with urllib.request.urlopen(f"{BASE}/api/kb/documents/{doc_id}", timeout=60) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    d = data["data"]
    print(f"docId      : {d['docId']}")
    print(f"文档编号   : {d['docNo']}")
    print(f"标题       : {d['title']}")
    print(f"文件名     : {d['fileName']}")
    print(f"状态       : {d['statusText']} (status={d['status']}, finished={d['finished']})")
    print(f"切片数     : {d['chunkCount']}")
    print(f"错误信息   : {d['errorMsg']}")
    return 0


def cmd_retrieve(args: list) -> int:
    """
    完整检索链路的中间输出 —— 阶段 4 验收标准 1 的落点。

    这条走的是 /api/debug/kb/retrieve，它会跑完整的
    「双路召回 → RRF 融合 → 重排 → 截断」，并把每一段的中间结果都打出来。
    """
    question = args[0]
    data = get("/api/debug/kb/retrieve", {"q": question})
    detail = data["detail"]

    print(f"问题: {data['question']}")
    print(f"耗时: {data['latency']}")
    print("-" * 78)

    sections = [
        ("vector_hits", "① 向量召回（余弦相似度）"),
        ("keyword_hits", "② 关键词召回（ts_rank）"),
        ("fused", "③ RRF 融合（只看名次）"),
        ("reranked", "④ 重排（bge-reranker）"),
    ]
    for key, title in sections:
        items = detail.get(key) or []
        ids = [next(iter(i.values())) for i in items]
        print(f"{title}  {len(items)} 条")
        print(f"   {ids}")
    print(f"⑤ 最终送进 prompt: {detail.get('final_top_k')}")

    events = detail.get("events")
    if events:
        print(f"\n★ 过程事件: {events}")

    print("\n最终切片:")
    for c in data["finalChunks"]:
        print(f"  [{c['chunkId']}] 分数={c['score']:.6f}")
        print(f"       路径: {c['headingPath']}")
        print(f"       内容: {c['preview']}")
    return 0


COMMANDS = {
    "search": cmd_search,
    "retrieve": cmd_retrieve,
    "stability": cmd_stability,
    "status": cmd_status,
}


def main() -> int:
    if len(sys.argv) < 3 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        return 2
    try:
        return COMMANDS[sys.argv[1]](sys.argv[2:])
    except urllib.error.URLError as e:
        print(f"请求失败（应用启动了吗？）：{e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
