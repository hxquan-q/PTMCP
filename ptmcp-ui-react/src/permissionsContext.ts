import { createContext, useContext } from 'react';
import { canPerformAll, type SensitiveAction } from './permissions';

export interface PermissionsValue {
  /** 敏感动作门控（见 SENSITIVE_ACTIONS）。 */
  can: (action: SensitiveAction) => boolean;
  /** 原始权限码判断：用于尚未收进 SENSITIVE_ACTIONS 的组织管理动作。 */
  has: (code: string) => boolean;
}

export const PermissionsContext = createContext<PermissionsValue>({ can: () => false, has: () => false });

export function createPermissionsValue(permissions: string[] | null | undefined): PermissionsValue {
  const map = canPerformAll(permissions);
  const codes = new Set(permissions ?? []);
  return { can: (action) => map[action], has: (code) => codes.has(code) };
}

export function useSensitivePermissions(): PermissionsValue {
  return useContext(PermissionsContext);
}
