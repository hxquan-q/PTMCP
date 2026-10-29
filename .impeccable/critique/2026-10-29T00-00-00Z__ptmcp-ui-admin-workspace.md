---
target: ptmcp-ui 管理工作台（本轮聚焦表格/状态/筛选/按钮/对齐五个视觉维度）
total_score: 34
max_score: 40
na_heuristics:
p0_count: 0
p1_count: 0
p2_count: 2
target_identity: "ptmcp-ui/src"
timestamp: 2026-10-29T00:00:00Z
slug: ptmcp-ui-admin-workspace
status: fixes-applied
---
Method: dual-agent (A: 设计评审 · B: 检测器+渲染测量)

## 修复后判断

选项 D 已把三项 P1 结构性缺陷全部接线，P2 三项视觉缺陷同步收敛。原 27/40 的"规范写了没接线"核心问题消除：`.ptmcp-data-table` 现接入 6 个表格页、行内操作统一为 link 三档、筛选区统一 strip、formatDate 单一实现、info 档色点过 AA、StatusBadge 五图标全渲染、字级与纵向间距收敛。**剩余两项为功能层遗留（非视觉规范）+ 一条轴线细节，属次轮范畴。**

## 已修复（原 P1/P2 → ✅）

### ✅ 1. 共享表格规范已接线（本轮全量穿透）
`.ptmcp-data-table` 现已接入**全部 17 个含表格的视图**（此前仅 6 页且多为单表误计）。裸 `<ElTable>` 清零；每文件 ElTable 标签数 = class 数已逐一核对。批量注入时曾误伤 `<ElTableColumn`（前缀匹配），已修复 11 个文件并复检无残留。

### ✅ 2. 行内操作统一为三档
`.ptmcp-edit-action` 描边胶囊已从 src 与 index.css 全量清除（0 引用）。统一为：行内主操作=无框 link、危险=danger link 裸露、低频收 `.ptmcp-row-actions`/`more` 尾部。

### ✅ 3. 筛选区统一 strip 形态
`filter-bar`/`filter-row`/私有 `filters` 网格全清，统一 `.ptmcp-filter-strip`（≤4 字段 inline）+ `.is-dense` 变体（>4 字段 top 标签）。查询按钮紧随字段，不再 `margin-left:auto` 推半屏。

### ✅ 4. 状态徽章对比度与图标
`--ptmcp-text-faint` `#8b9aa8`(2.88:1)→`#667582`（实测 4.74:1，过 AA）。StatusBadge 由 2 图标补全为 5 图标全渲染（check/close/warning/clock/question），`v-else` 兜底 question，色弱场景有图形冗余。

### ✅ 5. 时间格式化单源
`formatDate` 收敛到 `portalUtils` 唯一实现；DatasourcesView/DatasourceDetailView/JobDetailView/PermissionsView 私有副本全删，改 import。views 目录零 `Intl.DateTimeFormat`/`toLocaleString` 重复。

### ✅ 6. 字级与间距收敛
字号 13.5/15px 漂移值收敛（13.5→13、15→14）；纵向间距去掉 20px 中间档，统一 16（块内）/24（区块间）。PermissionsView/AuditView 等 20px gap/padding/margin 全改 16；`workspace-nav` 组间距取 24。

## 剩余待办（次轮，非本轮视觉范围）

### ◯ A. [功能] 审计跨页返回丢筛选（Nielsen #3/#6）
`AuditView`/`OperationsView` 进入详情再返回，筛选条件与时间窗重建为空。需路由 `keep-alive` 或查询态入 sessionStorage。**是功能缺陷不是视觉规范**，选项 D 未覆盖。

### ◯ B. [功能] 授权链路键盘可达 + 默认布局（Nielsen #7）
PermissionsView 资源树激活、授权提交路径的键盘可达性；默认布局加载节奏拖慢首屏。同上，功能层。

### ◯ C. [细节] 左对齐轴线在卡片处仍可能折断（Nielsen #4 尾巴）
`.ptmcp-table-section > .el-card__body { padding-inline: 0 }` 使卡内标题相对页头 24px 线缩进 0，而 `.cell` td 内 padding 0 16px——标题左缘与页头轴线、与首列文字三者未完全对齐。建议卡内标题容器补 `padding-inline:16px` 或重新核对轴线。

