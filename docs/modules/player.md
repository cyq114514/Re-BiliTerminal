# 播放器与媒体（6 条播放路径 + native 生命周期）

> 什么时候读：改 `PlayerActivity` / `JumpToPlayerActivity` / 弹幕 / 字幕 / 直播 / 后台播放 / 进度上报，或排查黑屏、退出卡死、native 泄漏时。
> 路径约定：Java 路径省略前缀 `app/src/main/java/com/RobinNotBad/BiliClient/`；清单指 `app/src/main/AndroidManifest.xml`。

## 先读

| 文件 | 关键位置 | 为什么先读 |
| --- | --- | --- |
| `activity/player/PlayerActivity.java`（6,544 行） | `onCreate:641` `getExtras:538` `MPPrepare:1684` `onPrepared:1869` `onPause:3827` `onStop:3866` `onDestroy:3893` | 单页承载全部内置播放的主体。**定向阅读法**：只读这 7 个方法 + 下一行的线程段，不要从头读 |
| 同上 | `PLAYER_OPS:1448` `retirePlayer:1458` `runAfterPlayerReleased:1474` `ijkSurface:168` | native release 线程模型与 Surface 单实例假设，播放页最高风险处 |
| `api/PlayerApi.java` | `startGettingUrl:173` `jumpToPlayer:762` `getVideo:353` `getVideoDash:309` `getBangumi:421` | 全部路径的统一入口；`jumpToPlayer` 按 SP `player` 二分内置/外部播放器 |
| `activity/video/JumpToPlayerActivity.java` | `launcher:41` `requestVideo:137` `jump:166` | 取流中转页：`isBangumi()` 走 PGC，否则投稿视频；退出补报去重在 `:68-78` |
| `model/PlayerData.java` | 类型枚举 `:11-16`；`durationMs:29`；`extraHeaders:56` | 跨层唯一契约（Parcelable），字段变动=四条链路同时抖动 |
| `service/PlaybackService.java` | `start:72` `stop:80`、通知 ID 1028 `:43` | 后台保活+通知遥控；回调直接调 `PlayerActivity.serviceTogglePlay:3750` |
| `activity/video/shortvideo/ShortVideoFeedFragment.java` | `dropPlayer:461` `applyPlayerOptions:419` `onDestroyView:1529` | 短视频是**另一套播放器实现**（TextureView + 单实例复用），改核心要改两处 |
| `helper/MediaSessionHelper.java` | 类注释 `:11-21` | API21+ 类型隔离层，解释了"点播放就闪退"的历史根因 |

## 允许改动

- 播放页内部 UI、手势、缩放、字幕渲染、清晰度选择弹层、错误提示文案。
- 弹幕渲染参数（字号/速度/透明度/最大行数/同屏上限）与屏蔽词逻辑（`PlayerActivity:3121-3206` 一带）。
- 新增播放器设置项（key 表范式：`activity/settings/SettingTerminalPlayerActivity.java:43`，同时守 `settings.md` 的 SP 规则）。
- 后台播放/通知文案、`PlayerSentinel` 阶段名（`:643,660,675,1863,1890`）。
- 进度上报节流常量与日志（`PROGRESS_REPORT_INTERVAL_MS:306`），前提是不改去重语义。

## 禁止 / 慎改（红线）

1. **`PLAYER_OPS` 线程模型**（`:1448`）：改回主线程 `release()` = 退出/切清晰度 ANR（`:1442-1447` 注释写明这是历史 ANR 直接来源）；改多线程 = 新实例可能先于旧 release 建好，同一 `ijkSurface` 双挂 → 黑屏/泄漏。
2. **`ijkSurface` 单实例**（`:168,1472,1600`）：`setDisplay` 必须"先换新再放旧"，顺序反了播放器会持有已释放 Surface。
3. **`getExtras` 的 extra 键名**（`:538-640`）：`url/danmaku/title/aid/cid/mid/epid/seasonId/seasonType/durationMs/bvid/headers/third_party/third_danmaku_*/live_mode/progress/clipInfoList`，改名会同时打断跳转页、番剧、直播、第三方源四条链路。
4. **弹幕必须在主线程渲染**（`:763-785`、`streamDanmakuOnUi:2724`）：后台线程调 `streamDanmaku/prepare` 抛 `CalledFromWrongThreadException`，并中断同一 lambda 后面的 `setDisplay`，表现为"停在装填弹幕中 + 进度 00:00/00:00"（本地缓存弹幕分支曾如此）。
5. **MediaSession 不得搬回 PlayerActivity**：API21 类型进入 Activity 方法签名后，`BaseActivity:584` 的 EventBus `getMethods()` 反射在 4.4/5.0 抛 `NoClassDefFoundError` → 点播放闪退。
6. **进度上报口径**（`maybeReportProgress:2229`、`reportProgressNow:2244`、跳转页去重 `JumpToPlayerActivity:68-78`）：重复补报会把"看完 -1"冲成"看到最后一秒"；去掉去重会出现同秒两条参数相同的上报（真机日志已见）。
7. **后台播放语义**（`:3831-3835,3848`）：暂停态也起前台服务 = 通知常驻；`onResume` 不 `stop` = 通知残留。
8. **`loadLibrariesOnce:676` 与 `app/libs/*.so`**：so 不可重建，加载失败是 `SIGSEGV`，Java 层捕不到。
9. **媒体候选与请求头**：`buildMediaCandidates:213` 的 CDN 优先级、`mediaHeadersFor:288` 的 Cookie 出域规则，改错等于把 SESSDATA 发给第三方 PCDN（见 `network.md`）。

