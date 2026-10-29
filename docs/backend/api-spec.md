# REST、OAuth 与 MCP 接口契约

版本：1.0-design · 2026-10-23 · 状态：待实现。下列路径、DTO 和权限是开发目标，仓库参考源码不代表这些端点已存在。跨协议不混用响应封装。安全规则见 [authentication](authentication.md)、[authorization](authorization.md)。

## 1. 通用约定

REST 前缀 /api/v1，JSON UTF-8，新增字段 camelCase；ID 一律字符串，时间 RFC3339 UTC。请求未声明字段拒绝，禁止把请求 JSON 直接绑定数据库实体。不可识别枚举/非法 UTF-8/重复 JSON key返回400；请求体超限413；业务约束返回422。普通管理请求体上限1MiB，密码与密钥不写访问日志；大范围授权变更一次最多100条，禁止无界批量。

REST 采用资源路径和 HTTP 方法语义：集合用复数小写名词、路径复合词 kebab-case；GET/HEAD 不改状态，POST 创建/显式领域动作，PUT 幂等完整更新，PATCH 显式部分更新，DELETE 删除/撤销。复杂动作按从属资源/任务建模，不新增 getXxx/updateXxx RPC 路径；OAuth/MCP 固定协议路径例外。

REST 统一使用 RuoYi-Vue-Plus `org.dromara.common.core.domain.R<T>` 的 `code/msg/data` 三字段，不自建第二种外壳。成功 HTTP200（创建201、异步202），正文 code 仍为上游 HttpStatus.SUCCESS=200，形状：

~~~json
{"code":200,"msg":"操作成功","data":{"id":"101","version":"1"}}
~~~

错误：

~~~json
{"code":100006,"msg":"配置已被更新，请刷新后重试","data":{"requestId":"4ad5cda9-055f-4f88-a9d5-f926c6d9be47","errorCode":"VERSION_CONFLICT","retryable":false,"details":{"currentVersion":"8"}}}
~~~

requestId 由服务端每次实际调用生成，不接受外部覆盖；REST 每次在 X-Request-Id 响应头返回，错误另放入 data.requestId，不给 R<T> 添加顶层字段。traceparent 单独按可信代理约束处理。详情不得泄漏未授权对象名、连接串、SQL字面量或栈。无权访问的个人资源统一404。所有REST列表返回 data={items:[],nextCursor:null}，pageSize默认20最大100；cursor是带签名的不透明游标，包含筛选哈希、排序最后键、主体/权限版本和有效期。固定按 (createdAt DESC,id DESC)；审计按(startedAt DESC,requestId DESC)，元数据按规范名+id。游标过期/权限变化返回409 CURSOR_STALE，不默默跨权限继续。默认不返回total；管理概览需独立经授权聚合，不用列表全量计数。

管理写与个人写均要求有效 ADMIN Cookie、Origin/Referer同源约束、X-CSRF-Token。登录前先获取CSRF bootstrap。修改/删除版本化资源携带 If-Match: "version"；漏传400 PRECONDITION_REQUIRED，版本不符409。GET 返回 ETag，敏感响应 Cache-Control:no-store。状态动作返回最新DTO/version，删除返回200 data={deleted:true,id,version}；逻辑删除可重复，已删除资源仍鉴权后返回deleted:true。

POST 创建/启动任务/撤销/解密动作使用 Idempotency-Key（客户端随机UUID，同一意图重试复用），24小时窗口，服务端原子记录(actor,route,keyHash,payloadHash)。相同key/不同body为422 IDEMPOTENCY_KEY_REUSED；处理中重复409 OPERATION_IN_PROGRESS；已成功返回原资源结果并标 Idempotent-Replay:true；未知结局409 OPERATION_UNKNOWN，不重做。外部副作用“连接测试/同步”用job状态消除重试歧义。PAT/客户端secret只展示一次，重复key仅返回resource元数据和TOKEN_ALREADY_CREATED，不重放secret；请求丢失需撤销旧资源后重新创建新意图。普通PUT/DELETE本身幂等仍需If-Match；OAuth code/refresh严格一次消费，完全不采用此REST幂等机制；MCP SQL无自动幂等重放保证。

## 2. 权限码与管理范围

上述 R<T> 仅用于管理/个人 REST（含会话端点）。HTTP 状态保留 401/403/404/409/422/429/503 等真实语义，不能全部改成 200；正文 code 是业务码，不冒充 HTTP 状态。异常过滤器和控制器使用同一映射；代理非 JSON 错误由前端通用错误路径处理。MCP JSON-RPC、OAuth token/发现/错误均不包 R<T>。

只在 admin 的若依 Web 适配层引用实际 R<T>；common/guard/领域错误使用自己的稳定值类型，不反向依赖若依/Sa-Token。现有游标分页继续返回 `R<CursorPage<T>>`，不因复用 R 改成 TableDataInfo 或强制 total。设计取舍见[ADR0010](../architecture/adr/0010-REST响应复用R与模块错误码.md)。

功能码采用 ptmcp:<resource>:<action>，每端点在下表声明；复用若依菜单机制只是功能入口校验。平台管理员也须显式业务表READ才能查询数据。

