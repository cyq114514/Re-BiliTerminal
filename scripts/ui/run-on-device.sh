#!/usr/bin/env bash
# 在已启动的模拟器上执行 UI 冒烟（由 reactivecircus/android-emulator-runner 的 script 调用）。
# 输入（workflow env）：UI_DEVICE_ID/UI_LABEL/UI_API/UI_TARGET/UI_ARCH/UI_PROFILE/UI_AVD/UI_ROUND/UI_APK_DIR/UI_REPORT_DIR
# 输出：$UI_REPORT_DIR 下 device-info、screenshots、ui-report/*.xml、logcat、instrumentation.txt、status.json、diff、index.html
set -Eeuo pipefail

ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"
PKG="${UI_PKG:-com.RobinNotBad.BiliClient.re.test}"
REPORT="${UI_REPORT_DIR:-ui-report}"
APK_DIR="${UI_APK_DIR:-ui-apk}"
mkdir -p "$REPORT"/{screenshots,logcat,diff}
REPORT=$(cd "$REPORT" && pwd)
APK_DIR=$(cd "$APK_DIR" && pwd)

stage() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$REPORT/stages.txt"; }
fail_stage() {
    local code=$?
    stage "FAILED at ${1:-unknown} (exit $code)"
    exit "$code"
}
trap 'fail_stage "script-${UI_DEVICE_ID:-unknown}"' ERR

