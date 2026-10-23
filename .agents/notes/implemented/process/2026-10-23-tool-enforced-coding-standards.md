# Agent Note: 用工具落实编码标准，显式保留协议与安全边界

Status: implemented

## Problem

只写风格建议会让 Agent 与评审反复争论格式。plus-ui 的当前工具链与用户要求已有差异；通用手册直接覆盖现有数据库、REST/OAuth/MCP 或安全合同也会产生不一致。

## Decision

仓库以[工程标准](../../../../docs/standards/quality-tooling.md)和受版本管理的配置作为编码入口。Spotless/google-java-format 负责 Java 格式，Checkstyle 负责已配置非排版规则；前端复用 plus-ui 历史配置结构与 Prettier 参数，收紧 Vue/Props/TS 语义。工具典型正反例已验证，产品工程接线仍属于 M0。

用户已明确 SQL 投影禁令仅用于项目编写的 SQL；外部 SQL 保持受控展开。MySQL 通用时间列与 REST/前端设计同批同步。REST R<T> 的架构决定由 ADR0010 独立持有，本篇不建立平行契约。ASVS 用明确条款和证据状态自查，不由静态检查推导安全达标。

## Alternatives considered

- **全部靠人工检查手册**：无需工具链，但无法稳定复现格式裁决，已被用户明确排除。
- **把当前 plus-ui 当成自带 ESLint**：易于宣称复用，但实际源码使用 oxlint/oxfmt；选择固定历史配置来源并保留许可证，不回退运行时。
- **一套通用规范覆盖所有层**：表面统一，却会误禁外部 SQL、把标准协议包成 R、移除既有主键/事务约束，故显式划定适用面和适配项。

## Consequences

格式争议转为可执行工具结果；代价是维护配置来源、版本和 M0 接线证据。p3c/ASVS 中不能机械判定或尚未设计的部分仍需语义评审与实际测试，配置存在不是产品门禁已启用。

## Verification

真实 Java/Vue 格式与语义正反例、26 个检查器用例通过，详见[工具验证](../../../../docs/evidence/quality-tooling-validation.md)。本轮为作者自审；没有应用构建或 ASVS 验证结果。检索既有笔记后，本篇独立承载工具标准；与[Agent 研发合同](2026-10-23-agent-led-development.md)部分相关并互链，未替代其执行流程。
