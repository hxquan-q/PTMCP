# DEV-20261030-governance-assets：规范 Agent 入口、设计与配置资产

ID: DEV-20261030-governance-assets
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-11-04

## 目标与边界

用户要求参考官方 DeepSeek Harness 仓库开发治理，严格规范根 AGENTS、CLAUDE、DESIGN 及 .agents、.claude。保留现有 SAP 视觉权威、技能资产、本地配置、已存在修改及安全边界；不安装技能、不改变权限、不执行提交推送。

2026-11-04 续接：用户要求结合 Claude Code、Codex、Zed 官方建议精简根入口，明确最小正确改动、防止无依据的防御性代码，并将目录差异就近放置。只修改入口、资产治理和已有记录；不修改业务、安全契约或检查器，不批量创建模块规则。上段“不提交推送”为 2026-10-30 的历史范围；本轮按当前用户授权及 Git 规范处理。

开工前已有 `UserAdminController.java`、`AdminApiTest.java` 两处未提交修改，本轮保留且不纳入交付。

## 实施与验证

核对固定版本上游入口、目录规则和技能目录；制定项目职责、加载、变更及共享边界，新增可机械判断的门禁及隔离测试，运行仓库检查和笔记校验。

2026-11-04 当前切片（单 Agent）：根 AGENTS 保留全仓边界、最小改动纪律和路由；CLAUDE 只桥接根规则；更新后端目录入口、新增 React 目录入口。验收为入口简短且无第二套正文、局部规则可达、现有安全与授权边界保留，`python scripts/check.py` 与本片 `git diff --check` 通过。纯规则变更不新增业务测试、不运行 Maven/pnpm 或浏览器。

用户“继续”后的下一切片：在后端七个模块及前端 `src/services`、`src/preview` 下放置确有差异的规则，父入口只路由。根 AGENTS/CLAUDE 保持上一切片内容，现有业务契约、安全策略、源码、检查器与依赖不变。开工 HEAD 为 `0548d27`、工作树干净；原两处 Java 修改已由其他提交收录，本轮不改写或重新提交。仍为单 Agent 串行；验收为局部规则能沿父入口到达、链接有效、规则不复制通用流程、不把设计目标写成已实现，运行仓库检查并做语义自审。

## 结果与证据

- 核对上游固定提交 `ce7073d51254c26cd63b22a60835b8f948deb447`：根 `AGENTS.md` 为短入口，`packages/AGENTS.md` 与 `.agents/notes/implemented/AGENTS.md` 以目录规则补充根规则，`docs/AGENTS.md` 定义文档标准；`.agents/skills/.gitignore` 只忽略各技能的 `agents/openai.yaml`；上游 `.claude` 仅有指向技能目录的符号链接。本仓未复制该链接，也未声称宿主已加载技能。
- 新增 `docs/standards/agent-assets.md`：规则优先级与冲突处理、五类资产的唯一职责/读取时机/维护方式、DESIGN 变更合同、技能与宿主配置变更步骤，以及门禁能证明与不能证明的范围。`AGENTS.md` 前端路由显式链接 `DESIGN.md`，规则/技能/配置路由指向该规范；`agent-workflow.md`、`docs/README.md`、`validate_docs.py` 必需清单和既有研发流程 Note 同批同步。
- `scripts/verify_agent_workflow.py` 新增 `check_asset_contract`：`CLAUDE.md` 须链接 `AGENTS.md`，`AGENTS.md` 须链接 `DESIGN.md` 与 `agent-assets.md`，两者非空；仅对被 Git 跟踪的 `.agents/skills/*/SKILL.md` 校验 frontmatter 的 `name`（须等于目录名）与非空 `description`，被忽略的本机技能显式不在共享门禁范围。技能文件无法读取或 `git ls-files` 失败时报告错误。
- RED/GREEN：先加入入口路由反例，实现前 `test_entry_links_and_design_are_required` FAIL；实现后新增四类反例（缺链接、缺文件、空描述、名目录不一致）与“未跟踪本机技能不应失败”均通过。
- `python scripts/check.py` 退出 0（74 项检查器测试通过）；`git diff --check` 对本片已跟踪文件退出 0；外部笔记校验 13 篇通过、0 篇归档。仅作者自审，未运行 `--all`、浏览器视觉、宿主实际加载、独立评审或远端 CI；内部治理任务无需产品人工验收。

2026-11-04 来源与基线：

