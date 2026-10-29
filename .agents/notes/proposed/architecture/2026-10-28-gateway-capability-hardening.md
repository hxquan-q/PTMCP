# Agent Note: 问数鉴权网关的实现边界与收尾顺序

Status: proposed

## Problem

用户要求判断当前 PTMCP 是否已实现连接数据与鉴权的问数网关，并对照 universal-db-mcp、dbx 判断优化方向。
本记录提出后续顺序，不改变产品权限模型，不代表方案已采纳、业务已验收或允许生产发布。

调查基准为 `xquan/feat-backend` 的 `795fb347a295bb4b65fe0a15f607eac50eaab9cd`（2026-10-28），
原有 UI 与 T17 工作项修改不属于本轮。参考源码固定为：

- universal-db-mcp：`dc338cd7740c277c04722743b97eb1681e409016`，与昨天研究相同。
- dbx：`ee6fb66aa2bccc6232cb5e3d9d79a56ef13cac1b`。

### 历史检索与归属

- 活跃笔记中，[T19-a 基线取舍](../../implemented/architecture/2026-10-26-t19a-gateway-implementation.md)
  为部分重叠：保留唯一生产接线、拒绝旧 stub 分支的决定，本篇只评估当前能力与增强顺序，互链而不替换。
- [昨天的连接专项](../../../../docs/reference/universal-db-mcp-connection-review-20261027.md)
  和 [T15-c](../../../../tasks/work-items/T15-c.md) 已拥有 S1～S7，不再建立平行连接架构。
  其中 S1～S3 和 S4 目标身份隔离已有后续实现，不能把旧报告的发现全部当作当前缺陷。
- 本轮同时检索待办、相关 ADR、T13/T14/T19 工作项；未发现需要翻转的授权基线。
  概览中的“只有骨架”与旧工作项的历史缺口不能覆盖当前源码；更新概览应作为后续事实同步，不删除历史证据。

### 已实现与证据边界

**结论：核心问数鉴权链已实现，不是框架占位；完整客户端接入和生产运行闭环尚未验收。**

| 能力 | 当前实现事实 | 证据与边界 |
| --- | --- | --- |
| 数据连接与问数 | MySQL、PostgreSQL 接入，三工具为 `list_databases_and_tables`、`get_table_schema`、`execute_readonly_sql` | [McpToolService](../../../../ptmcp-parent/ptmcp-gateway/src/main/java/local/ptmcp/gateway/mcp/McpToolService.java)、[T19-a](../../../../tasks/work-items/T19-a.md)、[T19-b](../../../../tasks/work-items/T19-b.md)；不是自然语言生成 SQL 的模型服务 |
| 请求身份 | PAT/JWT、平台绑定、权威安全状态复核，Tier1 每请求 proof | [PatJwtAuthentication](../../../../ptmcp-parent/ptmcp-gateway/src/main/java/local/ptmcp/gateway/mcp/PatJwtAuthentication.java)、[T19-c](../../../../tasks/work-items/T19-c.md)；真实直连 mTLS/代理部署仍需联调 |
| 对象授权与执行隔离 | 平台 scope 与用户权限交集、Deny 优先；guard 后才激活业务池；出队及执行前复核身份、配置/快照、资源版本和 READ | `McpToolService.revalidateExecution`、[T15-c](../../../../tasks/work-items/T15-c.md)；不是依靠提示词或只读标签授权 |
| 连接安全 | TEST/SYNC/QUERY 共用物理连接策略，TLS 主机名验证、DNS 固定、部署级 CIDR/端口限制、精确池代与排空 | [T15-c S1～S3](../../../../tasks/work-items/T15-c.md)；PG 已补生产 `verify-full` 路径，不能继续报为未接 TLS |
| 审计准入与降级 | START 主库持久准入，terminal 主库失败回退加密且 fsync 的 WAL | [McpGatewayConfiguration](../../../../ptmcp-parent/ptmcp-gateway/src/main/java/local/ptmcp/gateway/mcp/McpGatewayConfiguration.java)、[T09-b](../../../../tasks/work-items/T09-b.md)；不等于已装配恢复调度 |
| OAuth 公开客户端 | code + PKCE、refresh 原子轮换、重放族撤销和 refresh revoke | [T13-b](../../../../tasks/work-items/T13-b.md)；access token 的 revoke 按 sid 仍未实现，不能把接口 200 当作已撤销 |
| 容量保护 | 用户/源/网关/队列有界，当前装配 `InMemoryQuotaLimiter` | [T19-d](../../../../tasks/work-items/T19-d.md)；用户与源配额未跨副本共享，网关额度本身按每副本定义 |
| 接入与发布 | 本地 HTTP/隔离双引擎已有测试，四个真实客户端与部署矩阵未完成 | [T19-e](../../../../tasks/work-items/T19-e.md) 为 blocked；不据框架构建判为可上线 |

[T15-c](../../../../tasks/work-items/T15-c.md) 记录 2026-10-28 全 reactor `clean verify`：
746 tests / 0 failures / 0 errors / 0 skipped，属于历史执行证据，首次评估没有重跑；
2026-10-29复核尝试verify但被Windows→WSL Docker双向TLS故障阻断，不能作为新的通过证据。
其总 Verification 仍为 NOT_RUN，表示完整 S4～S7 未验收，不能据局部 PASS 改为全部完成。

### 需要收尾的具体接线

