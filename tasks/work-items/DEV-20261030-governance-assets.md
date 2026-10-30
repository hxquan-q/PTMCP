# DEV-20261030-governance-assets：规范 Agent 入口、设计与配置资产

ID: DEV-20261030-governance-assets
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-30

## 目标与边界

用户要求参考官方 DeepSeek Harness 仓库开发治理，严格规范根 AGENTS、CLAUDE、DESIGN 及 .agents、.claude。保留现有 SAP 视觉权威、技能资产、本地配置、已存在修改及安全边界；不安装技能、不改变权限、不执行提交推送。

## 实施与验证

核对固定版本上游入口、目录规则和技能目录；制定项目职责、加载、变更及共享边界，新增可机械判断的门禁及隔离测试，运行仓库检查和笔记校验。

## 结果与证据

- 核对上游固定提交 `ce7073d51254c26cd63b22a60835b8f948deb447`：根 `AGENTS.md` 为短入口，`packages/AGENTS.md` 与 `.agents/notes/implemented/AGENTS.md` 以目录规则补充根规则，`docs/AGENTS.md` 定义文档标准；`.agents/skills/.gitignore` 只忽略各技能的 `agents/openai.yaml`；上游 `.claude` 仅有指向技能目录的符号链接。本仓未复制该链接，也未声称宿主已加载技能。
- 新增 `docs/standards/agent-assets.md`：规则优先级与冲突处理、五类资产的唯一职责/读取时机/维护方式、DESIGN 变更合同、技能与宿主配置变更步骤，以及门禁能证明与不能证明的范围。`AGENTS.md` 前端路由显式链接 `DESIGN.md`，规则/技能/配置路由指向该规范；`agent-workflow.md`、`docs/README.md`、`validate_docs.py` 必需清单和既有研发流程 Note 同批同步。
- `scripts/verify_agent_workflow.py` 新增 `check_asset_contract`：`CLAUDE.md` 须链接 `AGENTS.md`，`AGENTS.md` 须链接 `DESIGN.md` 与 `agent-assets.md`，两者非空；仅对被 Git 跟踪的 `.agents/skills/*/SKILL.md` 校验 frontmatter 的 `name`（须等于目录名）与非空 `description`，被忽略的本机技能显式不在共享门禁范围。技能文件无法读取或 `git ls-files` 失败时报告错误。
- RED/GREEN：先加入入口路由反例，实现前 `test_entry_links_and_design_are_required` FAIL；实现后新增四类反例（缺链接、缺文件、空描述、名目录不一致）与“未跟踪本机技能不应失败”均通过。
- `python scripts/check.py` 退出 0（74 项检查器测试通过）；`git diff --check` 对本片已跟踪文件退出 0；外部笔记校验 13 篇通过、0 篇归档。仅作者自审，未运行 `--all`、浏览器视觉、宿主实际加载、独立评审或远端 CI；内部治理任务无需产品人工验收。

## 交接

已建立的强制部分为入口/文件存在与链接、已跟踪技能基础元数据；不强制视觉一致性、配置权限安全、技能来源与依赖安全、规则语义或长期遵守。`.agents/skills/` 仍被 `.gitignore` 排除，若未来要共享某个技能，须按规范显式纳入并对该技能单独评审。`.claude/settings.local.json` 未读取、未修改。共享工作树原有修改保留，未提交、推送或发布。
