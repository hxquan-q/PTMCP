# DEV-20261102-mcp-transport-hardening：MCP HTTP 边界与四工具元信息

ID: DEV-20261102-mcp-transport-hardening
Status: verifying
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-11-04

## 目标与边界

来源：用户在 DatI / MCP / 权限对照后要求“开始改”。续接 [gateway 收尾](DEV-20261028-gateway-completion.md) 的 Origin / 协议头缺口；关联 [PRD FR-09/FR-18/FR-20、NFR-01](../../docs/product/PRD.md)、[TC-AUT-011/012](../../docs/test/test-case.md)（前者保护认证边界，后者真实客户端矩阵仍未验证）、[ADR0009](../../docs/architecture/adr/0009-无状态HTTP与三服务部署.md)、[API §7](../../docs/backend/api-spec.md#7-mcp-传输与四个工具的精确输入)、[机器契约](../../docs/backend/mcp-tools.json)。本片不实现发布管理、模板 SQL 或管理员代执行。

不变量：管理员配置发布，员工仅使用；四个固定工具不改名、不改输入；Bearer、平台∩用户、Deny、guard、取消与持久审计保持原链。HTTP 拒绝不得进入工具、借用业务连接。参考工程只读，不运行其脚本；原有四个未跟踪 `.auto/*.sh` 不改、不提交。

## 实施与验证

### 实施前接口变更合同

- 路由：gateway `/mcp`，仍 STATELESS / JSON-RPC，协议固定 `2025-06-18`；不新增 REST 包装、会话、SSE 或服务路由。权威合同同批更新 API §7。
- 认证：Origin 检查在 Bearer 校验前；允许 Origin 不代表获准认证或访问资源，正常请求仍逐请求认证；无 Origin 的非浏览器客户端继续可用。响应继续 no-store / X-Request-ID。
- Origin：仅一个合法 http(s) origin，可用 `ptmcp.gateway.allowed-origins` 显式配置逗号分隔白名单，默认空（拒绝所有带 Origin 的请求）。拒绝 `null`、通配符、重复/列表、用户信息、路径、query、fragment、非法端口；按 scheme/host/default port 规范化精确比对。不以 Host / Forwarded / X-Forwarded-* 自动放行。拒绝 HTTP 403，固定脱敏响应，无业务访问。不为此新增 CORS 授权。
- 协议头：initialize 正文协商版本，允许无头；若带头仍须单个且为受支持版本。后续请求须带单个 `MCP-Protocol-Version: 2025-06-18`。缺失时按规范无状态默认 `2025-03-26`，本端未支持该版本，返回 HTTP 400；空、重复、非法、不支持版本均 400。正文 parse error / invalid request 保留 JSON-RPC 行为，不用初始化豁免绕过非法协议头。
- 工具元信息：四工具增加稳定 title、annotations.readOnlyHint=true、destructiveHint=false、openWorldHint=false；不宣称结果幂等或审计无副作用。annotations 仅客户端提示，不参与授权或 guard。
- 副作用/生命周期：无持久化迁移；无新可写工具；版本头校验在工具执行前。既有业务审计不因 metadata 提示变更。If-Match、发布、回滚、委派不适用于本片。
- 兼容：新增元信息为增量；旧无头工具请求收紧为 400，调用方需先补版本头。浏览器 Origin 需先由部署显式配置；无 Origin 的 PAT/OAuth 客户端不受 Origin 收紧影响。紧急收窄应停用入口，不增加 wildcard 或绕过认证；代码回滚仍须保持既有身份/ACL/guard/审计门禁。
- 标准依据：MCP 官方仓库 `docs/specification/2025-06-18/basic/transports.mdx`（2026-11-02 读取）；官网直连获取失败，已使用官方 GitHub 原文核对。该规范要求校验 Origin；非法/不支持版本 HTTP 400，无其他版本信息时 SHOULD 默认 2025-03-26。不将常量改为 2025-11-25。

串行实施，由当前 Agent 独占本片源文件与测试；安全关键代码完成后按流程请求只读独立安全评审。最低证据：先 RED 再 GREEN；真实随机端口 HTTP 拒绝与认证测试、工具 schema/元信息契约、现有相关集成测试、Spotless/Checkstyle、`python scripts/check.py`。MySQL/PG、真实 PAT/OAuth 客户端只有实际执行才记录通过；mock 不替代客户端验收。

## 结果与证据

### 已实施

- 新增 `McpHttpPolicy`：显式 Origin 白名单、非法配置启动失败、单版本头与 initialize 例外；Filter 在认证前拒绝非法 Origin，Controller 在工具分派前拒绝版本错误。
- 四工具新增 title 与只读 annotations，输入 schema 不变；机器契约、API §7、部署配置同步。四个既有真实集成类的 HTTP helper 补版本头，原业务断言不删减。
- 新增真实随机端口 HTTP 测试、policy 单元边界、工具元信息与 JSON 契约对比；既有默认配置边界测试覆盖 POST/GET/DELETE 的 Origin 默认拒绝。

### 已执行验证（2026-11-02）

- RED：`McpTransportHttpTest,McpToolMetadataTest`，7 项，4 失败、0 错误。实际暴露非法/重复 Origin 放行、后续请求缺版本头放行、工具缺 title。之前两次命令参数错误不计 RED 证据。
- GREEN：进入 `scripts/Enter-Dev.ps1` 环境后，gateway reactor `spotless:apply`，再 `mvn -f ptmcp-parent/pom.xml -pl ptmcp-gateway -am test "-Dtest=McpTransportHttpTest,McpToolMetadataTest,McpHttpPolicyTest,McpHttpBoundaryTest,McpResourceMetadataHttpTest,GatewayApplicationHttpStartupTest" "-Dsurefire.failIfNoSpecifiedTests=false"`。16 项通过，0 失败/错误/跳过；Spotless、Checkstyle、Enforcer 通过。
- `python scripts/check.py`：PASS（文档链接/工具契约数量、工作流、质量配置、提交格式与脚本单测）；不是产品全量构建或真实客户端验收。
- `git diff --check`：PASS；原四个 `.auto/*.sh` 保留未改。
- 真实集成首次尝试（Windows native）：`McpQueryFlowTest,McpPgQueryFlowTest,McpTier1ProofTest,McpAuditResilienceTest` 共 47 项（不是此前误记的 51）全部因 Testcontainers 初始化错误报 ERROR，0 断言失败。根因日志为 Docker API `https://127.0.0.1:12376` Connection reset / Could not find a valid Docker environment；Docker CLI 可返回版本不等于 Java TLS API 可用。当时未改宿主网络/代理/证书或安全门禁。

### 独立安全评审与剩余阻断

- 真实数据库集成重试仍为同一 Docker API Connection reset，47 项 ERROR，未执行到业务断言。设置容器 Host Override 不能修复 daemon API 的本机 TLS 连接。此项 BLOCKED，未改宿主网络或放宽 TLS/门禁。第三次重试（绝对路径进环境 + 动态 WSL Host Override，任务 `bxrs934ri`）在 Java 连接 `https://127.0.0.1:12376` 时仍 `Connection reset` / Could not find a valid Docker environment，0 断言失败；同一窗口 `docker ps`（exit0）与 `Test-NetConnection 12376`（True）却正常，确认是宿主 loopback/TLS 对 Java 进程间歇不稳定，非代码问题。不再自动重试该环境；不绕过 TLS、不改宿主网络。
- 独立只读安全评审完成（agent `a379ed5d009ba7ee2`）：未发现可核实 High/Medium 问题，确认一项 Low 错误合同回归：无版本头、非法 `id=true/[]/{}` 应走现有 JSON-RPC `-32600`，原实现错误返回空 HTTP 400。新增 policy + 真实 HTTP 调用原服务 envelope 验证的回归，实际 RED 为 11 项、2 失败、0 错误（非法 id 被拒绝及 HTTP 期望200实际400）。随后补非法 id 判定，显式头优先拒绝保持；同一六类测试完整 GREEN 为 17 项，0 失败/错误/跳过（任务 `bwmyhyiol`，退出0），Spotless/Checkstyle/Enforcer 通过。原独立评审者已仅静态复核修复段与新增 HTTP 用例，确认原 Low 项在代码层面修复，未自行运行测试。HTTP 认证仍 mock，不称为真实 PAT/OAuth。评审未运行 Maven或验证代理/客户端。版本拒绝在认证后、工具调用前，不声称发生于所有数据库访问前。
- 真 MCP 客户端 PAT/OAuth、客户端兼容矩阵、生产部署与完整 conformance 均 NOT_RUN；mock 身份/工具的真实 HTTP 测试只证明 transport 边界，不替代这些验收。
- 产品人工接受 pending；无提交、推送、部署或发布。

### 2026-11-04 WSL/Linux 真实集成（解除 Docker 阻断）

Windows native 的 47 项 ERROR 保持为环境证据，不作为本片失败。未绕过 TLS，未改宿主网络。通过改为在 WSL 内用本地 `docker.sock` 跑同一四个类。Verification 的 PASS 只覆盖「快速套件 17/17 + 下面四类 47/47」，不含真实 PAT/OAuth 客户端矩阵、部署或 MCP conformance。

- qa 独立副本 `/var/tmp/qa-transport-20261104`（整仓含 `config/`）。12 个 Java + 3 个 docs 与工作树 SHA-256：SAME=15 DIFF=0。命令 `mvn -pl ptmcp-gateway -am test -Dtest=McpQueryFlowTest,McpPgQueryFlowTest,McpTier1ProofTest,McpAuditResilienceTest -Dsurefire.failIfNoSpecifiedTests=false -DtrimStackTrace=false -Dmaven.repo.local=/mnt/d/config/ptmcp/cache/maven`。结果 Tests run: 47, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS；03:53；EXIT:0。XML 头：McpQueryFlowTest 38/0/0/0，McpPgQueryFlowTest 6/0/0/0，McpTier1ProofTest 2/0/0/0，McpAuditResilienceTest 1/0/0/0。reviewer 复核了这四份 XML 原始头与 `<failure`/`<error`/`<skipped` 计数，并抽检 5 个关键文件 SHA 与工作树一致。
- impl 独立副本 `/var/tmp/ptmcp-transport-20261104`（补上 `config/quality` 后；前两轮分别是 wrapper SHA-256 校验失败、以及缺 checkstyle.xml，都没进 surefire）。同一 Maven 3.9.9（`/mnt/d/config/apache-maven-3.9.9`）+ Ubuntu JDK 21.0.12 + Docker 29.1.3 本地 socket + 同一缓存。`verify-cache2.log` EXIT:0，BUILD SUCCESS。surefire XML 头与 qa 相同：38/0/0/0、6/0/0/0、2/0/0/0、1/0/0/0，合计 47，0 失败/错误/跳过。
- Windows 快速套件（qa）：`McpTransportHttpTest,McpToolMetadataTest,McpHttpPolicyTest,McpHttpBoundaryTest,McpResourceMetadataHttpTest,GatewayApplicationHttpStartupTest` 17/17，XML 头 7+1+4+2+1+2，0 failures/errors/skipped。`mvn package -DskipTests` BUILD SUCCESS。`python scripts/check.py` 与 `git diff --check` PASS。
- Windows native 上 `WalDirectoryOwner.acquire` 对目录做 `FileChannel.open` 失败，不在本片 diff（`TerminalWal` / `WalDirectoryOwner` 未改）。另记 [DEV-20261104-windows-wal-directory-fsync](DEV-20261104-windows-wal-directory-fsync.md)，不并入本片验收。

## 交接

当前切片：HTTP Origin / 协议版本边界和工具元信息。静态评审 no findings；快速套件 17/17 与 WSL/Linux 四类真实集成 47/47 均为 GREEN。Verification 因此从 BLOCKED 改为 PASS，范围仅限上述两套；真实 MCP 客户端 PAT/OAuth、兼容矩阵、生产部署与 conformance 仍 NOT_RUN。Acceptance 保持 pending。未把发布壳或管理员代执行混入本片。

当前分支 dev，HEAD 仍 `3afa9e8bb195820743f48a2ee50c82710e4451e7`；改动未提交。安全关键提交/推送没有单独授权，本片不因验证通过自动提交或发布。reviewer 已说明具备提交条件，等用户单独授权后再按 git 规范提交。原四个未跟踪 `.auto` 脚本仍不纳入。
