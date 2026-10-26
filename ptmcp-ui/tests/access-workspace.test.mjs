import assert from 'node:assert/strict';
import test from 'node:test';
import { AccessError, deferred, flushAccess, mountAccessWorkspace } from './helpers/mount-access-workspace.mjs';

const grant = {
  id: '10',
  subjectType: 'USER',
  subjectId: '5',
  resourceType: 'OBJECT',
  resourceId: '1',
  resourceGeneration: '1',
  action: 'META',
  effect: 'ALLOW',
  includeDescendants: false,
  validFrom: '2026-10-25T00:00:00.000Z',
  validTo: null,
  reason: 'Synthetic rule',
  version: '1',
  status: 'ACTIVE'
};
const resource = (id) => ({ key: `OBJECT:${id}`, id, label: `Object ${id}`, type: 'OBJECT', generation: '1' });
const decision = {
  decisions: [{ resourceId: '1', meta: true, read: true, sourceGrantIds: ['10'] }],
  evaluatedAt: '2026-10-25T00:00:00Z',
  nextBoundaryAt: null
};
async function selectContext(page) {
  page.state.subjectId.value = '5';
  page.state.platformId.value = '7';
  page.state.selectResource(resource('1'));
  await flushAccess();
}

test('selecting another resource invalidates an edit whose detail is still loading', async () => {
  const detail = deferred();
  const page = await mountAccessWorkspace({ getGrant: () => detail.promise });
  try {
    await selectContext(page);
    const editing = page.state.openEdit(grant);
    page.state.selectResource(resource('2'));
    detail.resolve(grant);
    await editing;
    assert.equal(page.state.selectedResource.value.id, '2');
    assert.equal(page.state.dialogVisible.value, false);
    assert.equal(page.state.draftTarget.value, null);
  } finally {
    page.close();
  }
});

test('a conflict invalidates a pending preview and preserves the edit draft', async () => {
  const preview = deferred();
  const page = await mountAccessWorkspace({
    getGrant: async () => grant,
    previewAccess: () => preview.promise,
    saveGrant: async () => {
      throw new AccessError(409);
    }
  });
  try {
    await selectContext(page);
    await page.state.openEdit(grant);
    page.state.draft.reason = 'Local draft';
    const calculating = page.state.runPreview();
    await page.state.save();
    preview.resolve(decision);
    await calculating;
    assert.equal(page.state.preview.value, null);
    assert.equal(page.state.draft.reason, 'Local draft');
    assert.equal(page.state.latestConflict.value.id, grant.id);
  } finally {
    page.close();
  }
});

test('late conflict details cannot repopulate the workspace after session invalidation', async () => {
  const detail = deferred();
  let reads = 0;
  const page = await mountAccessWorkspace({
    getGrant: () => (++reads === 1 ? Promise.resolve(grant) : detail.promise),
    saveGrant: async () => {
      throw new AccessError(412);
    }
  });
  try {
    await selectContext(page);
    await page.state.openEdit(grant);
    page.state.draft.reason = 'Local draft';
    const saving = page.state.save();
    await flushAccess();
    page.clear();
    detail.resolve({ ...grant, version: '2' });
    await saving;
    assert.equal(page.state.latestConflict.value, null);
    assert.equal(page.state.dialogVisible.value, false);
    assert.deepEqual(page.state.grants.value, []);
  } finally {
    page.close();
  }
});

test('revocation conflict clears previews and refreshes the changed rule without replaying deletion', async () => {
  let reads = 0,
    deletes = 0;
  const page = await mountAccessWorkspace({
    listGrants: async () => {
      reads += 1;
      return [{ ...grant, status: reads > 1 ? 'REVOKED' : 'ACTIVE' }];
    },
    previewAccess: async () => decision,
    revokeGrantApi: async () => {
      deletes += 1;
      throw new AccessError(409);
    }
  });
  try {
    await selectContext(page);
    await page.state.runPreview();
    await page.state.revoke(grant);
    assert.equal(page.state.preview.value, null);
    assert.equal(reads, 2);
    assert.equal(page.state.grants.value[0].status, 'REVOKED');
    assert.equal(deletes, 1);
    assert.ok(page.state.pageError.value);
  } finally {
    page.close();
  }
});

