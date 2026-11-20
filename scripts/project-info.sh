#!/usr/bin/env bash
# 用途：输出项目关键工程信息（JSON），供 Agent/脚本读取，避免手抄版本号。
# 用法：scripts/project-info.sh
# 说明：只读；信息全部动态读取 gradle/git 配置，不维护 project-info.json 之类的副本。
#       无 jq 也能用：输出是标准 JSON，可直接交给 node -e 'JSON.parse(...)' 验证。

set -u

ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null) || {
    echo "ERROR: 当前目录不在 git 仓库内" >&2
    exit 1
}
cd "$ROOT" || exit 1

json_escape() {
    printf '%s' "$1" | tr -d '\r' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/	/\\t/g'
}

# --- Gradle / Android 配置 ---
version_code=$(grep -m1 -E '^[[:space:]]*versionCode[[:space:]]' app/build.gradle 2>/dev/null | grep -oE '[0-9]+' | head -1)
version_name=$(grep -m1 -E '^[[:space:]]*versionName[[:space:]]' app/build.gradle 2>/dev/null | sed -E 's/.*"([^"]+)".*/\1/')
application_id=$(grep -m1 -E '^[[:space:]]*applicationId[[:space:]]' app/build.gradle 2>/dev/null | sed -E 's/.*"([^"]+)".*/\1/')
compile_sdk=$(grep -m1 -E '^[[:space:]]*compileSdk[[:space:]]' app/build.gradle 2>/dev/null | grep -oE '[0-9]+' | head -1)
min_sdk=$(grep -m1 -E '^[[:space:]]*minSdk[[:space:]]' app/build.gradle 2>/dev/null | grep -oE '[0-9]+' | head -1)
target_sdk=$(grep -m1 -E '^[[:space:]]*targetSdk[[:space:]]' app/build.gradle 2>/dev/null | grep -oE '[0-9]+' | head -1)
agp_version=$(grep -m1 "id 'com.android.application' version" build.gradle 2>/dev/null | sed -E "s/.*version '([^']+)'.*/\1/")
gradle_version=$(grep -E '^distributionUrl=' gradle/wrapper/gradle-wrapper.properties 2>/dev/null | sed -E 's#.*gradle-([0-9.]+)-bin\.zip#\1#')
project_name=$(grep -m1 -E '^rootProject\.name' settings.gradle 2>/dev/null | sed -E 's/.*"([^"]+)".*/\1/')

# --- Git 状态 ---
branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
head_full=$(git rev-parse HEAD 2>/dev/null || echo "")
head_short=$(git rev-parse --short HEAD 2>/dev/null || echo "")
describe=$(git describe --tags --always --dirty 2>/dev/null || echo "")
latest_tag=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
tag_count=$(git tag -l 2>/dev/null | wc -l | tr -d ' ')
remote=$(git remote get-url origin 2>/dev/null || echo "")
if [ -z "$(git status --porcelain 2>/dev/null)" ]; then dirty=false; else dirty=true; fi

num_or_zero() { case "${1:-}" in ''|*[!0-9]*) printf '0' ;; *) printf '%s' "$1" ;; esac; }

cat <<EOF
{
  "project": "$(json_escape "${project_name:-}")",
  "generatedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "branch": "$(json_escape "$branch")",
  "head": "$(json_escape "$head_short")",
  "headFull": "$(json_escape "$head_full")",
  "describe": "$(json_escape "$describe")",
  "latestTag": "$(json_escape "$latest_tag")",
  "tagCount": $(num_or_zero "$tag_count"),
  "worktreeDirty": $dirty,
  "remote": "$(json_escape "$remote")",
  "versionName": "$(json_escape "${version_name:-}")",
  "versionCode": $(num_or_zero "$version_code"),
  "applicationId": "$(json_escape "${application_id:-}")",
  "compileSdk": $(num_or_zero "$compile_sdk"),
  "minSdk": $(num_or_zero "$min_sdk"),
  "targetSdk": $(num_or_zero "$target_sdk"),
  "agpVersion": "$(json_escape "${agp_version:-}")",
  "gradleVersion": "$(json_escape "${gradle_version:-}")"
}
EOF