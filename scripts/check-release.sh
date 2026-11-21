#!/usr/bin/env bash
# 用途：发版前置校验（release job 专用）。
#   1. tag 与 versionName 严格一致；仅放行历史遗留的 vX.Y.Z-fix / vX.Y.Zfix 命名；
#   2. 新 tag 的 versionCode 必须大于当前最新 Release（同 tag 补发由调用方跳过本项）；
#   3. 签名 secrets 4 键必须齐全，避免静默产出未签名正式包。
# 用法：TAG=... VERSION_NAME=... VERSION_CODE=... PREVIOUS_VERSION_CODE=... \
#        KEYSTORE_B64=... KEY_PASSWORD=... ALIAS_NAME=... ALIAS_PASSWORD=... \
#        scripts/check-release.sh
# 退出码：0 = 通过；1 = 任一硬条件不满足。
# 说明：不联网、不读取文件内容；所有变量由 release job 传入，输出不含 secret 值。

set -Eeuo pipefail

ok()   { printf '[ OK ] %s\n' "$*"; }
info() { printf '[INFO] %s\n' "$*"; }
no()   { printf '[FAIL] %s\n' "$*" >&2; }

fail=0

TAG=${TAG:-}
VERSION_NAME=${VERSION_NAME:-}
VERSION_CODE=${VERSION_CODE:-}
PREVIOUS_VERSION_CODE=${PREVIOUS_VERSION_CODE:-}

if [ -z "$TAG" ]; then no "TAG 为空"; fail=1; fi
if [ -z "$VERSION_NAME" ]; then no "VERSION_NAME 为空"; fail=1; fi
if ! [[ "$VERSION_CODE" =~ ^[0-9]+$ ]]; then no "VERSION_CODE 不是数字：[$VERSION_CODE]"; fail=1; fi

# 1. tag 与 versionName
if [ -n "$TAG" ] && [ -n "$VERSION_NAME" ]; then
    TAG_VERSION=${TAG#v}
    if [ "$TAG_VERSION" = "$VERSION_NAME" ]; then
        ok "tag 与 versionName 一致：$TAG == v$VERSION_NAME"
    else
        # 兼容历史 -fix/fix 命名（例如 v1.2.0fix、v1.0.2-fix1），但 base 版本仍须一致
        case "$TAG_VERSION" in
            *fix*)
                base=${TAG_VERSION%%fix*}
                base=${base%-}
                if [ "$base" = "$VERSION_NAME" ]; then
                    info "历史 fix tag 兼容放行：$TAG（base=$VERSION_NAME；新 tag 请统一 v<versionName>）"
                else
                    no "tag 与 versionName 不一致：$TAG vs v$VERSION_NAME（历史 fix tag 的 base 也不一致）"
                    fail=1
                fi
                ;;
            *)
                no "tag 与 versionName 不一致：$TAG vs v$VERSION_NAME；正式发版只允许 v<versionName>"
                fail=1
                ;;
        esac
    fi
fi

# 2. versionCode 必须递增（同 tag 补发由 workflow 传空 PREVIOUS_VERSION_CODE 跳过）
if [ "$fail" -eq 0 ]; then
    if [ -n "$PREVIOUS_VERSION_CODE" ]; then
        if ! [[ "$PREVIOUS_VERSION_CODE" =~ ^[0-9]+$ ]]; then
            no "PREVIOUS_VERSION_CODE 不是数字：[$PREVIOUS_VERSION_CODE]"
            fail=1
        elif [ "$VERSION_CODE" -le "$PREVIOUS_VERSION_CODE" ]; then
            no "versionCode 未递增：当前 $VERSION_CODE <= 最新 Release $PREVIOUS_VERSION_CODE"
            fail=1
        else
            ok "versionCode 递增：$PREVIOUS_VERSION_CODE -> $VERSION_CODE"
        fi
    else
        info "未提供 previous versionCode（同 tag 补发或首个 Release）；跳过递增校验"
    fi
fi

# 3. 签名 secrets 全有或全无（正式 tag 不再允许 unsigned 发布）
for key in KEYSTORE_B64 KEY_PASSWORD ALIAS_NAME ALIAS_PASSWORD; do
    if [ -z "${!key:-}" ]; then
        no "缺少签名 secret：$key（正式 tag 必须配置完整签名，禁止 unsigned 发布）"
        fail=1
    fi
done
if [ "$fail" -eq 0 ]; then
    ok "签名 secrets 4 键齐全（值不输出）"
fi

if [ "$fail" -ne 0 ]; then
    printf '发版前置校验失败：修复上述 FAIL 后重新打 tag。\n' >&2
    exit 1
fi

printf '发版前置校验通过：%s（versionName=%s versionCode=%s）\n' "$TAG" "$VERSION_NAME" "$VERSION_CODE"
exit 0