1. **资源发现端点已实现，OAuth 组合仍有断点。** [McpAuthenticationFilter](../../../../ptmcp-parent/ptmcp-gateway/src/main/java/local/ptmcp/gateway/mcp/McpAuthenticationFilter.java)
   与 [McpResourceMetadata](../../../../ptmcp-parent/ptmcp-gateway/src/main/java/local/ptmcp/gateway/mcp/McpResourceMetadata.java)
   共享受信固定配置，已提供 401 challenge 和 `/.well-known/oauth-protected-resource`，见收尾工作项 G1。
   2026-10-29 复核确认：[Dev-Linux](../../../../scripts/Dev-Linux.sh) 的 canonical MCP URL 与 OAuth audience
   `ptmcp-mcp` 不一致，而 authorize policy 要求 resource 等于 audience，标准客户端按发现 URL 请求会被拒。
   当前修复候选统一 canonical URI，并在 code/refresh 表单、授权持久化及 JWT 签发处绑定资源；
   旧缺失资源的授权需重新授权，不能根据部署配置补写。保留该兼容代价以阻止旧授权扩展到新资源。
   canonical资源与换码/刷新/撤销的真实HTTP/MySQL回归现已通过；真实客户端实际接入仍待版本化证据。
   [API 合同](../../../../docs/backend/api-spec.md) 与当前声明的
   [MCP 2025-06-18 授权规范](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization)
   将 Protected Resource Metadata 列为 MUST，DCR 则为 SHOULD，不应混为同一缺口。
2. **DCR/CIMD 只有原语，不能宣传即插即用。**
   [OAuthSecurityConfiguration](../../../../ptmcp-parent/ptmcp-authz/src/main/java/local/ptmcp/authz/oauth/OAuthSecurityConfiguration.java)
   明确关闭 `/connect/register`，发现文档移除 registration endpoint；
   [T14-a](../../../../tasks/work-items/T14-a.md) 也明确不接 HTTP。
   关闭可以保留，但须提供预注册客户端路径；CIMD 的解析 IP 尚未约束实际 socket，启用前必须补 DNS pinning 或受控出站代理。
   这是启用前置条件，不是声称当前已有暴露的 CIMD SSRF 端点。
3. **WAL 持久化不等于恢复闭环。**
   [WalReplayer](../../../../ptmcp-parent/ptmcp-persistence/src/main/java/local/ptmcp/persistence/audit/wal/WalReplayer.java)
   已补OS目录独占owner、`.open`活动段/`.wal`封段、在线回放共享owner和真正halt的故障测试，
   底层14例通过。网关启动/调度回放及过期START收敛仍在实施。
   本片采用持久目录内fsync的boot标记证明旧owner已退出；取得同一目录OS锁的新进程才能得到退休boot证明。
   START与租约同事务，恢复先回放所有封段，再在同一主库事务锁定退休boot的过期租约及RUNNING行并写UNKNOWN。
   当前boot、其他卷/实例、无归属的历史START、仅租约超时都不能自动判UNKNOWN；不重放SQL。
   损坏或缺钥段保留并告警，阻止UNKNOWN掩盖尚未恢复的终结；恢复失败可重试，不能静默清理证据。
   同JVM争锁必须在打开`.owner`前按realPath登记，否则Linux关闭失败竞争者的第二句柄会释放原生进程锁。
   boot标记回收还需元库单调OPEN→RETIRED围栏：START先锁boot再写审计及lease；退休事务先封禁旧boot，
   再处理过期lease。旧START先赢则退休等待其提交，退休先赢则迟到START拒绝，不能只凭一次空扫描删除证据。
   仅参与该围栏的新格式boot、全部lease终结且封段回放完成后才回收文件；DB墓碑保留，旧格式标记不自动删除。
   落盘为V10 audit_boot_owner及v2标记；退休结果同时返回recoveredCount与collectable，只有事务明确commit后才用后者删除文件。
   提交结果不明时保留标记重试，不能用“本批恢复0条”替代全部lease已终结；普通TERMINAL不反向获取boot锁。
4. **客户端取消尚未贯通。** `McpToolService.handle` 对无 id 通知直接 202，
   执行时创建独立 `ReadOnlyCancellation`，未见请求 owner 到取消句柄的映射。
   执行器支持取消不代表 `notifications/cancelled` 或断连能终止查询。
5. **连接管理完整 S4、密钥 S6 尚未完成，字典 S7 已有局部验证。**
   目标切换撤旧权限已修，候选验证/CAS 发布/同版本健康诊断仍待实施；
   [CredentialCipher](../../../../ptmcp-parent/ptmcp-datasource/src/main/java/local/ptmcp/datasource/connection/CredentialCipher.java)
   仍为单 key、无对象 AAD，WAL key provider/replayer 也只识别当前 key。
   G6/S7 已补三工具与参数说明，并在 schema 返回中提供已授权表/列的业务字典；
   双方言定向验证与独立只读安全复审已通过，人工接受、真实客户端及宽表读取内存仍未验证。

## Proposal

保留现有网关安全内核，先补接入与可恢复性，再改善问数上下文，最后按需求扩数据库。
所有方案均须维持 guard 前零业务连接、平台与用户权限交集、Deny 优先、审计持久准入和版本复核。
首次评估不改业务实现；后续实现由关联工作项逐片记录，本提案不授权生产或发布。

