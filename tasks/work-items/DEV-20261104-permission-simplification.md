# DEV-20261104-permission-simplification：单管理员 + 角色=纯数据模板

ID: DEV-20261104-permission-simplification
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-11-05

## 目标与边界

来源：用户决定把权限模型彻底简化为「单管理员 + 角色=纯数据模板」：

- 只有一个管理员负责整个网关；其余所有人都是普通用户。
- 用户的权限只按角色划分；普通用户不进管理后台、不做管理操作，通过角色获得数据读取权限。
- 唯一管理员不需要功能码：他本就能操作所有管理功能，不再以 `ptmcp:xxx` 码逐项授权。
- 角色=数据权限的模板：管理员在授权矩阵里给角色配置「能读哪些库/表」，再把用户挂到角色上。
  角色不再承载管理功能码，只承载数据权限。
- 权限管理收敛为「角色 + 授权矩阵」两条主路径，形成单一操作流：
  建角色 → 授权矩阵给角色授 READ → 人员组织挂角色。

用户已拍板的取舍（AskUserQuestion，按序）：

1. 「管理委派」入口 → **彻底去除这个功能**。
2. 角色管理页功能码呈现 → **彻底去除**。
3. 谁能进管理后台/谁算唯一管理员判定 → **用 platformAdmin 布旗**。
4. 委派去除范围 → **前后端全删**。
5. platformAdmin 来源 → 原以为可沿用现布尔，核查发现它**派生**自 `sys_role_permission` 的
   `ptmcp:platform:scope`，故改为 → **加独立布尔列**。
6. 普通用户（非平台管理员）管理端能力 → **普通用户全禁管理**。
7. 本次后端鉴权+数据模型+前端的大改以什么载体推进 → **新建独立工作项（本项）**。

契约升级（相对既有边界）：

- [DEV-20261102-role-boundary](../../tasks/work-items/DEV-20261102-role-boundary.md) 锁定的
  「平台管理员 / 组织授权管理员 / 安全审计员 / 普通员工四角色并存」「委派、功能码继续承载管理」边界
  **在本工作项执行后被取代**：组织授权管理员与安全审计员不再作为独立管理角色由委派分配到人，
  委派与功能码管理链路去除，只保留唯一平台管理员。
- [DEV-20261104-enterprise-agent-foundation](../../tasks/work-items/DEV-20261104-enterprise-agent-foundation.md)
  的「管理与使用分离（仅管理员维护）」方向与本项一致，作为佐证；本项把它落到具体鉴权数据模型。

不变量（保留）：

- 授权计算语义保留「平台 ∩ 用户」交集，数据权限仍经授权矩阵求值。
- 角色→用户的授权继承已由评估链支持（下述「技术核查结论」），不改写评估算法。
- SQL guard、只读执行、审计、未知语法拒绝等底线不变。
- 账号供应（DEV-20261104-admin-identity-provisioning）与本职场不交叉，保持其 UNBOUND/READY 语义。
- 「不输出真实业务 SQL/结果」「只在任务需要的隔离环境验证」「保留已有修改、历史证据和私有资产边界」照旧。

## 技术核查结论（已核，开工即复用）

- `AdminActionInterceptor`：每个 `@AdminAction("ptmcp:xxx")` 方法硬要求
  `permissions.permissionsOf(userId).contains(action.value())`，**平台管理员当前不豁免**。这是核心改写点。
- `platformAdmin` 不是独立布尔：派生自 `sys_role_permission` 的 `ptmcp:platform:scope`
  （`AdminSessionService.PLATFORM_SCOPE_PERMISSION`，`OrgStore` 863-867 / 1222-1231 行）。必须先立独立布尔列。
- 角色数据继承**已可用、无需重建**：
  - `GrantExpander.case ROLE -> subject.enabledRoleIds().contains(grant.subjectId())`；
  - `AuthorizationReader.loadGrants` 拉 `("ROLE", roleIds)` 参与求值。
- `sys_role_permission`（V5 建）既是动作码源、又是平台管理员源——循环耦合，本次一并去除。
- `permissionsOf` 三处 commutes `sys_user_role JOIN sys_role JOIN sys_role_permission`：
  `DelegationStore`、`AdminSessionService.permissions()`、`AdminSessionService.platformAdmin()`。
- 委派面：`DelegationAdminController`、`DelegationStore` 委派方法、`DelegationScopeResolver`、
  `DepartmentScopeGate` / `UserScopeGate` / `AclScopeGate`、`admin_delegation` 表、`DELEGABLE_PREFIXES`。
- `MeView(/session/me)`：`userId, username, displayName, department, roles, permissions,
  managedScopes, platformAdmin, expiresAt`。前端权限过滤读此。

## 实施切片（每片独立验证、独立提交推送 `dev`）

