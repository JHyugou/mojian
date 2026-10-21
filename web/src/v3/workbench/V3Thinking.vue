<script setup>
import { computed, ref } from 'vue'

/** v3 思考过程块：默认折叠成一行，点开看全文；pending 时显示"正在…"状态 */
const props = defineProps({
  text: { type: String, default: '' },
  mode: { type: String, default: 'collapsed' },
  pending: { type: Boolean, default: false }
})
const opened = ref(false)
const visible = computed(() => Boolean(props.text && props.text.trim()))
const expanded = computed(() => props.mode === 'full' || opened.value)
const summary = computed(() => {
  const chars = (props.text || '').trim().length
  if (chars < 40) return '想了想'
  return `思考了约 ${Math.max(3, Math.round(chars / 10))} 秒`
})
</script>

<template>
  <div v-if="visible && mode !== 'hidden'" class="think" :class="{ expanded, pending }">
    <div v-if="pending" class="think-trigger is-pending">
      <span class="dots"><i></i><i></i><i></i></span>
      <span>{{ text }}</span>
    </div>
    <template v-else>
      <button v-if="mode === 'collapsed'" class="think-trigger" @click="opened = !opened">
        <span class="dots"><i></i><i></i><i></i></span>
        <span>{{ summary }}</span>
        <span class="caret">▾</span>
      </button>
      <div v-if="expanded" class="think-body">
        <p v-if="mode === 'full'" class="think-meta">{{ summary }}</p>
        <div class="think-text">{{ text }}</div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.think { margin-bottom: 10px; }
.think-trigger {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 6px 13px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-pill);
  background: var(--v3-surface-1);
  color: var(--v3-text-faint);
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
  transition: color var(--v3-dur-2), border-color var(--v3-dur-2), background var(--v3-dur-2);
}
.think-trigger:hover { color: var(--v3-text-soft); border-color: var(--v3-hairline-gold); background: var(--v3-surface-2); }
.think-trigger.is-pending {
  cursor: default;
  border-color: var(--v3-hairline-gold);
  background: var(--v3-surface-2);
  color: var(--v3-text-muted);
}
.think-trigger.is-pending:hover { transform: none; }
.dots { display: inline-flex; gap: 3px; }
.dots i { width: 4px; height: 4px; border-radius: 50%; background: var(--v3-gold); opacity: 0.7; }
.is-pending .dots i { animation: think-bounce 1.15s infinite ease-in-out; }
.is-pending .dots i:nth-child(2) { animation-delay: 0.14s; }
.is-pending .dots i:nth-child(3) { animation-delay: 0.28s; }
@keyframes think-bounce {
  0%, 70%, 100% { opacity: 0.3; transform: translateY(0); }
  35% { opacity: 1; transform: translateY(-2px); }
}
@media (prefers-reduced-motion: reduce) {
  .is-pending .dots i { animation: none; opacity: 0.7; }
}
.expanded .dots i { opacity: 0.45; }
.caret { font-size: 9px; transition: transform var(--v3-dur-2); }
.expanded .caret { transform: rotate(180deg); }
.think-body {
  margin-top: 8px;
  padding: 12px 16px;
  border-left: 1px solid var(--v3-hairline-gold);
  background: linear-gradient(90deg, var(--v3-surface-1), transparent 80%);
  border-radius: 0 var(--v3-r-xs) var(--v3-r-xs) 0;
}
.think-meta { margin: 0 0 6px; font-size: 10px; letter-spacing: 0.16em; color: var(--v3-text-faint); }
.think-text {
  max-height: 380px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.95;
  color: var(--v3-text-muted);
}
</style>
