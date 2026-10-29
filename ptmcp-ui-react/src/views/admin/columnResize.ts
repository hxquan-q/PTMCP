/** 权限工作台三栏拖拽调宽 — 纯函数，与组件解耦供测试。栏 2 始终取余（100 - 栏0 - 栏1）。 */
export type ColumnWidths = [number, number];

const MIN = 14;
const MAX = 50;

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/** 分隔线 0：栏0 增减、栏1 反向补偿（栏2 不变）；分隔线 1：栏1 增减（栏2 取余补偿）。两栏均钳制在 [14,50]。 */
export function resizeColumns(start: ColumnWidths, divider: 0 | 1, deltaPct: number): ColumnWidths {
  if (divider === 0) {
    return [clamp(start[0] + deltaPct, MIN, MAX), clamp(start[1] - deltaPct, MIN, MAX)];
  }
  return [start[0], clamp(start[1] + deltaPct, MIN, MAX)];
}

/** 第三栏宽度（取余），保证总和不超 100。 */
export function remainderColumn(widths: ColumnWidths): number {
  return Math.max(0, 100 - widths[0] - widths[1]);
}
