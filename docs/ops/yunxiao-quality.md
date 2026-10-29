# 云效流水线与代码检测

日期：2026-10-26。适用于 Codeup 仓库 `Allstar_AI/ALLSTAR_DATACHAT_SUB`（组织
`621301e6c5006adb19febbd6`，仓库 ID `7609410`）。本页记录云效侧实际配置；流水线配置由
Flow API 保存，不假设仓库内的文件会自动同步。

## 流水线

| 项目 | 值 |
| --- | --- |
| 名称 | `ALLSTAR_DATACHAT_SUB 质量流水线` |
| ID | `5347135` |
| 类型 | `PIPELINEASCODE` |
| 触发 | `dev` 推送、目标分支为 `dev` 的 MR 新建/更新（`^dev$`）；Codeup 服务连接 `e35k9qkwkyicf6yy` |
| 构建环境 | 北京公共构建集群，`alinux3`；文档 `SMALL_1C2G`，后端/前端 `LARGE_4C8G` |

阶段 `质量检查（dev / MR）` 包含三个并行 Job：

1. **文档与流程检查**（10 分钟超时）：`SetupPython` 安装 Python 3.11，执行
   `python scripts/check.py`。显式传入 `AGENT_ARCHIVE_BASE`：MR/非 dev 分支比较与 `dev`
   的 merge-base；dev 默认比较 HEAD 父提交；首提交比较 HEAD。批量推送若需覆盖全部提交，
   应显式提供推送前 SHA；默认父提交比较不能证明整个批次均未改动归档。
2. **后端完整测试、格式、模块边界与质量探针**（30 分钟超时）：`SetupPython`、`SetupJava`
   安装 Python 3.11/JDK 21，先检查 Wrapper 实际版本，再执行
   `sh ./mvnw -B -ntp verify`（不跳测试），随后执行
   `python scripts/check_backend_quality.py --seed-repository "$HOME/.m2/repository" --timeout 240`。
   安装步骤附带的 Maven 3.9.3 不用于构建，仓库 Wrapper 固定 3.9.9。
3. **前端 ESLint/Prettier/类型检查/构建/契约测试**（15 分钟超时）：从 Node 官网下载
   Node 24.22.1，固定 SHA256 校验并缓存归档，安装 pnpm 11.9.0，
   执行 `pnpm --dir ptmcp-ui install --frozen-lockfile --ignore-scripts`、
   `pnpm --dir ptmcp-ui check`、`pnpm --dir ptmcp-ui test:contracts`。

不再每次执行 `dnf install`。第 4 次运行的日志显示仅仓库索引刷新就超过 15 分钟，阻塞后续
运行；改用工具步骤及已校验归档，避免重复刷新不相关软件仓库。Flow 的既有缓存包含
`$HOME/.m2`、`$HOME/.npm` 和 `$HOME/.cache`，Node 归档在后者的 `ptmcp/node` 下。

Node 24.22.1 Linux x64 的固定 SHA256：
`6c172a9ab6718f7b801fb5a7bd486af2ee5e32758086a04b84fcce2aa77095e6`，来自官方
[SHASUMS256](https://nodejs.org/dist/v24.22.1/SHASUMS256.txt)。官方 `SetupNode` 在本组织的
第 6 次实跑中下载该版本时返回 403，因此使用 HTTPS 官方归档与固定校验值；不关闭证书验证。

边界：第 6 次云端实跑的后端完整测试因 `Could not find a valid Docker environment` 失败。
这只证明当前任务未接通可用 Docker，不能据此断言所有公共集群不支持 Docker。
完整质量通过要求 Docker/Testcontainers 可运行；未接通时保持失败，不恢复 `-DskipTests`。
需要在控制台开启后端任务的 Docker Daemon，或接入有 Docker 的专用 Linux 构建集群，
再用双方言真实测试验证。当前未取得完整云端 PASS，不部署、不发布；扫描门禁阈值未修改。

本地根 `python scripts/check.py --all` 与 GitHub 工作流已补齐 `test:contracts`。
本地改动尚未推送，云端当前验证的是 Codeup 已有提交与新 Flow 配置；配置保存与运行通过
分别记录。实际结果与交接见 [流水线工作项](../../tasks/work-items/DEV-20261026-yunxiao-pipeline.md)。

更新流水线（需要 `ALIBABA_CLOUD_YUNXIAO_ACCESS_TOKEN`，不要把令牌写入仓库）：

```bash
export ALIBABA_CLOUD_YUNXIAO_ORGANIZATION_ID=621301e6c5006adb19febbd6
aliyun devops flow-update-pipeline \
  --pipeline-id 5347135 \
  --name "ALLSTAR_DATACHAT_SUB 质量流水线" \
  --content "$(cat docs/ops/yunxiao-pipeline.json)"
```

`docs/ops/yunxiao-pipeline.json` 是当前 Flow 配置的可读副本；修改后需执行上面的命令，Flow
不会自动读取它。

更新前读取并保存原 `pipelineConfig.flow` 至忽略目录，可使用同一 update 命令恢复；不要把包含
令牌的 CLI 调试输出保存为证据。本次备份、运行号与配置回读结果保留在上述工作项中。

配置依据：[任务/超时](https://help.aliyun.com/zh/yunxiao/user-guide/pipelined-task-jobs)、
[MR 触发](https://help.aliyun.com/zh/yunxiao/user-guide/code-source-trigger)、
[SetupPython](https://atomgit.com/flow-step/SetupPython)、
[SetupJava](https://atomgit.com/flow-step/SetupJava)、
[公共/私有构建集群](https://help.aliyun.com/zh/yunxiao/user-guide/build-a-cluster)。

## 代码检测

| 项目 | 值 |
| --- | --- |
| 检测方案 | `PTMCP Java/TypeScript 质量与安全检测`，ID `202610261326400000110700079920017` |
| 仓库检测任务 | `ALLSTAR_DATACHAT_SUB-检测任务`，ID `202610261326490000110900091750564` |
| 语言 | Java、TypeScript |
| 触发 | `dev` 分支的提交（commit）和合并请求（merge_request） |
| 质量门禁 | blocker `<= 0`；critical/major 当前不设上限 |
| 生成文件 | `ptmcp-ui/src/components.d.ts` 不再纳入 Git（`.gitignore`），由 unplugin-vue-components 在本地/构建时生成；代码检测不扫描该生成产物 |

规则包：

- 敏感信息检测（云效智能检测，All）
- Java开发规范检测（阿里巴巴开发规约）
- Java安全检测（SpotBugs）
- TypeScript-Vue开发规范（ESLint）
- 依赖包漏洞检测（Dependency-Check，支持 full）

边界：`依赖包漏洞检测`只支持 full 扫描；首次/全量扫描耗时较长。当前门禁只阻断 blocker，
critical/major 仍会出现在报告但不阻断合并；需要更严格策略时在云效代码检测页面调整后同步本页。
检测结果、规则详情和门禁阈值以云效页面为准，不在仓库保存扫描结果。

更新任务（示例，实际参数以 `aliyun devops codeup-update-quality-check-task --help` 为准）：

```bash
aliyun devops codeup-update-quality-check-task \
  --repository-id 7609410 \
  --biz-no 202610261326490000110900091750564 \
  --trigger-branch dev \
  --trigger-method commit,merge_request
```
