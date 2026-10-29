# DEV-20261028-gateway-completion：完整问数鉴权网关收尾

ID: DEV-20261028-gateway-completion
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: NOT_RUN
Updated: 2026-10-30

## 目标与边界

来源：用户在能力核查后要求立项并实施完整功能。承接
[能力核查](DEV-20261028-gateway-capability-review.md) 与
[收尾提案](../../.agents/notes/proposed/architecture/2026-10-28-gateway-capability-hardening.md)，
保留现有架构，不建立替代网关或平行连接方案。

完整范围是既定 MySQL/PG 只读网关的资源发现/预注册 OAuth 接入、审计恢复、候选连接发布与健康、
请求取消/容量、密钥兼容以及授权字典上下文；新增数据库、写 SQL、事务/插件、开放 DCR/CIMD、
生产操作和收费服务不在本轮。DCR 保持关闭，CIMD 不在缺少实际连接 pinning 时启用。

不变量：平台与用户权限交集、Deny 优先、guard 前零业务连接、主库 START 准入、terminal 持久后返回、
执行前版本与权限复核；不重试业务 SQL，不因取消 handler 提前释放数据库额度。

本轮基准 HEAD 为 `795fb347a295bb4b65fe0a15f607eac50eaab9cd`。
已有 UI/T17 改动、前轮三份评估文档与 `.impeccable/critique/` 均保留。
UI 改动涵盖 App、PageState、useCursorPage、styles、权限/审计/数据源/角色/用户/字典页面及相应测试；
本轮先不写这些被占用文件。不得把其他工作的 diff 纳入本轮提交。

## 实施与验证

2026-10-29 用户已要求“开始完成后端开发”，由审计转入实施。当前切片承接 T13/T14/T19：
统一发现文档、authorize、token、refresh 与 JWT audience 的 canonical MCP URI；
先增加遗漏/重复/错误 resource 与首次登录跳转的回归，再修复实现。保持预注册客户端、PKCE S256、
权限交集及 guard 前零业务连接；不开放 DCR/CIMD，不改 React 窗口资产。
后续续接身份撤销与 G2～G7，局部通过不代表整体完成。

按根工作流一次完成一个可验证切片；执行为单 Agent。安全关键切片需实际边界测试和独立评审，
适用规则要求的独立评审只委派只读审查，不并行实现或修改共享源码。

| 切片 | 承接 | 完成标准 | 当前状态 |
| --- | --- | --- | --- |
| G1 资源发现 | T19/T13/T14 | 真实 HTTP 401 能发现受信资源与 AS，URI 配置严格验证，预注册路径明确 | completed |
| G2 审计恢复 | T09/T20 | 唯一目录 owner、仅回放关闭段、幂等恢复/损坏告警/UNKNOWN 收敛与故障测试 | completed |
| G3 候选与健康 | T15-c S4 | 错候选不替换 active、CAS 发布、健康绑定版本与目标 | pending |
| G4 取消与容量 | T15-c S5/T19-d | 主体隔离取消、执行清理后归还额度、部署预算语义明确且有边界测试 | pending |
| G5 密钥兼容 | T15-c S6 | 新旧钥兼容、对象绑定与迁移格式、日志不泄漏、恢复仍可读 | pending |
| G6 字典上下文 | T15-c S7 | 三工具契约说明、授权后字典/关系/预算、撤权与恶意注释测试 | [verifying；定向 PASS](DEV-20261030-schema-context.md) |
| G7 组合验收 | T19-e/T20 | 双引擎、OAuth、恢复、前后端适用门禁；真实客户端与部署证据单列 | pending |

G1 写入范围为 gateway 元数据/过滤器/测试、API 合同、相关工作项与笔记。
资源元数据和 challenge 共享唯一受信配置，不从 Host/Forwarded 头生成 URI；
声明协议保持 2025-06-18。

验证环境：使用系统 JDK21 `/usr/lib/jvm/java-21-openjdk-amd64`，`bash ./mvnw --version`
已确认 Maven 3.9.9 / Java 21.0.12.1；Docker Server 29.5.3 可访问。
wrapper 未设置 executable，使用 bash 调用，不改文件权限。已有开发实例不启动/清理，实库验证使用隔离 Testcontainers。

适用门禁：逐片定向测试、gateway/authz/admin 及双方言组合 `verify`、`python scripts/check.py`、
最终 `python scripts/check.py --all`；失败必须修正或保留未完成，不能跳过测试伪造通过。

## 结果与证据

