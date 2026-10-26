# PTMCP 版本测试报告

版本：1.2-candidate · 日期：2026-10-26 · 报告性质：当前候选纵切执行状态；设计阶段历史快照单列。

**结论：当前仍不具备 PTMCP 上线测试通过的证据。** 本机隔离环境已跑通后端主要模块、MCP
问数闭环和前端契约/构建，但独立安全评审、私有 29VQ、四客户端矩阵、生产部署与人工接受仍缺失。
本报告中的套件 PASS 不等于测试计划 84 个主用例逐条通过；未逐条执行的主 ID 保持
`NOT_RUN/PARTIAL`，不能据此批准 M0/M4 或发布。

## 1. 测试对象与构件状态

| 项目 | 实际状态 |
| --- | --- |
| 设计基线 | [总体方案1.0-design](../总体方案.md)及配套文档 |
| PTMCP 应用 | 当前分支 `xquan/feat-backend` 的本地源码与候选构建；不是生产构件、镜像 digest 或发布版本 |
| 三 Boot 应用/前端 | admin、authz、gateway 均有 Spring Boot 启动路径与测试；Vue 前端有真实服务层和开发预览；生产同构部署未验证 |
| 数据库迁移 | V1–V6 在真实 MySQL 8.4 Testcontainers 上验证；完整元库恢复与部署演练未做 |
| 测试环境 | Enter-Dev：Java 21.0.10、Maven 3.9.9、Node 24.22.1、pnpm 11.9.0、Testcontainers + WSL Docker 29.1.3；合成数据、随机凭据，未连接既有业务库 |
| 公网域名/HTTPS/客户端注册 | 未联调；`*.example.com` 为保留示例 |
| 测试用例 | [test-case.md](test-case.md) 定义 84 个稳定主 ID 与参数化验收方法；尚无逐条业务执行日志 |

## 2. 2026-10-26 候选纵切执行记录

| 域/套件 | 状态 | 实际结果与证据 | 边界 |
| --- | --- | --- | --- |
| common | PASS（候选） | 224 例，0 失败/错误/跳过；含平台 proof/CIDR 纯策略 | 纯 Java 值对象/错误/权限/证明；不代表 HTTP/MCP 集成 |
| persistence | PASS（候选） | 85 例，覆盖 V1–V6 迁移、安全事务、审计仓储、WAL 回放、组织/元数据仓储 | 真实 MySQL 容器；未做生产规模、跨实例回放与恢复演练 |
| datasource | PASS（候选） | 35 例，MySQL/PG 只读执行、取消/预算、宽行/类型投影、元数据采集、PG approved-plan 适配 | PG 生产 TLS 证书链未做真实容器联调；10MiB 单格/内存峰值未证明 |
| guard | PASS（候选） | 31 例；含 T07-c 的 guard 全套件记录 | 真实数据库结果等价与完整管线集成仍待 T19 后续片 |
| admin | PASS（候选） | 45 例（AdminApiTest 8、SessionApiTest 7、DatasourceApiTest 8、Web 边界 20 等），0 失败 | 审计解密、平台 CRUD/Scope/客户端、真实前端网络未覆盖 |
| authz | PASS（候选） | 32 例，含 OAuthAuthorizationCodeFlowTest 13 + 启动 2（T13-a/T13-b/T14-a）；proof/CIDR 测试已迁入 common | access token 按 sid 撤销、并发刷新/双副本竞争、真实 MCP 客户端未执行 |
| gateway | PASS（候选） | 29 例（启动 2、边界 1、WAL 组件 3、权限 DENY 1、MySQL 闭环 15、审计韧性 1、PG 闭环 3、结果编码 1、Tier1 proof 2），2026-10-26 复跑全绿 | PG 闭环使用测试专用非 TLS 池；Tier1 直连 mTLS/代理部署未联调；配额为单进程内存计数 |
| MCP 查询闭环 | PASS（候选） | MySQL：PAT/JWT、ACL DENY/写 SQL/未知对象/多语句拒绝且零业务连接、START/terminal/WAL、Unicode/限幅、用户3并发+3秒队列超时；PG：目录/表结构/类型化查询、缺 schema 拒绝 | 本机 HTTP + Testcontainers；PG 生产 TLS 与对象级拒绝矩阵未做；配额仅单进程 |
| 前端构建与契约 | PASS（候选） | `pnpm --dir ptmcp-ui check` 通过；`test:contracts` 68/68；30 个预览入口浏览器走查 | 合成数据预览不是后端/安全/E2E 验收；人工视觉接受 pending |
| 仓库检查 | PASS | `python scripts/check.py` 48 项检查器回归通过 | 文档/流程/配置机械检查，不是业务安全评审 |
| 私有 29VQ | BLOCKED | 原始夹具/参数/预期/授权位置未提供 | 不能用合成用例替代 |
| ≥300 内部回归 | NOT_RUN | T18 分支存在合成 509 例候选，未合入当前基线、未在当前基线执行 | 合成回归不能冒充原 VQ 或生产等价 |
| 四客户端矩阵 | NOT_RUN | 用户尚未提供客户端版本 | 不能由协议测试推断兼容 |
| 部署/恢复/压测 | NOT_RUN | 无生产构件、镜像 digest、域名、压测与故障演练 | 不构成发布证据 |

