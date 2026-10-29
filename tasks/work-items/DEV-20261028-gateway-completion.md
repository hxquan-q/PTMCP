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

身份组合 GREEN：`ptmcp-identity-combined-green.log` BUILD SUCCESS，78 tests / 0 failures/errors/skipped；
覆盖双 MySQL 改密恢复13、真实 ADMIN 改密HTTP2、Session8、Org13、SecurityState13、OAuth27及MCP HTTP2。
Origin 有效 RED 为 `ptmcp-identity-origin-red.log`（8例中1 failure，跨站Origin携合法CSRF错误返回200）；
profile 围栏有效 RED 为 `ptmcp-identity-profile-red-real.log`（2 tests / 2 failures / 0 errors），
此前 fixture 缺登录名的失败不计行为 RED。GREEN 包含非法 Origin/Referer、same-site 拒绝与合法同源成功。
独立只读复审关闭 Pending 资料漂移与恢复过期版本，但发现新建 READY 只按 ID 匹配宿主的账号碰撞风险。
当前续接最小安全修复：前向迁移引入 UNBOUND，可靠供应完成前不签发；所有登录/改密/恢复核 ID+元库规范化登录名，
宿主事务先锁行核绑定再 CAS。新建宿主账号供应仍未完成，不能把本片 GREEN 计作身份全闭环。

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