当前58个管理handler以方法级`@AdminAction`显式登记功能码，由统一MVC拦截器在body绑定、控制器调用与幂等重放前
从主库当前成员关系、启用且未删除角色读取精确码。未声明或缺码均403 MANAGEMENT_SCOPE_DENIED；
平台管理员资格仅表达管理范围，不能替代端点动作，不支持通配符或相邻CRUD动作代替。
动作权限依赖不可用时返回503 DEPENDENCY_UNAVAILABLE；若在Cookie身份附着阶段失败，则为上述会话资格的503映射。
本入口检查不代替提交前事务内授权重验。当前审计控制器仍额外要求平台管理员范围，角色无关的解密资源范围尚待实现。

| resource | action |
| --- | --- |
| datasource | list、read、create、update、delete、test、sync |
| metadata | list、read、dictionary |
| user / department / role | list、read、create、update、delete；user另有status、reset-password、assign-role、move |
| platform | list、read、create、update、delete、scope |
| client | list、read、create、update、delete |
| acl | list、read、create、update、delete、preview、effective |
| delegation | list、create、delete |
| audit | list、read、decrypt |
| job | read |

所有 /admin 路径先验证登录+功能码，再验证服务端管理范围。组织授权管理员必须有有效 admin_delegation（部门根子树 ∩ 资源范围 ∩ allowedActions ∩ 有效期）；对目标主体和目标资源同时检查。不能向自己扩大委派、不能管理跨范围共享角色、不能授予超出委派的动作/有效期；任何本人/本人所属角色部门/成员关系/删除命中DENY等变更须比较前后本人有效META/READ，产生本人增权一律403 SELF_PRIVILEGE_ESCALATION，需平台管理员另行操作。数据源凭证、平台信任、客户端secret、委派只能平台管理员持对应码执行。原SQL解密依独立ptmcp:audit:decrypt及审计资源范围判定，不限定某角色；所有角色默认无解密权，须明确另授。/me无需后台菜单码，强制当前用户所有权、平台上限及有效业务权限。

## 3. Web 会话 API

| 方法与路径（/api/v1） | 认证/输入 | data、行为 |
| --- | --- | --- |
| GET /session/csrf | 可匿名，same-origin | {csrfToken,expiresAt}，绑定短期预会话Cookie；no-store，不通过可跨站脚本公开 |
| POST /session/login | CSRF；{username,password} | {userId,displayName,expiresAt}；设置 PTMCP_ADMIN_SESSION HttpOnly/Secure/SameSite=Lax Cookie；失败不区分不存在/密码错误，401 AUTHENTICATION_FAILED；5次错误锁10分钟429 LOGIN_LOCKED；成功轮换session和CSRF |
| GET /session/me | ADMIN Cookie | {userId,username,displayName,department:{id,name},roles:[{id,name}],permissions:[code],managedScopes:[...],platformAdmin,expiresAt}；不返回密码/token |
| POST /session/logout | Cookie+CSRF；{} | {loggedOut:true}；注销当前会话并清Cookie；无会话仍返回已退出，不可借此查询账户 |
| PUT /session/password | Cookie+CSRF；{oldPassword,newPassword} | {reauthenticationRequired:true}；BCrypt校验旧密码，新密码12～64字符且UTF-8≤72字节，禁止与旧密码相同；提高auth_version并撤销本人全部ADMIN/AS/OAuth/PAT；完成后清Cookie |

用户名1～30 ASCII字母数字下划线点横线，登录以规范小写查唯一索引；迁移已有账号需显式处理。创建/修改/重置密码统一12～64字符且UTF-8≤72字节，BCrypt沿用若依算法，不能静默截断或引入未定义预哈希。AS登录是独立页面/过滤链与 PTMCP_AS_SESSION，不共享以上cookie。

ADMIN Cookie每次请求均在会话索引/权威版本通过后，从主库当前成员、有效角色及功能码重算平台管理员资格；
资格解析只缓存本请求，不续期或轮换CSRF。`/session/me`返回的platformAdmin与其本次permissions一致。
资格依赖不可用时，过滤器在管理操作前返回503 SECURITY_STATE_UNAVAILABLE，带no-store/nosniff。
此请求入口核验不代替安全写入事务提交前的操作者授权重验。

## 4. 管理端资源 DTO

所有Read DTO包含 id、version、createdAt、updatedAt；任何secret只在特定创建响应中一次返回，不进普通DTO。

