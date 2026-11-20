# 风险地图（risk-map）

> 定位：**改动前的"会崩哪里"清单**。先看 §1 找自己所在的模块，再看 §2/§3 确认是否踩到全局状态与特殊机制，最后用 §4 的条目做"连带影响"自检。链路细节读 [data-flow](./data-flow.md)。

## 1 高风险模块排序（从上到下 = 从最危险到次危险）

| 级别 | 区域 | 规模 | 为什么排这里 | 关键证据 |
|---|---|---|---|---|
| **极高** | 播放器 / 媒体 | `PlayerActivity` 6,544 行 + 播放页包 9 类 | 一个页面同时承担在线/番剧/直播/本地/第三方/短视频(入口)场景；持有 ijk native 实例、5 个 Timer、动态广播、弹幕引擎、MediaSession、直播 WebSocket；native 释放线程错即 ANR/黑屏/泄漏/`SIGSEGV` | `activity/player/PlayerActivity.java:153,676,1448,3893` |
| **高** | 网络 / 全局状态 | `NetWorkUtil` 871 行 + `api/` 50 类 | 全局 OkHttp 单例、请求头表、Cookie 写回、重定向白名单、WBI 签名——改错一处全站请求受影响 | `util/NetWorkUtil.java:63,120,565,735`、`api/ConfInfoApi.java:64` |
| **高** | 下载 / 音频后台 | `DownloadService` 1,149 行、`AudioPlayerService` 1,619 行 | 静态进度状态 + 单 worker + `START_STICKY` 恢复 + 前台服务通知 ID；native stop/release 在专用线程 | `service/DownloadService.java:58-64,147,162`、`service/AudioPlayerService.java:129,151` |
| **高** | 第三方播放源 | `source/` 40 文件 6,703 行 | WebView 嗅探 + 不可信远程规则 + 本地 `127.0.0.1` HTTP 服务，安全上限是刻意设的 | `source/rule/RuleLimits.java:15-27`、`source/play/LocalHlsProxy.java:187` |
| **高** | 启动 / 隐私锁 / 崩溃页 | `BiliTerminal`、`SplashActivity`、`ErrorCatch`、`CatchActivity`、`activity/privacy/*` | 多进程、activity-alias 伪装、开屏 reentry、恢复链；错了表现为"闪退/图标消失/回不到页"，且难复现 | `BiliTerminal.java:102-106,350`、`SplashActivity.java:66,140`、`activity/privacy/PrivacyLockSettingsActivity.java:280` |
| **中高** | 设置 / 持久化 / 主题 | `activity/settings` 33 类、`SharedPreferencesUtil`、`download.db`、`ThemeManager` | 用户数据兼容（SP key 类型、DB 版本）+ 反射打开页 + 主题全局上色 | `adapter/SettingsAdapter.java:450`、`helper/sql/DownloadSqlHelper.java:13`、`ui/theme/ThemeManager.java:43` |
| **中** | 列表/详情/搜索等普通页面 | 其余 `activity/*` | 影响面主要是单页；但共享 `BaseActivity` 契约（主题/边距/EventBus/恢复栈） | `activity/base/BaseActivity.java:61,584,739` |
| **低** | 第三方 library 模块 | ijk 27 / DFM 64 / brotlij 3 类 | 原则上只读；DFM 已 fork（改了要评估上游语义） | 见 [module-map](./module-map.md) §6 |

## 2 全局可变状态清单（谁在读，改了就一起受影响）

