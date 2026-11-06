# PTMCP Linux 原生部署

不依赖 Docker：在目标机上直接运行三个 Spring Boot 进程（admin/authz/gateway）+
nginx 同源入口 + 现有 MySQL。适合不能/不愿装 Docker 的 Ubuntu/Debian 服务器。

> 另有容器部署：[`../../README.md`](../../README.md)（Compose，一条命令起全套）。
> 两者共用同一份环境变量映射、同一套 nginx 路由与同一 Flyway 迁移目录。

## 前置条件

| 依赖 | 要求 | 说明 |
| --- | --- | --- |
| JDK | 21+ | `java -version` 可用；非 PATH 时在 `app.env` 设 `JAVA_EXE` |
| nginx | 1.18+ | `apt install nginx`；只用本包配置，不读 `/etc/nginx` |
| MySQL | 8.0+ | 元库，库与账号需事先创建 |
| Flyway 或 Docker | 二选一 | 仅首次/升级迁移需要，见[迁移](#元库迁移) |
| openssl | 首次安装 | 生成 OAuth 签名私钥与随机密钥 |

## 发布包结构

```
ptmcp-<version>-linux/
├── install.sh                 # 首次安装入口（可重复执行续跑）
├── DEPLOY-LINUX.md
├── releases/<version>/
│   ├── backend/{admin,authz,gateway}.jar
│   ├── backend/oauth-pages/   # OAuth 登录/授权页
│   ├── frontend/              # 控制台 SPA
│   ├── migration/             # Flyway V*.sql
│   ├── nginx/{nginx.conf.template,mime.types}
│   └── scripts/               # platform.sh 及各子命令
├── shared/config/app.env      # 首次安装时生成（升级不覆盖）
├── shared/config/secrets/     # 本地生成的私钥
├── shared/data/wal/           # 网关 WAL
├── current -> releases/<version>
└── run/ logs/ temp/
```

## 一键安装

```bash
tar -xzf ptmcp-<version>-linux.tar.gz
cd ptmcp-<version>-linux
./install.sh http://<对外地址>:8080
```

首次运行会生成 `shared/config/app.env` 后退出，要求先填写元库连接：

```ini
PTMCP_METADATA_JDBC_URL=jdbc:mysql://127.0.0.1:3306/ptmcp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8
PTMCP_METADATA_DB_USERNAME=ptmcp
PTMCP_METADATA_DB_PASSWORD=********
```

再次运行 `./install.sh` 即完成预检 → 迁移 → 启动。参数：

- `--skip-migrate`：跳过 Flyway（已迁移过或由 DBA 执行）。
- `--no-start`：只做预检与迁移。

安装脚本幂等：中断后重跑不会重复生成密钥、不会重建已有配置。

## 日常运维

```bash
current/scripts/platform.sh status
current/scripts/platform.sh start|stop|restart
current/scripts/platform.sh logs [admin|authz|gateway]
current/scripts/platform.sh check          # 预检（运行中可复检）
current/scripts/platform.sh migrate
```

进程归属：每个后端在 `run/<svc>.pid` 记录 PID 与 `/proc/<pid>/stat` 启动时间，
停止前复核启动时间与命令行标记 `-Dptmcp.service=<svc>`。任一不符即拒绝操作，
**绝不按进程名杀 Java**，也不使用 `kill -9`。

端口：

| 服务 | 默认端口 | 监听 |
| --- | --- | --- |
| nginx | 8080（`PTMCP_HTTP_PORT`） | 0.0.0.0 |
| admin | 18080 | 127.0.0.1 |
| authz | 18081 | 127.0.0.1 |
| gateway | 18082 | 127.0.0.1 |

内部端口只给 nginx 使用，不要对外开放。修改端口后重启即生效（nginx 配置在启动时渲染）。

## 元库迁移

`migrate.sh` 优先使用 PATH 上的 `flyway`（或 `app.env` 的 `FLYWAY_EXE`），
否则回退 `docker run flyway/flyway:11-alpine`（固定镜像摘要）。两者都没有时直接失败，
不会用自定义表替代 Flyway 的版本与校验和语义。

迁移文件唯一来源：`ptmcp-parent/ptmcp-persistence/src/main/resources/db/ptmcp-migration`，
打包时已复制进 `releases/<version>/migration/`。

## 配置要点

- 三个进程共用 `shared/config/app.env`，启动脚本按应用展开成 `PTMCP_ADMIN_*`、
  `PTMCP_AUTHZ_*`、`PTMCP_GATEWAY_*`（与 `deploy/compose.yaml` 完全一致）。
- admin 与 gateway 必须使用同一把 `PTMCP_CREDENTIAL_KEY`、`PTMCP_AUDIT_KEY`；
  `PTMCP_GATEWAY_WAL_KEY` 独立。
- `PTMCP_PUBLIC_BASE_URL` 是浏览器与 Agent 看到的地址，也用于 OAuth issuer；
  gateway 的 JWKS 内网地址固定为 `http://127.0.0.1:<authz 端口>/oauth2/jwks`。
- HTTPS：在 nginx 前终止 TLS 或改用 HTTPS 配置，并把 `*_COOKIE_SECURE` 置 true、
  基址改成 `https://`。

## 已知限制

- 尚未播种管理员账号：首次部署可启动到健康，但登录流程不可完成（后续切片）。
- 未提供 systemd 单元；机器重启后需手动 `platform.sh start`（或自行接入 systemd/rc）。
- 未包含自动升级/回滚；升级时解压新版本包、改 `current` 链接并重跑预检与启动。
- 业务数据源使用自签证书时，需自行给 JVM 配置 truststore（`PTMCP_JAVA_OPTS` 追加
  `-Djavax.net.ssl.trustStore=...`）。
- nginx 以执行者身份运行；若用非 root 监听 80/443，需要 `CAP_NET_BIND_SERVICE` 或前置端口转发。

## 排障

| 现象 | 处理 |
| --- | --- |
| 预检 `mysql.tcp` 失败 | 确认 MySQL 已启动、`PTMCP_METADATA_JDBC_URL` 主机端口正确 |
| 预检 `mysql.auth` 失败 | 账号密码或授权不对；先手工 `mysql -h ... -u ... -p` 验证 |
| 启动卡在某个服务 | `current/scripts/platform.sh logs <svc>`；常见是密钥/数据库配置 |
| nginx 启动失败 | 看 `logs/nginx-error.log` 与 `nginx -t -c run/nginx.conf` 输出 |
| 端口被占用 | 预检会指出服务；不要直接杀进程，先确认归属 |
