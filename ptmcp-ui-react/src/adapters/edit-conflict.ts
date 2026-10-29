import type { DatasourceRead, DatasourceWrite } from '../services/data';

export interface ConflictDifference {
  key: string;
  label: string;
  base: string;
  latest: string;
  draft: string;
  serverChanged: boolean;
  draftChanged: boolean;
}

function difference(key: string, label: string, base: unknown, latest: unknown, draft: unknown): ConflictDifference[] {
  const text = (value: unknown): string => (value === null || value === undefined || value === '' ? '（空）' : String(value));
  const values = { base: text(base), latest: text(latest), draft: text(draft) };
  const serverChanged = String(base ?? '') !== String(latest ?? '');
  const draftChanged = String(base ?? '') !== String(draft ?? '');
  return serverChanged || draftChanged ? [{ key, label, ...values, serverChanged, draftChanged }] : [];
}

/** 仅显式挑选的非敏感字段进入三方比较;凭据永不回显、不参与 diff。 */
export function datasourceDifferences(base: DatasourceRead, latest: DatasourceRead, draft: DatasourceWrite): ConflictDifference[] {
  const fields = {
    name: '名称',
    host: '主机',
    port: '端口',
    databaseName: '数据库名',
    username: '用户名',
    poolMax: '连接池上限',
    status: '状态',
    tlsMode: 'TLS',
  } as const;
  return (Object.keys(fields) as Array<keyof typeof fields>).flatMap((key) => difference(key, fields[key], base[key], latest[key], draft[key]));
}

/** 服务端对带 version 的行在写操作上施加 If-Match 门禁;412 + VERSION_CONFLICT 视为乐观并发冲突。 */
export function isVersionConflict(error: { status?: number; code?: string }): boolean {
  return error.status === 412 && error.code === 'VERSION_CONFLICT';
}

/* ─── 字典编辑的三方比较（与 Vue edit-conflict 同源规则，适配 MetadataObject 内联 dictionary） ─── */
import type { DictionaryWrite, MetadataColumn, MetadataObject } from '../services/data';

type DictionaryAnnotation = { name?: string; displayName?: string; description?: string } | undefined;

function annotationOf(object: MetadataObject, columnName: string): DictionaryAnnotation {
  return (object.dictionary?.columns ?? []).find((item) => (item.name ?? '').toLowerCase() === columnName.toLowerCase());
}

/** 由当前对象生成可编辑草稿：对象级字典字段 + 按物理列展开的注释。 */
export function dictionaryDraft(object: MetadataObject): DictionaryWrite {
  return {
    title: object.dictionary?.title ?? '',
    description: object.dictionary?.description ?? '',
    ownerUserId: object.dictionary?.ownerUserId ?? null,
    columns: (object.columns ?? []).map((column) => {
      const annotation = annotationOf(object, column.name);
      return { columnName: column.name, title: annotation?.displayName ?? '', description: annotation?.description ?? '' };
    }),
  };
}

function columnStructure(column: MetadataColumn | undefined): string {
  if (!column) return '（不存在）';
  return `#${column.ordinal} · ${column.databaseType} · ${column.nullable ? '可空' : '非空'} · ${column.primaryKey ? '主键' : '非主键'} · 精度 ${column.precision ?? '—'} · 长度 ${column.maxLength ?? '—'} · 注释 ${column.comment ?? '（空）'}`;
}

/** 三方比较：服务端变化（serverChanged）与本地草稿变化（draftChanged）分别标记；只读结构字段参与提示。 */
export function dictionaryDifferences(base: MetadataObject, latest: MetadataObject, draft: DictionaryWrite): ConflictDifference[] {
  const before = dictionaryDraft(base);
  const after = dictionaryDraft(latest);
  const rows = (['title', 'description', 'ownerUserId'] as const).flatMap((key) => {
    const labels = { title: '业务标题', description: '业务说明', ownerUserId: '负责人 ID' };
    return difference(key, labels[key], before[key], after[key], draft[key]);
  });
  for (const key of ['generation', 'snapshotVersion', 'tableComment', 'databaseName', 'schemaName', 'objectName', 'objectType'] as const) {
    const labels = {
      generation: '资源代次（只读）',
      snapshotVersion: '快照版本（只读）',
      tableComment: '数据库注释（只读）',
      databaseName: '数据库名（只读）',
      schemaName: 'Schema（只读）',
      objectName: '对象名（只读）',
      objectType: '对象类型（只读）',
    };
    rows.push(...difference(key, labels[key], base[key], latest[key], base[key]));
  }
  const names = new Set([...(base.columns ?? []).map((column) => column.name), ...(latest.columns ?? []).map((column) => column.name)]);
  for (const name of names) {
    const previous = (base.columns ?? []).find((column) => column.name === name);
    const current = (latest.columns ?? []).find((column) => column.name === name);
    rows.push(
      ...difference(`structure:${name}`, `${name} / 结构（只读）`, columnStructure(previous), columnStructure(current), columnStructure(previous)),
    );
    for (const key of ['title', 'description'] as const) {
      const annotation = (value: DictionaryWrite) => value.columns.find((column) => column.columnName.toLowerCase() === name.toLowerCase())?.[key];
      rows.push(
        ...difference(
          `column:${JSON.stringify([name, key])}`,
          `${name} / ${key === 'title' ? '业务标题' : '业务说明'}`,
          annotation(before),
          annotation(after),
          annotation(draft),
        ),
      );
    }
  }
  return rows;
}

/** 把草稿变基到最新结构：保留仍存在列的注释，被删除列的注释单独返回，绝不静默丢弃。 */
export function rebaseDictionaryDraft(
  draft: DictionaryWrite,
  latest: MetadataObject,
): { draft: DictionaryWrite; removed: DictionaryWrite['columns'] } {
  const next = dictionaryDraft(latest);
  const removed: DictionaryWrite['columns'] = [];
  for (const entry of draft.columns) {
    const target = next.columns.find((column) => column.columnName.toLowerCase() === entry.columnName.toLowerCase());
    if (target) {
      target.title = entry.title;
      target.description = entry.description;
    } else {
      removed.push(entry);
    }
  }
  next.title = draft.title;
  next.description = draft.description;
  next.ownerUserId = draft.ownerUserId;
  return { draft: next, removed };
}
