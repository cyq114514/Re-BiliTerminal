# 云端 CI 与自动化测试体系（ci）

> 什么时候读：改 `.github/workflows/`、加/删测试、判断一次 CI 失败属于哪类问题、或需要向用户交付测试结果时。
> 相关文档：[testing.md](testing.md)（测试策略/覆盖率/未覆盖风险）、[commands.md](commands.md)（本地命令）、[environment.md](environment.md)（工具链版本）。

## 1. 这套 CI 解决什么问题

改造前（main = `cd3f5e4` 时期）只有一条 `build` 链：checkout → JDK 17 → 准备 SDK → `assembleDebug` → 上传 APK。
tag 推送时同一 workflow 再跑 `release`。它保证了「能编译、能发版」，但没有：

- 仓库规范/文档链接/测试不变量检查；
- 任何 JVM 单元测试（仓库没有 `app/src/test/`）；
- 测试报告与失败用例定位；
- 编译失败与测试失败的清晰分离；
- 对「Audio Focus 设计」「第三方源安全上限」这类业务红线的自动回归保护。

本次增量为 `checks` 与 `unit-test` 两个 job；`build` 不变，`release` 保留原发布动作并新增 P0 门禁。

## 2. Workflow 结构（`.github/workflows/ci.yml`）

```text
checks     规范与环境检查        只读脚本，不编译
build      Debug 编译            产物 app-debug.apk
unit-test  JVM 单元测试           JUnit XML + HTML 报告
ui-prepare 生成 UI 设备矩阵        PR 2 台 / main 2 台 / 定时或手动 4 台
ui-smoke   多屏幕 UI 冒烟          AVD + 安装 APK + UiSmokeTest + 截图/日志/报告
release    仅 tag v*              原有发布链（签名/资源冒烟/Release/update.json）
```

| Job | 触发 | 超时 | 关键动作 | 失败意味着 |
|---|---|---|---|---|
| `checks` | 全部触发 | 15m | `check-env.sh`、`check-repo.sh`、`check-links.sh`、`validate-ci.sh`、`check-tests.sh` | 规范/链接/CI 配置/测试不变量被破坏 |
| `build` | 全部触发 | 30m | `./gradlew :app:assembleDebug --stacktrace` | 编译失败（环境或代码） |
| `unit-test` | 全部触发 | 25m | `./gradlew :app:testDebugUnitTest --stacktrace`；汇总 XML；上传报告 | 测试失败（真实代码缺陷或测试代码缺陷） |
| `ui-prepare` | 非 tag | 5m | 按事件生成 AVD 矩阵（PR 圆屏+小屏；main 标准+方屏；schedule/dispatch 全部） | 矩阵脚本/事件分支错误 |
| `ui-smoke` | 矩阵非空 | 40m/设备 | 独占 runner+AVD；安装 build artifact；执行 `UiSmokeTest`；上传截图/Logcat/XML/HTML/差异 | UI 失败或模拟器基础设施失败（报告区分） |
| `release` | 仅 `refs/tags/v*` | 45m | 前置校验（tag/versionCode/secrets）→ 签名注入 → `assembleRelease` → 签名+versionCode 校验 → 资源冒烟 → GitHub Release → `update.json` | 发布链失败；测试/规范失败会阻止发布 |

`build` 与 `unit-test` **并行**、互不 `needs`：编译失败与测试失败在 Actions 页面上是两条独立结果，
不会因为其中一个失败而看不到另一个的真实状态。`release` 现为 `needs: [build, checks, unit-test]`：任一日常 job 失败，release 不会执行。

### 2.1 触发面

- `push` → `main`：完整日常 CI。
- `push` → tag `v*`：日常 CI + 发布链。
- `pull_request` → `main`：完整日常 CI；`docs/**`、`**.md`、`site/**` 纯文档变更跳过（避免无意义的 APK 构建）。
- `schedule`（周日 18:00 UTC）：完整 4 设备 UI 矩阵；独立 concurrency group，不被 main push 取消。
- `workflow_dispatch`：手动触发；`ui_devices=all/round-watch/small-phone/standard-phone/square-watch` 可只跑指定设备；选 tag ref 时仍会走发布链（原有行为）。

