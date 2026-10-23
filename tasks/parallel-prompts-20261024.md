# 剩余后端工作的多窗口并发 Prompt 集

日期：2026-10-24（UTC）。基线：`origin/xquan/feat-backend` @ `7dcbd88`（如协调者已推进，以开工时 `git fetch` 后的最新提交为准）。每个 Prompt 可直接粘贴到一个新窗口执行；同一波内文件互不重叠，可真正并发。

## 使用方式

1. 每个窗口先粘贴一次“通用前置”，再粘贴对应切片 Prompt。
2. 同一波（Wave）可同时启动；下一波必须等上一波对应切片已合入 `xquan/feat-backend`（由协调者窗口合入）。
3. 每个窗口只推送自己的分支，不合并、不建 PR、不改共享文件；协调者按序评审 + cherry-pick + 组合重验 + 推送。
4. 标 `BLOCKED-EXTERNAL` 的切片缺真实资产/客户端/域名：只能产出可运行骨架与 NOT_RUN/BLOCKED 证据，不得伪造通过。

## 依赖关系一览

| 波次 | 切片 | 依赖 | 说明 |
| --- | --- | --- | --- |
| A | A1 T05-c | 无 | guard 预算与解析隔离，新建 guard/budget |
| A | A2 T06-b | 无 | guard/scope 列绑定与星号展开 |
| A | A3 T08-b | 无 | common/authz 继承与资源树展开 |
| A | A4 T17-api-a | 无 | admin REST 基座（R<T>、请求ID、错误映射） |
| A | A5 T03-b | 无 | persistence V2 迁移：组织/平台/资源/ACL |
| A | A7 T03-c | 无 | persistence V3 迁移：OAuth/PAT/会话/审计/幂等/租约 |
| A | A6 T10-a | 无 | 新建 datasource 模块 + MySQL 只读执行协议（独占父 POM modules） |
| B | B1 T07-b | A2 | 限定名/列引用改写与重解析等价 |
| B | B2 T09-a | A7 | 审计 START/terminal 模型与仓储 |
| B | B3 T11-a | A6 | PostgreSQL 执行与类型投影/宽行限幅 |
| B | B4 T13-a | A5, A7 | OAuth 基线（AS 会话、code、JWKS） |
| B | B5 T15-a | A5, A6 | Schema 同步 job 与原子快照发布 |
| C | C1 T07-c | B1 | ApprovedQuery 封闭构造与生命周期 |
| C | C2 T09-b | B2 | 加密 WAL、fsync、UNKNOWN 恢复 |
| C | C3 T12-a | B2, B3 | 安全快照时效、撤销、配额 |
| C | C4 T14-a | B4 | Tier1 逐请求证明 + DCR/CIMD |
| C | C5 T16-a | A5, B2 | 组织/角色/ACL 管理 API |
| C | C7 T15-b | B5 | 数据源管理、字典 API |
| C | C6 T17-api-b | A3, A4, B2, B4 | /session 与 /me 接口 |
| D | D1 T13-b | B4, B2 | 公开客户端刷新轮换与 revoke 扩展 |
| D | D2 T18 | C1, B3 | 内部回归资产（BLOCKED-EXTERNAL：私有 29VQ） |
| D | D3 T19 | D1, C4, C6 | 四客户端矩阵（BLOCKED-EXTERNAL） |
| D | D4 T20 | C1, C2, C3, D1 | fuzz/压测/恢复演练 |
| E | E1 | 全部 | 协调者集成与最终验收审计 |

## 通用前置（每个窗口先粘贴一次）

```text
你在 C:/Users/xquan/Desktop/Datachat-mcp 工作。先读根 AGENTS.md、ptmcp-parent/AGENTS.md、
docs/standards/agent-workflow.md、docs/standards/git-rule.md、tasks/plan.md 与 tasks/work-items 中相关切片，
再读本 Prompt 点名的契约文件；不要全量扫文档库。

环境（Windows PowerShell）：
  Set-Location C:\Users\xquan\Desktop\Datachat-mcp
  . .\scripts\Enter-Dev.ps1
  .\scripts\Dev-Services.ps1 start        # 需要 MySQL/PG/Redis 时
禁止绕过 Enter-Dev 使用系统 Java8 构建。

隔离：git fetch origin，然后
  git worktree add .local/worktrees/<切片ID> -b xquan/<短横线分支名> origin/xquan/feat-backend
只写本 Prompt 列出的路径；任务记录写 tasks/work-items/<你的ID>.md（按模板：目标与边界/实施与验证/结果与证据/交接）；
本机证据写该 worktree 的 .local/verification/（忽略目录，不提交）。

共享文件只读，不得修改：tasks/plan.md、tasks/todo.md、README.md、AGENTS.md、docs/backend/*、docs/architecture/*、
docs/test/*、docs/evidence/m0-gate-register.md、docs/evidence/asvs-checklist.md、config/quality/rest-error-codes.json、
scripts/**、ptmcp-parent/pom.xml 的 modules（仅 A6 可改一次）、已冻结的 common/resource 与 common/error 类型。
需要新增错误码或改共享契约/父 POM（非 A6）时：停止并在报告中提出，不得自行改。

禁止：改动或提交视觉在制品（.impeccable/、DESIGN.md、PRODUCT.md、docs/frontend/design/、tasks/work-items/T17-sap-visual.md）；
提交 target/dist/node_modules/凭据/真实业务 SQL 或结果；连接或改写既有 ptmcp_dev 库；用 skip/H2/mock 冒充实库或业务通过；
删除或放宽既有断言；在 guard 完整前新增任何可执行查询入口（HTTP/MCP 均不得借业务连接）。

TDD：先写会失败的测试并保留真实失败证据，再最小实现；用实际 Spotless 格式化。命令：
  cd ptmcp-parent; .\mvnw.cmd -B -ntp -pl <模块> -am spotless:apply
  .\mvnw.cmd -B -ntp -pl <模块> -am verify
  cd ..; python scripts/check.py --all --maven-cache-seed D:/config/ptmcp/cache/maven
必须全绿且无 skipped 才提交。实库测试用 Testcontainers 固定镜像：
  MySQL mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69（实测 8.4.13）
  PostgreSQL postgres@sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6
随机凭据只存内存；缺 Docker 记 FAIL/BLOCKED，不得 skip。

完成：Conventional Commit（type(scope): 中文具体动作，正文写问题/最终行为/验证证据）；提交前 git diff --check、
确认暂存清单无秘密与无关文件；git push -u origin <你的分支>。不合并到 xquan/feat-backend、不建 PR、不发布。
最终回复必须含：切片ID、分支、commit SHA、改动文件清单、真实命令与结果（含失败→修复）、未验证项、远端 SHA 回显。
```

## A1 · T05-c：AST 预算与解析隔离

