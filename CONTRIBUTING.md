# 贡献指南（CONTRIBUTING）

> 本仓库是 AI 参与度很高的项目（见 `readme.md` 的 Vibe Coding 声明）。无论人还是 Agent，提交前请先读 [`AGENTS.md`](AGENTS.md)，并按 [`docs/README.md`](docs/README.md) 的三级索引按需读文档。

## 1. 提 Issue

- 功能异常 / 闪退：[Bug 反馈](https://github.com/cyq114514/Re-BiliTerminal/issues/new?template=bug_report.yml)（请附版本、设备、复现步骤；有日志更好）
- 新功能：[功能建议](https://github.com/cyq114514/Re-BiliTerminal/issues/new?template=feature_request.yml)
- 工程/构建/CI/文档类：[工程任务](https://github.com/cyq114514/Re-BiliTerminal/issues/new?template=engineering_task.yml)

> 不要在公开 Issue 里贴登录 Cookie、签名口令、私钥、`local.properties` 内容；敏感问题请私下联系维护者。

## 2. 改代码前

1. 读 `AGENTS.md` 的规则与红线；按任务读 `docs/modules/<相关模块>.md`。
2. 检查 `git status` 与当前分支；不覆盖他人未提交的改动。
3. 复杂任务先写清范围/验收/验证方式（模板：[`docs/templates/task-template.md`](docs/templates/task-template.md)）。

## 3. 本地验证

最低要求：

```bash
git diff --check
./scripts/check-repo.sh
./gradlew :app:assembleDebug        # ARM64/PRoot 需加 aapt2 override，见 docs/development/environment.md
./gradlew :app:testDebugUnitTest    # JVM 单测；ARM64/PRoot 同样需 aapt2 override
```

改/加测试时再跑 `./scripts/check-tests.sh`（测试体系不变量）。完整命令与分级见 [`docs/development/commands.md`](docs/development/commands.md)，测试策略见 [`docs/development/testing.md`](docs/development/testing.md)。
**未执行的检查不要写"通过"**：用 `NOT RUN` / `BLOCKED` / `PARTIAL` 并说明原因。
**本地构建/本地单测通过不等于 CI 通过**：推送后请等真实 Actions 结果再描述状态，CI 结构、失败分类与报告位置见 [`docs/development/ci.md`](docs/development/ci.md)。

## 4. 提交与 PR

- 提交信息用 Conventional Commits（`feat:` / `fix:` / `docs:` / `chore:` / `perf:` / `refactor:`），正文写根因与修法。
- 一个 PR 只做一件事；不夹带格式化、重命名、无关改动。
- PR 使用仓库自带的 [PR 模板](.github/pull_request_template.md)，如实填写验证结果与未验证项。
- 推送/开 PR 后**等待真实 CI 结果**再描述状态：哪些 job 通过、失败在哪一步以 Actions 页面为准；失败按 [`docs/development/ci.md`](docs/development/ci.md) 的分类定位并附日志；不要用 `continue-on-error`、删除/跳过测试让检查变绿。
- 分支：`feature/*`、`fix/*`、`chore/*`；不要直接向 `main` 推送未经检查的改动。

## 5. 红线（会被拒绝的改动）

- 擅自修改 `applicationId`、versionCode/versionName、签名配置、`update.json` 机制或创建 tag/Release。
- 把凭据、token、keystore、`local.properties` 内容提交进仓库。
- 删除测试/检查/日志来"通过"验证。
- 修改第三方模块（`ijkplayer-java`、`brotlij`）或替换 `app/libs/*.so` 而没有单独评审。
- 大范围格式化或重构混在功能提交里。

## 6. 文档

改动影响架构、模块边界、构建/CI 或重要业务行为时，同步更新对应文档；普通 Bug 修复按需更新 `docs/更新日志.md`。新增文档请登记到 `docs/README.md`。