| 顺序与承接 | 最小可验收切片 | 不扩张的边界 |
| --- | --- | --- |
| 1：T19/T13/T14 | 受信固定配置的 Protected Resource Metadata + 预注册 OAuth 客户端真实闭环；检查 401、issuer/resource/audience、PKCE、refresh、过期和撤销语义 | DCR 可继续关闭；先明确目标客户端及版本，不为追新直接升级声明协议 |
| 2：T09/T20、T15-c S6 | 装配有 owner/互斥的 WAL 恢复、过期 START 收敛、磁盘告警与可重复演练；新旧 key 可读，轮换后再退役旧 key | 不让 replayer 扫描正在追加的段；凭据/审计引入 AAD 时版本化，禁止换钥后直接丢弃旧密文 |
| 3：T15-c S4 | 候选配置验证后 CAS 发布；健康结果绑定 configVersion/目标与时间；继续沿用现有目标隔离 | 错凭据不覆盖当前可用配置；不恢复已退役目标的旧授权 |
| 4：T15-c S5、T19-d | 请求主体+平台+执行 owner 关联取消；执行结束或连接淘汰后释放额度；明确单副本上线限制或落实共享用户/源预算 | 每副本网关限额不误改为集群全局常量；不因取消 HTTP handler 就提前释放 DB 资源 |
| 5：T15-c S7 | 先补三工具准确 description/参数说明与一致性测试，再在既有 schema 返回中加入有预算、带来源的业务词义/单位/外键 | 不必先加新工具或 MCP resources；注释是数据，不是指令；关系两端都授权后才能披露 |
| 6：后续需求 | 只有真实客户需要时再扩引擎，每个新增方言补 guard、元数据、只读执行、TLS、取消与隔离测试 | 不以注册 adapter 数量代表数据库支持完成 |

优先 1、2 是接入/运行闭环的交付阻断项；3～5 复用现有任务，不另造平台。
单副本、仅 PAT 的受控试用可缩小验证范围，但须明确标注不是完整 OAuth 客户端或多副本生产验收。

### 权威用户状态与身份写入闭环

身份收尾沿用 authentication 合同：`sys_user_security` 是用户版本权威，OAuth family、PAT 和会话只携带快照。
V11 为已有主体回填高于存量凭证的版本；迁移时停止旧写方，禁止混跑，存量会话与授权需重新登录。
登录在读取宿主凭据前捕获 READY/authVersion，在全局→user→aggregate 锁序下签发时再次比较；
不能把旧密码验证结果包装为改密后的新版本。code、refresh、JWT/PAT复核均依赖同一权威状态。
ADMIN与AS继续独立Cookie/CSRF域，AS会话增加主库索引、绝对8小时及逐请求版本复核。

宿主身份库可独立部署，不能把元库事务和宿主写入描述为原子。改密先在元库置 CHANGE_PENDING、
提升authVersion并撤销全部域，明确提交后才写宿主；仅对应operation ID可在确认宿主成功后CAS恢复READY。
失败或提交结果不明保持拒绝登录/换码/刷新；重试不能用新操作覆盖未收敛操作。
宿主并发围栏采用 expected-hash CAS，旧摘要和独立目标 BCrypt 随 operation 不可变持久化；
更新为零时同事务 current read 核对目标 hash 与 active。相较于 GET_LOCK，其正确性不依赖跨库阶段的连接存活；
仅元库 READY CAS 不能保护宿主，仍拒绝。具体状态、迟到写证明和迁移边界见
[ADR-0011](../../../../docs/architecture/adr/0011-跨库身份变更与权威版本.md)，真实双 MySQL 故障测试正在实施。
共享主库限流作为Redis不可用时的持久依据，避免本地Map被多副本绕过；账号与IP均计数，成功只清账号。
本段为当前实施方向，尚未作为完整身份闭环通过。

### 组合编辑的权限边界

用户完整 PUT 组合了资料、启停、角色与部门修改，不能只检查一个目的部门的 move 权限。
资料编辑要求 user:update；实际改变状态、角色或部门时，追加相应专用动作，
并由同一个有效委派覆盖全部动作与新旧部门，不能跨委派拼接。操作者自身有效角色功能码另行相交。
角色整组替换必须按该候选委派自己的 allowedActions 检查最终全部角色功能码上限，不能取所有委派的并集。
与当前集合相同的 roleIds 仅表示回传原值，
不因完整 DTO 额外要求赋角权限；缺省保留原集合。授权分类读取的 profile.version 必须等于
请求 If-Match，不等立即冲突；落库仍保留相同版本 CAS。仅靠落库 CAS 不足以绑定授权快照：
预填未来版本号可能在并发停用后匹配成功，不能把旧状态的无变化判断用于新状态。
不自动重取或重试到新版本，冲突必须由调用方重新读取并提交。

统一对所有 PUT 强制要求四种动作最容易实现，但会让只有资料编辑权限的合法管理员无法保存完整 DTO，
因此按实际变化组合检查。此检查不替代事务内对角色定义、委派撤销和最后有效管理员的并发保护，
平台管理员逐动作码由下节统一入口补齐；间接业务权限自扩张仍须后续真实边界测试和独立复审。
专用接口按下述规则续接；启停撤证由下述独立切片验证。

