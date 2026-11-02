// Run against an isolated Vite development server, with Playwright's page passed to this function.
// Only the transport is synthetic; the mounted React dialog and its async state are real.
async function platformProofRetry(browserPage) {
  const context = await browserPage.context().browser().newContext();
  try {
    const page = await context.newPage();
    const origin = 'http://127.0.0.1:25177';
    await page.addInitScript(() => {
      const originalFetch = window.fetch;
      window.proofTest = {
        failure: 'CSRF_INVALID',
        writes: 0,
        withProof: [],
        writeVersions: [],
        platformReads: 0,
        platformVersion: null,
        committedVersion: null,
      };
      window.fetch = async (input, init) => {
        const path = String(input).replace('/backend/admin', '');
        if (!path.startsWith('/api/v1/')) return originalFetch(input, init);
        const { previewRequest } = await import('/src/preview/server.ts');
        const body = init?.body ? JSON.parse(init.body) : undefined;
        if (path.split('?')[0] === '/api/v1/admin/platforms' && (!init?.method || init.method === 'GET')) {
          const response = await previewRequest(path, { ...init, body });
          const data = await response.clone().json();
          window.proofTest.platformReads++;
          window.proofTest.platformVersion = data.items.find((row) => row.id === '2')?.version;
          return response;
        }
        if (path === '/api/v1/admin/platforms/2' && init?.method === 'PUT') {
          const state = window.proofTest;
          state.writes++;
          state.withProof.push(!!body?.proofConfig);
          state.writeVersions.push(new Headers(init.headers).get('If-Match'));
          const failure = state.failure;
          state.failure = null;
          if (failure === 'COMMITTED_NETWORK') {
            const response = await previewRequest(path, { ...init, body });
            if (!response.ok) throw new Error('synthetic write must commit before losing its response');
            const committed = await response.json();
            state.committedVersion = committed.version;
            throw new TypeError('synthetic response lost after commit');
          }
          if (failure === 'NETWORK') throw new TypeError('synthetic lost response');
          if (failure === 'INVALID_RESPONSE') return new Response('{', { status: 200 });
          if (failure) return new Response(JSON.stringify({ code: failure }), { status: failure === 'VERSION_CONFLICT' ? 412 : 403 });
        }
        return previewRequest(path, { ...init, body });
      };
    });
    const require = (condition, message) => {
      if (!condition) throw new Error(message);
    };
    await page.goto(`${origin}/#/admin/platforms`);
    const row = page.getByRole('row').filter({ hasText: 'preview-service' });
    await row.getByRole('button', { name: '编辑', exact: true }).click();
    const dialog = page.getByRole('dialog');
    const fingerprint = dialog.locator('input').nth(2);
    const save = dialog.getByRole('button', { name: '保存修改', exact: true });
    await fingerprint.fill('ab'.repeat(32));
    await save.click();
    await page.waitForFunction(() => window.proofTest.writes === 1);
    await page.waitForFunction(() => document.querySelector('[role="dialog"] input:nth-of-type(1)') !== null);
    await fingerprint.waitFor();
    require((await fingerprint.inputValue()) === '', 'failed request must clear proof material');
    await save.click();
    await page.evaluate(() => new Promise(requestAnimationFrame));
    require((await page.evaluate(() => window.proofTest.writes)) === 1, 'RED: cleared proof must not silently retry as retain-old');
    require(await dialog.isVisible(), 'failed rotation must stay open');
    await fingerprint.fill('cd'.repeat(32));
    await save.click();
    await dialog.waitFor({ state: 'hidden' });
    require(await page.evaluate(() => window.proofTest.withProof.every(Boolean)), 'rotation requests must contain fresh proof');

    // Cancelling a failed replacement is explicit, not an implicit blank retry.
    await row.getByRole('button', { name: '编辑', exact: true }).click();
    await page.evaluate(() => {
      window.proofTest.failure = 'CSRF_INVALID';
    });
    await fingerprint.fill('ef'.repeat(32));
    await save.click();
    await dialog.getByRole('button', { name: '取消本次证明替换', exact: true }).click();
    await save.click();
    await dialog.waitFor({ state: 'hidden' });
    require((await page.evaluate(() => window.proofTest.withProof.at(-1))) === false, 'explicit cancellation may retain existing proof');

    // A lost response cannot be treated as a definite rejection or retried blindly.
    await row.getByRole('button', { name: '编辑', exact: true }).click();
    await page.evaluate(() => {
      window.proofTest.failure = 'NETWORK';
    });
    await fingerprint.fill('ab'.repeat(32));
    await save.click();
    await dialog.getByText('保存结果无法确认，请关闭并重新读取平台后核对；不要直接重试。', { exact: true }).waitFor();
    require(await save.isDisabled(), 'unknown result must require a fresh read');
    require((await fingerprint.inputValue()) === '', 'unknown result must clear proof material');
    require((await page.evaluate(() => window.proofTest.writes)) === 5, 'unknown result must not issue a retry');
    const readsBeforeClose = await page.evaluate(() => window.proofTest.platformReads);
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await row.getByRole('button', { name: '编辑', exact: true }).click();
    require((await page.evaluate(() => window.proofTest.platformReads)) > readsBeforeClose, 'reopen must follow a fresh platform read');
    require((await fingerprint.inputValue()) === '', 'reopened form must not recover proof material');
    require(!(await save.isDisabled()), 'freshly read platform permits a new operation');

    // Proxy replacements keep retry intent without retaining either input.
    await dialog.getByRole('combobox').nth(3).selectOption('PROXY_SECRET');
    const network = dialog.locator('input').nth(2);
    const secret = dialog.locator('input[type="password"]');
    await network.fill('192.0.2.0/24');
    await secret.fill('synthetic-only-retry');
    await page.evaluate(() => {
      window.proofTest.failure = 'CSRF_INVALID';
    });
    await save.click();
    await dialog.getByRole('button', { name: '取消本次证明替换', exact: true }).waitFor();
    require((await network.inputValue()) === '' && (await secret.inputValue()) === '', 'proxy failure must clear both inputs');
    const writesAfterProxyFailure = await page.evaluate(() => window.proofTest.writes);
    await save.click();
    require((await page.evaluate(() => window.proofTest.writes)) === writesAfterProxyFailure, 'blank proxy retry must not retain old proof');
    await network.fill('192.0.2.0/24');
    await secret.fill('synthetic-only-retry');
    await save.click();
    await dialog.waitFor({ state: 'hidden' });

    for (const failure of ['VERSION_CONFLICT', 'INVALID_RESPONSE']) {
      await row.getByRole('button', { name: '编辑', exact: true }).click();
      await network.fill('192.0.2.0/24');
      await secret.fill('synthetic-only-retry');
      await page.evaluate((value) => {
        window.proofTest.failure = value;
      }, failure);
      await save.click();
      await dialog
        .getByText(
          failure === 'VERSION_CONFLICT'
            ? '该平台已被他人修改，请关闭并重新读取后核对。'
            : '保存结果无法确认，请关闭并重新读取平台后核对；不要直接重试。',
          { exact: false },
        )
        .waitFor();
      require(await save.isDisabled(), `${failure} must require a fresh read`);
      require((await network.inputValue()) === '' && (await secret.inputValue()) === '', `${failure} must clear both inputs`);
      await dialog.getByRole('button', { name: '取消', exact: true }).click();
    }
    // A committed write with a lost response must reload the committed version before another write.
    await row.getByRole('button', { name: '编辑', exact: true }).click();
    const beforeCommit = await page.evaluate(() => ({ ...window.proofTest }));
    await page.evaluate(() => {
      window.proofTest.failure = 'COMMITTED_NETWORK';
    });
    await network.fill('192.0.2.0/24');
    await secret.fill('synthetic-only-committed');
    await save.click();
    await dialog.getByText('保存结果无法确认，请关闭并重新读取平台后核对；不要直接重试。', { exact: true }).waitFor();
    require(await save.isDisabled(), 'committed unknown result must prohibit blind retry');
    require((await network.inputValue()) === '' && (await secret.inputValue()) === '', 'committed unknown result must clear proof');
    const committed = await page.evaluate(() => ({ ...window.proofTest }));
    require(committed.writes === beforeCommit.writes + 1, 'committed unknown result must issue only one write');
    require(!!committed.committedVersion &&
      committed.committedVersion !== beforeCommit.platformVersion, 'synthetic server must actually advance its version');
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await row.getByRole('button', { name: '编辑', exact: true }).click();
    const refreshed = await page.evaluate(() => ({ ...window.proofTest }));
    require(refreshed.platformReads > committed.platformReads, 'committed unknown result must trigger a fresh read');
    require(refreshed.platformVersion === committed.committedVersion, 'fresh read must observe the committed version');
    require((await network.inputValue()) === '' && (await secret.inputValue()) === '', 'fresh read must not recover committed proof material');
    await save.click();
    await dialog.waitFor({ state: 'hidden' });
    const afterRefresh = await page.evaluate(() => ({ ...window.proofTest }));
    require(afterRefresh.writeVersions.at(-1) === `"${committed.committedVersion}"`, 'next operation must use the freshly read version');
    require(afterRefresh.withProof.at(-1) === false, 'new explicit save may retain the committed proof');
    return {
      result: 'PASS',
      actualReactDialog: true,
      syntheticTransport: true,
      cases: [
        'reenter-after-rejection',
        'explicit-cancel',
        'unknown-result-refresh',
        'proxy-retry',
        'version-conflict',
        'invalid-response',
        'committed-response-lost',
      ],
    };
  } finally {
    await context.close();
  }
}