当前切片：网关与管理端预览共用parent_id权威部门主体读取，不信任ancestors缓存；
各节点自身停用/软删只排除该节点，保持有效祖先与DENY。以活动用户档案为单语句锚点，
含直属最多64节点，环/缺父/缺直属/超深拒绝SECURITY_STATE_UNAVAILABLE；
继承关闭仍校验完整链但只贡献直属主体（可用性收紧），不增加部署开关或epoch切换实现。
先补真实MySQL缓存污染、状态组合、合法64层/非法链及网关决定回归，获得有效RED后实施。
独立只读设计核阅已提出上述边界与null直属部门预览要求，实际实现已完成终审。
有效RED：`department-subject-gateway-red.log` BUILD FAILURE/exit1，2026-10-30 05:50:04 +08:00，
12 tests/11 failures/0 errors/0 skipped；证明停用节点仍授予READ、中层停用漏根DENY及非法链被接受。
持久层首轮21 errors来自测试roleKey夹具连字符，不计产品RED；修正后
`department-subject-red2.log` BUILD FAILURE/exit1，2026-10-30 05:53:54 +08:00，
21 tests/20 failures/0 errors/0 skipped，覆盖缓存污染、各节点状态、缺父/缺直属/环/超深。
共享读取器及null安全比较已实施，补充继承关闭所有Directory入口和无效用户错误映射兼容测试，
隔离Testcontainers组合`department-subject-green.log`于2026-10-30 05:59:42 +08:00
BUILD SUCCESS/exit0：Org50、Admin84、ToolPermissionDeny12共146项，0失败/错误/跳过。
独立只读终审实际核阅实现、有效RED、GREEN及exit文件，并比较本片5个源码/测试文件与构建工作区一致，
无新增阻断；评审未重复运行动态测试。局部部门主体读取缺口关闭，不外推整体授权快照或管理委派。
上一轮根检查已实际执行，python scripts/check.py与git diff --check均exit0，62项检查器回归通过。

下一切片：修复DelegationScopeResolver仍信任ancestors及root==target无条件放行。
部门/主体管理范围从target的parent_id完整链判定，不信任缓存；未知或软删target不覆盖，
匹配根须实际存在且未软删。停用但未删除部门仍允许受委派管理，供重新启用，不能照搬业务主体过滤。
64节点与坏链拒绝规则复用同一边界，root==target也完整验链；中间状态不改变父边拓扑。
范围仅为委派读取，不修完整PUT单委派覆盖新旧父/子树、提交前重验、写树防环、epoch或间接自扩权。
计划先补缓存污染/无效根/坏链真实MySQL及HTTP零副作用RED，再最小实现、组合GREEN与独立终审。

当前切片：所有admin handler显式声明功能码，统一MVC入口默认拒绝未声明/无功能码请求；
平台管理员不能绕过端点动作，通用用户PUT仍按实际变化补status/assign-role/move。
身份状态读复用user:read，操作日志复用audit:list，原文访问轨迹复用audit:read，解密单独audit:decrypt。
先以真实MySQL/Servlet验证仅平台标记无权限、相邻动作码不能替代、停删角色/撤码即时拒绝、
依赖故障零写入及平台管理员组合编辑反例，再实现、组合测试与独立只读复审。
本片保留URL/DTO/ETag与CSRF，不扩展为事务内授权重验、最后管理员或自扩权保护。

有效RED：`ptmcp-admin-action-red-valid.log` BUILD FAILURE/exit1，2026-10-30 04:26:25 +08:00，
18 tests /18 failures /0 errors /0 skipped；包括九类列表、无动作码创建、list替代read、
停/软删角色与撤码、权限依赖故障以及平台管理员通用PUT缺少实际变更动作。
前轮`ptmcp-admin-action-red.log`的8项非法角色键夹具错误不计行为RED，仅其10项failure有效。
实现为58个方法级AdminAction与统一默认拒绝MVC入口；测试独立登记58条HTTP合同，
另补缺声明handler、撤成员、撤码后同幂等键重试、audit读码不能解密及真实Cookie/CSRF/个人入口。
首次GREEN尝试`ptmcp-admin-action-green-attempt.log`实际25项，0 failures/24 errors/0 skipped，
24项因测试注入同时匹配MVC和Actuator路由Bean未运行断言；Cookie新用例通过。
已以明确Qualifier选业务handlerMapping；定向重验`ptmcp-admin-action-focused-green.log`
BUILD SUCCESS/exit0，2026-10-30 04:42:23 +08:00，Admin24+Session1共25项，
0 failures/0 errors/0 skipped。覆盖全部58条真实路由合同、未声明handler零副作用、
成员/角色/动作撤销、撤码后的幂等重放、解密隔离和真实Cookie/CSRF/个人入口。
管理API、用户存储/安全状态、身份恢复、OAuth与MCP HTTP的组合回归及实际独立只读终审仍在进行。
首轮组合`ptmcp-admin-action-combined-green.log`在SecurityState13与Org23通过后外部中断，
04:47:35的Surefire记录为EOF与Maven shutdown hook；进程和执行句柄均已消失，
没有完整退出/测试汇总，不计产品失败或组合GREEN。确认无残留构建后，改用独立后台进程重跑同一组合。

