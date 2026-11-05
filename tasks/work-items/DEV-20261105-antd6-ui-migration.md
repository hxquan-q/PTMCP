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
2. 其余页面原生 `<table>` → antd Table（PermissionsPage、OrgPage、PlatformsPage、MetadataPage、detail 页）；`.link` 行内链接 → Button type="link" 收敛。
3. CSS 清理：删除已无引用的 .btn/.input/.data-table 等遗留块（现全部仍有未迁移页引用）。
4. 主构建 chunk 分包（antd manualChunks）。
5. Impeccable finish-reviewer + documenter（DESIGN.md 与 sidecar 同步）随迁移完成切片执行；本次切片已跑 detect（见下）。
6. 真实接口/权限/OAuth 端到端验证。

## 交接

- 版本结论：ProComponents latest 2.8.10 不支持 antd 6，必须用 beta 3.1.15-9；这是本工作项的关键前提。
- 既有历史 note（2026-10-29 react-app-bootstrap「不引入组件库」）被本请求显式覆盖。
- 设计方向：用户固定 Ant Design，不再提问技术/风格；Impeccable 方向合同待随 S1 记录。

### 2026-11-05 用户反馈修复（S4 后补丁）

用户截图反馈四处异常，逐一定位：

1. **详情页右侧大片空白**（数据源/元数据详情）：卡片自带 `maxWidth: 860` 且左对齐为迁移前既有样式（HEAD~1 已存在），但宽屏上观感破损。修复：DatasourceDetailPage / MetadataDetailPage / AuditDetailPage / HistoryDetailPage / DictionaryDetailPage 五个详情页内容列包 `maxWidth: 860 + marginInline: auto`，标题/返回/卡片/页头按钮同列对齐。
2. **状态胶囊丢底色观感**（详情页「运行中/连接未知」）：底色变量都有定义（bg-warning #fffaeb 等极浅），但 antd Tag 默认 4px 圆角+窄内边距让浅色底不可辨识（旧 .pill 是 24px 高胶囊）。修复：StatusDot/Badge 恢复旧 pill 几何（borderRadius 999、paddingInline 10、fontWeight 600、fontSize 12.5、lineHeight 22px）。
3. **Alert 文案居中**（权限矩阵空态）：Alert 作为 EmptyState action 传入，antd Empty 的 children 区 `text-align: center` 被 Alert 继承（旧 .empty-state 是 flex 居中、不设 text-align，无此问题）。修复：PermissionsPage 中 Alert 移出 action，独立成行并包 `textAlign: left` 容器。
4. **操作日志空表**：真实环境无操作记录时 antd Table 空表头照常渲染，属正常空态（预览环境验证有数据路径正常）；空态文案走 sibling EmptyState。
5. 访问审计筛选行/主体搜索（Segmented+Input）复验无缺陷，为控件换 antd 后的正常观感。

验证：lint/tsc/prettier/build/build:oauth 全过、tests 199/199；playwright 复验数据源与元数据详情居中、状态胶囊、权限页。

### 2026-11-05 真实测试库接入（S4 回归修复 + 环境排障）

用户要求停用演示数据、接真实测试库（121.15.172.251:23306 / asd_cloud_base）。排查链与修复：

