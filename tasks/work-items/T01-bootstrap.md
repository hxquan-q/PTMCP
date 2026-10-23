# T01-bootstrap：根目录前后端工程骨架

ID: T01-bootstrap
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-23

## 目标与边界

来源：用户明确要求在项目根目录创建真正的前端和后端工程。后端位于 ptmcp-parent，前端位于 ptmcp-ui；D:/config 仅作工具/缓存/本地运行环境。续接 T01-env，不将环境探针当产品工程。

交付 Java21/Maven Wrapper 多模块骨架（common、guard、gateway、authz、admin）和 Vue3/TS/Element Plus 前端；接入既有工具规范、构建/启动脚本与CI，启动真实工程给出可用入口。未发布骨架包名暂用 local.ptmcp，不绑定未经确认的组织域名，发布前由组织确定。

用户最新收窄：仅框架和环境，不进行业务编码；前端接入 Element Plus、Iconify/unplugin-icons、Tailwind CSS、Animate.css/@vueuse/motion 并核对兼容。撤回本轮刚新增但尚未实现的服务状态/业务拒绝/ParserBaseline测试及UI健康API测试，不为通过而修改既有测试或阈值；这些计划行为不再属于本轮，未实现/未测试状态如实保留。只生成脚手架标准启动类、框架配置和最小显示壳，不写自定义控制器、认证逻辑、SQL处理或业务页面。

一期业务、SQL执行、登录/OAuth和数据库迁移仍按后续任务实施；所有业务入口默认不开放，首页明确工程就绪与业务未实现，不能生成默认账户/凭据或放开SQL。既有未提交工作和Reference保留，不提交/推送。

## 实施与验证

单Agent串行：父POM/模块/标准启动入口→格式/依赖/构建→前端框架依赖/配置→lint/typecheck/build→真实启动与浏览器核验→组合检查/文档。用户明确授权本轮框架创建及本地运行；本轮无业务测试或独立评审，技术初始化任务不需要业务功能人工验收。

写范围：ptmcp-parent、ptmcp-ui、项目启动/检查脚本、CI、必要规则/任务/状态文档。原设计中的persistence/datasource/业务模块按依赖后续引入，不用空实现宣称完成。共享common/guard保持纯Java，若依R只在admin适配层复用，gateway/authz禁止Sa-Token与若依依赖。

验收：Wrapper固定Maven、Java21编译；格式/Checkstyle/单元与HTTP启动测试通过；gateway仅JSQLParser5.4且无Sa-Token；三服务可独立打包启动；UI在根目录有独立package/锁文件和可访问首页、真实代理健康检查；业务入口非2xx；UI不存凭据、不用业务mock冒充完成；本地/CI命令一致。实际操作命令与未验证项回填结果。

## 结果与证据

已创建根目录ptmcp-parent（五模块与Wrapper）、ptmcp-ui（package/锁文件和最小壳），接通用户指定Element Plus/Iconify/Tailwind/Animate.css/Motion组合。后端只含三个标准启动类；前端无业务API/页面/存储逻辑。Root检查--all、CI构建任务与工程README已补齐。

实际验证：Maven clean verify/reactor全绿、有效POM/依赖树已生成，gateway单JSQLParser5.4且无Sa-Token；前端pnpm check全绿且无CSS构建警告；三JAR原生健康HTTP200、未实现业务路径404；三Vite代理health=UP；浏览器1280×800及390×844无横向溢出，真实SVG/ElTag/grid及Motion/Animate模块就绪，最后导航0error/0warning。详见[框架验证](../../docs/evidence/framework-bootstrap-validation.md)。根目录 python scripts/check.py --all 已实际执行并退出0，包含既有26个检查器回归和真实Maven/pnpm构建。

用户收窄范围后移除的是本轮尚未实现功能的新增测试，不是既有测试；26个检查器回归及安全/质量规则保留，没有skip或降低阈值。框架当前没有业务测试，未宣称业务PASS。作者自审修正了Checkstyle工作目录、Animate嵌套@charset、关闭Preflight后的box-sizing和favicon404问题。

本地验证会话已用Ctrl+C结束，未给机器遗留后台应用。远端CI未运行、业务/安全/客户端/性能均未验证；未提交、推送或发布。

## 交接

本轮框架初始化完成，无剩余实施项。用户本轮只要框架/环境，不自行推进T02～T20的业务编码。后端从ptmcp-parent启动，前端从ptmcp-ui启动；D盘smoke仍是环境探针，不是本项目。验证进程均已停止，8080/8081/8082/5173监听数为0；没有提交/集成/发布。
