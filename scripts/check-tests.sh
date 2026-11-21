#!/usr/bin/env bash
# 用途：测试体系不变量检查（只读，不联网、不运行测试）。
#
# 检查两类问题：
#   1) 关键业务约束被测试/重构悄悄破坏（例：收藏夹音频播放「不请求 Audio Focus」的设计）；
#   2) 测试本身不合规（真实 B 站地址、真实凭据、测试依赖误进 release、@Ignore 吞用例…）。
#
# 用法：scripts/check-tests.sh
# 退出码：0 = 通过（允许 WARN）；1 = 存在 FAIL。
# 说明：这是质量门禁的一部分，与 JUnit 互补；断言都基于源码文本，不执行被测代码。

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

TEST_DIR=app/src/test/java
AUDIO_SERVICE=app/src/main/java/com/RobinNotBad/BiliClient/service/AudioPlayerService.java
WF=.github/workflows/ci.yml

echo "== 测试体系不变量检查 =="

# 1. 测试基础设施
if [ -d "$TEST_DIR" ]; then
    test_count=$(find "$TEST_DIR" -name '*Test.java' -type f 2>/dev/null | wc -l | tr -d ' ')
    ok "JVM 测试目录存在（$test_count 个 *Test.java）"
    if [ "$test_count" -lt 10 ]; then
        no "测试文件只有 $test_count 个：核心业务覆盖可能不完整（预期 ≥10）"
    fi
else
    bad "缺少 ${TEST_DIR}（JVM 单元测试目录）"
fi

if grep -qE "^[[:space:]]*testImplementation[[:space:]]+'junit:junit" app/build.gradle 2>/dev/null; then
    ok "JUnit 使用 testImplementation（不会进 release）"
else
    bad "app/build.gradle 缺少 testImplementation junit（测试依赖未隔离）"
fi

if grep -qE "^[[:space:]]*implementation[[:space:]]+'junit:junit" app/build.gradle 2>/dev/null; then
    bad "JUnit 被写成 implementation：测试依赖会进入运行时/APK"
fi

if grep -qE "^[[:space:]]*testImplementation[[:space:]]+'org.robolectric:robolectric" app/build.gradle 2>/dev/null; then
    ok "Robolectric 使用 testImplementation（仅 JVM 测试）"
else
    info "未使用 Robolectric（若测试不需要 Android 框架可接受）"
fi

# 2. 音频播放设计约束：收藏夹音频与视频并行，刻意不请求 Audio Focus。
#    任何 requestAudioFocus/abandonAudioFocus 调用都会让音乐打断其它音频，属于设计红线。
if [ -f "$AUDIO_SERVICE" ]; then
    if grep -nE 'requestAudioFocus|abandonAudioFocus|AudioManager\.AUDIOFOCUS' "$AUDIO_SERVICE" >/dev/null 2>&1; then
        bad "AudioPlayerService 出现 Audio Focus 调用：违反「收藏夹音频与视频并行/不抢焦点」设计"
        grep -nE 'requestAudioFocus|abandonAudioFocus|AudioManager\.AUDIOFOCUS' "$AUDIO_SERVICE" | sed 's/^/       /'
    else
        ok "AudioPlayerService 未请求 Audio Focus（与视频并行播放的设计保持不变）"
    fi
else
    bad "缺少 ${AUDIO_SERVICE}（无法校验音频焦点设计约束）"
fi

# 3. 测试不得依赖真实线上服务/凭据
real_net=$(grep -rnE 'https?://(api|app|api\.live|passport|space|www)\.bilibili\.com|https?://[^"[:space:]]*supabase|b23\.tv' "$TEST_DIR" 2>/dev/null || true)
if [ -n "$real_net" ]; then
    bad "测试源码出现真实 B 站/Supabase 地址（测试必须离线、用虚构数据）："
    printf '%s\n' "$real_net" | head -10 | sed 's/^/       /'
else
    ok "测试源码未出现真实 B 站/Supabase 地址"
fi

creds=$(grep -rnE 'SESSDATA=|bili_jct=|access_key=[A-Za-z0-9]{8,}|-----BEGIN [A-Z ]*PRIVATE KEY-----' "$TEST_DIR" 2>/dev/null || true)
if [ -n "$creds" ]; then
    bad "测试源码出现疑似真实凭据/私钥："
    printf '%s\n' "$creds" | head -5 | sed 's/^/       /'
else
    ok "测试源码未包含真实凭据"
fi

ignored=$(grep -rnE '@Ignore([^A-Za-z]|$)' "$TEST_DIR" 2>/dev/null || true)
if [ -n "$ignored" ]; then
    no "存在 @Ignore 跳过的用例：必须说明原因，不能用来让门禁变绿"
    printf '%s\n' "$ignored" | head -5 | sed 's/^/       /'
else
    ok "没有 @Ignore 跳过用例"
fi

# 4. CI 必须真的跑测试，且不得用 continue-on-error 吞核心失败
if [ -f "$WF" ]; then
    if grep -qE ':app:testDebugUnitTest' "$WF"; then
        ok "CI 执行 :app:testDebugUnitTest"
    else
        bad "CI 未执行 :app:testDebugUnitTest（测试无法成为质量门禁）"
    fi
    if grep -nE 'continue-on-error:[[:space:]]*true' "$WF" >/dev/null 2>&1; then
        bad "CI 使用 continue-on-error: true：可能隐藏核心测试/构建失败"
    else
        ok "CI 未使用 continue-on-error 隐藏失败"
    fi
else
    bad "缺少 $WF"
fi

echo
echo "== 汇总：FAIL $fail 项，WARN $warn 项 =="
if [ "$fail" -gt 0 ]; then
    echo "测试体系不变量检查未通过。" >&2
    exit 1
fi
echo "测试体系不变量检查通过。"
exit 0