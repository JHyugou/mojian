/*
 * 引用消息的组装。
 *
 * 策划面板与写作面板都用它 —— 引用的格式必须一致，
 * 因为模型是靠这段固定格式认出"哪部分是被引的原文"的。
 */

/**
 * 把引用与用户输入拼成一条真正发出去的消息。
 *
 * 引用放在最前面并标明出处，用 `>` 逐行引用：
 * 模型由此能分辨「被引的原文」与「用户的要求」——
 * 这两者混在一起最容易改错地方。
 *
 * @param citations [{ kind, targetName, text }]
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
      return `【引用 · ${c.kind || '资料'}「${c.targetName}」】\n${lines}`
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
