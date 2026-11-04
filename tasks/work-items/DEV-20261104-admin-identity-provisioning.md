# DEV-20261104-admin-identity-provisioning：管理员签发首个凭证

ID: DEV-20261104-admin-identity-provisioning
Status: verifying
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-11-04

## 目标与边界

来源：用户要求外部 Agent 首次接入通过 OAuth 浏览器登录，账密只能由管理员创建和管理，
不开放注册，并且只能访问授权范围内的数据。核查发现新建用户为 `UNBOUND`，而现有改密接口
要求 `READY` 且宿主账号已存在，管理员无法为新用户建立首个凭证。

Given 管理员新建了一个 `UNBOUND` 用户且宿主不存在该身份，When 管理员通过
`PUT /api/v1/admin/users/{id}/password` 设置首个密码，Then 系统创建宿主账号、把
`identityState` 收敛为 `READY`，用户可凭该账密走 AS 登录。

不变量：

- 不接管既有宿主账号：同 ID 或同规范登录名（忽略大小写与首尾空白）已存在时，在任何宿主写之前以
  `422 RESOURCE_AMBIGUOUS` 拒绝。
- 意图先行：元库先提交不可变 `identity_provision`（PENDING，保存目标 BCrypt），宿主确认后才转 READY。
- 失败可恢复：宿主失败保持 UNBOUND，`retry` 只使用已持久目标，不接受新密码；请求不明按幂等收敛；
  PENDING 期间资料/角色/状态/删除写入返回 409，防止 profile version 脱离意图锚点。
- 重复完成不追加 epoch/事件：并发的已完成操作回滚后按同一 operation 核对成功。
- 既有 `READY` 改密协议不变，仍走 `identity_password_change`。

兼容/安全边界：沿用 [ADR-0011](../../docs/architecture/adr/0011-跨库身份变更与权威版本.md)；
不开放注册与 DCR/CIMD，不自动导入宿主历史账号，不改宿主密码策略，不新增前端恢复入口。

已有修改和所有权：本切片独占 `ptmcp-persistence` 安全存储、`IdentityPasswordService`、
对应测试、V17 迁移及相关契约文档；未触碰其他并行工作文件。

## 实施与验证

依赖与读写路径：元库 `sys_user_profile` / `sys_user_security` / `identity_provision` 与宿主
`sys_user`；元库先提交，宿主持久确认后再完成元库状态。锁序保持全局安全行 → 用户行 → operation 行。

当前切片：管理员首次发证。失败基线为 `UNBOUND` 用户调用重置返回错误且无法生成宿主凭证。

改动：

- `V17__identity_provision.sql`：新表及状态/材料/版本约束（含 `requested_by>0` 与目标 hash 长度）、
  `UNIQUE(user_id)`、FK 到 `sys_user_security`。
- `IdentityProvisions`：`begin` / `pending` / `findIfPresent` / `complete`；全局→用户锁序、安全事件、
  epoch 与 `USER_CREDENTIAL_ISSUED` 操作日志。
- `HostIdentityStore`：`provision` 精确匹配幂等创建宿主行；`byLoginName` 按
  `LOWER(TRIM(user_name))` 探测同规范登录名；唯一键冲突映射 422。
- `UserSecurityStore.displayName` / `requireActiveProfile`：只读宿主显示名与宿主写前状态复核，
  绑定仍用规范化登录名。
- `OrgStore`：存在 PENDING 供应时，用户资料/角色/状态/删除写入返回 409。
- `IdentityPasswordService`：UNBOUND 走供应；已有 PENDING 时 `reset` 返回 409 指向 `retry`；
  宿主写前复核元库状态；目标用户状态类错误不再以 401 冒充调用者会话失效。
- `UsersPage.tsx`：入口文案改为“设置/重置密码”，说明账密仅由管理员创建和管理。

回滚/收窄：应用可回退到上一版本；V17 只新增表，不修改既有表。若需停用供应入口，可回退
`IdentityPasswordService` 的 UNBOUND 分支，`identity_provision` 保留为无写入的墓碑表。
存在 PENDING 行时不得删除该表：目标 BCrypt 是唯一恢复材料，删表会使恢复永久丢失。

验收标准与命令：

- `./mvnw -pl ptmcp-admin -am -Dtest=AdminApiTest,IdentityPasswordServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `./mvnw -pl ptmcp-persistence -Dtest=OrgManagementStoreTest,SecuritySchemaMigrationTest,SecurityStateTest,SecurityChangeTransactionTest,UnboundIdentityMigrationTest test`
- `python scripts/check.py --all`

环境：Java 21、Maven 3.9.9、Testcontainers MySQL 8.4（WSL dockerd TLS）。

## 结果与证据

2026-11-04 定向证据：

- `IdentityPasswordServiceTest`（真实 MySQL）覆盖首次发证、宿主失败后保持 UNBOUND 并用持久目标恢复、
  宿主提交确认丢失、元库完成确认丢失的幂等重试、同 ID/同名宿主账号拒绝、
  重复完成不追加 epoch/事件；新增供应 PENDING 期间编辑/删除被拒后仍可恢复、大小写与首尾空白
  变体宿主名冲突、非 ACTIVE 目标返回 409、签发审计断言。
- `AdminApiTest#adminSetsTheFirstPasswordForANewUser` 通过真实 HTTP 验证新建 UNBOUND 用户 →
  设置密码 → READY、宿主 BCrypt 可验证、`identity_provision` 仅留 COMPLETED 墓碑；
  `adminFirstCredentialNeverAdoptsACaseVariantHostAccount` 验证大小写/空白变体冲突返回 422 且不落意图。
