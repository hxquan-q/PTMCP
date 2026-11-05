# Agent Note: MCP 双版本传输与 modern 无状态支持

Status: implemented

## Problem

当前网关固定协议版本 `2025-06-18`，不支持 MCP 2026-07-28 无状态协议。新协议要求：`server/discover` 方法、每请求 `_meta` 协议版本、`resultType` 字段、`ttlMs`/`cacheScope` 缓存提示和 `Mcp-Method`/`Mcp-Name` 路由头。同时 legacy `initialize` 版本协商未回显请求版本，guard `relationColumns` 在 O(n×m) 嵌套循环中查列定义，OAuth challenge 未声明 `scope`。

## Decision

网关双时代服务：legacy `2025-06-18` 语义不变；新增 modern `2026-07-28` 请求路径。

- `McpHttpPolicy` 接受两个版本头；其余不变（无头仅 `initialize` 放行）。
- `McpToolService` dispatch 增加 `server/discover`（返回 `supportedVersions`、capabilities、serverInfo）；`tools/list` 外层加 `resultType:"complete"`、`ttlMs:300000`、`cacheScope:"private"`。
- legacy `initialize` 版本协商：正文 `params.protocolVersion` 为受支持版本时回显该版本，否则回显 `2025-06-18`。
- guard `relationColumns` 预构建 `Map<Long, List<ColumnDef>>`，按 objectId O(1) 查列。
- `McpResourceMetadata` document 加 `scopes_supported`，challenge 加 `scope` 参数。
- 不实现 modern 的 `initialize`/`ping`/`notifications/cancelled`（取消由客户端关闭响应流承载）、`Mcp-Method`/`Mcp-Name` 路由头校验或 `subscriptions/listen`；认证、guard、审计与审批语义不变。

## Alternatives considered

- 仅更新到 `2026-07-28` 并删除 legacy：会拒绝现有 `2025-06-18` 客户端，不满足增量迁移需求。
- 仅在 legacy `initialize` 中回显 `2026-07-28`：modern 客户端要求 `server/discover` 和每请求 `_meta`，仅靠 `initialize` 无法合规；必须在 dispatch 增加独立 modern 路径。
- 将 `ttlMs`/`cacheScope` 放入每个 tool descriptor：官方规范明确为 `tools/list` 结果层字段，不是 descriptor 属性。

## Consequences

新 modern 客户端可无状态调用 `server/discover` 和 `tools/list`，获得缓存提示减少重复拉取；legacy 客户端行为不变。guard 列查找从 O(n×m) 降到 O(1)，大快照执行路径开销下降。OAuth 客户端可在 challenge 中直接读到所需 scope，减少误请求过度授权。modern 路径未校验路由头和取消流，完整 2026-07-28 合规需后续切片；性能门禁和大快照压测未运行。

## Verification

定向快速 MCP 套件 GREEN（编译+测试）；`python scripts/check.py` 仓库门禁 PASS。真实客户端矩阵、WSL/Linux Testcontainers 完整集成和性能压测未运行，不能视为协议合规或生产发布。