```text
切片：T05-c（父 T05）。写：ptmcp-parent/ptmcp-guard/src/main/java/local/ptmcp/guard/budget/**、
对应 src/test 同包、tasks/work-items/T05-c.md。不改 guard/pom.xml（仍只依赖 common + JSQLParser）。

契约：docs/backend/sql-guard.md §1（64KiB 输入、AST 深度 64、物理关系引用 64、解析受隔离任务与超时约束、
Future.cancel 不算证明）、§8 预算行。实现：
1) BoundedParser：在专用有界线程池中解析，带超时；超时/线程无法确认终止时隔离该 worker 并拒绝后续使用，
   返回 SQL_BUDGET_EXCEEDED；池容量固定、有界队列、命名线程、可关闭。
2) AstMetrics：显式遍历 Select/Expression/Comment 树统计最大深度（含子查询、UNION 分支、CTE 定义体、
   CASE/函数参数），不依赖 parser 内部字段；深度 >64 → SQL_BUDGET_EXCEEDED。
3) RelationBudget：接收 ScopeTableBinder.BindingResult 的物理引用列表（保留重复），>64 → SQL_BUDGET_EXCEEDED；
   unresolved 非空时由调用方拒绝，本类只统计。
4) 解析失败映射 SQL_SYNTAX_INVALID；错误不回显 SQL。

RED（先写测试）：深度 64 通过 / 65 拒绝；引用 64 通过 / 65 拒绝；边界精确（64 不可 off-by-one）；
超时注入（用 1ms 超时 + 接近 64KiB 的构造输入，或注入可控慢解析 hook）必须返回预算错误且池可继续服务；
解析线程异常不泄漏到调用方；关闭后拒绝新任务。
证据：保留首次失败日志；报告里给出实测深度/引用计数与耗时。词法/语句形状/白名单已有实现，不改其断言。
```

## A2 · T06-b：列引用绑定与星号展开

```text
切片：T06-b（父 T06）。写：ptmcp-parent/ptmcp-guard/src/main/java/local/ptmcp/guard/scope/ 下**新增**列相关类型
（如 ColumnDef、RelationColumns、ColumnBinding、ColumnBinder），对应 src/test 同包，tasks/work-items/T06-b.md。
可只读引用既有 ScopeTableBinder；不得修改其 R1 断言或行为（如需改动先在报告中提出）。
不改 pom.xml。

契约：sql-guard.md §4（列绑定顺序、限定/非限定解析、歧义拒绝、`t.*` 绑定到特定 range、星号按快照展开、
CTE/派生列来源记录）、§6（改写必须使用绑定，不能字符串替换）。

实现：
1) ColumnDef(ordinal, name, databaseType, nullable) 快照列定义；RelationColumns 接口
   `List<ColumnDef> columns(ResourceRef)`，由调用方提供快照数据，guard 自身不连库。
2) RangeBinding(alias, kind=PHYSICAL|CTE|DERIVED, ref/输出列)；作用域栈带 parent 链。
3) ColumnBinder.bind(Select, ScopeTableBinder.BindingResult, RelationColumns)：
   - 输出每个查询块的 range 列表与列绑定；未限定列在当前块候选唯一才绑定，多候选 → RESOURCE_AMBIGUOUS，
     未找到才沿相关子查询 parent 链查找，仍无 → INVALID_ARGUMENT（工作项说明该码选择）。
   - `*` 展开为该 range 的有序列（物理表用快照列序）；`t.*` 只绑定到该别名的 range；未知别名拒绝。
   - CTE/派生表输出列记录来源列集合（用于后续等价校验），不把派生别名当物理表。
   - 未知表（BindingResult.unresolved 非空）时不进入列绑定，直接返回可判定的拒绝。

RED：必须包含 docs/test/test-case.md TC-GRD-003/007/008 的列子集：`s.*` 保持绑定、自连接 a/b 别名、
`db.table.column` 与相关子查询外层列、内层同名列歧义拒绝、`t.*` 未知别名拒绝、重复输出列名按 index 保留。
证据：断言具体绑定（别名+列名+来源），不只断言“没抛异常”。不实现改写（B1 负责）。
```

## A3 · T08-b：主体继承、资源树与 Scope 展开

```text
切片：T08-b（父 T08）。写：ptmcp-parent/ptmcp-common/src/main/java/local/ptmcp/common/authz/ 下**新增**类
（如 SubjectScope、ResourceNode、GrantRow、GrantExpander），对应 src/test 同包，tasks/work-items/T08-b.md。
不得修改已冻结的 PermissionGrant / PermissionEvaluator 行为与断言；不改 pom.xml（common 仍禁 Spring）。

契约：authorization.md §2/§3（Allow 来源=个人/有效角色/启用部门及上级；先资源树展开再时间区间与 Deny；
平台 Scope 只作上限）、总体方案 §4（READ 隐含 META、META Deny 阻断 READ、停用角色不贡献 Allow、默认拒绝）。

实现（纯函数，无 JDBC）：
1) SubjectScope(userId, enabledRoleIds, deptId, deptAncestorIds)：只有启用角色/部门进入集合。
2) ResourceNode(id, parentId) 资源树；GrantRow(subjectType, subjectId, resourceId, action, effect,
   includeDescendants, validFrom, validTo, grantId)。
3) GrantExpander.expand(grants, subject, resourceTree, targetResourceId) → List<PermissionGrant>：
   个人/角色/部门（含上级部门）命中；includeDescendants 只向子树下传，不向上；
   目标资源或主体不匹配的行丢弃；重复行按 grantId 去重；不改写时间窗（交给 evaluator 判定）。
4) 文档明确：平台 Scope 不在本类计算，调用方仍需与 platformMeta/platformRead 相交。

RED：角色 Allow + 个人 Deny（Deny 生效）；部门父级 Allow 继承到子部门但不下传到无关部门；
includeDescendants=false 不影响子树；停用角色（不在 enabledRoleIds）不贡献；过期行仍返回但 evaluator 判 false；
有效行去重后 grantId 唯一；目标资源不匹配全部丢弃。
```

## A4 · T17-api-a：HTTP 边界原语（不含响应封装）

```text
切片：T17-api-a（父 T17）。写：ptmcp-parent/ptmcp-admin/src/main/java/local/ptmcp/admin/web/**、
对应 src/test 同包、tasks/work-items/T17-api-a.md。不改父 POM 或其它模块。

**关键约束**：不要新建 org.dromara.* 类，也不要自造与若依 R<T> 同形/同名的安慰性外壳。
现行 ADR0010 要求复用真实上游 R<T>，而官方发布构件在 Maven Central 不可得（已核实 404），
因此本切片只做协议无关的边界原语；响应封装的落地方式由协调者决定（vendor 上游源码或其它），
遇到需要封装时停下来在报告中提出。

实现（只做这四件）：
1) RequestIdFilter：为每个请求生成服务端 UUID，放入响应头 X-Request-Id；忽略客户端传入值（不接受覆盖），
   不把它当认证信息。
2) ErrorDescriptor(ErrorCode, HttpStatus, retryable) 与 ErrorMapping：覆盖 docs/backend/api-spec.md §10 全部
   已登记错误码到 HTTP 状态与 retryable 的映射；未知/未登记码 → 500 + INTERNAL_ERROR，且 4xx/5xx 都不输出
   stack/message/exception/对象名。映射表用追加式数据结构，未登记码必须测试失败而不是默认 200。
3) StrictJson：拒绝未知字段、重复 key、非 UTF-8、超大请求体（>1MiB → 413）；数字 ID 字段按字符串接收。
4) 约定测试：MockMvc 打一个仅测试用的探针控制器（@Profile("test") 或 test 源码内），验证响应头、4xx 形状
   不含敏感细节、未知字段 400、重复 key 400、超限 413。不得新增业务端点、认证或数据库依赖。

RED：先写映射完备性测试（遍历 ErrorCode.values() 断言每个都有映射且 HTTP 与 retryable 符合契约表），
再写 StrictJson/过滤器测试；真实观察失败后实现。报告里列出你为哪些码选了哪个 HTTP 状态及依据行号。
```

