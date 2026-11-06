# DEV-20261104-enterprise-agent-foundation：PTMCP 面向企业 Agent 的基础能力研究

ID: DEV-20261104-enterprise-agent-foundation
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-11-04

## 目标与边界

来源：用户请求——参考《数仓与企业 Agent 落地方案调研》，结合 `Reference/skill/data-qa`，调研 PTMCP 如何演进为企业 Agent 的基础。

目标：给出有证据边界的定位判断、能力差距、目标架构、可复用模式、分阶段路线和风险，供后续立项/ADR 使用。

不做项：

- 不改业务实现、不改 PRD/架构/路线图合同；只新增研究文档和工作项。
- 不连接业务库，不执行真实业务 SQL，不调用收费模型，不安装参考仓代码。
- 不提交、不推送、不部署。
- 不把厂商基准或 `data-qa` 本地快照当成生产验收。

不变量：只读查询网关、SQL guard、平台 ∩ 用户权限、审计、未知语法拒绝、`Reference/` 仅参考。

## 实施与验证

依赖：`docs/reference/mcp-landscape-optimization-20261029.md`、`docs/product/PRD.md`、`docs/architecture/architecture.md`、`docs/architecture/roadmap.md`、`docs/backend/mcp-tools.json`、`docs/evidence/mcp-capability-audit-20261030.md`；`Reference/skill/data-qa` 的 `SKILL.md`、`CONSTRAINTS.md`、`rules/core.md`、`catalog/`、`evals/`、`scripts/mesqa.py`。

外部研究：通过 rsf 检索并落盘 20 余篇来源，覆盖 MCP 2026-07-28、A2A 1.0、Agent Skills、Entra Agent ID、Snowflake/阿里云/InfoQ 网关、dbt/DataSpace/Cube/Atlan/Count/Agami 数据准确性、Anthropic 方法论、Braintrust 可观测性。来源与限制见报告附录 A。

实施：新增 `docs/reference/enterprise-agent-foundation-20261104.md`；未改源码、配置、PRD、架构、路线图或既有 ADR。

验证命令与结果：

- `python scripts/check.py` → PASS（docs 167 个 Markdown、836 个本地链接、4 个 MCP 工具、20 条 PRD、84 条测试用例；无错误）。
- `git diff --check` → PASS（退出码 0；仅覆盖已跟踪改动，本轮无已跟踪改动）。
- 新增文件空白检查 → PASS（尾随空白 0、制表符 0）。
- 本地链接复核：报告引用的工作项、PRD、架构、路线图、`mcp-tools.json`、`data-qa` 路径均在仓内。

未执行项及原因：未运行 Maven/pnpm（无代码变更）；未做真实客户端联调；未核对外部 URL 的实时可达性（`check.py` 不检查外链）。

## 结果与证据

实际修改：

- `docs/reference/enterprise-agent-foundation-20261104.md`：研究建议全文。
- `tasks/work-items/DEV-20261104-enterprise-agent-foundation.md`：本工作项。
- 用户指定的外部方案目录（`D:\why\工作\项目\国人AI项目\智能问数`）：README、01/02/03 按 MES 优先、对外交付、客户私有部署、仅管理员维护同步。

核心结论：

1. 定位为“企业 Agent 的受治理数据与工具基础”，不做通用 Agent 运行时。
2. 准确性杠杆是语义层 + VQ + 评测门禁，不是更强模型。
3. 发布单元从“数据源 + 工具”升级为“领域数据包”。
4. 外部 Agent 做理解/澄清/解释，PTMCP 做身份/上下文/编译/守门/执行/证据/审计。
5. 三阶段：语义上下文 + VQ + 评测门禁；技能注册 + Agent 身份 + 轨迹 + 预算；多领域包 + 行动边界 + A2A。
6. 管理与使用分离：语义资产、包发布/回滚、授权、配额、审计和升级只由管理员维护；普通用户只使用，不提供自助配置/发布/升级；自由 SQL 默认关闭，仅管理员可按白名单开启。

证据路径：报告附录 A（来源与限制）、附录 B（`data-qa` 资产清单）。

评审类型：自审（未委派独立评审）。发现并修正：初稿附录存在占位/不准确 URL，已按本地研究文件的实际 URL 修正；DataSpace 与 MCP 规范明确标注为“解读/分析”，未冒充原文；复核时统一了交叉引用（§4/§5/§8/§9）、证据等级（阿里云 A/B、MCP 规范 A/B）和评测集计数（85 条核心 + 233 条压力集 + 156 条扰动集），删除了无依据的“21 trap”。

剩余风险：外部来源多为厂商材料；`data-qa` 资产只证明本地快照；路线周期是建议，需结合现有 M0–M4 资源再排期。

## 交接

当前焦点：研究交付完成，管理与使用分离已定；等待用户决定是否把“领域数据包 + 语义工具 + 评测门禁”立为下一阶段工作项。

下一步建议：先做报告第 9 节的 5 件事，顺序为契约 → 选题 → 最小工具 → 基线评测 → 轨迹字段。

实际 git status：新增 2 个未跟踪文件；未提交、未推送。

已有授权与待决定事项：本轮仅授权研究/文档；未授权提交、推送、部署、连接业务库或收费调用。用户已确认：MES 优先、对外交付、客户私有部署（单客户单实例）、仅管理员维护（普通用户只使用；语义资产、发布/回滚、授权、配额、审计、升级均由管理员执行；自由 SQL 默认关闭，仅管理员白名单开启）。仍需落地：客户管理员账号与交接人（运营项，不阻塞方案）。