恢复核实后台脚本未产生日志，实际重跑在2026-10-30 04:57:05退出1：
`ptmcp-admin-action-combined-fixture-failure.log`中SecurityState13、Org23通过，
admin模块123项有1 failure/0 errors/0 skipped。唯一失败是旧委派自撤销夹具未授
`ptmcp:delegation:delete`，请求被新增入口提前拒绝。仅给该actor补齐真实主库动作角色，
保留原403/SELF_PRIVILEGE_ESCALATION断言；authz/gateway当轮被跳过，不能计入通过。
完整重跑`ptmcp-admin-action-combined-final.log`于2026-10-30 05:04:59 +08:00
BUILD SUCCESS/exit0：SecurityState13、Org23、Admin79、Datasource9、IdentityPassword17、
Session18、OAuth29、McpHttp1，共189项，0 failures/0 errors/0 skipped。
独立只读终审实际读取该日志与exit文件，并按e862ed2至当前源码审查58个动作声明、
主库精确码、默认拒绝、动态动作及CSRF/范围/幂等顺序，未发现新增阻断，可关闭本片旁路。
评审未执行动态测试；提交前事务内授权重验、最后有效管理员、间接自扩权及解密资源范围仍待独立切片。

下一局部修复：通用用户PUT向存储传入三个null，导致已有email/phoneNumber/remark被清空；
DTO不支持编辑这些字段，应保留同一If-Match绑定快照内的原值。先增加真实HTTP/MySQL参数化回归，
分别覆盖普通资料事务和实际停用安全事务，完整比较用户记录及ETag；获得有效RED后再改三个实参。
本修复不改变DTO或版本/权限合同，不另建架构笔记；沿用现有工作项记录实际结果。
已取得有效RED：`ptmcp-user-profile-red.log`于2026-10-30 05:08:42 +08:00退出1，
2 tests /2 failures /0 errors /0 skipped；两条HTTP均成功并正确更新姓名/状态/version，
但完整UserRow对比显示三项资料变成null。只把通用update的三个实参改为同一before快照原值，
保留currentUser版本绑定、动态动作检查与存储CAS。
`ptmcp-user-profile-green.log`于2026-10-30 05:12:59 +08:00 BUILD SUCCESS/exit0，
Admin81、Org23共104项，0 failures/0 errors/0 skipped。
独立只读复核已读取实际RED/GREEN日志并审查同一If-Match快照、存储CAS与两个参数化流程，
未发现新增阻断；未执行动态测试，结论只覆盖联系方式保留。
随后根`python scripts/check.py`退出0，62项检查器回归通过；`git diff --check`退出0。

下一切片：部门完整PUT原子性。当前先moveDepartment提交，再updateDepartment提交；
名称校验或第二次SQL/日志失败会留下已移动的子树。目标为一个OrgStore命令、一个If-Match、
一次目标版本增长，同事务提交父边/资料/后代缓存/操作日志；现有单独编辑与移动复用内部路径。
先以真实Servlet/MySQL覆盖无效名称、操作日志故障、成功移动编辑及旧版本冲突，再最小修复。
本片不改变DTO与端点动作码，不覆盖parent_id权威树/整子树委派/提交前操作者重验、
自扩权或部门epoch/event；这些已知缺口保留，不能据本片称部门权限闭环通过。
首次部门RED执行3项，2个有效failure分别证明无效名称后子树已移动、成功PUT版本误增两次；
日志故障项在创建触发器时因测试用户缺SUPER失败，未执行行为断言，不计有效RED。
故障改用仅本次隔离库的临时CHECK约束拒绝更新日志，不提升用户权限或修改共享数据库配置，重跑后再实施。
有效RED：`department-edit-red-valid.log`于2026-10-30 05:21:58 +08:00 BUILD FAILURE/exit1，
3 tests /3 failures /0 errors /0 skipped；CHECK已实际触发503，随后完整部门记录比较证明移动残留，
另两项分别证明非法名称仍移动、成功ETag误为3。此前触发器权限错误保留为夹具失败，不计本次行为RED。
候选实现把完整PUT收束为editDepartment，由update/move复用内部changeDepartment事务；
锁内核同一预期版本，一次CAS同时写父边/名称/状态，后代缓存及一条操作日志同连接提交或回滚，
返回事务内不可变DepartmentRow，不在提交后另读。
首次组合`department-edit-green.log`于2026-10-30 05:29:17 +08:00 BUILD SUCCESS/exit0，
Org23+Admin84共107项，0 failures/0 errors/0 skipped，三条原子性回归均通过。
独立只读复核指出复用路径给MOVE日志附加name，会把既有UPDATE/CREATE的手工JSON限制
传播至原先只记录计数的MOVE。当前先以历史引号/反斜杠部门名补真实MOVE回归，
取得候选实现的有效失败后恢复原MOVE日志结构；最终结果及复核另记，不累计重复执行。
兼容有效RED：`department-move-compat-red.log`于2026-10-30 05:32:06 +08:00退出1，
1 test/1 failure/0 errors/0 skipped，历史引号与反斜杠名称导致MOVE写JSON日志失败。
现保留MOVE仅descendantsMoved的旧结构，不把CREATE/UPDATE既有手工JSON限制传播给移动。
最终组合`department-edit-green-final.log`于2026-10-30 05:38:25 +08:00 BUILD SUCCESS/exit0，
Org24+Admin84共108项，0 failures/0 errors/0 skipped；包含历史名称兼容及三条完整PUT回归。
独立只读终审已核阅实际最终日志与当前源码，未发现新增阻断；复审未运行动态测试或修改文件。
本片部分提交/目标双增版本问题关闭，原子性不代替部门授权/版本失效或commit丢确认闭环。

