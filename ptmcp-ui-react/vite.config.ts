import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '127.0.0.1',
    port: 5175,
    strictPort: true,
    proxy: {
      '/backend/admin': { target: 'http://127.0.0.1:8080', rewrite: (path) => path.replace(/^\/backend\/admin/, '') },
      '/backend/authz': { target: 'http://127.0.0.1:8081', rewrite: (path) => path.replace(/^\/backend\/authz/, '') },
      '/backend/gateway': { target: 'http://127.0.0.1:8082', rewrite: (path) => path.replace(/^\/backend\/gateway/, '') }
    }
  }
});
