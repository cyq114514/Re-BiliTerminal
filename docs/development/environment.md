# 开发环境（environment）

> 什么时候读：第一次在本机构建、换机器/换环境、构建报错时。
> 命令清单见 `commands.md`；命令是否在本机真实可用也标注在那里。

## 1. 版本矩阵（以实际配置为准）

| 项 | 版本/值 | 出处 |
|---|---|---|
| JDK（构建） | 17（`sourceCompatibility`/`targetCompatibility` 为 Java 11） | `app/build.gradle:121-122` |
| Gradle Wrapper | 8.14.3（腾讯镜像 + SHA-256 校验） | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.10.0 | `build.gradle:6-8` |
| compileSdk / minSdk / targetSdk | 33 / 19 / 26 | `app/build.gradle:11,29,31` |
| 构建产物 ABI | release：`armeabi-v7a`；debug：`armeabi-v7a` + `x86`（`-PemuArm64` 时加 `arm64-v8a`） | `app/build.gradle:42-47,111-116` |
| 应用包名 | `com.RobinNotBad.BiliClient.re`（debug 再加 `.test`） | `app/build.gradle` |
| 依赖仓库 | 本地：阿里云镜像优先；CI（`GITHUB_ACTIONS=true`）：直连 google/mavenCentral/jitpack，**跳过镜像** | `settings.gradle:3-27` |
| JVM 单元测试依赖（`testImplementation`，不进 APK） | JUnit `4.13.2`、Robolectric `4.18` (Linux aarch64 native SQLite 需要 4.18+)、`androidx.test:core 1.5.0` | `app/build.gradle:134-142,190-195` |

> 镜像分支是踩坑后的既有设计（v1.3.3 的 tag CI 因阿里云镜像 502 整条构建失败），**不要删除或"统一"**。

## 2. 首次配置

1. 安装 JDK 17、Android SDK（`platforms;android-33` + `build-tools`）。
2. 在仓库根目录创建 `local.properties`（已被 `.gitignore` 忽略）：

```properties
# 只放 SDK 路径；签名/凭据见下
sdk.dir=/path/to/Android/sdk
```

3. 签名相关（仅构建 release 时需要，**不要提交**）：

```properties
KEY_PATH=/path/to/your.jks      # 必须是纯 ASCII 路径
KEY_PASSWORD=******
ALIAS_NAME=******
ALIAS_PASSWORD=******
```

缺 `KEY_PATH` 时 release 会静默产出 `app-release-unsigned.apk`（`app/build.gradle:71-102` 的条件挂载）。`KEY_PATH` 含中文会因 `Properties.load()` 按 ISO-8859-1 解析而报 `keystore file not found`。

4. 可选凭据（同样只走 `local.properties` 或 CI secrets）：`BGM_PROXY_AUTH`（Bangumi 反代 Basic 头）、`DANDAN_APP_ID`/`DANDAN_APP_SECRET`（弹弹play 弹幕）。缺失只降级，不影响其他功能。

## 3. 本地构建（x86_64 / CI 正常路径）

```bash
./gradlew :app:assembleDebug     # 调试包
./gradlew :app:assembleRelease   # 正式包（需签名配置；本地结果不等价于 CI）
```

产物：`app/build/outputs/apk/{debug,release}/*.apk`。

### 3.1 本地 JVM 单元测试（无需设备/签名）

```bash
./gradlew :app:testDebugUnitTest --stacktrace
```

- 测试代码在 `app/src/test/java/`，JUnit4 + Robolectric；`testOptions.unitTests` 开启 `includeAndroidResources`/`returnDefaultValues`（`app/build.gradle:134-142`）。
- 依赖只在 `testImplementation`（`app/build.gradle:190-195`），**不会进入 APK**，也不影响 minSdk 19。
- `app/src/test/resources/robolectric.properties` 固定 `sdk=33` 且 `conscryptMode=OFF`：`conscrypt-android` 在 JVM 上安装 Provider 会 `UnsatisfiedLinkError`，而测试不验证 TLS，关掉它不影响被测业务逻辑（说明见 [`testing.md`](testing.md) §2.3）。
- Robolectric 的 `android-all-instrumented`（约 160MB）由 Gradle `robolectricJars` 配置解析并复制到 `app/build/robolectric-jars/preinstrumented`，通过 `robolectric.dependency.dir` 交给 Robolectric；不在测试运行期自行联网下载（避免 CI 偶发 404）。
- 不要求 `local.properties` 签名配置、不需要真机/模拟器；但**需要 Android SDK**（`ANDROID_HOME` 或 `local.properties` 的 `sdk.dir`）。
- **ARM64 / PRoot 同样要加 aapt2 override**（与 §4 构建相同参数）：

```bash
./gradlew :app:testDebugUnitTest \
  -Pandroid.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2
```

