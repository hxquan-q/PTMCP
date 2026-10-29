import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  DrawerFields,
  Dialog,
  EmptyState,
  Field,
  PageHeader,
  SearchInput,
  Select,
  StatusDot,
  TextInput,
  useDebounced,
} from '../../components/common';
import { CopyIcon, PlusIcon } from '../../components/Icons';
import {
  createToken,
  listMyAuthorizations,
  getMyHistoryDetail,
  listMyHistory,
  listMyTokens,
  revokeAuthorization,
  revokeToken,
  type AuditRow,
  type AuthorizationRow,
  type TokenCreated,
  type TokenRow,
} from '../../services/access';
import { listDatasources, listMetadata, type DatasourceRead, type MetadataObject } from '../../services/data';
import { mePortalApi } from '../../services/mePortalApi';
import { BackBar } from '../admin/DetailPages';

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
  const [creating, setCreating] = useState(false);

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
      <PageHeader
        title="个人令牌"
        description="用于脚本与工具访问的访问令牌，可随时撤销。"
        actions={
          <button className="btn btn--primary" onClick={() => setCreating(true)}>
            <PlusIcon /> 新建令牌
          </button>
        }
      />
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
      {creating ? (
        <TokenDialog
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false);
            load();
          }}
        />
      ) : null}
    </>
  );
}

const PAT_SCOPES = ['mcp:catalog', 'mcp:schema', 'mcp:query'];
const MAX_PAT_DAYS = 90;

function TokenDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState('');
  const [platformId, setPlatformId] = useState('');
  const [platforms, setPlatforms] = useState<Array<{ id: string; name: string }>>([]);
  const [scopes, setScopes] = useState<string[]>(['mcp:catalog']);
  const [expiresAt, setExpiresAt] = useState(() => new Date(Date.now() + 30 * 86400_000).toISOString().slice(0, 10));
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [created, setCreated] = useState<TokenCreated | null>(null);

  useEffect(() => {
    mePortalApi.accessContext
      .platforms()
      .then((ctx) => {
        setPlatforms(ctx.items.map((p) => ({ id: p.id, name: p.name })));
        setPlatformId((cur) => cur || ctx.items[0]?.id || '');
      })
      .catch(() => setPlatforms([]));
  }, []);

  const submit = async () => {
    setFormError('');
    if (name.trim().length < 1 || name.trim().length > 128) {
      setFormError('令牌名称长度须为 1–128 个字符');
      return;
    }
    if (!platformId) {
      setFormError('请选择所属平台');
      return;
    }
    if (scopes.length === 0) {
      setFormError('请至少选择一个范围');
      return;
    }
    const expiry = new Date(`${expiresAt}T23:59:59`);
    if (Number.isNaN(expiry.getTime()) || expiry.getTime() <= Date.now() || expiry.getTime() > Date.now() + MAX_PAT_DAYS * 86400_000) {
      setFormError(`过期时间须在未来 ${MAX_PAT_DAYS} 天内`);
      return;
    }
    setSaving(true);
    try {
      const result = await createToken({ name: name.trim(), platformId, scopes, expiresAt: expiry.toISOString() });
      setCreated(result);
    } catch (e) {
      setFormError((e as Error).message || '创建失败，请稍后重试。');
    } finally {
      setSaving(false);
    }
  };

  if (created) {
    return (
      <Dialog
        open
        title="令牌已创建"
        onClose={onClose}
        width={560}
        footer={
          <button className="btn btn--primary" onClick={onSaved}>
            我已保存，关闭
          </button>
        }
      >
        <Alert kind="warning" title="令牌仅此一次展示">
          关闭后无法再次查看，请立即复制并妥善保管；泄露可随时撤销。
        </Alert>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 10 }}>
          <code
            style={{
              flex: 1,
              padding: '10px 12px',
              background: 'var(--ptmcp-canvas)',
              border: '1px solid var(--ptmcp-border)',
              borderRadius: 8,
              fontSize: 12.5,
              wordBreak: 'break-all',
              userSelect: 'all',
            }}
          >
            {created.token}
          </code>
          <button className="btn btn--secondary" onClick={() => navigator.clipboard?.writeText(created.token).catch(() => undefined)}>
            <CopyIcon /> 复制
          </button>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog open title="新建个人令牌" onClose={onClose} width={500}>
      <Field label="令牌名称" hint="1–128 个字符">
        <TextInput value={name} onChange={setName} placeholder="如：Cursor 接入专用" />
      </Field>
      <Field label="所属平台">
        <Select value={platformId} onChange={setPlatformId} width={240} options={platforms.map((p) => ({ value: p.id, label: p.name }))} />
      </Field>
      <Field label={`范围（已选 ${scopes.length}）`} hint="catalog 目录 / schema 结构 / query 查询">
        <div style={{ display: 'flex', gap: 16 }}>
          {PAT_SCOPES.map((scope) => (
            <label key={scope} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={scopes.includes(scope)}
                onChange={() => setScopes((s) => (s.includes(scope) ? s.filter((x) => x !== scope) : [...s, scope]))}
              />
              <code>{scope}</code>
            </label>
          ))}
        </div>
      </Field>
      <Field label={`过期日期（最长 ${MAX_PAT_DAYS} 天）`}>
        <TextInput value={expiresAt} onChange={setExpiresAt} type="date" />
      </Field>
      {formError ? <Alert kind="danger">{formError}</Alert> : null}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
        <button className="btn btn--secondary" onClick={onClose} disabled={saving}>
          取消
        </button>
        <button className="btn btn--primary" onClick={submit} disabled={saving}>
          {saving ? '创建中…' : '创建令牌'}
        </button>
      </div>
    </Dialog>
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
  const [loadingMore, setLoadingMore] = useState(false);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [from, setFrom] = useState(() => new Date(Date.now() - 7 * 86400_000).toISOString().slice(0, 10));
  const [to, setTo] = useState(() => new Date().toISOString().slice(0, 10));
  const [tool, setTool] = useState('');
  const [result, setResult] = useState('');
  const [verdict, setVerdict] = useState('');
  const [queryTick, setQueryTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    listMyHistory({
      from: from ? `${from}T00:00:00Z` : undefined,
      to: to ? `${to}T23:59:59Z` : undefined,
      tool: tool || undefined,
      result: result || undefined,
      verdict: verdict || undefined,
      pageSize: 20,
    })
      .then((p) => {
        if (cancelled) return;
        setRows(p.items);
        setNextCursor(p.nextCursor);
      })
      .catch(() => {
        if (!cancelled) {
          setRows([]);
          setNextCursor(null);
        }
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [queryTick, from, to, tool, result, verdict]);

  const loadMore = () => {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    listMyHistory({
      from: from ? `${from}T00:00:00Z` : undefined,
      to: to ? `${to}T23:59:59Z` : undefined,
      tool: tool || undefined,
      result: result || undefined,
      verdict: verdict || undefined,
      cursor: nextCursor,
      pageSize: 20,
    })
      .then((p) => {
        setRows((prev) => [...prev, ...p.items]);
        setNextCursor(p.nextCursor);
      })
      .catch(() => undefined)
      .finally(() => setLoadingMore(false));
  };

  return (
    <>
      <PageHeader title="查询历史" description="你的 MCP 查询与工具调用记录。" />
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '12px 14px',
            borderBottom: '1px solid var(--ptmcp-border)',
            flex: 'none',
            flexWrap: 'wrap',
          }}
        >
          <TextInput value={from} onChange={setFrom} type="date" />
          <span style={{ color: 'var(--ptmcp-text-faint)' }}>→</span>
          <TextInput value={to} onChange={setTo} type="date" />
          <Select
            value={tool}
            onChange={setTool}
            label="工具"
            options={[
              { value: '', label: '全部工具' },
              { value: 'query', label: 'query' },
              { value: 'catalog', label: 'catalog' },
              { value: 'schema', label: 'schema' },
            ]}
          />
          <Select
            value={result}
            onChange={setResult}
            label="结果"
            options={[
              { value: '', label: '全部结果' },
              { value: 'SUCCESS', label: '成功' },
              { value: 'ERROR', label: '失败' },
              { value: 'UNKNOWN', label: '未知' },
            ]}
          />
          <Select
            value={verdict}
            onChange={setVerdict}
            label="判定"
            options={[
              { value: '', label: '全部判定' },
              { value: 'ALLOW', label: '允许' },
              { value: 'DENY', label: '拒绝' },
            ]}
          />
          <button className="btn btn--secondary" onClick={() => setQueryTick((t) => t + 1)}>
            查询
          </button>
          <button
            className="btn btn--secondary"
            onClick={() => {
              setFrom(new Date(Date.now() - 7 * 86400_000).toISOString().slice(0, 10));
              setTo(new Date().toISOString().slice(0, 10));
              setTool('');
              setResult('');
              setVerdict('');
              setQueryTick((t) => t + 1);
            }}
          >
            重置
          </button>
        </div>
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
                  <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>
                    <button
                      className="link"
                      onClick={() => (window.location.hash = `/me/history/${encodeURIComponent(r.requestId)}`)}
                      aria-label={`查看请求 ${r.requestId} 详情`}
                    >
                      {fmt(r.startedAt)}
                    </button>
                  </td>
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
          {!loading && rows.length === 0 ? <EmptyState title="暂无查询历史" description="调整时间范围或筛选条件后重试。" /> : null}
        </div>
        <div className="table-footer" style={{ justifyContent: 'space-between' }}>
          <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
            已加载 {rows.length} 条{nextCursor ? ' · 可能还有更多' : ''}
          </span>
          {loading ? null : nextCursor ? (
            <button className="btn btn--secondary" onClick={loadMore} disabled={loadingMore}>
              {loadingMore ? '加载中…' : '加载更多'}
            </button>
          ) : (
            <span style={{ fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>没有更多了</span>
          )}
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

/* ─── 查询历史详情（个人视角，脱敏 SQL + 耗时分解 + 排障指引） ─── */
export function HistoryDetailPage({ requestId }: { requestId: string }) {
  const [row, setRow] = useState<AuditRow | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    setRow(null);
    getMyHistoryDetail(requestId)
      .then((data) => !cancelled && setRow(data))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : '读取失败'))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [requestId]);

  const guidance =
    row?.result === 'UNKNOWN'
      ? '执行结局不确定或请求超时，请按 requestId 排查；不要直接认定未执行或自动重复查询。'
      : row?.result === 'ERROR'
        ? '语法或只读规则拒绝时，请让 Agent 修正请求后再试；排障请使用 requestId 联系管理员。'
        : '如需排障或核对完整 SQL，请使用 requestId 联系管理员（个人视角仅展示脱敏语句）。';

  return (
    <>
      <BackBar to="/me/history" label="返回查询历史" />
      <PageHeader title={row?.requestId ?? '查询详情'} description={row ? `${fmt(row.startedAt)} · ${row.tool}` : '单次工具调用详情。'} />
      {error ? (
        <Alert kind="danger" title="查询详情读取失败">
          {error}
        </Alert>
      ) : null}
      {loading ? <Alert kind="info">加载查询详情…</Alert> : null}
      {row ? (
        <>
          <div className="table-card" style={{ padding: '16px 18px', maxWidth: 860, marginBottom: 14 }}>
            <DrawerFields
              fields={[
                {
                  label: '结果',
                  value: (
                    <StatusDot tone={row.result === 'SUCCESS' ? 'success' : row.result === 'ERROR' ? 'danger' : 'warning'}>{row.result}</StatusDot>
                  ),
                },
                { label: '判定', value: row.verdict ?? '—' },
                { label: '错误码', value: row.errorCode ?? '—' },
                { label: '完成时间', value: fmt(row.completedAt) },
              ]}
            />
          </div>
          {row.sqlMasked ? (
            <div className="table-card" style={{ padding: '16px 18px', maxWidth: 860, marginBottom: 14 }}>
              <strong style={{ display: 'block', fontSize: 12, color: 'var(--ptmcp-text-muted)', marginBottom: 6 }}>SQL（脱敏）</strong>
              <pre
                style={{
                  margin: 0,
                  padding: '12px 14px',
                  background: 'var(--ptmcp-chrome)',
                  border: '1px solid var(--ptmcp-border)',
                  borderRadius: 8,
                  fontSize: 12.5,
                  lineHeight: 1.6,
                  whiteSpace: 'pre-wrap',
                  overflow: 'auto',
                  fontFamily: "ui-monospace, 'SF Mono', Consolas, monospace",
                }}
              >
                {row.sqlMasked}
              </pre>
            </div>
          ) : null}
          <div className="table-card" style={{ padding: '16px 18px', maxWidth: 860, marginBottom: 14 }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))', gap: 10 }}>
              {[
                { label: '总耗时', value: row.durationMs != null ? `${row.durationMs} ms` : '—' },
                { label: '排队', value: row.queueMs != null ? `${row.queueMs} ms` : '—' },
                { label: '数据库', value: row.dbMs != null ? `${row.dbMs} ms` : '—' },
                { label: '返回行数', value: row.rowsReturned ?? '—' },
                { label: '响应字节', value: row.responseBytes ?? '—' },
                { label: '截断', value: row.truncated ? '是' : '否' },
              ].map((m) => (
                <div key={m.label}>
                  <div style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>{m.label}</div>
                  <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--ptmcp-heading)', marginTop: 4 }}>{m.value}</div>
                </div>
              ))}
            </div>
          </div>
          <Alert kind="info" title="排障指引">
            {guidance}
          </Alert>
        </>
      ) : null}
    </>
  );
}