| 状态 | 位置 | 读/写者 | 连带风险 |
|---|---|---|---|
| 静态 `Context` | `BiliTerminal.java:47` | 几乎所有 api/util（例 `PlayerApi.java:174`） | 赋值时机一变 → 启动期 NPE；`:crash` 进程也会赋值一次 |
| 静态 `SharedPreferences` | `util/SharedPreferencesUtil.java:92` | 全项目 SP 读写 | 读取方法有空保护（`:104` 类型兜底），但**在 static 初始化器里读**会踩初始化顺序 |
| 全局 OkHttp 单例 + `webHeaders` | `util/NetWorkUtil.java:63,735` | 全部 api | 改拦截器链/头 → Cookie 出域、重定向白名单、WBI 全部连带；`refreshHeaders`(`:762`) 与 `updateWebHeaders`(`:794`) 分工不可混（拦截器线程只能后者） |
| 账号代际 | `util/NetWorkUtil.java:565` | 切号（`AccountManager`） | 不递增 → 切号后旧响应回写 Cookie（`:582` 有校验） |
| EventBus 全局总线 | `activity/base/BaseActivity.java:584,616` | 全页面 + `util/MsgUtil`（`:41`） | 事件类型/注册时机改动 = 全局提示与评论回传失效 |
| 主题静态单例 | `ui/theme/ThemeManager.java:37,43,66-73` | 全部 View（`ThemeApplier.apply` `ui/theme/ThemeApplier.java:61`） | 改派生逻辑 → 全应用配色/高亮卡片变化；`DEFAULT_*` 常量必须逐字不变 |
| 栈顶 `InstanceActivity`（弱引用） | `BiliTerminal.java:77,287` | 启动弹窗宿主 | 换实现 → 免责声明/更新弹窗不弹或被压 |
| 首屏回调列表 | `BiliTerminal.java:73,182-184,283` | Splash / 弹窗 | 列表语义被改（后注册挤掉前面）→ 启动更新检查丢失 |
| 第三方源单例 | `source/SourceManager.java:30` | 源页 + 播放页 | 缓存不清 → 换源/导入后仍旧列表 |
| 下载静态进度 | `service/DownloadService.java:58-64` | 下载 UI + 通知 | 改语义 → UI 不刷新/队列卡死（`START_STICKY` 恢复依赖它 `:151-162`） |
| 音频监听器列表 `sListeners` | `service/AudioPlayerService.java:129,137,141` | `AudioPlayerActivity`、顶栏胶囊（`BaseActivity.java:277`） | 漏移除 = Activity 泄漏 |
| `PLAYER_OPS` / `AUDIO_OPS` 单线程 | `activity/player/PlayerActivity.java:1448`、`service/AudioPlayerService.java:151` | native release/stop | 线程模型改动 → native 崩溃/ANR |
| 本地代理单例 | `source/play/LocalHlsProxy.java:86`（ServerSocket） | 第三方 HLS 播放 | 端口/会话不清理 → 代理泄漏、后续播放 404 |

## 3 特殊机制清单（每个都有"踩了就很难查"的历史）

| 机制 | 位置 | 为什么危险 |
|---|---|---|
| JNI：ijk 加载/释放 | `PlayerActivity.java:676,679,1448,1458` + `app/libs/libijk*.so` | 顺序/线程错 = native 崩溃、黑屏、BufferQueue 泄漏；so 无法从源码重建 |
| JNI：brotli / ndkbitmap | `app/libs/*/libbrotli.so`、`libndkbitmap.so` | 解压链路与弹幕缓存绘制；替换 so 无兼容保证 |
| **EventBus 反射 + API21 类型隔离** | `helper/MediaSessionHelper.java:22`（原因注释 `:11-20`） | `BaseActivity` 对子类 `getMethods()` 反射注册；把 API21+ 类型（如 `MediaSession`）写进 Activity 签名，会让 Android 4.4/5.0 在注册时抛 `NoClassDefFoundError` → "点播放就闪退" |
| 反射打开设置页 | `adapter/SettingsAdapter.java:450`、`activity/settings/SettingSearchActivity.java:184` | 设置项 `id` 被当类名 `Class.forName`；改包名/类名 = 静默失效（只弹"无法打开"） |
| 多进程 | `AndroidManifest.xml:110,113` + `BiliTerminal.java:102-106,350` | 新初始化若不加 `isMainProcess` 判断 → `:crash` 进程重复初始化/重复上报，崩溃页更不稳定 |
| activity-alias 伪装 | `AndroidManifest.xml:670-695`、`activity/privacy/PrivacyLockSettingsActivity.java:280,303-312` | alias 由运行时启停，顺序错（先关后开）→ 桌面无图标；名字解析不能用 `getPackageName()` 拼（namespace 与 applicationId 不同） |
| AsyncLayoutInflater（自研） | `util/AsyncLayoutInflaterX.java:30,54,131` | API<23 强制主线程 inflate；后台 inflate 与主题/边距/selector 竞态，注释记录过平台 NPE |
| 动态注册广播 | `PlayerActivity.java:3786`（耳机拔出）等；`util/BatteryLowAlert.java` | 必须成对 unregister；早退分支易漏 |
| 5 个 Timer + WebSocket | `PlayerActivity.java:3893` 起的 `onDestroy`（含 `cancelAllTimers`） | 漏取消 = native + Timer 泄漏、退出后仍操作 View；直播弹幕监听必须显式 destroy |
| 冷启动恢复链 | `util/ResumePageUtil` + `BaseActivity.java:102-103,607` + `BiliTerminal.java:165` | 恢复栈内容是 Intent 列表，页面 extra 变化即恢复失败（有 try 兜底）；默认关闭（`RESUME_PAGE_ENABLE`，`SharedPreferencesUtil.java:47`） |
| 本地 HTTP 代理 | `source/play/LocalHlsProxy.java`（只绑 `127.0.0.1`）、`service/AudioCacheProxy.java:187` | 绑定地址/端口一改即安全边界变化；音频播放"不卡"依赖它 |
| 无线程取消的线程池 | `util/CenterThreadPool.java:79` | 页面销毁后回调仍执行，必须自查 `destroyed`/`isFinishing` |

