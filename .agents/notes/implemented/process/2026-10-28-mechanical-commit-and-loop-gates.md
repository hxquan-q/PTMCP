# Agent Note: 用机械门禁约束提交与自治循环停止

Status: implemented

## Problem

`git-rule` 把提交类型限定为 feat/fix/docs/refactor/test/build/ci/chore/perf/revert，但只写在散文里：历史提交有 16 条 `verify`、1 条 `session`，另有约 45 条没有 type 前缀。自治循环 `.auto/prompt.md` 只定义"measure 与 checks 全绿才 keep"，没有停止条件：115 轮记录中，第 93 轮已写明目标 blocked，之后仍跑了 23 轮，其中 18 轮 discard、主指标 772 连续 17 轮不变，全部是无代码变更的验证复跑。CI 对任意分支任意 push 跑完整后端构建与隔离探针，没有并发取消或路径过滤。

## Decision

1. `.auto/prompt.md` 增加停止条件：目标阻塞即写交接并停止；连续 5 轮 `discard` 且主指标不变即停止并报告。日志区分 `evidence` 轮（指标持平但有新证据）与 `progress` 轮，停止判断只看 `progress`。
2. 新增 `scripts/verify_commits.py` 并接入 `scripts/check.py`。按 `--range` 或 `AGENT_COMMIT_RANGE` 检查提交消息（默认 HEAD）：类型白名单、`type(scope): 描述` 形状、标题 72 字符上限，拒绝私钥块、GitHub/GitLab/AWS 令牌与 `{{PASSWORD_…}}` 类脱敏占位符。历史提交不改写，只检查给定范围。
3. 循环提交映射到既有类型：验证轮用 `test(scope)` 或 `chore(evidence)`，会话/目标状态用 `chore(session)`；不扩充白名单。
4. CI 增加 `concurrency` cancel-in-progress；新增 `changes` 作业，docs-only 变更（`docs/**`、`tasks/**`、`.agents/notes/**`）跳过后端与前端构建，文档与流程作业仍运行；文档作业传入 `AGENT_COMMIT_RANGE`。

## Alternatives considered

- **扩充 type 白名单，加入 verify/session**：零改动、循环可原样提交；但会让提交类型失去检索价值，且与 `git-rule` 已公布的九类冲突。选择映射到既有类型。
- **只靠评审提醒，不加脚本**：成本最低；但历史已证明散文约束会被绕过（约 45 条无前缀提交）。机械可判定的规则交给脚本。
- **循环内联停止判断，不改 prompt**：避免改 prompt；但停止语义属于循环合同，写在 prompt 才能跨会话生效。
- **CI 全量跑，不做路径过滤**：最保守；但当前分支高频推送，文档改动也触发完整构建。用显式排除列表限制跳过范围。

## Consequences

收益：提交类型与消息形状从"评审记得"变成"门禁拒绝"；循环不再把验证复跑当进展；同分支连续推送只保留最新 CI 运行，docs-only 变更不再消耗构建额度。

代价与边界：

- 提交消息检查只对给定范围生效，默认 HEAD；本地不会拦截正在编辑的消息（未安装 commit-msg hook），真正的前置拦截依赖 CI 的 `base..head`。
- 路径过滤用显式排除列表，新增目录默认仍触发构建；被文档变更间接影响的构建不会被跳过。
- 停止条件是循环合同而非强制：循环仍可能被人工重启或外部调度绕过，日志中的 `status: "stopped"` 需要人工尊重。
- `{{PASSWORD_…}}` 占位符的来源已查明：字节检查表明磁盘与 git 中是正常标识符，占位符只是输出脱敏显示；因此不清理历史提交，检查器只对新增范围生效。

## Verification

- `python scripts/check.py` 通过，60 项检查器回归（含新增 9 项提交检查）。
- 反例：`0875494 verify(real): …` 与 `8c14d15 session: …` 被拒（退出码 1，报 unsupported type）；`d00f22d docs(datasource): …` 与 `HEAD~3..HEAD` 通过（退出码 0）。
- `.github/workflows/quality.yml` 通过 YAML 解析；本机不运行 GitHub Actions，`concurrency` 与路径过滤的实际效果未在远端验证。
- 循环停止条件未在真实循环中复跑（当前循环已停止）；以合同形式生效，下次运行验证。
- 本轮为本机作者自审，未提交、未推送，未声称用户验收。

依据：[信号与噪声收敛](2026-10-28-workflow-signal-noise-reduction.md)、[Agent 主导研发](2026-10-23-agent-led-development.md)、[本轮工作项](../../../../tasks/work-items/DEV-20261028-workflow-gates.md)、`.auto/log.jsonl` 的 115 轮记录、`git log` 提交类型分布。