专用status/roles/department沿用同一命令级判定：始终要求对应专用动作，
actor当前功能码与单一有效委派相交，迁移由这一份委派同时覆盖新旧部门；
赋角的最终整组功能码必须落在该候选委派自身的动作上限内，即使其他委派覆盖同部门也不能合并。
三端点使用同一次UserRow读取绑定If-Match并生成写入参数，不为授权和落库分别追新快照；
status保留姓名、部门、角色、联系方式与备注，专用动作不附加user:update。
直接复用通用PUT的四动作组合检查最省新增代码，但会对单独启停或赋角多索取资料编辑权限；
因此共用候选委派/角色上限算法，由各入口明确命令所需动作。此细化不覆盖事务内actor重验；
平台管理员的端点动作由下节统一入口与命令动态动作共同约束。
专用接口已有真实MySQL/Servlet边界RED及124项组合GREEN，含权限拒绝、未来版本并发与资料保留；
追加整组多角色回归纳入Admin55项GREEN，独立只读终审无新增阻断。两轮分别记录，
不累计重复执行；实际日志和限制见[收尾工作项](../../../../tasks/work-items/DEV-20261028-gateway-completion.md)。

委派有效期以 UTC `DATETIME(6)` 保存，直接绑定 `LocalDateTime`，不能先经
`Timestamp.valueOf` 按 JVM 本地时区解释。INSERT 与重叠查询必须使用同一规则；
否则台北环境可提前授权，夏令时还可能归一化有效窗口。读取仍显式转换 UTC。
不统一平移旧委派：历史 JVM 时区、连接配置或夏令时可能混用，需按来源核对或撤销重建。

### 用户启停与撤证事务

ACTIVE与DISABLED实际切换是安全变更，通用PUT与专用status均在OrgStore统一执行。
先以profile版本绑定本次命令，捕获权威身份版本；安全事务按global→user→aggregate锁序复核，
同事务更新profile/roles、authVersion/identityVersion、撤销ADMIN/AS/PAT/OAuth、提升epoch、写事件和操作日志。
启用也撤销旧凭证，不能使停用前令牌重新有效；UNBOUND保持UNBOUND，不借启停完成供应。
CHANGE_PENDING拒绝且无副作用，普通资料或相同状态不额外撤证或提升epoch。
提交回包丢失返回OPERATION_UNKNOWN，不自动重放；可重读状态与版本后由调用者处理。

将撤证放在controller提交后回调最少改动，但失败窗口会留下已停用或重新启用却未撤证的状态，
不能满足同事务约束。所有资料编辑一律撤证则实现简单，却会使无安全变化的姓名/联系方式保存踢出登录；
因此按真实状态变化选择既有SecurityChangeTransaction，并在锁内再次绑定原profile与authority，
普通路径保留同版本CAS，不追新版本。该片不替代最后有效管理员与事务内操作者授权重验。

### ADMIN平台管理员资格逐请求核验

登录时的platformAdmin不能作为八小时内的授权真源。每个请求在ADMIN索引与权威用户版本核验后，
从主库的当前成员关系、有效角色与功能码重新判断ptmcp:platform:scope，仅在本请求缓存结果。
资格解析不回写共享会话内容，不更改session ID、CSRF、issuedAt或expiresAt，不因读取权限续期。
既有显式CSRF轮换仍更新会话中的CSRF，并可顺带保存本请求资格；下一请求仍必须重新查询主库。
会话端点返回的platformAdmin与其本次permissions一致，不能出现角色已撤但仍显示平台管理员。
权限依赖查询失败必须在过滤链默认拒绝并使用既有稳定错误映射，不把异常当作匿名或沿用旧管理员标记。

登录时缓存能减少主库读取，但角色成员、停用/软删除和功能码删除都不必改变authVersion，
因此只靠会话索引有效不足以撤销旧管理资格。把每次读取结果写回共享会话能复用现有字段，
却会引入与并发CSRF轮换相互覆盖的风险；采用请求内结果，每请求增加短主库读取。
该片只约束下一请求的身份附着，不代替安全写入提交前的操作者/委派重验或最后有效管理员保护。

### 管理接口逐动作功能码

所有实际`/api/v1/admin/**`控制器动作显式声明所需功能码，统一MVC入口先核当前身份与主库功能码，
再进入既有管理范围门禁。平台管理员标记只表达管理范围，不能跳过list/read/create/update等动作码。
没有动作声明的管理handler默认拒绝，不依HTTP方法或URL拼出隐含CRUD通配规则；
用户完整PUT仍由命令门禁按实际变化补充status/assign-role/move，平台管理员同样适用。
读取身份状态复用user:read，操作日志复用audit:list，原文访问轨迹复用audit:read；
原文解密继续另核audit:decrypt及既有范围/审计要求，不能因平台管理员身份自动获得解密权。
接口URL、DTO、Cookie/CSRF与ETag不变；缺少权限时403，依赖查询失败使用既有稳定503映射。

在每个controller手写权限调用最直观，但容易遗漏新增端点；按路径猜动作集中实现简单，
却不能准确表达test/sync/dictionary及身份恢复。显式方法声明加统一默认拒绝保留可审查的动作映射，
代价是新增端点须同时登记动作并补接线验证。该片只保证请求入口当前功能码与动态组合动作，
不替代提交前授权重验、最后管理员及间接自扩权保护。先建立真实MySQL/Servlet反例再实施。

实现覆盖58个实际handler，测试以独立HTTP合同逐项核对实际路由与声明，而非从注解生成预期。
统一入口另验证未声明测试handler零副作用、当前成员/角色/功能码撤销、幂等重放前撤码、解密动作隔离，
真实Cookie回归同时保持CSRF与个人服务入口。平台管理员测试actor使用独立高ID和主库角色，
避免与OrgStore新建用户的ID/角色集合碰撞；无功能码反例显式移除其动作，不能靠管理员豁免维持旧测试。
本片有效RED18项、定向GREEN25项另行留证；完整组合为189项、0失败/错误/跳过，
独立只读终审读取实际日志并核对源码后未发现新增阻断。已关闭请求入口的逐动作码旁路，
提交前重验、最后管理员及间接自扩权仍是独立缺口，不随本片完成而宣称全后端通过。

