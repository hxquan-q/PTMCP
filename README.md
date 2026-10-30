# PTMCP / Datachat-mcp

权限管控型问数 MCP 安全网关。外部 Agent 负责生成 SQL，PTMCP 负责身份与平台权限、元数据披露、SQL 静态守门、只读执行和审计。

正式工程位于项目根目录：后端已有管理 API、OAuth 和 MCP 查询相关实现，React 前端正在接管；具体能力与差距见[能力核查](docs/evidence/mcp-capability-audit-20261030.md)和[工作项](tasks/README.md)。存在实现不代表真实客户端、完整业务链路、安全验收或发布已通过。

| 工程 | 入口 | 技术栈 |
| --- | --- | --- |
| 后端 ptmcp-parent | [POM](ptmcp-parent/pom.xml) / [启动说明](ptmcp-parent/README.md) | Java 21、Spring Boot、Maven Wrapper；包含 common、guard、persistence、datasource、admin、authz、gateway |
| 前端 ptmcp-ui-react | [package.json](ptmcp-ui-react/package.json) / [启动说明](ptmcp-ui-react/README.md) | React、TypeScript、Vite、Tailwind CSS；版本以 package 和锁文件为准 |
| 本机工具和基础服务 | [环境指南](docs/ops/development-environment.md) | D:/config；数据库/Redis与专用WSL环境 |

## 开发与验证

先按[开发环境指南](docs/ops/development-environment.md)选择工具。Windows 下从仓库根执行 `. .\scripts\Enter-Dev.ps1`；后端构建和启动步骤见[后端说明](ptmcp-parent/README.md)。前端在 `ptmcp-ui-react` 目录执行 `pnpm dev`（真实后端代理）或 `pnpm dev:preview`（合成数据）；`pnpm check` 和 `pnpm test` 分别检查构建质量与前端测试，不能替代真实后端联调。`Reference/` 是选型参考，不是本项目运行入口。

- [文档导航](docs/README.md)
- [项目总体方案](docs/总体方案.md)
- [产品需求](docs/product/PRD.md)
- [整体架构](docs/architecture/architecture.md)
- [实施路线](docs/architecture/roadmap.md)
- [测试报告（2026-10-26 历史快照）](docs/test/test-report.md)
- [最新能力核查与验证缺口](docs/evidence/mcp-capability-audit-20261030.md)
- [任务、工作项与进度](tasks/README.md)

Agent 开发从 [AGENTS.md](AGENTS.md) 开始，执行方式见[开发流程](docs/standards/agent-workflow.md)，任务与续做示例见[任务入口](tasks/README.md)。根目录 `python scripts/check.py` 检查文档、流程、配置及检查器回归。`scripts/check.py --all`、GitHub CI 与 Linux 脚本的 React 入口已同步为 `ptmcp-ui-react` 的 `pnpm check` + `pnpm test`；本机 Windows 以 Node 24.22.1 / pnpm 11.9.0 实测两者退出码 0，但 GitHub Actions 与 Linux 脚本尚未在本机实际运行，不能据此声称跨平台门禁已通过。后端与真实客户端联调仍按[后端说明](ptmcp-parent/README.md)和[React 说明](ptmcp-ui-react/README.md)分别执行。

生产部署产物、真实客户端联调及完整业务验收仍有缺口；历史解析器复现和当前文档检查不等于全系统已通过测试。
