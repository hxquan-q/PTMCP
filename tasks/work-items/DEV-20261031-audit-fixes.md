# DEV-20261031-audit-fixes：核查确认缺陷分片修复

ID: DEV-20261031-audit-fixes
Status: verifying
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-11-02

## 目标与边界

来源：用户要求核实审计结论后明确要求“请你着手修复”。承接 [网关收尾](DEV-20261028-gateway-completion.md) G7 / T17；本项只跟踪本次审计修复，不建立第二份产品基线。

开工为干净 `dev` / `5047728`，单 Agent 实现；此前工作项的并发 UI 所有权为历史记录，本次未发现未提交修改。保留 guard 前零业务连接、平台与用户交集、Deny 优先、敏感凭证只写不回显和 ADR0011 的 UNBOUND/READY 边界。

当前切片：
- Given 现行 MySQL/PG 与平台 Write 合同，When 打开表单/筛选并提交，Then 只提供实际支持的枚举，Tier1 必须证明，证明配置按模式完整输入或同模式保留。
- Given REST UTC 时间，When 显示列表/详情，Then 显式转换为 Asia/Taipei，不依赖浏览器默认时区。
- 不做数据库扩展、SQL 放宽、URL PAT、自动 schema 调度、二期空表或生产操作。旧 REQ 与现行 ADR/PRD/backend 冲突的行为不作为修复目标。

后续独立切片（本轮尚未实施）：REST ID 字符串及精度边界；真实上游 R<T> 和幂等响应兼容；可信账号供应（不自动接管同名/同 ID 宿主）；平台 clients 显式 DTO；共享 metadata 契约与 JDBC helper/仓储/引擎分派重构。UNION、告警及授权错误详情仅按现行权威契约另行定界，不删除 fail-closed 保护。

## 实施与验证

首片改 React 调用方/表单/展示、测试和本工作项。2026-10-31 独立评审确认首片新增的轮换失败重试缺陷（Medium），以及既有管理端证明前缀字符串与网关 JSON 消费合同不一致（失败关闭，并非已证实越权）。原 163 项通过不覆盖这两条路径，Verification 改为 FAIL，修复及重验前不作完整交付。

续接切片按顺序：
1. 保留请求后材料清理，用非敏感状态保留替换意图；明确失败后必须重填或显式取消替换，结果未知/版本冲突须重新读取核对。增加实际组件失败重试浏览器回归及状态边界测试。
2. 管理端按现有 authentication JSON 合同写入：MTLS 指纹去冒号并小写（仅直连证书，不隐式信任转发）；代理 CIDR 经 CidrRange 校验，secret 原文 UTF-8 编码再 Base64（1～256 字节，不 trim 原文）。真实管理服务写入→持久行→网关验证，新证明通过/旧证明拒绝，另测非法材料与不可信来源。保持主库读取、CSRF、If-Match、幂等和只写边界；不降 Tier、不关闭证明。旧 JSON 继续消费；旧前缀格式不增加网关宽松回退，须管理员重配，回滚旧管理端会重新引入不兼容写入，部署前必须联动验证。没有 schema 变更或生产数据迁移。

权威合同：[API](../../docs/backend/api-spec.md) PlatformWrite / DatasourceWrite、[代码规范](../../docs/standards/code-style.md) 时间规则、[页面规格](../../docs/frontend/page-spec.md) P-02/P-09。

接口合同：不改变 REST 路径、HTTP 状态、裸 DTO 现状或服务器授权；平台 POST/PUT 保留现有 CSRF、Idempotency-Key / If-Match。输入 catalogMode=DISCOVER/HIDE，proofMode=NONE/MTLS/PROXY_SECRET；proofConfig 写入专用，MTLS 为 certificateFingerprint，代理为 proxyNetwork+secret。同模式已配置时留空保留；切换模式须新材料；NONE 不发送旧材料；Tier1 不得 NONE。关闭弹窗清除表单内存，禁止日志/持久存储凭证。创建/编辑同步部署前端即可，回滚前端不改变已存数据，但会恢复旧表单缺陷。

计划按 RED → GREEN：时间跨日/明确偏移/缺失时区；引擎白名单与 ACTIVE 筛选；平台模式/证明保留与替换/不回显；pnpm test + check、根 python scripts/check.py、git diff --check。证明配置属于认证调用边界，安排独立只读安全评审。浏览器与真实后端连通验收未运行则明确保留。

## 结果与证据

2026-10-31，本轮前端切片的自动化检查 PASS；本字段不代表整份审计、真实后端联调或人工验收通过。

