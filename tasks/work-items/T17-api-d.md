# T17-api-d：管理委派端点与前端真实模式接线

ID: T17-api-d
Status: verifying
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-10-26

## 目标与边界

来源：用户指令「持续打磨前后端联调，确保前后端功能正确、真实实现，而不是演示 demo」。
承接 [T17-ui-business](T17-ui-business.md) 交接项 1（管理委派列表/创建/撤销）。本切片实现
`/api/v1/admin/delegations` GET/POST/DELETE 并解除前端真实模式禁用；委派的非平台管理员
作用域执行（ManagementScope 委派感知，PRD「组织授权管理员必须同时具备功能码和有效委派」）
仍属后续切片，控制器 javadoc 与本工作项显式声明，不把记录 CRUD 当作作用域已完成。

## 实施与验证

- `ptmcp-persistence/.../org/DelegationStore.java`（新增）：list（userId/status/resourceType
  筛选、id DESC 游标、limit+1 由控制器截断）、create（单事务：主体/部门/资源存在校验，
  DATASOURCE generation=1、NAMESPACE/OBJECT 取 max(active generation)，重叠窗口
  DUPLICATE_GRANT，自我委派 SELF_PRIVILEGE_ESCALATION，动作⊆调用者 ptmcp:user/department/
  role/acl: 功能码，动作码正则白名单）+ revoke（If-Match 乐观锁）。每次变更同事务写
  `admin_operation_log`（本表首个生产写入方）。
- `ptmcp-admin/.../api/DelegationAdminController.java`（新增）：平台管理员门禁；POST 走
  `IdempotentWrites`（Idempotency-Key 必填，重放返回 `Idempotent-Replay: true` 与原响应）；
  DELETE 走 If-Match；EXPIRED 为派生读模型（status=ACTIVE 且窗口已过），不写库。
- `AdminStoresConfiguration`：注册 `delegationStore`。
- `AdminApiTest`：新增 3 个用例（生命周期+EXPIRED/REVOKED 派生+操作日志两行；自我委派/
  外来动作/重复/If-Match 冲突/受控管理员自撤；幂等重放+未知资源 404 只落一行）。发现并
  适配：清库后 `POST /users` 的 MAX+1 编号从 1 起，与 shim 主体撞号，用占位用户隔离。
- 前端：`frontendMode.ts` 新增 `delegationApiReady`（域级后端就绪标志，随切片逐域开放）；
  `controlPlane.ts` 的 `controlRequest` 支持按域放行；`DelegationsView.vue` 换用
  `delegationReady` 并移除「待接入」警告。预览模式（5174）合成数据交互不变。
- `tests/delegation-contract.test.ts`（新增）：预览流契约（list/create/revoke/REVOKED 过滤）+
  真实模式域放行断言（委派出网络请求、platforms/effective 仍被「尚未接入」门禁拒绝）。
- 环境（随基线提交 cc93bad）：测试镜像参数化 `ptmcp.test.mysqlImage/pgImage/mysqlSeries`
  （默认仍固定 digest 与 8.4.，本机 mysql:8.0/postgres:15）；pnpm 对齐 11.9.0。

## 结果与证据

- `./.auto/measure.sh`：前端契约 72/72、后端 392/392（admin 全 reactor 真实 MySQL 容器），
  0 失败；`.auto/checks.sh`：`pnpm check`（lint/prettier/vue-tsc/build）+ `python3
  scripts/check.py` 通过。
- 提交：cc93bad（测试环境基线）、16734b3（本切片），分支 `xquan/feat-backend`，已推送。
- 未验证项：MySQL 8.4 生产版本断言（本机无法拉取 8.4 镜像，与 DEV-20261026 记录一致）；
  浏览器端到端（无真实后端会话走查，本轮仅契约层）；委派作用域执行。

## 交接

