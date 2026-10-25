# PTMCP 业务前端

Vue3 + TypeScript + Vite，运行入口位于本仓库根目录的 `ptmcp-ui`。现有源码已包含管理/个人布局、Cookie会话与CSRF请求层、权限路由，以及数据源、任务、元数据、组织和个人门户页面。逐页范围与缺口见[业务覆盖矩阵](../docs/frontend/business-coverage.md)，开发和验收证据由 `tasks/work-items/T17-ui-business.md` 维护；页面存在或构建通过不等于业务验收完成。

管理委派、平台/客户端管理和全局审计路由当前显示接口未接入说明，不会回退为模拟成功。授权服务登录/同意页由 `ptmcp-authz` 独立承载，不使用控制台Cookie。

## 当前依赖分工

| 依赖 | 用途与接线 |
| --- | --- |
| Element Plus 2.14.5 | 组件库；ConfigProvider使用中文；组件由unplugin-vue-components按需导入 |
| Iconify / unplugin-icons 24.0.0 | 构建时生成本地SVG组件；预装@iconify-json/ep，使用IconEpXxx，无运行时CDN请求 |
| Tailwind CSS 4.3.3 | @tailwindcss/vite插件，负责布局/间距/响应式等工具样式 |
| Animate.css 4.1.1 | 已导入，按需使用预设class，不默认给页面加动画 |
| @vueuse/motion 3.0.3 | MotionPlugin已注册，后续可用v-motion做交互/进入动效 |
| Pinia / Vue Router | 内存会话与权限状态、管理/个人布局、登录恢复和功能码路由 |

所有精确版本和传递依赖以package.json、pnpm-lock.yaml为准。Vite8/@tailwindcss/vite4的peer范围、Vue3与motion的peer范围已核对，并实际构建验证。

## 样式与动效约定

`src/styles/index.css` 不加载Tailwind Preflight，避免重置Element Plus表单/按钮。CSS layer顺序为theme/base/components/utilities，Element Plus样式在components，工具类在utilities。布局优先作用于外层容器；组件主题优先用props/CSS变量。

Animate.css单独导入，避免将其@charset放进CSS layer产生优化警告。同一元素不要同时用Animate.css和motion控制相同的transform/opacity；实际动效遵循prefers-reduced-motion。

Iconify自动安装关闭；新图标集显式加依赖和锁文件。工作台使用PTMCP品牌、浅灰顶栏、白色侧栏和蓝色导航状态；菜单依照服务端功能码显示。

## 安装、检查和启动

从仓库根的PowerShell执行：

```powershell
. .\scripts\Enter-Dev.ps1
cd ptmcp-ui
pnpm install --frozen-lockfile --ignore-scripts
pnpm check
pnpm test:contracts
pnpm dev
```

启动地址为 `http://127.0.0.1:5173`，Ctrl+C停止。开发代理为 `/backend/admin` →8080、`/backend/authz` →8081、`/backend/gateway` →8082；页面通过同源代理调用真实接口。后端未启动时显示连接错误，工程不内置账号或凭据。

`pnpm check` 顺序执行ESLint、Prettier、vue-tsc和Vite构建；`pnpm test:contracts` 使用Node 24执行脚本列出的契约测试。现有传输层与资源适配用例覆盖长整数、CSRF、401、写请求不自动重放、预览资源类型编码和时间窗；运行范围以package.json为准。它们不替代页面端到端测试、真实权限验证或OAuth客户端联调。格式修复用 `pnpm format`，CI不write/fix。

`src/services/http.ts` 集中管理传输与错误，`src/stores/session.ts` 只在内存保存会话/权限。业务服务位于 `src/services`，页面位于 `src/views/admin`、`src/views/me`。当前后端成功响应主要为DTO直出，错误为 `{code,codeNumber,retryable}`；统一传输层兼容明确的 `R<T>` 外壳，但这不证明后端已完成ADR0010的响应迁移。密码、PAT与会话凭据不写入浏览器持久存储。

自动生成的src/components.d.ts由Vite组件插件更新，保留以支持首次typecheck；不手工改生成内容。配置继承来源见[UPSTREAM](UPSTREAM.md)，框架和业务规范见[前端指南](../docs/frontend/dev-guide.md)。
