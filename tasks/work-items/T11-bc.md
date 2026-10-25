# T11-b/c：PostgreSQL 游标、宽行和类型投影

ID: T11-b/c
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-25

## 目标与边界

来源/父任务：T11 父任务；[数据源设计](../../docs/backend/datasource.md) §6/§7、T11-a 工作项。

在 T11-a 协议上补齐：guard 输出计划的执行端校验（`PgOutputPlan`）、计划漂移终止与 trace
审计、聚合/UNION/窗口投影、100MiB 宽行有界读取、500 行游标分页内存有界、迟到取消零误杀。
不做：guard 侧 OutputPlan 生成（T07-c）；HTTP/MCP 接入；MySQL 实现与断言（零改动，本切片
基于 origin/xquan/feat-backend@2ca3d81，与 T10 分支的枚举/预算扩展无交集，合并时为同文件
枚举常量的平凡冲突）；KMS；Schema 同步。

## 实施与验证

### 生产代码（ptmcp-datasource postgres 包 + 共享枚举）

- `PgOutputPlan`（新增）：approved 请求携带的期望投影（列标签 + LogicalType），值对象，
  空/空白列名/空列表拒绝。
- `PgReadOnlyExecutor`：
  - 新增计划感知重载 `execute(poolGeneration, request, plan, cancellation)`；原签名委托
    plan=null，T11-a 调用方不变；
  - 计划校验发生在 `ResultSetMetaData` 取得后、**任何单元格读取之前**：列数/标签/派生
    LogicalType 三者与计划逐一比对，任何不一致→`PlanDriftException`→执行终止、连接 EVICT、
    trace 终态 `VERSION_DRIFT`（`ExecutionState` 新增常量）、抛 `RESULT_TYPE_UNSUPPORTED`；
  - 漂移路径与取消/超时/清理路径在终态机中显式分离，observer 恰好收到一次 trace。
- 未改 MySQL 执行器、ColumnProjection、PgExecutorSettings、共享预算常量。

### 测试（真实 PostgreSQL 18.6 容器，固定 digest；PgReadOnlyExecutorTest 12→18 例）

新增 6 例：

1. `planDriftTerminatesBeforeReadingCellsAndAuditsTheDrift`：列数/标签/类型三种漂移各自
   RESULT_TYPE_UNSUPPORTED + trace 终态 VERSION_DRIFT + trace 不含数据文本。
2. `matchingPlanAllowsTheExecution`：计划一致→正常 RETURNED。
3. `aggregateUnionAndWindowOutputsProjectSafely`：COUNT/SUM 字符串化、UNION 去重排序、
   ROW_NUMBER() 窗口输出，全部 RETURNED。
4. `hundredMiBTextFieldStreamsBounded`：100MiB text 行**不从客户端读取裸列**（pgJDBC 可能
   在流开始前缓冲整格，datasource.md §7 明示），改以服务端 `left(body,4096)` 有界前缀验证
   4KiB 输出 + 堆峰值 <96MiB。
5. `lateCancellationAfterCompletionDoesNotAffectTheNextRequest`：完成后到达的 cancel 不影响
   下一请求（零误杀）。
6. `cursorPagingStreamsFiveHundredRowsWithinBudget`：500 行游标分页 + maxRows=500，堆峰值
   <128MiB、responseLimit 未触发行边界正确。
   说明：server-side cursor 由 pgJDBC（autocommit off + fetchSize=100 + FORWARD_ONLY）语义
   提供，客户端证据为有界堆与正确分页；Wire 级 portal 证据不在驱动能力内，记录为说明。

## 结果与证据

- 定向：`mvnw -pl ptmcp-datasource -am test -Dtest=PgReadOnlyExecutorTest` → 18/18 绿。
- 模块回归：`mvnw -pl ptmcp-datasource -am verify` → BUILD SUCCESS（persistence 审计/WAL/
  安全/迁移族 + datasource MySQL/PG/元数据全绿，真实容器）。
- 仓库门禁：`python scripts/check.py` PASS；`git diff --check` 干净。

## 偏差

- 100MiB 场景从"裸列读取"改为"服务端有界前缀读取"：实测确认 pgJDBC 对未bounded text 会在
  流式读取开始前把整格载入驱动内存（单次 fetch 后堆峰值 ~295MiB），这正是 datasource.md §7
  警告的行为；设计正确的安全形状是 guard 输出计划限定服务端前缀表达式，执行端只接收 4KiB。
- domain 列拒绝（T11-a）依赖目录查询；计划漂移拒绝（本切片）依赖 wire 元数据与计划比对，
  两者共同构成"未知类型/不一致终止"。

## 未验证项

- guard OutputPlan 真实来源与列类型一致性（T07-c 接线后由 T19-b 端到端验证）。
- M0 网络延迟注入的取消竞态（T10/T11 共同遗留）。
- 固定 digest 之外的 PostgreSQL 版本。

## 交接

- T19-b 端到端：ApprovedQuery 适配器携带 `PgOutputPlan`（来自 guard）调计划感知重载；
  VERSION_DRIFT trace 进审计事件。
- 下一窗口建议：T12-b（分布式安全状态）需先解决 Redis 客户端准入，或 T16-d（审计管理 API）。
