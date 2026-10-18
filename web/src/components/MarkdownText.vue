<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * Markdown 渲染（v3）。
 *
 * <p>模型的回答本来是 Markdown 源码，直接当纯文本显示会看到一堆星号。
 * 内容来自模型/资料，间接来自用户输入，所以渲染出的 HTML <b>必须过滤</b>：
 * 用 DOMPurify 而不是自己写正则 —— 净化 HTML 是那种看起来简单、
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
    /*
     * 渲染失败就退回纯文本。
     * 兜底路径不能再依赖 DOMPurify —— 它需要 DOM 环境；
     * 一旦不可用，兜底跟着抛，"有兜底"就变成了"没兜底"。
     */
    console.warn('Markdown 渲染失败，退回纯文本', e)
    return escapeHtml(props.text).replace(/\n/g, '<br>')
  }
})

function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}
</script>

<template>
  <div class="md" :class="{ 'is-inline': inline }" v-html="html"></div>
</template>

<style scoped>
.md {
  font-size: 13.5px;
  line-height: 1.95;
  word-break: break-word;
  color: inherit;
}

.md :deep(h1),
.md :deep(h2),
.md :deep(h3) {
  font-family: var(--v3-font-serif);
  color: var(--v3-text);
  letter-spacing: 0.04em;
  margin: 16px 0 8px;
  line-height: 1.5;
}
.md :deep(h1) { font-size: 17px; }
.md :deep(h2) { font-size: 15.5px; }
.md :deep(h3) { font-size: 14.5px; }

.md :deep(p) { margin: 8px 0; }
.md.is-inline :deep(p) { margin: 0; }

.md :deep(ul),
.md :deep(ol) { margin: 8px 0; padding-left: 20px; }
.md :deep(li) { margin: 4px 0; }
.md :deep(li::marker) { color: var(--v3-gold); }

/* 引用块：模型常用来引作者原文，左侧一条金线更像"引用" */
.md :deep(blockquote) {
  margin: 10px 0;
  padding: 6px 0 6px 14px;
  border-left: 1px solid var(--v3-hairline-gold);
  color: var(--v3-text-muted);
}

.md :deep(code) {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--v3-surface-2);
  font-family: var(--v3-font-mono);
  font-size: 12px;
  color: var(--v3-gold-bright);
}

.md :deep(pre) {
  margin: 10px 0;
  padding: 12px 14px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-2);
  overflow-x: auto;
}
.md :deep(pre code) { padding: 0; background: none; color: var(--v3-text-soft); }

.md :deep(table) {
  width: 100%;
  margin: 12px 0;
  border-collapse: collapse;
  font-size: 12.5px;
}
.md :deep(th),
.md :deep(td) {
  padding: 7px 10px;
  border: 1px solid var(--v3-hairline);
  text-align: left;
}
.md :deep(th) {
  background: var(--v3-surface-2);
  font-family: var(--v3-font-serif);
  color: var(--v3-text);
}

.md :deep(hr) {
  height: 1px;
  border: 0;
  margin: 16px 0;
  background: linear-gradient(90deg, transparent, var(--v3-hairline-strong) 18%, var(--v3-hairline-strong) 82%, transparent);
}

.md :deep(a) {
  color: var(--v3-gold);
  text-decoration: none;
  border-bottom: 1px solid var(--v3-hairline-gold);
}

.md :deep(strong) { color: var(--v3-gold-bright); font-weight: 600; }

.md :deep(> *:first-child) { margin-top: 0; }
.md :deep(> *:last-child) { margin-bottom: 0; }

/* 用户气泡里正文色更深一点，保证对比度 */
</style>
