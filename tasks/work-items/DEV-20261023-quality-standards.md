# DEV-20261023-quality-standards：引入工程标准与工具配置

ID: DEV-20261023-quality-standards
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-23

## 目标与边界

来源：用户要求引入阿里 Java/MySQL 规约、Spotless/Java formatter/Checkstyle、Vue 风格/ESLint/Prettier、REST R<T>/模块错误码、Conventional Commits 和 OWASP ASVS。用户补充：SELECT * 禁令仅限项目编写的 SQL，外部 SQL 保留现有受控展开。

当前无应用 Maven/UI 工程。交付可复用工具配置及其验证、同步后的设计契约和 ASVS 自查入口；不启动产品实现、不修改 Reference、不继承参考项目授权、不提交/推送。已有未提交文档及前轮规则全部保留。

## 实施与验证

单 Agent 串行。读：现有业务/安全设计、本地 R.java、官方文档和固定 plus-ui 历史配置。写：config/quality、docs 相关标准/接口/数据库/前端/测试/导航、规则入口、任务/笔记、检查器与测试。

验收：明确格式只由工具判定；工具配置可解析并用隔离夹具验证；前端复用来源可追溯；REST 三字段与真实 R<T> 一致且不包装 OAuth/MCP；错误码可验证唯一性和分段；数据库通用字段一致；ASVS 记录具体版本/条款/证据和未验证差距。

检查：`python scripts/check.py`；另在临时目录以真实 formatter/linter 验证正反例，不访问真实数据库或业务服务。实际命令与未验证项在结果节登记。

## 结果与证据

完成规范、可复用 Java/前端工具配置及上游 MIT 归属；增加错误码登记与校验，8 个模块/49 个错误；REST/前端 R<T> 契约、数据库通用时间字段、总体方案/ADR0010、Conventional Commits 和 ASVS 5.0.0 自查同批同步。用户对 SELECT * 范围的答复已落实，外部 SQL 受控展开不变。T01/T03/T17 增补真实接线验收，仍待实现。

实际验证：新增 8 个错误码回归先以未实现模块导入失败（退出1），实现后全部通过；统一 `python scripts/check.py` 的文档/流程/质量配置与 26 个回归通过（退出0），新增 TC-API-009/TC-DB-001 后共有84个设计TC。格式和语义真实正反例：Spotless格式错→拒绝，apply→validate通过；Checkstyle通配导入/BadMethod拒绝；ESLint合法Vue通过，单词组件/Props修改/无类型Props逐条命中；Prettier错格式拒绝，write后check通过。详细版本、命令和限制见[工具验证](../../docs/evidence/quality-tooling-validation.md)。

作者自审修正：Maven 实际 JDK8 与 javapath21 不同，探针显式选21；质量配置路径改为父POM声明 ptmcp.qualityRoot，避免 -f/Wrapper/CWD 根误判；R成功常量引用真实 HttpStatus.SUCCESS；Vue工具配置只给Node配置文件Node globals。核对REST示例/前端适配、create_time/update_time引用及MCP/OAuth隔离，无用格式审查代替工具结果。

未验证：产品应用/完整源码、实际MySQL迁移/SQL AST门禁、生产安全、远端CI、完整ASVS。ASVS仅做设计映射，所有条目为 NOT_RUN/GAP；未把本工作项 PASS 扩大到安全达标。当前任务是内部规范和配置交付，人工产品功能验收不适用；未进行独立评审。

## 交接

本轮内部规范任务完成。工作树仍保留原有未提交工作及本轮规范/配置，未提交/集成/推送/发布。后续业务实现按 T01/T03/T17 把模板接入真实工程并提供失败门禁证据，不能把配置解析当应用构建。参考源码未修改、用户级配置未改变；临时工具/缓存用于本次验证，不成为项目运行前提。
