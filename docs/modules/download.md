# 下载与缓存（DownloadService 单 worker + download.db + DASH 合成）

> 什么时候读：改下载队列/断点续传/候选重试/DASH 合成/番剧缓存/下载通知/音频本地代理，或排查"队列卡死、假取消、半截文件、通知常驻"时。
> 路径约定：Java 路径省略前缀 `app/src/main/java/com/RobinNotBad/BiliClient/`；清单指 `app/src/main/AndroidManifest.xml`。

## 先读

| 文件 | 关键位置 | 为什么先读 |
| --- | --- | --- |
| `service/DownloadService.java`（1149 行） | 静态状态 `:58-64`；`onStartCommand:147`；单 worker 守卫 `:60,160-164`；`START_STICKY:452` | 下载的全部状态在**静态字段**里（`started/percent/state/section`），UI 直接读；worker 只能有一个 |
| 同上 | 入队 `startDownloadBangumi:961`、`startDownload:994,1035`、`startDownloadBatch:1085` | 三条入队路径 + 批量；`aid+cid` 去重在插库前做 |
| 同上 | `downFile:650`、`downloadFrom:669`、完成收尾 `:396-403`、异常收口 `:440-448` | 候选逐个重试 + Range 续传 + 长度校验；成功即删 DB 行 |
| 同上 | `downloadDashPair:600-631`（`MediaMerger.mergeAv:620`） | DASH 双流：视频/音频各下一份再 remux 成 `video.mp4` |
| `helper/sql/DownloadSqlHelper.java` | `download.db` 版本 5 `:13`；建表 `:17-31`；`onUpgrade:35-65` | 列语义：`state/type/qn/download_type/audio_url/epid/season_id/season_type`；迁移只 ALTER 不 drop |
| `model/DownloadSection.java` | 字段 `:21-27`；Cursor 兜底 `:32-65` | 旧库缺列时按 `getColumnIndex==-1` 兜底，读侧必须兼容 |
| `util/MediaMerger.java` | 结果码 `:34-42`；能力边界 `:20-28` | 只 remux 不转码；**杜比 ec-3/FLAC 写不进去** |
| `util/FileUtil.java` | `getVideoDownloadPath:85-105`、`stringToFile:119` | DASH 之外所有落盘目录都走这里；`.nomedia` 与相册语义在此 |
| `service/AudioCacheProxy.java` | 上限 `:51`、`cacheDir:109`、`localUrl:131`（返回代理地址 `:134`）、`prefetch:138`、`startServer:185` | 音频本地代理（`audio_cache_v2`，80MB）——听歌不卡的关键件 |

## 允许改动

- 下载页/列表 UI、进度文案、批量删除交互（状态字符串 `none/downloading/error` 不改语义）。
- 缓存目录清理策略、`.nomedia` 开关（`save_ban_gallery`）、落盘路径设置项。
- 通知文案/进度刷新频率（ID 与 channel 不动）。
- 音频缓存的清理阈值与日志（`AudioCacheProxy.MAX_CACHE_BYTES`）——改协议不行。

## 禁止 / 慎改（红线）

1. **单 worker 保证**（`:60,160-164`）：允许多 worker = 同一条目被两个线程重复下载、互删目录；`START_STICKY` 重投递正是靠这层挡住的。
2. **`download.db` 版本与列语义**（`DownloadSqlHelper:13-31`）：v5 新增 `epid/season_id/season_type`；`onUpgrade` 必须逐列独立 `ALTER`，**绝不 drop 表**（`:51-64` 注释：用户几十条历史记录比一次结构修正重要）。降版本回滚 = 数据丢失。
3. **通知 ID 1027 / channel**（`:80-81`）：与后台播放 1028、音频 1029 共用同一通知空间（`PlaybackService:42-43`、`AudioPlayerService:87-88`），撞 ID 会互相覆盖。
4. **`START_STICKY` 与静态状态复位**（`:151-158,446-448`）：改返回值 = 进程被杀后队列不续跑；漏复位 `started` = 所有下载入口永远提示"队列已在进行中"，只能杀进程。
5. **候选顺序与 Range 语义**（`PlayerApi.buildMediaCandidates:213`、`downloadFrom:669` 内 `:693-704`）：去掉长度校验 = 半截文件当成功（本地列表可播但损坏）；忽略 `206/416` = 断点续传失效；`200` 不覆盖写 = 文件头部错乱。
6. **`MediaMerger` 结果码与能力边界**（`:34-42`）：失败必须落 `error`，不能当成功；杜比/FLAC 音轨写不进去，别承诺"全格式合成"。
7. **`AudioCacheProxy` 代理协议**（`:134` `http://127.0.0.1:port/a?cid=`）：绑定地址只能 `127.0.0.1`；改 URL 形态 = 音频播放 404/卡顿。
8. **取消语义**（`:446-448`、`onDestroy:803-810`）：取消靠 `started=false` 让循环退出；直接 `interrupt/kill` worker 会让条目永久停在 `downloading`。

