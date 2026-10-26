/** This is a build mode, never a URL/localStorage flag or a production permission. */
export const frontendPreview = import.meta.env?.DEV === true && import.meta.env.MODE === 'frontend-preview';
export const controlPlaneAvailable = frontendPreview;
export const mutationNotice = frontendPreview ? '演示数据已更新，仅保存在本页内存，刷新后重置。' : '操作已完成。';
