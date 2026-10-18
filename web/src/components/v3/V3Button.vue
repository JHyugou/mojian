<script setup>
import { computed } from 'vue'

/**
 * v3 按钮。
 *
 * 变体：primary（哑金）/ glass（玻璃）/ ghost（无底）/ outline（金线）/ danger。
 * 视觉上刻意偏"平"：主按钮是一段没有镜面高光的哑金渐变，靠悬停位移和一道
 * 很淡的光晕给反馈，而不是亮片式的光泽。按压用 scale 0.985，只动 transform。
 */
const props = defineProps({
  variant: { type: String, default: 'primary' },
  size: { type: String, default: 'md' },
  loading: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  block: { type: Boolean, default: false },
  type: { type: String, default: 'button' }
})
defineEmits(['click'])

const cls = computed(() => [
  'v3-btn',
  `is-${props.variant}`,
  `is-${props.size}`,
  { 'is-block': props.block, 'is-loading': props.loading }
])
</script>

<template>
  <button
    :type="type"
    :class="cls"
    :disabled="disabled || loading"
    @click="$emit('click', $event)"
  >
    <span v-if="loading" class="spinner" aria-hidden="true"></span>
    <slot name="icon" />
    <span class="label"><slot /></span>
  </button>
</template>

<style scoped>
.v3-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid transparent;
  border-radius: var(--v3-r-pill);
  font-family: var(--v3-font-sans);
  font-weight: 500;
  letter-spacing: 0.02em;
  cursor: pointer;
  white-space: nowrap;
  transition:
    transform var(--v3-dur-1) var(--v3-ease-spring),
    background var(--v3-dur-2) var(--v3-ease-out),
    border-color var(--v3-dur-2) var(--v3-ease-out),
    color var(--v3-dur-2) var(--v3-ease-out),
    box-shadow var(--v3-dur-2) var(--v3-ease-out);
}
.v3-btn:active:not(:disabled) { transform: scale(0.985); }
.v3-btn:disabled { opacity: 0.45; cursor: not-allowed; }
.v3-btn.is-block { width: 100%; }

.is-sm { height: 32px; padding: 0 14px; font-size: 12.5px; }
.is-md { height: 40px; padding: 0 20px; font-size: 13.5px; }
.is-lg { height: 50px; padding: 0 28px; font-size: 15px; letter-spacing: 0.04em; }

/* primary：哑金，不做镜面高光 */
.is-primary {
  color: #241d12;
  background: linear-gradient(135deg, #d9bd86 0%, #c9a46a 58%, #b3905a 100%);
  box-shadow: 0 10px 30px -20px var(--v3-glow-gold);
}
.is-primary:hover {
  transform: translateY(-1px);
  background: linear-gradient(135deg, #e2c892 0%, #d0ac71 58%, #b9975f 100%);
  box-shadow: 0 16px 38px -22px var(--v3-glow-gold);
}
.is-primary:active { transform: translateY(0) scale(0.985); }

/* glass：玻璃面板上的次级动作，只有边界与底色 */
.is-glass {
  color: var(--v3-text-soft);
  background: var(--v3-surface-1);
  border-color: var(--v3-hairline-strong);
  backdrop-filter: blur(14px);
}
.is-glass:hover {
  color: var(--v3-text);
  border-color: var(--v3-hairline-gold);
  background: var(--v3-surface-2);
  transform: translateY(-1px);
}

.is-ghost { color: var(--v3-text-muted); background: transparent; }
.is-ghost:hover { color: var(--v3-text); background: var(--v3-surface-2); }

.is-outline {
  color: var(--v3-gold-bright);
  background: transparent;
  border-color: var(--v3-hairline-gold);
}
.is-outline:hover { background: var(--v3-glow-gold); transform: translateY(-1px); }

/*
 * 危险按钮：在亮色主题里旧色 #ffd9d3 落在浅粉底上几乎看不见（实测截图只剩一团浅色）。
 * 改用主题里的 cinnabar 深色做文字、压低底色透明度，保证两种主题都读得清。
 */
.is-danger {
  color: var(--v3-cinnabar);
  background: var(--v3-glow-cinnabar);
  border-color: rgba(201, 76, 59, 0.42);
  font-weight: 500;
}
.is-danger:hover {
  color: #fff;
  background: var(--v3-cinnabar);
  border-color: var(--v3-cinnabar);
}
:global(html[data-theme="inksea"]) .is-danger { color: #ffb4a8; }
:global(html[data-theme="inksea"]) .is-danger:hover { color: #2a0d0a; }

.spinner {
  width: 14px;
  height: 14px;
  border: 1.5px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: v3-spin 700ms linear infinite;
}
.label { display: inline-flex; align-items: center; gap: 6px; }
</style>