### 部门完整编辑的事务边界

部门PUT同时提交父部门、名称和启停状态，移动和资料编辑必须由OrgStore的一次事务完成，
用同一个If-Match约束目标，成功只提升一次目标版本；移动所重建的后代缓存和操作日志一并提交。
事务返回本次写入的不可变结果，不在提交后另读可能已被并发改动的记录。
内部单独改名与移动入口复用同一写入路径，避免完整PUT再组合两个已提交命令。
MOVE操作日志保留仅descendantsMoved的原结构，不把历史名称重新拼入JSON；
否则含引号或反斜杠的历史名称会把编辑日志的既有限制传播为移动失败。
此兼容反例已经真实MySQL复现并修复；最终Org24+Admin84共108项组合通过，
详细RED/GREEN与独立复核结果见收尾工作项，不累计重复执行次数。

仅在controller提前检查名称能挡住最常见的半提交，却不能抵御第二阶段SQL或日志故障；
因此以一次存储命令为事务边界，并用真实MySQL日志故障验证子树、版本和操作轨迹全部回滚。
这一片只修复完整编辑的提交原子性，不把既有ancestors范围解析、提交前授权重验、
间接自扩权及部门安全epoch/event缺口记为完成；这些仍按授权合同独立补齐。

### 部门完整编辑的新旧范围与版本绑定

部门PUT必须由同一条有效委派包含department:update且覆盖旧目标和移动后的新父部门，
不能先验证旧范围、再找另一委派覆盖新范围。未移动时不额外要求当前父部门在委派内，
以保留委派根自身的资料编辑和重新启用；实际提升为根仅允许平台管理员。
入口精确功能码仍独立检查；目标快照版本先与If-Match相等，再判断是否移动，落库CAS继续保留。
仅靠落库CAS无法拒绝请求提前填写下一版本、等待并发迁移后再写入；快照绑定负责关掉这条路径。

分别检查两个范围最容易复用既有门禁，但允许拼接互不相交的委派；直接要求新父始终可管理
会使委派根改名也被拒绝，因此按实际父边变化追加范围。既有旧目标门禁保留在目标快照读取前，
避免无管理范围者从新增读取路径取得404/409信息。这增加一次范围读取，完整命令事务重验时可统一。
本片不证明整子树写集安全：权威后代展开见下一节，提交前授权重验
分别继续实施。先以真实MySQL/Servlet建立越界、委派拼接、零副作用和未来If-Match反例再修改实现。
有效RED为8项中5个failure：四条权限拒绝实际200，未来版本实际200，未以等待超时替代断言。
当前实现已通过Org69、Admin98、网关12共179项组合，0失败/错误/跳过，BUILD SUCCESS/exit0；
另含平台管理员提升为根正例。独立只读终审实际核读源码、RED/GREEN、退出码及三文件SHA-256
一致性，无新增阻断；局部关闭两个入口缺口，详细证据见网关收尾工作项，片外边界保持不变。

### 部门权威写树与安全事务

部门create/edit/move/delete及旧update/move包装统一使用SecurityChangeTransaction：先锁全局安全行，
再按ID读取并锁定部门行；树内父边、资料、受影响版本、操作日志、policy_epoch及DEPARTMENT_CHANGED
事件同一元库事务提交。创建事件必须使用数据库实际生成的部门ID和version=1，不能预估ID或提交后补事件。
事务执行器增加返回类型化Outcome(value,event)的内部可信回调，既有execute(event,Change)包装复用
相同提交/清理路径；只有明确提交成功才返回Committed(value,receipt)，丢失提交确认仍OPERATION_UNKNOWN，
该事务单次调用不自动重放。HTTP的IdempotentWrites仍对全部RuntimeException释放请求键，
因此同键重试可能重复创建；store级提交丢确认测试不能外推HTTP幂等闭环，保留UNKNOWN及真实重试另续一片。
回调不运行网络、DDL或独立提交，沿用既有固定锁序。

锁内从parent_id构造部门快照，缓存ancestors不决定防环、上行链或后代写集。只校验命令涉及的路径/移动子树，
不因无关分支损坏冻结整个组织。真实后代展开包含停用和软删中间桥，移动时也重建软删后代的缓存并提升其版本，
descendantsMoved按实际重建行数计数。伪造包含目标ID的外部分支不得被更新。现有路径损坏（环、缺父、负父、
超过64节点）报SECURITY_STATE_UNAVAILABLE；新命令造成环、深度超过64或缓存长度超过现有VARCHAR(500)
则INVALID_ARGUMENT并零提交，不截断路径。实际移动先完整校验全部旧后代的链和可递增版本，
再规划目的地及新缓存；逐行交错检查会先遇拟议超深而掩盖更深处的既有损坏，故采用两个阶段。
原地资料更新允许保留已有软删父边，实际移入的新父必须未软删。本片选择显式拒绝超长缓存以保留当前schema，不暗中扩容。

