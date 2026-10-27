function record(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function normalizeFields(source: Record<string, unknown>, fields: string[]): Record<string, unknown> {
  const result = { ...source };
  fields.forEach((key) => {
    if (source[key] !== null && source[key] !== undefined) result[key] = String(source[key]);
  });
  return result;
}

/** Only known identity fields are normalized. Business JSON and SQL are kept intact. */
export function normalizeOrgResponse(path: string, value: unknown): unknown {
  const fields = path.startsWith('/api/v1/admin/departments')
    ? ['id', 'parentId']
    : path.startsWith('/api/v1/admin/users')
      ? ['id', 'deptId']
      : path.startsWith('/api/v1/admin/roles')
        ? ['id']
        : path.startsWith('/api/v1/admin/clients')
          ? ['id', 'platformId', 'version']
          : path.startsWith('/api/v1/admin/platforms')
            ? ['id', 'version', 'scopeVersion', 'securityVersion']
            : path.startsWith('/api/v1/admin/delegations')
              ? ['id', 'userId', 'departmentRootId', 'resourceId', 'resourceGeneration', 'version']
              : path.startsWith('/api/v1/admin/acl/grants')
                ? ['id', 'subjectId', 'resourceId', 'resourceGeneration']
                : [];
  const normalize = (item: unknown): unknown => {
    const source = record(item);
    if (!source) return item;
    const result = normalizeFields(source, fields);
    if (path.startsWith('/api/v1/admin/users') && Array.isArray(source.roleIds)) result.roleIds = source.roleIds.map(String);
    return result;
  };
  if (Array.isArray(value)) return value.map(normalize);
  if (path.startsWith('/api/v1/admin/delegations') || path.startsWith('/api/v1/admin/platforms')) {
    const page = record(value);
    if (page && Array.isArray(page.items)) return { ...page, items: page.items.map(normalize) };
    return normalize(value);
  }
  if (path.startsWith('/api/v1/admin/acl/preview')) {
    const response = record(value);
    if (!response || !Array.isArray(response.decisions)) return value;
    return {
      ...response,
      decisions: response.decisions.map((decision: unknown) => {
        const item = record(decision);
        return item
          ? {
              ...item,
              resourceId: String(item.resourceId),
              sourceGrantIds: Array.isArray(item.sourceGrantIds) ? item.sourceGrantIds.map(String) : [],
            }
          : decision;
      }),
    };
  }
  return normalize(value);
}

export function normalizeMeResponse(path: string, value: unknown): unknown {
  const fields = path.startsWith('/api/v1/me/access-context')
    ? ['id']
    : path.startsWith('/api/v1/me/dictionary')
      ? ['objectId']
      : path.startsWith('/api/v1/me/tokens')
        ? ['id', 'platformId']
        : path.startsWith('/api/v1/me/history')
          ? ['datasourceId', 'platformId']
          : path.startsWith('/api/v1/me/authorizations')
            ? ['id', 'platformId']
            : [];
  const normalize = (item: unknown): unknown => {
    const source = record(item);
    return source ? normalizeFields(source, fields) : item;
  };
  const response = record(value);
  if (response && Array.isArray(response.items)) return { ...response, items: response.items.map(normalize) };
  if (path.startsWith('/api/v1/me/tokens') && response && record(response.metadata)) return { ...response, metadata: normalize(response.metadata) };
  return normalize(value);
}
