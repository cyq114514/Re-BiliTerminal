#!/usr/bin/env bash
# 用途：在 CI runner 上准备 Android SDK（platform 33 / build-tools），不修改仓库文件。
#
# 背景：ubuntu-latest 镜像并非总是预装 compileSdk 33 对应的 platform（真实 CI 首跑踩过），
# 因此所有需要编译/单测的 job 共用本脚本，避免 workflow 里重复三大段 shell。
#
# 环境变量：ANDROID_HOME（GitHub hosted runner 预置）。找不到 sdkmanager 时只告警并依赖
# AGP 自动下载（软失败，不阻断），与既有 CI 行为一致。
#
# 退出码：0 = SDK 可用；1 = 显式安装失败/关键目录缺失。

set -u

echo "ANDROID_HOME=${ANDROID_HOME:-<未设置>}"

if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
        export ANDROID_HOME="$ANDROID_SDK_ROOT"
    else
        echo "::error::ANDROID_HOME 未设置"
        exit 1
    fi
fi

SDKMANAGER=$(find "$ANDROID_HOME" -maxdepth 5 -type f -name sdkmanager 2>/dev/null | sort -V | tail -1)
if [ -z "$SDKMANAGER" ]; then
    echo "::warning::未找到 sdkmanager，跳过显式安装与断言（依赖 AGP 自动下载 SDK 组件）"
    exit 0
fi

if [ ! -d "$ANDROID_HOME/platforms/android-33" ]; then
    echo "platform 33 缺失，使用 sdkmanager 安装（首次约 1~2 分钟）"
    yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
    "$SDKMANAGER" --install "platforms;android-33" "build-tools;33.0.3" >/dev/null
fi

if [ ! -d "$ANDROID_HOME/platforms/android-33" ]; then
    echo "::error::platforms/android-33 不可用（sdkmanager 安装失败且 runner 未预装）"
    exit 1
fi
ls "$ANDROID_HOME/build-tools" | sort -V | tail -1
echo "Android SDK 就绪：$ANDROID_HOME"