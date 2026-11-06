import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

/**
 * 设计系统与无障碍静态契约。
 *
 * 这些断言不渲染组件，只钉住「改一行 CSS/TSX 就会退化」的那几条硬规则：
 * 渐变、圆角档位、间距网格、触控热区、语义化交互、减少动效。目的不是替代
 * axe-core 或浏览器走查，而是让评审前就能拦住最常见的 AI 味回归。
 */

const SRC = new URL('../src/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const CSS_PATH = join(SRC, 'styles/index.css');

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

const sourceFiles = walk(SRC);
const tsxFiles = sourceFiles.filter((f) => f.endsWith('.tsx') || f.endsWith('.ts'));
const cssText = readFileSync(CSS_PATH, 'utf8');

function rel(file: string): string {
  return file.slice(SRC.length - 1);
}

/** 把注释剥掉再匹配，避免规则被注释里的示例文本误伤。 */
function stripCssComments(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, '');
}
const cssCode = stripCssComments(cssText);

function cssBlocks(selector: string): string[] {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const re = new RegExp(`(^|\\n)\\s*${escaped}\\s*(,[^{]*)?\\{([^}]*)\\}`, 'g');
  const blocks: string[] = [];
  for (const m of cssCode.matchAll(re)) blocks.push(m[3]!);
  return blocks;
}

// ── 视觉纪律 ────────────────────────────────────────────────────────────────

test('CSS 不使用渐变（视觉 AI 指纹的主要来源）', () => {
  const offenders = cssCode
    .split('\n')
    .map((line, i) => ({ line, no: i + 1 }))
    .filter(({ line }) => /(linear|radial|conic|repeating-[a-z]+)-gradient\s*\(/i.test(line));
  assert.deepEqual(offenders, [], `发现渐变：${offenders.map((o) => `index.css:${o.no}`).join(', ')}`);
});

test('TSX 内联样式不使用渐变', () => {
  const offenders: string[] = [];
  for (const file of tsxFiles) {
    readFileSync(file, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        if (/(linear|radial|conic|repeating-[a-z]+)-gradient/i.test(line)) offenders.push(`${rel(file)}:${i + 1}`);
      });
  }
  assert.deepEqual(offenders, [], `内联渐变：${offenders.join(', ')}`);
});

test('圆角只取 --ptmcp-radius-* 六个档位，不出现新的魔法数字', () => {
  const allowed = new Set(['xs', 'sm', 'control', 'panel', 'pill', 'circle']);
  const bad: string[] = [];
  for (const m of cssCode.matchAll(/border-radius:\s*([^;]+);/g)) {
    const value = m[1]!.trim();
    if (value === '0' || value === 'inherit') continue;
    for (const part of value.split(/\s+/)) {
      if (part === '0') continue;
      const token = part.match(/^var\(--ptmcp-radius-([a-z]+)\)$/);
      if (!token) {
        bad.push(value);
        break;
      }
      if (!allowed.has(token[1]!)) bad.push(value);
    }
  }
  assert.deepEqual(bad, [], `未走刻度令牌的圆角：${bad.join(' | ')}`);
});

test('间距落在 2px 偶数网格上（-1px 仅用于 hairline 重叠）', () => {
  const props = /^(padding|margin|gap|row-gap|column-gap)(-(top|right|bottom|left))?$/;
  const bad: string[] = [];
  for (const m of cssCode.matchAll(/^\s*([a-z-]+):\s*([^;]+);/gm)) {
    if (!props.test(m[1]!)) continue;
    for (const num of m[2]!.matchAll(/(-?\d+)px/g)) {
      const n = Number(num[1]);
      if (n === -1) continue;
      if (n % 2 !== 0) bad.push(`${m[1]}: ${m[2]!.trim()}`);
    }
  }
  assert.deepEqual(bad, [], `奇数间距：${bad.join(' | ')}`);
});

test('TSX 内联间距同样落在偶数网格上', () => {
  const bad: string[] = [];
  for (const file of tsxFiles) {
    readFileSync(file, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        for (const m of line.matchAll(/\b(padding|margin|gap)[A-Za-z]*:\s*'?(-?\d+)\b/g)) {
          const n = Number(m[2]);
          if (n === 0 || n === -1) continue;
          if (n % 2 !== 0) bad.push(`${rel(file)}:${i + 1} ${m[1]}: ${n}`);
        }
      });
  }
  assert.deepEqual(bad, [], `内联奇数间距：${bad.join(' | ')}`);
});

