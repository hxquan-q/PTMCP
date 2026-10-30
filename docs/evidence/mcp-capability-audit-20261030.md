# 企业 MCP 问数网关增量核查（2026-10-30）

## 结论与范围

**已有可运行的权限型问数后端、OAuth 授权链和真实服务端登录/同意页面；完整后端与 React 产品验收仍未完成。**
外部 Agent 负责理解问题和生成 SQL，PTMCP 负责身份、平台与用户权限交集、SQL guard、只读执行和审计。
不能把三工具、接口或构建存在当作整套系统已完成。

本报告续接 [2026-10-29 核查](mcp-capability-audit-20261029.md)、
[网关收尾工作项](../../tasks/work-items/DEV-20261028-gateway-completion.md) 和
[既有提案](../../.agents/notes/proposed/architecture/2026-10-28-gateway-capability-hardening.md)。
旧报告保留当时事实；本报告记录后续源码、验证与协作的增量，不建立替代需求或授权基线。
读取时共享分支为 `dev`，HEAD 为 `bd636ef`；后端安全候选和 pi 的 React 修改均在工作树中，
HEAD 本身不能代表全部测试输入。本窗口未提交、推送或部署这些安全候选。

源码核查与报告为自审；各安全切片的独立只读评审及实际测试分别记在工作项。
本次没有真实外部 MCP 客户端、生产数据库、域名/证书或部署验收证据。

## 后端实际进度

| 能力 | 当前实际行为 | 仍需完成 |
| --- | --- | --- |
| MCP 与只读问数 | initialize、ping、tools/list、tools/call 与三工具已接真实元库、权限、快照、guard、执行器和审计；MySQL/PG 是当前引擎边界 | MCP HTTP 的 Origin/协议版本校验未见生产接线；真实客户端组合仍待验 |
| OAuth 资源绑定 | 发现文档、authorize、token、refresh 与 JWT audience 使用 canonical MCP URI；遗漏、重复、错误及存储资源被修改的拒绝已有局部实测 | 客户端需预注册 client_id 和精确 callback；DCR/CIMD 关闭；不承诺任意 Agent 只填 URL 即可连接 |
| OAuth 撤销与会话 | access JWT 撤销按本地验签、客户端归属和 family 绑定处理；AS 有持久会话索引、权威版本复核与 logout，最长 8 小时 | 共享登录失败限流、种子账号保护及完整浏览器组合仍待；不能沿用旧报告“AS 无索引/access 撤销全缺”的结论 |
| 管理授权 | 逐动作入口默认拒绝；平台管理员资格主库重算；部门主体/委派范围按 parent_id 权威链；部门、角色、显式委派写入已有安全事务；角色创建已接 HTTP 幂等 | 其他写路径的事务内 actor/委派重验、整子树、最后有效管理员、间接自扩权和成员/ACL 引用竞争仍待 |
| 当前角色资格片 | create/update/deleteRole 持有 global 锁后，同连接检查 READY、ACTIVE 及有效角色并集中的平台码和精确动作码，再读目标；无 trusted/unchecked 入口 | 只检查当前身份与功能资格，未绑定入场 authVersion/会话，不能识别注销、过期、改密完成或停用后再启用的旧在途请求 |
| 账号供应 | 新建档案与权威 UNBOUND 状态存在；档案不等于可登录的宿主账号 | 可信企业身份开通/同步、ID 对齐与跨库失败恢复仍待；用户 DELETE 未实现 |
| G2 审计恢复 | 已接恢复 worker、唯一目录 owner、关闭段回放、主库退休围栏和有证据的过期 START 收敛，局部工程验证已完成 | Windows/部署卷持久性、真实多副本与运行演练仍未验，不能以 WAL 写成功替代全部恢复验收 |
| G3 数据源 | CRUD、test/sync/job、快照及连接安全策略已有；DatasourceAdminService 的 health 仍固定 UNKNOWN | candidate→probe→CAS publish 与绑定配置版本/目标的健康投影未完成 |
| G4 取消/容量 | 有执行器超时、取消和额度原语；生产装配仍用 InMemoryQuotaLimiter；无 id 通知直接 accepted | 尚无 MCP 请求与主体绑定的取消注册表；不能据 202 认定已停止 SQL；多副本用户/源预算需补 |
| G5 密钥兼容 | 凭据 AES-GCM 与 WAL 加密已有 | CredentialCipher 仍单钥且未见对象 AAD；旧钥兼容、版本化格式和迁移未完成 |
| G6 字典上下文 | 已补工具 description/annotations、授权后业务字典/关系与 UTF-8 文本预算；独立任务 verifying、定向 PASS | 宽表总读取内存、真实客户端与人工接受仍未验；不能继续报告“三工具描述/业务字典全缺” |
| G7 组合验收 | 局部真实 HTTP、MySQL/PG 与故障回归已有 | 全系统适用 Maven/前端门禁、真实客户端、浏览器、恢复与部署证据仍需收敛，整体保持 in_progress |