实现：
- `platformForm.ts` 统一平台初始化、枚举校验与 Write 转换，页面补齐 MTLS 指纹和代理网络/密钥输入；证明材料不从读模型回填，模式切换、请求结束及关闭表单清空局部材料。Tier1/NONE 拒绝，同模式已配置时全空保留，切换模式必须新材料；NONE 不转发陈旧输入。
- `dsForm.ts` 为列表/创建/编辑提供 MySQL、PostgreSQL 与 ACTIVE/DRAFT/DISABLED 白名单；启用筛选使用 ACTIVE。读模型仍可展示未知引擎，但不提供未知引擎写入选项。
- `dateTime.ts` 显式使用 Asia/Taipei，接入审计、详情、委派、数据源和个人页的 instant 展示；缺少时区不猜本地时间。只处理显示，尚未覆盖 datetime-local 输入、查询日期范围或到期日期的整体时区语义。
- 合成预览独立校验平台 Write，行/更新操作日志剔除 proofConfig、credential 等只写输入；数据源种子收敛双引擎、VERIFY_FULL、poolMax 1～8。依赖旧错误枚举/四引擎/部分 PUT 的测试夹具同步为现行合同，原功能断言保留。

RED → GREEN：
- 先取得 UTC 直接截取、引擎/状态白名单、平台旧枚举与证明边界的失败回归；补充预览拒绝/证明保留替换/材料不回显及数据源 fixture 合同测试。新增 fixture 测试先暴露 poolMax 超限及非 VERIFY_FULL；收敛种子后通过。
- 原生 Node strip-types 的 extensionless 导入失败采用现有测试约定的临时 resolve hook 修复；全量旧夹具不合法则修夹具，不放宽合同。一次字符串断言误将合法 proofConfigured 当 proofConfig，改为精确 JSON 键并保留材料值检查。

实际检查（项目环境 Java 21 / Maven 3.9.9 / Node 24.22.1 / pnpm 11.9.0）：
- `powershell.exe -NoProfile -Command '. ./scripts/Enter-Dev.ps1; Set-Location ptmcp-ui-react; pnpm test; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }; pnpm check; exit $LASTEXITCODE'`：退出 0，163 tests / 163 pass / 0 fail；lint、Prettier、TypeScript 与生产构建通过。完整本机临时日志 `/tmp/ptmcp-audit-frontend-check.log`，不进入仓库。
- `python scripts/check.py`：退出 0，文档/工作流/质量配置/提交检查通过，74 项脚本测试通过。完整本机临时日志 `/tmp/ptmcp-audit-repo-check.log`。未使用 --all，未运行本轮 Maven verify。
- `git diff --check`：退出 0；`python scripts/change_scope.py` 核对 18 个 tracked 修改与 5 个新增路径，无 staged/committed 变更。范围报告不等于所有权或语义评审。

浏览器（隔离合成预览 `http://127.0.0.1:25176`，不是实际后端）：
- 实际完成 Tier1/NONE 拒绝、MTLS 新建、同模式空材料编辑保存、切 PROXY_SECRET 缺材料拒绝、完整材料替换；编辑不回填，代理字段 type=password，模式往返和取消重开后材料为空。对 localStorage/sessionStorage 的合成材料检查均为未存储。
- 数据源筛选/新建只列 MySQL 与 PostgreSQL；选择 ACTIVE 后显示 4 条启用种子；固定 UTC `2026-10-20T09:12:00Z` 在详情显示 `2026-10-20 17:12`。
- 控制台唯一 error 为 favicon.ico 404；无已观察到的应用脚本错误。没有为此扩展修复范围。

评审与限制：
- 已自审数据源/时间改动；首轮独立只读安全评审已回收，确认两项缺陷，见上方续接切片。修复后同一独立安全评审已完成：指定范围未发现新增、可确认的 High / Medium 阻断，原失败重试问题已修复；评审为只读源码/diff 核验，没有独立复跑作者的测试。不将自审或作者测试冒充独立运行验收。
- 对应 TC-UI-005 的时间展示与 TC-AUT-009 的证明配置调用方边界；没有执行这些稳定主用例的完整端到端步骤，也不声称真实 Tier1/MCP 证明校验、OAuth、双库或人工体验验收已通过。
- 上述 163 项与根检查为首轮历史证据，当时后端未改；续接已修改后端 writer 并补真实 MySQL 回归，详见下节。ID/R<T>/账号供应等后续切片尚未实施。现有后端历史失败保留在父工作项，不挪作本轮检查结果。

## 续接实现（2026-10-31）