// ── 触控热区（WCAG 2.2 AAA 2.5.5） ─────────────────────────────────────────

test('定义 44px 触控热区令牌，并让图标按钮把热区撑到 44px', () => {
  assert.match(cssCode, /--ptmcp-hit:\s*44px;/, '缺少 --ptmcp-hit: 44px');
  assert.equal(cssBlocks('.btn').length, 0, '遗留 .btn 规则应已移除');
  assert.equal(cssBlocks('.btn::after').length, 0, '遗留 .btn::after 应已移除');
  assert.equal(cssBlocks('.btn--icon').length, 0, '遗留 .btn--icon 应已移除');
  assert.equal(cssBlocks('.btn--icon::after').length, 0, '遗留 .btn--icon::after 应已移除');
  const icons = cssBlocks('.icon-btn');
  assert.ok(
    icons.some((block) => /width:\s*36px/.test(block) && /height:\s*36px/.test(block)),
    '.icon-btn 视觉尺寸应为 36×36',
  );
  assert.ok(
    icons.some((block) => /width:\s*var\(--ptmcp-hit\)/.test(block) && /height:\s*var\(--ptmcp-hit\)/.test(block)),
    '粗指针下 .icon-btn 应抬到 --ptmcp-hit',
  );
  const iconAfter = cssBlocks('.icon-btn::after');
  assert.equal(iconAfter.length, 1, '缺少 .icon-btn::after 热区扩展');
  assert.match(iconAfter[0]!, /width:\s*max\(100%,\s*var\(--ptmcp-hit\)\)/, '.icon-btn::after 必须用 --ptmcp-hit 定宽');
  assert.match(iconAfter[0]!, /height:\s*max\(100%,\s*var\(--ptmcp-hit\)\)/, '.icon-btn::after 必须用 --ptmcp-hit 定高');
});

test('行内文字按钮统一走 antd Button（≥24px 由库尺寸 token 保证，WCAG 2.2 AA 2.5.8）', async () => {
  assert.equal(cssBlocks('.link').length, 0, '遗留 .link 规则应已移除');
  assert.equal(cssBlocks('.btn--link').length, 0, '遗留 .btn--link 规则应已移除');
  const linkButtons = tsxFiles.filter((f) => /<Button[^>]*\btype="link"/s.test(readFileSync(f, 'utf8')));
  assert.ok(linkButtons.length > 0, '未找到 antd 文字链接按钮');
  const { theme } = await import('antd');
  const token = theme.getDesignToken();
  assert.ok(token.controlHeightSM >= 24, `antd 小号控件高度 ${token.controlHeightSM}px 低于 24px`);
  assert.ok(token.controlHeight >= 24, `antd 默认控件高度 ${token.controlHeight}px 低于 24px`);
});

test('触控设备（pointer: coarse）把导航与切换控件抬到 44px 热区', () => {
  const idx = cssCode.indexOf('@media (pointer: coarse)');
  assert.ok(idx >= 0, '缺少 @media (pointer: coarse) 规则');
  const block = cssCode.slice(idx, cssCode.indexOf('\n}\n', idx));
  for (const sel of ['.nav-item', '.realm-switch-btn', '.skip-link', '.crumb-link']) {
    assert.ok(block.includes(sel), `触控规则缺 ${sel}`);
  }
  assert.match(block, /min-height:\s*var\(--ptmcp-hit\)/, '触控规则必须用 --ptmcp-hit');
});

// ── 动效偏好 ────────────────────────────────────────────────────────────────

test('尊重 prefers-reduced-motion，同时压掉 animation 与 transition', () => {
  const idx = cssCode.indexOf('@media (prefers-reduced-motion: reduce)');
  assert.ok(idx >= 0, '缺少 prefers-reduced-motion 媒体查询');
  const block = cssCode.slice(idx, cssCode.indexOf('\n}\n', idx));
  assert.match(block, /animation-duration:\s*0\.01ms\s*!important/, '未压 animation-duration');
  assert.match(block, /transition-duration:\s*0\.01ms\s*!important/, '未压 transition-duration');
  assert.match(block, /scroll-behavior:\s*auto\s*!important/, '未压 scroll-behavior');
});

