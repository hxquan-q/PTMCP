# common 模块规则

本模块同时遵循[后端规则](../AGENTS.md)。

- 资源身份变更先读 [SQL 守门的资源合同](../../docs/backend/sql-guard.md)；内部 ID、逻辑名称、数据库物理名称与展示名不能互换，名称比较不统一做 trim/大小写折叠。
- 稳定错误码只编辑[错误登记](../../config/quality/rest-error-codes.json)，在仓库根运行 `python scripts/generate_error_codes.py`；生成枚举不手改。领域错误与 REST/OAuth/MCP 线格式按 [API 契约](../../docs/backend/api-spec.md)分层，不直接序列化 Throwable。
- 权限值对象与判定变更先读[权限合同](../../docs/backend/authorization.md)；管理功能权限与业务数据权限分别判断，不互相替代。

定向验证使用[本模块测试](src/test/java/local/ptmcp/common)，按改动覆盖身份保真、协议隔离或权限交集反例。
