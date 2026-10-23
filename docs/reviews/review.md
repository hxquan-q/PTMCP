以下为您整理的 **《PTMCP 企业级问数安全网关 — 最终实施基线方案（修订版）》**。本方案保留 **Java 后端 + Vue 前端** 技术主线，将原需求规格书、开源选型方案 与源码审查及复现报告中的 6 项高优先级（P1）问题、4 项中优先级（P2）问题 全面融合，作为项目正式立项与 M0～M4 研发的实施基线。   

# PTMCP 企业级问数安全网关 — 最终实施基线方案（修订版）

## 一、 核心基线结论与范围界定

1. **产品定位与核心架构保留**：PTMCP（Permission-controlled Text-to-SQL MCP Gateway）定位为轻量级、中心化的“薄网关（Schema 过滤 + AST 静态安全拦截 + 只读 SQL 执行器）”，不捆绑大模型推理，专门为外部 Agent 平台提供带权限隔离的数据底座。架构上保留 **Java + Vue 主线**、**独立 `ptmcp-guard` 守门模块**、**执行器只跑 AST 重生成的 `final_sql`**、**底层数据库只读账号兜底**、以及 **`ptmcp-admin`（管理后台）与 `ptmcp-gateway`（网关服务）分进程隔离部署**的核心设计。   
2. **撤回两项不成立的原方案假设**：
   - **撤回“JSQLParser 5.4 原版 `TablesNamesFinder` 能自动处理同名遮蔽并直接用于安全 ACL”的结论**：本地复现证实，原版 `TablesNamesFinder` 在遇到同名派生表别名、嵌套 CTE、非递归同名 CTE 及 UNION 分支派生表别名时会漏掉真实物理表（6 个测试案例中 4 个反例错误返回空集合 `[]`），且 AST 重生成的 `final_sql` 仍保留对真实物理表的访问，必须自研带作用域栈的物理关系绑定器。   
   - **撤回“接入 Spring Security 7.1.2 与 mcp-security 0.1.14 默认配置即可让各桌面客户端无感自动续期”的结论**：同版本源码核对证实，Spring Security 7.1.2 的 `OAuth2RefreshTokenGenerator` 对客户端认证方式为 `none` 的公开客户端（Public Client）默认返回 `null`（不签发 Refresh Token），且 `mcp-security` 0.1.14 默认沿用该生成器，必须将客户端 OAuth 联调前移至 M0 并专门设计公开客户端续期机制。   
3. **一期数据库方言范围收敛**：虽然产品远期规划提及 MySQL、SQL Server、PostgreSQL、Oracle 等多异构数据库，但**一期实施基线正式锁定仅支持 MySQL 与 PostgreSQL 两个方言**，SQL Server 与 Oracle 延期至后续阶段，不在一期 `datasource.type` 中开放未实现安全沙箱能力的数据库选项。   
4. **排期确认策略**：将“带作用域的表级 ACL、资源与执行对象绑定、公开客户端 OAuth 真实联调”全部前移至 M0 阶段完成验证；待 R1～R6 六项高优先级问题的技术路径在 M0 跑通闭环后，再最终锁定 9～10 周的完整交付承诺。   

## 二、 技术栈选型与工程模块隔离基线（含 R8 修订）

### 1. 核准技术栈清单

| **维度**                 | **选型基线与版本约束**                                       | **审查修订说明**                                             |
| ------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **运行时与基础框架**     | JDK 21 + Spring Boot 4.1.x                                   | Maven Central 最新为 4.1.2，本地若依 6.X 源码实际为 4.1.1，M0 需验证补丁版本对齐。 |
| **MCP 服务端框架**       | Spring AI 2.0.x（`spring-ai-starter-mcp-server-webmvc`）+ MCP java-sdk 2.1.0 | 主协议采用 Streamable HTTP（`/mcp`）；本地若依为 2.0.1，升级 2.0.2 需在 M0 验证。 |
| **OAuth 授权与资源服务** | Spring Security 7.1.2 Authorization Server + `mcp-security` 0.1.14 | `mcp-security` 为社区 WIP 项目（约 120 stars），需锁死版本并封装在适配层内。 |
| **SQL 静态安全内核**     | `ptmcp-guard`（纯 Java）：移植 dbx 词法预检 + JSQLParser 5.4 + 自研作用域表绑定器 | 严格遵循“单解析器主判定”原则；Druid 1.2.28 WallFilter 仅作可选旁路复核告警，不干预主链路。 |
| **业务库连接池**         | HikariCP 7.0.2 / 7.1.0 + MySQL Connector/J + pgJDBC          | Spring Boot 4.1.2 默认管理版本为 7.0.2，若升级 7.1.0 需明确理由并通过集成验证。 |
| **管理后台与前端**       | RuoYi-Vue-Plus 6.0（Sa-Token + MyBatis-Plus 3.5.17）+ `plus-ui`（Vue 3 + TS + Element Plus） | 与网关分进程部署，避免 Sa-Token 与 Spring Security 过滤链冲突。 |
| **元数据库与缓存**       | MySQL 8 或 PostgreSQL（M0 锁定其一）+ Redis                  | 本地若依脚本 `ry_vue.sql` 为 MySQL DDL；若定 MySQL 则改写审计表为 MySQL 分区，若定 PG 则需计入若依脚本迁移工期。 |

