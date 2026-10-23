# 决策笔记模板

创建到 `.agents/notes/proposed/<class>/YYYY-MM-DD-topic.md`，先检索相关 ADR 和旧 Note；不要复制同一个决定。以下为 proposed 正文，真实考虑过的方案才记录。

```markdown
# Agent Note: 一个非显然取舍

Status: proposed

## Problem

什么问题需要解决，现有合同和证据是什么？

## Proposal

采用什么方案，边界、不做项和兼容方式是什么？

## Alternatives considered

备选的最强理由、为何放弃、什么条件改变后值得重访。

## Acceptance criteria

可执行的验收与反例、证据入口。

## Risks

收益、代价、未验证假设和失败后的收窄/回退。
```

落地后按[生命周期](../../docs/standards/decision-notes.md)移动并改写为 implemented；`Decision` 用现在时，`Consequences` 同时说明收益/代价，`Verification` 写实际验证和缺口。机械小改不新建 Note。
