# PTMCP 产品上下文

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

依据 [PRD](docs/product/PRD.md)：平台管理员管理数据源、平台与组织；组织授权管理员在委派范围内管理授权；安全审计员追踪工具调用；员工在个人门户了解可用数据并配置外部 Agent 接入。

## Product Purpose

企业权限管控型问数 MCP 网关。外部 Agent 负责生成 SQL 和解释结果；PTMCP 负责身份、平台 Scope、目录与结构过滤、只读 SQL 守门、受控执行及审计。

## Operating Context

中文企业工作台，主要在桌面浏览器完成配置和排障；时间按 Asia/Taipei 显示。管理视角与个人视角分别导航。详细范围沿用 [页面规格](docs/frontend/page-spec.md)，不另立业务基线。

## Capabilities and Constraints

- 一期设计含 MySQL/PostgreSQL 数据源、元数据字典、组织账号、平台与客户端、授权矩阵、审计、个人字典/接入/PAT/OAuth 授权/历史。
- 平台 Scope 与用户有效权限取交集；META 与 READ 分开；DENY 优先级遵循领域契约。管理身份不隐含业务 READ。
- 不提供自然语言聊天、在线 SQL 执行台、审批中心、行列权限或无契约运行大屏。
- 2026-10-26 已有后端候选纵切（含本机 MCP 问数闭环与只读管理接口）和 18 组前端页面/开发预览；设计图、预览数据与工程绿灯均不代表生产部署、独立安全评审或人工功能接受。
- 2026-10-23 用户明确先做视觉设计与效果图；2026-10-26 用户授权实施，SAP 令牌层已落地到前端页面，人工视觉接受仍 pending。

## Brand Commitments

用户明确指定 `npx getdesign@latest add sap` 对应的 SAP 方案。现有根目录 [DESIGN.md](DESIGN.md) 是本次视觉权威；不将 PTMCP 标识替换成 SAP 标识，不声称 SAP 官方产品或认证。

## Evidence on Hand

[PRD](docs/product/PRD.md)、[页面规格](docs/frontend/page-spec.md)、[前端指南](docs/frontend/dev-guide.md)及根目录 SAP DESIGN.md。没有真实生产指标、客户背书或业务数据；效果图中的对象与记录必须标注为演示。

## Product Principles

1. 操作围绕实际管理任务，重要身份、范围和状态始终可辨。
2. 可见目录、可读数据和可执行管理操作分别解释。
3. 先呈现事实和可追踪标识，再解释错误与恢复动作。
4. 设计、实现、验证和人工接受分别记录。

## Accessibility & Inclusion

沿用前端指南的 WCAG 2.2 AA 目标：键盘操作、可见焦点、文本状态、输入标签、核心桌面流程及窄屏重排。静态效果图不能证明这些目标已实现。
