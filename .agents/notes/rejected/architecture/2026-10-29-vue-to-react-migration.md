# Agent Note: 是否将前端技术栈从 Vue 迁到 React

Status: rejected — 用户已于 2026-10-29 拍板更换 React 体系，本文「不迁」的建议被否决；其「新建 React 应用逐步接管」的重访条件被采纳为执行路径，见 [React 前端新应用 ptmcp-ui-react](../../implemented/architecture/2026-10-29-react-app-bootstrap.md)。

## Problem

用户反馈「页面效果还是不太行」，提出深度调研是否把前端从 Vue 3 + Element Plus 整体迁到 React（以便用 shadcn/ui、Aceternity 等 React 生态组件库）。需要判定：视觉差距的根因是不是框架，以及迁移的真实成本是否值得。

## Proposal

**不建议此时整体迁到 React**，先继续在当前 Vue 栈上收敛视觉差距；若用户仍要求 React，采用「新建 React 应用逐步接管」而非原地重写。

证据（2026-10-29 实测 `ptmcp-ui/`）：

- **代码规模**：51 个 `.vue` + 33 个 `.ts` ≈ 15,077 行；其中约 2,000 行（`services/`、`adapters/`、`composables/` 纯 TS）与栈无关可带走，**约 13,000 行 UI/视图要逐页重写**。
- **组件表面**：37 种 Element Plus 组件、约 1,500 处调用点（ElButton 344、ElFormItem 267、ElTableColumn 228、ElTable/ElTableColumn 46 处）。
- **最难迁移点**：46 处声明式 `ElTable` 列定义——React 主流表格（TanStack Table）是命令式 headless，列模板/作用域插槽没有机械对应，要逐表重写渲染逻辑。
- **测试基建全绑 Vue**：15 个测试文件用自定义 `compileScript + registerHooks + createRenderer` 挂载器直接跑真实 SFC；迁 React 要整批换成 React Testing Library + jsdom，等于重建测试层。
- **深度定制点**：16 个文件依赖 `--el-*` CSS 变量与 `::v-deep` 穿透；28 个 `IconEp*` 走 unplugin-icons（React 需换 `@iconify/react` 重新接线）。
- **沉没成本**：本 session 刚完成的 4 个新组件（DetailDrawer/StepWizard/StatCard/GlobalSearch）+ 15 页名列推平 + 全局 token 体系，迁移即作废。

## Alternatives considered

1. **原地重写为 React（用户提议的最强形式）**
   - 最强理由：解锁 shadcn/ui、Radix、Aceternity、Magic UI、Tremor 等 React-only 组件库；团队若 React 经验更深则长期收益成立。
   - 放弃原因：视觉差距 95% 由设计 token、间距、密度、留白决定而非框架；React 不会自动变好看，只是把同一套 `--ptmcp-*` 决策重写一遍。换 1.5 万行 + 重建测试去换一个换皮库，成本比继续驯化 Element Plus 高一个数量级。
   - 重访条件：若验收确认 Element Plus 的表格/表单根本无法达到目标密度与质感（而非"还没调好"），或后端/团队整体转向 React，则可立项。

2. **换 Vue 生态的 shadcn 等价物（`shadcn-vue`、Naive UI、PrimeVue）**
   - 最强理由：不换框架、保留全部业务/测试基建，又能拿到接近 shadcn 的设计语言；Tailwind 已在 devDeps。
   - 放弃原因：shadcn-vue 质量与维护弱于原版；引入第二套组件体系会与 Element Plus 主题/优先级互殴，等于再做一轮全站换皮，性价比不如在现有 token 上收尾。
   - 重访条件：若确定要弃用 Element Plus 但不迁 React，则此路径优于 React。

3. **维持 Vue + Element Plus，继续按设计稿收敛（当前默认）**
   - 最强理由：已建立 `--ptmcp-*` token、`fill-page` 布局契约、`ptmcp-cell-*`/`ptmcp-status-dot` 工具类与 4 个新组件；视觉问题可在该体系内继续调，无需付出迁移税。
   - 风险：若根因真是 Element Plus 表达力上限，继续调会变成打补丁。

4. **新建 React 应用逐页接管（渐进迁移）**
   - 最强理由：不冻结现有功能，按页灰度切换，风险可控。
   - 放弃原因：仍需双栈并行维护期，且同样要解决表格/表单重写与测试基建复制；只在「确定要换」之后才有意义。

## Acceptance criteria

- 若选「继续 Vue」：以设计稿逐页验收（间距/密度/状态色/抽屉/向导/⌘K），列出仍不达标的具体组件清单，而非笼统「效果不好」。
- 若选「迁 React」：立项前先交付一份 React 技术选型清单（组件库、表格、表单、图标、测试框架）与一页高保真原型对比 Element Plus 现状，作为是否启动的放行门。

## Risks

- **收益（若迁）**：长期组件生态更丰富，招聘面更广。
- **代价（若迁）**：约 1.5 万行重写、15 个测试文件重建、46 个表格逐页改写、双栈过渡期；期间业务功能冻结或双写，回归风险高。
- **未验证假设**：用户「效果不太行」具体指哪些页面/元素未列明——若根因是某几个具体组件（如表格密度、空状态），应定点修复而非换栈。
- **收窄/回退**：任何 React 迁移必须先有并行运行的 PoC 页证明收益，未达验收则回退继续 Vue。
