#!/usr/bin/env bash
# 用途：检查本机开发环境是否满足本仓库构建要求（只读，不修改任何文件/配置）。
# 用法：scripts/check-env.sh
# 退出码：0 = 环境可用（允许 WARN）；1 = 存在必须修复的问题。
# 说明：缺少某个工具时给出明确提示，不尝试安装。

set -u

ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null) || {
    echo "FAIL: 当前目录不在 git 仓库内（脚本需放在仓库的 scripts/ 下）" >&2
    exit 1
}
cd "$ROOT" || exit 1

fail=0
warn=0
ok()   { printf '[ OK ] %s\n' "$*"; }
info() { printf '[INFO] %s\n' "$*"; }
no()   { printf '[WARN] %s\n' "$*"; warn=$((warn + 1)); }
bad()  { printf '[FAIL] %s\n' "$*"; fail=$((fail + 1)); }

echo "== 环境检查：$ROOT =="

# 1. git
if command -v git >/dev/null 2>&1; then
    ok "git: $(git --version 2>/dev/null | awk '{print $3}')"
else
    bad "未找到 git（无法检查分支/工作区，也无法构建发布）"
fi

branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "(unknown)")
head_short=$(git rev-parse --short HEAD 2>/dev/null || echo "?")
dirty_count=$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')
info "分支: $branch ｜ HEAD: $head_short ｜ 未提交改动: $dirty_count 个文件"

# 2. JDK
if command -v java >/dev/null 2>&1; then
    java_line=$(java -version 2>&1 | head -1)
    java_ver=$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')
    java_major=${java_ver%%.*}
    case "$java_major" in
        1) java_major=$(printf '%s' "$java_ver" | cut -d. -f2) ;;
    esac
    if [ -n "${java_major:-}" ] && [ "$java_major" -ge 17 ] 2>/dev/null; then
        ok "JDK: ${java_ver}（要求 17+）"
    else
        bad "JDK: $java_ver 低于 17（构建需要 JDK 17）"
    fi
    info "JAVA_HOME=${JAVA_HOME:-<未设置>} ｜ $java_line"
else
    bad "未找到 java（需要 JDK 17+）"
fi

# 3. Android SDK
sdk=""
if [ -f local.properties ]; then
    sdk=$(grep -E '^[[:space:]]*sdk\.dir=' local.properties 2>/dev/null | head -1 | cut -d= -f2-)
fi
if [ -z "${sdk:-}" ]; then
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
fi
if [ -n "${sdk:-}" ] && [ -d "$sdk" ]; then
    ok "Android SDK: $sdk"
    if [ -d "$sdk/platforms/android-33" ]; then
        ok "platform: android-33 存在（compileSdk 33）"
    else
        no "缺少 platforms/android-33（可用 sdkmanager 安装）"
    fi
    if [ ! -d "$sdk/build-tools" ]; then
        no "缺少 build-tools（aapt2 不可用）"
    fi
else
    bad "未找到 Android SDK：在 local.properties 写 sdk.dir，或设置 ANDROID_HOME"
fi

# 4. aapt2 架构（ARM64 主机与 x86_64 aapt2 不兼容）
aapt2=""
if [ -n "${sdk:-}" ] && [ -d "$sdk/build-tools" ]; then
    aapt2=$(find "$sdk/build-tools" -maxdepth 2 -name aapt2 -type f 2>/dev/null | sort -V | tail -1)
fi
host_machine=$(uname -m 2>/dev/null || echo unknown)
if [ -n "$aapt2" ]; then
    # ELF e_machine（offset 18，2 字节小端）：b7 00=arm64，3e 00=x86_64
    elf=$(od -An -tx1 -j18 -N2 "$aapt2" 2>/dev/null | tr -d ' \n')
    case "$elf" in
        b700) aapt2_arch="arm64" ;;
        3e00) aapt2_arch="x86_64" ;;
        *)    aapt2_arch="unknown" ;;
    esac
    if [ "$aapt2_arch" = "x86_64" ] && [ "$host_machine" = "aarch64" ]; then
        no "aapt2 是 x86_64，当前主机是 aarch64：Debug 构建需加 -Pandroid.aapt2FromMavenOverride=<arm64 aapt2>（见 docs/development/environment.md §4）"
    else
        ok "aapt2: ${aapt2}（${aapt2_arch}，主机 ${host_machine}）"
    fi
else
    info "未找到 aapt2（x86_64 环境由 Gradle 自带，可忽略）"
fi

# 5. Gradle Wrapper
if [ -x ./gradlew ]; then
    dist_url=$(grep -E '^distributionUrl=' gradle/wrapper/gradle-wrapper.properties 2>/dev/null | cut -d= -f2-)
    gradle_ver=$(printf '%s' "$dist_url" | sed -E 's#.*/gradle-([0-9.]+)-bin\.zip#\1#')
    ok "Gradle Wrapper: ${gradle_ver:-未知版本}"
    if grep -qE '^distributionSha256Sum=' gradle/wrapper/gradle-wrapper.properties 2>/dev/null; then
        ok "wrapper 校验和已配置"
    else
        no "wrapper 未配置 distributionSha256Sum（供应链校验缺失）"
    fi
    if ls -d "$HOME"/.gradle/wrapper/dists/gradle-* >/dev/null 2>&1; then
        ok "Gradle 分发件已缓存（$HOME/.gradle/wrapper/dists）"
    else
        info "Gradle 分发件未缓存：首次构建需联网下载"
    fi
else
    bad "缺少可执行的 gradlew"
fi

# 6. 磁盘空间（构建缓存 + 产物）
if command -v df >/dev/null 2>&1; then
    avail_kb=$(df -Pk . 2>/dev/null | awk 'NR==2 {print $4}')
    if [ -n "${avail_kb:-}" ]; then
        avail_mb=$((avail_kb / 1024))
        if [ "$avail_mb" -lt 2048 ]; then
            no "磁盘可用仅 ${avail_mb}MB（建议 >2GB）"
        else
            ok "磁盘可用: $((avail_mb / 1024))GB"
        fi
    fi
else
    info "无 df，跳过磁盘检查"
fi

# 7. 发布工具（发布链只在 CI，缺失不影响本地开发）
for tool in gh jq; do
    if command -v "$tool" >/dev/null 2>&1; then
        info "$tool: 已安装（可选，用于读取 CI 结果/本地发布检查）"
    else
        info "$tool: 未安装（发布链在 CI 执行，本地开发不需要）"
    fi
done

echo
echo "== 汇总：FAIL $fail 项，WARN $warn 项 =="
if [ "$fail" -gt 0 ]; then
    echo "环境未就绪：请先处理 FAIL 项。" >&2
    exit 1
fi
echo "环境可用。下一步：scripts/check-repo.sh 检查仓库规范。"
exit 0