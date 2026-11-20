# 关键数据流（data-flow）

> 定位：**定位"我的改动落在哪条链路的第几步"**。每条链给起点→终点文件与行号，并标出线程切换点（`⟶BG` 后台 / `⟶UI` 主线程）和跨进程点（`⟶PROC`）。行为描述未实测的标【未核实】；风险与连带影响见 [risk-map](./risk-map.md)。

图例：`⟶BG` = `CenterThreadPool.run`（或等价后台线程）；`⟶UI` = `runOnUiThread`；`⟶PROC` = 跨进程/跨 Activity 边界。

---

## 1 视频播放（在线普通视频，最高频链路）

| # | 位置 | 动作 | 线程 |
|---|---|---|---|
| 1 | 推荐/搜索/详情卡片 | 点击 → `BiliTerminal.jumpToVideo`（`BiliTerminal.java:325,329`）或 `util/TerminalContext` → Intent | UI |
| 2 | `api/PlayerApi.java:173` | `startGettingUrl(playerData)`：`FLAG_ACTIVITY_NEW_TASK` + `putExtra("data", PlayerData)` 打开中转页 | UI |
| 3 | `activity/video/JumpToPlayerActivity.java:42` | 中转页解析参数、`registerForActivityResult` 等待播放页回传 | UI |
| 4 | `api/PlayerApi.java:301`（Dash）/`:353`（Durl） | 取流：WBI 签名 URL（`:311`，签名在 `api/ConfInfoApi.java:64`）→ 解析出 `videoUrls/audioUrls/danmakuUrl/qn/progress` | ⟶BG（由中转页发起）【未核实发起线程】 |
| 5 | `api/PlayerApi.java:213` | `buildMediaCandidates`：官方 CDN 优先、PCDN 垫底，得到候选地址列表 | BG |
| 6 | `model/PlayerData.java:10` | 跨层唯一契约（Parcelable；类型常量 `:11-16`），经 Intent 传递 | UI |
| 7 | `activity/video/JumpToPlayerActivity.java:186`（`startActivity`）→ `PlayerActivity.java:641`（`onCreate`） | 进入播放页：先 `getExtras()`（`:538`）读 `url/danmaku/aid/cid/epid/...`，再初始化 UI | UI |
| 8 | `PlayerActivity.java:676,679` | `IjkMediaPlayer.loadLibrariesOnce(null)` → `new IjkMediaPlayer()`（JNI 边界，`libijk*.so`） | UI |
| 9 | `PlayerActivity.java:723` | `layout_control.postDelayed(60ms)`：**并发派发**弹幕（`downdanmu:2546`）、字幕、高能进度、互动视频 | ⟶BG |
| 10 | `PlayerActivity.java:1539` → `:1684` → `:1869` | `setDisplay()`（挂 Surface）→ `MPPrepare()`（下发播放选项）→ `prepareAsync()` → `onPrepared` | UI（native 回调回主线程） |
| 11 | `PlayerActivity.java:2244,306` | `onPrepared` 之后建立 5 个 Timer（progress/loading/online/speed/surface）+ 每 5s 进度上报（`:2232`） | BG Timer |
| 12 | `PlayerActivity.java:3827,3866,3893` | 退出路径：`onPause`（后台播放开关 → `PlaybackService.start` `:3835`）→ `onStop` → `onDestroy`（停 Timer/EventBus 反注册/`PlaybackService.stop`） | UI |

- 弹幕渲染必须回主线程：`streamDanmakuOnUi`（`:2724`）；错线程会抛 `CalledFromWrongThreadException` 并中断同批 `setDisplay`（事故注释 `:763-776`）。
- native 释放：`retirePlayer`（`:1458`）投递到单线程 `PLAYER_OPS`（`:1448`），禁止在主线程同步 `release()`。
- 短视频走的是**另一套**实现（`activity/video/shortvideo/ShortVideoFeedFragment`），不经过 `PlayerActivity`。

## 2 番剧播放与进度上报（PGC，双 API 体系）

| # | 位置 | 动作 | 线程 |
|---|---|---|---|
| 1 | `activity/video/BangumiActivity` → `activity/video/info/BangumiInfoFragment` | 追番/详情/选集入口 | UI |
| 2 | `api/PlayerApi.java:416-545` | PGC 取流：v2 `pgc/player/web/v2/playurl` 才带 `play_view_business_info`（观看进度），v1 仅兜底（注释 `:53-55`、`:80`） | ⟶BG |
| 3 | `api/PlayerApi.java:444` | 从 `result.play_view_business_info` 取 `user_status.watch_progress`（"这一集自己的进度"，不是整季） | BG |
| 4 | `model/PlayerData.java:12` | 以 `TYPE_BANGUMI` 的 `PlayerData` 进入 `JumpToPlayerActivity` → `PlayerActivity`（同 §1 第 7~11 步） | UI |
| 5 | `PlayerActivity.java:2244` | 周期上报：`reportProgressNow`，间隔 `PROGRESS_REPORT_INTERVAL_MS = 5000`（`:306`），去重（`:2232` 注释） | BG Timer |
| 6 | `PlayerActivity.java:3827,3866,3893` | 兜底上报：`onPause/onStop/onDestroy` 各一次（去重后只写一次），并 `saveLocalProgressNow`（`:1267`） | UI |
| 7 | `activity/video/JumpToPlayerActivity.java:76` | 回到中转页时判断"内置播放器已自行上报则跳过补报"（去重语义，**改上报口径前必读**） | UI |
| 8 | `BiliTerminal.java:142-148` | 本地 `episode_progress` 已于 v6 废弃，**进度只信服务端**；启动时按标记键清一次 | 主进程 |