本次实际复跑（2026-10-26，四线整理时）：

```text
mvnw.cmd -B -ntp -pl ptmcp-admin -am verify
→ BUILD SUCCESS；common 210 / persistence 85 / datasource 31 / admin 45，0 failures/errors/skipped。

mvnw.cmd -B -ntp -pl ptmcp-authz -am verify
→ BUILD SUCCESS；common 224 / persistence 85 / authz 32，0 failures/errors/skipped。

mvnw.cmd -B -ntp -pl ptmcp-gateway -am verify
→ BUILD SUCCESS；common 224 / persistence 85 / guard 216 / datasource 35 / gateway 29，
  0 failures/errors/skipped。

# 在 ptmcp-parent 执行的全仓 Maven 门禁
mvnw.cmd -B -ntp verify
→ BUILD SUCCESS（6m12s）；common 224 / persistence 85 / guard 216 / datasource 35 /
  admin 45 / authz 32 / gateway 29，0 failures/errors/skipped。
```

`python scripts/check.py`（轻量检查）48 项通过。`python scripts/check.py --all` 在本机
Windows 下用 `cmd /d /c mvnw.cmd` 未解析当前目录，未能执行 Maven；上面的全仓 Maven verify
与 `pnpm --dir ptmcp-ui check` 已分别实际执行并通过。门禁脚本的 Windows wrapper 解析需后续
修正，不能把“脚本退出0”当作 Maven 已运行。

PG 接线测试使用测试专用非 TLS 连接工厂（真实 PG 容器）验证执行/guard/结果编码；
生产 `PinnedTlsPgPoolFactory` 的 `verify-full` + PEM根证书路径尚未做真实容器联调，保持缺口。

环境说明：本机 Clash Verge（mihomo TUN）使 WSL localhost 端口转发间歇失效，Testcontainers
首次报 `Could not find a valid Docker environment`；通过 WSL 虚拟网卡 TCP 转发 +
`TESTCONTAINERS_HOST_OVERRIDE=<WSL IP>` 后一次全绿。首次 datasource test-compile 失败为旧
`target` 增量编译残留，`clean test-compile` 后消除。网关 TLS 测试在 host override 下还需测试
临时证书 SAN 与 allowed-cidrs 包含该 IP（2026-10-26 已改为按环境变量动态加入，生产策略不变）。
以上均为本机环境处理，未改断言或降低阈值，不改变业务结论。

## 3. 历史已执行证据：JSQLParser 5.4

来源为 [2026-10-23审查复现证据](../reviews/PTMCP_审查复现证据_2026-10-23.md)，其记录
Java/Javac21.0.10，JSQLParser5.4 jar SHA-256 为
`6E10F0B014D6A474808EEAAFD639EC9092117AF2D4DC083764D0B4BF6897ADD7`。以下是该历史运行记录，
本轮文档整理未重新运行探针，也未新增数据库级实验。

| 历史ID | 场景 | 应识别物理表 | 原版TablesNamesFinder记录 | 结论 |
| --- | --- | --- | --- | --- |
| HIST-FINDER-001 | control_cte | orders、users | orders、users | 对照正确 |
| HIST-FINDER-002 | control_shadow | salary | salary | 对照正确 |
| HIST-FINDER-003 | derived_alias | salary | 空集合 | 缺陷复现：漏表 |
| HIST-FINDER-004 | nested_cte | salary | 空集合 | 缺陷复现：漏表 |
| HIST-FINDER-005 | nonrecursive_cte | salary | 空集合 | 缺陷复现：漏表 |
| HIST-FINDER-006 | union_alias | salary | 空集合 | 缺陷复现：漏表 |

统计为6例：2个对照正确、4个反例漏表。它证明原版Finder不能直接承担安全ACL；没有证明PTMCP
已被攻破，也没有证明计划中的ScopedTableBinder已修复。额外历史探针包括显式erp.salary、
未限定pg_authid、自定义CAST类型可解析、显式OPERATOR样例解析失败，均不扩大为数据库越权结论。

## 4. 源码核对与运行测试分开记录

| 事项 | 证据类型 | 可得结论 | 不能推导的结论 |
| --- | --- | --- | --- |
| Spring Security7.1.2公开RT生成行为 | 官方同版本源码核对 | 默认generator拒绝授权码公开客户端签发RT | 四客户端已刷新成功/失败 |
| 公开refresh认证入口与轮换持久化 | 官方源码与设计分析 | 仅改generator不足，须扩展完整链和family记录 | PTMCP扩展已实现、并发已安全 |
| JDBC流式/字段物化 | 官方驱动文档及源码核对 | fetchSize和stream API不等于任意大字段内存有界 | PTMCP已通过防OOM压测 |
| 若依用户/依赖边界 | 本地源码核对 | 用户状态与PTMCP事件需适配；Sa-Token/解析器依赖需隔离 | 三进程依赖收敛/启动已通过 |

