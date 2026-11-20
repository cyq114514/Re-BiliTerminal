# 修改说明

> 一个 PR 只做一件事；未执行的检查**不得**标为通过（用 `NOT RUN` / `BLOCKED` / `PARTIAL` 并写原因）。
> 规则与验证矩阵见 [`AGENTS.md`](../AGENTS.md)、[`docs/development/workflow.md`](../docs/development/workflow.md)。

## 1. 目标

<!-- 要解决什么问题？对应 Issue/用户反馈/崩溃？ -->

## 2. 改动范围

- 涉及模块：`app` / `ijkplayer-java` / `DanmakuFlameMaster` / `brotlij` / `docs` / `.github`（选填）
- 主要文件：
- 明确**不**包含的改动（如版本号/签名/包名/ABI/用户数据）：

## 3. 实现要点

<!-- 根因与修法；有取舍时写为什么这么做。不要贴大段代码。 -->

## 4. 验证

| 检查 | 命令/方式 | 结果 | 备注 |
|---|---|---|---|
| 静态检查 | `git diff --check` | | |
| 仓库自检 | `./scripts/check-repo.sh` | | |
| Debug 构建 | `./gradlew :app:assembleDebug`（ARM64 加 aapt2 override） | | |
| CI 构建 | GitHub Actions（push/PR） | | 未 push 时写 `BLOCKED` |
| Release 等价 | CI tag 构建（R8 + 资源收缩 + 资源冒烟） | | 通常 `NOT RUN` |
| 仪器化测试 | `./gradlew -PemuArm64 connectedDebugAndroidTest` | | 需设备；会写线上测试数据 |
| 真机回归 | 按模块文档的回归清单 | | 无设备时 `NOT RUN` |

结果取值：`PASS` / `FAIL` / `PARTIAL` / `BLOCKED` / `NOT RUN`。

## 5. 未验证项

<!-- 逐条写：没验证什么、为什么、建议谁在什么环境补验。 -->

## 6. 风险与回滚

- 风险：
- 回滚：`git revert <commit>` / 恢复某文件 / 关闭某开关

## 7. 文档变化

- [ ] 不需要（普通 Bug 修复）
- [ ] 已同步：`docs/architecture/**`、`docs/modules/**`、`docs/update…`（列出）

## 8. 敏感配置与发布

- [ ] 本 PR **没有**新增凭据、token、keystore、`local.properties` 内容
- [ ] 本 PR **没有**修改 `applicationId`/versionCode/versionName/签名/tag 触发规则
- [ ] 本 PR **没有**创建 tag 或 Release
- [ ] 若涉及 secrets/CI 权限变化，已在描述中说明并等待维护者确认

## 9. Checklist

- [ ] 改动范围与描述一致，没有夹带格式化/重命名
- [ ] 完整 diff 已自查（无意外删除）
- [ ] 未执行的检查已如实标注
- [ ] 与相关 Issue 已关联