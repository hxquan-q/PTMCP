# PTMCP 文档导航

整理日期：2026-10-30。本文是研发文档入口，不是产品状态总表。后端与 React 已有部分实现；设计、实现、验证、人工接受和发布分别判断，不能由源码或构建推导业务已验收。

## 从这里开始

1. [项目总体方案](总体方案.md)：业务边界、技术基线、跨文档参数和review裁决。
2. [PRD](product/PRD.md)：角色、20项业务需求、规则、验收与二期边界。
3. [概要架构](architecture/architecture.md)：模块、服务依赖、查询和变更流程。
4. [研发路线](architecture/roadmap.md)：M0～M4顺序与退出条件。
5. [能力核查 2026-10-30](evidence/mcp-capability-audit-20261030.md)：本地源码和在制工作树的核查快照；后续变化以工作项和实际源码为准。
6. [测试报告 2026-10-26](test/test-report.md)：历史候选纵切证据，不代表当前构件已通过。

开发 Agent 从根[AGENTS.md](../AGENTS.md)进入，按[开发流程](standards/agent-workflow.md)与[研发闭环](standards/harness-development.md)完成任务；[决策笔记](standards/decision-notes.md)说明 ADR/Note 分工。[后端工程](../ptmcp-parent/README.md)、[React 工程](../ptmcp-ui-react/README.md)提供运行与检查入口；当前工作从[任务入口](../tasks/README.md)查询。

规范优先级：总体方案与已纳入基线的ADR定义跨域不变量；PRD定义产品行为；backend契约定义字段/接口和算法细节；其他文档引用这些规则。新变更须同步相关文档，不能靠后写的文件隐式覆盖既有契约。冲突应在同一次变更中修正。

## 按工作阶段查找

