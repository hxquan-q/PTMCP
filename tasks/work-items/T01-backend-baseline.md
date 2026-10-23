# T01-backend-baseline：后端实施任务与可复现公共基线

ID: T01-backend-baseline
Status: done
Owner: coordinator
Acceptance: not-required
Verification: PASS
Updated: 2026-10-23

## 目标与边界

来源：用户授权先制定task并完成全部后端、为前端预留接口，允许多路子代理协作，每完成一部分及时提交推送。续接T01-env/T01-bootstrap和现行T01～T20，不将既有框架记成业务能力。

初始HEAD为7315b2b，只有README被跟踪；README修改及工程、规则、文档、脚本、设计稿全部为开工前既有内容。保留原文与所有权，仅将经核对的公共研发依赖纳入初始基线提交，明确不是本轮新实现。保留且不提交 .impeccable、PRODUCT.md、DESIGN.md、docs/frontend/design 和在制 T17-sap-visual；Reference、本机凭证/缓存/产物与私有评测不进入Git。现有前端仅作为完整检查的构建依赖纳入，不改页面。

内部研发基线与任务组织不需要产品体验人工接受；各业务切片另行验收，禁止代勾父任务。前端视觉任务边界和既有历史记录不重写。

## 实施与验证

协调者独占写tasks/plan、初始Git索引与后续共享契约。三个只读调查子代理分别梳理接口验收、工程环境、安全执行链；本轮不并行写源码。所有写并行须另建独立工作区并重新集成验证。

已创建开发分支 xquan/feat-backend，保留所有原有内容。按tasks/plan的依赖表推进；每片测试/评审/记录后提交推送。先验收T02基础契约，再引入数据库和guard；安全链未就绪不开放查询接口。

验收：1）现行后端范围与依赖、逐片测试和提交策略有可追踪记录；2）规定环境的python scripts/check.py --all通过；3）暂存文件白名单、秘密/私有边界和diff检查通过；4）分支提交后远端SHA可核对。不会通过放宽检查或删除测试制造通过；基线提交可通过普通revert回退，不reset/clean原有工作树。

## 结果与证据

2026-10-23从WSL调用Windows PowerShell，加载scripts/Enter-Dev.ps1：Java21.0.10、Maven3.9.9、Python3.14.7、pnpm11.9.0。python scripts/check.py --all退出0：26个检查器回归、六项目Maven reactor、前端lint/format/typecheck/build通过。原始本机日志 .local/verification/backend-baseline-20261023.log（忽略目录，不含凭据；不是业务测试报告）。

Git远端读取成功，origin/main仍为7315b2b。分段提交/推送尚待执行；当前没有业务测试/安全独立评审/客户端兼容通过结论。初始秘密特征扫描只发现Compose secret资源名，无凭证值；暂存白名单排除视觉稿/私有夹具/本机秘密/缓存。将暂存树用git archive导出到忽略的.local隔离目录后，执行python3 scripts/check.py退出0，74份Markdown/341处本地链接及26个回归通过，证明无需本机未提交的视觉文件即可运行仓库门禁。

自审发现原有文件CRLF被git diff --check判为尾空白：在.gitattributes声明文本LF规范化、保留mvnw.cmd的CRLF工作树规则，只规范索引行尾，不重写原有文字。剩余空白仅来自历史原始REQ/review中的既有Markdown硬换行，遵循历史输入不重写政策保留；除这两个历史文件外的暂存git diff --check退出0，不关闭检查。调查另发现总体方案旧REST外壳和三个漏登错误符号，已纳入T02-b同批修正，不能把基线结构PASS当契约语义一致性PASS。

## 交接

本项已完成：公共基线及任务计划提交cec14ae6dbcc2038202a233f0cc6300538da0959并推送至origin/xquan/feat-backend，git ls-remote返回同一SHA。远端提示仓库已迁移至hxquan-q/PTMCP，现有origin重定向推送成功；未改远端配置、未合入main、未发布。提交后仅 .impeccable、DESIGN.md、PRODUCT.md、docs/frontend/design、T17-sap-visual 为原有未跟踪在制品，均保留。此状态回填随下一切片提交。

T02-a为下一切片；真实业务、接口、OpenAPI、集成/安全/恢复/性能均未完成。私有29VQ资产已在G-12记录缺失，不妨碍推进不依赖它的实现，但最终不得将其记PASS。

Git身份沿用本机Windows Git既有配置，不伪造作者。用户已明确授权按部分提交和推送；未授权发布、生产操作、收费外部调用或真实业务数据读取。