- `OrgManagementStoreTest`（295 项）验证 PENDING 供应期间的编辑守卫不破坏既有用户管理路径。
- `SecuritySchemaMigrationTest` 在空库执行全部迁移（含 V17）通过；相关
  `SecurityStateTest`、`SecurityChangeTransactionTest`、`UnboundIdentityMigrationTest` 通过。
- `python scripts/check.py` 仓库检查通过。
- 2026-11-04 全量门禁 `python scripts/check.py --all --maven-cache-seed D:/config/ptmcp/cache/maven`
  （Java 21、Maven 3.9.9、Testcontainers MySQL 8.4、pnpm）通过：Maven `verify` 全模块
  BUILD SUCCESS，质量探针按预期拒绝违规夹具，前端 check/test 通过，最终
  `PASS: repository checks, backend verification/quality probes and frontend checks/contracts.`

未执行项及原因：真实外部 Agent 的 OAuth 首登端到端属 T19 真实客户端矩阵，本切片未运行；
管理端 UI 恢复入口未实现（见残余）。

2026-11-04 独立只读评审（子 Agent，非自审）：0 Critical / 2 High / 3 Medium / 4 Low。已修复：

- H1：PENDING 供应期间资料/角色/状态/删除写入会推进 profile version，使 `retry` 永久不可达。
  `OrgStore` 在全局→用户锁序内检查 PENDING 供应并返回 409；新增“编辑被拒后仍可恢复”回归测试。
- H2：宿主写前不复核元库状态。`finishProvision` 先核 UNBOUND、auth_version 与目标 ACTIVE，
  不满足则不落宿主写；新增禁用目标用例。
- M1：目标用户状态错误复用 TOKEN_REVOKED→401，前端会误判管理员会话过期。管理端点映射为 409。
- M2：宿主登录名大小写/首尾空白变体漏判。改为 `LOWER(TRIM(user_name))` 比较，测试宿主表改用
  与 dev 一致的 `utf8mb4_0900_bin`，并新增变体冲突用例。
- M3：首发票据无操作日志。`begin` 同事务写 `USER_CREDENTIAL_ISSUED`，测试断言存在。
- L3：宿主唯一键冲突映射为 422 RESOURCE_AMBIGUOUS。L4：V17 增加 `requested_by>0` 与目标 hash
  长度约束，并记录“存在 PENDING 行时不得删表”的回滚警示。

评审发现保留项：L1 在 H1 修复后受支持编辑路径全部 409，窗口关闭；L2 预检与 begin 之间的外部宿主
写入、以及“宿主已提交但元库无法完成”的清理仍无自动放弃路径，保持 fail-closed 并列为残余。

人工接受：pending，需用户在真实环境确认管理员建户与首登流程。

残余：

- `UsersPage.tsx` 尚无调用 `POST /users/{id}/password/retry` 的恢复入口，供应失败或
  `CHANGE_PENDING` 仍需管理员直接调用接口。
- 外部客户端首次 OAuth 登录的真实版本矩阵仍为 `NOT_RUN`，不能由本切片的 HTTP 测试推定。
- 供应预检与 `begin` 之间的外部宿主写入，以及“宿主已提交但元库无法完成”的残留活动账号，
  没有应用内放弃/清理路径；需运维核对后手工处置，不能自动接管或删除。
- 真实宿主 `sys_user` 若存在额外 NOT NULL 无默认列，`provision` 的六列插入会失败；
  部署前需按实际宿主表形状检查，dev/test 表形状不具代表性。

## 交接

当前焦点：修复后重跑定向测试与全量门禁，然后提交/推送当前工作分支。

剩余项与下一步：补充 UI 恢复入口（另行切片）；T19 真实客户端首登验收；网关整体人工验收；
部署前核对真实宿主表形状与 PENDING 残留的运维处置流程。

实际 git status / 提交：当前工作树含本切片源码、测试、V17、文档与工作项；
未跟踪的 `research/`、`tasks/wayfinder/`、`docs/reference/enterprise-agent-foundation-20261104.md`、
`tasks/work-items/DEV-20261104-enterprise-agent-foundation.md` 不属于本切片，不纳入提交。

已有授权与待决定事项：实施与适用检查在长期授权内；提交/推送当前分支在通过检查后进行。
安全关键发布、生产操作未授权。