S2 已完成：`GET /api/v1/admin/acl/effective`（AclGrantAdminController#effective，资源树 id 序
稳定分页、datasourceId 子树筛选、reasonCodes 闭合世界解释），前端 EffectiveAccessPane 解禁，
提交 ab5ec33。测试注意：清库后首个 createUser id=1 撞 shim 主体，需占位用户（两处已踩）。

S3 已完成：平台 CRUD（PlatformAdminService，proof_config 经 CredentialCipher 只写加密、
security_version 作 If-Match 乐观锁、Tier1 必须逐调用证明、code 不可变、逻辑删 DELETED、
操作日志三动作），前端 PlatformsView/PlatformDetailView(平台本体)/PlatformBasicsForm 解禁，
提交 79e6af3。注意：platform_registry 无 version 列；readLong 不解析带引号数字（新
readQuotedLong）；调试 printStackTrace 用后即删。

S4 已完成：平台 Scope（GET/PUT /platforms/{id}/scopes 整组替换、scope_version+security_version
双自增、If-Match 对 security_version、资源 generation 校验、OBJECT descendants 归一化），
前端 ScopeConfigurationPane 解禁，提交 1831220。注意：MySQL JSON 列读回带空格规范化；
jsonPathCursor 只适用 nextCursor（误用曾致 If-Match 怪值）。

S5 已完成：客户端管理（POST /platforms/{id}/clients 一次性 secret；client_secret 列存
{bcrypt} 与 Spring AS 兼容、client_secret_hash 存 SHA-256；PUT/DELETE /clients/{id} 同事务
撤销 ACTIVE refresh family；回调规则同 DCR），新迁移 V7 将 admin_operation_log.aggregate_id
升级 VARCHAR(64)（OAuth client id 为 UUID），前端 PlatformDetailView 客户端域解禁，
提交 2a541e5。注意：客户端 id 为 VARCHAR，服务层全 String id；独立 /clients/{id} 路由需
独立 ClientAdminController。

S6 已完成：审计解密（POST /audit/{id}/decrypt，reason 10-500 必填、fields⊆{sqlRaw,sqlFinal}、
解密与 audit_sensitive_access 落库同事务、expiresAt=now+300s 前端倒计时清除；AuditCipher
独立部署密钥 ptmcp.admin.audit-key，与 credential-key 分离），前端 AuditView/AuditDetail
解禁，提交 5e353c6。发现：当前 gateway terminal() 写 encryptedRaw=null，audit_log 密文列
恒空——密文写入是 gateway 侧切片（已记 .auto/ideas.md），解密通路已就绪并测试覆盖。

S1–S6 全部完成，controlPlane 六域（delegations/effective/platforms/scopes/clients/audit）
真实模式全部解禁，前端预览模式（5174）合成数据交互保留。

UI 打磨批次一（b3a781f）：委派状态彩色标签（生效中/已到期/已撤销，对齐数据源页视觉）、
Scope 差异确认框分条 diff（新增/修改/删除逐条列出）、审计解密倒计时 aria-live、
EffectiveAccessPane 空态解释+原因码图例、平台 proof 留空占位统一。U2 客户端 secret 一次性
弹窗已有 OnceSecretDialog 实现无需改动。

浏览器端到端走查（f8d7966）：Dev-Linux 真实栈（MySQL:13306 + 迁移 V1–V7 + admin/authz +
5173），Playwright 登录走查。抓到并修复 3 个真实缺陷：①ClientConfigurationForm 残留
controlPlaneAvailable 门禁（保存按钮真实模式永禁用，S5 漏网）②委派目录对未同步数据源的
SCHEMA_STALE 503 整页报错（改单源软失败，数据源节点仍可委派）③平台对话框过时「本地预览」
标题。复验链路全通：登录→委派列表/EXPIRED 筛选/新建对话框（管理员下拉排除自己）→平台创建
跳详情→客户端 CONFIDENTIAL 创建→secret 一次性弹窗→MySQL 确认 {bcrypt}+32B hash、
PUBLIC 无 secret；390px 委派页无横向溢出。截图 .playwright-mcp/e2e-*.png。预期网络噪音
（登录前 401、软失败 503）与非缺陷记录分离。走查脚本 /tmp/e2e-walk*.py（会话级，不入库）。