| DTO | 写入字段与验证 | 读取补充字段 |
| --- | --- | --- |
| DatasourceWrite | code(1..64,创建后不变)、name(1..128)、engine MYSQL/POSTGRESQL、host、port、databaseName、username、credential(仅创建/轮换时必填)、tlsMode=VERIFY_FULL、poolMax(1..8)、status DRAFT/ACTIVE/DISABLED | configVersion/resourceVersion/lastSyncAt/activeSnapshotId/credentialConfigured/health；无credential密文 |
| UserWrite | username(仅创建)、displayName、departmentId、email≤50、phoneNumber≤11、remark≤500；创建initialPassword | status/roles；密码单独动作；不能写authVersion/menu权限 |
| DepartmentWrite | parentId、name≤30、orderNum、status；移动不得成环/越界 | pathIds、childrenCount；不能写ancestors |
| RoleWrite | key≤100(创建后不变)、name≤30、status、permissionCodes[]（仅调用者可委派子集） | memberCount、managedDepartmentIds；菜单权限与表ACL分别管理 |
| PlatformWrite | code/name、tier(1..3)、catalogMode DISCOVER/HIDE、proofMode MTLS/PROXY_SECRET/NONE、proofConfig（写入专用；证书指纹/代理约束）、status | scopeVersion/securityVersion/proofConfigured；Tier1不得NONE，降级要显式重新批准 |
| ScopeWrite | items:[{resourceType:DATASOURCE/NAMESPACE/OBJECT,resourceId,resourceGeneration,action:META/READ,includeDescendants}]，≤100 | scopeVersion；取代整组，不能指向非活动对象 |
| ClientWrite | name、type PUBLIC/CONFIDENTIAL、authMethods、redirectUris[]、allowedScopes[]、jwksUri(可选)、status；禁止PUBLIC secret | clientId/platformId/registrationSource；CONFIDENTIAL支持private_key_jwt或经审核secret方式；URI规则见认证设计 |
| GrantWrite | subjectType USER/ROLE/DEPT、subjectId、resourceType/resourceId/resourceGeneration、action META/READ、effect ALLOW/DENY、includeDescendants、validFrom、validTo(nullable)、reason1..500 | status/effectiveNow/staleResource；validTo>validFrom；OPEN ended只有完整委派权限可设 |
| DelegationWrite | userId、departmentRootId、resourceType/resourceId、allowedActions[]、validFrom、validTo | id/version/status；只有平台管理员能创建/撤销；不允许目标本人操作 |
| DictionaryWrite | title≤128、description≤8000、ownerUserId可空、columns:[{columnName,title,description}]≤500 | schemaComment/业务说明分开；未知列拒绝，富文本一期禁用 |

host/port先做DNS与网络目的地策略验证，拒绝云元数据/loopback等未授权地址；生产内网业务地址必须由运维CIDR白名单允许。不能提交任意JDBC URL、driverClass、SQL、连接属性或脚本；仅服务器模板形成连接串。连接错误转换稳定errorCode，真实host/驱动栈不回显用户。poolMax受所有副本预算约束，参数合法但容量不够返回422 CONNECTION_BUDGET_EXCEEDED。

## 5. 管理 API 完整清单

下表路径都位于 /api/v1/admin。表内“CRUD”明确展开为：GET /resource(list权限，分页)、POST /resource(create权限，Write DTO→Read DTO，201)、GET /resource/{id}(read→Read DTO)、PUT /resource/{id}(update，完整Write DTO，If-Match→Read DTO)、DELETE /resource/{id}(delete，If-Match→删除结果)。禁止把一个CRUD权限自动映射全部动作。

