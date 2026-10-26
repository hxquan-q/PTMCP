import assert from 'node:assert/strict';
import test from 'node:test';
import { mountHistoryPage } from './helpers/mount-history-page.mjs';
import { deferred, flushAccess as flush } from './helpers/mount-access-workspace.mjs';

test('history filter change cancels old read without releasing a newer loading state', async () => {
  const first = deferred();
  const second = deferred();
  let calls = 0;
  const page = await mountHistoryPage(() => (++calls === 1 ? first.promise : second.promise));
  try {
    page.state.filters.tool = 'query';
    await flush();
    const current = page.state.load();
    first.resolve({ items: [{ requestId: 'old' }], nextCursor: 'old' });
    await flush();
    assert.equal(page.state.loading, true);
    assert.deepEqual(page.state.items, []);
    second.resolve({ items: [{ requestId: 'new' }], nextCursor: null });
    await current;
    assert.equal(page.state.loading, false);
    assert.equal(page.state.items[0].requestId, 'new');
  } finally {
    page.close();
  }
});

test('history marks edited filters unqueried and does not show stale errors', async () => {
  const page = await mountHistoryPage(async () => {
    throw new Error('offline');
  });
  try {
    await flush();
    assert.equal(page.state.loadError, 'offline');
    page.state.filters.result = 'SUCCESS';
    await flush();
    assert.equal(page.state.loadError, '');
    assert.equal(page.state.hasLoaded, false);
    assert.equal(page.state.loading, false);
  } finally {
    page.close();
  }
});

test('history keeps rows and cursor on pagination failure and retries that same cursor', async () => {
  const queries = [];
  const page = await mountHistoryPage(async (query) => {
    queries.push(query);
    if (queries.length === 2) throw new Error('offline');
    return { items: [{ requestId: String(queries.length) }], nextCursor: queries.length === 1 ? 'page-2' : null };
  });
  try {
    await flush();
    await page.state.load(true);
    assert.equal(page.state.items.length, 1);
    assert.equal(page.state.nextCursor, 'page-2');
    await page.state.retry();
    assert.equal(queries[2].cursor, 'page-2');
    assert.equal(page.state.items.length, 2);
  } finally {
    page.close();
  }
});

test('history reset restores recent window and ignores invalid windows and late cleared results', async () => {
  let calls = 0;
  const pending = deferred();
  const page = await mountHistoryPage(async () => {
    calls++;
    return calls === 1 ? { items: [], nextCursor: null } : pending.promise;
  });
  try {
    await flush();
    page.state.filters.from = new Date('invalid');
    await flush();
    await page.state.load();
    assert.equal(calls, 1);
    page.state.filters.tool = 'query';
    await flush();
    const reset = page.state.resetFilters();
    await flush();
    assert.equal(page.state.filters.tool, '');
    assert.equal(calls, 2);
    page.clear();
    pending.resolve({ items: [{ requestId: 'late' }], nextCursor: 'late' });
    await reset;
    assert.deepEqual(page.state.items, []);
    assert.equal(page.state.nextCursor, null);
    assert.equal(page.state.loading, false);
  } finally {
    page.close();
  }
});

test('history enforces the 31-day boundary and prevents duplicate pagination reads', async () => {
  const queries = [];
  const pending = deferred();
  const page = await mountHistoryPage(async (query) => {
    queries.push(query);
    return queries.length < 3 ? { items: [], nextCursor: 'next' } : pending.promise;
  });
  try {
    await flush();
    const from = new Date('2026-01-01T00:00:00Z');
    page.state.filters.from = from;
    page.state.filters.to = new Date(from.getTime() + 31 * 86400000 + 1);
    await page.state.load();
    assert.equal(queries.length, 1);
    page.state.filters.to = new Date(from.getTime() + 31 * 86400000);
    await page.state.load();
    assert.equal(queries.length, 2);
    assert.equal(queries[1].from, from.toISOString());
    const more = page.state.load(true);
    await page.state.load(true);
    assert.equal(queries.length, 3);
    pending.resolve({ items: [], nextCursor: null });
    await more;
  } finally {
    page.close();
  }
});

test('history access failure removes previous rows and invalidates their cursor', async () => {
  let calls = 0;
  const page = await mountHistoryPage(async () => {
    if (++calls === 1) return { items: [{ requestId: 'visible-before-denial' }], nextCursor: 'old' };
    throw Object.assign(new Error('forbidden'), { status: 403 });
  });
  try {
    await flush();
    await page.state.load(true);
    assert.deepEqual(page.state.items, []);
    assert.equal(page.state.nextCursor, null);
    assert.equal(page.state.failedAppend, false);
  } finally {
    page.close();
  }
});
