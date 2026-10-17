import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './assets/theme.css'
// 创作工作台的设计系统（mj- 命名空间）。旧页面继续用 theme.css，两套互不干扰，
// 这样"新工作台先上、旧页面后迁"就不会出现半新半旧的视觉混乱。
import './assets/workbench.css'

/*
 * 把未捕获的错误回传到后端。
 *
 * 前端出错时，用户能告诉我们的只有"页面白了" —— 而真正的错误在浏览器的控制台里，
 * 我们看不到。让页面自己把错误报回来，排查就从"猜"变成"看"。
 *
 * 用 sendBeacon 而不是 fetch：页面可能正在崩溃，beacon 不依赖页面还活着。
 * 自己出错也不该再抛 —— 整个函数体包在 try 里。
 */
function reportClientError(message, where) {
  try {
    const payload = JSON.stringify({
      message: String(message).slice(0, 500),
      where: String(where || '').slice(0, 300)
    })
    if (navigator.sendBeacon) {
      navigator.sendBeacon('/api/agent/client-error', new Blob([payload], { type: 'application/json' }))
    } else {
      fetch('/api/agent/client-error', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: payload,
        keepalive: true
      }).catch(() => {})
    }
  } catch (e) {
    // 连报错都失败就算了，绝不能再抛
  }
}

window.addEventListener('error', (event) => {
  reportClientError(event.message, `${event.filename}:${event.lineno}:${event.colno}`)
})
window.addEventListener('unhandledrejection', (event) => {
  const reason = event.reason
  reportClientError(reason?.message || reason, reason?.stack || '未处理的 Promise 拒绝')
})

createApp(App)
  .use(createPinia())
  .use(router)
  .mount('#app')
