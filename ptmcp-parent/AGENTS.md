# 后端规则

适用于 `ptmcp-parent/`，与[根规范](../AGENTS.md)同时适用。模块有专属差异时，在对应子目录补充 `AGENTS.md`，只写差异。

构建前进入[项目环境](../docs/ops/development-environment.md)，使用本目录 Maven Wrapper；命令与模块现状见 [README](README.md)。格式和依赖门禁见[工程标准](../docs/standards/quality-tooling.md)，不在规则中缓存版本或测试数量。

## 按改动读取

| 改动 | 契约 |
| --- | --- |
| 公共资源身份、错误、权限值对象 | [common](ptmcp-common/AGENTS.md) |
| SQL 解析、绑定、改写、审批 | [guard](ptmcp-guard/AGENTS.md) |
| 连接、元数据、执行、预算、取消 | [datasource](ptmcp-datasource/AGENTS.md) |
| Schema、仓储、安全事务、WAL | [persistence](ptmcp-persistence/AGENTS.md) |
| 管理/个人 REST、会话、委派、数据源管理 | [admin](ptmcp-admin/AGENTS.md) |
| OAuth、刷新、撤销、客户端注册 | [authz](ptmcp-authz/AGENTS.md) |
| MCP 传输、工具、查询与审计接线 | [gateway](ptmcp-gateway/AGENTS.md) |

涉及安全逻辑同时读[安全架构](../docs/architecture/security.md)与[测试计划](../docs/test/test-plan.md)；Web 鉴权、会话、日志自查按 [ASVS 清单](../docs/test/asvs-checklist.md)留证。

common/guard 保持纯 Java；gateway/authz 不引入若依或 Sa-Token。查询接线必须保持身份、权限、guard、审计与执行的合同顺序，不能提供旁路。真实数据库、客户端及故障边界按工作项验证，mock 或构建通过不能替代。
