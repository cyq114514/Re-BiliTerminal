# AGENTS.md — Re：哔哩终端（Re-BiliTerminal）

> 给所有 Coding Agent 的入口：先读本文件，再按任务读专项文档，**不要一次读完所有文档**。
> 本文件只放导航与硬规则；架构、数据流、模块细节在 `docs/`。人类开发者同样适用。

## 1. 项目是什么

- **Re：哔哩终端（BiliTerminal）**：哔哩终端（BiliClient）的**非官方**二次开发分支，轻量第三方 B 站 Android 客户端；GPL-3.0。
- 本项目**绝大部分改动由 AI 生成**（见 `readme.md` 的 Vibe Coding 声明）：注释与实现可能不完全对应，遇到矛盾以代码为准，改动取最小必要。
- 规模：468 个 Java 文件 / 92,653 行、344 个资源文件；4 个 Gradle 模块；约 110+ 个 Activity、20+ 个 Fragment。
- 版本：1.3.9 / versionCode 20261120（`app/build.gradle:32-33`）；应用包名 `com.RobinNotBad.BiliClient.re`（debug 变体再加 `.test`）。

## 2. 技术栈与构建

- Java + XML（**无 Kotlin、无 Compose**）；minSdk 19 / targetSdk 26 / compileSdk 33；AGP 8.10.0、Gradle Wrapper 8.14.3、JDK 17 构建（source/target Java 11）。
- 环境与命令：`docs/development/environment.md`、`docs/development/commands.md`。
- **本机（ARM64 Linux / PRoot）限制**：只能做 Debug 构建且需替换 aapt2；Release（R8 + 资源收缩）、签名、发布、仪器化测试只能在 GitHub Actions 完成。

## 3. 目录地图

```text
app/                        唯一业务模块（全部 UI/网络/播放/下载）
  src/main/java/com/RobinNotBad/BiliClient/
    BiliTerminal.java        Application：启动编排 + 全局静态 Context
    activity/                页面，13 个子包（按业务域）
    api/                     50 个静态 API 类（HTTP 拼接 + JSON 解析一体）
    adapter/ model/ service/ 列表适配器 / 数据模型 / 4 个 Service
    source/                  第三方番剧源子系统（规则、WebView 嗅探、本地 HLS 代理）
    ui/                      theme（主题/强调色）+ widget（自绘控件）
    util/ helper/ event/     工具类、Glide/MediaSession/SQL、EventBus 事件
  src/main/res/              布局与资源（颜色/圆角/字号 token 规则见 DESIGN.md）
  src/androidTest/           唯一测试：TLS 回移仪器化测试（需设备，会真实联网）
  libs/                      10 个预编译 .so（不可重建）
ijkplayer-java/              第三方播放器 Java 封装（原样，勿改）
DanmakuFlameMaster/          弹幕引擎（**已 fork**：6 个上游文件 + 1 个 Bili* 类被本项目改过）
brotlij/                     Brotli JNI（原样，勿改）
docs/ site/ netlify/         文档 / 官网 / Netlify Functions
```

## 4. 按任务读什么

**一级**（每次）：本文件 + `docs/README.md` + `docs/architecture/overview.md`。

**二级**（按任务，读对应一份即可）：

| 任务 | 必读 |
|---|---|
| 播放器 / 弹幕 / 媒体 | `docs/modules/player.md`、`docs/architecture/risk-map.md` |
| 番剧 / 进度 / 番剧缓存 | `docs/modules/anime.md` |
| 网络 / API / 登录态 / 反代 | `docs/modules/network.md` |
| 下载 / 缓存 / 音频 | `docs/modules/download.md` |
| 设置 / 主题 / 持久化 | `docs/modules/settings.md`；界面同时读 `DESIGN.md` |
| 构建 / 发布 / CI | `docs/development/environment.md`、`.github/workflows/ci.yml` |
| 改包结构 / 找模块归属 | `docs/architecture/module-map.md`、`dependency-map.md` |

**三级**（只在追历史/排查根因时）：`docs/更新日志.md`（2,500+ 行，按版本检索，不要通读）、`docs/audit/**`（历史审计，**不代表当前状态**）、`docs/decisions-*.md`。

## 5. 开发规则

- 先理解现有实现再改；最小必要修改，不夹带格式化/重命名/无关重构。
- 不擅自改变业务行为；不删除用途未确认的代码。
- 不引入未经评估的依赖，不升级 AGP/Gradle/JDK/SDK/compileSdk。
- **禁止擅自改动**：`applicationId`/namespace、versionCode/versionName、签名配置、`local.properties` 注入方式、Release 资产命名、`update.json` 机制、tag 触发规则。
- 第三方边界：`ijkplayer-java`、`brotlij` 原样；`DanmakuFlameMaster` 已 fork（改前查 git 历史与上游语义）；`app/libs/*.so` 不可重建，不要"顺手清理"。
- 用户数据兼容：SP key 的类型与含义、`download.db` 版本/列语义不可随意变更。
- 不得通过删除检查、测试或日志来掩盖错误。

## 6. 验证规则（未执行不得标 PASS）

| 级别 | 命令/方式 | 说明 |
|---|---|---|
| 静态 | `git diff --check` + 人工审 diff | 每次必做 |
| 仓库自检 | `./scripts/check-env.sh`、`check-repo.sh`、`project-info.sh`、`validate-ci.sh`、`check-links.sh` | 5 个只读脚本，详见 `docs/development/commands.md` §1.1 |
| 文档链接 | `./scripts/check-links.sh` | 改文档后运行；出现失效链接即失败 |
| 编译 | `./gradlew :app:assembleDebug` | 本机可行（ARM64 需 aapt2 override） |
| Release 等价 | CI tag 构建（R8 + `shrinkResources` + 资源冒烟） | 本地**无法**等价验证 |
| 仪器化 | `./gradlew -PemuArm64 connectedDebugAndroidTest` | 需设备/模拟器；CI 当前不跑 |
| 真机回归 | 按模块文档的回归清单 | 第二阶段；本机 NOT RUN |

未执行写 `NOT RUN`；无法执行写 `BLOCKED` / `PARTIAL` 并说明原因（如无 gh/无设备/无凭据）。

## 7. Git 规则

- 改动前先看 `git status` 与当前分支；不覆盖用户未提交的修改。
- 工作分支：`feature/*`、`fix/*`、`chore/*`；`main` 保持稳定。
- 提交沿用 Conventional Commits，并在正文写清**根因与修法**（本仓库惯例）。
- 提交前看完整 diff；不 `git add .`（工作区可能有被 ignore 的构建产物）。
- **不擅自** push 正式分支、合并 PR、创建 tag/Release；远程推送与发版需要明确授权。

## 8. 其他 Agent 工具

`.codebuddy/agents/code explorer.md` 是 **CodeBuddy 工具的私有 agent 定义**，不属于本项目规范；若你在其中看到规则，以本文件与 `docs/` 为准。该文件已归一为单段 frontmatter，请勿在其中追加项目规则。

## 9. 文档维护

架构、模块边界、构建配置或重要业务行为变化时，同步更新对应文档；普通 Bug 修复不必改架构文档；不生成重复开发日志；推测不得写成事实。细则见 `docs/development/workflow.md`。文档总索引：`docs/README.md`。