| 路径/方法 | 权限 | 输入→data与行为 |
| --- | --- | --- |
| /datasources CRUD | datasource对应动作 | DatasourceWrite→DatasourceDTO；列表筛选keyword/engine/status；停用/删除立即增安全版本，池DRAIN；仍有关联活动授权时删除409 REFERENCES_EXIST，先停用并处理依赖 |
| POST /datasources/{id}/test | datasource:test，平台管理员 | {draft?:DatasourceWrite}→202 JobDTO；draft不能改变id/engine/code，临时凭证不保存；无测试SQL入参，只运行内建健康探针 |
| POST /datasources/{id}/sync | datasource:sync | {mode:FULL}→202 JobDTO；每源串行，不变更配置；重复运行409 SYNC_IN_PROGRESS |
| GET /jobs/{id} | job:read且任务源管理范围 | JobDTO={id,type,datasourceId,state,progress:{processed,total:null},startedAt,finishedAt,errorCode,snapshotId}；total未知不伪造百分比 |
| GET /metadata/objects | metadata:list | datasourceId必填、namespaceId/keyword/kind/cursor/pageSize→ObjectSummary[]；仅管理范围，不绕过用户门户READ |
| GET /metadata/objects/{id} | metadata:read | →ObjectDetail（物理键、generation、快照version、列定义、字典）；含业务结构需显式管理元数据权限，菜单列举不自动授权查询 |
| PUT /metadata/objects/{id}/dictionary | metadata:dictionary | DictionaryWrite+If-Match→ObjectDetail；不能修改真实数据库DDL |
| /users CRUD | user对应动作 | UserWrite→UserDTO；筛选keyword/departmentId/status；不接受外部org树替换；删除逻辑删除并撤销令牌 |
| PUT /users/{id} | user:update，加实际变更的status/assign-role/move | 完整 UserWrite+If-Match→UserDTO；授权分类快照版本必须等于If-Match，落库保留同版本CAS，冲突不自动重试；roleIds缺省保留、按集合比较；委派管理员须自身具备所需功能码，且同一有效委派覆盖全部动作与新旧部门；角色变化时最终整组功能码不得超过该委派，不能跨委派拼接 |
| PUT /users/{id}/status | user:status | {status:ACTIVE/DISABLED}+If-Match→UserDTO；保留其他资料与角色；不能停用最后一个有效平台管理员 |
| GET /users/{id}/identity | user:read，平台管理员 | →IdentityState={userId,version,identityState:UNBOUND/READY/CHANGE_PENDING,recoveryRequired}；不返回摘要、目标或操作材料 |
| PUT /users/{id}/password | user:reset-password，平台管理员 | {newPassword,reason}+If-Match+Idempotency-Key→200 IdentityState；先提权威版本并撤销全部认证，跨库异常保持 Pending，由管理员恢复 |
| POST /users/{id}/password/retry | user:reset-password，平台管理员 | {}+If-Match+Idempotency-Key→200 IdentityState；只重试持久目标；完成重试不写宿主、不提版本/epoch；不接受新密码；版本与 body 一起绑定幂等摘要 |
| PUT /users/{id}/roles | user:assign-role | {roleIds:[]}+If-Match→UserDTO；整组替换，最终整组功能码由同一有效委派覆盖，不能逐角色拼接不同委派 |
| PUT /users/{id}/department | user:move | {departmentId,reason}+If-Match→UserDTO；新旧部门均可管理；更新身份版本与epoch |
| /departments CRUD | department对应动作 | DepartmentWrite→DepartmentDTO；列表可parentId过滤；删除有子部门/用户409；PUT移动校验整棵子树无环且在范围 |
| /roles CRUD | role对应动作 | RoleWrite→RoleDTO；共享范围外成员的角色仅平台管理员能改；删除有关联成员/ACL则409 |
| /platforms CRUD | platform对应动作，平台管理员 | PlatformWrite→PlatformDTO；删除前须停用/处理客户端及族；变更信任方式增securityVersion |
| GET /platforms/{id}/scopes | platform:read | →ScopeRead={items,scopeVersion,version} |
| PUT /platforms/{id}/scopes | platform:scope | ScopeWrite+If-Match→ScopeRead；主库事务+安全事件 |
| GET /platforms/{id}/clients | client:list | status/type/cursor/pageSize→ClientDTO列表 |
| POST /platforms/{id}/clients | client:create | ClientWrite→201 {client,clientSecret?}；一次secret；公开客户端无secret |
| GET /clients/{id} | client:read | →ClientDTO |
| PUT /clients/{id} | client:update | ClientWrite+If-Match→ClientDTO；redirect/authMethod变化撤销关联授权族 |
| DELETE /clients/{id} | client:delete | If-Match→删除结果；撤销所有关联族 |
| GET /acl/grants | acl:list | subjectType/subjectId/resourceType/resourceId/status/cursor/pageSize→GrantDTO列表 |
| POST /acl/grants | acl:create | GrantWrite→201 GrantDTO；授予不能超委派，重复逻辑授权返回409 DUPLICATE_GRANT（数据库唯一键需按规范化内容实现） |
| GET /acl/grants/{id} | acl:read | →GrantDTO及来源信息 |
| PUT /acl/grants/{id} | acl:update | GrantWrite+If-Match→GrantDTO；保留操作审计，不能改历史创建人 |
| DELETE /acl/grants/{id} | acl:delete | If-Match→撤销结果；记录REVOKED，不物理删除 |
| POST /acl/preview | acl:preview | {subjectUserId,platformId,resourceIds:[1..100],at?:UTC}→{decisions:[{resourceId,meta,read,reasonCodes,sourceGrantIds}],versions,evaluatedAt,nextBoundaryAt}；仅判定，不接受SQL/不借业务连接；未来时间只作模拟，不可执行 |
| GET /acl/effective | acl:effective | userId/platformId必填、datasourceId/cursor/pageSize→有效资源与来源；管理范围外404；不能以此向普通用户泄露全部组织权限 |
| GET /delegations | delegation:list，平台管理员 | userId/cursor/pageSize→委派列表 |
| POST /delegations | delegation:create，平台管理员 | DelegationWrite→201委派DTO；同时检查管理员能力及期限 |
| DELETE /delegations/{id} | delegation:delete，平台管理员 | If-Match→撤销结果+epoch事件 |
| GET /audit | audit:list | from/to(单次≤31天)、userId/platformId/datasourceId/tool/result/verdict/errorCode/cursor/pageSize→AuditSummary[]；不能筛sql_raw，不返回密文 |
| GET /audit/{requestId} | audit:read | →AuditDetail（脱敏SQL、对象、版本、阶段耗时、错误/截断），普通审计员无原文 |
| GET /audit/{requestId}/sensitive-access | audit:read | →原文访问轨迹；不含解密内容，仍核当前审计范围 |
| POST /audit/{requestId}/decrypt | audit:decrypt，额外明确授权 | {fields:[sqlRaw/sqlFinal],reason:10..500}→{sqlRaw?,sqlFinal?,expiresAt}；每次持久审计后才返回，no-store，服务端不缓存明文；保留期外404 |
| GET /operations | audit:list，平台管理员 | action/aggregateType/actorId/cursor/pageSize→管理操作日志游标页；不含原始SQL或结果 |