### 2. Maven 多模块边界与依赖冲突隔离（解决 R8）

本地源码核对发现，若依的 `ruoyi-common-mybatis` 直接依赖了 `ruoyi-common-satoken` 与 `mybatis-plus-jsqlparser`（而 MyBatis-Plus 3.5.17 依赖的是 **JSQLParser 5.2**）。若公共模块直接复用若依 MyBatis 层，会将 JSQLParser 5.2 和 Sa-Token 传递引入 `ptmcp-gateway`，破坏 `ptmcp-guard` 对 **JSQLParser 5.4** 的版本要求并污染网关安全配置。为此强制执行以下模块边界：   

- **`ptmcp-common`（纯契约层）**：仅包含与框架无关的领域模型、DTO、错误码枚举、权限交集计算纯逻辑接口，**严禁依赖 `ruoyi-common-mybatis`、`ruoyi-common-satoken` 或任何安全/ORM 插件模块**。   
- **`ptmcp-guard`（纯 Java 沙箱内核）**：零 Spring 依赖，显式锁定 `com.github.jsqlparser:jsqlparser:5.4`（二进制 SHA-256: `6E10F0B014D6A474808EEAAFD639EC9092117AF2D4DC083764D0B4BF6897ADD7`）。包含词法预检、单语句与只读校验、函数/类型白名单、带作用域的物理表绑定器、完全限定名改写、LIMIT 收敛与二期行级改写。   
- **`ptmcp-datasource`（连接池与安全执行器）**：依赖 HikariCP 与 MySQL/PG 驱动，负责按 `datasource_id` 建池、连接上下文绑定与清理、游标/流式读取、单元格字节截断、五阶段超时与安全取消。   
- **`ptmcp-authz` & `ptmcp-gateway`（网关与授权服务进程）**：基于 Spring Boot 4.1 + Spring Security 7.1 + Spring AI 2.0，使用独立的轻量级持久化适配层访问元数据库，**在 M0 阶段必须产出 `effective-pom` 与 `dependency:tree` 检查及启动测试，确保未传递引入 Sa-Token 及低版本 JSQLParser**。   
- **`ptmcp-admin`（管理后台进程）**：基于 RuoYi-Vue-Plus 6.0 二开，仅调用 `ptmcp-datasource` 的连通性测试与 Schema 同步能力，无需传递依赖 `ptmcp-guard`；若未来后台确需同进程调用 `ptmcp-guard`，必须先将若依内部 JSQLParser 统一升级至 5.4 并完成若依数据权限全量回归。   
- **`ptmcp-regression-internal`（内部私有测试模块）**：存放 Proprietary 许可的 `mes-data-qa` 评测集、29 条 VQ 夹具及参数渲染器，仅供内部 CI 门禁使用，开源发布时剥离；同时在 `third_party/dbx/LICENSE` 中完整保留 Apache-2.0 许可声明与源码归属。   

## 三、 六项高优先级问题（P1：R1～R6）架构修订细则

### 1. 【R1 修订】自研带作用域栈的物理关系绑定器（替代 `TablesNamesFinder`）

- **复现缺陷说明**：在 JSQLParser 5.4 下，`SELECT s.* FROM salary s CROSS JOIN (SELECT 1 AS n) salary`、`SELECT * FROM salary WHERE EXISTS (WITH salary AS (SELECT 1 AS n) SELECT * FROM salary)`、`WITH salary AS (SELECT * FROM salary) SELECT * FROM salary` 以及 `SELECT 1 FROM salary UNION ALL SELECT * FROM (SELECT 1 AS n) salary` 四类查询，原版 `TablesNamesFinder.getTables(ast)` 均返回 `[]`。原因是其源码（`TablesNamesFinder.java` 第 226～262、340～369、523～529 行）使用无作用域的全局列表 `otherItemNames` 记录所有 CTE 名和派生表别名，并在遍历时跳过或最后统一执行 `tables.remove(otherItemNames)`，导致同名真实表被抹除。   
- **实施方案**：
  1. **构建词法作用域栈（Lexical Scope Stack）**：在 `ptmcp-guard` 中编写自定义的 `ScopedTableBinder` 访问器，每个 `Select` / `PlainSelect` / `SetOperationList` 维护独立的局部作用域帧。   
  2. **严格区分 CTE 作用域规则**：
     - 处理非递归 `WithItem`（如 `WITH salary AS (SELECT * FROM salary)`）时，先在**外层作用域**下解析 CTE 定义体内的 `SELECT * FROM salary`（此时 `salary` 尚未进入可见 CTE 名称集，正确绑定为物理表 `salary`），解析完定义体后再将 CTE 名称 `salary` 注册到当前查询层级的 CTE 符号表中。   
     - 处理递归 `WITH RECURSIVE` 时，允许递归分支引用自身 CTE 名，但基础分支与外部引用仍按作用域严格区分；若一期无需支持递归 CTE，可直接将 `WITH RECURSIVE` 拒绝（fail-closed）。   
     - 子查询（如 `EXISTS (WITH salary AS ...)`）内部定义的 CTE 仅压入子查询局部作用域帧，退出子查询即弹出，绝不遮蔽外层查询的 `FROM salary`。   
  3. **严格隔离派生表别名（Derived Table Alias）**：`ParenthesedSelect`（派生表，如 `(SELECT 1 AS n) salary`）的别名 `salary` 仅作为当前 `FROM` 子句内的列限定符别名，**绝不作为可遮蔽同级或跨 `UNION` 分支物理表名的符号**，严禁因派生表别名从物理表集合中移除任何 `Table` 节点。   
  4. **Fail-Closed 兜底**：遍历过程中凡遇到未显式处理的 `FromItem` 子类或无法确定绑定关系的节点，直接判定为拒绝并阻断执行，不调用数据库连接池。   

