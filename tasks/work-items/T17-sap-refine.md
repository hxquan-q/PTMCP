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
| 1 状态与过渡态 | 审计/平台中文状态、正常模式计数、准确提示；结构化权限/审计证据 | 204b697/6cf1f75 已推送；746项与门禁通过，四页浏览器及权限计算专项通过 |
| 2 全站布局 | 18 组头部、容器、筛选与表格底栏 | 首步迁移五个数据管理页面与增强公共头部，10次1440/390浏览器检查通过；其余页面及筛选表格待实施 |
| 3 工作台 | 权限三栏、审计速览、字典级联、客户端/PAT 引导 | 待实施 |
| 4 工程与契约 | DTO 边界、公共分页/异步/PageState、文档同步 | 待实施 |

每片运行 `.auto/measure.sh`（契约与后端测试）及自动 `.auto/checks.sh`（pnpm check、仓库检查）。最终还需 `pnpm --dir ptmcp-ui test:contracts`、项目环境 `python scripts/check.py --all`，真实浏览器检查 18 组页面正常/预览、桌面/窄屏、键盘及错误路径，记录截图/日志。测试数字不替代 UI 验收；新增测试仅防真实回归。

## 结果与证据

- 首步已通过 `.auto/measure.sh`：744 项（前端 78、后端 666），0 失败；自动 `pnpm check` 与 `python scripts/check.py` 通过。提交 204b697 已推送当前分支。
- 首步状态映射定向回归：首次运行 2 PASS/1 FAIL，未知状态 `toString` 误命中原型；改为 Object.hasOwn，仅识别自身定义状态。
- 第二步完整门禁：`.auto/measure.sh` 746 项（前端 80、后端 666），0 失败；自动 pnpm check/仓库检查通过；显式 `pnpm --dir ptmcp-ui test:contracts` 通过。提交 6cf1f75 已推送。
- 第二步：EvidenceFields 用语义化 dl/ol 递归展示对象、数组、标量与空值，保留未知键；权限预览与审计详情替换裸 JSON。有效资源的来源/原因逐项列表展示。
- Playwright Chromium 首轮发现审计详情立即 watcher 在 trailSequence 初始化前运行，修复初始化顺序，新增真实 SFC setup 回归。随后发现预览 sensitive-access 被通用详情路由吞掉、返回对象导致 rows.map 失败；补独立预览访问记录数组及实例隔离回归，仅含合成元数据，不存 SQL 明文。
- 浏览器证据：`.playwright-mcp/sap-refine/` 四页 1440px 截图；首步中文徽章/合成预览标记可见，审计结构化对象 ID/安全版本可见。最终态四页复验 console/pageerror 0，详情不再出现 rows.map 失败；实际读取审计详情截图确认结构化证据与徽章。全站及正常模式浏览器检查、完整 CI 仍待执行。
- 自审：展示层分离启停、连接健康、授权判定与执行结局；DENY 使用警示而非系统故障色，UNKNOWN 不推断失败，未知码保留原文，装饰图标 aria-hidden。未改网络/会话/鉴权。
- 权限专项：Playwright 选择主体、数据源、资源节点、平台并计算，断言使用版本区域显示安全版本、页面无 pre；读取当前有效资源后来源/原因列表存在；截图 `permissions-evidence.png`。
- 布局首步：PageHeader 支持 titleId/before/status/actions，标题统一30px/600，无动作不生成空容器；五页数据源列表/详情、任务、元数据列表/详情迁移，删除重复标题和外层padding及父级覆盖。两项真实组件渲染测试保护标题关联、转义和插槽信息保全。pnpm check通过；Playwright 5页×1440/390检查标题字号/字重/ID及横向溢出均通过，console/pageerror 0，记录 `.local/run/sap-refine-headers.jsonl`，截图同目录。
- 布局第二步：组织三页、权限页、个人门户八页共12个视图迁移公共头部，保留说明/动作/状态/aria-labelledby；清除重复 heading/h1/说明CSS。新增证据组件实际渲染回归覆盖嵌套未知字段、大整数ID、HTML转义、null/0/false/空集合和原型键。
- 布局第二步定向验证：pnpm test:contracts、pnpm check通过；12页×1440/390 Chromium检查标题30px/600/ID、无页面横向溢出及console/pageerror 0，记录 `.local/run/sap-refine-headers-portal.jsonl`，截图同目录。全量实验待运行。
- 布局第三步：新增 CursorFooter，首批10列表采用已加载计数/后续游标按钮/忙碌锁；数据源与元数据删除重复底栏CSS。新增显式 ptmcp-filter-bar/ptmcp-table-section（chrome、方角无四周边框、统一label与表格顶底线），补列表标题。运行组件渲染回归保护游标计数与按钮状态，不伪造total。pnpm check与契约测试通过。
- 第三步浏览器：10页×1440/390通过chrome背景/0圆角/计数/无横向溢出与0console/pageerror检查；首次发现scoped边框圆角覆盖公共样式，使用显式el-card类限定修复后重验。记录 `.local/run/sap-refine-filters.jsonl`，截图同目录；已实际读取数据源桌面截图。
- 状态收敛：全src的ElEmpty已替换PageState，新增默认插槽保留空态操作；PageState支持requestId显式复制、失败提示、并发锁和上下文/离页迟到反馈作废，尊重reduced-motion。useCursorPage单独返回当前requestId，重试/重置清理；errorText可选择不重复拼接编号。五个公共游标页及五个数据管理页接入错误恢复与编号复制。预览响应增加独立合成请求编号，不改变资源ID。
- 状态验证：实际SFC回归覆盖剪贴板失败/重复点击/切换ID迟到反馈；游标回归覆盖编号清理。pnpm check与契约测试通过。Playwright 4错误列表×2宽度验证真实剪贴板复制，6空列表×2宽度验证PageState与空态操作保留；0console/pageerror。读取窄屏截图发现重复错误/空表与重复编号，修正首批失败不渲染空表、编号只展示一次，再以断言复验通过。记录 `.local/run/sap-refine-state.jsonl`，截图同目录。完整实验待执行。
- 浏览器验证、截图、完整 CI 尚未运行，保持 NOT_RUN。

## 交接

当前焦点：第三步557dc05已推送、752项通过。空/错误态与requestId公共能力已通过定向及浏览器检查，待完整实验；门户/组织等其余错误上下文、余下列表布局和独立登录/授权/错误页仍待统一。全部目标仍在进行，未宣称完成或人工接受。后续逐项更新上表证据；明确阻塞才暂停目标。
