# Agent Note: 用派生视图与仓库卫生收敛流程噪声

Status: implemented

## Problem

流程骨架已落地，但运行态有两类失真。

一是状态：19 个工作项停在 `verifying` 且全部 `Verification: PASS`，其中 5 个 `Acceptance: not-required` 已满足 `done` 条件却未收尾，另外 14 个其实在等人工验收。`rg '^Status:'` 无法区分"工程未完成"和"等用户"，`verifying` 成了混合队列。同族口径也不一致：T05-a 为 pending，T05-b1/b2 为 not-required。

二是工作树：`.auto/last-be.log` 与 `.auto/log.jsonl` 被跟踪，每轮自治循环必改；根目录散落 UI 截图；`.agents/skills`、`.codex`、`.github/{agents,hooks,skills}` 五处本机工具安装物未跟踪。流程要求开工先看 `git status`，真实改动却被这些噪声淹没。`todo.md` 另有一段手写状态叙述，已与工作项漂移（2026-10-26 与 2026-10-28 两句并存），与"不维护第二份动态总表"的规则冲突。

## Decision

状态继续以工作项为唯一来源，不新增状态枚举。新增 `scripts/work_status.py` 作为派生视图，按读者需要分五组输出：待人工验收（`verifying` + `PASS` + `pending`）、工程进行中、阻塞、可收尾（`verifying` + `PASS` + 非 `pending`）、状态矛盾（`done` 但字段不合规）。视图不落盘；`--strict` 在可收尾或矛盾时非零退出，供后续 CI 使用。

按该视图收尾 T05-b1、T05-b2、T06-a、T07-a、T08-a：条件为 `Acceptance: not-required` 且 `Verification: PASS`，后续依赖切片已完成，置 `done` 并在交接记录原因。14 个 `Acceptance: pending` 的项保持 `verifying`，等待用户按批验收。

`.auto` 运行日志移出版本库并加入 `.gitignore`，只保留 `checks.sh`、`measure.sh`、`prompt.md`、`ideas.md`；本机工具安装物（`.agents/skills`、`.codex`、`.github/agents`、`.github/hooks`、`.github/skills`）整体忽略。`todo.md` 删除手写状态段，改为指向派生视图；`parallel-prompts-20261024` 标注 A～C 波次已完成、仅 D 波次保留参考，`tasks/README` 同步措辞。

## Alternatives considered

- **新增 `awaiting-acceptance` 状态**：查询更直接；但要同步校验器、模板、文档和 19 个工作项，且与现有 `Status`/`Acceptance` 两字段语义重叠。派生视图零规则变更即可覆盖，先做视图。
- **把日志提交入库统一管理**：可追溯每次循环；代价是每次运行都制造 diff 噪声，与"开工先识别原有修改"直接冲突。需要留存时另存摘要。
- **删除 5 个可收尾项**：能立刻减少条目；但丢失实现证据与交接，且同类 14 项仍需保留，口径更乱。改为置 `done`。
- **直接删除根目录截图**：工作树立刻干净；但截图属于他人产物，未经确认不删除。会话期间截图已消失（同时有并发前端进程在改动工作树），本轮未做删除。

## Consequences

收益：一条命令回答"现在卡在哪、等谁"；工作树恢复为只显示真实改动；待办表与工作项不再双写。代价：派生视图需要每次状态变更后重跑（未接 CI）；忽略本机工具安装物意味着团队共享需另行显式提交；收尾 5 项改变了状态记录，若后续认为这些切片仍需人工验收，应把 `Acceptance` 改回 `pending` 并退回 `verifying`。

## Verification

`python scripts/check.py` 通过（文档链接、工作项字段、笔记骨架与检查器回归）。`python scripts/work_status.py` 输出 55 项：待人工验收 14、工程进行中 1、阻塞 1、可收尾 0、矛盾 0；`--strict` 退出码 0。新增 `scripts/tests/test_work_status.py` 三例通过。`git status --short` 只剩真实改动与本轮编辑。本轮为本机作者自审，未在远端 CI 运行，未声称用户验收。

依据：55 个工作项字段、`.auto/log.jsonl` 的 115 轮记录、git 历史提交类型分布。同主题既有记录见[Agent 主导研发](2026-10-23-agent-led-development.md)，本篇只补充状态读取与仓库卫生，不改变其入口/路由决定。提交门禁与循环停止条件见[机械门禁](2026-10-28-mechanical-commit-and-loop-gates.md)。