全局锁已经覆盖安全写契约，复用它可避免另设树锁及锁序；代价是管理树写串行，当前实现按ID读取全部部门
以得到稳定权威拓扑，成本随部门数增加。权限问数读取仍不依赖缓存，未来大规模组织需测量并优化锁时长，
不能用本片少量夹具声称容量达标。只换递归查询最少改动，却不能封闭跨祖先并发移动；
另加一套手工epoch/event事务会重复提交不明处理，因此复用现有执行器并支持创建后生成事件。
本片不把锁内树校验等同于actor/角色/委派重验、整子树委派或间接自扩权；这些继续独立实施。
部门删除保留既有直接子部门/直属用户引用检查，尚未与用户创建/换部门共用写入围栏，
不证明并发成员变更和跨软删桥的引用完整性。真实MySQL缓存污染、防环、深度/大ID、
各树命令epoch/event回滚、全局锁顺序及双向并发移动的结果见既有网关收尾工作项；
初始33项RED与后续扩展测试分开记录，不声称扩展后的全部用例均先执行过RED。
扩展组合以`department-tree-green3.log/.exit`于2026-10-30 06:59:31 +08:00完成：
真实MySQL/Servlet及工具权限拒绝回归共302项，零失败/错误/跳过，7份格式化源码回拷后哈希与测试目录一致。
独立只读终审重算三处哈希并核读实际日志，无新增阻断，未重复运行动态测试。
该证据只关闭权威写树和安全事务切片，整体提案及上述授权、HTTP幂等边界继续保留。

### HTTP写请求的UNKNOWN幂等保护

数据库已提交但回包丢失时，错误不是失败回滚的证明。现有wrapper对任意RuntimeException删除RUNNING，
recorder又将UNKNOWN或过期RUNNING重新占用，能让同一创建意图再执行。成功后的序列化/响应记录故障也有同类风险。
本片承接API §2既有“不重做未知结局”合同，保留请求摘要与If-Match意图绑定、成功重放前权限校验。

计划在action分派后对全部运行时失败保留占位，尽力RUNNING→UNKNOWN；领域拒绝首响继续使用原有码，
未分类异常和已返回成功后的响应处理异常用OPERATION_UNKNOWN，不向客户端暴露异常文本。
不能依据INVALID_ARGUMENT/VERSION_CONFLICT等码自动释放：跨库改密可先提交PENDING/撤证再在宿主写入时拒绝。
保留completeFailed供已证明无副作用/完整回滚的显式调用者，纯MVC/鉴权/JSON前置拒绝不占键。
代价是部分action内的确定拒绝也保留UNKNOWN，牺牲同键重试便利；若要恢复，需把校验前移或传递明确无副作用证据，
不能补一个错误码白名单。整体事务与响应同库提交能消除此间隙，但现有supplier含跨库、网络和任务提交，
本片采用保守占位，避免把这些副作用包入元库长事务。

occupy先核payload hash，异payload仍422；UNKNOWN和未知状态不删除，过期RUNNING返回UNKNOWN但保留RUNNING，
使原执行者还能最终完成。completeUnknown只改RUNNING，不覆盖已经提交的SUCCEEDED；标记写失败仍保留RUNNING，
过期也不能重做。24小时仅为最小保留，不证明失败；未来墓碑清理、人工解除或重占必须另设计owner代次和迟到完成防护。
FAILED的显式安全重试和SUCCEEDED重放保留，不扩schema、不改OAuth/MCP一次消费，也不扩大普通PUT/DELETE适用面；
现有共享wrapper的身份改密PUT仍纳入兼容验证。一次性secret策略独立待补，不在本片伪称完成。
验证使用隔离MySQL与Servlet真实提交/故障代理、序列化失败、完成记录确认丢失、过期在途及同键并发，
并核对领域拒绝兼容、异摘要422和权限重验；当前为待实施方案，结果续记原工作项。

### 业务授权的部门主体读取

网关与管理端权限预览共用部门主体读取器，以活动用户档案为单语句快照的锚点，
沿parent_id权威边读取完整链，不解析ancestors缓存。每个节点仅在自身status=0、del_flag=0时
贡献DEPT主体；停用或软删只排除该节点，仍检查其上有效祖先，不能把上级DENY一并截断。
无效直属部门产生null deptId，USER与有效ROLE授权仍参与，预览比较不得自动拆箱null。

链最多含64个实际节点（包括直属部门），只有parent_id=0表示正常根。恰好64节点且到根可用；
自环、多节点环、非零缺父、缺直属部门或需要第65节点时拒绝SECURITY_STATE_UNAVAILABLE，
不能返回截断的主体集合。单条有界递归查询使用5秒statement timeout，避免逐跳读取拼接新旧父边。
继承关闭只贡献有效直属DEPT，但仍校验完整权威链：这是对旧路径的可用性收紧，
使损坏结构不因开关而被掩盖。两读取器使用同一个显式布尔语义；当前生产装配仍默认开启，
本片不增加部署开关或声称完成继承开关变化的policy_epoch闭环。

保留缓存读取最省查询，但无法证明祖先身份与状态；逐跳查询容易跨语句拼接并发迁移的父边。
一次有界查询增加最多64节点的固定读取成本，换取同一语句内完整可校验的链。
这一片不修委派范围、部门写树防环、提交前actor重验或部门epoch/event；整体策略快照一致性
也不能仅由部门链的单语句读取证明。先以真实MySQL授权/预览及网关决策反例获得RED再实施。
当前实现位于persistence/org/DepartmentSubjectReader，AuthorizationReader与AclDirectory共用；
原网关逐跳读取和预览ancestors解析已移除，AclDirectory(dataSource, false)统一关闭三个入口的继承。
有效RED分别为网关12例11失败、持久层21例20失败；组合GREEN为Org50、Admin84、网关12共146项，
全部无失败/错误/跳过。独立只读终审核阅实际代码、RED/GREEN和构建文件一致性，无新增阻断。
本总提案仍保持proposed，不以这一个切片表示全部网关缺口落地。