上述状态入口为工作项与实际源码：
[OrgStore](../../ptmcp-parent/ptmcp-persistence/src/main/java/local/ptmcp/persistence/org/OrgStore.java)、
[McpToolService](../../ptmcp-parent/ptmcp-gateway/src/main/java/local/ptmcp/gateway/mcp/McpToolService.java)、
[DatasourceAdminService](../../ptmcp-parent/ptmcp-admin/src/main/java/local/ptmcp/admin/datasource/DatasourceAdminService.java)、
[McpGatewayConfiguration](../../ptmcp-parent/ptmcp-gateway/src/main/java/local/ptmcp/gateway/mcp/McpGatewayConfiguration.java)、
[CredentialCipher](../../ptmcp-parent/ptmcp-datasource/src/main/java/local/ptmcp/datasource/connection/CredentialCipher.java) 与
[G6 工作项](../../tasks/work-items/DEV-20261030-schema-context.md)。

## 首次 MCP 连接与 OAuth 页面

当前服务端流程是：

1. 外部客户端无有效 Bearer 调用 `/mcp`，网关返回 `401` 和指向资源元数据的 `WWW-Authenticate`。
2. 支持 OAuth 的客户端读取 Protected Resource Metadata 和 AS metadata，准备预注册 client_id、
   精确 redirect_uri、PKCE S256、state 和 canonical resource，再打开系统浏览器访问 `/oauth2/authorize`。
3. 浏览器无有效 AS 会话时，授权服务器才 `302 /login`。已有 AS 会话可能不再要求输入密码；
   是否显示同意页取决于客户端 requireAuthorizationConsent 和已有同意状态。
4. 登录/允许后向精确 callback 返回 code；客户端带 verifier/resource 换 token，再用 Bearer 调用 MCP。
   已有有效 PAT 或 access token 的连接不需要这次登录。

因此“首次是否跳转”的准确答案是：**支持该 OAuth 流程、已有预注册配置且无有效凭证时，会进入浏览器登录；
`/mcp` RPC 本身返回授权 challenge，不直接 302 页面。** 客户端不支持 OAuth、缺少 client_id 或服务器未启用 OAuth 时，
不能承诺自动跳转。该职责划分依据项目声明的
[MCP 2025-06-18 授权规范](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization)，
没有验证其他协议版本兼容性。

真实页面由 [OAuthPageController](../../ptmcp-parent/ptmcp-authz/src/main/java/local/ptmcp/authz/oauth/OAuthPageController.java)
生成服务端 HTML：`/login` 与 `/oauth2/consent` 均存在，包含 CSRF、转义、CSP 与 no-store。
它们使用独立 `PTMCP_AS_SESSION`，由
[OAuthSecurityConfiguration](../../ptmcp-parent/ptmcp-authz/src/main/java/local/ptmcp/authz/oauth/OAuthSecurityConfiguration.java) 和
[AsSessionSecurity](../../ptmcp-parent/ptmcp-authz/src/main/java/local/ptmcp/authz/oauth/AsSessionSecurity.java)
装配，与 React 控制台会话隔离。React 迁移无需重写令牌签发或把密码/授权码经过管理 API。

