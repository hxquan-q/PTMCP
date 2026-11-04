# DEV-20261031-ui-polish：控制台视觉与可达性逐页优化

ID: DEV-20261031-ui-polish
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-11-04

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

### 切片 U3：P-13 我的 MCP 接入配置逐页细节

`src/views/me/IntegrationsPage.tsx`（本人可写路径）连同列表页与 4 步向导一起审，发现的核心问题不是配色，而是**页面在说它做不到的事**：

- 列表页在非预览模式下把 `PREVIEW_INTEGRATIONS` 合成样例当真实登记渲染（含统计卡、状态页签、筛选与「重置」）。现改为普通模式取空集合，合成数据、统计、页签、筛选行只在预览模式出现，并在筛选行标注「演示数据：合成预览模式下的样例接入，不代表真实登记」；普通模式空态说明控制台不代替客户端登记接入，并给出「前往创建个人令牌」出口。个人门户侧没有可核验的接入列表端点（已查 `services/`），因此不猜接口、不伪造列表。
- 每行与完成页都有「测试连接」，实际调的是 `testDatasource`（数据源连通性），与「客户端 MCP 接入」语义完全不同，且网关握手不可能由控制台代替。按钮、`testing/testResult/runTest` 状态与 `testDatasource` 引用同批删除；完成页状态固定为「待验证（模板已生成）」，后续步骤明写「连接结果以客户端为准，控制台不代替握手也不显示『连接成功』」。向导标题同样去掉可被误读的“接入名称”推断字段。
- 步骤 3 丢弃了服务端 `configTemplate` 返回的能力信息。补「服务端能力」区块逐项回显 transport / 服务端地址 / authentication / 兼容性，缺失时分别显示「待配置（未返回部署地址）」「未返回（按不支持处理）」「尚未验证」；`url` 缺失时追加警示，且未取得合法模板时「复制」「完成并查看后续步骤」保持禁用并给 `title` 说明原因——不再允许“看着配好了”。
- 失败被伪装成空的三处（平台列表、模板获取、模板 JSON 解析）改为可辨识错误态：`describeFailure` 保留 message 与 `requestId`，`RequestIdText` 明示可凭此请求 ID 找管理员排查；获取失败提示返回上一步重试，解析失败说明不生成任何示例配置。
- 复制反馈原本无论如何都显示成功。改为 `ok | fail | null`，配 `aria-live="polite"` 播报、2 秒复位，完成页失败时给 warning Alert 而不是静默。
- 键盘与读屏：列表容器 `role="region"` + `aria-label="接入列表"` + `tabIndex=0`（可滚动区域须可获得焦点，SC 2.1.1）；状态页签从自建 tablist 换成共享 `Tabs`（roving tabindex + ←→），与 Audit/Datasources/Permissions/Platforms 一致；JSON/YAML 切换从伪 tablist 换成原生 `radiogroup` + 28px `seg-item`（`:focus-within` 描边，交给原生方向键语义）；步骤变更由 `aria-live` 播报「第 N 步，共 4 步」并把焦点移到向导卡片（`tabIndex=-1`），`Stepper` 补 `aria-current="step"` 与 `sr-only` 状态后缀，编号不再只靠颜色。
- 完成页「前往创建 PAT」是个没有 `onClick` 的死链接，现指向 `#/me/tokens`；后续步骤补上真实站内跳转 `#/me/history`、`#/me/authorizations`；新增 `summary-grid` 如实汇总平台 / 平台 ID / 客户端 / 传输方式 / 服务端地址 / 认证方式 / 状态 / 兼容性。
- 设计债：内联的 `#0d1526`/`#c8dcf5`/`#4c6184` 代码配色与散落内联样式移入 token 与类（`--ptmcp-code-bg/fg/gutter`、`.wizard-card`、`.option-grid`、`.summary-grid`、`.server-capabilities`、`.next-steps`、`.done-badge`、`.code-block/.code-line/.code-gutter/.code-text`、`.seg-group/.seg-item`、`.sr-only`）；行号色 `#4c6184` 在白底外实测仅 4.1:1 且属小字号，加深为 `#7f93b5`（6:1），代码正文 12.57:1。
- 附带修正复核暴露的共享层回归：`AppShell.tsx` 视角切换选中态用 `--ptmcp-blue` 写在 `--ptmcp-surface` 上，实测 4.30:1 未达 AA，改 `--ptmcp-blue-text`；该 tablist 此前只有点击、没有键盘操作，补 roving tabindex 与 ←→（无管理权限时不响应）。

浏览器实测（隔离合成预览，1440×900 与 720×900）：列表 6 行、页签 4 个 `tabindex` 恰 1 个为 0 且最低对比度 4.9:1、区域 `role/aria-label/tabindex` 齐备、`hasTestButton:false`；向导逐步走完后步骤条依次标出「选择平台（当前步骤）→ 选择客户端 → 连接方式与认证 → 获取配置模板」，`aria-live` 与焦点落卡生效；步骤 4 服务端能力为 `streamable-http` / `https://mcp.example.test/mcp` / `OAuth / PAT` / 尚未验证，代码块 10 行，正文 12.57:1、行号 6:1；真实 `ArrowRight`（`press_key`）把模板切到 YAML 且焦点环可见，`radiogroup` 内两个选项热区 28px；完成页 8 项汇总、状态「待验证（模板已生成）」、`next-steps` 链接为 `#/me/history`、`#/me/authorizations`、徽标底色 `rgb(237,246,238)`、测试按钮 0 个、复制播报文案「已复制到剪贴板」；视角切换 ←→ 实测双向可跳转（`#/me/*` ↔ `#/admin/datasources`），选中态 5.91:1、未选中 5.09:1；720px 下文档横向溢出 0px、表格 `scrollWidth == clientWidth`（603px）无需横向滚动。产物 `C:\Users\xquan\AppData\Local\Temp\ptmcp-shots\p13-list-1440.png`、`p13-step4-template-1440.png`、`p13-step4-full.png`、`p13-done-full.png`、`p13-list-720.png`（本宿主无图像通道，仅作为交付给用户查看的证据，未做像素比对）。

实际检查：`pnpm exec prettier --write`（4 文件 unchanged）；`pnpm exec eslint --max-warnings 0` 覆盖 `IntegrationsPage.tsx`、`common.tsx`、`AppShell.tsx` 无输出；`pnpm exec tsc --noEmit` 通过；`node --experimental-strip-types --test tests/*.test.ts` 退出 0（164 tests / 164 pass / 0 fail）；`pnpm build` 通过（CSS 23.92 kB / gzip 5.71 kB，JS 451.65 kB / gzip 124.30 kB）。

未验证：普通模式（非预览）空态无法在浏览器走通——未登录时应用渲染 `LoginPage`，该分支只有代码级依据；真实后端联调、人工体验接受未做。

### 切片 U4：P-04 元数据页逐页细节

`src/views/admin/MetadataPage.tsx` 与 P-13 是同一类问题——页面把“读不到”说成“没有”，把“没返回”说成“否”：

- 数据源目录读取失败时 `catch(() => undefined)` 静默吞掉，页面留下一个空下拉和空表；元数据读取失败时 `catch` 清空行，随后显示「没有匹配的元数据对象」。现在两类失败各自成为可辨识的 danger 态，显示服务端消息与 `requestId`、「重新读取」按钮，并在元数据失败处明写“列表当前不可信，请勿据此判断对象是否已授权”。续读失败也不再静默：页脚给出原因并把按钮文案切到「重试续读」，游标保留。
- `authorized` / `metadataVisible` 在契约里是可选字段，旧代码用三元把它们当成必然布尔：字段缺失时渲染「未授权」「隐藏」，属于凭空断言。改为 `StateDot` 三态，非布尔一律显示「未知」。
- 空态按原因分流：无数据源（前往数据源页）、搜索无匹配（回显关键字并提供「清空搜索」）、无关键字且该源为空（说明可能尚未同步或快照未发布，不假装本页能触发同步）。切换数据源或关键字时先清空旧行，避免把上一批结果显示成新数据源的容量。
- 计数诚实化：`MetadataPage` 契约只有 `items/nextCursor`，没有总数，页眉与页脚统一改成「已加载 N 个」，并按游标显示「服务端仍有更多 / 已到末尾」，附「契约未返回总数」。此前写死的「共 N 个对象」是过度声称。
- 可达性与控件：读取中不再留下空表，而是区域内的「正在读取…」说明；`aria-live="polite"` 状态句覆盖加载/失败/已加载数量；列表容器补 `role="region"`+`aria-label`+`tabIndex=0`；下拉补可见 `数据源` 标签（此前可访问名来自首个选项文本），`SearchInput` 新增可选 `label` 落到 `aria-label`；表头补 `scope="col"`；行内「详情」按钮补 `aria-label="查看 … 详情"`，读屏不再只听到“详情”。
- 移除死别名 `const filtered = rows;`；工具栏与页脚的散落内联样式移入 `.table-toolbar`/`.toolbar-status`/`.table-state`/`.footer-note`/`.footer-error`。
- 共享层收敛：把 U3 里页面内私有的 `describeFailure`/`RequestIdText` 上移为 `services/http.ts` 的 `readFailure`（ApiError 保留服务端消息与 requestId，网络/取消只回落到页面级说明）与 `components/common.tsx` 的 `RequestIdText`（requestId 为空时不渲染），P-13 与 P-04 共用，不再各写一份。

浏览器实测（隔离合成预览，1440×900）：区域 `{role:region,label:元数据对象列表,tabindex:0}`；下拉可见标签拼接为「数据源 · 6 个选项」且未禁用；搜索框 `aria-label=搜索对象名或表名`；初始 3 行、播报「已加载 3 个元数据对象」、页脚「已加载 3 个 · 已到末尾（契约未返回总数）」；输入 `zzz-not-exist` 后 rows 0、空态标题回显关键字、描述与「清空搜索」出现，点击清空后恢复 3 行且播报同步；切到第 2 个数据源后重载为 2 行并播报「已加载 2 个元数据对象」；本页文本 AA 对比度不达标项 0、按钮热区 <24px 项 0；「详情」可访问名为「查看 synthetic_orders 详情」等。截图 `p04-metadata-1440.png`（无图像通道，仅供用户查看）。

未验证：预览种子恒把 `authorized`/`metadataVisible` 置为 `true`，「未知」分支与真实后端的失败/409 路径只有代码级依据，未在浏览器复现；人工体验接受未做。

实际检查：`pnpm exec prettier --write`（5 文件）；`pnpm exec eslint --max-warnings 0` 覆盖 `MetadataPage.tsx`、`IntegrationsPage.tsx`、`common.tsx`、`services/http.ts` 无输出；`pnpm exec tsc --noEmit` 通过；`node --experimental-strip-types --test tests/*.test.ts` 退出 0（164/164）；`pnpm build` 通过（CSS 24.40 kB / gzip 5.80 kB，JS 454.23 kB / gzip 125.17 kB）；仓库根 `python scripts/check.py` 74 测试 OK。

