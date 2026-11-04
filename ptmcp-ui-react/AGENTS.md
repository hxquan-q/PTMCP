# React 前端规则

适用于 `ptmcp-ui-react/`，与[根规范](../AGENTS.md)同时适用。页面或模块确有差异时，再就近补充 `AGENTS.md`，不复制父规则。

## 按改动读取

| 改动 | 入口 |
| --- | --- |
| 页面、组件、样式、交互 | [视觉设计](../DESIGN.md)、[前端指南](../docs/frontend/dev-guide.md)、[页面规格](../docs/frontend/page-spec.md) |
| 请求、DTO、会话、权限、错误 | [前端指南](../docs/frontend/dev-guide.md)、[API 契约](../docs/backend/api-spec.md)；接口变化按[变更合同](../docs/standards/api-change-template.md)同步 |
| 格式、类型、构建配置 | [工程标准](../docs/standards/quality-tooling.md)；实际配置以工程文件为准 |

复用现有组件、设计令牌和 `src/services/` 的传输/会话处理；局部改动不另建组件库、请求层或全局状态体系。

预览使用合成数据，不能作为真实接口、OAuth 或权限验收；缺少后端能力的动作须明确呈现限制，不能用模拟成功掩盖失败。历史 Vue 证据不自动成为 React 的验收证据。

## 验证

先进入[项目环境](../docs/ops/development-environment.md)，命令见 [README](README.md)。代码切片运行 `pnpm test` 与 `pnpm check`；页面/交互变更补实际浏览器验证，分别记录响应式、键盘操作、真实接口联调及未验证范围。
