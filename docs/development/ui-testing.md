# 多屏幕 UI 冒烟（ui-smoke）

> 什么时候读：改 UI/布局/资源、看云端 UI 失败、补截图基线、评估「哪些页面还没有自动化」时。
> 相关：[ci.md](ci.md)（job 与触发）、[testing.md](testing.md)（JVM 单测边界）、[commands.md](commands.md)（命令）、[workflow.md](workflow.md)（Agent 流程）。

## 1. 目标与边界

云端在真实 Android Emulator 上验证：页面能启动、根视图可见且不越界、页面重建不崩、关键交互/滚动可用，并保存截图、Logcat、JUnit XML/HTML，供 Agent 定位。

**不做**：不改业务 UI、不做像素级强制门禁（第一阶段只报告差异）、不覆盖 native 播放/Service/真机传感器。IJKPlayer/Surface/Service/真实播放链路仍按真机回归清单执行。

## 2. 设备矩阵（真实 AVD 配置）

| id | 设备 | API | target | arch | AVD profile | 实测屏幕 | 备注 |
|---|---|---|---|---|---|---|---|
| `round-watch` | 圆屏手表 | 30 | `android-wear` | `x86` | `wearos_small_round` | 384×384 @320dpi，`ro.build.characteristics=watch` | 系统镜像 `system-images;android-30;android-wear;x86`；真圆屏 |
| `small-phone` | 小屏手机 | 26 | `default` | `x86` | `Nexus One` | 480×800 @240dpi | 代表性小屏 |
| `standard-phone` | 标准手机 | 30 | `default` | `x86` | `pixel_2` | 待 CI 实测记录 | 主分支标准机 |
| `square-watch` | 方屏手表 | 30 | `android-wear` | `x86` | `wearos_square` | 待 CI 实测记录 | 与圆屏同一 Wear 镜像，不同 profile |

选择 `x86`（而非 x86_64）的原因：debug 包只带 `armeabi-v7a`/`x86` 原生库（`app/libs` 无 x86_64，且不可重建）。不升级 compileSdk/AGP/Gradle 来适配新系统镜像。

## 3. 触发矩阵

| 事件 | 设备 | 目的 |
|---|---|---|
| PR | `round-watch` + `small-phone` | 快速反馈圆屏裁切与小屏挤压 |
| push `main` | `standard-phone` + `square-watch` | 主分支常规覆盖 |
| schedule（周日 18:00 UTC） | 全部 4 种 | 每周完整矩阵 |
| `workflow_dispatch`（`ui_devices`） | all / 单个 | 手动复现、发版前抽查 |

`ui-prepare` 生成矩阵；`ui-smoke` 每个 matrix 条目独占一个 runner + 一个 AVD，不与其他 job 争抢。tag push 不跑 UI 矩阵，`release` 的 `needs` 不含 `ui-smoke`，UI 失败不阻断正式发版（稳定后再评估）。

## 4. Job 流程

```text
build job: assembleDebug + assembleDebugAndroidTest → artifact Re-BiliTerminal-debug
  ↓
ui-smoke（每设备）:
  download APK artifact
  Enable KVM
  android-emulator-runner 创建/启动 AVD（profile/API/target/arch）
  scripts/ui/run-on-device.sh:
    install app + androidTest APK
    pm clear + 注入隔离 prefs（setup=true, telemetry=false, 教程已读, last_version）
    adb wake / stayon / 关动画 / 锁定方向
    冷启动 App（LauncherDefault）→ 截图 + Logcat
    am instrument -e class UiSmokeTest -e listener UiReportListener
      → 设备侧写 TEST-ui-smoke.xml、截图 ui-shots/
    adb pull 截图/XML；抓全量 Logcat、crash buffer、dumpsys
    scripts/ui/visual-diff.py（基线存在时）
    scripts/ui/make-report.py（status.json / index.html / job-summary.md）
  upload artifact ui-report-<device>-api<API>-<run_number>（始终，14 天）
```

## 5. 覆盖页面与断言

`app/src/androidTest/java/com/RobinNotBad/BiliClient/ui/UiSmokeTest.java`（10 个用例 + 旋转）：

| 用例 | 页面 | 断言 |
|---|---|---|
| `menuPage` | `MenuActivity` | 启动、`android.R.id.content` 可见且不越界、重建 |
| `homeRecommendPage` | `RecommendActivity` | 同上 |
| `bangumiPage` | `BangumiActivity` | 同上 |
| `bgmDetailPage` | `BgmSubjectActivity`（bgmId=1） | 同上 |
| `videoDetailPage` | `VideoInfoActivity`（aid=2） | 同上 |
| `searchPage` | `SearchActivity` | 启动/可见；**不重建**（搜索页重建后无法回到 RESUMED，原因记录在测试代码） |
| `downloadsPage` | `DownloadListActivity` | 启动、可见、重建 |
| `settingsPage` | `SettingMainActivity` | 启动、可见、重建（asyncInflate 最多轮询 8s） |
| `audioQueuePage` | `AudioQueueActivity` | 空队列也能启动、可见、重建 |
| `favoriteFolderPage` | `FavoriteFolderListActivity` | 游客未登录也能启动、空态可见、重建 |
| `rotationSmoke` | 设置页横屏 | **仅手机设备**（Wear skip）；验证横竖屏切换 |