### 2.2 并发、缓存、权限

- `concurrency.group = ci-${{ github.ref }}`：同一 ref 连推时取消旧任务；**tag 发布不取消**（`cancel-in-progress` 对 tag 为 false），避免两条发布链互相覆盖。
- `actions/setup-java@v4` 的 `cache: gradle` 复用 `~/.gradle/caches`；SDK 由 runner 预装 + `scripts/ci-prepare-sdk.sh` 补齐 platform 33。
- 顶层 `permissions: contents: read`；只有 tag-only 的 `release` job 单独申请 `contents: write`。
- 普通 PR/main push **不注入任何 secrets**；release secrets 只出现在 `release` job 的 `env` 中。

### 2.3 产物与日志

| 产物/位置 | 内容 | 保留 |
|---|---|---|
| Actions Job Summary | 测试总数/失败数/耗时/失败用例名与消息 | 与 run 同生命周期 |
| artifact `unit-test-results` | `app/build/test-results/testDebugUnitTest/`（XML）+ `app/build/reports/tests/testDebugUnitTest/`（HTML） | 14 天 |
| artifact `Re-BiliTerminal-debug` | Debug APK | 7 天 |
| Job 日志 | Gradle `--stacktrace` 输出；失败测试的完整堆栈 | 与 run 同生命周期 |

失败定位顺序：先看 Job Summary（测试类/用例/消息）→ 再下载 `unit-test-results` 的 HTML/XML → 最后看 Gradle 日志。
编译失败没有 XML，`scripts/summarize-tests.py` 会明确打印 **NOT RUN** 并以退出码 2 结束，不会把「没跑」误报成 PASS。

## 3. 状态标记与失败分类

统一使用以下状态（报告、交接、Issue 都按此写）：

| 状态 | 含义 |
|---|---|
| `PASS` | 实际执行且通过 |
| `FAIL` | 实际执行但失败 |
| `PARTIAL` | 部分执行/部分覆盖 |
| `BLOCKED` | 受环境/权限/依赖限制无法执行 |
| `NOT RUN` | 没有执行（**不得写成 PASS**） |

一次 CI 失败先归类，再修：

| 现象 | 类别 | 处理 |
|---|---|---|
| `checks` 失败（脚本输出 `[FAIL]`） | 仓库规范/CI 配置/测试不变量 | 按脚本定位修文件；不要改脚本让它变绿 |
| `build` 在 `compileDebugJavaWithJavac`/资源阶段失败 | 代码编译/资源错误 | 本地 `:app:compileDebugJavaWithJavac` + `assembleDebug` 复现 |
| `build` 在依赖解析/SDK 阶段失败 | CI 环境问题 | 看是否 `GITHUB_ACTIONS` 镜像/网络问题；不要删 `settings.gradle` 的镜像分支 |
| `unit-test` 有 XML 与 failure | 代码缺陷或测试代码缺陷 | 看失败用例名与断言；先判断预期行为是否被改坏 |
| `unit-test` 无 XML（脚本报 NOT RUN） | 编译/环境问题 | 修编译；不可能用「没有测试结果」当 PASS |
| 同一测试偶发失败 | 测试不稳定 | 找出共享状态/时间/随机/线程依赖；不允许用 `@Ignore` 或放宽断言掩盖 |

### 3.1 不允许的行为

- 用 `continue-on-error`、`|| true`、删除/跳过测试让门禁变绿；
- 把未执行的测试写成 PASS；
- 在测试里访问真实 B 站/Supabase、真实账号、私人 Cookie、真实第三方源；
- 为了让测试通过而放宽 `UrlGuard`/`RuleLimits`/`SafeRegex`/HLS 代理的安全校验；
- 给 `AudioPlayerService` 加 Audio Focus（会破坏「收藏夹音频与视频并行」的设计）。

`scripts/check-tests.sh` 已把其中可自动检查的部分变成 `checks` job 的硬失败项。

## 4. 多屏幕 UI 冒烟（已接入）

