#!/usr/bin/env bash
# ============================================================
# 灌业务种子数据（阶段 8）
#
# 【★★★ 为什么需要它：干净环境的库是【空的】，而且是全空】
#
#   2026-09-24 实测（用一个空卷起全栈，逐表 count）：
#
#       产品 0 · 订单 0 · 券 0 · 知识库文档 0 · 知识库切片 0
#       评测题 0 · qa_log 0 · 会话 0
#
#   Flyway 只建【表结构】，一行数据都不灌。
#
#   ★ 而 SeedRunner 挂在 @Profile("seed") 上 —— 一个【专门的开关】，
#     不是 local。所以 prod profile 下它根本不跑。
#     （它的类注释写着「为什么用 @Profile("seed") 而不是每次都跑？」——
#      这是有意的，灌数据不该是每次启动的副作用。）
#
#   ⇒ 所以干净环境是【两步】，不是一步：
#        ① 本脚本          灌业务数据（商品/订单/券/售后政策）
#        ② scripts/ingest.py  灌知识库（要调向量化）
#
#   ⚠️ 两步的顺序不能反：知识库的「业务表 → 文档」那一步
#      （POST /api/kb/documents/sync）读的就是①灌进去的商品和售后政策。
#      先灌知识库的话会同步到 0 份，而且不报错。
#
# 【★ 为什么用 `run` 而不是往 compose 里加一个服务】
#   `docker compose run` 就是在已有的 app 服务上跑一条【一次性】容器，
#   用的是同一个镜像、同一份环境变量。加一个服务要复制一遍 build 配置，
#   而两份配置会漂移 —— 那正是本项目反复避免的形态。
#
# 【★★★ 为什么不能写完就等它自己退出 —— 实测踩过】
#
#   第一版写的是 `run --rm app --spring.main.web-application-type=none`，
#   并且注释里写着「跑完自己退出」。**那句话是错的。**
#
#   实测：容器灌完种子之后**一直活着**（`docker ps` 里 `Up 14 minutes`）。
#   原因不是 Web 容器 —— 而是这个应用有【一堆常驻的非守护线程】：
#
#       三个 ThreadPoolTaskExecutor（answer- / ingest- / retrieve-）
#       排队限流的心跳定时任务
#       Pub/Sub 的订阅监听容器
#
#   ⇒ JVM 有活着的非守护线程就【不会】退出。`web-application-type=none`
#     只关掉了 Tomcat，关不掉那些。
#
#   ★ 而当时我看到的「EXIT=0」是 **`grep` 的退出码**，不是这条命令的 ——
#     管道后面 `$?` 取的是最后一个命令的状态。
#     ⚠️ 这和本项目那句「别信 HTTP 200，要看返回里有没有新加的键」是同一类：
#        **判据取错了对象，而它看起来一切正常。**
#
#   处置：**不等超时，等日志里的完成标记**。
#   下面是「起 → 轮询日志找标记 → 拆」——
#   确定性等待，不是拍一个 sleep 30。
#
# 【幂等】
#   SeedRunner 的幂等粒度是【按表】（见 CLAUDE.md 第七节）——
#   反复跑是安全的：已有的表会跳过，缺的表会补上。
#   ★ 所以「跑第二次没变化」是正常的，不是没生效。
#
# 用法：
#   bash scripts/seed.sh                       # 默认 project
#   bash scripts/seed.sh -p xbla-clean         # 指定 compose project
# ============================================================
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

PROFILE_ARGS=(--profile full)
if [ "${1:-}" = "-p" ] && [ -n "${2:-}" ]; then
    PROFILE_ARGS=(-p "$2" --profile full)
fi

# 完成标记。★ 取自 SeedRunner 自己的日志（`=== 种子数据检查/灌入完成`）——
# 不另造一个「我自己的标记」：那样它和真实完成之间就多了一层会漂移的东西。
DONE_MARKER="灌入完成"
FAIL_MARKER="Exception"

CONTAINER="xbla-seed-$$"
MAX_WAIT=180   # 秒。正常约 2 秒就完成，这个上限只是防它卡死

echo "============================================================"
echo "灌业务种子数据"
echo "============================================================"

cleanup() {
    # ★ 无论怎么退出都要拆掉那个容器 —— 它不会自己消失
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ★ 覆盖 profile：prod（数据源指向容器里的 postgres）+ seed（打开 SeedRunner）。
#   Spring 支持逗号分隔的多个 profile。
docker compose "${PROFILE_ARGS[@]}" run -d --name "$CONTAINER" \
    -e SPRING_PROFILES_ACTIVE=prod,seed \
    app --spring.main.web-application-type=none >/dev/null

echo "  （等 SeedRunner 报完成…）"

elapsed=0
while [ "$elapsed" -lt "$MAX_WAIT" ]; do
    logs="$(docker logs "$CONTAINER" 2>&1 || true)"

    if printf '%s' "$logs" | grep -q "$DONE_MARKER"; then
        # ★ 把 SeedRunner 自己打的那几行原样给人看 ——
        #   它是「灌了什么、跳过什么」的唯一出处，不要转述
        printf '%s\n' "$logs" | grep -E "SeedRunner|profiles are active"
        echo
        echo "============================================================"
        echo "✅ 种子数据完成（耗时 ${elapsed}s，容器已拆除）"
        echo "★ 下一步（如果还没做）：灌知识库"
        echo "     python scripts/ingest.py --url http://localhost -u <用户名>:<口令>"
        echo "   ⚠️ 顺序不能反 —— 知识库要从业务表同步出商品和售后政策文档"
        echo "============================================================"
        exit 0
    fi

    if printf '%s' "$logs" | grep -q "$FAIL_MARKER"; then
        echo "❌ 启动或灌库过程中抛异常了："
        printf '%s\n' "$logs" | tail -30
        exit 1
    fi

    sleep 2
    elapsed=$((elapsed + 2))
done

echo "❌ 等了 ${MAX_WAIT}s 还没看到完成标记 —— 把它最后 30 行打出来："
docker logs "$CONTAINER" 2>&1 | tail -30
exit 1
