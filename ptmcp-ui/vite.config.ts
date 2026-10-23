import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import tailwindcss from '@tailwindcss/vite';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import Icons from 'unplugin-icons/vite';
import IconsResolver from 'unplugin-icons/resolver';
export default defineConfig({
  plugins: [
    vue(),
    tailwindcss(),
    Components({
      dts: 'src/components.d.ts',
      resolvers: [ElementPlusResolver({ importStyle: false }), IconsResolver({ prefix: 'Icon', enabledCollections: ['ep'] })]
    }),
    Icons({ compiler: 'vue3', autoInstall: false })
  ],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/backend/admin': { target: 'http://127.0.0.1:8080', rewrite: (path) => path.replace(/^\/backend\/admin/, '') },
      '/backend/authz': { target: 'http://127.0.0.1:8081', rewrite: (path) => path.replace(/^\/backend\/authz/, '') },
      '/backend/gateway': { target: 'http://127.0.0.1:8082', rewrite: (path) => path.replace(/^\/backend\/gateway/, '') }
    }
  }
});
