# T17-sap-refine：全站视觉与核心工作台交互收敛

ID: T17-sap-refine
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: NOT_RUN
Updated: 2026-10-27

## 目标与边界

续接 [T17-sap-impl](T17-sap-impl.md) 与 [T17-ui-business](T17-ui-business.md)。用户新要求覆盖 18 组页面，设计依据为 [DESIGN](../../DESIGN.md)、[页面规格](../../docs/frontend/page-spec.md) 与 [覆盖矩阵](../../docs/frontend/business-coverage.md)。

完整范围：统一 PageHeader/容器/筛选栏/表格底栏、StatusBadge；权限矩阵三栏与结构化版本/来源/判定解释；审计速览与结构化详情；字典级联 loading/竞态、四客户端选项卡与 PAT 引导；清理过时接入文案，说明组织字段 DTO 边界；统一游标过期恢复、异步任务及 PageState/requestId 复制。逐片实施，不缩减未完成范围。

不变量：Cookie+CSRF、内存会话、预览隔离、真实后端授权、敏感信息生命周期、既有业务操作和原有修改保留。不改后端鉴权语义，不把预览当真实业务验收，不创建未获授权的生产操作。

## 实施与验证

单 Agent。初始工作树干净，分支 xquan/feat-backend。源代码核对确认原始 JSON、过时审计提示和标题/容器差异仍存在；覆盖矩阵仍包含已被 T17-api-d 等实现替代的历史差距，后续按实际契约更新。

切片顺序及证据映射：

| 切片 | 验收项 | 当前证据/剩余工作 |
| --- | --- | --- |
| 1 状态与过渡态 | 审计/平台中文状态、正常模式计数、准确提示；结构化权限/审计证据 | 首步实现 StatusBadge 与状态映射，清理两列表文案；结构化证据待实施 |
| 2 全站布局 | 18 组头部、容器、筛选与表格底栏 | 待逐页清点、实现和浏览器检查 |
| 3 工作台 | 权限三栏、审计速览、字典级联、客户端/PAT 引导 | 待实施 |
| 4 工程与契约 | DTO 边界、公共分页/异步/PageState、文档同步 | 待实施 |

每片运行 `.auto/measure.sh`（契约与后端测试）及自动 `.auto/checks.sh`（pnpm check、仓库检查）。最终还需 `pnpm --dir ptmcp-ui test:contracts`、项目环境 `python scripts/check.py --all`，真实浏览器检查 18 组页面正常/预览、桌面/窄屏、键盘及错误路径，记录截图/日志。测试数字不替代 UI 验收；新增测试仅防真实回归。

## 结果与证据

- 首步状态映射定向回归：首次运行 2 PASS/1 FAIL，未知状态 `toString` 误命中原型；改为 Object.hasOwn，仅识别自身定义状态。修复后及完整门禁待运行。
- 自审：展示层分离启停、连接健康、授权判定与执行结局；DENY 使用警示而非系统故障色，UNKNOWN 不推断失败，未知码保留原文，装饰图标 aria-hidden。未改网络/会话/鉴权。
- 浏览器验证、截图、完整 CI 尚未运行，保持 NOT_RUN。

## 交接

当前焦点：完成切片 1 首步的定向/完整门禁及浏览器检查，再补结构化权限/审计证据。全部目标仍在进行，未宣称完成或人工接受。后续逐项更新上表证据；明确阻塞才暂停目标。
