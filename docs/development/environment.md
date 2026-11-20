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

## 4. ARM64 Linux / PRoot 专节（本机现状）

本机（OnePlus 13 上的 Ubuntu 24.04 ARM64 PRoot）实测：JDK 17 与 Gradle 8.14.3 可用，但 **AGP 从 Maven 取回的 `aapt2` 是 x86_64 二进制，无法执行**（无 binfmt，不装 qemu）。Debug 构建需要指向系统 arm64 的 aapt2：

```bash
./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2
```

- 该参数**只为本机环境服务**，CI/官方 x86_64 环境**不需要**，也不要把它写进 Gradle 默认配置。
- 本机可做：Debug 编译（冷启动约 2 分 19 秒 / 增量约 29 秒）、`aapt2 dump badging/resources` 校验产物。
- 本机不可做：`assembleRelease`（R8 + `shrinkResources` 需要官方 aapt2 语义）、签名与发布（无 keystore、无 gh）、仪器化测试（无设备）、`adb`/`logcat`（SDK 内 adb 为 x86_64）。
- 结论：**Release 与发布链只在 GitHub Actions 验证**（见 `.github/workflows/ci.yml`）。

## 5. CI 环境事实

- 运行环境：`ubuntu-latest` + `actions/setup-java@v4`（temurin 17）；Android SDK 依赖 runner 预装，通过 `ANDROID_HOME` 使用。**只有 tag 发布 job** 会把 `sdk.dir=$ANDROID_HOME` 写进 `local.properties`（build job 不写）。
- 唯一 workflow 是 `.github/workflows/ci.yml`，触发与行为：

| 触发 | 执行内容 |
|---|---|
| `push` → `main` | checkout → JDK 17 → 准备 Android SDK → `:app:assembleDebug` → 上传 debug artifact（不发版、不注入凭据） |
| `push` → tag `v*` | 同上的 debug 构建后（在 `build` job），进入 `release` job：写 `local.properties` → `:app:assembleRelease` → 资源冒烟（教程插图 ≥6）→ 上传 release artifact → 创建/覆盖 GitHub Release → 写 update 元数据 → 生成并上传 `update.json` |
| `pull_request` → `main` | 同 `push main`：只跑 debug 构建并上传 artifact；不注入 secrets、不碰 Release；纯 `docs/**`、`**.md`、`site/**` 变更按 `paths-ignore` 跳过 |
| `workflow_dispatch` | 按所选 ref：非 tag 仅 debug；tag 走完整发布链 |

- **准备 Android SDK**：每个 job 先检查 `platforms/android-33` 与 `build-tools`；runner 未预装时用自带的 `sdkmanager` 安装（首次约 1~2 分钟）。找不到 `sdkmanager` 时只告警并依赖 AGP 自动下载（软失败，不阻断）。真实 CI 首跑发现 `ubuntu-26.04` 镜像不再预装 platform-33，这一步是必要的。
- **权限**：顶层默认 `permissions: contents: read`；只有 tag-only 的 `release` job 单独申请 `contents: write`。PR 构建与 main push 都不持有写 token，也不接触 secrets。
- **并发/超时/缓存/保留期**：`concurrency` 按 ref 分组（tag 发布不取消，分支/PR 连推取消旧任务）；`build` 超时 30 分钟、`release` 45 分钟；`setup-java` 启用 Gradle 缓存；artifact 保留 7 天。
- **release secrets 共 7 个**：`KEYSTORE_B64`（base64 解码为 `$HOME/release.jks`）、`KEY_PASSWORD`、`ALIAS_NAME`、`ALIAS_PASSWORD`（签名，4 键"全有才签名、缺任一按未签名"）；`BGM_PROXY_AUTH`（Bangumi 反代 Basic 头，缺则境内番剧库不可用）；`DANDAN_APP_ID`、`DANDAN_APP_SECRET`（弹弹play 弹幕签名，缺则第三方番剧无弹幕）。缺失只在 CI 打 `::warning`，不失败。
- **Release 资产命名**：签名成功为 `Re-BiliTerminal-<tag>-release.apk`；未签名为 `Re-BiliTerminal-<tag>-release-unsigned.apk`。
- **`update.json` 字段**：`versionCode`、`versionName`、`sha256`、`size`、`fileName`、`notes`、`htmlUrl`。**versionCode 优先从已构建 APK 的 `aapt2 dump badging` 读取**（与包体强一致）；解析失败才回退 `app/build.gradle` 的 grep 并打告警。客户端 `UpdateManager` 主通道读取 `releases/latest/download/update.json`。
- **已知发布链风险（历史遗留，未修）**：secrets 缺失导致未签名包时，流程仍会生成并上传 `update.json`，客户端可能提示更新到签名不一致、无法安装的包。修复需要产品决策（阻断 Release / 不写 update.json / update.json 标记 signed:false），不在本次文档修复范围。

### 真实运行记录（2026-11-21，PR #1 及其合并）

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
| `aapt2` 报格式错误/无法执行 | 架构不匹配（ARM64 + x86_64 aapt2） | 用 §4 的 override 参数 |
| `keystore file not found` | `KEY_PATH` 含非 ASCII 或路径错 | 用纯 ASCII 绝对路径 |
| release 包文件名带 `unsigned` | `local.properties` 缺 `KEY_PATH` | 补签名配置，或确认是否需要未签名包 |
| CI 依赖解析 502 | 阿里云镜像在境外 runner 不可靠 | 不要删 `settings.gradle` 的 `GITHUB_ACTIONS` 分支 |
| `SDK location not found` | 缺 `local.properties` 的 `sdk.dir` | 补上（CI 由 workflow 生成） |
| 依赖下载慢 | 本地镜像已优先阿里云；首轮仍需完整下载 | 保留 `~/.gradle` 缓存；不要反复 `clean` |

## 7. 安全注意

- `local.properties`、`*.jks`、`*.keystore`、`*.p12` 已在 `.gitignore`；**签名口令与凭据不得写进任何文档或源码**。
- 仓库历史曾出现明文签名口令（已从当前工作区文档移除，**仍需轮换密钥**才能真正失效）；新增文档/日志前先确认不含凭据。
- CI 的 secret 只在 tag 分支使用；debug 构建先于凭据写入（`ci.yml:40-43`），不要打乱顺序。