### 2. 【R2 修订】统一资源身份标识与数据库执行对象强绑定

- **缺陷说明**：仅凭 `database_name` 路由和字符串前缀补全，无法区分多数据源同名库、PostgreSQL 的 `database` 与 `schema`、隐式 `pg_catalog` 系统表（如 `SELECT * FROM pg_authid`），且存在校验用 `erp.orders` 而连接实际按默认 `search_path` 执行 `public.orders` 的错位风险。   
- **实施方案**：
  1. **四元组资源键与接口改造**：
     - 统一内部资源唯一标识为四元组：`(datasource_id, catalog, schema, object_name)`，并携带标识符引号标记（区分大小写敏感与折叠规则，适配 PG 默认小写折叠与 MySQL `lower_case_table_names` 配置）。   
     - 3 个核心 MCP Tools（`list_databases_and_tables`、`get_table_schema`、`execute_readonly_sql`）入参显式包含 `datasource_id`、`database_name`（及 PG 下的 `schema_name`），禁止仅凭模糊库名跨源匹配。   
  2. **系统库与隐式目录完备拦截**：
     - 显式拦截系统命名空间：`information_schema`、`mysql`、`performance_schema`、`sys`、`pg_catalog`、`pg_toast`。   
     - 针对 PG 默认在 `search_path` 中隐式搜索 `pg_catalog` 的机制，在 `ptmcp-guard` 中内置 `pg_*` 系统视图/表前缀拦截规则（凡未加用户 schema 限定且以 `pg_` 开头的对象引用直接拒绝，或强制改写为用户 schema 限定名使其无法命中 `pg_catalog`）。   
  3. **AST 物理表完全限定名重写（Fully-Qualified Rewrite）**：
     - `ptmcp-guard` 完成物理表绑定与 ACL 比对后，**直接在 AST 的 `Table` 节点上写入已验证的 `schema` / `database` 前缀**（例如在 PG 下将未限定的 `orders` 改写为 `"sales"."orders"`，在 MySQL 下改写为 ``erp`.`orders``），再通过 `deparse` 生成 `final_sql`。由此保证无论底层连接的默认 `search_path` 如何，数据库实际执行的对象与通过 ACL 检查的对象 100% 相同。   
  4. **每次借还连接的上下文强制同步**：
     - 借出连接时：执行器校验并设置连接的当前 catalog/schema（如 PG 显式执行 `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY; SET LOCAL search_path TO <verified_schema>; SET LOCAL statement_timeout = 15000;`），不依赖 HikariCP 建池时的单次初始化。   
     - 归还连接时：强制回滚/结束只读事务，重置会话级状态与超时设置；若重置失败则直接销毁该物理连接。   

### 3. 【R3 修订】公开客户端 OAuth 2.1 续期机制定制与前置联调

- **缺陷说明**：Spring Security 7.1.2 `OAuth2RefreshTokenGenerator` 对 `ClientAuthenticationMethod.NONE` 的公开客户端不签发 Refresh Token，`mcp-security` 0.1.14 亦未改变该默认行为。   
- **实施方案**：
  1. **M0 客户端矩阵联调**：在 M0 阶段针对 WorkBuddy、Cursor、Claude Code、VS Code 逐一实测并记录：客户端注册方式（DCR / CIMD / 预注册）、`token_endpoint_auth_method`、PKCE `S256` 参数、`redirect_uri` 格式（重点验证 WorkBuddy 的 `workbuddy://` 自定义协议在 Spring Security 7.0.5+ 严格校验下的通过性，必要时定制白名单 scheme 校验器）以及 Access Token 过期后的客户端重试/刷新行为。   
  2. **自定义受控公开客户端 Refresh Token 生成器**：
     - 为满足员工“首次登录授权后自动续期、后续无感调用”的需求，在 `ptmcp-authz` 中自定义 `PtmcpRefreshTokenGenerator` 替代默认实现：仅当公开客户端在授权码流程中严格通过 **PKCE (`S256`)** 校验、且属于已启用 `refresh_token` 授权类型的合规 MCP 客户端时，允许签发 Refresh Token。   
     - **强制配套安全防护**：
       1. **Refresh Token Rotation（刷新令牌轮换）**：每次使用 Refresh Token 换取新 Access Token 时，必须同时签发新的 Refresh Token 并立即使旧 Refresh Token 失效。   
       2. **重放检测（Replay Detection）**：若检测到已作废的旧 Refresh Token 被再次提交（排除极短窗口内的网络并发重试），判定为令牌泄露，立即吊销该授权链（`oauth2_authorization`）下的全部 Access Token 与 Refresh Token，强制用户重新登录。   
       3. **严禁伪造机密客户端**：禁止为了绕开 `none` 限制而将共享 `client_secret` 下发到员工本地 IDE 配置文件中。   

