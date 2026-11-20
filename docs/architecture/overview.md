# 架构总览（overview）

> 定位：这是架构文档的入口。**第一次接触本项目、或被派到一个不知道落在哪个模块的任务时，先读这一份**（10 分钟内建立"它是什么、怎么跑起来、线程怎么走"的模型）；之后按需跳到 [module-map](./module-map.md)、[dependency-map](./dependency-map.md)、[data-flow](./data-flow.md)、[risk-map](./risk-map.md)。

- 基线：分支 `chore/agent-friendly-repo`，代码与 `main @ a26c03c`（1.3.9）一致；规模为本机实测：**468 个 .java / 92,653 行**、4 个 Gradle 模块、清单 **113 个 `<activity>`**（115 处 `<activity` 含 2 个 alias）。
- 证据口径：文中 `path:line` 均按当前 HEAD 逐条打开核对过；**行为性描述（运行时表现）标【未核实】**，表示本仓库无法构建/真机验证，只能由代码与注释推断。

## 1 项目定位与边界

| 项 | 事实 | 证据 |
|---|---|---|
| 是什么 | 哔哩哔哩**非官方**第三方客户端（"Re：哔哩终端"），GPL-3.0；仅 GitHub Release 分发，不上架 Play | `readme.md:5`、`LICENSE` |
| 包名 | namespace `com.RobinNotBad.BiliClient`，applicationId `com.RobinNotBad.BiliClient.re`（debug 再加 `.test`，可与正式版共存） | `app/build.gradle:9,28`、`app/build.gradle:109` |
| 系统下限/形态 | `minSdk 19`（Android 4.4）、`compileSdk 33`、Java 11；纯原生 View（无 Compose/无导航框架），含圆屏/旋钮适配（`ui/widget/` 21 个自绘类） | `app/build.gradle:11,29,31,121-122` |

**不是**本项目的东西（改之前必须确认归属）：`ijkplayer-java/`、`DanmakuFlameMaster/`、`brotlij/` 三个模块与 `app/libs/*.so` 二进制。判定方法见 [module-map](./module-map.md) §6。

## 2 技术栈（只用这些，不要引入新框架）

| 层 | 选择 | 版本/位置 |
|---|---|---|
| 语言/构建 | Java 11、AGP 8.10.0、Gradle Wrapper 8.14.3、JDK 17 | `build.gradle:6-8`、`gradle/wrapper/gradle-wrapper.properties:3` |
| 界面 | AndroidX AppCompat + MDC + ConstraintLayout + 自绘 View；**无 ViewBinding** | `app/build.gradle:13-15,128-130` |
| 网络 | **OkHttp 3.12.13 手写封装**（`util/NetWorkUtil`），无 Retrofit/Rx | `app/build.gradle:141`、`util/NetWorkUtil.java:63` |
| JSON | Gson 2.8.9 + 原生 `JSONObject` 混用 | `app/build.gradle:173` |
| 图片/事件 | Glide 4.13.2（含 okhttp3 集成 + compiler）、EventBus 3.2.0（全局单例总线） | `app/build.gradle:148-150,155`、`activity/base/BaseActivity.java:584` |
| 播放 | 内嵌 `ijkplayer-java` + `app/libs/libijk*.so`（预编译） | `app/build.gradle:163`、`activity/player/PlayerActivity.java:676` |
| 弹幕/解压 | 内嵌 `DanmakuFlameMaster`（**已 fork**）、`brotlij`（Brotli JNI） | `app/build.gradle:164-165` |
| 其他 | jsoup 1.15.3、protobuf-javalite 3.21.12、Conscrypt 2.5.2（仅崩溃通道 TLS）、zxing 3.3.0、PhotoView 2.3.0 | `app/build.gradle:133-179` |

版本与用途的完整表见 [dependency-map](./dependency-map.md) §2。

## 3 分层实况：没有 MVVM，是一条"Activity → 静态 api → 线程池 → 回主线程"的直线

```text
Activity / Fragment（113 个清单页，直接持有 UI 状态）
   │  调用静态方法，URL 字符串手工拼接，参数校验与 try/catch 写在页面里
   ▼
api/*.java（50 个类，HTTP + JSON 解析一体，无接口/无 DI、无 Repository）
   │  NetWorkUtil.getJson/get/post → 全局 OkHttp 单例
   ▼
util/CenterThreadPool.run(...)（后台：API≥17 走 kotlinx 协程 Dispatchers.IO，否则线程池/裸线程）
   │  回调里再 runOnUiThread / CenterThreadPool.runOnUiThread 回主线程
   ▼
Activity 方法（setText/setAdapter/...）
```

