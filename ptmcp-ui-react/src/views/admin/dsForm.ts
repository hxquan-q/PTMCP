import type { DatasourceRead } from '../../services/data';

/** 数据源表单模型与校验 — DetailPages（编辑）与 DatasourcesPage（新建）共用。 */
export interface DsForm {
  code: string;
  name: string;
  engine: string;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  credential: string;
  tlsMode: 'VERIFY_FULL';
  poolMax: number;
  status: string;
}

export function emptyDsForm(): DsForm {
  return {
    code: '',
    name: '',
    engine: 'MYSQL',
    host: '',
    port: 3306,
    databaseName: '',
    username: '',
    credential: '',
    tlsMode: 'VERIFY_FULL',
    poolMax: 2,
    status: 'DRAFT',
  };
}

export function dsForm(value: DatasourceRead): DsForm {
  return {
    code: value.code,
    name: value.name,
    engine: value.engine,
    host: value.host,
    port: value.port,
    databaseName: value.databaseName,
    username: value.username,
    credential: '',
    tlsMode: 'VERIFY_FULL',
    poolMax: value.poolMax,
    status: value.status,
  };
}

export function dsValidate(form: DsForm): Record<string, string> {
  const errs: Record<string, string> = {};
  if (!form.name.trim()) errs.name = '请输入名称';
  else if (form.name.length > 128) errs.name = '名称最多128字符';
  if (!form.host.trim()) errs.host = '请输入主机';
  else if (form.host.length > 253) errs.host = '主机最多253字符';
  if (!form.databaseName.trim()) errs.databaseName = '请输入数据库名';
  else if (form.databaseName.length > 128) errs.databaseName = '数据库名最多128字符';
  if (!form.username.trim()) errs.username = '请输入用户名';
  else if (form.username.length > 128) errs.username = '用户名最多128字符';
  if (form.credential.length > 1024) errs.credential = '连接凭据最多1024字符';
  if (!Number.isInteger(form.port) || form.port < 1 || form.port > 65535) errs.port = '端口必须为1～65535';
  if (!Number.isInteger(form.poolMax) || form.poolMax < 1 || form.poolMax > 8) errs.poolMax = '连接池上限必须为1～8';
  return errs;
}

/** 新建专用：编码在编辑时不可改，仅在创建时校验。 */
export function dsCreateValidate(form: DsForm): Record<string, string> {
  const errs = dsValidate(form);
  const code = form.code.trim();
  if (!code) errs.code = '请输入编码';
  else if (!/^[a-z][a-z0-9-]{1,63}$/.test(code)) errs.code = '编码须为小写字母开头，仅含小写字母、数字与短横线，长度 2–64';
  if (!form.credential.trim()) errs.credential = '请输入连接凭据（仅创建时提供，之后不可回读）';
  return errs;
}
