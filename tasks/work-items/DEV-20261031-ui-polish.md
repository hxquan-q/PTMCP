# DEV-20261031-ui-polish：控制台视觉与可达性逐页优化

ID: DEV-20261031-ui-polish
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-10-31

## 目标与边界

来源：用户要求“一个个优化页面和细节”，参考随附设计图与 shadcn/Apple/HeroUI 等组件站风格。开工基线 `dev`/`5047728`，工作树另含 [DEV-20261031-audit-fixes](DEV-20261031-audit-fixes.md) 的未提交改动，本项不接管、不提交、不覆盖那些路径。

用户提供的 6 张设计图与浏览器截图在本宿主环境没有可读图像通道（`Read` 对 PNG/JPG 与 `take_screenshot` 产物均返回空描述），因此本项不声称按像素比对设计图。改以可度量证据推进：路由遍历 + 计算样式审计（对比度、点击热区、可访问名称、溢出、截断、坏 token），并把结论对齐 [DESIGN.md](../../DESIGN.md)、[页面规格](../../docs/frontend/page-spec.md) 与[前端指南](../../docs/frontend/dev-guide.md)。

不变量：不改 REST 契约、权限判定、SQL guard 与凭证只写不回显；不因美化引入无后端支撑的假控件或假数据；不放宽任何门禁。

## 实施与验证

### 切片 U1：共享层 token 与控件（覆盖 16 个路由）

审计基线（1440×900，隔离合成预览 `http://127.0.0.1:25175`，非真实后端）先给出可复现缺陷：

- `--ptmcp-blue-bg` 被 `AppShell` 侧栏卡片渐变引用但从未声明，整条渐变退化为透明。
- 每个页面 3～5 处文本低于 WCAG 2.1 AA 4.5:1：`#0071e3` 蓝文本在 `--ptmcp-selected` 浅蓝底只有 4.19:1（激活导航、分页当前页、行内链接），`--ptmcp-text-faint #667582` 在 `#f5f5f7` 上 4.35:1（面包屑、搜索占位、分组标题）。
- 图标按钮命中区 17×17、分页箭头 13×13、表格行内链接 13px 高，低于 WCAG 2.2 AA 最小目标尺寸。
- 数据源页存在无 `onClick` 的「查询」「更多筛选」「批量操作」与一个无名称的图标按钮；`⌘K` 徽标只是文本，没有键盘监听；搜索弹层不支持 Esc 与方向键。

同批修正：

- `index.css` 新增 `--ptmcp-blue-text: #0061c9`（白底 5.91:1、浅蓝底 5.27:1）供链接/激活态/当前页使用，`--ptmcp-blue` 保留为填充色（白字 4.70:1 达标）；`--ptmcp-text-faint` 加深到 `#5b6a77`（浅灰底 5.11:1）；补声明 `--ptmcp-blue-bg`。控件层新增 `.btn--icon`（32×32）、`.page-btn`（28×28，含省略号窗口）、`.nav-item`、`.tab-btn`、`.option-card`、`.chip-btn`、`.cmd-row`、`.search-trigger`，并补齐输入框 hover/focus ring/disabled、按钮 active 反馈、表格行过渡与 `prefers-reduced-motion`。
- `AppShell.tsx`：侧栏行、身份 chip、关闭与通知按钮改用上述类；移除始终走不到的「更多」图标分支和无后端的铃铛（保留可用出口，不伪造通知数据）；`⌘K`/`Ctrl+K` 真正打开命令面板，弹层支持 Esc、↑↓ 循环、⏎ 打开、悬停同步高亮与底部快捷键提示。
- `common.tsx`：Dialog/Drawer 关闭按钮改 32×32；分页换 `.page-btn` 并加 `aria-current="page"`、页码可访问名称与 `…` 折叠；Tabs 改为 roving tabindex + 左右方向键；OptionCard/GlobalSearch 的内联样式移入类，使选中态与 hover 可分离。

复验结果（同脚本重跑 16 路由）：AA 对比度不达标项 0（基线约 60 处）；16 页中 15 页热区问题清零，剩余仅 16px 原生复选框（切片 U2 处理）与数据源页那个待删的死按钮；坏 token 0；截图未出现横向溢出、文本截断或 11px 以下字号。

实际检查（项目环境 Node 24 / pnpm 11.9.0）：

- `node --experimental-strip-types --test tests/*.test.ts`：退出 0，164 tests / 164 pass / 0 fail。
- `pnpm exec eslint src/components/common.tsx src/layouts/AppShell.tsx --max-warnings 0`：通过；`tsc --noEmit`：通过；`pnpm build`：通过（CSS 19.84 kB / gzip 4.98 kB）。
- `pnpm exec prettier --write` 已用于上述三个文件；仓库级 `pnpm check` 仍被 DEV-20261031-audit-fixes 未提交的 `platformForm.ts`（`replacementRequired` 声明未使用，eslint error）和未跟踪的 `tests/browser/`（Prettier 未通过）挡住，本项不代为修正。
- 头部 `Verification: PASS` 描述已完成切片（U1 共享层、U2 P-10）的适用检查全部通过；本项仍是 `in_progress`，其余页面逐页切片未完成，仓库级 `pnpm check` 因上述外部失败未绿。
- 浏览器行为验证：`⌘K` 打开→输入“数据”得 4 条（导航 + 合成数据源）→`ArrowDown`+`Enter` 落到 `#/admin/metadata` 并关闭弹层→再次 `Ctrl+K` 打开→`Escape` 关闭；激活导航行高 36px。

