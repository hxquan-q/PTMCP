# PTMCP 业务页面覆盖与差距

源码核对日期：2026-10-27（SAP 布局/状态/结构化证据切片）。对应[页面规格](page-spec.md)的18组页面；本表记录范围、代码位置与契约差距，不是任务状态总表或验收报告。执行状态、网络证据、截图、人工接受和 `NOT_RUN` 原因由 `tasks/work-items/T17-sap-refine.md` 维护。

本轮已落地：全站工作台公共 PageHeader（30px/600，支持返回/状态/操作与 titleId 关联）、SAP chrome 筛选栏与顶底分割线表格、CursorFooter 已加载数量/加载锁、StatusBadge 四类语义（启停/连接/判定/执行）、PageState 空错恢复与 requestId 复制、EvidenceFields 结构化证据（权限预览/审计详情）、权限三栏工作台与判定公式、审计主从速览、字典四层级联独立 loading/错误/重试、接入页客户端卡片与凭证占位符高亮、Users 表单 DTO 边界分组；Datasources/Metadata 迁移公共 useCursorPage（CURSOR_STALE 回首页收拢）。

本次增加独立 `frontend-preview` 开发模式，入口及启动命令见[前端README](../../ptmcp-ui/README.md#不依赖后端的完整前端预览)。30个导航入口覆盖18组页面的前端展示；AS登录/同意为独立预览，实际仍由授权服务承载。预览提供合成数据与空/错/慢/冲突状态，绝不代表真实后端、安全或OAuth验收。

核对包含管理/个人门户源码及本次登录、ACL、组织/个人修复切片；各切片的集成与运行证据仍由工作项记录。下表“已有”仅说明实现或端点存在；仍须按页面规格逐项验证。当前没有18组全流程通过或已发布的证据。

## 源码与接口入口

- 前端页面路径以下均相对 `ptmcp-ui/src/views/`；请求模块位于 `ptmcp-ui/src/services/`。路由与操作码映射见[router](../../ptmcp-ui/src/router/index.ts)，统一HTTP实现见[http](../../ptmcp-ui/src/services/http.ts)。
- 管理Controller位于 `ptmcp-parent/ptmcp-admin/src/main/java/local/ptmcp/admin/api/`；个人门户与会话分别在同模块的 `me/`、`session/`。
- API实施证据入口：[数据源与元数据](../../tasks/work-items/T15-b.md)、[组织与ACL](../../tasks/work-items/T16-a.md)、[会话与个人门户](../../tasks/work-items/T17-api-b.md)、[管理审计与平台只读接口](../../tasks/work-items/T17-api-c.md)。这些后端记录不能替代本轮浏览器验收。
- 本轮新增P-08/P-09/P-11业务结构，由协调者接入现有路由；缺少后端端点时禁用请求/提交并说明未读取状态。`FrameworkHome.vue` 为旧框架样例，不再是 `/` 的业务入口；`/` 经守卫选择有权限的管理入口或个人字典。

## 18组范围矩阵

| 页面组与路由 | 已有代码与真实接口 | 差距及需要的验收证据 |
| --- | --- | --- |
| P-01 登录与安全：`/login`、`/me/security` | `auth/LoginView.vue`、`me/SecurityView.vue`、内存 `stores/session.ts`；`SessionController` 的csrf/login/me/logout/password | 登录前CSRF、登录后轮换与会话读取已接线；需分别留存锁定、错误、returnTo、改密后全部凭证失效、离页清密与浏览器存储证据。运行使用不透明内存会话与主库撤销索引，Sa-Token设计选型尚不能视作运行集成。 |
| P-02 数据源：`/admin/datasources`、`/:id` | `admin/data/DatasourcesView.vue`、`DatasourceDetailView.vue`、`services/data.ts`；`DatasourceAdminController`的CRUD/test/sync | 已有筛选、游标、表单、只写credential、版本化写入、测试/同步入口。需真实验证创建至删除闭环、连接预算、脱敏失败、409合并、无权限动作及数据库两种引擎；不能用页面显示健康值证明业务连接已测。 |
| P-03 任务：`/admin/jobs/:id` | `admin/data/JobDetailView.vue`；`JobAdminController`的GET详情 | 已有按jobId轮询、可见性暂停与离页清理、未知total文本展示。需任务成功/失败/恢复、无快照、404、终态停轮询、页面隐藏后恢复的网络证据；失败不等于可用快照。 |
| P-04 管理元数据：`/admin/metadata`、`/:objectId` | `admin/metadata/MetadataView.vue`、`MetadataDetailView.vue`；`MetadataAdminController`的列表/详情/dictionary | 已有源选择、namespaceId/keyword/kind筛选、只读结构与表/列业务字典编辑。需核对全部字段限制、无快照/无匹配区别、结构变更冲突及只读视图说明；管理结构可见不能替代个人READ授权。 |
| P-05 部门：`/admin/departments` | `admin/org/DepartmentsView.vue`、`orgPortalApi.ts`；`DepartmentAdminController`的CRUD | 已有部门树、创建/编辑/删除、If-Match、名称30字符限制、父节点排除后代，以及移动前旧新路径/子树数量确认。服务端DTO为parentId/name/enabled/ancestors/version，未覆盖规格中的orderNum、childrenCount等；有效委派范围尚未落地。名称边界、移动影响与版本冲突仍需浏览器证据。 |
| P-06 用户：`/admin/users`、`/:id` | `admin/org/UsersView.vue`；`UserAdminController`的列表/创建/读取/更新及status/roles/department | 已有组织档案管理、逐动作权限与部门/角色/状态专用端点接线，角色加载失败时禁止覆盖。后端UserWrite没有initialPassword、email、phoneNumber、remark，也没有用户DELETE或重置密码端点；前端已补联系方式/备注/初始密码、密码重置理由及删除确认，开发预览可操作，正常模式缺接口字段/动作保持禁用；“创建档案”不等于可登录账号开通。版本冲突、自增权拒绝及独立动作能力仍需真实证据。 |
| P-07 角色：`/admin/roles`、`/:id` | `admin/org/RolesView.vue`；`RoleAdminController`的CRUD | 已有key/name/enabled/permissionCodes表单及版本化更新/删除；功能码按当前会话权限子集多选，原有超出子集的码只读保留，独立可委派码目录仍缺。DTO不含memberCount/managedDepartmentIds；跨范围共享角色只读、委派到期等仍依赖未实现的范围能力，不能将会话权限等同完整委派契约。 |
| P-08 委派：`/admin/delegations` | `admin/integrations/DelegationsView.vue`、`adminSurface.ts`；后端未发现委派Controller，`ManagementScope`仍仅支持平台管理员 | 前端已补列表/筛选/分页/错误恢复、受控目录选择、创建确认和版本化撤销；独立开发预览走通创建/撤销。正常模式提交保持禁用，不调用缺失端点。待真实管理范围选择器、列表/撤销端点与越级/自委派网络证据。 |
| P-09 平台/客户端：`/admin/platforms`、`/:id` | `admin/integrations/PlatformsView.vue`、`PlatformDetailView.vue`及同域表单/Scope组件、`adminSurface.ts`；底层平台注册与OAuth客户端服务端代码存在；控制台已接入只读平台目录（`PlatformAdminController`），平台写/Scope/客户端管理端点仍未接入 | 已有平台筛选/列结构、基础信息/Scope/客户端三页签、Tier/proof/Scope白名单/精确回调 URI本地校验；前端已补数据驱动列表/详情、平台创建/编辑/删除、客户端CRUD与一次性secret、Scope增删/差异确认/整组替换/冲突草稿。独立预览可完整操作，正常模式可读平台目录但仍禁用缺端点的保存/写操作，不调用缺失端点、不伪造secret。资源目录缺少可信generation时不可选。待平台CRUD、Scope差异、客户端CRUD/一次性secret、信任降级及真实回调兼容验收。 |
| P-10 ACL：`/admin/permissions`、`/admin/permissions/preview` | `admin/access/PermissionsView.vue`已接入`useAccessWorkspace.ts`，两条路由复用工作区；`services/access.ts`调用`AclGrantAdminController`的grants CRUD及preview | 已有受控主体选项、资源树、单条规则写入与预览接线；需验证generation、META/READ与DENY、冲突及切换清理。命名空间缺少实时generation读取接口，当前禁用新增/编辑，允许预览；PG catalog仅分组。前端已补当前有效资源区及reasonCodes/versions按可用字段展示。真实GET effective尚未实现，正常模式禁止读取；开发预览可检查合成判定，不能宣称真实完整有效权限解释。 |
| P-11 全局审计：`/admin/audit`、`/:requestId` | `admin/audit/AuditView.vue`、`AuditDetailView.vue`；后端已接入只读全局审计列表/详情（`AuditAdminController`，只投影脱敏 SQL/指纹），decrypt 管理端点仍未实现；个人history端点独立存在 | 已有31天日期边界、审计筛选、调用记录列结构、详情脱敏边界和UNKNOWN说明；前端已补列表/详情、排障上下文、版本/阶段耗时和独立权限下的解密申请（理由/字段/到期清理/离页清空）。独立预览使用合成明文；正常模式可查询审计列表/详情，解密仍保持禁用，不调用缺失端点。待decrypt端点、理由10～500字符、明文到期清除及真实网络证据。 |
| P-12 个人字典：`/me/dictionary`、`/:objectId` | `me/DictionaryView.vue`、`DictionaryDetailView.vue`、`mePortalApi.ts`；`MeController`的access-context/dictionary | 已逐级读取个人上下文与目录、提供各层游标加载更多、按READ打开详情，并处理过时请求与ID变化。需上下文分页、切换/失效清理、并发筛选、DISCOVER/HIDE与META DENY、无READ不返结构、外键隐藏及他人对象ID的真实验证。 |
| P-13 接入配置：`/me/integrations` | `me/IntegrationsView.vue`；`MeController`的mcp-config及access-context | 已有平台/客户端选择、通用模板、主动复制和compatibilityStatus展示。模板存在不表示客户端已连接；四客户端版本、OAuth/PAT、刷新与撤销必须另有实际联调证据，example.test地址只能用于合成夹具。 |
| P-14 PAT：`/me/tokens` | `me/TokensView.vue`；`MeController`的tokens列表/创建/撤销 | 已有Tier3平台、Scope、期限、一次性明文弹窗与主动复制，并处理pending及离页后的迟到响应。需90天上限、响应丢失不重建、撤销/过期、弹窗/离页/会话失效清除及浏览器存储/日志不泄漏的证据。当前端点的实际幂等语义须与目标契约分别记录。 |
| P-15 我的授权：`/me/authorizations` | `me/AuthorizationsView.vue`；`MeController`的authorizations列表/整族撤销 | 已有列表、游标、单次撤销锁与撤销确认、失败重读和失效后的重新接入指引；需验证只能撤销本人、关联JWT/刷新族失效及到期后的接入指引。管理会话退出不等于全部授权撤销。 |
| P-16 查询历史：`/me/history`、`/:requestId` | `me/HistoryView.vue`、`HistoryDetailView.vue`；`MeController`的history列表/详情 | 已有日期窗口、工具/结果筛选、重置最近七天、分页原游标重试、脱敏详情与游标；未查询/空/错分离且迟到请求不能改变新查询加载状态；日期按浏览器本地时区编辑、UTC传输、Asia/Taipei展示，应明确时区。需核对完整筛选UI、31天边界、跨时区、截断与UNKNOWN说明、他人requestId统一404、错误后清除旧详情。无原始结果下载或SQL重放。 |
| P-17 AS登录/同意：授权服务 `/oauth2/authorize`流程 | `ptmcp-authz`的`OAuthPageController`、`OAuthSecurityConfiguration`提供服务端登录/同意页，独立PTMCP_AS_SESSION；不属于Vue管理路由 | 现有服务端页包含服务端校验客户端名、Scope、CSRF和允许/拒绝。开发专用Vue预览已补平台、精确回调、有效期策略说明、Scope选择及允许/拒绝结果，不发送OAuth请求。规格要求的平台、精确回调目标、有效期等完整说明仍需核对/补齐；本轮控制台浏览器验证不覆盖AS完整code/PKCE/state闭环。 |
| P-18 错误与导航：`/`、`/403`、`/404` | `router/index.ts`、`components/common/StaticErrorPage.vue`、`WorkspaceLayout.vue`、Admin/Personal布局 | 已有匿名静态错误页、首个有权限入口、管理/个人导航与会话失效跳转。需真实验证直接地址、失权刷新、未知路由、移动菜单Esc/焦点圈定/恢复，以及1280/1440/390px布局。 |

本次为布局/交互收敛切片，不在下表逐组重复“已有”代码入口；本轮组件级变更与验证证据见 [T17-sap-refine](../../tasks/work-items/T17-sap-refine.md)。18 组页面的完成条件仍为每页规格第20节要求：网络证据、409/键盘/截图与人工接受分别保留，页面结构统一不代表业务验收完成。

## 共性差距与验证口径

1. 当前REST成功DTO直出、错误 `{code,codeNumber,retryable}` 与ADR0010的统一外壳存在差距；前端集中适配，不把兼容解包写成服务端迁移已完成。HTTP状态、CSRF与写请求不重放由请求层契约测试覆盖。
2. 管理Controller当前通过 `ManagementScope.requirePlatformAdmin` 限制管理面，委派能力未实现。页面的逐动作功能码判断不能替代后端逐动作鉴权；“隐藏按钮”也不能证明API越权已拒绝。
3. 组织接口目前是数组列表与enabled字段，不能伪装成已支持cursor/status/memberCount等目标契约；用户凭证开通与档案维护分开报告。
4. `tests/http-contract.test.ts`是传输层回归；`tests/access-resource.test.ts`是资源类型/精度和时间窗回归。它们不覆盖真实浏览器、数据库两引擎、委派、AS或外部客户端。实际命令与结果必须指向本轮运行证据。
5. 每个页面的完成条件仍为[page-spec第20节](page-spec.md#20-页面完成定义)：路由/功能码、字段、范围鉴权网络证据、加载空错/409、键盘焦点及相关FR用例。缺接口、尚未运行和人工未接受分别保留，不用“已实现页面数”替代验收。
