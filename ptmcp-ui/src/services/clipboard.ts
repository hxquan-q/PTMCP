/**
 * Copy helper with a fallback for insecure HTTP origins.
 *
 * `navigator.clipboard` is restricted to secure contexts. The public
 * development server runs on plain HTTP, where the legacy execCommand path is
 * still needed for one-click copying.
 */
export async function copyText(text: string): Promise<void> {
  const clipboard = globalThis.navigator?.clipboard;
  if (clipboard && typeof clipboard.writeText === 'function') {
    await clipboard.writeText(text);
    return;
  }
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.appendChild(area);
  area.select();
  try {
    if (typeof document.execCommand !== 'function' || !document.execCommand('copy')) {
      throw new Error('复制失败，请手动选择文本。');
    }
  } finally {
    area.remove();
  }
}