`ui-smoke` 已接入云端 AVD：圆屏手表 / 小屏手机 / 标准手机 / 方屏手表，真实运行
`UiSmokeTest` 并收集截图、Logcat、JUnit XML/HTML 与视觉差异。设备配置、触发矩阵、
覆盖页面、报告结构和安全隔离详见 [ui-testing.md](ui-testing.md)。

- PR 只跑圆屏+小屏，main 跑标准+方屏，schedule/workflow_dispatch 跑全部；
- `ui-smoke` 不在 `release.needs` 中，UI 失败不阻断正式发版；
- 现有 `TlsBackportInstrumentedTest` 仍**不纳入** UI 冒烟（会真实联网并写线上 Supabase）。

## 5. 发布链（P0 门禁）

- 只有 tag `v*` 会进入 `release` job；普通 PR/main push 永远拿不到写权限与 secrets。
- `release` 依赖 `[build, checks, unit-test]`：任一日常 job 失败，发布不执行。
- 发版前置校验 `scripts/check-release.sh`（任一 FAIL 即中止）：
  - tag 必须严格等于 `v<versionName>`（历史 `-fix`/`fix` tag 白名单放行，base 版本仍需一致）；
  - 新 tag 的 `versionCode` 必须大于当前最新 Release；同 tag 补发跳过递增检查；
  - 签名 4 个 secrets 必须齐全，正式 tag **禁止 unsigned 发布**。
- 构建后额外硬校验：`apksigner verify` 必须通过，且 APK `versionCode` 与 `build.gradle` 一致。
- 7 个 secrets（签名 4 键 + `BGM_PROXY_AUTH` + `DANDAN_APP_ID/SECRET`）只在 release job 注入；
  debug/unit-test job 在任何凭据写入之前完成。
- `versionCode` 优先从已构建 APK 的 `aapt2 dump badging` 读取；Release 资源冒烟（教程插图 ≥6）保留。
- `update.json` 生成/上传逻辑未改；签名缺失不再是“降级发布”，而是直接失败。
- **不要**在普通测试 job 里执行发布动作，也不要把 release secrets 提升到 workflow 级 `env`。

> 关于 `BuildConfig` 里的静态凭据：`DANDAN_APP_ID`/`DANDAN_APP_SECRET`/`BGM_PROXY_AUTH` 会打进 APK，
> 对 APK 用户**不是秘密**；本次没有更换生产凭据，也没有改注入方式。CI 只保证「普通 PR 不接触 secrets、日志不打印明文」。

## 6. 维护手册

### 改 workflow 后

```bash
./scripts/validate-ci.sh     # 静态检查 permissions/concurrency/timeout/缓存/retention/tag 收口
./scripts/check-tests.sh     # 测试不变量：CI 是否真的跑测试、是否 continue-on-error、音频焦点红线
```

### 加/改测试后

1. 本地跑对应测试（见 [testing.md](testing.md) §2）；
2. 跑 `./scripts/check-tests.sh`；
3. 确认 `:app:testDebugUnitTest` 全绿，XML 中有新增用例；
4. 推送后**必须等真实 CI 结果**再声明通过（Agent 不得替代 CI 结果，见根目录 `AGENTS.md` §6）。

### 新增 Job 的红线

- 任何新 job 必须有 `timeout-minutes`；
- 不得在 workflow 级申请 `contents: write`；
- 需要 secrets 的 job 必须用 tag/环境保护条件收口；
- 第三方 action 目前使用 major tag（`validate-ci.sh` 有 WARN）。后续可逐步 pin 到 commit SHA，但必须一次只改一个并验证 CI。

## 7. 当前局限

- **不跑 lint**：仓库未配置 `lint {}`，AGP 默认 lint 对 minSdk 19 历史代码噪音大；未纳入门禁（评估记录见 `testing.md` §6）。
- **不跑 release 编译**（日常）：Release（R8 + `shrinkResources` + 签名）仍只在 tag 发布时验证，本地无法等价复现。
- **UI 冒烟只跑 JVM/模拟器可覆盖的页面骨架**：IJKPlayer/Surface/Service/真实播放链路仍靠真机回归（见 §4 与 `ui-testing.md`）。
- **Docker/自托管 runner**：未使用；全部 job 使用 GitHub 托管 `ubuntu-latest`，不要求用户电脑/手机在线。
- **action pin**：10+ 个 action（含 UI matrix）仍是 major tag（validate-ci WARN），供应链加固留作后续小步改进。