> 本项为安全关键 + 数据模型 + 跨契约重构，改动含迁移与鉴权决策。每片单独形成可验证单元，
> 依次在本工作项追加「结果与证据」；不允许一揽子跨片提交。任一片动摇实现事实时回读本项并更新切片。

### S1 平台管理员独立布尔列（数据模型前置）

- 新增迁移（顺延现有 V 序号，先核 `ptmcp-persistence/src/main/resources/db/ptmcp-migration/` 当前最大编号），
  在 `sys_user_security`（V11 建，含 `auth_version`/`identity_state` 等权威状态列）增 `platform_admin BOOLEAN NOT NULL DEFAULT 0`。
- 独立于 `sys_role_permission`：平台管理员资格从 `ptmcp:platform:scope` 摘出，改读新列。
- `AdminSessionService.platformAdmin(userId)` 改查新列；`platformAdmin()` 原 JOIN 路径删除。
- 播种初始平台管理员 = 系统种子账号，置 `platform_admin=1`（沿用 <dev-bootstrap-missing-user-security> 记忆：
  播种须补安全状态行，此处同批补该列，避免 admin 登录后资格为 0）。
- 验收反例：普通用户 `platform_admin=0`，即使被手动塞了 `ptmcp:platform:scope` 也不再计为管理员；
  改列后旧会话重算资格。
- 验证：`SecuritySchemaMigrationTest` 全量迁移新编号通过；新增布尔列读写断言。

### S2 AdminActionInterceptor 全允/全拒二元化

- 平台管理员：任何 `@AdminAction` 直接放行（不再查功能码）。
- 普通用户（`platform_admin=0`）：所有 `@AdminAction` 拒绝（`MANAGEMENT_SCOPE_DENIED`），
  因普通用户不再有功能码。
- 校验：原 `permissions.permissionsOf(...)` 功能码路径在该拦截器内删除，改判 `platformAdmin`。
- 验收反例：普通用户调用任一管理端点 `403/拒绝`；平台管理员调用所有管理端点（含未显式给码的）放行。
- 验证：`AdminApiTest` 增普通用户全拒、管理员全允两用例。

### S3 去除 sys_role_permission 表 + 动作码链路

- 新迁移 DROP `sys_role_permission`（先清依赖引用，见 S4），并删除
  `AdminSessionService.permissions()` / `/session/me` 的 `permissions` 字段（前端不再消费）。
- `OrgStore` 移除 role-permission 写路径；`RoleAdminController` 的 `RoleWrite/RoleRead` 去 `permissionCodes`。
- `DelegationStore.permissionsOf` 删除（S4 整块删时可顺带）。
- 角色=纯数据模板确立：角色定义只保留 `key/name/enabled`（+ 并发 version，供成员归属分离写）。
- 验收反例：表不存在后建角色/改角色不再写权限码；`/session/me` 无 `permissions` 字段。
- 验证：全量迁移 DROP 通过；角色 CRUD API 测试更新为无权限码形态。

### S4 委派全删 + 控制面收敛到纯平台管理员

- 后端删：`DelegationAdminController`、`DelegationStore` 委派方法与 `permissionsOf`、
  `DelegationScopeResolver`、`DepartmentScopeGate`/`UserScopeGate`/`AclScopeGate`、
  `admin_delegation` 表（新迁移 DROP）、`DELEGABLE_PREFIXES`。
- `AclGrantAdminController` / `RoleAdminController` 的 `requireCatalogAccess`（现走
  `scopeResolver.effectiveDelegations`）改为纯 `platformAdmin` 判定。
- 审计遗留：仅审计员经委派的管理动作面随委派删除；审计数据的读取面是否保留取决于 PRD 口径（S6 对齐）。
- 验证：全量迁移 DROP `admin_delegation` 通过；其余管理受控入口均按纯平台管理员判定。

### S5 前端主路径化 + platformAdmin 过滤

- 隐藏/删除「管理委派」路由与导航入口（`DelegationPage`/nav 对应项）。
- 授权矩阵默认主体切到「角色」，弱化 USER/DEPT 主体为可用项或按 PRD 收入次级。
- 角色管理页只保留角色定义与成员归属，去功能码呈现（S3 契约落地）。
- 前端权限过滤改用 `platformAdmin`（`/session/me` 返回），不再依赖 `permissions` 集。
- 人员组织页挂角色即完成单操作流；页面引导文案：「建角色 → 矩阵给角色授 READ → 人员组织挂角色」。
- 验证：`pnpm --dir ptmcp-ui-react check/test`；预览服务截图核对桌面+移动两处去委派、矩阵默认角色、
  角色页无功能码。

### S6 PRD / API 契约 / 测试用例 / 文档同步