APP_APK=$(ls "$APK_DIR"/*.apk 2>/dev/null | grep -v -i androidTest | head -1 || true)
TEST_APK=$(ls "$APK_DIR"/*androidTest*.apk 2>/dev/null | head -1 || true)
[ -n "$APP_APK" ] || { stage "找不到 app APK（$APK_DIR）"; exit 2; }
[ -n "$TEST_APK" ] || { stage "找不到 androidTest APK（$APK_DIR）"; exit 2; }

stage "wait for adb"
adb wait-for-device
for _ in $(seq 1 60); do
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 5
done
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || { stage "模拟器未完成启动"; exit 3; }

stage "collect device info"
{
    echo "device_id=$UI_DEVICE_ID"
    echo "label=$UI_LABEL"
    echo "avd=$UI_AVD"
    echo "api=$UI_API"
    echo "target=$UI_TARGET"
    echo "arch=$UI_ARCH"
    echo "profile=$UI_PROFILE"
    echo "round=$UI_ROUND"
    echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
    echo "device=$(adb shell getprop ro.product.device | tr -d '\r')"
    echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
    echo "release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
    echo "characteristics=$(adb shell getprop ro.build.characteristics | tr -d '\r')"
    echo "wm_size=$(adb shell wm size | tr -d '\r')"
    echo "wm_density=$(adb shell wm density | tr -d '\r')"
} > "$REPORT/device-info.txt"
USER_ROT=$(adb shell settings get system user_rotation | tr -d '\r' || true)
ACCEL=$(adb shell settings get system accelerometer_rotation | tr -d '\r' || true)
python3 - "$REPORT" "$USER_ROT" "$ACCEL" <<'PY'
import json, os, sys
report, rotation, accel = sys.argv[1], sys.argv[2], sys.argv[3]
kv = {}
with open(os.path.join(report, "device-info.txt"), encoding="utf-8") as f:
    for line in f:
        k, sep, v = line.strip().partition("=")
        if sep:
            kv[k] = v
kv["user_rotation"] = rotation
kv["accelerometer_rotation"] = accel
with open(os.path.join(report, "device-info.json"), "w", encoding="utf-8") as f:
    json.dump(kv, f, ensure_ascii=False, indent=2)
PY

stage "install APKs"
adb install -r -t "$APP_APK" >/dev/null
adb install -r -t "$TEST_APK" >/dev/null
adb shell pm clear "$PKG" >/dev/null

stage "seed isolated UI prefs (no telemetry, skip setup)"
ROUND_BOOL=false
[ "$UI_ROUND" = "1" ] && ROUND_BOOL=true
cat > "$REPORT/prefs.xml" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="setup" value="true" />
    <boolean name="crash_report_enable" value="false" />
    <boolean name="crash_report_asked" value="true" />
    <boolean name="usage_stats_enable" value="false" />
    <boolean name="player_ui_round" value="$ROUND_BOOL" />
    <float name="dpi" value="1.0" />
</map>
XML
adb push "$REPORT/prefs.xml" /data/local/tmp/rebili-ui-prefs.xml >/dev/null
adb shell run-as "$PKG" sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/rebili-ui-prefs.xml shared_prefs/default.xml && chmod 600 shared_prefs/default.xml' >/dev/null

stage "disable animations / lock rotation"
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$k" 0 >/dev/null 2>&1 || true
done
adb shell settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true

stage "cold start app (real launcher alias)"
adb logcat -c || true
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$PKG/.LauncherDefault" > "$REPORT/cold-start.txt" 2>&1 || true
sleep 6
adb shell dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity' > "$REPORT/resumed-activity.txt" || true
adb shell pidof "$PKG" > "$REPORT/pid.txt" 2>/dev/null || true
adb exec-out screencap -p > "$REPORT/screenshots/00-app-launch.png" 2>/dev/null || true
adb logcat -d -v threadtime > "$REPORT/logcat/00-cold-start.txt" 2>/dev/null || true
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true

AAPT2=$(find "${ANDROID_HOME:-/opt/android-sdk}/build-tools" -maxdepth 2 -name aapt2 -type f | sort -V | tail -1)
[ -n "$AAPT2" ] || { stage "找不到 aapt2"; exit 2; }
TEST_PKG=$("$AAPT2" dump badging "$TEST_APK" 2>/dev/null | sed -n "s/package: name='\([^']*\)'.*/\1/p" | head -1)
[ -n "$TEST_PKG" ] || { stage "无法解析 androidTest APK 包名"; exit 2; }
stage "test package=$TEST_PKG"

stage "run instrumentation"
adb logcat -c || true
set +e
adb shell am instrument -w -r \
    -e class com.RobinNotBad.BiliClient.ui.UiSmokeTest \
    -e listener com.RobinNotBad.BiliClient.ui.UiReportListener \
    "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" > "$REPORT/instrumentation.txt" 2>&1
INSTRUMENT_EXIT=$?
set -e
stage "instrumentation exit=$INSTRUMENT_EXIT"

stage "pull screenshots / reports / logcat"
adb pull "/sdcard/Android/data/$PKG/files/ui-shots" "$REPORT/screenshots" >/dev/null 2>&1 || true
adb pull "/sdcard/Android/data/$PKG/files/ui-report" "$REPORT/ui-report" >/dev/null 2>&1 || true
if [ -f "$REPORT/ui-report/TEST-ui-smoke.xml" ]; then
    mv "$REPORT/ui-report/TEST-ui-smoke.xml" "$REPORT/TEST-ui-smoke.xml"
fi
adb logcat -d -v threadtime > "$REPORT/logcat/all-threadtime.txt" 2>/dev/null || true
adb logcat -d -b crash -v threadtime > "$REPORT/logcat/crash-buffer.txt" 2>/dev/null || true
adb shell dumpsys activity activities > "$REPORT/dumpsys-activities.txt" 2>/dev/null || true
adb shell dumpsys window > "$REPORT/dumpsys-window.txt" 2>/dev/null || true

stage "visual diff"
python3 scripts/ui/visual-diff.py \
    --current "$REPORT/screenshots" \
    --baseline "ui-baselines/$UI_DEVICE_ID" \
    --out "$REPORT/diff" > "$REPORT/diff-run.txt" 2>&1 || true

stage "write status + HTML"
python3 scripts/ui/make-report.py \
    --report-dir "$REPORT" \
    --device-id "$UI_DEVICE_ID" \
    --label "$UI_LABEL" \
    --api "$UI_API" \
    --run-url "${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-}" \
    --commit "${GITHUB_SHA:-}" \
    --instrumentation-exit "$INSTRUMENT_EXIT" > "$REPORT/report-build.txt" 2>&1 || true

# 失败判定：XML 存在且无 failure 才 PASS；XML 缺失视为测试基础设施/运行器失败。
if [ ! -f "$REPORT/TEST-ui-smoke.xml" ]; then
    stage "RESULT=INFRA_FAILED（没有 TEST-ui-smoke.xml）"
    exit 4
fi
if grep -q '<failure' "$REPORT/TEST-ui-smoke.xml"; then
    stage "RESULT=FAILED（存在失败用例）"
    exit 1
fi
stage "RESULT=PASS"
exit 0
