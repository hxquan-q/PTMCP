# PTMCP 原生部署包（Linux / Windows）

不依赖 Docker 的单机部署：三个 Spring Boot 进程 + nginx 同源入口 + 现有 MySQL。
目录约定、配置外置、预检与进程归属校验沿用团队既有部署包
（`E:/industrial_plat-ai/deploy`，2026-11-04 版）的做法，按 PTMCP 的
三进程结构与 `PTMCP_*` 环境变量改造。

> 容器部署见 [`../README.md`](../README.md)。两条路径共用同一套变量映射、
> nginx 路由和 Flyway 迁移目录，可按服务器条件二选一。

## 选择哪种方式

| 场景 | 建议 |
| --- | --- |
| Ubuntu/Debian，允许装 Docker | Compose（`deploy/compose.yaml`），一条命令起全套含 MySQL |
| Windows Server，不想装 Docker Desktop | 本目录 Windows 包 |
| 已有 MySQL、只想跑应用与入口 | 本目录对应平台包 |

## 共同设计

- **布局**：`releases/<version>/` + `shared/config/app.env` + `current`；
  配置与密钥外置，升级不覆盖。运行期产物在 `run/`、`logs/`、`temp/`。
- **配置**：单一 `app.env`，启动脚本按应用展开为 `PTMCP_ADMIN_*` /
  `PTMCP_AUTHZ_*` / `PTMCP_GATEWAY_*`，对应相同的应用属性；Compose 另有自动账号分权步骤，
  原生包仍需 DBA 配置外部元库权限，不包含该步骤。
- **端口**：nginx 对外 `8080`；admin/authz/gateway 分别 `18080/18081/18082`，
  只监听 `127.0.0.1`。
- **归属**：启停只操作本包启动记录中的进程（PID + 启动时间 + 命令行标记），
  不按进程名杀 Java/nginx。
- **迁移**：Flyway CLI 或 Docker 固定镜像二选一；不引入自定义迁移账本。
- **密钥**：首次安装在本机生成（`openssl` / .NET RNG），不从网络下载。

## 快速开始

Linux：

```bash
tar -xzf ptmcp-<version>-linux.tar.gz && cd ptmcp-<version>-linux
./install.sh http://localhost:8080     # 生成配置后退出
vi shared/config/app.env            # 填元库连接
./install.sh                        # 预检 → 迁移 → 启动
```

Windows：

```cmd
0-install.cmd http://localhost:8080
notepad shared\config\app.env
0-install.cmd
```

详细说明：[Linux](linux/docs/DEPLOY-LINUX.md)、[Windows](windows/docs/DEPLOY-WINDOWS.md)。

## 从这里构建发布包

```bash
deploy/native/build-release.sh                  # Maven + pnpm 构建后打包
deploy/native/build-release.sh --skip-build     # 使用已有产物
```

产物在 `deploy/native/dist/`：

- `ptmcp-<version>-linux.tar.gz`
- `ptmcp-<version>-windows.zip`

## 目录

```
deploy/native/
├── build-release.sh
├── linux/
│   ├── install.sh
│   ├── nginx/{nginx.conf.template,mime.types}
│   ├── scripts/{lib.sh,platform.sh,check-prereq.sh,migrate.sh,init-env.sh,app.env.example}
│   └── docs/DEPLOY-LINUX.md
└── windows/
    ├── 0-install.cmd  1-start.cmd  2-stop.cmd  3-status.cmd  4-migrate.cmd  5-check.cmd
    ├── platform.ps1  check-prereq.ps1  migrate.ps1  init-env.ps1  Ptmcp.Layout.ps1
    ├── nginx/{nginx.conf.template,mime.types}
    ├── scripts/{Platform.Runtime.psm1,app.env.example}
    └── docs/DEPLOY-WINDOWS.md
```

## 当前限制

- 管理员账号播种、数据库自动发现、依赖自动安装尚未实现（后续切片）。
- 未提供 systemd/Windows 服务注册、自动升级与回滚。
- 仅 HTTP 示例；HTTPS 需自行终止 TLS 并调整基址与 Cookie 标志。

OAuth 基址必须是无路径、凭证、query 和 fragment 的 HTTPS URL，或主机严格为
`localhost`、`127.0.0.1`、`[::1]` 的 HTTP URL；不接受局域网 HTTP。
直接 HTTP 的监听端口由基址推导（省略端口为 80）；HTTPS 基址需要外部 TLS 入口，
生成的 nginx 内部 HTTP 监听仍为 8080，外部端口由 TLS 入口发布。
业务端口白名单未配置时为 `3306,5432`，显式空值拒绝全部；CIDR 空值也拒绝全部。