## A5 · T03-b：元库 V2 迁移（组织/平台/资源/ACL）

```text
切片：T03-b（父 T03）。写：ptmcp-parent/ptmcp-persistence/src/main/resources/db/ptmcp-migration/V2__*.sql、
同模块 src/test/java/local/ptmcp/persistence/ 下**新增**测试类、tasks/work-items/T03-b.md。
**不得修改 V1__security_foundation.sql**、不得改 SecurityChangeTransaction/SecurityEvent、不改 POM。

契约：docs/backend/db-design.md §2/§3/§4 与 §7；通用要求：显式主键、UTC DATETIME(6) create_time/update_time、
无 ON UPDATE 篡改历史、布尔 TINYINT(1)+CHECK、状态用 VARCHAR+CHECK、有符号 BIGINT、snake_case、
uk_/idx_ 命名、不建或建显式 FK 都须与设计一致并说明。

V2 覆盖（按 db-design 表格逐列落 DDL）：
platform_registry、platform_scope、datasource、resource_namespace、schema_snapshot、resource_object、
snapshot_object、resource_column、business_dictionary、acl_grant、admin_delegation；
外加 sys_user_login_name（若依登录名规范化唯一列）与 sys_dept/sys_role/sys_user_role 的最小适配视图
（若目标库没有若依基础表：在本片建仅含 PTMCP 所需列的等价表并在工作项明确这是“新建”而非复用，禁止假装复用上游 DDL）。

测试（真实 MySQL 8.4 容器，沿用既有 fixture 模式；不改 a1/a2 既有断言）：
空库升级 V1→V2 一次成功、重复 migrate 0；每表主键/时间列/无 ON UPDATE/命名规范；
唯一键与 CHECK 实际生效（含 acl_grant 的 valid_to>valid_from、platform_scope 唯一组合、
resource_object UNIQUE(namespace_id,object_norm,generation)）；同 generation 删除重建不复用 object_id 由后续逻辑保证但需写明；
DML 事务回滚整组；外键/孤儿策略与设计一致。
证据：information_schema 断言 + 真实 SQL 失败样例；不连接既有 ptmcp_dev。
```

## A6 · T10-a：ptmcp-datasource 模块与 MySQL 只读执行

```text
切片：T10-a（父 T10）。写：ptmcp-parent/pom.xml（**仅** modules 加 ptmcp-datasource 一行）、
新模块 ptmcp-parent/ptmcp-datasource/**（pom + src）、tasks/work-items/T10-a.md。
不改其它模块 POM、不改父 POM 其它内容。依赖：common、HikariCP、mysql-connector-j（版本走 Boot BOM）。
**不得**依赖 guard（执行器不接受自由 SQL 类型）；本片暴露的输入类型必须模块内封闭，见下。

契约：datasource.md §1/§2/§4/§8、总体方案 §8（执行状态机 ACQUIRED→CONFIGURED→EXECUTING→DRAINING→
CLEANING→RETURNED，取消需联合确认，不确定则 EVICTED）、§9 预算（借池 3s、SQL 15s、取消 3s、清理 2s）。

实现（MySQL）：
1) PoolKey(datasourceId, configVersion, engine, host, port, database, username, credentialVersion) 与
   DataSourcePoolRegistry：按 key 建池，generation 变化建新池并 DRAIN 旧池；池上限 1..8；不缓存凭据到日志。
2) ReadOnlyRequest：final class，构造器**包内私有**，`static ReadOnlyRequest of(String sql, int maxRows)`
   只对同包可见，并标注“仅由 T07-c 之后的 ApprovedQuery 适配器调用”；模块外无任何 execute(String) 公共入口。
3) MysqlReadOnlyExecutor：借连接→断言 autocommit/readOnly→开只读事务→设置 `max_execution_time`、
   `time_zone='+00:00'`、`SET SESSION TRANSACTION READ ONLY`→执行→流式读取（fetchSize 100）→
   超时/取消→关闭语句→rollback→恢复状态→归还；任一步无法确认即 EVICTED 并报告 CLEANUP_FAILED。
4) 可观察状态与耗时（阶段名 + 毫秒），不含 SQL 文本。

测试（真实 MySQL 容器 + 合成表）：只读事务下 `UPDATE` 必须 #1792 类错误；SESSION 污染（先设 +09:00/
REPEATABLE-READ）由执行器重置为 UTC/只读；超时 15s 预算可配置为 1s 触发 QUERY_TIMEOUT；
取消后下一请求复用连接不误杀；EVICTED 不归还；池 generation 切换；fetchSize 与行上限；
错误映射到已登记码且不含驱动栈。缺 Docker → FAIL，不 skip。
证据：状态机迁移序列断言（不是只看返回值）。
```

## A7 · T03-c：元库 V3 迁移（OAuth/PAT/会话/审计/幂等/租约）

```text
切片：T03-c（父 T03）。写：ptmcp-parent/ptmcp-persistence/src/main/resources/db/ptmcp-migration/V3__*.sql、
同模块新增测试类、tasks/work-items/T03-c.md。不改 V1/V2、不改 T03-b 的测试。

契约：db-design.md §5/§6 与 §7；审计一期不分区；request_id CHAR(36) 全局唯一；
不可变事件不被通用 update_time 覆盖；令牌只存哈希。

V3 覆盖：oauth2_registered_client、oauth2_authorization、oauth2_authorization_consent、
oauth_refresh_family、oauth_refresh_token、personal_token、web_session_index、execution_lease、
audit_outbox、audit_log、audit_sensitive_access、api_idempotency、admin_operation_log。
审计/幂等/租约的字段、索引名、唯一键严格照设计表；OAuth 表以锁定 Spring Security 版本官方 schema 为起点，
自定义列以扩展列/旁表落，并在工作项写明所用 Spring Security 版本来源；不得凭记忆编造官方列。

测试（真实 MySQL）：V1→V2→V3 连续升级一次成功、重复 0；审计表不分区；
audit_outbox UNIQUE(request_id,event_kind) 冲突行为；audit_log 主键幂等（重复 upsert 不覆盖已 terminal）；
api_idempotency 复合主键原子占用（两连接竞争一胜）；personal_token.token_hash 唯一且不存明文列；
family/token 唯一键 (family_id,generation)；execution_lease 索引可用 EXPLAIN 证明命中；
所有表主键/时间列/命名/CHECK 断言。禁止存 token 明文或种子账号。
```

## B1 · T07-b：限定名/列引用改写与重解析等价

