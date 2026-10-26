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
| 触发 | 推送到 `dev`（`^dev$`），Codeup 服务连接 `e35k9qkwkyicf6yy` |
| 构建环境 | 北京公共构建集群，`alinux3`，`LARGE_4C8G` |

阶段 `质量检查（dev）` 包含三个并行 Job：

1. **文档与流程检查**：安装 Python 3.11，执行 `python3.11 scripts/check.py`。
2. **后端编译、格式、模块边界与质量探针**：安装 JDK 21，执行
   `ptmcp-parent/mvnw -B -ntp -DskipTests verify`，再执行
   `scripts/check_backend_quality.py`（实际格式/Checkstyle/Enforcer 正反例）。
3. **前端 ESLint/Prettier/类型检查/构建/契约测试**：下载 Node 24.22.1，安装 pnpm 11.9.0，
   执行 `pnpm --dir ptmcp-ui install --frozen-lockfile --ignore-scripts`、
   `pnpm --dir ptmcp-ui check`、`pnpm --dir ptmcp-ui test:contracts`。

边界：云效托管构建环境不提供 Docker/Testcontainers，后端 Job 使用 `-DskipTests`，只验证
编译、格式、模块边界与静态契约；真实数据库/审计/网关集成测试仍需在本机或独立集成环境执行，
不能把流水线绿灯当成 M0/M4 通过。当前流水线不部署、不发布。

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

## 代码检测

| 项目 | 值 |
| --- | --- |
| 检测方案 | `PTMCP Java/TypeScript 质量与安全检测`，ID `202610261326400000110700079920017` |
| 仓库检测任务 | `ALLSTAR_DATACHAT_SUB-检测任务`，ID `202610261326490000110900091750564` |
| 语言 | Java、TypeScript |
| 触发 | `dev` 分支的提交（commit）和合并请求（merge_request） |
| 质量门禁 | blocker `<= 0`；critical/major 当前不设上限 |
| 文件过滤 | 已排除生成文件 `ptmcp-ui/src/components.d.ts`（unplugin-vue-components 产物） |

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