/* ─── 我的数据字典详情（个人视角，只读结构与业务注释） ─── */
export function DictionaryDetailPage({ objectId }: { objectId: string }) {
  const [obj, setObj] = useState<MetadataObject | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    setObj(null);
    mePortalApi
      .dictionaryDetail<MetadataObject>(objectId, '1')
      .then((data) => !cancelled && setObj(data))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : '读取失败'))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [objectId]);

  const dict = obj?.dictionary;

  return (
    <>
      <BackBar to="/me/dictionary" label="返回我的数据字典" />
      <PageHeader
        title={obj ? (dict?.title ?? obj.objectName) : '字典详情'}
        description={
          obj ? `${obj.databaseName}${obj.schemaName ? ` / ${obj.schemaName}` : ''} · ${obj.objectType} · 个人视角只读` : '对象结构与业务字典。'
        }
      />
      {error ? (
        <Alert kind="danger" title="字典详情读取失败">
          {error}
        </Alert>
      ) : null}
      {loading ? <Alert kind="info">加载字典详情…</Alert> : null}
      {obj ? (
        <>
          <div className="table-card" style={{ padding: '16px 18px', maxWidth: 860, marginBottom: 14 }}>
            {dict?.description ? <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--ptmcp-text)' }}>{dict.description}</p> : null}
            <DrawerFields
              fields={[
                { label: '对象名', value: <code>{obj.objectName}</code> },
                { label: '类型', value: <Badge tone={obj.objectType === 'TABLE' ? 'accent' : 'neutral'}>{obj.objectType}</Badge> },
                { label: '代次', value: `v${obj.generation}` },
                { label: '表注释', value: obj.tableComment ?? '—' },
              ]}
            />
          </div>
          <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
            <div style={{ padding: '12px 14px', borderBottom: '1px solid var(--ptmcp-border)', fontSize: 13, fontWeight: 600 }}>
              列结构{' '}
              <span style={{ color: 'var(--ptmcp-text-faint)', fontWeight: 400 }}>{obj.columns?.length ? `${obj.columns.length} 列` : ''}</span>
            </div>
            <div className="table-scroll">
              {obj.columns?.length ? (
                <table className="data-table">
                  <thead>
                    <tr>
                      <th style={{ width: 40 }}>#</th>
                      <th>列名</th>
                      <th>类型</th>
                      <th>业务标题</th>
                      <th>业务说明</th>
                    </tr>
                  </thead>
                  <tbody>
                    {obj.columns.map((c) => {
                      const annotation = dict?.columns?.find((d) => (d.name ?? '').toLowerCase() === c.name.toLowerCase());
                      return (
                        <tr key={c.ordinal}>
                          <td style={{ color: 'var(--ptmcp-text-faint)' }}>{c.ordinal}</td>
                          <td>
                            <code style={{ fontSize: 12 }}>{c.name}</code>
                          </td>
                          <td style={{ color: 'var(--ptmcp-text-muted)' }}>{c.databaseType}</td>
                          <td>{annotation?.displayName ?? '—'}</td>
                          <td style={{ color: 'var(--ptmcp-text-muted)' }}>{annotation?.description ?? '—'}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              ) : (
                <EmptyState title="无列结构信息" description="该对象未同步列结构，请联系管理员触发元数据同步。" />
              )}
            </div>
          </div>
        </>
      ) : null}
    </>
  );
}
