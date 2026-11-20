#!/usr/bin/env bash
# 用途：检查仓库基本规范（只读）：工作区、敏感文件、凭据泄露、文档索引、版本与 tag 关系。
# 用法：scripts/check-repo.sh
# 退出码：0 = 通过（允许 WARN）；1 = 存在必须处理的问题。
# 说明：不修改仓库；不检查 gitignore 之外的构建产物。

set -u

ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null) || {
    echo "FAIL: 当前目录不在 git 仓库内" >&2
    exit 1
}
cd "$ROOT" || exit 1

fail=0
warn=0
ok()   { printf '[ OK ] %s\n' "$*"; }
info() { printf '[INFO] %s\n' "$*"; }
no()   { printf '[WARN] %s\n' "$*"; warn=$((warn + 1)); }
bad()  { printf '[FAIL] %s\n' "$*"; fail=$((fail + 1)); }

echo "== 仓库规范检查：$ROOT =="

# 1. 工作区
dirty=$(git status --porcelain 2>/dev/null)
if [ -z "$dirty" ]; then
    ok "工作区干净"
else
    changed=$(printf '%s\n' "$dirty" | wc -l | tr -d ' ')
    no "工作区有 $changed 个未提交改动（提交前先确认 diff；不要 git add .）"
    printf '%s\n' "$dirty" | head -20 | sed 's/^/       /'
    if [ "$changed" -gt 20 ]; then
        info "仅显示前 20 项"
    fi
fi

# 2. 敏感文件是否被跟踪
tracked_sensitive=$(git ls-files 2>/dev/null | grep -Ei '\.(jks|keystore|p12|apk)$' || true)
if [ -n "$tracked_sensitive" ]; then
    bad "以下敏感/产物文件被 git 跟踪（应加入 .gitignore 并移出版本库）："
    printf '%s\n' "$tracked_sensitive" | sed 's/^/       /'
else
    ok "无被跟踪的 .jks/.keystore/.p12/.apk"
fi

if git ls-files --error-unmatch local.properties >/dev/null 2>&1; then
    bad "local.properties 被 git 跟踪（含本机路径/可能含签名信息，必须移除）"
else
    ok "local.properties 未被跟踪"
fi

# 3. 凭据泄露扫描（当前工作区，git grep 只扫已跟踪文件）
leaks=$(git grep -n -I -E 'biliterminal123|-----BEGIN [A-Z ]*PRIVATE KEY-----' -- . ':!scripts/check-repo.sh' 2>/dev/null || true)
if [ -n "$leaks" ]; then
    bad "发现疑似凭据/私钥（当前工作区）："
    printf '%s\n' "$leaks" | head -10 | sed 's/^/       /'
else
    ok "未发现已知的口令/私钥字符串"
fi

# 4. 文档索引：docs 顶层 .md 是否登记在 docs/README.md
if [ -f docs/README.md ]; then
    missing=""
    for f in docs/*.md; do
        [ -f "$f" ] || continue
        base=$(basename "$f")
        [ "$base" = "README.md" ] && continue
        grep -qF "$base" docs/README.md || missing="$missing $base"
    done
    if [ -n "$missing" ]; then
        no "以下文档未在 docs/README.md 登记：$missing"
    else
        ok "docs 顶层文档均已登记索引"
    fi
else
    no "缺少 docs/README.md（文档地图）"
fi

# 5. 版本与 tag 关系
vc=$(grep -m1 -E '^[[:space:]]*versionCode[[:space:]]' app/build.gradle 2>/dev/null | grep -oE '[0-9]+' | head -1)
vn=$(grep -m1 -E '^[[:space:]]*versionName[[:space:]]' app/build.gradle 2>/dev/null | sed -E 's/.*"([^"]+)".*/\1/')
info "当前版本: ${vn:-?} / ${vc:-?}"
latest_tag=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
if [ -n "$latest_tag" ]; then
    tag_vc=$(git show "$latest_tag:app/build.gradle" 2>/dev/null | grep -m1 -E '^[[:space:]]*versionCode[[:space:]]' | grep -oE '[0-9]+' | head -1)
    ahead=$(git rev-list --count "$latest_tag..HEAD" 2>/dev/null || echo 0)
    if [ "${ahead:-0}" -gt 0 ] && [ "${tag_vc:-}" = "${vc:-}" ]; then
        no "HEAD 比最新 tag（$latest_tag）多 $ahead 个提交，但 versionCode 仍是 $vc：下次发版前必须递增"
    else
        ok "版本与最新 tag 关系：tag=$latest_tag（versionCode=${tag_vc:-?}），领先 $ahead 个提交"
    fi
else
    info "仓库没有 tag（或不在 git 历史中）"
fi

# 6. 关键工程文件
for f in AGENTS.md .github/workflows/ci.yml .gitattributes; do
    if [ -f "$f" ]; then ok "存在 $f"; else no "缺少 $f"; fi
done

echo
echo "== 汇总：FAIL $fail 项，WARN $warn 项 =="
if [ "$fail" -gt 0 ]; then
    echo "仓库规范检查未通过。" >&2
    exit 1
fi
echo "仓库规范检查通过。"
exit 0