- `PlatformDialog` 清空材料后保留 `replacementRequired`，明确拒绝后须重填或显式取消；网络/无效响应和 412 设置重新读取门禁。关闭后清空平台旧行并 reload，loading 时不可重开旧行。材料仍在 finally/模式切换/卸载时清理。
- `PlatformAdminService` 改为加密网关现有 JSON：显式私有 policy record；MTLS 去冒号、小写且 proxyCidrs 为空；代理 CIDR 复用 `CidrRange.parse`，secret 保留 UTF-8 原文字节后 Base64，限制 1～256 字节。部分材料（含仅网络、空白密钥）和模式错配拒绝；同模式全空保留，NONE 清除规则不变。没有修改网关 verifier 或 schema，没有生产数据操作。
- 新 `PlatformProofRoundTripTest` 使用真实管理服务、完整元库迁移/MySQL Testcontainer、两端真实部署加密与网关 verifier；gateway 的 admin 依赖仅 test scope，使用 admin POM 在 process-test-classes 附加的 plain 主类 JAR，避免 Spring Boot repackage 主 JAR 在 verify 阶段不兼容；原可执行 JAR 不变。覆盖证明轮换、来源、指纹 Header 不可信、UTF-8 256 字节/超限、IPv6、模式错配/部分材料、NONE 清理、读模型/操作日志不含材料及错误 prefix 失败关闭与重配。Servlet 证书 DER 为合成 mock，不是 TLS 握手/证书链端到端验证。
- 更新 authentication/API 合同，说明 MTLS 表单 direct-only、Base64 Header、旧错误 prefix 重配和旧 writer 回滚限制；不以降 Tier/NONE 绕过。

## 结果与证据

