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

### 尚未做

个人门户其余页面（`MePages.tsx` 在 DEV-20261031-audit-fixes 占用清单内，须后接）与 `AuditPage`/`DetailPages` 的逐页细节。P-02 数据源页的死控件与复选区仍待处理，但该文件与 `services/*`、`dsForm.ts`、`platformForm.ts` 等正被 DEV-20261031-audit-fixes 的未提交改动占用，接管前须先确认对方落地状态，避免同文件互踩。DESIGN.md 记录的 SAP 调色板（`#0057d2`）与实现所用 Apple 调色板（`#0071e3`）之间的契约偏差处置——需用户确认以哪一侧为准，未擅自动手。P-10 的自增权防护与 NAMESPACE/DATASOURCE 级授权按上文理由保留未实现。

## 结果与证据

设计层：U1–U5 修复的是可度量的可达性、控件与状态诚实性缺陷，不等于按设计图完成视觉还原（图像通道不可用）。除配色与热区外，实际改动集中在“页面声称却做不到的能力”：假数据列表、语义错误的测试连接、无处理器的死链接、把失败显示成空、无脑报成功的复制、把“成员数未知”当成“0 个成员”放行删除、把 3 次非原子写入报成一次保存、用不完整目录覆盖真实角色/权限分配，均已按可核验来源收紧或移除。验证层：自动化门禁与浏览器交互已跑，U5 还发现并修掉一个只有实跑才能暴露的回归（`setLoading(false)` 丢失）；人工体验接受与真实后端联调未做。本项不改后端，也不把框架构建当作业务验收。`Acceptance` 仍为 pending：设计图比对与人工接受是用户的通道，不由本项自封。

## 交接

切片 U3（P-13，提交 `c869979`）、U4（P-04，提交 `b4f0acc`）与 U5（P-05/P-06/P-07 组织页，随本条提交）已完成；本项已提交路径限于 `src/views/me/IntegrationsPage.tsx`、`src/views/admin/MetadataPage.tsx`、`src/views/admin/UsersPage.tsx`、`src/views/admin/RolesPage.tsx`、`src/views/admin/DepartmentsPage.tsx`、`src/components/common.tsx`、`src/layouts/AppShell.tsx`、`src/styles/index.css`、`src/services/http.ts` 与本工作项，其余 DIRTY 路径属 DEV-20261031-audit-fixes，未暂存、未改动。下一个焦点：个人门户 `MePages.tsx` 与 `AuditPage`/`DetailPages`（三者均在对方占用清单内，须先确认 `DEV-20261031-audit-fixes` 落地状态再接管，避免同文件互踩）；P-02 数据源页同理。每个切片只暂存自己改过的路径。预览端口 25175 用完须停止并确认无监听。待用户决断：DESIGN.md 与实现的调色板偏差以哪侧为准；P-13 普通模式是否需要后端提供“本人接入列表”端点（当前按契约缺失处理，只显示空态与出口，不伪造）；组织部门写操作是否补权限码（`SENSITIVE_ACTIONS` 现无该码，未擅自新增）。远端提示仓库已迁移到 `github.com/hxquan-q/PTMCP`，历次推送仍按既有 origin 成功；是否更新 remote URL 属配置变更，未擅自修改。
