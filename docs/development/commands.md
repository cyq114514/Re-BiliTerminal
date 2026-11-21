# 验证命令（commands）

> 什么时候读：需要构建、验证、查产物时。所有命令都在本仓库真实存在；**未实测的会标注**。
> 分级：**A** 已在本机实测可用 ｜ **B** 本机可用但需前置条件 ｜ **C** 只能 CI ｜ **D** 需要设备/凭据 ｜ **E** 未验证（存在但本次未跑）

## 1. 仓库与 Diff

| 命令 | 用途 | 级别 | 备注 |
|---|---|---|---|
| `git status --short` | 改动前/提交前检查工作区 | A | 有被 ignore 的构建产物，不要 `git add .` |
| `git diff --stat` | 提交前看改动面 | A | 再看关键文件完整 diff |
| `git diff --check` | 空白错误/冲突标记 | A | 每次提交前必做 |
| `git log --oneline -10` | 近期提交风格与上下文 | A | 提交正文惯例：根因 + 修法 |
| `git log --follow -- <path>` | 判断文件是原样引入还是本项目改过 | A | 见 `../architecture/module-map.md` 的边界判定 |

### 1.1 仓库自检脚本（`scripts/`，均为只读）

| 命令 | 用途 | 退出码 |
|---|---|---|
| `./scripts/check-env.sh` | 检查 git/JDK/SDK/aapt2 架构/Gradle wrapper/磁盘；给出 ARM64 override 提示 | 0 可用（允许 WARN）｜1 有 FAIL |
| `./scripts/check-repo.sh` | 工作区、敏感文件是否入库、凭据泄露、docs 索引、版本与 tag 关系 | 同上 |
| `./scripts/project-info.sh` | 输出关键工程信息 JSON（版本/分支/tag/SDK/AGP/Gradle），供脚本或 Agent 读取 | 0 |
| `./scripts/validate-ci.sh` | 静态检查 workflow：permissions/concurrency/timeout/缓存/retention/tag 收口等 | 0 无 FAIL｜1 有 FAIL |
| `./scripts/check-links.sh` | 检查已跟踪 Markdown 的相对链接（跳过外部/锚点/查询串；代码围栏内示例不查） | 0 全部有效｜1 有失效链接 |
| `./scripts/check-tests.sh` | 测试体系不变量：CI 是否真的跑测试、是否 `continue-on-error`、测试依赖是否误进 release、是否访问真实 B 站/凭据、是否 `@Ignore` 吞用例、Audio Focus 红线 | 0 通过（允许 WARN）｜1 有 FAIL |

> 以上 6 个脚本均为只读：不联网、不编译、不运行测试、不写文件、不读 secrets；缺少依赖时给出提示而非自动安装。
> `validate-ci.sh` 当前对仓库 workflow 通过（FAIL 0；1 个 WARN：多个 action 使用浮动 major tag、未 pin 到 commit SHA，本次检查为 11 处——数量随 workflow 增减，以脚本实际输出为准）。修改 workflow 后请重跑；环境有 PyYAML 时会额外做一次 YAML 语法解析。
> 另外两个脚本**不属于本机自检**，只在 CI 或排查 CI 时使用：
> - `./scripts/ci-prepare-sdk.sh`：CI job 内准备 Android SDK（`platform 33` + build-tools）。依赖 `ANDROID_HOME`，找不到 `sdkmanager` 时只告警（软失败）；会调用 `sdkmanager` 安装组件，**不是只读脚本**，不要拿它在本地替代 §2 的构建前置。
> - `python3 scripts/summarize-tests.py [results_dir]`：汇总 Gradle JUnit XML 到控制台与 GitHub Step Summary；默认目录 `app/build/test-results/testDebugUnitTest`。**没有 XML 时以退出码 2 明确报 NOT RUN**（编译失败/未执行），不会把「没跑」误报成 PASS。
> - `./scripts/check-release.sh`：**发版前置校验**，只在 release job 调用；校验 tag/versionName、versionCode 递增、签名 secrets 齐全。默认无参数时全部 FAIL，需由 workflow 注入 `TAG`/`VERSION_NAME`/`VERSION_CODE`/`PREVIOUS_VERSION_CODE` 与 4 个签名 env；输出不含 secret 值。

## 2. 构建

| 命令 | 用途 | 级别 | 前置/备注 |
|---|---|---|---|
| `./gradlew --version` | 验证 Wrapper 可用、JDK 版本 | A | 会校验 `distributionSha256Sum`（下载时） |
| `./gradlew :app:assembleDebug` | 编译调试包 | B | x86_64 直接可用；ARM64 需见下条 |
| `./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2` | ARM64/PRoot 编译调试包 | A（本机实测） | 只为本机环境服务，CI 不需要 |
| `./gradlew :app:assembleRelease` | 正式包（R8 + 资源收缩 + 签名） | C | 本地需 `local.properties` 签名配置，且**不等价于 CI**；只在 CI 验证 |
| `./gradlew clean` | 清理构建产物 | A | 会删 `app/build/`；不要因为编译慢而反复 clean |
| `./gradlew :app:lintDebug` | AGP 默认 lint | E | 任务名已用 `:app:tasks` 确认存在；本仓库未配置 lint、CI 也不跑，结果未验证 |