## 4 「改这里容易连带崩哪里」（按影响面排序，18 条）

| # | 位置 | 改动 | 后果 |
|---|---|---|---|
| 1 | `activity/player/PlayerActivity.java:1448` | 把 native release 改回主线程 / 改用 `reset()` | `stop/release` 阻塞主线程 → 退出播放页/切清晰度 ANR；后续 `setDisplay` 黑屏；短视频 `activity/video/shortvideo/ShortVideoFeedFragment` 的同类逻辑必须同步 |
| 2 | `activity/player/PlayerActivity.java:676` | 改 `loadLibrariesOnce` 时机 / 替换 so / 删 ABI | so 缺失或加载失败 → `SIGSEGV`，Java 层捕不到（`ErrorCatch`/`PlayerSentinel` 只能事后补报） |
| 3 | `activity/player/PlayerActivity.java:3205-3206` | 在后台线程调 `enableDanmakuDrawingCache`/`prepare`，或提前 prepare | `CalledFromWrongThreadException`；异常会中断同一 lambda 里的 `setDisplay`（真实事故注释 `:763-776`） |
| 4 | `util/NetWorkUtil.java:120`（重定向白名单） | 放宽 host 校验 / 允许非 https | 带 Cookie 的请求可被任意域收取；收紧则 `b23.tv` 短链、Opus 抓取失败 |
| 5 | `util/NetWorkUtil.java:649`（csrf 同步） | 去掉或改键 | 所有写操作返回 `-111`；`csrf` 与 Cookie 不一致导致随机失败 |
| 6 | `api/ConfInfoApi.java:64,70-81`（WBI 签名/mixin 缓存） | 改签名参数或缓存 TTL | 全部 `wbi/*` 接口 `-403`，表现为"推荐/搜索/取流全空" |
| 7 | `helper/MediaSessionHelper.java`（隔离层） | 把 MediaSession 搬回 `PlayerActivity` | Android 4.4/5.0 上点播放闪退（EventBus 反射 `NoClassDefFoundError`） |
| 8 | `adapter/SettingsAdapter.java:450` / `activity/settings/SettingSearchActivity.java:184` | 改设置项 `id` 字符串 / 重命名页面类 | 设置页与设置搜索无法打开对应页（静默，只弹提示） |
| 9 | `BiliTerminal.java:350`（`isMainProcess`） | 删除或改判断 | `:crash` 进程执行完整初始化（重复上报、Cookie 续期、登录逻辑），崩溃页反而更不稳 |
| 10 | `BiliTerminal.java:165`（Activity 计数） | 改计数规则 | 正常退出不清恢复栈（下次冷启动回到旧页），或误清导致"恢复失效" |
| 11 | `activity/SplashActivity.java:140`（隐私锁闸门）+ `:66`（reentry） | 调整闸门顺序 / 去掉 `unlockVerified` | 解锁死循环（注释记录 2026-10-31 真机复现 5 次），或桌面图标二次启动压栈、用户"回到推荐页" |
| 12 | `service/DownloadService.java:60,162`（单 worker） | 允许多 worker | 同一 entry 重复下载、互删目录；`START_STICKY` 恢复时静态标记错乱 |
| 13 | `service/AudioPlayerService.java:129,151` | 监听器改强引用 / 改线程模型 | `AudioPlayerActivity` 泄漏、切歌卡顿、native stop 阻塞主线程 |
| 14 | `source/rule/RuleLimits.java:15-27`、`source/rule/SafeRegex.java:23`、`source/net/UrlGuard.java:17` | 放宽上限 / 放开内网 | 不可信订阅可让内存/主线程爆掉；收紧则正常源解析失败 |
| 15 | `service/PlaybackService.java:72` + `activity/player/PlayerActivity.java:3835`（后台播放） | 语义改错 | 暂停态也起前台服务（通知常驻），或 `onResume` 没 `PlaybackService.stop` 导致通知残留 |
| 16 | `activity/player/PlayerActivity.java:538`（`getExtras` 键名）与 `model/PlayerData.java:56`（`extraHeaders` 编码） | 改 extra 键名/序列化字段 | 同时打断 `JumpToPlayerActivity`、番剧页、直播/本地入口、第三方源四条链路 |
| 17 | `helper/sql/DownloadSqlHelper.java:13,18`（`download.db` v5 + 列语义） | 改版本号/列名/语义 | 用户下载队列丢失或升级后崩溃（迁移必须逐列 `ALTER`，见 `:35`） |
| 18 | `ui/theme/ThemeManager.java:43,46-52`（`DEFAULT_*` 常量）与 `ui/theme/ThemeApplier.java:61` | 改默认色/派生逻辑 | 全应用配色变化（含"粉色快路径"），视觉回归面 = 所有页面 |

