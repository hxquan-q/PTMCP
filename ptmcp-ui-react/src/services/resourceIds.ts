/**
 * 授权资源 ID 编码空间 — 与服务端 ResourceIds.java 对齐：
 * encoded = (typeCode << 48) | tableId，DATASOURCE=1、NAMESPACE=2、OBJECT=3。
 * 使用 BigInt 全程计算，禁止经 Number 截断；编码结果在 2^53 以内，但表 id 校验必须先于位移。
 */
export type ResourceType = 'DATASOURCE' | 'NAMESPACE' | 'OBJECT';

const TYPE_SHIFT = 48n;
const TYPE_CODES: Record<ResourceType, bigint> = { DATASOURCE: 1n, NAMESPACE: 2n, OBJECT: 3n };
const CODE_TYPES: Record<string, ResourceType> = { '1': 'DATASOURCE', '2': 'NAMESPACE', '3': 'OBJECT' };
const MAX_TABLE_ID = 1n << TYPE_SHIFT;

/** 编码资源 id；tableId 必须是十进制正整数且小于 2^48（与服务端校验一致）。 */
export function encodeResourceId(type: ResourceType, tableId: string): string {
  const trimmed = tableId.trim();
  if (!/^[1-9]\d*$/.test(trimmed)) throw new Error(`invalid table id: ${tableId}`);
  const value = BigInt(trimmed);
  if (value >= MAX_TABLE_ID) throw new Error(`table id out of range: ${tableId}`);
  return ((TYPE_CODES[type] << TYPE_SHIFT) | value).toString();
}

/** 解码资源 id；无法识别时返回 null（不抛错，便于展示层容错）。 */
export function decodeResourceId(encoded: string | number): { type: ResourceType; tableId: string } | null {
  let value: bigint;
  try {
    value = BigInt(String(encoded).trim());
  } catch {
    return null;
  }
  if (value <= 0n) return null;
  const type = CODE_TYPES[(value >> TYPE_SHIFT).toString()];
  if (!type) return null;
  return { type, tableId: (value & (MAX_TABLE_ID - 1n)).toString() };
}

/** 批量编码 OBJECT 表 id；遇到非法值返回错误文案而不是静默丢弃。 */
export function encodeObjectIds(tableIds: string[]): { ids: string[]; error: string } {
  const ids: string[] = [];
  for (const id of tableIds) {
    try {
      ids.push(encodeResourceId('OBJECT', id));
    } catch {
      return { ids: [], error: `元数据对象 id 非法：${id}` };
    }
  }
  return { ids, error: '' };
}