- 关键证据：`util/CenterThreadPool.java:39-70`（协程 or 线程池二选一）、`:79`（`run`）、`:178`（`runOnUiThread`）；`api/PlayerApi.java:311`（URL 字符串拼接的典型）；`activity/player/PlayerActivity.java:723`（后台线程→主线程的典型写法）。
- 页面之间**没有路由框架**：全部 `new Intent().setClass(...)` + `putExtra`。集中跳转入口是 `util/TerminalContext.java` 与 `BiliTerminal.jumpToVideo/jumpToArticle/jumpToUser`（`BiliTerminal.java:325-342`）；`BaseActivity.startActivity` 被重写以统一处理 `ActivityNotFoundException`（`activity/base/BaseActivity.java:739`）。
- **全仓没有** `ViewModel`/`LiveData`/`Room`/`Retrofit`/`RxJava`/DI 框架（`grep` 无反证）。结论：**任何"补一层架构"的重构都会触及全部 113 个页面**，本阶段不做；新代码沿用现有写法，不要引入第三套风格。

## 4 启动链

```text
桌面图标
  └─ activity-alias（默认 .LauncherDefault → SplashActivity；伪装模式 → CalculatorActivity）
       └─ SplashActivity（extends Activity，不继承 BaseActivity）
            └─ 主进程：BiliTerminal.onCreate 完成后 → runStartupFlow
                 ├─ 第零步 隐私锁闸门（未解锁 → 计算器页）
                 ├─ 第一步 纯本地决策（恢复栈/请求头/首次设置）
                 ├─ 第二步 进首屏：startRestoreChain 或 startMainPage → MenuActivity.btnNames[0]
                 └─ 第三步 网络校验（Cookie 续期/刷新/版本检查，不挡开屏）
```

| 环节 | 事实 | 证据 |
|---|---|---|
| Application | `.BiliTerminal`，`largeHeap=true`、`allowBackup=false`、自定义 `networkSecurityConfig` | `AndroidManifest.xml:28,29,32,33` |
| 最早初始化 | `attachBaseContext`：`MultiDex.install` → `ErrorCatch.initEarly` | `BiliTerminal.java:80,84,89` |
| 全局 Context | `onCreate` 里一次性赋值静态 SP 与静态 Context（按 SP `dpi` 覆盖 density） | `BiliTerminal.java:96,97`，`getFitDisplayContext` 在 `:302` |
| `:crash` 进程 | `CatchActivity` 声明 `android:process=":crash"`；`onCreate` 里 `!isMainProcess()` 立即 `ErrorCatch.initCrashProcess` 并 `return` | `AndroidManifest.xml:110,113`、`BiliTerminal.java:102-106`、`isMainProcess` 在 `:350`（读 `/proc/self/cmdline`） |
| 主进程启动任务 | 2s 后崩溃补发/早期崩溃补发/播放哨兵、活跃统计；旧 SP `episode_progress` 一次性清理；assets 内置源灌入（300ms）；Cookie 周期续期；动态/消息未读数 | `BiliTerminal.java:119-137,142-148,231,236,238,250` |
| Activity 计数 | 注册 `ActivityLifecycleCallbacks`，存活数归零 → 清冷启动恢复栈 | `BiliTerminal.java:153,158,165` |
| 首屏回调 | 启动弹窗不直接从开屏页拉起，而是挂到"首屏 resumed"之后 | `BiliTerminal.java:73,182-184,283` |
| 开屏页 | `extends Activity`（避开 `BaseActivity` 的主题/EventBus/边距链路），自己装 DPI 与主题；非任务根时直接 `finish`（防"桌面二次启动压栈"） | `activity/SplashActivity.java:45,58,66` |
| 启动流 | `runStartupFlow` 后台线程、可重入；隐私锁闸门、恢复链、首屏选择、延后的网络校验 | `activity/SplashActivity.java:133,140,151,157,213,221,246,276` |
| 首屏选择 | "启动首页"设置（`launch_page`）→ 否则 `MenuActivity.btnNames` 插入顺序第一项 → 兜底 `RecommendActivity`；`btnNames` 是 `LinkedHashMap`，**顺序即菜单顺序与启动首页语义** | `activity/SplashActivity.java:283,285,333`、`activity/MenuActivity.java:70`（注释 `:67`） |
| 伪装/外链 | 两个 `activity-alias` 由隐私锁运行时启停（先开新、后关旧）；`GetIntentActivity`（exported）处理 `http/https/bilibili` 与 `type=video_av/video_bv` | `AndroidManifest.xml:670-695,122`、`activity/privacy/PrivacyLockSettingsActivity.java:280`、`activity/GetIntentActivity.java:13,22` |