- 已读取 [Claude Code 最佳实践](https://code.claude.com/docs/en/best-practices)与[指令记忆文档](https://code.claude.com/docs/en/memory)：200 行是建议目标；从代码可推断的说明应精简；局部规则按需加载，`@` 导入自身不减少加载量。
- 已读取 [Codex 最佳实践](https://developers.openai.com/codex/learn/best-practices)与[AGENTS 指南](https://developers.openai.com/codex/guides/agents-md)：短而准确、重复偏差后更新、目录就近约束；32 KiB 是可配置的默认指令链总预算，不是单文件硬上限。沿用仓库现有 8 KiB/1 KiB 门禁，不改阈值。
- 已读取 [Zed Instructions](https://zed.dev/docs/ai/instructions)与[Skills](https://zed.dev/docs/ai/skills)：总规范常驻、任务流程按需；别名按优先级选择，不能假设所有宿主递归加载规则。本轮不改用户级路径或宿主设置。
- 社区“减少 50–90% 代码”及特定模型系统性表现未做独立实测，不作为本项目效果或官方结论；最小改动纪律采用用户明确偏好，并保留必要防御。
- 改动前 `python scripts/check.py` 退出 0，文档 164 篇、本地链接 809 处、检查器回归 74 项通过。后端旧入口仍声称仅有启动类，与 README 所述业务实现不符；已去除该状态缓存，改为契约路由。

2026-11-04 交付：根 AGENTS 46 行、4,643 字节，CLAUDE 7 行、205 字节；后端规则 21 行，React 规则 19 行。根入口从 5,567 字节收敛，未复制架构、版本或通用风格正文。经验规则的增补原则放在资产治理，保留现有安全/授权/证据边界，未修改检查器。

2026-11-04 验证与自审：

- `python scripts/check.py` 退出 0：165 篇文档、829 处本地链接、流程/配置/提交消息检查及 74 项检查器回归通过；本片 `git diff --check` 退出 0，新增 React 规则无行尾空白。未为文档改动增加测试或改变门禁。
- 在项目 Node 24 环境以 `npx --offline --no-install tsx` 依次执行 Skill 的 `verify-agent-note-tree.ts`、`verify-agent-note-format.ts`、`verify-archived-agent-notes.ts`，均退出 0：15 篇活跃笔记、0 篇归档、0 个封印。使用缓存，未安装新依赖；既有 npm `store-dir` 配置警告未在本轮扩展修复。
- 作者自审（非独立评审）：逐项核对普通修复、SQL/认证变更、前端交互、规则维护的路由；确认原有安全专项评审路径可达、最小改动不会豁免安全检查、目录间不误用局部规则、根 CLAUDE 未批量导入领域文档。Zed 高于 AGENTS 的项目别名在根目录均不存在；仍未声称实际宿主加载实验通过。
- 沿用既有研发流程 Note，记录拒绝“为所有模块预建规则”和“内部一律互信/禁止新文件”的理由；其他状态治理、工具门禁与业务笔记保持原职责。内部规则任务无需产品功能人工验收。

用户“继续”后的模块切片结果：新增后端七模块与前端 services/preview 共 9 份目录规则，每份 9～10 行；父入口按模块路由，未新增目录级 CLAUDE 或第二套通用流程。根 AGENTS/CLAUDE 内容未改，仍为 46/7 行。规则分别回链已有资源身份/错误登记、guard 审批、物理连接/取消、事务/WAL、管理写、OAuth、MCP、真实 HTTP 和合成预览合同；未改变这些合同的语义。

- `python scripts/check.py` 退出 0：现有范围 165 篇 Markdown、827 处本地链接，74 项检查器回归通过。现有收集器只扫描工程根 Markdown，**不涵盖新增嵌套规则正文**；没有将其 PASS 扩大解释。
- 额外在仓库根以标准库一次性检查 9 份新规则的标题、编码、行尾空白、43 处本地链接，并从根 AGENTS 遍历规则链接验证全部可达，退出 0；未新增脚本或修改检查器。`git diff --check`、`git diff --exit-code -- AGENTS.md CLAUDE.md` 均退出 0。
- 项目环境中的三个笔记校验仍以缓存 `npx --offline --no-install tsx` 运行，均退出 0：15 篇活跃笔记、0 篇归档。沿用同一研发流程 Note，只同步已下拆的事实，无新增决策基线。
- 作者自审：逐项对应既有领域合同与源代码的信任/事务/生命周期边界；继承父级的构建、授权、证据与最小改动规范。普通 UI 目录继续继承前端入口。业务测试路径仅作为后续变更的验证入口，本轮未运行这些业务测试，也未声称宿主自动加载已验证。

## 交接

已建立的强制部分为入口/文件存在与链接、已跟踪技能基础元数据；不强制视觉一致性、配置权限安全、技能来源与依赖安全、规则语义或长期遵守。`.agents/skills/` 仍被 `.gitignore` 排除，若未来要共享某个技能，须按规范显式纳入并对该技能单独评审。`.claude/settings.local.json` 未读取、未修改。共享工作树原有修改保留，未提交、推送或发布。

2026-11-04 交接：本切片已完成检查与自审，只纳入本轮七份规则/治理/记录文件，保留两处原有 Java 修改。当前分支为 `dev`，提交前比本地 `codeup/dev` 引用多 8 个既有提交，其中含 MCP 传输安全变更；本轮不附带推送这些变更，安全关键推送仍需对应明确授权。未执行跨分支操作或发布。

未运行 Maven/pnpm 工程检查、浏览器、Claude/Codex/Zed 新会话加载或长期遵循实验；本轮只验证规则资产，不声称业务或宿主行为验收。

模块切片交接：13 份文件属于本轮（9 份新规则、2 份父入口、既有工作项与 Note），无业务代码/配置变更。开工时已有提交 `0548d27` 保留；提交前本地分支比 `codeup/dev` 多 10 个既有提交，仍含前述安全变更，本轮不推送或执行跨分支/发布操作。当前规则分层已完成；新模块差异仍在收到实际需求时就近补充，不预建目录规则。
