# 研发任务与会话恢复

[plan.md](plan.md) 说明产品实施顺序，[todo.md](todo.md) 保留 T01～T20 的稳定父 ID。后端已按依赖分片实施；子片通过不等于稳定父任务或业务里程碑已完成，勾选须覆盖相应完整验收。

本机环境按[T01-env](work-items/T01-env.md)配置；根目录前后端框架见[T01-bootstrap](work-items/T01-bootstrap.md)。构建前先按[环境指南](../docs/ops/development-environment.md)选择工具。业务实现的依赖与顺序续接plan.md，状态和当前交接从工作项检索，不将框架或环境验证算作业务完成。

## 工作项

进入非平凡实施前，在 `tasks/work-items/<ID>.md` 新建或续接[工作项模板](templates/work-item.md)。产品切片使用 `T01-a` 等父 ID 后缀；流程任务使用 `DEV-YYYYMMDD-topic`，例如本轮 [DEV-20261023-agent-workflow](work-items/DEV-20261023-agent-workflow.md)。先检索现有记录防止碰撞。

目标/范围、实施/验证、结果/证据和交接为固定章节，内容按任务裁剪。小改可复用原工作项；没有原记录时用短工作项，不为管理工作本身递归建档。状态和完成规则以[开发流程](../docs/standards/agent-workflow.md)为唯一正文。

```powershell
# 看工作状态，不维护第二份动态总表
rg '^Status:' tasks/work-items
rg '^Status: (ready|in_progress|verifying|blocked)$' tasks/work-items
```

## 并行实施 Prompt

剩余工作的多窗口并发 Prompt 集见[parallel-prompts-20261024](parallel-prompts-20261024.md)：
按 A→B→C→D 波次给出每切片独立 Prompt、写入范围、RED 要求与验收命令；每窗口只推自己的分支，
协调者负责评审、cherry-pick、组合 `--all` 重验与推送。

## 直接给 Agent 的任务示例

**实现**：“按仓库规范实现 T01 的最小依赖基线。自行调查、拆分工作项、实施并验证，保留现有未提交文档，交付可评审结果。”

**修复**：“修复这个现象：……。先确认期望合同并复现，补回归验证，再做最小修复；不要顺带重构其他模块。”

**续做**：“续接工作项 T01-a，先核对实际 status/diff 和上次证据，再推进下一未完成切片。”

**仅计划**：“调查 T13 的实施方案并列出风险与验收切片，本轮只交方案。”

**多 Agent**：“使用子 Agent 并行调查 guard 和 OAuth，各自只读；主 Agent 整合结果。”明确多 Agent 后才派工；写代码还需按路径和依赖隔离。

用户只需提供目标与约束，文件选择、模板填写、测试命令和日常记录由 Agent 完成。示例不会自动授予提交、部署或付费调用权限。
