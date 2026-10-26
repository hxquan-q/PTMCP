export type AccessResourceType = 'DATASOURCE' | 'NAMESPACE' | 'OBJECT';
const typeCodes = { DATASOURCE: 1n, NAMESPACE: 2n, OBJECT: 3n } as const;

/** The preview endpoint uses the backend's typed resource ID space, not table IDs. */
export function encodePreviewResource(type: AccessResourceType, id: string): string {
  if (!/^[1-9]\d*$/.test(id)) throw new Error('资源 ID 必须是正整数');
  const tableId = BigInt(id);
  if (tableId >= 1n << 48n) throw new Error('资源 ID 超出当前权限服务支持的范围');
  return ((typeCodes[type] << 48n) | tableId).toString();
}

export function validGrantWindow(from: Date | null, to: Date | null): boolean {
  return from instanceof Date && Number.isFinite(from.getTime()) && (!to || (Number.isFinite(to.getTime()) && to > from));
}
