# DEV-20261030-harness-development：按官方 Harness 实践完善项目研发闭环

ID: DEV-20261030-harness-development
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-30

## 目标与边界

依据官方 deepseek-ai/deepseek-harness 的固定版本，覆盖任务、设计、开发、接口、测试、评审和交付，适配现有 Java/React、安全规则和工作项，不移植 DSH 应用架构，不改变业务合同或清理共享工作树。

## 实施与验证

核对上游原始资料与本项目入口；编写基于实际变更层的只读范围报告和反例测试；补齐研发全过程操作合同和上游映射；运行定向测试、仓库检查和适用的笔记校验。远端 CI、产品和长期执行效果不在本片验证范围。

## 结果与证据

- 直接核对官方仓库固定提交 `ce7073d51254c26cd63b22a60835b8f948deb447` 的入口、文档、测试、评审及检查规则；适配映射和未覆盖事项见 `docs/standards/harness-development.md`。既有 Agent 研发流程 Note 同步这一扩展，无新建重复决定。
- 新增 `scripts/change_scope.py` 区分提交、暂存、未暂存和未跟踪路径；基线须显式提供，失败时非零退出。定向测试先因脚本不存在失败，再通过，2 项通过；临时 Git 仓库验证基线、分层、Unicode、非法基线和不更改索引。`python scripts/change_scope.py --base HEAD` 在实际工作区退出 0，仅证明本地范围报告运行。
- `python scripts/check.py` 退出 0；`git diff --check` 针对本片已跟踪文件退出 0。外部 Skill 三项笔记校验均通过（13 篇笔记、0 篇归档）；零归档不能证明归档兼容。
- 仅作者自审；未运行 `--all`、业务、数据库、真实客户端、独立评审或远端 CI。结构门禁不证明规则被执行、证据真实或安全正确；内部流程工具无需产品人工验收。

## 交接

本片建立研发闭环及基础工具，不表示完整复制 DSH 的运行时、所有技能、平台矩阵、发布系统或自动强制全部语义；剩余差距见研发闭环映射。已有共享工作树修改保留，未提交或推送。
