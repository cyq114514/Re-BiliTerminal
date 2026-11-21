# 官网 Netlify 部署教程

> 适用：`site/` 目录下的纯静态站点（4 个页面 + 静态资源，无构建步骤）。
> 免费额度：100GB 流量/月、300 分钟构建/月，对静态站完全够用。

---

## 0. 前置检查

部署前确认 `site/` 目录结构完整：

```
site/
├── index.html          首页
├── faq.html            常见问题
├── changelog.html      更新日志
├── privacy.html        隐私说明
├── _redirects          /api/update.json 反向代理（关键）
├── robots.txt
├── sitemap.xml
└── assets/
    ├── style.css
    ├── site.js
    └── icon.png
```

> `_redirects` 必须在**发布目录的根**，且文件名不能带扩展名。它负责把
> `/api/update.json` 代理到 GitHub Release 资产，页面版本号才能自动跟随发版。

---

## 1. 注册 / 登录

1. 打开 <https://app.netlify.com>
2. 点 **Sign up** → 选 **GitHub**（用 GitHub 账号授权登录，最省事）
3. 授权后进入控制台

---

## 2. 部署方式

### 方式 A：拖拽部署（最快，不用先推 Git）

1. 控制台 → **Sites** → 右侧 **Add new site** → **Deploy manually**
2. 页面出现虚线框（"Drag and drop your site output folder here"）
3. 把本地 `site` **文件夹**整个拖进去（注意：拖文件夹本身，不是里面的文件）
4. 等待 10~30 秒，出现 `Site deploy in progress` → `Published`
5. 点 **Open production deploy** 打开站点

> 缺点：以后改内容要重新拖一次，不会自动更新。

### 方式 B：连接 GitHub（推荐长期使用）

1. **前提**：仓库已推送到 GitHub（`cyq114514/Re-BiliTerminal`）
2. 控制台 → **Add new site** → **Import an existing project**
3. 选 **GitHub** → 授权 → 在列表里选 `Re-BiliTerminal`
4. 填写构建配置（**关键，别填错**）：

| 配置项 | 填什么 |
|---|---|
| Branch to deploy | `main` |
| Base directory | 留空 |
| **Build command** | **留空**（纯静态，不需要构建） |
| **Publish directory** | `site` |

5. 点 **Deploy site**
6. 首次上线需要手动发布一次：仓库里的 `netlify.toml` 会跳过所有自动构建（官网只由 tag
   发版流程部署），所以请在本地按第 6 节末尾的 `netlify deploy` 命令发布一次，
   或暂时删掉 `netlify.toml` 再让 Netlify 构建；成功后再按第 6 节完成常驻配置。

> 注意：**push 到 `main` 不会自动部署官网**。官网只在推 `v*` tag 发版时由 CI 部署，
> 首次接入需要按第 6 节配置 Netlify 停止自动构建 + GitHub Secrets。

---

## 3. 修改站点名（拿到固定网址）

默认网址形如 `https://random-name-123456.netlify.app`，建议改掉：

1. 站点页 → **Site configuration** → **Site details**
2. 点 **Change site name**
3. 输入 `re-biliterminal` → **Save**
4. 得到 **`https://re-biliterminal.netlify.app`**

> 站点名全局唯一，被别人占用就换一个（如 `rebili-terminal`、`re-biliterminal-cn`）。
> 确定后同步修改 `sitemap.xml` 与 `robots.txt` 里的域名。

---

## 4. 部署后验证清单

按顺序逐条确认：

| # | 检查 | 预期 |
|---|---|---|
| 1 | 打开首页 | 深色页面正常显示，标题「Re：哔哩终端」 |
| 2 | 下载区版本号 | 显示 `v1.2.1` 等**真实版本**（说明代理生效） |
| 3 | 直接访问 `/api/update.json` | 返回一段 JSON（含 `versionName` / `fileName` / `sha256`） |
| 4 | 点「下载 APK」 | 跳转 GitHub Releases 并开始下载 |
| 5 | 点「镜像 · ghfast.top」 | 通过镜像开始下载 |
| 6 | 依次打开 `faq.html` / `changelog.html` / `privacy.html` | 均正常，无 404 |
| 7 | 手机浏览器打开 | 排版不横向滚动（页面已做 `overflow-x: hidden`） |
| 8 | 点「复制」按钮 | 复制到 SHA-256 |

**第 3 条最关键**：它决定版本号是否自动跟随发版。

---

## 5. 绑定自定义域名（可选）

有域名之后再做：

1. 站点页 → **Domain management** → **Add a domain**
2. 输入域名（如 `re.example.com`）→ **Verify** → **Add domain**
3. 到域名服务商后台添加解析：

