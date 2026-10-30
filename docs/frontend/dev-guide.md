# PTMCP 前端开发设计规范

版本：1.2-business · 日期：2026-10-25 · 状态：业务页面与会话适配已进入实现/联调，逐页验收仍待证据确认。

本文定义前端实施与验收要求；当前可运行工程与命令见[ptmcp-ui-react README](../../ptmcp-ui-react/README.md)。[总体方案](../总体方案.md)、[PRD](../product/PRD.md)和[API 契约](../backend/api-spec.md)优先定义业务/接口；[页面规格](page-spec.md)定义逐页目标，[业务覆盖矩阵](business-coverage.md)记录源码映射与已知差距。运行结果、人工接受和未验证项由 `tasks/work-items/T17-ui-business.md` 维护。

## 1. 来源与工程边界

采用 React19/TypeScript/Vite（Tailwind CSS 4 负责样式），工程位于根目录 ptmcp-ui-react；原 Vue 工程已移除，迁移取舍见[React 启动决策笔记](../../.agents/notes/implemented/architecture/2026-10-29-react-app-bootstrap.md)。Reference中的后台参考源码不是UI运行入口。

当前package/锁文件固定Node24/pnpm，样式使用Tailwind CSS 4与工程自有设计令牌，不引入第三方组件库。现有管理/个人工作台、统一请求层、内存会话/权限上下文与业务页面在本工程实施；Reference仍仅用于参考。缺少后端契约的页面明确展示不可用，不使用模拟成功替代联调。

当前目录分工（以源码为准，新增目录应由实际职责驱动）：

```text
ptmcp-ui-react/
  src/
    services/            # 统一HTTP传输、资源API及核对的DTO
    adapters/            # 资源身份、传输字段与页面模型转换
    components/          # 公共状态、图标、Toast与工作台基础组件
    layouts/             # 管理与个人布局、导航、错误页
    preview/             # 无后端合成数据开发模式
    views/auth/          # 控制台登录
    views/admin/         # 数据源、任务、元数据、组织与ACL
    views/me/            # 字典/配置/PAT/授权族/历史/安全
    styles/              # 设计令牌与全局样式
    App.tsx              # hash路由、会话门禁与视角落地
    navigation.ts        # 导航分组、路由映射与权限过滤
    permissionsContext.ts # 仅内存会话/权限上下文
  tests/                 # 已有传输与资源适配契约测试
  package.json
  pnpm-lock.yaml
```

实际命令以package.json为准：`pnpm check`包含lint、格式、类型与构建，`pnpm test`单独执行契约与纯函数回归。契约测试覆盖范围与页面浏览器验收分别报告；构建通过不能支持全部业务已验收的结论。

## 2. 命名、类型和模块规则

遵循 React/JSX 约定与仓库 ESLint 配置（[工程标准](../standards/quality-tooling.md)）。组件文件与组件名使用多词 PascalCase（如 AccessMatrix.tsx），App 例外；Props 使用显式 TS 类型，必填属性明确；禁止修改 Props，应通过回调通知父组件；列表渲染提供稳定 key。

ESLint 的 TypeScript 规则负责上述行为，Prettier 负责排版；不让评审逐行判格式。上游历史配置中关闭的规则不继承为豁免；引入真实 UI 时修复自有模块的违规，不全局关闭规则。

- 组件使用 PascalCase，自定义 Hook 使用 `useXxx`，API 函数使用业务动词，避免 `any` 与跨页面巨型全局状态。
- REST DTO 使用 camelCase；MCP 原始 Tool 入参保持 snake_case。不要对任意响应递归转换大小写，以免破坏 SQL 列名、JSON 值与原始标识符。
- ID 全部使用 string；BIGINT/DECIMAL 保持 string。不能通过 `Number(id)` 排序或拼装后再回传。
- UTC ISO 8601 传输；页面按用户时区显示并标注；有效期限编辑同时显示时区。服务端确认时刻决定权限，浏览器时钟不决定权限。
- 分离 transport DTO、页面表单和资源身份；显示别名不代替 datasourceId/objectId。引用缓存键至少包含身份、平台、数据源和资源版本。
- 原始注释、SQL、字典文字和错误消息纯文本展示。SQL 高亮器只生成受控 token，不使用未经转义的 `v-html`。

```ts
// 当前传输与页面使用的基础形状；字段按服务端DTO/OpenAPI核对。
type Id = string;
type CursorPage<T> = { items: T[]; nextCursor: string | null };
type TransportResult<T> = { data: T; etag: string | null; requestId: string | null };
type CurrentApiFailure = { code: string; codeNumber: number; retryable: boolean };

interface ResourceLabel {
  datasourceId: Id;
  catalog: string;
  schema: string | null;
  objectName: string;
  businessName: string | null;
}
```