```text
切片：T07-b（父 T07）。前置：A2 已在 xquan/feat-backend 合入（ColumnBinder/ColumnBinding 可用）。
写：ptmcp-parent/ptmcp-guard/src/main/java/local/ptmcp/guard/rewrite/ 下新增 QualifiedRewriter、
FinalSqlValidator，对应 src/test 同包，tasks/work-items/T07-b.md。不改 LimitRewriter 断言（可复用）。

契约：sql-guard.md §6/§7：只改 physicalBindings 的节点；保留原别名；MySQL 用 database.table、
PG 用 schema.table；`table.column`/`db.table.column`/`t.*` 通过 columnBindings 同步改写；
无别名时生成 `__ptmcp_r<nodeId>` 并一次性同步全部引用；禁止字符串替换；
重解析后必须比较物理资源多重集、列来源、作用域引用图、允许的 LIMIT 变化，未声明差异 →
REWRITE_VALIDATION_FAILED。

实现：
1) QualifiedRewriter.rewrite(Select, ColumnBindingResult) → RewrittenSql(text, plan)：先改 FROM 关系
   为全限定 + 生成/保留别名，再按绑定改所有 Column；冲突的别名先换唯一名；CTE/派生别名绝不改成物理表。
2) FinalSqlValidator：parse(text) → 重新 ScopeTableBinder + ColumnBinder，比较物理引用多重集与列绑定数量、
   外层 LIMIT 是否等于期望值；不一致抛 REWRITE_VALIDATION_FAILED；不能用“表集合相同”通过。
3) 明确不产生 ApprovedQuery（C1 负责），返回文本仍不可执行。

RED（先失败）：`SELECT orders.id FROM orders WHERE orders.id>0` → 全限定且引用同步；
`db.table.col` 与自连接 a/b；`t.*` 展开后引用正确；别名冲突自动换名；CTE 名不被改写；
改写后列数/顺序与原查询一致；人为篡改 plan（少一个列绑定/多一个物理引用）必须 REWRITE_VALIDATION_FAILED。
证据：给出改写前后 SQL 文本与绑定比较结果；不改 A2 的列绑定断言。
```

## B2 · T09-a：审计 START/terminal 模型与持久准入

```text
切片：T09-a（父 T09）。前置：A7（V3 表）已合入。写：ptmcp-parent/ptmcp-persistence/src/main/java/local/ptmcp/persistence/audit/**、
对应 src/test 同包、tasks/work-items/T09-a.md。不改 SecurityChangeTransaction/V1-V3/Epoch 测试。

契约：audit.md §1/§2、总体方案 §9（入口最小 START 持久化才允许进入昂贵 guard，
同一 request_id 不重复 START；terminal 幂等；拒绝也写 terminal）、ADR0007。

实现：
1) AuditStart(requestId, userId?, platformId?, tool, sqlHash(32B), rawLength, startedAt)：
   **只含最小信息**，不接收 SQL 明文；构造校验 requestId 为 UUID、tool 白名单。
2) AuditTerminal(requestId, result RUNNING|SUCCESS|ERROR|UNKNOWN, verdict ALLOW|DENY|ERROR|UNKNOWN,
   errorCode?, durationMs?, queueMs?, rowsReturned?, truncated, maskedSql?, sqlFingerprint?,
   encryptedRaw?(byte[], keyId) 占位可为 null 本片不加密)。
3) AuditRepository：`insertStart` 以 UNIQUE(request_id,event_kind) 原子占用，重复相同 payload 幂等返回，
   不同 payload → IDEMPOTENCY_KEY_REUSED；`submitTerminal` 只在 terminal 已持久后返回成功，
   已存在 terminal 不覆盖（返回既有状态），乱序（terminal 先到）先建含身份记录；
   `markUnknownIfStale` 用 execution_lease 过期 + 无在途证据恢复 UNKNOWN（本片只做方法 + 测试，
   不给正在运行的查询用）。
4) 所有 SQL 显式列、参数化；错误不回显 SQL/连接串。

测试（真实 MySQL）：START 提交后新连接可见；重复 START 幂等、不同 payload 冲突；
terminal 幂等不覆盖 SUCCESS；terminal 先到再 START 只补空字段；request_id 全表唯一；
maskedSql 不含明文条件（用合成敏感串断言写库值）；
START 失败时调用方拿到的异常映射 DEPENDENCY_UNAVAILABLE 且不产生半条记录。
不实现 WAL（C2）、不接 guard 网关（C1 之后）。
```

## B3 · T11-a：PostgreSQL 只读执行与类型投影

```text
切片：T11-a（父 T11）。前置：A6（ptmcp-datasource 模块）已合入。写：ptmcp-parent/ptmcp-datasource/src/main/java/local/ptmcp/datasource/postgres/**、
对应 src/test 同包、tasks/work-items/T11-a.md；可为 datasource/pom.xml 增加 postgresql 驱动（版本走 BOM）。
不改 A6 的 MySQL 实现与其断言。

契约：datasource.md §4/§5/§6、总体方案 §8（PG 顺序：借干净连接→setReadOnly(true)/setAutoCommit(false)
→建立 READ ONLY 事务→SET LOCAL search_path TO pg_catalog、statement_timeout/lock_timeout→执行→
关闭结果与语句→rollback→恢复→归还；禁止事务外 SET LOCAL）、§9 输出预算（单格 4KiB、总 1MiB、fetchSize 100）。

实现：
1) PgReadOnlyExecutor：严格按上面顺序；`SET LOCAL` 必须在事务内；验证 `SHOW search_path` 与
   `SHOW statement_timeout` 生效；任何设置失败即 CONFIGURED 失败 → 清理/EVICTED。
2) ColumnProjection：按 JDBC 类型映射到 logicalType（STRING/INTEGER/DECIMAL/FLOAT/BOOLEAN/DATE/TIME/
   LOCAL_DATETIME/INSTANT/BINARY/JSON）；BIGINT/DECIMAL 用字符串；NULL 保留 null；
   未知类型 → RESULT_TYPE_UNSUPPORTED（即使 0 行也必须拒绝，不能靠空结果放过）。
3) 单元格 4KiB 截断：保留有界前缀 + 独立 cellTruncations 元数据（rowIndex/columnIndex/originalBytes/
   returnedBytes），不把值改成 "[TRUNCATED]"；行/响应预算在追加整行前计算，放不下整行丢弃并标 RESPONSE_LIMIT。

测试（真实 PG 18 容器 + 合成表）：只读事务拒绝写入；`SET LOCAL` 作用域正确（事务外无效的反例）；
search_path 已是 pg_catalog；10MiB text/bytea 只输出截断前缀且内存不爆（记录 JVM 峰值 RSS/heap，不必精确）；
Unicode、JSON、重复列名（按 index 保留）、NULL、BIGINT 超 JS 安全整数、DECIMAL 精度；
未知类型（自定义 domain/composite）拒绝。缺 Docker → FAIL，不 skip。
```

## B4 · T13-a：OAuth 基线（AS 会话、授权码、JWKS）

