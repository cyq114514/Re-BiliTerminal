#!/usr/bin/env python3
"""汇总 Gradle JUnit XML 结果，输出到控制台与 GitHub Step Summary。

用法：
    python3 scripts/summarize-tests.py [results_dir]
默认 results_dir = app/build/test-results/testDebugUnitTest

退出码：
    0 = 至少执行了 1 个用例（失败也会返回 0，由 Gradle 任务本身决定 job 失败，避免重复/掩盖）
    2 = 没有找到任何测试结果（编译失败 / 环境故障 / 测试未执行）——这是 NOT RUN，必须显式失败

只依赖 Python 标准库，不联网。
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_DIR = "app/build/test-results/testDebugUnitTest"


def gh_escape(text: str) -> str:
    return (
        text.replace("%", "%25")
        .replace("\r", "%0D")
        .replace("\n", "%0A")
    )


def main() -> int:
    results_dir = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DIR
    files = sorted(glob.glob(os.path.join(results_dir, "TEST-*.xml")))

    summary_lines = []
    if not files:
        msg = (
            f"NOT RUN：未在 {results_dir} 找到 TEST-*.xml。"
            "通常意味着编译失败/环境故障/测试任务未执行；请查看 Gradle 日志。"
        )
        print(msg)
        print(f"::error title=单元测试未执行::{gh_escape(msg)}")
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as fh:
                fh.write("## JVM 单元测试：NOT RUN\n\n" + msg + "\n")
        return 2

    total = failures = errors = skipped = 0
    duration = 0.0
    classes = []
    failed_cases = []

    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            print(f"::warning::无法解析 {path}: {exc}")
            continue
        suite = root.find("testsuite") if root.tag == "testsuites" else root
        if suite is None:
            continue
        name = suite.get("name", os.path.basename(path))
        t = int(suite.get("tests", "0"))
        f = int(suite.get("failures", "0"))
        e = int(suite.get("errors", "0"))
        s = int(suite.get("skipped", "0"))
        d = float(suite.get("time", "0") or 0)
        total += t
        failures += f
        errors += e
        skipped += s
        duration += d
        classes.append((name, t, f, e, s, d))
        for case in suite.findall("testcase"):
            problem = case.find("failure")
            kind = "FAIL"
            if problem is None:
                problem = case.find("error")
                kind = "ERROR"
            if problem is None:
                continue
            case_name = f"{case.get('classname', name)}#{case.get('name', '?')}"
            message = problem.get("message") or (problem.text or "").strip().splitlines()[0:1]
            if isinstance(message, list):
                message = message[0] if message else ""
            failed_cases.append((case_name, kind, str(message)[:500]))

    status = "PASS" if failures == 0 and errors == 0 else "FAIL"
    summary_lines.append(f"## JVM 单元测试：{status}")
    summary_lines.append("")
    summary_lines.append(f"- 用例总数：**{total}**")
    summary_lines.append(f"- 失败：**{failures}** ｜ 错误：**{errors}** ｜ 跳过：**{skipped}**")
    summary_lines.append(f"- 耗时：**{duration:.1f}s**")
    summary_lines.append(f"- 结果目录：`{results_dir}`")
    summary_lines.append("")
    summary_lines.append("| 测试类 | 用例 | 失败 | 错误 | 跳过 |")
    summary_lines.append("|---|---:|---:|---:|---:|")
    for name, t, f, e, s, _ in classes:
        summary_lines.append(f"| `{name}` | {t} | {f} | {e} | {s} |")
    if failed_cases:
        summary_lines.append("")
        summary_lines.append("### 失败用例")
        for case_name, kind, message in failed_cases:
            summary_lines.append(f"- **{kind}** `{case_name}`：{message}")
            print(
                f"::error title=测试失败::{gh_escape(case_name + ' — ' + message)}"
            )

    text = "\n".join(summary_lines)
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as fh:
            fh.write(text + "\n")

    # 失败时仍返回 0：job 的失败状态由 Gradle 任务本身给出；本脚本只负责可读报告。
    return 0


if __name__ == "__main__":
    sys.exit(main())