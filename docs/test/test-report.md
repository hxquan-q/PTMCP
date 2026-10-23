# PTMCP 版本测试报告

版本：1.1-bootstrap · 日期：2026-10-23 · 报告性质：当前框架与业务验证状态，历史探针分开记录。

**结论：当前不具备PTMCP上线测试通过的证据。** 已建立本机环境与根目录可构建/启动框架，[框架验证](../evidence/framework-bootstrap-validation.md)仅证明工具和接线；本轮按用户要求不开发业务。业务权限/SQL/OAuth/审计测试未执行，历史解析器缺陷不能作为新守门内核通过证明。

## 1. 测试对象与构件状态

| 项目 | 实际状态 |
| --- | --- |
| 设计基线 | [总体方案1.0-design](../总体方案.md)及配套文档 |
| PTMCP应用commit/镜像digest | 本地框架JAR/dist可构建，未提交，不是生产构件或镜像 |
| 三Boot应用、前端和数据库migration | 三应用/前端框架已启动核验；业务与migration未实现 |
| 参考源码 | Reference中的dbx-main与RuoYi-Vue-Plus-6.X，仅用作源码/依赖设计依据 |
| 测试环境 | 已建本机MySQL/PG/Redis和Testcontainers环境；业务夹具、迁移和客户端集成仍待交付 |
| 公网域名/HTTPS/客户端注册 | 尚未联调；文档`*.example.com`为需替换的保留示例 |
| 测试用例 | [test-case.md](test-case.md)定义84个稳定主ID与参数化验收方法，尚非业务执行日志 |

## 2. 历史已执行证据：JSQLParser 5.4

来源为 [2026-10-23审查复现证据](../reviews/PTMCP_审查复现证据_2026-10-23.md)，其记录Java/Javac21.0.10，JSQLParser5.4 jar SHA-256为 `6E10F0B014D6A474808EEAAFD639EC9092117AF2D4DC083764D0B4BF6897ADD7`。以下是该历史运行记录，本轮文档整理未重新运行探针，也未新增数据库级实验。

| 历史ID | 场景 | 应识别物理表 | 原版TablesNamesFinder记录 | 结论 |
| --- | --- | --- | --- | --- |
| HIST-FINDER-001 | control_cte | orders、users | orders、users | 对照正确 |
| HIST-FINDER-002 | control_shadow | salary | salary | 对照正确 |
| HIST-FINDER-003 | derived_alias | salary | 空集合 | 缺陷复现：漏表 |
| HIST-FINDER-004 | nested_cte | salary | 空集合 | 缺陷复现：漏表 |
| HIST-FINDER-005 | nonrecursive_cte | salary | 空集合 | 缺陷复现：漏表 |
| HIST-FINDER-006 | union_alias | salary | 空集合 | 缺陷复现：漏表 |

统计为6例：2个对照正确、4个反例漏表。它证明原版Finder不能直接承担安全ACL；没有证明PTMCP已被攻破，也没有证明计划中的ScopedTableBinder已修复。额外历史探针包括显式erp.salary、未限定pg_authid、自定义CAST类型可解析、显式OPERATOR样例解析失败，均不扩大为数据库越权结论。

## 3. 源码核对与运行测试分开记录

| 事项 | 证据类型 | 可得结论 | 不能推导的结论 |
| --- | --- | --- | --- |
| Spring Security7.1.2公开RT生成行为 | 官方同版本源码核对 | 默认generator拒绝授权码公开客户端签发RT | 四客户端已刷新成功/失败 |
| 公开refresh认证入口与轮换持久化 | 官方源码与设计分析 | 仅改generator不足，须扩展完整链和family记录 | PTMCP扩展已实现、并发已安全 |
| JDBC流式/字段物化 | 官方驱动文档及源码核对 | fetchSize和stream API不等于任意大字段内存有界 | PTMCP已通过防OOM压测 |
| 若依用户/依赖边界 | 本地源码核对 | 用户状态与PTMCP事件需适配；Sa-Token/解析器依赖需隔离 | 三进程依赖收敛/启动已通过 |