## 3. 会话、CSRF 与凭证

控制台当前由后端 `AdminSessionService` 维护单实例内存不透明会话与主库撤销索引；不能把设计中Sa-Token选型当成已完成的运行集成。Cookie 为 `PTMCP_ADMIN_SESSION`，HttpOnly、Secure、SameSite=Lax，由服务端写入。前端不读取/拼装管理 token，不在 localStorage、sessionStorage、IndexedDB、URL、日志、埋点或错误上报中保存 token。管理 API 经同源转发，以 `credentials: 'same-origin'` 请求。

启动顺序：

1. 调用 `GET /api/v1/session/csrf`，读取 `TransportResult.data` 中的 `csrfToken/expiresAt` 并只放内存；不能读取管理 Cookie 来伪造 CSRF。
2. `POST /api/v1/session/login` 带 `X-CSRF-Token`；登录后重新获取 CSRF，再调用 `GET /api/v1/session/me` 获取用户与功能权限。
3. 所有 POST/PUT/PATCH/DELETE 都带 CSRF；退出、改密、创建 PAT、解密、连接测试/同步也不例外。Session/CSRF 轮换遵守服务端值，不缓存旧值跨登录使用。
4. 401 清空会话、权限、资源缓存、临时 PAT 和解密 SQL，回登录页。登录成功恢复安全的同源路由，但不自动重放原写请求或 SQL。
5. 403 区分 CSRF/功能权限/管理范围/业务授权；CSRF 失效仅允许重新获取并让用户明确再次提交，不能无条件重放创建 PAT 等操作。
6. 退出与改密完成清空内存并替换路由。其他标签页可用不含凭证的 BroadcastChannel 通知失效；不能广播 token 或完整权限数据。

AS 使用独立 `PTMCP_AS_SESSION`。OAuth token 属于外部客户端/授权服务，个人门户不代理保存 Access/Refresh Token，也不把控制台 Cookie 送到 `/mcp` 当作 MCP 身份。MCP 配置页只展示服务端发布的通用配置与占位符。

PAT 创建成功只在一次性弹窗内存中展示完整值；关闭、路由离开、账号变化或失效立即清空。不自动填入持久配置、不写入表单恢复缓存。解密 SQL 同样只在当前详情内存展示；后退/刷新不能从 store 或浏览器缓存还原。

## 4. 路由、菜单与操作权限

`/` 跳转到首个有权访问的管理页面或 `/me/dictionary`。匿名用户仅可进入 `/login` 与静态错误页。所有管理路由标注所需 `ptmcp:<resource>:<action>`，菜单与按钮均用后端发放的功能码控制；[页面规格](page-spec.md)列出资源族。

控制台可切换管理/个人视角，切换只是导航。服务端逐请求检查身份、功能码、委派管理范围和资源归属。不能通过隐藏菜单实现授权。组织授权管理员对用户/部门/角色/授权还受有效委派范围约束；其查看的跨范围共享角色显示只读并说明原因，平台管理员持相应功能码可修改。

不把菜单 JSON、平台 Header、客户端 clientId 或本地“管理员”标志当作安全事实。个人 API 使用 `/api/v1/me`，不传 userId；路径/查询参数不能模拟其他人。失去管理权限的标签页下次请求应 403，并刷新菜单，清掉失权页面内容。

路由参数仅含非敏感 ID；安全 returnTo 必须为同源允许路由，不接受 `https://` 或 `//` 外链。审计 requestId URL 编码；不能在 query 中附带原 SQL、密码、PAT 或连接串。

## 5. HTTP 适配与状态管理

每个资源 API 模块只处理传输，页面使用明确的表单/展示模型。当前管理/个人接口成功为DTO直出，`ApiExceptionAdvice`失败返回 `{code,codeNumber,retryable}`；ADR0010定义的 `{code:200,msg,data}` 与嵌套错误外壳仍有后端迁移差距。`services/http.ts`按真实HTTP状态处理错误，并只对可识别的外壳解包；页面统一读取 `TransportResult.data`。requestId优先取 `X-Request-Id`，存在规范错误外壳时兼容 `data.requestId`。OAuth标准端点和 `/mcp` 不套REST适配器。

