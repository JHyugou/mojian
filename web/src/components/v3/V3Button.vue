<script setup>
import { computed, ref } from 'vue'

/**
 * v3 按钮。
 *
 * 变体：primary（金色渐变）/ glass（玻璃）/ ghost（无底）/ outline（金线）/ danger。
 * 悬停有一条很克制的"光扫"（sheen），按下用 scale 0.985 反馈，均只动 transform/opacity。
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
const sheenKey = ref(0)
function onEnter() {
  sheenKey.value += 1
}
</script>

<template>
  <button
    :type="type"
    :class="cls"
    :disabled="disabled || loading"
    @mouseenter="onEnter"
    @click="$emit('click', $event)"
  >
    <span v-if="loading" class="spinner" aria-hidden="true"></span>
    <slot name="icon" />
    <span class="label"><slot /></span>
    <span v-if="variant === 'primary'" :key="sheenKey" class="sheen" aria-hidden="true"></span>
  </button>
</template>

<style scoped>
.v3-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  overflow: hidden;
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

/* 尺寸 */
.is-sm { height: 32px; padding: 0 14px; font-size: 12.5px; }
.is-md { height: 40px; padding: 0 20px; font-size: 13.5px; }
.is-lg { height: 50px; padding: 0 28px; font-size: 15px; letter-spacing: 0.04em; }

/* primary：金箔 */
.is-primary {
  color: #201a10;
  background: var(--v3-grad-gold);
  box-shadow: 0 8px 26px -12px var(--v3-glow-gold), 0 1px 0 rgba(255, 255, 255, 0.35) inset;
}
.is-primary:hover { transform: translateY(-1px); box-shadow: 0 14px 36px -14px var(--v3-glow-gold), 0 1px 0 rgba(255, 255, 255, 0.4) inset; }
.is-primary:active { transform: translateY(0) scale(0.985); }

.sheen {
  position: absolute;
  top: -20%;
  bottom: -20%;
  width: 42%;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.55), transparent);
  animation: v3-sheen 900ms var(--v3-ease-out) both;
  pointer-events: none;
}

/* glass：玻璃面板上的次级动作 */
.is-glass {
  color: var(--v3-text);
  background: var(--v3-surface-2);
  border-color: var(--v3-hairline-strong);
  backdrop-filter: blur(14px);
}
.is-glass:hover { border-color: var(--v3-hairline-gold); background: var(--v3-surface-raised); transform: translateY(-1px); }

/* ghost：文字按钮 */
.is-ghost { color: var(--v3-text-soft); background: transparent; }
.is-ghost:hover { color: var(--v3-text); background: var(--v3-surface-2); }

/* outline：金色细线 */
.is-outline {
  color: var(--v3-gold-bright);
  background: transparent;
  border-color: var(--v3-hairline-gold);
}
.is-outline:hover { background: var(--v3-glow-gold); transform: translateY(-1px); }

.is-danger { color: #ffd9d3; background: var(--v3-cinnabar-soft); border-color: rgba(201, 76, 59, 0.4); }
.is-danger:hover { background: rgba(201, 76, 59, 0.24); }

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
