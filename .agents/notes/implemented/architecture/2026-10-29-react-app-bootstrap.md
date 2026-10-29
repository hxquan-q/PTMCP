# Agent Note: 新建 React 前端应用 ptmcp-ui-react 接管 Vue UI

Status: implemented

## Problem

用户明确拍板「更换成 React 体系」，要求按 React 生态（shadcn/Radix/Tailwind/Aceternity/Magic UI/Tremor 等参考系）实现新一批设计稿（MCP 接入配置总览、接入向导六步、数据源管理控制台、权限矩阵管理控制台），并继续优化。此前的评估笔记 [是否将前端技术栈从 Vue 迁到 React](../../rejected/architecture/2026-10-29-vue-to-react-migration.md) 建议不迁，但保留了「新建 React 应用逐步接管」作为确定要换之后的执行路径——本笔记记录的正是该路径的落地。

## Decision

在仓库内新建平级应用 `ptmcp-ui-react/`（不原地重写 `ptmcp-ui/`），Vue 应用与测试保持完整，两套 UI 可并行起 preview 对比，由用户评估后再谈接管节奏。

- **技术栈**：React 19 + react-dom、Vite 8（`@vitejs/plugin-react`）、TypeScript strict、Tailwind CSS 4（`@tailwindcss/vite`）。不引入组件库依赖——shadcn/Radix 风格的 OptionCard、Stepper、Drawer、Tabs 等按仓库既有 `--ptmcp-*` token 手写，保持零新增运行时依赖（与仓库「依赖最小化」标准一致）。
- **路由**：不装 react-router，用约 20 行 hash router（`#/me/integrations`、`#/admin/datasources`、`#/admin/permissions`），避免为一个尚未接管的路由表引入框架依赖；接管时再评估。
- **复用而非重写**：`src/preview/server.ts`（581 行合成后端）、`services/uuid.ts`、`services/portalIds.ts` 从 Vue 应用逐字拷贝——它们本就是纯 TS。`services/http.ts` 按同一契约（`request<T>` 返回 `{data, etag, requestId}`、POST/PUT 自动附带 `X-CSRF-Token`、preview 模式动态 `import('../preview/server')`）重写为 React 版。
- **设计体系**：整套 `--ptmcp-*` token（Fiori 密度 + Apple 质感的混合：蓝 #0071e3、canvas #f5f5f7、hairline 边框、8/12px 圆角）与单元件类（`.cell-icon[data-engine]`、`.cell-stack`、`.status-dot.is-*`、`.badge--*`、`.table-card`、`.data-table`、`.table-scroll`、`.table-footer`）原样移植到 `src/styles/index.css`，Vue/React 两边视觉同源。
- **端口**：dev/dev:preview 固定 `127.0.0.1:5175`（Vue 仍占 5174），`/backend/admin|authz|gateway` 代理配置与 Vue 版一致。
- **页面范围（首批按设计稿）**：
  - `views/me/IntegrationsPage`：列表（tabs + 筛选条 + 表格 + 详情抽屉）与「新建接入」五步向导（Stepper + OptionCard 网格 + 平台/客户端/连接方式选择 + JSON/YAML 配置模板 + 测试验证 + 完成态）单文件双模式切换。
  - `views/admin/DatasourcesPage`：满页 `table-card`（筛选条 + 复选表格 + footer 分页）+ 右侧详情 Drawer（基本信息/连接配置/同步状态/使用情况四个 tab）。
  - `views/admin/PermissionsPage`：视口锁死三栏工作台——授权主体（tab + 搜索 + 列表）/ 资源范围（数据源选择 + 树）/ 授权规则（compact table + 分页），栏间可拖拽分隔线，规则栏支持「专注模式」展开为 100%，底部「有效权限预览」默认折叠为一行、点击展开。

## Alternatives considered

1. **原地重写 `ptmcp-ui/` 为 React**
   - 最强理由：不留双栈。
   - 放弃原因：Vue 侧 15 个测试文件与 51 个 SFC 全部失效，且失去 A/B 对比能力；被否笔记的渐进路径本身就是为了避免这一步。
2. **装 shadcn/ui CLI + Radix + react-router + TanStack Table 全家桶**
   - 最强理由：与用户列出的参考系「对齐」。
   - 放弃原因：仓库标准要求依赖最小化；这批设计稿所需组件（OptionCard/Stepper/Drawer/表格）用 token 手写即可达标，引入全家桶反而把 Vue 侧「依赖太重」的问题复制过来。后续页面上量后若需要 TanStack Table 再单独立项。
3. **复用 `ptmcp-ui/` 目录、同仓双框架混跑**
   - 放弃原因：构建、lint、测试边界都会纠缠；平级目录是最干净的隔离。

## Consequences