## 必查回归清单（按波及面排序）

- [ ] 断点恢复：下载中杀进程 → 重进下载页 → 继续下载，从 `file.length()` 处续（日志无"从头覆盖"）。
- [ ] 候选重试：首个候选 403/超时 → 自动换下一条（日志 `候选 i/n 失败`）；全部失败落 `error` 且不锁队列。
- [ ] 多分段/P 下载（`type=video_multi` + `child`）与重名去重；重复点"下载"提示已在队列。
- [ ] DASH 合成：1080P 高码率/4K → `video.mp4` 可播；人为制造合并失败 → 提示重新下载且条目为 `error`。
- [ ] 番剧单集缓存：`video_pgc` + `epid/season_type` 落库 → 下完本地播放（配合 `anime.md`）。
- [ ] 取消/删除/清空：下载中删除 → 停服务 + 删目录；`state=error` 的条目仍可重新入队。
- [ ] 前台通知：进度刷新、成功通知（`:396,495`）、退出后通知消失（`:488`）。
- [ ] 音频缓存：命中 `audio_cache_v2`、清理后重新缓存；听歌切曲不卡。
- [ ] 通知权限：声明了 `POST_NOTIFICATIONS`（清单 `:15`），但 targetSdk 26 无需运行时申请——真机 Android 13 表现【未核实】。

## 数据流要点

1. 入队：`aid+cid` 查重 → 插 DB（`state=none`）+ 建目录 + 写 `.DOWNLOADING` 标记 → `start(-1)` → `onStartCommand` 立即 `startForeground(1027)`（`:149`）。
2. 单 worker：`WORKER_RUNNING.compareAndSet(false,true)`，重复 start/失败重试/`START_STICKY` 重投递都会被拒（`:160-164`）。
3. 取任务 `getFirst:871`：优先上次 `firstDown` 行，被删则回退全表；只跳过 `state=error`（`:887`）。
4. 现取流：播放地址不落库（几小时过期），每次下载现场取；候选来自 `PlayerApi.buildMediaCandidates`（官方 CDN 优先、PCDN 垫底）。
5. 逐候选 `downFile:650` → `downloadFrom:669`：本地已有 `file.length()` 就带 `Range: bytes=N-`；`206` 追加、`416` 视为已完整、`200` 从头覆盖；结束校验 `file.length()<total` 判失败换候选。
6. DASH：`downloadDashPair:600` 双轨下载 → `mergeAv:620` → 成功删临时双轨；失败返回 `ERR_UNKNOWN` 并提示重新下载。
7. 收尾 `:396-448`：成功通知 → 删 `.DOWNLOADING` → `deleteSection` → `refreshLocalList`；`finally` 里 `started=false`、`WORKER_RUNNING=false`、`stopSelf()`。

## 常见坑

- 1.2.4：worker 抛未捕获异常 → 线程池吞掉、`started` 仍为 true，之后所有下载入口只提示"队列已在进行中"，只能杀进程；现在所有出口都走 `try/finally` 复位。
- 1.2.4：下载页点返回曾被实现成"假取消"（直接 `finish()` 还弹失败）；取消必须走 `started=false` 语义。
- 曾每条任务重开一次 `download.db`（8 个静态方法各自 `new DownloadSqlHelper().close()`），已改成静态 helper 复用（`../更新日志.md:567-579`）。
- `MediaMerger` 注释建议"失败时保留双文件继续用"，但 `downloadDashPair` 的 `finally` 会删临时双轨（`:627-629`）并落 `error` —— 合并失败只能重下，改这块时两边要一起定。
- `.DOWNLOADING` 标记与 DB 状态必须同步：标记残留会让本地列表把未完成文件当缓存视频。

## 相关文档

- `../变更记录-番剧缓存-20261114.md`、`../变更记录-缓存听歌与网络错误-20261114.md`
- `../architecture/data-flow.md`（下载/音频缓存数据流）、`../architecture/risk-map.md`
- `../audit/收藏夹音频播放-源码审计.md`、`../更新日志.md`（1.2.3 候选+Range、1.2.4 收尾逻辑、1.3.8 番剧缓存）