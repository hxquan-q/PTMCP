/**
 * Query-error codes as user-readable text, aligned with the gateway's McpErrorCatalog wording.
 * Used by the personal history views; unknown codes fall back to the raw code so nothing is
 * silently swallowed.
 */
const QUERY_ERROR_TEXT: Record<string, string> = {
  INVALID_ARGUMENT: '请求参数不合法',
  TOKEN_INVALID: '当前凭证缺少执行该操作所需的授权范围',
  TOKEN_REVOKED: '当前凭证已被撤销，请重新认证',
  PLATFORM_MISMATCH: '请求声明的平台与凭证绑定平台不一致',
  RESOURCE_NOT_FOUND: '请求的数据资源不存在或不可见',
  DIALECT_CONTEXT_INVALID: '方言或上下文参数不合法',
  SCHEMA_STALE: '元数据快照缺失或已过期，请重新发起查询',
  RESOURCE_VERSION_CHANGED: '资源版本已变化，请重试',
  TABLE_NOT_AUTHORIZED: '当前账号无权查询该资源，请联系管理员',
  SQL_SYNTAX_INVALID: 'SQL 语法不合法或包含不支持的语法',
  SQL_NOT_READONLY: '仅允许只读查询语句',
  SQL_UNSUPPORTED: '包含不受支持的 SQL 结构',
  SQL_BUDGET_EXCEEDED: 'SQL 复杂度或大小超出预算',
  FUNCTION_NOT_ALLOWED: '包含不允许的函数',
  TYPE_NOT_ALLOWED: '包含不允许的数据类型',
  REWRITE_VALIDATION_FAILED: '改写后的语句未通过安全校验',
  USER_CONCURRENCY_LIMIT: '你的并发查询数已达上限，请稍后再试',
  DATASOURCE_CONCURRENCY_LIMIT: '该数据源的并发查询数已达上限，请稍后再试',
  GATEWAY_BUSY: '网关繁忙，请稍后再试',
  QUERY_TIMEOUT: '查询超时，请收窄查询范围',
  CLIENT_CANCELLED: '查询已被取消',
  DRIVER_ERROR: '数据源驱动执行出错，请检查查询或联系管理员',
  DEPENDENCY_UNAVAILABLE: '依赖服务暂不可用，请稍后重试',
};

export function queryErrorText(code: string | null | undefined): string | null {
  if (!code) return null;
  return QUERY_ERROR_TEXT[code] ?? code;
}
