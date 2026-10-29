# ptmcp-ui-react

PTMCP 数据访问管理平台的 React 实现，与 [ptmcp-ui](../ptmcp-ui)（Vue）并行运行、逐步接管的候选前端。设计基线与令牌体系（`--ptmcp-*`、`.data-table`、`.table-card` 等）与 Vue 版同源；能力以本仓 [页面规格](../docs/frontend/page-spec.md) 与 [前端指南](../docs/frontend/dev-guide.md) 为准。

## 运行

```bash
pnpm install
pnpm dev:preview   # 127.0.0.1:5175，合成后端（src/preview/server.ts，无需 Java）
pnpm dev           # 真实后端模式：代理 /backend/admin→8080、/backend/authz→8081、/backend/gateway→8082
pnpm test          # node --experimental-strip-types --test tests/（契约 + 纯函数）
pnpm check         # eslint + prettier + tsc + vite build
```

Node 版本随根仓 [开发环境](../docs/ops/development-environment.md)（当前 pin 24）。Windows 下若 WSL 内无 node，测试用 `pnpm exec node --experimental-strip-types --test tests/`。

## 架构

- **路由**：自研 hash 路由（`App.tsx` + `navigation.ts`）：精确 → 前缀（详情页取尾段）→ 404；路由与导航项挂 `permission`，无权渲染 403，菜单按 `session.permissions` 过滤。
- **会话**：`services/session.ts` 探测/登录/登出；`http.ts` 在 401 时广播 `onSessionExpired`（排除 login/csrf 引导），App 切登录页并记录 `safeReturnTo`，重登后回跳。
- **服务层**：`services/http.ts`（同源 REST、CSRF 引导、大整数 ID 保真、幂等键）；`data.ts` / `access.ts` / `orgPortalApi.ts` / `mePortalApi.ts` 按 `/api/v1` 契约分域；写操作统一 If-Match 乐观并发，412 在页面呈现三方 diff/变基。
- **preview server**：`src/preview/server.ts` 为纯前端合成后端（与 Vue 版同源分叉），支撑无后端开发与契约测试；测试用 fetch 代理直连实例。
- **组件**：零第三方 UI 依赖；`components/common.tsx` 提供 Dialog/Drawer（含焦点圈禁）、表格、OptionCard、Stepper 等；样式在 `styles/index.css`（含 ≤900px/≤620px 响应式与 focus-visible）。

## 与 Vue 版的能力对等（2026-10-30）

- 路由对等：admin 13 + me 8（含 4 条详情前缀路由）全部就位；差异仅 Vue 的静态错误页样式细节。
- 功能对等：数据源（列表/详情/编辑/创建/测试/同步/冲突）、元数据与字典（含字典编辑 + 变基）、权限矩阵（三栏 + 新增规则 + 有效权限预览）、管理委派（新建/撤销）、部门/用户/角色/平台与客户端 CRUD（含 secret 一次性展示）、审计（列表 + 解密弹窗 30s 自动清除）、操作日志、个人门户（字典/接入 4 步向导 + 服务端模板/令牌创建/授权/历史 + 详情/安全）。
- 超出 Vue 的部分：390px 响应式、Dialog/Drawer 焦点圈禁、404 未知路由页为 React 版先行实现。
- 列表能力：全部 10 个列表页筛选/游标分页与 Vue 对齐且走服务端过滤；个人视角页面零 admin 端点依赖；登录门/会话过期回跳/改密即登出/权限门控（路由 403 + 按钮 SENSITIVE_ACTIONS 11 动作）齐备。
- 测试：`tests/` 90+ 例（http/preview/uuid/portalIds/角色/组织/平台客户端/委派/审计查询与解密/令牌授权/字典/数据源创建/导航/会话/树/模板/历史审计列表筛选/密码强度）。

## 已知差距（接管前须补）

1. 真实后端联调：preview 之外未跑过（需本机 Java + MySQL/PG）；403 拒绝分支无在线实证。
2. 组件级测试（RTL/jsdom）缺失，交互逻辑由契约测试 + Playwright 走查覆盖。
3. 390px 仅接入配置页做了截图走查；其余页面未逐页核对。
4. 接入配置「我的接入」列表两端均为静态种子（后端无持久化端点）；真实部署以 authorizations/tokens 为事实源。

## 双栈并行约定

Vue 应用保持功能全集与测试基线；React 侧每个切片以 `tests/` 增量 + Playwright 截图（`verify-*.png` 存仓库根）作为证据。验收由人工视觉接受（pending），接管节奏由用户决定。