test('switching a resource during save releases the pending lock after the response', async () => {
  const response = deferred();
  const page = await mountAccessWorkspace({ getGrant: async () => grant, saveGrant: () => response.promise });
  try {
    await selectContext(page);
    await page.state.openEdit(grant);
    page.state.draft.reason = 'Local draft';
    const saving = page.state.save();
    await flushAccess();
    page.state.selectResource(resource('2'));
    response.resolve(grant);
    await saving;
    assert.equal(page.state.saving.value, false);
    assert.equal(page.state.dialogVisible.value, false);
  } finally {
    page.close();
  }
});

test('a rule revoked while its detail loads cannot be opened for editing', async () => {
  const page = await mountAccessWorkspace({ getGrant: async () => ({ ...grant, status: 'REVOKED', version: '2' }) });
  try {
    await selectContext(page);
    await page.state.openEdit(grant);
    assert.equal(page.state.dialogVisible.value, false);
    assert.match(page.state.pageError.value, /撤销|不可编辑/);
  } finally {
    page.close();
  }
});

test('confirmation states both the previous and proposed validity window', async () => {
  const page = await mountAccessWorkspace({ getGrant: async () => grant, saveGrant: async () => grant });
  try {
    await selectContext(page);
    await page.state.openEdit(grant);
    page.state.draft.from = new Date('2026-12-01T00:00:00Z');
    page.state.draft.to = new Date('2026-12-02T00:00:00Z');
    await page.state.save();
    const message = page.calls[0][0];
    assert.match(message, /台北/);
    assert.match(message, /无结束/);
    assert.match(message, /2026\/12\/1|2026-12-01|2026年12月1/);
    assert.match(message, /2026\/12\/2|2026-12-02|2026年12月2/);
  } finally {
    page.close();
  }
});

test('closing the editor prevents a pending detail refresh from reopening it', async () => {
  const detail = deferred();
  let reads = 0;
  const page = await mountAccessWorkspace({ getGrant: () => (++reads === 1 ? Promise.resolve(grant) : detail.promise) });
  try {
    await selectContext(page);
    await page.state.openEdit(grant);
    const reopening = page.state.openEdit(grant);
    page.state.dialogVisible.value = false;
    detail.resolve({ ...grant, version: '2' });
    await reopening;
    assert.equal(page.state.dialogVisible.value, false);
    assert.equal(page.state.editing.value.version, '1');
  } finally {
    page.close();
  }
});

test('a late revocation error cannot replace the next subject context', async () => {
  const response = deferred();
  const page = await mountAccessWorkspace({ revokeGrantApi: () => response.promise, previewAccess: async () => decision });
  try {
    await selectContext(page);
    const revoking = page.state.revoke(grant);
    await flushAccess();
    page.state.subjectId.value = '6';
    await flushAccess();
    await page.state.runPreview();
    response.reject(new AccessError(409));
    await revoking;
    assert.equal(page.state.pageError.value, '');
    assert.equal(page.state.preview.value, null);
    assert.equal(page.state.pendingDelete.value, '');
    await page.state.runPreview();
    assert.deepEqual(page.state.preview.value, decision);
  } finally {
    page.close();
  }
});

test('an uncertain write clears the former preview and blocks recalculation until the rules are reloaded', async () => {
  const response = deferred();
  let previews = 0;
  const page = await mountAccessWorkspace({
    getGrant: async () => grant,
    saveGrant: () => response.promise,
    previewAccess: async () => {
      previews += 1;
      return decision;
    }
  });
  try {
    await selectContext(page);
    await page.state.runPreview();
    await page.state.openEdit(grant);
    page.state.draft.effect = 'DENY';
    const saving = page.state.save();
    await flushAccess();
    assert.equal(page.state.preview.value, null);
    await page.state.runPreview();
    assert.equal(previews, 1);
    response.reject(new AccessError(0));
    await saving;
    assert.equal(page.state.unknownOutcome.value, true);
    await page.state.runPreview();
    assert.equal(previews, 1);
    page.state.dialogVisible.value = false;
    await page.state.loadGrants();
    await page.state.runPreview();
    assert.equal(previews, 2);
  } finally {
    page.close();
  }
});