同意页已有客户端名、scope、允许/拒绝，但平台、精确回调与有效期/授权族说明尚不完整。
补页时应从经过校验且绑定当前授权流程的服务端上下文取值，不把任意查询参数直接当作授权事实。

## React 接口与 pi 协作

已定位到用户指定的 pi agent 会话并投递问题；本窗口只读 React，后端/契约与 React 文件所有权已明确。
Vue 已由另一会话按用户选择移除，当前入口为 `ptmcp-ui-react/`，不能继承 Vue 历史测试结果。

[http.ts](../../ptmcp-ui-react/src/services/http.ts) 正常模式实际 fetch `/backend/admin/api/v1/...`，
带 same-origin Cookie、CSRF、no-store 和长整数保护；仅 `DEV && MODE=frontend-preview` 使用合成服务，
没有自动网络重试。[vite.config.ts](../../ptmcp-ui-react/vite.config.ts) 的 admin/authz/gateway 代理分别指向 8080/8081/8082。
数据源、元数据、组织/角色/用户、ACL/委派、平台/client、审计、个人字典/PAT/授权/历史均有服务层调用。
**这些证明存在接口调用，不证明请求契约完整或各页面已在真实浏览器完成读写和异常验收。**

角色页面协作已核实的修正：创建时执行 key 校验，规则对齐后端小写开头 1～100 字符；
创建意图复制并冻结 permissionCodes 数组；源码已按 uncertain 分类保留网络/响应失败、OPERATION_IN_PROGRESS、
OPERATION_UNKNOWN 的原 key/body，未知错误码优先于 HTTP 状态分类，VERSION_CONFLICT 识别为冲突，明确编码重复才提示重复。
编辑结果未知时，提交入口和按钮均禁止原地重提，文案引导关闭并刷新核对；修后测试结果见下文。
不能把全部 409 当作角色编码重复，也不能在结局未知时自动换键重建。

[IntegrationsPage](../../ptmcp-ui-react/src/views/me/IntegrationsPage.tsx) 已把演示测试限定在明确 preview 模式，
真实模式禁用按钮并说明须由客户端握手；演示结果标为 demo-ok，明确不代表真实 MCP 连通。
本轮定位的其他真实模式接线缺口已由续接窗口修正，以下为本窗口只读复核到的源码状态；
最终 test/check 结果另列，真实浏览器联调仍待，不以投递回执代替完成：

| 页面 | 本轮发现与后端依据 | 当前源码修正及边界 |
| --- | --- | --- |
| MCP 接入列表/向导 | 原先硬编码“已生效”清单，按 tier 猜平台并回退 id=1；模板失败仍可复制示例 | 样例只在明确 preview；正常模式无列表后端接口，展示空态。向导加载真实平台目录，由用户选择 platformId；无目录不请求，失败禁复制/完成；尚未实现接入记录持久管理 |
| MCP 连接选项 | 后端实际返回 streamable-http、oauth/pat，兼容状态为 UNVERIFIED；原 UI 额外呈现无认证、API Key、stdio | 已收窄为当前选项；认证元数据缺失/空时拒绝推进，step2 校验服务端能力，识别 UNVERIFIED。模板生成不等于客户端握手或 OAuth client 注册 |
| 用户删除 | UserAdminController 没有 DELETE 路由，权限码存在不足以证明能力 | 正常模式禁用并提示使用停用；演示删除仅 preview，后端 DELETE 仍未实现 |
| OAuth 客户端新增/编辑 | 原先仅发 name/type/authMethods，缺必需 status/allowedScopes，未提供 redirectUris/jwksUri | 已发送完整 ClientWrite 并保留编辑值；提供精确回调、scope、JWKS 和状态，校验 PUBLIC 仅 none、CONFIDENTIAL 禁 none、纯 private_key_jwt 必需 HTTPS JWKS；真实注册→授权组合仍待验 |
| 有效权限预览 | 原先发送 subjectType/subjectId 和原始对象 ID；后端只收 subjectUserId、编码资源 ID 且上限100 | 已只对 USER 开放计算，发送 subjectUserId，用 BigInt 编码并解码结果、归一化 sourceGrantIds；最多取前100个并明确提示，非法 ID 阻断；角色/部门主体预览仍未实现 |