| 类型 | 主机记录 | 记录值 |
|---|---|---|
| CNAME | `re` | `<你的站点名>.netlify.app` |

4. 等 DNS 生效（几分钟到几小时）
5. Netlify 会自动签发 HTTPS 证书（Let's Encrypt）

> 参考站 `biliterminal.zsapp.asia` 就是这么做的：CNAME 到 `rebiterminal.netlify.app`。

---

## 6. 日常更新流程（官网只在发版时更新）

官网的发布时机是：**普通 commit / PR 不部署，只有推 `v*` tag 发版才部署**。

| 场景 | 会不会更新官网 |
|---|---|
| push commit 到 `main` / 合并 PR | 否（Netlify 跳过构建） |
| push tag `v*` 且 CI 发布成功 | 是（CI 的 `deploy-site` job 用 Netlify CLI 部署） |
| 本地手动 `netlify deploy --prod` | 是（应急用，见本节末尾） |

发版时 CI 的顺序是：`release` job 构建签名 APK → 创建/更新 GitHub Release 与 `update.json`
→ `deploy-site` job 部署 `site/` + `netlify/functions/`。页面版本号来自
`/api/update.json` 的实时代理，所以 APK 和官网永远是同一版。

### 一次性配置（新站点 / 换账号时做一次）

1. **停止自动构建**（防止 push 就更新线上）：
   站点 → **Project configuration** → **Developer settings** →
   **Continuous deployment** → **Build settings** → **Configure** →
   **Build status** 选 **Stopped builds**。
   > 停止构建只停「git push / build hook / 后台按钮」触发的构建；
   > CI 里用 CLI 的手动部署仍然可以正常发布，不受影响。
   > 仓库里的 `netlify.toml` 还写了 `ignore = "exit 0"` 兜底：即使有人把自动构建重新打开，
   > 普通 push 也只会得到一个 Skipped，不会更新线上。
2. **创建访问令牌**：
   Netlify 右上角头像 → **User settings** → **Applications** →
   **Personal access tokens** → **New access token**，名字填 `github-actions`，
   复制生成的值（只显示一次）。
3. **找到 Site ID**：
   站点 → **Project configuration** → **Site details**（或 **Site information**）→
   **Site ID**，复制形如 `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` 的值。
4. **加到 GitHub Secrets**：
   仓库 → **Settings** → **Secrets and variables** → **Actions** →
   **New repository secret**，新增两条：
   - `NETLIFY_AUTH_TOKEN` = 第 2 步的令牌
   - `NETLIFY_SITE_ID` = 第 3 步的 Site ID

配置完推一个 `v*` tag 验证：Actions 里 `官网部署（tag）` job 变绿，Netlify 的
**Deploys** 列表出现一条 `release vX.Y.Z` 的生产部署。

### 应急：只改官网文案、不想发版

`site/` 的改动（含 `changelog.html`）默认等下一次 tag 才上线。遇到错别字之类要马上改：

```bash
netlify deploy --prod --dir=site --functions=netlify/functions
```

> 需要先 `netlify login` 并在仓库根目录 `netlify link` 过站点。这条命令绕过 CI，
> 线上会立刻更新，但不会产生新的 APK / Release。

---

## 7. 常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| push 了 commit，官网没变化 | 预期行为：官网只在 tag 发版时部署 | 等下次发版；急着改文案用第 6 节的本地 CLI 部署 |
| Actions 的 `官网部署（tag）` 报缺少 NETLIFY_AUTH_TOKEN | 还没配 GitHub Secrets | 按第 6 节「一次性配置」补齐两条 secret |
| `/api/update.json` 返回 404 | `_redirects` 没进发布目录，或发布目录填错 | 确认 Publish directory = `site`；确认 `site/_redirects` 存在且无扩展名 |
| 页面没有样式（纯文字） | 上传的是外层目录，`assets/` 路径对不上 | 重新拖 `site` 文件夹本身 |
| 打开是 404 页 | Publish directory 填成了 `site/index.html` | 改成 `site` |
| 版本号一直是旧值 | 浏览器缓存，或代理未生效 | 强制刷新；检查第 3 条 |
| 国内打开很慢/打不开 | Netlify 节点在境外 | 属正常现象，可另开 GitHub Pages 作为备用线路 |
| 部署失败提示 build command | 构建命令没留空 | 清空 Build command 后重新部署 |

---

## 8. 回滚

站点页 → **Deploys** → 找到上一次成功的部署 → **Publish deploy**。
秒级生效，不需要重新构建。
