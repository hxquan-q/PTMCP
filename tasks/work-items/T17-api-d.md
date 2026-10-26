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
补齐，仅剩 ideas 中的 gateway 密文写入与 T16 委派作用域执行。待办移交 .auto/ideas.md：gateway
密文写入、委派作用域执行（T16）、org POST 幂等接线、MySQL 8.4 复验、浏览器端到端走查、
UI 打磨切片池。
