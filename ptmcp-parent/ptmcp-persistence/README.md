# PTMCP 元库持久化基础

此模块只面向MySQL元数据库，不是业务SQL执行器。当前交付两部分：T03-a1 的 Flyway V1 迁移资源与真实 MySQL 集成测试（sys_security_state、sys_security_event、security_consumer_cursor）；T03-a2 的包内安全变更协调器，把受信仓储行变更、epoch 递增与单条受限事件放进同一主库短事务。

协调器（`SecurityChangeTransaction`）不暴露 HTTP、公共原始 SQL 或公共 Connection 入口，只接受包内受信 JDBC 回调：先 `sys_security_state` 单行 `FOR UPDATE`，再回调按 §7 锁序改行，最后 epoch+1 与事件插入，只有在 commit 确认后才返回 receipt。提交异常返回 `OPERATION_UNKNOWN` 且不自动重试；已确认提交后的 close 失败仍返回 receipt 并标 `cleanupFailed`。回调是受信代码契约，不是沙箱：[T03-a2](../../tasks/work-items/T03-a2.md) 明确后续仓储须逐个证明版本校验与子锁序，UNKNOWN 核验与告警接线仍在调用层。

没有组织/令牌/ACL/审计完整模型、生产仓储或应用启动迁移入口，不能据此宣称完整T03或撤销时效通过。

## 迁移边界

资源位于 `src/main/resources/db/ptmcp-migration`，有意不使用Boot默认的db/migration。admin/authz/gateway当前未增加本模块依赖或数据库配置，不会因为框架启动便连接或迁移数据库。未来受控迁移入口必须显式选择该location、拒绝自动baseline未知非空库、禁用clean并使用独立迁移账号；本轮只在测试中显式配置和验证这些条件。

V1三表均InnoDB/utf8mb4、有显式主键、UTC DATETIME(6) create_time/update_time，无ON UPDATE；唯一系统种子为singleton_id=1、policy_epoch=1，没有PTMCP账号/默认密码。事件UUID/正epoch、JSON对象、非负attempts和消费游标有实库CHECK/唯一键验证；payload只允许ID/版本的内容安全及epoch写事务须后续生产者/仓储验证，不能仅凭JSON对象约束宣称通过。

Flyway自有schema history为第三方迁移工具元数据，不冒称PTMCP领域表。单一V1是版本发布单元，**不代表MySQL DDL可事务回滚**。不提供破坏性down/自动清库；生产迁移失败、升级、备份恢复和N/N-1兼容仍未验收。

## 实库验证

先在仓库根的Windows PowerShell加载环境并启动专属开发Docker，再从实际父工程运行Wrapper：

```powershell
. .\scripts\Enter-Dev.ps1
.\scripts\Dev-Services.ps1 start
cd ptmcp-parent
.\mvnw.cmd -B -ntp -pl ptmcp-persistence -am verify
```

测试用固定digest的MySQL8.4镜像创建独立一次性Testcontainers容器，随机凭据只在进程内，不连接已有ptmcp_dev/业务库或读取本机秘密；缺Docker/TLS将失败而非skip。SSL关闭/公开钥检索仅用于此隔离合成容器，不是生产TLS或业务数据源配置示例。

覆盖空库与重复迁移、主键/时间/命名/索引/CHECK、有符号边界、事件唯一键竞争、三表DML整体rollback、clean禁用与未知非空库拒绝。全部查询显式选择列，COUNT(*)只作计数。测试不是SQL guard或身份认证验收。

实际版本由父Boot BOM/POM与构件树确定，当前定向证据记录Flyway12.4.0、Connector/J9.7.0、Testcontainers2.0.5、MySQL8.4.13；不把元库结果当业务驱动宽行/取消/只读执行通过。实际命令、失败/成功、独立评审与集成结果见[T03-a1](../../tasks/work-items/T03-a1.md)，全契约见[数据库设计](../../docs/backend/db-design.md)。
