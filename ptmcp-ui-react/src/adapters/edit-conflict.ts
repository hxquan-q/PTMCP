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
