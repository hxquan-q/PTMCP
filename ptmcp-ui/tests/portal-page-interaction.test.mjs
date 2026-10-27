import assert from 'node:assert/strict';
import test from 'node:test';
import { mountPortalPage, settlePortal } from './helpers/mount-portal-page.mjs';
import { deferred } from './helpers/mount-access-workspace.mjs';
const pageOf = (items) => ({ items, nextCursor: null });

test('字典切换平台取消下级读取，迟到响应不清除新loading或恢复旧目录', async () => {
  const old = deferred();
  const current = deferred();
  const namespace = deferred();
  const signals = [];
  const page = await mountPortalPage('DictionaryView', {
    accessContext: {
      platforms: async () => pageOf([{ id: 'A' }, { id: 'B' }]),
      datasources: (id, _cursor, signal) => {
        signals.push(signal);
        return id === 'A' ? old.promise : current.promise;
      },
      namespaces: () => namespace.promise,
    },
    dictionary: { list: async () => pageOf([]) },
  });
  try {
    await settlePortal();
    assert.equal(page.state.loadingDatasources, true);
    page.state.platformId = 'B';
    assert.equal(signals[0].aborted, true);
    old.resolve(pageOf([{ id: 'old-source' }]));
    await settlePortal();
    assert.equal(page.state.loadingDatasources, true);
    assert.deepEqual(page.state.datasources, []);
    current.resolve(pageOf([{ id: 'new-source' }]));
    await settlePortal();
    assert.equal(page.state.loadingDatasources, false);
    assert.deepEqual(
      page.state.datasources.map((row) => row.id),
      ['new-source'],
    );
    page.state.datasourceId = 'new-source';
    assert.equal(page.state.loadingNamespaces, true);
    page.state.platformId = '';
    namespace.resolve(pageOf([{ id: 'old-namespace' }]));
    await settlePortal();
    assert.equal(page.state.datasourceId, '');
    assert.equal(page.state.namespaceId, '');
    assert.deepEqual(page.state.namespaces, []);
    assert.equal(page.state.loadingNamespaces, false);
  } finally {
    page.close();
  }
});

test('字典目录失败提供当前请求ID与独立重试，会话失效清空全部选择和目录', async () => {
  let failures = true;
  let namespaceCalls = 0;
  const page = await mountPortalPage('DictionaryView', {
    accessContext: {
      platforms: async () => pageOf([{ id: 'platform-1' }]),
      datasources: async () => {
        if (failures) throw Object.assign(new Error('directory unavailable'), { requestId: 'fixture-context' });
        return pageOf([{ id: 'source-1' }]);
      },
      namespaces: async () => {
        namespaceCalls++;
        return pageOf([]);
      },
    },
    dictionary: { list: async () => pageOf([]) },
  });
  try {
    await settlePortal();
    assert.equal(page.state.datasourceError, 'directory unavailable');
    assert.equal(page.state.datasourceRequestId, 'fixture-context');
    assert.equal(namespaceCalls, 0);
    failures = false;
    await page.state.retryDatasources();
    assert.equal(page.state.datasourceError, '');
    assert.equal(page.state.datasourceRequestId, null);
    assert.equal(page.state.datasources[0].id, 'source-1');
    page.clear();
    await settlePortal();
    assert.equal(page.state.platformId, '');
    assert.equal(page.state.datasourceId, '');
    assert.deepEqual(page.state.platforms, []);
    assert.deepEqual(page.state.datasources, []);
    assert.deepEqual(page.state.objects, []);
  } finally {
    page.close();
  }
});

test('客户端配置切换使旧模板作废，PAT提示只跟随当前模板且复制不注入凭证', async () => {
  const old = deferred();
  const current = deferred();
  const calls = [];
  const page = await mountPortalPage('IntegrationsView', {
    accessContext: { platforms: async () => pageOf([{ id: 'platform-1', tier: 3 }]) },
    mcpConfig: (platform, client) => {
      calls.push([platform, client]);
      return client === 'GENERIC' ? old.promise : current.promise;
    },
  });
  try {
    await settlePortal();
    page.state.clientType = 'CURSOR';
    await settlePortal();
    old.resolve({ configTemplate: 'old', authentication: [] });
    await settlePortal();
    assert.equal(page.state.config, null);
    assert.equal(page.state.loading, true);
    const template = '{"Authorization":"Bearer ${PTMCP_PAT}"}';
    current.resolve({ configTemplate: template, authentication: ['pat'] });
    await settlePortal();
    assert.equal(page.state.needsPat, true);
    assert.equal(page.state.templateParts.join(''), template);
    assert.deepEqual(calls, [
      ['platform-1', 'GENERIC'],
      ['platform-1', 'CURSOR'],
    ]);
    assert.deepEqual(page.copied, []);
    await page.state.copyConfig();
    assert.deepEqual(page.copied, [template]);
    page.clear();
    await settlePortal();
    assert.equal(page.state.config, null);
    assert.equal(page.state.needsPat, false);
  } finally {
    page.close();
  }
});