依据为 [UsersPage](../../ptmcp-ui-react/src/views/admin/UsersPage.tsx)、
[PlatformsPage](../../ptmcp-ui-react/src/views/admin/PlatformsPage.tsx)、
[ClientWrite 服务类型](../../ptmcp-ui-react/src/services/access.ts)、
[UserAdminController](../../ptmcp-parent/ptmcp-admin/src/main/java/local/ptmcp/admin/api/UserAdminController.java) 和
[ClientAdminService](../../ptmcp-parent/ptmcp-admin/src/main/java/local/ptmcp/admin/platform/ClientAdminService.java)。
这些是源码复核，未将接口拒绝描述成新运行的 HTTP 用例。新增客户端与 ACL 适配测试已捕获 fetch 正文、
校验必需字段及编码；MCP 缺元数据和角色未知结果也有反例。React 最终 test/check 已实际通过，逐页真实联调仍未验。
原 pi pane 已关闭，已向同仓库续接 React 迁移的窗口重发这些发现、文件所有权和验证要求。
迁移门禁还发现 CI、scripts/check.py --all、Linux 开发入口和种子扫描引用已删除的 ptmcp-ui；
续接窗口已同步实际 React 路径、test 命令和检查器回归；本窗口再次扫描 .github/scripts/config，
未发现旧 ptmcp-ui 目录或 test:contracts 命令残留。该静态扫描不替代 CI 平台实际运行。

前端所有者确认源码冻结后，本窗口于 10:45:26～10:45:59 +08 独立运行
`pnpm.cmd --dir ptmcp-ui-react test` 与 `pnpm.cmd --dir ptmcp-ui-react check`，原始退出码均为 0。
151 项测试全部通过，0 失败/取消/跳过；ESLint、Prettier、TypeScript 与 Vite 生产构建全部通过。
环境为 Node v24.22.1 / pnpm 11.9.0；104 份前端输入运行前后 SHA-256 一致。
原始日志、退出码、时间和哈希保存在专属 WSL 证据目录的 `gateway-audit-react-final-*`。
测试包含 DTO/fetch 正文与合成 preview 反例，不代表每页真实后端联调、浏览器体验或 MCP/OAuth 客户端握手完成。
该窗口的 36 份自有文件已精确暂存，后端与本报告未纳入；本窗口没有操作其暂存区。

## 剩余工作如何落地

| 顺序 | 实现切片 | 必须保留的验收反例 |
| --- | --- | --- |
| 1 | 给安全写命令传入可校验的 actor 会话/版本证明；在同一安全事务内重验 actor、当前角色和完整单条委派；补整子树、最后管理员和间接自扩权 | 入口通过后撤权/注销/改密，等待锁的旧请求不得写入；不得拼接多委派；通过 ROLE/DEPT 或删除 DENY 给自己增权仍拒绝 |
| 2 | 完成可信 UNBOUND 身份供应、宿主账号与元库 ID 对齐、失败补偿；接共享登录限流和显式种子退出策略 | 新建档案不得被当作可登录账号；供应失败不自动 READY；多副本绕过单机限流的反例 |
| 3 | G3：新增候选配置状态、探测记录与 active 的 CAS 发布；健康携带目标/配置版本/检测时间 | 错候选不影响 active；旧任务不能覆盖新健康；并发发布冲突；目标切换不复活旧授权 |
| 4 | G4：以用户/平台/请求 owner 注册取消句柄，执行真正终结或连接淘汰后归还额度；落实共享预算或明确单副本契约 | 同 RPC id 跨用户不能互取消；排队、执行、断连、迟到取消；取消响应不能提前释放数据库额度 |
| 5 | G5：版本化密文封套、keyring 和对象 AAD，保留旧钥读取及可恢复迁移 | 新旧密文可读；密文跨对象替换拒绝；轮换后旧 WAL 仍能回放；迁移中断不丢凭据 |
| 6 | 补 MCP Origin/版本入口和 AS 页说明；G7 做真实客户端 OAuth/PAT、双引擎、浏览器、恢复组合 | 无 token 的 401 发现链、错误资源/回调/PKCE、拒绝授权、刷新/撤销、恶意 Origin 与不支持版本均有实际证据 |

