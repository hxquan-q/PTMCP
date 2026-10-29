# DEV-20261030-schema-context：授权字典上下文

ID: DEV-20261030-schema-context
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: NOT_RUN
Updated: 2026-10-30

## 目标与边界

承接[网关收尾 G6](DEV-20261028-gateway-completion.md)与[T15-c S7](T15-c.md)，
落实 FR-05、get_table_schema 批量授权及不可信文本边界。模型可读取已维护的表/列业务字典，
工具说明与现有参数一致；不新增工具、resources、数据库迁移或独立 NL2SQL 服务。

不变量：先整批 READ 授权，再取指定对象字典；平台与用户权限交集、Deny 优先、当前快照绑定；
无 READ 不返回字段/字典；关系目标仍须 READ。元数据调用不使用业务连接或执行器。
物理注释和业务说明分开，均作为不可信数据；单字段和整批文本有 UTF-8 预算，完整响应继续受 1MiB 硬限制。

已有 AuditRecoveryWorker/McpAuditWriter 与 React 变更归另一窗口，不编辑、不纳入本切片。

## 实施与验证

当前 Agent 串行独占 PublishedSnapshotReader、McpToolService、上下文预算辅助类及对应测试、API 合同。
沿用[收尾提案](../../.agents/notes/proposed/architecture/2026-10-28-gateway-capability-hardening.md)
的 G6 决定，不创建平行架构笔记。先建立真实 HTTP/MySQL RED，随后实现并跑双方言相关组合。
使用独立 WSL 测试副本，避免与其他窗口共享 target；隔离 Testcontainers，不连接开发业务库。
安全关键 diff 完成后依工作流委派只读独立评审，不委派实现。

验收：三工具非空准确说明；表/列字典带来源/版本；旧列字典不可见；混合批次和撤权拒绝；
恶意注释仅在数据字段；Unicode 不被截断为非法字符；文本预算与全包预算同时生效。
检查入口为定向 Maven test/verify、python scripts/check.py 与 git diff --check。

## 结果与证据

尚未运行本切片测试，不沿用前轮结果。人工接受、真实客户端与部署联调待验证。

## 交接

本切片本地实施已获用户授权。安全关键合入/推送仍受单独授权边界约束；准备可评审候选后报告。
不将 G6 局部结果写成整个网关完成，不纳入其他窗口 diff。
