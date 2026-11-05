# DEV-20261105-mcp-protocol-upgrade：MCP 双版本边界与发现/缓存增强

ID: DEV-20261105-mcp-protocol-upgrade
Status: done
Owner: current-agent
Acceptance: accepted
Verification: PASS
Updated: 2026-11-05

## 目标与边界

来源/父任务/用户请求：
用户要求对照 DBX 与 MCP 最新官方仓库调研当前网关，并完成可行改进。研究证据见
[research/mcp-official-latest-2026-11-05.md](../../research/mcp-official-latest-2026-11-05.md)。

目标、当前与期望行为（Given/When/Then）：
- Given `MCP-Protocol-Version` 为 `2025-06-18` 或 `2026-07-28`，When POST `/mcp`，Then 单头放行，未知/重复仍 400。
- Given legacy `initialize` 正文请求版本，When 服务协商，Then 回显请求中受支持的版本；不支持或缺失仍保留 `2025-06-18` 合同。
- Given modern `server/discover` 或 `tools/list`，When 以 `2026-07-28` 头调用，Then 返回 `server/discover` 或带 `resultType`/`ttlMs`/`cacheScope` 的 `tools/list` 结果。
- Given 配置了 OAuth 资源元数据，When 匿名发现或收到 401，Then 文档含 `scopes_supported`，challenge 含 `scope`。

不做项、不变量、兼容/安全边界：
不实现 `Mcp-Method`/`Mcp-Name`、无状态协议的完整 `server/discover`、新版取消流差异或会话/SSE；不放宽 Bearer、平台证明、Origin、guard、审计或每请求权限。保留 `2025-06-18` 老客户端兼容；不覆盖工作树中其他主题修改。

已有修改和所有权：
工作树含 ptmcp-admin、research、tasks/wayfinder、图片等未提交内容，保持不动。本片独占 gateway MCP 源码/测试、`mcp-tools.json`、`api-spec.md`、`deploy.md` 与本工作项。

## 实施与验证

依赖、读写路径、独占契约：
读取 `ptmcp-parent/AGENTS.md`、`ptmcp-parent/ptmcp-gateway/AGENTS.md`、MCP API 契约与既有 17 项快速套件。源码修改限于 gateway。

串行/并行结论及负责人；并行时记录授权和工作区：
主 Agent 串行实施，不委派业务写入。

当前切片、失败/基线、实施和回滚/收窄路径：
先加/改测试确认当前单版本失败，再实施：
已实施：
1. `McpHttpPolicy` 双版本头；`McpToolService` legacy `initialize` 版本协商 + modern `server/discover`/`tools/list`（`resultType`/`ttlMs`/`cacheScope`）。
2. `McpResourceMetadata` document 加 `scopes_supported`，challenge 加 `scope`。
3. guard `relationColumns` 预构建 `Map<Long, List<ColumnDef>>`，按 objectId O(1) 查列。
4. `mcp-tools.json`/`api-spec.md` §7/`deploy.md` 同步。

验收标准、实际存在的验证命令/环境；缺失条件标明：
Windows 进入 `scripts/Enter-Dev.ps1` 后运行 gateway 定向 Spotless 与快速 MCP 套件；仓库根 `python scripts/check.py`。真实客户端矩阵、WSL/Linux Testcontainers 与性能压测如未运行须明确 NOT_RUN。

## 结果与证据

实际修改、命令/退出码、覆盖行为和证据路径：
`McpToolService`、`McpHttpPolicy`、`McpResourceMetadata` 与三个测试文件已按方案修改；
`mcp-tools.json` 版本号/日期、`api-spec.md` §7 双版本与缓存提示说明、`deploy.md` 传输行同步；
新增 `.agents/notes/implemented/architecture/2026-11-05-mcp-dual-version-transport.md`。
Windows + JDK21（`D:\config\jdk\jdk21`）下进入 `ptmcp-parent` 依次运行：
- `mvnw -pl ptmcp-gateway -am spotless:apply` → 退出 0；
- `mvnw -pl ptmcp-gateway -am compile test-compile` → 退出 0；
- `mvnw -pl ptmcp-gateway -am -Dtest=McpTransportHttpTest,McpToolMetadataTest,McpHttpPolicyTest,McpHttpBoundaryTest,McpResourceMetadataHttpTest,McpResourceMetadataTest,McpGatewayConfigurationHttpStartupTest -Dsurefire.failIfNoSpecifiedTests=false test` → 退出 0（37 项，0 失败/0 错误/0 跳过）；
- 仓库根 `python scripts/check.py` → 退出 0（docs 173 md / 845 链接 / 4 MCP 工具；workflow、quality config、commit、74 个脚本单测全 PASS）。
首轮新 `McpTransportHttpTest` modern 测试在 `verifyNoInteractions(tools)` 处失败（前两个 modern 请求已触达 dispatcher），已移除该行并重跑 GREEN。

未执行项及原因、剩余风险：
真实外部 Agent 客户端矩阵、WSL/Linux Testcontainers 完整集成、生产部署联调与性能压测均未运行，
不能视为协议合规或生产发布。modern 路径的 `Mcp-Method`/`Mcp-Name` 路由头校验与
`subscriptions/listen` 长通知流仍为后续切片；性能与大快照行为依赖后续压测确认。

评审类型（自审/独立）、发现与修复：
自审；不是独立安全评审。

人工接受来源；内部任务不适用时说明原因：
内部工程切片，人工接受待用户复核。

## 交接

当前焦点、剩余项和下一步：
剩余项：modern 路由头校验、取消流差异、真实客户端矩阵、性能压测、生产部署联调。
工作树另有 ptmcp-admin 等 9 个修改文件与多个未跟踪文件，属其他主题，保持不动；
本片 git 提交/推送按仓库授权执行。

实际 git status、提交/集成/发布事实：
待实施后填写。

已有授权与待决定事项：
用户已授权调研后开发；常规提交推送依仓库授权执行，安全关键/发布另需授权。