详细来源与限制见 [原审查](../reviews/PTMCP_方案审查_2026-10-23.md)和 [总体方案](../总体方案.md)。

## 4. 当前PTMCP测试执行状态

| 测试域 / 用例组 | 状态 | 原因或解除条件 |
| --- | --- | --- |
| TC-GRD-001～018 守门与改写 | NOT_RUN | ScopedTableBinder、ApprovedQuery与真实双引擎执行链待实现 |
| TC-ACL-001～011 权限/时效 | NOT_RUN | 权限模型、权威版本确认与事件链待实现 |
| TC-AUT-001～013 认证与令牌 | NOT_RUN | AS刷新/公开标准revoke扩展、family持久化、真实客户端环境待实现/配置 |
| TC-EXE-001～010 安全执行器 | NOT_RUN | 方言事务、限幅、取消状态机和仪器化待实现 |
| TC-AUD-001～007 审计 | NOT_RUN | audit_outbox/WAL/幂等消费者待实现 |
| TC-UI-001～006 前端与API | NOT_RUN | PTMCP UI、委派范围校验与服务端接口待实现 |
| TC-API-001～008 管理/元数据契约 | NOT_RUN | 数据源目的地校验、快照、接口并发/幂等与输出契约待实现 |
| TC-OPS-001～005、TC-PERF-001～003 | NOT_RUN | 运行产物、环境、故障注入和压测数据缺失 |
| TC-DEL-001 发布证据完整性 | NOT_RUN | 当前为设计交付，完整运行构件与可复跑资产尚未交付 |
| 29条内部VQ业务等价验收 | BLOCKED | 原始夹具、参数渲染器、测试数据、预期结果和授权交付位置未提供 |
| ≥300条内部回归/fuzz | NOT_RUN | 完整参数化manifest和执行模块尚未形成；不得把此文档主用例数量当300条实测 |
| 四客户端公网联调 | NOT_RUN | 指定客户端版本、HTTPS回调环境和注册记录尚未形成 |

当前PTMCP全系统PASS=0、FAIL=0；这两个数字不表示无缺陷，而表示尚无该系统执行记录。用例未执行时不计算通过率，历史Finder的4个漏表不混入PTMCP测试统计。

## 5. 阻塞项与下一步

| 阻塞项 | 责任领域 | 解除证据 | 影响 |
| --- | --- | --- | --- |
| PTMCP工程与依赖基线未构建 | 技术负责人 | commit、effective POM、依赖树、启动日志 | M0尚不可判定 |
| MySQL/PG执行与内存边界待证明 | 内核/执行器负责人 | 结果等价日志、分配轨迹、取消竞态记录 | 宽行路径不得开放 |
| 公开刷新完整链未验证 | 认证负责人 | 真实NONE+S256客户端、轮换/重放/撤销日志 | 自动续期默认关闭 |
| 29条VQ资产不可复核 | 业务验收/资产负责人 | 有许可的内部fixture与数据seed/预期 | 业务结果验收BLOCKED |
| 运维/恢复环境未形成 | 运维负责人 | 镜像digest、部署清单、RPO/RTO演练结果 | 不可发布生产 |

建议先执行 [测试计划M0门禁](test-plan.md#51-m0-退出条件)，再为已实现功能生成绑定具体构件的新报告。保留本报告为设计阶段快照，不以覆盖历史方式制造“曾经已通过”的记录。

## 6. 正式执行报告必须补齐的字段

后续每次报告必须给出测试开始/结束UTC时间与Asia/Taipei展示、执行人角色、应用SHA与镜像digest、依赖与配置版本、客户端版本、fixture版本、PASS/FAIL/NOT_RUN/BLOCKED分布、失败最小复现、缺陷链接、性能原始数据位置、敏感信息脱敏说明、遗留风险及发布结论。出现授权绕过、错误对象读取、失效超过5秒、审计丢失或连接复用误杀时，结论必须为阻断发布。