S7 用户密码重置（0972cb4）：PUT /users/{id}/password 跨库按序设计——元库
SecurityChangeTransaction 原子提交（安全 epoch bump + USER_SECURITY_CHANGED 事件 + ACTIVE
授权族撤销 + sys_user_profile 版本递增〔If-Match〕+ 掩码操作日志）→ 提交后身份库写 bcrypt
hash（12～64 字符、UTF-8≤72B 校验；明文只存在于单请求帧、不入日志）。身份库写入失败时失败
封闭（凭证已撤销，重试即可）。identity 数据源为可选装配
（ptmcp.admin.identity-datasource.*，生产指向宿主身份库，dev 同库）；adminDataSource 标注
@Primary。前端 UserPasswordDialog/UsersView 解禁，警告文案对齐真实行为。遗留：web_session
行级撤销未做（epoch 机制已使旧票据失效），PasswordDialog 的「会话」表述由 epoch 兜底。

至此 T17-api 的管理面端点缺口（委派/平台/客户端/Scope/effective/审计解密/密码重置）全部
补齐，仅剩 ideas 中的 gateway 密文写入与 T16 委派作用域执行。

S8 org POST 幂等接线（1d49dca）：T16-a 交接遗留收尾——departments/users/roles 创建端点接
IdempotentWrites（Idempotency-Key 必填、同键同体重放、缺键 400），前端 orgPortalApi 三个
create 补发 Idempotency-Key。至此全部 admin 写端点（POST/PUT/DELETE）均有幂等或乐观锁
契约门禁。

S7 走查验证批（ab14624）：Dev-Linux.sh 对存量 dev.env 自动补写新增密钥
（PTMCP_ADMIN_AUDITKEY / PTMCP_ADMIN_IDENTITY_DATASOURCE_*， Secrets 不落仓库）；修复
UserPasswordDialog 缺失 Idempotency-Key 头（IdempotentWrites 必填校验曾致 400）。浏览器全
流程验证：警告文案→双密码+理由→确认→「操作已完成」→sys_user bcrypt、profile version 递增、
USER_PASSWORD_RESET 掩码日志落库。跨库一致性场景确认：元库用户无身份库账号时重置返回 404
（失败封闭，符合 PRD「创建档案不等于开通可登录账号」）。待办移交 .auto/ideas.md：gateway
密文写入、委派作用域执行（T16）、org POST 幂等接线、MySQL 8.4 复验、浏览器端到端走查、
UI 打磨切片池。

S9 gateway 审计密文写入（210868f）+端到端验证（3084f76）：AuditSealer（AES-GCM iv||ct||tag
与 admin AuditCipher 同格式同 keyId 派生 local:sha256hex16；部署配对
ptmcp.gateway.audit-key=ptmcp.admin.audit-key）；成功路径密封原始 SQL；集成用例用独立
audit-key 证实验钥配对（防误用 credential-key）。

拒绝查询审计盲区补全（961a502）：错误路径（GUARD 拒绝→DENY、执行失败→ERROR）也密封尝试
的原始 SQL——审计员可经 reason 门禁读出「用户尝试了什么被拒的 SQL」。集成用例：DENY 行
解密回原文。

sql_masked 网关侧脱敏器（4111d09）：SqlLiteralMasker（jsqlparser 双解析验证+正则字面量脱敏
→?，失败保守 null；与 sealer 状态解耦）。成功/拒绝两路径填充。个人历史与管理台 SQL 脱敏
列自此点亮。遗留：MySQL 反斜杠转义保守放弃。

