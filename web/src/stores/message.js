import { defineStore } from 'pinia'

let seed = 0

/**
 * 全局轻提示。生成类长任务失败时也统一走这里。
 */
export const useMessageStore = defineStore('message', {
  state: () => ({
    items: []
  }),
  actions: {
    push(text, type = 'info') {
      const id = ++seed
      this.items.push({ id, text, type })
      setTimeout(() => this.dismiss(id), 3200)
    },
    success(text) {
      this.push(text, 'success')
    },
    error(text) {
      this.push(text, 'error')
    },
    dismiss(id) {
      this.items = this.items.filter((i) => i.id !== id)
    }
  }
})