### 4. 【R4 修订】收紧平台身份信任分级与服务端强绑定

- **缺陷说明**：本地 IDE 配置、WorkBuddy 的客户端 `staticHeaders`、以及允许 `none` 认证的公开 CIMD 域名，均不能从密码学上证明请求来自受控的平台后端；若据此赋予大权限 Scope，会导致客户端伪造平台身份越权。   
- **实施方案**：
  1. **重构平台信任三级模型（区分“逻辑分组”与“密码学可信调用方”）**：   
     - **Tier 1（高可信后端平台）**：仅限自研 Agent 平台或经架构核实由服务端反向代理发起请求的平台。必须采用**服务端注入的 `X-Platform-Id` + `X-Platform-Secret`（配合来源内网 IP/mTLS 校验）或 OAuth `private_key_jwt` 机密客户端认证**。只有通过 Tier 1 密码学核验的调用方，才能被授予扩展的平台级数据源 Scope。   
     - **Tier 2（受管桌面/公开客户端）**：通过 DCR 或 CIMD 注册的桌面客户端（如 WorkBuddy、Cursor、Claude Code）。**所有新注册的 DCR/CIMD 客户端默认进入“零扩展/最小受限 Scope”**，需由管理员在后台显式绑定平台策略；在未证实 WorkBuddy 的 `staticHeaders` 由腾讯受控服务端代理注入前，将其按客户端侧凭证对待，不授予高于普通桌面端的平台特权。   
     - **Tier 3（未知/兜底客户端）**：仅使用 PAT 且未提供可信服务端平台证明的请求，一律归入基础个人终端平台 Scope。   
  2. **平台身份不可篡改与一致性校验**：
     - 用户通过 OAuth 登录或生成 PAT 时，服务端根据已验证的客户端身份将 `platform_id` 固化写入 JWT Claims（含 `aud` Resource Indicator）或 `personal_token` 表记录中。   
     - 请求到达 `ptmcp-gateway` 时，网关提取 Token 内固化的 `platform_id`；若请求同时携带了 `X-Platform-Id` Header，**两者必须完全一致，如有任何冲突直接返回 403 拒绝，严禁使用请求 Header 覆盖 Token 内的 `platform_id`**。   

### 5. 【R5 修订】权限缓存、用户生命周期与令牌撤销闭环

- **缺陷说明**：若依后台修改用户状态、角色或部门并不会自动通知网关或吊销 OAuth/PAT，且 `acl_grant` 的 `valid_to` 自然到期不会触发写操作，导致旧权限缓存和已签发令牌继续生效。   
- **实施方案**：
  1. **统一权限与认证失效事件总线**：
     - 在 `ptmcp-admin` 中对以下 8 类操作统一织入失效事件发布逻辑：**① 用户停用/删除、② 用户改密/重置密码、③ 用户角色变更或角色停用、④ 用户部门迁移或部门树结构调整、⑤ `acl_grant` 授权增删改、⑥ 数据源/平台应用启停或 Scope 变更、⑦ Schema 表同步新增/删除、⑧ PAT 手动吊销**。   
     - 事件落库（`sys_security_event` 表）并同步通过 Redis Pub/Sub 推送至 `ptmcp-gateway` 与 `ptmcp-authz`；网关同时启动每 30 秒一次的数据库增量版本号轮询作为 Redis 消息丢失或故障时的兜底补偿。   
  2. **时间敏感型有效权限缓存（Time-Bounded Cache）**：
     - 网关计算 `(user_id, platform_id)` 的有效授权表集合时，同时扫描所有命中的 `acl_grant` 记录的 `valid_from` 与 `valid_to` 时间戳。   
     - Redis 缓存条目的过期时间（TTL）动态计算为：`TTL = min(标准缓存时长, 最近一个未来 valid_from 或 valid_to 距离当前的秒数)`，确保授权到期瞬间缓存自动失效，无需人工干预。   
  3. **令牌即时吊销与每请求状态核验**：
     - 当触发“用户停用/删除、用户改密”事件时，服务端立即删除/作废该用户在 `oauth2_authorization` 表中的所有活跃 Token 及 `personal_token` 表中的 PAT，并在 Redis 中递增该用户的 `user_auth_version` 并标记 `user_status=DISABLED`。   
     - `ptmcp-gateway` 在每次请求通过 JWT 签名校验或 PAT 哈希校验后，强制核对 Redis/本地短暂缓存中的 `user_status` 与 `user_auth_version`；若用户已停用或 Token 签发时的版本号低于当前版本，立即返回 401/403 阻断，将撤销生效延迟严格控制在秒级。   
  4. **登录与刷新认证策略对齐若依**：
     - `ptmcp-authz` 的表单登录与 Refresh Token 换发流程，必须完整实现若依 `PasswordAuthStrategy` 的检查项：校验 `sys_user.status`（停用/删除账号拒绝登录与刷新）、BCrypt 密码比对、Redis 登录失败次数累加与账户锁定（如错误 5 次锁定 10 分钟）及登录日志记录。   

