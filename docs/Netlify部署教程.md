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
6. 等待构建完成（通常 20~40 秒），出现 `Published`

> 之后每次 `git push` 到 `main`，Netlify 会自动重新部署，不用手动操作。

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

## 6. 日常更新流程

| 方式 | 操作 |
|---|---|
| 已连 GitHub | 改完文件 `git push` → Netlify 自动重新部署 |
| 拖拽部署 | 重新拖一次 `site` 文件夹 → Netlify 保留历史版本，可回滚 |

**发新版 APK 时官网无需改动**：只要 GitHub Release 里带上新的 `update.json`，
页面版本号、下载文件名、SHA-256 会自动更新。

---

## 7. 常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
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
