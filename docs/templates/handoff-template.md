# 交接模板（handoff template）

> 复制本文到 `docs/handoffs/YYYYMMDD-<主题>.md`（或任务分支的 PR 描述）。
> 规范见 `../development/agent-handoff.md`；只写事实，不写聊天记录，不写凭据。

# 交接：<主题>

- 日期：
- 负责人（当前）/ 接手者：
- 分支：`<branch>` ｜ HEAD：`<commit>`
- 关联任务/Issue：

## 1. 任务目标与验收条件

- 目标：
- 验收条件：
- 当前阶段（Phase / 批次）：

## 2. 状态总览

| 项 | 状态 | 说明 |
|---|---|---|
|  | 完成 / 进行中 / 未开始 / 阻塞 |  |

## 3. 改动文件

| 文件 | 改动 | 是否提交 |
|---|---|---|
|  |  |  |

> 删除/新增文件单独列出；未提交改动注明原因。

## 4. 已执行检查

| 命令/方式 | 结果 | 备注 |
|---|---|---|
| `git diff --check` | PASS |  |
| `./gradlew :app:assembleDebug` | PASS / NOT RUN | 本机 ARM64 需 aapt2 override |
| 模块回归 | NOT RUN | 无设备 |

## 5. 未完成 / 未验证（必须写原因）

- 未完成：
- 未验证（`BLOCKED`/`PARTIAL`/`NOT RUN`）：

## 6. 已知问题与风险

- ：

## 7. 下一步建议

1. 
2. 

## 8. 相关文档与提交

- 文档：
- 提交：
- 需要用户决策的事项：