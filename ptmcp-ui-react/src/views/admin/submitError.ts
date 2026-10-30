/** 角色提交错误分类 — 纯函数，供对话框与测试共用。
 * 原则：只有服务端明确「未处理该写」的结局才允许丢弃创建意图；其余一律保留原 key+body。 */

export type SubmitOutcome = 'uncertain' | 'conflict' | 'duplicate' | 'validation';
export type SubmitMode = 'create' | 'edit';

/** 传输/解析层明确表示「不知道服务端做了什么」的错误码：优先于 HTTP 状态判断。
 * http.ts 解析失败时带原 HTTP 状态抛 INVALID_RESPONSE，若先按 400/422 判 validation 会错误丢弃创建意图。 */
const UNKNOWN_CODES = new Set(['OPERATION_UNKNOWN', 'NETWORK_ERROR', 'INVALID_RESPONSE', 'OPERATION_IN_PROGRESS']);

/**
 * 分类提交错误：
 * - uncertain：UNKNOWN/INVALID_RESPONSE/NETWORK_ERROR/IN_PROGRESS 等未知码（无论带什么状态码）、5xx、无状态码、未分类
 * - conflict：版本冲突（412 或 409+VERSION_CONFLICT），服务端确认未处理
 * - duplicate：编码/键冲突（409 + 明确 DUPLICATE_KEY/ALREADY_EXISTS code），服务端确认未处理
 * - validation：请求被拒绝（400/422，且非未知码），服务端确认未处理
 */
export function classifySubmitError(error: { status?: number; code?: string }): SubmitOutcome {
  // 未知码先判：状态码可能来自未解析成功的响应，不能证明写未提交
  if (error.code && UNKNOWN_CODES.has(error.code)) return 'uncertain';
  // 明确的「服务端未处理」结局
  if (error.status === 412) return 'conflict';
  if (error.status === 409 && error.code === 'VERSION_CONFLICT') return 'conflict';
  if (error.status === 409 && (error.code === 'DUPLICATE_KEY' || error.code === 'ALREADY_EXISTS')) return 'duplicate';
  if (error.status === 400 || error.status === 422) return 'validation';
  return 'uncertain';
}

/** 是否保留创建意图（同 key + 同 body 重试）：仅创建场景的 uncertain 保留。 */
export function shouldPreserveIntent(outcome: SubmitOutcome): boolean {
  return outcome === 'uncertain';
}

/** 分类结果 → 用户可读文案。编辑与创建语义不同：编辑不是幂等写，不能承诺「不会重复」。 */
export function submitErrorMessage(outcome: SubmitOutcome, fallback: string, mode: SubmitMode = 'create'): string {
  switch (outcome) {
    case 'conflict':
      return '该角色已被他人修改。请关闭对话框后重新打开以获取最新版本。';
    case 'duplicate':
      return '角色编码已存在，请更换编码后重试。';
    case 'validation':
      return '请求被服务端拒绝，请检查输入内容。';
    case 'uncertain':
      return mode === 'create'
        ? '提交结果未知：创建可能已生效。已保留创建意图，用同一请求键重试不会重复创建；若仍不确定，请关闭后刷新列表核对。'
        : '提交结果未知：更新可能已生效，也可能未生效。请关闭对话框并刷新列表核对最新版本后再操作，不要直接重复提交。';
    default:
      return fallback;
  }
}

/** 「结果未知」提示条文案，按创建/编辑区分幂等语义。 */
export function uncertainNotice(mode: SubmitMode): string {
  return mode === 'create'
    ? '服务端可能已处理该请求但结果不确定。已保留创建意图，用同一请求键重试是安全的（服务端幂等，不会重复创建）。若多次不确定，请关闭后刷新列表核对，或联系管理员。'
    : '服务端可能已处理该请求但结果不确定。更新可能已生效，也可能未生效；请关闭对话框并刷新列表核对最新版本后再操作，不要直接重复提交。';
}

/** 结果未知时主按钮文案。 */
export function uncertainActionLabel(mode: SubmitMode): string {
  return mode === 'create' ? '用同一请求键重试' : '重新提交修改';
}
