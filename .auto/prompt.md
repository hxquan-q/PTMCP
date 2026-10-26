# Autoresearch: PTMCP 前端 UI 优化与前后端联调真实化

## Objective

企业级问数系统（PTMCP）持续完善，两条主线（用户 2026-10-26 指定）：

1. 持续不断优化前端 UI 界面。
2. 持续打磨前后端联调：确保前后端功能正确、真实实现，不是演示 demo。

核心断点：前端 `ptmcp-ui/src/services/controlPlane.ts` 期望一组 `/api/v1/admin` 端点，
后端 `ptmcp-parent/ptmcp-admin` 尚未实现，且前端用 `controlPlaneAvailable =
frontendPreview`（仅预览模式放行）把真实模式全部禁用。每个切片 = 实现一组真实端点 +
后端测试 + 前端解除该域禁用 + 契约测试，或一个 UI 优化切片。

## Metrics

- **Primary**: tests_passing（个，越高越好）— 前端契约测试 + 后端测试通过的用例总数。
  每个真实功能切片必须带测试，该数字只随真实功能/测试增长。
- **Secondary**: tests_failed（越低越好，必须为 0 才能 keep）、fe_tests_pass、
  be_tests_pass、iteration_focus（记录在 asi，不进数值）。
- 警惕过拟合：不为涨数字写无意义测试。测试必须覆盖真实契约（状态码、越权、并发、
  边界），评审时问「这个测试在防什么回归」。

## How to Run

`./.auto/measure.sh` — 输出 `METRIC tests_passing=N` 等行。
- 前端：`cd ptmcp-ui && node --experimental-strip-types --test tests/`（约 1s）
- 后端：`cd ptmcp-parent && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -pl ptmcp-admin -am test`
  （Testcontainers，需 Docker；镜像 digest 已拉取）
后端 Maven 输出尾部存 `.auto/last-be.log`。

`.auto/checks.sh` — `pnpm --dir ptmcp-ui check`（lint+prettier+vue-tsc+build）+
`python3 scripts/check.py`。检查失败不能 keep。

## Files in Scope

- `ptmcp-parent/ptmcp-admin/**` — 管理面控制器/服务/测试（委派、平台、客户端、Scope、
  ACL effective、审计解密）。
- `ptmcp-parent/ptmcp-persistence/**` — 新增 Store/仓储时（跟随切片，小步）。
- `ptmcp-ui/src/**` — 页面、服务层、契约测试（`ptmcp-ui/tests/`）。
- `tasks/work-items/T17-*.md` 等 — 切片交接记录（每切片更新）。
- `.auto/**` — 会话文件。

## Off Limits

- `Reference/`（选型参考，非产品源码）。
- 安全边界：SQL guard、权限交集模型、审计不可抵赖性——只能加强不能放宽。
- 不改测试阈值/删断言换通过；不把演示模式标成真实通过；不输出凭证。
- 不动 `config/dev/secrets/`；不提交真实业务数据。

## Constraints

- 后端 Java 21（JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64），Maven 3.9.9。
- 前端 Node 24 + pnpm（`pnpm --dir ptmcp-ui`）。
- 每切片：`measure.sh` 全绿（0 failed）+ `checks.sh` 通过才 keep；keep 后按仓库规范
  提交（中文 conventional commit），推送到 `xquan/feat-backend` 已获长期授权。
- API 契约以 `ptmcp-ui/src/services/controlPlane.ts`、`orgPortalApi.ts` 为准（前端已定
  形状）；后端实现不得随意改契约，发现契约与安全模型冲突时停下来报告。
- 权限门禁沿用 `ManagementScope.requirePlatformAdmin`；委派的细粒度作用域是后续切片，
  不在本会话伪造。
- Idempotency-Key / If-Match 语义与现有控制器一致（见 AclGrantAdminController、
  UserAdminController、DELEGATIONS 前端 `versioned()` 头）。

## Slices（联调线 S1–S6 已完成；现转入 UI 打磨线）

**UI 打磨切片池**（每轮一个可见改进，pnpm check + 契约测试护住，浏览器走查记录进工作项）：
- U1 委派列表管理列窄屏折行 + 状态列 EXPIRED 视觉区分。
- U2 客户端创建后的 secret 一次性弹窗（对照 PAT 模式：关闭后明文节点清空）。
- U3 Scope 差异确认框 diff 可读性（新增/修改/删除分色列出）。
- U4 审计解密倒计时可访问性（aria-live + 键盘可达）。
- U5 EffectiveAccessPane 空态文案与原因码解释。
- U6 平台表单 proof 配置「留空保持原值」提示一致性。

## 联调 Slices（已完成，留档）

1. **S1 委派**：`/api/v1/admin/delegations` GET/POST、DELETE `/{id}`（admin_delegation 表
   已有）。前端 DelegationsView 解除禁用 + 契约测试。
