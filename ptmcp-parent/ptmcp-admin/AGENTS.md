# admin 模块规则

本模块同时遵循[后端规则](../AGENTS.md)；改动按 [API](../../docs/backend/api-spec.md)、[认证](../../docs/backend/authentication.md)、[权限](../../docs/backend/authorization.md)合同定位。

- 管理 Cookie、MCP 身份与 OAuth 会话属于不同安全域；功能权限、委派范围和本人资源归属都须在服务端判断，不能信任前端视角或传入 userId。
- 版本化写操作和幂等动作分别遵循 If-Match、请求键及 UNKNOWN 合同；幂等重放仍须鉴权，未知结局不重新占位执行，秘密不随成功回放再次返回。
- 数据源测试/同步复用 datasource 的物理连接策略；元库事务内不建业务连接。接口变化按[变更合同](../../docs/standards/api-change-template.md)同步调用方，不顺带重做会话或响应层。

鉴权/会话/日志变更按 [ASVS](../../docs/test/asvs-checklist.md)及[HTTP 边界测试](src/test/java/local/ptmcp/admin)留证，服务层测试不替代 CSRF、功能权限和错误脱敏验证。
