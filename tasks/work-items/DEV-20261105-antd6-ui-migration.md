# DEV-20261105-antd6-ui-migration：前端引入 Ant Design 6 + ProComponents

ID: DEV-20261105-antd6-ui-migration
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: NOT_RUN
Updated: 2026-11-05

## 目标与边界

用户请求（/impeccable，已授权实施）：将前端自研组件体系替换为 Ant Design 6 + ProComponents；保留 Vite + React 19 + TypeScript 构建基础；尽量延续 Tailwind CSS 4 做布局与间距。

版本组合（2026-11-05 registry 实证）：

- `antd@6.7.0`（latest，peer react >=18，兼容 React 19.2）。
- `@ant-design/pro-components@3.1.15-9`（beta tag，peer `antd ^6.0.0`、react >=18；latest 2.8.10 只支持 antd 4/5，不可用）。
- `@ant-design/cssinjs@^2.1.2`（官方 Tailwind 4 CSS layer 集成：`@layer theme, base, antd, components, utilities` + `<StyleProvider layer>`）。
- `@ant-design/icons@^6.3.4`（直接 import 需声明为直接依赖，pnpm 严格 node_modules）。

边界：

- 不恢复 S4/S5/S6 已删除的委派管理面；管理门仍为布尔门（useAdminGate）。
- services 层 HTTP/DTO/CSRF/幂等契约不动；cursor 分页不得伪造 total/总页数。
- 保留：焦点圈禁/归还、Esc 关闭、live region、Toast 暂停语义、敏感值一次性展示、重复提交锁、aria 关联、reduced-motion 适配。
- OAuth 独立入口（vite.oauth.config.ts）迁移后必须可构建。
- 不触碰既有未提交修改：`src/services/data.ts`、`src/views/admin/dsForm.ts`、`tests/ds-create.test.ts`（他窗工作流），提交时按 hunk 归属排除。

组件映射（自研 → antd 6）：

| 自研（common.tsx / Toast.tsx） | antd 6 替代 |
| --- | --- |
| Dialog | Modal（保留 Esc/遮罩关闭/焦点管理，删除自研 focus-trap/inert 锁） |
| Drawer | Drawer |
| TextInput / SearchInput | Input（invalid→status="error"，保留 aria-label/describedBy） |
| Select | Select（value:string 契约不变） |
| Tabs（外置内容视图切换） | Segmented（避免无 tabpanel 的假 tablist） |
| Stepper | Steps |
| Alert（info/warning/danger） | Alert（danger→error） |
| Pagination | Pagination（外层保留 nav aria-label="分页"） |
| EmptyState | Empty + 四 kind 图标/文案逻辑保留 |
| StatusDot / Badge | Tag（颜色映射保对比度） |
| Toast 栈 | notification（duration=0 + 自管定时器保暂停语义；danger 组 role=alert 保留） |
| 原生表格（各页面） | Table / ProTable（total 已知的客户端分页表用 ProTable；cursor 表禁 total） |
| AppShell 导航 | Layout + Menu + Breadcrumb + Dropdown；Ctrl+K 搜索浮层保留自研 combobox 语义 |

保留为业务组合（非控件，Tailwind/token 样式）：Field、PageHeader、StatCard、CellIcon、DrawerFields、GlobalSearch、OptionCard、CharReveal、LoginBackdrop、useMagnetic 等按需逐步收敛。

## 实施与验证

切片计划：

1. S1 基础：依赖安装、main.tsx（StyleProvider layer + ConfigProvider zh_CN + theme colorPrimary #0f62d6）、index.css layer 声明与旧样式清理。
2. S2 控件：common.tsx 内部换 antd 实现（保持对外 API），Toast.tsx 换 notification 适配。
3. S3 外壳：AppShell 换 Layout/Menu/Breadcrumb/Dropdown，保留视角切换/Ctrl+K/skip-link 语义。
4. S4 页面：逐页原生 table/button 换 Table/Button；total 已知列表试点 ProTable。
5. 验证：`pnpm test`、`pnpm check`（lint/format/typecheck/build/build:oauth）、根 `python scripts/check.py`；浏览器桌面/移动截图、键盘与弹层抽查（预览模式合成数据，真实接口联调另计）。
6. Impeccable 收尾：detect --json 一次 → finish-reviewer（独立只读）→ documenter 同步 DESIGN.md 与 sidecar。

