import { useCallback, useEffect, useState } from 'react';
import { Alert, Badge, DrawerFields, PageHeader, StatusDot } from '../../../components/common';
import { getAudit, listSensitiveAccess, type AuditRow, type SensitiveAccessRow } from '../../../services/access';
import { LockIcon } from '../../../components/Icons';
import { useAdminGate } from '../../../adminGateContext';

import { AuditDecryptDialog } from './AuditDecryptDialog';
import { BackBar, errorText, useDatasources } from './shared';
import { formatTaipeiTime as fmt } from '../../../services/dateTime';

export function AuditDetailPage({ id }: { id: string }) {
  const [decryptOpen, setDecryptOpen] = useState(false);
  const isAdmin = useAdminGate();
  const [trail, setTrail] = useState<SensitiveAccessRow[]>([]);
  const [trailLoading, setTrailLoading] = useState(false);
  const [trailError, setTrailError] = useState('');
  const loadTrail = useCallback((requestId: string) => {
    setTrailLoading(true);
    setTrailError('');
    listSensitiveAccess(requestId)
      .then(setTrail)
      .catch((e) => {
        setTrail([]);
        setTrailError(e instanceof Error ? e.message : '访问留痕读取失败。');
      })
      .finally(() => setTrailLoading(false));
  }, []);
  useEffect(() => {
    setTrail([]);
    loadTrail(id);
  }, [id, loadTrail]);
  const [row, setRow] = useState<AuditRow | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [, dsName] = useDatasources();

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setRow(null);
    setError('');
    getAudit(id)
      .then((r) => !cancelled && setRow(r))
      .catch((e) => !cancelled && setError(errorText(e)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [id]);

  const uncertain = row && (row.result === 'UNKNOWN' || row.result === 'START');

  return (
    <>
      <BackBar to="/admin/audit" label="返回审计记录" />
      <PageHeader title={row ? `请求 ${row.requestId}` : '审计详情'} description="单次 MCP 请求的判定证据与执行指标。" />
      {error ? (
        <Alert kind="danger" title="审计详情读取失败">
          {error}
        </Alert>
      ) : null}
      {loading ? <Alert kind="info">加载审计详情…</Alert> : null}
      {row ? (
        <div className="table-card" style={{ padding: '16px 18px', maxWidth: 860 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
            <StatusDot tone={row.result === 'SUCCESS' ? 'success' : row.result === 'ERROR' ? 'danger' : 'warning'}>
              {row.result === 'SUCCESS' ? '成功' : row.result === 'ERROR' ? '失败' : '进行中'}
            </StatusDot>
            {row.verdict ? <Badge tone={row.verdict === 'ALLOW' ? 'success' : 'danger'}>{row.verdict}</Badge> : null}
            {row.errorCode ? <Badge tone="danger">{row.errorCode}</Badge> : null}
          </div>
          {uncertain ? (
            <Alert kind="warning" title="执行结局不确定">
              该请求未完成或结局未知。按 requestId 排查,不能认定未执行或自动重试。
            </Alert>
          ) : null}
          <DrawerFields
            fields={[
              { label: '用户', value: `用户 ${row.userId}` },
              { label: '平台', value: `平台 ${row.platformId}` },
              { label: '数据源', value: dsName(row.datasourceId) },
              { label: '工具', value: row.tool },
              { label: '开始时间', value: fmt(row.startedAt) },
              { label: '完成时间', value: fmt(row.completedAt) },
            ]}
          />
          {row.sqlMasked ? (
            <div style={{ marginTop: 14 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
                <strong style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>SQL（脱敏）</strong>
                {isAdmin ? (
                  <button className="btn btn--secondary" onClick={() => setDecryptOpen(true)}>
                    <LockIcon /> 解密查看
                  </button>
                ) : null}
              </div>
              <pre
                style={{
                  margin: 0,
                  padding: '12px 14px',
                  background: 'var(--ptmcp-chrome)',
                  border: '1px solid var(--ptmcp-border)',
                  borderRadius: 'var(--ptmcp-radius-panel)',
                  fontSize: 12.5,
                  lineHeight: 1.6,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                  fontFamily: "ui-monospace, 'SF Mono', Consolas, monospace",
                  color: 'var(--ptmcp-text-strong)',
                }}
              >
                {row.sqlMasked}
              </pre>
              <AuditDecryptDialog
                open={decryptOpen}
                requestId={row.requestId}
                onDecrypted={() => loadTrail(row.requestId)}
                onClose={() => setDecryptOpen(false)}
              />
            </div>
          ) : null}
          <div className="table-card" style={{ padding: '16px 18px', maxWidth: 860, marginBottom: 14 }}>
            <strong style={{ fontSize: 14, color: 'var(--ptmcp-heading)' }}>解密访问记录</strong>
            <p style={{ margin: '6px 0 10px', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>
              每次解密都会留痕：操作者、时间、理由与字段。留痕本身不可删除。
            </p>
            {trailError ? <Alert kind="danger">{trailError}</Alert> : null}
            {trailLoading ? <Alert kind="info">读取访问留痕…</Alert> : null}
            {!trailLoading && !trailError && trail.length === 0 ? (
              <p style={{ margin: 0, fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>尚无解密记录。</p>
            ) : null}
            {trail.length ? (
              <table className="data-table">
                <thead>
                  <tr>
                    <th>时间</th>
                    <th>操作者</th>
                    <th>理由</th>
                    <th>字段</th>
                  </tr>
                </thead>
                <tbody>
                  {trail.map((t) => (
                    <tr key={t.id}>
                      <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(t.createdAt)}</td>
                      <td>用户 {t.operatorId}</td>
                      <td style={{ maxWidth: 360 }}>{t.reason}</td>
                      <td>
                        {(t.fields ?? []).map((f) => (
                          <Badge key={f} tone="neutral">
                            {f}
                          </Badge>
                        ))}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : null}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))', gap: 10, marginTop: 14 }}>
            {[
              { label: '总耗时', value: row.durationMs != null ? `${row.durationMs} ms` : '—' },
              { label: '排队', value: row.queueMs != null ? `${row.queueMs} ms` : '—' },
              { label: '数据库', value: row.dbMs != null ? `${row.dbMs} ms` : '—' },
              { label: '返回行数', value: row.rowsReturned ?? '—' },
              { label: '响应字节', value: row.responseBytes ?? '—' },
            ].map((m) => (
              <div key={m.label} className="table-card" style={{ padding: '10px 12px' }}>
                <div style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>{m.label}</div>
                <div style={{ fontSize: 16, fontWeight: 600, color: 'var(--ptmcp-heading)', marginTop: 4, fontVariantNumeric: 'tabular-nums' }}>
                  {m.value}
                </div>
              </div>
            ))}
          </div>
          {row.fingerprint ? (
            <div style={{ marginTop: 12, fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>
              指纹 <code>{row.fingerprint}</code>
              {row.truncated ? ' · 结果已截断' : ''}
            </div>
          ) : null}
        </div>
      ) : null}
    </>
  );
}

/* ─── 元数据详情:/admin/metadata/{id} ─── */
