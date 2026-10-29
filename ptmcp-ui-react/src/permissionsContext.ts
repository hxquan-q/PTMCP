import { createContext, useContext } from 'react';
import { canPerformAll, type SensitiveAction } from './permissions';

export interface PermissionsValue {
  can: (action: SensitiveAction) => boolean;
}

export const PermissionsContext = createContext<PermissionsValue>({ can: () => false });

export function createPermissionsValue(permissions: string[] | null | undefined): PermissionsValue {
  const map = canPerformAll(permissions);
  return { can: (action) => map[action] };
}

export function useSensitivePermissions(): PermissionsValue {
  return useContext(PermissionsContext);
}
