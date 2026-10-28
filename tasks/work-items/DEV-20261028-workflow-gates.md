# DEV-20261028-workflow-gates：流程噪声收敛与机械门禁

ID: DEV-20261028-workflow-gates
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-28

## 目标与边界

来源：用户 2026-10-28 要求优化开发流程，并确认按推荐实施 A+B+C。

目标：把已写在散文里的三类纪律变成可机械检查的事实——（1）自治循环在目标阻塞或连续无进展时停止；（2）提交消息符合 git-rule 的类型白名单且不含凭证/脱敏占位符；（3）CI 对同分支连续推送取消旧运行，docs-only 变更不触发后端/前端构建。

不做项：不改写历史提交；不新增工作项状态枚举；不触碰 `ptmcp-ui/**`（并发进程持有未提交改动）；不提交、不推送。

不变量：`tasks/work-items/` 仍是状态唯一来源；既有安全门禁与测试阈值不放宽；本机工具安装物继续不入库。

## 实施与验证

- 新增 `scripts/verify_commits.py`：按 `--range`/`AGENT_COMMIT_RANGE` 检查提交消息（默认 HEAD），校验类型白名单、`type(scope): 描述` 形状、标题长度，拒绝私钥、GitHub/GitLab/AWS 令牌与 `{{PASSWORD_...}}` 类脱敏占位符。
- `scripts/check.py` 接入该检查；`scripts/tests/test_verify_commits.py` 覆盖合规、历史违规（verify/session/无前缀）、占位符与超长标题。
- `.auto/prompt.md` 增加停止条件与提交类型映射，并修复被脱敏器误伤的列名。
- `.github/workflows/quality.yml` 增加 `concurrency` 取消、`changes` 作业与 docs-only 跳过，文档作业传入提交范围。
- 验证：`python scripts/check.py` 全绿；新增测试通过；YAML 可解析（本机不运行 GitHub Actions）。

## 结果与证据

- `python scripts/check.py` 通过；检查器回归 60 项（新增 9 项提交检查）。
- 反例：`0875494 verify(real): …`、`8c14d15 session: …` 退出码 1；`d00f22d docs(datasource): …`、`HEAD~3..HEAD` 退出码 0。
- `.github/workflows/quality.yml` 通过 YAML 解析；GitHub Actions 未在本机运行，`concurrency` 与 docs-only 跳过的远端效果未验证。
- 循环停止条件未在真实循环复跑（当前循环已停止），以 `.auto/prompt.md` 合同形式生效。
- 未提交、未推送；`ptmcp-ui/**` 的并发改动未触碰。

## 交接

C 切片已完成，笔记已从 proposed 流转为 implemented。后续若循环重启，先确认 `status: "stopped"` 与停止原因；若 CI 路径过滤误跳过构建，把对应目录从排除列表移除。