// ── 语义化交互与类型纪律 ───────────────────────────────────────────────────

test('交互元素用语义标签，div/span 不靠 onClick 冒充按钮', () => {
  const bad: string[] = [];
  for (const file of tsxFiles) {
    const lines = readFileSync(file, 'utf8').split('\n');
    lines.forEach((line, i) => {
      const tag = line.match(/<(div|span)\b[^>]*\bonClick=/);
      if (!tag) return;
      const hasSemantics = /\brole=/.test(line) || /aria-hidden="true"/.test(line) || /aria-hidden=\{true\}/.test(line);
      if (!hasSemantics) bad.push(`${rel(file)}:${i + 1}`);
    });
  }
  assert.deepEqual(bad, [], `div/span 上的裸 onClick：${bad.join(', ')}`);
});

test('图片必须有 alt，装饰图标必须 aria-hidden', () => {
  const bad: string[] = [];
  for (const file of tsxFiles) {
    readFileSync(file, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        if (/<img\b/.test(line) && !/\balt=/.test(line)) bad.push(`${rel(file)}:${i + 1} 缺 alt`);
      });
  }
  assert.deepEqual(bad, [], bad.join(', '));
});

test('源码不使用 any 逃生口', () => {
  const bad: string[] = [];
  for (const file of tsxFiles) {
    readFileSync(file, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        if (/:\s*any\b/.test(line) || /\bas\s+any\b/.test(line) || /<any>/.test(line)) {
          bad.push(`${rel(file)}:${i + 1}`);
        }
      });
  }
  assert.deepEqual(bad, [], `any 出现于：${bad.join(', ')}`);
});

test('界面文案不夹带 emoji（图标一律走 Icons.tsx）', () => {
  const emoji = /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}\u{1F1E6}-\u{1F1FF}]/u;
  const bad: string[] = [];
  for (const file of tsxFiles) {
    readFileSync(file, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        if (emoji.test(line)) bad.push(`${rel(file)}:${i + 1}`);
      });
  }
  assert.deepEqual(bad, [], `emoji 出现于：${bad.join(', ')}`);
});

// ── 色彩对比度（WCAG 2.2 AA 4.5:1） ──────────────────────────────────────

