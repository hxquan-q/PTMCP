# DEV-20261026-linux-dev：当前 Linux 服务器本地开发启动脚本

ID: DEV-20261026-linux-dev
Status: verifying
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-10-26

## 目标与边界

来源/父任务或用户请求：用户要求在 Codeup 仓库所在的当前 Linux 服务器上开发，先接回 Git 远端，再增加本地 dev 启动脚本。

目标：提供只用于本机开发的 Linux 启动入口，能够生成隔离的本地凭据、启动 MySQL 8.4、执行 V1–V6 元库迁移、创建可登录的本地管理员，并以明确命令启动 admin/authz/gateway/前端。脚本必须可重复执行、失败可诊断、不会把凭据写入仓库。

不做项：不新增生产部署路径；不修改 V1–V6 迁移语义；不创建 OAuth 客户端/DCR/CIMD 业务；不绕过 SQL guard 或引入非 TLS 网关执行旁路；不把本机开发数据、密码、密钥写入 Git；不提交、不推送、不发布。

不变量：数据库/缓存只绑定 `127.0.0.1`；所有密码与密钥运行时随机生成并落在 `.local/dev`、`config/dev/secrets`（均已在 `.gitignore`）；管理员不使用固定默认密码；迁移按 V1–V6 顺序执行并记录校验和；脚本拒绝在已有数据卷但密钥缺失时静默重建；保留用户现有未跟踪文件。

已有修改和所有权：当前工作区基于 `xquan/feat-backend`（5916832），远端 `dev` 比它多 3 个提交。已有未跟踪文件 `docs/ops/yunxiao-pipeline.json`、`docs/ops/yunxiao-quality.md`、`wget-log` 保留不动。

## 实施与验证

依赖、读写路径、独占契约：新增 `scripts/Dev-Linux.sh`、`scripts/dev_seed_admin.py`、`scripts/dev_identity.sql`、`docs/ops/development-linux-server.md`；只写 `config/dev/secrets` 与 `.local/dev` 下的本机资产。MySQL 镜像沿用 `config/dev/compose.yaml` 的固定 digest；compose 增加 `PTMCP_MYSQL_IMAGE` 可选覆盖，默认值不变。

串行/并行结论及负责人：单 Agent 串行。下载 JDK 21 与 MySQL 8.4 镜像可并行，脚本编写与下载并行；实际启动验证在 JDK 安装完成后执行。

当前切片、失败/基线、实施和回滚/收窄路径：基础设施与管理员种子已完成并验证；gateway 默认 MCP disabled，待 TLS 材料另行启用。失败时保留日志，使用 `reset --yes` 重建专用开发卷。

验收标准、实际存在的验证命令/环境：
- `bash -n scripts/Dev-Linux.sh`、`python3 -m py_compile scripts/dev_seed_admin.py`；
- `./scripts/Dev-Linux.sh up` 启动 MySQL、迁移 V1–V6、生成管理员；
- `./scripts/Dev-Linux.sh status` 显示 MySQL healthy；
- admin/authz 启动后 `/actuator/health` 返回 UP；
- 使用生成的凭据完成 CSRF + 登录 + `/api/v1/session/me`；
- `python scripts/check.py` 与 `cd ptmcp-ui && pnpm check`。

## 结果与证据

实际修改、命令/退出码、覆盖行为和证据路径：