敏感解密频控（ASVS 4.2.1，c91f680）：每操作者滑动 1 分钟窗 10 次上限，计数直接用
audit_sensitive_access 审计行自身（尝试落库=限流状态）；失败尝试也占预算；超限
SENSITIVE_REVEAL_LIMIT 140006→429 retryable（新错误码走正规规则治理流程生成）。

审计敏感访问记录（c0c7241）：GET /audit/{id}/sensitive-access + 前端「明文访问记录」卡片
（操作者/理由/字段/时间，不可删除）+解密成功自动刷新联动。

T16 委派作用域执行六片（判定核 9da7437、departments 9f7fc28、users 51cbfe3、roles 只读放开
96232ca、ACL 四维门禁 49f72ba、AC-02 跨范围角色约束 3c9ba5c）：DelegationScopeResolver
（窗口/动作/资源/子树 ancestors 判定）+各域 ScopeGate（平台管理员全权；受控管理员=有效委派
∩子树∩功能码）+行级过滤+建根平台专属+move 双侧校验+SelfEscalationGuard 保持。受控管理员
浏览器走查四项断言全过零缺陷。

S7 补全 AC-01（4cc6aa5）：管理员密码重置同事务撤销全部持久凭证（web_session/PAT/OAuth）。
S8 org POST 幂等（ccfe97f 前后补齐）。教训：多行 patch 必须从 spotless 后文本复制；测试
body 数字 id 不能字符串 replace 用户名；测试窗口禁写死日期；调试 printStackTrace 用后即删。

MCP 查询流端到端全通（106999b）：MySQL 8.4.13 拉取成功（dev 卷升 8.4，8.0 覆盖移除）；
业务库 TLS 激活（自建 CA+SAN 127.0.0.1，compose 挂载+ssl flags，Dev-Linux.sh
ensure_mysql_tls 自动生成，truststore PKCS12 经 env）；凭据密钥部署配对（admin/gateway
credential-key 同值）；gateway truststore env 冲突清除。真实查询经 PAT→guard→TLS 执行
返回数据，审计行三层验证。注意：compose 卷路径用 ${PTMCP_DEV_TLS_DIR}（flow 需引号）；
Dev-Linux stop 不停 gateway——需 pkill。

审计链浏览器解密验证（走查记录）：真实查询行经 admin console 全链 UI 验证——脱敏 SQL→
reason 门禁解密显示真实业务 SQL+倒计时→敏感访问留痕联动。截图 e2e-decrypt-*.png。
至此「真实查询→密文→浏览器 reason 门禁解密→复核留痕」在 dev 真实栈全链闭环。

受控管理员 PAT/查询 E2E（走查记录）：delegate-walker access-context 可见委派子树平台→
API 创建 PAT 201→gateway PAT 查询 SUCCESS（rows [[2,'測試']]）→审计行归属 user_id=3、
sql_masked 正确、raw/final 双密文齐。委派管理员的完整查询+审计链验证通过。PAT 创建无
权限码门禁（安全边界由 ACL 授权控制，语义正确）。

me/integrations 真实模式验证（走查记录）：gateway 启用后，接入配置页平台选择→显示真实
gateway URL（8082/mcp）+PAT 认证说明——用户侧「创建 PAT→查看 MCP 配置→接入客户端」旅程
闭环。截图存证。

PAT 撤销生命周期 E2E（走查记录）：受控管理员 PAT 撤销前查询 SUCCESS→DELETE 撤销→撤销后
查询 401 TOKEN_REVOKED（gateway 实时强制撤销，无缓存延迟）——T12 撤销传播的真实栈验证
通过。

管理操作日志读端点（f472edd）：GET /admin/operations（平台管理员、id DESC 游标分页、
action/aggregateType/actorId 筛选）——S1-S9 七切片写入的 admin_operation_log（委派/平台/
客户端/密码重置）自此可读，管理操作审计复核闭环。nextCursor 仅溢出页返回。UI 操作日志页
为下一切片。