```text
切片：T13-a（父 T13）。前置：A5、A7 已合入。写：ptmcp-parent/ptmcp-authz/src/main/java/local/ptmcp/authz/oauth/**、
src/test 同包、tasks/work-items/T13-a.md；可在 ptmcp-authz/pom.xml 增加
spring-boot-starter-security + spring-security-oauth2-authorization-server（版本走 Boot BOM）。
不改 admin/gateway POM、不改共享契约。

契约：authentication.md §4、api-spec.md §9、ADR0003/0006。范围：
1) AS 独立会话 Cookie `PTMCP_AS_SESSION`（HttpOnly/Secure/SameSite=Lax，最长 8 小时），与 admin 会话不共享。
2) /oauth2/authorize（authorization_code，强制 S256、精确 redirect_uri、state、resource）与最小同意页。
3) /oauth2/token（code 交换）、/.well-known/oauth-authorization-server、
   /.well-known/oauth-protected-resource（gateway 侧发现资源可先留占位，不在本片实现 gateway 端点）、/oauth2/jwks（只发公钥）。
4) JDBC 仓储落 V3 表；code 消费为原子条件更新；不开放 implicit/password grant。
5) **PUBLIC none 客户端的 refresh 扩展不在本片**（D1），本片对 PUBLIC 客户端不发 refresh token 并显式测试。

测试（MockMvc + 真实 MySQL）：
code 流程闭环（PKCE 正确→换 token→JWKS kid 可验签）；
code 重复使用拒绝；verifier 不匹配拒绝；plain 挑战拒绝；redirect_uri 多/少字符拒绝；state 缺失拒绝；
implicit/password grant 拒绝；AS 会话缺失时 authorize 要求登录；公钥端点不含私钥字段；
错误响应遵循 OAuth 标准（不套 REST 外壳、不 HTTP200 包装）。
不得伪造 Tier1 证明；不得把 admin 会话复用为 AS 会话。
```

## B5 · T15-a：Schema 同步 job 与原子快照发布

```text
切片：T15-a（父 T15）。前置：A5（资源表）、A6（datasource 模块）已合入。
写：ptmcp-parent/ptmcp-persistence/src/main/java/local/ptmcp/persistence/schema/**、
ptmcp-parent/ptmcp-datasource/src/main/java/local/ptmcp/datasource/metadata/**、对应 src/test、
tasks/work-items/T15-a.md。不改 A6 既有执行器断言。

契约：datasource.md §3（同步为异步 Job，每源仅一个 RUNNING，主库租约 + configVersion 防双 worker；
流程：读配置→网络/TLS 验证→最小元数据连接→采集 staging→规范化/系统对象过滤→差异→
主库事务复核 configVersion→原子发布 snapshot + resourceVersion/epoch→事件；
失败保留旧快照但过期不得用于新查询；不发布半张表）、db-design §4、总方案 §5（事务提交才算生效）。

实现：
1) MetadataSyncJob 状态机 QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED；租约（lease_owner/lease_until）原子占用，
   过期接管必须确认旧任务未发布。
2) 采集器：MySQL 读 information_schema（database/表类型/列序/JDBC 类型/键/注释/lower_case_table_names），
   PG 读 pg_catalog（schema/relkind/列/类型 OID/OID 身份/视图依赖）；只执行内建参数化查询，不接受用户 SQL。
3) 发布：复用 SecurityChangeTransaction 语义（同一元库事务内：复核 configVersion、切换 active_snapshot_id、
   升 resourceVersion、插事件），失败整组回滚；旧快照标 RETIRED 但保留。
4) 对象世代：删除重建产生新 generation/object_id，旧授权不继承（本片只保证世代变化，不实现授权迁移）。

测试（真实 MySQL 元库 + MySQL/PG 业务容器）：两并发 worker 仅一个发布；租约过期接管；
发布失败保留旧快照；旧快照过期后 list 拒绝（SCHEMA_STALE）；同名表在不同 schema 不混；
删除重建 generation 变化；取消 job 不发布半成品；采集器不执行用户输入。
```

## C1 · T07-c：ApprovedQuery 与封闭守门管线

```text
切片：T07-c（父 T07）。前置：B1 已合入。写：ptmcp-parent/ptmcp-guard/src/main/java/local/ptmcp/guard/approved/**、
同包 src/test、tasks/work-items/T07-c.md。不改已冻结的 lexing/ast/scope/rewrite 断言（可调用）。

契约：sql-guard.md §1/§7（流水线顺序；ApprovedQuery 模块私有构造、不可变；executor 无 execute(String)；
执行前核验版本/过期/主体一致）、总体方案 §8。

实现：
1) GuardPolicy（方言 + 词法模式 + 预算 + guardVersion）与 GuardContext（principalId/platformId/
   datasource/configVersion/catalog/schema/snapshotId/resourceVersion/policyEpoch/authVersion/
   authoritativelyCheckedAt）——后者只能由调用方传入服务端已核实的值，本类不校验其真实性但在 Javadoc 写明
   “构造者负责”，且**不得**提供 HTTP 反序列化入口。
2) GuardPipeline.guard(String sql, GuardContext ctx, GuardPolicy policy, GuardDependencies deps)
   依序执行：词法 → 语句形状 → 白名单 → 作用域绑定 → 列绑定 → 物理 READ 校验（回调 PermissionDecider：
   对每个 ResourceRef 询问读权限，任一拒绝即 TABLE_NOT_AUTHORIZED 且不进入改写）→ LimitRewriter →
   FinalSqlValidator → 构造 ApprovedQuery。
3) ApprovedQuery 字段至少：requestId、principalId、platformId、datasourceId、configVersion、catalog/schema、
   snapshotId/resourceVersion、policyEpoch/authVersion、resourceRefs、finalSql、sqlHash、effectiveLimit、
   guardVersion、approvedAt/expiresAt；构造器包内私有、全部 getter 返回不可变；
   `expiresAt` 默认 approvedAt+瞬时窗口，执行前必须由调用方复核。
4) 本片**不**写执行器，也不落地审计接入；返回 ApprovedQuery 也不能直接执行（网关尚未接线）。

RED：任一阶段失败即短路且不调用 PermissionDecider 之后的阶段（用计数断言顺序与短路）；
越权资源（decider 返回 false）→ TABLE_NOT_AUTHORIZED 且 finalSql 未生成；
篡改 ApprovedQuery（反射/重新构造）不可行 → 测试证明无公共构造器与 setter；
过期的 ApprovedQuery 在 `isUsable(now)` 为 false；同一输入两次 guard 的 sqlHash 稳定、finalSql 一致。
```

## C2 · T09-b：加密 WAL、fsync 与 UNKNOWN 恢复

```text
切片：T09-b（父 T09）。前置：B2 已合入。写：ptmcp-parent/ptmcp-persistence/src/main/java/local/ptmcp/persistence/audit/wal/**、
同包 src/test、tasks/work-items/T09-b.md。不改 B2 仓储对外行为（可新增构造参数）。

契约：audit.md §3/§4、总体方案 §9（结束事件持久入库或加密 fsync WAL 后才返回成功；
仅内存队列不算持久确认；崩溃遗留 START 恢复为 UNKNOWN 而不冒充成功；WAL/DB 都不可写时停止新 SQL）、ADR0007。

实现：
1) KeyProvider 接口（AES-256-GCM，返回 keyId + key；本片用测试用内存 key，不落盘、不读本机秘密）。
2) TerminalWal：追加写 segment 文件（长度前缀 + nonce + 密文 + tag + payloadHash），
   每次 append 后 `FileChannel.force(true)`；fsync 失败即抛错，不许“尽力而为”。
3) WalReplayer：启动时重放未确认 segment → upsert terminal（幂等）→ 标记已消费；
   同一 requestId 重复事件不重复、不篡改既有 SUCCESS。
4) 恢复：扫描超过租约的 START → 标 UNKNOWN（不覆盖已有 terminal）；提供只读统计供运维。
5) 磁盘/目录不可写 → 明确失败（CLEANUP_FAILED / AUDIT_UNAVAILABLE），调用方必须停止新 SQL。

测试（真实文件系统 + 真实 MySQL）：append 后 fsync 前 kill 子进程 → 重放要么完整要么丢弃（不产生半条）；
fsync 后崩溃 → 重放成功且幂等；目录只读 → 报错且不静默丢事件；重放两次行数不变；
UNKNOWN 恢复不覆盖既有 SUCCESS；密文文件里 grep 不到合成敏感串（断言字节层不含明文）。
用子进程 + Runtime.halt 模拟崩溃（不是 mock）；报告记录注入点。
```