## 必查回归清单（按波及面排序）

- [ ] 6 条路径各播一次：在线 / 番剧 / 直播 / 本地缓存 / 第三方源 / 短视频。
- [ ] 切清晰度、切 P、换源；连续快速切 3 次（`switchToken:3890` 竞态守卫仍生效）。
- [ ] 后台播放 + 通知：开 `player_background` → Home → 通知可暂停/停止 → 回前台通知消失。
- [ ] 熄屏 / 来电 / 耳机拔出（`becomingNoisyReceiver:429`）；切后台再回来不黑屏（`surfaceLostWhileBackground:215` + `onResume:3843` 内 `:3851-3856`）。
- [ ] 弹幕四路：在线 protobuf / 本地 XML / 第三方弹弹play / Feed 模式；长按加速后弹幕不消失。
- [ ] 进度：普通视频退出、切后台、看完（-1）；番剧心跳；经跳转页退出不重复上报。
- [ ] 退出后无 native / 5 个 Timer（`:191`）泄漏；`PlayerSentinel` 不残留"异常退出"。
- [ ] `-PemuArm64` 只用于 TLS 仪器化测试：该模式追加 `arm64-v8a`，而 ijk 无 arm64 so，播放相关必崩（`app/build.gradle:111-116`）。

## 数据流要点（6 条路径）

1. **在线投稿**：`VideoInfoFragment:795` → `startGettingUrl:173` → 跳转页 `getVideo:353` → `jumpToPlayer:762` → `PlayerActivity`（SP `player=terminalPlayer` 才内置，否则交给外部播放器）。
2. **番剧**：`BangumiInfoFragment:735` → 跳转页 `getBangumi:421`（PGC v2/v1，见 `anime.md`）→ 同上。
3. **直播**：`LiveInfoActivity:171` 取好地址后直接 `jumpToPlayer`，带 `live_mode`；弹幕走 WebSocket。
4. **本地**：`LocalListActivity:296` / `LocalPageChooseActivity:85` / `LocalVideoAdapter:105` → `jumpToPlayer`。
5. **第三方源**：`SourceDetailActivity:305` → `ThirdPartyPlayback.start` → `ThirdPartyPlayback:290` 调 `jumpToPlayer(data, true)` **强制内置**（外部播放器拿不到 Referer/UA）。
6. **短视频**：菜单 `MenuActivity:73` → `ShortVideoPlayerActivity` → `ShortVideoFeedFragment`，**不经过** `PlayerActivity`。
音频另属 `AudioPlayerService` + `AudioPlayerActivity` 体系（通知 1029），不在本页。

## 常见坑

- 切后台回来黑屏（1.2.3fix）：`surfaceChanged` 补挂载 + 暂停态 `seek(±1ms)` 强制重解一帧，与 `onResume` 的 250ms 恢复是两条互补路径。
- `onPrepared` 迟到（1.2.9）：必须按 `inBackground:225` 拦后台起播，否则退后台瞬间出声。
- 弹幕"整屏消失"/长按加速后反复消失（1.3.5）：已改成按播放位置投放，不要再引入"按墙钟推进"的假设。
- 顶栏渐隐黑不作用于播放页：`BaseActivity.setRound` 只挂 BaseActivity 子类，`PlayerActivity:153` 直接继承 `Activity`（`../更新日志.md:124`）。

## 相关文档

- `../architecture/risk-map.md`（高风险调用链全集）、`../architecture/data-flow.md`（播放链路时序）
- `../audit/弹幕消失-排查报告-20261105.md`、`../更新日志.md`（1.2.3 / 1.2.4 / 1.3.5 / 1.3.8）
- `../../app/AGENTS.md` §2（播放页强制约束）、`../development/commands.md`（构建与仪器化命令）