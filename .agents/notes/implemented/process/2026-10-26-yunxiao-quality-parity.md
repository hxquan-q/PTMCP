# Agent Note: 云效质量检查保留完整测试

Status: implemented

## Problem

现有云效 Job 每次使用 dnf 刷新仓库，实际环境准备超过 15 分钟；后端使用 skipTests，
而根检查与 GitHub 漏掉已经存在的前端契约测试，三端绿灯含义不同。

## Decision

云效使用 SetupPython/SetupJava 和仓库 Maven Wrapper；后端必须执行完整 verify，再执行
既有隔离质量探针。根 --all、GitHub 与云效均执行前端 check 和 test:contracts。
云效增加目标为 dev 的 MR 新建/更新触发及任务超时，文档任务使用较小规格。

固定 Node 24.22.1 的官方 SetupNode 下载在真实云端返回 403，改用 Node 官网 HTTPS 归档，
固定 SHA256 校验并缓存，只在校验通过后使用。该路径保留固定版本和证书验证。

## Alternatives considered

继续使用 skipTests 能得到编译绿灯，但无法检查已实现的数据库、认证和网关回归，故不采用。
新建付费构建资源并非必要前置；用户选择继续公共集群并自行开启 Docker Daemon。
没有自行安装持久 Runner、开放 Docker TCP 端口或购买资源。

## Consequences

环境缺少 Docker 时完整质量检查明确失败；不能把命令已部署或纯 Java 测试成功当整体验收。
本笔记 implemented 仅指检查配置与本地执行入口已生效，不代表完整云端测试通过。
默认 dev 归档比较使用 HEAD 父提交；批量推送需显式提供推送前 SHA 才覆盖整个批次。

## Verification

[工作项](../../../../tasks/work-items/DEV-20261026-yunxiao-pipeline.md) 记录实际命令、运行号、
配置回读及缺口。根入口两项回归先 RED 再 GREEN；本地 --all 已通过，云端第 7 次运行的
文档和前端成功，后端因未接通 Docker 失败。MR 真实事件和 Docker 开启后的完整云端运行仍待验证。
当前未提交、推送或部署。配置/操作入口见 [云效说明](../../../../docs/ops/yunxiao-quality.md)。