`table-scroll` 可聚焦区域这一项在 21 处出现中，此前只有 1 处合规；本切片另修 1 处，其余位于 DEV-20261031-audit-fixes 占用文件（`DatasourcesPage/PlatformsPage/DetailPages/DelegationsPage/AuditPage`）与后续组织页、个人门户页，按页推进，不一次性覆盖他人未提交改动。

### 切片 U5：P-05/P-06/P-07 组织页逐页细节

三页共有的根因是“一次合并读取 + 一个 catch”和“写操作只有 `finally` 没有 `catch`”，前者把任一目录的读取失败转嫁成另一份数据的缺失，后者让写失败变成未处理拒绝、界面继续显示旧状态。

- `UsersPage`：`Promise.all([listUsers, listRoles, listDepartments])` 的单个 catch 一律报「用户列表加载失败」，改为 `Promise.allSettled` 三路独立呈现——用户列表失败是 danger（消息 + `requestId` + 「重新读取」），角色/部门目录失败是 warning。角色目录读不到时角色列不再渲染成空白或假徽章，而显示「已分配 N 个（角色目录未加载）」；部门目录读不到时该列显示 `部门 ID …` 而不是「—」。`toggleStatus` 与删除对话框补 `catch`，启停失败进入可见 danger 播报，成功提示改为带具体用户名与动作（「用户「X」已停用」）。`deptFilter` 在加载或部门目录失败时禁用。
- `UserDialog` 的分离提交原本是 3 次非原子写入（资料 → 部门 → 角色）却只报「保存失败」：现在记录失败步骤与已生效步骤，失败时明写“已生效的步骤：…；用户写入按子资源分次提交、不是原子操作，请关闭并刷新核对”。目录不可用（读取失败，或编辑一个已带角色的成员而角色目录为空）时禁止保存，避免用不完整清单覆盖真实分配；成员持有但目录里没有的角色 ID 以「角色 X（目录外，保留）」显式列出并原样回传——此前这些 ID 既不可见也不可控。角色目录“真的为空”与“读取失败”分开措辞，前者不再阻断保存。
- `RolesPage`：`getSession` 失败静默 `setPermissionOptions([])`，对话框只显示「无匹配权限码」，管理员无从知道是会话读取失败。现在失败进入 `permsFailure`，页面顶部与对话框都给出原因，并在目录不可用时阻止提交（否则会创建出一个没有任何权限的角色）；「无匹配」文案改为回显筛选词。列表 `loadError` 由裸字符串改为 `Failure`（`requestId` + 「重新读取」），读取前先清空旧行。行内「删除」补 `roleDelete` 权限判定——此前只有对话框底部判定，无权限用户仍能打开确认框。`memberCount` 缺失由「—」改为「未知」并附说明。
- `DepartmentsPage`：部门与成员分两路捕获，成员读不到时成员列显示「—」（附原因）且删除一律禁用，不再把“成员数未知”当成“0 个成员”放行删除；删除条件由 `deletionBlock()` 单点判定，行内按钮与对话框确认按钮共用，对话框内预检失败会把原因留在框里。`deleteDepartment`/`updateDepartment`/`createDepartment` 补 `catch`，失败留在对话框并显示 `requestId`；关闭与取消会清除残留失败。
- 计数诚实化：组织三页都按 `pageSize=100` 取列表且契约不返回总数，「共 N 个用户/角色/部门」统一改为「已加载 N 个」，并配 `aria-live="polite"` 状态句（加载/失败/已加载数量）。空态按失败、搜索无匹配（回显关键字 + 「清空搜索」）、真无数据分流，读取失败不再显示成空列表。
- 控件与可达性：区域 `role="region"`+`aria-label`+`tabIndex=0`、`th scope="col"`、`SearchInput label`、行内动作 `.row-actions`（替掉逐个 `marginLeft: 10`）、对话框底部 `.form-actions`、可滚动的角色/权限码多选区收敛为 `.check-list`（`role="group"`+`aria-label`+`tabIndex=0`+`focus-visible`）。`common.tsx` 的 `TextInput` 新增可选 `label` 落到 `aria-label`（权限码筛选框此前只靠 `Field` 的隐式关联拿到名字）。`index.css` 补 `.link:disabled` 可辨状态（颜色 + `cursor: not-allowed` + 去下划线）、`.link--danger`；`code` 与部门 ID 的 11px 字号抬到 12px。
- 记录事实而非发明权限：`src/permissions.ts` 的 `SENSITIVE_ACTIONS` 没有部门新建/删除权限码，后端也未提供 `DELETE /admin/users/{id}`（故 `canDeleteUsers` 仍限合成预览）。部门页的写操作因此仍未做权限门控——不擅自新增权限码，列为待契约确认。

浏览器实测（隔离合成预览 1440×900；预览走进程内 `previewRequest`）：三页 region 均 `{role:region,tabindex:0,label:用户列表/角色列表/部门列表}`、`th` 全为 `scope=col`、无名按钮 0、表内 <12px 文本 0、`aria-live` 各 1、工具栏状态「已加载 2 个」。部门页两个节点的删除按钮均 `disabled=true` 且 `title=部门下仍有成员`，禁用态计算样式 `{color:rgb(91,106,119),cursor:not-allowed,textDecoration:none}`、热区 24×39px。`UserDialog` 检出 `.check-list[role=group][tabindex=0][aria-label=可选角色]`、2 个 `.check-row`、`.form-actions`、提交按钮可用；`RoleDialog` 检出 54 个 `.check-row`、筛选框 `aria-label=筛选权限码`。写路径实跑：新建表单在缺少部门时留在框内并播报「请选择所属部门」（客户端校验），补齐后创建成功并 reload，随后用删除确认框移除该合成用户、列表恢复种子 2 条——合成预览数据已复位。本切片的一个真实缺陷由实测发现并修掉：`DepartmentsPage` 改用 `allSettled` 时漏掉 `setLoading(false)`，导致表格已有数据仍常驻「正在读取…」，修复后状态句变回「已加载 2 个」。

未验证：列表读取失败的 Alert（含 `requestId` 与「重新读取」）、`UserDialog` 的分步部分失败提示、目录外角色/权限码保留徽章、`memberCount` 的「未知」分支——合成预览没有稳定的失败注入点（进程内 transport 拦不到），只有代码与类型门禁依据；真实后端联调与人工体验接受未做。

实际检查：`pnpm exec prettier --write`（3 页 + `common.tsx` + `index.css`）；`pnpm exec eslint --max-warnings 0` 覆盖 `DepartmentsPage.tsx`、`UsersPage.tsx`、`RolesPage.tsx`、`common.tsx` 无输出；`pnpm exec tsc --noEmit` 通过；`node --experimental-strip-types --test tests/*.test.ts` 164/164 通过；`pnpm build` 通过（CSS 25.15 kB / gzip 5.93 kB，JS 462.72 kB / gzip 127.74 kB）；仓库根 `python scripts/check.py` 74 测试 OK。

### 切片 U6：P-01 登录页逐页细节

`src/views/auth/LoginPage.tsx` 是共享工作树里最后一个未被 DEV-20261031-audit-fixes 占用的页面。它的问题集中在“错误说得不准、状态没有语义、页脚声称做不到的事”：

- 文档里没有任何标题：品牌名 `PTMCP` 只是一个 `div`，读屏用户进入页面拿不到 heading。改为 `<h1 class="login-title">` + `<p class="login-subtitle">`，`form` 保留可访问名「登录 PTMCP」并补 `aria-busy`。
- 校验只有一句笼统文案：用户名空、密码空、两者都空都打印「请输入用户名和密码。」，且不指出缺哪个字段、焦点仍在原处。现按缺失字段分别报「请输入用户名。」「请输入密码。」，给对应输入框加 `aria-invalid` 与 `aria-describedby=login-error`，并把焦点移到该框；该字段一旦开始输入即清除错误态。
- 共享层 `Alert` 此前一律 `role="status"`，登录失败这种必须立刻播报的通知会被推迟。现按语义分级：danger → `role="alert"`，info/warning 保持 `role="status"`，并新增可选 `id` 供 `aria-describedby` 引用。该改动会影响全部页面的 danger 提示播报时机（仅 aria，不改布局与文案），其余页面仍由各自所有者验收。
- 把“登录后置步骤失败”报成“登录失败”：`login()` 在 POST 之后还要刷新 CSRF，两步共用一个 `catch`；若失败发生在刷新阶段，服务端会话其实已建立，旧页面会留在登录框里报“登录失败”。现改为以服务端会话为准——失败后核对 `getSession()`，已登录则正常进入控制台，只有确认未登录才显示失败消息与 `requestId`。
- 页脚写「登录行为将被审计」，但 `AdminSessionService.login/recordFailure` 只做失败计数与 `LOGIN_LOCKED`（429），后端登录路径没有写入任何审计记录，PRD 与安全文档也无该要求——属于凭空声称。改为可核验陈述：连续多次失败会临时锁定登录，锁定时长以服务端提示为准；同时不实现前端倒计时解锁（页面规格明确禁止）。
- 控件与响应式：卡片固定 `width: 360`、容器 `height: 100vh`，窄屏溢出、矮屏无处滚动。改为 `.login-shell{min-height:100vh/100dvh;padding}` + `.login-card{width:100%;max-width:360px}`；散落内联样式移入 `.login-brand/.login-brand-mark/.login-title/.login-subtitle/.login-submit/.login-footnote`；新增 `.input--invalid:not(:focus)` 红边（聚焦时仍保留蓝色焦点环，避免与焦点态互相吞掉）。按页面规格不新增“记住密码”和“查看当前密码”，密码不裁剪（仅裁剪用户名）。

浏览器实测（隔离合成预览，1440×900，经退出登录 → 登录页 → 重新登录走通真实流程）：`h1` 恰 1 个「PTMCP」，`form` landmark 名「登录 PTMCP」且 `aria-busy=false`；两输入框可访问名取自 `<label>`（用户名/密码），298×34px；页内可聚焦元素恰 3 个，真实 `Tab` 首站落用户名，焦点环 `rgb(53,74,95) 3px`。空字段提交得 `role=alert`、「无法登录 / 请输入用户名。」、`id=login-error`、用户名 `aria-invalid=true` + `aria-describedby=login-error` 且焦点落用户名；只缺密码时同套路落密码框，失焦后该框边框 `rgb(165,11,15)`、合法框回到 `rgb(139,154,168)`；输入即清除 Alert 与 `aria-invalid`。实测对比度（对白卡）：标题 15.17:1、副标题 5.54:1、脚注 12px 5.57:1、输入正文 9.15:1，均达 AA。窄屏（工具下限 500×600）横向溢出 0、卡片 360 宽居中（x=70）且完整落在视口内。`requestSubmit()`（浏览器原生提交路径，等价于表单内 Enter）登录成功后离开登录页并落 `#/admin/datasources`，预览会话已复位为登录态。产物 `p01-login-1440.jpeg`、`p01-login-error-1440.jpeg`（本宿主无图像通道，仅供用户查看）。