1. **S4 回归（后端真 bug）**：V20 迁移已 `DROP TABLE admin_delegation`，但 `DatasourceTargetIsolation.retire()` 仍执行 `UPDATE admin_delegation ...` → 目标变更事务 SQLException→DEPENDENCY_UNAVAILABLE(503)→整体回滚，**数据源永远无法改 host/端口/库名**。修复：删除该语句；同步清理 `DatasourceTargetIsolationTest`（truncate 列表、两处委派 INSERT、5 处委派断言）与 `SessionApiTest` truncate 列表。该测试类自 S4 起即因删表而坏（seed 直接 DELETE 不存在的表）。
2. **环境缺口**：`allowed-ports` 默认仅 `3306,5432`，dev.env 补 `PTMCP_ADMIN_DATASOURCE_ALLOWEDPORTS='23306'`。
3. **TLS 主机名机制**：该库为 MySQL 自动生成证书（无 IP SAN），VERIFY_FULL 必须用证书 CN 作 host，由 `.local/dev/hosts`（`-Djdk.net.hosts.file`）钉到 IP。原 host 字段本就是 CN，**不是笔误**；中途改成 IP 反而触发 DRIVER_ERROR，已恢复。
4. **Cursor 端口劫持**：Cursor 在 127.0.0.1/[::1] 上二次监听 25173/28080-82，loopback 请求被转发器截走，导致前端登录间歇 401/429/空响应（curl 直连正常、代理路径诡异失败）。绕过：走 LAN IP `http://192.168.1.18:25173`；2026-11-05 起本机验证均用该地址。
5. **UI_PUBLIC**：dev.env 置 `PTMCP_UI_PUBLIC='true'` 与 25173 公网端口一致。

结果（API + UI 双重确认）：

- `PUT /admin/datasources/1` 200（configVersion 4→6）；
- `POST .../test` → job SUCCEEDED，health=**HEALTHY**；界面「运行中 · 连接正常 · 凭据已配置」；
- `POST .../sync` → job SUCCEEDED，processed **808** 对象，快照 #3 发布；
- admin 服务以 dev.env 全量环境重启（`.local/dev/run/admin.out.log` 落盘）；前端 25173 以正常模式（非 frontend-preview）运行。

遗留：该测试类 Testcontainers 运行结果见提交前记录；dev.env 与 hosts/信任库属本机私有环境（`.local/` 未入库）。

### 2026-11-05 真实接口联调：前后端契约缺口修复

用真实后端（ASD Cloud Base，808 对象）逐页走查管理端与个人门户，发现并修复三处真实断裂：

1. **元数据列表/详情缺字段（后端契约）**：前端 `MetadataObject` 要求 `tableName` 与 `datasourceId`，admin 元数据 API 的 `Item`/`Detail` 都未返回 → 详情页「表名」「数据源」空白，列表按 `undefined` 渲染。修复：`MetadataCatalogService` 列表 SQL 增选 `n.datasource_id`，两个 DTO 补 `datasourceId`、`tableName`；`DatasourceApiTest` 增两条 jsonPath 断言。真实接口验证：列表 200（`datasourceId:"1"`、`tableName:"###t_audit_report"`），详情 200 且 UI 显示对象名/表名/数据源/5 列列结构。
2. **权限矩阵资源树整页 400（前端越界参数）**：`PermissionsPage` 请求 `pageSize=200`，后端 `MAX_PAGE=100` 直接 400，资源树空且报「资源树加载失败」；即使不报错，单页也只能看到 100 个对象。修复：新增 `permissions/metadataLoader.ts` 的 `listAllMetadata`，按 `nextCursor` 续拉到末尾（100/页，50 页防御上限），组件用 `AbortController` 在卸载/切数据源时中断。真实环境验证：809 个 treeitem 全部加载，无 4xx。
3. **favicon 404**：`index.html` 无 icon 引用。补 `public/favicon.svg`（复用 LogoIcon 六边形）与 `<link rel="icon">`，硬刷新后控制台无 404。

同时把详情页「授权/字典可见」由布尔缺失时显示「隐藏/未授权」改为「未知」，避免把未返回的字段说成否定状态。

检查：前端 eslint / prettier / tsc / 199 单测 / `vite build` / `vite build --config vite.oauth.config.ts` 全过；后端 `DatasourceApiTest` 12/12 通过（走 WSL relay `tcp://127.0.0.1:12476` + `.docker-ptmcp` 证书，直连 12376 已失效）。未验证：其余后端模块测试、LAN IP 入口（本轮浏览器会话仅 127.0.0.1 可达）。

