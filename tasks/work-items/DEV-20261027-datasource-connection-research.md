# DEV-20261027-datasource-connection-research：数据库连接源码对比与完善方案

ID: DEV-20261027-datasource-connection-research
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-27

## 目标与边界

用户要求深入结合本项目，拉取最新代码，对照 universal-db-mcp，提出数据库接入与问数连接的具体完善方案。
本轮交付源码研究与可执行切片，不修改业务实现、不连接真实业务库、不合并分支、不提交或推送。
保留 guard 前零业务连接、平台与用户权限交集、审计持久准入、版本复核、只读和失败关闭边界。

开工已有修改：`.github/workflows/quality.yml`、`docs/ops/yunxiao-pipeline.json`、
`docs/ops/yunxiao-quality.md`、`scripts/check.py`、`scripts/tests/test_check_entrypoint.py`，
以及云效笔记、`.agents/skills/` 和两个云效工作项；全部归原工作所有者，本轮不改动。

## 实施与验证

续接调查依据：[T15-b](T15-b.md)、[T19-a](T19-a.md)、[T19-b](T19-b.md)、
[T10-a](T10-a.md)、[T11-a](T11-a.md)、[T12-a](T12-a.md)，不建立替代产品基线。

当前切片：fetch 两个远端，冻结本项目与参考仓 SHA；读取管理、元数据、执行、池和前端调用；
形成 [研究方案](../../docs/reference/universal-db-mcp-connection-review-20261027.md)。
写入范围仅该研究文档和本工作项；隔离参考材料位于忽略目录 `.local/research/`。

委派依据：本轮采用 `research` 技能，其明确要求后台 Agent 调查。
参考研究 Agent 只读外部源码，独占 `.local/research/universal-db-mcp/` 与其 findings 文件；
主 Agent 调查 PTMCP 并整合，无并行业务代码写入。

验收：每项现状有源码定位；区分源码事实、推断、既有测试与本轮运行；方案列出文件、兼容、反例与阶段。
执行 `python scripts/check.py` 和文档差异自审；本轮不重跑 Maven/pnpm 或数据库实验，不能报告运行时安全验证通过。

## 结果与证据

已新增 [研究方案](../../docs/reference/universal-db-mcp-connection-review-20261027.md)：
记录两条项目基线与参考仓 SHA，列出参考机制、9 组源码发现、端到端结构、7 个实施切片、
兼容/回退与最低验证矩阵。方案仍为建议，没有覆盖现有 ADR/backend 合同或启动业务实现。

优先发现：管理工厂与网关 pinning/TLS 装配不一致；配置更新直接替换且网关快照读取缺少
configVersion 联合判定；approved-plan/池代绑定和出队资源权限复核不足；健康固定 UNKNOWN；
多副本配额/管理队列、请求取消接线、密钥轮换/AAD、字典到 MCP 上下文仍有具体改进点。
以上为静态事实与风险分析，未将双库切换、DNS/取消竞争等反例记为已运行。

2026-10-27 本轮实际检查：

- `git fetch origin`、`git fetch codeup` 成功；核对 dev 左右计数 0/0、较新功能分支 0/150；
  首次误查不存在的 origin/dev 后改用实际 Codeup 跟踪分支，未改当前分支。
- 参考仓实际 clone 成功，SHA 与默认分支远端 HEAD 相同，工作区 clean。
- `python scripts/check.py` 退出 0：文档、工作流程、质量配置 PASS；检查器回归 50/50 PASS。
  文档检查包含本轮新文件；外部链接和 Mermaid 渲染不属于该检查能力。
- `git diff --check` 退出 0；未修改原有已跟踪文件。
- 自审：区分生产装配与测试工厂，纠正不能以旧 HikariPoolFactory 的 REQUIRED 误报生产 TLS；
  标明最新分支新增 cause 原文探针与静态风险，未声称已经复现泄露；时间估算标为建议。

未运行 Maven/pnpm、参考仓测试、真实业务库或故障注入，因为本轮只交研究与方案，没有业务实现改动。
评审类型为主 Agent 自审；参考 Agent 是外部源码调查者，不算 PTMCP 独立安全评审。
Acceptance not-required 仅表示内部研究文档交付，不代表用户已接受方案、功能或生产发布。

## 交接

本地 `dev` 与 `codeup/dev` 均为 `8afb59c6e9c017af588f7f3b07f3b5cfad5492d7`。
fetch 后 `codeup/xquan/feat-backend` 为 `b64777c49c6748d1656691b19a2b9f9bd10d5e68`，
相对当前分支新增 150 个提交，已导出到 `.local/research/ptmcp-b64777c/` 对照，没有合并。
`origin` 没有 dev 分支，不能用不存在的 `origin/dev` 判断更新状态。
参考仓最新 main 为 `dc338cd7740c277c04722743b97eb1681e409016`。

当前焦点已完成；后续若实施，先从报告 S1～S3 续接 T15/T19/T10/T11，建立真实反例。
最终工作树仅新增本工作项与研究文档；原云效修改全部保留。无业务代码修改、提交、合并、推送或发布。
无需本轮追加决策；生产连接预算、证书与运行时矩阵由实施切片实际配置和验证。
