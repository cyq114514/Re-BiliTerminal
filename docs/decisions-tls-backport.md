# 老设备 TLS 回移（崩溃上报通道）决策记录

> 2026-11-14 选定。记录「Android 4.4 / 部分 5.x 设备崩溃上报因 TLS 握手失败」的
> 根因证据、被排除的方案与最终结论，供后续维护者判断边界。

## 问题

崩溃上报双路（Netlify 反代 → Supabase 直连）在老设备上全部失败：
`SSLHandshakeException: SSLProtocolException: SSL handshake aborted:
ssl=0x800fd790: error:14077410:SSL routines:SSL23_GET_SERVER_HELLO:sslv3 alert handshake failure`，
HTTP -1，连续 20 次后被队列丢弃（米兔手表类 4.4 设备实测）。

## 根因证据（2026-11-14 实测）

| 证据 | 结论 |
|---|---|
| openssl 探测 supabase.co 直连 | 仅 TLS 1.2+AEAD(GCM)（CBC 全拒）；链 = supabase.co ← GTS WE1 ← GTS Root R4 ← GlobalSign Root CA（交叉签名中间证书服务器有下发，4.4 可验） |
| openssl 探测 *.netlify.app | TLS 1.2 下仅收 ECDHE-**ECDSA**-AES128-GCM（RSA 套件、CBC 全拒，TLS 1.0/1.1 指定套件后全拒）；链 = \*.netlify.app ← DigiCert Global G2 CA ← **DigiCert Global Root G2** |
| DigiCert Global Root G2 入库时间 | Android 5.0 起才在系统 CA 库（Azure 官方文档佐证），4.4 缺失 |
| 设备日志报错 `sslv3 alert handshake failure` | 服务端在 ClientHello 阶段拒绝（握手死在证书交换**之前**）——不是缺根证书（那会报 `Trust anchor...`，且不会是 OpenSSL alert）；即设备拿不出任何 TLS1.2+AEAD 套件，兼容工厂启用了 TLS1.2 也无济于事（ROM 裁剪的 OpenSSL 无 GCM） |
| 两端点 15ms 内先后失败 | 双双瞬时拒绝，与「TLS1.2+AEAD-only」的服务端要求吻合 |
| `SSL23_GET_SERVER_HELLO` 特征串 | OpenSSL 1.0.x（4.x 系统 TLS 栈）；设备挂 Xposed，系统栈被动过手脚的额外可能 |

推论：失败是「协议+套件」层（主）与「根证书」层（4.4 对 Netlify 必中）叠加；
`SSLSocketFactoryCompat` 只管启用开关，永远修不了后两层。

## 已排除的方案

| 方案 | 排除理由 |
|---|---|
| 只补根证书 bundle（D 单独） | 证据显示握手死在 ClientHello，缺根不是本案主堵点；Conscrypt 也只换 socket 不换信任库，所以 D 只能当搭售不能当主力 |
| GMS ProviderInstaller | 米兔手表/国产老 ROM 无 GMS，目标设备覆盖率为零 |
| BouncyCastle bctls 纯 Java TLS | dex/压缩后约 2MB，不比 Conscrypt v7a-only 小，且慢、集成糙 |
| 服务端放宽（自建老 TLS 兼容端点，腾讯云函数/CF Worker） | 可行但属「给残缺栈续命」且只救上报一条路；TLS 层不修，以后任何新端点老设备都够不着。**留作后手**：若 F+D 发版后 GFW 层可达性仍是主要缺口再启用 |
| Conscrypt 全量（4 ABI，+约 2MB） | 用户否决：体积不可接受 → 改为 ABI 拆分瘦身 |

## 结论：F+D —— Conscrypt 瘦身版 + 根证书 bundle

