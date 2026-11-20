# app/AGENTS.md — app 模块局部约束

> 作用域：`app/` 目录。通用规则见根目录 `AGENTS.md`；这里只写 app 模块特有的、容易踩的约束。

## 1. 界面与资源（强制）

- 颜色、字号、圆角、间距**一律沿用既有 token**（`res/values/colors.xml`、`dimens.xml`、`styles.xml`），规范全文见根目录 `DESIGN.md`：卡片圆角 10dp / 胶囊 14dp / 角标 3dp；卡片垂直间距 4dp；正文 ≥12sp（11sp 仅角标/次级文字）；次要文字 alpha 只有 0.9 / 0.7 / 0.5 三档。
- 新增 UI **不得新造渐变/发光**；需要强调用强调色实底或既有描边 token。
- 选中态要跟随自定义强调色的控件，必须在 Java 里用 `ThemeManager.getPrimary()` 重建 drawable 并注册 `OnThemeChangedListener`（`StateListDrawable` 不会被 `ThemeApplier` 换色）。
- 顶栏返回：番剧分区子页面用 `setTopbarExit()`；顶级 `BangumiActivity` 用 `setMenuClick()`（详见 `DESIGN.md`）。

## 2. 播放页（最高风险）

- `activity/player/PlayerActivity.java` 6,544 行，一个页面承载在线/番剧/直播/本地/第三方/短视频多个入口。改之前先读 `docs/modules/player.md`。
- **native release 必须走 `PLAYER_OPS` 专用线程**（`PlayerActivity.java:1448` 附近），不能改主线程或随手 `reset()`。
- 弹幕 prepare/draw 相关调用**必须在主线程**（`:3205` 附近有历史事故注释：后台调用会打断同 lambda 里的 `setDisplay`）。
- `ijkSurface` 按单实例假设使用；短视频另有一套播放实现（`ShortVideoFeedFragment`），改核心时要同步检查。

## 3. 网络与隐私

- 媒体请求头唯一出口是 `PlayerApi.mediaHeadersFor`（`api/PlayerApi.java:288` 附近）；**PCDN/第三方线路不得携带 Cookie**。
- Cookie 只允许由 `NetWorkUtil` 的 API 域白名单写入；重定向跟跳白名单（`util/NetWorkUtil.java:96-126`）与 csrf 同步（`:643-649`）不要随手放宽。
- 登录凭证不进日志；新增日志前先看现有脱敏工具。

## 4. 第三方与二进制

- `app/libs/` 的 10 个 `.so` 由 `jniLibs.srcDirs = ['libs']` 直接打包，**仓库内无构建脚本，不可重建**。
- `activity/settings/login/CaptchaWebViewActivity` 是自实现的 Geetest WebView 壳（依赖已注释），不是第三方 vendored 代码。

## 5. 设置项与持久化

- 新增一个设置项通常要同步 4 处：设置列表（`adapter/SettingsAdapter.java` 的 `type` 字符串 / id）、设置搜索（`activity/settings/SettingSearchActivity.java` 的 AutoPage 注册）、SP key 常量（`util/SharedPreferencesUtil.java`）、必要时 `AndroidManifest.xml` 注册新页面。
- 已有 SP key 不得改名/改类型；`download.db` 的版本与列语义只增不改（迁移细节见 `docs/modules/download.md`）。

## 6. 资源保活与裁剪

- 只被 `getIdentifier` 动态引用的资源会被 release 的 `shrinkResources` 裁掉；需要保活的加进 `res/raw/keep.xml`（现有 `@xml/*`、`@mipmap/tutorial_*`）。
- 混淆规则在 `app/proguard-rules.pro`；新增反射、Gson 模型或 EventBus `@Subscribe` 前确认 keep 规则。

## 7. 测试

- `src/test/` 是 **JVM 单元测试**（JUnit4 + Robolectric，`testImplementation`）：不需要设备/模拟器，由 CI 的 `unit-test` job 执行；本地命令、ARM64 override、`sdk=33`/`conscryptMode=OFF` 的由来见 [`docs/development/testing.md`](../docs/development/testing.md)。
- 测试依赖只能放 `testImplementation`（不会进入 APK）；不要为了跑测试把它们改成 `implementation`。
- 覆盖对象是纯逻辑/状态机/数据库迁移/安全校验；**不要为了通过测试改业务行为，也不要放宽** `UrlGuard`/`RuleLimits`/`SafeRegex`/HLS 代理的校验或登录/Cookie 白名单。
- `src/androidTest/` 只有 TLS 回移测试，且**会向线上 Supabase 写测试数据并真实联网**；不要在没有说明的情况下运行，且**不纳入 CI**（本地 `NOT RUN`）。
- 不要为通过检查而删除/跳过测试（`@Ignore` 也在禁止范围）；测试体系不变量由 `scripts/check-tests.sh` 检查，新增/改测试后跑一次。
- 测试代码规范（命名、隔离、负向用例、失败消息）与 Robolectric 用法入口：[`docs/development/testing.md`](../docs/development/testing.md) §2/§3。

## 8. 构建红线（app 级）

- 不改 `applicationId`、namespace、versionCode/versionName、`signingConfigs`、`abiFilters`、`multiDexEnabled`、`minSdk/targetSdk`。
- `release` 只打 `armeabi-v7a`；`debug` 为 v7a + x86（`-PemuArm64` 才加 arm64），改动 ABI 策略需单独评审。