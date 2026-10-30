# DEV-20261031-datasource-discovery：外部 Agent 数据源发现工具 list_datasources

ID: DEV-20261031-datasource-discovery
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-10-31

## 目标与边界

来源：用户在 DBX 对比核查后确认"要跟 dbx web 一样对外部 agent 提供数据库访问服务，同时保持可管控性"，
并按 `/test-driven-development` 立项实施。此前结论：网关三工具（list_databases_and_tables、
get_table_schema、execute_readonly_sql）均要求 `datasource_id`，外部 Agent 无入口得知自己可用哪些数据源——
即 DBX `dbx_list_connections` 对应能力缺失。

本轮新增第四个 MCP 工具 `list_datasources`：空输入 schema，复用 `mcp:catalog` scope；仅返回
"可用已发布数据源（ACTIVE + active_snapshot 指向同 config_version 的未过期 PUBLISHED 快照）中至少
有一个对象对调用者可见"的条目；可见性判定与 list_databases_and_tables 完全同一谓词（DISCOVER：
平台可见 ∧ 无显式 META Deny；HIDE：用户对对象持有 META 或 READ）；输出仅
datasourceId/code/name/engine/databaseName/queryable（queryable=任一对象 READ），
绝不暴露主机、端口、账号或凭证；审计 tool=`list_datasources`、datasourceId 为空，START→terminal
时序与既有工具一致。

不在本轮：动态注册客户端、新 scope、列级权限、跨源联合、前端改动（React 不读 mcp-tools.json）。

不变量：权限按平台与用户交集、Deny 优先、guard 前零业务连接、主库 START 准入、terminal 持久后返回。

## 实施与验证

RED（先测后码，均在隔离工作区 /var/tmp/ptmcp-readiness-20261031.QbgQcI，基准 5047728）：

- RED-1 全栈运行时：`McpQueryFlowTest.listDatasourcesReturnsTheSeededConnectionForThePatHolder`
  对旧网关调用 list_datasources → `-32602 Invalid params`（未知工具拒绝），证明边界缺口真实存在。
- RED-2 编译期：`McpDatasourceDiscoveryTest`（新，直连 reader/权限服务的夹具级测试）
  → `cannot find symbol: class DatasourceDiscoveryService`。

GREEN 实现切片：

- `PublishedSnapshotReader.listUsable()`：一条 JOIN 查询返回可用数据源（复用 load() 的可用性口径）。
- `DatasourceDiscoveryService`（gateway 新类）：listVisible(userId, platformId) 逐源判定可见性并给出
  queryable，不触业务连接。
- `McpToolService`：TOOLS/描述符/分发/listDatasources（审计先行、预算校验、错误路径 terminal）。
- `AuditStart.SUPPORTED_TOOLS` 闭合集加入 list_datasources（RED-1 修复过程中的真实根因：
  闭合集拒绝 → INVALID_ARGUMENT 逃逸 → 控制器 500；AuditTerminal 复用同集，DB 无 tool 约束）。
- 契约文档：mcp-tools.json v1.1-design（四工具、首个为 list_datasources）、总体方案 §5、api-spec §7、
  user-manual §5、README、audit.md（发现类工具 datasourceId 为空）、validate_docs.py 期望四工具。

夹具修复（测试自身与约束对齐，非产品改动）：catalog_mode 种子值须为约束枚举 DISCOVER/HIDE（非 STRICT）；
过期快照须满足 `expires_at > captured_at` 约束（2 天前捕获、1 天前到期）。

## 结果与证据

GREEN 证据（WSL PTMCP-Dev，Maven 3.9.9 离线，JDK21，隔离工作区）：

- `McpQueryFlowTest` 38/38 PASS（含新工具全栈用例与四工具 tools/list 契约比对 mcp-tools.json）。
- `McpDatasourceDiscoveryTest` 3/3 PASS：HIDE 模式按用户交集过滤、DISCOVER 模式平台可见 + queryable
  区分、DISCOVER 下全对象 META Deny 整源隐藏；不可用（无快照/过期）数据源任何模式不出现。
- 文档门禁 `validate_docs.py` PASS（mcp_tools=4，errors=[]）。

全量门禁取证与基线缺陷修复：

- 首轮全 reactor `verify`：common 234 绿，persistence 19 中 1 失败——
  `SnapshotPublicationMigrationTest.upgradesV7WithoutTrustingPollutedResources`（expected 7 but was 1），
  反应堆在 persistence 中止，后续五模块 SKIPPED。
- 根因（git 取证，非本切片引入）：`fefc822`（在基线 5047728 内）给二级 migrate 加了 `.target("8")`，
  但该测试本质是 V7→最新全量升级验证（109 行后断言依赖 V14 产物 datasource_candidate 七列、
  metadata_sync_job.candidate_id、policy_epoch=2）；不设 target 时 V8..V14 恰好 7 个迁移（fefc822 前绿态），
  固定 target=8 后仅 1 个，断言恒失败。提交信息的"固定目标版本"意图与实现方向相反。
- 修复：单行删除 `.target("8")`（恢复最后已知绿态，非放宽断言）；已同步对等窗口 datachat-mcp-d3
  （其确认不占用该文件），随本切片单独成 commit。

REFACTOR：无必要改动（服务类与 reader 职责已按现有风格分离）。

全 reactor `verify`（修复后重跑，2026-10-31，同一隔离工作区）：8 模块全 SUCCESS
（common 234、persistence、guard、datasource、admin 221、authz 58、gateway 115——含
McpQueryFlowTest 38 与 McpDatasourceDiscoveryTest 3），BUILD SUCCESS，Total time 11:48 min。
Verification 终值 PASS 即此结论。

## 交接

- 归属勘误（4132a00）：该提交中 docs/backend/api-spec.md 的差异含两处归属——§7 list_datasources
  契约段落属本切片；约 112 行 PlatformWrite `proofConfig` 仅写段落属 DEV-20261031-audit-fixes 窗口
  （其提交前编辑发生在本切片归属核查之后，整文件暂存时被一并带入落库，来源窗口已确认不回退不改写）。
  该段落的行为契约由归属窗口验证与验收，本切片的门禁结论（validate_docs PASS、全 reactor verify）
  不构成对其安全切片的验收；其时序更早的基线内容其余未受影响。
- 多窗口边界：本轮提交仅含 ptmcp-parent 后端 6 文件、docs 7 文件与本工作项及迁移测试单行修复；
  React 窗口文件（含 dateTime.ts、platformForm.ts、tests/browser/ 等）、gateway pom.xml、
  PlatformProofRoundTripTest、PlatformAdminService、authentication.md、DEV-20261028-gateway-completion.md
  与 DEV-20261031-audit-fixes.md 归属其他窗口，未纳入。
- 本轮属自审；独立评审按仓库规则另行安排。
- 后续候选（未立项）：list_datasources 分页/过滤参数、DISCOVER 模式下按平台分组的目录前缀。
