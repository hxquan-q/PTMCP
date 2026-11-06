# PTMCP 单机部署（Compose）

> 不希望安装 Docker 时，使用原生部署包：[`native/README.md`](native/README.md)
> （Linux/Windows 三进程 + nginx，共享同一套配置变量与路由）。

一套 Compose 栈跑完整产品：自托管 MySQL 元库 + 一次性迁移 + 三个 Boot 进程（admin/authz/gateway）
+ 同源入口（UI 静态站与反向代理）。对外只发布一个 HTTP 端口。

> 当前切片只交付镜像与 Compose 编排。**管理员账号播种、数据库自动探测、依赖自动安装属于后续切片**，
> 因此全新部署能启动到"服务健康"，但还不能完成登录（见[已知限制](#已知限制)）。

## 前置条件

- Docker Engine 24+ 与 Compose v2（`docker compose version`）。
- 镜像构建需要 BuildKit/Buildx（`docker buildx version`），Dockerfile 使用缓存挂载。
- Ubuntu/Debian：`curl -fsSL https://get.docker.com | sh` 后把操作者加入 `docker` 组。
- Windows：不直接跑 Docker Desktop 时，在 WSL2 发行版内安装 Docker Engine，命令在 WSL 内执行。
- 主机需能访问 Docker Hub（构建阶段还需访问 Maven Central 与 npm registry）。

## 一键启动

```bash
cd deploy
./init-env.sh http://localhost:8080       # 本机 HTTP；远程部署使用已终止 TLS 的 HTTPS 基址
docker compose up -d --build             # 构建四个应用镜像并启动
docker compose ps                        # 等 health 全部 healthy / migrate 为 exited(0)
```

`init-env.sh` 只在目标主机本地生成密钥（`openssl`），不从网络下载任何凭证：

| 产物 | 用途 |
| --- | --- |
| `deploy/.env` | 运行参数与三个密钥环变量，权限 600 |
| `deploy/secrets/mysql_migrate.txt` | 仅迁移容器使用的 DDL 账号密码 |
| `deploy/secrets/mysql_{admin,authz,gateway}.txt` | 三个进程各自的受限元库账号密码 |
| `deploy/secrets/mysql_root.txt` | 元库 root 密码（仅健康检查与运维使用） |
| `deploy/secrets/oauth-signing.pem` | OAuth 令牌签名私钥（明文 PKCS#8 RSA 2048） |

全部被 git 忽略。已有 `.env` 时脚本拒绝覆盖；确需重建须先备份并传 `--force`，
**这会轮换全部密钥，旧库中已加密的凭证将无法解密**。

基址只接受无路径、凭证、query、fragment 的 HTTPS URL，或 `localhost`、
`127.0.0.1`、`[::1]` 的 HTTP URL，端口为 1..65535。局域网 HTTP 会在写秘密前被拒绝。
直接 HTTP 发布端口与基址一致（省略端口为 80）；HTTPS 需要外部 TLS 入口，
默认向本栈内部 HTTP 8080 转发，并保留外部 authority。本栈不自行监听 TLS。
外部 TLS 场景还需同步本层 nginx 的转发协议与后端受信代理配置，不能把任意客户端
提供的转发头当作可信信息；本轮仅验证直接回环 HTTP，未完成外部 TLS 验收。

`prepare-secrets` 保持宿主文件 0600，在专属卷内为 MySQL UID 999 与 authz UID 10001
分别准备 0400 文件、0700 目录。authz 仅只读挂载签名卷；admin/gateway 不挂载它。
Compose 本地文件型 secrets 的 uid/mode 不能完成该转换，依据
[Docker secrets 说明](https://docs.docker.com/reference/compose-file/services/#secrets)。

`--build` 可省略（`up -d`），仅在镜像未构建或源码更新后需要。

## 结构

```
deploy/
├── compose.yaml              # prepare-secrets → mysql → migrate → configure-db → 三应用 / ui
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
| `/login` `/logout` `/oauth/` `/oauth2/` `/.well-known/*`（除下一行） | authz |
| `/.well-known/oauth-protected-resource`、`/mcp` | gateway |
| `/healthz` | nginx 自身 |

## 接入外部 MySQL

元库不要求由本栈托管。把 `PTMCP_METADATA_JDBC_URL` 指到现有 MySQL 8.4，并提供账号密码后，
由 DBA 创建 `ptmcp_migrate` 和三个服务账号，按
[权限脚本](docker/mysql-accounts.sh)的表/列边界授权。用 override 去掉 `mysql` 服务与依赖，
外部 DBA 负责权限时也去掉 `configure-db` 及应用对它的依赖，再单独执行迁移：

```bash
docker compose run --rm migrate
```

迁移镜像只挂载本仓 `ptmcp-parent/ptmcp-persistence/src/main/resources/db/ptmcp-migration`，
执行账号需要该库的 DDL 权限；三个应用进程只使用运行期需要的权限。

## 元库账号与已有卷升级

新环境按 `migrate → configure-db → 应用` 启动：`ptmcp_migrate` 持有元库 DDL，
三个应用各持独立密码，仅按职责获得表/列 DML 权限。gateway 不可改用户/ACL，
authz 不可改数据源/员工密码，admin 不可改审计内容或 OAuth 授权码。
`configure-db` 在建表后执行，可重复配置受本包管理的三个账号；失败会阻止应用启动。
它不创建宿主 `sys_user`；同库接入该表后需重跑该步骤，使 admin 可写、authz 只读，
gateway 仍无密码表读取权限。

已有 MySQL 卷不会重跑官方 `MYSQL_USER` 初始化，也不会自动撤销旧共用账号：

1. 在维护窗口用原清单停止旧应用，备份元库、`.env` 与 secrets；保留 credential/audit/WAL 密钥及签名钥，不能用 `--force` 升级。
2. DBA 创建独立 `ptmcp_migrate` 并授目标库 DDL；为四个新账号分别生成密码，写入新增环境键和对应 secret 文件。运行账号密码为 48 位十六进制，文件与环境值必须一致。
3. 用新清单执行 `prepare-secrets`、`migrate`、`configure-db`，核对运行账号无 DDL/GRANT OPTION，再启动应用。外部身份库的权限由其 DBA 单独授予。
4. 确认没有进程使用旧账号后，由 DBA 撤销或删除旧共用账号；只更换应用配置不会消除其旧权限。保留迁移前备份与回退窗口，不自动删除数据库或旧密钥。

`PTMCP_BUSINESS_ALLOWED_PORTS` 未配置时默认 `3306,5432`；显式空值保持拒绝全部，
与 admin/gateway 的物理连接策略一致。

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

## 部署回归

仓库根运行 `python3 -m unittest discover -s deploy/tests -v`（Linux + Docker/Compose）和
`powershell.exe -NoProfile -File deploy/tests/test_windows.ps1`（Windows PowerShell 5.1 + OpenSSL）。
Windows 已有 nginx 时可追加 `-NginxExe <nginx.exe路径>`，验证真实配置检查、启停、健康及静态访问。
它们仅使用临时目录、合成配置与独立 Compose project，不读取现有 deploy/.env。
完整镜像冒烟还需先将当前源码构建为 `ptmcp-review-{admin,authz,gateway,ui}:local`，再运行
`PTMCP_DEPLOY_TEST_IMAGES=1 python3 deploy/tests/test_compose.py`（Compose 2.24.4+）。
该模式检查服务健康、CSRF、登录失败与 OAuth 入口，不创建通用管理员或替代真实客户端验收。
