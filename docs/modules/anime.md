# 番剧（B 站 PGC + bgm.tv 条目 + 番剧缓存）

> 什么时候读：改追番/索引/新番页、番剧详情与选集、按集进度、bgm.tv 条目页、番剧单集缓存，或排查"进度没更新/换集串进度/反代失败"时。
> 路径约定：Java 路径省略前缀 `app/src/main/java/com/RobinNotBad/BiliClient/`；清单指 `app/src/main/AndroidManifest.xml`。

## 先读

| 文件 | 关键位置 | 为什么先读 |
| --- | --- | --- |
| `api/PlayerApi.java` | PGC 常量 `:53-55`；`getBangumi:421`；v1/v2 分支 `:415-478`；`getEpisodeProgressMs:628`；`getLastPlayProgress:562` | **双 API 体系的核心**：只有 v2 自带 `play_view_business_info`（集级进度），v1 只能兜底出流、无集级进度 |
| `api/BangumiApi.java`（540 行） | `getFollowingList:241`、季详情 `getSeasonDetail:414`、季级进度 `getSeasonProgress:477`、分区 `:384` | B 站番剧库：追番/索引/排行/分集信息，全部 `pgc/*` 接口 |
| `activity/video/BangumiActivity.java` | 页签常量 `:43-47`、推荐开关 `:121-129`、追番加载 `:473` | 番剧主页四页签（推荐/追番/索引/新番），推荐页可被设置关闭 |
| `activity/video/info/BangumiInfoFragment.java`（850 行） | 切季 `:549-567`、上次看到定位 `:602`、播放入口 `:733-742`、缓存按钮 `:726` | 详情页主体：选集/切季/定位/发起播放/单集缓存都在这 |
| `api/BgmApi.java`（1042 行） | 反代常量 `:60-65`、凭据 `:101-107`、候选顺序 `:184-209`、模式映射 `:211-228`、热门榜 Kazumi `:696` | bgm.tv 条目/评分/封面/热门榜；境内直连被污染，主通道是 Basic 鉴权反代 |
| `service/DownloadService.java` | `startDownloadBangumi:961`、`video_pgc` 分支 `:212` | 番剧单集缓存复用下载队列，只以 `download_type=video_pgc` 区分取流接口 |
| `util/SegmentSkipController.java`、`util/SegmentSkipMode.java` | `PlayerActivity:582-587` 读取档位；设置项 `SettingTerminalPlayerActivity:108` | 片头片尾跳过的档位只读一次，播中改设置不生效 |

## 允许改动

- 追番/索引/新番/推荐的展示与筛选、分页与空态、错误提示文案。
- 番剧详情分集 UI、选集弹窗、切季交互、上次看到定位的提示文案。
- 片头片尾跳过档位与默认值（`SegmentSkipMode`，注意下标语义见 `settings.md`）。
- bgm.tv 条目页展示、封面尺寸/代理开关、评分展示。
- 番剧缓存的 UI 与状态文案（`download_type=video_pgc` 语义不动）。

## 禁止 / 慎改（红线）

1. **进度口径**：不要加"本地按集进度回写"，会与服务端打架；不要把投稿视频的 `wbi/v2 last_play_cid` 配对逻辑用于 PGC——`getEpisodeProgressMs` 对 `isPgc` 直接 `return 0`（`:703-707`）就是为了避免串集。
2. **`BGM_PROXY_AUTH` 注入链**：`app/build.gradle:63-68` → `BuildConfig` → `BgmApi.proxyAuth:101-107`。凭据缺失时反代不可用，境内番剧库接口整体失败。
3. **`BgmApi` 候选 base 顺序与常量**（`:184-209`）：反代在前、直连垫底、Kazumi 不进通用候选（`/v0/*` 需 AppID 签名，实测 401，只有热门榜免签 `:694-696`）。改顺序 = 弱网/境内超时放大。
4. **`BGM_BASE_MODE` 的取值语义**（`:186-228`）：`auto/direct/proxy`，旧值 `bgmapi/kazumi` 已映射到反代；`active_base` 只认两套，历史镜像值必须判无效（`:205-209`）。新增模式值要同时改 `toBase` 与 `isKnownBase`。
5. **PGC 的 `season_type`**：国创/电视剧等分区传错会让服务端按错误剧集类型取流（`DownloadService.java:955-958` 注释）。落库三列 `epid/season_id/season_type` 不可省。
6. **`download.db` 的 `video_pgc` 语义**：改动即历史番剧缓存条目取流错接口（见 `download.md`）。
7. **第三方源边界**：`BgmSubjectActivity:224-231` 向 `SourceMatchActivity` 传了 `EXTRA_BGM_ID`，但该字段目前只有声明与 `putExtra`（全仓 grep 无读取点），**不得把 bgmId 当第三方站内 ID 使用**；第三方源播放必须走 `ThirdPartyPlayback`（强制内置播放器）。

