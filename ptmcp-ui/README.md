# PTMCP 前端框架

Vue3 + TypeScript + Vite，工程位于本仓库根目录的ptmcp-ui。当前仅最小启动壳，没有业务页面、请求封装、登录、权限或凭据存储。

## 当前依赖分工

| 依赖 | 用途与接线 |
| --- | --- |
| Element Plus 2.14.5 | 组件库；ConfigProvider使用中文；组件由unplugin-vue-components按需导入 |
| Iconify / unplugin-icons 24.0.0 | 构建时生成本地SVG组件；预装@iconify-json/ep，使用IconEpXxx，无运行时CDN请求 |
| Tailwind CSS 4.3.3 | @tailwindcss/vite插件，负责布局/间距/响应式等工具样式 |
| Animate.css 4.1.1 | 已导入，按需使用预设class，不默认给页面加动画 |
| @vueuse/motion 3.0.3 | MotionPlugin已注册，后续可用v-motion做交互/进入动效 |
| Pinia / Vue Router | 已接入基础应用入口，无业务store或权限路由 |

所有精确版本和传递依赖以package.json、pnpm-lock.yaml为准。Vite8/@tailwindcss/vite4的peer范围、Vue3与motion的peer范围已核对，并实际构建验证。

## 样式与动效约定

`src/styles/index.css` 不加载Tailwind Preflight，避免重置Element Plus表单/按钮。CSS layer顺序为theme/base/components/utilities，Element Plus样式在components，工具类在utilities。布局优先作用于外层容器；组件主题优先用props/CSS变量。

Animate.css单独导入，避免将其@charset放进CSS layer产生优化警告。同一元素不要同时用Animate.css和motion控制相同的transform/opacity；后续引入实际动效时处理prefers-reduced-motion。这次仅接通库，不进行页面布局设计或业务编码。

Iconify自动安装关闭；新图标集显式加依赖和锁文件。最小壳里的图标/ElTag/布局类只用于证明插件与样式链可运行，不表示业务完成。

## 安装、检查和启动

从仓库根的PowerShell执行：

```powershell
. .\scripts\Enter-Dev.ps1
cd ptmcp-ui
pnpm install --frozen-lockfile --ignore-scripts
pnpm check
pnpm dev
```

启动地址为 `http://127.0.0.1:5173`，Ctrl+C停止。开发代理预留 `/backend/admin` →8080、`/backend/authz` →8081、`/backend/gateway` →8082，不修改服务端CORS，不内置凭据。

`pnpm check` 顺序执行ESLint、Prettier、vue-tsc和Vite构建。当前没有业务测试，不用passWithNoTests或虚构测试结果；后续新增行为时再建立实际测试入口。格式修复用 `pnpm format`，CI不write/fix。

自动生成的src/components.d.ts由Vite组件插件更新，保留以支持首次typecheck；不手工改生成内容。配置继承来源见[UPSTREAM](UPSTREAM.md)，框架和业务规范见[前端指南](../docs/frontend/dev-guide.md)。
