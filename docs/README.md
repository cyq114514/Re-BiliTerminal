# 文档地图（docs 索引）

> 用途：仓库内所有文档的入口索引，按「什么时候读」分三级，避免每次全量扫描。
> 新增文档请同时在本页登记。
> 根目录入口：[AGENTS.md](../AGENTS.md)（Agent/开发总规范）、[readme.md](../readme.md)（项目介绍/下载/构建）、[DESIGN.md](../DESIGN.md)（界面规范）。

## 一级 · 每次先读

| 文档 | 什么时候读 |
| --- | --- |
| [AGENTS.md](../AGENTS.md) | 任何 Agent/开发工作开始前：规则、验证要求、Git 纪律、红线 |
| [architecture/overview.md](architecture/overview.md) | 第一次接触项目：分层实况、启动链、全局状态、线程模型 |
| [readme.md](../readme.md) | 用户视角：它是什么、怎么装、怎么构建、已知问题 |
| [DESIGN.md](../DESIGN.md) | 任何界面改动前：颜色/字号/圆角/间距/顶栏按钮的强制规范 |
| [更新日志.md](更新日志.md) | 查「某版本改了什么、为什么」；按版本检索，**不要从头通读**（2,500+ 行） |

## 二级 · 按任务查

### 架构

| 文档 | 什么时候读 |
| --- | --- |
| [architecture/module-map.md](architecture/module-map.md) | 找模块归属、判断文件属于本项目还是第三方 |
| [architecture/dependency-map.md](architecture/dependency-map.md) | 查依赖版本、`app/libs` 二进制、构建仓库/镜像、能不能升级 |
| [architecture/data-flow.md](architecture/data-flow.md) | 追播放/番剧/下载/崩溃上报/更新/登录态的数据流与线程切换 |
| [architecture/risk-map.md](architecture/risk-map.md) | 改动前评估「会连带崩哪里」；高风险机制与全局状态清单 |

### 模块专项（改对应模块前必读）

| 文档 | 什么时候读 |
| --- | --- |
| [modules/player.md](modules/player.md) | 播放器、弹幕、字幕、直播、后台播放、进度上报 |
| [modules/anime.md](modules/anime.md) | 番剧/追番/索引、PGC 进度、Bangumi 反代、番剧缓存 |
| [modules/network.md](modules/network.md) | 网络封装、API、Cookie/登录态、WBI、TLS、错误处理 |
| [modules/download.md](modules/download.md) | 下载/缓存、`download.db`、DASH 合成、音频缓存代理 |
| [modules/settings.md](modules/settings.md) | 设置项、设置搜索、主题/强调色、SP 持久化 |

### 开发与协作

| 文档 | 什么时候读 |
| --- | --- |
| [development/environment.md](development/environment.md) | 首次构建、换环境、构建报错、ARM64/PRoot 限制 |
| [development/commands.md](development/commands.md) | 需要真实可用的构建/验证/产物核验命令时 |
| [development/workflow.md](development/workflow.md) | 标准任务七步、验证矩阵、Diff 自查、多 Agent 协作 |
| [development/agent-handoff.md](development/agent-handoff.md) | 任务中断/换模型/接力时写与读交接 |
| [templates/task-template.md](templates/task-template.md) | 复杂任务开工前记录目标/范围/验收 |
| [templates/handoff-template.md](templates/handoff-template.md) | 写交接时直接套用 |

### 决策与历史记录

| 文档 | 什么时候读 |
| --- | --- |
| [decisions-theme-color.md](decisions-theme-color.md) | 改主题/强调色/`ThemeManager`、`ThemeApplier` 前（代码里有引用） |
| [decisions-tls-backport.md](decisions-tls-backport.md) | 改 `CrashUploader` / 老设备 TLS / Conscrypt 前（代码里有引用） |
| [变更记录-*.md](变更记录-UI视觉系统升级-20261114.md) | 追某批改动（2026-11-14 共 8 份）的根因、取舍与回滚方式 |
| [需求-私信反馈整理2-20261114.md](需求-私信反馈整理2-20261114.md) | 用户私信反馈的原文、研究结论与实施/验证记录 |
| [测试流程-第三方播放源-20261106.md](测试流程-第三方播放源-20261106.md) | 回归第三方播放源（线路/剧集/弹幕）前 |
| [测试流程-功能移植-20261105.md](测试流程-功能移植-20261105.md) | 回归功能移植批次前 |
| [功能移植评估-Plan.md](功能移植评估-Plan.md) | 评估从上游/第三方移植功能时 |
| [交接-收藏夹音频播放.md](交接-收藏夹音频播放.md) | 历史交接范例（含当时构建/真机做法；本机路径为历史记录） |
| [Netlify部署教程.md](Netlify部署教程.md) | 改官网/部署前（注意：教程未覆盖 `netlify/functions/` 反代函数） |
| [crash-console.html](crash-console.html) | 崩溃上报控制台（`site/console.html` 为手工同步副本，两者可能漂移） |

> **2026-11-14 批次（共 8 份变更记录）**：[UI 一致性与主题色收口](变更记录-UI一致性与主题色收口-20261114.md)、[UI 视觉系统升级](变更记录-UI视觉系统升级-20261114.md)、[开屏跟随配色](变更记录-开屏跟随配色-20261114.md)、[性能审计修复](变更记录-性能审计修复-20261114.md)、[批量删除与私信按钮](变更记录-批量删除与私信按钮-20261114.md)、[搜索页头部防抖](变更记录-搜索页头部防抖-20261114.md)、[番剧缓存](变更记录-番剧缓存-20261114.md)、[缓存听歌与网络错误](变更记录-缓存听歌与网络错误-20261114.md)。


## 三级 · 历史审计（只作史料，不代表当前状态）

| 位置 | 内容 |
| --- | --- |
| [audit/](audit/) | 21 份一次性审计：`ui-audit-20261105/`（12 份）、`ui-adaptation-audit-20261107/`（2 份）、番剧/第三方源/弹幕/收藏夹/主题等专项 |
| [audit/piliplus-home-recommendation-audit.md](audit/piliplus-home-recommendation-audit.md) | 首页推荐改造的 79KB 详细审计 |
| [screens/](screens/) | 39 张历史截图（约 24.7MB）。**仅风格参考，不能当当前 UI 的证据**（最新审计自述为「历史遗留」） |

## 阅读注意

- **不要**把 `audit/` 里的结论直接当现状：它们对应的是当时的代码，后续批次已多次改动。
- 文档中的 `path:line` 以写作时的分支为准；代码变动后行号会漂移，优先用方法名/常量名（`grep -n`）重新定位。
- `audit/第三方播放源-移植方案-20261106.md` 引用的 `docs/optional-features.md`、`util/OptionalFeatures.kt` 是**上游私有仓库**的文件，本仓库没有，属上游事实，不是本仓库缺陷。
- 站点（`site/`）与文档（`docs/`）存在同源内容，当前**靠手工同步**（如崩溃控制台、更新日志快照），改动时两边都要看。
- 版本事实以 `app/build.gradle`（versionName/versionCode）与 `git tag` 为准；`site/assets/site.js` 里的兜底版本号只是离线预览用的旧快照。