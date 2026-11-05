import { useEffect, useMemo, useState } from 'react';
import { Button } from 'antd';
import { Alert, Dialog, EmptyState, Select, TextInput } from '../../../components/common';
import { getPlatformScopes, savePlatformScopes, type ScopeRule, type PlatformRow } from '../../../services/access';
import { scopeChangeText, scopeChanges, scopeKey, scopeRuleText } from '../scopeDiff';
import { showToast } from '../../../toast';

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
          <div className="table-scroll" role="region" aria-label="平台授权范围规则" tabIndex={0} style={{ maxHeight: 280, marginBottom: 12 }}>
            <table className="data-table">
              <thead>
                <tr>
                  <th>资源类型</th>
                  <th>资源 ID</th>
                  <th>代次</th>
                  <th>动作</th>
                  <th>子级</th>
                  <th style={{ width: 60 }} />
                </tr>
              </thead>
              <tbody>
                {rules.map((r, i) => (
                  <tr key={`${r.resourceType}:${r.resourceId}:${r.action}`}>
                    <td>
                      <Select
                        ariaLabel="授权范围资源类型"
                        value={r.resourceType}
                        onChange={(v) => updateRule(i, { resourceType: v })}
                        options={[
                          { value: 'OBJECT', label: 'OBJECT' },
                          { value: 'DATASOURCE', label: 'DATASOURCE' },
                          { value: 'NAMESPACE', label: 'NAMESPACE' },
                        ]}
                      />
                    </td>
                    <td>
                      <TextInput value={r.resourceId} onChange={(v) => updateRule(i, { resourceId: v })} />
                    </td>
                    <td>
                      <TextInput value={r.resourceGeneration} onChange={(v) => updateRule(i, { resourceGeneration: v })} />
                    </td>
                    <td>
                      <Select
                        ariaLabel="授权范围动作"
                        value={r.action}
                        onChange={(v) => updateRule(i, { action: v })}
                        options={[
                          { value: 'META', label: 'META' },
                          { value: 'READ', label: 'READ' },
                        ]}
                      />
                    </td>
                    <td>
                      <input
                        type="checkbox"
                        checked={r.includeDescendants}
                        onChange={(e) => updateRule(i, { includeDescendants: e.target.checked })}
                        aria-label="包含子级"
                      />
                    </td>
                    <td>
                      <button
                        className="link"
                        style={{ color: 'var(--ptmcp-red)' }}
                        onClick={() => setRules((rs) => rs.filter((_, idx) => idx !== i))}
                        aria-label="移除规则"
                      >
                        移除
                      </button>
                    </td>
                  </tr>
                ))}
                {rules.length === 0 ? (
                  <tr>
                    <td colSpan={6}>
                      <EmptyState title="无授权范围规则" description="该平台在 Scope 交集后不可见任何资源；添加规则后再保存。" />
                    </td>
                  </tr>
                ) : null}
              </tbody>
            </table>
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
              <div className="table-scroll" role="region" aria-label="授权范围变更对比" tabIndex={0} style={{ marginTop: 8, maxHeight: 160 }}>
                <table className="data-table">
                  <tbody>
                    {changes.added.map((r) => (
                      <tr key={`a:${scopeKey(r)}`}>
                        <td style={{ color: 'var(--ptmcp-green)' }}>新增</td>
                        <td>{scopeRuleText(r)}</td>
                      </tr>
                    ))}
                    {changes.removed.map((r) => (
                      <tr key={`r:${scopeKey(r)}`}>
                        <td style={{ color: 'var(--ptmcp-red)' }}>移除</td>
                        <td>{scopeRuleText(r)}</td>
                      </tr>
                    ))}
                    {changes.modified.map((m) => (
                      <tr key={`m:${scopeKey(m.after)}`}>
                        <td style={{ color: 'var(--ptmcp-orange)' }}>修改</td>
                        <td>
                          {scopeRuleText(m.after)}（{scopeChangeText(m)}）
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
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