### 切片 U2：P-10 权限矩阵逐页细节

开工前复审计（同一浏览器脚本，选中态被真正点亮后才测得）暴露 U1 漏检的一处对比度回归：资源树选中行用 `--ptmcp-blue` 写在 `--ptmcp-selected` 上，实测 4.19:1。同批与页面级缺陷一起修：

- 资源树原本用 `<div onClick role="treeitem">`，键盘完全不可达，也不符合 ARIA tree 模式。改为 `<button class="tree-row" role="treeitem">` + 容器 `role="tree"`/子层 `role="group"` + `aria-level`，并实现单 Tab 焦点（roving tabindex）与 `↑↓ ←→ Home End`：←→ 折叠/展开或进入首个子节点，容器统一 `onKeyDown` 依据 `data-key/data-depth/data-branch/data-open` 导航。选中态样式移入 CSS，蓝文本改走 `--ptmcp-blue-text`。
- 拖拽分隔线只有 6px 且不能键盘操作：加 `tabIndex`、`aria-valuenow/min/max/valuetext` 与 ←→ 4% 步进（复用纯函数 `resizeColumns` 的 [14,50] 钳制），`::after` 把命中区扩到 24px 宽而视觉不变。
- 预览面板原实现把上一组主体的结论留在页面上：切换主体/主体类型/平台/数据源即清空 `previewResult`；结论改为持有完整 `Preview`，展示服务端 `evaluatedAt` 与 `nextBoundaryAt`；给定未来时点时标注「未来时点模拟，不可据此执行查询」；折叠按钮补 `aria-controls`，面板补 `role="region"`。
- 三处把失败伪装成空的分支（`catch` 后只清数组）改为可辨识错误态并给出重读入口：主体目录失败清空选择、规则读取失败提示列表不可信、资源树失败时禁用「+ 新增规则」（页面规格要求资源树加载失败禁止提交）。空态文案区分“没有匹配的主体/该源暂无可见元数据（可能是待同步）/未选择数据源”。
- 新增规则对话框：到期时间原样允许过去（会写入一条永不生效的规则），现校验 `validTo > now` 并给 `min`；理由补 500 字上限；移除对象级规则里语义为假的「包含下级对象」勾选（`includeDescendants` 恒发 `false`），改为紧邻配置说明「READ ALLOW 隐含 META；META DENY 阻断 READ，DENY 优先」。规则表补「有效期」列并把 ALLOW/DENY、ACTIVE 状态本地化为允许/拒绝、生效中/已撤销。
- 未接线也未按页面规格实现的部分保持未验证记录：页面规格要求的“组织授权管理员不得自增 META/READ，需解释拒绝并指向平台管理员”未做——当前 `PermissionsValue` 只有 `can()`，拿不到本人主体与委派范围，需后端契约确认；`NAMESPACE`/`DATASOURCE` 级授权同样未开放，因服务端对这两类 `resourceGeneration` 的取值没有可核验来源，不猜。预览的 `reasonCodes` 也不在现契约内。

复验（隔离合成预览，非真实后端）：树 4 行中 `tabIndex=0` 恰 1 个、`role=group` 1 个；`↑↓ Home End ←` 焦点落点分别为 `obj:1 / schema:synthetic / obj:3 / schema:synthetic`；选中行实测对比度 5.66:1、行高 32px；分隔线键盘两次 `→` 后 `aria-valuenow` 25→37 且栅格模板同步；对话框到期填 `2020-01-01` 时内联报「有效期须晚于当前时间…」且未提交，改 `2030-06-01` 保存成功、新行显示「至 06/01 10:00」；预览当前时点得 3 条决策、未来时点出现模拟标注、切换主体后旧结论清空。

实际检查：`node --experimental-strip-types --test tests/*.test.ts` 退出 0（164/164）；`tsc --noEmit` 通过；`pnpm exec eslint src/views/admin/PermissionsPage.tsx --max-warnings 0` 无输出；`pnpm exec prettier --write` 已跑；`pnpm build` 通过（CSS 21.17 kB / gzip 5.17 kB，JS 451.37 kB / gzip 123.99 kB）。

### 尚未做

数据源/平台/接入配置等其余页面逐页细节（死控件接线或移除、16px 复选框命中区、空/加载/错误/409 态、窄屏），以及 DESIGN.md 记录的 SAP 调色板（`#0057d2`）与实现所用 Apple 调色板（`#0071e3`）之间的契约偏差处置——后者需用户确认以哪一侧为准，未擅自动手。P-10 的自增权防护与 NAMESPACE/DATASOURCE 级授权按上文理由保留未实现。

## 结果与证据

设计层：本切片修复的是可度量的可达性与控件缺陷，不等于按设计图完成视觉还原（图像通道不可用）。验证层：自动化与浏览器交互已跑，人工体验接受与真实后端联调未做。本项不改后端，也不把框架构建当作业务验收。

## 交接

焦点在切片 U3：P-13 我的 MCP 接入配置（`src/views/me/IntegrationsPage.tsx`，本人可写路径，且有对应设计图），随后 P-04 元数据、P-05/P-06/P-07 组织页。P-02 数据源页的死控件与复选区仍待处理，但该文件与 `DatasourcesPage.tsx`、`services/*`、`dsForm.ts`、`platformForm.ts` 等正被 DEV-20261031-audit-fixes 的未提交改动占用，接管前须先确认对方落地状态，避免同文件互踩。每个切片只暂存自己改过的路径。预览端口 25175 用完须停止并确认无监听。