### 管理委派的部门范围读取

委派的部门/主体目标也从parent_id权威链判定，不能因为ancestors包含委派根就放行，
也不能以target等于根的快捷分支跳过存在性和拓扑验证。目标到parent=0的完整链最多64节点，
缺父、负父、环或需要第65节点时拒绝SECURITY_STATE_UNAVAILABLE；即便先命中根也验证整链。
不存在或软删目标不覆盖，匹配的根须实际存在且未软删；SQL不可用维持DEPENDENCY_UNAVAILABLE。
已存在的软删目标或根仍先校验完整链，不能以软删短路掩盖上方坏链；结构有效后才按状态拒绝覆盖。

管理范围允许覆盖停用但未删除的部门，以便授权管理员重新启用，不能用业务主体的status过滤代替管理权。
停用/软删中间节点仍沿权威父边遍历；节点状态不创造或删除父子关系。
单条有界查询避免逐跳拼接新旧父边，拒绝只修缓存字符串格式，因为合法数字仍可伪造范围。
这一片不证明多个目标同一快照/同一委派覆盖、整子树管理、提交前授权重验或树写入安全，
不把委派读取修复升级为全部管理命令完成。先以真实MySQL及Servlet越界零写入反例获得RED。
实际RED首轮Org16项10失败、Admin5项5失败，BUILD FAILURE但-fn使exit0；补充RED3项全部失败、exit1。
实现后组合GREEN为Org69、Admin89、网关12共170项，全部无失败/错误/跳过，BUILD SUCCESS/exit0；
独立只读终审已核读源码、实际RED/GREEN及退出码并复核构建文件SHA-256一致，无新增阻断。

### 参考机制及迁移边界