### 6. 【R6 修订】JDBC 驱动级防 OOM 流式截断与安全取消状态机

- **缺陷说明**：MySQL 与 PG 驱动默认将整个结果集读入 JVM 内存，大字段会在应用层截断前造成 OOM；且超时后若未等取消完成就归还连接，迟到的 `KILL QUERY` 会误杀下一请求。   
- **实施方案**：
  1. **驱动级分批拉取与流式单元格截断**：
     - **PostgreSQL 驱动配置**：连接强制开启只读事务且设置 `connection.setAutoCommit(false)`，执行查询时设置 `statement.setFetchSize(100)` 与 `statement.setMaxRows(500)`，强制 pgJDBC 使用服务端游标分批取数，禁止一次性加载全量结果。   
     - **MySQL 驱动配置**：连接串启用 `useCursorFetch=true&defaultFetchSize=100`（服务端游标分批读取），配合 SQL 改写层强制收敛外层 `LIMIT <= 500` 及 `statement.setMaxRows(500)`。   
     - **流式读取防大字段 OOM**：遍历 `ResultSet` 时，针对 `VARCHAR` / `TEXT` / `CLOB` / `JSON` / `BLOB` 等变长或大对象列，**禁止直接调用 `rs.getString()` 或 `rs.getBytes()`**；统一通过 `rs.getCharacterStream()` 或 `rs.getBinaryStream()` 配合固定大小缓冲区（如单单元格上限 4KB）读取，超出部分立即丢弃并标记 `[TRUNCATED]`。   
     - **四级内存与并发预算**：设定单单元格上限（4KB）、单次请求总响应体积上限（1MB，超限即停止继续 fetch 行并标记 `truncated=true`）、单用户并发查询上限（如 3 个）及单数据源并发信号量，保护网关 JVM 与业务数据库内存。   
  2. **五阶段超时与“先确认终止、后归还/淘汰”连接状态机**：
     - 独立配置五项超时参数：`checkoutTimeout=3s`（从 Hikari 借连接）、`connect/socketTimeout=20s`（底层网络兜底）、`queryTimeout=15s`（SQL 执行超时）、`cancelTimeout=3s`（取消指令执行限时）、`cleanupTimeout=2s`（事务回滚与会话重置限时）。   
     - **数据库原生取消机制**：PG 通过会话级 `SET LOCAL statement_timeout = 15000` 配合 JDBC `statement.cancel()`（发送 PG CancelRequest 报文）；MySQL 通过会话级 `SET SESSION max_execution_time = 15000` 配合受控的 `statement.cancel()`。   
     - **安全归还铁律**：当请求因超时、客户端断开或字节超限提前中止时，**当前工作线程必须一直持有该 JDBC 连接，直至 `cancel()` 和 `rollback()` 明确成功返回且确认无在途查询后，才允许重置状态并归还 HikariCP 连接池**。若 `cancel()` 或 `rollback()` 抛出异常、或超过 `cancelTimeout` / `cleanupTimeout` 仍未确认干净终止，**立即调用 `connection.abort(executor)` 或通过 HikariCP 将该物理连接强制标记为损坏并淘汰销毁（Evict），绝不归还池中**，彻底杜绝迟到 `KILL` 误杀后续请求的竞争隐患。   
     - **多副本连接数核算**：后台配置数据源 `pool_max` 时，按 `全局数据库可承受最大连接数 ≥ Σ(各数据源 pool_max) × ptmcp-gateway 实例副本数` 进行容量管控与告警提示。   

## 四、 四项中优先级问题（P2：R7、R9、R10）修订细则

### 1. 【R7 修订】静态只读证明边界、函数与类型转换（CAST）白名单

- **拒绝带限定路径的函数与表函数**：移植 dbx `sql_risk.rs`（第 1435～1457 行）的严格约束，在 `FunctionAllowlist` 中**一律拒绝带有 schema 或 database 前缀的函数调用（如 `public.custom_fn()`、`erp.my_udf()`）以及 `FROM` 子句中的表函数（Table Function）**，仅允许调用无前缀的数据库内置标量/聚合/窗口白名单函数。   
- **显式约束 `CastExpression` 目标类型**：针对 JSQLParser 将 `CAST(x AS type)` 及 PG `x::type` 解析为 `CastExpression` 的特点（如探针中的 `SELECT CAST('x' AS public.leaky_type)`），访问器除允许 `CAST` 节点外，**必须校验 `CastExpression.getColDataType()` 的类型名：严禁带有 schema 前缀，且必须命中内置基础类型白名单（`CHAR`、`VARCHAR`、`TEXT`、`INT`、`INTEGER`、`BIGINT`、`SMALLINT`、`DECIMAL`、`NUMERIC`、`FLOAT`、`DOUBLE`、`DATE`、`TIME`、`DATETIME`、`TIMESTAMP`、`BOOLEAN`、`JSON`、`JSONB`）**，防止通过自定义类型转换触发隐式函数副作用。   
- **拒绝自定义操作符与未知 AST 节点**：显式 `OPERATOR(...)` 语法及任何不在已验证安全列表内的 `Expression` 子类一律 fail-closed 拒绝。   
- **数据库侧权限收口**：接入 PTMCP 的数据库账号必须仅授予目标业务 schema 的 `SELECT` 权限，明确禁止授予 `EXECUTE`（自定义函数/存储过程）、MySQL `FILE`、PG `pg_read_server_files` 与 `pg_execute_server_program` 权限。   

