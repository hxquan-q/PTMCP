# 本机环境安装与验证记录

日期：2026-10-23。范围：T01-env，本机开发工具和隔离基础服务；不包含 PTMCP 业务实现或生产验收。

## 安装事实

复用 D:/config 中的 JDK21.0.10、Maven3.9.9，保留 JDK8/17 和机器级配置。Node24.22.1 LTS 与 pnpm11.9.0 独立安装到 D:/config/ptmcp；进入环境后 Maven/npm/pnpm 缓存使用该目录。Python/Git 复用已有可用安装。

Node Windows ZIP SHA-256：`d2046d28c2c3dcd211ac5145c0bf30e2047ef9dfb306693c8c6e2eccd8b95fa5`。Ubuntu amd64 rootfs SHA-256：`ec6972af8ea2bd05c55299d204e5ae3f91769b84cfaec7395639d236a39f9a03`。均与官方下载的校验清单一致；安装来源/清单保留在 D:/config/ptmcp/downloads。

新建 WSL2 PTMCP-Dev（Ubuntu24.04），VHD 位于 D 盘，未修改既有 Ubuntu-26.04；安装 Docker29.1.3 与 Compose2.40.3。MySQL8.4.13、PostgreSQL18.6、Redis7.4.11 均使用官方镜像及固定 digest，见[Compose](../../config/dev/compose.yaml)。密码随机生成到本机受限目录，不写入仓库。

## 已执行检查

| 检查 | 结果与边界 |
| --- | --- |
| 进入环境，java/mvn/node/pnpm 实际版本 | PASS；Maven 实际 JDK21；重复进入环境只有一个本地仓库参数；pnpm store 在 D 盘 |
| Spring Boot4.1.2 探针：Spotless、Checkstyle、Maven package | PASS；Java21 编译/打包成功，没有跳过质量门禁；探针无单元测试，不计作业务测试通过 |
| java -jar 启动，GET /ready | HTTP200 且正文为预期；探针进程已结束，不留下应用服务 |
| Vue3.5.24/Vite7.2.2 探针 | ESLint、Prettier、vue-tsc 和构建通过；Vite HTML 和 TS 模块 HTTP200；开发探针进程已结束 |
| 三服务健康和 Windows 端口 | MySQL13306、PG15432、Redis16379 均在 loopback 可连接，容器 healthy |
| Windows Java JDBC/Redis 认证读写 | MySQL与PG创建自有合成表、显式列读写通过；PG开发用户不是超级用户；Redis AUTH/PING/SET/GET 通过 |
| 重启 Docker 与重建容器后只读核验 | 两库合成记录与 Redis 键仍存在，PASS，不通过重新插入伪造持久性 |
| Docker Windows 双向 TLS | 有客户端证书时 /_ping 返回 OK，无客户端证书被拒绝；严格 CA/KU/EKU 校验通过，未关闭证书校验 |
| Testcontainers2.0.5 Windows 探针 | 使用双向TLS连接Docker，创建并连接临时Redis、PING成功，关闭容器/JVM；不禁用Ryuk清理机制 |
| 启动/停止/再启动 | 三服务受脚本控制、数据卷保留；隐藏保活进程通过PID/创建时刻/命令归属验证，不关闭其他发行版 |
| 本机自检与仓库检查 | 使用 scripts/Test-DevEnvironment.ps1 和 python scripts/check.py；具体完成结果回写 T01-env |

探针源码/锁文件/构建产物在 `D:/config/ptmcp/smoke`；日志在 `D:/config/ptmcp/logs`。JDBC 驱动由探针 Boot BOM 解析为 MySQL9.7.0、PG42.7.14；这不是对产品驱动/BOM已完成M0兼容验收的声明。

## 排障与修正

- PATH 中 java21 与机器 JAVA_HOME8 不一致：使用项目会话入口，保留机器级默认。
- pnpm11 不接受给 run 命令直接传 --store-dir：项目 shim 使用 --config.store-dir，安装、store、脚本执行均实测。
- PowerShell 的 Maven -D 点号参数需要整体引用：class path 探针使用完整引号，避免被当成生命周期阶段。
- WSL 的 systemd 服务不足以维持宿主会话：由 start/stop 管理专用隐藏保活进程；停止容器后发行版可由 WSL 自行回收，不承诺立即退出。
- Python严格TLS检查发现初版CA缺少keyUsage：补全CA及叶证书的BasicConstraints/KU/EKU并重签，严格校验和有/无客户端证书测试均通过；没有降级TLS。
- Testcontainers首次连接Ryuk端口经历WSL转发就绪重试，最终创建/连接/清理通过，不把暂时拒绝直接记为最终失败。

## 状态与限制

当前交付是本机环境、配置与管理脚本，PTMCP 根 POM、Wrapper、产品 UI 和三个业务应用仍未创建。完整安全/负载/故障、客户端OAuth、生产容量和私有评测未执行；环境探针不能替代它们。作者自审，无独立评审，无提交、推送或生产部署。

使用方式与凭据位置见[开发环境指南](../ops/development-environment.md)，执行记录见[T01-env](../../tasks/work-items/T01-env.md)。