## 3 下载 / 缓存

| # | 位置 | 动作 | 线程 |
|---|---|---|---|
| 1 | `activity/DownloadActivity`、`activity/video/local/DownloadListActivity`、播放页下载入口 | 选择清晰度/集数 | UI |
| 2 | `api/PlayerApi.java:180,190,200` | `startDownloading` / `startDownloadingAudioOnly` / `startDownloadingDash` | UI |
| 3 | `service/DownloadService.java:961,994,1035` | `startDownloadBangumi` / `startDownload` 入队：写 `download` 表（insert `:975`、`:1011`），再 `startService` | UI |
| 4 | `service/DownloadService.java:147,162` | `onStartCommand`：`WORKER_RUNNING.compareAndSet` 保证**单 worker**；`START_STICKY` 进程重启时靠静态标记恢复（`:151-162` 注释） | 主线程（Service） |
| 5 | `service/DownloadService.java:871` | worker 循环 `getFirst()` 取队首 | ⟶BG worker |
| 6 | `api/PlayerApi.java:213` | **重新取流**（旧链接数小时即过期）→ 候选地址逐个重试（每次触发重新取流） | BG |
| 7 | `service/DownloadService.java:62` | 写文件并按字节更新 `percent`（`:724,735`），通知栏进度（`FOREGROUND_ID = 1027`，`:81`） | BG → 通知 |
| 8 | `util/MediaMerger.java:75` | DASH 双流（video+audio）`mergeAv` 合成单文件 | BG |
| 9 | `service/DownloadService.java:927,936` | 删除单条 / 清空队列（`delete from download`） | UI 调用 |

缓存旁路：音频走本地 HTTP 代理 `service/AudioCacheProxy.java:187`（只绑 `127.0.0.1`），播放页用代理地址 `:134`，预取 `:138`；弹幕缓存落 `cacheDir/danmaku.xml`（`PlayerActivity.java:2743`）。

## 4 崩溃上报（**跨进程**，唯一有 `:crash` 进程的链路）

| # | 位置 | 动作 | 线程/进程 |
|---|---|---|---|
| 1 | `ErrorCatch.java:103` | `uncaughtException`：先 `ResumePageUtil.clear()`（防崩溃循环），再 `CrashTrace.log` | 崩溃线程（任意） |
| 2 | `ErrorCatch.java:118` | `CrashUploader.buildReport(...)` 组 JSON（含当前页名 `CrashUploader.java:99,109`） | 崩溃线程 |
| 3 | `ErrorCatch.java:123,127` | `saveLocal` 本地文本兜底 → `CrashFilter.decide` 决定"上传 / 仅本地" | 崩溃线程 |
| 4 | `ErrorCatch.java:132` | `CrashUploader.enqueue` 写入 `filesDir/crash_pending`（`CrashUploader.java:481`） | 崩溃线程 |
| 5 | `ErrorCatch.java:146` | `startActivity(CatchActivity)` + `FLAG_ACTIVITY_NEW_TASK`，extra 带 `stack/reportEnabled/crashFile/filterReason/trace` | UI 边界 |
| 6 | `AndroidManifest.xml:110,113` | `CatchActivity` 运行在 `:crash` 进程 | ⟶PROC |
| 7 | `BiliTerminal.java:102-106` | `:crash` 进程 `onCreate` 里 `!isMainProcess()`（`:350` 读 `/proc/self/cmdline`）→ `ErrorCatch.initCrashProcess`（`ErrorCatch.java:83`）→ 立即 `return`，不做网络/续期初始化 | :crash |
| 8 | `activity/CatchActivity.java:28,73,94` | 崩溃页渲染 + "重新上报未发出的记录" + "重启"（回调 `SplashActivity`） | :crash |
| 9 | `BiliTerminal.java:120,122`（`CrashUploader.java:210,342`） | **下次主进程启动** 2s 后补发 `flushPending` / `flushEarly` | 主进程 ⟶BG |
| 10 | `CrashUploader.java:186` → `:58` → `:50` | `tryUpload`：先走 Netlify 反代（`:58`），失败回落 Supabase REST 直连（`:50`）；成功才清 pending | BG |
| 11 | `util/NetWorkUtil.java:186` | 崩溃通道专用 TLS 回移（Conscrypt），**不动全局 OkHttp 栈** | BG |

