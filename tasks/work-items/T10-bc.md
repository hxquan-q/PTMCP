# T10-b/c：MySQL 执行器代际、取消与输出预算

ID: T10-b/c
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-25

## 目标与边界

来源/父任务：T10 父任务；[数据源设计](../../docs/backend/datasource.md) §2/§7/§8、T10-a 工作项。

在 T10-a 只读执行协议上补齐执行治理：审计/安全准入门、五阶段 timeout（排队/连接/执行/读取/
清理）、execution owner 与 generation 不匹配隔离、输出字节预算、UNKNOWN 终态、未知输出类型
拒绝。不做：PostgreSQL（T11）；HTTP/MCP 接入；guard/ApprovedQuery 适配（T07-c/T19-b）；
persistence 侧审计 START 与安全状态的真实实现接线（T19-b，本切片定义 seam）。

## 实施与验证

### 生产代码（全部 ptmcp-datasource 模块内）

- `ExecutionAdmission`（新增）：`requireAdmission(datasourceId, executionId)` 门——审计准入
  与安全状态检查的注入点；**生产构造器必须注入**（借连接前调用，拒绝即绝不触碰业务池）；
  `internalPermissive()` 仅限模块内部既有测试脚手架，注释明示不可用于生产接线。
- `MysqlReadOnlyExecutor`：
  - 借连接前调用 admission；拒绝时业务连接数为 0、生命周期 trace 不产生；
  - 阶段升级为 QUEUE→CONNECT→CHECKOUT→CONFIGURE→EXECUTE→READ→DRAIN→CLEAN→RETURN
    （+CANCEL/EVICT/QUARANTINED），trace 保留单调时钟耗时；
  - 读取阶段 wall-clock read 预算（`ExecutorSettings.readTimeoutMillis`）背靠服务端
    `max_execution_time`：超时按受控中断处理（取消语句→EVICT→QUERY_TIMEOUT）；
  - 输出字节预算（`ExecutorSettings.outputBudgetBytes`，默认 1MiB、硬界 64KiB..8MiB）：
    DRAIN 期间按单元格 UTF-8 字节累计，超限受控停止→EVICT→`SQL_BUDGET_EXCEEDED`；
  - generation/owner 陈旧隔离：RETURN 前校验 `handle.state()==ACTIVE` 且
    `registry.current().generation()==handle.generation()`，不满足→abort、trace
    `QUARANTINED`、执行结果不受影响；UNKNOWN 类运行时异常→`INTERNAL_ERROR`+EVICT，
    内部细节不外泄。
- `ExecutorSettings`：新增 outputBudgetBytes/readTimeoutMillis 组件；原三参构造保留
  （read 跟随 SQL 预算、输出默认 1MiB），T10-a 用法不变。
- `ReadOnlyRequest`：新增 `OutputKind`（仅 ROWS）；`of(sql,maxRows,kind)` 对 null/未知
  输出类型拒绝；原 `of(sql,maxRows)` 委托 ROWS。
- `ExecutionStage`/`ExecutionState`：+QUEUE/CONNECT/READ、+QUEUED/CONNECTED/READING/
  QUARANTINED。既有 T10-a 生命周期断言随五阶段同步升级（本切片的核心行为变化，
  非断言弱化：每个阶段仍断言顺序与耗时非负）。

### 测试（真实 MySQL 8.4 容器；MysqlReadOnlyExecutorTest 9→15 例）

新增 6 例：

1. `admissionFailureRejectsBeforeAnyBusinessConnection`：准入拒绝→AUDIT_UNAVAILABLE、
   计数工厂业务连接数 0、无 trace 产生。
2. `outputByteBudgetStopsTheFetchAndQuarantinesTheConnection`：20 行×20KB 对 64KiB 预算→
   SQL_BUDGET_EXCEEDED、连接 EVICT、池存活下一请求成功。
3. `largeFieldUnicodeDuplicateLabelsAndEmptyResultsAreSafe`：300K 字符 LONGTEXT（中日文/
   emoji/变音符）、重复列标签 ×3、空结果集，全部 RETURNED。
4. `staleGenerationMidExecutionQuarantinesInsteadOfReturning`：执行中 `beginDrain` 标陈旧→
   查询成功但连接 abort、trace QUARANTINED（说明：不走 `registry.activate` 触发，因其会
   立即关闭旧池中断在途查询，见偏差）。
5. `unknownInternalFailureMapsToUnknownTerminalAndNeverReturnsTheConnection`：注入
   createStatement 运行时异常→INTERNAL_ERROR+EVICT，"boom" 不外泄。
6. `unknownOutputKindAndNewBudgetBoundsAreRejected`：null 输出类型、输出/读取预算越界拒绝。

既有 9 例全部保持绿（生命周期断言按五阶段升级；超时/取消路径状态序列加入
QUEUED/CONNECTED/READING）。

## 结果与证据

- 定向：`mvnw -pl ptmcp-datasource -am test -Dtest=MysqlReadOnlyExecutorTest` → 15/15 绿。
- 模块回归：`mvnw -pl ptmcp-datasource -am verify` → BUILD SUCCESS（datasource 15+12 PG+
  元数据 10 全绿，真实 MySQL 容器）。
- 仓库门禁：`python scripts/check.py` PASS；`git diff --check` 干净。

## 偏差

- **输出字节超限按拒绝实现**（SQL_BUDGET_EXCEEDED），datasource.md §7 的 RESPONSE_LIMIT
  截断标志语义归 T19-b OutputPlan/JSON-RPC 编码层；届时若改为截断语义，需同步本层。
  SQL_BUDGET_EXCEEDED 为既有码（150004，guard 预算语义），未新增错误码。
- 隔离触发用 `beginDrain()` 而非 `registry.activate`：后者立即 close 旧池会杀死在途连接
  （Hikari 语义），无法构造"查询成功但连接隔离"的场景；T19 副本切换时的真实行为是
  在途执行失败（T10-a 已观测），由 UNKNOWN/DRIVER_ERROR 终态覆盖。
- read 预算与 SQL 预算当前同源（服务端 max_execution_time 覆盖执行+取数），readTimeout
  是墙时钟兜底，不是独立取消定时器（遵守 T10-a 删除驱动侧 queryTimeout 的证据）。

## 未验证项

- M0 网络延迟注入下的迟到取消竞态（同 T10-a 遗留，需专项环境）。
- ExecutionAdmission 的真实 persistence 实现（audit START 持久确认 + SecurityStateReader）
  在 T19-b 接线；本切片以 seam+fail-closed 语义与零连接数证据为界。
- 2C4G 内存画像下的 fetchSize/effectiveFetchSize 推导（§7 归 M0 profiler）。

## 交接

- 下游接线（T19-b）：ApprovedQuery 适配器调 `of(sql,maxRows,OutputKind.ROWS)`；生产执行器
  必须用四参构造注入 admission（审计 START 持久确认 + 安全快照新鲜度检查）。
- T11-a/b/c（PostgreSQL）可直接复用 ExecutionStage/State/Admission/Settings 的新形状。
- 未提交项：无；本切片以单 commit 交付。
