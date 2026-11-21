#!/usr/bin/env bash
# 用途：静态检查 GitHub Actions workflow 配置（只读，不联网、不运行 CI）。
# 用法：scripts/validate-ci.sh
# 退出码：0 = 无 FAIL（允许 WARN/INFO）；1 = 存在 FAIL（缺失关键保护/效率配置）。
# 说明：本机无 PyYAML，这里只做文本断言；YAML 语法请以 GitHub 渲染为准。
#       基线：在 CI 工程改造之前，当前 ci.yml 预期 FAIL 4 项（concurrency/timeout/缓存/retention），
#       这是有意的改进清单，不是脚本故障。

set -u

ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null) || {
    echo "FAIL: 当前目录不在 git 仓库内" >&2
    exit 1
}
cd "$ROOT" || exit 1

WF_DIR=.github/workflows
fail=0
warn=0
ok()   { printf '[ OK ] %s\n' "$*"; }
info() { printf '[INFO] %s\n' "$*"; }
no()   { printf '[WARN] %s\n' "$*"; warn=$((warn + 1)); }
bad()  { printf '[FAIL] %s\n' "$*"; fail=$((fail + 1)); }

if [ ! -d "$WF_DIR" ]; then
    echo "FAIL: 缺少 $WF_DIR 目录" >&2
    exit 1
fi

