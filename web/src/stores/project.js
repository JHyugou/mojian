import { defineStore } from 'pinia'

const STORAGE_KEY = 'mojian.currentProject'

function readStored() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null')
  } catch {
    return null
  }
}

/**
 * 当前选中的创作项目，持久化到 localStorage。
 */
export const useProjectStore = defineStore('project', {
  state: () => ({
    current: readStored()
  }),
  getters: {
    currentId: (state) => state.current?.id ?? null
  },
  actions: {
    select(project) {
      this.current = project
      localStorage.setItem(STORAGE_KEY, JSON.stringify(project))
    },
    clear() {
      this.current = null
      localStorage.removeItem(STORAGE_KEY)
    }
  }
})
