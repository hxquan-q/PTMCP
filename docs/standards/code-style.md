# 开发与代码规范

版本：1.3-bootstrap · 2026-10-23；适用于当前实现与后续维护。业务已按工作项分片实现和验证，完整产品验收不能由构建或局部测试推导。

Java 语义与 MySQL 开发采用 [Alibaba p3c](https://github.com/alibaba/p3c)；React/TypeScript 的约束与实际自动化配置见[前端指南](../frontend/dev-guide.md)和[工程标准与门禁](quality-tooling.md)。格式由 Spotless/google-java-format 和 Prettier 判定，评审聚焦语义、安全、兼容，不人工裁定排版。

## 1. 语言、目录与依赖

后端 Java 21、UTF-8，由 google-java-format AOSP 统一四空格排版；当前前端为 React/TypeScript strict，由已配置的 ESLint 规则校验、Prettier 统一格式。包前缀在 M0 由组织确定，文档样例不绑定未经确认的域名。目录与依赖遵循[整体架构](../architecture/architecture.md)及[ADR0005](../architecture/adr/0005-隔离后台与网关依赖.md)。

common不引用Spring/ORM/若依；guard纯Java且只采用锁定的JSQLParser；datasource不依赖guard反向实现。适配器/控制器负责编排和边界转换，领域逻辑不从HTTP线程上下文偷偷取用户。跨模块对象用明确值类型，不传无约束Map、Object或任意SQL字符串。

管理新API的JSON字段camelCase，MCP工具参数snake_case；Java/TS模型通过边界适配转换，禁止在同一接口随意混用。数据库snake_case，API ID一律字符串。时间持久化UTC，显示Asia/Taipei，不用本机默认时区参与授权判断。

## 2. Java 风格与示例

类/接口UpperCamelCase，方法/字段lowerCamelCase，常量UPPER_SNAKE_CASE。使用不可变领域值、显式构造参数与构造器注入；不把实体直接序列化为API响应。校验异常转换成稳定错误码，不把数据库异常栈泄露给客户端。

```java
public record ResourceKey(
        String datasourceId,
        String catalog,
        String schema,
        String objectName) {
    public ResourceKey {
        Objects.requireNonNull(datasourceId, "datasourceId");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(objectName, "objectName");
    }
}
```

该例只展示不可变值风格；名字折叠/引号/对象版本由专门的命名空间模型处理，不在通用构造器里粗暴toLowerCase。数据库元信息是受验证输入，不能由用户传catalog字符串后直接拼接SQL。

ApprovedQuery只能由guard内部审批流程创建，不提供供controller绕过的公共raw-sql执行入口。支持AST节点采用显式分派，未知节点抛拒绝；禁止default分支悄悄当安全节点。注释说明方言、并发和安全不变量的原因，不复述代码。

## 3. API、事务与幂等

控制器执行认证/委托范围、schema校验、请求体上限与错误转换；服务层实现事务和业务状态机；仓储层访问元库。后台业务表读写参数化，任意SQL执行只允许guard批准的业务查询路径。

自有 REST 资源使用名词路径与 HTTP 方法表达动作，成功/错误统一若依 `R<T>`，HTTP 状态和模块业务码分开；OAuth/MCP 保持标准协议。数字业务码唯一登记在[错误码表](../../config/quality/rest-error-codes.json)，错误映射见[API契约](../backend/api-spec.md)。

所有 PTMCP 元库表有显式主键及 create_time/update_time；表/字段小写 snake_case。项目编写的 SQL 显式选择列，禁止 SELECT * / t.*，允许 COUNT(*)；外部 SQL 的受控星号展开继续按 guard 合同验证。完整例外与 SQL 检查边界见[工程标准](quality-tooling.md)。

授权/用户状态/版本与sys_security_event同事务；refresh消费/新token/授权同事务。幂等键由业务意图产生，一次原子唯一键占用；同键不同payload拒绝，处理中重复返回已定义冲突，不先SELECT再INSERT制造竞态。SQL执行不自动重试，取消或网络失败视情况保留UNKNOWN。

迁移采用版本化DDL，先expand后contract；禁止启动时自动清库、覆盖参考脚本或不可逆修改。MySQL唯一键、NULL、字符序与锁竞争必须实库验证，不用H2结果冒充生产方言行为。

## 4. 并发与资源清理

请求取消、SQL超时、驱动取消、执行任务终止与连接归还使用明确状态机。关闭Statement/ResultSet、等待取消任务、rollback/恢复、淘汰顺序不得被try-finally中的默认close掩盖。清理中的连接仍占并发额度。

不在普通线程池运行无界解析/无限队列；任务必须有预算和可观察结果。缓存条目带权威确认时间与版本，不能在层层get时刷新有效期。使用单调时钟测耗时、可信UTC时间做授权边界，测试注入Clock而非实际sleep长等待。

## 5. 前端与安全展示

前端详见[开发指南](../frontend/dev-guide.md)。组件按业务域分组；基础组件不读取权限树或调用业务API。异步请求需要取消与竞态保护，旧请求不能覆盖新筛选状态。加载、空、错误、403、过期、截断均有明确页面状态。

所有表名、注释、SQL和数据库文本默认转义展示；禁止未经净化的innerHTML。会话Cookie由服务端管理，前端不存PAT、refresh、数据库密码到localStorage；一次性凭证展示退出后清空内存。菜单隐藏不是API权限校验。

## 6. 日志、错误与数据

错误码可被依赖，定义在公共契约并有兼容策略。日志只记录request_id、错误分类、对象标识的允许披露部分、规则版本和阶段耗时。禁止日志输出Authorization、Cookie、secret、连接密码、原始结果、未经脱敏SQL。解析失败不能靠不完整正则假装安全脱敏。

DECIMAL/BIGINT以字符串传输；重复列名用列序号/稳定列ID区分，不用JSON对象键覆盖。NULL与截断必须分开，不能将截断字符串写成真实数据库值。二进制编码及序列化膨胀计入1MiB预算。

## 7. 检查工具与命令

Java 门禁采用 Spotless 3.1.0 + google-java-format 1.28.0 AOSP、Checkstyle 插件 3.6.0 / 引擎 10.26.1；当前 React 前端按工程内 ESLint、TypeScript 和 Prettier 配置检查，不沿用已移除工程的 Vue/Props 规则。版本与来源见[工程标准](quality-tooling.md)及实际工程配置。锁定 Wrapper、Node 与包管理器/锁文件，不引入冲突 lockfile。依赖安装脚本先禁用/审查，不能整体放行未知脚本。

工程命令（先进入开发环境，在对应工程目录执行；局部测试通过不等于完整业务通过）：

```powershell
# 工作目录：ptmcp-parent
.\mvnw.cmd -pl ptmcp-guard -am test
.\mvnw.cmd -pl ptmcp-gateway -am verify
.\mvnw.cmd help:effective-pom
.\mvnw.cmd dependency:tree
.\mvnw.cmd validate
# 工作目录：ptmcp-ui-react；版本以package/锁文件为准
pnpm lint
pnpm format:check
pnpm typecheck
pnpm build
pnpm test
```

当前统一检查命令为 `python scripts/check.py`，涵盖文档结构、Agent 工作合同、工具模板/错误码登记和检查器回归；分项 `python docs/evidence/validate_docs.py` 仍可用。结果记录在[文档验证报告](../evidence/documentation-validation.md)。这些检查不替代应用与安全测试。

Agent 的任务准入、风险分级、实施与交付遵循[开发流程](agent-workflow.md)；决策因果按[笔记政策](decision-notes.md)存放。`python scripts/check.py --all` 的实际执行范围以当前脚本为准；本次变更未运行该全量门禁，不能将脚本配置等同于 React 或后端已验证。

## 8. Definition of Done

- 需求FR、设计决策、测试TC和实现变更可追溯；接口/Schema变更同步文档。
- 相关单元、双方言集成、契约及故障用例通过；无跳过安全测试、降低阈值或未知节点静默放行。
- formatter/lint/typecheck、依赖收敛及已启用的静态检查通过；不提交凭证或内部私有夹具。
- 安全关键路径经过独立评审，更新[ASVS 自查](../test/asvs-checklist.md)的受影响条款/证据与差距；性能/内存结论给出环境、样本、版本和原始证据。
- 新功能的日志、运维、迁移/回滚路径具备，不以“先上线后补”代替验收。

更改质量阈值必须在同一变更解释原因、影响和新证据；不能通过删除断言、test.skip、全局忽略或改规则版本伪造通过。
