# T17-api-c：管理端审计与平台只读目录接口

ID: T17-api-c
Status: verifying
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-10-26

## 目标与边界

来源/父任务：T17 父任务；接续 [T17-api-b](T17-api-b.md) 后前端仍缺的管理端只读端点。
契约：[API 规格](../../docs/backend/api-spec.md) §7/§10（`GET /audit`、`GET /audit/{requestId}`，
平台目录取自 `/platforms` 管理面）、[审计设计](../../docs/backend/audit.md)、
[权限设计](../../docs/backend/authorization.md)、[页面规格](../../docs/frontend/page-spec.md) P-09/P-11。

目标：为管理审计页和平台页提供真实、只读、平台管理员限定的 REST 投影：

- `GET /api/v1/admin/audit`：31 天窗口、userId/platformId/datasourceId/tool/result/verdict/
  errorCode 过滤、cursor/pageSize、时间倒序；返回字符串 ID 与脱敏 SQL。
- `GET /api/v1/admin/audit/{requestId}`：脱敏详情、对象/版本、阶段耗时、错误/截断与 SQL 指纹；
  不返回 raw/final 密文、key id 或明文。
- `GET /api/v1/admin/platforms`：平台目录列表，支持 keyword/status/cursor/pageSize；只报告
  proof 是否配置，不返回 proof 配置。

不做项：平台创建/编辑/删除/详情、Scope/客户端端点；审计 `POST /audit/{id}/decrypt`；
原始 SQL/密文读取；前端页面改动；身份框架替换。以上能力继续由前端正常模式显式禁用。

不变量：所有端点先经 `ManagementScope.requirePlatformAdmin`；审计查询与个人 history 共用
31 天窗口和分页上限；ID 以字符串跨边界；未知/非法 requestId 统一 404；过滤非法输入 400；
分页稳定且 `nextCursor` 只在还有数据时出现；不把管理身份当作业务 READ 授权。

已有修改：开工时工作树中的 T17 UI、T19-a gateway 与既有 T16 改动全部保留；本片只新增/修改
admin 审计与平台只读相关文件及其测试。

## 实施与验证

- `local.ptmcp.admin.audit.AuditQueryService`：把个人 history 查询从 `MeService` 抽成共享读模型，
  同时提供管理端 `AdminAuditPage/AdminAuditDetail`；SQL 只投影 `sql_masked`/`sql_fingerprint`。
- `local.ptmcp.admin.api.AuditAdminController`：平台管理员限定，支持契约中的过滤/游标/31 天窗口。
- `local.ptmcp.admin.platform.PlatformDirectoryService` + `PlatformAdminController`：只读目录、
  LIKE 转义、状态白名单、id 游标；明确不提供写路径。
- `AdminStoresConfiguration` 装配两个服务；`MeController`/`MeService` 改为委托共享查询服务，
  保持个人 history 的既有响应形状与所有权过滤。
- `AdminApiTest` 新增 3 例：审计列表过滤/游标/范围、审计详情脱敏与 404、平台目录只读分页/过滤。

验收命令（Enter-Dev，真实 MySQL Testcontainers，`TESTCONTAINERS_HOST_OVERRIDE` 直连 WSL 容器）：

```text
mvnw.cmd -B -ntp -pl ptmcp-admin -am verify
```

## 结果与证据

2026-10-26 实际运行：

| 检查 | 结果 |
| --- | --- |
| `ptmcp-admin -am verify` | PASS，BUILD SUCCESS；common 210、persistence 85、datasource 31、admin 45 例，0 失败/错误/跳过 |
| `AdminApiTest` | 8/8 PASS（新增审计列表、审计详情、平台目录 3 例） |
| 审计列表 | 覆盖平台管理员限定、userId/tool/result/verdict/errorCode/platformId/datasourceId 过滤、页码游标、31 天窗口与非法参数 |
| 审计详情 | 覆盖脱敏 `sqlMasked`、SQL 指纹、`sqlRawCipher`/`sqlFinalCipher`/`keyId` 不存在、未知/非法 requestId 404 |
| 平台目录 | 覆盖关键字/状态过滤、id 游标、proof 配置只报告布尔、无写端点 |

未执行项：审计解密端点、平台 CRUD/详情/Scope/客户端、审计原文读取、真实前端网络联调、
独立安全评审。审计涉及敏感读取，当前仅为自审候选，状态保持 `verifying`。

评审类型：自审（范围校验、脱敏投影、游标/窗口边界、ID 字符串契约、LIKE 转义）；无独立安全评审。

## 交接

当前焦点：只读审计/平台接口已提交候选，前端可切换到真实端点；写操作与解密仍禁用。

剩余项：按 API 规格实现平台 CRUD/Scope/客户端、审计 decrypt 与理由/到期清理；补真实前端
网络证据与独立安全评审。父任务 T17 与 T16 的完整验收不由本片代替。

Git：本片随工作树四线整理提交；不推送、不发布。用户功能接受 pending。
