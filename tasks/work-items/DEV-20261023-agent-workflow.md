# DEV-20261023-agent-workflow：Agent 主导研发规范

ID: DEV-20261023-agent-workflow
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-23

## 目标与边界

来源：用户要求参考 DSH 文章、write-notes-like-deepseek 与 open-forge，优化当前开发规范和范式。交付仓库级规则、执行流程、决策记忆、任务交接和可执行检查。内部工程规范按下列验收完成，用户未作产品功能验收；不启动 T01～T20 的业务实现，不修改用户级配置或参考项目，不提交、推送或发布。

已有工作区：README.md 已修改，.gitignore、docs/、tasks/ 未跟踪；均为本轮开始前存在的用户工作。现有文档校验基线 PASS：43 篇 Markdown、187 个本地链接、3 个 MCP 工具、20 个 FR、82 个 TC。

## 实施与验证

执行方式：单 Agent 串行；本轮共享规则和检查入口强耦合，无并行写入。读路径：现有 docs/tasks、open-forge 规则与所给外部资料。写路径：AGENTS.md、CLAUDE.md、docs/standards、docs/reference/agent-development-research.md、docs/evidence、tasks 工作项/模板/导航、scripts、.github、根 README/.gitignore 的最小补充。

1. 规则与流程：保留现有业务基线、ADR 和任务 ID，建立分层导航及任务合同。
2. 检查机制：先写失败用例，再实现零第三方依赖的规则/笔记/任务校验，接入本地统一入口和 CI 文件。
3. 收尾：核对链接、规则冲突、反例与恢复场景，记录自审和未验证边界。

验收：新 Agent 从根入口能定位目标约束与现有计划；小改有轻路径；安全变更有反例与评审要求；未实现设计不会进入 implemented；伪完成任务与损坏笔记能被检查拒绝；一次命令完成现阶段可运行的检查。

命令：`python scripts/check.py`；新增检查器的 RED/GREEN 记录在完成段补记。应用构建/数据库/真实客户端测试不适用当前工程规范变更。

## 结果与证据

已交付：根 AGENTS 与 CLAUDE 桥接、开发流程、决策笔记政策、工作项/模板/示例、参考调查、第一篇过程笔记、统一标准库检查器、CI 文件及 PR 模板。同步了既有代码/Git 规范和导航；T01～T20 与 9 份设计 ADR 保留待实现。

验证记录（2026-10-23）：

- RED：先新增工作流检查用例，运行 `python -m unittest discover -s scripts/tests -v` 退出 1，缺少尚未实现的 `verify_agent_workflow` 模块；未把导入失败冒称为业务反例复现。
- GREEN：实现检查器后，15 个工作流用例通过；扩展 ADR 演进门禁后，共 18 个回归通过，退出 0。临时 Git 夹具隔离用户环境与 hooks，未在当前仓库提交。
- `python scripts/check.py`：文档、工作合同、回归全部通过，退出 0；统一入口在最终文档落盘后再次核对，最终结构结果见[验证记录](../../docs/evidence/documentation-validation.md)。
- CI YAML 已用本机 PyYAML 解析；PyYAML 不属于项目检查依赖。`git diff --check` 无错误。原有产品契约仍为 3 个工具、20 个 FR、82 个 TC。

自审发现与修正：原检查器精确限制 9 份 ADR，现保留基线并允许新增/拒绝重号；PR 模板缺一级标题导致初次全量检查失败，已修复；检索归档排除 glob 已改为仓库相对路径；补充首推分支的归档追溯限制。新会话/小改/安全修复/续做/缺依赖的路径已对照规则逐项核对，这是文档语义自审，不是独立模型行为实验。

未验证：GitHub 实际 CI、跨操作系统运行、真实 Agent 长期遵循情况、Java/Vue/数据库/OAuth/部署。当前内部工程规范按预定检查交付，Acceptance 为 not-required，不表示用户已接受产品功能。

## 交接

当前工程规范任务完成，无剩余实施项。后续收到业务实现请求时，从 T01 的一个工作项启动；未来新增模块再补本域 AGENTS 和真实构建门禁。实际工作树仍含本轮开始前已有的未提交文档，以及本轮新增规则/工具；不回退、不夹带提交。提交/集成/发布：未发生。授权为本轮用户的规范优化请求，不包括生产、推送或发布。业务 T01～T20 仍待实现。
