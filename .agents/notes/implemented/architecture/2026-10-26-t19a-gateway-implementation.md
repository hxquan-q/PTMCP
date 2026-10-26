# Agent Note: T19-a 网关实现取舍——采用工作树生产接线，替代旧骨架分支

Status: implemented

## Problem

T19-a 存在两套互不兼容的实现：

- 分支 `xquan/feat-t19-a` 的 `dc16748`（2026-10-25）提供 `POST /mcp` JSON-RPC 骨架、
  `McpAuthFilter` 接受固定测试令牌、`tools/call` 返回 not-implemented，测试仅覆盖协议分发。
- 当前工作树实现提供完整边界：PAT/JWT 验证、权限交集、guard、审计 START/terminal、
  加密 WAL、真实 MySQL 执行、输出限幅与集成测试。

两套类名与文件不同（`McpProtocolController` vs `McpToolController`、
`McpAuthFilter` vs `McpAuthenticationFilter`），直接合并会留下重复路由与两套认证语义。
旧分支同时把 `T19-a` 工作项标为 `done`，但实际只是骨架，不能作为当前基线。

## Decision

采用当前工作树的 T19-a 实现，作为唯一网关基线；`dc16748` 仅保留为历史骨架，不再合并。
旧工作项中的“T19-b 接线”计划已由当前实现覆盖，后续 PG/Tier1/预算等缺口从当前工作项继续。

本记录与 [T19-a 工作项](../../../../tasks/work-items/T19-a.md) 同步；分支保留，不删除共享历史。

## Alternatives considered

1. **先合并旧骨架，再在其上补认证与执行**：旧骨架的测试固定了 stub token 与 not-implemented
   响应，补全需要替换控制器、认证过滤器、配置和测试，等于重写；还会与当前实现产生重复路由。
2. **cherry-pick 旧分支的测试/文档到当前实现**：旧测试只验证 JSON-RPC 分发，当前
   `McpQueryFlowTest` 已覆盖真实 HTTP、权限、审计与执行；仅保留少量协议断言的想法可复用，
   但无法作为独立基线。
3. **两套并存并靠配置切换**：会增加两套攻击面与运维分支，且没有产品需要两个 MCP 入口；拒绝。

## Consequences

- `xquan/feat-t19-a` 的 `dc16748` 标记为已替代，不进入当前分支历史；工作树的实现随四线整理提交。
- 当前实现仍是候选：PostgreSQL 执行、Tier1 proof、并发预算/排队、公开客户端 refresh/revoke、
  独立安全评审与四客户端矩阵未完成，`T19-a` 保持 `verifying`。
- 网关类名以工作树为准；后续文档和测试不应再引用旧骨架的 `McpProtocolController`/stub token。

## Verification

- 当前方法的集成证据见 [T19-a 结果与证据](../../../../tasks/work-items/T19-a.md)：gateway 22 例、
  查询闭环 14 例、审计韧性、WAL 与真实 MySQL 执行；本机隔离环境，未连接既有业务库。
- 2026-10-26 四线整理时复跑 `ptmcp-admin -am verify`：common 210、persistence 85、datasource 31、
  admin 45，0 失败；gateway 套件结果以 T19-a 工作项记录为准。
- 旧分支的 `McpProtocolTest`/`McpToolRegistryTest` 仅作为历史骨架证据，不参与当前验收。