未验证：预览 transport 的 `login` 恒返回成功且 `signedIn` 初值为 `true`，所以服务端拒绝（`AUTHENTICATION_FAILED`）、429 锁定文案、`requestId` 显示，以及“POST 成功但 CSRF 刷新失败时按会话进入控制台”的新分支只有代码与类型门禁依据，未在浏览器复现；请求进行中的 `aria-busy=true`/按钮禁用态因预览在一帧内返回而未被捕获（非竞态可复现）；人工体验接受未做。

实际检查：`pnpm exec prettier --write`（3 文件，`LoginPage.tsx` 与 `index.css` unchanged、`common.tsx` 因签名换行重排）；`pnpm exec eslint --max-warnings 0` 覆盖 `LoginPage.tsx`、`common.tsx` 无输出；`pnpm exec tsc --noEmit` 通过；`node --experimental-strip-types --test tests/*.test.ts` 退出 0（164/164）；`pnpm build` 通过（CSS 26.09 kB / gzip 6.11 kB，JS 463.16 kB / gzip 128.05 kB）；仓库根 `python scripts/check.py` 74 测试 OK。

### 切片 U7：应用层反馈面（通知栈 / 等待帧 / 403·404 整页）

页面级路由在共享工作树里已全部被 DEV-20261031-audit-fixes 占用，本切片改做“跨 16 个路由都生效”的三块外壳：`src/toast.ts`、`src/components/Toast.tsx`、`src/App.tsx`（当时均为 CLEAN）。

- 通知只能靠鼠标点整条关闭：`ToastContainer` 用一个可点 `div` 承载 dismiss，键盘用户完全没有出口（SC 2.1.1）；全部 tone 共用一个 `role="status"` polite 区域，失败通知会被推迟播报；容器用 `.table-card` 加内联样式、固定 `maxWidth: 360`。现改为每条 toast 自带真实 `<button class="toast-close" aria-label="关闭…提示">`（实测 24×24，达 SC 2.5.8 目标尺寸），按 tone 分 `.toast--*` 语义色左边框与图标。
- 实时区域此前“无通知就整体不渲染”，而读屏只在已存在的 live region 里才播报新插入内容，首条通知存在漏报风险。现 `.toast-stack` 常驻挂载失败（`role="alert"`）与其余（`role="status"`）两个区域，只换内容不换容器（实测空队列时仍挂载 2 个区域）。
- 失败通知 4s 后自己消失，用户往往还没读完（SC 2.2.1 时限须由用户控制）：`showToast` 现把 danger 记为常驻（`expiresAt: null`），必须手动关闭；新增 `pauseToast/resumeToast`，悬停或键盘聚焦冻结倒计时并保留剩余时长，移开/失焦后继续；`dismissToast/resetToasts` 清理定时器，避免已关闭的旧定时器把后一条 toast 误删。
- 已登录用户会先看到登录页：`sessionState === 'probing'` 落到 `sessionState !== 'signed-in'` 分支渲染 `LoginPage`，核对完成后整页被替换，若用户正在输入就被吞掉。现 probing 渲染 `.boot-shell` 等待帧，并把登录页分支提到鉴权之前（顺带修正：未登录访问非法地址原本先渲染 404 卡片，现一律先回登录页）。
- `/` 重定向帧返回 `null`，是一屏没有任何说明的空白。现给同一等待帧配文案「正在打开首页…」。
- 403/404 整页只有 `height: 100vh` + 固定 `width: 420` 的内联样式、借用 `.table-card`，且整页替换后焦点仍留在原处，读屏不会念出被拒原因。现改为 `<main class="error-shell">` + `.error-card` 响应式（`width:100%; max-width:420px`），挂载后把焦点移到 `<h1>` 使其被播报，权限码与地址长串用 `overflow-wrap:anywhere` 兜底。

浏览器实测（隔离合成预览，MutationObserver 逐次记录 `#root` 提交，非人工肉眼判断）：退出登录 → 提交登录，状态序列为 `["app","login","boot[正在核对登录状态…]","app"]`，probing 期间不再出现登录页，落回 `#/admin/datasources`；用户启停两次已复位为“启用”。1440×900 成功通知：落在 `role=status / aria-live=polite` 组内，`.toast--success` 左边框 `rgb(37,111,58)`、容器 `pointer-events:none` 而单条 `auto`、位置 top 68 / right 20 / 宽 265、关闭按钮 24×24 且可访问名「关闭成功提示」；悬停后 5.2s 仍在（默认 4s 时限），移出后 4.6s 内消失；关闭按钮 `focus()` 后真实 `Enter` 键使其消失（toasts=0）。380×740：通知栈改为底部整宽（left/right/bottom 间距 16/16/16，宽 348），不再遮挡顶栏。404 页：`<main>` 语义、码「404」、标题「页面不存在」、描述含原地址，`document.activeElement` 即 `<h1>`（`tabindex=-1`），1440 卡宽 420、380 卡宽 348、横向溢出 false，`.error-shell` 高 740、卡 top 261 垂直居中（首轮同帧读到高度 0 系测量时机噪声，已复测覆盖）。控制台无 error/warning。产物 `u7-toast-1440.jpeg`、`u7-404-380.jpeg`（仓库外，本宿主无图像通道，仅供用户查看）。

未验证：danger 的 `role="alert"` 即时播报与“失败通知常驻”只有单元测试与代码路径依据——预览 transport 不产生失败请求，未能用真实失败复现；403 卡片未复现（预览管理员持有全部权限码，构造不出 denied 路由，与 404 共用同一组件）；读屏软件对焦点移入 `<h1>` 的实际播报、以及 `prefers-reduced-motion` 下等待帧动效（现依赖全局 reduce 规则把动画压到 0.01ms，视觉表现为静止圆环）均未测，“正在核对登录状态…”这一帧在正常速率的预览宿主里无法被人眼捕获，只有观察器证据。交互变更需用户接受：整条 toast 点击关闭改为仅关闭按钮可关（避免把不可聚焦的 `div` 当按钮）。人工体验接受未做。

实际检查：`pnpm exec prettier --write`（5 文件，`Toast.tsx`/`App.tsx`/`index.css` unchanged，`toast.ts` 与 `tests/toast.test.ts` 重排）；`pnpm exec eslint --max-warnings 0` 覆盖 `toast.ts`、`components/Toast.tsx`、`App.tsx` 退出 0；`pnpm exec tsc --noEmit` 通过；`node --experimental-strip-types --test tests/toast.test.ts` 10/10（本切片新增 danger 常驻、暂停/续时、清除陈旧定时器 3 条用例 + 原有 7 条）、`tests/*.test.ts` 全量 167/167；`pnpm build` 通过（CSS 28.73 kB / gzip 6.57 kB，JS 464.38 kB / gzip 128.44 kB）；仓库根 `python scripts/check.py` 74 测试 OK。

### 切片 U8：应用外壳与命令面板的键盘可达闭环

页面路由仍全部被 DEV-20261031-audit-fixes 占用，本切片只做外壳三文件（当时均 CLEAN）：`src/layouts/AppShell.tsx`、`src/components/common.tsx`、`src/styles/index.css`。外壳的问题不是“不好看”，而是键盘用户到不了正文、弹层关不住自己：

- 顶栏 + 侧栏在每页都排在正文之前，正文自身又不是可聚焦区域，键盘用户进正文只能 Tab 过全部导航（SC 2.4.1 跳过区块）。现加 `.skip-link`「跳到主内容」。全站是 hash 路由，`href="#…"` 会被路由当成导航变更，所以用真实 `<button>` 把焦点送进正文区域，而不是写一个会改路由的锚点。
- 两个视角的 `<nav>` 都没有可访问名，读屏的 landmark 列表里是两条同名 “navigation”；面包屑只是裸 `<div>` 文本，没有语义。现侧栏按视角命名（管理工作区导航 / 个人门户导航），面包屑包进 `<nav class="app-shell-crumb" aria-label="面包屑">`，正文区为 `<main class="app-shell-main">`。
- 视角切换原本挂着 `role="tablist"`/`role="tab"`，但它切换的是整条路由、页面上没有 `tabpanel`，且没有按 tablist 约定实现方向键——语义与实际行为不符，读屏会播报“标签页”而用户找不到对应面板。现改为 `role="group" aria-label="视角切换"` + `aria-current`。
- 无管理权限时“管理视角”用 `disabled`，等于把它从 Tab 序列里删除：键盘用户既不知道存在另一个视角，也读不到原因。现改 `aria-disabled` 保持可达，点击短路，`title` 说明「当前账号没有管理权限」。
- 正文是本页唯一的纵向滚动容器却不可聚焦，键盘无法用方向键滚动它（SC 2.1.1）。现给 `role="region" aria-label="页面内容" tabIndex={0}` 与负内偏移焦点环。此处如实记录实测边界：在 520×420 遍历 10 条路由，只有 `#/me/integrations` 该区域自身溢出 7px，其余页面是满高工作区、滚动发生在内层容器；保留这个 tab stop 的理由是它同时充当跳过导航的落点与溢出兜底，不是“每页都需要它滚动”。
- `useFocusTrap` 的圈禁边界与浏览器原生 Tab 序列不一致：它把 `tabindex="-1"` 的行也算作 first/last，于是弹层最后一个真正可聚焦元素（关闭按钮）不是边界，Tab 直接漏到背后的页面。现过滤条件补 `el.tabIndex >= 0`。这是实跑才暴露的缺陷——首轮验证出现假阴性：DOM 里行已是 `-1`，而 Vite HMR 仍在跑旧版 hook（`common.tsx` 的 `useDebounced` 导出无法 Fast Refresh，只能 invalidate 整模块图），硬刷新后结论才翻转。
- 命令面板此前完全没套焦点圈禁（自己写 `useEffect` 只管输入框聚焦），也不遵守组合框语义；搜索框带内联 `outline:'none'`，把全站 `:focus-visible` 焦点环吞掉（SC 2.4.7）。现套共享 `useFocusTrap`，输入框改 `role="combobox"` + `aria-expanded` + `aria-autocomplete="list"` + `aria-controls` + `aria-activedescendant`，选项补 `id`；结果行统一 `tabIndex={-1}`，焦点始终留在输入框、由 `aria-activedescendant` 指向高亮行，消除“聚焦行”和“高亮行”两套互相矛盾的状态；空态占位改 `role="presentation"`（listbox 的子项只应是 option）；`GlobalSearch` 触发按钮补 `type="button"`、`aria-haspopup="dialog"`、`aria-keyshortcuts="Control+K Meta+K"`。
- 样式：`.app-shell` 用 `100vh` 且无 `dvh` 兜底；命令面板固定 `width: 520`、`maxHeight: '60vh'`，窄屏被固定宽撑出横向溢出。现抽出 `.app-shell/.skip-link/.app-shell-main/.app-shell-crumb/.app-shell-scroll/.cmdk-panel`，`height:100vh/100dvh`、`width:min(520px,calc(100vw - 32px))`、`max-height:60vh/60dvh`；跳过链接靠 `:focus` 进入视口并用负偏移避免撑出滚动条，过渡已被全局 `prefers-reduced-motion` 规则中性化。

