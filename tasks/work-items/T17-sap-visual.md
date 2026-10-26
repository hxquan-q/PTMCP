# T17-sap-visual：SAP 视觉方案与核心页面效果图

ID: T17-sap-visual
Status: verifying
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-10-26

## 目标与边界

来源：用户指定 impeccable，根据现有功能需求设计 SAP 审美系统；问答明确仅做视觉设计，不修改前端，先效果图再确认。后续补充指定 `npx getdesign@latest add sap`。关联 T17、P-02/P-10/P-11；不将父任务标为完成。

沿用现有 DESIGN.md 的 SAP 视觉权威，产出数据源、授权矩阵、审计三个核心桌面页面效果图及全系统布局/状态方案。演示对象明确标注，不引入聊天、SQL 编辑器、假监控或二期权限功能。前端、后端、锁文件和既有 DESIGN.md 不改。

开工已有 README 修改及 .agents/config/docs/工程/scripts/tasks 等未跟踪内容，均保留。单 Agent 串行；仅写本工作项、PRODUCT.md、Impeccable 工作流记录与本次设计交付目录。

## 实施与验证

当前切片：确认 getdesign SAP 来源，沉淀产品上下文，生成独立大图，检查视觉一致性、文字与产品契约，同步设计交付说明。

只使用内置图像生成工具。合成数据与生产数据隔离；无业务连接、安装、提交、推送或发布。验收为设计产物与来源可追溯、三个主任务可读、SAP 规范一致；运行 python scripts/check.py。未改应用不重复 Maven/pnpm 构建，不把图片核对当 E2E。

## 结果与证据

2026-10-23 已产出：`docs/frontend/design/sap-20261023/`（datasources/permissions/audit 三页 desktop+mobile PNG、对应 HTML、design.css）。2026-10-26 核对：DESIGN.md 令牌与 design.css 一致；`python scripts/check.py` 退出0。图像可读性未做机器复核，留人工接受。

2026-10-26 用户授权实施（“完成前端页面的优化”），落地记录见 [T17-sap-impl](T17-sap-impl.md)；本项仍作为设计权威与效果图交付记录，人工视觉接受 pending。

## 交接

设计交付完成，待用户视觉确认；实施已由 T17-sap-impl 承接。无提交/集成/发布。
