# M0 验证门禁与证据登记

版本：1.0-design · 2026-10-23 · 以下M0产品证据当前均未完成。

| ID | 验证项 | 预期材料 | 当前状态/阻塞 | 责任角色 |
| --- | --- | --- | --- | --- |
| G-01 | 依赖收敛与三进程启动 | effective POM/tree、锁定依赖、自动配置/启动日志 | PARTIAL：框架effective POM/tree见[框架证据](framework-bootstrap-validation.md)，新增[三应用HTTP启动回归](../../tasks/work-items/T01-a.md)已组合verify；业务/安全依赖接入后仍须重验，T01-b正反例未完成 | 后端 |
| G-02 | 关系/列作用域绑定 | 六例各自集合、列限定/相关子查询/双源同名fixture | NOT_RUN：仅有原版组件缺陷证据 | 内核 |
| G-03 | SQL改写等价 | 原SQL/final_sql、列序/类型/结果比较与截断转换预期 | NOT_RUN：guard与测试库未建立 | 内核/QA |
| G-04 | 单MySQL+PAT+ACL纵切 | 允许/拒绝、静态拒绝0业务连接、持久START/terminal | NOT_RUN：实现未建立 | 后端/QA |
| G-05 | 公开OAuth完整链 | NONE+S256真实客户端、全部converter/provider/轮换/重放日志 | NOT_RUN：AS与客户端环境未建立 | 认证/安全 |
| G-06 | Tier1平台证明 | token端点认证与每次MCP请求证明、伪造/冲突/撤销 | NOT_RUN：受信代理/mTLS未建立 | 认证/运维 |
| G-07 | 权限时效 | 主库提交时间、各副本拒绝时间、丢通知/过期/主库故障 | NOT_RUN：状态缓存未实现 | 后端/QA |
| G-08 | 宽行/驱动/内存 | 指定驱动、10MiB单格、Unicode/JSON/BLOB、批次/并发峰值 | NOT_RUN：输出限幅与测试库未建立 | 执行器/QA |
| G-09 | 安全取消 | 在途任务与迟到取消时间线、rollback/evict、下一请求无误杀 | NOT_RUN：执行状态机未实现 | 执行器/QA |
| G-10 | 审计持久性 | START前后杀进程、terminal/WAL重放、UNKNOWN、磁盘满 | NOT_RUN：审计通道未实现 | 后端/运维 |
| G-11 | 元库迁移与恢复 | MySQL8.4 DDL、唯一键竞争、恢复后令牌作废 | NOT_RUN：迁移与部署产物未建立 | 数据/运维 |
| G-12 | 私有29VQ与≥300回归 | fixture清单、参数渲染、预期、CI日志及脱敏证明 | BLOCKED：原始私有资产未提供 | 业务/QA |
| G-13 | 四客户端矩阵 | WorkBuddy/Cursor/Claude Code/VS Code实际版本与流程记录 | NOT_RUN：不能由文档推断兼容 | 集成 |
| G-14 | UI/权限委派 | 可运行页面、会话/CSRF、跨部门/跨资源、自授权拒绝 | NOT_RUN：UI/管理API未实现 | 前端/QA |

原版JSQLParser的6例已经运行，其2个对照正确、4个反例漏表，状态为HISTORICAL_COMPONENT；这不填充G-02的PTMCP实现PASS。

## 构件与外部依赖冻结记录

| 项目 | 设计候选 | 冻结证据 |
| --- | --- | --- |
| Java | 21 | 待记录供应商/补丁/镜像digest |
| Boot / AI / Security | 4.1.2 / 2.0.2 / 7.1.2 | 待effective POM、树、启动和认证集成 |
| mcp-security | 0.1.14 | 待适配层契约与回归 |
| JSQLParser | 5.4 | 历史jar SHA-256在原复现；新构建需重复锁定 |
| HikariCP | 7.0.2 | 随Boot BOM，待驱动/取消验证 |
| 业务库/元库驱动 | BOM解析的实际版本 | 待依赖锁定，不用历史pgJDBC探针版本冒充生产版本 |
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