- 报告：`app/build/test-results/testDebugUnitTest/`（XML）、`app/build/reports/tests/testDebugUnitTest/index.html`（HTML）。命令与失败定位见 [`commands.md`](commands.md) §4；测试策略见 [`testing.md`](testing.md)。
- 没有实际执行过该命令时一律写 `NOT RUN`；**本地通过不等于 CI 通过**（CI 结果只能来自真实 Actions，见 [`ci.md`](ci.md) §3）。

## 4. ARM64 Linux / PRoot 专节（本机现状）

本机（OnePlus 13 上的 Ubuntu 24.04 ARM64 PRoot）实测：JDK 17 与 Gradle 8.14.3 可用，但 **AGP 从 Maven 取回的 `aapt2` 是 x86_64 二进制，无法执行**（无 binfmt，不装 qemu）。Debug 构建需要指向系统 arm64 的 aapt2：

```bash
./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2
```

- 该参数**只为本机环境服务**，CI/官方 x86_64 环境**不需要**，也不要把它写进 Gradle 默认配置。
- 本机可做：Debug 编译（冷启动约 2 分 19 秒 / 增量约 29 秒）、`aapt2 dump badging/resources` 校验产物。
- JVM 单测（§3.1）同样受 aapt2 架构限制，必须带同一 override 参数；测试本身不需要设备。
- 本机不可做：`assembleRelease`（R8 + `shrinkResources` 需要官方 aapt2 语义）、签名与发布（无 keystore、无 gh）、仪器化测试（无设备）、`adb`/`logcat`（SDK 内 adb 为 x86_64）。
- 结论：**Release 与发布链只在 GitHub Actions 验证**（见 `.github/workflows/ci.yml`）。

## 5. CI 环境事实

- 运行环境：`ubuntu-latest` + `actions/setup-java@v4`（temurin 17，`cache: gradle`）；Android SDK 由 runner 预装 + `scripts/ci-prepare-sdk.sh` 补齐 `platform 33`/build-tools，通过 `ANDROID_HOME` 使用。**只有 tag 发布 job** 会把 `sdk.dir=$ANDROID_HOME` 等写进 `local.properties`；`checks`/`build`/`unit-test` 都不写 `local.properties`、不接触 secrets。
- 唯一 workflow 是 `.github/workflows/ci.yml`，共 **4 个 job**（`checks` / `build` / `unit-test` / `release`）。job 级细节（超时、失败分类、报告位置）见 [`ci.md`](ci.md)，这里只记环境事实：

| 触发 | 执行内容 |
|---|---|
| `push` → `main` | `checks`（规范/链接/CI 静态/测试不变量）＋ `build`（Debug 编译 + 上传 APK）＋ `unit-test`（JVM 单测 + 报告 artifact）；`build` 与 `unit-test` **并行**。不发版、不注入凭据 |
| `push` → tag `v*` | 日常 3 个 job + `release`（仅 tag，`needs: build`）：写 `local.properties` → `:app:assembleRelease` → 资源冒烟（教程插图 ≥6）→ 上传 release artifact → 创建/覆盖 GitHub Release → 写 update 元数据 → 生成并上传 `update.json` |
| `pull_request` → `main` | 与 `push main` 相同（3 个日常 job）；不注入 secrets、不碰 Release；纯 `docs/**`、`**.md`、`site/**` 变更按 `paths-ignore` 跳过 |
| `workflow_dispatch` | 手动触发：按所选 ref 跑日常 3 个 job；tag ref 仍走完整发布链 |

