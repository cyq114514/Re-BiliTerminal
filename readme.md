<div align="center">

# Re：哔哩终端

哔哩终端（BiliClient）的**非官方**二次开发分支 · 轻量第三方 B 站 Android 客户端

当前版本 **1.0.2-fix1**（`versionCode 20260925`）· 基于哔哩终端 2.9.8 · 许可 GPL-3.0

[下载最新版](https://github.com/cyq114514/Re-BiliTerminal/releases/latest)　|　[更新日志](#更新日志)　|　[Vibe Coding 声明](#vibe-coding-声明)

</div>

---

> [!WARNING]
> ## Vibe Coding 声明
>
> **本分支是 vibe coding 产物。请先读完这一段，再决定是否使用、fork 或参考它。**
>
> - 本项目（Re：哔哩终端）自 1.0.0 起的**绝大部分改动，由 AI 编码助手（大语言模型）生成**，
>   人工主要负责「提需求、审阅结果、真机验证、写文案与做决策」，而非逐行手写。
> - 因此代码里可能存在 AI 生成代码的典型特征：**注释与实现不完全对应、冗余分支、过度防御、
>   命名不统一、局部实现风格与上游不一致**。这些不必然造成功能缺陷，但确实影响可读性。
> - **不要把本分支的代码当作工程规范或学习范本。** 想要风格统一、人工编写的哔哩终端，请回到上游项目。
> - 功能改动会尽量在提交信息里写明**根因与修法**，并附真机验证结论；发现问题欢迎直接提 Issue。
> - 如果你介意 AI 参与编码，**请不要使用、不要 fork、不要参考本分支**。

---

## 这是什么

「Re：哔哩终端」是基于 **哔哩终端（BiliClient，原项目已停更归档）** 的**非官方分支**，
面向**低配手机 / 手表 / 老设备**。

相对上游，本分支主要做了两件事：

1. **品牌与安装隔离** —— 应用更名为「Re：哔哩终端」，包名改为 `com.RobinNotBad.BiliClient.re`，
   可与原版「哔哩终端」**同时安装、互不覆盖**。
2. **修番剧相关的观看进度问题** —— 这也是本分支存在的主要理由。

## 更新日志

| 版本 | 主要内容 |
| --- | --- |
| 1.0.0 | 修复番剧播放进度无法保存；历史记录支持显示番剧；修复从历史处续播 |
| 1.0.2 | 播放中自动保存进度（意外退出最多丢 15 秒）；弹幕缓存；历史记录点番剧直达续播；稍后再看显示进度 |
| **1.0.2-fix1** | 修复番剧断点续播**上报静默失效**；修复**跳转后弹幕卡死 / 退出播放后整个应用卡死** |

### 1.0.2-fix1 具体修了什么

**观看进度上报（番剧续播进度写不进服务端）**

- 上报凭证不再读本地快照 `csrf` / `mid`，改为从实时 Cookie（`bili_jct` / `DedeUserID`）派生。
  原实现下 Cookie 轮换后两者会错位，导致**所有 POST 返回 `-111`，而 GET 一切正常** ——
  表现为「只有观看记录上报静默失效」，且同一份代码在不同设备/登录时机表现不同。
- 心跳接口的 `start_ts` 夹到 `>= 0`，避免设备时钟偏慢时被服务端判 `-400`。
- 上报前若 `mid` 为 0，用实时 Cookie 补一次解析，避免已登录却被判未登录。
- 番剧续播进度在 `x/player/wbi/v2` 取不到时，兜底走观看记录列表接口。
- WBI 密钥取成功后才落 `last_wbi`，缓存为空强制刷新，避免一次取密钥失败污染当天所有 WBI 请求。

**跳转后弹幕卡死 / 退出播放后整个应用卡死**

- 弹幕绘制线程不再轮询 `ijkPlayer.getCurrentPosition()`。该回调跑在弹幕同步/绘制线程上，
  而它是会取播放器原生锁的 JNI 调用 —— 既是「弹幕时间轴被旧位置拽住」的直接原因，
  也是绘制线程卡住后主线程 `release()` 内 `join()` 无限等待、**整个应用卡死**的共因。
- 退出链路（`finish` / `onPause` / `onStop` / `onDestroy`）与 MediaSession 状态更新
  不再在主线程调用 `getCurrentPosition()`。
- 弹幕未 `prepared` 时的 seek 请求会暂存、prepared 后补做；新增带观察窗与冷却的弹幕时间轴校正。
- DanmakuFlameMaster 的 `Thread.join` 加 2s 超时，避免主线程被无限阻塞。
- 所有 `TimerTask` 加异常兜底（TimerTask 抛未捕获异常会永久终止整个 Timer，
  导致进度条与进度上报一起静默失效）。

## 下载与安装

从 [Releases](https://github.com/cyq114514/Re-BiliTerminal/releases) 下载 `Re-BiliTerminal-<版本>-release.apk`。

| 项目 | 说明 |
| --- | --- |
| 系统要求 | **Android 4.0.4 及以上**（`minSdk 14`，`targetSdk 26`） |
| ABI | `armeabi-v7a`、`x86`（**不含 arm64-v8a**，与上游一致；arm64 设备走 32 位兼容模式） |
| 签名 | 正式证书。同签名版本**直接覆盖安装即可**，无需卸载、不丢登录态 |
| 共存 | 若设备上同时装有原版「哔哩终端」，两者会并存，属正常现象 |

> 本软件永久免费开源，不收集账号与隐私信息。**若你在任何平台付费下载到它，那你被坑了。**

## 构建

```bash
git clone https://github.com/cyq114514/Re-BiliTerminal.git
cd Re-BiliTerminal
# 需要 JDK 17+ 与 Android SDK（compileSdk 33）
./gradlew :app:assembleDebug     # 调试包
./gradlew :app:assembleRelease   # 正式包（需自行配置签名）
```

Release 签名通过项目根目录的 `local.properties` 读取（该文件已在 `.gitignore` 中）：

```properties
sdk.dir=/path/to/Android/sdk
KEY_PATH=/path/to/your.jks
KEY_PASSWORD=******
ALIAS_NAME=******
ALIAS_PASSWORD=******
```

> **`KEY_PATH` 必须是纯 ASCII 路径。** `local.properties` 会被 `Properties.load()`
> 按 ISO-8859-1 解析，路径里一旦含中文就会读成乱码，导致 `validateSigningRelease`
> 报 `keystore file not found`。

## 技术栈

- **语言 / 界面**：Java + XML（`minSdk 14`，无 Kotlin、无 Compose）
- **播放器**：`ijkplayer-java`（模块内置，源自 [bilibili/ijkplayer](https://github.com/bilibili/ijkplayer)）
- **弹幕**：`DanmakuFlameMaster`（模块内置，已打本地补丁）
- **网络 / 解析**：OkHttp 3.12.1 · Gson 2.8.9 · protobuf-javalite（弹幕协议）
- **其他**：Glide、EventBus、jsoup、PhotoView、zxing、Brotli（`brotlij` + brotli4j）

## 已知问题

- 观看进度依赖服务端接口，**未登录时不会上报**（日志中会有明确提示）。
- 使用外部播放器（小电视 / 凉腕）时拿不到播放结束时的真实进度，会用进入播放前的进度兜底。
- 部分界面文案（如初始引导页里的 QQ 群与官网信息）**仍沿用上游**，未随本分支更新。
- 只打包 32 位 ABI，极少数纯 64 位设备无法安装。

## 上游与致谢

- **哔哩终端（BiliClient）** —— 本分支的上游项目，原项目已停更归档。
- [WearBili](https://github.com/SpaceXC/WearBili) —— 布局与部分开源代码参考。
- [腕上哔哩](https://github.com/luern0313/WristBilibili) —— 部分开源代码与 API 收集参考。
- [Re:WearBili](https://github.com/SpaceXC/Re-WearBili) —— 兄弟项目，全新 UI 与动效。
- [bilibili/ijkplayer](https://github.com/bilibili/ijkplayer) —— 播放内核。

本分支为**非官方构建**，与哔哩哔哩（Bilibili）官方及上游项目均无隶属关系。
使用中遇到的问题请**在本仓库提 Issue**，请勿反馈至官方或上游渠道。

## 许可

[GPL-3.0](LICENSE)。上游为 GPL-3.0 项目，本分支沿用同一许可，分发时请保留版权声明与许可全文。

---

<div align="center">
<sub>代码有 AI 编码助手参与 · 功能经真机验证 · 问题请提 Issue</sub>
</div>
