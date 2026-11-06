# DEV-20261106-deploy-container-pack：生产容器编排包

ID: DEV-20261106-deploy-container-pack
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: NOT_RUN
Updated: 2026-11-06

## 目标与边界

用户批准的部署切片 A：为 Ubuntu/Windows(WSL2) 单机部署交付可直接运行的镜像与 Compose，
不引入启动器、自动探测或依赖自动安装（后续切片 B/C/D）。

- `deploy/docker/apps.Dockerfile`：三阶段构建 admin/authz/gateway；运行层非 root（uid 10001）、
  精简 JRE、`HEALTHCHECK` 打 actuator；authz 额外内嵌 `dist-oauth` 页面。
- `deploy/docker/ui.Dockerfile`：构建 SPA，nginx 非 root 运行，承载同源入口。
- `deploy/docker/nginx.conf`：单端口发布 `/backend/{admin,authz,gateway}/{,}`、`/oauth*`、
  `/.well-known/*`（受保护资源元数据归 gateway）、`/mcp`、SPA fallback、`/healthz`。
- `deploy/compose.yaml`：mysql（自托管元库）+ migrate（一次性 Flyway）+ 三个应用 + ui；
  `edge`/`data(internal)` 双网络，read_only + tmpfs、cap_drop ALL、no-new-privileges；
  密钥文件走 secrets，必填变量用 `:?` 失败关闭。
- `deploy/init-env.sh` + `deploy/.env.example`：本地生成 `.env` 与 secrets（数据库密码、
  PKCS#8 RSA 签名钥），拒绝静默覆盖。
- `deploy/README.md`：启动、路由、外部元库、密钥轮换、已知限制与排障。

明确不做：首个管理员播种（登录在 B 切片前不可完成）、数据库自动探测、依赖自动安装、
镜像发布流水线、业务库 truststore 挂载、TLS 终止。

## 实施与验证

- `deploy/init-env.sh` 在 WSL 中生成 `.env` 与三个 secret 文件（`--force` 语义未实测覆盖路径）。
- `docker compose config -q` 通过；服务集合 `mysql, migrate, gateway, admin, authz, ui`。
- 镜像构建、栈启动与端到端健康检查：见下节"结果与证据"。
- 评审类型：自审（非独立评审）。

## 结果与证据

（随验证完成填写）

## 交接

当前焦点：容器切片停在上层镜像构建（Maven checkstyle 配置路径未随构建上下文复制），
未完成栈启动；用户随后要求复用 E 盘部署包，已另开
[原生部署工作项](DEV-20261106-deploy-native-pack.md)，本项保留待续。

剩余步骤：修复 `deploy/docker/apps.Dockerfile` 的 `config/quality` 复制、
用可达代理重建镜像、`docker compose up -d` 验证健康与路由。

提交 `2b36898`（dev，已推送 origin）收存本切片文件；镜像构建失败未修复。

未验证项：

- Windows 原生 Docker Desktop 下未验证（仅在 WSL2 内 Docker Engine 验证）。
- 外部 MySQL 元库模式、HTTPS 域名、业务库自签证书场景未实测。
- 非 root nginx 在只读根文件系统下的完整行为、构建缓存命中率未量化。
