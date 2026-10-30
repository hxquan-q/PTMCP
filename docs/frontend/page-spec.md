# PTMCP 页面与交互规格

版本：1.0-design · 日期：2026-10-23 · 状态：页面目标规格；部分页面已有实现，完整 UI/契约验收仍需逐项证据。

本文件给出一期全部控制台页面。字段约束、错误码和 HTTP 行为以[API 契约](../backend/api-spec.md)为准；共用交互以[开发规范](dev-guide.md)为准。路由为计划中的 UI 路由，均不是已部署链接；[低保真](wireframes.md)用于走查。

## 1. 通用页面协议

所有受保护页面先获取会话和权限。管理 API 前缀为 `/api/v1/admin`，个人 API 为 `/api/v1/me`，会话为 `/api/v1/session`。下文接口省略这些前缀时会明确标注。个人页强制本人，无 userId 选择器；所有管理接口由服务端再校验委派范围。

共用状态：首次请求显示结构化 skeleton；刷新保留已有非敏感内容并标记刷新；空状态解释“没有记录/没有可见资源/筛选无匹配”的区别；错误内联给出原因、requestId 和允许动作。401清空敏感内容后登录；403说明权限不足；404对象已删除或不可访问；409重新读取差异，不能静默覆盖；429/503不自动重放写操作。加载中不允许重复提交。

所有列表使用 cursor，每页默认20、最大100，默认不显示总数或总页数。权限或筛选变化导致 CURSOR_STALE 时回到首批。日期筛选请求区间≤31天；时间显示用户时区。所有修改/删除版本化资源带 If-Match；写操作遵守 CSRF、幂等键和服务端校验。

## 2. P-01 登录与安全页

**路由**：`/login`、`/me/security`。**角色**：全部账号；登录页可匿名。**关联**：FR-01。

| 页面 | 字段/展示 | 操作与接口 |
| --- | --- | --- |
| 登录 | username、password、通用错误、锁定等待说明 | GET session/csrf；POST session/login；成功重新取CSRF+GET session/me，跳安全returnTo |
| 账号安全 | username/displayName/部门/会话到期；oldPassword/newPassword/确认值 | PUT session/password；POST session/logout |

登录不提供“记住密码”持久化 token。用户名按契约校验；密码不裁剪。创建/改密/重置的新密码12～64字符且UTF-8≤72字节，不静默截断或客户端预哈希。错误不区分未知用户。锁定时显示等待时间提示，不能依赖前端倒计时解锁。改密前提示将撤销全部 ADMIN/AS/OAuth/PAT，成功清空内存并重新登录。密码表单离开即清除；无“查看当前密码”。

## 3. P-02 数据源列表与详情

**路由**：`/admin/datasources`、`/admin/datasources/:id`。**角色**：平台管理员。**操作码**：`ptmcp:datasource:list/read/create/update/delete/test/sync`按操作分别检查。**关联**：FR-03、04、19。

| 区域 | 规格 |
| --- | --- |
| 筛选/列表 | keyword、engine、status；列含名称/code/id、引擎、databaseName、DRAFT/ACTIVE/DISABLED、health、lastSyncAt、activeSnapshotId、poolMax、操作 |
| 新建/编辑 | code/name、MYSQL/POSTGRESQL、host/port、databaseName、username、credential、tlsMode=VERIFY_FULL、poolMax 1～8、status。code创建后不可改；engine和身份变更遵守后端约束 |
| 敏感值 | credential仅新建/替换时输入；编辑显示“已配置”，空白保持；不回显密文、真实密码或完整连接串 |
| 操作 | CRUD；POST /datasources/{id}/test 返回jobId；POST /datasources/{id}/sync body={mode:FULL}；详情展示configVersion/resourceVersion |

测试连接为内建探针，不接受任意 SQL/驱动属性/原始 JDBC URL。可以测试草稿配置，临时凭证不保存。连接预算错误定位 poolMax 并解释需运维核准全副本容量。停用确认显示影响，删除有活动依赖时 REFERENCES_EXIST，指引先停用并处理依赖。

加载/空/错：初次无源显示“新建数据源”；网络错误保留筛选；测试失败只显示脱敏原因与任务；Schema无快照时显示“待同步”，不能显示为“无表”。源已删除的详情404返回列表。

## 4. P-03 同步与测试任务

**路由**：`/admin/jobs/:id`（数据源详情可用抽屉打开同一任务）。**权限**：`ptmcp:job:read`及任务源管理范围。**关联**：FR-04、19。