## 截图驱动复修（用户实拍反馈后）

选项 D 落地后用户实拍三屏指出布局层缺陷，已针对性修复（非方向问题，是接线后的对齐/密度细节）：

- **权限页 rules 列空鼓**：`rules-column` 上下两块面板，下块空态坍塌成大段留白、两列高度失衡。`.rules-panel` 改 `min-height:380px` flex 列，`page-state` 居中填充，三卡底边对齐。
- **元数据筛选参差不齐**：inline strip 五字段宽度漂移。改 `.is-dense` 网格 `minmax(160px,1fr)`，标签回 top、字段 `width:100%`、查询按钮 `align-self:end`。
- **授权页裸表**：ElTag→`StatusBadge`(pat domain)、操作列 `align=right fixed=right`+`.ptmcp-row-actions`、接 `ptmcp-data-table`。
- **卡内轴线折断**：`.el-card__body{padding-inline:0}` 使卡内标题相对首列 `.cell`(0 16px) 错位。给 `.section-heading`/`.ptmcp-table-heading`/`.ptmcp-scroll-hint`/`.ptmcp-meta-note` 补 `padding-inline:16px`；`.ptmcp-table-footer` 显式补回 `padding:16px`（其自带 `16px 0` 会被 shorthand 清掉 block）。
- **文字溢出推列**：加 `.cell > span:not([class])` 与 `.ptmcp-cell-truncate` 兜底省略号截断，长 ID/名称不再撑宽列。
- **斑马纹清除**：删 TokensView/HistoryView/DictionaryDetailView 残留的 `stripe`（Fiori 用 hairline 行分隔不铺底色），其余 15 表本就不带。全量核对 18 个 `<ElTable>` ↔ 18 个 `ptmcp-data-table` 一一对应。

**复核教训**：首轮"6 页已接线"实为误计——`grep -c class` 按文件计不等于"每个表都接"；且 `ElTable` 是 `ElTableColumn` 前缀，裸 `s/<ElTable/` 曾误伤 11 文件。已记 [[verify-batch-class-injection]]。

## 做得好的（保留）

- **令牌层是真 Fiori**：墨色 5 档、线 3 档、状态色只给语义、交互色注释"不做装饰"、卡片显式无阴影，贯彻到 table-section。
- **状态体系把 UNKNOWN 当一等公民**：每 domain 有 info+question 兜底，注释"保留原码绝不推断"；审计页 DENY=warning、判定/执行错误分列、preview-guidance 主动提示权限拒绝≠故障。
- **检测器零发现**：impeccable detect 对 81 文件零 findings（含绕过忽略复检）。

## Nielsen 启发式评分（修复后）

| # | 评估项 | 前 | 现 | 依据 |
|---|---|---:|---:|---|
| 1 | 系统状态可见 | 3 | 4 | icon 补全后状态反馈完整 |
| 2 | 与用户语言匹配 | 3 | 4 | formatDate 时区统一 Asia/Taipei |
| 3 | 用户控制与自由 | 3 | 3 | 审计往返丢筛选仍未修 |
| 4 | 一致性与标准 | 2 | 4 | 表格 chrome/行内操作/筛选/时间格式全统一 |
| 5 | 错误预防 | 3 | 3 | 危险操作档位已统一；执行/判定错误同 danger 相邻列仍可再分 |
| 6 | 识别而非记忆 | 2 | 2 | 审计关键列离屏、返回丢筛选依旧 |
| 7 | 灵活与效率 | — | — | 键盘授权链路、默认布局拖慢未评/未改 |
| 8 | 美学与极简 | — | — | 节奏收敛后更干净 |
| 9 | 错误恢复 | — | — | requestId 全链路保留 |
| 10 | 帮助与文档 | — | — | — |

## 验证

- `pnpm typecheck` ✅ · `pnpm format` ✅ · `pnpm test:contracts` **109/109**（Node 24）
- `pnpm lint` 7 errors 为**既有基线**（`.prettierrc.cjs`/`*.test.mjs` env no-undef），stash 复现，与改动无关
- 测试基建补丁：`mount-data-page.ts` / `permissions-view.test.mjs` 各补 `../../portalUtils` stub（新 import 暴露的映射缺口，+3 行）