2. **S2 ACL effective**：`GET /api/v1/admin/acl/effective`，复用 preview 的求值服务。
   前端 EffectiveAccessPane 解除禁用。
3. **S3 平台 CRUD**：`/api/v1/admin/platforms` POST/PUT/DELETE + GET `/{id}`（含
   proof_config 加密，CredentialCipher 同款）。前端 PlatformDetailView/BasicsForm 解除。
4. **S4 平台 Scope**：`GET/PUT /platforms/{id}/scopes`，保存时 scope_version+1。
   前端 ScopeConfigurationPane 解除。
5. **S5 客户端**：`/platforms/{id}/clients` POST、`/clients/{id}` PUT/DELETE；一次性
   secret（只存 hash，创建响应带明文一次）。前端 ClientConfigurationForm 解除。
6. **S6 审计解密**：`POST /audit/{id}/decrypt`（reason 必填 + audit_sensitive_access 落库
   + 有效期），密钥与 gateway wal-key 同源（需配置对齐，先查 McpGatewayConfiguration）。
   前端 AuditDecryptDialog 解除。
7. **S7+ UI 打磨**：每轮一个可见改进（交互状态、空态/加载态、键盘可达性、窄屏、视觉
   细节），pnpm check + 契约测试护住，浏览器走查记录进工作项。

## What's Been Tried

- **S7 用户密码重置**（0972cb4）：跨库按序（元库 SecurityChangeTransaction 原子：epoch+事件
  +授权族撤销+profile 版本+操作日志 → 身份库 bcrypt 写入，失败封闭）；identity 数据源可选
  装配；Dev-Linux 自动补写 AUDITKEY/IDENTITY 配置（存量 dev.env 兼容）。
- **S8 org POST 幂等接线**（1d49dca）：departments/users/roles 创建端点接 IdempotentWrites，
  前端 orgPortalApi 补 Idempotency-Key。全部 admin 写端点契约门禁齐备。tests 372→471。
- **T16 委派作用域执行完成并端到端闭环**：T16-1 判定核（9da7437）、T16-2 departments
  （9f7fc28）、T16-3 users（51cbfe3）、T16-4 roles 只读放开（96232ca）、T16-5 ACL 四维门禁
  （49f72ba）、T16-6 受控管理员浏览器走查四项断言全过零缺陷、T16-7 AC-02 跨范围共享角色
  约束（3c9ba5c）。tests 372→726。
  教训：写端点委派化必须换 AdminPrincipal.require；测试 body 数字 id 不能字符串 replace
  用户名；测试窗口禁写死日期；admin 会话验证源=sys_user_profile.password_hash（身份库
  sys_user.password 服务 OAuth）；bash 传 bcrypt 用文件 docker cp（$ 展开截断）。
- **S9 gateway 审计密文写入**（210868f）：AuditSealer 同格式同派生+部署同钥配对；measure
  扩 admin+gateway 双 reactor，tests_passing 476→726（gateway 测试纳入度量）。
- **S1–S6 联调真实化全部完成**（提交 cc93bad→5e353c6）：
  S1 委派 CRUD+幂等+操作日志；S2 ACL effective 稳定分页；S3 平台 CRUD（proof_config 只写
  加密、security_version 乐观锁）；S4 平台 Scope 整组替换双版本自增；S5 客户端一次性
  secret（bcrypt+SHA-256 双哈希、改删撤授权族、V7 迁移）；S6 审计解密（独立密钥、reason
  门禁、敏感访问同事务落库）。前端六域 `*ApiReady` 全解禁，预览模式合成交互保留。
- **教训**：AdminApiTest 清库后 POST /users 从 id=1 重编（撞 shim 主体，测试需占位用户）；
  platform_registry 无 version 列（乐观锁=security_version）；MySQL JSON 列读回带空格；
  jsonPathCursor 只适用 nextCursor；测试 INSERT 字符串禁裸 ?；调试 printStackTrace 用后即删。
- **未做/移交 .auto/ideas.md**：gateway 密文写入（encryptedRaw 恒 null）、委派作用域执行
  （T16）、org POST 幂等接线、MySQL 8.4 复验、浏览器端到端、UI 打磨池。
- 之前（非本会话）已落地：审计 list/detail、平台只读 list、ACL grants CRUD+preview、
  用户/部门/角色 CRUD、会话+CSRF、me 全套只读端点。

## 环境备忘

- Docker 可用；测试镜像 `mysql@sha256:76f0358...` 首拉慢，已后台重试直到成功。
- 后端测试 Testcontainers 需 Docker daemon；传输层瞬断重试已内建于测试夹具。
- Vite 代理：5173 → `/backend/admin`→8080、`/backend/authz`→8081、`/backend/gateway`→8082。
- 本机 JDK 默认 17，务必显式 `JAVA_HOME=java-21`。
