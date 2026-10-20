import { onBeforeUnmount, watch } from 'vue'

/**
 * 输入框草稿持久化：页面意外重载、Vite HMR、切到别的页面再回来时，
 * 没发出去的正文不应该丢。按“项目 + 会话/章节”分开存 localStorage，
 * 切会话/章节时自动保存旧草稿、恢复新草稿。
 */
export function useComposerDraft(draft, keyGetter) {
  const PREFIX = 'mojian.composer.'
  let saveTimer = null
  let currentKey = null

  const storageKey = (key) => `${PREFIX}${key}`

  function save(key = currentKey) {
    if (!key) return
    try {
      const value = String(draft.value ?? '')
      if (value.trim()) localStorage.setItem(storageKey(key), value)
      else localStorage.removeItem(storageKey(key))
    } catch { /* 隐私模式等忽略 */ }
  }

  function syncKey() {
    const nextKey = keyGetter()
    if (nextKey === currentKey) return
    // 先把旧 key 的内容落盘，再加载新 key
    save(currentKey)
    currentKey = nextKey
    try {
      draft.value = localStorage.getItem(storageKey(currentKey)) || ''
    } catch {
      draft.value = ''
    }
  }

  watch(draft, () => {
    if (saveTimer) clearTimeout(saveTimer)
    // 清空要立即落盘，否则刷新后会短暂把旧草稿恢复回来
    if (!String(draft.value ?? '').trim()) {
      save()
      return
    }
    saveTimer = setTimeout(() => save(), 250)
  })

  watch(keyGetter, syncKey, { immediate: true, flush: 'post' })

  function flush() {
    if (saveTimer) clearTimeout(saveTimer)
    save()
  }

  // 页面刷新/关标签页时不保证 Vue 生命周期，靠浏览器事件补一次
  window.addEventListener('pagehide', flush)
  window.addEventListener('beforeunload', flush)

  onBeforeUnmount(() => {
    flush()
    window.removeEventListener('pagehide', flush)
    window.removeEventListener('beforeunload', flush)
  })

  return {
    sync: syncKey,
    clear() {
      if (saveTimer) clearTimeout(saveTimer)
      try {
        localStorage.removeItem(storageKey(currentKey))
      } catch { /* ignore */ }
      draft.value = ''
    }
  }
}
