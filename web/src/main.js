import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
// v3 设计系统（墨海/素笺）：全套页面只依赖这一份令牌与基础样式
import './assets/v3/tokens.css'
import { initV3Theme } from './v3/theme'
import { vReveal } from './v3/motion'

/*
 * 未捕获错误的回传监听**不在这里**，在 index.html 的内联脚本里。
 * 原因：如果模块图本身加载失败，这个文件根本不会执行，
 * 放在这里的监听器也永远不会注册 —— 而那恰恰是最需要线索的情况。
 */

// 主题要在挂载前应用：否则首屏会先用默认色渲染、再"跳"成用户选的那套
initV3Theme()

const app = createApp(App)
const pinia = createPinia()

// v-reveal：进入视口时入场（首页/预览页等通用）
app.directive('reveal', vReveal)

app.use(pinia).use(router).mount('#app')