身份绑定：新建用户为 UNBOUND；宿主供应/显式导入闭环前，登录、改密和发证均拒绝。普通资料编辑不改变绑定状态；当前尚无供应转换接口。已有 READY/Pending 也核对宿主 ID 与元库规范化登录名，不符时不自动重绑。

专用status/roles/department接口始终核对应动作；委派管理员还须自身当前功能码与同一有效委派相交。
这一份委派必须覆盖本条命令的全部动作、新旧部门及最终整组角色功能码，不从其他委派补足。
每个接口使用同一次用户快照核If-Match、授权及生成写参数，保留落库同版本CAS；冲突不追新快照重试。
专用命令不额外要求user:update，URL、DTO、ETag保持兼容。平台管理员同样核对应动作；
通用PUT还按实际变化组合status/assign-role/move。事务内actor/委派/角色定义重验仍待后续切片。

启停实现：通用PUT与专用status共用OrgStore；ACTIVE/DISABLED实际变化与profile/roles、
authVersion/identityVersion、ADMIN/AS/PAT/OAuth撤销、epoch、事件和操作日志在同一元库事务提交。
启用不恢复旧凭证，相同状态/普通资料保存不额外撤证；提交回包丢失返回409 OPERATION_UNKNOWN，禁止自动重放。
阶段验证见[网关收尾工作项](../../tasks/work-items/DEV-20261028-gateway-completion.md)。
表中最后有效平台管理员保护仍是未完成的验收要求，当前实现尚缺跨所有权限变更入口的并发保护。

身份恢复补充：CHANGE_PENDING 期间用户资料、角色、部门与状态写入返回409 VERSION_CONFLICT，
先用当前 GET identity 的版本完成恢复再编辑；已完成恢复也拒绝过期 If-Match。
Cookie 管理写的 Origin/Referer 必须与请求的 scheme/host/port 一致；Sec-Fetch-Site 只接受 same-origin/none。
部署代理必须只从受信代理恢复外部请求地址，不能信任客户端自行提供的转发头。

业务对象不存在404、授权目标不可见404；可见对象但动作不足403 MANAGEMENT_SCOPE_DENIED。全局列表的actor过滤在持久查询层执行，禁止先分页再前端过滤。平台/数据源健康测试和同步运行的系统账号同样受凭证、目的地及审计约束。

身份动作响应中的 version 是用户 aggregate version，首个响应带 ETag；幂等重放以持久 body 中的 version 为准。
不再返回含混的 X-Security-Epoch。独立宿主未配置时不开放身份管理动作；跨库协议见 [ADR-0011](../architecture/adr/0011-跨库身份变更与权威版本.md)。
当前 REST 实现直接返回显式 DTO，统一 R 包装尚待 G7 合同对齐，不能宣称已完成该包装。

## 6. 个人门户 API

全部前缀 /api/v1/me，身份只从会话取，拒绝userId查询参数。下表执行本人归属及有效权限检查，所有写校验CSRF。

| 方法/路径 | 输入 | data与约束 |
| --- | --- | --- |
| GET /access-context | level=PLATFORM/DATASOURCE/NAMESPACE必填；后两级platformId必填，NAMESPACE还需datasourceId；cursor/pageSize | {items,nextCursor,evaluatedAt,versions}；PLATFORM item={type:PLATFORM,id,name,tier,catalogMode}；DATASOURCE item={type:DATASOURCE,id,name,engine}；NAMESPACE item={type:NAMESPACE,id,databaseName,schemaName:null或PG字符串,displayName}；每层按平台Scope/目录语义过滤，无可见对象源不返回，显式META Deny优先，不含列/凭证；PG catalog/database只作展示分组，不是新增授权资源层 |
| GET /dictionary | platformId、datasourceId必填，databaseName/schemaName/keyword/cursor/pageSize | 与MCP目录同语义ObjectSummary列表；不能跨平台扩大范围 |
| GET /dictionary/{objectId} | platformId必填 | 与get_table_schema同语义ObjectDetail；无READ拒绝，不能只凭META取列 |
| GET /tokens | status/cursor/pageSize | 本人PAT元数据id/name/prefix/scopes/platformId/expiresAt/lastUsedAt/status，无完整token/hash |
| POST /tokens | {name1..64,platformId(Tier3),scopes:[mcp:catalog/mcp:schema/mcp:query],expiresAt?} | 201 {token:一次明文,tokenMetadata}；默认30天最大90天；scope只能收紧用户与平台，no-store |
| DELETE /tokens/{id} | 无body | 本人撤销结果；不是本人统一404；重复撤销幂等 |
| GET /history | from/to≤31天、tool/result/verdict/cursor/pageSize | 本人AuditSummary列表；不支持客户端userId覆盖 |
| GET /history/{requestId} | 无 | 本人AuditDetail，仅脱敏SQL，无原文解密路径 |
| GET /mcp-config | platformId、clientType=GENERIC/WORKBUDDY/CURSOR/CLAUDE_CODE/VSCODE | {transport:"streamable-http",url,authentication:["oauth"/"pat"],configTemplate,compatibilityStatus,validatedVersion:null或实测版本}；模板secret占位，不自动嵌PAT，不编造兼容通过 |
| GET /authorizations | cursor/pageSize | 本人授权族列表{id,clientName,platformId,issuedAt,lastUsedAt,status,absoluteExpiresAt}；无token |
| DELETE /authorizations/{familyId} | 无body | 撤销整族及关联JWT；非本人404 |

