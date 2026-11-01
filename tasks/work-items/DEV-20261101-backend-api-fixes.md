# DEV-20261101-backend-api-fixes：后端接口评审九项修复

ID: DEV-20261101-backend-api-fixes
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: FAIL
Updated: 2026-11-02

## 目标与边界

用户明确要求修复本会话后端评审发现的九项问题，续接
[网关收尾](DEV-20261028-gateway-completion.md)，不重建产品基线。
范围为 ACL 撤销/未来授权自增权、授权更新原子性、委派列表资源范围、客户端 secret 幂等存储与回放、
管理端与 AS 客户端字段兼容、未接线 private_key_jwt 的失败关闭、客户端分页及网关测试依赖回归。
关联 FR-07/10/16/17、T08/T16/T19、[API](../../docs/backend/api-spec.md)、
[授权](../../docs/backend/authorization.md)、[身份](../../docs/backend/authentication.md)。

不做生产发布、真实凭证轮换、后台账户供应、其余管理写路径整体重构或前端视觉修改。
维持 guard 前零业务连接、平台与用户权限交集、Deny 优先、身份/版本复核、审计持久准入及 UNKNOWN 不重做。
前端正在其他会话修改，用户已允许忽略其变化；不暂存、不回退这些资产。
开工前后端已有 authentication.md、admin/gateway POM、PlatformAdminService 与 PlatformProofRoundTripTest 的修改，
保留既有证明写入器及测试，只对 gateway POM 新增必要的测试依赖隔离。

## 实施与验证

串行实施三个切片：A 为 ACL 四项，B 为客户端与凭证四项，C 为网关依赖。
源码写入由 current-agent 独占；按仓库高风险规则，仅委派只读独立安全评审，不并行实现。
取舍见[边界修复笔记](../../.agents/notes/proposed/bug-fix/2026-11-01-backend-api-boundaries.md)。

接口合同：REST 路径、Cookie/CSRF、精确动作码、请求字段与稳定错误码保持；
授权 PUT 使用同一资源 ID、递增版本并保留创建人，在一笔安全事务中完成校验、写入及事件。
客户端 secret 首次响应仍一次展示，幂等持久体只保存元数据，回放带 TOKEN_ALREADY_CREATED 且无 secret。
客户端列表返回 items/nextCursor，页大小不超过请求值，以唯一稳定键续页而不是 version。
客户端列表字段落盘改为 JSON 数组，读端兼容既有 CSV 及旧管理端换行/空格表示；
先部署兼容 AS 读端，再部署管理写端，旧 AS 不可直接回滚读取新数组。
private_key_jwt 尚无完整密钥/外联接线，创建与更新明确拒绝，保留已登记行的失败关闭和删除恢复路径。

验证使用独立 Linux 源码副本、JDK21/Maven3.9.9、专属 Testcontainers MySQL/PG，
不连接已有元库。Windows Docker/Testcontainers 与 WAL 文件系统存在已复现的环境阻断；
Linux 副本补齐 docs/backend/mcp-tools.json，不能将副本漏文件归为源码缺陷。
每片先跑反例，再跑受影响完整套件；最终执行仓库检查、后端 verify 与适用前端契约核对。
历史客户端幂等响应清理仅在隔离迁移中验证，不在生产执行；备份中曾出现的 secret 仍需运维轮换。

## 结果与证据

修复前：本会话隔离副本九个定向测试全部断言失败，0 执行错误、0 跳过；
分别证明自撤 DENY 实际 200 并增权、未来自授实际 201、400 替换后旧授权 REVOKED、
secret 明文存储/重复回放、范围外授权披露、分页溢出/无续页、scope/URI 读取异常及私钥客户端读为 null。
证据位于本机临时目录 ptmcp-api-review-20261101/review-evidence.json。
既有 Linux 管理端 221、OAuth 58 项通过；网关四个启动/HTTP 测试因 JDBC 自动配置加载失败。
仓库 python scripts/check.py 通过（74 项检查器回归）。以上均为修复前证据，不冒充修复后通过。

正式 RED：BackendApiRegressionTest 8 项全部失败（0 error/skip）；RegisteredClientCompatibilityTest 5 项中3项失败；
SelfEscalationTimeTest 的 META_DENY 到期、未来 USER/ROLE 授予共3项失败（5项、0 error/skip）。
只读比较当前时刻不能用 READ_DENY 隐藏 META 增益，因此到期反例使用 META_DENY，未削弱断言。