- 更新 PRD「管理侧与使用侧」与权限矩阵章节，落「单管理员 + 角色=纯数据模板」；
  同步总体方案、架构/权限、页面规格、用户说明。标注取代 role-boundary 旧四角色口径。
- 更新后端 `docs/backend/api-spec.md`（去委派、`/session/me` 去 `permissions`、角色去 `permissionCodes`）。
- 更新 `docs/test/test-case.md` 权限相关用例。
- 验证：`python scripts/check.py` 通过；文档断链不新增。

## 验收标准与命令

- 全量迁移串：`SecuritySchemaMigrationTest` 在空库执行到最新（含 S1/S3/S4 三个新 DROP/ALTER）通过。
- 后端：`./mvnw -pl ptmcp-admin -am -Dtest=AdminApiTest,IdentityPasswordServiceTest
  -Dsurefire.failIfNoSpecifiedTests=false test` + `ptmcp-persistence` 相关 Store/迁移测试。
- 前端：`pnpm --dir ptmcp-ui-react check`、`pnpm --dir ptmcp-ui-react test`。
- 仓库：`python scripts/check.py --all`（涉及构建先入项目环境）。

环境：Java 21、Maven 3.9.9、Testcontainers MySQL 8.4（WSL dockerd TLS）、Node/pnpm 同既有前端。

## 实施与验证

### S1 平台管理员独立布尔列（已实施）

- 迁移 `V18__platform_admin_independent_flag.sql`：`sys_user_security` 增
  `platform_admin TINYINT(1) NOT NULL DEFAULT 0` + `chk_user_security_platform` CHECK(0/1)，
  并从旧 `ptmcp:platform:scope` 角色码回填现存管理员（S3 删表前的过渡源仍在）。
- 判定源切换：`AdminSessionService.platformAdmin(userId)`/`me()` 改读新列（原角色 JOIN 删除）；
  `OrgStore.activePlatformAdmins` 改查列；`OrgStore.requireCurrentRoleActor` 起始
  `UserSecurityStore.isPlatformAdmin`（新增静态读列方法），保留动作码 `permitted` 检查为
  S1 过渡边界（S2 移除）。
- 测试夹具随语义重指向（列驱动后角色码不再授管理员）：
  - `SessionApiTest`：`seedPortal` 经 `makePlatformAdmin(ALICE)` 置列；原 4 个
    「角色变更收回范围」用例合并为列存续用例；`ScopeGrantedAfterLogin` 反转为
    「角色码不 elevate 非管理员」；scope 探测改改名 `sys_user_security`（新判定源）断言
    `SECURITY_STATE_UNAVAILABLE`；`assertSessionScope` 解耦 `/admin/roles` 代理断言（S1 中
    动作码门仍在，二者不再同源）。
  - `AdminApiTest`：`ADMIN_ID` 夹具置 `platform_admin=1`；`lastPlatformAdminCannotBeDisabledOrDeleted`
    第二管理员改由列授予；`roleCreateIdempotencyRetainsUnknown...[platform:scope]` 撤销改为
    「角色权限集重写 + 列翻转」双写（列翻转是判定源，权限集重写保写效应哈希变化）。
  - `OrgManagementStoreTest`：`seedRoleActor` 置 `platform_admin=1`；`NO_PLATFORM` 用例改清列；
    撤销竞态 `platform:scope` 分支改翻转列。
  - `BackendApiRegressionTest`：ADMIN 夹具置 `platform_admin=1`。
- 修复一处 S1 回归：`OrgStore.activePlatformAdmins` 改列后遗留 `ur.user_id` 别名引用
  （表已不在 JOIN 中）导致 `userCreateDuplicateLoginAndRoleAssignment` 抛
  `DEPENDENCY_UNAVAILABLE`，改为 `s.user_id`。

### S2 AdminActionInterceptor 全允/全拒二元化（已实施）

- `AdminActionInterceptor`：仅判 `AdminPrincipal.platformAdmin()`——管理员全放行，
  其余一律 `MANAGEMENT_SCOPE_DENIED`；构造函数去掉 `DelegationStore` 依赖，
  `AdminWebConfiguration` 同步收窄。
- `OrgStore.requireCurrentRoleActor`：删动作码查询，只判 `isPlatformAdmin`（列）；
  `action` 参数随用移除。管理员免码放行成立，S2 验收「含未显式给码端点」可达。
- `ManagementScope.requirePlatformAdminPrincipal(3 参)`（管理员 ∧ 能力码）折叠为平台管理员
  单判；`DatasourceAdminController` 8 处调用点与 7 个码常量删除。
- `AclMutationGuard.authorize`：`platformAdmin` 改由列判定，非管理员直接拒；`validate` 的
  委派范围分支（非管理员路径）随二元化死亡而移除；`policy()` 去 `action` 参数。
