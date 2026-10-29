import { useEffect, useMemo, useState } from 'react';
import { Alert, Badge, EmptyState, PageHeader, SearchInput, StatusDot, useDebounced } from '../../components/common';
import {
  listMyAuthorizations,
  listMyHistory,
  listMyTokens,
  revokeAuthorization,
  revokeToken,
  type AuditRow,
  type AuthorizationRow,
  type TokenRow,
} from '../../services/access';
import { listDatasources, listMetadata, type DatasourceRead, type MetadataObject } from '../../services/data';
import { mePortalApi } from '../../services/mePortalApi';

function fmt(at: string | null | undefined): string {
  return at ? at.slice(0, 19).replace('T', ' ') : '—';
}

export function DictionaryPage() {
  const [datasources, setDatasources] = useState<DatasourceRead[]>([]);
  const [objects, setObjects] = useState<MetadataObject[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const debounced = useDebounced(keyword);

  useEffect(() => {
    let cancelled = false;
    listDatasources({ pageSize: 100 })
      .then(async ({ data }) => {
        if (cancelled) return;
        setDatasources(data.items);
        const all = await Promise.all(
          data.items.map((d) =>
            listMetadata({ datasourceId: d.id, pageSize: 200 })
              .then((p) => p.data.items)
              .catch(() => [] as MetadataObject[]),
          ),
        );
        if (!cancelled) setObjects(all.flat().filter((o) => o.metadataVisible));
      })
      .catch(() => undefined)
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = useMemo(
    () =>
      objects.filter(
        (o) =>
          !debounced ||
          (o.objectName ?? '').toLowerCase().includes(debounced.toLowerCase()) ||
          (o.tableName ?? '').toLowerCase().includes(debounced.toLowerCase()),
      ),
    [objects, debounced],
  );

  return (
    <>
      <PageHeader title="我的数据字典" description="当前可见的元数据对象与业务字典。" />
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '12px 14px',
            borderBottom: '1px solid var(--ptmcp-border)',
            flex: 'none',
          }}
        >
          <SearchInput value={keyword} onChange={setKeyword} placeholder="搜索对象名或表名…" width={260} />
          <span style={{ marginLeft: 'auto', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>共 {filtered.length} 个对象</span>
        </div>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>对象</th>
                <th>所属数据源</th>
                <th>库 / Schema</th>
                <th>类型</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((o) => (
                <tr key={o.id}>
                  <td>
                    <span className="cell-stack">
                      <strong>{o.objectName ?? o.tableName}</strong>
                      <span>{o.tableComment || `ID ${o.id}`}</span>
                    </span>
                  </td>
                  <td style={{ color: 'var(--ptmcp-text-muted)' }}>{datasources.find((d) => d.id === o.datasourceId)?.name ?? o.datasourceId}</td>
                  <td style={{ color: 'var(--ptmcp-text-muted)' }}>
                    {o.databaseName}
                    {o.schemaName ? ` / ${o.schemaName}` : ''}
                  </td>
                  <td>
                    <Badge tone={o.objectType === 'TABLE' ? 'accent' : 'neutral'}>{o.objectType}</Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!loading && filtered.length === 0 ? <EmptyState title="暂无可见的字典对象" description="联系管理员授权后在此浏览。" /> : null}
        </div>
      </div>
    </>
  );
}

export function TokensPage() {
  const [rows, setRows] = useState<TokenRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [revokingId, setRevokingId] = useState('');

  const load = () => {
    setLoading(true);
    listMyTokens()
      .then((p) => setRows(p.items))
      .catch(() => setRows([]))
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const revoke = (id: string) => {
    setRevokingId(id);
    revokeToken(id)
      .then(load)
      .catch(() => undefined)
      .finally(() => setRevokingId(''));
  };

  return (
    <>
      <PageHeader title="个人令牌" description="用于脚本与工具访问的访问令牌,可随时撤销。" />
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '12px 14px',
            borderBottom: '1px solid var(--ptmcp-border)',
            flex: 'none',
          }}
        >
          <span style={{ fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>共 {rows.length} 个令牌</span>
        </div>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>令牌</th>
                <th>范围</th>
                <th>过期时间</th>
                <th>最近使用</th>
                <th>状态</th>
                <th style={{ width: 80 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    <span className="cell-stack">
                      <strong>{r.name}</strong>
                      <span>
                        <code style={{ fontSize: 12 }}>{r.prefix ?? ''}•••</code> · ID {r.id}
                      </span>
                    </span>
                  </td>
                  <td>
                    <span style={{ display: 'inline-flex', gap: 4, flexWrap: 'wrap' }}>
                      {(r.scopes ?? []).map((s) => (
                        <Badge key={s} tone="neutral">
                          {s}
                        </Badge>
                      ))}
                    </span>
                  </td>
                  <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.expiresAt)}</td>
                  <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.lastUsedAt)}</td>
                  <td>
                    <StatusDot tone={r.status === 'ACTIVE' ? 'success' : 'neutral'}>{r.status === 'ACTIVE' ? '生效中' : '已撤销'}</StatusDot>
                  </td>
                  <td>
                    {r.status === 'ACTIVE' ? (
                      <button className="link" disabled={revokingId === r.id} onClick={() => revoke(r.id)}>
                        {revokingId === r.id ? '撤销中…' : '撤销'}
                      </button>
                    ) : (
                      <span style={{ color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!loading && rows.length === 0 ? <EmptyState title="暂无个人令牌" /> : null}
        </div>
      </div>
    </>
  );
}

export function AuthorizationsPage() {
  const [rows, setRows] = useState<AuthorizationRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [revokingId, setRevokingId] = useState('');

  const load = () => {
    setLoading(true);
    listMyAuthorizations()
      .then((p) => setRows(p.items))
      .catch(() => setRows([]))
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const revoke = (id: string) => {
    setRevokingId(id);
    revokeAuthorization(id)
      .then(load)
      .catch(() => undefined)
      .finally(() => setRevokingId(''));
  };

  return (
    <>
      <PageHeader title="授权管理" description="第三方客户端对你的账号授权,可随时收回。" />
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '12px 14px',
            borderBottom: '1px solid var(--ptmcp-border)',
            flex: 'none',
          }}
        >
          <span style={{ fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>共 {rows.length} 条授权</span>
        </div>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>客户端</th>
                <th>签发时间</th>
                <th>最近使用</th>
                <th>过期时间</th>
                <th>状态</th>
                <th style={{ width: 80 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    <strong>{r.clientName}</strong>
                    <span style={{ marginLeft: 8, color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>ID {r.id}</span>
                  </td>
                  <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.issuedAt)}</td>
                  <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.lastUsedAt)}</td>
                  <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.absoluteExpiresAt)}</td>
                  <td>
                    <StatusDot tone={r.status === 'ACTIVE' ? 'success' : 'neutral'}>{r.status === 'ACTIVE' ? '生效中' : '已撤销'}</StatusDot>
                  </td>
                  <td>
                    {r.status === 'ACTIVE' ? (
                      <button className="link" disabled={revokingId === r.id} onClick={() => revoke(r.id)}>
                        {revokingId === r.id ? '撤销中…' : '撤销'}
                      </button>
                    ) : (
                      <span style={{ color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!loading && rows.length === 0 ? <EmptyState title="暂无授权记录" /> : null}
        </div>
      </div>
    </>
  );
}

export function HistoryPage() {
  const [rows, setRows] = useState<AuditRow[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    listMyHistory()
      .then((p) => !cancelled && setRows(p.items))
      .catch(() => !cancelled && setRows([]))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <>
      <PageHeader title="查询历史" description="你的 MCP 查询与工具调用记录。" />
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>工具</th>
                <th>SQL(脱敏)</th>
                <th>行数</th>
                <th>耗时</th>
                <th>结果</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.requestId}>
                  <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.startedAt)}</td>
                  <td>
                    <Badge tone="neutral">{r.tool}</Badge>
                  </td>
                  <td style={{ color: 'var(--ptmcp-text-muted)', maxWidth: 360, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    <code style={{ fontSize: 12 }}>{r.sqlMasked ?? '—'}</code>
                  </td>
                  <td style={{ color: 'var(--ptmcp-text-muted)' }}>{r.rowsReturned ?? '—'}</td>
                  <td style={{ color: 'var(--ptmcp-text-muted)' }}>{r.durationMs != null ? `${r.durationMs} ms` : '—'}</td>
                  <td>
                    <StatusDot tone={r.result === 'SUCCESS' ? 'success' : r.result === 'ERROR' ? 'danger' : 'warning'}>
                      {r.result === 'SUCCESS' ? '成功' : r.result === 'ERROR' ? '失败' : '进行中'}
                    </StatusDot>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!loading && rows.length === 0 ? <EmptyState title="暂无查询历史" /> : null}
        </div>
      </div>
    </>
  );
}

export function SecurityPage() {
  const [platforms, setPlatforms] = useState<{ items: { id: string; name: string; tier: number }[] } | null>(null);

  useEffect(() => {
    let cancelled = false;
    mePortalApi.accessContext
      .platforms()
      .then((ctx) => !cancelled && setPlatforms({ items: ctx.items }))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <>
      <PageHeader title="账号安全" description="会话状态与当前可见接入上下文。" />
      <div className="table-card" style={{ padding: '16px 18px', maxWidth: 640 }}>
        <strong style={{ fontSize: 14, color: 'var(--ptmcp-heading)' }}>当前可见平台</strong>
        {platforms ? (
          <ul style={{ margin: '10px 0 0', padding: 0, listStyle: 'none', display: 'grid', gap: 8 }}>
            {platforms.items.map((p) => (
              <li key={p.id} style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 13 }}>
                <StatusDot tone="success">·</StatusDot>
                <strong>{p.name}</strong>
                <Badge tone="neutral">Tier {p.tier}</Badge>
              </li>
            ))}
          </ul>
        ) : (
          <p style={{ margin: '10px 0 0', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>加载接入上下文…</p>
        )}
        <div style={{ marginTop: 14 }}>
          <Alert kind="info">修改密码与会话管理暂未在预览模式提供;接入线上环境后通过会话管理页操作。</Alert>
        </div>
      </div>
    </>
  );
}
