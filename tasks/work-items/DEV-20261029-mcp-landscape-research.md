# DEV-20261029-mcp-landscape-research：开源项目核验与系统优化规划

ID: DEV-20261029-mcp-landscape-research
Status: in_progress
Owner: current-agent
Acceptance: not-required
Verification: NOT_RUN
Updated: 2026-10-29

## 目标与边界

用户要求使用 research 技能，结合其提供的数据库只读连接、OAuth/企业权限及 NL2SQL 开源项目清单，调研 PTMCP 如何优化。
用户在暂停后明确追问为何不继续，本轮恢复调研。交付可追溯的项目核验、适配取舍、优先级、切片和验收方法。
承接 [连接专项](DEV-20261027-datasource-connection-research.md)、[网关核查](DEV-20261028-gateway-capability-review.md) 与
[网关收尾](DEV-20261028-gateway-completion.md)，不创建替代产品或架构基线，不重复实施既有修复。
只写研究报告与本工作项；不改业务实现，不安装参考仓依赖，不执行参考仓代码，不连接业务库，不提交、推送或部署。
维持 guard 前零业务连接、平台与用户权限交集、Deny 优先、审计持久准入、版本复核和只读边界。

开工基准为 `dev` / `74e5515449c6449024a720aad38750abb8a24e13`。
已有 OAuthDataConfiguration、OAuthSecurityConfiguration、PtmcpOAuth2AuthorizationService 三个 Java 文件修改及未跟踪截图归其他工作所有，全部保留。
仓库处于共享工作状态，报告以读取时的源码为准，动态变化单独注明，不把历史核查当作当前事实。

## 实施与验证

research 技能明确要求后台 Agent 调查，因此委派外部数据库/OAuth 项目核验；主 Agent 只读核查本项目、NL2SQL 项目和规范并整合。
子 Agent 独占隔离目录 `.local/research/mcp-landscape-20261029/external/`，只写 findings.md；禁止修改产品代码和跟踪文档。
主 Agent 独占本工作项和 `docs/reference/mcp-landscape-optimization-20261029.md`；没有并行业务写入，整合后重验。
事实优先级：固定版本源码/协议、当前源码、README 自述、推断；分别记录。未取得确切地址的项目保持未核验。

验收：覆盖用户三类清单；纠正关键能力声明；对照当前实现；给出保留/借鉴/延后、阶段依赖、反例验收与指标；每个外部结论附一手来源。
验证使用 `python scripts/check.py`、`git diff --check` 和逐项文档自审。
本轮不运行 Maven/pnpm、数据库或真实客户端实验，文档通过不代表业务安全、性能或生产验收通过。

## 结果与证据

调研进行中；外部源码与内部事实待整合。仅记录实际观察，不预填通过。

## 交接

继续读取当前实现及官方协议，核验用户清单后产出单份研究报告，再完成适用检查和自审。
本工作项为内部研究交付，Acceptance not-required 不代表用户接受方案、业务功能或发布。