- `UserScopeGate.requireCommand`：删能力码前置检查，保留平台管理员早退与委派收窄（S4 删）。
- 测试随二元化重指向：
  - 删除（前提死于码分离/委派，行为已不可达）：`adminAction*` 码分离家族 7 个、
    委派行为家族 10 个（delegated* 与 ancestor-delegation）、
    `BackendApiRegressionTest.grantListMustRespectDelegatedResourceScope`。
  - 重写为列驱动：撤销/重检/幂等竞态全部改列翻转（含 AdminApiTest 两个幂等用例收敛为
    单测、SessionApiTest cookie 用例「删码仍放行」）；`DatasourceApiTest` 反转为
    「无码管理员放行」（CRUD 精确断言 + 作业端点 404 证门放行）。
  - 通用校验测试换管理员主体：genericUserUpdate* 7 个、specializedUser* 2 个、
    escalation 夹具置列；`assertSpecializedEditDenied` 独立走非管理员请求。
  - OrgManagementStoreTest：拒绝原因收敛为 8 个列/安全态原因；依赖失败行改
    `SELECT platform_admin`；撤销竞态只留列行；`RejectsBeforeReadingTarget` 改清列拒绝；
    删 `ActorOneHasNoBypass`（前提死）。
  - 新增 S2 验收两用例：普通用户全拒（6 端点 403）、管理员无码全允（4 面 200）。

## 结果与证据

### S1（2026-11-05）

- 全量迁移：`./mvnw -o -pl ptmcp-persistence test -Dtest=SecuritySchemaMigrationTest`
  → `Tests run: 7, Failures: 0, Errors: 0`（空库迁移至 V18，含新列与 CHECK 约束、回填）。
- persistence 全量：`./mvnw -o -pl ptmcp-persistence test`
  → `Tests run: 410, Failures: 0, Errors: 0`（含 OrgManagementStoreTest 列驱动重指向用例、
  SecurityStateTest 14 例、SecurityChangeTransactionTest 16 例）。
- admin 全量：`./mvnw -o -pl ptmcp-admin test`
  → `Tests run: 253, Failures: 0, Errors: 0`（含 SessionApiTest 列存续/反例/scope 失败断路、
  AdminApiTest 末位管理员列授予、撤销竞态列翻转）。
- 仓库门禁：`python scripts/check.py` → docs 证据 PASS（无断链）、agent-workflow PASS、
  quality-config PASS、verify-commits PASS、scripts unittests PASS。
- 基线对照：`platformAdminScopeLookupFailureRejectsBeforeManagementWrite` 在 HEAD 临时
  worktree 复跑为 PASS（旧实现经角色 JOIN 在会话解析期失败），确认 S1 语义变化后按新判定源
  重写用例；worktree 已清理。
- 评审类型：自审（本切片为单 Agent 实施；未声称独立评审）。
- 环境备注：Docker 经 WSL relay `tcp://127.0.0.1:12476`（mTLS 客户端证书置于
  `~/.docker-ptmcp`），`TESTCONTAINERS_HOST_OVERRIDE=<WSL VM IP>` 直连发布端口。

### S2（2026-11-05）

- admin 全量：`./mvnw -o -pl ptmcp-admin test` → `Tests run: 228, Failures: 0, Errors: 0`。
- persistence 全量：`./mvnw -o -pl ptmcp-persistence test` → `Tests run: 386, Failures: 0, Errors: 0`。
- 全仓 `test-compile` 通过；`python scripts/check.py` 五项门禁 PASS。
- 备注：本机 loopback 对 WSL 转发间歇抖动，两轮 suite 因 `Could not find a valid Docker
  environment` 整类报错，按既有协议重跑后全绿；代码无相关改动。
- 评审类型：自审（单 Agent 实施）。

## 交接

当前焦点：S3 删 `sys_role_permission` 表与动作码链路（`AdminSessionService.permissions()`、
`/session/me` 的 `permissions` 字段、`DelegationStore.permissionsOf`、角色 CRUD 的
`permissionCodes`、OrgStore role-permission 写路径、播种的 portal 角色码）。S2 已提交推送 `dev`。

已有授权与待决定事项：

- 实施与适用检查在长期授权内；功能/修复切片通过检查后提交并推送当前分支。
- 本项安全关键 + 破坏性 + 涉及数据模型，**每片提交前单独在切片内给出该片门禁证据**；不做一揽子提交。
- 发布/生产操作、跨分支操作未授权。
- 待确认口径（S6 收敛）：审计数据的读取面在委派删除后归属；授权矩阵 USER/DEPT 主体是保留为次级
  还是同样收窄到仅角色。默认按「USER/DEPT 保留为可用次级、入口默认角色」推进，与用户单操作流目标一致。