## 8. 本次交付验证记录（2026-11-21）

> 只记录实际执行过的命令与输出；未执行项明确写 NOT RUN。

| 项 | 状态 | 证据 |
|---|---|---|
| 本地 JVM 单元测试（全部） | **PASS** | `./gradlew clean :app:testDebugUnitTest :app:assembleDebug -Pandroid.aapt2FromMavenOverride=...` → `BUILD SUCCESSFUL`；XML 汇总 **211 tests / 0 failures / 0 errors / 0 skipped**（19 个测试类，含基础设施冒烟） |
| 本地 Debug 编译 | **PASS** | 同一次 Gradle 调用产出 `app/build/outputs/apk/debug/app-debug.apk`（约 22.0 MB，构建时间 2026-11-21 03:03（本地）） |
| 仓库自检脚本 | **PASS** | `check-env`（FAIL 0 / WARN 1：本机 aapt2 override 提示）、`check-repo`（FAIL 0 / WARN 2：工作区未提交、tag 版本提示）、`check-links`（FAIL 0）、`validate-ci`（FAIL 0 / WARN 1：action 未 pin SHA）、`check-tests`（FAIL 0 / WARN 0） |
| GitHub Actions 真实运行 | **PASS** | 最终交付提交 [run 44842783655](https://github.com/cyq114514/Re-BiliTerminal/actions/runs/44842783655)（`main` @ `d706c40`）：`规范与环境检查` / `编译自检（Debug）` / `JVM 单元测试` success，`发布（tag）` skipped（非 tag，符合预期）；artifact `unit-test-results`（XML+HTML，约 58KB）与 `Re-BiliTerminal-debug`（约 16.7MB）上传成功。此前 [44839324416](https://github.com/cyq114514/Re-BiliTerminal/actions/runs/44839324416)、[44838407423](https://github.com/cyq114514/Re-BiliTerminal/actions/runs/44838407423) 也全绿；同代码本地 clean 跑出 **211 tests / 0 failures / 0 errors / 0 skipped**，CI 该 job 同提交 success（运行期 android-all 下载已改为本地目录，日志抓取受本机网络限制未拉取，状态以 Actions job conclusion 为准） |
| Android Emulator 集成测试 | **NOT RUN / NOT ADOPTED** | 见 §4 评估；现有仪器化测试会写线上数据，未接入 |
| 发版链模拟（临时 tag，已清理） | **PASS** | 临时 tag `v1.3.9-ci-dryrun` @ `b64a17e` 触发 [run 44854878742](https://github.com/cyq114514/Re-BiliTerminal/actions/runs/44854878742)：`checks`/`build`/`unit-test`/`release` 全 success。真实 secrets 签名成功（证书 `CN=BiliTerminal`），APK `versionCode=20261120`/`versionName=1.3.9`/`armeabi-v7a`；教程插图 **6/6**；Release 自动创建并上传 `Re-BiliTerminal-v1.3.9-ci-dryrun-release.apk`(7,685,189B) + `update.json`(717B)，`update.json` 的 versionCode/sha256/size/fileName/htmlUrl 与 APK 一致。验证后已删除测试 Release、tag（API 404、本地 tag 已删），**未递增 versionCode、未发正式版本**；该 Actions run 保留作审计 |
| P0 发版门禁云测（临时 tag，已清理） | **PASS** | 临时 tag `v1.3.10-dryrun` @ `ed22718` 触发 [run 44856939788](https://github.com/cyq114514/Re-BiliTerminal/actions/runs/44856939788)：`release` 前置校验日志 `最新 Release v1.3.9 的 versionCode=20261120` → `[OK] versionCode 递增 20261120 -> 20261121`、tag 严格一致；全部 4 个 job success。构建后 `apksigner` 校验真实证书 `CN=BiliTerminal`，`APK versionCode=20261121` 与 build.gradle 一致，教程插图 6/6；Release + `update.json` 字段与 APK sha256/size 一致（`versionCode=20261121`）。验证后删除测试 Release、tag、临时分支；`check-release.sh` 的错 tag/未递增/缺签名 3 类失败路径已在本机用例覆盖 |
| 真实版本 Release（首次正式发版，已被同 tag 补发撤销） | **PASS** | tag `v1.4.0` @ `9c03c98` 触发 [run 44900294301](https://github.com/cyq114514/Re-BiliTerminal/actions/runs/44900294301)：`规范与环境检查`/`编译自检（Debug）`/`JVM 单元测试`/`发布（tag）` 全 success。产物 `Re-BiliTerminal-v1.4.0-release.apk`（7,703,009B），`versionCode=20261121`/`versionName=1.4.0`/`armeabi-v7a`，`apksigner` 证书 `CN=BiliTerminal`；`update.json` 的 versionCode/sha256/size/fileName 与 APK 一致（sha256 `62ff7f3d…`）。`官网部署（tag）` 首跑因未配置 `NETLIFY_AUTH_TOKEN`/`NETLIFY_SITE_ID` failure；2026-11-21 补齐两条 secret、Netlify `stop_builds=true` 后重跑 success（Netlify deploy `6b0187548fa5916de17103d5`，11:58Z；线上 `/api/update.json` 已返回 1.4.0）。当天下午因反馈修复需同版本补发，该 Release 与 tag 已显式撤销 |
| 真实版本 Release（v1.4.0 同 tag 补发） | **PASS** | 撤销原 Release/tag 后，`v1.4.0` 指向修复提交 `2213384`，[run 44911394384](https://github.com/cyq114514/Re-BiliTerminal/actions/runs/44911394384)：`规范与环境检查`/`编译自检（Debug）`/`JVM 单元测试`/`发布（tag）`/`官网部署（tag）` 全 success。新产物 `Re-BiliTerminal-v1.4.0-release.apk`（7,704,756B），`versionCode=20261121`/`versionName=1.4.0`/`armeabi-v7a`，证书 `CN=BiliTerminal`；sha256 `e7cac240…`，`update.json` 字段与 APK 一致，Release 说明已改为应用内 1.4.0 更新日志并保留 `ReBiliTerminal-update` 元数据注释。补发前本地干净 worktree：24 套件 / 231 用例通过，新增 `MenuBadgeRefreshTest` / `PlayerLayoutRegressionTest` |
| 真实版本 Release（v1.4.1） | **PASS** | tag `v1.4.1` @ `8e86eef` 触发 [run 44934122748](https://github.com/cyq114514/Re-BiliTerminal/actions/runs/44934122748)：`规范与环境检查`/`编译自检（Debug）`/`JVM 单元测试`/`发布（tag）`/`官网部署（tag）` 全 success（`生成 UI 设备矩阵`/`UI 冒烟` 按事件 skipped，符合预期）。同提交 `main` push [run 44934104467](https://github.com/cyq114514/Re-BiliTerminal/actions/runs/44934104467) success。产物 `Re-BiliTerminal-v1.4.1-release.apk`（7,716,889B），`versionCode=20261122`/`versionName=1.4.1`/`armeabi-v7a`，`apksigner` 证书 `CN=BiliTerminal` / SHA-256 `8557d314…ae9e75`；下载后实测 sha256 `411eaff4bcbdc6cd022298e40272cb2515152067b2b755ffd07683fffa009f4d`，与 `update.json` 的 versionCode/size/sha256 完全一致。本版合并了 main 的 v1.4.0 补发（`2213384`），播放器取线上形态，删除已被取代的 `PlayerSymmetricLayoutTest`，本地 232 用例 0 失败 |

### 8.1 已知遗留/风险（本次未修）

1. **归一化死分支**：`PlayerProgressRules.normalizeProgress` 的“按秒自校准”分支在正数输入下数学上不可达（`raw > duration` 与 `raw*1000 <= duration` 互斥）。本次保持生产行为不变，测试锁定现状；若要修，必须单独评审并补真实服务端样例。
2. **`adoptLastPlayTime` 无上限**：超大异常值直接 `(int)` 截断（可能变负）。现有调用方对进度做了 `> 5` 判断才使用，风险有限；未在本轮改行为。
3. **AudioQueue 不过滤 `invalid`**：过滤责任在 `FavoriteVideoListActivity` 调用方；测试已锁定该契约并注释。是否下沉到队列需单独评审。
4. **未接入 lint / 覆盖率 / 模拟器**：见 §7 与 `testing.md` §6。
5. **CI checkout 行尾差异（已修并验证）**：Ubuntu runner 上 `eol=crlf` 文件会显示为 modified；`check-repo.sh` 已过滤「仅 CR/LF 差异」。第二次 CI 日志：`[ OK ] 工作区干净` + `[INFO] 已忽略 6 个仅行尾差异（CI checkout 的 eol=crlf 归一化）`；若再出现 dirty，说明是真实内容改动。
6. **PR 触发尚未单独实测**：本次是 main push 触发；PR 的同套 job 与 `paths-ignore` 由同一 workflow 定义，等首次 PR 再确认。
7. **`check-repo` 的 versionCode WARN 仍在**：本地检查仍会提示「HEAD 比 tag 多提交但 versionCode 未递增」；发版 job 现在会硬拦，但本地/PR 阶段仍只是告警。
8. **官网部署 secrets（已解决）**：v1.4.0 首跑因 `NETLIFY_AUTH_TOKEN` / `NETLIFY_SITE_ID` 缺失 failure；2026-11-21 已写入两条 secret、Netlify `stop_builds=true`，重跑 `官网部署（tag）` success（deploy `6b0187548fa5916de17103d5`）。普通 push 的自动构建仍被 `netlify.toml` 的 `ignore` 跳过（Netlify 记为 `Canceled build due to no content change`）。

### 8.2 真实 CI 失败与修复记录（过程，非最终状态）

1. **run 44839675340（`bb675c9`）`JVM 单元测试` FAIL**：
   - 现象：12 个 Robolectric 测试类全部 `Failed to fetch maven artifact org.robolectric:android-all-instrumented:13-robolectric-9030017-i7`（`FileNotFoundException`）。
   - 原因：Robolectric 用自己的 `MavenArtifactFetcher` 在测试运行期下载 android-all，不经过 Gradle 仓库/缓存、无重试；同一提交上一次 run 成功，属于外部网络偶发。
   - 修法：`app/build.gradle` 增加 `robolectricJars` 配置，由 Gradle 解析 `android-all-instrumented`（复用 Gradle 缓存），`prepareRobolectricJars` 复制到 build 目录，`robolectric.dependency.dir` 指向它；测试运行期不再联网。
2. **`AppSignTest` 偶发 `sign` 只有 31 位（本地复现）**：
   - 根因（真实生产缺陷）：`ToolsUtil.md5` 用 `for (i = 0; i < 32 - md5code.length(); i++)` 边插入边改长度，最多只补 1 个 0；MD5 首字节 <0x10 时会少 1~2 位，导致 B 站 `sign`/`w_rid` 随机失效。
   - 修法：改为 `while (hex.length() < 32) hex = "0" + hex;`，新增「首字节 0 的 MD5 已知向量」与「300 次批量签名定长 32 位」回归用例。
   - 验证：本地 `clean :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL，**211 tests / 0 failures / 0 errors / 0 skipped**；最终 CI [run 44842783655](https://github.com/cyq114514/Re-BiliTerminal/actions/runs/44842783655) 三个日常 job 全 success（`unit-test` 含 `prepareRobolectricJars`，无运行期下载失败）。

### 8.3 下一步（按顺序）

1. 首次真实 PR 后确认 PR 触发链路与 `paths-ignore`（本次只验证了 main push）。
2. 视需要补 P2 纯逻辑测试与增量 lint（见 `testing.md` §7）。
3. 满足 `ci.md` §4 的接入条件后再评估 Emulator nightly；真机回归仍按模块清单执行。