个人门户用 `GET /api/v1/me/access-context` 按 `level=PLATFORM/DATASOURCE/NAMESPACE` 逐级初始化选项；DATASOURCE需platformId，NAMESPACE需platformId+datasourceId，各层均支持pageSize/cursor，返回 `data={items,nextCursor,evaluatedAt,versions}`。item由type区分：PLATFORM含id/name/tier/catalogMode，DATASOURCE含id/name/engine，NAMESPACE含id/databaseName/schemaName/displayName。不能调用管理全局列表；上下文按平台Scope/目录语义过滤、不含列，权限或版本变化后重新读取。选择上级变化要清空下级选择及游标，PG databaseName只是命名空间属性/展示分组，不产生CATALOG授权级。

列表默认游标分页，读取 `data.items/nextCursor`。没有 total 时显示“已加载 N 条”，不伪造总页数；统一由 `CursorFooter` 呈现计数、加载更多与忙碌锁，无后续游标不显示翻页控件。筛选、排序、平台或数据源改变即清空 cursor。只允许后端白名单排序字段；发新请求时取消过时请求，并通过请求代次避免旧响应覆盖新筛选；游标过期（CURSOR_STALE）统一由 `useCursorPage` 回到首批。

写操作带资源版本/`If-Match`，409 展示“服务端已变化”，保留非敏感编辑草稿并加载差异，用户合并后再提交。不能自动用最新 ETag 覆盖他人变更。PAT、密码、secret 草稿不跨页恢复。

可安全重试的只读 GET 允许少量退避，遵守后端 `retryable` 和 Retry-After。POST/PUT/DELETE、解密、建源测试、同步、创建 PAT 与 SQL 不自动重试。异步任务使用服务端 jobId 轮询：页面隐藏时暂停，恢复时查询最新状态；同一任务不并行发多轮询，不自行宣称超时等于任务失败。

POST 创建/启动任务/撤销/解密按契约发送随机 `Idempotency-Key`，同一意图在24小时内复用该值，不在失败后换key重复执行。收到 OPERATION_IN_PROGRESS/OPERATION_UNKNOWN 先查询资源/任务；已创建 PAT 或 clientSecret 的幂等回放不含明文，应撤销旧资源再新建，不能提示用户“再次点击取回”。登录/退出遵守会话端点自身语义，不将该机制套到 OAuth code/refresh 或 MCP SQL。

内存 store：`session/me` 返回的 userId/username/displayName/department/roles/permissions/managedScopes/expiresAt、当前平台选择、CSRF、任务订阅和资源缓存。URL 可存非敏感筛选。允许持久化的偏好仅为主题、表格密度、语言等无权限含义的值。用户/平台/资源版本变化立即使相关缓存失效；不将缓存目录当作当前有效权限。

## 6. 表格与信息展示

列表默认显示清晰标题、上下文、筛选、加载/空/错状态和主操作。工作台页面使用公共 `PageHeader`，标题为 30px/600，`titleId` 保留区域的 aria-labelledby 关联；副标题、返回、状态与右侧操作分别使用 description/before/status/actions。页面外侧留白由 WorkspaceLayout 的内容容器负责，不在业务页重复增加 padding 或依赖父级覆盖；全站迁移进度见 [T17-sap-refine](../../tasks/work-items/T17-sap-refine.md)。长库名/表名折行或省略后可键盘展开，不能仅 hover 才可见。原名、业务别名、数据源 ID/数据库/schema 区分显示。同名对象不只靠别名区分。

状态使用文字+图标+颜色；启动/停用与连接正常/异常分开。密码/secret 不在表格列出现。脱敏 SQL 默认折叠，长文本虚拟化只优化渲染，不放宽后端预算。个人历史无结果集下载。

截断信息使用专门提示：“已返回500行，行数上限已触发”“某单元格内容已截断”“响应达到1MiB”；只显示服务端报告的原因。不得用 `[TRUNCATED]` 写入数据值，不把 null 显示为空字符串，不把 string 金额转浮点数。列表不自行根据 rowsReturned=500 推断一定截断。

## 7. 授权矩阵专用交互

采用主体栏、资源树、规则表及有效权限预览。资源树有 datasource→catalog→schema(PG)→object 层级，显示物理名和业务名。只向API提交DATASOURCE/NAMESPACE/OBJECT；NAMESPACE绑定catalog+schema，MySQL的schema为空。PG的catalog仅分组，没有授权勾选框，不能生成不存在的catalog资源ID。每条规则明确 ALLOW/DENY、META/READ、生效/到期、继承和来源。

