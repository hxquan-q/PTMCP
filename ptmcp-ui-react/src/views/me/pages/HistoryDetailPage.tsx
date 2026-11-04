import { useEffect, useState } from 'react';
import { Alert, DrawerFields, PageHeader, StatusDot } from '../../../components/common';
import { getMyHistoryDetail, type AuditRow } from '../../../services/access';
import { BackBar } from '../../admin/DetailPages';
import { formatTaipeiTime as fmt } from '../../../services/dateTime';

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
                  borderRadius: 'var(--ptmcp-radius-control)',
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