展示任务 id/type/datasourceId/state、processed、total（未知为null）、开始/结束、snapshotId、稳定 errorCode。`GET /jobs/{id}` 轮询；没有 total 不展示虚构百分比。页面离开停止轮询，返回按jobId恢复。只有状态为成功且有完整快照才提供“查看元数据”；失败提供“返回源配置”，不声称部分同步可用。SYNC_IN_PROGRESS跳转已有可见任务或提示查询，不创建重复任务。任务404不泄露其他管理范围内容。

## 5. P-04 元数据与业务字典

**路由**：`/admin/metadata`、`/admin/metadata/:objectId`。**权限**：`ptmcp:metadata:list/read/dictionary`。**关联**：FR-04、05。

先选datasourceId，再按namespaceId、keyword、kind筛选。列表展示catalog/schema/objectName、业务别名、TABLE/VIEW、快照version/generation与变更状态。GET /metadata/objects，GET /metadata/objects/{id}。详情中原始物理键、类型、数据库注释只读；有显式 metadata:read 且在管理范围内才返回管理结构，不与门户READ混为一谈。

业务表单：title≤128、description≤8000、ownerUserId可空；列级业务title/description，最多500列，列名只读且不能新增不存在列。PUT /metadata/objects/{id}/dictionary。纯文本编辑，不支持富文本；保存不执行DDL。版本冲突显示数据库结构/字典差异供人工合并。

视图默认关闭，显示依赖与定义核验状态（以服务端可见字段为准）；说明只有视图和全部底层物理关系READ且依赖/定义专项验证通过才开放，不能用业务说明或视图独立授权覆盖该限制。未知依赖不显示“可查询”。

空状态区分无快照/当前筛选无对象；未知或已重建对象提示重新同步与确认授权，不能把旧授权迁到同名对象。长字典说明可折叠，复制内容明确为业务数据而非系统配置。

## 6. P-05 部门

**路由**：`/admin/departments`。**权限**：`ptmcp:department:list/read/create/update/delete`及有效委派。**关联**：FR-02、08。

左侧部门树按parentId加载，右侧展示id/name/parentId/orderNum/status/pathIds/childrenCount。新建/编辑字段parentId、name≤30、orderNum、status；CRUD /departments。树移动为明确编辑动作，提交前显示旧/新路径和影响子树；禁止成环、跨范围移动。前端不写ancestors/pathIds。

删除有子部门/用户时409，转到关联列表处理；不提供递归一键删除。无委派范围时显示“尚无管理范围”，不把空树理解为无组织；委派到期后清空已失权树。

## 7. P-06 用户

**路由**：`/admin/users`、`/admin/users/:id`。**权限**：user各动作，停用/status、reset-password、assign-role、move分别校验。**关联**：FR-01、02、08。

筛选keyword/departmentId/status；展示username/displayName/部门/角色/status与操作。创建字段username、displayName、departmentId、email≤50、phoneNumber≤11、remark≤500、initialPassword；创建后username只读。CRUD /users；列表不返回密码/authVersion/token。

详情动作：PUT /users/{id}/status、/password、/roles、/department，body分别为status、newPassword+reason、roleIds整组、departmentId+reason。密码动作只写不读。角色选择必须在可管理范围；部门迁移前显示新旧部门与授权将重算。停用、删除、重置均提示凭证失效影响；最后一个有效平台管理员不可停用。错误不回显初始/新密码；提交成功清空敏感表单。

无成员/无筛选结果分别提示；角色清单无法加载时禁止覆盖为空数组。失权用户详情404返回列表，不用缓存继续展示敏感信息。

## 8. P-07 角色

**路由**：`/admin/roles`、`/admin/roles/:id`。**权限**：`ptmcp:role:list/read/create/update/delete`及委派。**关联**：FR-02、07、08。

展示key/name/status/memberCount/managedDepartmentIds。字段key≤100（创建后不可改）、name≤30、status、permissionCodes；CRUD /roles。功能码选择仅显示调用者可委派子集；数据权限通过独立授权矩阵编辑，不把菜单勾选转换为READ。

组织授权管理员查看跨范围共享角色时为只读并解释原因；平台管理员持相应功能码可修改。有关成员/ACL的角色删除409。停用提示有效权限重算。初次加载/空/错遵守通用状态；功能码加载失败不能默认“全选/全不选”覆盖。

## 9. P-08 管理委派