身份组合 GREEN：`ptmcp-identity-combined-green.log` BUILD SUCCESS，78 tests / 0 failures/errors/skipped；
覆盖双 MySQL 改密恢复13、真实 ADMIN 改密HTTP2、Session8、Org13、SecurityState13、OAuth27及MCP HTTP2。
Origin 有效 RED 为 `ptmcp-identity-origin-red.log`（8例中1 failure，跨站Origin携合法CSRF错误返回200）；
profile 围栏有效 RED 为 `ptmcp-identity-profile-red-real.log`（2 tests / 2 failures / 0 errors），
此前 fixture 缺登录名的失败不计行为 RED。GREEN 包含非法 Origin/Referer、same-site 拒绝与合法同源成功。
独立只读复审关闭 Pending 资料漂移与恢复过期版本，但发现新建 READY 只按 ID 匹配宿主的账号碰撞风险。
当前续接最小安全修复：前向迁移引入 UNBOUND，可靠供应完成前不签发；所有登录/改密/恢复核 ID+元库规范化登录名，
宿主事务先锁行核绑定再 CAS。新建宿主账号供应仍未完成，不能把本片 GREEN 计作身份全闭环。
绑定有效 RED：`ptmcp-identity-binding-red.log` 为新建仍 READY 的 1 failure；
`ptmcp-identity-binding-http-red-real.log` 为 reset/恢复/ADMIN 登录 4 failures；
`ptmcp-identity-binding-as-red.log` 为 AS 登录 1 failure，三份均 0 errors。
独立复审另发现自助改密在宿主重命名后先创建 Pending，已补 begin 前绑定检查；
`ptmcp-identity-self-binding-red-real.log` 1 test / 1 failure / 0 errors，预期401、实际409。
首次组合因测试重复使用旧 profile version 而停止，随后 RED 跑因新锁测试缺 PreparedStatement import 停止，
均已修正夹具，不计产品缺陷证据。`ptmcp-identity-binding-green-final.log` BUILD SUCCESS/exit0，
112 tests / 0 failures / 0 errors / 0 skipped：SecurityState13、V12→V13迁移1、Org13、
AdminApi26、双库恢复17、Session11、OAuth29、MCP HTTP2；完成时间2026-10-30 02:42:48 +08:00。
覆盖宿主锁跨hash写、ID碰撞、Pending后宿主重命名、新UNBOUND同ID同名拒绝登录/重置、
自助改密begin前拒绝且authority/profile/epoch/operation/session均不变。
根检查 `ptmcp-root-identity-binding-check.log` exit0，62项检查器回归通过；格式已由Spotless生成。
身份绑定片独立只读复审已读取实际 GREEN112 日志及当前 diff，未发现新增阻断；
自助改密 begin 前绑定缺口关闭。复审未重复执行动态测试；UNBOUND 供应仍未完成。

当前切片：封闭通用用户 PUT 绕过专用 status/roles 权限的入口。
通用编辑始终要求 user:update；实际启停、角色整组变化、部门迁移分别追加对应动作，
同一个有效委派必须覆盖整条命令的所有动作与新旧部门，角色上限不跨委派取并集；另核 actor 自身功能码。
原值随完整 DTO 回传不额外索取 status/assign-role/move。角色按集合比较；缺省 roleIds 表示保留。
先用真实 MySQL + Servlet 边界建立拒绝且 profile/roles/version 不变的反例，再验证合法资料编辑与授权变更。
本片不声称修复专用接口旧门禁、平台管理员逐动作码、业务权限自扩张、角色定义/委派并发、
登录时平台管理员快照或启停撤证；这些继续后续闭环。
首次13例被委派窗口时区偏移遮住，不能作权限旁路 RED；
`ptmcp-generic-user-put-fixture.log` 13 failures/0 errors 明确复现新短期委派在UTC往返后偏移8小时。
先修 DelegationStore 的 DATETIME 写入/重叠查询，直接绑定 UTC LocalDateTime，
增加 UTC、Asia/Taipei、America/New_York（跨DST）的精确微秒与半开区间回归，再回到权限 RED。
不自动改存量委派时间；若旧版本曾部署，需基于来源核对并由管理员撤销/重建受影响委派。
时区复审确认四处直接绑定与 UTC 读取契约一致；回归含纽约 spring gap/秋季重叠数字的精确往返。
首次追加回归漏声明 SQLException，第二次 DST request_id 超出 CHAR(36)，均为夹具错误，
已补 throws 并使用 UUID；不作为产品行为 RED 或修复通过证据。

