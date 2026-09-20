#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 6 验收探针 —— Redis 分布式排队限流，100 并发压测。

★ 它【不调模型，不花钱】。全部请求打到 /api/debug/ratelimit/fake-stream，
  那是一条走【完整】排队链路的假流式端点（真 Lua、真 ZSet、真 SSE、
  真 queue-/answer- 线程池、真名额释放），只有「最后一步调模型」被换成了按参数占位。

★ 为什么必须用假端点：模型的延迟方差有 ±5%（docs/06 §1.4），
  它会把「无超卖」这类并发性质要测的时序差异整个盖住。

★★★ 三条纪律：
  ① 【判性质，不判措辞】—— 断言的判据要能被代码保证，不能依赖模型/文案。
  ② 【每条正向断言都要有反对照】—— 组六和组七是一对：同一个仪器（/trace）、
     同一个 TTL、同一个 key，一个断言「没人续期就会被回收」，
     另一个断言「有人续期就不会被回收」。少了任何一半，另一半都可能恒真。
  ③ 【并发性质只能并发测】—— 组二的核心断言是「采样到的 permitsInUse 峰值 ≤ N」，
     单请求跑一万次也测不出超卖，因为超卖只在两个请求【同时】的那一刻存在。

★ 推理链：本脚本前半段用【假端点】验证排队层本身（可复现、不花钱），
  后半段（--real）用【真实端到端】验证「排队层接进真实链路」这一步接通了。
  两者缺一不可 —— R17 说的就是这个：「假端点压测通过 ≠ 真实链路可用」。

判定项（八组）：
  一、基础         单请求不排队；admitted 在 meta 之前；queuedAhead 是 null 不是 0
  二、★ 无超卖     100 并发期间采样 permitsInUse 的【峰值】≤ permits
  三、★ 无死锁     100 并发【全部】拿到 done；收尾时队列为空、无名额残留
  四、位置准确     收到的 position 与 ZRANK 逐字相等；且【单调不增】
  五、队列上限     队列满时【如实拒绝】，不是一个静默丢弃
  六、僵尸回收     leak 出来的名额（没人续期）在 TTL 之后确实被回收
  七、看门狗       ★ 组六的反对照：真实占位的名额过了 TTL 【不】被回收
  八、拒绝路径     failed 事件而不是兜底 500 JSON（含 queue- 池满的同步那条路）

用法：
  # 推荐（把租期压到 10 秒，让组六/组七在几十秒内跑完）
  SPRING_APPLICATION_JSON='{"xbla":{"ratelimit":{"permits":1,"permit-ttl":"10s"}}}' \
      ./mvnw spring-boot:run
  python scripts/probe_ratelimit.py

  # 真实端到端（★ 会调模型，花钱）
  python scripts/probe_ratelimit.py --real
"""
import argparse
import json
import sys
import threading
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://localhost:8080"
RATELIMIT = "/api/debug/ratelimit"

PASS, FAIL, SKIP = "[✓]", "[✗]", "[—]"
results = []
lock = threading.Lock()


def check(name, ok, detail=""):
    with lock:
        results.append((name, ok, detail))
    print(f"  {PASS if ok else FAIL} {name}" + (f"  —— {detail}" if detail else ""))
    return ok


def skip(name, detail=""):
    with lock:
        results.append((name, None, detail))
    print(f"  {SKIP} {name}" + (f"  —— {detail}" if detail else ""))


# ============================================================
# HTTP 基础
# ============================================================

def http(method, path, timeout=30):
    req = urllib.request.Request(BASE + path, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def data_of(method, path, timeout=30):
    """调一个接口并返回 ApiResponse.data（失败抛异常，让调用点自己决定怎么报）"""
    status, body = http(method, path, timeout)
    if status != 200 or body.get("code") != 0:
        raise RuntimeError(f"{method} {path} → HTTP {status} code={body.get('code')} "
                           f"{body.get('message')}")
    return body.get("data") or {}


def state():
    return data_of("GET", f"{RATELIMIT}/state")


def reset():
    return data_of("POST", f"{RATELIMIT}/reset")


def leak(permits=0, queue=0):
    return data_of("POST", f"{RATELIMIT}/leak?permits={permits}&queue={queue}")


def release(trace_id):
    return data_of("POST", f"{RATELIMIT}/release?traceId={urllib.request.quote(trace_id)}")


def trace(trace_id):
    return data_of("GET", f"{RATELIMIT}/trace?traceId={urllib.request.quote(trace_id)}")


# ============================================================
# SSE 客户端
# ============================================================

class SseRun:
    """
    一次 SSE 请求的全部痕迹。

    ★ 它必须【同时】记住三样东西，少一样就有一条断言做不了：
        events  —— 事件序列（判「admitted 在 meta 之前」「有没有 queued」）
        status  —— HTTP 状态码 + Content-Type（判「不是兜底 500 JSON」）
        times   —— 每个事件到达的本地时刻（判「等了多少秒」）
    """

    def __init__(self, label):
        self.label = label
        self.events = []          # [(t, event, payload)]
        self.status = None
        self.content_type = None
        self.body_head = ""       # 非 SSE 响应时，正文前 300 字（用来证明「不是 JSON 兜底」）
        self.error = None
        self.t0 = None
        self.t_end = None
        self.thread = None
        self._lock = threading.Lock()

    def add(self, event, payload):
        with self._lock:
            self.events.append((time.time(), event, payload))

    def names(self):
        return [e for _, e, _ in self.events]

    def first(self, event):
        for _, name, payload in self.events:
            if name == event:
                return payload
        return None

    def has(self, event):
        return event in self.names()

    def wait_for(self, event, timeout=60):
        """等某个事件出现（并发场景下不能等整个流结束）"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.has(event) or self.t_end is not None:
                return self.has(event)
            time.sleep(0.02)
        return False

    def join(self, timeout=300):
        if self.thread:
            self.thread.join(timeout)