路由走查结果：其余页面（数据源/元数据/授权矩阵/人员组织/角色/平台/审计/操作日志/个人门户 6 页）无 4xx/5xx、无控制台错误。数据源详情「连接未知」为设计行为：健康证据只认 5 分钟内的 ACTIVE_PROBE，过期即 UNKNOWN。

### 2026-11-05 全量后端回归与权限页 403 诊断

1. **后端 `ptmcp-admin` 全量测试**：13 个测试类，surefire 聚合 `tests=201 failures=0 errors=0 skipped=0`，此前「未验证：其余后端模块测试」在本模块范围收窄。
2. **权限页「新增授权规则」真实环境 403 诊断**：前端请求体契约正确（raw `resourceId` + `resourceGeneration` + subject/action/effect/includeDescendants/validFrom/validTo/reason），后端返回 `{"code":"SELF_PRIVILEGE_ESCALATION","codeNumber":140005,"retryable":false}`。根因是 `SelfEscalationGuard` 设计：受授予对象（user 1）即当前操作者，新增 READ 触发自我提权拦截，**不是前后端契约缺陷**。dev 库只有一个用户/角色/部门，所有候选主体都等于操作者，因此本环境无法完成授权创建/撤销的端到端验证；该 HTTP 契约由 `AdminApiTest`（自授予→403 且授权列表仍为空；第二用户授予→创建成功→撤销）覆盖。
3. **前端缺陷修复（`src/services/http.ts`）**：后端 403 响应体不含 `message`，前端原先一律显示「没有执行该操作的权限。」。新增按 `code` 的中文映射（CSRF_INVALID / MANAGEMENT_SCOPE_DENIED / TABLE_NOT_AUTHORIZED / SELF_PRIVILEGE_ESCALATION），未知 code 保留通用文案；`tests/http-contract.test.ts` 新增对应单测。
   验证：前端单测 200/200 通过；eslint / prettier / tsc / `vite build` / `vite build --config vite.oauth.config.ts` 全过。真实页面（权限页）探针复验返回 `{"status":403,"code":"SELF_PRIVILEGE_ESCALATION","message":"不能给当前登录账号授予其尚未拥有的访问权限。"}`。
4. **排障注意**：`GET /api/v1/session/csrf` 对已认证会话会轮换 CSRF token（`AdminSessionService.issueCsrf`），用 curl 直接探测会使页面内 token 失效并出现一次 `CSRF_INVALID`，刷新页面即可恢复；非缺陷。

### 2026-11-05 真实接口全路由走查与续读失败缺陷修复

以已登录会话在 `http://192.168.1.18:25173` 走查（真实后端 + ASD Cloud Base 808 对象）：

- 管理端列表：数据源、元数据、权限矩阵、人员组织、角色、平台、访问审计、操作日志；个人门户 6 页（字典/接入/令牌/授权/历史/安全）。全部标题正确、无 4xx/5xx、无控制台错误（仅应用启动时 2 次未登录 `session/me` 401，属会话探测预期）。
- 详情路由真实数据：数据源 #1（测试连接/同步元数据/保存更改齐全）、元数据 #809（对象名/表名/数据源已回填，验证前一契约修复）、角色 #1（Platform Admin）。权限矩阵 809 个 treeitem，点选折叠/展开为树控件行为；组织页成员行内编辑面板正常；审计 0 条时查看按钮按 requestId 输入禁用，属预期。
- 元数据游标续读：`加载更多` 20 → 40 行，footer 显示真实「服务端仍有更多」，未伪造总数。首次续读请求出现一次 `net::ERR_CONNECTION_TIMED_OUT`（环境网络瞬断），页面进入「重试续读」态，点击后成功补齐 40 行，重试路径有效。
- 全局搜索（Ctrl+K）：输入「数据源」返回导航命中「数据源导航」，浮层与键盘语义正常。

走查中发现并修复一处真实缺陷：