| 要解决的问题 | 首选入口 | 注意事项 |
| --- | --- | --- |
| 了解业务范围与验收 | [总体方案](总体方案.md)、[PRD](product/PRD.md)、[页面规格](frontend/page-spec.md) | 描述目标和合同，不自动证明实现或验收 |
| 理解架构与技术取舍 | [整体架构](architecture/architecture.md)、下方 ADR、[安全架构](architecture/security.md) | 计划中的组件和实际接线需分别核对 |
| 修改接口、存储或安全逻辑 | [后端详细设计](#后端详细设计)、[代码规范](standards/code-style.md) | 核对源码、Schema、测试及相关工作项；偏差不能静默覆盖设计 |
| 本地开发与质量检查 | [后端工程](../ptmcp-parent/README.md)、[React 工程](../ptmcp-ui-react/README.md)、[开发环境](ops/development-environment.md) | 脚本与 CI 的运行状态以实际构件和日志为准，见[根入口](../README.md#开发与验证) |
| 查当前工作与证据 | [任务入口](../tasks/README.md)、[证据目录](evidence/README.md)、[测试计划](test/test-plan.md) | 按执行时间和被测构件解读；历史 PASS 不继承到新代码 |
| 部署、恢复或上线评估 | [部署设计](ops/deploy.md)、[运维手册](ops/operation-manual.md) | 文档不是生产部署授权；部署、恢复与发布证据单独验收 |

## 产品与用户

| 文档 | 内容 |
| --- | --- |
| [PRD](product/PRD.md) | 定位、角色、范围、FR/NFR、业务验收、审查追踪 |
| [用户业务流程](product/user_flow.md) | 建源/授权、OAuth/PAT、续期、拒绝、撤销的Mermaid流程 |
| [FAQ](product/faq.md) | 管理员、员工和集成常见问题 |
| [用户手册](ops/user-manual.md) | 管理授权、接入凭证、字典和个人历史操作 |

## 架构与决策

| 文档 | 内容 |
| --- | --- |
| [整体架构](architecture/architecture.md) | 上下文、部署单元、代码模块、依赖、时序 |
| [基础设施](architecture/infra.md) | 网络、Nginx、容量、状态存储、可选K8s |
| [安全架构](architecture/security.md) | 资产、信任边界、威胁、控制与验证范围 |
| [路线与门禁](architecture/roadmap.md) | 研发顺序、退出条件、风险与待交付资产 |
| [ADR0001](architecture/adr/0001-元数据库选用MySQL8.4.md) | 一期元数据库MySQL8.4 |
| [ADR0002](architecture/adr/0002-Redis缓存与权限失效.md) | Redis缓存与权威失效时效 |
| [ADR0003](architecture/adr/0003-独立Web会话与MCP-JWT.md) | Web会话、MCP JWT与PAT分离 |
| [ADR0004](architecture/adr/0004-自研作用域SQL守门内核.md) | 单解析器与自研作用域/列绑定 |
| [ADR0005](architecture/adr/0005-隔离后台与网关依赖.md) | 若依后台与网关依赖隔离 |
| [ADR0006](architecture/adr/0006-公开客户端刷新令牌轮换.md) | 完整公开客户端刷新扩展与严格轮换 |
| [ADR0007](architecture/adr/0007-审计持久准入与非分区存储.md) | 持久审计准入与非分区幂等 |
| [ADR0008](architecture/adr/0008-执行预算与安全取消.md) | 内存/结果预算与取消状态机 |
| [ADR0009](architecture/adr/0009-无状态HTTP与三服务部署.md) | 无状态Streamable HTTP与轻量部署 |
| [ADR0010](architecture/adr/0010-REST响应复用R与模块错误码.md) | REST 复用实际 R<T>、模块错误分段与协议隔离 |
| [ADR0011](architecture/adr/0011-跨库身份变更与权威版本.md) | 权威用户版本与可恢复的跨库改密；实施及故障验证状态见工作项 |

## 后端详细设计

| 文档 | 内容 |
| --- | --- |
| [数据库设计](backend/db-design.md) | ER图、表字段/索引、若依映射、事务与迁移 |
| [API契约](backend/api-spec.md) | REST/OAuth/MCP三层接口、DTO、错误、分页与幂等；目标契约不等于当前部署行为 |
| [接口变更合同模板](standards/api-change-template.md) | 开发时记录输入输出、权限、兼容、验证及状态；同步权威接口文档 |
| [SQL守门](backend/sql-guard.md) | 词法/AST、作用域、列/对象绑定、白名单与改写 |
| [认证设计](backend/authentication.md) | 会话、JWT/PAT、平台证明、公开refresh全链 |
| [权限设计](backend/authorization.md) | Allow/Deny、继承、委派、交集、缓存与时效 |
| [数据源与执行器](backend/datasource.md) | 动态池、Schema、宽行输出、事务、取消/清理 |
| [审计设计](backend/audit.md) | START准入、terminal、WAL恢复、加密/脱敏/解密 |
| [MCP工具输入Schema](backend/mcp-tools.json) | 四个工具的可机器读取输入约束 |

## 前端设计

| 文档 | 内容 |
| --- | --- |
| [前端开发指南](frontend/dev-guide.md) | React 工程入口、路由、API适配、会话/CSRF、组件和状态要求 |
| [业务页面覆盖快照](frontend/business-coverage.md) | 2026-10-29 Vue 页面与接口核对；仅作历史追溯，不代表 React 验收 |
| [UI资源与设计入口](frontend/ui-link.md) | 仓库内原型索引和外部设计稿当前状态 |
| [页面规格](frontend/page-spec.md) | 管理/个人页面字段、动作、权限、错误状态 |
| [低保真原型](frontend/wireframes.md) | 仓库内页面结构与关键交互，不冒充已发布Figma |

## 测试、运维与规范

| 文档 | 内容 |
| --- | --- |
| [测试计划](test/test-plan.md) | 环境、夹具、层级、门禁、性能与故障验证 |
| [测试用例](test/test-case.md) | 稳定TC编号、输入/步骤/预期与FR/R追踪 |
| [测试报告](test/test-report.md) | 2026-10-26 候选纵切快照、历史证据、阻塞与报告模板 |
| [部署手册](ops/deploy.md) | 环境、配置/产物契约、迁移、灰度与回滚 |
| [本机开发环境](ops/development-environment.md) | D盘工具与WSL环境、进入/启动/停止、自检、凭据与连接 |
| [Linux 开发服务器](ops/development-linux-server.md) | 本地 MySQL、迁移、管理员种子与启动脚本；React 迁移后须实测 |
| [运维手册](ops/operation-manual.md) | 指标/告警、故障处理、密钥、备份恢复 |
| [代码规范](standards/code-style.md) | 模块边界、Java/TS风格、安全、测试与DoD |
| [Git规范](standards/git-rule.md) | 分支/提交/PR、私有资产、版本与文档维护 |
| [Agent 开发流程](standards/agent-workflow.md) | 调查、任务合同、切片、验证、权限、交付、恢复与规则治理 |
| [决策笔记](standards/decision-notes.md) | 文档/ADR/工作项分工、真实取舍和笔记生命周期 |
| [工程标准与工具门禁](standards/quality-tooling.md) | p3c、Spotless/Checkstyle、React/ESLint/Prettier、MySQL 采用与配置来源 |
| [Agent 与设计资产治理](standards/agent-assets.md) | 根入口/桥接、DESIGN、.agents 技能、.claude 本机配置的职责、加载与变更边界 |
| [云效质量流水线记录](ops/yunxiao-quality.md) | 2026-10-26 Flow 与扫描配置快照；当前云端配置应另行核实 |
| [ASVS 安全自查](test/asvs-checklist.md) | ASVS 5.0.0 身份/会话/授权/日志的条款、证据与差距 |
| [范式参考与采用边界](reference/agent-development-research.md) | PDF、笔记项目、open-forge 的来源与适配理由 |
| [研发任务计划](../tasks/plan.md) / [待办](../tasks/todo.md) | 实施依赖与稳定父任务验收；实际进度查看工作项 |

## 输入、证据与历史材料

- [用户review](reviews/review.md)：本轮补全文档的直接依据。
- [最初需求](REQ.md)、[开源调研](reference/PTMCP_开源选型与二开方案.md)：历史输入，不再单独作为冲突技术基线。
- [源码审查](reviews/PTMCP_方案审查_2026-10-23.md)、[解析器复现](reviews/PTMCP_审查复现证据_2026-10-23.md)：保留证据与适用范围。
- [证据说明](evidence/README.md)、[M0登记](evidence/m0-gate-register.md)、[本轮文档验证](evidence/documentation-validation.md)、[MCP 能力核查 2026-10-30](evidence/mcp-capability-audit-20261030.md)：区分已有实验和待验证假设。

README不承诺任何参考项目的许可证已完成发布审查。复制/翻译dbx规则保留归属；内部skills/VQ不进入开源分发。真实公网地址、Figma/Swagger链接、镜像digest、驱动补丁和客户端版本仅在实际交付后登记。
