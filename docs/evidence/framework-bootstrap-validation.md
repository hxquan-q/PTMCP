# 根目录框架初始化验证

日期：2026-10-23；任务T01-bootstrap。最终范围按用户最新指令：只建立框架/环境和前端依赖组合，不进行业务编码。

## 实际工程与边界

后端在根目录ptmcp-parent：Maven3.9.9 Wrapper（含发行包SHA-256）、Java21、Boot4.1.2，以及common/guard/admin/authz/gateway模块。只生成标准启动类、package说明、application配置和构建门禁；没有自定义控制器、认证、SQL算法或数据库访问代码。守门/授权能力未实现。

前端在根目录ptmcp-ui：Vue3.5.42、Vite8.2.2、TS5.9.3、Element Plus2.14.5、unplugin-icons24.0.0、Tailwind4.3.3、Animate.css4.1.1、VueUse Motion3.0.3，Pinia/Router入口就绪。只保留最小启动壳，没有业务页面或API封装。确切依赖与来源见 `ptmcp-ui/README.md`、`ptmcp-ui/UPSTREAM.md`（Vue 工程已于 2026-10-29 移除，React 工程见 ptmcp-ui-react）。

## 检查结果

| 检查 | 实际结果 |
| --- | --- |
| Wrapper生成与完整性 | 官方Maven Wrapper3.3.4生成only-script入口；Maven3.9.9 ZIP经官方SHA-512验证后登记SHA-256 |
| Maven clean verify | 六个reactor项目均SUCCESS，Spotless/Checkstyle/Java版本/依赖收敛与模块边界检查生效 |
| effective-pom / dependency:tree | 已生成至各target；gateway树只含一个JSQLParser5.4，没有Sa-Token或若依依赖 |
| pnpm check | ESLint零warning、Prettier、vue-tsc、Vite构建通过；不使用passWithNoTests/跳过选项 |
| 三个真实JAR启动 | admin8080/authz8081/gateway8082的/actuator/health均HTTP200、status=UP |
| 业务能力边界 | admin /api/v1/me/history、authz /oauth2/token、gateway /mcp 均404，框架不冒充业务实现 |
| Vite与开发代理 | 5173可访问；三条/backend代理实际返回对应服务health=UP |
| 浏览器依赖组合 | 页面真实出现Element Plus Tag与一个本地SVG；main计算样式为grid；Motion指令已注册，Animate.css样式已加载 |
| 视口与错误 | 1280×800和390×844检查；无横向溢出；最后一次导航控制台0error/0warning |
| 最终组合检查 | python scripts/check.py --all 退出0，含26个既有检查器回归与实际Maven verify/pnpm check；验证进程已结束，四个框架端口监听数0 |

浏览器截图、HTTP结果在本机`.local`（忽略、不分发）；框架构建日志在D:/config/ptmcp/logs。启动验证使用受控前台工具会话，验证完结束会话，不注册后台应用或自启任务。

## 范围调整与修正

本轮早期曾规划状态接口/默认拒绝测试，记录了缺少启动配置和缺少health模块导致的RED；用户随后明确只搭框架，不要业务编码，因此撤回本轮新增、尚未实现的相关测试和Vitest入口，而非实现这些接口。既有26个检查器回归未删除或降级；不把取消的测试记作通过。当前没有业务测试，也没有任何业务验收PASS。

修正了从错误工作目录定位Checkstyle配置的问题；实际在父工程目录运行Wrapper。Animate.css的@charset不能嵌套在CSS layer内，改为独立导入后构建无该警告；禁用Preflight后补最小box-sizing约定，保证手机宽度布局。favicon使用空data地址，避免未配置图标造成404。

## 尚未验证

远端CI尚未实际运行；只核对并配置了后端verify、前端冻结安装/check与文档检查任务。后续业务认证、MCP、SQL、数据库迁移、ASVS和安全/负载/客户端测试均未进行。没有产品提交、推送或生产部署，评审为作者自审。
