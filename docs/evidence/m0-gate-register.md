# M0 验证门禁与证据登记

版本：1.0-design · 2026-10-23；候选纵切状态更新：2026-10-26。**M0 仍未正式通过。**
下表只按工作项中的实际证据升级；`PARTIAL` 表示已有实现与定向测试，但不能替代该项完整验收，
也不能据此勾选 M0 或升级发布结论。独立安全评审、私有资产、外部客户端与生产部署缺失项保持
`NOT_RUN/BLOCKED`。

| ID | 验证项 | 预期材料 | 当前状态/阻塞 | 责任角色 |
| --- | --- | --- | --- | --- |
| G-01 | 依赖收敛与三进程启动 | effective POM/tree、锁定依赖、自动配置/启动日志 | PARTIAL：框架 effective POM/tree 与三应用 HTTP 启动回归已组合 verify；T19-a 新增 gateway 生产依赖与条件装配，业务/安全依赖收敛尚未做最终全量复核 | 后端 |
| G-02 | 关系/列作用域绑定 | 六例各自集合、列限定/相关子查询/双源同名 fixture | PARTIAL：[T06-a](../../tasks/work-items/T06-a.md) 六例逐例断言物理关系集合，[T06-b](../../tasks/work-items/T06-b.md) 列绑定/`t.*` 展开单元证据；精确大小写/引号归一化仍由快照 producer 供给，未做完整真实 Schema 集成与独立评审 | 内核 |
| G-03 | SQL改写等价 | 原SQL/final_sql、列序/类型/结果比较与截断转换预期 | PARTIAL：[T07-a](../../tasks/work-items/T07-a.md)/[T07-b](../../tasks/work-items/T07-b.md)/[T07-c](../../tasks/work-items/T07-c.md) 已有 LIMIT、限定名与重解析等价断言；T19-a 执行 final SQL 但未做原 SQL/改写 SQL 的真实数据库结果对照，`REWRITE_VALIDATION_FAILED` 真实方言覆盖仍缺 | 内核/QA |
| G-04 | 单MySQL+PAT+ACL纵切 | 允许/拒绝、静态拒绝0业务连接、持久START/terminal | PARTIAL：[T19-a](../../tasks/work-items/T19-a.md) 本机真实 HTTP 闭环 14 例，覆盖 PAT/JWT、ACL 交集、写入/多语句拒绝零业务连接、START/terminal 与 WAL；仅隔离 MySQL、同步路径，PG/并发预算/独立安全评审未完成 | 后端/QA |
| G-05 | 公开OAuth完整链 | NONE+S256真实客户端、全部converter/provider/轮换/重放日志 | PARTIAL：[T13-a](../../tasks/work-items/T13-a.md) 13 例授权码流程通过；PUBLIC refresh 轮换/revoke、双副本竞争、丢响应注入与真实客户端仍未执行 | 认证/安全 |
| G-06 | Tier1平台证明 | token端点认证与每次MCP请求证明、伪造/冲突/撤销 | PARTIAL：[T19-c](../../tasks/work-items/T19-c.md) 已接加密平台策略、MTLS 指纹/代理 secret 与来源 CIDR，真实 HTTP 覆盖缺失/错误/不受信来源；直连 mTLS 证书链/有效期/吊销、受信代理部署与配置写入仍未联调 | 认证/运维 |
| G-07 | 权限时效 | 主库提交时间、各副本拒绝时间、丢通知/过期/主库故障 | PARTIAL：[T08-a](../../tasks/work-items/T08-a.md)、[T08-b](../../tasks/work-items/T08-b.md)、[T12-a](../../tasks/work-items/T12-a.md) 有纯函数/安全状态测试，T19-a 在借连接前复核安全状态；≤5 秒撤权、多副本、丢事件与主库故障实验未做 | 后端/QA |
| G-08 | 宽行/驱动/内存 | 指定驱动、10MiB单格、Unicode/JSON/BLOB、批次/并发峰值 | PARTIAL：[T10-a](../../tasks/work-items/T10-a.md)/[T11-a](../../tasks/work-items/T11-a.md) 有 MySQL/PG 真实执行、限幅与类型投影，T19-a 覆盖 4KiB 单格与 1MiB 响应预算，[T19-b](../../tasks/work-items/T19-b.md) 补 PG 类型化结果与目录/结构闭环；10MiB 单格、内存峰值与并发批次未证明，PG 生产 TLS 未联调 | 执行器/QA |
| G-09 | 安全取消 | 在途任务与迟到取消时间线、rollback/evict、下一请求无误杀 | PARTIAL：[T10-a](../../tasks/work-items/T10-a.md) 有取消/连接淘汰测试；M0 网络延迟注入、迟到取消竞态与生产规模未执行 | 执行器/QA |
| G-10 | 审计持久性 | START前后杀进程、terminal/WAL重放、UNKNOWN、磁盘满 | PARTIAL：[T09-a](../../tasks/work-items/T09-a.md)/[T09-b](../../tasks/work-items/T09-b.md) 与 T19-a 已有加密 WAL、回放、START 不可写 503、terminal 回退；磁盘满、跨实例回放、180 天保留与独立安全评审仍缺 | 后端/运维 |
| G-11 | 元库迁移与恢复 | MySQL8.4 DDL、唯一键竞争、恢复后令牌作废 | PARTIAL：[T03-a1](../../tasks/work-items/T03-a1.md)/[T03-a2](../../tasks/work-items/T03-a2.md)/[T03-b](../../tasks/work-items/T03-b.md)/[T03-c](../../tasks/work-items/T03-c.md) 已有真实 MySQL 空库/升级/并发/回滚与 V1–V6 迁移候选；完整元库运行恢复、令牌作废和部署演练仍 NOT_RUN | 数据/运维 |
| G-12 | 私有29VQ与≥300回归 | fixture清单、参数渲染、预期、CI日志及脱敏证明 | BLOCKED：原始私有资产未提供；T18 分支的合成 509 例不是 29VQ 验收，且未合入当前基线 | 业务/QA |
| G-13 | 四客户端矩阵 | WorkBuddy/Cursor/Claude Code/VS Code实际版本与流程记录 | NOT_RUN：用户尚未提供客户端版本；不能由文档或 MCP 协议测试推断兼容 | 集成 |
| G-14 | UI/权限委派 | 可运行页面、会话/CSRF、跨部门/跨资源、自授权拒绝 | PARTIAL：[T17-ui-business](../../tasks/work-items/T17-ui-business.md) 有 18 组页面、真实会话/CSRF、隔离后端浏览器走查；委派/平台写操作/审计解密在后端仍未完整，真实 18 组全流程与人工接受 pending | 前端/QA |

