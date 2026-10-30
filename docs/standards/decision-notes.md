# 决策笔记与长期记忆

版本：1.1-agent · 2026-10-23。采用分层和生命周期，并按 PTMCP 既有 ADR 适配。笔记 Skill 在本地可用；本仓格式及 `python scripts/check.py` 的验证规则仍以本文为准，不能假定与外部看板/归档校验完全兼容。

## 谁记录什么

| 位置 | 唯一职责 | 使用方式 |
| --- | --- | --- |
| AGENTS 与 standards | 开发边界与操作规则 | 常驻摘要，按任务下钻 |
| PRD、总体方案、backend 等 docs | 产品/技术合同，当前均明确为设计基线 | 实现后同步实际行为与验证状态，未来计划必须显式标注 |
| `docs/architecture/adr/` | 跨域、长期架构决策及其理由 | 既有设计 ADR 继续权威，仍待实现/验证；不搬进 implemented |
| `tasks/work-items/` | 本次目标、步骤、状态、验证和交接 | 短期工作；不把日志提升为永久规则 |
| `.agents/notes/` | ADR 未承载的非显然取舍、被否路线和复盘因果 | 先检索，复用/互链；不重复 ADR 的同一决定 |
| `docs/evidence/` | 可复用证据入口和实际验证记录 | 区分历史、未运行、失败和通过 |

同一架构决定优先更新所属 ADR；只有额外独立的流程、故障经验等 concern 才写 Note 并回链。ADR 是设计决策，不自动证明系统已实现；实现后的行为与代码冲突时需明确处理偏差。

## 何时写

先查入口注释、相关模块文档、ADR 及活跃笔记：按主题搜索 proposed、implemented、rejected，排除已冻结 archived；明确既有记录的适用范围。改变跨模块契约、持久化/网络格式、行为不变量、开发工具链/测试策略，或存在后人很可能重提的被否方案时，保留最小决策记录。若已有 ADR/Note 承载，在原处同步仍成立的事实；已裁决但尚未实施的新方案以 proposed 表达，不把规划写成 implemented。

纯排版、错字、机械重命名和看 diff 即懂且无隐藏取舍的局部修复，只需工作项结果。安全反例、看不见的兼容约束、事故原因，即便一行代码也值得记录。不能为填模板编造备选；没有真实备选时写“无”，说明依据。

## 路径和状态

新增笔记路径为 `.agents/notes/<lifecycle>/<class>/YYYY-MM-DD-topic.md`。日期是首次提出日，主题用小写 kebab-case。仅创建用到的目录，不维护逐篇总索引；通用入口说明不属于动态笔记索引。

- lifecycle：`proposed / implemented / rejected / archived`。
- class：`feature / bug-fix / simplification / architecture / process / testing`。simplification 表示不新增能力的简化，可包含使行为更清晰的重构，不机械限制净增行数。
- 文件首行为 `# Agent Note: 标题`，空行后 `Status: <lifecycle>`；rejected 在状态后加 ` — 原因`。

| 状态 | 必备二级标题 | 事实要求 |
| --- | --- | --- |
| proposed | Problem、Proposal、Alternatives considered、Acceptance criteria、Risks | 尚待落地，不当成现状 |
| implemented | Problem、Decision、Alternatives considered、Consequences、Verification | 描述已生效事实；验证缺口如实写明 |
| rejected | Problem、Proposal、Alternatives considered、Acceptance criteria、Risks | 保留提案和否定原因，便于理解什么条件改变后可重访 |
| archived | Problem、Decision、Alternatives considered、Consequences、Verification | 仅来自 implemented 的历史快照，状态改 archived 后冻结 |

这是本项目格式；与参考 Skill 的 archived 头部/封印格式不同，不声称可直接通过其验证器或无转换导入其看板。模板见[决策模板](../../tasks/templates/decision-note.md)。

必备章节按上表列出的顺序排列；可在其间增加自由章节，但二级标题不能重复。代码块中的示例不计入章节检查。

## 内容与流转

1. proposed 写清独立成立的问题、拟定方案、真实备选的最强理由及放弃原因、收益/代价和可验证标准。用户已授权范围内由 Agent 自审后继续实施，不因“写了提案”追加确认；实质未决决策按开发流程处理。
2. 实施完成且相关验证满足后，在同批变更中移动到 implemented，更新状态和相对链接；Proposal 改 Decision，验收/风险并入 Consequences 和 Verification，移除计划标题。**已提交设计不等于已实施系统**，过程规范本身落地则可以 implemented/process。
3. 路径/符号等事实变化原地同步；核心决定或理由翻转时新建记录并互链，旧结论保留。写明吸收了什么、保留了什么，不能把旧篇改成相反观点。
4. 完全失效的 implemented 可迁入 archived，补 `Archived: YYYY-MM-DD`；更新活跃入站链接，在后继篇记录替代关系。仍约束当前安全/所有权或仅部分被替代时保留活跃。过时提案转 rejected，不能冒充历史实现。
5. archived 在首次提交后只读；后继记录修正历史事实/补充解释。校验器相对本地 HEAD 检查已跟踪归档的修改/删除，CI 对 PR 基线或推送前基线检查；无可用基线则失败，不把自报哈希当可信批准。新分支首次推送没有前一提交事件时，对 HEAD 自检，不能追溯该分支此前的归档改动，仍需 PR 基线检查/评审。
6. 新建前对主题命中的活跃记录当场分类：无关、部分重叠（保留并互链）、完全吸收（先保留独特理由及引用，再依授权处理）或过时提案（保留被否理由并按流程处理）。在新记录说明相关关系；无真实备选写“无”，不为模板编造。迁移、归档、删除和覆盖他人工作仍遵守仓库及用户授权边界。
7. 提交前语义自审：问题是否独立成立；决定是否为现行事实；备选是否真实且说明取舍；后果是否包含收益与代价；验证是否指向实际命令、结果和未测限制。检查器只约束结构，不证明理由、行为或证据为真。

不要为整理目录自动删除有防坑价值的历史。移除记录须确认授权与入站引用；本轮不批量迁移或清理已有 ADR、review 和任务。

每篇记录的验证应指向代码/契约/实际命令和结果，并明确失效信号。将来重新考虑被否路线时，先提供原约束已变化的证据；引用旧笔记不是无限期禁止重新评估。

校验命令统一为 `python scripts/check.py`。自动检查只验证可机械判定的部分；备选是否真实、方案是否合适和证据是否充分仍需评审。