- **data 层错误被 `readFailure` 丢弃**：`dataRequest` 把 `ApiError` 包装为 `DataRequestError`（保留 message/requestId），但 `readFailure` 只识别 `ApiError`，导致所有经 data.ts 的失败（含 403 文案映射、网络错误）只显示页面兜底文案，且丢失 requestId。实测元数据续读失败显示为「续读失败：续读失败，可再次点击重试」。修复：`http.ts` 增加按 `name === 'DataRequestError'` 的结构识别（避免 http→data 循环依赖），保留服务端消息与 requestId；`MetadataPage` 兜底文案改为「可再次点击重试」消除重复前缀。
- 测试：`tests/http-contract.test.ts` 新增 `readFailure keeps the server message and requestId of data-layer wrappers`，覆盖 DataRequestError（含/不含 requestId）、ApiError 与普通 Error。前端 201/201 通过；eslint / prettier / tsc / `vite build` / `vite build --config vite.oauth.config.ts` 全过。

安全注意：本轮登录脚本曾一次性包含本机 dev 账号口令并被 Playwright 工具回显到会话记录（脚本已删除，仓库与本工作项均未写入口令值）。建议轮换本机 dev 管理员口令；该操作属安全关键，需用户授权后执行。

未验证：权限写操作端到端（dev 库单用户触发 SelfEscalationGuard，由 `AdminApiTest` 覆盖）、OAuth 真实端到端、AppShell 与其余原生表格迁移。

### 2026-11-05 个人门户 me 页表格迁移与两处迁移回归修复

- AuthorizationsPage / HistoryPage 原生 `<table>` → antd Table：`rowKey`、`size="middle"`、`pagination={false}`，游标语义不变（footer 仍为真实「已加载 N 条 / 可能还有更多 / 没有更多了」，不伪造 total）；空态由 sibling `EmptyState` 承担。历史页「时间」跳转由 `.link` → `Button type="link"`，保留 `aria-label` 与详情路由。
- 回归修复 1（全站空态）：EmptyState 换 antd Empty 后，`.empty-state-desc/.hint` 的 `max-width: 400` 块在块级 description 容器内左对齐，文案偏离标题中线；CSS 改为 `margin: 0 auto`。实测授权页 desc/title/empty 三处中心均为 1070px。
- 回归修复 2（历史页筛选行）：日期 `TextInput` 未传 `width`，antd Input 默认 `width: 100%` 在 flex 筛选行里独占整行并换行；补 `width={150}`，与 AuditPage 日期筛选一致。实测两个日期框同排（y=192）、各 150px。
- 检查：eslint / prettier / tsc / tests 201/201 / `vite build` / `vite build --config vite.oauth.config.ts` 全过。
- 浏览器（真实后端，LAN 入口 `http://192.168.1.18:25173`）：授权页空态居中、页脚「已加载 0 条 · 没有更多了」；历史页筛选行单行、空态与页脚正确。期间 dev server CSS 与 `/me/history` 各出现 timeout（已知 LAN/TUN 环境抖动），重试后正常，无产品侧错误。
- 未验证：两张表的有数据行渲染（dev 库当前 0 条授权、0 条历史）；授权撤销交互。

### 2026-11-05 个人门户 me 页表格迁移（令牌 / 字典 / 接入）

