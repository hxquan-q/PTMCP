const taipeiFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Taipei',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

/** REST instants must include an offset; never interpret them in the browser's timezone. */
export function formatTaipeiTime(value: string | null | undefined, precision: 'minute' | 'second' = 'second'): string {
  if (!value || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value)) return '—';
  const instant = new Date(value);
  if (!Number.isFinite(instant.getTime())) return '—';
  const parts = taipeiFormatter.formatToParts(instant);
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value ?? '';
  const dateTime = `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}`;
  return precision === 'minute' ? dateTime : `${dateTime}:${part('second')}`;
}