## C3 · T12-a：安全快照时效、撤销与配额

```text
切片：T12-a（父 T12）。前置：B2、B3 已合入。写：ptmcp-parent/ptmcp-persistence/src/main/java/local/ptmcp/persistence/security/**、
同包 src/test、tasks/work-items/T12-a.md。不改 V1-V3、不改 T03-a2 协调器语义（可新增调用方）。

契约：authorization.md §4/§5/§6、总体方案 §7、ADR0002、TC-ACL-004～010。
硬约束：本地缓存 ≤1s、权威快照不陈旧 >5s、`authoritativelyCheckedAt` 不得被缓存命中/心跳续命；
超时必须重读主库，主库不可确认则拒绝新查询（SECURITY_STATE_UNAVAILABLE），不得 fail-open。

实现：
1) SecuritySnapshot(policyEpoch, subjectVersions, platformVersion, familyVersions, checkedAt)；
   SecurityStateReader：每次读取用新事务/current read，checkedAt 取“读取发起前的保守时刻”。
2) SecurityStateGuard：`verify(snapshot, now)` 要求 `now - checkedAt ≤ 5s` 且版本未变，否则重读；
   连续失败 N 次或超时 → 拒绝；缓存条目只带 checkedAt 与版本，禁止在 get 时刷新。
3) QuotaLimiter（接口 + 内存实现，Redis 版留待后续 ADR）：用户 3、源/副本 8、网关 16、
   排队 32 / 3s；拒绝返回 USER_CONCURRENCY_LIMIT / DATASOURCE_CONCURRENCY_LIMIT / GATEWAY_BUSY。
4) 撤销：`revokeSubject/revokeFamily/revokeToken` 走 SecurityChangeTransaction（epoch+事件同事务），
   返回 receipt 即视为已提交；不等待广播。

测试（真实 MySQL + 固定 Clock）：5s 边界前通过、≥5s 拒绝并触发重读；Redis 命中/心跳不能续命
（用假缓存反复命中后仍拒绝）；epoch 变化后旧快照失效；并发 4 个用户与 9 个源并发被限流且无超卖
（统计实际并发峰值）；撤销提交后新请求立即用新 epoch（读取路径读主库）。
不实现真实 Redis 分布式配额（写清限制）。
```

## C4 · T14-a：Tier1 逐请求证明、DCR 与 CIMD 防护

```text
切片：T14-a（父 T14）。前置：B4 已合入。写：ptmcp-parent/ptmcp-authz/src/main/java/local/ptmcp/authz/platform/**、
同包 src/test、tasks/work-items/T14-a.md。不改 B4 的 OAuth 流程断言。

契约：authentication.md §8、security.md §5、api-spec.md §9（/connect/register）、TC-API-007、TC-AUT-008/009。
要点：Tier1 除 AS 认证外，每次 MCP 调用还需 mTLS 或经核实的后端代理 secret + 网络约束；
public client_id/CIMD/隐藏 Header 不构成后端证明；DCR 默认最小 Scope、不接受自报 platform_id；
CIMD 只允许公网解析、拒绝 loopback/私网/链路本地/云 metadata/重定向、响应大小与超时受限。

实现：
1) PlatformProofVerifier 接口 + 两种实现：MtlsProof（证书指纹白名单）、ProxySecretProof
   （HMAC/常量时间比较 + 来源 CIDR 白名单）。失败 → PLATFORM_PROOF_REQUIRED；平台不符 → PLATFORM_MISMATCH。
2) DcrRegistration：校验 redirect_uri（精确、禁 fragment、禁 userinfo、禁通配）、auth method、
   scope 子集；默认只给 mcp:catalog；拒绝注册 Tier1。
3) CimdResolver：HTTPS only、禁重定向、DNS 解析结果全部校验（拒 loopback/私网/链路本地/ULA/metadata 169.254.169.254、
   IPv4-mapped IPv6）、响应 ≤32KiB、连接+读取超时、Content-Type JSON、失败即拒。
4) 出站限额与缓存 TTL（写清数值来源）。

测试：伪造 Header/client_id 无法获得 Tier1；缺证明 401/403 语义；指纹不符拒绝；
CIMD 指向 127.0.0.1/169.254.169.254/10.x/重定向/超大响应全部拒绝（用本地伪服务器，不访问真实外网）；
DCR 注册重复 redirect 变体被拒；Tier1 注册被拒。不得连接真实外部 URL。
```

## C5 · T16-a：组织/角色/ACL 管理 API 与自增权阻断

```text
切片：T16-a（父 T16）。前置：A5、B2 已合入。写：ptmcp-parent/ptmcp-admin/src/main/java/local/ptmcp/admin/api/**（org/role/acl）、
ptmcp-parent/ptmcp-persistence/src/main/java/local/ptmcp/persistence/org/**、对应 src/test、
tasks/work-items/T16-a.md。不改 A4 的边界原语断言（可复用）。

契约：api-spec.md §2/§5（CRUD 语义、If-Match、Idempotency-Key、幂等/版本冲突码、管理范围与
SELF_PRIVILEGE_ESCALATION）、authorization.md §7。
前置说明：A4 未落地响应封装（等协调者决定）。本切片把端点实现在 admin 内并以 `@RestController` 返回
**显式 DTO**；如果 A4 的封装决策尚未落地，则用返回 DTO 直出 + 在报告中标注“待封装接入”，
不得自造 R<T> 同形类。

实现（最小 CRUD，不含 UI）：
1) /api/v1/admin/departments、/roles、/users（含 PUT /status、/roles、/department）、
   /api/v1/admin/acl/grants（含 DELETE 撤销不物理删除、POST /acl/preview 只判定不连业务库）。
2) If-Match/ETag + version 乐观锁（VERSION_CONFLICT）；Idempotency-Key 原子占用（复用 V3 表）。
3) 管理范围校验（delegation 未实现时：仅平台管理员通过，其余 MANAGEMENT_SCOPE_DENIED，写清限制）。
4) **自增权阻断**：对“给本人/本人角色/本人部门增 Allow、删改命中本人的 Deny、改本人角色/部门”的命令，
   比较命令前后调用方有效 META/READ（用 T08-a 判定 + 展开后的 grant），一旦增权 →
   SELF_PRIVILEGE_ESCALATION，且命令不落库。

测试（MockMvc + 真实 MySQL，需模拟登录主体；用测试用 SecurityContext 或请求头注入只在 test profile 生效）：
CRUD + 409 版本冲突；缺 If-Match 400；重复 Idempotency-Key 同 body 重放、不同 body 422；
给本人增 Allow → 403 且库内无变化；删本人命中的 Deny → 403；越级部门 403；
ACL 预览不触发业务库连接（用计数假池断言 0）；撤销后历史行保留 REVOKED。
```

## C6 · T17-api-b：/session 与 /me 接口（前端对接面）

