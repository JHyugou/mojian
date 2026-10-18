import { ref } from 'vue'

/**
 * v3 轻量通知队列。
 * 独立于旧的 message store：v3 页面（首页/新工作台）不依赖旧外壳也能弹提示。
 */
const items = ref([])
let seed = 0

function push(text, type = 'info', duration = 3200) {
  const id = ++seed
  items.value = [...items.value, { id, text, type }]
  if (duration > 0) {
    setTimeout(() => dismiss(id), duration)
  }
  return id
}

function dismiss(id) {
  items.value = items.value.filter((item) => item.id !== id)
}

export function useV3Notify() {
  return {
    items,
    info: (text) => push(text, 'info'),
    success: (text) => push(text, 'success'),
    error: (text) => push(text, 'error', 5200),
    dismiss
  }
}