def run_sse(path, label=""):
    """开一条 SSE 连接，【立刻返回】（在后台线程里收事件）"""
    run = SseRun(label)

    def worker():
        run.t0 = time.time()
        try:
            req = urllib.request.Request(BASE + path, method="GET")
            # ★ 超时给得很宽：排队本来就可能等几十秒，而 socket 超时
            #   是在「多久没收到字节」上计的，不是总时长。太短会把
            #   「正常排队」误判成「连接失败」—— 而那正是本脚本要测的东西。
            with urllib.request.urlopen(req, timeout=180) as r:
                run.status = r.status
                run.content_type = r.headers.get("Content-Type")
                event = None
                for raw in r:
                    # ★ 按【字节】分行是安全的：\n 是 0x0A，不会出现在
                    #   任何多字节 UTF-8 序列的内部。所以这里不会把中文切坏。
                    line = raw.decode("utf-8").rstrip("\r\n")
                    if line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:"):
                        payload = line[5:].strip()
                        try:
                            payload = json.loads(payload)
                        except Exception:
                            pass
                        run.add(event, payload)
                    elif line == "":
                        event = None
        except urllib.error.HTTPError as e:
            run.status = e.code
            run.content_type = e.headers.get("Content-Type")
            run.body_head = e.read().decode("utf-8", "replace")[:300]
        except Exception as e:                    # noqa: BLE001 —— 探针要把任何失败都记下来
            run.error = repr(e)
        finally:
            run.t_end = time.time()

    run.thread = threading.Thread(target=worker, daemon=True)
    run.thread.start()
    return run


class Sampler:
    """
    并发采样器 —— <b>验收标准 1 的核心仪器</b>。

    ★★ 为什么必须是【采样】而不是「跑完再看」：超卖是瞬时状态。
      8 个名额被 9 个请求同时用着，那个状态可能只存在 3 毫秒 ——
      跑完再读 /state 看到的永远是 0。只有持续采样才能捕捉到峰值。

    ★ 采样间隔 20ms：一次 /state 要打 6~8 条 Redis 命令（本地约 1ms），
      20ms 对 15 秒的压测是 750 个样本 —— 足够密，又不会把 Redis 打满。
      ⚠️ 太密（比如 1ms）采样器自己会变成压测的一部分，测出来的
      permitsInUse 峰值里有一部分是采样器自己的开销造成的假象。
    """

    def __init__(self, interval=0.02):
        self.interval = interval
        self.peak = 0
        self.samples = 0
        self.peak_at_ms = None
        # ★★★ 峰值那一瞬间的【完整快照】—— 不是「再读一次」。
        #
        #   permitsInUse 是 ZCARD(slots)，而它【包含已过期但还没被扫掉的条目】。
        #   所以「峰值 14 > permits 8」有两种含义完全相反的解释：
        #
        #     expiredNotYetSwept 也是 6  →  只是僵尸没清（R7），【不是】超卖
        #     expiredNotYetSwept 是 0，heldHere 是 14  →  【真超卖】
        #
        #   ⚠️ 第一版只记了 permitsInUse 一个数，于是这次发现【判不了】——
        #      而这两种情况的修法完全相反（一个改清扫时机，一个改分配逻辑）。
        self.peak_snapshot = None
        self.errors = 0
        self._stop = threading.Event()
        self._t0 = None
        self._thread = threading.Thread(target=self._loop, daemon=True)

    def start(self):
        self._t0 = time.time()
        self._thread.start()
        return self

    def _loop(self):
        while not self._stop.is_set():
            try:
                s, body = http("GET", f"{RATELIMIT}/state", timeout=10)
                if s == 200 and body.get("code") == 0:
                    d = body["data"]
                    v = d["redis"]["permitsInUse"]
                    self.samples += 1
                    if v > self.peak:
                        self.peak = v
                        self.peak_at_ms = int((time.time() - self._t0) * 1000)
                        self.peak_snapshot = {
                            "permitsInUse": v,
                            "expiredNotYetSwept": d["redis"]["expiredNotYetSwept"],
                            "queueSize": d["redis"]["queueSize"],
                            "aliveSize": d["redis"]["aliveSize"],
                            "heldHere": d["local"]["heldHere"],
                            "waitingHere": d["local"]["waitingHere"],
                            "heartbeatFailures": d["local"]["heartbeatFailures"],
                            "tMs": self.peak_at_ms,
                        }
                        # ★★ 峰值那一刻再打一次 /slots —— 「多了 6 个」是没用的信息，
                        #    「多出来的 6 个是谁、还活着吗」才指向修法。
                        #   ⚠️ 只在破峰值时打：这个端点会 dump 全部成员，
                        #      每个采样都打会让采样器自己变成压测的一部分。
                        try:
                            self.peak_snapshot["slots"] = data_of("GET", f"{RATELIMIT}/slots")
                        except Exception as e:            # noqa: BLE001
                            self.peak_snapshot["slots"] = f"读取失败：{e}"
                else:
                    self.errors += 1
            except Exception:                     # noqa: BLE001
                self.errors += 1
            self._stop.wait(self.interval)

    def stop(self):
        self._stop.set()
        self._thread.join(5)


