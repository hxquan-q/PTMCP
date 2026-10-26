# Linux 开发服务器

本页说明如何在当前 Linux 服务器上用 `scripts/Dev-Linux.sh` 启动 PTMCP 的本地开发环境。它只管理专用的 `ptmcp-dev` Compose 项目和本机开发凭据，不是生产部署流程。

## 前置条件

| 工具 | 要求 | 当前服务器 |
| --- | --- | --- |
| JDK | 21 | `/usr/lib/jvm/java-21-openjdk-amd64` |
| Node.js | 24.x | 已安装 |
| pnpm | 11.x | 已安装；仓库锁定 11.9.0 |
| Docker + Compose | 可用，当前用户在 `docker` 组 | 已安装 |
| Python / OpenSSL / curl | 仅本地脚本使用 | 已安装 |

如果 JDK 21 缺失：

```bash
sudo apt-get install -y openjdk-21-jdk-headless
```

## 首次启动

```bash
cd /home/ubuntu/ALLSTAR_DATACHAT_SUB
./scripts/Dev-Linux.sh doctor
./scripts/Dev-Linux.sh up
./scripts/Dev-Linux.sh build
./scripts/Dev-Linux.sh start
```

`up` 默认只启动 MySQL 8.4（端口 `127.0.0.1:13306`），并完成：

1. 在 `config/dev/secrets/` 生成随机数据库凭据（已 gitignore）；
2. 在 `.local/dev/` 生成本地应用配置、OAuth RSA 私钥和管理员密码；
3. 启动 `config/dev/compose.yaml` 中固定 digest 的 MySQL 8.4；
4. 按顺序执行 `V1`–`V6` 元库迁移；
5. 创建本地管理员 `admin`，密码只写入 `.local/dev/admin-credentials.txt`（0600）。

需要 PostgreSQL/Redis 时：

```bash
./scripts/Dev-Linux.sh up --with-pg
./scripts/Dev-Linux.sh up --with-redis
./scripts/Dev-Linux.sh up --all
```

如果固定 digest 的 MySQL 8.4 镜像在当前服务器拉取失败，可以临时用本机已有的 8.0 镜像跑通开发链路：

```bash
PTMCP_MYSQL_IMAGE=mysql:8.0 ./scripts/Dev-Linux.sh up
```

这不是目标基线。最终仍应切回 `config/dev/compose.yaml` 中固定的 8.4 digest；切换前执行 `./scripts/Dev-Linux.sh reset --yes`，再不带环境变量运行 `up`。也可以把 `PTMCP_MYSQL_IMAGE='mysql:8.0'` 临时写入 `.local/dev/dev.env`（该文件已 gitignore），验证完成后删除该行。

## 访问

- 前端：`http://127.0.0.1:5173`
- admin：`http://127.0.0.1:8080`
- authz：`http://127.0.0.1:8081`
- gateway：`http://127.0.0.1:8082`

查看管理员密码：

```bash
cat .local/dev/admin-credentials.txt
```

Vite 默认只绑定 `127.0.0.1`。从其他机器访问时优先使用 SSH 隧道，只需转发 5173；前端代理会在服务器上访问三个后端：

```bash
ssh -L 5173:127.0.0.1:5173 <user>@<server>
```

## 临时公网访问

需要临时从公网打开开发页面时：

```bash
# 1. 允许 Vite 绑定所有网卡
#    在 .local/dev/dev.env 设置 PTMCP_UI_PUBLIC='true'，或启动时传入环境变量
PTMCP_UI_PUBLIC='true' ./scripts/Dev-Linux.sh start

# 2. 放行主机防火墙
sudo ufw allow 5173/tcp

# 3. 如云服务器启用了安全组，还需在云控制台放行 5173/tcp
# 4. 访问 http://<公网IP>:5173
```

也可以直接手工运行 `cd ptmcp-ui && pnpm dev:public`。

**安全边界**：Vite 是开发服务器，当前链路由 HTTP 承载，管理员密码和会话 Cookie 会明文传输。不要用于生产、真实业务数据或长期暴露；建议在云安全组限制来源 IP，或改用 SSH 隧道。恢复默认时把 `PTMCP_UI_PUBLIC` 设为 `false` 并重启 UI，然后执行 `sudo ufw delete allow 5173/tcp`。