浏览器实测（隔离合成预览，真实按键而非 `focus()` 合成，1440×900）：结构快照 —— `.skip-link` 是文档第一个可聚焦元素，`tablistCount=0`，侧栏名随视角变化（`#/admin/*` → 管理工作区导航，切 `#/me/dictionary` → 个人门户导航，再切回 `#/admin/datasources`），面包屑为 `NAV|面包屑`，正文 `region` 名「页面内容」且 `tabIndex=0`，触发器 `haspopup=dialog`/`keyshortcuts` 齐备，视角按钮 `aria-current` 为 `true/false`，横向溢出 0。真实键盘跳过流程：`Tab` 首站落「跳到主内容」，`Enter` 后 `activeElement` 即正文 region、`location.hash` 未变；120ms 过渡结束后实测 `top:10/left:12`（首轮在 t=0 读到 `top:-48` 属过渡未完成的测量时机噪声，已复测覆盖）。焦点环：跳过文字对白底 5.91:1、边框 4.70:1，面包屑 5.11:1；搜索框内联 outline 移除后实测 `2px solid rgb(0,113,227)` offset 2px。组合框序列：`#/admin/users` 按 `Ctrl+K` 开面板，输入「数」得 4 个 option、`aria-activedescendant` 指向 `cmdk-opt-1`「元数据与字典」，`ArrowDown` 后 `activeElement` 仍是 `INPUT`（焦点不再被行抢走），`Enter` 落 `#/admin/metadata`、面板卸载且焦点归还 `.search-trigger`。窄屏 500×600（≤620 顶栏搜索框按既有规则隐藏，改由 `Ctrl+K` 打开）：面板宽 468、left 16 / right 484、`maxHeight` 360（=60dvh）、焦点仍在输入框、文档横向溢出 0。共享 hook 回归探针（`src/views/admin/UsersPage.tsx:502` 的 `Dialog`，非本次改动文件）：10 个 tabbable，`创建用户→关闭`、`关闭→创建用户` 两个方向都被拦截，`Escape` 关闭后焦点回到「新建用户」触发按钮——证明 `tabIndex >= 0` 过滤没有把普通弹窗的圈禁改坏。控制台无 error/warning。产物 `ptmcp-u8-skiplink-1440.jpeg`、`ptmcp-u8-cmdk-1440.jpeg`（仓库外，本宿主无图像通道，仅供用户查看）。

顺带记录一个非本片引入的事实：同一轮 `list_console_messages` 出现 6 条 Chrome “issue”（不是 error/warning）——「A form field element should have an id or name attribute」。就 `#/admin/users` 抽样，命中的是该页自己的 `<select>` 与「搜索用户名或显示名」`<input>`（后者的可访问名取自 `aria-label` 或 `placeholder`，本轮抽样用的是一次回退取值、未能区分；前者测到 `id`/`name`/`aria-label` 三者皆空，命名来源需下一切片连同 `<label htmlFor>` 一并核），二者都在对方占用文件里，属跨页既存形态——影响自动填充与部分辅助技术的字段关联。留给后续切片统一处理，不在本次外壳改动里零散修补。

未验证：`aria-disabled` 的不具备管理权限分支在预览里构造不出（管理员 `adminHomePath` 恒非空），只有代码与类型门禁依据；读屏软件对 `aria-current`、组合框 `aria-activedescendant` 变更、region 名称的实际播报质量未测；`prefers-reduced-motion` 下的跳过链接视觉表现未截图验证；真实后端下的搜索结果（面板的数据源命中来自 `listDatasources`）未联调；共享 `useFocusTrap` 的另两个消费方 `Dialog`/`Drawer` 只做了 `Dialog` 探针，`Drawer` 未实测；人工体验接受未做。本切片新增的是外壳行为，无纯函数可单测（工程测试栈为 `node:test` 直跑 TS，无 DOM 环境），故只有浏览器证据，不声称有单元覆盖。

实际检查：`pnpm exec prettier --write`（3 文件全部 unchanged）；`pnpm exec eslint --max-warnings 0` 覆盖 `AppShell.tsx`、`common.tsx` 退出 0；`pnpm exec tsc --noEmit` 通过；`node --experimental-strip-types --test tests/*.test.ts` 退出 0（167/167，与本项既有基线一致，本切片未新增用例）；`pnpm build` 通过（CSS 29.56 kB / gzip 6.71 kB，JS 464.30 kB / gzip 128.52 kB）；仓库根 `python scripts/check.py` 74 测试 OK。预览服务已停止并确认 127.0.0.1:25175 无监听。

### 切片 U9：共享控件层的语义、可编程名与键盘焦点环

页面路由仍被 DEV-20261031-audit-fixes 占用，本切片继续只做 CLEAN 的共享面：`src/components/common.tsx` 与 `src/styles/index.css`（提交 `3c1519a`）。它修的四类问题都是“长得对、语义和键盘是错的”：

- `Tabs` 挂 `role="tablist"`/`role="tab"` 并用 roving `tabIndex`（未选中项 `-1`），但全站没有任何 `tabpanel`（grep 证明，且五个消费方都在控件之外各自渲染内容）：读屏播报“标签页”却找不到对应面板，而未选中的视图连 Tab 都到不了。现改 `role="group" aria-label="视图切换"` + `aria-current`，每个视图都是原生可达的按钮，`ArrowLeft/Right` 只作为快捷切换（同时移动焦点与选中）。实测 10 条路由 `[role=tablist],[role=tab]` 命中 0；`#/admin/permissions` 三个视图 `tabIndex` 全为 0、`aria-current` 为 `true/false/false`，真实 `Tab` 从「用户」落「角色」（此前不可达），`Enter` 生效，`ArrowRight` 把焦点与 `aria-current` 一起移到「部门」且下划线变 `rgb(0,113,227)`。
- 分页此前是一堆裸 `<span>` 里的按钮：不是 landmark、页码只有数字、`type` 还默认 `submit`。现改 `<nav class="pagination" aria-label="分页">`，页码补「第 p 页，共 N 页」与 `aria-current="page"`，箭头显式 `type="button"`，折叠省略号 `aria-hidden` 并落到 `.page-gap`。实测 `#/admin/datasources`、`#/admin/permissions` 各 1 个 `nav.pagination`（名「分页」，标签 `["上一页","第 1 页，共 1 页","下一页"]`），92×28 右对齐停在 `.table-footer` 内（footer 右 499 / nav 右 485，即 14px 内边距），520×600 下文档横向溢出 0。
- 表单可编程名：`TextInput`/`Select`/`SearchInput` 的 `<input>`/`<select>` 现带 `useId()` 派生 `id`，`SearchInput` 再补一条 `sr-only` 的 `<label htmlFor>`，可访问名来源由 `aria-label` 换成真实 `<label>`。这一条是被实测纠正的：只加 `id` 把 Chrome 的 6 条「form field should have an id or name」换成了 2 条「No label associated with a form field」——占位符和 `aria-label` 都不满足 Chrome 的表单启发式，也进不了自动填充。补真 `<label>` 后共享控件侧归零；实测该 label 为 1×1 `position:absolute overflow:hidden`、`label.htmlFor === input.id`，输入框 260×34、`.input-wrap` 高 34（无布局位移），可访问名经 `labels` 解析为「搜索用户名或显示名」。
- 焦点环被同权重却更靠后的声明整条吞掉（本片最重要的发现）：`src/styles/index.css:310` 的 `.input:focus,.select:focus{…outline:none}` 与 `:286` 的 `.input:focus-visible{outline:2px solid}` 权重同为 (0,2,0) 但排在其后，于是全站输入框键盘焦点零可见指示（SC 2.4.7）。定性没有靠推断特异性：对聚焦中的 `.input` 枚举命中的 outline 规则得 `[.input:focus-visible=2px solid / .input:focus=none / input:focus-visible=2px solid]`，计算值却是 `3px none rgb(53,74,95)`（正是 `outline:none` 的展开：宽度回落 medium→3px、颜色 currentColor），同页 `.btn` 对照组为 `2px solid rgb(0,113,227)`——规则本身有效，只是被压过。删掉 `outline:none` 后蓝边框与 3px 光晕照旧、焦点环回归：10 条路由每个 `.input`/`.select` 实测 `2px solid rgb(0,113,227) @1px`，并用真实 `Tab` 落在搜索框上复核过一次（`boxShadow rgba(0,113,227,.08) 0 0 0 3px`、边框 `rgb(0,113,227)`）。顺带删除 `[role='tab']:focus-visible`——Tabs 改语义后 src 里已无该角色，删除前 grep 只剩这一处 CSS 自身命中。

样式与体积：新增 `.tab-row`（`flex / gap 2px / margin-bottom 14px / border-bottom 1px solid rgb(223,228,233)`，实测 268×37.5 @x=243，与被移除的内联样式逐项一致）、`.pagination`、`.page-gap`。没有视觉回归基线可对照，本宿主无图像通道，几何与计算样式就是本片的全部依据。

未验证：`.page-gap` 的省略号分支没被走到——种子数据每个列表都不足一页，只有代码路径依据；读屏对 `role=group`+`aria-current`、`aria-current="page"` 的实际播报质量未测；`Drawer` 的焦点圈禁仍只有代码路径核对；共享组件无单元覆盖（测试栈 `node:test` 无 DOM 环境），本片全部为浏览器证据。两处既存缺陷定性后落在对方文件里，未顺手改：`src/views/admin/AuditPage.tsx:213,215` 的 `TextInput type="date"` 不传 `label`，是收尾扫描剩下的那 1 条 Chrome issue 的来源（`#/admin/jobs` 以 `initialTab="jobs"` 复用同一组件，同一条缺陷出现在两条路由，不重复计数）；`src/views/admin/DatasourcesPage.tsx:132` 的 `<button class="btn btn--link">` 只包一个 `DotsIcon`，既无可访问名也没有 `onClick`，是死控件。另记两条有意不动的债：`index.css` 现存两处重叠的 `:focus-visible` 块（`:286` offset 1px、`:827` offset 2px），合并要先定全站 offset 取舍，且 `.link` 是 `<a>`、不被 `button:focus-visible` 覆盖，不在本片擅自改视觉；`common.tsx` 仍有 47 处内联 `style`（`EmptyState`/`Stepper`/`PageHeader`/`StatCard` 的 token 化）留作下一片。

