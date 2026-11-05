import { useState } from 'react';
import { Button, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Alert, Field, TextInput } from '../../../components/common';
import { DataRequestError, getMetadataObject, type MetadataObject } from '../../../services/data';
import { dictionaryDifferences, dictionaryDraft, rebaseDictionaryDraft } from '../../../adapters/edit-conflict';
import type { ConflictDifference } from '../../../adapters/edit-conflict';
import { updateDictionary, type DictionaryWrite } from '../../../services/data';
import { EditIcon } from '../../../components/Icons';
import { showToast } from '../../../toast';

const conflictColumns: ColumnsType<ConflictDifference> = [
  {
    title: '字段',
    dataIndex: 'label',
    render: (v: string, r) => (
      <>
        <strong>{v}</strong>
        {r.serverChanged ? <span style={{ marginLeft: 6, fontSize: 11, color: 'var(--ptmcp-orange)' }}>服务端已变</span> : null}
      </>
    ),
  },
  { title: '我编辑前', dataIndex: 'base', render: (v: string) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>{v}</span> },
  { title: '服务端最新', dataIndex: 'latest', render: (v: string) => <span style={{ color: 'var(--ptmcp-text-strong)' }}>{v}</span> },
  { title: '我的草稿', dataIndex: 'draft', render: (v: string) => <span style={{ color: 'var(--ptmcp-blue)' }}>{v}</span> },
];