```text
切片：T17-api-b（父 T17）。前置：A3、A4、B2、B4 已合入。写：ptmcp-parent/ptmcp-admin/src/main/java/local/ptmcp/admin/session/**、
ptmcp-parent/ptmcp-admin/src/main/java/local/ptmcp/admin/me/**、对应 src/test、tasks/work-items/T17-api-b.md；
可在 admin/pom.xml 增加必要依赖（版本走 BOM）。不改 A4/C5 的既有断言。

契约：api-spec.md §3（会话）、§6（/me 全部端点）、docs/frontend/dev-guide.md 与 page-spec.md 的字段需求。
会话实现约束：服务端不透明会话（Cookie `PTMCP_ADMIN_SESSION`，HttpOnly/Secure/SameSite=Lax，≤8h），
CSRF token 与预会话绑定；**不要**引入 Sa-Token（上游 Boot4 适配未验证，属待决策项），
也不要用 Spring Security 替代 admin 既定路线；在报告中写明该决策点。

实现（按前端可消费形状）：
1) GET /api/v1/session/csrf、POST /session/login、GET /session/me、POST /session/logout、
   PUT /session/password（旧密码校验、12~64 字符且 ≤72 UTF-8 字节、禁与旧相同、成功后清 Cookie 与撤销会话）。
2) /api/v1/me：access-context（PLATFORM/DATASOURCE/NAMESPACE 三层）、dictionary、dictionary/{objectId}、
   tokens（PAT：创建仅一次展示、列表不含明文、撤销幂等）、history、history/{requestId}、mcp-config、
   authorizations、DELETE /authorizations/{familyId}。
3) 身份只从会话取，拒绝 userId 查询参数；他人资源统一 404；写操作校验 CSRF；敏感响应 no-store。
4) PAT 生成 ≥256bit 随机、只存哈希（用 V3 personal_token），默认 30 天、最大 90 天，不能提升平台。

测试（MockMvc + 真实 MySQL）：登录成功/失败不区分原因、5 次锁定、Cookie 属性断言、成功后会话轮换；
改密撤销全部会话并清 Cookie；越权访问他人 history → 404；PAT 创建一次展示且库里无明文、
跨平台使用拒绝、撤销幂等；mcp-config 的 validatedVersion 为 null（不编造兼容）。
报告里给前端可用的 OpenAPI 片段（手写 YAML 放 docs/ 之外？——放 tasks/work-items/T17-api-b.md 附录即可，
不改共享 docs）。
```

## C7 · T15-b：数据源管理与字典 API

```text
切片：T15-b（父 T15）。前置：B5 已合入。写：ptmcp-parent/ptmcp-admin/src/main/java/local/ptmcp/admin/datasource/**、
ptmcp-parent/ptmcp-admin/src/main/java/local/ptmcp/admin/metadata/**、对应 src/test、tasks/work-items/T15-b.md。

契约：api-spec.md §4/§5（DatasourceWrite、CRUD、POST /test →202 JobDTO、POST /sync →202、
GET /jobs/{id}、/metadata/objects、/metadata/objects/{id}、PUT dictionary）、datasource.md §1/§2（目的地校验、
CIDR 白名单、禁任意 JDBC URL/属性、VERIFY_FULL 不降级、poolMax 1..8 与连接预算）。

实现：
1) DatasourceAdminService：创建/更新/停用/删除（有关联活动授权时 REFERENCES_EXIST）、
   凭据只写不读（返回 credentialConfigured 布尔）；目的地 DNS 解析 + 私网/loopback/metadata 拒绝 +
   运维 CIDR 白名单（配置项，默认空 = 全部拒绝，写清）。
2) 测试/同步复用 B5 job；/jobs/{id} 返回 progress 且 total 未知时为 null。
3) 元数据对象列表/详情（按管理范围过滤）+ 字典写入（只改说明，不改 DDL；未知列拒绝；富文本禁用）。
4) 所有写操作 If-Match + Idempotency-Key（复用 C5 实现）。

测试（MockMvc + 真实 MySQL；数据源连通性用 Testcontainers 起的 MySQL/PG 作为“业务库”）：
非白名单/loopback/metadata 目标拒绝；任意 JDBC URL 字段被拒；TLS VERIFY_FULL 失败不降级；
poolMax 越界 422；删除有关联授权 409；字典写未知列 422；列表按管理范围过滤；
凭据不出现在任何响应与日志（用合成敏感值断言）。
```

## D1 · T13-b：公开客户端刷新轮换与标准撤销扩展

```text
切片：T13-b（父 T13）。前置：B4、B2 已合入。写：ptmcp-parent/ptmcp-authz/src/main/java/local/ptmcp/authz/oauth/refresh/**、
.../oauth/revoke/**、对应 src/test、tasks/work-items/T13-b.md。不改 B4 的 code 流程断言（可扩展）。

契约：authentication.md §5/§6、ADR0006、TC-AUT-003～006/013。默认关闭，开关 `ptmcp.oauth.public-refresh.enabled`
（默认 false）；关闭时行为必须与 B4 一致并有测试。

实现：
1) 公开客户端（none）refresh：独立 converter/provider/grant/generator，不误走要求 PKCE verifier 的 code provider；
   以 RT 持有 + client/family 绑定校验，不凭 client_id 撤销。
2) 轮换事务：先锁 sys_security_state → sys_user_security → family → token generation，
   检查状态/到期，旧 token → CONSUMED，写新 token（generation+1），更新 idle_expires_at=min(now+7d, absolute)，
   同事务写 epoch/事件；**提交成功才返回** token。
3) 重放：遇到 CONSUMED 旧 token → 撤销整族 + 事件 + 返回 invalid_grant。
4) 标准 /oauth2/revoke：凭真实 token + client/family 绑定；未知/跨 client 统一 200 防枚举；
   写失败不假报成功。

测试（真实 MySQL）：正常刷新旧 token 失效新 token 可用；同一旧 token 两连接并发仅一次成功（屏障），
失败方触发整族撤销且 JWT 校验方 5s 内拒绝（本片可只验证族状态位）；
响应丢失后重试旧 token → 第二套 token 不产生；跨 client/跨 family 拒绝；
revoke 未知 token 200 且不影响他族；事务注入失败不返回可用 token；
开关关闭时 PUBLIC 客户端拿不到 refresh token。
```

## D2 · T18：内部回归资产与 ≥300 参数化用例（BLOCKED-EXTERNAL）

```text
切片：T18（父 T18）。前置：C1、B3 已合入。**BLOCKED-EXTERNAL**：私有 29 条 VQ 数据集与参数渲染器未提供，
G-12 在 docs/evidence/m0-gate-register.md 中为 BLOCKED。

写：新增 ptmcp-parent/ptmcp-regression/**（父 POM modules **由协调者**添加，你不要改父 POM，先提交模块内容并在报告里请求）、
tasks/work-items/T18.md。**不要**加入开源分发资产目录之外的真实业务数据。

任务：
1) 回归 harness：从 case manifest（YAML/JSON）加载 → 依次跑 lexical/statement/allowlist/bind/column/authorize/
   limit/rewrite/final-validate，输出每个 case 的预期/实际与耗时；合成夹具数据随模块入库。
2) 生成 ≥300 条**合成**参数化用例（覆盖 R1 六例、系统表、路径函数、CAST、锁、多语句、预算边界、列歧义、
   UNION/CTE 嵌套、引号大小写、双 schema 同名表），每例标注 caseId 与来源（synthetic 或 VQ-xx）。
3) VQ 部分：只写 loader 接口与 29 条占位清单（id + NOT_RUN/BLOCKED），**不得**编造 SQL 或伪造通过。
4) CI 接入步骤写进报告，不要改 .github（共享文件）。

验收：`verify` 中 ≥300 条合成用例全绿；VQ 部分在报告中明确 BLOCKED 及所需输入（原始 SQL、参数渲染器、预期结果、
许可归属）。若无法在 CI 时间预算内完成，给出实测耗时并提出拆分方案，不得删用例凑数。
```