有效门禁 RED：`ptmcp-generic-user-put-red-valid.log` BUILD FAILURE/exit1，
OrgManagementStoreTest 14/14通过（UTC/DST修复）；旧gate下Admin组合编辑14例中12 failures，0 errors/0 skipped。
分别记录组件结果，不能将整体失败误算为UTC回归失败。
独立复审发现授权快照未绑定If-Match的High：预填下一版本后与并发停用交错，可绕过status。
当前先加真实MySQL+MockMvc闩锁回归，暂停控制器读取后的快照，让停用提交后再继续；
以409及停用后的完整UserRow不变为通过条件，再补快照版本相等检查并保留落库CAS。
`ptmcp-user-put-future-version-red.log` 已取得有效RED：1 test /1 failure /0 errors /0 skipped，
预期409实际200。已补before.version与If-Match相等检查并保留落库CAS。
完整定向 GREEN：`ptmcp-user-put-green-final.log` BUILD SUCCESS/exit0，
2026-10-30 03:21:03 +08:00；OrgManagementStoreTest14/14、AdminApiTest43/43，
共57例，0失败/错误/跳过。Spotless四份Java结果经词法一致性检查后回拷，未改前端。
独立只读复审已实际核阅GREEN日志与源码，确认未来If-Match的High关闭，无本片新增阻断；
复审未重复构建。本片不能证明完整会话过滤链或身份闭环。
根检查`ptmcp-root-user-put-check.log` exit0；笔记tree/format/archived三项全局Skill脚本均exit0。

当前启停切片：用户ACTIVE/DISABLED实际变化时，在元库同事务完成profile/roles、
authVersion/identityVersion、ADMIN/AS/PAT/OAuth撤销、epoch、事件及操作日志；
启用不复活旧凭证，同状态/普通资料不额外撤证，UNBOUND保留，CHANGE_PENDING拒绝且零副作用。
先在真实最新迁移MySQL建立撤证、失败回滚和提交回包丢失反例，再实现；
最后有效管理员保护、逐请求平台管理员重算、供应和专用接口授权仍是后续范围。
有效RED：`ptmcp-user-status-red.log` BUILD FAILURE/exit1，2026-10-30 03:30:16 +08:00，
7 tests /5 failures /0 errors /0 skipped；分别复现权限版本未提升、UNBOUND版本未提升、
事件失败未触发回滚、版本耗尽未拒绝、真实commit后丢失确认仍错误返回DEPENDENCY_UNAVAILABLE。
同状态/普通资料不撤证及冲突/Pending零副作用两例通过。生产补丁已接入既有安全事务。
最终GREEN：`ptmcp-user-status-green-final.log` BUILD SUCCESS/exit0，2026-10-30 03:42:57 +08:00，
Org23、Admin43、Session11、OAuth29，共106 tests，0失败/错误/跳过。
新增两项真实MySQL闩锁回归覆盖捕获后的profile/authority/Pending变化及同值ACTIVE保存与并发停用；
旧命令必须VERSION_CONFLICT，完整profile/roles/authority/epoch/event/log和五域凭证快照不变。
首轮75项GREEN没有运行authz，最终组合已补OAuth29；最终命令误写不存在的McpHttpEndpointTest，
gateway只完成编译、没有运行MCP HTTP测试，不能把该项计作通过，后续使用McpHttpBoundaryTest补验。
本片独立只读复审已实际核阅最终106项GREEN日志与源码，未发现新增阻断；未重复运行动态测试。
结论不代替最后有效管理员、供应或完整身份验收。

当前切片：ADMIN平台管理员资格逐请求从当前主库有效角色/成员/功能码重算，仅缓存本请求，
保留原session ID/CSRF/绝对期限；真实Cookie回归先复现撤成员、停/软删角色、删scope后的旧会话越权，
再覆盖新授资格与权限依赖故障。权限读取失败须在过滤链拒绝并返回稳定错误。
本片不代替安全写入事务内actor重验、最后有效管理员保护或多副本会话内容共享。
首轮`ptmcp-admin-scope-red.log`为6例/6 failures/0 errors/0 skipped；其中5例有效复现撤权后仍为管理员、
新授资格不生效。故障例被夹具非法角色键400挡住，不计为依赖故障有效RED；
改用不依赖角色权限表的合法部门创建请求，在旧生产代码上单独补验。过滤器通过既有MVC异常解析器
映射身份附着阶段的DomainException，设置no-store/nosniff，不捕获下游controller/filter异常。
`ptmcp-admin-scope-dependency-red.log`取得有效RED：1 test/1 failure/0 errors/0 skipped，
2026-10-30 03:55:05 +08:00，权限表不可用时旧会话仍成功创建部门（预期503、实际201）。
该例使用隔离MySQL临时重命名权限表并在finally恢复，不影响开发库。
最终GREEN：`ptmcp-admin-scope-green.log` BUILD SUCCESS/exit0，2026-10-30 03:59:53 +08:00；
Org23、Admin43、Session17、OAuth29、McpHttpBoundary1，共113 tests，0失败/错误/跳过。
本次实际运行了MCP HTTP边界测试，补齐上一启停组合误写类名后的遗漏；该类实际只有1例，不能按预计数量登记。
独立只读终审实际核阅日志和四份源码，未发现新增阻断，登录时缓存平台管理员资格的缺口关闭；
复审未重复构建，同请求重复解析/下游异常传播仅静态核验。Spotless仅调整SessionApiTest排版并已回拷。
提交前actor重验、最后有效管理员、共享限流/供应与多副本仍未完成。

