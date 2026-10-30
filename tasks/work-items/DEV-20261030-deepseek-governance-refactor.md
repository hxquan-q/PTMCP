# DEV-20261030-deepseek-governance-refactor：决策记忆与开发治理重构

ID: DEV-20261030-deepseek-governance-refactor
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-30

## 目标与边界

来源：用户要求完整按 DeepSeek 笔记范式重构，承接 DEV-20261030-development-contract。范围为研发流程、接口文档及决策记忆治理，不更改业务代码、现有协议安全不变量或历史证据。沿用 ADR、工作项和项目归档格式，说明与 Skill 的差异，不声称外部 Skill 可直接校验项目归档。已有共享工作树的修改保留；不删除或迁移他人的历史记录。

## 实施与验证

串行检查现有规范、活跃笔记、检查器与 Skill；先为头部和首节缺陷添加反例，修正门禁；完善决策记忆的检索、当场分类、语义自审与交付指引，并在现有流程 Note 记录取舍；执行定向检查、`python scripts/check.py` 与空白检查。门禁不能证明笔记内容真实或人员遵守。无需产品功能人工验收。

## 结果与证据

- 项目规则补充先检索、活跃记录当场分类、真实备选及语义自审；现有 Note 更新对应规则，未新建重复决定。检查器增加非空标题和 Problem 首节的拒绝规则。
- RED：`python -m unittest discover -s scripts/tests -p test_agent_workflow.py -v` 退出 1，两个新增反例失败；GREEN：同命令退出 0。`python scripts/check.py` 退出 0；`git diff --check` 对本次修改的已跟踪文件退出 0。
- 最初 `npx --offline --no-install tsx` 因 ENOTCACHED 失败，直接使用 Node 因模块加载错误失败。用户授权安装后，使用 `npx --yes tsx` 运行用户 Skill 目录下 `verify-agent-note-tree.ts`、`verify-agent-note-format.ts`、`verify-archived-agent-notes.ts`，三项均退出 0：13 篇活跃笔记、0 篇归档。归档数量为零，不能证明已有归档的格式互通；项目与 Skill 的归档规则仍存在差异。
- 自审：沿用既有 ADR/工作项，不删除历史；现有 Note 包含独立问题、真实备选、收益代价及证据；新检查只验证形式。未做独立评审、业务、生产或远端 CI 验收。

## 交接

本片外部校验阻断已解除；`npx` 使用缓存运行，不代表已配置项目本地依赖或离线 CI。当前仅完成局部治理重构，不代表完整迁移 DeepSeek Harness；未来出现归档时须按项目规则处理格式差异。已有共享工作树修改未清理、未提交或推送。
