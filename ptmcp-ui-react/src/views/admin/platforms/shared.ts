/* 平台与客户端页共用的常量与表单类型：主页面与三个弹窗都要用，单独放一份避免循环引用。 */
export const TIER_LABEL: Record<number, string> = { 1: '一级', 2: '二级', 3: '三级' };
export const CLIENT_TYPES = ['PUBLIC', 'CONFIDENTIAL'];
export const CLIENT_STATUSES = ['ACTIVE', 'DISABLED'];

export interface ClientForm {
  platformId: string;
  name: string;
  type: string;
  status: string;
  authMethods: string[];
  redirectUrisText: string;
  allowedScopes: string[];
  jwksUri: string;
}