## 常用命令

```bash
./scripts/Dev-Linux.sh status          # 容器和健康状态
./scripts/Dev-Linux.sh admin           # 前台运行 admin
./scripts/Dev-Linux.sh authz           # 前台运行 authz
./scripts/Dev-Linux.sh gateway         # 前台运行 gateway（MCP 默认关闭）
./scripts/Dev-Linux.sh ui              # 前台运行 Vite
./scripts/Dev-Linux.sh logs admin      # 跟随后台日志
./scripts/Dev-Linux.sh logs infra      # 跟随 Compose 日志
./scripts/Dev-Linux.sh stop            # 停止后台应用
./scripts/Dev-Linux.sh down            # 停应用并移除容器，保留数据卷
./scripts/Dev-Linux.sh reset --yes     # 移除容器和数据卷，重新初始化
```

`start` 会在后台启动 admin、authz 和前端，日志与 PID 位于 `.local/dev/run/`。`reset --yes` 会删除专用数据库卷和新管理员密码；它不会删除 `config/dev/secrets/` 与 `.local/dev/dev.env`。

前端检查和构建必须在 `ptmcp-ui` 目录内执行，这样 corepack 才会按 `package.json` 使用 pnpm 11.9.0：

```bash
cd ptmcp-ui
pnpm check
```

## 迁移与种子数据

- 迁移文件仍以 `ptmcp-parent/ptmcp-persistence/src/main/resources/db/ptmcp-migration/V*.sql` 为唯一来源。
- 本地脚本使用 `ptmcp_dev_schema_migration` 记录版本和 SHA-256 校验和；它不是 Flyway 的 `flyway_schema_history`。修改已应用的迁移文件会导致脚本拒绝继续，此时按提示 `reset --yes` 重建即可。
- `scripts/dev_identity.sql` 是本地 OAuth 账号所需的 `sys_user` 表。生产环境的 `sys_user` 由宿主身份库提供，不能把该文件应用到生产元库。
- `scripts/dev_seed_admin.py` 只生成随机密码、BCrypt 哈希和本地种子 SQL；密码不会打印到终端，也不会写入仓库。

## OAuth、Gateway 与安全边界

- `authz` 的 OAuth 默认在本地启用，签名私钥位于 `.local/dev/oauth-signing.pem`。本地脚本没有注册 OAuth 客户端，因此后台登录可用，但完整客户端授权/刷新链路仍需另行配置。
- `gateway` 默认 `PTMCP_GATEWAY_ENABLED=false`，启动后 MCP 工具保持关闭。启用它需要业务库 TLS 信任材料（MySQL truststore、PostgreSQL 根证书）和客户端配置；不要为了本地方便引入非 TLS 执行旁路。
- 所有数据库/缓存端口只发布到 `127.0.0.1`，不监听公网。
- `.local/` 和 `config/dev/secrets/` 已在 `.gitignore` 中；不要提交、复制或粘贴其中的密码与密钥。
- 本地管理员密码是随机生成的，仅用于可丢弃的开发库。共享服务器上首次登录后应修改密码。
- 本脚本不执行提交、推送、发布、生产迁移或真实业务库连接。

## 故障排查

- **端口被占用**：脚本会提示 13306/15432/16379 冲突；先停止占用进程。
- **MySQL 8.4 拉取慢或卡住**：Compose 使用固定 digest，首次需要下载镜像。若 Docker 的某个层长时间停在 `Verifying Checksum`，可取消拉取后重试；仅在本机开发链路阻塞时，按上文临时使用 `mysql:8.0`。8.0 结果不能写成 8.4 验证通过。
- **JDK 版本错误**：执行 `./scripts/Dev-Linux.sh doctor`，确认使用 Java 21。
- **Maven Wrapper 首次下载慢**：脚本优先使用系统 Maven 3.9.9；没有时回退到 Wrapper。
- **pnpm 版本错误**：在 `ptmcp-ui` 目录内执行命令，不要从仓库根用 `pnpm --dir` 触发 corepack 的默认版本。
- **管理员密码丢失**：执行 `./scripts/Dev-Linux.sh reset --yes` 重建本地库并生成新密码。