当前切片：封闭用户专用status/roles/department写接口的委派拼接与快照漂移。
三端点绑定单次读取的profile.version与If-Match，不自动追新；status保留联系方式与备注。
操作者自身功能码与同一有效委派相交，迁移的两部门、赋角最终整组码均由同一个委派覆盖。
专用动作不额外索取user:update；通用PUT沿用按实际变化组合动作的既定合同。
先建立真实MySQL/Servlet拒绝且完整记录不变、未来If-Match并发以及合法命令回归，再实现和独立复审。
本片不扩大为事务内actor/角色定义重验、最后管理员或平台管理员逐动作码，这些仍是后续安全切片。
有效RED：`ptmcp-user-specialized-red.log` BUILD FAILURE/exit1，2026-10-30 04:05:44 +08:00，
11 tests/10 failures/0 errors/0 skipped；六个权限拒绝反例均实际200，三项真实MySQL闩锁并发预期409实际200，
启停例真实清空email/phone/remark。仅对应专用动作、不要求user:update的合法流程原本通过。
实现复用命令级单委派算法，专用三端点单快照绑定If-Match；原有正向用例补齐真实actor功能角色，
越界迁移用例改用当前版本，防止旧夹具的过期If-Match掩盖范围断言，不改变生产绕过或测试期望。
组合GREEN：`ptmcp-user-specialized-green.log` BUILD SUCCESS/exit0，2026-10-30 04:11:37 +08:00，
Org23、Admin54、Session17、OAuth29、MCP HTTP1，共124 tests/0 failures/0 errors/0 skipped。
只读初审未发现新增阻断；按建议增加两个候选均含assign-role、分别覆盖不同角色码时，
最终请求两角色整组必须403且完整写入效果不变的第12项回归。
追加GREEN：`ptmcp-user-specialized-role-set-green.log` BUILD SUCCESS/exit0，2026-10-30 04:14:27 +08:00，
AdminApiTest 55 tests/0 failures/0 errors/0 skipped；与前述124项组合分别记录，不将重复执行累计。
独立只读终审已核阅实际RED、两份GREEN日志及当前diff，未发现本片新增阻断；评审未修改文件或重跑动态测试。
本片权限拼接、授权快照漂移及status丢失联系方式关闭；事务内重验、平台管理员逐动作码、最后管理员与自扩权仍待后续。

双库 CAS 首次 GREEN：`ptmcp-identity-cas-green.log`，10 tests / 0 failures/errors/skipped，BUILD SUCCESS。
独立实际 diff 复审未发现迟到写/提交不明新增高危阻断；发现恢复 hash 长期保留、控制器 epoch 字段错配及并发完成额外 epoch，已补回归和修复，重验进行中。
有效生命周期/HTTP RED：`ptmcp-identity-http-lifecycle-red.log`，12 tests / 2 failures / 0 errors/skipped；分别为完成未清 hash、应返回200明确版本但仍204旧头。
后续 GREEN、真实会话 Origin/CSRF 和组合 OAuth/网关验证未收敛前，不记身份闭环完成。


身份版本定向 GREEN：`ptmcp-identity-version-green.log` BUILD SUCCESS（2026-10-30 01:20:25 +08:00），
SecurityStateTest 13/13、OAuthAuthorizationCodeFlowTest 27/27，0失败/错误/跳过。
仅覆盖当时版本，后续跨库改密、独立宿主读取和 JWT family 绑定仍须重验。
改密有效 RED：`ptmcp-identity-pending-red-real.log` 2 tests / 2 failures / 0 errors / 0 skipped：
返回 epoch21 而非 profile6，宿主写失败后仍 READY。前两次被格式检查挡住，不计产品 RED。
当前切片按[ADR-0011](../../docs/architecture/adr/0011-跨库身份变更与权威版本.md)完成 V12 expected-hash CAS、
恢复接口、真实双 MySQL 并发/提交不明回归；身份闭环、G3～G5/G7 仍未完成。