- TokensPage / DictionaryPage / IntegrationsPage 原生 `<table>` → antd Table：列顺序、列宽（80/70/90）与单元格渲染不变，`rowKey`、`size="middle"`、`pagination={false}`；行内 `.link` 链接统一为 `Button type="link"`，保留详情/撤销行为。
- 游标与总数语义不变：令牌/字典页脚仍为真实「已加载 N 个 / 可能还有更多 / 没有更多了」，不伪造 total；接入页 total 已知，保留原有客户端分页；接入页空态保留「前往创建个人令牌」，实测跳转 `#/me/tokens`。
- 空态：与授权/历史页一致，表体 `locale.emptyText` 置空、sibling `EmptyState` 承担文案；加载中只显示 antd spinner。
- 检查：eslint / prettier / tsc / tests 201/201 / `vite build` / `vite build --config vite.oauth.config.ts` 全过。
- 浏览器（真实后端，LAN `http://192.168.1.18:25173`）：令牌页表头 + 「暂无个人令牌」空态居中 + 页脚「已加载 0 个 · 没有更多了」；字典页未选平台/数据源时表头 + 「请选择平台与数据源」空态；接入页 8 列表头 + 空态 + 动作按钮 + 页脚「共 0 条」。控制台 14 条错误均为 dev server 静态资源 HMR 超时（`ERR_CONNECTION_RESET/TIMED_OUT`），无产品侧错误。
- 未验证：三表有数据行渲染。dev 库当前 0 条令牌、0 条历史；接入页正常模式无服务端列表（设计如此，样例仅预览模式）；`/me/access-context?level=PLATFORM` 对当前用户返回 `items: []`（无可见数据源授权），字典页无法选择平台/数据源。令牌撤销交互同样未实测。

### 2026-11-05 管理端平台与客户端表格迁移

- PlatformsPage 两张原生 `<table>`（平台 7 列 / 客户端 6 列）→ antd Table：列顺序与「操作」列宽 150 不变，`rowKey="id"`、`size="middle"`、`pagination={false}`；数据仍由 `listPlatforms({pageSize:100})` + 逐平台 `listClients` 一次载入，无游标、无 total，不新增分页。
- 行内操作 `.link` → `Button type="link" size="small"`，收敛到 `.row-actions` 的 10px gap：平台行 Scope（非管理员禁用）/ 编辑（加载中禁用）/ 删除（danger）；客户端行 编辑 / 删除（danger）。保留 `aria-label`（Scope 授权范围、删除平台/客户端）与详情路由。
- 空态：表体 `locale.emptyText` 置空、sibling `EmptyState` 承担（「暂无平台」/「暂无客户端」）；表体新增 `loading={loading}`，加载中显示 antd spinner（原实现加载中只剩空表头）。
- 检查：eslint / prettier / tsc / tests 201/201 / `vite build` / `vite build --config vite.oauth.config.ts` 全过。
- 浏览器（真实后端，LAN `http://192.168.1.18:25173`）：平台页签表头 7 列 + 「暂无平台」空态；切到客户端页签表头 6 列（客户端/Client ID/所属平台/类型/状态/操作）+ 「暂无客户端」空态；控制台错误仍为 dev server HMR 超时噪声，无产品侧错误。
- 未验证：有数据行的单元格渲染与 Scope/编辑/删除交互（dev 库当前 0 平台、0 客户端）。

### 2026-11-05 管理端元数据表格迁移

- MetadataPage 原生 `<table>`（对象 / 库 Schema / 类型 / 代次 / 授权 / 可见 / 操作，操作列宽 60）→ antd Table：`rowKey="id"`、`size="middle"`、`pagination={false}`，列顺序与单元格渲染不变；`locale.emptyText` 置空，空态仍由既有 Alert / EmptyState 承担。表体不传 `loading`，避免「加载更多」追加行时整表闪烁，加载态继续由 sibling `table-state` 文案承担。
- 行内 `.link` → `<Button type="link" size="small">`，保留 `aria-label`（查看…详情）与详情路由；游标语义不变：页脚仍为真实「已加载 N 个 · 服务端仍有更多 / 已到末尾（契约未返回总数）」，「加载更多」只追加行、不伪造 total。
- 检查：eslint / prettier / tsc / tests 201/201 / `vite build` / `vite build --config vite.oauth.config.ts` 全过。
- 浏览器（真实后端，LAN `http://192.168.1.18:25173/#/admin/metadata`）：默认数据源 20 行渲染，7 列表头与单元格（对象名 + ID、库名、TABLE Badge、代次、授权/可见）正常；点行内「详情」跳转 `#/admin/metadata/809`；返回后「加载更多」由 20 → 40 行，工具栏与页脚计数同步，服务端仍有更多。控制台仍为 dev server HMR/`me/history` 超时噪声，无产品侧错误。
- 未验证：授权/可见为布尔时的彩色 StatusDot 分支（当前列表服务端未回该字段，显示「未知」）；详情页本身未改动。

