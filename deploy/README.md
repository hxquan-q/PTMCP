# PTMCP 单机部署（Compose）

> 不希望安装 Docker 时，使用原生部署包：[`native/README.md`](native/README.md)
> （Linux/Windows 三进程 + nginx，共享同一套配置变量与路由）。

一套 Compose 栈跑完整产品：自托管 MySQL 元库 + 一次性迁移 + 三个 Boot 进程（admin/authz/gateway）
+ 同源入口（UI 静态站与反向代理）。对外只发布一个 HTTP 端口。

> 当前切片只交付镜像与 Compose 编排。**管理员账号播种、数据库自动探测、依赖自动安装属于后续切片**，
> 因此全新部署能启动到"服务健康"，但还不能完成登录（见[已知限制](#已知限制)）。

## 前置条件

- Docker Engine 24+ 与 Compose v2（`docker compose version`）。
- Ubuntu/Debian：`curl -fsSL https://get.docker.com | sh` 后把操作者加入 `docker` 组。
- Windows：不直接跑 Docker Desktop 时，在 WSL2 发行版内安装 Docker Engine，命令在 WSL 内执行。
- 主机需能访问 Docker Hub（构建阶段还需访问 Maven Central 与 npm registry）。

## 一键启动

```bash
cd deploy
./init-env.sh http://<对外地址>:<端口>   # 首次：生成 .env 与 secrets/
docker compose up -d --build             # 构建四个应用镜像并启动
docker compose ps                        # 等 health 全部 healthy / migrate 为 exited(0)
```

`init-env.sh` 只在目标主机本地生成密钥（`openssl`），不从网络下载任何凭证：

| 产物 | 用途 |
| --- | --- |
| `deploy/.env` | 运行参数与三个密钥环变量，权限 600 |
| `deploy/secrets/mysql_app.txt` | 元库应用账号密码 |
| `deploy/secrets/mysql_root.txt` | 元库 root 密码（仅健康检查与运维使用） |
| `deploy/secrets/oauth-signing.pem` | OAuth 令牌签名私钥（明文 PKCS#8 RSA 2048） |

全部被 git 忽略。已有 `.env` 时脚本拒绝覆盖；确需重建须先备份并传 `--force`，
**这会轮换全部密钥，旧库中已加密的凭证将无法解密**。

`--build` 可省略（`up -d`），仅在镜像未构建或源码更新后需要。

## 结构

```
deploy/
├── compose.yaml              # 六个服务：mysql / migrate / admin / authz / gateway / ui
├── init-env.sh               # 生成 .env 与 secrets/
├── .env.example              # 无密钥的变量清单与说明
├── docker/
│   ├── apps.Dockerfile       # 三个 Boot 镜像（maven 构建 + 精简 JRE 运行）
│   ├── ui.Dockerfile         # SPA 构建 + nginx 同源入口
│   └── nginx.conf            # /backend/*、/oauth*、/.well-known/*、/mcp 路由
└── secrets/                  # 本地生成，不入库
```

网络分两层：`edge` 承载浏览器/Agent 入口与三个应用；`data` 为 `internal: true`，
只给 mysql、migrate 与三个应用使用，不提供外网出口。

## 对外路由（nginx，单端口）

| 路径 | 上游 |
| --- | --- |
| `/`、`/assets/*` | 控制台 SPA |
| `/backend/admin/` `/backend/authz/` `/backend/gateway/` | 对应应用（剥前缀） |
| `/oauth/` `/oauth2/` `/.well-known/*`（除下一行） | authz |
| `/.well-known/oauth-protected-resource`、`/mcp` | gateway |
| `/healthz` | nginx 自身 |

## 接入外部 MySQL

元库不要求由本栈托管。把 `PTMCP_METADATA_JDBC_URL` 指到现有 MySQL 8.4，并提供账号密码后，
用 override 去掉 `mysql` 服务与 `migrate` 对它的依赖，再单独执行迁移：

```bash
docker compose run --rm migrate
```

迁移镜像只挂载本仓 `ptmcp-parent/ptmcp-persistence/src/main/resources/db/ptmcp-migration`，
执行账号需要该库的 DDL 权限；三个应用进程只使用运行期需要的权限。

## 密钥环与轮换

- `PTMCP_CREDENTIAL_KEY`、`PTMCP_AUDIT_KEY` 必须同时给 admin 与 gateway 相同的值，
  否则网关无法解密数据源凭证、审计记录无法解封。
- `PTMCP_GATEWAY_WAL_KEY` 只用于网关 WAL 文件加密，独立轮换；轮换后未消费的 WAL 不可读。
- 生产建议改用 Docker secrets / 外部 KMS 注入，而不是 `.env`（本切片尚未接入）。

## 已知限制

- **尚无初始管理员**：迁移不创建 `sys_user`，OAuth 登录无法完成授权页跳转；
  管理员引导（首个账号、初始配额）在后续切片提供。
- **仅 HTTP**：示例基址为 `http://`，Cookie 安全标志为 false。上线 HTTPS 时把
  `PTMCP_PUBLIC_BASE_URL`、`PTMCP_OAUTH_ISSUER`、`PTMCP_GATEWAY_*` 一起改成 `https://` 域名，
  两个 `*_COOKIE_SECURE` 置 true，并在前置做 TLS 终止。
- **业务数据源 TLS 信任库未挂载**：接自签证书业务库时需自行把 truststore 挂进 admin/gateway，
  本切片未提供该挂载点。
- 元库默认走容器网络内明文 MySQL（`useSSL=false`），`data` 网络不出容器。
- 未包含备份、监控、日志轮转与多副本；单机 Compose 不含高可用语义。

## 排障

```bash
docker compose ps                       # 状态与健康
docker compose logs -f admin            # 单服务日志
docker compose config                   # 确认变量展开（含密钥，勿外传）
docker compose down                     # 停止；加 -v 会删除元库卷
```

- `migrate` 非 0 退出：后续服务不会启动，先看 `docker compose logs migrate`。
- 构建阶段拉取依赖失败：为 docker CLI 与 daemon 配置可达的 HTTP 代理后再 `up --build`。
- authz 启动即失败并提示签名密钥：确认 `deploy/secrets/oauth-signing.pem` 为
  `-----BEGIN PRIVATE KEY-----` 开头的未加密 PKCS#8，且至少 2048 位。