- **收益**：React 生态通道打开；preview server 原样复用让视觉迭代零后端依赖；Vue 应用不受任何影响，切换与否的决定权留给验收之后。
- **代价**：双栈并行期客观存在——Vue 侧 15 个测试文件、完整页面集仍是事实上的功能全集；React 侧目前只有三页（接入配置、数据源、权限矩阵），其余路由是导航占位，接管前要逐页补齐并重建测试层（React Testing Library + jsdom）。
- **已验证**：`pnpm typecheck`（tsc --noEmit）通过；`pnpm dev:preview` 在 5175 起合成后端模式。
- **路由覆盖**：现已注册 20 条路由（`App.tsx` `ROUTES`），含 4 条前缀详情路由（`prefix: true`）：`/admin/jobs/{id}`、`/admin/audit/{requestId}`、`/admin/metadata/{id}`、`/admin/datasources/{id}`（`views/admin/DetailPages.tsx`：任务详情 2s 轮询非终态任务、审计详情含脱敏 SQL 与耗时指标、元数据详情含列结构表 + 字典合并展示、**数据源详情含可编辑表单 + etag/If-Match 乐观并发 + 412 冲突面板**）。剩余未移植：StepWizard 驱动的流程页、`EffectiveAccessPane` 独立页（React 目前把预览合并在 PermissionsPage 底部条）。路由解析顺序：先精确匹配非 prefix 路由，再匹配 `path.startsWith(prefix + '/')`，尾部段 `decodeURIComponent` 后作为 `id` 传入 `element(id)`。
- **已接线**：权限工作台「新增规则」接 `POST /api/v1/admin/acl/grants`、`撤销`接软删；「有效权限预览」接 `POST /api/v1/admin/acl/preview`；接入向导第 5 步「测试连接」接 `POST /api/v1/admin/datasources/{id}/test`，以返回的真实 `job.state` 判定成败（替换原 `setTimeout` 假成功）。
- **撤销契约（易踩坑）**：preview server 对带 `version` 的行在 DELETE 上也施加 `If-Match` 412 门禁（`server.ts`）。所有撤销辅助函数（`deleteGrant`/`revokeDelegation`/`revokeToken`/`revokeAuthorization`）必须先 GET 该行取 `etag`，再带 `{'If-Match': etag}` DELETE，否则静默 412 不生效。
- **已知缺口**：`GET /api/v1/admin/acl/effective` 的批量版本未接；资源树 datasource 切换仅显示首个 schema，树内搜索/过滤未做；StepWizard 驱动的「新建数据源」向导、`EffectiveAccessPane` 独立页、`AuditDecryptDialog`、`UserPasswordDialog`、`PlatformDetailView`、`ClientConfigurationForm` 等抽屉/弹窗类子页未移植；集成页其余 mock 行（INTEGRATIONS 常量列表）仍是前端常量，未接后端列表。
- **回退条件**：若验收判定 React 版没有带来可感知收益，删除 `ptmcp-ui-react/` 即可，Vue 侧零成本回退。

## Verification

- 2026-10-29（初始落地）：`pnpm typecheck` 通过；`pnpm dev:preview` 在 127.0.0.1:5175 起合成后端模式，接入配置/数据源/权限矩阵三页可交互。
- 2026-10-30（向导重设计 + 契约测试）：接入向导改为 4 步（选择平台/选择客户端/连接方式与认证/获取配置模板）+ 完成页（测试连接接真实 job 返回）；Playwright 走查列表→向导 4 步→完成页→测试连接全流程，截图 `verify-integrations.png`、`verify-wizard-s1/s2/s4.png`、`verify-wizard-done.png`（存仓库根，人工视觉接受仍 pending）。新增 `ptmcp-ui-react/tests/`（http/preview-server/uuid/portalIds，16 例）修复 React `http.ts` 两处契约回归：`wrapped ? nested?.data`→`root?.data`（信封双重解包）与裸 `JSON.parse`（大整数 ID 精度丢失）；`pnpm check`（eslint+prettier+tsc+build）全绿。未验证：真实后端（非 preview）下的联调；React 侧尚未有 jsdom/RTL 组件测试。
- 2026-10-30（管理面 CRUD 接管，切片 2–7）：数据源页对齐设计稿（种子 6 行/4 引擎/3 状态 + 抽屉补创建/更新时间）；角色/用户/部门/平台与客户端/管理委派五个管理页从只读列表升级为完整 CRUD（照 Vue orgPortalApi/controlPlane 契约：POST 幂等键、PUT/DELETE If-Match、412 冲突面板、用户分离子资源提交链式携带新 version、部门环防护、CONFIDENTIAL 客户端 secret 一次性弹窗、委派不能自委派与有效期窗口）；审计详情补敏感解密弹窗（理由 10–500 + 字段白名单 + 30s 倒计时自动清除 + aria-live）。全部经 Playwright 实操走查（verify-datasources3/verify-ds-drawer/verify-role-dialog/verify-departments/verify-client-secret/verify-delegation/verify-decrypt.png）+ 契约测试走真实传输层（fetch 代理 preview 实例，React 测试 16→36 例）。未验证：真实后端联调、窄屏/键盘全量自查（仍 pending）。
