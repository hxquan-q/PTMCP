import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

// Vite resolves extensionless source imports; mirror that only while loading these TS modules.
const sourceImports = registerHooks({
  resolve(specifier: string, context: { parentURL?: string }, nextResolve: (s: string, c: unknown) => unknown) {
    const extensionlessSource = context.parentURL?.includes('/src/') && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier);
    return nextResolve(extensionlessSource ? `${specifier}.ts` : specifier, context);
  },
});
const { previewAccess } = await import('../src/services/access.ts');
const { encodeResourceId } = await import('../src/services/resourceIds.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

interface CapturedRequest {
  path: string;
  body: Record<string, unknown>;
}

async function withPreviewServer(fn: (requests: CapturedRequest[]) => Promise<void>): Promise<void> {
  const server = createPreviewServer();
  const originalFetch = globalThis.fetch;
  const requests: CapturedRequest[] = [];
  setCsrfToken('test-csrf');
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input).replace('/backend/admin', '');
    const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as Record<string, unknown>) : {};
    requests.push({ path, body });
    return server(path, {
      method: init?.method,
      headers: init?.headers,
      body,
      signal: init?.signal,
    });
  }) as typeof fetch;
  try {
    await fn(requests);
  } finally {
    globalThis.fetch = originalFetch;
  }
}

test('previewAccess sends only subjectUserId/platformId/resourceIds and no subjectType/subjectId', async () => {
  await withPreviewServer(async (requests) => {
    const objectId = encodeResourceId('OBJECT', '1');
    const preview = await previewAccess({ subjectUserId: '2', platformId: '1', resourceIds: [objectId] });

    const [call] = requests.filter((r) => r.path.includes('/acl/preview'));
    assert.ok(call, 'previewAccess must POST /api/v1/admin/acl/preview');
    assert.deepEqual(Object.keys(call.body).sort(), ['platformId', 'resourceIds', 'subjectUserId']);
    assert.equal(call.body.subjectUserId, '2');
    assert.equal(call.body.platformId, '1');
    assert.deepEqual(call.body.resourceIds, [objectId]);

    // 命中 USER 2 对 OBJECT 1 的 ALLOW；返回的 resourceId 仍是编码值，sourceGrantIds 为字符串。
    assert.equal(preview.decisions.length, 1);
    assert.equal(preview.decisions[0].resourceId, objectId);
    assert.equal(preview.decisions[0].read, true);
    assert.deepEqual(preview.decisions[0].sourceGrantIds, ['1']);
    assert.equal(typeof preview.evaluatedAt, 'string');
  });
});

test('previewAccess only adds at when provided', async () => {
  await withPreviewServer(async (requests) => {
    await previewAccess({ subjectUserId: '2', platformId: '1', resourceIds: [encodeResourceId('OBJECT', '1')] });
    await previewAccess({
      subjectUserId: '2',
      platformId: '1',
      resourceIds: [encodeResourceId('OBJECT', '1')],
      at: '2026-10-30T00:00:00Z',
    });
    const calls = requests.filter((r) => r.path.includes('/acl/preview'));
    assert.equal('at' in calls[0].body, false);
    assert.equal(calls[1].body.at, '2026-10-30T00:00:00Z');
  });
});

test('unencoded metadata ids all-deny, proving the ResourceIds encoding is required', async () => {
  await withPreviewServer(async () => {
    const preview = await previewAccess({ subjectUserId: '2', platformId: '1', resourceIds: ['1'] });
    assert.equal(preview.decisions.length, 1);
    assert.equal(preview.decisions[0].resourceId, '1');
    assert.equal(preview.decisions[0].read, false, 'raw table id must not match an OBJECT grant');
    assert.deepEqual(preview.decisions[0].sourceGrantIds, []);
  });
});

test('encoded ids are matched by type as well as table id', async () => {
  await withPreviewServer(async () => {
    const asNamespace = encodeResourceId('NAMESPACE', '1');
    const preview = await previewAccess({ subjectUserId: '2', platformId: '1', resourceIds: [asNamespace] });
    assert.equal(preview.decisions[0].read, false, 'NAMESPACE 1 must not match an OBJECT 1 grant');
  });
});

test('previewAccess normalizes numeric decision fields to strings', async () => {
  const originalFetch = globalThis.fetch;
  setCsrfToken('test-csrf');
  globalThis.fetch = (async () =>
    new Response(
      JSON.stringify({
        decisions: [{ resourceId: 844424930131969, meta: true, read: true, sourceGrantIds: [1, 2] }],
        evaluatedAt: '2026-10-30T00:00:00Z',
        nextBoundaryAt: null,
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    )) as typeof fetch;
  try {
    const preview = await previewAccess({ subjectUserId: '2', platformId: '1', resourceIds: ['844424930131969'] });
    assert.equal(preview.decisions[0].resourceId, '844424930131969');
    assert.deepEqual(preview.decisions[0].sourceGrantIds, ['1', '2']);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