2026-10-29 OAuth 资源切片 RED 已实际复现：
`OAuthPropertiesTest` 9 例中 8 failures，非法 URI 被接受；
`OAuthAuthorizationCodeFlowTest` 真实 HTTP/MySQL 16 例中 3 failures（0 errors/0 skipped），
缺失 token/refresh resource 与存储资源被修改均错误返回 200。
日志位于本机临时目录 `ptmcp-oauth-resource-red.log`、`ptmcp-oauth-flow-red-linux.log`，不提交原日志。
Windows Docker mTLS 转发仍不可用，改在专属 WSL 的 Unix socket 内运行隔离 Testcontainers；
使用 OpenJDK 21.0.12.1、Maven 3.9.9 与共享依赖缓存，不关闭 TLS 或连接真实业务库。

OAuth canonical resource 修复已通过 authz 定向测试：真实 HTTP/MySQL 18/18、配置9/9、
token表单策略1/1，均0失败/0错误/0跳过；gateway元数据单测21/21及HTTP发现测试均已BUILD SUCCESS。
覆盖换码/刷新遗漏、错误、重复resource不消费凭证，以及持久resource被篡改或旧授权缺失时拒绝重新绑定。
独立只读安全评审未发现本片新的安全阻断；已修正撤销后refresh测试参数和注释时序，
补充legacy缺失与refresh存储资源篡改反例。评审未运行动态测试，动态结果来自本执行环境。
资源切片日志 `ptmcp-resource-green-final.log`；整体目标仍在实施，不能据此宣布后端完成。

2026-10-30 OAuth撤销与WAL底层组合验证：`ptmcp-oauth-wal-green.log`，BUILD SUCCESS，
真实HTTP/MySQL OAuth 22/22、配置9/9、token表单策略1/1；WAL回放10/10、owner/在线封段4/4，
全部0失败/0错误/0跳过。撤销归属两项及旧快照save回滚撤族已先取得真实RED。
修复先验client归属再处理refresh摘要、JWT本地验签与canonical audience/主体/family绑定；
全局epoch先锁与统一撤族锁序，重放在grant写入前提交撤销，拒绝旧快照复活授权。
独立只读复审已确认OAuth撤销归属、统一锁序、重放撤销提交与旧快照防复活均关闭，无新增阻断；
复审读取实际GREEN日志，未重复运行动态测试。

G2新增两项真实RED：`ptmcp-unknown-owner-red.log`为超时错误恢复2条，
`ptmcp-wal-native-lock-red.log`为同JVM失败竞争者关闭第二句柄后外部JVM错误取得锁（预期23、实际0）。
修复后`ptmcp-audit-recovery-green.log` BUILD SUCCESS：AuditRepository 11/11、WAL回放10/10、
owner5/5、gateway McpAuditWriter 3/3，均无失败/错误/跳过。
已经接入网关恢复worker、START/执行租约同主库事务、先回放封段再处理有据退休boot的过期START、
损坏/容量/主库故障暂停新准入。仅凭超时的旧API无副作用，当前boot及无归属历史不自动UNKNOWN。
G2已完成本片工程验证：V10元库退休围栏拒绝迟到START/heartbeat/confirm，UNKNOWN保留已确认数据源；
v2 boot证据在退休事务提交且全部租约终结后回收，v1保留。旧写方不查围栏，不支持新旧网关混跑。
随后续接权威用户版本/ADMIN与AS会话、G3～G7，整体尚未完成。
worker首轮`ptmcp-recovery-worker-red.log`实际为5例、1 failure、3 errors：
failure为容量不足仍回放并删除封段；3 errors为新测试夹具只改lease_until而未同步heartbeat_at，违反CHECK，
属于测试编写错误，不计产品缺陷。已修正夹具，续加退休后迟到START零副作用及UNKNOWN保留数据源的回归。
有效围栏RED为`ptmcp-recovery-fence-red-valid.log`：12例、2 failures、0 errors/0 skipped，
分别证明UNKNOWN丢失数据源及退休后迟到START被错误接受；此前夹具CHECK错误已单独纠正。
最终`ptmcp-recovery-fence-green.log` BUILD SUCCESS/exit 0：AuditRepository16/16、WAL回放10/10、
owner5/5、worker5/5、McpAuditWriter3/3，共39例、0失败/错误/跳过。
覆盖START先提交的真实闩锁竞争、退休先赢、未过期租约保留证据、真实commit后丢回包、v1保留、
元库/损坏/容量故障暂停与恢复。独立只读复审针对实际diff并读取GREEN日志，无新增阻断；
复审未重复构建。Windows/部署卷durability、真实多副本部署仍未验证。
保留另一窗口的React、Vue测试辅助和.auto改动；其他窗口的提交可能已包含早期后端改动，不回退历史。

G6 本地实现与定向验证已完成，人工接受待定，见[授权字典上下文](DEV-20261030-schema-context.md)。
2026-10-30 00:57:08 +08:00 隔离 `clean verify` BUILD SUCCESS：37 tests / 0 failures / 0 errors / 0 skipped；
含真实 MySQL/PG HTTP、工具合同一致性、字典撤权/到期与 Unicode 预算。独立只读安全复审无新增阻断，
`python scripts/check.py`及`git diff --check`均退出0。宽表总读取内存、真实客户端与人工接受仍未验证；
G6 为 verifying，不代表 G2～G5 或 G7 完成。