**路由**：`/admin/delegations`。**角色**：平台管理员。**权限**：`ptmcp:delegation:list/create/delete`。**关联**：FR-02、07、08。

GET /delegations按userId筛选；展示被委派管理员、departmentRootId、resourceType/resourceId、allowedActions、validFrom/validTo、状态/version。POST同字段创建，DELETE /delegations/{id}撤销。无编辑端点，变更范围先撤销再新建并明确期间影响；不能假装PUT已经存在。

创建先选管理人，再选部门根和资源范围、可委派动作、期限，显示组合边界：“部门子树 ∩ 资源范围 ∩ 动作 ∩ 有效期”。禁止目标本人自我操作；超出操作者能力/期限拒绝。到期按服务器时间计算，空状态提示尚未委派；撤销提示组织授权管理员将失去对应操作权。

## 10. P-09 平台与客户端

**路由**：`/admin/platforms`、`/admin/platforms/:id`（基础信息/Scope/客户端三页签）。**角色**：平台管理员。**权限**：platform各动作、scope、client各动作。**关联**：FR-06、09、10、18。

平台字段code/name、tier1～3、catalogMode DISCOVER/HIDE、proofMode MTLS/PROXY_SECRET/NONE、proofConfig、status；只展示proofConfigured，不回显secret。Tier1不可NONE；说明private_key_jwt只证明token端点，MCP逐调用仍需proof。CRUD /platforms。

Scope页：GET/PUT /platforms/{id}/scopes，资源树项resourceType/resourceId/resourceGeneration、action META/READ、includeDescendants，最多100条；提交显示范围差异和securityVersion。目录模式说明与META DENY优先级紧邻配置，不能暗示DISCOVER开放列结构。

客户端页：GET/POST /platforms/{id}/clients、GET/PUT/DELETE /clients/{id}。表单name、PUBLIC/CONFIDENTIAL、authMethods、redirectUris、allowedScopes、jwksUri、status。scope固定mcp:catalog/mcp:schema/mcp:query并受服务端审批；PUBLIC不能填secret，默认DCR仅catalog且无资源范围。回调按精确URI校验，不能用通配符；当前版本兼容性待实测。

机密客户端secret若创建时返回，仅一次弹窗展示并清空；列表只能见“已配置”。变更redirect/authMethod与删除会撤销关联授权族，确认框明确提示。平台信任降级必须显式重新批准，不自动补Header。平台/客户端列表空、已停用、proof未配置、Scope为空分别展示，Scope空意味着不可读库。

## 11. P-10 授权矩阵与有效权限

**路由**：`/admin/permissions`、`/admin/permissions/preview`。**权限**：acl:list/read/create/update/delete/preview/effective及委派。**关联**：FR-07、08。

主体面板以用户/角色/部门 Tab 切换目录，支持按名称或字符串 ID 搜索；资源树与规则/预览分成独立面板，桌面三栏、窄屏纵向。公式解释“平台 Scope ∩ 用户规则（含继承）= 最终权限”，DENY 优先，结论只由后端返回。主体选择USER/ROLE/DEPT+subjectId；资源树datasource/catalog/schema/object；PG catalog为无授权勾选框的分组节点，NAMESPACE对应实际catalog+schema，只提交DATASOURCE/NAMESPACE/OBJECT目标。规则表effect/action/includeDescendants/validFrom/validTo/reason、来源和staleResource。GET /acl/grants查询，POST/PUT/DELETE单条规则；一次变更最多100条，页面不伪造不存在的无界批量接口。多条提交需逐条显示结果和未提交项，部分失败不得显示整批成功。

规则输入resourceType/resourceId/resourceGeneration，版本由服务端快照给出；过期或已重建资源禁止复用旧generation。READ/META及DENY优先级见开发规范。时间为左闭右开，validTo须晚于validFrom；开放结束日期仅完整委派可选。每次保存说明新查询最大5秒生效。

组织授权管理员不能以本人/所在角色或部门grant、成员调整或移除命中本人DENY增加自身META/READ，委派内也不例外。页面解释自增权拒绝并指引由平台管理员另行操作；不能仅隐藏本人用户节点而放开间接路径，服务端比较变更前后本人有效权限。此规则也适用于用户角色/部门与角色成员操作。

预览输入subjectUserId、platformId、resourceIds（1～100）、at可选；POST /acl/preview展示meta/read、reasonCodes/sourceGrantIds、versions/evaluatedAt/nextBoundaryAt。未来时点标“模拟，不可执行”；GET /acl/effective可读当前资源与来源。无权主体不出现在选择器；预览不接受SQL、不借业务连接。不用预览成功替代员工真实调用验证。

