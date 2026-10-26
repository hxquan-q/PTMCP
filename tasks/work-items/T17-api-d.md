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

下一步：S2 ACL `GET /acl/effective`（复用 preview 求值服务，前端 EffectiveAccessPane 解禁）；
随后 S3 平台 CRUD、S4 平台 Scope、S5 客户端一次性 secret、S6 审计解密。域级就绪标志模式
（`*ApiReady`）在后续切片复用。