- 依赖：`org.conscrypt:conscrypt-android:2.5.2`，**版本锁死**——2.6.x 起用 NDK 27 构建，放弃 API 19/20
- 作用范围：**只挂 CrashUploader 的 client**。B 站 API 在这些设备上当前是通的，不动全局 `setOkHttpSsl`，不扩大回归面
- provider 不做全局 `insertProviderAt`，用 `SSLContext.getInstance("TLS", conscryptProvider)` 显式传 provider，避免污染进程级 JCA 默认序
- 信任：合并 KeyStore（系统 AndroidCAStore + res/raw 内置 PEM：DigiCert Global Root G2、ISRG Root X1）→ `TrustManagerFactory`（平台 PKIX，不手写验证逻辑）。**Conscrypt 不换信任库，4.4 对 Netlify 的 G2 缺根必须靠这 4KB 补**
- Conscrypt 生效时不再套 `SSLSocketFactoryCompat`（其 `setEnabledProtocols` 会把 TLS 1.3 限回去）
- ABI：release 只留 armeabi-v7a，x86 移入 debug 变体（模拟器用）。现包 x86 `.so` 原始 7.7MB（压缩后约 4MB+），加 Conscrypt v7a（约 1~1.5MB）后 **release 包净减约 3MB**
- 既有「上报失败是常态」设计不变：本地落盘、待发队列、MAX_RETRY、优先级淘汰

## 风险与验收

- Conscrypt 2.5.2（2021 年）为最后支持 API19 的版本，安全风险对崩溃上报（不带 Cookie）可接受；根证书几年一换，补 PEM 重发版即可
- SNI 风险：OkHttp 3.12 对非平台 socket 的 setHostname 反射若失效，Conscrypt 工厂自建的 socket 默认按创建时的 host 走 SNI；实测必须含「API 19 模拟器真实上传成功」
- 验收门：API 19 模拟器（x86 debug 包）真实上报成功 + 反代直连双路至少一路通；4.4 真机（手表）CrashTrace 显示上报成功；5.0+ 设备回归零影响（未改动其路径）；B 站 API 冒烟不受影响
- 案例二（DynamicInfoActivity `Stats.reply` NPE）已在现行代码修复（判空 + 降级 0），截图设备为旧版包；上报通道修好后以「不再复发」验收，无需另改

## 后手（未启用）

腾讯云函数第三路端点（国内可达 + 对老套件宽容），治 GFW 层失败；部署后需
openssl 复测其边缘的最低 TLS 版本与证书链。触发条件：F+D 发版后仍有设备
「TLS 修好了但端点不可达」。

## 实施与验证记录（2026-11-14）

- 落地：conscrypt-android 2.5.2 + `NetWorkUtil.applyCrashUploadTls`（显式 provider，
  合并信任锚，API≤22 生效）+ CrashUploader 接线；release ABI 只留 armeabi-v7a
  （x86 移 debug 变体，另支持 `-PemuArm64` 临时追加 arm64 供模拟器测试）。
  release 体积 9.6MB → **7.3MB**（净减 2.3MB，其中 libconscrypt_jni.so v7a 1.22MB）。
- **验证中发现的新问题**：Netlify 上的 `crash` 函数已 404（Mac curl 直测确认，与设备无关），
  且旧 `upload()` 逻辑把反代 404 当「服务端拒绝」短路、直连永远不会被尝试——
  即修复前**所有设备**的崩溃上报实际全灭。已改为「反代 404 → 继续试直连」；
  **netlify 函数需重新部署**（另行动作，见 docs/Netlify部署教程.md）。
- 仪器化验证（API 36 arm64 模拟器，`-PemuArm64 connectedDebugAndroidTest`，2 条全绿）：
  1. `conscrypt路径_反代与直连均完成握手并拿到2xx`——Conscrypt 原生库加载
     （socket 工厂 org.conscrypt.OpenSSLSocketFactoryImpl）、合并信任锚生效、
     反代握手拿到 HTTP 响应（其 404 为函数缺失，非 TLS）、直连完整插入 201；
  2. `仅内置补根也能验过netlify链`——只信任内置 G2/ISRG 两张根（等价模拟 4.4
     系统库无 G2 的情形）仍能验过 \*.netlify.app 链，证明补根独立起效。
- 本机验证边界：Apple Silicon 模拟器只有 arm64 镜像（API 24+），无法复现 API 19
  系统栈缺 TLS1.2+AEAD 的现场，也无法在本机跑 4.4 虚拟机；最终验收需 4.4 真机
  （米兔手表类）：冷启动后 CrashTrace 应出现「TLS 栈已切换 Conscrypt」与「上报成功」。
- 测试在 crash_reports 表留 3 条可删除行：1 条 CurlProbe、2 条 TlsBackportInstrumentedTest。
