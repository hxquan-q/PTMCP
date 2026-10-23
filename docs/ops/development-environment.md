# 本机开发环境

配置日期：2026-10-23。Windows工具与运行数据位于D:/config，新增环境集中于D:/config/ptmcp。正式框架已在本仓库根目录的[ptmcp-parent](../../ptmcp-parent/README.md)和[ptmcp-ui](../../ptmcp-ui/README.md)创建；D盘smoke仍只是环境探针，不是项目源码。

## 已安装与复用

| 工具/服务 | 版本 | 位置或连接 |
| --- | --- | --- |
| JDK | 21.0.10 | 复用 `D:/config/jdk/jdk21` |
| Maven | 3.9.9 | 复用 `D:/config/apache-maven-3.9.9` |
| Node.js LTS | 24.22.1 | `D:/config/ptmcp/node-v24.22.1-win-x64` |
| pnpm | 11.9.0 | `D:/config/ptmcp/pnpm-11.9.0`；项目 shim 固定 D 盘 store |
| Git / Python | 复用现有可用安装 | Python 用于仓库检查；不重新安装或替换 |
| Docker / Compose | 29.1.3 / 2.40.3 | 独立 WSL2 发行版 `PTMCP-Dev`，Ubuntu 24.04 LTS |
| MySQL | 8.4.13 | `127.0.0.1:13306`，库/用户 `ptmcp_dev` |
| PostgreSQL | 18.6 | `127.0.0.1:15432`，库/用户 `ptmcp_dev`，非超级用户 |
| Redis | 7.4.11 | `127.0.0.1:16379`，DB 0，有密码 |

镜像在[Compose 文件](../../config/dev/compose.yaml)中使用实际拉取的不可变 digest，不依赖后续同名 tag 的变化。以上补丁号是本次实际安装结果，不是对未验证组合的兼容承诺。

没有修改机器级 JAVA_HOME/PATH，也未替换旧 JDK8/17、全局 Node26/pnpm 或现有 Ubuntu-26.04。原系统 JAVA_HOME 指向8，故每次本机构建前先选择项目环境；IDE 的项目 JDK 同样应指向21。

## 每次开发

在本仓库根目录的 PowerShell 中执行：

```powershell
# 只影响当前终端及其子进程
. .\scripts\Enter-Dev.ps1

# 后台启动数据库/缓存；已启动时可重复执行
.\scripts\Dev-Services.ps1 start

# 检查实际工具、D盘store、服务健康/端口、Windows Docker双向TLS
.\scripts\Test-DevEnvironment.ps1
```

同一终端中的 `java`、`mvn`、`node`、`pnpm` 选择上述工具，Maven/pnpm/npm 缓存集中到 `D:/config/ptmcp/cache`。重复进入环境不会重复累加 Maven 仓库参数；关闭终端即结束本终端的工具选择。Maven Wrapper 建立后仍继承 JAVA_HOME 和本地仓库参数。

`D:/config/ptmcp/Enter-Dev.ps1`、`Dev-Services.ps1` 和 `Test-DevEnvironment.ps1` 是从仓库源脚本复制的本机安装产物，便于不进入仓库时使用；维护以仓库 `scripts/` 为源。

## 服务管理与数据

```powershell
.\scripts\Dev-Services.ps1 status
.\scripts\Dev-Services.ps1 stop
.\scripts\Dev-Services.ps1 start
```

start 创建隐藏的 WSL 保活进程，启动容器并等待健康；记录 Windows PID 和创建时刻，重复调用复用同一进程。stop 只停止本环境的三个容器与验证归属的保活进程，保留数据卷；不执行 down -v，不调用全局 WSL shutdown，不停止原有 Ubuntu。WSL 的 systemd 服务本身不能保证发行版持续运行，所以不能只依赖 docker 的 restart 策略。

停止后发行版可能暂时仍显示 Running，由 WSL 自行回收；判断数据库是否停止应看容器状态，不为追求立即退出而强制关闭整个 WSL。

发行版磁盘位于 `D:/config/ptmcp/wsl/PTMCP-Dev/ext4.vhdx`，Docker 镜像和三个数据库卷均在该磁盘中。数据不直接存到 NTFS bind mount，保留 Linux 文件系统语义；stop/start、Docker 重启和容器重建均验证过数据保留。该磁盘、卷和凭据目录属于开发资产，清理前先备份，不用卸载/重建代替常规启动。

Linux 侧实际 Compose/秘密位于 `/opt/ptmcp`，通过 WSL 管理；Windows 的 `docker` 命令是项目 shim，可查询 Linux Docker。默认不安装 Docker Desktop，也不注册 Windows 开机自动启动。需要使用服务时运行 start。

## 凭据和 Windows 集成测试

随机密码位于本机 `D:/config/ptmcp/secrets/development.json`，JDBC 探针使用同目录的 `jdbc.properties`。Windows 目录 ACL 仅授权当前用户与 SYSTEM；Linux 秘密目录仅 root 可遍历。密码、私钥和实际本机配置均不进入仓库，不在终端输出。应用的本地配置应从安全的本机入口读取，不把示例密码硬编码为部署默认值。

项目环境设置 `DOCKER_HOST=tcp://127.0.0.1:12376`、`DOCKER_TLS_VERIFY=1` 与本机 `DOCKER_CERT_PATH`。Docker 接口只绑定 loopback，校验服务端与客户端证书；没有客户端证书时拒绝连接，未开启无认证的2375端口。这样 Windows Java/Testcontainers 可以连接 WSL Docker，已实际启动 Redis 测试容器、连接 PING 并执行清理。

Docker 私有 CA 在专用发行版，客户端材料在 `D:/config/ptmcp/secrets/docker-tls`，一年有效；到期需重新签发并更新本机材料，不能通过关闭 TLS 校验处理。证书不导入 Windows 全局信任库。

## 验证范围与后续

[环境验证记录](../evidence/development-environment-validation.md)保留真实构建、HTTP、JDBC/Redis、TLS、Testcontainers 和持久性结果。探针在 `D:/config/ptmcp/smoke`，运行日志在 `logs`，Spring Boot/Vite 探针服务验证后已停止。没有产品安全认证、业务迁移、私有评测或生产部署的通过结论。

T01-env完成环境；T01-bootstrap补齐根目录前后端框架、Wrapper与质量门禁。当前按用户要求不进行业务编码；后续实现单独续接任务，不把探针拷贝成绕过guard的查询服务。

来源：[Node 官方发行与校验](https://nodejs.org/dist/v24.22.1/)、[WSL 导入命令](https://learn.microsoft.com/en-us/windows/wsl/basic-commands#import-a-distribution)、[Ubuntu 官方 rootfs](https://cloud-images.ubuntu.com/noble/current/)、[Docker TLS](https://docs.docker.com/engine/security/protect-access/)、[MySQL](https://hub.docker.com/_/mysql)、[PostgreSQL](https://hub.docker.com/_/postgres)、[Redis](https://hub.docker.com/_/redis)。