## 5 全局状态清单（"改一处、全局受影响"的默认假设成立）

| 状态 | 位置 | 谁在读 | 改动后果 |
|---|---|---|---|
| 静态 `Context` | `BiliTerminal.java:47` | 几乎所有 api/util | 赋值时机一变即启动期 NPE |
| 静态 `SharedPreferences` | `util/SharedPreferencesUtil.java:92`（`getSharedPreferences()` 在 `:94`） | 全项目读写 | 读取方法都有空保护，但初始化顺序变化仍可能 NPE |
| 全局 OkHttp + 请求头 | `util/NetWorkUtil.java:63`（单例）、`:735`（`webHeaders`，volatile copy-on-write）、`:762`（`refreshHeaders`）、`:794`（`updateWebHeaders`） | 全部 api | 改拦截器/头 → Cookie 出域、重定向、WBI 全部连带 |
| EventBus 全局总线 | `activity/base/BaseActivity.java:584,616` | 全页面 + `util/MsgUtil` | 事件类型/注册时机改动 = 全局提示与评论回传失效 |
| 主题静态单例 | `ui/theme/ThemeManager.java:37,43,66` | 全部 View | 改派生逻辑 → 全应用配色变化 |
| 栈顶 `InstanceActivity`（弱引用） | `BiliTerminal.java:77,287` | 启动弹窗宿主 | 换实现 → 免责声明/更新弹窗不弹 |
| 第三方源单例 | `source/SourceManager.java:30` | 源页面 + 播放页 | 缓存不清 → 换源/导入后仍旧列表 |
| 下载静态进度 | `service/DownloadService.java:58-64` | 下载 UI + 通知 | 改语义 → UI 不刷新/队列卡死（`START_STICKY` 恢复依赖） |
| `PLAYER_OPS` / `AUDIO_OPS` 单线程池 | `activity/player/PlayerActivity.java:1448`、`service/AudioPlayerService.java:151` | native release | 线程模型改动 → native 崩溃/ANR |

清单的完整版与连带风险见 [risk-map](./risk-map.md) §2。

## 6 线程模型（四条铁律）

1. **后台一律走 `CenterThreadPool.run`**，不要 `new Thread`（`util/CenterThreadPool.java:79`；异常会被统一转成 `MsgUtil.err`）。
2. **回主线程一律 `runOnUiThread` / `CenterThreadPool.runOnUiThread`**（`:178`）。弹幕渲染、`setDisplay`、`prepare` 必须在主线程，否则 `CalledFromWrongThreadException`（事故注释 `activity/player/PlayerActivity.java:763-776`）。
3. **native 播放器只能在自己的单线程释放**：`retirePlayer`/`runAfterPlayerReleased` 投递到 `PLAYER_OPS`（`PlayerActivity.java:1448,1458,1474`）；ijk Surface 是单实例，两个 native 播放器不能同时挂同一 Surface（`PlayerActivity.java:1470-1472` 注释）。
4. **线程池没有取消机制**：页面销毁后回调仍会执行，回调里必须自查 `destroyed`/`isFinishing`（`PlayerActivity.java:3893` 之后的早退分支是范例）。

## 7 建议的阅读顺序

| 顺序 | 读什么 | 目的 |
|---|---|---|
| 1 | 本文件 | 建立整体模型与风险直觉 |
| 2 | [module-map](./module-map.md) + [dependency-map](./dependency-map.md) | 确认改动属于哪个模块/包、是不是第三方；能不能靠换/升依赖解决（多数不能） |
| 3 | [data-flow](./data-flow.md) → [risk-map](./risk-map.md) | 找到改动落在哪条链路第几步，再按"会连带崩哪里"自检 |
| 4 | [docs 索引](../README.md)、[更新日志](../更新日志.md) | 查历史决策与某版本改动原因（更新日志按版本检索，勿通读） |
| 6 | `../README.md`（docs 索引）、`../更新日志.md` | 查历史决策与某版本改动原因（更新日志不要从头读） |