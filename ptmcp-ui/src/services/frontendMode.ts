/** This is a build mode, never a URL/localStorage flag or a production permission. */
export const frontendPreview = import.meta.env?.DEV === true && import.meta.env.MODE === 'frontend-preview';
export const controlPlaneAvailable = frontendPreview;
export const mutationNotice = frontendPreview ? '演示数据已更新，仅保存在本页内存，刷新后重置。' : '操作已完成。';

/**
 * Control-plane domains with a real backend implementation (DelegationAdminController,
 * ptmcp-parent/ptmcp-admin). Each flag opens its domain in normal mode as the backing API slice
 * lands; domains without a flag stay preview-only. See tasks/work-items/T17-api-*.md.
 */
export const delegationApiReady = true;
/** GET /api/v1/admin/acl/effective（AclGrantAdminController#effective）。 */
export const effectiveApiReady = true;
/** /api/v1/admin/platforms 平台目录与 CRUD（PlatformAdminController）。客户端仍在后端切片中。 */
export const platformApiReady = true;
/** GET/PUT /api/v1/admin/platforms/{id}/scopes（PlatformAdminController#*Scopes）。 */
export const scopeApiReady = true;