空状态分无主体/无规则/有效权限为零。资源树加载失败时禁止提交。409重载差异；切换主体/平台后丢弃旧预览。被DENY的节点必须文字说明，半选只是配置差异，不当作有效授权。

## 12. P-11 全局审计

**路由**：`/admin/audit`、`/admin/audit/:requestId`。**权限**：audit:list/read；解密另需独立 `ptmcp:audit:decrypt`、受控审计范围与理由，任何角色默认无权。**角色**：获授范围的安全审计员/管理员。**关联**：FR-16、19。

GET /audit筛选from/to≤31天、userId/platformId/datasourceId/tool/result/verdict/errorCode；列表列时间、员工、平台、工具、目标、脱敏SQL摘要、result/verdict/error、耗时、行数、requestId。数据源选项仅来自其受控审计范围；不能把权限DENY计为系统故障。

列表提供右侧排障速览；有 audit:read 时可点击行或键盘操作速览按钮，查看阶段耗时、错误说明与脱敏 SQL，上一条/下一条保持当前筛选和游标。切换筛选、失权或离页清空旧速览；异步响应只更新当前选中记录。完整详情与解密仍走独立详情页，速览不请求原 SQL。

GET /audit/{requestId}详情：调用上下文、可见对象、使用版本、sqlMasked/指纹、各阶段耗时、预算/截断、错误和持久结局；START遗留为UNKNOWN并解释不确定。无原始结果集，无sqlRaw/sqlFinal默认字段。

解密弹窗说明敏感性，reason 10～500字符，fields选择sqlRaw/sqlFinal，POST /audit/{requestId}/decrypt；显示expiresAt，关闭/过期/离开立即清空。无权限不显示按钮，直接请求仍拒绝；请求失败不尝试从日志取明文。详情404可能为保留期外，按统一提示不泄露其他范围记录。当前不设计批量导出或持久下载原SQL。

## 13. P-12 我的数据字典

**路由**：`/me/dictionary`、`/me/dictionary/:objectId`。**角色**：全部已登录用户。**关联**：FR-12、13。

先逐级GET me/access-context，各级均返回 `{items,nextCursor,evaluatedAt,versions}` 并支持pageSize/cursor：

| level | 必需上下文 | item DTO |
| --- | --- | --- |
| PLATFORM | 无 | {type:PLATFORM,id,name,tier,catalogMode} |
| DATASOURCE | platformId | {type:DATASOURCE,id,name,engine} |
| NAMESPACE | platformId、datasourceId | {type:NAMESPACE,id,databaseName,schemaName:null或PG字符串,displayName} |

只加载当前层，不接收嵌套全量树；切换上级清空下级选择和cursor。上下文不含列，不返回空资源源，显式META DENY优先。platformId/datasourceId必选，databaseName/schemaName、keyword可选；GET me/dictionary。不查询管理全局列表。展示物理路径、表级说明、authorized、metadataVisible。DISCOVER无READ显示“仅目录可见”，详情按钮不可用；HIDE不返回无权对象。PG databaseName仅用于命名空间属性和展示分组，不存在独立CATALOG授权资源。

GET me/dictionary/{objectId}?platformId=...详情由服务端READ检查；展示列顺序/名/type/nullable/主外键/字段说明。外键引用对象无READ时省略该外键并显示foreignKeysRestricted提示，不猜测隐藏对象名。视图受依赖图、定义hash和底层READ的联合限制，未知依赖或无底层READ时不给“可查询”承诺。来源结构/字典说明均纯文本。

空状态区别平台/源未选择、暂无可见表；无READ不给空字段假象，而给权限说明。404/403清理旧结构，不回退展示过期缓存。当前选择平台切换后整份目录缓存失效。

## 14. P-13 MCP 接入配置

**路由**：`/me/integrations`。**角色**：全部已登录用户。**关联**：FR-09、10、18。

先GET me/access-context?level=PLATFORM，以items/nextCursor分页读取可选平台；选择platformId与GENERIC/WORKBUDDY/CURSOR/CLAUDE_CODE/VSCODE，GET me/mcp-config。显示transport、url、authentication、configTemplate、compatibilityStatus、validatedVersion。配置块为纯文本，有复制按钮和反馈；无部署地址/未验证版本时显示待配置/待验证，不给虚假“连接成功”。

