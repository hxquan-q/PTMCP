import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { MotionPlugin } from '@vueuse/motion';
import 'animate.css';
import App from './App.vue';
import router from './router';
import './styles/index.css';
createApp(App).use(createPinia()).use(router).use(MotionPlugin).mount('#app');