shopt -s nullglob
files=("$WF_DIR"/*.yml "$WF_DIR"/*.yaml)
if [ ${#files[@]} -eq 0 ]; then
    echo "FAIL: $WF_DIR 下没有 workflow 文件" >&2
    exit 1
fi

echo "== CI 配置静态检查：${#files[@]} 个 workflow =="

for f in "${files[@]}"; do
    echo
    echo "--- $f"

    # 1. 基本结构
    if grep -qE '^name:' "$f"; then ok "有 name"; else no "缺少 name"; fi
    if command -v python3 >/dev/null 2>&1 && python3 -c 'import yaml' >/dev/null 2>&1; then
        if python3 -c "import sys,yaml; list(yaml.safe_load_all(open(sys.argv[1], encoding='utf-8')))" "$f" >/dev/null 2>&1; then
            ok "YAML 语法解析通过（PyYAML）"
        else
            bad "YAML 语法解析失败（PyYAML），GitHub 会拒绝该 workflow"
        fi
    else
        info "未安装 PyYAML，跳过 YAML 语法解析（只做文本断言）"
    fi
    if grep -qE '^on:' "$f"; then ok "有 on 触发块"; else bad "缺少 on 触发块"; fi
    if grep -qE '^[[:space:]]+push:' "$f"; then ok "监听 push"; else info "未监听 push"; fi
    if grep -qE '^[[:space:]]+pull_request:' "$f"; then ok "监听 pull_request"; else no "未监听 pull_request（PR 无自动检查）"; fi
    if grep -qE '^[[:space:]]+workflow_dispatch:' "$f"; then ok "支持手动触发"; else info "未支持 workflow_dispatch"; fi
    if grep -qE '^[[:space:]]+tags:' "$f"; then ok "监听 tag"; else info "未监听 tag（无发版链）"; fi

    # 2. 权限
    if grep -qE '^permissions:' "$f"; then
        if grep -qE '^[[:space:]]{2}contents:[[:space:]]*write' "$f"; then
            no "workflow 级 contents: write（PR 构建也持写权限；建议拆 job，release 单独授权）"
        else
            ok "权限已声明且非 workflow 级写权限"
        fi
    elif grep -qE '^[[:space:]]+permissions:' "$f"; then
        ok "job 级 permissions 已声明"
    else
        bad "未声明 permissions（默认权限随仓库设置漂移）"
    fi

    # 3. 效率与并发
    if grep -qE '^concurrency:' "$f"; then ok "有 concurrency"; else bad "缺少 concurrency（连推会并行跑，release 可能互踩）"; fi
    if grep -qE 'timeout-minutes:' "$f"; then ok "有 timeout-minutes"; else bad "缺少 timeout-minutes（卡死会占满额度）"; fi
    if grep -qE 'cache:[[:space:]]*gradle|gradle/actions/setup-gradle' "$f"; then ok "配置了 Gradle 缓存"; else bad "未配置 Gradle 缓存（每次重新下载依赖）"; fi
    if grep -qE 'retention-days:' "$f"; then ok "artifact 设置了 retention-days"; else bad "artifact 未设 retention-days（默认保留 90 天）"; fi

    # 4. 环境显式化
    if grep -qE 'android-actions/setup-android|sdkmanager|ci-prepare-sdk' "$f"; then
        ok "显式安装/声明 Android SDK"
    else
        no "未显式安装 Android SDK（依赖 runner 预装 ANDROID_HOME）"
    fi

    # 5. 依赖供应链
    unpinned=$(grep -cE 'uses:[[:space:]]*[^#]+@v[0-9]' "$f" 2>/dev/null || true)
    if [ "${unpinned:-0}" -gt 0 ]; then
        no "有 $unpinned 个 action 使用浮动 major tag（未 pin 到 commit SHA）"
    else
        ok "action 未使用浮动 major tag"
    fi

    # 6. 发布链保护
    if grep -qE "refs/tags/" "$f"; then
        ok "发布步骤按 tag 条件收口"
    else
        info "未发现 tag 条件（若本 workflow 不发版可忽略）"
    fi
    secret_lines=$(grep -c 'secrets\.' "$f" 2>/dev/null || true)
    if [ "${secret_lines:-0}" -gt 0 ]; then
        info "引用 secrets 共 $secret_lines 处：需人工确认都位于 tag/受信分支条件内（脚本不做语义判断）"
    fi

    # 7. 发布产物与更新机制
    if grep -q 'update\.json' "$f"; then ok "包含 update.json 生成/上传逻辑"; else info "未发现 update.json 逻辑"; fi
    if grep -q 'dump badging' "$f" && grep -qE 'apksigner.*verify|APKSIGNER.*verify' "$f"; then
        ok "release 签名与 APK versionCode 有硬校验"
    elif grep -qE 'apksigner.*verify|APKSIGNER.*verify' "$f"; then
        ok "release 包含 apksigner 签名校验"
    else
        info "未发现 release 签名校验（若本 workflow 不发版可忽略）"
    fi
    if grep -qE 'mipmap/tutorial_' "$f"; then ok "包含 release 资源冒烟（教程插图）"; else info "未发现资源冒烟断言"; fi
    if grep -q 'dump badging' "$f"; then
        ok "versionCode 从已构建 APK 读取（aapt2 dump badging）"
    elif grep -qE "grep -E 'versionCode" "$f"; then
        no "versionCode 通过 grep build.gradle 取得（脆弱；建议从已构建 APK 的 aapt2 badging 读取）"
    else
        ok "未发现脆弱的 versionCode 解析"
    fi

    # 8. 触发面
    if grep -qE 'paths-ignore:|paths:' "$f"; then
        ok "配置了 paths 过滤"
    else
        info "未配置 paths-ignore（docs-only 变更也会跑全量构建）"
    fi

    # 9. 发版门禁（P0）：release 必须等全部日常 job，且包含前置校验/签名校验
    if grep -qE '^  release:' "$f"; then
        release_block=$(sed -n '/^  release:/,$p' "$f")
        if printf '%s\n' "$release_block" | grep -qE 'needs:.*\[.*checks.*unit-test.*\]|needs:.*\[.*unit-test.*checks.*\]'; then
            ok "release 依赖 checks + unit-test（测试失败不发布）"
        elif printf '%s\n' "$release_block" | grep -q 'scripts/check-release.sh'; then
            bad "release 未同时依赖 checks + unit-test（测试失败仍可能发布）"
        else
            info "release 未使用 P0 门禁脚本（若本 workflow 不发布可忽略）"
        fi
        if printf '%s\n' "$release_block" | grep -q 'scripts/check-release.sh'; then
            ok "release 调用发版前置校验脚本（tag/versionCode/签名）"
        else
            bad "release 缺少 scripts/check-release.sh 前置校验"
        fi
        if printf '%s\n' "$release_block" | grep -qE 'apksigner.*verify|APKSIGNER.*verify'; then
            ok "release 构建后校验 APK 签名"
        else
            bad "release 缺少 apksigner verify（unsigned 可能静默发布）"
        fi
    fi
done

echo
echo "== 汇总：FAIL $fail 项，WARN $warn 项 =="
if [ "$fail" -gt 0 ]; then
    echo "CI 配置存在必须修复项（基线清单见 docs/development/environment.md）。" >&2
    exit 1
fi
echo "CI 配置静态检查通过。"
exit 0