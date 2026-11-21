# 自动化测试指南（testing）

> 什么时候读：写/改测试、评估某模块能不能自动化、交付测试报告、或需要回答「哪些没测」时。
> 相关文档：[ci.md](ci.md)（云端 job 与失败分类）、[commands.md](commands.md)（命令与环境）、[workflow.md](workflow.md)（Agent 七步流程）。

## 1. 测试分层与取舍

| 层 | 执行环境 | 本仓库做法 | 覆盖对象 |
|---|---|---|---|
| JVM 单元测试 | 本机/CI，无需设备 | **本次建设重点**：JUnit4 + Robolectric | 纯业务逻辑、状态机、安全校验、数据库迁移 |
| 仪器化测试 | 真机/模拟器 | 仅历史 TLS 测试（会真实联网、写线上数据），**不纳入 CI** | Android 框架真实行为 |
| 真机回归 | 真机 | 按模块文档清单人工执行 | IJKPlayer/Surface/Service/播放链路 |

原则：**能在 JVM 稳定验证的逻辑，不放到模拟器**；**依赖 native/真实设备行为的部分，不伪装成 JVM 测试**。

## 2. 本地运行

### 2.1 标准命令

```bash
# 全量 JVM 单测（CI 使用的任务）
./gradlew :app:testDebugUnitTest --stacktrace

# 单个类 / 多个类
./gradlew :app:testDebugUnitTest --tests 'com.RobinNotBad.BiliClient.util.PlayerProgressRulesTest'
./gradlew :app:testDebugUnitTest --tests 'com.RobinNotBad.BiliClient.source.*'

# 单测 + Debug 编译（提交前推荐一起跑）
./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

**ARM64 / PRoot（本机手机环境）必须加**：

```bash
./gradlew :app:testDebugUnitTest \
  -Pandroid.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2