### 2. 【R9 修订】审计日志高可靠投递与敏感 SQL 字面量脱敏

- **全链路可靠审计与降级策略**：
  - 每次 MCP 工具调用在入口处生成唯一 `request_id`。   
  - 审计采用“内存有界队列 + 本地磁盘 WAL 溢出持久化（或 Redis Stream AOF 持久化） + 批量异步入库（按 `request_id` 唯一索引幂等去重）”架构。   
  - **不可用阻断策略**：为严格满足需求规格书“记录每一次 MCP 工具调用”的审计合规要求，当审计队列满且本地持久化/Redis Stream 均不可写时，网关触发高优告警并对 `execute_readonly_sql` 采取 **fail-closed（拒绝执行查询）**，确保不存在“无审计的暗箱数据查询”。   
- **SQL 敏感字面量脱敏与分级存储**：
  - `ptmcp-guard` 在解析 AST 时同步生成脱敏指纹 `sql_masked` / `sql_fingerprint`（将 `WHERE name = '张三' AND id_card = '440...'` 中的字符串与数字字面量统一替换为 `?`）。   
  - 推送至外部日志中心（ELK / Loki）及管理后台审计列表默认展示 `sql_masked`；包含原始字面量的 `sql_raw` 与 `sql_final` 使用 AES-256-GCM 加密后存入元数据库审计表，仅具备特定审计脱敏权限的安全管理员可解密查看，并严格执行 180 天到期自动清理归档。   
  - 普通用户门户的“我的查询历史”接口强制从服务端安全上下文读取当前登录 `user_id` 进行过滤，不接受前端传入 `user_id`。   

### 3. 【R10 修订】MCP 工具错误语义、元数据可见性与验收标准对齐

- **MCP 工具错误语义规范化**：
  - 修正原方案“权限拒绝返回 `isError=false`”的设计。遵循 MCP 2026-07-28 工具规范：HTTP/OAuth 鉴权失败直接由安全过滤链返回 HTTP 401/403；进入 MCP Tool 后发生的**表越权、非只读阻断、危险函数拦截、语法错误、查询超时等所有拒绝或执行失败，统一返回 `CallToolResult(isError = true)`**。   
  - 在 `content` 中返回结构化 JSON 提示，显式包含 `"retryable": false` 与错误分类码（例如：`{"status":"denied","errorCode":"TABLE_NOT_AUTHORIZED","retryable":false,"objects":["erp.t_salary"],"message":"权限不足：当前账号无权查询表 [erp.t_salary]，请联系管理员申请权限。"}`），引导外部 Agent 直接向用户转述原因而非盲目重试；同时网关监控面板按 `verdict` 枚举统计，不将业务权限拒止误算为系统故障。   
- **元数据可见性与 Action 行为统一**：
  - `acl_grant` 明确区分 `meta`（目录可见）与 `read`（数据可查，自动包含 `meta`）两种动作。   
  - `list_databases_and_tables`：若平台配置为“展示全表并标记权限”，则返回平台范围内的表名与注释，并准确标注 `authorized: true/false`；若开启“隐藏无权表”，则仅返回用户具备 `read`（或 `meta`）权限的表。   
  - `get_table_schema`：**严格收紧——仅允许对当前用户具备 `read` 权限的表返回字段名、类型、主外键与字段注释**；若用户对该表无 `read` 权限，直接返回权限拒绝或仅返回表名与表级注释，绝不泄露敏感表的内部列结构。   
- **验证材料入库与量化指标口径**：
  - 所有 PoC 探针源码、Maven `pom.xml`、测试用例 ID、参数渲染逻辑、预期输出与实测日志统一提交至仓库 `docs/evidence/` 及测试模块中，确保每一项结论均可本地一键复跑验证。   
  - **29 条 VQ 验收口径**：不仅要求 JSQLParser 解析通过，还必须验证经过 `LexicalPrecheck`、`ScopedTableBinder`、ACL 校验、完全限定名与 `LIMIT` 改写后生成的 `final_sql` 在测试库上执行结果与原 SQL 一致。   
  - **性能与安全验收口径**：“P95 网关额外开销 < 50ms”明确定义为：在 2C4G 实例、50 并发、SQL 长度 ≤ 4KB、Redis 权限缓存热命中条件下，网关从接收请求到发起 JDBC 调用前（扣除业务数据库执行与网络传输时间）的内部处理耗时；“无高危绕过”定义为在本文档界定的威胁模型及内部回归集（≥300 条，含 dbx 移植测试、R1 复现反例集、skills 评测集与 sqlmap 变异集）下拦截率 100%。   

