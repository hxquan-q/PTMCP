# DEV-20261106-deploy-native-pack：Linux/Windows 原生部署包

ID: DEV-20261106-deploy-native-pack
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: NOT_RUN
Updated: 2026-11-06

## 目标与边界

承接用户"复用 E 盘部署包"的指示：以 `E:/industrial_plat-ai/deploy`（2026-11-04 版）
的目录约定、配置外置、预检与进程归属模型为蓝本，为 PTMCP 的三进程结构交付
不依赖 Docker 的 Linux/Windows 原生部署包。

- `deploy/native/linux/`：`install.sh` + `platform.sh`（start/stop/status/check/migrate/logs）
  + `check-prereq.sh` + `migrate.sh` + `init-env.sh` + `lib.sh` + nginx 模板。
- `deploy/native/windows/`：`0-install` 至 `5-check` 入口 + `platform.ps1`
  + `Platform.Runtime.psm1` + 预检/迁移/配置生成脚本 + nginx 模板。
- `deploy/native/build-release.sh`：Maven + pnpm 构建后组装
  `ptmcp-<version>-linux.tar.gz` 与 `ptmcp-<version>-windows.zip`。
- 布局与 Compose 版一致：`releases/<version>/` + `shared/config/app.env` + `current`；
  变量映射与 `deploy/compose.yaml` 相同（`PTMCP_ADMIN_*`/`PTMCP_AUTHZ_*`/`PTMCP_GATEWAY_*`）。
- 端口：nginx `8080`（`PTMCP_HTTP_PORT`）；admin/authz/gateway `18080/18081/18082`，
  只监听 `127.0.0.1`（与容器内 8080/8081/8082 不同，避免与入口端口冲突）。
- 迁移：Flyway CLI 或 Docker 固定镜像；不引入自定义迁移账本。

明确不做：管理员播种、数据库自动发现、依赖自动安装、systemd/Windows 服务注册、
自动升级/回滚、HTTPS 终止、业务库 truststore 注入（均属后续切片）。

## 实施与验证

- 打包：`build-release.sh --skip-build` 生成 Linux tar.gz 与 Windows zip（各约 90MB），
  目录与文件齐全。
- Linux（WSL `PTMCP-Dev`，Java 21）：`lib.sh` 启动 gateway → 状态正确 → SIGTERM 停止；
  nginx 模板渲染后无 `@...@` 占位符；伪造 PID 记录（指向 `sleep`）时拒绝停止且
  外部进程存活；端口被占用时拒绝启动；`check-prereq.sh` 汇总 16 PASS / 2 FAIL / 1 WARN
  （FAIL 为预期：本机无 nginx、元库指向关闭端口）。
- Windows（Windows 11 + JDK 21.0.10）：`Platform.Runtime.psm1` 启动 admin →
  `run/admin.json` 记录 PID/CreationDate/CommandLine → 停止成功；伪造记录指向当前
  PowerShell 时拒绝操作且不误杀；nginx 模板渲染无占位符；`check-prereq.ps1` 的
  Java 21 检查通过、nginx 缺失按预期 FAIL；`init-env.ps1` 生成 app.env 与
  PKCS#8 PEM，重复执行拒绝覆盖。
- 语法：Linux 全部 `bash -n` 通过；Windows 全部 `.ps1/.psm1` 经
  PowerShell Parser 解析通过。
- 评审类型：自审（非独立评审）。

## 结果与证据

- 测试包：`deploy/native/dist/ptmcp-test-20261106-{linux.tar.gz,windows.zip}`（git 忽略）。
- 关键日志：gateway 启动输出 `gateway 已启动（PID 2031909，端口 18082）`；
  Windows `admin 已启动（PID 54220，端口 18080）` 与停止后 `stopped`。
- 备份源：`E:/industrial_plat-ai/deploy` 最新 2026-11-04 版；复用其
  `releases/current/shared` 布局、四步安装语义与"四重身份校验"思路，
  未复制其单后端/引擎依赖相关脚本与产品专有配置。

## 交接

当前焦点：原生部署包骨架与进程托管已验证，端到端启动仍需 nginx 与完整元库。

剩余步骤：
1. 在装有 nginx 的测试机执行 `install.sh` / `0-install.cmd` 全流程，验证三进程健康与 HTTP 路由。
2. 补首个管理员播种（切片 B）后验证登录、OAuth 授权页与 `/mcp`。
3. 视需要补 systemd / Windows 服务注册、升级与回滚。

工作树事实：本轮只新增 `deploy/native/**`、更新 `.gitignore` 与 `deploy/README.md`，
不影响既有 Docker 交付物；`deploy/native/dist/` 为本地构建产物，已忽略。

未验证项：

- nginx 实际启动与端到端 HTTP 路由未验证：Windows 与 WSL 均未安装 nginx，
  安装属宿主依赖变更，未在未经确认的情况下执行。
- 三进程同时启动到健康、OAuth 登录/授权页与 `/mcp` 端到端未验证
  （管理员播种仍缺失，登录本就不可完成）。
- Flyway CLI / Docker 迁移未在本切片实际执行。
- 真实 Linux 发行版（非 WSL）的 systemd 集成、非 root 监听 80/443 未验证。
- 升级/回滚、外部 MySQL、HTTPS、业务库自签证书场景未覆盖。
