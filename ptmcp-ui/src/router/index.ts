import { createRouter, createWebHistory } from 'vue-router';
export default createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [{ path: '/', name: 'framework-home', component: () => import('../views/FrameworkHome.vue') }]
});
