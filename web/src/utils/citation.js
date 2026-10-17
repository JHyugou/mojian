/*
 * 引用消息的组装。
 *
 * 策划面板与写作面板共用 —— 引用的格式必须一致，
 * 因为模型是靠这段固定格式认出「哪部分是被引的原文」的。
 */

/**
 * 把引用与用户输入拼成一条真正发出去的消息。
 *
 * 引用放在最前面并标明出处，用 `>` 逐行引用：
 * 模型由此能分辨「被引的原文」与「用户的要求」——
 * 这两者混在一起最容易改错地方。
 *
 * 出处必须带**可定位的标识**，不能只有名字：
 *   - 人物/设定/蓝图 → 记录 ID（名称会重，ID 不会）
 *   - 章节正文       → 章节序号 + 段号（标题会重，而且作者常改标题不改序号）
 * 没有定位标识，模型只能靠"再查一遍"或"猜"，前者多花一步，后者会改错地方。
 *
 * @param citations [{ kind, targetName, text, locator }]
 * @param text      用户的输入
 */
export function buildCitedMessage(citations, text) {
  const body = (text || '').trim()
  if (!citations || !citations.length) {
    return body
  }

  const quoted = citations
    .map((c) => {
      const lines = String(c.text)
        .split('\n')
        .map((line) => '> ' + line)
        .join('\n')
      const locator = c.locator ? `${c.locator}` : ''
      return `【引用 · ${c.kind || '资料'}「${c.targetName}」${locator}】\n${lines}`
    })
    .join('\n\n')

  return body
    ? `${quoted}\n\n${body}`
    : `${quoted}\n\n请针对上面引用的内容处理。`
}

/** 引用条上显示的截断预览 */
export function citationPreview(text, max = 40) {
  const flat = String(text || '').replace(/\s+/g, ' ').trim()
  return flat.length > max ? flat.slice(0, max) + '…' : flat
}

/**
 * 算出选中位置在正文里是第几段（段以空行为界）。
 *
 * 为什么值得算：作者框选一句"把这里改紧凑些"，模型需要知道"这里"是哪里。
 * 只给选中的文字不够 —— 同一句话可能在整章里出现好几次。
 *
 * @param container 正文容器元素
 * @param selection 当前的选区
 * @returns 段号（从 1 开始）；算不出来时返回空
 */
export function segmentIndexOf(container, selection) {
  if (!container || !selection || !selection.rangeCount) return null
  try {
    const range = selection.getRangeAt(0)
    // 把光标起点之前的文字量出来，就是在整段文本里的偏移
    const pre = range.cloneRange()
    pre.selectNodeContents(container)
    pre.setEnd(range.startContainer, range.startOffset)
    const offset = pre.toString().length
    const before = container.textContent.slice(0, offset)
    // 连续空行（含只有空白字符的行）算一段分隔
    return before.split(/\n\s*\n/).length
  } catch (e) {
    // 选区跨了元素边界等情况：算不出来就不标，不要因为标错而误导模型
    return null
  }
}
