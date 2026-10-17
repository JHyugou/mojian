import { defineStore } from 'pinia'

/**
 * 资料变更信号。
 *
 * <p>问题：工作台右栏的人物/设定/蓝图/章节统计只在"进页面"和"换项目"时刷新，
 * 而数据会在你停在页面上时变化 —— 最典型的就是 Agent 起草的内容被你确认采纳，
 * 数字却还停在改动前。
 *
 * <p>做法：谁改了资料就喊一声 {@link touch}，关心的人监听 version 去重取。
 * 为什么用一个计数器而不是逐个页面传事件：
 * <b>改数据的地方和展示数据的地方离得很远</b>（策划面板改、右栏显示），
 * 一层层往上 emit 再往下传 props 会把中间的组件都污染一遍 ——
 * 它们根本不关心这件事，只是恰好站在中间。
 */
export const useLibraryStore = defineStore('library', {
  state: () => ({
    version: 0
  }),
  actions: {
    /** 资料发生了变化（人物/设定/蓝图/章节增删改） */
    touch() {
      this.version += 1
    }
  }
})