## D3 · T19：四客户端接入矩阵（BLOCKED-EXTERNAL）

```text
切片：T19（父 T19）。前置：D1、C4、C6 已合入。**BLOCKED-EXTERNAL**：无真实客户端（WorkBuddy/Cursor/
Claude Code/VS Code 指定版本）、无测试域名与回调。

写：ptmcp-parent/ptmcp-gateway/src/main/java/local/ptmcp/gateway/mcp/**（仅配置/模板与协议适配骨架，
**不得**在此切片开放可执行 SQL 入口；guard 完整链路 C1 已合入但执行器接线不含敏感配置）、
tasks/work-items/T19.md、以及客户端矩阵模板（放该工作项附录，不改共享 docs）。

任务：
1) /mcp 的 STATELESS 传输骨架：401 + WWW-Authenticate 资源发现、仅注册三个工具（schema 取
   docs/backend/mcp-tools.json 为只读来源，代码中复制为常量并加一致性测试）。
2) mcp-config 模板（GENERIC/WORKBUDDY/CURSOR/CLAUDE_CODE/VSCODE）与占位 secret，不嵌 PAT、不写死 URL
   （从配置读取，默认空则返回未配置）。
3) 客户端矩阵表格：每客户端记录“需要的流程、预期行为、待验证项”，validatedVersion 一律 null，
   compatibilityStatus=NOT_RUN；写出每个客户端需要用户提供什么（版本号、回调 scheme、域名）。

验收：骨架测试（401 语义、工具清单与 mcp-tools.json 一致、模板不含 secret）；
矩阵文档明确 BLOCKED 原因与解锁输入。禁止声称任何客户端已兼容。
```

## D4 · T20：fuzz、性能与恢复演练

```text
切片：T20（父 T20）。前置：C1、C2、C3、D1 已合入。写：ptmcp-parent/ptmcp-regression/src/test/java/local/ptmcp/regression/fuzz/**、
scripts/bench/**（新脚本，命名不与既有冲突）、tasks/work-items/T20.md。不改 scripts/check.py 与 CI 共享文件。

任务（三块，可分批提交，各自独立可验收）：
1) Fuzz：基于 JSQLParser 变异（截断/替换/嵌套加深/引号注释边界）跑 lexical+statement+allowlist+guard 管线，
   断言不抛未捕获异常、不 OOM、超预算即拒；固定随机种子入库，失败种子保留为回归；
   单轮时间上限明确（例如 60s），超时必须报失败而不是跳过。
2) 性能：2C4G 语义下的内部开销基准（JVM 参数、并发 50、≤4KiB SQL、热权限缓存），
   分段计时（词法/解析/绑定/授权/改写/审计准入），输出 P50/P95/P99 与拒绝率到 JSON；
   本机跑不出 2C4G 时在报告中说明环境差异，不伪造达标。
3) 恢复：脚本化演练（元库不可用 → 审计 fail-closed；WAL 回放；撤销后 5s 内拒绝），
   产出时间线与 JSON 证据；不执行任何生产操作。

验收：三块各自可复跑；未能证明的项目（真实 2C4G、真实 Redis 多副本、真实客户端）明确 NOT_RUN 并写解锁条件。
禁止把容器 OOM Kill 记为“内存上界通过”。
```

## E1 · 协调者窗口：集成、重验与最终审计

```text
角色：协调者（不并行写业务代码）。基线 xquan/feat-backend，按 A→B→C→D 顺序合入。

每轮：
1) git fetch origin；读该切片工作项与 diff；确认只改授权路径、无秘密、无 skip、无放宽断言。
2) 独立评审（不同上下文子代理或人工）：只看 diff + 契约，找缺陷；不把作者自述当独立证据。
3) cherry-pick 到 xquan/feat-backend（保留原始 SHA，不重写历史）。
4) 冲突处理：按最终业务行为合并，不盲选 ours/theirs；共享契约冲突由协调者裁定并同步文档。
5) 组合重验：python scripts/check.py --all --maven-cache-seed D:/config/ptmcp/cache/maven，
   必须全绿；再手动跑该切片的实库/故障用例关键路径（不能只信单元测试）。
6) 更新 tasks/plan.md 状态、工作项证据、docs/evidence/m0-gate-register.md、docs/test/asvs-checklist.md
   的受影响条款（仅在新增真实证据时升级状态），必要时更新模块 README。
7) 推送并核对 git ls-remote 与远端 CI（gh run list）；CI 失败必须修，不得跳过。

最终审计（全部切片完成后）：
- 逐条映射目标：FR-01～20、安全不变量（0 连接静态拒绝、≤5s 撤销、审计准入、只读事务、预算）、
  前端对接契约（R<T> 决策落地、X-Request-Id、错误码、/me、OpenAPI）。
- 明确列出仍为 NOT_RUN/BLOCKED 的项（私有 29VQ、四客户端真实版本、2C4G 压测、KMS/密钥归属、
  CIDR 白名单、Sa-Token/R<T> 依赖决策、Redis 分布式配额）及解锁输入。
- 完成前不得声称“全部后端完成”；未验证项保持原状态。
```

## 冲突与顺序提醒

| 冲突点 | 处理 |
| --- | --- |
| 父 POM modules | 只有 A6 可加 `ptmcp-datasource`；D2 的 `ptmcp-regression` 由协调者在合入 D2 时添加 |
| `ptmcp-persistence` 迁移文件名 | A5=V2、A7=V3，D2 之后若需 V4 由协调者分配版本号，禁止并发自增 |
| `ptmcp-guard/scope` | A2 只新增列绑定类型；B1 复用不改；C1 复用不改 |
| `ptmcp-admin/web` | A4 建边界原语；C5/C6/C7 各自新增子包，不互改 |
| 响应封装 `R<T>` | A4 不实现；C5/C6/C7 用显式 DTO 直出并标注待接入；协调者决定 vendor 上游源码后统一替换 |
| Sa-Token | C6 用服务端不透明会话，不引 Sa-Token；协调者在报告后决定 |
| 每个切片的分支 | 各自 `xquan/<slice>`，只 push 自己分支；冲突一律回协调者 |

## 已知外部阻塞（不要试图绕过）

1. 私有 29 条 VQ 数据集、参数渲染器与业务预期未提供 → T18 的 VQ 部分 BLOCKED。
2. 四客户端真实版本、测试域名/回调 → T19 BLOCKED。
3. Tier1 mTLS 证书、受信代理、生产域名与 CIDR 白名单 → C4/C7 只能做接口与负例。
4. KMS/主密钥归属 → C2 用测试内存 key，生产加密仍待决策。
5. RuoYi `org.dromara:ruoyi-common-core` 官方构件在 Central 不存在 → R<T> 接入方式待决策（vendor 带许可源码 vs 其它）。