G1 已完成：`McpResourceMetadata` 由四处部署配置（`resource-uri`、`resource`、`issuer`、
`resource-metadata-uri`）生成 RFC 9728 文档，只允许 HTTPS 或回环 HTTP，拒绝 userinfo/query/fragment/
控制字符/错误端口/跨源 metadata URL；`/.well-known/oauth-protected-resource` 与 401 challenge
引用同一文档，未配置时发现端点 404 以保留仅 PAT 部署。无 DCR 时预注册路径写入认证文档。

实际执行（系统 JDK21，2026-10-28）：

- `bash ./mvnw -q -pl ptmcp-gateway -am -Dspotless.check.skip=false test` 退出 0；
  含 `McpResourceMetadataTest`、`McpResourceMetadataHttpTest`（真实 HTTP 发送伪造
  `X-Forwarded-Host`/`Forwarded`，响应仍只含受信配置）及模块内既有测试。
- `python scripts/check.py` 退出 0：60 项检查器回归 PASS，文档与链接检查 PASS。
- `git diff --check` 退出 0。

未验证：目标真实 MCP 客户端/授权服务器的发现、PKCE、refresh、撤销联调；公网证书与域名；
多副本部署。G1 完成仅表示服务端发现端点与预注册文档闭环，不等于真实客户端验收。

前轮 746 项属于历史基线，不覆盖本轮代码。
安全关键变更的独立评审、真实客户端版本与环境、生产证书/域名、故障恢复及人工接受均待实际证据。
用户已授权本地实施；生产/发布/跨分支或破坏性动作不随本工作项自动授权。

## 交接

2026-10-29 续接核查范围：按用户要求复核全部后端、前端真实接口、OAuth首次连接和页面，
参考本地DBX并为另一窗口React迁移提供框架无关交接；本轮不修改业务或前端源码，不迁移账号/数据，不发布。
当前基准为 `dev` / `6ea9a66ac24d4440dbc83b5362256121685ea154`。
另一窗口的React应用与迁移笔记归其所有，未编辑或纳入本轮变更。

本轮结果见 [能力核查](../../docs/evidence/mcp-capability-audit-20261029.md)。G1发现端点仍为completed，
但开发配置的canonical MCP URL与authorize audience不一致，真实OAuth组合需先修再验；
另确认账号档案不供应宿主账号、access JWT revoke、AS会话撤销/登录保护与Origin/协议版本接线缺口。
这些细化承接T13/T14/T17/T19，不以文档核查改成已实现。
同步了既有提案及后端README、Vue README、页面覆盖矩阵的过时事实；没有翻转ADR或新增平行架构。

本轮实际验证（均先进入项目环境）：

- Vue `pnpm.cmd test:contracts`：exit 0，109/109通过、0失败/0跳过。
- Vue `pnpm.cmd check`：exit 1，lint 1 error+5 warnings，后续format/typecheck/build未执行；未抢改前端文件。
- parent `mvnw.cmd -B -ntp verify` 两次均exit 1：persistence 10 errors、0 failures、0 skipped，Docker环境发现失败；
  common已执行，其余业务模块未运行，不能报为本轮通过。
- 专属开发MySQL/PG/Redis已恢复Healthy并保持运行；Linux Docker用同一客户端证书_ping为OK，
  Windows→WSL mTLS握手重置，未关闭TLS或修改共享网络。业务运行验收保持未验证。
- `python scripts/check.py`：exit 1，文档142份/本地链接635项通过；流程门禁发现另一窗口新增React建立笔记缺Verification节。
  未改其文件或放宽门禁；单独运行质量配置/HEAD提交格式检查及检查器回归，均exit 0，回归62/62通过。
- 定稿后 `python docs/evidence/validate_docs.py`：exit 0，142份Markdown/637项本地链接；`git diff --check`：exit 0。
  文档自审核对了源码/历史测试/本轮失败与proposed状态，不把报告升级为业务完成。
  本轮未做独立安全评审、人工接受、提交、推送或发布。

React交接另确认：权限树为fixture，部分写/预览操作未接；接入页“测试连接”仅定时置success，
未实际请求MCP，真实模式也未隔离该回调。应由前端窗口在接管前限制为明确预览或接入真实验证，不能以UI成功宣称连通。

下一实施焦点为G1后的OAuth资源契约与身份闭环，随后继续G2；
G3～G5及G7完整验收仍待完成，G6局部验证已通过、人工接受待定。
React窗口可直接按报告的真实接口和安全行为迁移，不能继承Vue的测试通过状态。
整体保持 in_progress，不把立项、计划或局部测试通过报告为完整交付。
尚未提交、推送、部署或发布；原有 UI 修改归原工作所有者。
