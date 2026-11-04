import { createContext, useContext } from 'react';

/** S5：管理控制台的唯一门控是平台管理员身份（S1 独立列 / S2 二元门的直接对应）。 */
export const AdminGateContext = createContext<boolean>(false);

export function createAdminGateValue(platformAdmin: boolean | undefined | null): boolean {
  return platformAdmin === true;
}

export function useAdminGate(): boolean {
  return useContext(AdminGateContext);
}
