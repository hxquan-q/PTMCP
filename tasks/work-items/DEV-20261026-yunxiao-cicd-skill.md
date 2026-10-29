# DEV-20261026-yunxiao-cicd-skill：云效 CI/CD 可复用 SOP Skill

ID: DEV-20261026-yunxiao-cicd-skill
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-26

## 目标与边界

来源：用户要求将为已有仓库建立云效 CI/CD 的过程整理成可供其他项目参考的 SOP Skill。
提炼 [流水线优化](DEV-20261026-yunxiao-pipeline.md) 的实际经验，覆盖仓库调查、工具能力、
CI、制品、部署/回滚和验收；脱离本项目的组织、仓库、服务连接、流水线 ID 与凭据。

开工已有上一任务的 CI、脚本、云效说明、配置及工作项/笔记修改，均原样保留。
本项单 Agent 串行完成，仅新增 Skill 源文件与本工作项，安装个人副本并导出分享包。
不修改现有云端流水线、不运行部署、不推送代码，也不把待开启 Docker 的原工作项标为完成。

## 实施与验证

源文件放 `.agents/skills/yunxiao-cicd-sop/`，安装到个人 Codex 技能目录；模板按仓库实际
技术栈适配，不固定 PTMCP 路径或版本。共享正文放 SKILL.md，平台命令、交付、排障按需引用。
仅创建对复用有实际帮助的模板，不新增通用自动部署脚本或要求特定 MCP 才能工作。

验收：Skill Creator 校验、内部链接和 YAML/JSON 解析、模板脱敏与占位变量替换检查、
安装副本/ZIP 与源文件一致性、代表性场景自审、仓库 `python scripts/check.py`。
本次文档/模板不需要重复运行无关业务构建；不以结构检查宣称跨项目或真实生产验收。

## 结果与证据

交付 [Skill 入口](../../.agents/skills/yunxiao-cicd-sop/SKILL.md)、UI 元数据、平台命令/
制品部署/排障三份参考，以及质量 Flow、发布 manifest、验收记录三份脱敏模板，共 8 个文件。
安装副本：`C:/Users/xquan/.codex/skills/yunxiao-cicd-sop/`。
分享包：`.local/deliverables/yunxiao-cicd-sop-20261026.zip`，仅含 Skill 文件，没有日志或项目配置。

实际验证：

- Skill Creator `quick_validate.py` 对仓库源和个人安装副本均退出 0。
- 所有 JSON/YAML 可解析，内部链接存在，无初始化 TODO、私钥头或本项目组织/仓库/
  流水线/服务连接 ID；模板明确保留待填写参数，没有假装可以原样执行。
- 离线渲染质量模板，替换全部参数，真实 Bash `-n` 与成功执行均退出 0；失败命令返回 19，
  后续命令未执行。仅运行无网络的本地示例，没有调用云端创建/更新或部署 API。
- `python -B scripts/check.py` 退出 0，50 项检查器回归通过；`git diff --check` 退出 0。
- 安装副本与源文件逐字节一致；ZIP CRC 与全部文件内容校验通过，8 个文件一致。

场景自审（非独立前向测试）：GitHub/Node 单工程按真实源 schema 与锁文件接入，不套 Maven；
已有 Flow 遇组织接口 403 时继续验证可用的仓库/CLI 能力并保护并发修改；Docker 缺失时保留
质量失败；完整 CD 请求缺少部署资产时输出 CI 与待实施发布契约、列出实际缺口，有资产和授权
则继续部署验证。对配置保存、CI、制品、测试部署、生产发布分别报告，未将模板写成已验证系统。

未执行跨项目真实接入或生产发布；本项验收是可复用 Skill 的结构、模板、安装与分享完整性。
没有新增通用自动部署代码或强制子 Agent 流程，保留目标仓库与用户授权边界。

## 交接

Skill 已完成并可按 `$yunxiao-cicd-sop` 使用；源文件与个人安装副本后续更新时应同步并重验。
本项仅新增 `.agents/skills/yunxiao-cicd-sop/` 和本工作项，原有未提交修改保留；没有提交、推送
或更改云端。原流水线仍等待用户开启 Docker Daemon 后续验，本项没有扩大云端操作授权。
