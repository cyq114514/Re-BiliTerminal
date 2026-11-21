#!/usr/bin/env python3
"""把 UI 冒烟产物汇总为 status.json / index.html / job-summary.md。"""
import argparse
import html
import json
import os
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


def esc(s):
    return html.escape("" if s is None else str(s))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--report-dir", required=True)
    ap.add_argument("--device-id", required=True)
    ap.add_argument("--label", default="")
    ap.add_argument("--api", default="")
    ap.add_argument("--run-url", default="")
    ap.add_argument("--commit", default="")
    ap.add_argument("--instrumentation-exit", type=int, default=0)
    args = ap.parse_args()
    rd = Path(args.report_dir)

    device = {}
    if (rd / "device-info.json").is_file():
        device = json.loads((rd / "device-info.json").read_text(encoding="utf-8"))
    frames = {}
    if (rd / "diff" / "summary.json").is_file():
        frames = json.loads((rd / "diff" / "summary.json").read_text(encoding="utf-8"))

    tests = []
    failures = 0
    errors = 0
    skipped = 0
    total = 0
    suite_time = 0.0
    xml_file = rd / "TEST-ui-smoke.xml"
    if xml_file.is_file():
        root = ET.parse(xml_file).getroot()
        suite = root.find("testsuite") if root.tag == "testsuites" else root
        total = int(suite.get("tests", "0"))
        failures = int(suite.get("failures", "0"))
        errors = int(suite.get("errors", "0"))
        skipped = int(suite.get("skipped", "0"))
        suite_time = float(suite.get("time", "0") or 0)
        for tc in suite.findall("testcase"):
            f = tc.find("failure")
            e = tc.find("error")
            sk = tc.find("skipped")
            tests.append({
                "classname": tc.get("classname", ""),
                "name": tc.get("name", ""),
                "time": tc.get("time", ""),
                "status": "FAIL" if (f is not None or e is not None) else ("SKIP" if sk is not None else "PASS"),
                "message": (f.get("message") if f is not None else (e.get("message") if e is not None else "")),
            })
        result = "PASS" if failures == 0 and errors == 0 else "FAIL"
    else:
        result = "INFRA_FAILED"

    screenshots = sorted(p.name for p in (rd / "screenshots").glob("*.png")) if (rd / "screenshots").is_dir() else []
    diff_pages = frames.get("pages", []) if frames else []
    diff_missing = [p["page"] for p in diff_pages if p.get("status") == "BASELINE_MISSING"]
    diff_changed = [p["page"] for p in diff_pages if p.get("status") == "OK" and (p.get("diff_ratio") or 0) > 0]

    status = {
        "device_id": args.device_id,
        "label": args.label,
        "api": args.api,
        "result": result,
        "tests": total,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "duration_seconds": suite_time,
        "instrumentation_exit": args.instrumentation_exit,
        "device": device,
        "screenshots": screenshots,
        "diff": {"baseline_available": (frames or {}).get("baseline_available", False),
                 "changed_pages": diff_changed, "baseline_missing": diff_missing},
        "run_url": args.run_url,
        "commit": args.commit,
        "generated_at": datetime.now(timezone.utc).isoformat(),
    }
    (rd / "status.json").write_text(json.dumps(status, ensure_ascii=False, indent=2), encoding="utf-8")

    img_cards = "".join(
        f'<figure><img src="screenshots/{esc(n)}" alt="{esc(n)}"><figcaption>{esc(n)}</figcaption></figure>'
        for n in screenshots
    )
    diff_html = ""
    if frames:
        if not frames.get("baseline_available"):
            diff_html = '<p class="muted">尚无截图基线；本次截图可作为首批基线候选。</p>'
        else:
            rows = "".join(
                f'<tr><td>{esc(p.get("page"))}</td><td>{esc(p.get("status"))}</td>'
                f'<td>{esc(p.get("diff_ratio"))}</td></tr>' for p in diff_pages)
            diff_html = ('<table><tr><th>页面</th><th>状态</th><th>差异比例</th></tr>'
                         + rows + '</table>')
    test_rows = "".join(
        f'<tr class="{"bad" if t["status"]=="FAIL" else ""}"><td>{esc(t["classname"])}</td>'
        f'<td>{esc(t["name"])}</td><td>{esc(t["status"])}</td><td>{esc(t["time"])}</td>'
        f'<td>{esc(t["message"])}</td></tr>' for t in tests)
    doc = f"""<!doctype html><html lang="zh"><meta charset="utf-8">
<title>UI 冒烟 · {esc(args.device_id)} · {esc(result)}</title>
<style>
body{{font:14px/1.5 system-ui,sans-serif;margin:24px;background:#111;color:#eee}}
h1,h2{{font-weight:600}} .bad{{color:#ff6b6b}} .ok{{color:#7bd88f}} .muted{{color:#aaa}}
table{{border-collapse:collapse;width:100%;margin:12px 0}} td,th{{border:1px solid #333;padding:6px;text-align:left}}
figure{{display:inline-block;margin:6px;width:220px;vertical-align:top}} img{{max-width:100%;border:1px solid #333}}
code{{background:#222;padding:2px 4px}}
</style>
<h1>UI 冒烟 · {esc(args.label)} ({esc(args.device_id)})</h1>
<p class="{'ok' if result=='PASS' else 'bad'}"><b>{esc(result)}</b> · tests={total} failures={failures} errors={errors} skipped={skipped} · {suite_time:.1f}s</p>
<p>API {esc(args.api)} · model {esc(device.get('model'))} · sdk {esc(device.get('sdk'))} · {esc(device.get('wm_size'))} / {esc(device.get('wm_density'))} · commit <code>{esc(args.commit[:7])}</code> · <a href="{esc(args.run_url)}">run</a></p>
<h2>用例</h2><table><tr><th>class</th><th>case</th><th>status</th><th>time</th><th>message</th></tr>{test_rows}</table>
<h2>截图（{len(screenshots)}）</h2>{img_cards}
<h2>视觉差异</h2>{diff_html}
<p class="muted">原始产物：<a href="instrumentation.txt">instrumentation.txt</a> · <a href="logcat/all-threadtime.txt">logcat</a> · <a href="TEST-ui-smoke.xml">JUnit XML</a></p>
</html>"""
    (rd / "index.html").write_text(doc, encoding="utf-8")

    md = [f"## UI 冒烟 · {args.label} ({args.device_id})",
          "",
          f"- 结果：**{result}**；用例 {total}，失败 {failures}，错误 {errors}，跳过 {skipped}",
          f"- 设备：API {args.api}，model {device.get('model','?')}，{device.get('wm_size','?')} @ {device.get('wm_density','?')}",
          f"- 截图：{len(screenshots)} 张；视觉差异：{len(diff_changed)} 页有像素变化，{len(diff_missing)} 页缺基线",
          f"- run：{args.run_url}"]
    if result != "PASS":
        md.append("")
        md.append("失败用例：")
        for t in tests:
            if t["status"] == "FAIL":
                md.append(f"- `{t['classname']}#{t['name']}`：{t['message']}")
    (rd / "job-summary.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"report: {result} tests={total} failures={failures} screenshots={len(screenshots)}")
    return 0 if result == "PASS" else (2 if result == "INFRA_FAILED" else 1)


if __name__ == "__main__":
    sys.exit(main())
