/** Synthetic UI fixtures, loaded only by Vite's development-only frontend-preview mode.
 * No network, browser persistence, real credentials or business SQL are used here. */
type Row = Record<string, unknown>;
export type PreviewScenario = 'normal' | 'empty' | 'error' | 'conflict' | 'slow';
let scenario: PreviewScenario = 'normal';
export function setPreviewScenario(value: PreviewScenario): void {
  scenario = value;
}
interface PreviewOptions {
  method?: string;
  body?: unknown;
  headers?: HeadersInit;
  signal?: AbortSignal;
}

export function createPreviewServer() {
  const now = new Date().toISOString();
  const permissions = Object.entries({
    datasource: 'list read create update delete test sync',
    metadata: 'list read dictionary',
    job: 'read',
    department: 'list read create update delete',
    user: 'list read create update delete status reset-password assign-role move',
    role: 'list read create update delete',
    platform: 'list read create update delete scope',
    client: 'list read create update delete',
    delegation: 'list create delete',
    acl: 'list read create update delete preview effective',
    audit: 'list read decrypt',
  }).flatMap(([resource, actions]) => actions.split(' ').map((action) => `ptmcp:${resource}:${action}`));
  const tables: Record<string, Row[]> = {
    departments: [
      { id: '1', name: '演示总部', parentId: null, enabled: true, ancestors: '0', version: 1 },
      { id: '2', name: '数据团队', parentId: '1', enabled: true, ancestors: '0,1', version: 1 },
    ],
    users: [
      { id: '1', username: 'preview-admin', displayName: '前端演示管理员', deptId: '1', enabled: true, roleIds: ['1'], version: 1 },
      { id: '2', username: 'preview-analyst', displayName: '合成分析员', deptId: '2', enabled: true, roleIds: ['2'], version: 1 },
    ],
    roles: [
      { id: '1', key: 'PREVIEW_ADMIN', name: '演示管理员', enabled: true, permissionCodes: permissions, version: 1 },
      { id: '2', key: 'PREVIEW_READER', name: '演示分析员', enabled: true, permissionCodes: [], version: 1 },
    ],
    platforms: [
      {
        id: '1',
        code: 'preview-desktop',
        name: '合成桌面平台',
        tier: 3,
        catalogMode: 'DISCOVER',
        proofMode: 'NONE',
        proofConfigured: false,
        status: 'ACTIVE',
        version: '1',
        scopeVersion: '1',
        securityVersion: '1',
      },
      {
        id: '2',
        code: 'preview-service',
        name: '合成受信服务',
        tier: 1,
        catalogMode: 'HIDE',
        proofMode: 'MTLS',
        proofConfigured: true,
        status: 'DISABLED',
        version: '1',
        scopeVersion: '1',
        securityVersion: '1',
      },
    ],
    clients: [
      {
        id: '1',
        clientId: 'preview-public',
        platformId: '1',
        name: '合成公开客户端',
        type: 'PUBLIC',
        authMethods: ['none'],
        redirectUris: ['http://127.0.0.1:3000/callback'],
        allowedScopes: ['mcp:catalog'],
        jwksUri: '',
        status: 'ACTIVE',
        version: '1',
        secretConfigured: false,
      },
    ],
    delegations: [
      {
        id: '1',
        userId: '2',
        departmentRootId: '2',
        resourceType: 'DATASOURCE',
        resourceId: '1',
        allowedActions: ['ptmcp:acl:list'],
        validFrom: now,
        validTo: new Date(Date.now() + 7 * 86400000).toISOString(),
        status: 'ACTIVE',
        version: '1',
      },
    ],
    datasources: [
      {
        id: '1',
        version: '1',
        code: 'preview-mysql',
        createdAt: '2026-10-20T09:12:00Z',
        updatedAt: now,
        name: '合成订单库',
        engine: 'MYSQL',
        host: 'db.example.test',
        port: 3306,
        databaseName: 'synthetic',
        username: 'preview_reader',
        tlsMode: 'VERIFY_FULL',
        poolMax: 2,
        status: 'ACTIVE',
        configVersion: '1',
        resourceVersion: '1',
        activeSnapshotId: '1',
        lastSyncAt: now,
        lastSyncResult: 'SUCCEEDED',
        credentialConfigured: true,
        health: 'HEALTHY',
      },
      {
        id: '2',
        version: '1',
        code: 'preview-pg',
        createdAt: '2026-10-21T10:00:00Z',
        updatedAt: '2026-10-21T10:00:00Z',
        name: '合成分析库',
        engine: 'POSTGRESQL',
        host: 'pg.example.test',
        port: 5432,
        databaseName: 'synthetic',
        username: 'preview_reader',
        tlsMode: 'VERIFY_FULL',
        poolMax: 2,
        status: 'DRAFT',
        configVersion: '1',
        resourceVersion: '1',
        activeSnapshotId: null,
        lastSyncAt: null,
        credentialConfigured: false,
        health: 'UNKNOWN',
      },
      {
        id: '3',
        version: '3',
        code: 'prod-mysql',
        createdAt: '2026-10-18T08:30:00Z',
        updatedAt: new Date(Date.now() - 3600_000).toISOString(),
        name: '生产业务库',
        engine: 'MYSQL',
        host: 'prod-db.example.test',
        port: 3306,
        databaseName: 'production',
        username: 'prod_reader',
        tlsMode: 'VERIFY_FULL',
        poolMax: 10,
        status: 'ACTIVE',
        configVersion: '3',
        resourceVersion: '7',
        activeSnapshotId: '15',
        lastSyncAt: new Date(Date.now() - 3600_000).toISOString(),
        lastSyncResult: 'SUCCEEDED',
        credentialConfigured: true,
        health: 'HEALTHY',
      },
      {
        id: '4',
        version: '2',
        code: 'dwh-clickhouse',
        createdAt: '2026-10-15T14:00:00Z',
        updatedAt: new Date(Date.now() - 7200_000).toISOString(),
        name: '数据仓库',
        engine: 'CLICKHOUSE',
        host: 'dwh.example.test',
        port: 8123,
        databaseName: 'analytics',
        username: 'dwh_reader',
        tlsMode: 'VERIFY_FULL',
        poolMax: 20,
        status: 'ACTIVE',
        configVersion: '2',
        resourceVersion: '4',
        activeSnapshotId: null,
        lastSyncAt: new Date(Date.now() - 7200_000).toISOString(),
        lastSyncResult: 'FAILED',
        credentialConfigured: true,
        health: 'DEGRADED',
      },
      {
        id: '5',
        version: '1',
        code: 'test-mysql',
        createdAt: '2026-10-12T16:20:00Z',
        updatedAt: new Date(Date.now() - 86400_000).toISOString(),
        name: '测试库',
        engine: 'MYSQL',
        host: 'test-db.example.test',
        port: 3306,
        databaseName: 'test',
        username: 'test_reader',
        tlsMode: 'PREFERRED',
        poolMax: 5,
        status: 'DISABLED',
        configVersion: '1',
        resourceVersion: '2',
        activeSnapshotId: '9',
        lastSyncAt: new Date(Date.now() - 86400_000).toISOString(),
        lastSyncResult: 'SUCCEEDED',
        credentialConfigured: true,
        health: 'HEALTHY',
      },
      {
        id: '6',
        version: '1',
        code: 'example-sqlite',
        createdAt: '2026-10-10T11:00:00Z',
        updatedAt: new Date(Date.now() - 172800_000).toISOString(),
        name: '示例数据源',
        engine: 'SQLITE',
        host: 'localhost',
        port: null,
        databaseName: 'demo',
        username: 'demo',
        tlsMode: 'NONE',
        poolMax: 1,
        status: 'ACTIVE',
        configVersion: '1',
        resourceVersion: '1',
        activeSnapshotId: null,
        lastSyncAt: new Date(Date.now() - 172800_000).toISOString(),
        lastSyncResult: 'SUCCEEDED',
        credentialConfigured: true,
        health: 'HEALTHY',
      },
    ],
    objects: [
      {
        id: '1',
        objectId: '1',
        datasourceId: '1',
        namespaceId: '1',
        databaseName: 'synthetic',
        schemaName: null,
        objectName: 'synthetic_orders',
        tableName: 'synthetic_orders',
        objectType: 'TABLE',
        tableComment: '前端合成订单表',
        generation: '1',
        snapshotId: '1',
        snapshotVersion: '1',
        authorized: true,
        metadataVisible: true,
        columns: [
          {
            ordinal: 1,
            name: 'id',
            databaseType: 'BIGINT',
            jdbcType: -5,
            nullable: false,
            precision: 19,
            scale: 0,
            maxLength: null,
            comment: '合成编号',
            primaryKey: true,
            foreignKey: null,
          },
          {
            ordinal: 2,
            name: 'amount',
            databaseType: 'DECIMAL',
            jdbcType: 3,
            nullable: false,
            precision: 12,
            scale: 2,
            maxLength: null,
            comment: '合成金额',
            primaryKey: false,
            foreignKey: null,
          },
        ],
        dictionary: { title: '合成订单', description: '仅用于前端预览，不对应真实业务表。', ownerUserId: null, version: '1', columns: [] },
        foreignKeys: [],
        foreignKeysRestricted: true,
      },
      {
        id: '2',
        objectId: '2',
        datasourceId: '1',
        namespaceId: '1',
        databaseName: 'synthetic',
        schemaName: null,
        objectName: 'synthetic_customers',
        tableName: 'synthetic_customers',
        objectType: 'TABLE',
        tableComment: '前端合成客户表',
        generation: '1',
        snapshotId: '1',
        snapshotVersion: '1',
        authorized: true,
        metadataVisible: true,
        columns: [],
        dictionary: { title: '合成客户', description: '仅用于前端预览。', ownerUserId: null, version: '1', columns: [] },
        foreignKeys: [],
        foreignKeysRestricted: true,
      },
      {
        id: '3',
        objectId: '3',
        datasourceId: '1',
        namespaceId: '1',
        databaseName: 'synthetic',
        schemaName: null,
        objectName: 'v_daily_revenue',
        tableName: 'v_daily_revenue',
        objectType: 'VIEW',
        tableComment: '前端合成日营收视图',
        generation: '1',
        snapshotId: '1',
        snapshotVersion: '1',
        authorized: true,
        metadataVisible: true,
        columns: [],
        dictionary: { title: '日营收视图', description: '仅用于前端预览。', ownerUserId: null, version: '1', columns: [] },
        foreignKeys: [],
        foreignKeysRestricted: true,
      },
      {
        id: '4',
        objectId: '4',
        datasourceId: '2',
        namespaceId: '2',
        databaseName: 'analytics',
        schemaName: null,
        objectName: 'fact_events',
        tableName: 'fact_events',
        objectType: 'TABLE',
        tableComment: '前端合成事件事实表',
        generation: '1',
        snapshotId: '2',
        snapshotVersion: '1',
        authorized: true,
        metadataVisible: true,
        columns: [],
        dictionary: { title: '事件事实表', description: '仅用于前端预览。', ownerUserId: null, version: '1', columns: [] },
        foreignKeys: [],
        foreignKeysRestricted: true,
      },
      {
        id: '5',
        objectId: '5',
        datasourceId: '2',
        namespaceId: '2',
        databaseName: 'analytics',
        schemaName: null,
        objectName: 'dim_users',
        tableName: 'dim_users',
        objectType: 'TABLE',
        tableComment: '前端合成用户维表',
        generation: '1',
        snapshotId: '2',
        snapshotVersion: '1',
        authorized: true,
        metadataVisible: true,
        columns: [],
        dictionary: { title: '用户维表', description: '仅用于前端预览。', ownerUserId: null, version: '1', columns: [] },
        foreignKeys: [],
        foreignKeysRestricted: true,
      },
    ],
    grants: [
      {
        id: '1',
        subjectType: 'USER',
        subjectId: '2',
        resourceType: 'OBJECT',
        resourceId: '1',
        resourceGeneration: '1',
        action: 'READ',
        effect: 'ALLOW',
        includeDescendants: false,
        validFrom: now,
        validTo: null,
        reason: '前端合成授权',
        status: 'ACTIVE',
        version: '1',
      },
      {
        id: '2',
        subjectType: 'USER',
        subjectId: '2',
        resourceType: 'OBJECT',
        resourceId: '2',
        resourceGeneration: '1',
        action: 'READ',
        effect: 'ALLOW',
        includeDescendants: false,
        validFrom: now,
        validTo: null,
        reason: '前端合成授权',
        status: 'ACTIVE',
        version: '1',
      },
      {
        id: '3',
        subjectType: 'USER',
        subjectId: '2',
        resourceType: 'OBJECT',
        resourceId: '3',
        resourceGeneration: '1',
        action: 'READ',
        effect: 'DENY',
        includeDescendants: false,
        validFrom: now,
        validTo: null,
        reason: '敏感营收数据，禁止读取',
        status: 'ACTIVE',
        version: '1',
      },
      {
        id: '4',
        subjectType: 'USER',
        subjectId: '1',
        resourceType: 'OBJECT',
        resourceId: '1',
        resourceGeneration: '1',
        action: 'READ',
        effect: 'ALLOW',
        includeDescendants: false,
        validFrom: now,
        validTo: null,
        reason: '管理员读取订单',
        status: 'ACTIVE',
        version: '1',
      },
      {
        id: '5',
        subjectType: 'ROLE',
        subjectId: '2',
        resourceType: 'OBJECT',
        resourceId: '4',
        resourceGeneration: '1',
        action: 'READ',
        effect: 'ALLOW',
        includeDescendants: true,
        validFrom: now,
        validTo: null,
        reason: '分析角色读取事实表',
        status: 'ACTIVE',
        version: '1',
      },
    ],
    tokens: [
      {
        id: '1',
        name: '合成开发令牌',
        prefix: 'preview_',
        scopes: ['mcp:catalog'],
        platformId: '1',
        expiresAt: '2099-01-01T00:00:00Z',
        lastUsedAt: null,
        createdAt: now,
        status: 'ACTIVE',
      },
    ],
    authorizations: [
      {
        id: '1',
        clientName: '合成公开客户端',
        platformId: '1',
        issuedAt: now,
        lastUsedAt: now,
        status: 'ACTIVE',
        absoluteExpiresAt: '2099-01-01T00:00:00Z',
      },
    ],
    operations: [
      {
        id: '1',
        actorId: '1',
        requestId: 'preview-op-1',
        action: 'PLATFORM_CREATE',
        aggregateType: 'PLATFORM',
        aggregateId: '1',
        beforeMasked: null,
        afterMasked: '{"code":"preview-desktop","name":"合成桌面平台"}',
        occurredAt: now,
      },
      {
        id: '2',
        actorId: '1',
        requestId: 'preview-op-2',
        action: 'ROLE_CREATE',
        aggregateType: 'ROLE',
        aggregateId: '1',
        beforeMasked: null,
        afterMasked: '{"key":"PREVIEW_ADMIN","name":"演示管理员"}',
        occurredAt: now,
      },
    ],
    audit: ['SUCCESS', 'UNKNOWN', 'ERROR'].map((result, index) => ({
      requestId: `preview-request-${index + 1}`,
      userId: '2',
      platformId: '1',
      datasourceId: '1',
      tool: 'query',
      startedAt: new Date(Date.now() - (index + 1) * 60000).toISOString(),
      completedAt: result === 'UNKNOWN' ? null : now,
      result,
      verdict: result === 'ERROR' ? 'DENY' : 'ALLOW',
      errorCode: result === 'ERROR' ? 'NOT_AUTHORIZED' : null,
      sqlMasked: 'SELECT id FROM synthetic_orders WHERE id = ?',
      durationMs: 24,
      rowsReturned: result === 'SUCCESS' ? 5 : null,
      truncated: false,
      resourceRefs: [{ objectId: '1' }],
      versions: { securityVersion: '1' },
      queueMs: 2,
      dbMs: 15,
      responseBytes: 160,
      fingerprint: 'synthetic-fingerprint',
    })),
    jobs: [
      {
        id: '1',
        datasourceId: '1',
        type: 'SYNC',
        state: 'SUCCEEDED',
        progress: { processed: 1, total: 1 },
        startedAt: now,
        finishedAt: now,
        snapshotId: '1',
        errorCode: null,
      },
    ],
  };
  const scope: Record<string, Row> = {
    '1': {
      items: [{ resourceType: 'OBJECT', resourceId: '1', resourceGeneration: '1', action: 'READ', includeDescendants: false }],
      scopeVersion: '1',
      version: '1',
    },
  };
  const sensitiveAccess: Record<string, Row[]> = {};
  const OP_MAP: Record<string, { type: string; create: string; update: string; delete: string }> = {
    platforms: { type: 'PLATFORM', create: 'PLATFORM_CREATE', update: 'PLATFORM_UPDATE', delete: 'PLATFORM_DELETE' },
    clients: { type: 'OAUTH_CLIENT', create: 'CLIENT_CREATE', update: 'CLIENT_UPDATE', delete: 'CLIENT_DELETE' },
    users: { type: 'USER', create: 'USER_CREATE', update: 'USER_UPDATE', delete: 'USER_DELETE' },
    departments: { type: 'DEPARTMENT', create: 'DEPARTMENT_CREATE', update: 'DEPARTMENT_UPDATE', delete: 'DEPARTMENT_DELETE' },
    roles: { type: 'ROLE', create: 'ROLE_CREATE', update: 'ROLE_UPDATE', delete: 'ROLE_DELETE' },
    delegations: { type: 'ADMIN_DELEGATION', create: 'DELEGATION_CREATE', update: '', delete: 'DELEGATION_REVOKE' },
  };
  function recordOperation(action: string, aggregateType: string, aggregateId: string, before?: unknown, after?: unknown): void {
    tables.operations.push({
      id: String(++serial),
      actorId: '1',
      requestId: `preview-op-${tables.operations.length + 1}`,
      action,
      aggregateType,
      aggregateId,
      beforeMasked: before != null ? JSON.stringify(before).slice(0, 200) : null,
      afterMasked: after != null ? JSON.stringify(after).slice(0, 200) : null,
      occurredAt: new Date().toISOString(),
    });
  }
  let serial = 100;
  let signedIn = true;
  let responseSequence = 0;
  const response = (data: unknown, status = 200) =>
    new Response(JSON.stringify(data), {
      status,
      headers: {
        'Content-Type': 'application/json',
        'Cache-Control': 'no-store',
        'X-PTMCP-Preview': 'synthetic',
        'X-Request-Id': `preview-response-${++responseSequence}`,
        ETag: `"${String((data as Row | null)?.version ?? ((data as Row | null)?.dictionary as Row | undefined)?.version ?? '1')}"`,
      },
    });
  const fail = (status: number, code: string) => response({ code, retryable: false }, status);
  function page(rows: Row[], query: URLSearchParams) {
    let values = rows.filter((row) =>
      [
        'status',
        'type',
        'tier',
        'engine',
        'userId',
        'subjectId',
        'subjectType',
        'result',
        'verdict',
        'tool',
        'errorCode',
        'platformId',
        'datasourceId',
        'aggregateType',
        'actorId',
        'action',
      ].every((key) => !query.get(key) || String(row[key]) === query.get(key)),
    );
    const keyword = (query.get('keyword') ?? '').toLowerCase();
    if (keyword)
      values = values.filter((row) =>
        ['name', 'code', 'username', 'displayName', 'tableName', 'objectName'].some((key) =>
          String(row[key] ?? '')
            .toLowerCase()
            .includes(keyword),
        ),
      );
    if (query.get('departmentId')) values = values.filter((row) => row.deptId === query.get('departmentId'));
    if (query.get('from')) values = values.filter((row) => String(row.startedAt) >= query.get('from')!);
    if (query.get('to')) values = values.filter((row) => String(row.startedAt) < query.get('to')!);
    const start = Number(query.get('cursor') ?? 0);
    const size = Math.min(Number(query.get('pageSize') ?? 20), 100);
    return { items: values.slice(start, start + size), nextCursor: values.length > start + size ? String(start + size) : null };
  }
  return async (path: string, options: PreviewOptions = {}): Promise<Response> => {
    if (options.signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const url = new URL(path, 'http://preview.invalid');
    const parts = url.pathname.replace('/api/v1/', '').split('/');
    const [area, resource, id, action] = parts;
    const method = options.method ?? 'GET';
    const body = options.body && typeof options.body === 'object' ? (options.body as Row) : {};
    if (area !== 'session') {
      if (scenario === 'slow')
        await new Promise<void>((resolve, reject) => {
          const finish = () => {
            options.signal?.removeEventListener('abort', abort);
            resolve();
          };
          const timeout = setTimeout(finish, 1200);
          const abort = () => {
            clearTimeout(timeout);
            reject(new DOMException('Aborted', 'AbortError'));
          };
          options.signal?.addEventListener('abort', abort, { once: true });
        });
      if (options.signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      if (scenario === 'error') return fail(503, 'SYNTHETIC_UNAVAILABLE');
      if (scenario === 'conflict' && !['GET', 'HEAD'].includes(method)) return fail(412, 'VERSION_CONFLICT');
      if (scenario === 'empty' && method === 'GET') {
        if ((resource === 'metadata' && !!action) || (resource === 'acl' && id === 'grants' && !!action)) return fail(404, 'NOT_FOUND');
        if (resource === 'platforms' && action === 'scopes') return response({ items: [], version: '1', scopeVersion: '1' });
        if (id && !(resource === 'acl' && ['grants', 'effective'].includes(id)) && !(resource === 'metadata' && id === 'objects') && !action)
          return fail(404, 'NOT_FOUND');
        return response(
          ['departments', 'users', 'roles'].includes(resource) || (resource === 'acl' && id === 'grants')
            ? []
            : { items: [], nextCursor: null, evaluatedAt: now, versions: {} },
        );
      }
    }
    if (area === 'session') {
      if (resource === 'csrf') return response({ csrfToken: 'synthetic-csrf', expiresAt: '2099-01-01T00:00:00Z' });
      if (resource === 'login') {
        signedIn = true;
        return response({});
      }
      if (resource === 'logout' || resource === 'password') {
        signedIn = false;
        return response({});
      }
      if (!signedIn) return fail(401, 'SESSION_EXPIRED');
      return response({
        userId: '1',
        username: 'preview-admin',
        displayName: '前端演示管理员',
        department: { id: '1', name: '演示总部' },
        roles: [{ id: '1', name: '演示管理员' }],
        permissions,
        platformAdmin: true,
        managedScopes: ['PREVIEW_ONLY'],
        expiresAt: '2099-01-01T00:00:00Z',
      });
    }
    if (!signedIn) return fail(401, 'SESSION_EXPIRED');
    if (area === 'me' && resource === 'access-context') {
      const level = url.searchParams.get('level');
      const rows =
        level === 'PLATFORM'
          ? tables.platforms.map((row) => ({ ...row, type: 'PLATFORM' }))
          : level === 'DATASOURCE'
            ? tables.datasources.map((row) => ({ ...row, type: 'DATASOURCE' }))
            : [{ type: 'NAMESPACE', id: '1', databaseName: 'synthetic', schemaName: null, displayName: 'synthetic' }];
      return response({ ...page(rows, new URLSearchParams({ pageSize: '100' })), evaluatedAt: now, versions: { securityVersion: '1' } });
    }
    if (resource === 'mcp-config')
      return response({
        transport: 'streamable-http',
        url: 'https://mcp.example.test/mcp',
        authentication: ['OAuth', 'PAT'],
        configTemplate: '{"mcpServers":{"synthetic":{"url":"https://mcp.example.test/mcp","headers":{"Authorization":"Bearer <YOUR_PAT>"}}}}',
        compatibilityStatus: 'NOT_VALIDATED',
        validatedVersion: null,
      });
    if (resource === 'acl' && id === 'effective')
      return response({
        items: tables.objects.map((row) => ({
          resourceId: row.id,
          resourceType: 'OBJECT',
          meta: true,
          read: true,
          sourceGrantIds: ['1'],
          reasonCodes: ['SYNTHETIC_PREVIEW'],
        })),
        nextCursor: null,
      });
    if (resource === 'acl' && id === 'preview') {
      const subjectGrants = tables.grants.filter(
        (g) => g.subjectType === body.subjectType && String(g.subjectId) === String(body.subjectId) && g.status === 'ACTIVE',
      );
      return response({
        decisions: ((body.resourceIds as string[]) ?? []).map((resourceId) => {
          const matched = subjectGrants.filter((g) => String(g.resourceId) === String(resourceId) && g.action === 'READ');
          const deny = matched.find((g) => g.effect === 'DENY');
          const allow = matched.find((g) => g.effect === 'ALLOW');
          const read = !!allow && !deny;
          return {
            resourceId,
            meta: read,
            read,
            sourceGrantIds: matched.map((g) => String(g.id)),
            reasonCodes: deny ? ['DENY_MATCHED'] : allow ? ['SYNTHETIC_PREVIEW'] : ['NO_GRANT'],
          };
        }),
        evaluatedAt: now,
        nextBoundaryAt: null,
        versions: { securityVersion: '1' },
      });
    }
    if (resource === 'platforms' && action === 'scopes') {
      const current = scope[id] ?? { items: [], scopeVersion: '1', version: '1' };
      if (method === 'PUT') {
        if (new Headers(options.headers).get('If-Match') !== `"${current.version}"`) return fail(412, 'VERSION_CONFLICT');
        scope[id] = { items: body.items, scopeVersion: String(Number(current.scopeVersion) + 1), version: String(Number(current.version) + 1) };
        recordOperation(
          'PLATFORM_SCOPE_SAVE',
          'PLATFORM',
          String(id),
          { rules: (current.items as unknown[]).length },
          { rules: Array.isArray(body.items) ? body.items.length : 0 },
        );
      }
      return response(scope[id] ?? current);
    }
    if (resource === 'audit' && action === 'sensitive-access' && method === 'GET') {
      if (!tables.audit.some((row) => row.requestId === id)) return fail(404, 'NOT_FOUND');
      return response(sensitiveAccess[id] ?? []);
    }
    if (resource === 'audit' && action === 'decrypt') {
      if (!tables.audit.some((row) => row.requestId === id)) return fail(404, 'NOT_FOUND');
      if (
        String(body.reason ?? '').trim().length < 10 ||
        String(body.reason).length > 500 ||
        !Array.isArray(body.fields) ||
        !body.fields.length ||
        body.fields.some((field) => !['sqlRaw', 'sqlFinal'].includes(String(field)))
      )
        return fail(400, 'INVALID_REQUEST');
      (sensitiveAccess[id] ??= []).push({
        id: String(++serial),
        operatorId: '1',
        reason: body.reason,
        fields: body.fields,
        createdAt: new Date().toISOString(),
      });
      return response({
        ...Object.fromEntries(
          (body.fields as string[]).map((field) => [field, 'SELECT id FROM synthetic_orders WHERE id = 42 /* synthetic preview only */']),
        ),
        expiresAt: new Date(Date.now() + 30000).toISOString(),
      });
    }
    if (resource === 'datasources' && ['test', 'sync'].includes(action)) {
      const job: Row = { ...tables.jobs[0], id: String(++serial), datasourceId: id, type: action === 'test' ? 'TEST' : 'SYNC' };
      tables.jobs.push(job);
      if (action === 'test') job.snapshotId = null;
      else {
        const source = tables.datasources.find((row) => row.id === id);
        if (source) {
          source.activeSnapshotId = job.snapshotId;
          source.lastSyncAt = new Date().toISOString();
        }
      }
      return response(job, 202);
    }
    let table = resource;
    let rowId: string | undefined = id;
    let subAction = action;
    if (resource === 'metadata') {
      table = 'objects';
      rowId = action;
      subAction = parts[4];
    }
    if (resource === 'acl') {
      table = 'grants';
      rowId = action;
      subAction = parts[4];
    }
    if (resource === 'dictionary') table = 'objects';
    if (resource === 'history') table = 'audit';
    if (resource === 'platforms' && action === 'clients') {
      table = 'clients';
      rowId = undefined;
    }
    const rows = tables[table];
    if (!rows) return fail(501, 'PREVIEW_NOT_IMPLEMENTED');
    const row = rows.find((item) => (table === 'audit' ? item.requestId : item.id) === rowId);
    if (method === 'GET') {
      if (table === 'roles')
        for (const role of rows) {
          role.memberCount = tables.users.filter((user) => (user.roleIds as string[]).includes(String(role.id))).length;
          role.managedDepartmentIds = role.id === '1' ? ['1'] : ['2'];
        }
      if (rowId) return row ? response(row) : fail(404, 'NOT_FOUND');
      const selected = resource === 'platforms' && action === 'clients' ? rows.filter((item) => item.platformId === id) : rows;
      return response(
        ['departments', 'users', 'roles', 'grants'].includes(table) ? page(selected, url.searchParams).items : page(selected, url.searchParams),
      );
    }
    if (method === 'POST') {
      const created: Row = { ...body, id: String(++serial), version: '1', status: body.status ?? 'ACTIVE', createdAt: now };
      // Write-only inputs are never retained in fixtures or included in list/detail DTOs.
      for (const key of ['credential', 'initialPassword', 'newPassword', 'proofConfig']) delete created[key];
      if (table === 'datasources')
        Object.assign(created, {
          credentialConfigured: true,
          configVersion: '1',
          resourceVersion: '1',
          activeSnapshotId: null,
          health: 'UNKNOWN',
          lastSyncAt: null,
        });
      if (table === 'clients')
        Object.assign(created, { platformId: id, clientId: `synthetic-client-${created.id}`, secretConfigured: body.type === 'CONFIDENTIAL' });
      if (table === 'platforms') Object.assign(created, { securityVersion: '1', scopeVersion: '1', proofConfigured: body.proofMode !== 'NONE' });
      if (table === 'tokens') Object.assign(created, { prefix: 'preview_', lastUsedAt: null, expiresAt: body.expiresAt ?? '2099-01-01T00:00:00Z' });
      rows.push(created);
      const opMeta = OP_MAP[table];
      if (opMeta?.create) recordOperation(opMeta.create, opMeta.type, String(created.id), null, { name: created.name ?? created.code ?? created.id });
      if (table === 'clients')
        return response(
          {
            client: created,
            ...(body.type === 'CONFIDENTIAL' && ((body.authMethods as string[]) ?? []).includes('client_secret_basic')
              ? { clientSecret: 'synthetic-secret-not-a-credential' }
              : {}),
          },
          201,
        );
      if (table === 'tokens') return response({ token: 'synthetic-pat-not-a-credential', metadata: created }, 201);
      return response(created, 201);
    }
    if (!row) return fail(404, 'NOT_FOUND');
    const expectedVersion = table === 'objects' ? (row.dictionary as Row)?.version : row.version;
    if (expectedVersion && new Headers(options.headers).get('If-Match') !== `"${expectedVersion}"`) return fail(412, 'VERSION_CONFLICT');
    if (method === 'DELETE') {
      if (['delegations', 'grants', 'tokens', 'authorizations'].includes(table)) {
        row.status = 'REVOKED';
        row.version = String(Number(row.version ?? 0) + 1);
      } else rows.splice(rows.indexOf(row), 1);
      const delOpMeta = OP_MAP[table];
      if (delOpMeta?.delete) recordOperation(delOpMeta.delete, delOpMeta.type, String(row.id), { name: row.name ?? row.code }, null);
      return response({ ...row, deleted: true, revoked: true });
    }
    if (method === 'PUT' || method === 'PATCH') {
      if (subAction === 'dictionary') {
        row.dictionary = { ...body, version: String(Number((row.dictionary as Row).version) + 1) };
        row.dictionaryTitle = body.title;
        row.dictionaryDescription = body.description;
      } else if (subAction === 'status') row.enabled = body.status === 'ACTIVE';
      else if (subAction === 'roles') row.roleIds = body.roleIds;
      else if (subAction === 'department') row.deptId = body.departmentId;
      else if (subAction !== 'password') {
        const safe = { ...body };
        for (const key of ['credential', 'initialPassword', 'newPassword', 'proofConfig']) delete safe[key];
        Object.assign(row, safe);
        if (table === 'platforms') {
          row.proofConfigured = body.proofMode === 'NONE' ? false : !!body.proofConfig || row.proofConfigured;
          row.securityVersion = String(Number(row.securityVersion ?? 0) + 1);
        }
      }
      if (table !== 'objects') row.version = typeof row.version === 'number' ? row.version + 1 : String(Number(row.version ?? 0) + 1);
      const updOpMeta = OP_MAP[table];
      if (updOpMeta?.update && !subAction) recordOperation(updOpMeta.update, updOpMeta.type, String(row.id), null, body);
      return response(row);
    }
    return fail(501, 'PREVIEW_NOT_IMPLEMENTED');
  };
}

export const previewRequest = createPreviewServer();
