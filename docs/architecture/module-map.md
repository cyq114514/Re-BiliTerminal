# 模块与包地图（module-map）

> 定位：**动手前回答两个问题**——"我要改的代码属于哪个 Gradle 模块 / 哪个包"和"这块是不是本项目自己的代码"。需要看依赖版本与构建仓库时读 [dependency-map](./dependency-map.md)；要判断风险读 [risk-map](./risk-map.md)。

规模数据均为本地 `find`/`wc` 实测（当前 HEAD）：`app` 468 个 .java / 92,653 行；清单 113 个 `<activity>`（115 处声明含 2 个 alias）、19 个业务 Fragment 类（另有 3 个基类，共 22 个声明）。

---

## 1 四个 Gradle 模块

| 模块 | 目录 | 归属 | 规模（实测） | 职责 | 修改边界 |
|---|---|---|---|---|---|
| `:app` | `app/` | **本项目**（GPL-3.0，含上游"腕上哔哩"派生代码，见 `util/SharedPreferencesUtil.java:5-7` 头注释） | 468 java / 92,653 行 | 全部业务：UI、网络、播放页、下载、设置、第三方源、主题 | 唯一可自由改的模块 |
| `:ijkplayer-java` | `ijkplayer-java/` | **第三方，原样未改** | 27 java / 4,205 行 / 36 tracked 文件 | `tv.danmaku.ijk.media.player` 的 Java API 层（`IjkMediaPlayer` 等）；**不含 JNI 源码**，so 由 `app/libs` 提供 | 视为只读依赖（`git log` 仅 Initial commit，1 个提交） |
| `:DanmakuFlameMaster` | `DanmakuFlameMaster/` | **第三方 + 本项目 fork** | 64 java / 10,334 行 / 76 tracked 文件 | `master.flame.danmaku` 弹幕引擎（`DanmakuView`/`DrawHandler`/`CacheManagingDrawTask`）+ `tv.cjump.jni.NativeBitmapFactory`（`libndkbitmap`） | 已与上游有 diff，改动需查 git 历史（见 §6） |
| `:brotlij` | `brotlij/` | **第三方，原样未改** | 3 java / 281 行 / 8 tracked 文件 | `com.netease.hearttouch.brotlij.Brotli` → `System.loadLibrary("brotli")`；被 `api/UserInfoApi`（响应解压）与 `activity/player/PlayerDanmuClientListener`（直播弹幕 WS）使用 | 只读；许可证来源【未核实】（源码无 license 头、模块无 LICENSE 文件） |

模块命名不统一（注意搜索/脚本大小写）：root project 名是 `BiliTerminal`（`settings.gradle:30`），模块名分别是 `app` / `ijkplayer-java` / `DanmakuFlameMaster` / `brotlij`（`settings.gradle:31-34`）；`brotlij` 的 namespace 是 `com.wolfcstech.brotlij` 而源码包名是 `com.netease.hearttouch.brotlij`（`brotlij/build.gradle:7`）。

## 2 依赖方向（三个 library 都是叶子，互不依赖）

```text
                    ┌──────────────────────────────┐
                    │             :app             │
                    │  468 java / 92,653 行         │
                    └──┬───────────┬───────────┬───┘
      project deps     │           │           │      （app/build.gradle:163-165）
                       ▼           ▼           ▼
              :ijkplayer-java  :DanmakuFlameMaster  :brotlij
                 无 deps 块       无 deps 块         api fileTree(libs) → 空集
                       │           │           │
                       └───────────┴───────────┘
                     共同运行时依赖 app/libs/*.so（10 个，§3）
```

- 三个 library **没有** `dependencies {}` 外部 maven 声明（`ijkplayer-java/build.gradle:8` 的 deps 块为空壳，DFM 与 brotlij 无外部依赖）。
- 仓库统一在 `settings.gradle` 声明并锁定：`dependencyResolutionManagement` + `FAIL_ON_PROJECT_REPOS`（`settings.gradle:18-19`）⇒ **模块内不得再写 `repositories {}`**（会直接构建失败）。
- app 内部依赖方向（由 import 反推）：`activity → api/model/adapter/ui/util`；`api → util/model`；`service → api/util/helper`；`source → util/api/model`；`util` 不 import `activity`（唯一例外是跳转集中入口 `util/TerminalContext.java`）。