实际检查：`pnpm exec prettier --write`（2 文件均 unchanged）；`pnpm exec eslint --max-warnings 0` 覆盖 `common.tsx` 退出 0——把 `index.css` 交给 eslint 会得到「File ignored because no matching configuration was supplied」并因 `--max-warnings 0` 判为失败，CSS 格式门禁由 prettier 负责，lint 范围只放 ts/tsx；`pnpm exec tsc --noEmit` 通过；`node --experimental-strip-types --test tests/*.test.ts` 167/167；`pnpm build` 通过（CSS 29.73 kB / gzip 6.73 kB，较 U8 +0.17 kB / +0.02 kB；JS 464.40 kB / gzip 128.58 kB）；仓库根 `python scripts/check.py` 74 测试 OK。预览服务已停止并确认 127.0.0.1:25175 无监听（停止前按命令行核对 PID 6772 确为本仓 `--mode frontend-preview --strictPort` 实例，避免误杀他人进程）。

### 切片 U10：面包屑的列表语义与可返回的上层

U8 交接点名的余量项，涉及当时仍 CLEAN 的三个文件：`src/App.tsx`、`src/layouts/AppShell.tsx`、`src/styles/index.css`（代码提交 `eb82e07`）。改造的前提是把跨文件契约换成结构化数据，否则语义无从落位：

- 面包屑此前是 25 个不透明 fragment（`git show HEAD:src/App.tsx` 里 `breadcrumb: <>` 恰 25 处），文本形如 `管理控制台&nbsp;›&nbsp;用户`，整体作为 `ReactNode` 灌进 `<nav aria-label="面包屑">`。nav 里没有列表，读屏给不出「第 N 个，共 M 个」的位置；`›` 是文本节点，会按朗读模式被念成符号；间距靠 `&nbsp;` 硬撑。现路由表契约改为 `Crumb[]`（`{ label: string; navKey?: string }`），渲染成 `nav > ol.crumb-list > li`，分隔符换成 `ChevronRightIcon`（`Icons.tsx` 的 `base()` 自带 `aria-hidden`），实测每个 `li` 的分隔子节点是 `svg[aria-hidden=true]`、可访问名不再含箭头。
- 上层不可回：详情路由中间的「用户」「平台与客户端」只是文本，键盘与鼠标都没有返回出口的，只能靠浏览器后退或翻侧栏。现带 `navKey` 的中间层渲染为 `<button class="crumb-link">`，接外壳已有的 `onNavigate` → `NAV_TO_PATH`（不新开路由通道、不改 URL 拼装；`NAV_TO_PATH` 的键在列表页与详情页共用同一 `path` 字面量，故不会映射到带 `:id` 的模板）。9 条三级路由各有一个可点中间层；根层「管理控制台 / 个人门户」没有对应列表页，按事实保留纯文本 `span` 而不是造一个假链接。
- 当前页此前与中间层视觉一致、无语义标记。现末项为 `.crumb-current`（`--ptmcp-text-strong` + 600 字重）并挂 `aria-current="page"`，实测每条路由恰 1 个 `aria-current` 且总落在最后一个 `li`。
- 控件与可达性：`.crumb-link` 保留 12px 文本形态但热区抬到 `min-height:24px`（SC 2.5.8），实测两个中间层按钮的盒尺寸 34×24 与 82×24；焦点环不自写，由共享 `button:focus-visible` 负责，真实 `Tab` 后实测 `2px solid rgb(0,113,227) @2px`。`.app-shell-crumb` 的 top padding 由 12 收到 10 以抵消按钮带来的抬升，实测两种形态高度为 28（两层皆文本的列表路由）与 34（含按钮的详情路由）；旧形态没有留存测量值，这里只给机制与新值，不做像素级 before/after 断言。`flex-wrap:wrap` 是兜底，25 条路由的 label 都是短静态词，换行分支未被复现。

批量改写一次覆盖 25 个路由条目，脚本毁掉了 3 个单行路由对象（`/me/tokens`、`/me/history`、`/me/security`），已按 `git diff` 原样恢复；结构完整性用逐属性计数对 HEAD 复核：`path: '` 25=25、`element:` 26=26、`realm:` 26=26、`prefix: true` 9=9、`permission:` 27=27，`label: '` 由 16 增至 75，恰等于 59 个面包屑项（25×2 + 9）+ 原有 16 个导航组标签。

浏览器实测（隔离合成预览，1440×900，真实按键与真实点击而非合成事件）：16 条路由逐一走查得 `items:2, currents:1, lastIsCurrent:true, sepHidden:true, navH:28`；详情路由 `#/admin/platforms/1` 为 3 项、末项当前页；对比度（对白卡）根项 5.11:1、中间层链接 5.09:1、当前页 13.93:1，均达 AA 的 4.5:1。键盘链路：`Tab` 落 `crumb-link` → 焦点环如上 → `Enter` 回到 `#/admin/users`；鼠标点 `#/admin/platforms/1` 的「平台与客户端」→ 落 `#/admin/platforms`，面包屑同步收回两层（`{"before":{"hash":"#/admin/platforms/1","listH":24,"liTops":[65,62,65]},"afterHash":"#/admin/platforms"}`）。500×600 下文档横向溢出 0，`ol` 高 24 为单行——`liTops` 三个值不等是 `align-items:center` 下不同高度元素的顶边差，不是换行。控制台 `<no console messages found>`（本轮没有聚焦未命名控件，故该条不构成表单命名证据）。

方法教训两条，都指向自检本身不可信：其一，用于校验批量改写的自制解析器返回「route objects parsed: 0 problems: 0」，看起来全绿，实为解析器空转——「无问题」可以被 0 个命中满足，这类判据一律不作数，改读真实 `git diff` 才发现 3 个对象被毁。其二，我先写的「是否换行」启发式是 `new Set(liTops).size`，首轮把 `[65,62,65]` 判成两行；换成 `ol` 自身高度（24）与 `li` 垂直区间是否重叠作判据后结论才翻转。同一个坑在 U8 的过渡期几何测量里出现过一次（t=0 读数），本轮再确认：几何断言要选与对齐方式无关的量。

未验证：读屏软件对 `ol` 位置与 `aria-current="page"` 的实际播报质量；hover 态（`--ptmcp-blue-text` + 下划线）只有 CSS 声明，未实测悬停计算色、未截图；`breadcrumb?.length` 为空的短路分支走不到（25 条路由都有 ≥2 层），只是防御性代码；403/404 整页在 `AppShell` 之外渲染、本就不显示面包屑，本片维持 U7 形态未改；真实后端下带权限过滤的中间层跳转（`firstAccessibleNavPath` 那条链）未联调；面包屑渲染无单元覆盖（工程测试栈 `node:test` 无 DOM 环境），全部为浏览器证据；设计图比对不可行（本宿主无图像通道），人工体验接受未做。

实际检查：`pnpm exec prettier --check src/App.tsx src/layouts/AppShell.tsx src/styles/index.css` 得「All matched files use Prettier code style!」；`pnpm exec eslint --max-warnings 0` 覆盖 `App.tsx`、`AppShell.tsx` 退出 0（CSS 不交给 eslint，同 U9 记录的原因）；`pnpm exec tsc --noEmit` 通过；`node --experimental-strip-types --test tests/*.test.ts` 167/167；`pnpm build` 通过（CSS 30.25 kB / gzip 6.83 kB，较 U9 +0.52 kB / +0.10 kB；JS 464.43 kB / gzip 128.68 kB）；仓库根 `python scripts/check.py` 74 测试 OK。预览服务已停止并确认 127.0.0.1:25175 无监听（停止前按命令行核对 PID 19196 确为本仓 `--mode frontend-preview --strictPort` 实例）。

### 切片 U11：详情抽屉与对话框的名字、焦点和背景锁

页面路由仍被 DEV-20261031-audit-fixes 占用，本切片只改 CLEAN 的共享层：`src/components/common.tsx` 与 `src/styles/index.css`。U8 登记过「Drawer 圈禁只做了代码核对、未实测」，这一轮补上，并修掉实测才露出的三处：

- 对话框和抽屉的标题都是 `<strong>`，`role="dialog"` 没有 `aria-labelledby`。抽屉因此没有可访问名。现改为 `<h2 class="overlay-title" tabindex="-1">`，对话框用 `aria-labelledby` 指向它。
- `useFocusTrap` 打开时聚焦第一个按钮，抽屉里那就是「关闭」，只读详情还没读到标题就被要求关掉。现先聚焦标题；标题不进 Tab 序列。
- `aria-modal` 挡不住背后的页面。Chrome 里打开抽屉后，`#root` 内仍有 34 个可 Tab 元素。现用 `useOverlayLock` 给 `.app-shell` 加 `inert` 并锁 `body` 滚动，计数归还，避免两层叠着时内层关闭把外层一起解开。第一次把 `inert` 加在 `#root` 上，结果抽屉自己也被锁死、标题无法聚焦；浮层改 `createPortal` 挂到 `document.body` 后才分开。
- 关闭时归还焦点必须晚于解开 `inert`。外壳还带着 `inert` 时调用 `focus()`，浏览器直接丢掉，焦点落到 `body`。现把归还函数放进解锁清理里，实测关闭后回到打开它的「查看」。
- 遮罩、标题、正文和页脚的内联定位收进 `.overlay-scrim`、`.overlay-dialog`、`.overlay-drawer`、`.overlay-header`、`.overlay-body`、`.overlay-footer`。抽屉窄屏上限是 `calc(100vw - 24px)`，不再用固定像素把 460px 的数据源抽屉裁掉。

浏览器实测（隔离合成预览 `http://127.0.0.1:25175/#/me/integrations`，无头 Chrome）：点「查看」后焦点落在 `h2.overlay-title`「个人开发环境」，`aria-labelledby` 指向同一节点；`.app-shell` 带 `inert`，`body` overflow 为 hidden。抽屉内 Tab / Shift+Tab 都 `preventDefault` 并停在层内。关闭后 `document.activeElement` 与打开前的「查看」是同一节点，外壳 `inert` 已解除。390×700 下抽屉宽 366、文档横向溢出 0；1424 宽下抽屉 420、遮罩铺满视口（`rgba(11,27,40,.28)`，z-index 40）。标题 `rgb(27,39,51)` 15px、字段标签 `rgb(85,107,126)` 13px，都在白底上。

实际检查：`eslint src/components/common.tsx --max-warnings 0` 退出 0；`prettier --check` 覆盖 `common.tsx` 与 `index.css` 通过；`tsc --noEmit` 通过。未跑全量 `pnpm test` / `pnpm build` / 仓库根 `python scripts/check.py`：工作树里 DEV-20261031-audit-fixes 的未提交文件会让仓库级检查失败，本项不代为修正。预览服务用完即停。

未验证：读屏对 `aria-labelledby` 的实际播报；数据源页 460px 抽屉（该文件仍被对方占用，只核对了调用点 `width={460}` 与 CSS 上限）；两层浮层叠加的计数归还只走了代码，没有页面能同时打开 Dialog 和 Drawer。