OAuth说明首次登录、受控刷新/重新授权；PAT示例只有占位符，并链接PAT创建页，不嵌入完整token。不得将共享secret发给桌面。复制后不自动连接、执行SQL或唤起外部协议；只提供对应版本已验证的人工步骤。失败保留选择项并给requestId。

## 15. P-14 PAT

**路由**：`/me/tokens`。**角色**：全部已登录用户。**关联**：FR-11。

GET me/tokens筛选status；列name/prefix/scopes/platformId/expiresAt/lastUsedAt/status。创建POST me/tokens字段name1～64、Tier3 platformId、scopes子集、expiresAt；默认30天，最大90天。完整值只在创建响应一次弹窗展示，带“已安全保存后关闭”提示和显式复制按钮；关闭清空内存。

DELETE me/tokens/{id}撤销，确认说明客户端需更换凭证。无“查看/找回/恢复原PAT”按钮；遗失引导撤销+新建。网络响应丢失或TOKEN_ALREADY_CREATED显示元数据和撤销路径，不自动重建。空列表显示创建指引，过期/撤销状态保留可查；非本人统一404。

## 16. P-15 我的授权

**路由**：`/me/authorizations`。**角色**：全部已登录用户。**关联**：FR-10。

GET me/authorizations：clientName、platformId、issuedAt、lastUsedAt、status、absoluteExpiresAt。DELETE me/authorizations/{familyId}撤销整族及关联JWT。页面不出现Access/Refresh Token，不提供手动刷新按钮。

撤销前说明影响所有使用该授权族的客户端会话，新查询在状态核验窗口失效。空状态“尚无OAuth授权”，链接接入说明；到期/被撤销提供重新授权步骤，不把控制台退出解释为撤销全部授权。

## 17. P-16 我的查询历史

**路由**：`/me/history`、`/me/history/:requestId`。**角色**：全部已登录用户。**关联**：FR-17、19。

GET me/history筛选from/to≤31天、tool/result/verdict；无用户筛选。列表展示时间、平台、工具、可见目标、脱敏SQL摘要、结果、行数/截断、耗时与requestId。GET me/history/{requestId}显示本人脱敏详情和可执行的排障说明，无原SQL解密、原始结果集或自动重试SQL。

错误说明：权限不足→联系管理员；语法/只读拒绝→让Agent修正；超时/未知结局→按requestId排查，不能直接认定未执行；截断→收窄条件或聚合。无记录与无筛选匹配分别显示；猜测他人requestId统一404，不能展示缓存详情。

## 18. P-17 授权服务登录与同意页

**宿主**：`ptmcp-authz`独立会话/过滤链，入口为标准 `/oauth2/authorize`；不是控制台的管理API页面。**角色**：外部Agent授权中的员工。**关联**：FR-09、10。

登录用户名/密码、CSRF、锁定提示与控制台规则一致；使用 `PTMCP_AS_SESSION`。同意页展示经服务端核验的clientName/clientId、平台、请求Scope、精确回调目标和有效期说明；“同意/拒绝”均走标准OAuth处理并校验CSRF/state/PKCE上下文。客户端自填描述作为不可信文本，不显示其提供的任意HTML/图标脚本。

回调、client、scope非法时展示安全错误和requestId，不提供“忽略并继续”。取消后标准access_denied，不创建授权。页面不声称所有公开客户端都可刷新，显示实际授权策略；成功回调不在页面URL暴露token。

## 19. P-18 通用错误页与导航

**路由**：`/403`、`/404`；未知路由落404，`/`跳首个可用入口。菜单包含管理视角与个人视角，功能失权时即时刷新。错误页提供返回上一安全页面/个人字典/重新登录；不展示服务堆栈、连接串、隐藏表名或伪造部署帮助链接。

不设自然语言问答页、在线SQL执行台、内置审批中心、二期列行权限页面或假实时运行大屏。一期运行状态嵌入数据源/任务/审计页面，完整监控在[运维手册](../ops/operation-manual.md)中配置，不创建无后端契约的数据看板。

## 20. 页面完成定义

每页交付需有实际路由/操作码映射、字段校验、权限与委派校验的网络证据、加载空错与409截图、键盘/焦点验收以及相关FR用例记录。所有凭证页面检查浏览器存储/日志/错误上报不泄漏。UI低保真走查不能代替端到端验收；18组页面的实现与验收须分别核对，不能把部分页面存在等同于全部完成。