## 必查回归清单（按波及面排序）

- [ ] 追番 → 详情 → 播放 → 退出 → 再进：进度回到原位（v2 集级）；换另一集再回来，两集进度不串。
- [ ] 换集、切清晰度；选集弹窗跨季切换后 `seasonId/seasonType` 正确（用切季后的季）。
- [ ] 直连 vs 反代：`BGM_BASE_MODE=auto` / `direct` / `proxy`；凭据缺失时降级到直连而不是整页报错。
- [ ] 封面代理 `BGM_IMAGE_PROXY` 开/关 + `wsrv.nl` 失败回落。
- [ ] 未登录态打开追番页（提示"未登录"，不崩）；`-101` 不得被误判导致登出。
- [ ] 番剧单集缓存：缓存 → 任务落库（epid/season_type）→ 下完本地可播；多分段 durl 只下第一段的已知限制（`../变更记录-番剧缓存-20261114.md`）。
- [ ] 片头片尾跳过：各档位切换 + 播中改设置不生效（设计如此）。
- [ ] `BANGUMI_SHOW_RECOMMEND` 关闭后页签退化（`SharedPreferencesUtil:13`、`BangumiActivity:121-129`）。

## 数据流要点

1. 详情页 `playEpisode:733` 组 `PlayerData`（`seasonId/seasonType` 用**当前展示季**，页内切季后与 `info.season_id` 不同）→ 跳转页。
2. 跳转页 `PlayerApi.getBangumi:421`：先 `PGC_PLAYURL_V2`（`:54`），`result.video_info` 出流、`result.play_view_business_info.user_status.watch_progress` 出集级进度。
3. v2 **响应解析失败/取不到 durl** 才回退 v1（`:468-478`）；**网络层失败不回退**（v2 已用尽重试，`:463-465`）。
4. `getEpisodeProgressMs:628` 判定"这集看过"必须 `last_ep_id == 本集 epid`，否则只是季级残留（`:609-628` 注释）。
5. 播放中周期上报走 `HistoryApi.reportHistoryPgc:126`（epid/sid/sub_type），退出兜底在 `PlayerActivity:2244`。
6. 本地进度已废弃：旧 SP `episode_progress` 在启动时清一次（`BiliTerminal.java:142-148`），**按集进度只信服务端**。

## 常见坑

- v1 回退**本轮拿不到集级进度**（`PlayerApi:418-419`），别把"进度没更新"直接判成上报失败。
- v2 网络异常快速失败是有意设计：再试 v1 只会把起播等待翻倍（`:463-465`）。
- 季级 `user/status` 的 `progress` 是"本季最近一条"，不能当"本集进度"；`last_ep_id` 必须成对判定。
- 上报 `progress<=0` 有害（会覆盖本季记录），只有 `-1`（看完）例外放行（`HistoryApi:126-140`）。
- 反代三件套必须挂**网络拦截器**：应用拦截器只跑第一跳，302 后的第二跳收不到头（`BgmApi:155-171`）。

## 相关文档

- `../变更记录-番剧缓存-20261114.md`（番剧缓存设计与回滚边界）
- `../audit/番剧中心-升级审计-20261106.md`、`../audit/番剧分区-源码审计.md`
- `../audit/第三方播放源-移植方案-20261106.md`、`../architecture/data-flow.md`（进度数据流）
- `../更新日志.md`（1.3.5 反代、1.3.8 番剧缓存、1.2.9 四页签）