管理操作日志 UI 页（c44e327）：路由 /admin/operations+导航「操作日志」（安全与追踪组，
ptmcp:audit:list 门禁）+controlPlane.audit.operations+OperationsView（列表/三筛选/展开
payload 明细）。浏览器走查：17 行真实轨迹（密码重置/客户端/平台/委派）、聚合筛选、明细
展开全通。教训：Dev-Linux build 后 start 不重编译 spring-boot:run 类路径（operations
端点曾 404）——改后端必须完整 build+start。

AC-02 浏览器验证（走查记录）：受控管理员（delegate-walker）在 UsersView 编辑对话框选中
越权角色（携 ptmcp:audit:decrypt 码）保存→403 拒绝消息正确呈现（『没有执行此操作的权限』）
——AC-02 UI 侧核心断言通过。narrow 角色赋权 200 由 AdminApiTest 覆盖；浏览器多选下拉二次
选择时序留待完善（脚本问题非产品缺陷）。截图 e2e-ac02-broad-denied.png。

操作日志导航平台管理员一致性修复（cf19b4a）：MeView +platformAdmin（session 既有值）+
SessionUser 接口/store computed/NavigationItem.platformAdminOnly+WorkspaceLayout 过滤——
受控管理员不再看到点击即 403 的操作日志入口。双 context 浏览器验证：admin
platformAdmin=True+导航可见；delegate False+导航隐藏+直接访问 403。

UsersView 角色赋权针对性提示（AC-02 UX，ed73bf9）：setRoles 步骤单独 try-catch——
MANAGEMENT_SCOPE_DENIED 映射针对性文案（所选角色包含委派范围外功能码，联系平台管理员）
并中止后续步骤；浏览器验证提示正确呈现。

S7 双存储缺口修复（d909c29）：管理员重置的元库事务补写 sys_user_profile.password_hash
（admin 会话验证源），与身份库 sys_user.password（OAuth 端）同步——修复用户无法用新密码
登录 admin 门户的缺口（dev E2E 实证 401）。AdminApiTest 新增 profile hash 断言。

me/history DENY 行可读化（b1439ff）：queryErrorText 前端映射模块（对齐网关
McpErrorCatalog 文案）+详情页错误码行追加可读括注。浏览器验证：真实 DENY 行
（SQL_NOT_READONLY）详情页显示「仅允许只读查询语句」+原始码同显+字面值不泄露。

me/history 列表 DENY 行可读化（0d5c631）：列表加「原因」列（queryErrorText 可读文本，
DENY 行红色高亮）——此前列表无错误码列，用户须点进详情才知道被拒原因。浏览器验证：DENY
行显示「仅允许只读查询语句」。教训：视图新增 import 必须同步 mount helper 的 urls 映射。

PAT scope 边界 E2E（走查记录）：受控管理员 catalog-only PAT 查询→TOKEN_INVALID（缺
mcp:query 授权范围）；full-scope PAT 查询 200 success——scope 最小化边界在真实栈（gateway
启用+TLS 执行段）验证。注：python cookiejar 会话多步间失效会映射 400 假阳性，curl 实证为准。

个人历史数据隔离 E2E（走查记录）：delegate-walker（user 3）me/history 显示仅本人 5 行
（PAT 查询 ALLOW/DENY 混合），admin 的 19 行（含 DELETE 密文行）零泄露；DENY 行 sqlMasked
有值（S9 拒绝路径密封+脱敏对错误路径同样生效的实证）。多租户数据隔离在真实栈验证通过。

org 变更操作日志（702895f）：OrgStore.writeOperationLog 共享 helper（connection 内事务写，
UUID request_id，masked payload 调用方构造）+updateUser 事务内写 USER_UPDATE 轨迹。显式
用例（改名后 USER_UPDATE 行 after 含新名）。教训：改 persistence 后测试跑旧类（target
过期）——两次失败假象同根因，必须 install。departments/roles 接线为下一切片。
