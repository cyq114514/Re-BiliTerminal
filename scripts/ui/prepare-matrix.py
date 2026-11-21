#!/usr/bin/env python3
"""根据触发事件生成 ui-smoke 的设备矩阵（GitHub Actions job output）。

PR: 圆屏手表 + 小屏手机（快速反馈）
main push: 标准手机 + 方屏手表
schedule / workflow_dispatch: 全部四种
tag push: 空矩阵（发版链不跑 UI 测试）
"""
import json
import os

DEVICES = {
    "round-watch": {
        "id": "round-watch", "label": "圆屏手表", "api": "30", "target": "android-wear",
        "arch": "x86", "profile": "wearos_small_round", "avd": "round-watch",
        "round": "1", "shape": "round",
    },
    "small-phone": {
        "id": "small-phone", "label": "小屏手机", "api": "26", "target": "default",
        "arch": "x86", "profile": "Nexus One", "avd": "small-phone",
        "round": "0", "shape": "phone-small",
    },
    "standard-phone": {
        "id": "standard-phone", "label": "标准手机", "api": "29", "target": "default",
        "arch": "x86", "profile": "pixel_2", "avd": "standard-phone",
        "round": "0", "shape": "phone-standard",
    },
    "square-watch": {
        "id": "square-watch", "label": "方屏手表", "api": "30", "target": "android-wear",
        "arch": "x86", "profile": "wearos_square", "avd": "square-watch",
        "round": "0", "shape": "square",
    },
}

EVENT = os.environ.get("EVENT_NAME", "")
REF = os.environ.get("REF_NAME", "")
INPUT = (os.environ.get("UI_DEVICES") or "all").strip().lower()

if EVENT == "push" and REF.startswith("refs/tags/"):
    selected = []
elif EVENT == "pull_request":
    selected = ["round-watch", "small-phone"]
elif EVENT == "push":
    selected = ["standard-phone", "square-watch"]
elif EVENT == "schedule":
    selected = list(DEVICES)
elif EVENT == "workflow_dispatch":
    if INPUT in ("", "all"):
        selected = list(DEVICES)
    else:
        selected = [x for x in (s.strip() for s in INPUT.split(",")) if x in DEVICES]
else:
    selected = []

matrix = {"include": [DEVICES[x] for x in selected]}

out = os.environ.get("GITHUB_OUTPUT")
line = "matrix=" + json.dumps(matrix, ensure_ascii=False, separators=(",", ":"))
has_ui = "has_ui=" + ("true" if selected else "false")
print("ui devices: " + (", ".join(selected) if selected else "(none)"))
if out:
    with open(out, "a", encoding="utf-8") as f:
        f.write(line + "\n" + has_ui + "\n")
else:
    print(line)
    print(has_ui)