- 后端 RED：Windows 编译到 Testcontainers 后被 Docker TLS `Connection reset` 阻断，不能算行为复现；改用项目已有 WSL Unix socket，独立快照 `/var/tmp/ptmcp-proof-20261031.stJyHy`。首次被并行测试文件中间态格式阻断，未跳过门禁；在快照修复格式后，4 tests / 3 failures / 1 error，真实复现 writer 前缀不能被 JSON consumer 读取、非法 CIDR 被接受。日志 `/tmp/ptmcp-proof-wsl-red.log`。
- 后端 GREEN：同快照 `mvn -o -B -ntp -Dmaven.repo.local=/mnt/d/config/ptmcp/cache/maven -pl ptmcp-gateway -am -Dtest=PlatformProofRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false test`，退出 0，7 tests / 0 failures / 0 errors；Spotless/Checkstyle/Enforcer 同时执行，日志 `/tmp/ptmcp-proof-wsl-green.log`。不代表全 reactor 测试通过。
- 前端较早整树重验（历史结果）：进入项目环境后对本片文件 Prettier，再 `pnpm test` 与 `pnpm check`，退出 0，164 tests / 164 pass / 0 fail；ESLint、Prettier、TypeScript、生产构建通过，日志 `/tmp/ptmcp-proof-frontend-final.log`。此前 PermissionsPage 并行编辑态 unused 阻断已在重跑消失，未修改该文件以消错。后续共享树再次变化，不能用本历史结果替代下述最新整树结果。
- 浏览器：Prettier 后通过 `tests/browser/platform-proof-retry.mjs` 运行七场景，PASS：明确拒绝重填、显式取消、未知结果 fresh-read、代理重试、412、无效响应、已提交后响应丢失。最后一项先让合成服务实际写入并提升版本，再丢弃响应；断言禁止盲重试、关闭重开读到已提交版本、下一次显式保存的 If-Match 使用新版本且不回填材料。真实挂载 React，transport 使用合成 preview；独立 browser context 在 finally 关闭。不是 Java REST/MCP/TLS 全端到端证据。
- 根检查：首跑 workflow 被并行 `DEV-20261031-datasource-discovery.md` 中间态缺章节/Verification 枚举阻断，已通知所属会话，不改其任务；对方完成后 `python scripts/check.py` 重跑退出 0，日志 `/tmp/ptmcp-proof-repo-check-final.log`。`git diff --check` PASS；change_scope 退出 0，报告 `/tmp/ptmcp-proof-scope.log`，范围不等于所有权。
- 追加 verify：初次两个 AdminApiTest 通过，但 gateway testCompile 在 admin Boot repackage 后找不到普通类（本片新测试依赖的构建缺陷，日志 `/tmp/ptmcp-proof-wsl-verify.log`）。附加 plain 类包并改测试 classifier 后，同快照再次 `-pl ptmcp-gateway -am '-Dtest=AdminApiTest#platformCrudSealsProofConfigAndKeepsItUnreadable+platformScopeFullSetReplacementIsOptimisticAndLogged,McpTier1ProofTest,PlatformProofRoundTripTest' -Dsurefire.failIfNoSpecifiedTests=false verify` 退出 0，合计 11 tests / 0 failures / 0 errors（admin 2、round-trip 7、Tier1 HTTP 2），日志 `/tmp/ptmcp-proof-wsl-verify-classifier.log`。Spotless/Checkstyle/Enforcer、jar/Boot repackage 均通过；`jar tf` 核实 plain 根目录类与原 BOOT-INF 可执行类包同时保留。
- 新 round-trip 的 MTLS 是 Servlet 证书合成边界；既有 Tier1 HTTP 测试直接 seed 有效 JSON，覆盖受信代理指纹/secret 和缺失/错误/来源/Tier，不覆盖管理 HTTP 写入到真实 TLS 握手的一体化流程。两组证据互补，不能宣称全端到端/完整测试主用例已验收。
- 集成追加 verify：基线 `4132a00` 加本片四个后端文件，在 `/var/tmp/ptmcp-proof-integrated-20261031.RZZ9U4` 独立运行上述 11 项再加 `McpDatasourceDiscoveryTest`（3）和 `SnapshotPublicationMigrationTest`（1），退出 0，15 tests / 0 failures / 0 errors / 0 skipped，所有 reactor 模块 SUCCESS；Spotless/Checkstyle/Enforcer 与 Boot 打包通过，日志 `/tmp/ptmcp-proof-integrated-verify.log`。`git diff --name-only 4132a00 b4f0acc -- ptmcp-parent config/quality` 为空，后续提交未改变此后端验证范围。不是全后端测试。
- 制品布局：plain JAR 保留根目录主类，默认 admin JAR 保留 BOOT-INF 可执行布局；实际检查 gateway 可执行归档不含 ptmcp-admin runtime 依赖。plain 是附加可发布制品，其“仅测试”是 gateway 依赖的 test scope；部署仍选择原无 classifier 的可执行 admin JAR。
- 独立复审：原 `security-expert` 只读核验前端恢复状态、writer/consumer 合同、7 项 Java 测试结构及两个 POM，指定范围无新增可确认 High / Medium 阻断，原 Medium 已修。运行证据由本窗口执行，评审未独立复跑。按评审指出的合成提交后丢响应覆盖缺口追加第七条浏览器回归并 PASS；未改变生产代码。
- 最新共享前端：`pnpm test` 164/164，通过；紧随其后的 `pnpm check` 在 ESLint 阶段失败：`UsersPage.tsx:9:3` 未使用 `RequestIdText` 导入，日志 `/tmp/ptmcp-proof-frontend-integrated.log`。该文件正被其他窗口编辑，没有代改、删导入或放宽检查；此后未再次对共享树宣称通过。
- 隔离前端：基线 `b4f0acc` 加本片 22 路径及同基线 `config/quality`，快照 `C:/Users/xquan/AppData/Local/Temp/ptmcp-proof-ui-final-20261031.iS8rBo`，不包含其他窗口未提交的公共组件、样式和 Users/Departments/Roles 修改。复用已安装依赖的 Windows Junction，不修改锁文件或在线下载。`pnpm test` 164/164、0 fail/skip；补齐共享配置后 `pnpm check` 退出 0，ESLint、Prettier、TypeScript、Vite 生产构建全部通过。测试日志 `/tmp/ptmcp-proof-ui-isolated-final.log`，最终门禁日志 `/tmp/ptmcp-proof-ui-isolated-check.log`。这是本片隔离证据，不是最新共享树集成通过。
- 隔离环境过程：首个快照离线安装因缺 `react-19.2.0.tgz` 缓存失败（`ERR_PNPM_NO_OFFLINE_TARBALL`，`/tmp/ptmcp-proof-ui-isolated.log`），测试未运行；复制依赖目录因 Windows 符号链接失败，后续测试亦未运行。改为新快照和 Junction 后测试通过，首次格式检查因快照漏带共享 `config/quality` 失败；补入同基线配置再跑门禁通过。没有将这些环境失败记为源码行为 RED，也未跳过门禁。
- 收尾核对：本片 22 个前端路径按文件内容 SHA-256 与已验证快照一致；与快照不同的共享前端仅 `components/common.tsx`、`styles/index.css`、Users/Departments/Roles，均不归本片。4 个后端文件（admin POM/writer、gateway POM/round-trip 测试）逐个与已通过 15 项集成验证的 WSL 快照 `cmp` 相同。最新 `python scripts/check.py` 退出 0（文档/工作流/质量配置/提交消息检查及 74 项脚本回归），日志 `/tmp/ptmcp-proof-repo-closeout.log`；不是 `--all` 或整树框架构建。`git diff --check` 通过，仅提示 authentication 文件下次 Git 操作将 CRLF 规范化为 LF；`change_scope.py` 退出 0，报告 `/tmp/ptmcp-proof-scope-closeout.log`，staged 为空。没有暂存、提交或推送。

