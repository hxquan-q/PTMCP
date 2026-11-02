import type { PlatformWrite } from '../../services/access';

export const CATALOG_MODES = ['DISCOVER', 'HIDE'];
export const PROOF_MODES = ['NONE', 'MTLS', 'PROXY_SECRET'];

interface ExistingPlatform {
  code: string;
  name: string;
  tier: number;
  catalogMode: string;
  proofMode: string;
  proofConfigured?: boolean;
  status: string;
}

export interface PlatformForm {
  code: string;
  name: string;
  tier: string;
  catalogMode: string;
  proofMode: string;
  status: string;
  certificateFingerprint: string;
  proxyNetwork: string;
  secret: string;
}

export function platformForm(existing?: ExistingPlatform | null): PlatformForm {
  return {
    code: existing?.code ?? '',
    name: existing?.name ?? '',
    tier: String(existing?.tier ?? 3),
    catalogMode: existing?.catalogMode ?? 'DISCOVER',
    proofMode: existing?.proofMode ?? 'NONE',
    status: existing?.status ?? 'ACTIVE',
    certificateFingerprint: '',
    proxyNetwork: '',
    secret: '',
  };
}

export function validatePlatformForm(values: PlatformForm, existing?: ExistingPlatform | null, replacementRequired = false): string {
  if (!existing && !/^[a-zA-Z][a-zA-Z0-9_-]{0,63}$/.test(values.code.trim()))
    return '平台编码须以字母开头，仅含字母、数字、下划线与短横线，长度 1–64';
  if (!values.name.trim() || values.name.trim().length > 128) return '平台名称长度须为 1–128 个字符';
  if (!['1', '2', '3'].includes(values.tier)) return '请选择平台层级';
  if (!CATALOG_MODES.includes(values.catalogMode)) return '请选择有效目录模式';
  if (!PROOF_MODES.includes(values.proofMode)) return '请选择有效证明模式';
  if (!['ACTIVE', 'DISABLED'].includes(values.status)) return '请选择有效状态';
  if (values.tier === '1' && values.proofMode === 'NONE') return 'Tier1 必须配置 MTLS 或代理密钥证明';
  if (values.proofMode === 'NONE') return replacementRequired ? '请先明确取消本次证明替换，再修改证明模式' : '';
  const replacing = values.proofMode === 'MTLS' ? !!values.certificateFingerprint.trim() : !!(values.proxyNetwork || values.secret);
  if (!replacementRequired && !replacing && existing?.proofMode === values.proofMode && existing.proofConfigured) return '';
  if (values.proofMode === 'MTLS') {
    return /^(?:[a-fA-F0-9]{2}:?){32}$/.test(values.certificateFingerprint.trim()) ? '' : '请输入 SHA-256 证书指纹（32 字节十六进制）';
  }
  if (!values.proxyNetwork.trim() || values.proxyNetwork.length > 256) return '请输入代理源网络 CIDR（最多 256 字符）';
  if (!values.secret.trim()) return '请输入代理证明密钥';
  return '';
}

export function toPlatformWrite(values: PlatformForm): PlatformWrite {
  const body: PlatformWrite = {
    code: values.code.trim(),
    name: values.name.trim(),
    tier: Number(values.tier),
    catalogMode: values.catalogMode,
    proofMode: values.proofMode,
    status: values.status,
  };
  if (values.proofMode === 'MTLS' && values.certificateFingerprint.trim()) {
    body.proofConfig = { certificateFingerprint: values.certificateFingerprint.trim() };
  } else if (values.proofMode === 'PROXY_SECRET' && (values.proxyNetwork || values.secret)) {
    body.proofConfig = { proxyNetwork: values.proxyNetwork.trim(), secret: values.secret };
  }
  return body;
}
