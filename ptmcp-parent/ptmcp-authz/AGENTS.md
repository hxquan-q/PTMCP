# authz 模块规则

本模块同时遵循[后端规则](../AGENTS.md)；授权、刷新、撤销和注册以[认证合同](../../docs/backend/authentication.md)为准。

- PUBLIC/NONE 的 refresh/revoke 使用合同规定的完整扩展链；公开 clientId 不能替代 token/family 绑定验证，也不能使 CONFIDENTIAL 客户端降级认证。
- refresh 消费、后继生成与重放撤族保持单一事务；确认提交后才响应。响应丢失或提交未知不通过宽限窗口、缓存明文响应或自动重试规避严格轮换。
- OAuth 发现、token 与错误遵循协议格式，不套管理 REST 外壳；控制台 Cookie 不作为 AS 会话。DCR/CIMD 网络读取保持受控目的地、重定向与资源预算边界。

验证从[本模块测试](src/test/java/local/ptmcp/authz)选择客户端绑定、并发消费/重放及 SSRF 反例；真实客户端扩展链的验收按工作项另取，不能由单个 provider 测试推导。
