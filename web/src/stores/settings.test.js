import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSettingsStore } from './settings'

/**
 * 发送键判定的回归测试。
 *
 * <p>背景：用户反馈"切到 Shift+Enter 发送后，点一下 Shift 就发出去了"。
 * 原因是判定只看修饰键、不看这次按的是不是 Enter —— 输入框把每个 keydown
 * 都交给它，于是 Shift 键本身就满足了条件。这类 bug 构建不会报、冒烟看不着，
 * 只能靠这种"把按键序列喂进去"的单元测试守住。
 */
describe('发送键判定（shouldSendOn）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  const key = (overrides = {}) => ({ key: 'Enter', shiftKey: false, ctrlKey: false, altKey: false, metaKey: false, isComposing: false, ...overrides })

  it('默认 Enter 模式：Enter 发送，Shift+Enter 换行', () => {
    const s = useSettingsStore()
    expect(s.shouldSendOn(key())).toBe(true)
    expect(s.shouldSendOn(key({ shiftKey: true }))).toBe(false)
  })

  it('Shift+Enter 模式：Shift+Enter 发送，Enter 换行', () => {
    const s = useSettingsStore()
    s.setSendKey('shift-enter')
    expect(s.shouldSendOn(key({ shiftKey: true }))).toBe(true)
    expect(s.shouldSendOn(key())).toBe(false)
  })

  it('单独按 Shift 绝不能触发发送（用户报的 bug）', () => {
    const s = useSettingsStore()
    s.setSendKey('shift-enter')
    expect(s.shouldSendOn(key({ key: 'Shift', shiftKey: true }))).toBe(false)
    s.setSendKey('enter')
    expect(s.shouldSendOn(key({ key: 'Shift', shiftKey: true }))).toBe(false)
  })

  it('普通字符按键不能触发发送', () => {
    const s = useSettingsStore()
    for (const k of ['a', '1', 'Backspace', 'ArrowUp', ' ']) {
      expect(s.shouldSendOn(key({ key: k })), k).toBe(false)
    }
  })

  it('中文输入法组词期间不发送', () => {
    const s = useSettingsStore()
    expect(s.shouldSendOn(key({ isComposing: true }))).toBe(false)
    expect(s.shouldSendOn(key({ keyCode: 229 }))).toBe(false)
  })

  it('带 Ctrl/Alt/Meta 的 Enter 不发送（留给快捷键）', () => {
    const s = useSettingsStore()
    expect(s.shouldSendOn(key({ ctrlKey: true }))).toBe(false)
    expect(s.shouldSendOn(key({ altKey: true }))).toBe(false)
    expect(s.shouldSendOn(key({ metaKey: true }))).toBe(false)
  })
})