产物路径：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release*.apk
```

## 3. 产物核验（无需设备）

| 命令 | 用途 | 级别 |
|---|---|---|
| `<aapt2> dump badging app/build/outputs/apk/debug/app-debug.apk` | 核对 package/versionCode/versionName/minSdk/targetSdk | A（本机 arm64 aapt2 实测） |
| `<aapt2> dump resources <apk> \| grep -c 'mipmap/tutorial_'` | 核对 release 未被裁掉的教程插图（CI 断言 6/6，对应 `res/raw/keep.xml`） | A（debug 包可复现；release 需实际产物） |

> `<aapt2>`：x86_64 环境用 `$ANDROID_HOME/build-tools/<版本>/aapt2`；本机 ARM64 用 `/usr/lib/android-sdk/build-tools/debian/aapt2`。

## 4. 测试

JVM 单元测试（JUnit4 + Robolectric，无需设备）是本次新增的日常门禁。测试清单与未覆盖风险见 [`testing.md`](testing.md)，CI job 结构与失败分类见 [`ci.md`](ci.md)。

| 命令 | 用途 | 级别 | 备注 |
|---|---|---|---|
| `./gradlew :app:testDebugUnitTest --stacktrace` | 全量 JVM 单元测试（与 CI `unit-test` job 同一任务） | B | 首次运行会下载 Robolectric `android-all`（几十 MB）；本机 ARM64/PRoot 会因 aapt2 架构失败，需下一行的 override |
| `./gradlew :app:testDebugUnitTest --stacktrace -Pandroid.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2` | ARM64/PRoot 跑 JVM 单测 | B | 该参数只为本机环境服务，CI x86_64 不需要；本机实际结果以交付报告为准，未跑写 NOT RUN |
| `./gradlew :app:testDebugUnitTest --tests 'com.RobinNotBad.BiliClient.util.PlayerProgressRulesTest'` | 只跑单个测试类（`--tests` 可重复或写通配） | B | 定位失败用例时用，避免全量重跑 |
| `./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace` | 单测 + Debug 编译（提交前推荐一起跑） | B | 一次 Gradle 调用完成两项验证 |
| `./gradlew -PemuArm64 connectedDebugAndroidTest` | 仪器化测试（TLS 回移，2 个用例：强制 Conscrypt 握手 + 仅内置补根验 netlify 链） | D | 需 arm64 设备/模拟器；**会向线上 Supabase 写数据并真实联网**；**不纳入 CI**，本地 NOT RUN |

报告位置：

```text
app/build/test-results/testDebugUnitTest/TEST-*.xml   # 机读结果（CI 汇总用）
app/build/reports/tests/testDebugUnitTest/index.html  # 人读报告
```

> 失败先看 HTML 中的失败用例与堆栈；XML 里的 `<failure message="...">` 是 CI Job Summary 的来源。
> 测试依赖（JUnit/Robolectric/`androidx.test:core`）均为 `testImplementation`，不会进入 APK；`app/src/test/resources/robolectric.properties` 固定 `sdk=33`、`conscryptMode=OFF`（原因见 testing.md §2.3）。
> 新增/改动测试后还应跑 `./scripts/check-tests.sh`（见 §1.1）。

## 5. 设备与日志（第二阶段）

| 命令 | 用途 | 级别 | 备注 |
|---|---|---|---|
| `adb install -r <apk>` | 真机安装 | D | 本机 PRoot 无 adb（x86_64），需在能跑 adb 的环境 |
| `adb logcat -s AndroidRuntime` | 崩溃日志 | D | 同上 |

## 6. 发布链（不要手动执行）

| 命令/动作 | 说明 |
|---|---|
| `git tag v<versionName>` + push tag | 触发 CI 正式构建、创建 Release、上传 `update.json`（`.github/workflows/ci.yml`） |
| `gh release view/upload` | CI 内部使用；本机未安装 gh |
| 直接运行 CI 的 release 步骤 | 需要 7 个 secrets；**不允许**在本地伪造发布 |

> 红线：不手动改 `versionCode`/`versionName` 发版；不推送正式 tag；不创建 Release。发版是仓库所有者的动作。

## 7. 状态标记约定

- `PASS`：实际执行且符合预期；`FAIL`：执行且失败；`PARTIAL`：部分执行；`BLOCKED`：环境/权限不允许；`NOT RUN`：未执行。
- 报告里必须写清命令、退出码与关键输出，不得只写"已验证"。
- 测试结果的完整分类与失败归类见 [`ci.md`](ci.md) §3、登记要求见 [`testing.md`](testing.md) §8。**未推送的改动、没有真实 Actions 结果时，CI 状态一律写 `NOT RUN`**；本地测试通过不能写成「CI 通过」。
