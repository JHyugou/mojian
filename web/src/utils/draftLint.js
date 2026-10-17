/*
 * 草稿内容体检。
 *
 * 模型会产生一类"低级错误"：中文句子里混进英文词。
 * 真实案例（已采纳进正式设定的一条）：
 *
 *   · 有利爪，能撕开质量一般的甲胄——皮甲、锁子、state军制式甲不在话下。
 *                                                ↑ 本该是"正规"
 *
 * 这类错误的特点是：**写的人看不见，读的人一眼就撞上**。
 * 而作者是最后一道关，不该让他靠肉眼逐字检查几千字的草稿 ——
 * 机器该先替他扫一遍，把可疑的地方指出来。
 *
 * 注意这是"提示"不是"拦截"：判错的代价是作者多看一眼，
 * 而漏判的代价是一个错字进了他的设定库。
 */

/** 中文里正常出现的英文，不该当成混杂。小说设定里出现的基本就这些 */
const ALLOWED = new Set([
  'AI', 'ID', 'URL', 'API', 'OK', 'JSON', 'HTML', 'CSS', 'HTTP', 'HTTPS',
  'MB', 'KB', 'GB', 'CPU', 'GPU', 'UI', 'UX', 'IP', 'SEO', 'PDF', 'PNG'
])

/** 字段名翻成人话。体检结果是要给人看的，显示 roleIdentity 等于让他先翻译一遍 */
const FIELD_LABELS = {
  name: '姓名',
  title: '名称',
  roleIdentity: '身份',
  description: '设定全文',
  content: '内容',
  tags: '标签',
  triggerKeywords: '触发词',
  summary: '概要',
  detail: '详细走向',
  nodeType: '节点类型'
}

/** 中文字符 */
const HAN = '[\\u4e00-\\u9fa5]'

/**
 * 找出"紧挨着中文的英文词"。
 *
 * 为什么要"紧挨着"这个条件：中文里正常引用英文时通常有空格或标点隔开
 * （"用 URL 打开"），而模型出错时是直接粘在一起（"state军制式甲"）。
 * 这个条件能滤掉绝大部分误报。
 */
function findMixedWords(text) {
  const found = []

  // 英文词后面紧跟中文：state军
  for (const match of text.matchAll(new RegExp(`([A-Za-z]{2,})${HAN}`, 'g'))) {
    if (!ALLOWED.has(match[1].toUpperCase())) {
      found.push({ word: match[1], index: match.index })
    }
  }
  // 中文后面紧跟英文词：锁子state（上面那种已经覆盖，这里补反向的漏网）
  for (const match of text.matchAll(new RegExp(`${HAN}([A-Za-z]{2,})`, 'g'))) {
    const word = match[1]
    if (!ALLOWED.has(word.toUpperCase())) {
      const index = match.index + 1
      if (!found.some((item) => index >= item.index && index < item.index + item.word.length + 1)) {
        found.push({ word, index })
      }
    }
  }
  return found
}

/**
 * 给一份草稿做体检。
 *
 * @param payload 草稿内容（字段名 → 文本）
 * @returns [{ level, label, detail }]，空数组表示没发现问题
 */
export function lintDraft(payload) {
  const issues = []

  for (const [key, raw] of Object.entries(payload || {})) {
    const text = String(raw || '')
    if (!text) continue
    const where = FIELD_LABELS[key] || key

    const mixed = findMixedWords(text)
    if (mixed.length) {
      const samples = mixed.slice(0, 3).map((item) => {
        // 截取上下文，让作者一眼看到问题出在哪句话里
        const from = Math.max(0, item.index - 12)
        const to = Math.min(text.length, item.index + item.word.length + 12)
        return `「${text.slice(from, to).replace(/\s+/g, ' ')}」`
      })
      issues.push({
        level: 'warn',
        label: '中英混杂',
        detail: `「${where}」里有 ${mixed.length} 处：${samples.join('、')}`
      })
    }

    // 重复句子：模型偶尔会把同一句写两遍，读起来很刺眼。
    // 阈值放到 6 个字 —— 更长的句子重复几乎不可能是巧合，
    // 而 6 字以内的短语（"他说。"）重复是正常的修辞
    const sentences = text
      .split(/[。！？\n]+/)
      .map((s) => s.trim())
      .filter((s) => s.length >= 6)
    const seen = new Set()
    const duplicated = new Set()
    for (const sentence of sentences) {
      if (seen.has(sentence)) {
        duplicated.add(sentence)
      }
      seen.add(sentence)
    }
    if (duplicated.size) {
      issues.push({
        level: 'warn',
        label: '重复句子',
        detail: `「${where}」里有 ${duplicated.size} 句出现了两次以上，例如「${[...duplicated][0]}」`
      })
    }

    // 占位符：模型偶尔会留下没写完的痕迹
    if (/TODO|XXX|待补充|\[待定\]|\{\{/.test(text)) {
      issues.push({ level: 'warn', label: '疑似占位符', detail: `「${where}」里有没写完的痕迹` })
    }
  }

  return issues
}

/**
 * 把体检发现整理成一句可以直接发给 Agent 的话。
 *
 * 比让作者自己组织语言快得多 —— 他发现问题的当下就该能顺手让它改。
 */
export function buildFixRequest(title, issues) {
  const lines = issues.map((issue) => `- ${issue.label}：${issue.detail}`)
  return `草稿「${title}」的体检发现这些问题：\n\n${lines.join('\n')}\n\n请只修这些地方，其余内容一个字都不要动。`
}
