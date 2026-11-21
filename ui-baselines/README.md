# UI 截图基线

云端 UI 冒烟（`ui-smoke`）会把每种设备的页面截图上传为 artifact。首次验证通过后，可从 artifact 中挑选
`ui-report-<device>/screenshots/*.png` 作为基线，按以下结构入库：

```text
ui-baselines/
  round-watch/       # 设备 id，与矩阵 matrix.id 一致
    home.png
    bangumi.png
    ...
  small-phone/
  standard-phone/
  square-watch/
```

约定：

- 文件名必须与测试截图同名（含 `-recreated` 的重建后截图可自行取舍）。
- 基线只允许来自**同设备、同 AVD 配置**的验证截图；跨设备/跨 API 不能复用。
- 动态区域（时间、头像、推荐流内容、网络错误文案）造成的差异只记录、不默认阻断 CI；
  `scripts/ui/visual-diff.py` 会输出 `diff/summary.json` 与红色差异图供 Agent 分析。
- 基线变更需要人工确认（或后续由 UI 测试 Agent 在审查后提交），不要为了让差异消失而随意覆盖。
