# DEV-20261030-note-gate：加强决策笔记结构门禁

ID: DEV-20261030-note-gate
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-30

## 目标与边界

按用户指定的 write-notes-like-deepseek 方法审视现有工程治理。沿用现有 ADR、工作项、生命周期和标准库检查入口，不另建管理系统；补齐现有笔记结构检查不能拒绝必需章节乱序或重复章节的问题。

不改业务行为、安全约束或历史归档，不以结构检查代替决策语义评审。开工时工作树已有文档、业务代码、笔记、截图迁移及其他在制修改；本切片不覆盖或提交其他工作的内容。

## 实施与验证

单 Agent 串行；先在隔离 Git 夹具中建立失败回归，再对笔记检查器实施最小修改；允许非必需章节穿插，忽略代码块内示例。运行定向回归、统一仓库检查、笔记 Skill 校验和差异空白检查。检查依然无法判定决策是否真实、完整或适合当前业务。

## 结果与证据

- 沿用既有研发合同和笔记体系；检查器现在拒绝笔记必需章节乱序和重复二级标题，允许自由章节及代码块示例。具体理由和备选原位补入既有流程决策，未复制 ADR 或新建并行决策。
- RED：`python -m unittest discover -s scripts/tests -p test_agent_workflow.py -v` 退出 1，新增的乱序及重复标题两项未被原检查器识别；GREEN：实现后同命令 19 项通过、退出 0，覆盖 proposed 和 implemented、自由章节及围栏示例。
- `python scripts/check.py` 退出 0：149 篇 Markdown、743 处本地链接、66 项检查器回归通过；`git diff --check` 退出 0。项目内 Skill 副本的 tree、format、archived 脚本经已有 jiti 分别通过（13 篇活跃笔记、0 篇归档）；用户指定的另一安装路径在本机不可读取，未声称执行了该路径的脚本。
- 评审为自审；未运行远端 CI、前后端构建、业务或人工产品验收。自动检查无法确认决策、验证证据及 Agent 行为的真实性与质量；内部规则维护无需产品人工验收。

## 交接

当前切片已完成；日常继续按既有流程先查历史、按风险选择工作项和笔记、验证后更新记录。共享文件 `docs/standards/decision-notes.md` 与流程笔记在其他窗口已有未提交段落，本切片在隔离工作树 `.local/wt-sync` 内只提交自有段落并推送 `codeup/dev`；主工作树的他人改动保持不变。远端 CI 的实际运行结果待推送后另行核对，不预先声称通过。