原版 JSQLParser 的 6 例已经运行，其 2 个对照正确、4 个反例漏表，状态为 HISTORICAL_COMPONENT；
这不填充 G-02 的 PTMCP 实现 PASS。2026-10-26 的候选纵切证据见
[测试报告](../test/test-report.md)；M0 的正式退出仍需补齐上表 PARTIAL/NOT_RUN/BLOCKED 项。

## 构件与外部依赖冻结记录

| 项目 | 设计候选 | 冻结证据 |
| --- | --- | --- |
| Java | 21 | 待记录供应商/补丁/镜像digest |
| Boot / AI / Security | 4.1.2 / 2.0.2 / 7.1.2 | 待effective POM、树、启动和认证集成 |
| mcp-security | 0.1.14 | 待适配层契约与回归 |
| JSQLParser | 5.4 | 历史jar SHA-256在原复现；新构建需重复锁定 |
| HikariCP | 7.0.2 | 随Boot BOM，待驱动/取消验证 |
| 业务库/元库驱动 | BOM解析的实际版本 | 元库T03-a1定向记录Connector/J9.7.0、Flyway12.4.0；业务MySQL/PG与宽行/取消组合仍待冻结，不用元库或历史探针冒充业务通过 |
| RuoYi / plus-ui | 本地参考6.X / 前端待引入 | 待tag/commit、许可与构建校验 |
| MySQL / PG / Redis / Nginx | 8.4 / 18 / 待M0锁定 / 待M0锁定 | 待精确补丁和部署清单 |

## 复核用一手来源

- [Spring 7.1.2刷新生成器](https://raw.githubusercontent.com/spring-projects/spring-security/7.1.2/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/token/OAuth2RefreshTokenGenerator.java)
- [Spring授权模型与服务接口](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/core-model-components.html)
- [pgJDBC 42.7.8源码](https://raw.githubusercontent.com/pgjdbc/pgjdbc/REL42.7.8/pgjdbc/src/main/java/org/postgresql/jdbc/PgResultSet.java)
- [PostgreSQL SET LOCAL范围](https://www.postgresql.org/docs/18/sql-set.html)
- [MySQL8.4分区与唯一键](https://dev.mysql.com/doc/refman/8.4/en/partitioning-limitations-partitioning-keys-unique-keys.html)
- [MCP工具协议](https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2026-07-28/server/tools.mdx)

网页更新不自动改变本设计的冻结版本；组件升级需重跑相应门禁并更新记录。
