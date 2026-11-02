import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { formatTaipeiTime } from '../src/services/dateTime.ts';

test('Taipei display preserves seconds and crosses midnight and year boundaries', () => {
  assert.equal(formatTaipeiTime('2026-12-31T16:00:01.123456Z'), '2027-01-01 00:00:01');
  assert.equal(formatTaipeiTime('2027-01-01T00:00:01+08:00', 'minute'), '2027-01-01 00:00');
  assert.equal(formatTaipeiTime('2026-12-31T11:00:01-05:00'), '2027-01-01 00:00:01');
});

test('missing offsets and invalid instants are never interpreted as local time', () => {
  for (const value of [null, undefined, '', 'not-a-date', '2026-12-31', '2026-12-31T16:00:00', '2026-13-31T16:00:00Z']) {
    assert.equal(formatTaipeiTime(value), '—');
  }
});

test('display does not depend on the process timezone', () => {
  const source = new URL('../src/services/dateTime.ts', import.meta.url).href;
  const script = `import { formatTaipeiTime } from ${JSON.stringify(source)}; process.stdout.write(formatTaipeiTime('2026-12-31T16:00:01Z'));`;
  for (const TZ of ['UTC', 'America/New_York', 'Asia/Tokyo']) {
    const result = spawnSync(process.execPath, ['--experimental-strip-types', '--input-type=module', '-e', script], {
      env: { ...process.env, TZ },
      encoding: 'utf8',
      timeout: 10_000,
    });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, '2027-01-01 00:00:01');
  }
});
