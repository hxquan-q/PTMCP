import type { MetadataObject } from '../../services/data';

/** 权限矩阵资源树节点：SCHEMA（分组）→ OBJECT（可授权资源）。 */
export interface ResourceNode {
  key: string;
  label: string;
  sub?: string;
  kind: 'DATASOURCE' | 'SCHEMA' | 'OBJECT';
  /** OBJECT 节点才携带 — 对应预览 resourceId */
  resourceId?: string;
  children?: ResourceNode[];
}

/** 元数据对象 → schema 分组树；schema 缺省回落到 databaseName，再回落 default。 */
export function buildTree(objects: MetadataObject[]): ResourceNode[] {
  const bySchema = new Map<string, MetadataObject[]>();
  for (const obj of objects) {
    const schema = obj.schemaName ?? obj.databaseName ?? 'default';
    if (!bySchema.has(schema)) bySchema.set(schema, []);
    bySchema.get(schema)!.push(obj);
  }
  return [...bySchema.entries()].map(([schema, objs]) => ({
    key: `schema:${schema}`,
    label: schema,
    kind: 'SCHEMA' as const,
    children: objs.map((o) => ({
      key: `obj:${o.id}`,
      label: o.objectName ?? o.tableName,
      sub: o.objectType,
      kind: 'OBJECT' as const,
      resourceId: o.id,
    })),
  }));
}

/** 按 key 深度查找节点（键盘导航/记忆展开用）。 */
export function findResourceNode(nodes: ResourceNode[], key: string): ResourceNode | null {
  for (const node of nodes) {
    if (node.key === key) return node;
    if (node.children) {
      const hit = findResourceNode(node.children, key);
      if (hit) return hit;
    }
  }
  return null;
}

/** 收集树中全部 OBJECT 节点的 resourceId（「全选本源」/批量授权用）。 */
export function collectResourceIds(nodes: ResourceNode[]): string[] {
  return nodes.flatMap((node) => (node.kind === 'OBJECT' && node.resourceId ? [node.resourceId] : collectResourceIds(node.children ?? [])));
}
