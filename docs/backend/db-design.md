# 数据库详细设计

版本：1.0-design · 2026-10-23 · 状态：T03-a1已增加安全基础三表迁移，余表与完整事务/恢复仍待实现验证。本文为设计契约，实际迁移范围见[基础模块](../../ptmcp-parent/ptmcp-persistence/README.md)，不将全表格冒称已实现。跨文档参数以 [总体方案](../总体方案.md) 为准。

## 1. 存储边界与约定

一期元数据库固定 MySQL 8.4 / InnoDB / utf8mb4。连接、数据库会话和应用时钟统一 UTC，时间字段为 DATETIME(6)，API 使用 RFC3339 的 Z 时间；用户界面才转换 Asia/Taipei。禁止使用无时区本地时间计算到期。业务 MySQL / PostgreSQL 是被查询目标，不能混用为元库；本项目不分库分表，审计一期不分区。

内部主键使用正整数有符号 BIGINT（1～9223372036854775807，分配算法由工程实现锁定），REST/MCP 中这些 ID 使用 ASCII 十进制字符串，禁止经过浮点数转换；输入前导零按数值规范化，0、符号、空白、非ASCII数字及溢出拒绝。common 的 ResourceId 已实现此内部值契约，不代表 HTTP 序列化或业务身份认证已实现；适配层必须显式输出十进制字符串而非直接序列化领域对象。request_id、事件 ID 使用 UUID 字符串 CHAR(36) ASCII。配置表有 version BIGINT NOT NULL DEFAULT 1、create_time/update_time DATETIME(6)、created_by/updated_by BIGINT；安全修改 version 乐观锁失败返回 409。所有新表名称、状态、长度以本文为迁移输入，迁移产物必须补上显式 CHECK、NOT NULL、索引名称与外键名称；不允许 ORM 自动改生产表。

本文表格中 N 表示可空，未标 N 为非空；所有布尔值 TINYINT(1) 加 CHECK IN (0,1)。状态使用 VARCHAR + CHECK 集合而非 MySQL ENUM，便于受控迁移。标识符原文/规范值使用二进制排序规则，业务连接名称规则由方言适配决定，不依赖元库不区分大小写排序。密钥材料不进入数据库备份明文，所有密码/原 SQL 用 KMS 信封加密。

## 2. 实体关系与写入所有权

本文所有 PTMCP 元库表（含关联、快照、审计及 OAuth 扩展后的实际表）都必须有显式主键、`create_time DATETIME(6) NOT NULL`、`update_time DATETIME(6) NOT NULL`。下方表格省略通用时间字段时仍继承此约定；已列的主键/复合主键保持，不额外塞入无用 id。物理表/列小写 snake_case，时间由服务端按 UTC 可靠填充；REST 的 createdAt/updatedAt 通过适配器映射，不把数据库列名直接序列化。

不可变快照/事件首次插入时两时间相同，禁止 `ON UPDATE` 自动改写历史；outbox 投递状态、审计 START→terminal 等允许状态变化的表只在合法状态迁移时更新 update_time，不能用它替代 occurred_at/started_at/published_at 等领域时间。OAuth 锁定标准 schema 的协议字段保留，补通用列须同步 JDBC/仓储映射和迁移测试。

自有 Mapper/仓储/运维 SQL 禁止投影 SELECT * / t.*，保留 COUNT(*) 行数语义，禁止拼接用户值。迁移在 MySQL 8.4 测试实例断言每表主键/时间字段、命名/唯一索引和事务完整性；当前仅sys_security_state/sys_security_event/security_consumer_cursor有版本化DDL和实库结构/约束测试；其余表及完整自有SQL AST扫描门禁未完成，不能以三表结果宣称全部通过。阿里手册采用及架构差异见[工程标准](../standards/quality-tooling.md)。外部业务源只读，不因本规范给接入库加字段或改名。