## 五、 修订后的安全沙箱八步流水线（`execute_readonly_sql`）

Plaintext

```
[入参: datasource_id, database_name, schema_name(可选), sql_query]
   │
   ├─▶ Step 1: 原文词法预检 (LexicalPrecheck，移植 dbx)
   │     • 限制字符/字节长度；拒绝 NUL、非法 UTF-8 及 \G 等客户端指令
   │     • 状态机扫描字符串、引号与注释：凡遇 MySQL 可执行注释 /*! 或优化器 Hint /*+ 直接拒绝
   │     • 扫描字面量与注释外关键字：拒绝 INTO OUTFILE/DUMPFILE、任意 INTO、:=、多余分号 ; 以及 USE/SET/CALL/DO/HANDLER/LOAD DATA
   │
   ├─▶ Step 2: 语法树解析与单语句校验 (JSQLParser 5.4)
   │     • CCJSqlParserUtil.parseStatements 解析失败即拒绝 (fail-closed)
   │     • 严格要求语句数 == 1，且顶层节点类型必须为 Select (禁止 EXPLAIN ANALYZE)
   │
   ├─▶ Step 3: 结构只读红线校验 (ReadOnlyVerifier，移植 dbx)
   │     • 递归检查所有 WithItem (禁止 ParenthesedInsert/Update/Delete 可写 CTE 及未授权递归 CTE)
   │     • 递归检查 SetOperationList 分支与子查询；禁止 getForMode() 锁子句、getIntoTables() 及 getMySqlSelectIntoClause()
   │
   ├─▶ Step 4: 函数与类型转换严格白名单 (FunctionAndTypeAllowlist，覆盖 R7)
   │     • 遍历 Function、AnalyticExpression (OVER 窗口)、CastExpression、ExtractExpression 等全部表达式节点，未知节点直接拒绝
   │     • 禁止带 schema/db 限定前缀的函数及表函数；仅放行当前方言白名单内的内置纯函数 (拒绝 sleep/load_file/pg_read_file/set_config 等)
   │     • 校验 CastExpression 目标类型：禁止带 schema 限定，且必须属于内置基础标量类型白名单
   │
   ├─▶ Step 5: 带作用域栈的物理表绑定与越权判定 (ScopedTableBinder & AclChecker，覆盖 R1/R2)
   │     • 按词法作用域栈提取全部真实物理表，准确识别同名派生表别名、嵌套 CTE 与非递归同名 CTE 中的物理表
   │     • 结合已验证的 (datasource_id, catalog, schema) 绑定四元组资源键；拦截系统库及 PG 隐式 pg_* 系统目录
   │     • 与 Redis 有效权限集合 (平台 Scope ∩ 用户有效授权，带时间边界与状态核验) 比对；未授权立即返回 isError=true 结构化拒止
   │
   ├─▶ Step 6: AST 完全限定名与性能保护改写 (AstRewriter，覆盖 R2)
   │     • 将 AST 中所有物理表节点强制改写为已验证的完全限定名 (schema.table / db.table)，杜绝“检查 A 执行 B”
   │     • 外层收敛 LIMIT <= 500 (兼容 OFFSET 与 PG FETCH FIRST n ROWS ONLY)；二期在此注入行级过滤派生表
   │     • 通过 AST deparse 生成 final_sql，并提取参数脱敏后的 sql_masked
   │
   ├─▶ Step 7: 强绑定上下文与流式防 OOM 执行 (SafeExecutor，覆盖 R6)
   │     • 从对应 datasource_id 连接池借出连接，显式设置只读事务、已验证的 catalog/schema/search_path 及 15s 语句超时
   │     • 启用驱动游标分批读取 (fetchSize=100, maxRows=500)，使用字符/字节流按 4KB 单元格上限与 1MB 总上限流式截断
   │     • 若发生超时或中断，持有连接直至 cancel + rollback 确认完成后再归还；状态不确定则强制淘汰销毁物理连接
   │
   └─▶ Step 8: 结果封装与可靠审计落盘 (覆盖 R9/R10)
         • 将执行状态、脱敏 SQL (及加密原 SQL)、命中规则、耗时、行数写入持久化审计通道 (审计不可写则 fail-closed)
```

## 六、 重排后的里程碑实施计划（M0 前移验证 + M1～M4）

根据审查结论，将核心风险项（表级 ACL 作用域绑定、对象与执行上下文绑定、公开客户端 OAuth 续期验证、模块依赖隔离）全部前移至 **M0**，按以下 5 个递进步骤推进：   

