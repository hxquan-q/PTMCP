# DEV-20261106-deploy-review-fixes：部署阻断修复

ID: DEV-20261106-deploy-review-fixes
Status: blocked
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-11-06

## 目标与边界

承接用户要求逐步实现部署评审的 11 条修复，关联
[容器包](DEV-20261106-deploy-container-pack.md)与
[原生包](DEV-20261106-deploy-native-pack.md)。本项为部署工程修复，人工产品验收不适用；
不据此宣称生产交付、初始管理员或真实客户端验收完成。

不变量：秘密不进入源码/日志；authz 非 root；空端口集合拒绝全部；
OAuth 资源仅允许 HTTPS 或回环 HTTP；应用运行账号不得持有 DDL 权限。
相关约束见[部署手册](../../docs/ops/deploy.md)。
关联 [ADR-0009](../../docs/architecture/adr/0009-无状态HTTP与三服务部署.md)、FR-10/FR-19；
本项提供 TC-DEL-001 的部署工程证据，不代替 TC-OPS-003/004/005 或真实客户端验收。
决策和被否方案见[部署边界笔记](../../.agents/notes/implemented/bug-fix/2026-11-06-deploy-boundary-fixes.md)。

配置兼容：REST/OAuth/MCP 线格式不变；Compose 将旧的共用元库用户名/密码替换为
迁移和三个固定服务身份及四个独立密码。已有卷必须按
[升级步骤](../../deploy/README.md#元库账号与已有卷升级)迁移和撤销旧权限，不能运行 `--force` 轮换业务加密密钥。

## 实施与验证

按顺序交付可验证切片：

1. Docker 质量配置复制；三份 nginx 的登录/退出、Host 端口、路径引号及 Windows 无 BOM 写入。
2. 三个配置生成器的 OAuth 基址校验、原生监听端口、Windows 秘密 ACL、空端口拒绝语义。
3. Compose 签名钥非 root 读取与迁移/分进程运行账号隔离，补兼容及升级说明。
4. 定向运行验证、仓库检查和适用构建门禁，自审实际 diff。

## 结果与证据

评审类型：单 Agent 自审；修复候选已实施并通过下述工程验证。安全专项独立评审尚缺，
因此保留 verifying，不宣称可合入/发布。

| 评审项 | 实现与验收证据 |
| --- | --- |
| R01 Docker 质量配置 | `apps.Dockerfile` 复制 `/config/quality`；相同 Maven 镜像、目录布局 validate 为 BUILD SUCCESS，实际三应用 Maven 构建阶段及四个最终镜像均构建成功 |
| R02 非 root 私钥 | 准备容器将 0600 宿主 secret 复制到 authz 独占卷，目录 0700/文件 0400、UID 10001；实际可读，UID 10002 不可读；authz 启动、JWKS 成功 |
| R03 login/logout | 三份 nginx 精确转发；真实 nginx GET/POST 路由与实际 Compose OAuth 登录失败重定向、POST logout 均通过 |
| R04 外部端口 | 三份 nginx 使用 `$http_host`；真实 upstream 保留端口，实际同源 CSRF 获取/登录进入凭证校验，异源仍 403 |
| R05 无 BOM | Windows PowerShell 5.1.26100.8875 生成文件无 EF-BB-BF，使用显式 UTF8Encoding(false)；真实 Windows nginx 1.30.6 读配置和启动成功 |
| R06 OAuth 基址 | 两个 Bash 生成器共用 URL helper，Windows 使用等价校验；非回环 HTTP、非法端口、凭证/路径/query/fragment 与非法主机在写秘密前被拒绝 |
| R07 Windows ACL | 普通用户可继承读取的隔离安装目录中，新建和 Force 覆盖的配置/PEM 均仅当前用户与 SYSTEM 可读；先收紧目录和既有文件再写入 |
| R08 空端口 | Linux、Compose 和 Windows 实际子进程均区分未设置/显式空/自定义；5.1 通过空白环境值保留应用 trim 后的空集合语义 |
| R09 元库分权 | 真实 MySQL 8.4.13 从空卷迁移至 V20；迁移/三运行账号各异，9 次运行账号 DDL 和跨职责写入均在权限边界失败；admin 只能撤销 OAuth，authz 不可改宿主密码，gateway 不可读密码表 |
| R10 空格路径 | 两个原生模板引用全部文件路径；Linux nginx 实际在含空格目录启动并转发成功；Windows nginx 1.30.6 实际完成配置检查、启动、健康/静态访问和停止 |
| R11 监听端口 | 原生生成器由直接 HTTP 基址生成端口（19090、IPv6 字面基址 19091、无端口 80）；HTTPS 外部 TLS 场景保留内部 8080，已写明适配与未验收边界 |

额外修复两个验证中发现的同链路阻断：`maven.test.skip` 会漏生成下游依赖的 test-jar，
镜像/原生打包改用 `skipTests` 保留夹具；Windows 原生命令两路输出并发读取，
64 KiB stderr 回归通过，消除 OpenSSL 随机进度输出造成的死锁。
真实 Windows nginx 补验还发现 PID 文件创建与写入之间的空文件竞争，已增加明确的
未就绪处理和确定性 red/green 测试；不会把 PID 文件存在误判为已写好。

实际命令/结果（2026-11-06）：

- `python scripts/check.py`：PASS，74 项检查器回归；`git diff --check`、全部部署 Bash/PowerShell 语法检查 PASS。
- `python3 -m unittest discover -s deploy/tests -v`（WSL PTMCP-Dev）：5 项集成测试 PASS，覆盖三份真实 nginx、生成器及真实 Compose/MySQL。临时栈使用唯一 project 名，结束后仅清理该栈及其卷。
- `powershell.exe -NoProfile -ExecutionPolicy Bypass -File deploy/tests/test_windows.ps1`：PASS，覆盖新建/既有文件 ACL、URL/端口、无 BOM、实际子进程端口环境和大 stderr。
- 追加 `-NginxExe .local/deploy-review-tools/nginx-1.30.6/nginx.exe`：PASS，真实 Windows nginx 配置/启动/healthz/SPA/停止与空 PID 文件回归。zip 与官方 pluknet.key 验证 PGP 签名通过，指纹 D6786CE303D9A9022998DC6CC8464D549AF75C0A；只解压在本任务 `.local` 下，未改宿主安装。
- `PTMCP_DEPLOY_TEST_IMAGES=1 python3 deploy/tests/test_compose.py`：PASS（45.861s）；四个当前源码镜像全部 healthy，同源 CSRF 200、异源 403、未知账号登录 401，OAuth 页面、JWKS、表单登录失败跳转及退出可达。
- 四个镜像实际由 `docker buildx build --load` 构建，标签为 `ptmcp-review-{admin,authz,gateway,ui}:local`，仅在本机，未发布。Buildx v0.38.2 从官方 release 下载并核对 SHA-256，放在 `.local/deploy-review-tools/`；通过任务专用 DOCKER_CONFIG 使用，未改全局工具或 daemon 配置。构建时仅对子进程清除失效的代理环境变量。
- 项目环境下首次 `python scripts/check.py --all`：Windows 在 persistence 的 Testcontainers Docker 环境探测失败；未降低门禁或忽略测试。改在 WSL Java 21.0.12.1/Maven 3.9.9 的隔离副本执行 verify；第一次漏复制 `docs/backend/mcp-tools.json`，仅两套契约测试报缺文件。补齐原始契约后以 `-pl ptmcp-gateway -am -Dtest=McpToolMetadataTest,McpQueryFlowTest -Dsurefire.failIfNoSpecifiedTests=false verify` 重跑通过。该次 WSL Surefire XML 汇总 1277 项、failure/error/skipped 均 0；保留历史失败，后续完整 Windows 结果见下项。
- Windows 补验 `python scripts/check.py --all --maven-cache-seed D:/config/ptmcp/cache/maven`：完整退出 0 / PASS；后端 1277 项、前端 206 项、仓库 74 项及隔离 Maven 正反例均通过，未修改检查阈值或跳过测试。使用下述临时原始 TCP 中继修复本次验证的网络可达性，仍严格验证原有服务端/客户端证书；不宣称永久开发环境已修好。
- 项目环境下 `python scripts/check_backend_quality.py --seed-repository D:/config/ptmcp/cache/maven --timeout 900`：实际隔离 Maven 正反例 PASS；`pnpm --dir ptmcp-ui-react check` 与 `test`：PASS，206 项前端测试。
- 三条 `npx --no-install tsx .agents/skills/write-notes-like-deepseek/scripts/verify-*.ts`：笔记树/格式/归档校验 PASS。
- 原生包用已有 JAR/UI 产物验证装配：Linux tar 成功，WSL 缺 zip 且 PATH 无 powershell.exe，Windows zip 用宿主 Compress-Archive 完成；两个包内 URL helper/运行模块与当前源码逐字节一致。它们仅为装配探针，不代表这些既有 JAR 对应新发布版本或完成原生安装验收。

本机证据：`.local/deploy-review-{build,images,maven,maven-rerun,quality,repo-check,package}.log`，
隔离后端副本 `/tmp/ptmcp-deploy-verify-qdRHiP`；前端日志在系统 TEMP 的 `ptmcp-deploy-ui-*`。
Windows 原生补验日志为 `.local/deploy-review-windows-nginx-final.log`，该次真实启停已 PASS。
完整 Windows 补验日志 `.local/deploy-review-windows-all.log` 已结束并为 PASS；此前
失败/WSL 组合复验证据仍保留。验证对应 HEAD `de52c5e` 加本工作项的未提交修复。

自审覆盖正确性、简洁性、结构、安全和资源：核对 source/包/helper 同步、配置兼容、
secret/账号挂载边界、失败阻止启动、同/异源 CSRF、权限拒绝错误码及临时栈归属。
发现的 test-jar、OpenSSL 输出死锁与“建表前授权”问题均已修正并复验；未声称独立评审。

## 交接

已完成授权内的 11 项实现、定向回归与完整门禁。用户随后明确要求“提交推送”，
本次按该授权提交并推送修复候选到当前 dev 分支的配置上游 codeup/dev；
不据此扩大为委派、合入或发布授权，Git 执行结果以提交记录及远端 ref 为准。
阻断仅剩安全专项独立评审：尚无明确委派授权，因此维持 blocked / 待评审候选，
不把提交或自动目标续行当成独立评审通过。收到评审授权后恢复 verifying，
按提交后的实际 diff 和证据安排只读独立评审，修复发现并复验。

开始工作树只有 `research/` 下既有未跟踪文件，保留且不纳入本项。
当前分支 dev；用户已单独授权本批安全关键修复的提交/推送。仅纳入本项代码、测试、
部署文档及决策记录，既有 research 文件不纳入；没有合入或发布。

剩余：安全专项独立评审及对应授权；真实管理员/客户端、外部 TLS、原生整包安装、
生产性能/恢复仍由父工作项验收。原生外部元库无自动账号分权步骤。
两处失败探针的 nginx 进程已按精确 exe/配置路径核对后停止；自动审核拒绝递归清理
这两处 TEMP 目录（仅报告 blocked by policy），因此保留，不绕过拒绝。
Windows Docker 阻断已定位：既有 Windows portproxy 指向 WSL 网卡 12376，Docker 只监听
WSL 回环 12376；相同客户端材料在 WSL 内双向 TLS 验证通过。只为本次验证运行临时原始
TCP 中继，将该网卡地址转到回环；不终止 TLS、不改证书/全局 trust、daemon 或 portproxy。
全量检查 exec session 7633 已完成，退出 0。删除本任务中继控制文件也被自动审核拒绝，
没有换工具绕过删除；通过 session 52797 的进程中断关闭自身中继，核查 `/proc` 无该命令、
12376 仅余原 Docker 回环监听。`.local/deploy-review-docker-relay.live` 保留，不能据其存在
声称中继仍在运行。临时中继不是交付物或永久环境修复；没有遗留 nginx 测试进程。
不得用本项的隔离健康与负向登录测试代替成功员工登录/授权和生产交付。