~~~mermaid
erDiagram
  sys_user ||--|| sys_user_security : extends
  sys_user ||--o{ sys_user_role : has
  sys_role ||--o{ sys_user_role : assigned
  sys_dept ||--o{ sys_user : contains
  sys_user ||--o{ personal_token : owns
  sys_user ||--o{ oauth_refresh_family : authorizes
  oauth_refresh_family ||--o{ oauth_refresh_token : rotates
  platform_registry ||--o{ platform_scope : bounds
  platform_registry ||--o{ oauth2_registered_client : registers
  datasource ||--o{ resource_namespace : contains
  resource_namespace ||--o{ resource_object : snapshots
  resource_object ||--o{ resource_column : contains
  resource_object ||--o{ acl_grant : target
  audit_outbox }o--|| audit_log : materializes
  sys_security_state ||--o{ sys_security_event : advances
~~~

ERD 的 acl_grant 还可以指向数据源/命名空间；多态主体/目标用受限类型 + 服务事务检查，不创建无法表达的多态外键。audit_log 可在 START 消费后才出现，因此关系不表示必须先有 audit_log。

| 写入方 | 拥有数据 | 其他服务允许操作 |
| --- | --- | --- |
| admin | 若依组织数据、平台/范围、ACL、数据源配置、字典 | gateway/authz 用独立最小只读账号读取身份及策略 |
| authz | 注册客户端协议状态、authorization、refresh family、AS session | admin 通过撤销领域服务/同一权威事务改变状态，不能随意删除令牌行 |
| gateway | PAT 使用时间、审计准入/终结、池运行状态 | admin 查询审计；后台消费者物化事件 |
| metadata worker（admin 内部任务） | 快照 staging、发布版本、同步任务 | gateway 只读已发布快照 |
| 安全领域服务 | 安全版本 + sys_security_event | 所有影响安全的写入须在该领域事务中进行 |

三应用共享一个元库的受控表模型，不共享 ORM 对象或若依安全框架。admin/authz 对组织改动调用同一安全事务适配；不得让若依旧 CRUD 直接旁路。数据源凭证的解密权限只授予需要建立连接的工作负载。

## 3. 若依实际表复用与迁移映射

来源：Reference/RuoYi-Vue-Plus-6.X/script/sql/ry_vue.sql（本地源码）。这是参考实现字段，不是对 PTMCP 已实现的声明。

| 原表/实际字段 | PTMCP 语义与注意事项 |
| --- | --- |
| sys_user.user_id、dept_id、user_name、nick_name | 身份、直属部门、登录名、显示名；API 映射 userId/deptId/username/displayName |
| sys_user.password VARCHAR(100) | BCrypt 哈希；不返回、不记录、不当作第三方 secret |
| sys_user.status CHAR(1) | '0' 正常、'1' 停用；API 映射 ACTIVE/DISABLED |
| sys_user.del_flag CHAR(1) | '0' 存在、'1' 删除；删除比 status 优先 |
| sys_user.login_ip、login_date | 最后登录信息；完整失败记录另写安全日志 |
| sys_user.create_dept/create_by/create_time/update_by/update_time | 复用原审计字段，UTC 转换迁移；不强行改成新表通用字段 |
| sys_dept.dept_id、parent_id、ancestors、dept_name、status、del_flag | parent_id 是权威树边；ancestors 为可重建加速数据，迁移/写入检查环及一致性 |
| sys_role.role_id、role_key、role_name、status、del_flag | 角色主体；停用角色不参与有效授权 |
| sys_role.data_scope、sys_role_dept(role_id,dept_id) | 若依管理数据范围；不能直接换算成业务表 READ/META |
| sys_user_role(user_id,role_id) | 复合主键，用户角色成员关系 |
| sys_menu.perms / sys_role_menu | 控制台功能权限，使用 ptmcp:* 新权限码；不赋予业务数据权限 |

参考 sys_user.user_name 只有普通索引，PTMCP 迁移需先扫描重名并清理，再建立登录名规范列/唯一索引，防止一名多账号。新增 sys_user_login_name(user_id PK/FK, normalized_name VARCHAR(64) binary UNIQUE)；只允许后台规定的 ASCII 登录名并统一小写；现有不合规账号走人工迁移映射，禁止静默合并。参考种子账号/密码不进入目标环境。

新增安全扩展表避免侵入若依大量字段：

| 表 | 字段 | 主键/索引/约束 |
| --- | --- | --- |
| sys_user_security | user_id BIGINT；auth_version BIGINT=1；identity_version BIGINT=1；identity_state UNBOUND/READY/CHANGE_PENDING（默认UNBOUND）；identity_change_id UUID N；version BIGINT；password_changed_at DATETIME(6) N | PK/FK sys_user_profile.user_id；唯一权威；改密同时提升 auth/identity 版本；Pending 拒绝认证，不因超时释放 |
| identity_password_change | operation_id UUID；user_id；auth_version；profile_version；expected_hash BINARY(32) N；target_hash BCrypt N；status PENDING/COMPLETED；requested_by；时间 | PK operation_id；UNIQUE(user_id,profile_version)；FK sys_user_security；Pending 必须保留不可变 hash，完成同事务清空两列，仅保留幂等墓碑 |
| sys_subject_version | subject_type VARCHAR(16) USER/ROLE/DEPT；subject_id BIGINT；policy_version BIGINT；version BIGINT | PK(type,id)；删除保留 tombstone，不复用主体 ID |
| sys_security_state | singleton_id TINYINT=1；policy_epoch BIGINT；update_time DATETIME(6) | PK 1，锁住本行分配全局 epoch |
| sys_security_event | event_id CHAR(36)；epoch BIGINT；aggregate_type VARCHAR(32)；aggregate_id VARCHAR(64)；event_type VARCHAR(48)；payload JSON；occurred_at DATETIME(6)；published_at N；attempts INT=0 | PK event_id；UNIQUE(epoch,event_id)；INDEX(published_at,occurred_at,event_id)；payload 仅 ID/版本，不能含凭证或 SQL |
| security_consumer_cursor | consumer_id VARCHAR(128)；last_epoch BIGINT；update_time DATETIME(6) | PK consumer_id；不以“已经收到消息”替代主库安全核验 |

用户/角色/部门修改与扩展版本、epoch、outbox 同一 MySQL 主库事务。临时登录失败计数 Redis 账号/IP 双桶，锁5次10分钟，Redis 不可用时拒绝密码认证或使用经测试的主库限速后备，不能静默取消限速。

跨库改密按 [ADR-0011](../architecture/adr/0011-跨库身份变更与权威版本.md)先 Pending 与撤证、再宿主 expected-hash CAS、最后完成。
sys_user_profile.password_hash 是遗留兼容列，不作为认证源；改密时写 external 哨兵，不再复制宿主 BCrypt。
V11/V12/V13 升级需停旧写方，禁止旧新混跑；已备份的旧恢复材料仍按密码资产控制与清理。

## 4. 平台、资源与授权表

| 表 | 主要字段（除通用字段） | 约束与索引 |
| --- | --- | --- |
| platform_registry | id；code VARCHAR(64)；name VARCHAR(128)；tier TINYINT(1..3)；status ACTIVE/DISABLED/DELETED；catalog_mode DISCOVER/HIDE；proof_mode MTLS/PROXY_SECRET/NONE；proof_config_cipher BLOB N；proof_key_id VARCHAR(128) N；scope_version BIGINT；security_version BIGINT | UNIQUE(code) 永不复用；Tier1 proof_mode 不得 NONE；密文配置不在 GET 返回 |
| platform_scope | id；platform_id；resource_type DATASOURCE/NAMESPACE/OBJECT；resource_id BIGINT；resource_generation BIGINT；action META/READ；include_descendants BOOL | UNIQUE(platform_id,resource_type,resource_id,resource_generation,action)；INDEX(resource_type,resource_id)；范围只允许上限，不表达用户 Allow |
| datasource | id；code VARCHAR(64)；name VARCHAR(128)；engine MYSQL/POSTGRESQL；host VARCHAR(253)；port INT；database_name VARCHAR(128)；username VARCHAR(128)；credential_cipher BLOB；key_id VARCHAR(128)；tls_mode VERIFY_FULL；pool_max SMALLINT；status DRAFT/ACTIVE/DISABLED/DELETED；config_version BIGINT；resource_version BIGINT；active_snapshot_id BIGINT N；last_sync_at N | UNIQUE(code)；port 1..65535；pool_max 1..8（单副本设计默认预算），所有扩容须容量审批；不能任意写 JDBC URL/属性 |
| resource_namespace | id；datasource_id；catalog_raw VARCHAR(128)；catalog_norm VARCHAR(128) binary；schema_raw VARCHAR(128)；schema_norm VARCHAR(128) binary；generation BIGINT；status ACTIVE/MISSING/RETIRED | UNIQUE(datasource_id,catalog_norm,schema_norm,generation)；MySQL schema 字段存空串；PG 非空 |
| schema_snapshot | id；datasource_id；config_version BIGINT；resource_version BIGINT；status STAGING/PUBLISHED/FAILED/RETIRED；captured_at；expires_at；object_count INT；checksum BINARY(32) | INDEX(datasource_id,status,captured_at)；发布用 datasource.active_snapshot_id 原子切换；有效期由部署配置制定并验收，未配置拒绝启用 |
| resource_object | id；namespace_id；generation BIGINT；object_raw VARCHAR(128)；object_norm VARCHAR(128) binary；quoted BOOL；kind TABLE/VIEW；status ACTIVE/MISSING/RETIRED；definition_hash BINARY(32)；physical_identity VARCHAR(256) N；first_seen_at；last_seen_at | UNIQUE(namespace_id,object_norm,generation)；id 为授权对象世代，删除重建不得重用；PG physical_identity 可记录 OID 但不将 OID 当永久ID |
| snapshot_object | snapshot_id；object_id；object_version BIGINT；table_comment TEXT N；column_signature BINARY(32) | PK(snapshot_id,object_id)；FK；同一快照不可变 |
| resource_column | snapshot_id；object_id；ordinal INT；column_raw VARCHAR(128)；column_norm VARCHAR(128) binary；quoted BOOL；data_type VARCHAR(128)；jdbc_type INT；nullable BOOL；precision_value INT N；scale_value INT N；max_length BIGINT N；comment TEXT N；is_primary_key BOOL；foreign_key JSON N | PK(snapshot_id,object_id,ordinal)；UNIQUE(snapshot_id,object_id,column_norm)；非公开系统类型不标成可执行类型 |
| business_dictionary | object_id；column_norm VARCHAR(128) binary（空串为表级）；title VARCHAR(128)；description TEXT；owner_user_id BIGINT N；version；更新时间/人 | PK(object_id,column_norm)；内容不含可执行指令；版本跨快照保留，删除重建需重新确认 |
| metadata_sync_job | id；datasource_id；requested_by；config_version；state QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED；started_at N；finished_at N；snapshot_id N；error_code VARCHAR(64) N；lease_owner VARCHAR(128) N；lease_until N | INDEX(state,lease_until,id)；每源仅一个 RUNNING（事务占用租约，MySQL无条件部分唯一索引） |
| acl_grant | id；subject_type USER/ROLE/DEPT；subject_id；resource_type DATASOURCE/NAMESPACE/OBJECT；resource_id；resource_generation；action META/READ；effect ALLOW/DENY；include_descendants BOOL；valid_from DATETIME(6)；valid_to DATETIME(6) N；status ACTIVE/REVOKED；reason VARCHAR(500)；version | INDEX(subject_type,subject_id,status,valid_from,valid_to)；INDEX(resource_type,resource_id,resource_generation,status)；valid_to > valid_from；目标/主体不存在拒绝写入 |
| admin_delegation | id；user_id；department_root_id；resource_type DATASOURCE/NAMESPACE/OBJECT；resource_id；resource_generation；allowed_actions JSON；valid_from；valid_to N；status ACTIVE/REVOKED；version | INDEX(user_id,status,valid_from,valid_to)；平台管理员可写；命令须有一个完整委派覆盖主体子树+资源+动作+期限，不能跨委派拼接 |

视图一期只能在依赖图可完整解析、定义哈希一致、依赖表 READ 全通过时启用；视图 DEFINER/SECURITY 语义不能绕过依赖 ACL。未知视图/外部表/跨库链接对象不发布为可执行对象。命名空间/对象身份生命周期与同步策略见 [datasource](datasource.md)。schema_snapshot 的到期策略未通过双引擎DDL竞争测试前，相关路径保持关闭；不能用长 TTL 宣称对象绑定始终可靠。

## 5. OAuth、PAT 与会话

OAuth 协议标准表以锁定 Spring Security 版本所附 schema 为物理迁移起点，不复制网络上其他版本DDL。下表是逻辑设计语义，不是完整官方DDL：官方字段/索引保留为锁定版本迁移起点，自定义摘要/密文/family通过扩展列或旁表与适配接口实现。不能把该简化字段表直接套用stock JdbcOAuth2AuthorizationService；必须实现/验证RegisteredClientRepository、OAuth2AuthorizationService、OAuth2AuthorizationConsentService及family事务适配。尤其registered_client_id为注册表内部id，client_id为公开OAuth标识，外键一律使用内部registered_client_id，不混用。长 token 不明文落库、不输出序列化属性中的认证凭证，授权属性不使用任意Java对象反序列化。

| 表 | 字段/约束 |
| --- | --- |
| oauth2_registered_client | id VARCHAR(100) PK；client_id VARCHAR(200) UNIQUE；platform_id FK；client_type PUBLIC/CONFIDENTIAL；auth_methods JSON；grant_types JSON；redirect_uris JSON；allowed_scopes JSON；client_secret_hash N；jwks_uri N；metadata_uri N；metadata_digest BINARY(32) N；status ACTIVE/DISABLED；registration_source ADMIN/DCR/CIMD；version；create_time。URI须精确验证；DCR默认不开放数据查询范围 |
| oauth2_authorization | id VARCHAR(100) PK；client_id FK（内部注册ID）；user_id；family_id CHAR(36) N；authorization_code_hash BINARY(32) UNIQUE N；code_expires_at N；code_consumed_at N；redirect_uri；pkce_challenge；pkce_method；scopes JSON；auth_version；status ACTIVE/REVOKED/EXPIRED；version；create_time；expires_at。code消费为原子条件更新 |
| oauth2_authorization_consent | registered_client_id；user_id；scopes JSON；version；update_time；PK(client,user)。同意不改变平台上限 |
| oauth_refresh_family | id CHAR(36) PK；authorization_id FK；user_id FK；registered_client_id FK；platform_id FK；status ACTIVE/REVOKED/EXPIRED；auth_version BIGINT；absolute_expires_at；idle_expires_at；last_rotated_at；revoked_at N；revoke_reason N；version。INDEX(user_id,status)；INDEX(status,absolute_expires_at,idle_expires_at) |
| oauth_refresh_token | token_hash BINARY(32) PK；family_id FK；generation BIGINT；issued_at；expires_at；consumed_at N；successor_hash BINARY(32) N；status ACTIVE/CONSUMED/REVOKED。UNIQUE(family_id,generation)；INDEX(family_id,status)；旧哈希墓碑保留至整族过期，不能轮换即删除 |
| personal_token | id BIGINT PK；user_id FK；platform_id FK（仅Tier3）；token_prefix VARCHAR(16)；token_hash BINARY(32) UNIQUE；name VARCHAR(64)；scopes JSON；auth_version BIGINT；expires_at；last_used_at N；revoked_at N；status ACTIVE/REVOKED/EXPIRED；version。INDEX(user_id,status,create_time)；默认30天最大90天；随机≥256bit；服务端摘要可用带key_id的HMAC，密钥独立于JWT签名 |
| web_session_index | session_hash BINARY(32) PK；kind ADMIN/AS；user_id；auth_version；issued_at；expires_at；revoked_at N；version。INDEX(user_id,kind)；Redis保存会话内容，权威注销/版本在主库；会话最长8小时 |
| execution_lease | request_id CHAR(36) PK；user_id；datasource_id N（V9，guard后才绑定）；gateway_instance_id VARCHAR(128)；boot_id CHAR(36)；state QUEUED/RUNNING/CANCELLING/TERMINAL；execution_started_at N；heartbeat_at；lease_until；version。INDEX(gateway_instance_id,boot_id,state,lease_until)；START同事务写入，TERMINAL同事务关闭；仅超时不证明死亡。跨副本预算按G4验证，不把本表误称已共享配额 |
| audit_boot_owner | V10；PK(gateway_instance_id VARCHAR(128),boot_id CHAR(36))；state OPEN/RETIRED；create_time；update_time。START/heartbeat/confirm先锁boot；退休先封禁再恢复lease，RETIRED不得重开。所有非终结lease消失后可回收v2本地标记，元库墓碑永久保留；不支持与不查围栏的旧写方混跑 |

Refresh 事务统一先锁sys_security_state单行，再锁sys_user_security，再按family id、token generation顺序锁族与旧token。检查用户/客户端/平台/族状态及到期，再把 ACTIVE 旧 token 置 CONSUMED，插入新 token，更新 idle_expires_at=min(now+7d,absolute_expires_at)、generation、authorization/version，更新epoch/插安全事件并提交；同一事务失败不产生可用响应。遇 CONSUMED 则撤销整族并提交后返回 invalid_grant。JWT 的 sid=family_id（无刷新族授权也创建可撤销 session/family 标识），auth_version 为签发版本。精确协议见 [authentication](authentication.md)。全局单行锁会串行化安全变更/refresh，不覆盖普通查询读取；M0压测不足时只能用新ADR调整为无循环的分层版本方案，不能私自跳过锁或epoch。

用户改密的元库阶段、停用/删除在同一权威事务提高 auth_version，标记全部 family/PAT/session 撤销并写事件；大用户批量数据不以异步扫描作为即时撤销依据：auth_version立即拦截，后续清理才异步。业务角色变化通常不撤销登录，升 policy_epoch/主体版本使下一次请求重算授权。

## 6. 审计、幂等和任务表

| 表 | 字段 | 索引与不变量 |
| --- | --- | --- |
| audit_outbox | event_id CHAR(36)；request_id CHAR(36)；event_kind START/TERMINAL；seq TINYINT；event_payload MEDIUMBLOB（版本化、敏感字段已加密）；payload_hash BINARY(32)；create_time；published_at N；attempts；lease_until N | PK(event_id)；UNIQUE(request_id,event_kind)；INDEX(published_at,create_time,event_id)；START seq=0，TERMINAL seq=1；重复相同payload幂等，不同payload告警隔离 |
| audit_log | request_id CHAR(36)；user_id N；platform_id N；tool VARCHAR(64)；datasource_id N；started_at；completed_at N；result RUNNING/SUCCESS/ERROR/UNKNOWN；verdict ALLOW/DENY/ERROR/UNKNOWN；error_code N；sql_masked MEDIUMTEXT N；sql_fingerprint BINARY(32) N；sql_raw_cipher MEDIUMBLOB N；sql_final_cipher MEDIUMBLOB N；key_id VARCHAR(128) N；raw_length INT N；resource_refs JSON N；versions JSON N；duration_ms BIGINT N；queue_ms INT N；db_ms INT N；rows_returned INT N；response_bytes INT N；truncated BOOL；client_ip VARBINARY(16) N；create_time；terminal_hash BINARY(32) N | PK(request_id) 全局幂等；INDEX(started_at,request_id)、INDEX(user_id,started_at,request_id)、INDEX(platform_id,started_at,request_id)、INDEX(result,started_at,request_id)。一期绝不分区；不存结果行/完整token |
| audit_sensitive_access | id；request_id；operator_id；reason VARCHAR(500)；fields JSON；decision ALLOW/DENY；create_time | PK id；INDEX(request_id,create_time)；读取解密本身也可靠审计，不能混为查询执行记录 |
| api_idempotency | actor_id；route_key VARCHAR(128)；key_hash BINARY(32)；request_hash BINARY(32)；state RUNNING/SUCCEEDED/FAILED/UNKNOWN；resource_id N；response_cipher MEDIUMBLOB N；http_status INT N；create_time；expires_at | PK(actor_id,route_key,key_hash) 原子声明；24小时最小保留，一期管理API自动重试窗口≤24h；PAT创建响应不落完整secret，重放返回元数据+TOKEN_ALREADY_CREATED，需重新创建新intent |
| admin_operation_log | id；actor_id；request_id；action；aggregate_type/id；before_masked JSON N；after_masked JSON N；occurred_at | 只存脱敏差异；INDEX(actor_id,occurred_at)；关键写与领域表同事务，不依赖日志平台成功 |

审计 START 只有元库主库提交确认才允许执行；终结允许元库提交或加密 fsync WAL 确认。消费者事务 upsert audit_log，并标 published_at；不能先确认消息再更新记录。TERMINAL 乱序抵达允许先创建含完整身份的终结记录，后续 START 只能补空字段，不能回退 SUCCESS/ERROR。只有过期执行租约且确认无在途任务的 START 才恢复 UNKNOWN。详见 [audit](audit.md)。

## 7. 一致性事务清单

| 命令 | 单一事务原子内容 | 提交后动作 |
| --- | --- | --- |
| 用户改密/停用/删除 | 组织行+auth_version+会话族/PAT撤销标志+epoch+事件+管理操作记录 | 发布事件、清理Redis；失败补扫 |
| 角色/部门/成员变更 | 组织行、成员表、相关主体version、global epoch、事件 | 清有效权限缓存；部门移动重算父链 |
| ACL/平台范围变更 | grant/scope行、聚合version、global epoch、事件 | 广播失效；不等待广播才返回提交成功 |
| 数据源启停/凭证变化 | config_version/status、resource_version按需、epoch、事件 | 池先DRAIN旧代再建立新代；事务内不连业务库 |
| 快照发布 | 复核配置version，发布快照、更新active_snapshot_id/resource_version，世代变更标旧授权stale、epoch+事件 | 旧快照保留供在途解释；新查询只用新版本 |
| 刷新轮换/重放 | family/token/authorization与版本+事件 | 签发后只在提交成功才响应；重放整族撤销 |
| 审计准入 | START outbox持久化 | 之后才借业务连接 |

主库隔离级别与锁策略在实现时固定；事务不允许跨业务库。安全快照读取使用新建主库短事务的一致快照，权威时间保守取快照起始；不可使用滞后读副本，不能用RR事务二读epoch证明没有其他事务并发提交，执行前复查需新事务/current read。唯一键与CHECK是最后约束，不以应用先查询代替。所有安全写固定锁顺序sys_security_state→sys_user_security（多用户按ID）→平台/客户端/主体聚合（按类型+ID）→family（按ID）→refresh token（按generation/hash）→细表；没有用到的层跳过但不可反向获取。普通审计写不获取安全全局锁。事务只在元库，锁内不发网络请求，迁移前用并发改密/刷新/撤销死锁测试锁定实现。

## 8. 生命周期、归档和迁移验收

配置/主体软删除保留 ID，不级联删除历史审计；关联表以 RESTRICT 或服务领域检查，禁止随用户删除审计。资源 DROP/重建用新 generation/object_id，旧 grant 标 stale/revoked，不靠名字重新授权。无可靠物理身份的引擎，在快照检测到删除/重建/定义不确定时收紧到需管理员重新确认；不能凭同名表无变化证明未重建。

审计在线和归档统一保留180天，raw密文按同周期清除；备份覆盖与销毁遵从运维策略。refresh墓碑保留至族绝对过期且已不可能存在有效JWT（另加10分钟JWT寿命及经配置时钟偏差），不可因日常清理丢失重放依据。未消费outbox不能按时间直接删除；消费后按审计恢复窗口保留，窗口值需和备份恢复流程一起配置。

迁移实行：备份→空库全量→参考身份去重/UTC转换→增量回放→完整性检查→切换→只读核验。验收必须有唯一键竞争、外键/孤儿扫描、回滚、主从延迟不可用、refresh并发、快照发布失败、审计重放幂等及180天边界测试。生产DDL只前向演进；回滚应用前先检查schema兼容，禁止回滚脚本删除已发令牌墓碑或审计数据。
