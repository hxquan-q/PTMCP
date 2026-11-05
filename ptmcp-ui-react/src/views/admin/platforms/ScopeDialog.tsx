import { useEffect, useMemo, useState } from 'react';
import { Button, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Alert, Dialog, EmptyState, Select, TextInput } from '../../../components/common';
import { getPlatformScopes, savePlatformScopes, type ScopeRule, type PlatformRow } from '../../../services/access';
import { scopeChangeText, scopeChanges, scopeKey, scopeRuleText } from '../scopeDiff';
import { showToast } from '../../../toast';

type ChangeRow = { key: string; kind: '新增' | '移除' | '修改'; text: string };

const changeColumns: ColumnsType<ChangeRow> = [
  {
    title: '变更',
    dataIndex: 'kind',
    width: 88,
    render: (v: ChangeRow['kind']) => (
      <span style={{ color: v === '新增' ? 'var(--ptmcp-green)' : v === '移除' ? 'var(--ptmcp-red)' : 'var(--ptmcp-orange)' }}>{v}</span>
    ),
  },
  { title: '规则', dataIndex: 'text' },
];

export function ScopeDialog({ platform, onClose, onSaved }: { platform: PlatformRow; onClose: () => void; onSaved: () => void }) {
  const [rules, setRules] = useState<ScopeRule[]>([]);
  const [base, setBase] = useState<ScopeRule[]>([]);
  const [version, setVersion] = useState('');
  const [scopeVersion, setScopeVersion] = useState('');
  const [loading, setLoading] = useState(true);
  const [confirming, setConfirming] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getPlatformScopes(platform.id)
      .then((read) => {
        if (cancelled) return;
        setRules(read.items);
        setBase(read.items);
        setVersion(read.version);
        setScopeVersion(read.scopeVersion);
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : '读取授权范围失败。'))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [platform.id]);

  const changes = useMemo(() => scopeChanges(base, rules), [base, rules]);
  const dirty = changes.added.length + changes.removed.length + changes.modified.length > 0;

  const updateRule = (index: number, patch: Partial<ScopeRule>) => setRules((rs) => rs.map((r, i) => (i === index ? { ...r, ...patch } : r)));

  /* 可编辑规则行：index 兼作 rowKey 与更新定位，编辑 resourceId 时行键不随内容变化，输入焦点才不丢。 */
  const ruleColumns: ColumnsType<ScopeRule> = [
    {
      title: '资源类型',
      dataIndex: 'resourceType',
      render: (v: string, _r, i) => (
        <Select
          ariaLabel="授权范围资源类型"
          value={v}
          onChange={(next) => updateRule(i, { resourceType: next })}
          options={[
            { value: 'OBJECT', label: 'OBJECT' },
            { value: 'DATASOURCE', label: 'DATASOURCE' },
            { value: 'NAMESPACE', label: 'NAMESPACE' },
          ]}
        />
      ),
    },
    {
      title: '资源 ID',
      dataIndex: 'resourceId',
      render: (v: string, _r, i) => <TextInput value={v} onChange={(next) => updateRule(i, { resourceId: next })} />,
    },
    {
      title: '代次',
      dataIndex: 'resourceGeneration',
      render: (v: string, _r, i) => <TextInput value={v} onChange={(next) => updateRule(i, { resourceGeneration: next })} />,
    },
    {
      title: '动作',
      dataIndex: 'action',
      render: (v: string, _r, i) => (
        <Select
          ariaLabel="授权范围动作"
          value={v}
          onChange={(next) => updateRule(i, { action: next })}
          options={[
            { value: 'META', label: 'META' },
            { value: 'READ', label: 'READ' },
          ]}
        />
      ),
    },
    {
      title: '子级',
      dataIndex: 'includeDescendants',
      render: (v: boolean, _r, i) => (
        <input type="checkbox" checked={v} onChange={(e) => updateRule(i, { includeDescendants: e.target.checked })} aria-label="包含子级" />
      ),
    },
    {
      title: '',
      width: 60,
      render: (_v, _r, i) => (
        <Button
          type="link"
          size="small"
          danger
          style={{ padding: 0 }}
          onClick={() => setRules((rs) => rs.filter((_, idx) => idx !== i))}
          aria-label="移除规则"
        >
          移除
        </Button>
      ),
    },
  ];

  const changeRows: ChangeRow[] = [
    ...changes.added.map((r) => ({ key: `a:${scopeKey(r)}`, kind: '新增' as const, text: scopeRuleText(r) })),
    ...changes.removed.map((r) => ({ key: `r:${scopeKey(r)}`, kind: '移除' as const, text: scopeRuleText(r) })),
    ...changes.modified.map((m) => ({
      key: `m:${scopeKey(m.after)}`,
      kind: '修改' as const,
      text: `${scopeRuleText(m.after)}（${scopeChangeText(m)}）`,
    })),
  ];

  const save = async () => {
    setSaving(true);
    setError('');
    try {
      const read = await savePlatformScopes(platform.id, rules, version);
      setRules(read.items);
      setBase(read.items);
      setVersion(read.version);
      setScopeVersion(read.scopeVersion);
      setConfirming(false);
      showToast('授权范围已保存');
      onSaved();
      // 留在弹窗内展示已保存状态；由用户关闭
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 412) setError('授权范围已被他人修改，请关闭后重开以读取最新版本。');
      else setError(e instanceof Error ? e.message : '保存失败，请稍后重试。');
      setConfirming(false);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open title={`授权范围 · ${platform.name}`} onClose={onClose} width={720}>
      {loading ? <Alert kind="info">读取授权范围…</Alert> : null}
      {error ? <Alert kind="danger">{error}</Alert> : null}
      {!loading ? (
        <>
          <p style={{ margin: '0 0 10px', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>
            平台 Scope 与用户有效权限取交集后才生效；整组替换保存（scopeVersion v{scopeVersion} → 保存后递增）。
          </p>
          <div className="table-scroll" role="region" aria-label="平台授权范围规则" tabIndex={0} style={{ marginBottom: 12 }}>
            <Table<ScopeRule>
              rowKey={(_r, i) => `rule-${i}`}
              size="middle"
              dataSource={rules}
              columns={ruleColumns}
              pagination={false}
              scroll={{ y: 280 }}
              locale={{ emptyText: <EmptyState title="无授权范围规则" description="该平台在 Scope 交集后不可见任何资源；添加规则后再保存。" /> }}
            />
          </div>
          <Button
            onClick={() =>
              setRules((rs) => [
                ...rs,
                { resourceType: 'OBJECT', resourceId: '', resourceGeneration: '1', action: 'READ', includeDescendants: false },
              ])
            }
          >
            添加规则
          </Button>

          {confirming ? (
            <div style={{ marginTop: 12 }}>
              <Alert kind="warning" title="确认整组替换">
                即将保存：新增 {changes.added.length} · 移除 {changes.removed.length} · 修改 {changes.modified.length} 条规则。
              </Alert>
              <div className="table-scroll" role="region" aria-label="授权范围变更对比" tabIndex={0} style={{ marginTop: 8 }}>
                <Table<ChangeRow>
                  rowKey="key"
                  size="middle"
                  showHeader={false}
                  dataSource={changeRows}
                  columns={changeColumns}
                  pagination={false}
                  scroll={{ y: 160 }}
                  locale={{ emptyText: <span /> }}
                />
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 10 }}>
                <Button onClick={() => setConfirming(false)} disabled={saving}>
                  再想想
                </Button>
                <Button type="primary" onClick={save} disabled={saving}>
                  {saving ? '保存中…' : '确认保存'}
                </Button>
              </div>
            </div>
          ) : (
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 12 }}>
              <Button onClick={onClose} disabled={saving}>
                关闭
              </Button>
              <Button type="primary" disabled={!dirty || saving} onClick={() => setConfirming(true)}>
                保存（需确认差异）
              </Button>
            </div>
          )}
        </>
      ) : null}
    </Dialog>
  );
}
