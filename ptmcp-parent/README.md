# PTMCP 后端

Java21 / Spring Boot4.1.2 / Maven3.9.9，版本由父POM和Wrapper固定。当前已有管理API、OAuth授权服务、MCP三工具及MySQL/PG只读问数生产接线；完整接入和运行闭环仍按工作项收尾。源码现状、OAuth流程与验证缺口见[2026-10-29能力核查](../docs/evidence/mcp-capability-audit-20261029.md)，不能把源码存在或历史构建通过视为完整上线验收。

## 模块

| 模块 | 当前内容 |
| --- | --- |
| ptmcp-common | 纯Java资源/错误/版本合同、主体上下文与有效权限判定；平台与用户权限交集、Deny优先 |
| ptmcp-guard | 词法预检、AST门禁、资源绑定、权限复核和限幅改写；未知语法拒绝，支持范围以源码/测试及backend合同为准 |
| ptmcp-persistence | 元库迁移、安全epoch/事件、组织/ACL/委派、元数据、OAuth/PAT及审计仓储和WAL恢复原语；已被应用依赖，应用无自动Flyway迁移钩子 |
| ptmcp-datasource | MySQL/PG元数据与只读执行器、TLS/目标策略、连接池代、预算与取消原语 |
| ptmcp-admin | 管理/个人门户API、Cookie会话、CSRF、组织/ACL/委派、平台/client/scope、数据源/字典、审计与凭证管理；127.0.0.1:8080 |
| ptmcp-authz | code+PKCE、refresh轮换/重放撤族、revoke、metadata/JWKS及独立登录/同意页；默认关闭，127.0.0.1:8081 |
| ptmcp-gateway | POST /mcp、PAT/JWT/Tier1 proof、三工具、权限/guard/审计/真实只读执行接线及受保护资源发现；默认关闭，127.0.0.1:8082 |

common/guard禁止Spring、若依和ORM依赖；gateway/authz禁止Sa-Token和若依依赖，Maven Enforcer检查传递依赖及收敛。admin当前使用不透明内存会话及主库撤销索引，不能把ADR0005的若依/Sa-Token选型称为已运行集成；gateway/authz已有Spring Security接线。工程不内置默认账户。

## 构建与启动

从本仓库根目录的PowerShell进入环境，然后切到本目录：

```powershell
. .\scripts\Enter-Dev.ps1
# 完整verify/install需要Docker；只启动隔离开发服务，不修改已有元库数据
.\scripts\Dev-Services.ps1 start
cd ptmcp-parent
.\mvnw.cmd --version
.\mvnw.cmd clean install

# 按需在不同终端启动；先执行install使兄弟模块进入本地仓库
.\mvnw.cmd -pl ptmcp-admin spring-boot:run
.\mvnw.cmd -pl ptmcp-authz spring-boot:run
.\mvnw.cmd -pl ptmcp-gateway spring-boot:run
```

persistence的真实MySQL测试创建专属Testcontainers容器、生成临时随机凭据并清理，不连接已有ptmcp_dev；缺Docker/TLS应失败，不跳过。Flyway使用专属location，应用尚无启动迁移钩子；不能直接把单元/集成测试配置当生产连接配置。应用需要元库、宿主身份与部署密钥等显式配置；OAuth和gateway还须启用相应开关。控制台创建PTMCP档案不等于创建宿主登录账号。

上面三个run命令分别长期占用各自终端，按Ctrl+C结束；不是在同一终端依次执行。启动后对应端口的 `/actuator/health` 只证明应用健康，不证明业务配置、鉴权和问数验收通过。服务默认只绑定本机，生产域名/TLS、真实客户端与恢复演练另行验收。

Linux/macOS使用 `sh ./mvnw`。可打包后使用 `java -jar ptmcp-admin/target/ptmcp-admin-0.1.0-SNAPSHOT.jar` 启动对应模块。

## 检查

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify
.\mvnw.cmd dependency:tree
```

validate阶段执行Spotless、Checkstyle、Enforcer，CI只检查不自动修改。根 `python scripts/check.py --all` 同时执行实际Maven隔离门禁正反例；可选 `--maven-cache-seed D:/config/ptmcp/cache/maven` 只读复制现有缓存加速，未指定也不跳过探针。稳定错误分配仅编辑config/quality/rest-error-codes.json，再在仓库根执行 `python scripts/generate_error_codes.py`；根检查拒绝生成枚举缺失/漂移，禁止手改生成物。MCP使用符号名，REST适配使用restCode业务码（不是HTTP状态），OAuth保持标准错误。

领域异常不能直接序列化Throwable；物理名称与结构只能在授权后返回，不能直接拼SQL。guard通过后仍须执行身份/版本复核、只读连接策略和审计准入。实际测试与未验证项沿工作项记录；本轮Maven被Windows→WSL Docker双向TLS故障阻断，不能引用历史测试数冒充本轮通过。尚缺的生产WAL恢复、候选配置/健康、取消/共享预算、密钥兼容和字典上下文见[收尾工作项](../tasks/work-items/DEV-20261028-gateway-completion.md)。包名前缀暂用 local.ptmcp，正式发布前由组织确认。

总体方案、接口与安全合同见[文档导航](../docs/README.md)，环境和基础服务见[环境指南](../docs/ops/development-environment.md)。
