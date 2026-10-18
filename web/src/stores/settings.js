import { defineStore } from 'pinia'

/**
 * 界面偏好。
 *
 * <p>为什么单独一个 store，而不是散在各个组件里读 localStorage：
 * 这三项（主题、思维链、发送键）都是「一处设置、全局生效」，
 * 而且会同时被多个组件读到（输入框、消息气泡、整个页面）。
 * 散着放的话，改一个地方只在下次刷新才生效，用户会以为"没保存"。
 */

/** 思维链显示方式。照 DSH 的做法分三档 */
export const THINKING_MODES = [
  { key: 'full', label: '完全展开', hint: '思考过程全文显示，随时能看见它在想什么' },
  { key: 'collapsed', label: '部分折叠', hint: '平时收起成一行，想看时点开' },
  { key: 'hidden', label: '完全隐藏', hint: '只显示结果，不显示思考过程' }
]

/** 发送键 */
export const SEND_KEYS = [
  { key: 'enter', label: 'Enter 发送', hint: 'Shift+Enter 换行' },
  { key: 'shift-enter', label: 'Shift+Enter 发送', hint: 'Enter 换行' }
]

const KEY_THEME = 'mojian.theme'
const KEY_THINKING = 'mojian.thinkingMode'
const KEY_SEND = 'mojian.sendKey'
const KEY_AUTO_APPROVE = 'mojian.autoApprove'
const KEY_ROLLBACK_DB = 'mojian.rollbackDatabase'
const KEY_AUTO_APPROVE_DELETE = 'mojian.autoApproveDelete'

export const useSettingsStore = defineStore('settings', {
  state: () => ({
    /** 主题 id，见 themes.css */
    theme: localStorage.getItem(KEY_THEME) || 'sujuan',
    /** full | collapsed | hidden */
    thinkingMode: localStorage.getItem(KEY_THINKING) || 'collapsed',
    /** enter | shift-enter */
    sendKey: localStorage.getItem(KEY_SEND) || 'enter',
    /** 采纳模式：是否自动采纳 Agent 起草的内容 */
    autoApprove: localStorage.getItem(KEY_AUTO_APPROVE) === '1',
    /** 回滚对话时是否连带撤销数据库改动；默认开 */
    rollbackDatabase: localStorage.getItem(KEY_ROLLBACK_DB) !== '0',
    /**
     * 是否自动批准写作 Agent 的删除章节申请；默认关。
     * 这是不可逆的高危动作：开着时写作 Agent 可以在你不在场时直接删章。
     */
    autoApproveDelete: localStorage.getItem(KEY_AUTO_APPROVE_DELETE) === '1'
  }),

  actions: {
    setTheme(value) {
      this.theme = value
      localStorage.setItem(KEY_THEME, value)
      this.applyTheme()
    },

    setThinkingMode(value) {
      this.thinkingMode = value
      localStorage.setItem(KEY_THINKING, value)
    },

    setSendKey(value) {
      this.sendKey = value
      localStorage.setItem(KEY_SEND, value)
    },

    setAutoApprove(value) {
      this.autoApprove = value
      localStorage.setItem(KEY_AUTO_APPROVE, value ? '1' : '0')
    },

    setRollbackDatabase(value) {
      this.rollbackDatabase = value
      localStorage.setItem(KEY_ROLLBACK_DB, value ? '1' : '0')
    },

    setAutoApproveDelete(value) {
      this.autoApproveDelete = value
      localStorage.setItem(KEY_AUTO_APPROVE_DELETE, value ? '1' : '0')
    },

    /**
     * 把主题写到 <html data-theme>。
     *
     * 用属性选择器切换而不是换一份 CSS 文件：
     * 只改一个属性，浏览器只需要重算样式，不用重新下载与解析样式表 ——
     * 切换时不会有一下"白屏闪"。
     */
    applyTheme() {
      document.documentElement.dataset.theme = this.theme
    },

    /** 启动时调用一次 */
    init() {
      this.applyTheme()
    },

    /**
     * 当前发送键对应的「发送」判定：返回 true 表示这次按键应该发送。
     *
     * <p><b>必须先确认这次按的是 Enter</b>。之前只看修饰键，
     * 于是在"Shift+Enter 发送"模式下按一下 Shift 键本身就被判成发送——
     * 用户看到的正是"点一下 shift 就发出去了"。输入框把每个 keydown 都交给这里，
     * 所以第一行必须是 key 判定，而不是修饰键推断。
     */
    shouldSendOn(event) {
      if (!event || event.key !== 'Enter') return false
      if (event.isComposing || event.keyCode === 229) return false
      if (event.ctrlKey || event.altKey || event.metaKey) return false
      if (this.sendKey === 'shift-enter') {
        return event.shiftKey
      }
      // 默认 Enter 发送：带任何修饰键都不算发送（Shift 是换行）
      return !event.shiftKey
    },
  }
})