- 为什么不复用全局 OkHttp：老设备（API≤22）需要 Conscrypt 通道；决策见 `../decisions-tls-backport.md`。
- 跨进程读取限制：SP 在多进程间不共享内存缓存，所以 trace 随 Intent 传（`ErrorCatch.java:143` 注释已说明）。

## 5 应用更新检查

| # | 位置 | 动作 | 线程 |
|---|---|---|---|
| 1 | `activity/SplashActivity.java:221,366` | 首屏之后才跑 `runStartupChecks`（不挡开屏）；离线兜底也会查一次（`:357`） | ⟶BG |
| 2 | `api/AppInfoApi.java:54` | `checkVersion(context)` | BG |
| 3 | `util/UpdateManager.java:218` | `fetchLatest()` | BG |
| 4 | `util/UpdateManager.java:76` | 主通道：`releases/latest/download/update.json`（**直连 github.com，镜像不能参与元数据校验**） | BG |
| 5 | `util/UpdateManager.java:77,274` | 兜底：GitHub Releases API（update.json 404 时）；镜像前缀仅用于**下载**（`KEY_MIRROR_PREFIX`，`:87,149`） | BG |
| 6 | `util/UpdateManager.java:380` | `isNewer` 比较 versionName（含 versionCode 兼容） | BG |
| 7 | `activity/settings/UpdateActivity.java:61` | 用户确认更新 → 下载 | UI |
| 8 | `util/UpdateManager.java:523,634` | `downloadApk`（进度回调）→ `verifyApkIntegrity`（sha256 + 签名证书指纹比对 `:669-671`） | BG |
| 9 | `util/UpdateManager.java:698,716,720` | `installApk` → `FileProvider.getUriForFile` → `ACTION_VIEW`（`application/vnd.android.package-archive`）交系统安装器 | UI |

## 6 登录态 / Cookie 刷新（所有链路的公共前置）

| # | 位置 | 动作 | 线程 |
|---|---|---|---|
| 1 | `activity/settings/login/*` | 扫码 / 密码 / 短信 / App 授权登录 | UI |
| 2 | `api/CookiesApi.java:157`（`checkCookies`）、`api/LoginApi` | 换取 / 校验 Cookie；设备 Cookie 由 `util/CookieGenerator.ensureCookies` 生成（buvid3/4/fp、bili_ticket） | ⟶BG |
| 3 | `util/SharedPreferencesUtil.java:77,79` | 落盘 SP `default`：`cookies`、`csrf`、`mid`、`access_key`、`refresh_token` | BG |
| 4 | `util/NetWorkUtil.java:762,794,735` | `refreshHeaders()` → `updateWebHeaders()` → 全量替换 `webHeaders`（volatile copy-on-write 列表） | 调用方线程 |
| 5 | `util/NetWorkUtil.java:137,571` | 每个响应经 `CookieSaveInterceptor` → `saveCookiesFromResponse` 回写 SP | **OkHttp 拦截器线程**（禁止在此发起网络） |
| 6 | `util/NetWorkUtil.java:582,649` | 账号代际校验（切号后旧响应不回写）→ `bili_jct` 轮换时同步 SP `csrf`（不同步写操作一律 `-111`） | 拦截器线程 |
| 7 | `BiliTerminal.java:236` → `util/CookieRefresher.java:183` | 常驻进程每小时检查一次续期窗口 | 主进程 Timer |
| 8 | `api/CookieRefreshApi.java:81,112` | `refreshCookie`；`-101` 视为**服务端明确否定**（才登出），网络抖动不登出 | ⟶BG |
| 9 | `util/NetWorkUtil.java:661,680`、`api/PlayerApi.java:288` | Cookie 出域边界：`isBilibiliHost`（重定向/媒体）、`isApiHost`（才允许写会话 Cookie）、`mediaHeadersFor`（可信域才带 Cookie，PCDN 不带） | — |

## 7 横切关注点（六条链共用）

| 关注点 | 事实 | 证据 |
|---|---|---|
| 后台入口 | 一律 `CenterThreadPool.run`；API≥17 走协程 `Dispatchers.IO`，否则线程池/裸线程 | `util/CenterThreadPool.java:39-70,79` |
| 回主线程 | `runOnUiThread` / `CenterThreadPool.runOnUiThread`；UI 更新前需自查 `destroyed`/`isFinishing` | `util/CenterThreadPool.java:178`、`activity/player/PlayerActivity.java:3893` |
| 无取消机制 | 页面销毁后后台回调仍会执行（线程池无 cancel），必须靠标志位自查 | `util/CenterThreadPool.java:79`【未核实：具体泄漏表现需真机】 |
| native 线程 | 播放器释放固定 `PLAYER_OPS`；音频服务固定 `AUDIO_OPS` | `activity/player/PlayerActivity.java:1448`、`service/AudioPlayerService.java:151` |
| 跨进程 | 仅 `:crash`（崩溃页）；`isMainProcess` 分流初始化 | `AndroidManifest.xml:113`、`BiliTerminal.java:350` |
| 全局提示 | 错误统一 `util/MsgUtil` → EventBus sticky `SnackEvent` | `util/MsgUtil.java:41`、`event/SnackEvent` |