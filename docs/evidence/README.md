# 证据目录与状态约定

版本：1.0-design · 2026-10-23。

设计、实现、测试和部署是四种不同状态。本目录登记可复现证据与待补资产，不能因为设计包含算法或命令就声称系统可运行。

## 已有证据

- [JSQLParser 5.4解析器复现](../reviews/PTMCP_审查复现证据_2026-10-23.md)：2026-10-23，Java21.0.10，包含源码、jar来源/hash、6例输出和额外边界探针。它证明原版组件漏表，不证明PTMCP实现已经修复。
- [源码审查](../reviews/PTMCP_方案审查_2026-10-23.md)：核对本地若依/dbx与同版本OAuth源码，不是完整系统测试。
- [本轮文档验证](documentation-validation.md)：结构、链接、JSON和追踪项的检查；不计作产品功能PASS。
- [工程工具验证](quality-tooling-validation.md)：真实 Spotless/Checkstyle/ESLint/Prettier 隔离正反例，及配置/码段检查；不计作产品构建或 ASVS 通过。
- [本机环境验证](development-environment-validation.md)：D盘工具、专用WSL、真实构建/HTTP/JDBC/TLS/Testcontainers与持久性；不计作产品业务验收。
- [根目录框架验证](framework-bootstrap-validation.md)：正式前后端工程、构建/启动、UI依赖组合；本轮不进行业务编码。

历史证据存放在reviews中，保持原始材料，不为了目录整齐复制成第二套可变副本。本文建立指针并登记适用范围。

## 待补证据

完整清单见[M0门禁登记](m0-gate-register.md)，实际全系统状态见[测试报告](../test/test-report.md)。29VQ属于私有资产，提供case ID、版本、哈希与CI引用即可，公开仓不复制真实业务SQL/结果。

每次运行至少记录：case ID、构件commit/digest、依赖/驱动版本、配置、环境、fixture种子、输入哈希、预期/实际、开始结束时间、原始日志/指标位置、执行人和判断。公开输出只用合成数据；秘密值用安全引用，不写明文。

证据状态：NOT_RUN未执行；BLOCKED条件或资产缺失；PASS/FAIL需要真实运行；HISTORICAL_COMPONENT表示旧组件实验。不能用“0个失败”推导“全部通过”，也不能把只运行解析器计作OAuth/数据库验收。

## 维护工具

从仓库根执行：

```powershell
python scripts/check.py
```

命令校验文档/工作合同，并在临时仓库运行检查器回归，不修改当前仓库的 Git 状态，不访问数据库、云服务或客户端。分项文档检查仍为 `python docs/evidence/validate_docs.py`。历史review/调研原文不作为新文档严格格式检查对象；其存在性和引用保留。检查器不渲染Mermaid，也不替代安全/业务评审。
