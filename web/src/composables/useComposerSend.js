import { useSettingsStore } from '../stores/settings'

/**
 * 输入框发送键的统一处理。
 *
 * <p>为什么抽出来：策划面板与写作面板之前是两套实现 —— 策划读了设置但判定漏了
 * "按的是不是 Enter"（按一下 Shift 就发送），写作干脆硬编码 Enter、不读设置、
 * 也不判断中文输入法组词。同一个交互在两处走样，用户看到的就是"设置没生效"。
 *
 * <p>规则（三处缺一不可）：
 * <ol>
 *   <li>只有 <b>Enter</b> 是发送候选；单独按修饰键绝不能发送</li>
 *   <li>中文输入法组词期间（isComposing / keyCode 229）不抢 Enter</li>
 *   <li>按用户设置决定 Enter 还是 Shift+Enter 发送；另一个键留给换行</li>
 * </ol>
 */
export function useComposerSend(onSend) {
  const settings = useSettingsStore()

  function onKeydown(event) {
    if (!settings.shouldSendOn(event)) return
    event.preventDefault()
    onSend()
  }

  /** 输入框 placeholder 里的提示语跟着设置走，别写死 */
  function sendHint() {
    return settings.sendKey === 'shift-enter' ? 'Shift+Enter 发送，Enter 换行' : 'Enter 发送，Shift+Enter 换行'
  }

  return { onKeydown, sendHint }
}
