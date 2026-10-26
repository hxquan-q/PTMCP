# T17-sap-impl：把 SAP 视觉方案落地到全部前端页面

ID: T17-sap-impl
Status: verifying
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-10-26

## 目标与边界

来源：用户要求"继续完成当前系统的开发，完成前端页面的优化"。设计权威为根目录 [DESIGN.md](../../DESIGN.md) 与 [T17-sap-visual](T17-sap-visual.md) 产出的效果图/`design.css`（docs/frontend/design/sap-20261023/）。本轮为该方案的实施授权；T17-sap-visual 原约定"用户确认后实施"，本次用户指令即为实施授权。

目标：把 18 组页面的配色、标题阶梯、状态色、控件圆角与选中文本统一到 SAP 令牌层，消除各页一次性配色与设计语言违规（眉题/kicker、装饰网格、粗侧边条），登录页 hero 对齐 DESIGN 的 navy 渐变面。

不做项：不改路由、业务逻辑、请求层、契约与测试断言；不重排已验证的页面结构；不改后端；不把预览模式升级为业务验收。行为不变量（会话、CSRF、权限、游标、冲突草稿）全部沿用 T17-ui-business。

## 实施与验证

单 Agent 串行。开工保留全部既有修改（含前一会话未提交的预览模式与登录/ACL 修复）。审计基准：views/components/layouts/preview 中硬编码色与令牌差集（改造前 40+ 个一次性 hex 值）。

改动面：

- `ptmcp-ui/src/styles/index.css`：SAP 令牌层（ink 阶梯、状态色 green/red/orange、tag 底色、chrome/band/wash、选中行、输入框边 #91a0ad、navy/sky、::selection）与 Element Plus 全量映射（success/warning/danger/info 及 light-3/5/7/8/9、文本/边框/填充色阶）；按钮 8px、输入 6px、tag 4px、dialog 12px 圆角；表头 chrome 底 + meta 文字；`.mono` 表格数字 tabular-nums。
- data/metadata 五页：裸 hex（青绿 #0f6f68 系、旧灰阶 #1d2938 系、#dce5e9 系）全部换令牌；h1 改纯黑；filter-card 底色改 chrome；链接/健康/失败色改 blue/green/red 令牌。
- org/me/audit/integrations/access 各页：移除全部眉题（eyebrow/section-kicker，共 19 处模板 + 样式块）；h1/h2 标题改 `--ptmcp-heading` 纯黑；清理永不生效的 var() fallback（#0070c9/#1d2939/#667085/#d9e2ec）。
- `PageHeader.vue`：移除无调用方的 eyebrow 死代码。
- `PageState.vue`/`VersionConflictPanel.vue`/`TokensView` secret 块/`IntegrationsView` 配置块：状态色对齐 SAP（danger #ffebeb/#aa0808、warning #fff3df/#8d5200、neutral #f0f3f5/#475e75）。
- `LoginView.vue`：aside 改 navy→blue 渐变（DESIGN navy-hero 面）、移除装饰网格与眉题、h2 tracking 收敛到 -0.04em、状态点改 sky；panel h1 纯黑。
- `PermissionsView.vue`：kicker 移除；冲突面板 3px 左色条改为 band-blue 面（mockup 词汇）。
- 预览组件（PreviewToolbar/PagesPreviewView/AuthorizationPreviewView）同步令牌，预览与真实页面一致。
- 信息保全：`DictionaryDetailView` 眉题承载的 objectType 并入副标题，不丢数据。

## 结果与证据

环境：Enter-Dev（Node 24.22.1/pnpm 11.9.0），前端命令 `pnpm --dir ptmcp-ui`；证据文件在忽略目录 `.playwright-mcp/`、`.local/`。

| 检查 | 结果与边界 |
| --- | --- |
| `pnpm --dir ptmcp-ui check` | PASS 退出0（ESLint/Prettier/vue-tsc/Vite 构建），最终态复跑通过。 |
| `pnpm --dir ptmcp-ui test:contracts` | 68/68 PASS 退出0。曾出现 27 fail 为 shell 环境解析到旧 Node（strip/transform 不支持），进入 Enter-Dev 环境后全过，非代码回归。 |
| `python scripts/check.py` | 退出0，48 项检查器回归通过。 |
| Chromium 无头走查 | 30 个预览入口 × 1440/390 共 60 次导航：console/pageerror 0；眉题 0；页面级横向溢出 0；业务页 h1 计算色全部 rgb(0,0,0)。 |
| 计算样式审计 | 主按钮 #0057d2/8px/白字；输入边 rgb(145,160,173)=#91a0ad；success tag #edf6ee/#256f3a/4px；表头底 rgb(245,246,247)+#475e75；悬停/选中 #eef6ff；dialog 12px（:root 变量被 EP 组件局部覆盖，改全局 .el-dialog 覆盖后生效）；登录 aside navy 渐变、sky 状态点。 |
| Impeccable detector | 首轮 2 项（PermissionsView 3px 左色条、LoginView 装饰网格）已修复，复扫 views/components/preview/index.css 计 0 项。 |
| 截图 | `.playwright-mcp/sap-impl-{datasources,permissions,audit,dictionary,login}-{1440,390}.png` 等 8 张，供人工复核；本会话模型不可读图，未把截图当自动验收。 |

评审类型：自审（令牌映射、信息保全、无行为改动）。无独立安全评审；本轮不触及鉴权/传输逻辑。

未验证/边界：真实后端联调与 18 组业务验收仍按 T17-ui-business 口径，不由本轮代替；字体仍为 Segoe UI/微软雅黑替代（72 Brand 为专有字体，DESIGN 已注明替代要求）；人工视觉接受 pending。

## 交接

当前焦点：SAP 视觉统一已入工作树，待用户视觉接受。改动与前一会话未提交的预览/登录/ACL 修复同树交织，未做混合归属的 Git 提交；集成提交留待统一处理，非放弃。无推送/发布。

下一步：用户确认视觉效果后，可与 T17-ui-business 剩余真实端点联调切片合并集成提交；再按覆盖矩阵补网络证据。
