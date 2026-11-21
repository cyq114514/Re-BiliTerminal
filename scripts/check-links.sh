#!/usr/bin/env bash
# 用途：检查仓库内已跟踪 Markdown 文档中的相对链接目标是否存在（只读，不联网、不修改任何文件）。
# 用法：scripts/check-links.sh
# 退出码：0 = 没有失效相对链接（允许 WARN）；1 = 存在失效链接或缺少必需依赖。
# 说明：扫描范围 = git ls-files '*.md'；目标相对文档所在目录解析（以 / 开头按仓库根解析）；
#       忽略 http(s)/mailto 及其他带 scheme 的外部地址、纯锚点、协议相对地址；
#       去掉 #锚点后再判断文件是否存在。只检查行内链接 [文字](目标)，
#       图片、引用式链接与代码块/围栏内示例不在检查范围。

set -u

if ! command -v git >/dev/null 2>&1; then
    echo "FAIL: 缺少必需依赖 git（用于枚举已跟踪的 Markdown 文档；脚本不会自动安装）" >&2
    exit 1
fi

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

echo "== Markdown 相对链接检查：$ROOT =="

doc_count=0
docs_with_links=0
link_count=0
skip_count=0
scheme_re='^[A-Za-z][A-Za-z0-9+.-]*:'
fence_re='^[[:space:]]*(```|~~~)'
# 行内链接：[文字](目标)；前面不能是 !（排除图片）。group 3 是目标。
link_re='(^|[^!])(\[[^]]*\]\(([^)]*)\))'

while IFS= read -r -d '' doc; do
    doc_count=$((doc_count + 1))
    case "$doc" in
        */*) docdir=${doc%/*} ;;
        *)   docdir=. ;;
    esac
    lineno=0
    in_fence=0
    doc_links=0
    fail_before=$fail

    while IFS= read -r line || [ -n "$line" ]; do
        lineno=$((lineno + 1))

        # 跳过围栏代码块内的示例文本（避免把示例里的链接当成真实链接）
        if [[ "$line" =~ $fence_re ]]; then
            if [ "$in_fence" -eq 1 ]; then in_fence=0; else in_fence=1; fi
            continue
        fi
        [ "$in_fence" -eq 1 ] && continue

        # 绝大多数行没有行内链接，先用纯 Bash 快速排除
        case "$line" in
            *']('*) ;;
            *) continue ;;
        esac

        rest=$line
        while [[ "$rest" =~ $link_re ]]; do
            whole=${BASH_REMATCH[0]}
            target=${BASH_REMATCH[3]}
            rest=${rest#*"$whole"}

            # 去掉首尾空白（read 按 IFS 处理，内部空格保留）
            read -r target <<< "$target"

            # 支持 <带空格的路径> 与 ](path "title") 两种写法
            if [ "${target#<}" != "$target" ]; then
                target=${target#<}
                target=${target%%>*}
            else
                target=${target%%[[:space:]]*}
            fi

            # 外部地址 / 纯锚点 / 协议相对地址：不属于“仓库内相对链接”
            case "$target" in
                ''|'#'*|//*)
                    skip_count=$((skip_count + 1))
                    continue
                    ;;
            esac
            if [[ "$target" =~ $scheme_re ]]; then
                skip_count=$((skip_count + 1))
                continue
            fi

            # 去掉 #锚点：file.md#sec → file.md
            path=${target%%#*}
            if [ -z "$path" ]; then
                skip_count=$((skip_count + 1))
                continue
            fi

            case "$path" in
                /*) resolved="$ROOT$path" ;;
                *)  resolved="$docdir/$path" ;;
            esac

            link_count=$((link_count + 1))
            doc_links=$((doc_links + 1))
            if [ ! -e "$resolved" ]; then
                bad "$doc:$lineno → ${target}（解析为 ${resolved#"$ROOT"/}，不存在）"
            fi
        done
    done < "$doc"

    if [ "$doc_links" -gt 0 ]; then
        docs_with_links=$((docs_with_links + 1))
        if [ "$fail" -eq "$fail_before" ]; then
            ok "${doc}（$doc_links 条相对链接均存在）"
        fi
    fi
done < <(git ls-files -z -- '*.md')

if [ "$doc_count" -eq 0 ]; then
    no "未发现已跟踪的 Markdown 文档（git ls-files '*.md' 为空）"
fi
if [ "$link_count" -eq 0 ] && [ "$doc_count" -gt 0 ]; then
    info "未发现需要检查的仓库内相对链接"
fi

echo
echo "== 汇总：FAIL $fail 条，WARN $warn 项（扫描 $doc_count 个 Markdown，$docs_with_links 个含相对链接；检查 $link_count 条，跳过外部/锚点 $skip_count 条） =="
if [ "$fail" -gt 0 ]; then
    echo "存在失效相对链接：请修复文档链接或目标文件。" >&2
    exit 1
fi
echo "相对链接检查通过。"
exit 0