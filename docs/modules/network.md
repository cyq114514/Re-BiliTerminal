# 网络与 API（手写 OkHttp 封装 + Cookie/签名/媒体头）

> 什么时候读：加/改接口、改请求头与 Cookie 逻辑、排查登录态异常（-101/-111）、短链跳转失败、WBI 签名 403、老设备 TLS 问题时。
> 路径约定：Java 路径省略前缀 `app/src/main/java/com/RobinNotBad/BiliClient/`。

## 先读

| 文件 | 关键位置 | 为什么先读 |
| --- | --- | --- |
| `util/NetWorkUtil.java`（871 行） | 单例与拦截器 `:63-141`；请求方法族 `:264-368`；重试 `:379-419`；Cookie 写回 `:571-659`；域白名单 `:661-690`；请求头表 `:735-800`；TLS `:146-230` | 全部网络请求的唯一底座：单例 OkHttp + 手写跟跳 + Cookie 拦截器 + 超时 + 重试 + 脱敏 |
| `api/ConfInfoApi.java` | `WBI_KEY_TTL_MS:32`、`signWBI:62-92`、参数过滤/排序 `:96-120` | 所有 `wbi/*` 接口的签名；mixin key 缓存 30 分钟、`synchronized` |
| `api/PlayerApi.java` | `mediaHeadersFor:288-299`、`buildMediaCandidates:213-262` | 媒体请求头的**唯一出口**；可信域才带 Cookie，PCDN 一律不带 |
| `util/CookieGenerator.java` | `ensureCookies:24`、`getCookieString:53-75`、`generateBiliTicket:104`（HMAC 在 `:107`） | 设备 Cookie（buvid3/4/fp、bili_ticket），补登态与媒体头都依赖它 |
| `api/CookieRefreshApi.java` + `api/CookieRefreshResult.java` | 分类 `:102-112` / 枚举 `:24-56` | `-111` 是可重试的 csrf 失败、只有 `-101` 才是凭证失效，决定是否 `resetLogin` |
| `util/CookieRefresher.java` | `startPeriodicCheck:183-190`、`resetLogin:206` | 每小时检查；瞬时失败绝不 `resetLogin`（`:132-145`） |
| `api/CookiesApi.java` | `genBiliTicket:116` | 设备票据生成，改签名输入会影响所有域 |

## 允许改动

- 单个 api 类的新增/修正：URL、query 拼接、JSON 解析、错误提示文案。
- 重试次数/间隔的**设置项默认值**（不改语义）；日志级别与脱敏正则的补充（`:551-560`）。
- 新的只读 GET 接口（走 `getJson`，自动带 Cookie/重试）。
- 媒体候选的**日志与诊断**（不改 `buildMediaCandidates` 排序规则）。

## 禁止 / 慎改（红线）

1. **拦截器顺序与跟跳策略**（`:80-136`）：放宽 host 校验 = 带 Cookie 的请求可被任意域收取；收紧 = `b23.tv` 短链、Opus 网页抓取失效。
2. **`isBilibiliHost:661` / `isApiHost:680` 白名单**：把 CDN 域放进 `isApiHost`，被劫持的 CDN 返回 `Set-Cookie: SESSDATA=…` 就能整体覆盖登录态；Akamai 必须精确主机名（`:664-676` 注释）。
3. **WBI 签名与 mixin 缓存**（`ConfInfoApi:31-92`）：改参数过滤/编码/TTL → 全部 `wbi/*` 返回 `-403`，表现为"推荐/搜索/取流全空"；`calc_str` 含用户查询与密钥，不得打印（`:88`）。
4. **`webHeaders` 的 volatile copy-on-write 结构**（`:735-800`）：在 OkHttp 线程调 `refreshHeaders()` 会嵌套网络请求、递归占请求线程，拖慢所有接口。
5. **Cookie/csrf 同步**（`:643-649`）：去掉 = 所有写操作 `-111`；写错键 = `csrf` 与 Cookie 长期不一致。
6. **全局 TLS 栈**（`setOkHttpSsl:146-165`）：证书校验必须用系统 TrustManager，**不得 trust-all**（历史上就是这么漏的）；Conscrypt 只挂崩溃上报通道（`:186-230`），不做全局 `insertProviderAt`。
7. **日志**：URL/body 进日志前必须 `maskSensitiveData:554`；Cookie 只打印键名（`:616,640`），完整 Cookie 串不得落日志。
8. **`accountGeneration` 语义**（`:565-571`）：删掉或改成非原子自增，切号瞬间在途响应会把旧账号凭证合进新会话（TOCTOU，`:582-587`）。