| 参考 | 可吸收的机制与源码 | 为什么不能整包替代 |
| --- | --- | --- |
| dbx：工具契约 | [能力目录](https://github.com/t8y2/dbx/blob/ee6fb66aa2bccc6232cb5e3d9d79a56ef13cac1b/crates/dbx-core/src/ai/mcp_catalog.rs#L1)、[annotations/调用授权](https://github.com/t8y2/dbx/blob/ee6fb66aa2bccc6232cb5e3d9d79a56ef13cac1b/crates/dbx-mcp/src/server.rs#L2354)、[过滤发现列表](https://github.com/t8y2/dbx/blob/ee6fb66aa2bccc6232cb5e3d9d79a56ef13cac1b/crates/dbx-mcp/src/server.rs#L5937)；先用于三工具描述与契约测试 | annotations/隐藏工具不是执行授权；不必引入其完整工具注册框架 |
| dbx：紧凑上下文 | [schema context 默认 8 表、上限 20 表](https://github.com/t8y2/dbx/blob/ee6fb66aa2bccc6232cb5e3d9d79a56ef13cac1b/crates/dbx-mcp/src/server.rs#L4006)、[注释预算与不可信包装](https://github.com/t8y2/dbx/blob/ee6fb66aa2bccc6232cb5e3d9d79a56ef13cac1b/crates/dbx-mcp/src/server.rs#L2427)、[resource 复用工具](https://github.com/t8y2/dbx/blob/ee6fb66aa2bccc6232cb5e3d9d79a56ef13cac1b/crates/dbx-mcp/src/server.rs#L5845) | 数字仅作参考，不能替代本项目响应预算；MCP resources 不是 OAuth Protected Resource Metadata |
| dbx：请求生命周期 | [cancellation token 接线](https://github.com/t8y2/dbx/blob/ee6fb66aa2bccc6232cb5e3d9d79a56ef13cac1b/crates/dbx-mcp/src/server.rs#L2624)、[future drop 中断历史](https://github.com/t8y2/dbx/blob/ee6fb66aa2bccc6232cb5e3d9d79a56ef13cac1b/crates/dbx-mcp/src/server.rs#L796) | 不足以证明 DB 已终止/连接安全回收；仍须验证本项目执行器 |
| dbx：入口防护 | [所审 HTTP 层的 Origin/Host/Bearer 检查](https://github.com/t8y2/dbx/blob/ee6fb66aa2bccc6232cb5e3d9d79a56ef13cac1b/crates/dbx-mcp/src/http_auth.rs#L147) 可作为部署矩阵参考 | 所审层为共享 token，不等于员工身份网关；[分层策略存在覆盖语义](https://github.com/t8y2/dbx/blob/ee6fb66aa2bccc6232cb5e3d9d79a56ef13cac1b/crates/dbx-core/src/ai/mcp_policy.rs#L126)，不能照搬为权限交集 |
| universal：连接与关系呈现 | 继续承接昨天的连接专项；[关系来源与置信度](https://github.com/Anarkh-Lee/universal-db-mcp/blob/dc338cd7740c277c04722743b97eb1681e409016/src/utils/schema-enhancer.ts#L46) 可辅助问数 | 推断关系不是外键事实或授权依据；[首关键词安全检查](https://github.com/Anarkh-Lee/universal-db-mcp/blob/dc338cd7740c277c04722743b97eb1681e409016/src/utils/safety.ts#L54) 不能替代 AST guard 和未知语法拒绝 |

不迁入参考产品的写 SQL、事务、插件或连接管理工具到模型执行面；不把数据库凭据交给模型，
不自动重试业务 SQL，不以共享 token 替换员工身份，也不以子级规则覆盖上级权限。

## Alternatives considered

G6 的实施细化见[字典上下文工作项](../../../../tasks/work-items/DEV-20261030-schema-context.md)：
在既有 schema 返回中分别提供物理注释与 businessDictionary；每个文本字段最多 2048 UTF-8 字节，
整次 schema 的注释与字典文本共享 32768 字节预算，保留截断标记，列名/类型/键不截断。
字典仅在整批 READ 授权后读取，绑定同一 published snapshot/config/resource version；
只匹配快照内现存列。业务字典可存单位和业务词义，不从说明文字推导可执行指令。
读取后重新加载权限，返回前复核身份、发布版本与安全状态，再按当前时间评估请求表及已披露外键目标。
这会增加短元库读取，但可拒绝复核观察到的在途撤权、授权到期及版本变化；不承诺网络发送瞬间的零时延同步。
相较于把字典直接并入全库快照（一次读取、接线简单），按已授权对象读取多一次短元库事务，
但不把未授权字典搬入返回构建路径；相较于新增上下文工具，复用 schema 保留三工具协议和既有审计边界。
本篇其余 G1～G7 仍按各自证据推进，不随 G6 转为整体 implemented。

G6 的实际 RED/GREEN、实现范围和复审结论见上述工作项：最终隔离 `clean verify` 为
37 tests / 0 failures / 0 errors / 0 skipped，三工具合同与真实 tools/list 完整比较。
文本预算只约束输出；宽表字典仍在限幅前物化。若大规模列/字典成为交付场景，需先补总读取预算或流式读取
及内存边界测试，不能用现有 32KiB 输出上限证明聚合内存有界。

1. **整体采用参考仓的 MCP/驱动层**：能快速获得丰富驱动、工具和上下文体验；但其主体、策略和执行面
   不等价于 PTMCP，替换需要重新证明 guard、对象绑定和审计准入。保留当前内核，只吸收具体机制。
   若未来是独立单用户数据库工作台，可重新评估，不能悄悄改变网关定位。
2. **维持现状，仅凭 746 项通过宣布完成**：投入最小且核心链路已有测试；但测试历史不能证明
   发现端点、恢复调度或真实客户端已经接通。可用于范围明确的受控试用，不作为完整验收依据。
3. **先扩数据库或先开放 DCR/CIMD**：演示覆盖面和接入便利性更强；但未完成入口/恢复闭环会
   扩大待验证面，CIMD 还有实际连接 pinning 前置条件。先预注册闭环，确有客户端需求后再开放。

## Acceptance criteria

以下定义各切片的验收条件；实际已执行项与未验证项以关联工作项为准，不能把条件本身当作通过证据：

1. 真实目标客户端从 401 challenge 取到可用资源元数据及授权服务器元数据，完成 PKCE/refresh；
   错 audience/resource、过期、撤销和冒用平台均拒绝。保留 DCR 关闭时给出明确预注册路径。
2. 主审计库不可用时 terminal 落 WAL；kill/restart 后幂等入库、旧钥段可读、损坏隔离可告警、
   超租约 START 收敛；回放不与 active writer 竞争，不在未知执行结果时伪造成功或失败。
3. 错候选凭据不破坏 active；并发发布有 CAS；旧配置健康结果不覆盖新版本；切换目标后旧授权不复活。
4. 取消排队/执行中查询、迟到取消、重复 RPC id、跨用户同 id、断连均测试；
   确认 DB 结束或连接被淘汰后再归还额度，多副本用户/源预算符合选定部署契约。
5. 字典、外键和紧凑上下文只返回当前授权对象；撤权后不可见；超长注释受预算约束；
   “忽略规则”类注释只作为数据；工具 description、schema、路由与实际支持保持一致。
6. 按既有工作项完成自审/独立评审、适用 Maven/pnpm/故障测试与人工接受，分别报告；
   同步概览事实，但保留历史结果和未验证项，不降低原门禁。

首次评估的实际执行、检查结果与未验证项见 [评估工作项](../../../../tasks/work-items/DEV-20261028-gateway-capability-review.md)。
2026-10-29 的源码与运行复核见 [能力核查](../../../../docs/evidence/mcp-capability-audit-20261029.md)：
补充账号供应、AS 会话撤销/登录保护、access revoke、Origin/协议版本和前端真实接线缺口；
这些是原接入/安全闭环的细化，不翻转既有授权基线。
用户在评估后要求立项实施，后续执行承接
[网关收尾项目](../../../../tasks/work-items/DEV-20261028-gateway-completion.md)；
本篇在所有适用切片完成前保持 proposed，不能以实施授权替代实现和验收证据。

## Risks

- 收益是保留已验证的安全边界，优先补真实接入、可恢复性和模型选表能力；代价是先完成少量
  不显眼的运行接线，而不是迅速增加数据库数量与工具数量。
- 缺少真实客户端版本、受信域名/证书与部署目标时，互操作仍只能记未验证，不能靠参考仓测试替代。
- 密钥/AAD 与候选配置格式变更须明确迁移、旧钥保留及失败回退；T15-c 的 V8 已有停机升级边界，
  本提案不授权混跑旧代码、回退旧模型或恢复退役资源的权限。
- 源码核查不是漏洞复现或完整安全审计；生产可能存在仓库外配置，应以可复现证据补齐，不能假定存在。
- 首次评估仅做源码研究与文档自审，没有运行参考仓代码、Maven/pnpm、真实客户端或生产演练。
  2026-10-29复核的Maven/pnpm实际通过与失败分别记录在能力报告，不改变本篇proposed状态。