- 半选表示子节点存在不同规则，不能代表“部分有效 READ”；有效权限由后端预览返回。
- 父节点勾选先生成待提交差异，不立即递归写入大量记录。明确继承范围和未来新增对象是否涉及；对象删除重建仍不继承旧身份。
- DENY 以文字突出；READ ALLOW 隐含 META；META DENY 阻断 READ。UI 不通过切换 READ 隐式删除独立 META DENY。
- 时间窗必须 `validTo > validFrom`；开始/结束可为空的含义以 API 为准。展示当前时点与下一变更边界。
- 提交前展示新增/修改/删除差异、目标主体、资源范围、平台交集和影响期限；无差异禁用提交。
- 平台预览是计算结果，不能直接保存为个人授权。用户切换/过期/版本冲突后预览作废。

## 8. 表单、确认与错误

字段标签固定显示，必填/帮助/错误紧邻输入框；后端字段错误映射到字段，其余保留顶部摘要和 requestId。创建账号、改密和重置的新密码统一校验12～64字符且UTF-8≤72字节，不截断，不引入客户端预哈希。连接密码是只写字段，留空保持原值，有明确“替换密码”意图；不能以 `******` 当作真实新密码提交。连接参数、池预算与平台证明方式使用受限选项。

停用数据源/平台、删除授权/委派、撤销 PAT/授权族、账号停用/重置密码的确认框给出对象、作用范围及“新查询最大5秒、在途尽力取消”的影响。创建/替换密钥明确一次性展示。重复提交用 pending 锁定；失败解除按钮但不擅自重试。

| 失败 | 页面行为 |
| --- | --- |
| 400/422 | 保留非敏感输入，定位字段/规则；语法与权限拒绝不重试 |
| 401 | 清空敏感内存与缓存，提示会话结束，回登录 |
| 403 | 权限/委派/CSRF/平台失败按错误码解释，不展示无权对象细节 |
| 404 | 提示对象不存在或不可访问，返回上级并刷新列表 |
| 409 | 显示版本冲突，加载新版本供人工合并 |
| 413 | 提示请求体超限；管理REST上限1MiB，/mcp HTTP body上限256KiB；不与SQL64KiB预算混淆 |
| 429 | 显示忙碌和允许的等待提示；不自动重放写操作 |
| 503/网络中断 | 显示可用性问题、requestId（若有）、手动重试；未知提交结果先查询对象/任务 |

全局消息用于反馈保存结果；字段校验、权限说明与持续故障留在页面中，不能只靠稍纵即逝的 toast。返回内容中的 msg 及 MCP error.message 均要转义。

## 9. 可访问性、响应式与安全

目标为核心流程符合 WCAG 2.2 AA：完整键盘访问、可见焦点、输入标签、表格标题和正确语义；错误不只靠颜色表达；正文对比度至少4.5:1，大字3:1。模态框锁定焦点、关闭后回到触发按钮，Esc 不无提示丢弃敏感一次性值。权限树提供展开、选中和半选的可读状态。

空/错状态统一使用 `PageState`（SAP 令牌圆标、role=status、可选重试与主操作）；错误带 `requestId` 时提供一键复制（用户明确触发，失败提示手动复制），复制反馈随上下文切换作废。首批读取失败不重复渲染空表；分页失败保留已有行与游标。字典四层级联选择独立 loading 文案、切换上级清空下级与游标、失败可逐级重试。

1280px 桌面宽度完成全部主流程。窄屏改为主体/资源分步，表格可横向滚动且保留关键身份列，不让固定宽度弹窗越界。异步 loading 使用 aria-busy，任务状态变化适度 aria-live，避免频繁读屏。尊重 reduced-motion，不以动画表达唯一状态。

生产启用 CSP、禁止不可信脚本和 framing；请求/响应日志脱敏；敏感响应按后端 no-store 处理。业务字典/数据库注释均是不可信数据，不插入 HTML，不把内容转成组件指令。复制凭证由用户明确触发并显示成功/失败，不自动写剪贴板。第三方监控禁止采集密码框、PAT 弹窗与原 SQL 解密区域。

## 10. 前端验收与交付

必要检查覆盖：Cookie/CSRF适配；直接路由/按钮/API越权；组织委派边界；无READ结构不在响应或缓存；多身份缓存隔离；创建PAT一次展示与无存储泄漏；409人工合并；游标重置；任务失败/恢复；个人历史所有权；截断及类型精度；键盘与焦点。

UI测试不能取代服务端鉴权、双引擎SQL安全和客户端OAuth联调。构建后还需在真实浏览器验证网络、控制台错误、表格/矩阵响应式和敏感存储。命令见工程README与package.json；每次交付在工作项记录构件版本、浏览器版本、执行结果、截图和 `NOT_RUN` 原因，按实际证据确认，不能由页面存在推导通过。