### 2026-11-05 管理端详情页表格迁移（元数据列结构 / 数据源冲突对比）

- MetadataDetailPage 列结构原生 `<table>`（# / 列名 / 类型 / 可空 / 键 / 注释）→ antd Table：`rowKey="ordinal"`、`size="middle"`、`pagination={false}`；字典 `displayName` 附注于列名、`sensitiveTags` 仍以 Badge 渲染、数据源与注释回退（`dictCol?.description ?? c.comment ?? '—'`）不变；`locale.emptyText` 置空，空态由既有 `EmptyState`（「该对象没有列信息」）承担。
- DatasourceDetailPage 状态冲突对比表（字段 / 我编辑前 / 服务端最新 / 我的草稿）→ antd Table：`rowKey="key"`、`size="middle"`、`pagination={false}`；`serverChanged → orange 600`、`draftChanged → blue 600` 的高亮规则不变；外层 `table-scroll` 区域与 `aria-label="字段冲突对比"` 保留，空差异仍走「字段一致，仅版本号推进。」Alert 分支。
- 检查：eslint（max-warnings 0）/ prettier / tsc --noEmit / tests 201/201 / `vite build` / `vite build --config vite.oauth.config.ts` 全过。
- 浏览器（真实后端，LAN `http://192.168.1.18:25173`）：`#/admin/metadata/809` 列结构 6 列表头 + 5 行渲染（id/月份/供应商/报告类型/文件，字典描述与类型徽标正常）；`#/admin/datasources/1` 详情表单加载正常；控制台仅 dev server HMR 与密码字段 DOM 提示噪声，无产品侧错误。
- 未验证：冲突对比表的真实渲染（需构造版本冲突；本切片未对 dev 数据制造冲突）；真实环境大列结构表（如 800+ 对象表）的行数与滚动未逐页走查。

### 2026-11-05 管理端授权矩阵表格迁移（规则表 / 预览决策表）

- PermissionsPage 授权规则表（资源名称 / 类型 / 权限 / 效果 / 含下级 / 有效期 / 状态 / 操作，操作列宽 90）与有效权限预览决策表（资源 / META / READ / 依据）原生 `<table>` → antd Table：`rowKey` 分别为 `id` / `resourceId`、`size="middle"`、`pagination={false}`；预览表补 `scroll={{ x: 480 }}`。分页仍由 Pane footer 控制、只渲染当前页，游标/total 语义未变。
- 行内「撤销」`.link` → `<Button type="link" size="small" style={{ padding: 0 }}>`，撤销中置 `disabled` 并显示「撤销中…」；`locale.emptyText` 置空，空态仍由 sibling `EmptyState` 承担；单元格类名（`cell-lead` / `cell-stack`）与图标分支不变。
- 文件体量预算：迁移后页面一度到 923 行，触发 `tests/design-system.test.ts` 的 900 行上限。按测试意图拆分而非放宽阈值——新建 `src/views/admin/permissions/tables.tsx`（`GrantsTable` / `PreviewTable`，140 行），页面降至 806 行（测试口径）。未改阈值、未删断言。
- 检查：prettier / eslint（max-warnings 0）/ tsc --noEmit / tests 201/201 / `vite build` / `vite build --config oauth` 全过。主构建仍有 >500 kB 分包警告（既有待分包项）。
- 浏览器（真实后端，LAN `http://192.168.1.18:25173/#/admin/permissions`）：同一 hash 的 `goto` 不会重载（需 `location.reload()`）；强制重载后主体列表 1 个、资源树 1 个数据源 808 个对象正常，规则页脚「共 0 条规则」与「暂无授权规则」空态正确（当前角色与用户主体均无直接规则），预览区按契约提示角色主体不可计算。
- 尝试用「+ 新增规则」造一条临时规则验证有数据行与撤销按钮，被服务端 403 拒绝（`/admin/api/v1/admin/acl/grants`），提示为不能给当前登录账号授予其尚未拥有的权限——自授权防提权守卫按预期生效，非迁移缺陷；未对 dev 数据做任何写入。
- 未验证：规则表有数据行渲染与「撤销」交互；预览决策表渲染（dev 平台下拉为空，按钮保持 disabled）；两次提交的 403 属预期守卫，未做绕过。
- 环境备注：期间控制台错误均为 `ERR_CONNECTION_RESET` / `ERR_CONNECTION_TIMED_OUT`（已知 LAN/TUN 抖动），页面失败标记不自动重试，重载后恢复，无产品侧错误。