export function DictionaryEditor({
  obj,
  etag,
  onSaved,
}: {
  obj: MetadataObject;
  etag: string | null;
  onSaved: (next: MetadataObject, etag: string | null) => void;
}) {
  const [draft, setDraft] = useState<DictionaryWrite | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [conflict, setConflict] = useState<{ latest: MetadataObject; etag: string | null } | null>(null);
  const [removedNotes, setRemovedNotes] = useState<DictionaryWrite['columns']>([]);

  const conflictRows: ConflictDifference[] = conflict && draft ? dictionaryDifferences(obj, conflict.latest, draft) : [];

  const setColumn = (columnName: string, key: 'title' | 'description', value: string) => {
    setDraft((d) => (d ? { ...d, columns: d.columns.map((c) => (c.columnName === columnName ? { ...c, [key]: value } : c)) } : d));
  };

  /* 可编辑单元格：TextInput 受控于 draft，rowKey=columnName 保证逐键重渲染时输入焦点不丢。 */
  const draftColumns: ColumnsType<DictionaryWrite['columns'][number]> = [
    { title: '列', dataIndex: 'columnName', width: 160, render: (v: string) => <code style={{ fontSize: 12 }}>{v}</code> },
    {
      title: '业务标题',
      dataIndex: 'title',
      render: (v: string, c) => <TextInput value={v} onChange={(next) => setColumn(c.columnName, 'title', next)} maxLength={128} />,
    },
    {
      title: '业务说明',
      dataIndex: 'description',
      render: (v: string, c) => <TextInput value={v} onChange={(next) => setColumn(c.columnName, 'description', next)} maxLength={8000} />,
    },
  ];

  const save = async () => {
    if (!draft) return;
    if (!etag) {
      setSaveError('缺少版本信息，请刷新页面后重试。');
      return;
    }
    setSaving(true);
    setSaveError('');
    try {
      const { data, etag: nextEtag } = await updateDictionary(obj.id, draft, etag);
      setDraft(null);
      setConflict(null);
      onSaved(data, nextEtag);
      showToast('字典已保存');
    } catch (err) {
      if (err instanceof DataRequestError && err.status === 412) {
        try {
          const { data: latest, etag: latestEtag } = await getMetadataObject(obj.id);
          setConflict({ latest, etag: latestEtag });
        } catch {
          setSaveError('版本冲突且读取最新版本失败，请刷新页面。');
        }
      } else {
        setSaveError(err instanceof Error ? err.message : '保存失败，请稍后重试。');
      }
    } finally {
      setSaving(false);
    }
  };

  const adoptServerAndRebase = () => {
    if (!conflict || !draft) return;
    const { draft: rebased, removed } = rebaseDictionaryDraft(draft, conflict.latest);
    setDraft(rebased);
    setRemovedNotes(removed);
    onSaved(conflict.latest, conflict.etag);
    setConflict(null);
  };

  const discardDraft = () => {
    setDraft(null);
    setConflict(null);
    setRemovedNotes([]);
    setSaveError('');
  };

  return (
    <div className="table-card" style={{ padding: '16px 18px', marginBottom: 14, maxWidth: 860 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
        <strong style={{ fontSize: 14, color: 'var(--ptmcp-heading)' }}>业务字典</strong>
        {draft ? (
          <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>编辑中 · 字典版本 {obj.dictionary?.version ?? '—'}</span>
        ) : (
          <Button onClick={() => setDraft(dictionaryDraft(obj))}>
            <EditIcon /> 编辑字典
          </Button>
        )}
      </div>

      {conflict ? (
        <div style={{ marginBottom: 12 }}>
          <Alert kind="warning" title="版本冲突（412）">
            该对象的字典在你编辑期间已被他人修改（结构或注释可能已变化）。可采纳服务端最新结构并保留你的注释（变基），或放弃本地修改。
          </Alert>
          {conflictRows.length ? (
            <div className="table-scroll" role="region" aria-label="字段冲突对比" tabIndex={0} style={{ marginTop: 10 }}>
              <Table<ConflictDifference>
                rowKey="key"
                size="middle"
                dataSource={conflictRows}
                columns={conflictColumns}
                pagination={false}
                locale={{ emptyText: <span /> }}
              />
            </div>
          ) : null}
          <div style={{ display: 'flex', gap: 10, marginTop: 10 }}>
            <Button onClick={discardDraft}>放弃本地修改</Button>
            <Button type="primary" onClick={adoptServerAndRebase}>
              采纳服务端结构并保留我的注释
            </Button>
          </div>
        </div>
      ) : null}

      {removedNotes.length ? (
        <Alert kind="warning" title={`${removedNotes.length} 列已从最新结构中移除`}>
          这些列的注释不会被丢弃，如需保留请合并到相邻列说明：{removedNotes.map((c) => c.columnName).join('、')}
        </Alert>
      ) : null}

      {draft ? (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: '2fr 3fr 1fr', gap: 12, marginBottom: 12 }}>
            <Field label="业务标题" hint="最长 128">
              <TextInput value={draft.title} onChange={(v) => setDraft((d) => (d ? { ...d, title: v } : d))} maxLength={128} />
            </Field>
            <Field label="业务说明" hint="最长 8000">
              <TextInput value={draft.description} onChange={(v) => setDraft((d) => (d ? { ...d, description: v } : d))} maxLength={8000} />
            </Field>
            <Field label="负责人 ID" hint="可空">
              <TextInput value={draft.ownerUserId ?? ''} onChange={(v) => setDraft((d) => (d ? { ...d, ownerUserId: v || null } : d))} />
            </Field>
          </div>
          {draft.columns.length ? (
            <div className="table-scroll" role="region" aria-label="字典列编辑" tabIndex={0} style={{ marginBottom: 12 }}>
              <Table<DictionaryWrite['columns'][number]>
                rowKey="columnName"
                size="middle"
                dataSource={draft.columns}
                columns={draftColumns}
                pagination={false}
                scroll={{ y: 320 }}
                locale={{ emptyText: <span /> }}
              />
            </div>
          ) : (
            <p style={{ margin: '0 0 12px', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>该对象没有列结构信息，仅可编辑对象级字典字段。</p>
          )}
          {saveError ? <Alert kind="danger">{saveError}</Alert> : null}
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
            <Button onClick={discardDraft} disabled={saving}>
              取消
            </Button>
            <Button type="primary" onClick={save} disabled={saving}>
              {saving ? '保存中…' : '保存字典'}
            </Button>
          </div>
        </>
      ) : (
        <p style={{ margin: 0, fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>
          {obj.dictionary?.title || obj.dictionary?.description
            ? `${obj.dictionary.title ?? ''}${obj.dictionary.description ? ` — ${obj.dictionary.description}` : ''}`
            : '尚未填写业务字典。点击「编辑字典」为该对象与各列补充业务含义。'}
        </p>
      )}
    </div>
  );
}

/* ─── 用户详情（只读，列表弹窗承载编辑/重置密码） ─── */
