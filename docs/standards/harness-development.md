# 基于 Harness 实践的研发闭环

本文是本项目日常实施合同的操作补充，[开发流程](agent-workflow.md)和领域契约仍是权威来源。借鉴范围是 DeepSeek Harness 仓库的工程实践，不是将 PTMCP 改造成 DSH Agent 运行时，也不声称两仓工具或规则完全相同。

## 来源与适配

参考上游 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness/tree/ce7073d51254c26cd63b22a60835b8f948deb447)，固定提交 `ce7073d51254c26cd63b22a60835b8f948deb447`，查阅日期 2026-10-30。上游根 [AGENTS](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/AGENTS.md)、[文档规则](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/docs/AGENTS.md)、[测试策略](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/docs/testing.md)、[笔记规则](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/.agents/notes/README.md)、[评审](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/.agents/skills/dsh-code-review/SKILL.md)、[推送前检查](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/.agents/skills/dsh-pre-push-checks/SKILL.md)及[升级指南](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/.agents/skills/dsh-create-upgrade-guide/SKILL.md)是本页的直接依据。上游为 MIT 许可；本页为项目适配而非复制其脚本。

| 机制 | 本项目落点 | 边界与差距 |
| --- | --- | --- |
| 短入口、按领域下钻 | [AGENTS](../../AGENTS.md)、本文及领域文档 | 不照搬上游插件架构或命令 |
| 决策可检索、随实现同步 | [ADR](../architecture/adr/)、[笔记政策](decision-notes.md) | 项目归档冻结规则与上游不同；不假称格式互通 |
| 文档有归属、面向读者 | [文档入口](../README.md)、本文 | 原权威契约不复制到第二份清单；逐篇质量仍需审阅 |
| 变更范围驱动评审及检查 | `python scripts/change_scope.py`、工作项 | 只读文件名，不判断归属或推荐测试；远端基线需人工验证 |
| 分层测试与 CI | [测试计划](../test/test-plan.md)、`python scripts/check.py` | 上游 100% 覆盖率及其运行时不直接套用；本地通过不等于远端 CI 或真实环境通过 |
| 破坏性变更同批说明迁移 | 接口合同、工作项、权威契约和运维手册 | 目前无版本化升级指南自动门禁；不能将迁移说明延迟到发布 |
| 专项评审和可靠测试 | 下方检查及现有安全规则 | 依赖作者和独立评审实际执行，脚本无法验证语义 |

上游还有专用技能、翻译配对、发布、跨平台 CI 等机制；本项目不因列出这些机制就声称已安装或运行。参考版本更新后应重新核对，而非假定上游永不变化。

## 从目标到可交付结果

1. **发现与定界**：按根入口读取状态、已有任务、需求、ADR、源码与测试；写明目标、不做项、调用方、业务安全不变量和可验证退出条件。产品设计、现有实现与已验证行为分开记录。
2. **合同与取舍**：跨接口按[接口合同](api-change-template.md)覆盖输入输出、权限、错误、未知结果、兼容与调用方；跨域决定更新所属 ADR，其他非显然取舍按[笔记政策](decision-notes.md)记录。仅需局部修复时不复制一套设计文档。
3. **实施**：按可验证切片推进，先复现缺陷或建立边界反例；同时更新实现、调用方、Schema、合同及必要测试。外部可感知的不兼容变更当批记录受影响版本、用户操作、部署顺序、数据恢复及回滚限制，并链接权威契约/运维手册；禁止把待办写成已支持。
4. **分层验证**：按风险选择定向单元、集成、真实数据库、OAuth/MCP 客户端、浏览器及安全反例；记录命令、环境、对象、结果与跳过原因。检查异步取消、并发、清理、未知结果及日志泄漏；仅 mock、覆盖率或绿色构建不能替代真实边界。
5. **评审与交接**：评审实际 diff 和周边契约，先看正确性、生命周期、权限、敏感数据、兼容性和测试有效性，再看风格；缺陷须有复现场景。SQL、身份、ACL、令牌、审计、取消、迁移按现有规则要求安全专项独立评审，不能以自审冒充。记录工作项状态、证据、未测项、恢复步骤；人工接受、集成和发布分别取证。

## 检查变更范围

```sh
python scripts/change_scope.py
python scripts/change_scope.py --base <已核实的本地基线引用>
```

报告版本 1 含 HEAD、可选 base/merge-base 和 `committed`、`staged`、`unstaged`、`untracked` 路径数组；不提供基线时 `committed` 与 `merge_base` 为 null，**不猜测目标分支**。先独立核实 PR 或集成基线及其新鲜度；工具不联网、不 fetch。重定向目标或合并基线后重新确认并复查受影响行为。所有层都可能包含其他人的工作：路径不等于所有权或允许提交，先检查目标 diff 并保护共享工作。

报告为快照，不计算未跟踪目录内被 Git 忽略的文件，不分析内容、运行测试、验证授权或判断风险；重命名按旧/新路径列出。基线不存在、无共同祖先或 Git 失败时非零退出，不输出假报告。合入/推送只在获得相应授权后进行。

## 文档与测试评审要点

- 文档放在主题的权威位置，参考描述当前合同及边界，教程按读者完成目标的顺序描述前置条件和步骤；上层导航链接细节，不复制后端字段表。示例使用合成数据。
- 变更公共错误或失败恢复时说明稳定错误身份、处理责任、已提交/未提交/未知副作用、安全重试及对用户可见的下一步；不靠解析错误消息分支，不把捕获或日志当恢复。
- 检查测试能否在并行执行下隔离端口、数据库、临时目录和全局状态，清理后台线程及资源；跳过或环境故障如实报告，不为变绿静默删除断言。
- 局部检查选覆盖受影响行为的最小集合；`python scripts/check.py` 是仓库文档与治理检查，`--all` 才额外运行工程检查，且仍不自动代表用户验收或生产验证。CI 配置、触发与实际结果须分别核对。