### 2026-11-05 管理端组织成员表、详情页表格迁移与行内链接收敛

- OrgPage 成员表（账号 / 姓名 / 角色 / 状态 / 操作，操作列宽 200）→ antd Table：`rowKey="id"`、`size="middle"`、`pagination={false}`；整行拖拽改由 `onRow` 承载（`draggable`、`onDragStart` 写 `USER_MIME`、`onDragEnd`、`is-dragging` class），`canMoveUser` 门控与落点高亮逻辑不变；操作列补 `align: 'right'` 对应原 `.org-table td:last-child` 右对齐。`.org-table` 挂在 antd Table wrapper 上，`tbody tr` / `td:last-child` 后代选择器仍生效（已核对 `src/styles/index.css`）；无 CSS 定义的 `.org-actions-col` 随之移除。
- 详情页表格迁移：AuditDetailPage 敏感访问轨迹表、DictionaryEditor 冲突表与字典列编辑表（编辑表 `scroll={{ y: 320 }}`）、OrgDetailPages 平台详情关联客户端表、DictionaryDetailPage 个人字典列结构表（保留 `EmptyState` 空态）均换 antd Table，`rowKey`/`size="middle"`/`pagination={false}` 一致；ScopeDialog 授权范围规则表（可编辑，`rowKey=index` 保证编辑 resourceId 时输入焦点不丢，`scroll={{ y: 280 }}`）与变更对比表（`showHeader={false}`、`scroll={{ y: 160 }}`）。
- 行内链接收敛：OrgPage 三处、Wizard 三处、ScopeDialog 一处 `.link` → `Button type="link" size="small" style={{ padding: 0 }}`，保留既有 `aria-label`；全仓已无 `className="link"` 引用，`.link` / `.link--danger` 仅剩 CSS 死规则（清理留待专门切片）。
- 检查：prettier / eslint（max-warnings 0）/ tsc --noEmit / tests 201/201 / `vite build` / `vite build --config vite.oauth.config.ts` 全过；主构建仍有 index 1,131.77 kB > 500 kB 警告（既有分包项）。OrgPage 889 行（测试口径 890）仍在 900 行预算内，未改阈值。
- 提交：`fcd5200 refactor(admin-ui): 成员表与详情表迁移 antd Table，收敛行内链接`，已推送 origin dev；仅暂存上述 7 个文件，排除他窗 `src/services/data.ts`、`src/views/admin/dsForm.ts`、`tests/ds-create.test.ts`。
- 未验证：上述迁移页面的浏览器人工走查（成员表拖拽、ScopeDialog 可编辑行焦点、字典编辑表滚动、详情页表格有数据渲染）；`.link` 死 CSS 清理与 `.btn`/`.input` 引用复核；AppShell 外壳迁移；chunk 分包；Impeccable finish-reviewer/documenter；真实 OAuth 端到端。
