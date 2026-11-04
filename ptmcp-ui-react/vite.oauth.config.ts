import { fileURLToPath } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

/**
 * OAuth 登录/授权页单独构建：产物由 ptmcp-authz 从 PTMCP_AUTHZ_OAUTH_PAGES_DIR 读取，
 * 页面与授权服务同源。base 必须与 OAuthPageAssetsConfiguration 的 /oauth/assets/ 一致。
 */
export default defineConfig({
  base: '/oauth/',
  plugins: [react(), tailwindcss()],
  build: {
    outDir: 'dist-oauth',
    emptyOutDir: true,
    // 页面 CSP 只允许 'self' 脚本；modulepreload polyfill 是内联脚本，必须关闭。
    modulePreload: { polyfill: false },
    rollupOptions: {
      input: {
        login: fileURLToPath(new URL('./oauth/login.html', import.meta.url)),
        consent: fileURLToPath(new URL('./oauth/consent.html', import.meta.url)),
      },
    },
  },
});
