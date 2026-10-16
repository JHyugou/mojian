import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './assets/theme.css'
// 创作工作台的设计系统（mj- 命名空间）。旧页面继续用 theme.css，两套互不干扰，
// 这样"新工作台先上、旧页面后迁"就不会出现半新半旧的视觉混乱。
import './assets/workbench.css'

createApp(App)
  .use(createPinia())
  .use(router)
  .mount('#app')
