import type { DatasourceRead, DatasourceWrite, DictionaryWrite, MetadataColumn, MetadataDetail } from '../services/data';

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

/** Only explicitly selected non-secret fields enter comparison state. */
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

export function dictionaryDraft(value: MetadataDetail): DictionaryWrite {
  return {
    title: value.dictionary.title ?? '',
    description: value.dictionary.description ?? '',
    ownerUserId: value.dictionary.ownerUserId,
    columns: value.columns.map((column) => {
      const annotation = value.dictionary.columns.find((item) => item.columnName.toLowerCase() === column.name.toLowerCase());
      return { columnName: column.name, title: annotation?.title ?? '', description: annotation?.description ?? '' };
    }),
  };
}

function columnStructure(column: MetadataColumn | undefined): string {
  if (!column) return '（不存在）';
  return `#${column.ordinal} · ${column.databaseType} · JDBC ${column.jdbcType} · ${column.nullable ? '可空' : '非空'} · ${column.primaryKey ? '主键' : '非主键'} · 精度 ${column.precision ?? '—'} · 小数 ${column.scale ?? '—'} · 长度 ${column.maxLength ?? '—'} · 注释 ${column.comment ?? '（空）'} · 外键 ${column.foreignKey ? JSON.stringify(column.foreignKey) : '—'}`;
}

export function dictionaryDifferences(base: MetadataDetail, latest: MetadataDetail, draft: DictionaryWrite): ConflictDifference[] {
  const before = dictionaryDraft(base);
  const after = dictionaryDraft(latest);
  const rows = (['title', 'description', 'ownerUserId'] as const).flatMap((key) => {
    const labels = { title: '业务标题', description: '业务说明', ownerUserId: '负责人 ID' };
    return difference(key, labels[key], before[key], after[key], draft[key]);
  });
  for (const key of [
    'generation',
    'snapshotVersion',
    'physicalIdentity',
    'tableComment',
    'databaseName',
    'schemaName',
    'objectName',
    'objectType',
  ] as const) {
    const labels = {
      generation: '资源代次（只读）',
      snapshotVersion: '快照版本（只读）',
      physicalIdentity: '物理标识（只读）',
      tableComment: '数据库注释（只读）',
      databaseName: '数据库名（只读）',
      schemaName: 'Schema（只读）',
      objectName: '对象名（只读）',
      objectType: '对象类型（只读）',
    };
    rows.push(...difference(key, labels[key], base[key], latest[key], base[key]));
  }
  const names = new Set([...base.columns.map((column) => column.name), ...latest.columns.map((column) => column.name)]);
  for (const name of names) {
    const previous = base.columns.find((column) => column.name === name);
    const current = latest.columns.find((column) => column.name === name);
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

/** Keep removed annotations separately so adopting new structure never silently discards a draft. */
export function rebaseDictionaryDraft(
  draft: DictionaryWrite,
  latest: MetadataDetail,
): { draft: DictionaryWrite; removed: DictionaryWrite['columns'] } {
  const server = dictionaryDraft(latest);
  const names = new Set(server.columns.map((column) => column.columnName.toLowerCase()));
  return {
    draft: {
      title: draft.title,
      description: draft.description,
      ownerUserId: draft.ownerUserId,
      columns: server.columns.map((column) => {
        const local = draft.columns.find((item) => item.columnName.toLowerCase() === column.columnName.toLowerCase());
        return { ...(local ?? column), columnName: column.columnName };
      }),
    },
    removed: draft.columns
      .filter((column) => !names.has(column.columnName.toLowerCase()) && (column.title.trim() || column.description.trim()))
      .map((column) => ({ ...column })),
  };
}

export function isVersionConflict(error: { status?: number; code?: string }): boolean {
  return error.status === 412 || (error.status === 409 && error.code === 'VERSION_CONFLICT');
}
