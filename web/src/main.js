import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './assets/theme.css'
// 创作工作台的设计系统（mj- 命名空间）。旧页面继续用 theme.css，两套互不干扰，
// 这样"新工作台先上、旧页面后迁"就不会出现半新半旧的视觉混乱。
import './assets/workbench.css'

/*
 * 未捕获错误的回传监听**不在这里**，在 index.html 的内联脚本里。
 *
 * 原因：如果模块图本身加载失败（比如某个 import 指向不存在的导出），
 * 这个文件根本不会执行，放在这里的监听器也就永远不会注册 ——
 * 而那恰恰是最需要线索的情况（页面全白，除了控制台什么都没有）。
 * 2026-10-17 真的踩过一次，所以把它挪到了模块之前。
 */

createApp(App)
  .use(createPinia())
  .use(router)
  .mount('#app')