```

### 2.2 报告位置

```text
app/build/test-results/testDebugUnitTest/TEST-*.xml   # 机读结果（CI 汇总用）
app/build/reports/tests/testDebugUnitTest/index.html  # 人读报告
```

失败时先看 HTML 里的失败用例与堆栈；XML 里 `<failure message="...">` 是 CI 摘要的来源。

### 2.3 环境说明

- JUnit `4.13.2`、Robolectric `4.18`、`androidx.test:core 1.5.0` 均为 `testImplementation`，**不会进入 APK**。
- Robolectric 选 `4.18` 而不是更旧版本：`4.18` 才带 Linux aarch64 的原生 SQLite runtime；本机 ARM64/PRoot 上旧版本会让数据库类测试直接抛 `Robolectric native runtime is not supported on Linux (aarch64)`。
- `app/src/test/resources/robolectric.properties` 固定 `sdk=33` 且 `conscryptMode=OFF`：
  - `conscrypt-android` 只有 Android 原生库，Robolectric 在 JVM 上安装 Conscrypt Provider 会 `UnsatisfiedLinkError`；
  - 关掉 Conscrypt 不影响被测业务逻辑（测试不验证 TLS）。
- `android-all-instrumented`（约 160 MB）由 Gradle 的 `robolectricJars` 配置解析（走 settings.gradle 的仓库与 Gradle 缓存），`prepareRobolectricJars` 复制到 `app/build/robolectric-jars/preinstrumented`，测试通过 `robolectric.dependency.dir` 读取：**测试运行期不再由 Robolectric 自行联网下载**（历史 CI 曾因该下载 404 导致整批失败）。版本号 `13-robolectric-9030017-i7` 与 Robolectric/SDK 33 绑定，升级 Robolectric 时需同步。
- 测试不要求 `local.properties` 签名配置、不需要 `ANDROID_HOME`（CI 由 workflow 提供）。

## 3. 测试代码规范

1. **位置与命名**：`app/src/test/java/com/RobinNotBad/BiliClient/<包名>/<类名>Test.java`，与被测类同包。
2. **真实断言**：断言业务结果（队列内容、状态转换、解析后的值、数据库行），不要只 `verify` 方法被调用。
3. **离线**：不访问真实 B 站/Supabase/第三方源；不读真实账号、Cookie、设备状态；样例域名用 `example.com`。
4. **隔离**：单例（如 `AudioQueue`）在 `@Before/@After` 复位；文件用 `TemporaryFolder`；SharedPreferences 每个测试用独立文件；数据库每个测试独立文件名。
5. **负向与边界优先**：空数据、非法数据、越界、重复执行、异常退出、旧数据兼容，比「正常路径」更能防回归。
6. **不掩盖**：不允许 `@Ignore` 让失败消失；不允许把断言放宽到「只要不抛异常」；不允许改生产逻辑迎合测试。
7. **可定位**：失败消息写清输入与期望，如 `assertEquals("距结尾 4s 应归零", 0L, ...)`。

## 4. 已覆盖测试清单（PASS 以实际运行为准）

> 下表是**测试文件 → 业务模块 → 覆盖点**。当前共 **19 个测试类 / 211 个用例**（含 1 个基础设施冒烟）（2026-11-21 本地全量实测 `PASS`，见 `ci.md` §8）。
> 实际通过/失败数见每次 CI 的 Job Summary；标注 `PARTIAL` 的表示只覆盖该模块中可离线验证的部分。

### P0 核心

| 测试文件 | 模块 | 覆盖点 |
|---|---|---|
| `util/PlayerProgressRulesTest` | 番剧进度 | 单集/多集/跨集配对、current 优先、last_time 严格配对、超时长丢弃、24h 上限、单位不猜测、服务端确认无记录 |
| `util/VideoMetaManagerTest` | 本地续播 | 5s 起播阈值、距结尾 5s 归一化、越界、损坏/缺失 JSON 兼容、原子写临时文件 |
| `model/DownloadSqlHelperTest` | 下载数据库 | v1/v3/v4 → v5 迁移、历史记录保留、新列默认值、重复 open 幂等 |
| `model/DownloadSectionTest` | 下载记录 | Cursor→对象映射、旧列缺失回退、`name_short`、`audio_only`、PGC 身份传递、重复任务 SQL 语义 |
| `model/AudioQueueTest` | 收藏夹音频队列 | 过滤/去重（aid+cid、本地路径）、懒加载翻页状态、上一首/下一首、单曲/列表/随机模式、多P 插入、队列切换 |
| `util/AudioProgressStoreTest` | 音频续播 | 保存/恢复、5s/尾部归一化、隐私模式、7 天过期、损坏数据、`formatTime` |
| `util/AudioTrackPickerTest` | 音轨选择 | 30280→30232→30216 优先、带宽兜底、空流 |
| `util/SegmentSkipControllerTest` | 片头片尾跳过 | 五档语义、冷却、rearm 3s、剩余 15s 不跳、越界脏数据、ONCE 只跳一次 |
| `util/PlaybackQualityPolicyTest` | 画质策略 | 手动优先、网络分档、非法值回退 |
| `util/PlayerSeekPoliciesTest` | 进度跳转/横滑 | 倍率档位、mm:ss/hh:mm:ss 解析、越界 clamp、提示文案 |
| `util/PrivacyLockUtilTest` | 隐私锁 | 开关+密码双条件、SHA-256 不落明文、错误密码 |
| `scripts/check-tests.sh`（非 JVM 用例） | 收藏夹音频/视频并行 | 静态红线：`AudioPlayerService` 不得出现 `requestAudioFocus` / `abandonAudioFocus` / `AudioManager.AUDIOFOCUS`；CI `checks` 失败即阻断 |

### P1 安全与网络

| 测试文件 | 模块 | 覆盖点 |
|---|---|---|
| `source/net/UrlGuardTest` | 第三方源准入 | 协议白名单、内网/回环/链路本地地址、172.16–31 边界、相对地址解析、origin/query 处理 |
| `source/rule/SafeRegexTest` | 规则正则 | 长度上限、非法正则、输入截断、命名分组兼容 API<26 |
| `source/rule/RuleLimitsTest` | 安全上限 | 常量关系与上限被实际引用 |
| `source/play/HlsPlaylistTest` | 本地 HLS 改写 | 分片/key/init/多级播放列表改写、非法地址原样保留、`isPlaylist` |
| `source/play/HlsRouteTableTest` | HLS 代理安全 | 未注册 token 不可取、会话上限 8、淘汰清 token、子会话 |
| `util/AppSignTest` | 接口签名 | 参数排序、URL 编码、MD5 已知向量（含首字节 0 向量）、300 次批量签名定长 32 位、query 稳定 |
| `util/GsonUtilTest` | JSON 解析 | 空/非法 JSON、int/long 字符串兼容、列表、往返 |

### P2 其他（渐进覆盖）

`PrivacyLockUtil`（设置持久化/隐私锁）已覆盖；主题配置、搜索历史、动态数据转换、本地视频信息解析等按后续迭代补充，不在本次虚报。

## 5. 未覆盖风险清单

| 风险 | 原因 | 现有兜底 |
|---|---|---|
| IJKPlayer 初始化/暂停/释放/Surface 生命周期 | native + 真实设备行为，JVM 无法等价 | 真机回归；`PlayerActivity` 的 `PLAYER_OPS` 序列需人工审查 |
| Android Service 真实生命周期（下载/音频/后台播放） | 依赖系统调度与通知/前台服务 | Robolectric 只测数据库/状态纯逻辑；真机回归 |
| 真实网络请求、Cookie/账号切换、超时 | 需要真实服务与凭据，且不稳定 | 只测参数构建/JSON 解析/错误分支；网络层靠真机回归 |
| 音频与视频「播放状态互不干扰」的运行时行为 | 需要真实 MediaPlayer/IJK/Service 调度与同时播放 | `AudioPlayerService` 的 Audio Focus 红线由 `check-tests.sh` 静态守护（CI 必过）；真正的并行播放/后台状态仍需真机回归 |
| 第三方番剧源真实站点解析 | 站点会变、不可控 | `UrlGuard`/`RuleLimits`/`SafeRegex`/HLS 的离线安全测试 |
| Release 资源收缩/R8/签名 | 只能在 CI tag 构建验证 | tag 发布链的资源冒烟 + 人工发版验收 |
| 数据库跨进程/并发写 | 需要多进程环境 | 迁移/映射测试；真实下载流程真机回归 |
| UI 布局/主题 | 无截图测试框架 | 人工/真机截图回归（`docs/screens` 仅历史参考） |

**这些模块的状态必须写 `NOT RUN`/`BLOCKED`，不能用 JVM 测试结果替代真机验证。**

## 6. 为什么没有做的几件事

- **不引入 Mockito**：现有可测逻辑大多是纯函数/状态机；Robolectric 已提供真实 SharedPreferences/SQLite，Mockito 只会增加依赖与「mock 行为」失真。
- **不引入覆盖率门禁**：覆盖率数字容易驱动「为覆盖率写无价值测试」；本体系以「关键业务断言 + 失败可定位」为准。后续可加 `jacoco` 做趋势观察，但不作为门禁。
- **不跑 `lint`**：仓库未配置 lint，minSdk 19 + 大量历史代码会产生海量噪音；先保证编译与核心逻辑测试稳定，再评估增量 lint。
- **不把模拟器测试塞进 PR 门禁**：见 `ci.md` §4 的评估结论。

## 7. 后续建设建议（按优先级）

1. **补 P2 纯逻辑**：主题/设置持久化、搜索历史、动态 JSON→模型转换、本地视频信息解析。
2. **把下载/音频 Service 的状态机再抽一层**（只在有明确收益时）：如「任务状态转换表」独立成纯类，Service 只做 IO；需单独评审，避免为了测试大改 Service。
3. **CI 增量 lint**：只对改动文件跑，先 WARN 后 FAIL。
4. **Repository 规则/快照测试**：第三方源解析用固定 HTML/JSON fixture，防止规则解析回归。
5. **Emulator nightly（可选）**：先满足 `ci.md` §4 的四个接入条件，再谈是否进 PR 门禁。
6. **action pin 到 SHA** 与 **依赖漏洞扫描**：小步、可回滚。

## 8. 新增测试的检查清单

- [ ] 测试离线、可重复、无设备/账号依赖
- [ ] 覆盖至少一个负向/边界用例
- [ ] 失败消息能指出输入与期望
- [ ] 没有改生产行为；必要抽取已说明理由（纯函数、行为等价）
- [ ] `:app:testDebugUnitTest` 本地通过
- [ ] `scripts/check-tests.sh` 通过
- [ ] 在 `ci.md` 的验证记录或 PR 描述中登记真实结果（PASS/FAIL/NOT RUN）