### 尚未做

共享工作树里未被 DEV-20261031-audit-fixes 占用的页面已逐页走完（P-01/P-03/P-04/P-05/P-06/P-07/P-10/P-13），跨页的应用层反馈面（通知栈、probing/重定向等待帧、403·404 整页）也已在 U7 处理，外壳键盘链路已在 U8 处理。仍在待接清单：P-02 数据源页、P-08/P-09 平台与客户端页、`DetailPages`、`DelegationsPage`、`AuditPage`、`MePages`（个人门户其余页）——这些文件当前均被对方未提交改动占用，接管前须先确认其落地状态，避免同文件互踩。`table-scroll` 可聚焦区域在 21 处出现中已合规 3 处，其余同样落在上述占用文件内。U8 新登记两项未做，其一已在 U10 了结（面包屑改 `<ol>` + `aria-current="page"` + 中间层可返回，提交 `eb82e07`）；其二仍余一半：Chrome 报出的「form field 缺 `id`/`name`」既存 issue——U9 已把共享控件侧的 `id` 与 `<label htmlFor>` 补齐（6 条降为 1 条），余下那 1 条来自 `AuditPage.tsx:213,215` 不传 `label` 的两个日期框，落在对方占用文件里，只能随页面切片接管。`Drawer` 的圈禁在 U8 只做了代码路径核对，未实测，U9、U10 也未补。U9 另登记两项可独立推进的共享面余量：`common.tsx` 剩余 47 处内联 `style` 的 token 化（`EmptyState`/`Stepper`/`PageHeader`/`StatCard`），以及 `index.css` 两处重叠 `:focus-visible` offset（1px vs 2px）的合并——后者要先定一个全站值，属需用户或设计契约决断的取舍，未擅自统一。U10 之后 `src/App.tsx` 与 `src/layouts/AppShell.tsx` 这两块 CLEAN 外壳面已无登记余项，只剩 U10 自己新记的两条小口子：面包屑 hover 态只写了 CSS 未实测计算色，以及 `.crumb-list` 的 `flex-wrap` 兜底分支在现有 25 条短 label 路由下走不到（两者都不构成缺陷，记为证据缺口）。DESIGN.md 记录的 SAP 调色板（`#0057d2`）与实现所用 Apple 调色板（`#0071e3`）之间的契约偏差处置——需用户确认以哪一侧为准，未擅自动手。P-10 的自增权防护与 NAMESPACE/DATASOURCE 级授权按上文理由保留未实现；组织部门写操作缺权限码，未擅自新增。DESIGN.md 记录的 SAP 调色板（`#0057d2`）与实现所用 Apple 调色板（`#0071e3`）之间的契约偏差处置——需用户确认以哪一侧为准，未擅自动手。P-10 的自增权防护与 NAMESPACE/DATASOURCE 级授权按上文理由保留未实现；组织部门写操作缺权限码，未擅自新增。

## 结果与证据

设计层：U1–U10 修复的是可度量的可达性、控件与状态诚实性缺陷，不等于按设计图完成视觉还原（图像通道不可用）。除配色与热区外，实际改动集中在“页面声称却做不到的能力”：假数据列表、语义错误的测试连接、无处理器的死链接、把失败显示成空、无脑报成功的复制、把“成员数未知”当成“0 个成员”放行删除、把 3 次非原子写入报成一次保存、用不完整目录覆盖真实角色/权限分配、登录页那句没有实现依据的“登录行为将被审计”，U7 里“键盘关不掉的通知”“失败通知会在读完前自己消失”“核对会话时把已登录用户画成登录页”三处与界面承诺不符的行为，以及 U8 里外壳的四类“看得见但按不到”：视角切换挂 tablist 却没有 tabpanel（语义谎报）、无权限入口用 `disabled` 直接把自己从键盘序列里删掉、正文滚动容器不可聚焦（没有跳过导航的落点，也没有键盘滚动出口）、弹层圈禁边界与原生 Tab 不一致导致焦点漏到背后的页面。U9 把同一把尺子量到共享控件自身：有 tablist 无 tabpanel 的不止外壳、分页根本不是一个 landmark、有 `aria-label` 却拿不到 Chrome 认的可编程标签，并实测出一个静态检查看不见的缺陷——同权重却更靠后的 `outline:none` 把全站输入框的键盘焦点环抹掉，界面看起来“聚焦了”而键盘用户什么也看不见。U10 把尺子放到导航的位置感与出口上：面包屑挂在 `nav` 里却不是列表（读屏报不出「第 N 个，共 M 个」）、箭头符号被念进可访问名、详情页的上级只是装饰文字——看得见却点不回，返回只剩浏览器后退。均已按可核验来源收紧或移除。验证层：自动化门禁与浏览器交互已跑，U5 还发现并修掉一个只有实跑才能暴露的回归（`setLoading(false)` 丢失）；U7 的瞬时等待帧靠 MutationObserver 记录提交序列取证，人眼与截图都无法稳定捕获；U8 的焦点漏出同样是合成 `focus()` 测不出、只有真实按键 + 硬刷新才定性的缺陷（Vite HMR 会保留旧版 hook，首轮出现假阴性，已在正史里记为方法教训）；U9 的焦点环归因用的是“枚举该元素命中的 outline 规则 + 对照组”而不是按特异性推断，否则很容易把 `3px none` 误读成浏览器默认值——这也是只加 `id` 反而换出一条新 Chrome issue 的原因，改共享层必须逐项复测而非按规范想当然；U10 补记一条自检反例——自制解析器报「route objects parsed: 0, problems: 0」看似全绿，实为判据能被 0 个命中满足，3 个被批量改写毁掉的路由对象只有读真实 `git diff` 才暴露，此后跨文件批量改写的验收一律以 diff + 逐属性计数对 HEAD 为准，不把「零问题」当通过。人工体验接受与真实后端联调未做。本项不改后端，也不把框架构建当作业务验收。`Acceptance` 仍为 pending：设计图比对与人工接受是用户的通道，不由本项自封。

## 交接

切片 U3（P-13，提交 `c869979`）、U4（P-04，提交 `b4f0acc`）、U5（P-05/P-06/P-07 组织页，提交 `88966ca`）、U6（P-01 登录页，提交 `1f96dfd`）、U7（通知栈 / 等待帧 / 403·404 整页，提交 `c65db63`）、U8（应用外壳与命令面板键盘链路，提交 `7fb93e7`，文档跟进 `fe2ad6a`）与 U9（共享控件层语义 / 可编程名 / 键盘焦点环，提交 `3c1519a`，文档跟进 `eb29823`）与 U10（面包屑列表语义与上层可返回，提交 `eb82e07`）已完成。本项累计只触碰这些路径：`src/views/auth/LoginPage.tsx`、`src/views/me/IntegrationsPage.tsx`、`src/views/admin/{MetadataPage,UsersPage,RolesPage,DepartmentsPage,PermissionsPage}.tsx`、`src/components/{common.tsx,Toast.tsx}`、`src/layouts/AppShell.tsx`、`src/App.tsx`、`src/toast.ts`、`src/styles/index.css`、`src/services/http.ts`、`tests/toast.test.ts` 与本工作项；其余 DIRTY 路径属 DEV-20261031-audit-fixes，未暂存、未改动。

需要对方窗口知道六条事实：其一，U5 之后 `UsersPage.tsx:9` 的未使用 `RequestIdText` 导入已消失，该工作项记录的共享前端 `pnpm check` 阻断点（其工作项第 92 行）不再成立，可以重跑集成门禁；其二，U6 把共享 `Alert` 的 danger 通知从 `role="status"` 改为 `role="alert"`，会影响其未提交页面里 danger 提示的播报时机（只改 aria 语义，布局与文案不变）；已核对其 `tests/browser/` 与 `tests/` 中没有针对 `[role=status]` 的断言（仅 `DetailPages.tsx` 自带 `role="status"` 节点，未受影响），但 danger 提示的读屏表现仍需由其页面所有者在真实后端下复核。其三（U7）：`src/toast.ts` 的 `ToastMessage` 新增可选 `expiresAt`，并导出 `pauseToast/resumeToast`；danger 通知现常驻不自动消失，其未提交页面里任何依赖“失败 toast 4 秒后自己清空”的断言或截图脚本需复核；`ToastContainer` 不再对空队列返回 `null`（两个 live region 常驻，空 `.toast-group` 高度为 0，不改观感），toast 的关闭出口由“点整条”改为“点关闭按钮”，选择器若命中的是 `.table-card` 或整条容器需改指 `.toast-close`；`App.tsx` 的分支顺序调整为 probing → 未登录 → `/` → 404 → 403 → 控制台，未登录访问非法地址现在先见登录页而非 404。其四（U8）：`src/components/common.tsx` 的共享 `useFocusTrap` 现只把 `tabIndex >= 0` 的元素算作 first/last，弹层里带 `tabindex="-1"` 的行不再充当边界——若其未提交页面自定义了“可聚焦候选”列表或断言 Tab 落在某个 `-1` 行上，需按原生 Tab 序列改写；`AppShell` 顶栏搜索触发按钮由内联样式改为 `.search-trigger` 并新增 `aria-haspopup="dialog"`/`aria-keyshortcuts`，命令面板根节点改为 `.cmdk-panel`（宽度 `min(520px,100vw-32px)`），视角切换由 `role="tablist"`/`role="tab"` 改为 `role="group"` + `aria-current`，侧栏 `<nav>` 新增按视角区分的 `aria-label`，面包屑包进 `<nav aria-label="面包屑">`，正文容器改为 `.app-shell-scroll` 且成为 `tabIndex=0` 的命名 region。对其未提交工作的实际影响有两点：依赖 `[role=tab]`/内联样式选择器的脚本会失配；页内可聚焦元素计数与真实 `Tab` 序列的起点变了（首站现为「跳到主内容」，正文区多一个 tab stop），按 Tab 次数寻址的浏览器脚本需重数。其五（U9）：`common.tsx` 的共享 `Tabs` 不再输出 `role="tablist"`/`role="tab"`/`aria-selected` 与 roving `tabIndex`，改为 `role="group"` + `aria-current`，因此未选中的视图现在也在 Tab 序列里（页内可聚焦元素数再增）；`Pagination` 的根节点由 `span` 变 `nav.pagination`（可访问名「分页」），页码按钮新增 `aria-label="第 p 页，共 N 页"` 与 `aria-current="page"`，折叠省略号改 class `.page-gap` 且 `aria-hidden`；`TextInput`/`SearchInput`/`Select` 渲染的表单元素现带 `useId()` 生成的 `id`（由组件在树中的顺序决定、不是稳定字面量，按 id 硬取节点的脚本会失配，请用 class 或 `aria-label` 定位），`SearchInput` 的可访问名改由 `sr-only` `<label htmlFor>` 提供而非 `aria-label`；`index.css` 的 `.input:focus`/`.select:focus` 去掉 `outline:none`，全站输入框聚焦时多一圈 `2px solid` 焦点环（做像素比对截图时这是预期差异），并删除已无消费者的 `[role='tab']:focus-visible`。若其未提交页面自带 `role="tablist"` 标记或断言 `[aria-selected]`，需与新语义同步。其六（U10）：`AppShell` 的 `breadcrumb` prop 由 `ReactNode` 改为 `{ label: string; navKey?: string }[]`（可选，空数组即不渲染该 `<nav>`），`App.tsx` 路由表里 25 个 `breadcrumb: <>…</>` fragment 全部换成结构化数组，三级路由的中间层多带一个 `navKey`；渲染出的 DOM 由「nav 里一段带 `&nbsp;›&nbsp;` 的文本」变成 `nav[aria-label=面包屑] > ol.crumb-list > li.crumb-item`，末项 `span[aria-current=page].crumb-current`、可点中间层为 `button.crumb-link`（新增一个 tab stop，页内可聚焦元素计数与真实 `Tab` 序列在详情路由上 +1），分隔符是 `svg[aria-hidden]`。按旧 fragment 结构（文本节点、`›` 字符或 `nav` 直接子文本）取节点的浏览器脚本会失配，请改指 `.crumb-list li` / `.crumb-link` / `.crumb-current`。改动面是收敛的：全仓 `<AppShell` 调用点只有 `src/App.tsx:460` 一处，`src/navigation.ts` 仅 import `NavGroup`/`NavItem` 两个类型，不受本次 prop 变更影响。

