<script setup>
import { computed, ref } from 'vue'

/**
 * 思考过程块。
 *
 * 三档显示由设置里选：完全展开 / 部分折叠 / 完全隐藏。
 * 做成一个组件而不是在两个面板里各写一遍 —— 这东西的交互（折叠状态、展开动画）
 * 本身就有细节，复制两份必然走样。
 */
const props = defineProps({
  text: { type: String, default: '' },
  /** full | collapsed | hidden，来自设置 */
  mode: { type: String, default: 'collapsed' },
  /** 思考时的耗时，暂无精确值就不传 */
  seconds: { type: Number, default: 0 }
})

/** 折叠态下手动展开 */
const opened = ref(false)

const visible = computed(() => Boolean(props.text && props.text.trim()))

/** 折叠态是否展开：用户点开过就展开，否则跟着设置走 */
const expanded = computed(() => props.mode === 'full' || opened.value)

const summary = computed(() => {
  if (props.seconds > 0) return `已思考 ${props.seconds} 秒`
  // 没有耗时就用字数估个大概：中文大约每秒 8~12 字
  const chars = props.text.trim().length
  if (chars < 40) return '想了想'
  return `已思考约 ${Math.max(3, Math.round(chars / 10))} 秒`
})
</script>

<template>
  <div v-if="visible && mode !== 'hidden'" class="tb" :class="{ expanded }">
    <!-- 折叠态：一行药丸，点了才展开 -->
    <button
      v-if="mode === 'collapsed'"
      class="tb-trigger"
      :class="{ open: opened }"
      @click="opened = !opened"
    >
      <span class="tb-dots"><i></i><i></i><i></i></span>
      <span class="tb-summary">{{ summary }}</span>
      <span class="tb-caret">▾</span>
    </button>

    <!-- 展开态：左侧一条棕线，字比正文淡一档 —— 它是过程，不是结果 -->
    <div v-if="expanded" class="tb-body" :class="{ 'mj-fade': mode === 'collapsed' }">
      <p v-if="mode === 'full'" class="tb-summary-full">{{ summary }}</p>
      <div class="tb-text">{{ text }}</div>
    </div>
  </div>
</template>

<style scoped>
.tb { margin-bottom: 10px; }

/* ---------- 折叠触发 ---------- */

.tb-trigger {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 7px 15px;
  border: 0;
  border-radius: var(--radius-pill);
  background: var(--color-paper-warm);
  cursor: pointer;
  font-family: var(--font-sans);
  font-size: 12px;
  color: var(--color-muted);
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.tb-trigger:hover { background: var(--tint-strong); color: var(--color-brown-deep); }

.tb-dots { display: inline-flex; gap: 4px; }
.tb-dots i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--color-brown);
  opacity: 0.75;
}

/* 展开后三点不用再动：那时内容已经出来了 */
.tb-trigger.open .tb-dots i { animation: none; opacity: 0.5; }

.tb-summary { letter-spacing: 0.02em; }

.tb-caret {
  font-size: 9px;
  transition: transform var(--dur) var(--ease-out);
}
.tb-trigger.open .tb-caret { transform: rotate(180deg); }

/* ---------- 展开内容 ---------- */

.tb-body {
  margin-top: 9px;
  padding: 12px 16px;
  border-left: 2px solid var(--color-brown-soft);
  border-radius: 0 var(--radius-s) var(--radius-s) 0;
  background: var(--color-paper-warm);
  max-height: 420px;
  overflow-y: auto;
}

.tb-summary-full {
  margin-bottom: 8px;
  font-size: 11px;
  letter-spacing: 0.08em;
  color: var(--color-muted);
}

/*
 * 思考过程用比正文更淡的颜色 + 更小的字号。
 * 它是"为什么这么答"，不是"答案"—— 视觉上必须让位给下面的气泡。
 */
.tb-text {
  font-size: 12.5px;
  line-height: 1.95;
  color: var(--color-muted);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