已知环境注意：pnpm 需 `--ignore-workspace`（家目录 workspace 遮蔽）；脚本用 node_modules/.bin 直跑绕过 deps 校验；loopback 受 mihomo TUN 影响需超时重试。

## 结果与证据

2026-11-05 S1–S4 首批切片完成：

- 依赖：antd 6.7.0、@ant-design/pro-components 3.1.15-9、@ant-design/cssinjs 2.1.2、@ant-design/icons 6.3.4（pnpm peers check 无冲突）。
- S1 基础：main.tsx StyleProvider layer + ConfigProvider(zh_CN, colorPrimary #0f62d6)；index.css 顶部声明 `@layer theme, base, antd, components, utilities`。
- S2 控件：common.tsx 全部 22 个导出对外 API 不变，内部换 antd（Modal/Drawer/Input/Select/Segmented/Steps/Alert/Pagination/Empty/Tag）；Tag 保留 AA 校准色并加 alignSelf 防拉伸；Toast 栈保留自研（antd notification 无悬停暂停/分组常驻/live region，保留理由见「实施与验证」边界）。
- S3 外壳：AppShell 未动（后续切片）；useFocusTrap 收敛为自身归还焦点，删除无调用方的 useOverlayLock（antd Modal/Drawer 自带滚动锁与圈禁）。
- S4 页面：DatasourcesPage → antd Table（rowSelection//locale emptyText）；RolesPage → ProTable 试点；AuditPage 双表 → antd Table（游标分页保持 pagination=false，不伪造 total）；`.btn` 全站清扫 89 处 → antd Button（含 OAuth/LoginPage 的 htmlType="submit"）；遗留 reset 改为 `:not(.ant-btn)`/`:not([class*='ant-'])`（未分层规则会覆盖 antd layer 的按钮白字——实测发现并修复）。
- 关键坑：index.css 遗留 `button{color:inherit}` 未分层规则压过 antd 主按钮白字（DOM/伪元素均无遮挡，按钮文字被继承色覆盖）；antd 6 Drawer 弃用 width → size。
- 测试：199/199 通过（design-system.test.ts 空态断言同步到 antd Empty 模板字符串结构，aria-hidden 契约不变）。
- 构建：`vite build` ✓（chunk >500kB 提示，antd 引入后预期，分包优化留后续）；`vite build --config vite.oauth.config.ts` ✓；lint/tsc/prettier ✓。
- 根 `python scripts/check.py`：除 `verify_commits` 外全部 PASS；verify_commits FAIL 为既有提交 e3e9e54 头部 78 字符超 72 限制（他窗历史提交，未改动）。
- 浏览器证据（playwright，预览合成数据）：`antd-mig-ds-desktop3.jpeg`（数据源页桌面）、`antd-mig-roles.jpeg`（ProTable）、`antd-mig-role-dialog.jpeg`（Modal 表单）、`antd-mig-drawer.jpeg`（Drawer+Segmented+footer）、`antd-mig-mobile.jpeg`（390×844，表格横向滚动）。弹窗 Esc、行选择、加载态均走查。
- 真实接口、权限、OAuth 端到端未验证（预览合成数据）。

剩余范围（后续切片）：

1. AppShell → antd Layout/Menu/Breadcrumb/Dropdown（含两个 btn--icon 按钮与移动端导航）。
2. 其余页面原生 `<table>` → antd Table（IntegrationsPage、PermissionsPage、OrgPage、PlatformsPage、MetadataPage、detail 页、me pages）；`.link` 行内链接 → Button type="link" 收敛。
3. CSS 清理：删除已无引用的 .btn/.input/.data-table 等遗留块（现全部仍有未迁移页引用）。
4. 主构建 chunk 分包（antd manualChunks）。
5. Impeccable finish-reviewer + documenter（DESIGN.md 与 sidecar 同步）随迁移完成切片执行；本次切片已跑 detect（见下）。
6. 真实接口/权限/OAuth 端到端验证。

## 交接

- 版本结论：ProComponents latest 2.8.10 不支持 antd 6，必须用 beta 3.1.15-9；这是本工作项的关键前提。
- 既有历史 note（2026-10-29 react-app-bootstrap「不引入组件库」）被本请求显式覆盖。
- 设计方向：用户固定 Ant Design，不再提问技术/风格；Impeccable 方向合同待随 S1 记录。