下一个焦点：本项在共享工作树里能独立接管的路由、跨页应用层外壳（通知栈、等待帧、403/404）、外壳键盘链路（U8）、共享控件层语义/焦点环（U9）与面包屑语义（U10）已全部走完，余下 P-02 数据源页、平台与客户端页、`DetailPages`、`DelegationsPage`、`AuditPage`、`MePages` 均处于对方未提交改动中，须待 DEV-20261031-audit-fixes 落地（提交或明确放弃）后再接管，避免同文件互踩；在此之后本项还能独立推进的只剩 `src/components/common.tsx` 剩余 47 处内联 `style` 的 token 化一项——它只动表现形式、不动语义，而本宿主没有图像通道，改完只能靠计算样式与几何逐项对照，风险是 16 个路由的观感变化无人可验收，故建议排在页面接管之后由用户定夺是否值得做。Chrome 报的 form field 命名问题已由 U9 在共享侧解决（只剩对方文件里的两个日期框）。若在这之外还要推进，只剩需要用户先给结论的项（配色契约、焦点环 offset 全站取值、真实后端下的 danger/失败态复核）。

### 切片 U11-a：按接入/数据源/权限矩阵设计稿收视觉（已提交 7907ec3）

用户给出五张控制台设计稿（接入列表与四步向导、数据源列表+右侧详情、权限矩阵三栏+有效权限预览）。本片只改共享视觉，不改接口、不把预览样例接到真实模式，也不把「模板已生成」改成「连接成功」。

- 页头改为 `.page-header`（22px 标题）。统计卡改为色块图标 + 深色数字 + 标签/说明分行，接入列表文案对齐稿面「我的接入 / 新建接入配置」。
- 侧栏底部补「PTMCP 管理工作区 / 个人门户」说明卡；窄于 900px 随图标栏隐藏。顶栏搜索加宽到最多 420px。
- 选项卡说明允许换行，选中态加一圈蓝边；步骤条已完成连接线改蓝。表格行距加大。
- 权限矩阵主体行改成带边框的卡片；分栏把手默认藏起，悬停或键盘聚焦才出现；有效权限预览默认展开。

未做：不把向导平台步改成写死的 Tier 1/2/3 卡片（仍读 `me/access-context`）；不新增无后端的通知铃铛；浏览器像素比对未做。检查：对改动的 tsx 跑 eslint 与 prettier，`tsc --noEmit` 通过；`tests/navigation.test.ts` 与 `tests/mcp-template.test.ts` 9/9。全量 `pnpm check` 与提交未做——工作树仍混有 DEV-20261031-audit-fixes 的未提交改动。

### 切片 U12：按五张界面稿做像素级收敛与统一风格（已提交 7907ec3）

用户给出五张控制台界面稿（`PTMCP接入配置界面总览.png`、`MCP接入配置流程界面合集-3.png`、`PTMCP数据接入配置流程概览-2.png`、`数据源管理控制台界面.png`、`PTMCP权限矩阵管理控制台.png`）后，本切片把共享视觉层按稿面重做，而不是逐页打补丁。基线截图（1440×900 与 390×844，隔离合成预览）先确认了四处真实缺陷：状态只有裸文字没有胶囊、列表页筛选与表格挤在同一张卡、`td` 折行导致「允许/生效」被压成竖排、390px 下分组标题竖排且顶栏溢出。

同批修正（只动视觉层，未改接口与权限语义）：

- `index.css` 令牌层重定：画布 `#f5f7fa`、描边 `#e7ebf1`、主色 `#1677ff`、文字蓝 `#0958d9`、卡片圆角 12px、抽屉 16px、一层轻投影。新增 `.pill`（浅底胶囊 + 圆点）、`.filter-card`、`.table-caption`、`.btn--tinted`、`.btn--ghost`、`.card-tabs`、`.card-toolbar`、`.pane-head/.pane-title/.pane-sub/.pane-foot`、`.topbar-menu`，并统一细滚动条。
- `AppShell.tsx`：把醒目的「退出登录」收进右上角 `⋯` 账户菜单（Esc 与点击外部关闭），视角切换改成分段控件；窄屏隐藏侧栏分组标题，避免竖排。
- `common.tsx`：`StatusDot` 改为胶囊渲染，全站 23 处状态一次性统一；`TextInput` 补 `width` 以支持筛选项定宽（此前一律 100%，把审计筛选挤成一行一个控件）。
- 三个目标页按稿对齐：接入配置（卡片内视图切换 + 工具栏 + 首列名称/编码两行 + 状态胶囊 + 演示数据说明下移页脚）、数据源（筛选独立成卡、查询改浅蓝实心、重置改幽灵、标题行给列表名与数量、批量操作在选择前禁用）、权限矩阵（三栏统一头部与底部计数条、主体行改圆形头像卡、表头列宽收紧）。
- 审计页顺带修掉同源问题：筛选控件定宽后回到两行，不再一个输入框占一行。

验证：`eslint --max-warnings 0`、`prettier --check src tests`、`tsc --noEmit`、`vite build` 全过；前端契约测试 168/168；`python3 scripts/check.py` PASS。浏览器证据为 1440×900 的接入配置、数据源、数据源抽屉、权限矩阵、审计页与 390×844 的接入配置截图（会话产物，未入库）。390px 复测后 `document.scrollWidth === innerWidth`，顶栏不再横向溢出。

未验证或未做：像素级叠加比对（没有稿面切图与浏览器截图的逐像素 diff 工具）、真实后端模式下的同样走查、其余 10 个管理页的逐页目视复核；稿面中的通知铃铛与「更多筛选」「20 条/页」选择器没有后端支撑，按既有约定不加假控件，因此与稿面存在刻意差异；表格与列表的演示数据仍标注为合成预览，不代表真实登记。

推送状态：U7 提交 `c65db63` 与其 docs 跟进 `d18e438` 当时均未能推送——`git push origin dev` 连续失败于 `TLS connect error: unexpected eof`，`curl https://api.github.com/` 同期返回 000/schannel 握手失败，`gh auth status` 另报 keyring 中 github.com 令牌无效；本会话内该机到 GitHub 的链路问题属外部状态而非仓库问题，未擅自修改 git 配置或重新认证。本轮（U8）按项目记忆记录的方式重试，仍失败于同一处：`git -c credential.helper= -c credential.helper='!gh auth git-credential' push origin dev` 报 `TLS connect error: error:0A000126:SSL routines::unexpected eof while reading`；同机 `curl https://api.github.com` 与 `curl --http1.1 https://github.com` 均返回 000 + schannel 握手失败，连只读的 `git ls-remote origin` 也失败，而 `git ls-remote codeup dev` 同期成功返回 `5047728…`——据此定性为该机到 GitHub 的 TLS 链路不可达，不是凭据、命令形式或仓库问题。未改推 `codeup`：历次切片都只推 `origin/dev`，切到另一 remote 会把本地领先的提交一并公开到团队内部仓，属需用户确认的共享状态操作。当前 `origin/dev` 停在 `1f96dfd`，本地未推的是 `c65db63`、`d18e438`、`7fb93e7`、`fe2ad6a`、`3c1519a`、`eb29823`、`eb82e07`（U10 代码）与本片的文档提交；本轮（U9）在提交后按同一命令再试，仍失败于同一处 `TLS connect error: error:0A000126:SSL routines::unexpected eof while reading`，与 U8 时相比外部链路没有变化。本轮（U10）在代码提交 `eb82e07` 后按同一命令重试，仍报同一处 TLS 错误，并补了一次只读对照：`git ls-remote origin HEAD` 同样失败，而 `git ls-remote codeup dev` 同期成功返回 `5047728…`——与 U8/U9 的定性一致（该机到 GitHub 的 HTTPS/TLS 链路不可达，非凭据、命令形式或仓库问题），未据此改配置或改推另一 remote。链路恢复后按同一命令补推即可，若 `gh auth status` 仍报 keyring 令牌无效则需用户执行 `gh auth refresh -h github.com`。每个切片只暂存自己改过的路径。预览端口 25175 用完须停止并确认无监听。待用户决断：DESIGN.md 与实现的调色板偏差以哪侧为准；P-13 普通模式是否需要后端提供“本人接入列表”端点（当前按契约缺失处理，只显示空态与出口，不伪造）；组织部门写操作是否补权限码（`SENSITIVE_ACTIONS` 现无该码，未擅自新增）；登录页“是否应当审计登录行为”按后端现状已改为不声称，若产品要求审计，属后端能力缺口而非页面文案问题。远端提示仓库已迁移到 `github.com/hxquan-q/PTMCP`，而本机 `origin` 现指向 `github.com/hxquan-q/Datachat`；是否更新 remote URL 属配置变更，未擅自修改。

### 提交与集成验证（2026-11-02）

U11 详情浮层与 U12 视觉收敛已提交 `7907ec3`；时间、枚举与证明表单属审计修复，已随 `39be5fd` 提交。共享树复验：React 契约测试 168/168，ESLint/Prettier/tsc/vite build 通过，仓库 `python scripts/check.py` 74 项 PASS（本机无 `python3` 别名，改用 `python`）。

`AuditPage.tsx` 与 `DatasourcesPage.tsx` 同时含审计修复与视觉改动，提交时按 hunk 拆分：时区导入与引擎/状态枚举进入 `39be5fd`，筛选宽度、死按钮移除与卡片布局进入 `7907ec3`；未整文件暂存其他窗口内容。用户本轮明确授权提交与推送。推送回执（2026-11-02）：`origin/dev` 与 `codeup/dev` 已包含本项提交（当时 tip `6f10a64`）；GitHub 远端返回仓库已迁移到 `hxquan-q/PTMCP`，旧 URL 仍可推送，本机 `origin` 配置未改。