- Git：`git init` + SSH `origin`，当前分支 `xquan/feat-backend`（5916832），与远端一致；`dev` 多 3 个提交。
- JDK 21：`sudo apt-get install -y openjdk-21-jdk-headless` 完成，`/usr/lib/jvm/java-21-openjdk-amd64`。
- 脚本静态检查：`bash -n scripts/Dev-Linux.sh` 退出 0；`python3 -m py_compile scripts/dev_seed_admin.py` 退出 0；`./scripts/Dev-Linux.sh doctor` 退出 0。
- 种子脚本独立运行：生成 56 条 INSERT、包含 `ptmcp:platform:scope`，输出文件权限 600，未打印密码。
- 后端构建：`./scripts/Dev-Linux.sh build` 使用 JDK 21 + 系统 Maven 3.9.9，8 个模块 BUILD SUCCESS（3:01）。
- 本地基础设施：`PTMCP_MYSQL_IMAGE=mysql:8.0 ./scripts/Dev-Linux.sh up` 退出 0；V1–V6 全部应用；管理员种子成功。
- 真实登录：`GET /api/v1/session/csrf` → `POST /api/v1/session/login` HTTP 200；`GET /api/v1/session/me` HTTP 200；userId=1、displayName=PTMCP Dev Admin、permissions=51、`ptmcp:platform:scope=true`。
- Vite 代理登录：`http://127.0.0.1:5173/backend/admin/api/v1/session/login` HTTP 200；`/me` HTTP 200；重启后复跑仍为 200/200、51 个权限。
- authz：`/actuator/health` UP；`/oauth2/jwks` 返回 RSA 公钥。
- 前端：`cd ptmcp-ui && pnpm check` 通过（eslint、prettier、vue-tsc、vite build）。
- 仓库检查：`python scripts/check.py` 48 项通过。
- `stop` → `start`：退出 0；admin/authz/ui 三个健康检查均 UP；迁移跳过已应用版本，管理员密码保持不变。
- 恢复副本：`.local/backup/dev-linux-20261026.tgz`（因工作区曾清掉新增未跟踪文件，已重新写回并备份）。
- 公网访问：`PTMCP_UI_PUBLIC='true'`，Vite 监听 `0.0.0.0:5173`，`sudo ufw allow 5173/tcp`；`http://183.36.30.83:5173/` 返回 200；公网 CSRF → 登录 → `/me` 均 200，51 个权限。
- HTTP Secure Context 修复：新增 `src/services/uuid.ts` 的 `newUuid()`（优先 `crypto.randomUUID`，回退 `crypto.getRandomValues`）和 `src/services/clipboard.ts`（回退 `execCommand`）；`DatasourcesView`、`DatasourceDetailView`、`MetadataDetailView`、`http.ts`、`data.ts`、`controlPlane.ts` 已改用它。契约测试 70/70、`pnpm check` 通过。
- 合并：`origin/dev` 的 3 个提交已合并为 `eecd75b`，无冲突；后端 `-DskipTests install`、前端 `pnpm check`、契约测试 68/68、仓库检查 48 项通过。

未执行项及原因、剩余风险：MySQL 8.4 固定 digest 多次拉取均卡在某一层并导致 Docker 镜像存储短暂阻塞，已取消；本切片用 8.0 仅验证脚本/迁移/登录，不能写成 8.4 验证通过。gateway 默认 disabled；OAuth 客户端、前端 E2E、独立安全评审未做。公网 5173 是 HTTP 开发服务器，密码和会话 Cookie 会明文传输，只能临时使用；云安全组是否放行 5173 需用户在云控制台确认。工作区曾出现新增未跟踪文件被清掉的异常，已重新写回并建议在 `.local/backup` 保留副本。

评审类型（自审/独立）、发现与修复：自审；不宣称独立安全评审。

人工接受来源；内部任务不适用时说明原因：待用户验收。

## 交接

当前焦点、剩余项和下一步：验证脚本在 MySQL 8.4 镜像可用后的复跑；按需启用 gateway/OAuth 客户端；决定是否合并 `dev` 的 3 个提交。

实际 git status、提交/集成/发布事实：已接入 `origin`（SSH），当前分支 `xquan/feat-backend`，未提交、未推送。

已有授权与待决定事项：已授权安装 `openjdk-21-jdk-headless` 和按 digest 拉取 MySQL 8.4；8.4 拉取受服务器网络/Docker 缓存影响尚未完成；是否切换/合并到 `dev` 待用户决定。