## 3 app 内包地图

| 包 | 文件数 | 职责 | 代表类 |
|---|---|---|---|
| `activity/` 顶层 | 15 | 壳层/工具型页面：启动、菜单、崩溃页、对话框外壳、文本/图片查看、计算器伪装、外链、彩蛋 | `SplashActivity`、`MenuActivity`、`CatchActivity`、`GetIntentActivity`、`CalculatorActivity`、`DialogActivity`、`EchoCaveActivity` |
| `activity/base` | 6 | 基类（主题覆盖层、方向锁、恢复栈、DPI/圆屏、EventBus、`startActivity` 重写） | `BaseActivity`(797)、`InstanceActivity`、`RefreshListActivity`、`RefreshMainActivity`、`BaseFragment`、`RefreshListFragment` |
| `activity/*` 13 子包 | 133 | 业务页面，见 §4（与顶层 15 合计 148） | `activity/player/PlayerActivity`(6544) |
| `api/` | 50 | HTTP 请求 + JSON/Gson 解析一体，全部是静态方法；无服务接口抽象（仅 `BgmApi` 内 2 个回调接口） | `PlayerApi`(921)、`BgmApi`(1042)、`DynamicApi`、`UserInfoApi`、`ConfInfoApi` |
| `adapter/` | 57（17 顶层 + 8 子包） | RecyclerView/ViewPager 适配器 | `SettingsAdapter`、`QualitySelectorAdapter`、`ReplyAdapter`、`MenuGridAdapter` |
| `model/` | 67（65 顶层 + `bgm`/`message` 子包） | POJO / 响应模型（Parcelable + Gson/JSON 双风格） | `PlayerData`、`ApiResult`、`ApiResponse`、`SettingSection` |
| `service/` | 4 | 3 个前台 Service + 音频缓存代理 | `DownloadService`(1149)、`AudioPlayerService`(1619)、`PlaybackService`(266)、`AudioCacheProxy` |
| `source/` | 40（9 顶层 + 7 子包） | 第三方番剧源子系统：规则、订阅存储、WebView 嗅探、本地 HLS 代理、弹幕 | `SourceManager`、`SourceStore`、`SafeRegex`、`RuleLimits`、`LocalHlsProxy`、`ThirdPartyPlayback` |
| `ui/` | 21（`theme` + `widget`） | 主题（种子色/HCT 派生/递归上色）与自绘控件 | `ThemeManager`、`ThemeApplier`、`AmbientBackground`、`RotaryRecyclerView`、`HighEnergyProgressBar` |
| `util/` | 70（69 顶层 + `view` 子包） | 网络/持久化/崩溃/线程/格式化/业务跳转 | `NetWorkUtil`(871)、`CrashUploader`、`CenterThreadPool`、`SharedPreferencesUtil`、`TerminalContext`、`UpdateManager` |
| `helper/` | 4（含 `sql`） | Glide 模块、MediaSession 隔离层、教程、下载 SQLite | `MediaSessionHelper`、`CustomGlideModule`、`DownloadSqlHelper` |
| `event/` `listener/` | 2 / 3 | EventBus 事件与点击回调接口 | `SnackEvent`、`ReplyEvent` |
| 根包 | 2 | `BiliTerminal`(Application)、`ErrorCatch`(全局 UncaughtExceptionHandler) | — |

`source/` 子包职责：`rule`（远程规则安全层）、`resolve`（HTML 扫描 + WebView 嗅探）、`play`（HLS/本地代理/第三方播放编排）、`net`（URL 守卫与独立超时）、`animeko`、`danmaku`、`model`。

## 4 activity 13 子包（递归计数，共 148 个文件）

