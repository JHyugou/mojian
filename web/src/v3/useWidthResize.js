import { onBeforeUnmount, ref } from 'vue'

/**
 * 竖向分割线的拖拽调整（v3）。
 *
 * <p>三处用到：工作区树宽度、策划/写作区与右栏宽度、写作页"对话/正文"宽度。
 * 共用一个实现，避免三份各自处理 pointer capture、边界钳制、持久化和卸载清理。
 *
 * @param {object} options
 * @param {number} options.initial 初始宽度（px）
 * @param {number} options.min     最小宽度
 * @param {number} options.max     最大宽度
 * @param {string} options.storageKey localStorage 键名
 * @param {boolean} [options.invert] 右侧面板用：往左拖才是"变宽"
 * @returns {{ width: import('vue').Ref<number>, onPointerDown: Function, reset: Function }}
 */
export function useWidthResize({ initial, min, max, storageKey, invert = false }) {
  const read = () => {
    const saved = Number(localStorage.getItem(storageKey))
    return Number.isFinite(saved) && saved > 0 ? saved : initial
  }
  const clamp = (value) => Math.round(Math.min(max, Math.max(min, value)))
  const width = ref(clamp(read()))

  let cleanup = null

  function onPointerDown(event) {
    if (event.button !== 0) return
    event.preventDefault()
    const startX = event.clientX
    const startWidth = width.value
    document.body.classList.add('v3-resizing')

    const onMove = (moveEvent) => {
      const delta = moveEvent.clientX - startX
      width.value = clamp(startWidth + (invert ? -delta : delta))
    }
    const onUp = () => {
      document.body.classList.remove('v3-resizing')
      localStorage.setItem(storageKey, String(width.value))
      document.removeEventListener('pointermove', onMove)
      document.removeEventListener('pointerup', onUp)
      document.removeEventListener('pointercancel', onUp)
      cleanup = null
    }

    // 拖出元素/窗口外也要收到事件：挂到 document 上，并兼容取消
    document.addEventListener('pointermove', onMove)
    document.addEventListener('pointerup', onUp)
    document.addEventListener('pointercancel', onUp)
    cleanup = () => {
      document.removeEventListener('pointermove', onMove)
      document.removeEventListener('pointerup', onUp)
      document.removeEventListener('pointercancel', onUp)
      document.body.classList.remove('v3-resizing')
    }
  }

  function reset() {
    width.value = clamp(initial)
    localStorage.setItem(storageKey, String(width.value))
  }

  onBeforeUnmount(() => {
    cleanup?.()
  })

  return { width, onPointerDown, reset }
}
