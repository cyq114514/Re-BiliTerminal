#!/usr/bin/env python3
"""截图视觉差异（第一阶段：只报告，不让 CI 失败）。

基线目录：ui-baselines/<device-id>/<page>.png（首次运行不存在 → 标记 BASELINE_MISSING）。
差异图：<out>/<page>.png，红色标出超过容差的像素。
依赖 Pillow；未安装时输出 SKIP_NO_PILLOW，不阻断测试。
"""
import argparse
import json
import os
import sys
from pathlib import Path

try:
    from PIL import Image, ImageChops
    HAVE_PIL = True
except Exception:
    HAVE_PIL = False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--current", required=True)
    ap.add_argument("--baseline", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tolerance", type=int, default=12, help="单通道差值阈值（0-255）")
    ap.add_argument("--mask-top", type=float, default=0.06, help="忽略顶部比例（状态栏时钟）")
    ap.add_argument("--mask-bottom", type=float, default=0.04, help="忽略底部比例")
    args = ap.parse_args()

    cur_dir = Path(args.current)
    base_dir = Path(args.baseline)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    summary = {"baseline_dir": str(base_dir), "pillow": HAVE_PIL, "pages": []}
    if not HAVE_PIL:
        summary["status"] = "SKIP_NO_PILLOW"
        (out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print("visual-diff: Pillow 未安装，跳过像素比较")
        return 0

    if not base_dir.is_dir():
        summary["baseline_available"] = False
        for png in sorted(cur_dir.glob("*.png")):
            summary["pages"].append({"page": png.stem, "status": "BASELINE_MISSING", "diff_ratio": None})
    else:
        summary["baseline_available"] = True
        for png in sorted(cur_dir.glob("*.png")):
            entry = {"page": png.stem, "status": "OK", "diff_ratio": 0.0}
            base = base_dir / png.name
            if not base.is_file():
                entry["status"] = "BASELINE_MISSING"
                summary["pages"].append(entry)
                continue
            try:
                cur = Image.open(png).convert("RGB")
                old = Image.open(base).convert("RGB")
                if cur.size != old.size:
                    entry.update(status="SIZE_MISMATCH", current_size=cur.size, baseline_size=old.size)
                    summary["pages"].append(entry)
                    continue
                w, h = cur.size
                top = int(h * args.mask_top)
                bottom = h - int(h * args.mask_bottom)
                diff = ImageChops.difference(cur, old).convert("L")
                mask = diff.point(lambda p: 255 if p > args.tolerance else 0)
                if top > 0 or bottom < h:
                    # 在 mask 上裁掉顶部/底部：只统计中间区域；差异图仍保留完整画面
                    from PIL import ImageDraw
                    draw = ImageDraw.Draw(mask)
                    draw.rectangle([0, 0, w, top], fill=0)
                    draw.rectangle([0, bottom, w, h], fill=0)
                histogram = mask.histogram()
                changed = histogram[255] if len(histogram) > 255 else 0
                total = max(1, w * h)
                entry["diff_ratio"] = round(changed / total, 6)
                entry["changed_pixels"] = changed
                overlay = cur.copy()
                red = Image.new("RGB", cur.size, (255, 0, 0))
                overlay.paste(red, mask=mask)
                overlay.save(out_dir / png.name)
            except Exception as e:  # 单张坏图不应中断整个报告
                entry.update(status="ERROR", error=repr(e))
            summary["pages"].append(entry)
    (out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    changed = [p for p in summary["pages"] if p["status"] == "OK" and p.get("diff_ratio", 0) > 0]
    print(f"visual-diff: {len(summary['pages'])} pages, {len(changed)} with pixel changes"
          + ("" if summary.get("baseline_available") else "（基线缺失）"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
