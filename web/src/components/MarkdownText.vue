<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * Markdown 渲染。
 *
 * <p>模型的回答本来是 Markdown 源码（`**加粗**`、`- 列表`），直接当纯文本显示
 * 就会看到一堆星号和短横线 —— 既难读，也浪费了它本来组织得很清楚的结构。
 *
 * <p><b>必须过滤</b>：渲染出来的是 HTML，而内容来自模型（间接来自用户输入与
 * 项目资料）。不过滤的话，资料里混进一段 `<img onerror=...>` 就是一个注入点。
 * 这里用 DOMPurify 而不是"自己写正则" —— 净化 HTML 是那种看起来简单、
 * 实际有一百种绕过方式的事。
 */
const props = defineProps({
  text: { type: String, default: '' },
  /** 行内模式：不加段落外边距，适合放进对话气泡里 */
  inline: { type: Boolean, default: false }
})

marked.setOptions({ breaks: true, gfm: true })

const html = computed(() => {
  if (!props.text) return ''
  try {
    return DOMPurify.sanitize(marked.parse(props.text))
  } catch (e) {
    // 渲染失败就退回纯文本：显示得朴素一点，总比整块空白好
    console.warn('Markdown 渲染失败，退回纯文本', e)
    return DOMPurify.sanitize(props.text.replace(/\n/g, '<br>'))
  }
})
</script>

<template>
  <div class="md" :class="{ 'is-inline': inline }" v-html="html"></div>
</template>

<style scoped>
/* 排版规则集中在这里：会话流、方案说明、工具结果都要用同一套 */
.md {
  font-size: 13.5px;
  line-height: 1.85;
  word-break: break-word;
}

.md :deep(h1),
.md :deep(h2),
.md :deep(h3) {
  font-family: var(--font-serif);
  color: var(--color-brown-deep);
  letter-spacing: 0.04em;
  margin: 14px 0 8px;
  line-height: 1.5;
}
.md :deep(h1) { font-size: 17px; }
.md :deep(h2) { font-size: 15px; }
.md :deep(h3) { font-size: 14px; }

.md :deep(p) { margin: 8px 0; }
.md.is-inline :deep(p) { margin: 0; }

.md :deep(ul),
.md :deep(ol) { margin: 8px 0; padding-left: 20px; }
.md :deep(li) { margin: 4px 0; }
.md :deep(li::marker) { color: var(--color-brown); }

/* 引用块：模型常用来引作者原文，做成左侧细棕线更像"引用" */
.md :deep(blockquote) {
  margin: 10px 0;
  padding: 4px 0 4px 12px;
  border-left: 2px solid var(--color-brown-soft);
  color: var(--color-muted);
}

.md :deep(code) {
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--color-paper-warm);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12.5px;
}

.md :deep(pre) {
  margin: 10px 0;
  padding: 10px 12px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  background: var(--color-paper-warm);
  overflow-x: auto;
}
.md :deep(pre code) { padding: 0; background: none; }

.md :deep(table) {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  font-size: 13px;
}
.md :deep(th),
.md :deep(td) {
  padding: 6px 10px;
  border: 1px solid var(--color-line);
  text-align: left;
}
.md :deep(th) {
  background: var(--color-paper-warm);
  font-family: var(--font-serif);
  color: var(--color-brown-deep);
}

.md :deep(hr) {
  height: 1px;
  border: 0;
  margin: 14px 0;
  background: linear-gradient(90deg,
      transparent, var(--color-brown-soft) 18%, var(--color-brown-soft) 82%, transparent);
}

.md :deep(a) {
  color: var(--color-brown);
  text-decoration: none;
  border-bottom: 1px solid var(--color-brown-soft);
}

.md :deep(strong) { color: var(--color-brown-deep); }

/* 段落之间不要出现"首行粘连"：唯一的空行已经被解析成段落了 */
.md :deep(> *:first-child) { margin-top: 0; }
.md :deep(> *:last-child) { margin-bottom: 0; }
</style>