切片 A 已实施：ACL 三写同连接围栏/当前 actor 与委派重验、未来边界自增权、保留 ID/创建人、
操作日志/epoch/event 原子及范围列表过滤。隔离副本 a-green.log 的 Maven verify 退出0：
OrgManagementStoreTest 295、SelfEscalationTimeTest 5、AdminApiTest 136、A 专项6，共442项，零失败/错误/跳过。
含强制操作日志 CHECK 失败后授权与 epoch 完整回滚的真实 MySQL 证据。
这只是 A 的组合范围，不代表 B/C 或整个后端绿灯。

切片 B 已实施（2026-11-02）：
- 客户端 secret 幂等回放：IdempotentWrites 新增 replayBody 视图参数，clients.create 首次响应仍明文展示，
  api_idempotency.response_cipher 只持久化 clientSecret=TOKEN_ALREADY_CREATED 的元数据体，
  回放返回同状态码+标记体且不再泄露明文（BackendApiRegressionTest.confidentialCreation 断言存储与回放双无明文）。
- 客户端列表分页：续页键改用唯一稳定行 id（原 version 键全部创建行同值会漏页），
  响应改为 items/nextCursor（ClientPage/ClientPageView，对齐其余管理列表端点形状）。
- 字段落盘改 JSON 数组：redirect_uris/scopes 写入 JSON 数组（URI 内逗号/空格可存活），
  管理端读端兼容旧换行/空格/CSV；AS 读端 OAuthStoreSupport.splitCompat 同样兼容三种旧格式，
  畸形数组值 fail-closed 为空（RegisteredClientCompatibilityTest 5 项）。
- private_key_jwt 明确拒绝：管理端 AUTH_METHODS 词表移除，创建/更新 400；
  AS map() 对含 private_key_jwt 的存量行显式 fail-closed（含带 secret 的行），删除恢复路径不变。

切片 C 已实施（2026-11-02）：gateway 测试对 admin 依赖排除 spring-boot-starter-jdbc，
阻断 JDBC 自动装配拖垮四个启动/HTTP 上下文；PlatformProofRoundTripTest 自建 Hikari 池改用
独立 test scope HikariCP 依赖。网关 122 项全部通过。

全量组合验证（隔离副本 ~/ptmcp-api-ws，增量同步自共享树）：
common 234 + persistence 403 + guard 216 + datasource 58 + admin 232 + authz 64 + gateway 122 = 1329 项，
0 失败/错误/跳过，BUILD SUCCESS；React 契约 168 项通过；eslint/prettier/tsc/vite build 与
python scripts/check.py（74 项）通过。

独立只读安全评审（2026-11-02 回收，后台子代理，glm-5.3，只读命令）：
- 无 High；五项核心语义（secret 回放消毒、畸形 fail-closed、键集分页、pk_jwt 拒绝+存量
  fail-closed、ACL 围栏/重验/自增权）均未发现提权或泄露路径。
- Medium（提交一致性，已修复）：edefd05 漏交 authz 两主文件，HEAD 单独部署会使新建客户端
  在 AS 全部不可用且自带兼容测试必红——已随本批补交（splitCompat + pk_jwt fail-closed）。
- Low（已修复）：legacy 逗号 URI 被 [,\\s]+ 静默拆分——读端 legacy 分支改为仅按历史写入
  格式切分（含换行→换行；含空格→空格；否则 CSV），并补兼容测试。
- Low（运维移交，不在本切片执行）：修复前版本写入 api_idempotency.response_cipher 的
  明文 secret 存量行无清理任务，需一次性清洗 clients.create:% 或轮换相关 secret；
  response_cipher 列为明文 JSON，仅允许存无凭证体（schema 注释建议）。
- Info（移交）：cursor 长度上限 36 与 id 列宽 VARCHAR(100) 不匹配；围栏内权限码与
  HTTP 会话 platformAdmin 真值源不一致（执法更严，方向安全）。
评审未独立复跑测试；运行证据由本会话在隔离副本执行。
产品体验及真实客户端人工接受待用户确认；生产发布不在授权范围内。

## 交接

当前分支 dev；切片 A+B+C 本任务路径已提交（ACL 管理端/持久化六文件+AclMutationGuard、
客户端三文件、authz 两文件、AdminApiTest、三个新回归测试、本工作项），
提交见 git log feat(backend)/test(backend)。未提交保留：gateway/admin POM 与
PlatformAdminService/PlatformProofRoundTripTest/authentication.md 属并行审计修复会话，
其提交需该会话按 hunk 复核后在安全关键授权下集成；本任务对 gateway POM 的测试依赖
隔离改动已在共享树就位，随该会话 POM 一并提交。
下一步：回收独立安全评审结论、按接口合同核对部署顺序（先部署兼容 AS 读端，再部署管理写端），
等待人工接受。已有用户修改保持原状。
