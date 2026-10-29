# DEV-20261026-yunxiao-pipeline：补齐云效质量流水线

ID: DEV-20261026-yunxiao-pipeline
Status: verifying
Owner: current-agent
Acceptance: not-required
Verification: BLOCKED
Updated: 2026-10-26

## 目标与边界

来源：用户在流水线调查后要求直接优化。续接现有 Flow `5347135`，目标是完整后端测试、
前端契约测试与本地/GitHub/云效检查范围一致，并核查 MR 触发与等待原因。
现有代码检测清理仍由 [DEV-20261026-yunxiao-quality](DEV-20261026-yunxiao-quality.md) 记录。

开工 `git status --short` 为空，分支 `dev`。本项单 Agent 串行实施，保留业务源码和既有门禁。
不部署、不发布、不购买资源、不降低扫描阈值；不读取或输出访问令牌。用户授权优化现有
流水线及必要的隔离验证，未授权代码推送。内部工程任务不需要产品体验人工接受。

## 实施与验证

写范围：云效配置副本与操作说明、GitHub 工作流、根检查入口及其回归、必要工作项/决策记录。
先保留现有配置，再核查 Flow 运行与 Docker 配置；分片修改并运行针对性检查。
后端目标命令为 Wrapper `verify`（不带 skip），随后运行隔离质量正反例；前端为冻结安装、
`check` 与 `test:contracts`。真实云端执行和仅保存配置分别记录；环境阻断不能计为测试通过。

验收：`python scripts/check.py`、根入口回归、本地适用构建、云效配置回读及实际运行结果。
不可用的环境或 MR 事件验收必须明确保留缺口，不创建无关 MR 或修改业务代码来伪造事件。
云端更新前保存原 Flow 内容，必要时恢复原配置。仅在确认是本仓库 `dev` 的纯质量任务、
记录提交与状态后，停止已被新配置替代的旧运行，避免继续占用构建资源；不操作部署任务。

## 结果与证据

初始证据：CLI 可读取 Flow `5347135`；第 5 次运行和三个 Job 均为 `WAITING`。
当前后端命令含 `-DskipTests`；云效前端显式运行契约测试，但根 `--all` 与 GitHub 尚未包含。
云效文档 Job 未传入归档比较基线。

根入口新增两项回归先 RED（遗漏契约执行、契约失败未传播），补齐后 6 项入口测试 PASS。
2026-10-26 通过 CLI 更新 Flow，并回读配置 ID `22414749`，内容与本地副本一致；
MR 事件、工具安装步骤、10/30/15 分钟超时已保存。原始 Flow 备份在忽略目录
`.local/verification/yunxiao-pipeline-before-20261026.json`。云端执行尚未验证。

补充实际结果：

- 项目环境下 `python -B scripts/check.py --all --maven-cache-seed D:/config/ptmcp/cache/maven`
  退出 0；实际后端 666 项测试（失败/错误/跳过均 0）、质量门禁正反例、前端完整检查和
  68 项契约测试全部通过。日志 `.local/verification/yunxiao-optimization-local.log`。
- `python -B scripts/check.py` 退出 0，50 项检查器回归通过；`git diff --check` 退出 0。
- 第 4 次旧配置运行自行完成 SUCCESS；停止的是仍运行旧配置的第 5 次 dev 纯质量任务，
  未停止部署或其他项目。第 6 次新配置暴露 SetupNode 403 和 Docker 不可用。
- Node 官网 SHA256 已核对；移除失败的 SetupNode，使用固定 HTTPS 官方归档、校验、缓存。
  最终配置 ID `22414896` 已回读，Flow 内容与仓库副本一致。
  第 7 次实跑：文档 SUCCESS（72 秒）、前端 SUCCESS（66 秒）；后端 FAIL，根因为
  `Could not find a valid Docker environment`，没有恢复 skipTests。云端测试提交为
  Codeup 已有 `8afb59c6e9c017af588f7f3b07f3b5cfad5492d7`。
- 自审：检查 CLI 参数引用、版本选择、下载校验、非零退出传播、MR 目标过滤、超时与
  本地/云端边界；没有独立评审。已明确批量推送的归档基线限制，未改扫描门禁阈值。
- Windows 页面控制工具因无法可靠确定当前浏览器 URL 而停止，未继续使用页面控制；
  其余优化通过 CLI 完成。用户选择继续公共集群，自行开启 Docker Daemon。
- [决策记录](../../.agents/notes/implemented/process/2026-10-26-yunxiao-quality-parity.md)
  仅确认检查配置生效，不把环境阻断计为完整云端通过。

## 交接

当前焦点：用户已选择继续公共集群，并自行在控制台开启 Docker Daemon；等待完成信号后，
回读配置（保留用户的远端改动）并重跑完整后端。当前不把“我来开启”当成“已开启”。
本地未提交、推送或部署；Flow 优化已保存并实跑，完整云端测试尚未通过。
未触发真实 MR，不能声称 MR 合入准入已经验收或设置了分支保护。