## 5 改动前自检（30 秒版）

1. 我改的文件属于 `app`（本项目）还是三个 library / `app/libs`（第三方）？
2. 是否碰到 §2 的全局状态、§3 的特殊机制？若碰到，先写清楚"谁在读"。
3. 改动是否落在 §4 的 18 条里？是则按"后果"列准备回归项，并注明无法验证的部分。
4. 是否改了用户可见的持久化（SP key / DB / 主题默认值）？是则必须考虑旧数据兼容。
5. 收尾：本机只能做 Debug 构建与静态检查，Release/真机/仪器化一律写 `NOT RUN`，不得标 PASS。

## 6 必查回归速查（按改动区域，真机/仪器化一律记 NOT RUN）

| 改动区域 | 必查回归项 |
|---|---|
| 播放器 / 媒体 | 6 条播放路径（在线/番剧/直播/本地/第三方/短视频入口）、切 P/切清晰度/换源、后台播放+通知、熄屏/来电/耳机拔出、弹幕（在线/本地/第三方/Feed）、进度上报（普通/番剧）、退出后无 native 泄漏与 `PlayerSentinel` 误报 |
| 番剧 | 追番→详情→播放→退出进度、换集/换清晰度、直连 vs 反代（`BGM_BASE_MODE`）、封面代理、无登录态追番 |
| 网络 / API | 未登录/已登录/掉登录（`-101`）、弱网重试与 `text/html` 软失败、`b23.tv` 短链、PCDN 流不带 Cookie、API≤22 TLS、日志脱敏 |
| 设置 / 持久化 | 旧版本升级不丢数据（DB 迁移）、跨版本 SP 类型兼容、设置搜索能打开新增页、圆屏/DPI 与新旧 UI 双开关 |
| 下载 / 缓存 / 音频 | 队列断点恢复（`START_STICKY`）、候选地址重试、DASH 合成、番剧下载（epid）、音频缓存命中/清理、Android 13 通知权限 |
| 第三方源 | 内置订阅（63 条）搜索/选集、HTML 直扫 vs WebView 嗅探、HLS 走本地代理、失败切换线路、缺凭据时弹幕自动关闭 |
| 启动 / 崩溃 / 隐私锁 | 主动崩溃→崩溃页→上报闭环、崩溃页自身崩溃不循环、伪装/取消伪装后桌面图标、忘记密码恢复通道、冷启动恢复链 |

本文档为静态梳理，未执行上述回归：全部 **NOT RUN**；当前可获得的验证信号只有 Debug 编译（`./gradlew :app:assembleDebug`）与 CI 构建。
