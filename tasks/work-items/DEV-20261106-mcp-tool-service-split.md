# DEV-20261106-mcp-tool-service-split：拆分 McpToolService

ID: DEV-20261106-mcp-tool-service-split
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: NOT_RUN
Updated: 2026-11-06

## 目标与边界

用户授权的安全关键重构：把 `McpToolService`（原 1763 行）中与编排无关的纯函数拆到同包
package-private 协作类，**零行为变化**。

- `McpToolCatalog`：工具描述符、参数 schema、`listResult`、分页/行数上限常量。
- `McpToolArguments`：参数解析（required/optional text/int、text 数组、request id）与游标编解码。
- `McpToolResults`：结果封装（content/错误内容/callTool 结果、initialize/discover/cacheable list、
  JSON-RPC envelope 与错误、响应预算检查）。

不变：identity → permission → guard → audit → execution 顺序；START 审计先于 guard；
借连接前与回调内双重 revalidate；guard 拒绝不借业务连接；AUDIT_UNAVAILABLE 一律上抛；
取消与配额 finally 释放。上述编排代码仍全部留在 `McpToolService`，只把调用改为限定类名。
`Stage` 由 private 改为 package-private（`errorContent` 外移需要）。

## 实施与验证

- 移出原 1387–1750 行，代码逐字搬运，仅调整可见性与类名限定。
- `dictionaryDescriptor` 对 `orderedMap` 的调用改为 `McpToolCatalog.orderedMap`（首轮编译暴露）。
- 检查：`ptmcp-parent` 下 `mvnw -pl ptmcp-gateway -am verify`；仓库根 `python scripts/check.py`。
- 评审类型：自审（非独立评审）。

## 结果与证据

- 2026-11-06（另窗复核后填写）：`mvnw -pl ptmcp-gateway -am -DskipTests install` 中 common/persistence/guard/datasource/admin/gateway 编译、Spotless、Checkstyle 全部 SUCCESS（repackage 因运行中的 28082 实例 `java -jar` 锁住同名 jar 无法改名而失败，非代码问题；测试不受影响）。
- `mvnw -pl ptmcp-gateway test`（依赖取自刚安装的快照，JDK 21 + Docker WSL 中继 12476）：**140/140 通过，0 失败 0 错误，BUILD SUCCESS**，覆盖 McpQueryFlowTest / McpTier1ProofTest / ToolPermissionDenyTest / McpDatasourceDiscoveryTest / McpAuditResilienceTest 等全部网关行为测试。
- 同日全仓 `-am test` 首跑：persistence 两个迁移测试（OAuthAuditSchemaMigrationTest、SecurityChangeTransactionTest）因本机 loopback 抖动连接容器 MySQL 失败（已知 mihomo 环境问题），其余全绿；persistence 模块本切片未改动，不构成阻塞。
- 当日另窗以拆分后代码实测 MCP 端到端：initialize/tools/list/三工具调用、权限拒答、审计留痕全部符合拆分前行为（证据见 DEV-20261028-gateway-completion 的 2026-11-06 演示节）。
- 未完成完整 `verify`（repackage 文件锁）；如需补跑：停掉 28082 网关后再执行 `mvnw -pl ptmcp-gateway -am verify`。

## 交接

未验证：真实 MCP 客户端联调、人工验收。拆分后无新增测试，行为由既有 gateway 测试覆盖。