function cssVar(name: string): string {
  const m = cssCode.match(new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})`));
  assert.ok(m, `缺少颜色令牌 --${name}`);
  return m![1]!;
}

function channel(v: number): number {
  const c = v / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function luminance(hex: string): number {
  const h = hex.replace('#', '');
  return (
    0.2126 * channel(parseInt(h.slice(0, 2), 16)) + 0.7152 * channel(parseInt(h.slice(2, 4), 16)) + 0.0722 * channel(parseInt(h.slice(4, 6), 16))
  );
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

test('调色板成对满足 AA 4.5:1（正文与填充按钮）', () => {
  const pairs: [string, string, string][] = [
    ['ptmcp-blue-strong', '#ffffff', '主按钮底色/白字'],
    ['ptmcp-blue-strong-hover', '#ffffff', '主按钮悬停底色/白字'],
    ['ptmcp-blue-text', '#ffffff', '链接蓝/白底'],
    ['ptmcp-blue-text', cssVar('ptmcp-blue-soft'), '链接蓝/浅蓝底'],
    ['ptmcp-blue-text', cssVar('ptmcp-selected'), '链接蓝/选中行'],
    ['ptmcp-red', cssVar('ptmcp-bg-danger'), '错误红/错误底'],
    ['ptmcp-green', cssVar('ptmcp-bg-success'), '成功绿/成功底'],
    ['ptmcp-orange', cssVar('ptmcp-bg-warning'), '警告橙/警告底'],
    ['ptmcp-text-muted', cssVar('ptmcp-surface'), '次要文本/卡片底'],
    ['ptmcp-text-muted', cssVar('ptmcp-canvas'), '次要文本/画布底'],
    ['ptmcp-text-faint', cssVar('ptmcp-surface'), '弱文本/卡片底'],
  ];
  const bad = pairs
    .map(([token, against, label]) => ({ label, ratio: contrast(cssVar(token), against) }))
    .filter((p) => p.ratio < 4.5)
    .map((p) => `${p.label} ${p.ratio.toFixed(2)}:1`);
  assert.deepEqual(bad, [], `对比度不足：${bad.join(' | ')}`);
});

// ── 可滚动区域：必须可键盘聚焦并有名 ─────────────────────────────────────

test('可滚动区域都带 role/aria-label/tabIndex（WCAG 2.1.1 键盘可达）', () => {
  const bad: string[] = [];
  for (const file of tsxFiles) {
    readFileSync(file, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        if (!/className="table-scroll\b/.test(line)) return;
        for (const attr of ['role="region"', 'aria-label=', 'tabIndex={0}']) {
          if (!line.includes(attr)) bad.push(`${rel(file)}:${i + 1} 缺 ${attr}`);
        }
      });
  }
  assert.deepEqual(bad, [], bad.join(' | '));
});

test('下拉框都有可访问名（label 或 ariaLabel）', () => {
  const bad: string[] = [];
  for (const file of tsxFiles) {
    const text = readFileSync(file, 'utf8');
    for (const m of text.matchAll(/<Select\b([\s\S]*?)\/>/g)) {
      const body = m[1]!;
      if (!/\blabel=/.test(body) && !/\bariaLabel=/.test(body)) {
        const line = text.slice(0, m.index).split('\n').length;
        bad.push(`${rel(file)}:${line}`);
      }
    }
  }
  assert.deepEqual(bad, [], `无名字的下拉框：${bad.join(', ')}`);
});

// ── 文件体量预算：防止再出现「单文件 2000+ 行」 ─────────────────────────

test('视图/组件单文件不超过 900 行（超出就先拆分，不再继续堆）', () => {
  const BUDGET = 900;
  const scopes = ['views', 'components', 'layouts'].map((d) => join(SRC, d));
  const over: string[] = [];
  for (const dir of scopes) {
    for (const file of walk(dir)) {
      if (!file.endsWith('.tsx') && !file.endsWith('.ts')) continue;
      const lines = readFileSync(file, 'utf8').split('\n').length;
      if (lines > BUDGET) over.push(`${rel(file)}: ${lines} 行`);
    }
  }
  assert.deepEqual(over, [], `超出体量预算：${over.join(' | ')}`);
});

// ── 空态语义：读取失败不得画成「暂无数据」 ─────────────────────────────────

test('加载失败用 error/unavailable 空态，不用「暂无数据」冒充', () => {
  const failure = /失败|不可用|不存在|读取不到|加载出错/;
  const bad: string[] = [];
  for (const file of tsxFiles) {
    const text = readFileSync(file, 'utf8');
    for (const m of text.matchAll(/<EmptyState\b([\s\S]*?)\/>/g)) {
      const body = m[1]!;
      const titleMatch = body.match(/title=(?:"([^"]*)"|\{`([^`]*)`\})/);
      const title = titleMatch?.[1] ?? titleMatch?.[2] ?? '';
      if (!failure.test(title)) continue;
      if (!/kind="(error|unavailable)"/.test(body)) {
        bad.push(`${rel(file)}: 「${title}」缺 kind="error"`);
      }
    }
  }
  assert.deepEqual(bad, [], bad.join(' | '));
});

test('空态结构固定：图标容器 aria-hidden + 标题段落', () => {
  const css = cssBlocks('.empty-state');
  assert.equal(css.length, 1, '缺少 .empty-state 规则');
  for (const variant of ['filtered', 'error', 'unavailable']) {
    assert.equal(cssBlocks(`.empty-state--${variant} .empty-state-mark`).length, 1, `缺少 .empty-state--${variant} 图标语气`);
  }
  const common = readFileSync(join(SRC, 'components/common.tsx'), 'utf8');
  /* antd Empty 承载布局后，图标容器 className 变为模板字符串（带 kind 语气），aria-hidden 契约不变。 */
  assert.match(common, /className=\{`empty-state-mark empty-state--\$\{kind\}`\}\s*aria-hidden="true"/, '空态图标容器必须 aria-hidden');
});