| 子包 | 文件数 | 业务域 | 代表页面 |
|---|---|---|---|
| `video` | 28（含 5 子包） | 视频/番剧主战场：推荐、热门、排行、时间线、详情、清晰度、选集、合集 | `RecommendActivity`、`BangumiActivity`、`QualityChooserActivity`、`MultiPageActivity`、`JumpToPlayerActivity` |
| `settings` | 33（含 `login` 8、`setup` 2） | 设置中心 + 二级设置页 + 登录/授权/引导 | `SettingMainActivity`、`SettingTerminalPlayerActivity`、`ThemeColorActivity`、`SettingSearchActivity`、`login/*` |
| `user` | 23（含 `favorite` 5、`info` 4） | 我的/空间/历史/稍后再看/收藏夹/编辑资料/勋章 | `MySpaceActivity`、`HistoryActivity`、`user/favorite/*` |
| `player` | 9 | 播放页及附件（手势、弹幕控制、字幕、缩放、音频页） | `PlayerActivity`(6544)、`AudioPlayerActivity`、`PlayerDanmuClientListener`、`DanmakuFeedController` |
| `search` | 7 | 搜索页 + 6 类结果 Fragment | `SearchActivity`、`SearchFragment` |
| `base` | 6 | 基类 | §3 |
| `dynamic` | 6 | 动态流/详情/话题/投票/发动态 | `DynamicActivity`、`DynamicInfoFragment`、`SendDynamicActivity` |
| `message` | 5 | 消息中心/私信/通知 | `MessageActivity`、`PrivateMsgActivity` |
| `source` | 5 | 第三方源：搜索/选片源/选集/数据源管理/导入 | `SourceSearchActivity`、`SourceMatchActivity`、`SourceDetailActivity`、`SourceListActivity`、`SourceImportActivity` |
| `reply` | 3 | 评论区/写评论/评论详情 | `ReplyFragment`、`WriteReplyActivity` |
| `live` | 3 | 直播推荐/关注/详情 | `LiveInfoActivity`、`RecommendLiveActivity` |
| `privacy` | 3 | 隐私锁 + 计算器伪装 | `PrivacyLockSettingsActivity`、`CalcSettingsActivity` |
| `article` | 2 | 专栏详情 | `OpusInfoActivity` |

`video` 的 5 个子包：`info`(5，视频/番剧详情 Fragment 主体)、`shortvideo`(5，**独立实现的短视频播放器**)、`local`(3，本地缓存视频)、`series`(2)、`collection`(1)。

## 5 页面分组（按业务域找入口）

| 业务域 | 入口页 | 支撑 API / 子系统 |
|---|---|---|
| 启动与壳 | `SplashActivity` → `MenuActivity` → 首屏 | `BiliTerminal`、`ResumePageUtil`、`TerminalContext` |
| 视频 | `RecommendActivity` / 详情页 `video/info/VideoInfoFragment` | `PlayerApi`、`VideoInfoApi`、`JumpToPlayerActivity` |
| 番剧 | `BangumiActivity` / `BangumiInfoFragment` | `BangumiApi`、`PlayerApi`（PGC v2/v1）、`BgmApi`（bgm.tv 反代） |
| 播放 | `PlayerActivity`（在线/番剧/直播/本地/第三方统一入口） | `PlayerApi`、`PlayerData`、ijkplayer、`DanmakuFlameMaster` |
| 短视频 | `ShortVideoFeedFragment` / `ShortVideoPlayerActivity` | `ShortVideoFeedApi`（**另一套播放器代码**） |
| 第三方源 | `SourceSearchActivity` → `SourceMatchActivity` → `SourceDetailActivity` | `source/**`（`SourceManager`、`MediaResolver`、`WebViewSniffer`、`LocalHlsProxy`） |
| 音频/后台播放 | `AudioPlayerActivity` + 顶栏音频胶囊 | `AudioPlayerService`、`AudioCacheProxy`、`PlaybackService` |
| 下载 | `DownloadActivity` / `video/local/DownloadListActivity` / `LocalListActivity` | `DownloadService`、`DownloadSqlHelper`、`MediaMerger` |
| 用户与社交 | `MySpaceActivity`、`user/favorite/*`、`DynamicActivity`、`PrivateMsgActivity`、`ReplyFragment` | `UserInfoApi`、`FavoriteApi`、`DynamicApi`、`PrivateMsgApi`、`ImGrpcApi` |
| 设置与外观 | `SettingMainActivity` 及其 20 个二级页 | `SettingsAdapter`（`type` 字符串分派）、`ThemeManager`、`ThemeApplier` |
| 崩溃与隐私 | `CatchActivity`（`:crash` 进程）、`PrivacyLockSettingsActivity` + `CalculatorActivity` | `ErrorCatch`、`CrashUploader`、`CrashTrace` |