### 切片 U13：参考 React Bits / Arc / Efferd 的共享层升级（2026-11-04）

来源：用户要求参考 `reactbits.dev`、`DavidHDev/react-bits`、`uiarc.dev`、`kuratli/arc-library`、`efferd.com` 优化页面与布局；经选择确认范围为「全站共享层」，强度为「企业级升级」，保留 SAP 白/蓝企业语言。不新增依赖（仍只有 React/ReactDOM），不引入渐变函数，不伪造后端能力。

改动集中在共享层：

- `src/styles/index.css`：新增缓出/强调两档无过冲缓动与 120/220/420ms 时长令牌；登录画布三团实色模糊光斑（`filter: blur()`）漂移，登录卡 88% 白玻璃 + 顶部 2px 蓝线 + 60ms 阶梯入场；内容页最大 1600px 居中并按导航分组 `rise-in`；页面标题 3px 蓝竖条、导航选中竖条、Tab 下划线中心展开；统计卡指针柔光/强调线/抬升、表格行悬停首列蓝线、加载呼吸点、空态图标浮动；遮罩/对话框/抽屉/Toast/顶栏菜单入场；reduced-motion 下动画与过渡压到 0.01ms（含 `animation-delay`）。另修移动端真实缺陷：`.data-table .link` 是 `<button>`，默认 `white-space: normal` 会把首列名称折成两行，显式 `nowrap` 并保留 24px 可点高度。
- `src/views/auth/LoginPage.tsx`：加入纯装饰 `.login-aurora` 光斑层（`aria-hidden`），表单结构不变。
- `src/layouts/AppShell.tsx`：内容区包一层 `key={active}` 的 `.app-shell-page`，切换分组时整页入场；详情页共享 active，不重挂载。
- `src/components/common.tsx`：`StatCard` 增加 `useCountUp`（640ms 三次缓出，非数字原样输出，reduced-motion 直接落位）与指针柔光变量。
- `DESIGN.md`：新增「2026-11-04 共享层动效与登录表面」，记录令牌、表面与动效边界。

实际检查：`pnpm --ignore-workspace --config.verify-deps-before-run=false check` 全过（ESLint `--max-warnings 0`、Prettier、`tsc --noEmit`、主站与 OAuth 两套 build）；`pnpm ... test` 204/204。Impeccable detector 仅余两条 warning，均为刻意/既有：`.toast` 的 3px 左边线（既有样式，未改）与 `.page-header-copy::before` 3px 页签竖条（本切片刻意标识，非卡片侧边条）。浏览器实测（隔离合成预览 `http://127.0.0.1:25175`，Playwright；agent-browser 在本机连续挂起，改用 Playwright）：1440×900 与 390×844 的登录页、工作区、接入配置；统计卡悬停柔光、表格行悬停蓝线、抽屉、对话框、skip-link 焦点；390px 下统计卡 2×2、Tab 横向滚动、表格 `scrollWidth 923 / clientWidth 286` 可横向滚动；reduced-motion 模拟下 `.app-shell-page`/`.stat-card`/`.nav-item` 计算时长 `1e-05s`，计数直接落位为 `6/3/2/0`。

未验证：真实后端联调、OAuth 页面与权限验收（预览为合成数据，不能作为验收证据）；读屏软件实际播报；Safari/Firefox 的 `backdrop-filter` 与 `inert` 表现。预览端口 25175 的 vite 进程停止后数秒内被宿主侧重新拉起，判断为其他会话/看护进程所有，未继续强行终止。会话截图（仓库根 `01-…12-*.png`）未入库，验证后已删除；`.playwright-mcp/` 为既有未跟踪会话目录，未清理。共享树中 `index.css` 同时含另一窗口的 `.oauth-*` 样式块，本次按 hunk 归属只暂存本切片的共享层改动，不整文件带入；`package.json`/`tsconfig.json` 的 OAuth 构建改动也未暂存。仓库根 `python scripts/check.py` 因另一窗口未跟踪的 `DEV-20261104-oauth-react-pages.md` 状态字段非法而失败，与本切片无关，未修改他项文件。提交 `7bd93c9` 已推送 `origin/dev`（`3c9180c..7bd93c9`）。

### 切片 U14：个人门户新增「个人设置」分组（2026-11-04）

来源：用户指出「账号安全」不应与查询、令牌等业务入口平铺，应归入个人设置。原 `ME_GROUPS` 把 6 个入口放在同一个「个人门户」组，侧栏没有设置层级。

改动：`src/App.tsx` 将 `me-security` 从「个人门户」组拆出，新增「个人设置」组，当前仅含「账号安全」；路由 `/me/security`、导航 key、面包屑与页面内容均不变，P-01 契约不受影响。

实际检查：`pnpm --ignore-workspace --config.verify-deps-before-run=false test` 204/204；同参数 `check` 全过（ESLint `--max-warnings 0`、Prettier、`tsc --noEmit`、主站与 OAuth 两套 build）。浏览器实测（合成预览 `#/me/security`，1440×900）：侧栏出现「个人设置」分组标题，其下「账号安全」保持选中；「个人门户」组保留 5 个业务入口。未验证：390px 下分组标题按既有窄屏规则隐藏，仅靠组间距区分（该规则未改）。

### 切片 U15：账号安全页重做账号概览与设置双栏（2026-11-04）

来源：用户指出「个人设置」分组落地后，账号安全页本身仍是两张 640px 窄卡堆叠、缺少账号资料，页面观感不成立。本片按 P-01 补齐账号信息，并把页面重排为「概览带 + 设置双栏」。

- 概览带：浅蓝实色底（非渐变），左侧头像首字/图标、displayName、username、部门、角色徽章与平台管理员标识；右侧会话到期时间（`formatTaipeiTime`，minute）与「退出当前会话」。窄屏纵向堆叠，退出按钮占满行宽。
- 修改密码卡：图标化标题、字段 16px 节奏、服务端失效说明；底部操作带把「修改后需重新登录」与清空/提交分开。表单改为 `<form onSubmit>`，行为与校验规则不变。
- 当前可见平台卡：340px 侧栏，平台名 + Tier 徽章 + 语义圆点，空态与加载态分开，底部注明权限交集规则。
- 退出登录沿用 `logout()`，通过新增 `ptmcp:signed-out` 事件让 App 回到登录页，不显示「会话过期」警告（主动退出不是异常）。
- 清理拆分重构遗留在 `SecurityPage.tsx` 末尾的孤立注释。

实际检查：`pnpm --ignore-workspace --config.verify-deps-before-run=false test` 204/204；`lint`、`format:check`、`typecheck`、`vite build` 全过。浏览器实测（合成预览 `#/me/security`）：1440×900 双栏与 390×844 单栏均无横向溢出，控制台 0 error；点击「退出当前会话」回到无警告登录页，重新登录后回到账号安全页。未验证：真实后端改密与退出联调、读屏播报。

### 切片 U16：人员组织改为左树右表并支持拖拽调整（2026-11-04）

来源：用户要求人员组织管理做成「左树右表」：左侧部门、右侧成员；成员行展示账号、姓名、角色、状态与操作（开启、禁止、删除、更多，更多中可改资料与重置密码）；支持创建和管理部门/用户，并可拖拽变更归属。

- 新增 `src/views/admin/OrgPage.tsx`：左栏部门树（搜索、全部成员根节点、展开/折叠、子树成员数、停用标记、行内新建/编辑/删除），右栏成员表（账号、姓名、角色标签、状态、操作）。行操作按权限显示：开启/禁止、删除、更多；「更多」打开成员抽屉，集中编辑资料、部门、角色、状态并重置密码。新建成员默认落在当前选中部门。
- 新增 `orgDialogs.tsx`、`orgTree.ts`；复用既有 `orgForms.ts` 校验与 `orgPortalApi.ts`（写操作携带 `If-Match` 版本，创建携带 `Idempotency-Key`，重置密码要求 10～500 字理由并进入审计）。成员写入按资料 → 部门 → 角色分三次提交，失败时标明已生效步骤，不伪装成原子事务。
- 拖拽：部门拖到另一部门或根节点改变上级，成员拖到部门改变归属；禁止拖到自身、当前父级或后代；成员目录加载失败时阻断部门删除，避免基于不完整清单判断。
- `App.tsx`：新增 `/admin/org`（navKey `admin-org`），导航将原「部门」「用户」合并为「人员组织」；`/admin/departments`、`/admin/users` 保留为别名，旧书签仍可用；用户详情面包屑改挂 `admin-org`。`navigation.ts` 新增 `permissions` 任一命中语义，`permissionsContext.ts` 暴露 `has(code)`。
- 删除 `DepartmentsPage.tsx`、`UsersPage.tsx`（能力并入 OrgPage）。`styles/index.css` 追加 `.org-*`：288px 树面板、拖放目标内描边、成员表最小宽度、抽屉密码区、≤900px 与 ≤620px 响应式。
- 无障碍：树行、删除按钮、角色勾选框使用显式 `aria-label`，避免嵌套按钮与 Field 外层标签污染可访问名；表格状态统一为「启用/停用」，行操作保留「开启/禁止」动作词。
- 文档：修正 `docs/evidence/mcp-capability-audit-20261030.md` 中指向已删除 `UsersPage.tsx` 的本地链接，保留历史路径文本并改指合并后的 OrgPage。

实际检查：Prettier、`tsc --noEmit`、ESLint `--max-warnings 0`、`pnpm test` 209/209、`vite build` 全过；仓库根 `python scripts/check.py` PASS（含上述历史文档断链修复）。

浏览器实测（隔离合成预览 `http://127.0.0.1:25175/#/admin/org`，Playwright；1440×900 与 390×844）：部门树搜索/折叠/展开与平铺结果、按部门过滤成员与过滤空态、状态切换、新建与删除成员、部门对话框校验、成员抽屉重置密码客户端校验、部门拖到根再拖回、成员拖到另一部门再拖回、移动端列堆叠。测试产生的模拟数据已恢复（临时用户删除、部门层级还原），预览数据未入库。

未验证：真实后端联调与权限验收；412 版本冲突、目录加载失败告警、无权限按钮状态（合成预览未制造这些状态）；实际部门创建提交与实际密码重置提交（为保留演示凭据未执行）；读屏软件实际播报。已知文案小瑕疵：共享 `passwordError` 的「密码需12～64个字符」缺空格，属既有文案，本片未改。

自审：本切片为自审，无独立评审。共享工作树中 `index.css` 同时含另一窗口的 `.oauth-*` 块，按 hunk 只暂存 `.org-*`；`package.json`、`tsconfig.json`、`README.md` 与其他窗口的 OAuth 文件不进入本片提交。