- **4 个 job 的职责与超时**：`checks` 15 分钟（只读脚本，不编译）、`build` 30 分钟（`assembleDebug` + artifact）、`unit-test` 25 分钟（`testDebugUnitTest` + 报告 artifact）、`release` 45 分钟（仅 tag）。`build` 与 `unit-test` **互不 `needs`**，编译失败和测试失败是两条独立结果；`release` 仍只 `needs: build`，**既有发布链职责、步骤与触发条件没有因新增测试而改变**。
- **准备 Android SDK**：需要编译/单测的 job 统一调用 `./scripts/ci-prepare-sdk.sh`；`platforms/android-33` 或 build-tools 缺失时用 runner 自带 `sdkmanager` 补装（首次约 1~2 分钟），找不到 `sdkmanager` 时只告警并依赖 AGP 自动下载（软失败，不阻断）。真实 CI 首跑发现 `ubuntu-26.04` 镜像不再预装 platform-33，这一步是必要的。
- **权限**：顶层默认 `permissions: contents: read`；只有 tag-only 的 `release` job 单独申请 `contents: write`。PR 构建与 main push 都不持有写 token，也不接触 secrets。
- **并发**：`concurrency.group = ci-${{ github.ref }}`；分支/PR 连推取消旧任务，**tag 发布不取消**（`cancel-in-progress` 对 tag 为 false）。
- **缓存**：`setup-java` 启用 Gradle 缓存（复用 `~/.gradle/caches`）；CI（`GITHUB_ACTIONS=true`）跳过阿里云镜像（见 §1）。
- **报告与 artifact**：`unit-test` job 用 `scripts/summarize-tests.py` 写 GitHub Job Summary（测试总数/失败数/耗时/失败用例名与消息），并上传 `unit-test-results` artifact（XML + HTML，保留 14 天）；Debug APK artifact 保留 7 天。编译失败时没有 XML，汇总脚本会明确报 `NOT RUN`（退出码 2），不会误报 PASS。
- **release secrets 共 7 个**：`KEYSTORE_B64`（base64 解码为 `$HOME/release.jks`）、`KEY_PASSWORD`、`ALIAS_NAME`、`ALIAS_PASSWORD`（签名，4 键"全有才签名、缺任一按未签名"）；`BGM_PROXY_AUTH`（Bangumi 反代 Basic 头，缺则境内番剧库不可用）；`DANDAN_APP_ID`、`DANDAN_APP_SECRET`（弹弹play 弹幕签名，缺则第三方番剧无弹幕）。缺失只在 CI 打 `::warning`，不失败。
- **Release 资产命名**：签名成功为 `Re-BiliTerminal-<tag>-release.apk`；未签名为 `Re-BiliTerminal-<tag>-release-unsigned.apk`。
- **`update.json` 字段**：`versionCode`、`versionName`、`sha256`、`size`、`fileName`、`notes`、`htmlUrl`。**versionCode 优先从已构建 APK 的 `aapt2 dump badging` 读取**（与包体强一致）；解析失败才回退 `app/build.gradle` 的 grep 并打告警。客户端 `UpdateManager` 主通道读取 `releases/latest/download/update.json`。
- **已知发布链风险（历史遗留，未修）**：secrets 缺失导致未签名包时，流程仍会生成并上传 `update.json`，客户端可能提示更新到签名不一致、无法安装的包。修复需要产品决策（阻断 Release / 不写 update.json / update.json 标记 signed:false），不在本次文档修复范围。

### 真实运行记录（2026-11-21，旧版 CI：仅 build 链）

> 下表是**改造前 workflow（只有 `build` 一条链）**的真实运行记录，用于说明 SDK 预检查与缓存效果的来历。
> 本次新增的 `checks` / `unit-test` 结构**尚未推送、尚未运行（NOT RUN）**；首次真实结果应以 Actions 页面为准，不能沿用下表推断。

| run | 事件 | 结果 | 说明 |
|---|---|---|---|
| 44781850513 | PR | ❌ 失败 | 首跑：runner 不预装 platform-33，SDK 预检查步骤直接失败 → 改为缺失时 sdkmanager 安装 |
| 44782040134 | PR | ✅ 成功 | 冷启动无缓存：build job 2m23s（Gradle 1m22s），artifact 16.7MB/7 天，release job 正确跳过 |
| 44782797819 | PR | ✅ 成功 | **缓存命中**：job 95s，Gradle 37s（缓存约 446MB） |
| 44785586408 | PR | ✅ 成功 | 复查修复提交 `a050f94` 的最终 PR run |
| 44786409920 | push main | ✅ 成功 | merge commit `3a7db29` 合并后 main 构建通过 |

## 6. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `aapt2` 报格式错误/无法执行 | 架构不匹配（ARM64 + x86_64 aapt2） | 用 §4 的 override 参数（JVM 单测同样需要，见 §3.1） |
| `keystore file not found` | `KEY_PATH` 含非 ASCII 或路径错 | 用纯 ASCII 绝对路径 |
| release 包文件名带 `unsigned` | `local.properties` 缺 `KEY_PATH` | 补签名配置，或确认是否需要未签名包 |
| CI 依赖解析 502 | 阿里云镜像在境外 runner 不可靠 | 不要删 `settings.gradle` 的 `GITHUB_ACTIONS` 分支 |
| `SDK location not found` | 缺 `local.properties` 的 `sdk.dir` | 补上（CI 由 workflow 生成） |
| 依赖下载慢 | 本地镜像已优先阿里云；首轮仍需完整下载 | 保留 `~/.gradle` 缓存；不要反复 `clean` |

## 7. 安全注意

- `local.properties`、`*.jks`、`*.keystore`、`*.p12` 已在 `.gitignore`；**签名口令与凭据不得写进任何文档或源码**。
- 仓库历史曾出现明文签名口令（已从当前工作区文档移除，**仍需轮换密钥**才能真正失效）；新增文档/日志前先确认不含凭据。
- CI 的 secret 只在 tag 分支使用；debug 构建与 unit-test 先于凭据写入（secrets 只出现在 `ci.yml` 的 `release` job 内，`ci.yml:170-217`），不要打乱顺序。
- 普通 PR/main push **不注入任何 secrets**；需要 secrets 的新 job 必须用 tag/环境保护条件收口。