def wait_until(predicate, timeout, interval=0.05):
    """轮询等一个条件成立。★ 返回 False = 超时，调用点必须把它当成断言失败，不能忽略"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


def cfg():
    c = state()["config"]
    return c


# ============================================================
# 计分板
# ============================================================

def banner(title):
    print("\n" + "─" * 70)
    print(title)
    print("─" * 70)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--real", action="store_true",
                        help="跑真实 10 并发端到端（★ 会调模型，花钱）")
    parser.add_argument("--skip-slow", action="store_true",
                        help="跳过组六/组七（当 permit-ttl 很长时省时间）")
    args = parser.parse_args()

    # ── 前置：服务在不在 ──
    try:
        c = cfg()
    except Exception as e:                        # noqa: BLE001
        print(f"连不上 {BASE} —— 先起服务，而且 Redis 和 postgres 都要在跑。\n  {e}")
        sys.exit(2)

    permits = c["permits"]
    ttl_ms = c["permitTtlMs"]
    max_queue = c["maxQueue"]
    ttl_s = ttl_ms / 1000.0

    print("=" * 70)
    print("阶段 6 · Redis 分布式排队限流 —— 验收探针")
    print("=" * 70)
    print(f"  permits={permits}  maxQueue={max_queue}  permitTtl={ttl_s:g}s  "
          f"pollInterval={c['pollIntervalMs']}ms  enabled={c['enabled']}")
    sub = state().get("subscription", {})
    print(f"  Pub/Sub 订阅：enabled={sub.get('enabled')} listening={sub.get('listening')} "
          f"attempts={sub.get('attempts')}")
    if not c["enabled"]:
        print("\n⚠️ xbla.ratelimit.enabled=false —— 整个排队限流没在跑，本脚本无法验证任何东西。")
        sys.exit(2)
    if ttl_s > 20:
        print(f"\n⚠️ permit-ttl={ttl_s:g}s 偏长，组六/组七会各等一次租期（约 {ttl_s * 2.4:.0f} 秒）。")
        print("   想快一点：SPRING_APPLICATION_JSON='{\"xbla\":{\"ratelimit\":"
              "{\"permit-ttl\":\"10s\"}}}' ./mvnw spring-boot:run")

    # ============================================================
    # 组一 · 基础
    # ============================================================
    banner("【一】基础 —— 名额空着时不该有任何排队痕迹")
    reset()
    run = run_sse(f"{RATELIMIT}/fake-stream?holdMs=300&deltas=3&label=basic")
    run.join(60)

    check("① 请求成功跑完（HTTP 200 + 收到 done）",
          run.status == 200 and run.has("done"),
          f"status={run.status} events={run.names()}")

    # ★★ 这一条是「不排队」的形状：没有 queued 事件，而且 queuedAhead 是 null。
    #    ⚠️ 记 0 的话它和「进过队列而且是队首」长得一模一样 ——
    #       而那是两件完全不同的事（见 V9 迁移和 CallContext 的注释）。
    admitted = run.first("admitted") or {}
    check("② ★ 没排队 → 没有 queued 事件，且 queuedAhead 是 null（不是 0）",
          not run.has("queued") and admitted.get("queuedAhead") is None,
          f"queued={run.has('queued')} queuedAhead={admitted.get('queuedAhead')!r}")

    names = run.names()
    check("③ admitted 在 meta 之前（前端先知道「轮到我了」再收到正文）",
          "admitted" in names and "meta" in names
          and names.index("admitted") < names.index("meta"),
          f"顺序={names}")

    # ★ 端到端耗时必须 ≥ holdMs。小于它说明 holdMs 压根没生效 ——
    #   而那时组二/组三测的「并发」就不是我们以为的那个并发。
    elapsed_ms = (run.t_end - run.t0) * 1000
    check("④ 真的占住了 holdMs（端到端耗时 ≥ 300ms）",
          elapsed_ms >= 300,
          f"耗时={elapsed_ms:.0f}ms")

    # ============================================================
    # 组二 + 组三 · 100 并发
    # ============================================================
    concurrency = 100
    # ★ holdMs 按 permits 反推，让整组的墙上时间稳定在 15 秒左右：
    #   总时间 ≈ concurrency / permits × holdMs。太大了跑得慢，
    #   太小了采样器可能整个错过峰值窗口。
    hold_ms = max(60, min(1500, int(150 * permits)))

    banner(f"【二 / 三】{concurrency} 并发 —— 无超卖（采样） + 无死锁（全部完成）")
    reset()
    print(f"  参数：holdMs={hold_ms}ms，预计墙上时间 ≈ "
          f"{concurrency * hold_ms / permits / 1000:.0f}s")

    sampler = Sampler(interval=0.02).start()
    t_burst = time.time()
    runs = [run_sse(f"{RATELIMIT}/fake-stream?holdMs={hold_ms}&deltas=2&label=c{i}")
            for i in range(concurrency)]

    # ★ 收尾用轮询而不是 join 固定时长：100 条流各自结束的时刻不一样，
    #   等固定时间要么白等、要么不够 —— 而「不够」会让下面的断言假红。
    all_done = wait_until(lambda: all(r.t_end is not None for r in runs),
                          timeout=concurrency * hold_ms / permits / 1000 + 60)
    burst_s = time.time() - t_burst
    sampler.stop()

    finished = [r for r in runs if r.has("done")]
    check(f"⑤ ★★ 无死锁：{concurrency} 并发【全部】拿到 done",
          len(finished) == concurrency,
          f"{len(finished)}/{concurrency} 完成，耗时 {burst_s:.1f}s")

    failed = [r for r in runs if r.has("failed")]
    check("⑥ 没有一个被拒绝（队列 500 个位置，100 个不该触顶）",
          not failed,
          f"被拒 {len(failed)} 个：" + (failed[0].first('failed') or {}).get("message", "")
          if failed else "0 个")

    # ★★★ 组二的核心断言。
    check(f"⑦ ★★★ 无超卖：采样到的 permitsInUse 【峰值】 ≤ permits(={permits})",
          sampler.peak <= permits,
          f"峰值={sampler.peak}，出现在第 {sampler.peak_at_ms}ms，"
          f"采样 {sampler.samples} 次（错 {sampler.errors}）"
          # ★ 超了的时候，把峰值那一刻的【完整】快照打出来。
          #   没有它，这一条失败信息只有「14 > 8」，而 14 可以是两种完全
          #   不同的东西（僵尸 vs 真超卖）—— 修法相反，所以必须能分辨。
          + (f"\n       ★ 峰值那一刻的完整快照：{sampler.peak_snapshot}"
             + ("\n       → expiredNotYetSwept>0 说明那部分是【僵尸没清】，不是超卖；"
                if (sampler.peak_snapshot or {}).get("expiredNotYetSwept") else
                "\n       → expiredNotYetSwept=0 ⇒ 那些名额都是【活的】，即真超卖")
             if sampler.peak > permits else ""))

    # ★ 反对照：峰值必须【达到】permits。达不到的话说明这轮压根没跑满，
    #   「峰值 ≤ N」就变成了恒真 —— 一个没跑满的压测证明不了任何事。
    #
    # ★ 判据是 `>=` 而不是 `==`：这一条守的是【非空泛】，不是【正确】。
    #   写成 `==` 的话，真正的超卖会让 ⑦ 和 ⑧ 一起红 —— 同一个缺陷报两次，
    #   「到底是没跑满还是超卖了」反而看不出来。（注入 A 实测过这一点。）
    check("⑧ ★ 反对照：峰值确实【达到】过 permits（否则上面那条是恒真的）",
          sampler.peak >= permits,
          f"峰值={sampler.peak} vs permits={permits}")

    wait_until(lambda: (s := state())["redis"]["queueSize"] == 0
               and s["redis"]["permitsInUse"] == 0, timeout=20)
    s = state()
    check("⑨ 收尾：队列为空、无名额残留",
          s["redis"]["queueSize"] == 0 and s["redis"]["permitsInUse"] == 0,
          f"queue={s['redis']['queueSize']} permitsInUse={s['redis']['permitsInUse']} "
          f"expiredNotYetSwept={s['redis']['expiredNotYetSwept']}")

    # ★★ 这一条随配置【翻转】，不是一个固定断言。
    #
    #   wakeup.enabled=true  →  断言「确实送达过」（Pub/Sub 真的在起作用）
    #   wakeup.enabled=false →  断言「一次都没送达」（它真的被关掉了）
    #
    # ⚠️ 第一版写死了 `> 0`，于是在「故意关掉 Pub/Sub 做 A/B」的那次运行里
    #    必然假红 —— 而那次运行恰恰是这个开关存在的全部理由
    #    （见 RateLimitProperties.Wakeup 的类注释）。
    #    ★ 一个在 A/B 的其中一半上必然失败的断言，会让 A/B 的结论没法看。
    sig = s["signals"]
    wakeup_on = (state().get("subscription") or {}).get("enabled", True)
    detail = (f"signalsReceived={sig['signalsReceived']} "
              f"waitsTotal={sig['waitsTotal']} "
              f"wokenBySignal={sig['waitsWokenBySignal']} "
              f"放大系数={sig['amplification']:.2f}")
    if wakeup_on:
        check("⑩ Pub/Sub 确实送达过（signalsReceived > 0）",
              sig["signalsReceived"] > 0, detail)
    else:
        check("⑩ 反：唤醒已关 → 一次广播都不该有（这是 A/B 的对照半边）",
              sig["signalsReceived"] == 0 and sig["waitsWokenBySignal"] == 0, detail)

    # ============================================================
    # 组四 · 位置准确
    # ============================================================
    banner("【四】位置准确 —— 推给前端的 position 必须就是 ZRANK，而且单调不增")
    reset()

    # ★★ 用法：先 leak 满名额把队列【冻住】。
    #   不冻住的话，位置会在我们读完 /trace 之前就变了 ——
    #   而那会让「对不上」既可能是 bug 也可能是竞态，两种修法完全相反。
    leaked = leak(permits=permits)
    leaked_ids = leaked["ids"]
    check("⑪ 前置：leaked 的名额【没有】进本机登记表（否则心跳会替它续期）",
          leaked.get("registeredLocally") is False,
          f"ids={len(leaked_ids)} registeredLocally={leaked.get('registeredLocally')}")

    q_runs = [run_sse(f"{RATELIMIT}/fake-stream?holdMs=2000&deltas=2&label=q{i}")
              for i in range(4)]
    got_queued = [r.wait_for("queued", timeout=20) for r in q_runs]

    if all(got_queued):
        reported = []
        for r in q_runs:
            ev = r.first("queued") or {}
            reported.append((ev.get("traceId"), ev.get("position")))

        check("⑫ 4 个等待者报的位置是 {0,1,2,3}，互不重复",
              sorted(p for _, p in reported) == [0, 1, 2, 3],
              f"报的位置={[p for _, p in reported]}")

        # ★★★ 本组的心脏：拿「推给前端的那个数」和「Redis 现在说的」逐字比对。
        #   两个数隔着一次 SSE 往返，任何一处算错都会在这里露出来 ——
        #   而那处错在界面上看起来只是「等得有点久」。
        mismatches = []
        for tid, pos in reported:
            actual = trace(tid).get("queueRank")
            if actual != pos:
                mismatches.append(f"{tid[:12]}… 报{pos}/实际{actual}")
        check("⑬ ★★★ 每个 position 与当时的 ZRANK 逐字相等",
              not mismatches,
              "；".join(mismatches) if mismatches else f"{len(reported)} 个全部对得上")

        # ★ 单调不增：这是结构保证的（rank = 排在前面的人数，而前面的人只会离开），
        #   所以它一旦被违反，说明有人往队列前面【塞】了东西 ——
        #   而 R5/R6 说的正是那两种塞法（重抢换新 score / score 用时间戳）。
        seq_snapshots = []
        for _ in range(3):
            if leaked_ids:
                release(leaked_ids.pop())
            time.sleep(0.6)
            snap = {}
            for tid, _ in reported:
                snap[tid] = trace(tid).get("queueRank")
            seq_snapshots.append(snap)

        violations = []
        for tid, _ in reported:
            history = [s[tid] for s in seq_snapshots if s[tid] is not None]
            original = dict(reported)[tid]
            history = [original] + history
            for a, b in zip(history, history[1:]):
                if b > a:
                    violations.append(f"{tid[:12]}… {a} → {b}")
        check("⑭ ★ 位置单调不增（放开名额后只减不增）",
              not violations,
              "；".join(violations) if violations else "一路不增")
    else:
        # ★★ 这里是【断言】而不是 skip，理由：`leak(permits=<配置值>)` 必然
        #    把名额占满，所以「有人直接拿到名额」只可能意味着两件事之一 ——
        #    要么 leak 没生效，要么并发上限不是我们读到的那个。
        #    两种都是【被测系统】的问题，不是「环境不满足」。
        #
        #    ⚠️ 第一版写的是 skip。注入 A（并发上限 +1）时它确实跳过了 ——
        #       而报告顶部只是一行不起眼的 [—]，很容易被当成「这组不适用」翻过去。
        #       **一个用跳过表达的失败，等于静默漏跑。**
        check("⑫ 前置：4 个请求全部进入队列（leak 的名额确实占满了）", False,
              f"有 {got_queued.count(False)} 个没报出 queued 事件 —— "
              f"说明名额没被 leak 占满（并发上限被改了？）")
        for _ in range(4):
            if leaked_ids:
                release(leaked_ids.pop())

    for r in q_runs:
        r.join(60)
    reset()

    # ============================================================
    # 组五 · 队列上限
    # ============================================================
    banner("【五】队列上限 —— 满了要【如实拒绝】，不是静默丢弃")
    reset()
    leak(permits=permits, queue=max_queue)
    s = state()
    check("⑮ 前置：名额占满 + 队列填满",
          s["redis"]["permitsInUse"] >= permits and s["redis"]["queueSize"] >= max_queue,
          f"permitsInUse={s['redis']['permitsInUse']} queueSize={s['redis']['queueSize']}")

    run = run_sse(f"{RATELIMIT}/fake-stream?holdMs=200&label=full")
    run.join(60)
    failed_ev = run.first("failed") or {}
    check("⑯ ★ 队列满 → 收到 failed，且消息说清是「人太多」而不是「等太久」",
          run.has("failed") and "人" in (failed_ev.get("message") or "")
          and not run.has("done"),
          f"message={failed_ev.get('message')!r}")

    # ★ 组八的一半在这里就验掉了：被拒绝时 HTTP 层仍然是正常的 SSE。
    #   加这个 handler 之前，拒绝会落到兜底分支 → 500 + 一段 JSON 正文 ——
    #   而那段 JSON 会被前端的 EventSource 当成「什么都不发生」。
    check("⑰ ★ 它是 SSE 流里的一个 failed 事件，而不是一段兜底 JSON 正文",
          run.status == 200 and "text/event-stream" in (run.content_type or ""),
          f"status={run.status} content-type={run.content_type}")
    reset()

    # ============================================================
    # 组六 / 组七 · 僵尸回收 ←→ 看门狗（一对正-反对照）
    # ============================================================
    if args.skip_slow:
        skip("⑱⑲ 组六·僵尸回收", "--skip-slow")
        skip("⑳㉑ 组七·看门狗", "--skip-slow")
    else:
        banner(f"【六】僵尸回收 —— 没人续期的名额，{ttl_s:g} 秒后必须被回收")
        reset()
        leaked = leak(permits=permits)
        probe_id = leaked["ids"][0]
        before = trace(probe_id)
        check("⑱ 前置：leak 出来的名额在 Redis 里，且【没人在本机续期它】",
              before.get("holdsPermit") is True
              and before.get("renewedByThisProcess") is False,
              f"holdsPermit={before.get('holdsPermit')} "
              f"renewedByThisProcess={before.get('renewedByThisProcess')} "
              f"剩余={before.get('permitRemainingMs')}ms")

        # ★★ 观察「剩余时间在减少」，而不是只等一个总数。
        #   前者能证明【它确实在过期】，后者只能证明「后来能进」——
        #   而「后来能进」也可能是别的名额被释放了。
        remaining_samples = []
        for _ in range(4):
            time.sleep(ttl_s / 6)
            t = trace(probe_id)
            remaining_samples.append(t.get("permitRemainingMs"))
        decayed = all(a is not None and b is not None and b < a
                      for a, b in zip(remaining_samples, remaining_samples[1:]))
        check("⑲ ★ 剩余租期【在减少】（= 真的没人续期，不是「碰巧有空位」）",
              decayed,
              f"剩余时间序列={remaining_samples}")

        reset()
        leaked = leak(permits=permits)
        t0 = time.time()
        run = run_sse(f"{RATELIMIT}/fake-stream?holdMs=100&label=reclaim")
        # ★★ 超时值本身必须【比断言阈值大得多】，否则「没拿到」和「拿到得太慢」
        #   会撞在同一个数字上 —— 本脚本第一版就是这样：wait_for 的 70 秒超时
        #   被当成了「等待 70.0s」写进失败信息，于是**失败信息在撒谎**。
        #   一个指向错误方向的失败信息，比没有信息更费时间。
        got_done = run.wait_for("done", timeout=ttl_s * 4 + 30)
        waited_s = time.time() - t0
        check(f"⑳ ★★ 名额被回收：真实请求在租期之后拿到了名额",
              got_done and waited_s >= ttl_s * 0.9,
              f"{'拿到' if got_done else '★ 一直没拿到（等到探针自己超时）'}，"
              f"等待 {waited_s:.1f}s（租期 {ttl_s:g}s）")
        # ★ 上限也要卡：等得太久说明它不是在「等到期」，而是卡在别的地方。
        check("㉑ ★ 而且回收是【按时】发生的，没有卡死",
              waited_s <= ttl_s + ttl_s + 15,
              f"等待 {waited_s:.1f}s ≤ 租期×2+15s")
        run.join(30)
        reset()

        banner(f"【七】看门狗 —— {ttl_s:g} 秒租期不够用，但它【不】该被回收")
        reset()
        # ★★★ 本组是组六的反对照，用的是【同一个仪器、同一个 key、同一个 TTL】：
        #     组六：leak（没人续期）      → permitRemainingMs 一路降到 0
        #     组七：真实占位（有人续期）  → permitRemainingMs 一直在 TTL 附近徘徊
        #   少了组七，组六的「降到了 0」可能只是「租期恰好那么长」——
        #   而那种恒真断言比没有断言更糟，因为它会让人以为验证过了。
        hold_ms = int(ttl_s * 1.4 * 1000)
        long_run = run_sse(f"{RATELIMIT}/fake-stream?holdMs={hold_ms}&deltas=4&label=watchdog")
        admitted_ok = long_run.wait_for("admitted", timeout=30)
        long_tid = (long_run.first("admitted") or {}).get("traceId")

        if admitted_ok and long_tid:
            peaks = []
            for _ in range(4):
                time.sleep(ttl_s / 5)
                peaks.append(trace(long_tid).get("permitRemainingMs"))
            # ★ 断言的是「一直在租期附近」，不是「没变化」——
            #   心跳每 TTL/3 才推一次，所以它会 6.7s ↔ 10s 地锯齿轮动。
            renewed = all(p is not None and p > ttl_s * 1000 * 0.5 for p in peaks)
            check(f"㉒ ★★ 续期确实在跑：占位 {hold_ms}ms（> 租期 {ttl_s:g}s）期间剩余租期始终 > 一半",
                  renewed,
                  f"剩余租期采样={peaks}（租期={ttl_s * 1000:.0f}ms）")
            long_run.join(hold_ms + 30)
            check("㉓ ★ 而且它自己跑完了 —— 没被误判成僵尸而被抢走名额",
                  long_run.has("done") and not long_run.has("failed"),
                  f"events={long_run.names()}")
        else:
            skip("㉒㉓ 看门狗断言", "长占位请求没能拿到名额")
            long_run.join(hold_ms + 30)
        reset()

    # ============================================================
    # 组八 · 拒绝路径（同步那一条）
    # ============================================================
    banner("【八】queue- 池满 —— 出口拒在【返回 emitter 之前】，也必须是一条 SSE")
    reset()
    # ★★ 这一组守的是一个很窄但很脏的窗口：
    #   queue- 池的 maxPoolSize=128 而 queueCapacity=0，所以第 129 个等待者
    #   会在 ChatController 里【同步】被拒 —— 那时 SseEmitter 还没被返回给 Spring。
    #   在早期版本里这条会落到兜底的 500 JSON，而 AsyncConfig 的注释
    #   承诺过要发 failed 事件却【其实没实现】。
    #
    #   ⚠️ 它的形状和别的组都不一样：不是「流中途失败」，而是
    #      「流一开始就失败」。所以断言必须走 HTTP 层，不能只看事件。
    leak(permits=permits)
    pool_max = 128
    over = pool_max + 7
    print(f"  并发 {over} 个（queue- 池 maxPoolSize={pool_max}），"
          f"其中约 {over - pool_max} 个会被【同步】拒绝")
    sync_runs = [run_sse(f"{RATELIMIT}/fake-stream?holdMs=300&label=overflow{i}")
                 for i in range(over)]
    wait_until(lambda: all(r.status is not None for r in sync_runs), timeout=60)
    time.sleep(3)

    # ★ 核心断言：无论走哪条路，HTTP 层都必须是 SSE —— 不能有任何一个
    #   落到「200 + JSON」或「500 + JSON」。前者会被 EventSource 当成空流，
    #   后者会触发 onerror，两者都让前端拿不到那句说明。
    not_sse = [r for r in sync_runs
               if r.status != 200 or "text/event-stream" not in (r.content_type or "")]
    check(f"㉔ ★★ 全部 {over} 个都拿到 SSE 响应（没有一个是 JSON 兜底）",
          not not_sse,
          f"非 SSE 的 {len(not_sse)} 个：" +
          (f"status={not_sse[0].status} ct={not_sse[0].content_type} "
           f"body={not_sse[0].body_head[:120]!r}" if not_sse else "0 个"))

    rejected = [r for r in sync_runs if r.has("failed")]
    check("㉕ ★ 其中确实有人收到了 failed 事件（说明这条路真的被走到了）",
          len(rejected) > 0,
          f"{len(rejected)}/{over} 个被拒绝，"
          f"消息={((rejected[0].first('failed') or {}).get('message') if rejected else None)!r}")

    # ★★★ 组间排空 —— 这一步是后补的，补的理由是一次【真实端到端失败】。
    #
    #   本组故意把 `queue-` 池打满（128 个等待线程）。而 reset() 【不会取消】它们 ——
    #   它们要靠自己的下一次重试才会发现「名额空出来了」然后跑完，大约需要 5 秒。
    #
    #   ⚠️ 少了这一步，紧接着的 --real 那 10 个真实请求会撞在一个【仍然满着】的
    #      排队线程池上，于是全部被【同步】拒绝（原因=排队线程池已满），
    #      现象是「真实 10 并发只有 2 个成功」——
    #      而 /state 上一切正常（permitsInUse=0、queueSize=0），
    #      **看起来完全像是真实链路坏了**。实测就是这样误报过一次。
    #
    #   教训和「逐轮压测要等上一轮结束」是同一条：**一个组把资源打满之后，
    #   下一个组必须等它排空，否则两个组的结论会互相污染。**
    drained = wait_until(lambda: (s := state())["queuePool"]["active"] == 0, timeout=90)
    for r in sync_runs:
        r.join(90)
    if not drained:
        print(f"     ⚠️ queue- 池没排空（active={state()['queuePool']['active']}），"
              f"后面的真实测试会受影响")
    reset()

    # ============================================================
    # 真实端到端（可选，花钱）
    # ============================================================
    if args.real:
        banner("【真】真实 10 并发端到端 —— 排队层接进真实链路了吗")
        print("  ⚠️ 这一步会真的调模型（10 次问答），花钱。")
        reset()
        import urllib.parse
        q = urllib.parse.quote("商品支持七天无理由退货吗")
        real_runs = [run_sse(f"/api/chat/stream?question={q}&sessionNo=probe-real-{i}")
                     for i in range(10)]
        wait_until(lambda: all(r.t_end is not None for r in real_runs), timeout=300)
        ok = [r for r in real_runs if r.has("done")]
        bad = [r for r in real_runs if not r.has("done")]
        # ★ 失败时要把【那句 failed 说了什么】打出来 —— 否则「3/10」这个数字
        #   既可能是限流（正常），也可能是模型挂了（不正常），而两者修法相反。
        bad_detail = ""
        if bad:
            msgs = {(r.first("failed") or {}).get("message") or r.error or "无事件" for r in bad}
            bad_detail = f"；失败样本 events={bad[0].names()} 消息={list(msgs)[:2]}"
        check(f"㉖ ★★ 真实 10 并发：{len(ok)}/10 拿到 done",
              len(ok) == 10,
              f"事件种类={sorted(set(real_runs[0].names()))} "
              f"delta 数={real_runs[0].names().count('delta')}{bad_detail}")
        check("㉗ 真实路径也带排队事件（admitted 一定在）",
              all(r.has("admitted") for r in real_runs),
              "全部有 admitted" if all(r.has("admitted") for r in real_runs) else "有缺失")

        # ★★★ 正文在【delta】里，不在 done 里 —— 这一条我第一版写错了。
        #
        #   第一版断言的是 `done.answer` 非空，结果 10 个全是 0 长度。
        #   原因不是链路坏了，是 ChatServiceImpl.buildStreamResponse 里写着：
        #
        #       null,   // 流式的正文已经分批推走了，这里不再重复返回
        #
        #   ⚠️ 而【非流式】的 buildResponse 里同一个位置是 response.content()。
        #      同一个 record 的同一个字段，两条路径的语义不同 ——
        #      我拿非流式那半边的形状去断言流式这半边。
        #
        #   ★ 这已经是本会话第四次同类错误（前三次都出在 queue_ms 上）：
        #     **一个字段在两条路上有两个含义时，断言必须说清是哪条路。**
        answers = ["".join((p or {}).get("v", "") for _, e, p in r.events if e == "delta")
                   for r in ok]
        check("㉘ ★ 真的生成了回答（把 delta 拼起来是完整正文）",
              all(len(a) > 5 for a in answers),
              f"长度={[len(a) for a in answers]}")

        # ★ 把上面那个坑反过来钉住：done.answer 【应该】是 null。
        #   不是「随便」是 null，而是「流式不重复返回正文」这个决定的可见形式 ——
        #   少了这一条，将来有人顺手把它填上，没人会发现，
        #   而每次回答的 SSE 流量会白白翻倍。
        null_answers = [r for r in ok if (r.first("done") or {}).get("answer") is not None]
        check("㉙ ★ done.answer 必须是 null（流式正文在 delta 里，不重复返回）",
              not null_answers,
              "全部为 null" if not null_answers
              else f"{len(null_answers)} 个把正文又塞回了 done —— SSE 流量会翻倍")
        reset()

    # ============================================================
    print("\n" + "=" * 70)
    passed = sum(1 for _, ok, _ in results if ok is True)
    skipped = sum(1 for _, ok, _ in results if ok is None)
    total = len(results)
    print(f"结果：{passed}/{total - skipped} 项通过"
          + (f"，{skipped} 项跳过" if skipped else ""))
    for name, ok, detail in results:
        if ok is False:
            print(f"  {FAIL} {name}  {detail}")
    # ★ 跳过的项要单独列出来，而且【不能】藏在「全绿」里：
    #   一次「28 项通过、2 项跳过」看起来和「30 项通过」一样好，
    #   但那 2 项恰好是验收标准 3（名额不泄漏）的那两项。
    for name, ok, detail in results:
        if ok is None:
            print(f"  {SKIP} {name}  {detail}")
    print("=" * 70)
    # ★ 退出码只看【失败】。跳过是调用方明确要求的（--skip-slow），
    #   不该让它变成「验收失败」—— 但它必须在上面被看见。
    sys.exit(1 if (total - skipped) != passed else 0)


if __name__ == "__main__":
    main()
