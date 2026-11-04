# 后端规则

适用于 `ptmcp-parent/`，与[根规范](../AGENTS.md)同时适用。模块有专属差异时，在对应子目录补充 `AGENTS.md`，只写差异。

构建前进入[项目环境](../docs/ops/development-environment.md)，使用本目录 Maven Wrapper；命令与模块现状见 [README](README.md)。格式和依赖门禁见[工程标准](../docs/standards/quality-tooling.md)，不在规则中缓存版本或测试数量。

## 按改动读取

| 改动 | 契约 |
| --- | --- |
| SQL 解析、绑定、改写、审批 | [SQL 守门](../docs/backend/sql-guard.md) |
| 会话、OAuth、JWT/PAT、平台证明 | [认证](../docs/backend/authentication.md) |
| ACL、委派、有效权限、撤销 | [权限](../docs/backend/authorization.md) |
| 连接池、元数据、执行、预算、取消 | [数据源与执行器](../docs/backend/datasource.md) |
| 审计准入、WAL、恢复、解密 | [审计](../docs/backend/audit.md) |
| Schema、仓储、事务、迁移 | [数据库设计](../docs/backend/db-design.md) |
| REST/OAuth/MCP 输入输出与错误 | [API 契约](../docs/backend/api-spec.md)、[接口变更合同](../docs/standards/api-change-template.md) |

涉及安全逻辑同时读[安全架构](../docs/architecture/security.md)与[测试计划](../docs/test/test-plan.md)；Web 鉴权、会话、日志自查按 [ASVS 清单](../docs/test/asvs-checklist.md)留证。

common/guard 保持纯 Java；gateway/authz 不引入若依或 Sa-Token。查询接线必须保持身份、权限、guard、审计与执行的合同顺序，不能提供旁路。真实数据库、客户端及故障边界按工作项验证，mock 或构建通过不能替代。
