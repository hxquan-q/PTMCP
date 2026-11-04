# gateway 模块规则

本模块同时遵循[后端规则](../AGENTS.md)；MCP 线格式见 [API](../../docs/backend/api-spec.md)与[工具 Schema](../../docs/backend/mcp-tools.json)，身份和审计分别见[认证](../../docs/backend/authentication.md)、[审计](../../docs/backend/audit.md)。

- 传输方法、版本与 Origin 检查保持在工具分派之前；MCP 不套管理 REST 外壳，也不接受控制台 Cookie 代替 MCP 身份。
- 查询接线保留 START 持久准入、完整 guard、借连接前权威复核和受控执行；任何拒绝都不能退回直接执行原 SQL，不能把审批成功当作跳过审计的依据。
- TERMINAL 失败恢复依审计合同处理，不能捕获后仅记日志并报告完整成功；取消与客户端断开也须保留终结及连接清理语义。

按改动选[传输、MySQL/PG 查询和审计韧性测试](src/test/java/local/ptmcp/gateway)，验证拒绝没有借业务连接；真实客户端、恢复和生产验收分别留证。