## 交接

当前焦点：本片隔离前端 164 项测试及全部前端质量门禁、后端集成选跑 15 项、浏览器 7 场景通过；独立静态安全复审无新增可确认 High / Medium 阻断。当前 HEAD `b4f0acc` 的共享前端最新重跑仍 164/164，但 `pnpm check` 被另一窗口编辑中的 `UsersPage.tsx:9` 未使用 `RequestIdText` 导入阻断（`/tmp/ptmcp-proof-frontend-integrated.log`）；没有修改该文件消错。保留 Verification=FAIL 表示当前共享工作树尚未完成集成门禁，不能拿历史或隔离 GREEN 标整棵树通过。根轻量门禁、差异空白与范围检查已通过，见上述收尾证据。下一步待共享 UI 修改稳定后完成集成重验，并在安全关键提交/推送的单独授权下按 hunk 复核归属后集成；不直接整文件暂存共享路径。人工接受保持 pending。

并行协作：其他会话提交 `c869979`、`55b420d`、`4132a00`、`b0327fd`、`b4f0acc` 已进入 dev；其中本窗口新增 PlatformWrite API 段落随共享 `api-spec.md` 被 `4132a00` 一并提交，对方已在 `b0327fd` 增量记录段落归属及其验证不构成本安全片验收，未回退/改写历史；不将其视为本片提交授权。其余本片 writer/POM/UI/测试/authentication 工作项仍未提交。`styles/index.css` 的遗留样式，以及最新状态中公共组件、Users/Departments/Roles 编辑不归本片，完整保留，未代改或暂存。旧路径统计不是当前状态；范围报告不等于所有权，提交前必须再核对 hunk。WSL target 与并行会话隔离，不改全局网络或关闭 TLS。

本轮启动的 Vite 25176（任务 bl4zic5gz）与 25177（bm7ywpuqh）均已用 TaskStop 停止，并已核实这两个自有验证端口无监听；没有停止其他窗口服务、数据库或 WSL，没有删除数据卷。整体审计修复未完成，人工接受、发布和生产迁移未完成；无本窗口提交/推送。安全关键切片提交推送需单独授权，不将普通修复授权扩展到生产或跨分支操作。

### 共享树集成验证（2026-11-02）

用户本轮明确授权提交与推送。共享树含并行窗口已提交的用户删除、1MiB 请求体修复与 UI 视觉收敛，复验结果：

- 后端：WSL PTMCP-Dev 定向 `-f ptmcp-parent/pom.xml -pl ptmcp-gateway -am -Dtest=PlatformProofRoundTripTest`，7 tests / 0 failures / 0 errors，BUILD SUCCESS，Spotless/Checkstyle/Enforcer 同时通过（日志 `/tmp/audit-slice-verify.log`）；admin test scope plain JAR 在共享树可用。
- authz：`OAuthAuthorizationCodeFlowTest#adminWrittenJsonArrayClientRowsCompleteTheCodeFlow` 1/1 PASS（日志 `/tmp/authz-json-array.log`）；提交前先经 Spotless 修正格式。
- 前端：`node --experimental-strip-types --test tests/*.test.ts` 168/168；ESLint/Prettier/tsc/vite build 通过；`python scripts/check.py` 74 项 PASS（本机无 `python3` 别名）。
- 浏览器：合成 transport 挂载真实组件的七场景此前 PASS，本轮未复跑。

提交：`ed2f637`（后端 writer/POM/往返测试/合同）、`39be5fd`（前端时区/枚举/证明表单/回归）、`d1f6cf6`（AS JSON 数组授权码流）。共享 `AuditPage.tsx` 与 `DatasourcesPage.tsx` 按 hunk 拆分，视觉部分归 UI 提交 `7907ec3`，未整文件暂存其他窗口内容。Verification 改为 PASS，仍待人工验收；未做真实 Tier1 TLS 证书链、OAuth 四客户端、双库端到端与生产迁移，独立评审未复跑本轮测试。
