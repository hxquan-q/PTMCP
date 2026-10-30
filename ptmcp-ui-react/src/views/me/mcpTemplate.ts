/** 服务端 mcp-config.configTemplate（JSON 字符串）→ 展示用 JSON/YAML 模板。纯函数，供测试覆盖。 */
export interface McpTemplates {
  json: string;
  yaml: string;
}

function yamlScalar(value: unknown): string {
  if (typeof value === 'string') {
    return /^[\w./:@+-]+$/.test(value) ? value : JSON.stringify(value);
  }
  return String(value);
}

function toYamlLines(value: unknown, indent: number): string[] {
  const pad = ' '.repeat(indent);
  if (Array.isArray(value)) {
    if (value.length === 0) return [`${pad}[]`];
    return value.flatMap((item) =>
      item !== null && typeof item === 'object' ? [`${pad}-`, ...toYamlLines(item, indent + 2)] : [`${pad}- ${yamlScalar(item)}`],
    );
  }
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length === 0) return [`${pad}{}`];
    return entries.flatMap(([key, child]) => {
      if (child === null || typeof child !== 'object') return [`${pad}${key}: ${yamlScalar(child)}`];
      const nested = Object.keys(child as Record<string, unknown>);
      if (nested.length === 0) return [`${pad}${key}: ${Array.isArray(child) ? '[]' : '{}'}`];
      return [`${pad}${key}:`, ...toYamlLines(child, indent + 2)];
    });
  }
  return [`${pad}${yamlScalar(value)}`];
}

/** 解析失败返回 null；调用方必须禁用复制/完成并提示，不得回退示例或通用模板。 */
export function templatesFromConfig(rawConfigTemplate: string): McpTemplates | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(rawConfigTemplate);
  } catch {
    return null;
  }
  if (parsed === null || typeof parsed !== 'object') return null;
  return {
    json: JSON.stringify(parsed, null, 2),
    yaml: toYamlLines(parsed, 0).join('\n'),
  };
}

/** 服务端 authentication 数组 → 认证卡片可用性；未返回认证元数据时全部不可用（fail-closed，未知不等于支持）。 */
export function authAvailability(serverAuthentication: string[] | null | undefined): Record<'oauth-pat' | 'apikey' | 'none', boolean> {
  if (!serverAuthentication || serverAuthentication.length === 0) return { 'oauth-pat': false, apikey: false, none: false };
  const list = serverAuthentication.map((item) => item.toUpperCase());
  return {
    'oauth-pat': list.includes('OAUTH') || list.includes('PAT') || list.includes('OAUTH / PAT'),
    apikey: list.includes('API_KEY') || list.includes('APIKEY') || list.includes('API KEY'),
    none: list.includes('NONE') || list.includes('无认证'),
  };
}
