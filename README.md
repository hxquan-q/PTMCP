# PTMCP / Datachat-mcp

权限管控型问数 MCP 安全网关。外部 Agent 负责生成 SQL，PTMCP 负责身份与平台权限、元数据披露、SQL 静态守门、只读执行和审计。

正式工程位于项目根目录，后端正按[工作项](tasks/README.md)分片实施。已交付资源/错误基础契约与验证门禁，元库迁移和SQL守门按依赖推进；登录、完整权限/SQL查询链路与业务页面尚未完成，不把基础构建视为业务验收。

| 工程 | 入口 | 技术栈 |
| --- | --- | --- |
| 后端 ptmcp-parent | [POM](ptmcp-parent/pom.xml) / [启动说明](ptmcp-parent/README.md) | Java21、Spring Boot4.1.2、Maven Wrapper；common/guard/persistence/admin/authz/gateway |
| 前端 ptmcp-ui | [package.json](ptmcp-ui/package.json) / [启动说明](ptmcp-ui/README.md) | Vue3、Element Plus、Iconify、Tailwind CSS、Animate.css、VueUse Motion |
| 本机工具和基础服务 | [环境指南](docs/ops/development-environment.md) | D:/config；数据库/Redis与专用WSL环境 |

前端在ptmcp-ui执行 `pnpm dev`，后端在ptmcp-parent按说明执行Wrapper。构建前先从根目录加载 `. .\scripts\Enter-Dev.ps1`。Reference为选型参考，不是本项目运行入口。

- [文档导航](docs/README.md)
- [项目总体方案](docs/总体方案.md)
- [产品需求](docs/product/PRD.md)
- [整体架构](docs/architecture/architecture.md)
- [实施路线](docs/architecture/roadmap.md)
- [当前验证状态](docs/test/test-report.md)

Agent 开发从 [AGENTS.md](AGENTS.md) 开始，执行方式见[开发流程](docs/standards/agent-workflow.md)，任务与续做示例见[tasks](tasks/README.md)。根目录 `python scripts/check.py` 检查结构/流程；进入环境后 `python scripts/check.py --all` 另执行实际后端verify与前端check。

业务功能、部署产物与客户端联调尚待实施；历史解析器复现和当前文档检查不等于全系统已通过测试。