## 7. MCP 传输与三个工具的精确输入

唯一传输 HTTPS Streamable HTTP /mcp，Spring候选使用 protocol=STATELESS，不建立跨请求 Mcp-Session-Id 授权缓存。每次HTTP请求完整校验Bearer与平台proof；未认证返回401并带协议要求的WWW-Authenticate/资源元数据发现位置。服务可执行 initialize/tools/list/tools/call 等必要协议方法，但业务Tool只能注册下列三个。协议版本由SDK与客户端M0矩阵实测锁定，不宣称未知版本已兼容；不开放旧SSE路径。

工具输入 JSON Schema（均 additionalProperties:false；string长度单位为字符，sql_query另按UTF-8字节上限64KiB）。datasource_id 非空十进制ID字符串，database_name/schema_name按目标快照原名精确绑定，不做模糊改库。

~~~json
{
  "name":"list_databases_and_tables",
  "inputSchema":{"type":"object","additionalProperties":false,"required":["datasource_id"],"properties":{"datasource_id":{"type":"string","pattern":"^[0-9]+$"},"database_name":{"type":"string","minLength":1,"maxLength":128},"schema_name":{"type":"string","minLength":1,"maxLength":128},"keyword":{"type":"string","minLength":1,"maxLength":128},"cursor":{"type":"string","minLength":1,"maxLength":2048},"page_size":{"type":"integer","minimum":1,"maximum":100,"default":20}}}
}
~~~

