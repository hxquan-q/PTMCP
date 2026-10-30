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
- 头部 `Verification: PASS` 只描述已交付切片 U1 的适用检查（测试、类型、构建、16 路由复审计）全部通过；本项仍是 `in_progress`，逐页切片未开始，仓库级 `pnpm check` 因上述外部失败未绿。
- 浏览器行为验证：`⌘K` 打开→输入“数据”得 4 条（导航 + 合成数据源）→`ArrowDown`+`Enter` 落到 `#/admin/metadata` 并关闭弹层→再次 `Ctrl+K` 打开→`Escape` 关闭；激活导航行高 36px。

### 尚未做

数据源/权限矩阵/平台/接入配置等逐页细节（死控件接线或移除、复选框命中区、空/加载/错误态、窄屏），以及 DESIGN.md 记录的 SAP 调色板（`#0057d2`）与实现所用 Apple 调色板（`#0071e3`）之间的契约偏差处置——后者需用户确认以哪一侧为准，未擅自动手。

## 结果与证据

设计层：本切片修复的是可度量的可达性与控件缺陷，不等于按设计图完成视觉还原（图像通道不可用）。验证层：自动化与浏览器交互已跑，人工体验接受与真实后端联调未做。本项不改后端，也不把框架构建当作业务验收。

## 交接

焦点在切片 U2：逐页处理数据源等页面的死控件与复选区，再推进其余页面。共享层切片只暂存 `index.css`、`AppShell.tsx`、`common.tsx` 与本工作项，避免混入他人未提交改动。预览端口 25175 用完须停止并确认无监听。