## 必查回归清单（按波及面排序）

- [ ] 未登录 / 已登录 / 掉登录：`-101` 才登出；`-111` 只重试 + 刷新 csrf，不得清凭据（`CookieRefreshResult:30-36`）。
- [ ] 弱网/软失败：把 `API_RETRY_MAX_TIMES` 设 1 与 5 各试一次，返回 `text/html` 时仍能重试恢复。
- [ ] 短链 `b23.tv` 跳转（普通分享链接 + Opus 抓取）；跨域跳转在第二跳被拦截。
- [ ] 媒体头出域：播一个 PCDN 地址（`*.edge.mountaintoys.cn` / `*.mcdn.bilivideo.cn`），确认请求不带 Cookie；官方 CDN 带 Cookie。
- [ ] Cookie 轮换后写操作：点赞/投币/收藏/评论/发弹幕（-111 回归），切账号后旧请求不污染新会话。
- [ ] API≤22 设备 HTTPS（TLS1.1/1.2 + 系统信任）；崩溃上报 TLS 走 Conscrypt 且不影响 B 站 API。
- [ ] 日志脱敏：URL 里的 `csrf`/`wbi`、body 里的 `access_key/refresh_token`、`Set-Cookie` 内容。
- [ ] 断网冷启动：`isNetworkAvailable` 走本地判断，不因超时干等 8+16s（`:778`）。

## 结构要点（数据流）

1. 单例 `INSTANCE`（`:63`，`AtomicReference` 双检）一次构建：`followRedirects(false)`（`:81`）+ 手动跟跳拦截器（`:86-136`）+ `CookieSaveInterceptor`（`:137`，实现在 `:861-870`）+ `Inet4Selector`（`:65-77`）+ 8s 连接 / 16s 读 / 8s ping（`:139-141`）。
2. 跟跳只允许 https 且目标属于 `isBilibiliHost`（`:120`）；`b23.tv` 不跟，只回传 location 给调用方（`:111-115`）。
3. 请求入口：`getJson/getJsonNoCookie/getJsonPrivacy`（`:264-310`）、`post/postJson`（`:335-368`）→ `executeWithDoctypeRetry`（`:379`）：返回 HTML/非 JSON 的"软失败"自动重试，GET 重试、POST 不重试（`:382-384`）。
4. 重试参数来自设置项：`API_RETRY_MAX_TIMES`（默认 5）/`API_RETRY_INTERVAL_SECONDS`（默认 0.1s，`:381-383`）。
5. Cookie 写入域：有 `Domain` 属性时必须 `bilibili.com` 后缀匹配；无 `Domain` 时来源必须 `isApiHost`（`:680`）——CDN 域（hdslb/bilivideo/akamai）只用于媒体头与跟跳，**不许写会话**（`:597-610`）。
6. `bili_jct` 轮换时同步 SP `csrf`，与 Cookie 同锁写入（`:643-649`），否则写操作一律 `-111`。
7. 切号代际：请求发出时打 `accountGeneration` tag，回写前校验，丢弃旧账号的迟到 `Set-Cookie`（`:565-587`）。
8. 请求头表 `webHeaders` 是 volatile copy-on-write List（`:735-760`）；网络请求前用 `refreshHeaders()`（`:762`，含嵌套网络请求），**拦截器线程只能用 `updateWebHeaders()`**（`:794`）。

## 常见坑

- 1.2.4 事故：`-111` 被当成凭证失效，一次抖动就清空 `refresh_token` → 被迫重新扫码；现在归类 `CSRF_MISMATCH`（可重试）。
- 1.3.7：裁剪 ROM 拿不出 TLS1.2+AEAD，`SSLSocketFactoryCompat` 只能开协议开关救不了 → Conscrypt 只挂崩溃通道，别顺手加到全局 client。
- 拦截器里调 `ensureCookies` 会触发嵌套网络请求（`updateWebHeaders:794` 的上方注释 `:789-793`），这是"所有接口变慢"的隐蔽来源。
- 域名常量散落在各 api 类（无集中表），改域名要全仓搜 `api.bilibili.com` 等字面量。

## 相关文档

- `../decisions-tls-backport.md`（TLS 回移决策与验证记录）
- `../architecture/risk-map.md`、`../architecture/data-flow.md`（登录态/更新/崩溃上报数据流）
- `../更新日志.md`（1.2.3 / 1.2.4 / 1.3.7 网络相关根因）