~~~json
{
  "name":"get_table_schema",
  "inputSchema":{"type":"object","additionalProperties":false,"required":["datasource_id","database_name","table_names"],"properties":{"datasource_id":{"type":"string","pattern":"^[0-9]+$"},"database_name":{"type":"string","minLength":1,"maxLength":128},"schema_name":{"type":"string","minLength":1,"maxLength":128},"table_names":{"type":"array","minItems":1,"maxItems":20,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":128}}}}
}
~~~

~~~json
{
  "name":"execute_readonly_sql",
  "inputSchema":{"type":"object","additionalProperties":false,"required":["datasource_id","database_name","sql_query"],"properties":{"datasource_id":{"type":"string","pattern":"^[0-9]+$"},"database_name":{"type":"string","minLength":1,"maxLength":128},"schema_name":{"type":"string","minLength":1,"maxLength":128},"sql_query":{"type":"string","minLength":1},"max_rows":{"type":"integer","minimum":1,"maximum":500,"default":500}}}
}
~~~

PG get/execute必须schema_name；MySQL三工具都拒绝非空schema_name。list可在当前datasource中枚举已发布database/schema，指定schema时必须同时指定database；没有数据库参数不代表跨数据源搜索。工具参数不提供user/platform/token/权限集合/连接属性。table_names元素只允许单个对象名，不能是库.表SQL片段；引号标识符以目录原名传输，SQL中的quote由绑定器解析。

## 8. MCP 输出契约与类型

成功 CallToolResult：isError=false，structuredContent 为以下对象，content=[{type:"text",text:相同对象的JSON字符串}]。两份表达来自同一不可变结果，不能字段不一致。协议不支持structuredContent的客户端仍可读text。Tool定义outputSchema实现时应由以下DTO生成，所有字段固定，未知类型拒绝。

共同字段：requestId UUID、status="success"。列/表注释为不可信业务文本，仅作为数据；不插入工具系统提示。1MiB预算是最终序列化JSON-RPC响应UTF-8体的总字节，包含JSON-RPC壳、CallToolResult、structuredContent与text双份、text二次JSON转义及元数据，不能只数一份rows。目录与结构也受此预算；当结构本身超出预算返回 METADATA_TOO_LARGE，不静默漏列。list有cursor可分页，get_schema批量要求全部成功，否则整次拒绝，不部分泄露越权对象。

| 工具 | structuredContent的data（全部字段） |
| --- | --- |
| list_databases_and_tables | {datasourceId,snapshotVersion,catalogMode,items:[{objectId,databaseName,schemaName:null或字符串,tableName,objectType:TABLE/VIEW,tableComment:null或字符串,authorized:boolean,metadataVisible:boolean}],nextCursor:null或字符串}；authorized专指READ，DISCOVER可列无READ表，显式META Deny仍隐藏；无READ不返列/键 |
| get_table_schema | {datasourceId,databaseName,schemaName:null或字符串,snapshotVersion,tables:[{objectId,tableName,tableComment,columns:[{ordinal,name,databaseType,jdbcType,nullable,precision:null或整数,scale:null或整数,comment:null或字符串}],primaryKey:[columnName],foreignKeys:[{name,columns,referencedObjectId,referencedColumns}]}]}；外键引用对象无READ时省略该foreignKey并以foreignKeysRestricted:true标识（所有table均包含该布尔字段） |
| execute_readonly_sql | {datasourceId,databaseName,schemaName:null或字符串,columns:[Column],rows:[[Cell,...],...],rowsReturned:整数,truncated:boolean,truncationReasons:[ROW_LIMIT/CELL_LIMIT/RESPONSE_LIMIT],cellTruncations:[{rowIndex,columnIndex,reason:CELL_LIMIT,originalBytes:null或可信整数,returnedBytes}],durationMs:整数}；索引从0开始 |

Column={index:整数,name:字符串,databaseType:字符串,logicalType:STRING/INTEGER/DECIMAL/FLOAT/BOOLEAN/DATE/TIME/LOCAL_DATETIME/INSTANT/BINARY/JSON,nullable:布尔,encoding:null/BASE64/JSON_TEXT}。重复列名保留，按index访问，不用map覆盖列。Cell为null、boolean、JSON number或JSON string；BIGINT/NUMERIC/DECIMAL固定string（INTEGER仅安全32位整数用number），浮点NaN/Infinity作为string并logicalType=FLOAT，客户端不可当JSON非法数字。日期YYYY-MM-DD，TIME含精度及有时区时显式offset，LOCAL_DATETIME为不带Z的数据库本地值，INSTANT转UTC带Z；不能伪造本地DATETIME为UTC。BINARY为BASE64；JSON为有界JSON_TEXT字符串，截断后不声称是完整合法JSON。单格4KiB按输出UTF-8/解码二进制预算，JSON转义和BASE64膨胀同时计入总1MiB。

get_table_schema 的 data 另含 `contextPolicy="UNTRUSTED_DATA"`、`contextTruncated:boolean`。
每张 table 另含 `tableCommentTruncated:boolean`、`businessDictionary:null或Dictionary`；
每个 column 另含 `commentTruncated:boolean`、`businessDictionary:null或Dictionary`。
Dictionary={source:"business_dictionary",version:十进制字符串,title:字符串,description:null或字符串,truncated:boolean}。
业务字典标题/说明可表达业务词义与单位，独立于物理 tableComment/comment；不返回维护人等管理身份。
只有整批表 READ 通过才读指定对象字典；列字典必须仍匹配当前发布快照的列，旧列词条不披露。
读取后重新加载权限，返回前复核身份、主库安全状态、发布版本及当前时间的 READ（含已披露外键目标）；
返回前复核观察到在途撤权、到期或版本变化时整批拒绝；不承诺与网络发送瞬间的零时延同步。

schema 中每个注释、字典 title/description 最多 2048 UTF-8 字节；本次所有这些文本共享
32768 UTF-8 字节预算，按表名自然排序、每表物理注释→表字典→列 ordinal 顺序分配，列内先物理注释再字典。
按完整 Unicode code point 保留前缀，不添加伪造内容；任一字段缩短则其局部标记和 contextTruncated 为 true。
预算用尽时已有词条可返回空文本及 truncated=true；null 表示没有该说明/字典。列名、类型、键不因文本预算被删改。
最终 JSON 转义、text/structuredContent 双份和 JSON-RPC 壳仍计入 1MiB，结构超限整体返回 METADATA_TOO_LARGE。
此预算是输出约束，不是宽表读取的聚合内存证明。tools/list 的三工具与参数说明明确这些文本为数据，不能作为代理指令。

若已知SQL外层LIMIT等于上限且恰好返回上限，无法证明有更多行时 truncated=true,ROW_LIMIT 表示“已施加上限，可能还有行”，不额外取第501行突破预算。用户原LIMIT小于上限且全部返回不标ROW_LIMIT。CELL_LIMIT不将真实值改成[TRUNCATED]，有界前缀加独立cellTruncations。response预算在追加整行前计算，放不下的行整行舍弃，columns本身放不下返回RESULT_METADATA_TOO_LARGE。无任何列输出不能通过空结果逃避未知输出类型校验。

错误 CallToolResult 统一 isError=true，structuredContent与text相同：

~~~json
{"requestId":"4ad5cda9-055f-4f88-a9d5-f926c6d9be47","status":"denied","error":{"code":"TABLE_NOT_AUTHORIZED","message":"当前账号无权查询该资源，请联系管理员","retryable":false,"details":{}}}
~~~

status=denied用于策略拒止，error用于执行/系统失败。details.objects只列调用者已获META可见的物理键；隐藏资源只用不泄漏名称的resourceReference，不能因错误反查系统库。HTTP认证/proof失败在Tool之前401/403；JSON-RPC无效方法/无效参数遵循协议错误，工具内部SQL/ACL/超时均使用isError=true，不用HTTP200包装REST错误。

## 9. 认证发现与 OAuth 端点

| 端点 | 方法/职责 | 输出/特殊要求 |
| --- | --- | --- |
| /.well-known/oauth-protected-resource | GET gateway资源发现 | 标准protected resource metadata，resource=/mcp的固定受信URL，authorization_servers明确配置 |
| /.well-known/oauth-authorization-server | GET authz发现 | issuer固定，端点URL来自部署配置；不信任Host/X-Forwarded-Host拼接 |
| /oauth2/authorize | GET，authorization_code授权入口 | 必须S256/精确redirect_uri/state/resource；AS会话/同意页；不开放implicit/password grant |
| /oauth2/token | POST form-urlencoded | code/refresh，标准token响应，无REST包装；NONE+S256授权码流程正常设计开放，只有PUBLIC none的refresh扩展在M0通过前默认关闭；不以grant_type切换跳过client绑定 |
| /oauth2/revoke | POST form-urlencoded | {client_id,token,token_type_hint?}；CONFIDENTIAL按登记方式认证；PUBLIC none需专门revocation converter/provider/handler扩展（与refresh同M0验证，非默认code provider自动支持），凭真实token且client/family绑定才可撤销；不要求code_verifier，不凭client_id撤销；未知/跨client token统一标准200防枚举 |
| /oauth2/jwks | GET | 只发布公钥，轮换保留覆盖10分钟JWT有效期+时钟偏差 |
| /connect/register | POST，DCR（按部署开关） | 配额、限速、元数据校验，默认mcp:catalog且平台资源范围为空；不能注册Tier1；不接受用户指定platform_id提升信任 |

CIMD是客户端metadata的受控解析路径，不是任意代理/开放URL抓取接口；登记来源URL不证明后续Bearer来源。具体协议错误如invalid_grant/invalid_client/invalid_scope使用OAuth标准，不映射统一REST码。AS登录与同意页还需CSRF和独立会话，不把管理session当授权登录。

## 10. 稳定错误码与重试

REST 的整数 R.code 按模块分段，唯一分配来源为[错误码登记](../../config/quality/rest-error-codes.json)，成功码保留 200；例如 VERSION_CONFLICT 对应 100006。下表的 code 列是稳定符号键，REST 放 data.errorCode，MCP 继续放 error.code 字符串；OAuth 的 invalid_grant 等保持标准语义，不映射成 R<T>。表中的 HTTP 和 retryable 规则不因分段而改变。登记数字/符号发布后不得复用。服务端纯Java ErrorCode 枚举由 `python scripts/generate_error_codes.py` 生成，`--check` 和根检查只读验证生成物一致性，不能手改生成物绕过登记。前端类型与真实REST/MCP异常映射、序列化契约仍在后续适配片验收，不以生成检查替代。

| HTTP/Tool边界 | code | retryable |
| --- | --- | --- |
| 400/422 | INVALID_ARGUMENT、UNSUPPORTED_FIELD、DIALECT_CONTEXT_INVALID、IDEMPOTENCY_KEY_REUSED、CONNECTION_BUDGET_EXCEEDED | false |
| 401 | AUTHENTICATION_REQUIRED、AUTHENTICATION_FAILED、TOKEN_INVALID、TOKEN_REVOKED、SESSION_EXPIRED | false（重新认证，不重放原SQL） |
| 403 | PLATFORM_PROOF_REQUIRED、PLATFORM_MISMATCH、CSRF_INVALID、MANAGEMENT_SCOPE_DENIED、SELF_PRIVILEGE_ESCALATION | false |
| 404 | RESOURCE_NOT_FOUND | false |
| 409 | VERSION_CONFLICT、CURSOR_STALE、REFERENCES_EXIST、SYNC_IN_PROGRESS、OPERATION_IN_PROGRESS、OPERATION_UNKNOWN | false；先刷新状态/查任务 |
| Tool拒止 | SQL_SYNTAX_INVALID、SQL_NOT_READONLY、SQL_UNSUPPORTED、SQL_BUDGET_EXCEEDED、TABLE_NOT_AUTHORIZED、FUNCTION_NOT_ALLOWED、TYPE_NOT_ALLOWED、RESOURCE_AMBIGUOUS、RESULT_TYPE_UNSUPPORTED、REWRITE_VALIDATION_FAILED | false |
| Tool拒止/503 | SCHEMA_STALE、RESOURCE_VERSION_CHANGED、SECURITY_STATE_UNAVAILABLE、AUDIT_UNAVAILABLE | true（限次退避，须重新校验；不自动重放已入执行阶段的SQL） |
| 429/Tool | USER_CONCURRENCY_LIMIT、DATASOURCE_CONCURRENCY_LIMIT、GATEWAY_BUSY、LOGIN_LOCKED | true并Retry-After；LOGIN_LOCKED只在锁期后重试登录 |
| Tool执行错误 | QUERY_TIMEOUT、CLIENT_CANCELLED、DRIVER_ERROR、CLEANUP_FAILED、RESULT_METADATA_TOO_LARGE | false；结局需结合requestId审计查询，不能猜失败就重跑 |
| 503 | DEPENDENCY_UNAVAILABLE、CONNECTION_POOL_UNAVAILABLE | true，若已执行则错误detail仅标executionState=UNKNOWN，不自动重试 |
| 500 | INTERNAL_ERROR | false；隐藏栈并告警 |

retryable仅表达新尝试是否可能成功，不承诺幂等或必须重试。REST安全变更不会因通知失败返回“未提交”误导重试，主库提交后即算成功并告知event pending；权限变更最迟5秒主库核验生效。接口验收包含协议契约、越权、IDOR、CSRF、If-Match竞争、幂等并发、URI校验、Tool参数额外字段、UTF-8预算及敏感错误泄漏。机器可读OpenAPI/outputSchema应从本文在M0生成并纳入一致性测试，当前没有已发布Swagger地址。