| **阶段**                                  | **建议周期**             | **核心交付物（按审查建议重排）**                             | **硬性验收标准（Exit Criteria）**                            |
| ----------------------------------------- | ------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **M0：架构基线锁定与高风险纵切验证**      | 第 1～2 周               | 1. **工程与依赖基线（R8）**：锁定元数据库选型（MySQL 或 PG）；搭建 Maven 多模块，`ptmcp-common` 剥离若依安全/ORM 依赖，产出 `effective-pom` 与启动测试。     2. **最小安全纵切（R1/R2/R7）**：实现 `ptmcp-guard`（词法预检、只读校验、函数与 CAST 类型白名单、**自研 `ScopedTableBinder` 作用域表绑定器**、物理表完全限定名改写、LIMIT 改写）；打通单 MySQL + PAT + 单平台 + `final_sql` 执行 + 可靠审计。     3. **OAuth 可行性前置验证（R3/R4）**：搭建 `ptmcp-authz` 原型，完成 1 个公开客户端（`none + S256`，如 WorkBuddy/Cursor）和 1 个受信服务端客户端的真实联调，验证发现、登录、过期、受控 Refresh Token 轮换或重新授权、以及 `workbuddy://` 回调校验。 | • R1 复现报告中的 **6 个表提取案例（含 4 个反例）100% 准确识别 `salary` 表**，越权拒绝时执行器调用次数为 0。     • `final_sql` 中所有物理表均带显式库/schema 限定且与授权对象一致。     • `ptmcp-gateway` 依赖树中无 Sa-Token 且 JSQLParser 唯一版本为 5.4。     • 公开客户端与服务端客户端跑通真实 OAuth 登出/过期/刷新与重放检测流程。 |
| **M1：双方言执行器、失效闭环与回归集**    | 第 3～5 周               | 1. **PG 方言与执行器加固（R2/R6）**：接入 PostgreSQL，实现 PG `search_path` 绑定、隐式 `pg_*` 系统表拦截、`FETCH FIRST` 改写；落实 MySQL/PG 驱动游标分批读取、流式大字段截断、五阶段超时与“先确认取消再归还/异常淘汰”连接管理。     2. **权限交集与失效闭环（R4/R5）**：实现平台三级信任模型與 Token `platform_id` 强绑定；实现带 `valid_from/valid_to` 时间边界的 Redis 权限缓存，打通用户停用/改密/角色/部门变更对缓存、OAuth Token 及 PAT 的秒级联动失效。     3. **回归测试工程（R10）**：建立 `ptmcp-regression-internal`（≥300 条用例，含 dbx 移植测试、29 条 VQ 端到端校验及证据包）。 | • 双数据源同名库表、PG 双 schema 同名表、引号大小写、无 schema 系统表测试全通过。     • 大字段（如 10MB TEXT）与多行并发读取下 JVM 峰值内存有界；构造“查询刚结束、取消迟到、下一请求开始”竞争测试，下一请求零误杀。     • 停用活跃用户、变更角色、授权到期后，OAuth/PAT 与热缓存在约定秒级内全部失效。 |
| **M2：多客户端 OAuth 完备接入与后台核心** | 第 6～7 周               | 1. **全客户端联调**：完成 WorkBuddy、Cursor、Claude Code、VS Code 四类客户端的 OAuth / PAT 接入与配置模板固化。     2. **管理后台后端（`ptmcp-admin`）**：基于 RuoYi-Vue-Plus 6.0 完成数据源动态管理、Schema 增量同步与业务字典维护、平台注册、权限矩阵配置及加密/脱敏审计查询 API。 | • 四类主流客户端完成首次登录、续期与越权结构化提示（`isError=true`）实测。     • 审计队列满或不可用时正确触发告警与 fail-closed 保护，敏感字面量在日志外发中 100% 脱敏。 |
| **M3：双视角前端控制台（`ptmcp-ui`）**    | 第 6～8 周（与 M2 并行） | 1. **管理员视角（Admin Console）**：数据源管理台、组织与账号管理（复用若依）、平台驱动管理、树状权限配置矩阵、全局问数审计日志看板。     2. **普通用户视角（User Portal）**：我的数据字典（被授权表与字段浏览，无 `read` 权限不暴露列结构）、一键复制通用 MCP 配置、个人专属 PAT 管理与个人查询历史。 | • 管理员可在 10 分钟内完成新部门创建、数据源表级授权与平台 Scope 配置闭环。     • 用户门户历史记录严格隔离，无权表列信息零泄露。 |
| **M4：红队 Fuzz、压测与灰度上线**         | 第 9～10 周              | 1. **安全红队与 Fuzz**：针对作用域欺骗、方言冷门语法、自定义类型/函数、并发刷新令牌重放、慢查询风暴进行全面红队测试。     2. **性能压测与上线交付**：多副本连接池容量压测、可选接入加严版 Druid WallFilter 旁路告警、输出完整运维手册与可复跑验证证据包。 | • 指定威胁模型与回归/Fuzz 测试集下高危绕过为 0。     • 标准压测条件下（≤4KB SQL、50 并发、热缓存）网关内部 P95 开销 < 50ms。 |
| **二期演进**                              | 第 11～16 周             | ERP 组织架构定时/Webhook 同步、字段级（列级）敏感拦截与脱敏、行级（RLS）AST 派生表自动注入、企业 SSO（Keycloak OIDC）联邦对接、自研平台 Token Exchange（RFC 8693）。 | • 行列权限回归集全部通过；ERP 组织变更 5 分钟内自动同步并触发权限缓存重算。 |