## 6 本项目 vs 第三方：判定方法（三步，按顺序）

1. **看 git 历史**：`git log --oneline -- <path>` 只有 1 条（Initial commit）⇒ 原样引入，不要改。
2. **看包名/版权头**：保留第三方 org（`tv.danmaku.*`、`master.flame.*`、`com.netease.hearttouch.*`）且无本项目提交 ⇒ 原样。
3. **看命名与提交**：类名带 `Bili*`、有本项目提交、或注释里出现本项目特有概念 ⇒ **fork**，改动要同时评估上游语义。

据此得到的边界结论：

| 范围 | 归属 | 证据 |
|---|---|---|
| `app/src/main/java/**` | 本项目 | 全部本项目提交 |
| `app/libs/**/*.so` | 第三方预编译二进制，**不可重建**（仓库无 so 构建脚本，无 NDK） | `app/build.gradle:19`（`jniLibs.srcDirs = ['libs']`） |
| `ijkplayer-java/` | 第三方，未改（1 个提交） | `git log -- ijkplayer-java` = 1 |
| `brotlij/` | 第三方，未改（1 个提交） | `git log -- brotlij` = 1 |
| `DanmakuFlameMaster/` | **已 fork**：6 个提交；除 Initial commit 外，`CacheManagingDrawTask`(3 次提交)、`BaseDanmaku`、`AndroidDisplayer`、`DanmakuFilters`、`DrawHandler`、`DanmakuView` 各被改过，另有本项目类 `BiliProtobufDanmakuParser`（`63e3436` 改过）；`BiliDanmakuLoader`/`BiliDanmukuParser` 为上游自带 | `git log --oneline -- DanmakuFlameMaster` = 6 |
| `app/src/main/assets/sources/default_subscription.json` | 本项目维护的**第三方订阅数据**（63 条规则，约 170KB），首次启动 seed 进 SP | `BiliTerminal.java:231`（`SourceDefaults::seedIfNeeded`） |
| `docs/`、`site/`、`netlify/` | 本项目文档/官网/部署函数，无 Java 依赖 | — |

**app 内没有 vendored 第三方 Java 包**：未发现 `tv.danmaku`/`master.flame`/`org.brotli` 等外来命名空间；`activity/settings/login/` 里的人机验证页是 Geetest 的**自实现 WebView 壳**，SDK 依赖已在 `app/build.gradle:156-157` 注释掉。因此边界可以简化为"4 个模块 + `app/libs` 二进制"。

## 7 常见误判（写下来避免重犯）

- 把 `DanmakuFlameMaster` 当"原样上游"直接改 → 与上游 diff 会扩大，且它已被本项目打过补丁，必须按 fork 处理。
- 把 `ijkplayer-java` 当"播放器本体" → 真正的 native 在 `app/libs/libijk*.so`，改 Java 封装不会改变出流/解码行为。
- 在 `ui/widget` 里找"标准控件" → 这些是本项目自绘/圆屏适配控件（`RotaryRecyclerView` 等），不是通用库。
- 以为 `activity/video/shortvideo` 复用 `PlayerActivity` → 它是**另一套**播放器实现（`ShortVideoFeedFragment` 1572 行，自行管理 surface 与 release）。