沿既有工作项逐片 RED→实现→GREEN→独立评审推进，不扩驱动、写 SQL、事务/插件或开放 DCR/CIMD。
DBX 可继续参考入口防护、工具描述、上下文预算与取消接线；它的共享 bearer 和分层覆盖语义
不能替代 PTMCP 员工身份、平台∩用户权限或执行时鉴权。

## 本片验证与限制

角色资格片有效 RED 为 store 69 项/66 failures 与 HTTP 2 项/2 failures，均 0 errors/skipped、exit 1。
定向 GREEN 为 69+2 共 71 项全过、exit 0。第一轮七类兼容组合为 504 项/1 failure、exit 1：
新增测试管理员部门污染旧部门列表首项，生产资格校验没有因此放宽。
已把该专用身份的夹具部门设为软删，保留原部门 CRUD 断言；平台角色管理资格按既有合同不依赖业务部门授权。
修正后的最终七类兼容组合为504项零失败/错误/跳过，`role-qualification-combined2-green.log/.exit`
于10:04:51 +08记录BUILD SUCCESS/exit0；7份XML头计数与实际节点一致。
358份运行前/最终native/Windows输入SHA-256一致，未回拷或覆盖其他窗口文件。
独立只读终审已亲核上述日志/退出码、71项定向及7份最终组合XML实际节点，逐项重算358份三方哈希，差异0。
评审确认夹具修正没有改变原CRUD断言或掩盖本片产品回归，无本片新增阻断；未重复动态测试或连接数据库。

HTTP 新增两项使用 standalone MockMvc 与真实隔离 MySQL；未新增 Cookie/CSRF 链。
反向锁竞争采用闸门及有界等待，没有直接观察数据库锁等待。
隔离构建输入通过逐文件 SHA-256 与共享 Windows 工作树核对，不以共享 HEAD 替代运行源码。
日志、退出码与 XML 位于专属 WSL `/tmp/ptmcp-root-backend-workspace/`；没有连接企业业务数据库。
所有历史验证的命令、范围和局限见关联工作项，数量不跨轮累计。

第二轮完整 parent verify 于 10:38:09～10:53:31 +08 运行，exit 1：1258 项有 32 failures、
4 errors、0 skipped。79 份本轮 XML 的头计数与实际节点一致，358 份 Windows/native 输入前后哈希一致。
common/persistence/guard/datasource/admin/authz 六模块均通过；gateway 的 96 项失败集中四个旧 HTTP 测试类。
已确认它们在全量迁移后只创建档案/凭证，没有权威身份行，入口按合同返回 401 TOKEN_REVOKED。
独立只读复核支持仅补隔离夹具的显式 READY/版本 1，并补 PAT/真实签名 JWT 的缺行、UNBOUND、
版本不符拒绝且零业务 checkout/执行反例；生产鉴权不因此放宽。修后验证尚待记录。
原始日志、退出码、79 份 XML 与哈希为 `gateway-audit-parent2-verify*`，本轮失败保留。

本轮另在隔离临时副本运行 `python -B scripts/check_backend_quality.py --timeout 900
--seed-repository D:/config/ptmcp/cache/maven`，10:49:12～10:51:12 +08、exit 0。
7 个实际 Maven 正反探针通过：父/子模块 validate、正确格式控制、main/test 坏格式拒绝、
已格式化的星号导入控制及拒绝、common 引入 Spring 拒绝。负例同时核退出码和对应门禁诊断；
没有运行格式修复或连接数据库，不包含 parser 唯一性/Sa-Token 负例。
原始日志/退出码/时间为 `gateway-audit-quality-probes*`。

Skill 的 tree/format/archived 三脚本本轮均 exit 0，核验 13/13/0 篇笔记、0 项归档封印，
原始日志/退出码为 `gateway-audit-final-verify-*`；上一轮 12 篇笔记的结果仅为历史证据。
本轮分项运行后端、前端与质量探针，没有 Windows `scripts/check.py --all` 聚合通过记录。
