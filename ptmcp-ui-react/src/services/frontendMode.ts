/**
 * 合成前端预览模式（Vite dev + MODE=frontend-preview）判定。
 * 只有该模式允许展示样例数据或走合成后端；正常模式必须依赖真实服务端契约。
 */
export const frontendPreview = Boolean(import.meta.env?.DEV && import.meta.env.MODE === 'frontend-preview');
