#!/usr/bin/env bash
# ============================================================
# 生成 Nginx Basic Auth 的口令文件（阶段 8）
#
# 用法：
#   bash deploy/make-htpasswd.sh                 # 交互式输入口令
#   bash deploy/make-htpasswd.sh <用户名> <口令>   # 非交互（⚠️ 口令会进 shell 历史）
#
# 产物：deploy/.htpasswd
#
# 【★ 为什么这个文件不进 git】
#   它是**凭据**。虽然只是一个演示站的口令，但把它提交上去等于
#   把「访问控制」这件事的答案公开 —— 而 Basic Auth 是这一版【唯一】的
#   访问控制手段（公网隧道上除了它没有别的门）。
#   所以 .gitignore 里有一条 deploy/.htpasswd。
#
# 【★ 为什么不把这个文件打进镜像】
#   见 deploy/Dockerfile.web：镜像里只建 /etc/nginx/auth 目录，
#   文件由 compose 以只读方式挂进来。理由和上面一样 ——
#   镜像层里有过的东西，删掉也还在历史里。
#
# 【为什么用 apr1 而不是 bcrypt】
#   nginx 支持 crypt / apr1 / {PLAIN} / SHA。bcrypt 需要 nginx 编译时带
#   --with-http_auth_request_module 之外的支持，而 alpine 官方镜像【不带】。
#   ★ apr1（Apache MD5 变体）是官方镜像一定认的那一种。
#   ⚠️ 它的强度弱于 bcrypt —— 但因为配合了隧道和限流，
#      而且这只是演示站，这个取舍是清楚的（不是没想到）。
# ============================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/.htpasswd"

USER_NAME="${1:-xbla}"

if [ "$#" -ge 2 ]; then
    PASSWORD="$2"
else
    if [ ! -t 0 ]; then
        echo "非交互环境请传参：bash deploy/make-htpasswd.sh <用户名> <口令>" >&2
        exit 2
    fi
    read -r -s -p "给演示站设一个口令（输入不回显）：" PASSWORD
    echo
    read -r -s -p "再输一遍：" PASSWORD2
    echo
    if [ "$PASSWORD" != "$PASSWORD2" ]; then
        echo "两次输入不一致" >&2
        exit 1
    fi
fi

if [ -z "$PASSWORD" ]; then
    echo "口令不能为空" >&2
    exit 1
fi

# ★ openssl passwd -apr1 从【标准输入】读口令，不走命令行参数 ——
#   走参数的话口令会出现在进程列表里（ps 就能看到），
#   也会进 shell 历史。这里用 stdin 避开前一个。
HASH="$(printf '%s' "$PASSWORD" | openssl passwd -apr1 -stdin)"

# umask 在【创建文件之前】设（先创建再 chmod 会有一个极短的可读窗口）。
#
# ⚠️ 实测（2026-09-24，Windows + Git Bash）：这条 umask **没有生效** ——
#    产物是 644 而不是 600。原因是 NTFS 的权限模型和 POSIX 不同，
#    Git Bash 只能用一套映射来假装，umask 落不到实处。
#
#    ★ 所以这一行的保护在 Windows 上是【没有的】，真正在起作用的是
#      「.gitignore 里有 deploy/.htpasswd」那一条。
#    ★ 在 Linux/macOS 上它会正常生效。
#    ★ 把这个差异写下来，是为了不让下一个读代码的人以为这里有一层
#      实际上不存在的保护。
( umask 077; printf '%s:%s\n' "$USER_NAME" "$HASH" > "$OUT" )

echo "已生成 $OUT"
echo "  用户名：$USER_NAME"
echo "  （口令不回显，也没记在任何地方 —— 忘了就重新跑一次）"
echo
echo "★ 下一步：把 用户名:口令 随地址一起发给人，并在浏览器弹窗里填。"
echo "★ 确认 .gitignore 里有 deploy/.htpasswd —— 它是凭据，不能提交。"