详细来源与限制见 [原审查](../reviews/PTMCP_方案审查_2026-10-23.md)和
[总体方案](../总体方案.md)。

## 5. 当前测试执行状态（按主用例组）

| 测试域 / 用例组 | 状态 | 原因或解除条件 |
| --- | --- | --- |
| TC-GRD-001～018 守门与改写 | PARTIAL | 单元/AST 覆盖已实现；真实双方言结果等价、完整 fuzz 与独立评审未做 |
| TC-ACL-001～011 权限/时效 | PARTIAL | ACL 交集/DENY/窗口/安全状态已有测试与 T19-a 闭环；≤5 秒撤权、多副本、丢事件/主库故障未做 |
| TC-AUT-001～013 认证与令牌 | PARTIAL | 授权码流程、公开客户端 refresh 轮换/重放整族撤销、标准 refresh revoke、PAT/JWT、Tier1 proof 候选；access token 按 sid 撤销、直连 mTLS 证书链/代理部署、并发刷新/双副本竞争与真实客户端未做 |
| TC-EXE-001～010 安全执行器 | PARTIAL | MySQL/PG 均已接入真实执行与结果投影；用户/数据源/网关配额与3秒队列已接线（单进程内存）；PG 生产 TLS、对象级拒绝、多副本共享配额、生产规模与内存证据缺 |
| TC-AUD-001～007 审计 | PARTIAL | START/terminal/WAL/503 已有；磁盘满、180 天保留、SQL 脱敏 AST 与解密审计未完成 |
| TC-UI-001～006 前端与API | PARTIAL | 构建/契约/预览/隔离浏览器；真实 18 组 E2E、AS 闭环与人工接受未完成 |
| TC-API-001～008 管理/元数据契约 | PARTIAL | 数据源/组织/会话/审计/平台只读接口有测试；平台 CRUD/Scope/客户端、解密与完整越权专项未做 |
| TC-OPS-001～005、TC-PERF-001～003 | NOT_RUN | 无生产构件、故障演练、压测数据 |
| TC-DEL-001 发布证据完整性 | NOT_RUN | 完整运行构件与可复跑资产尚未交付 |
| 29条内部VQ业务等价验收 | BLOCKED | 原始夹具、参数渲染器、测试数据、预期结果和授权交付位置未提供 |
| ≥300条内部回归/fuzz | NOT_RUN | 当前基线未合入/执行；T18 分支合成 509 例需另行评审与接入 CI |
| 四客户端公网联调 | NOT_RUN | 指定客户端版本、HTTPS 回调环境和注册记录尚未形成 |

当前报告记录的是候选纵切套件结果，不计算全系统通过率；未执行的主用例不按 PASS 计。

## 6. 阻塞项与下一步

| 阻塞项 | 责任领域 | 解除证据 | 影响 |
| --- | --- | --- | --- |
| PG 生产 TLS/拒绝矩阵、Tier1 直连 mTLS/代理部署、多副本共享配额 | 网关/执行器/安全 | PG TLS 容器联调、直连证书链/代理配置、Redis/共享租约与压测、独立评审 | M0/M1 不能判定 |
| access token 按 sid 撤销、并发刷新/丢响应与双副本竞争 | 认证 | NONE+S256 真实客户端、并发/丢响应/双副本日志 | 自动续期仍为候选 |
| 独立安全评审与 ASVS 专项 | 安全 | 对实际 diff/证据的独立复核与发现修复 | 候选不能升级为发布 |
| 29条VQ资产不可复核 | 业务验收/资产 | 有许可的内部 fixture 与数据 seed/预期 | 业务结果验收 BLOCKED |
| 四客户端版本/域名/部署 | 集成/运维 | 版本化客户端记录、HTTPS 回调、镜像 digest、恢复演练 | M2/M3/M4 不能判定 |
| 前端真实 18 组 E2E 与人工接受 | 前端/产品 | 隔离后端网络证据、键盘/窄屏、用户接受记录 | T17 与 G-14 保持 PARTIAL |

建议先完成 Tier1 直连 mTLS/代理部署与 PG 生产 TLS/拒绝矩阵，再做多副本共享配额/压测与公开
access token 撤销/并发刷新；四客户端矩阵待用户提供版本。安全关键变更在独立评审前只按候选记录，
不把工程绿灯当发布批准。

## 7. 正式执行报告必须补齐的字段

后续每次报告必须给出测试开始/结束UTC时间与Asia/Taipei展示、执行人角色、应用SHA与镜像
digest、依赖与配置版本、客户端版本、fixture版本、PASS/FAIL/NOT_RUN/BLOCKED分布、失败最小
复现、缺陷链接、性能原始数据位置、敏感信息脱敏说明、遗留风险及发布结论。出现授权绕过、
错误对象读取、失效超过5秒、审计丢失或连接复用误杀时，结论必须为阻断发布。
