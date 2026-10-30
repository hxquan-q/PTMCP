import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
// Backend ports follow the dev script's environment when present; keep the high-port defaults in sync with scripts/Dev-Linux.sh.
const adminPort = process.env.PTMCP_ADMIN_PORT ?? '28080';
const authzPort = process.env.PTMCP_AUTHZ_PORT ?? '28081';
const gatewayPort = process.env.PTMCP_GATEWAY_PORT ?? '28082';
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '127.0.0.1',
    port: 25175,
    strictPort: true,
    proxy: {
      '/backend/admin': { target: `http://127.0.0.1:${adminPort}`, rewrite: (path) => path.replace(/^\/backend\/admin/, '') },
      '/backend/authz': { target: `http://127.0.0.1:${authzPort}`, rewrite: (path) => path.replace(/^\/backend\/authz/, '') },
      '/backend/gateway': { target: `http://127.0.0.1:${gatewayPort}`, rewrite: (path) => path.replace(/^\/backend\/gateway/, '') }
    }
  }
});
