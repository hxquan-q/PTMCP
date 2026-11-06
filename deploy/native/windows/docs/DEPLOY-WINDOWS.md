# PTMCP Windows 原生部署

在 Windows Server / Windows 10+ 上不依赖 Docker：三个 Spring Boot 进程 +
nginx for Windows 同源入口 + 现有 MySQL。布局、配置键、路由与 Linux 包一致。

## 前置条件

| 依赖 | 要求 | 说明 |
| --- | --- | --- |
| JDK | 21+ | 安装后把 `java.exe` 加入 PATH，或在 `app.env` 设 `JAVA_EXE` |
| nginx | Windows 版 | 解压后把 `nginx.exe` 路径写入 `app.env` 的 `NGINX_EXE` |
| MySQL | 8.0+ | 元库；库与账号需事先创建 |
| openssl | 首次安装 | Git for Windows 自带；用于生成 OAuth 签名私钥 |
| Flyway 或 Docker | 二选一 | 仅迁移需要；Docker Desktop 非必需 |

## 发布包结构

```
ptmcp-<version>-windows\
├── 0-install.cmd / 0-install.ps1     # 首次安装
├── 1-start.cmd  2-stop.cmd  3-status.cmd
├── 4-migrate.cmd  5-check.cmd
├── platform.ps1                      # 托管入口（start/stop/restart/status）
├── check-prereq.ps1  migrate.ps1  init-env.ps1  Ptmcp.Layout.ps1
├── DEPLOY-WINDOWS.md
├── releases\<version>\
│   ├── backend\{admin,authz,gateway}.jar
│   ├── backend\oauth-pages\
│   ├── frontend\
│   ├── migration\
│   ├── nginx\{nginx.conf.template,mime.types}
│   └── scripts\{Platform.Runtime.psm1,app.env.example}
├── shared\config\app.env             # 首次安装时生成（升级不覆盖）
├── shared\config\secrets\
├── shared\data\wal\
└── run\ logs\ temp\
```

## 一键安装

```cmd
0-install.cmd http://localhost:8080
```

首次运行生成 `shared\config\app.env` 后退出，先填写元库连接，再运行一次
`0-install.cmd`（不带参数）完成预检 → 迁移 → 启动。

参数：`-SkipMigrate`、`-NoStart`。安装脚本幂等。

## 日常运维

```cmd
1-start.cmd
2-stop.cmd
3-status.cmd
4-migrate.cmd
5-check.cmd
```

或直接使用 PowerShell：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File platform.ps1 -Action Start
powershell -NoProfile -ExecutionPolicy Bypass -File platform.ps1 -Action Stop
powershell -NoProfile -ExecutionPolicy Bypass -File platform.ps1 -Action Status
```

端口与路由同 Linux：nginx `8080`；admin `18080`、authz `18081`、gateway `18082`
只监听 `127.0.0.1`。

## 进程归属与停止语义

`run\<service>.json` 记录 PID、创建时间与命令行。停止前后逐项复核：

- PID 不存在 / 创建时间变化 / 命令行缺少 `-Dptmcp.service=<name>` → 拒绝操作；
- 端口在监听不作为归属证明；
- **绝不** `taskkill /im java.exe` 或按映像名杀进程。

Windows 对脱离控制台的 JVM 没有可靠的 Ctrl+C 通道，停止后端只终止已复核的
那一个 JVM 进程，**不保证 Spring 关闭钩子执行**；对优雅停机有要求时请在维护窗口操作。

nginx 通过 `nginx -s quit` 优雅退出，并等待 master/worker 退出。

## 元库迁移

`4-migrate.cmd` 优先使用 PATH/`FLYWAY_EXE` 上的 Flyway CLI；否则回退 Docker Desktop，
并把容器内的 `127.0.0.1` 改写为 `host.docker.internal`。都没有时直接失败。

## 已知限制

- 尚未播种管理员账号（后续切片）。
- 未注册 Windows 服务；机器重启后需手动 `1-start.cmd`（或自行用 NSSM/计划任务托管）。
- 未包含自动升级/回滚；升级时解压新版本、更新 `shared\config\release.txt` 后重跑安装。
- 业务库自签证书需自行配置 truststore（`PTMCP_JAVA_OPTS` 追加 `-Djavax.net.ssl.trustStore=...`）。
- 路径含空格可用，但 `PTMCP_JAVA_OPTS` 不要放带空格的路径参数（会按空格拆分）。

## 排障

| 现象 | 处理 |
| --- | --- |
| `找不到 nginx` | 在 `app.env` 写 `NGINX_EXE=C:\nginx\nginx.exe` |
| `需要 openssl` | 安装 Git for Windows，或用 Win64 OpenSSL 后重试 |
| 服务启动即退出 | 看 `logs\<svc>.log` 与 `logs\<svc>.err.log` |
| nginx 配置校验失败 | `logs\nginx-error.log`；也可手工 `nginx -t -c run\nginx.conf` |
| 端口被占用 | 预检会列出；先确认占用者，不要盲目结束进程 |

OAuth 基址必须是无路径、凭证、query 和 fragment 的 HTTPS URL，或主机严格为
`localhost`、`127.0.0.1`、`[::1]` 的 HTTP URL；不接受局域网 HTTP。
直接 HTTP 的监听端口由基址推导（省略端口为 80）；HTTPS 基址需要外部 TLS 入口，
生成的 nginx 内部 HTTP 监听仍为 8080，外部端口由 TLS 入口发布。
业务端口白名单未配置时为 `3306,5432`，显式空值拒绝全部；CIDR 空值也拒绝全部。

首次生成配置及 `-Force` 重建时，脚本在写入前将 `shared/config`、`secrets`
及现有 app.env/签名 PEM 的 ACL 限定为当前运行身份与 SYSTEM。
应用应由该身份启动；使用其他服务身份时由管理员明确授予所需读取权限。
nginx 配置以无 BOM UTF-8 写入，模板中的文件路径已引用，支持含空格安装目录。
