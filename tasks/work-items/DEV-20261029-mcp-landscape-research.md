# DEV-20261029-mcp-landscape-research：开源项目核验与系统优化规划

ID: DEV-20261029-mcp-landscape-research
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-30

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

已完成 [项目核验与优化规划](../../docs/reference/mcp-landscape-optimization-20261029.md) 并整合外部调查。
覆盖用户清单的18个明确仓库及缺URL的AgenticMCP，明确固定SHA、一手引用、许可与证据层级；
纠正SQLite写工具、池连接只读、代理身份/resource、外部服务依赖和作者自报准确率等边界。
对照当前源码及T15-c/G1～G7，区分已修、实施中与待验；给出优化优先级、依赖、反例和合成问数评测门。

整合时HEAD为 `fc6f27313f01ef482400e9f6f5d233d2f2361d31`，定稿复核到 `a3776d08b9950472282756768db5223efedd4a51`，其间其他窗口已有提交；
随后OAuth、WAL/目录owner及React继续出现修改，均保留且未纳入本调研实现。
已将新增WalDirectoryOwner/replayClosed的事实补入报告，避免重复报为未实现；生产恢复调度和组合运行证据仍单列待验。
本Agent仅新增研究报告、更新本工作项；未执行提交、推送或发布。

本轮实际验证：

- `python scripts/check.py`：exit 0，文档/本地引用、流程、质量配置、HEAD提交格式及62项检查器回归通过。
- `git diff --check`：exit 0，无空白错误。
- 一次性文档核对：exit 0，19个项目条目、18个带固定SHA来源的仓库，无草稿占位。
- 文档自审：确认三类全覆盖、一手来源与README分级、当前源码/历史证据/建议分开、承接现有工作项；修正了定稿期间新增WAL原语的描述。

本轮检查是研究文档验收；没有运行外部项目、Maven/pnpm、业务数据库、真实客户端、性能或问数评测。
自审不是独立安全评审，未声明业务实现、人工接受或发布完成。

## 交接

研究交付完成。实施仍续接DEV-20261028-gateway-completion与T15-c，按报告的身份/运行闭环、上下文、评测顺序推进；
本轮没有启动这些业务实现，也没有将其pending项改为完成。共享工作区其他窗口的变更由原工作负责，未重置或覆盖。
本工作项为内部研究交付，Acceptance not-required 不代表用户接受方案、业务功能或发布。