辅助判断：`android.R.id.content` 作为根视图（避免页面隐藏/替换 topbar 的误报），`pageName` 存在时校验可见，失败时 dump window hierarchy 与当前包名。单测有 180s 超时；instrumentation 总超时 900s。

### 暂不自动化（NOT COVERED）

- **视频播放页 `PlayerActivity`**：需要真实视频 URL/网络/ijkplayer 解码，模拟器上 native 行为不稳定；只做真机回归。
- **音乐播放页 `AudioPlayerActivity`**：依赖 `AudioPlayerService` 正在播放；无播放时页面会主动 finish，启动服务又会真实联网播放音频，不在冒烟中强行构造。
- 登录后的收藏夹内容/账号切换/写操作：禁止真实账号，游客空态只验证布局。
- 真实番剧/视频数据加载成功态：依赖线上接口，结果不稳定；只保证页面骨架与错误/空态不崩。
- IJKPlayer/Surface/Service 生命周期、通知、后台音频：真机回归。

## 6. 截图与视觉差异

- 每个页面截图：`<page>.png` 与 `<page>-recreated.png`；冷启动：`00-app-launch.png`；失败页：`<page>-FAILED.png`（仍上传）。
- 截图目录：App 外部目录 `Android/data/<pkg>/files/ui-shots/`，host 脚本拉取并平铺到 artifact 的 `screenshots/`。
- 基线：`ui-baselines/<device-id>/<page>.png`（手动从首次 PASS artifact 入库）。`scripts/ui/visual-diff.py` 用 Pillow 比较，忽略顶部 6%/底部 4%（状态栏/导航区），输出差异比例、红色差异图 `diff/<page>.png` 与 `diff/summary.json`。
- 第一阶段差异**只报告不失败**；动态内容（推荐流、头像、时间）允许差异，基线和阈值需要人工判断后再考虑门禁。
- 基线缺失时报告 `BASELINE_MISSING`，不会伪造 PASS；`ui-baselines/README.md` 记录了入库约定。

## 7. 报告与 Artifact

Artifact 名：`ui-report-<device-id>-api<API>-<run_number>`，保留 14 天；内容包括：

- `TEST-ui-smoke.xml`（JUnit XML）、`index.html`（含截图与失败消息）、`job-summary.md`（GitHub Step Summary）
- `screenshots/`（全部页面 + 失败页）、`diff/`（差异报告）
- `logcat/all-threadtime.txt`、`logcat/crash-buffer.txt`、`logcat/00-cold-start.txt`
- `device-info.json/txt`、`dumpsys-*`、`instrumentation.txt`、`stages.txt`、`status.json`

失败分类：`status.json.result = FAIL`（有失败用例）/**`INFRA_FAILED`**（无 XML、模拟器未启动、安装失败、instrumentation 超时）。两者都上传现有产物；后者看 `stages.txt` 与 Actions 日志。

## 8. 安全与隔离（硬要求）

- 不登录、不使用真实账号/Cookie/API Key；不做点赞/收藏/投币/删除等写操作。
- 测试启动前注入 prefs：`crash_report_enable=false`、`usage_stats_enable=false`、`crash_report_asked=true`，避免启动补发向生产 Supabase 写数据；测试内再次兜底设置。
- 只使用 B 站游客只读接口；接口失败/空数据不阻断布局冒烟。
- 不修改网络安全配置，不放宽 `UrlGuard`/`RuleLimits`/`SafeRegex`/HLS 代理校验。
- 日志中不输出 secret；release secrets 只在 tag release job 注入，UI job 不接触。

## 9. 本地执行与 GitHub 查看

本机（ARM64/PRoot）**无法启动 x86 模拟器**，UI 冒烟本地状态为 **NOT RUN**，只能云端执行：

```bash
# 手动触发（需已登录 gh；设备可选 all/round-watch/small-phone/standard-phone/square-watch）
gh workflow run ci.yml -f ui_devices=all

# 查看最近一次 UI run（示例）
gh run list --workflow=ci.yml --limit 10
gh run view <run-id>
```

GitHub UI：Actions → CI → 对应 run → Job `UI 冒烟（…）` → Job Summary 与 Artifacts。

## 10. Agent 分析闭环

1. 从 artifact 下载 `index.html` / `status.json` / `screenshots/` / `logcat/crash-buffer.txt`；
2. 失败用例名 → `UiSmokeTest` 对应页面与断言；`stages.txt` 判断是否基础设施失败；
3. 截图差异图 `diff/` 指向具体页面；`device-info.json` 确认设备/API/密度；
4. 修复后只重跑相关设备（workflow_dispatch `ui_devices=round-watch`）；
5. 不得用「跳过/放宽断言/删测试」让 UI job 变绿；基础设施失败与 UI 失败分开记录。
