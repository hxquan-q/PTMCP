# PTMCP 后端框架

Java21 / Spring Boot4.1.2 / Maven3.9.9，版本由父POM和Wrapper固定。当前为基础框架及逐片实施的纯Java契约：已增加ResourceId与单元测试、生成的稳定错误枚举，仍没有业务控制器、认证、数据库迁移或SQL执行实现。

## 模块

| 模块 | 当前内容 |
| --- | --- |
| ptmcp-common | 纯Java资源ID值契约；由唯一登记源生成的ErrorCode符号/REST业务码枚举；其余领域合同待实现 |
| ptmcp-guard | 纯Java模块，锁定JSQLParser5.4；守门算法未实现 |
| ptmcp-admin | 管理后台启动应用，127.0.0.1:8080 |
| ptmcp-authz | 授权服务启动应用，127.0.0.1:8081；OAuth未实现 |
| ptmcp-gateway | 网关启动应用，127.0.0.1:8082；MCP工具未实现 |

common/guard禁止Spring、若依和ORM依赖；gateway/authz禁止Sa-Token和若依依赖，Maven Enforcer检查传递依赖及收敛。后续按ADR0005分别接入admin的若依/Sa-Token和gateway/authz的Spring Security；本次不生成临时认证链或默认账户。

## 构建与启动

从本仓库根目录的PowerShell进入环境，然后切到本目录：

```powershell
. .\scripts\Enter-Dev.ps1
cd ptmcp-parent
.\mvnw.cmd --version
.\mvnw.cmd clean install

# 按需在不同终端启动；先执行install使兄弟模块进入本地仓库
.\mvnw.cmd -pl ptmcp-admin spring-boot:run
.\mvnw.cmd -pl ptmcp-authz spring-boot:run
.\mvnw.cmd -pl ptmcp-gateway spring-boot:run
```

上面三个run命令分别长期占用各自终端，按Ctrl+C结束；不是在同一终端依次执行。启动后对应端口的 `/actuator/health` 返回框架健康状态。不存在的业务地址返回404，不把启动成功等同鉴权、MCP或SQL能力可用。服务只绑定本机，当前骨架不作为生产部署。

Linux/macOS使用 `sh ./mvnw`。可打包后使用 `java -jar ptmcp-admin/target/ptmcp-admin-0.1.0-SNAPSHOT.jar` 启动对应模块。

## 检查

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify
.\mvnw.cmd dependency:tree
```

validate阶段执行Spotless、Checkstyle、Enforcer，CI只检查不自动修改。稳定错误分配仅编辑config/quality/rest-error-codes.json，再在仓库根执行 `python scripts/generate_error_codes.py`；根检查拒绝生成枚举缺失/漂移，禁止手改生成物。MCP使用符号名，REST适配使用restCode业务码（不是HTTP状态），OAuth保持标准错误。已增加ResourceId/ErrorCode纯单元测试及三应用随机端口HTTP启动回归；其余业务/安全测试仍按切片补齐。值对象单测不等于HTTP序列化、身份认证或业务流程通过，不能用框架构建成功证明这些能力完成。包名前缀暂用 local.ptmcp，正式发布前由组织确认。

总体方案、接口与安全合同见[文档导航](../docs/README.md)，环境和基础服务见[环境指南](../docs/ops/development-environment.md)。
