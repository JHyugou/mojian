<script setup>
import { ref } from 'vue'
import { useTilt } from '../../v3/motion'

/**
 * v3 卡片：玻璃/纸面两种材质。
 * 可开启 tilt（3D 微倾斜 + 光标高光）——只在"可点击的大卡片"上用，
 * 列表里的小卡片全靠 CSS 悬停，避免满屏都在动。
 */
const props = defineProps({
  material: { type: String, default: 'glass' }, // glass | paper | plain
  interactive: { type: Boolean, default: false },
  tilt: { type: Boolean, default: false },
  glow: { type: Boolean, default: true },
  padded: { type: Boolean, default: true }
})

const el = ref(null)
if (props.tilt) useTilt(el, { maxTilt: 5 })
</script>

<template>
  <div
    ref="el"
    class="v3-card"
    :class="[`is-${material}`, { 'is-interactive': interactive, 'has-tilt': tilt, 'has-glow': glow, 'is-padded': padded }]"
  >
    <span v-if="glow" class="card-glow" aria-hidden="true"></span>
    <slot />
  </div>
</template>

<style scoped>
.v3-card {
  position: relative;
  border-radius: var(--v3-r-lg);
  border: 1px solid var(--v3-hairline);
  transition:
    transform var(--v3-dur-3) var(--v3-ease-out),
    border-color var(--v3-dur-3) var(--v3-ease-out),
    background var(--v3-dur-3) var(--v3-ease-out),
    box-shadow var(--v3-dur-3) var(--v3-ease-out);
  will-change: transform;
}
.is-padded { padding: 22px; }

.is-glass {
  background: var(--v3-grad-glass), var(--v3-surface-0);
  backdrop-filter: blur(20px) saturate(120%);
  box-shadow: var(--v3-shadow-card);
}
.is-paper {
  background: var(--v3-surface-solid);
  box-shadow: var(--v3-shadow-card);
}
.is-plain { background: transparent; }

.is-interactive { cursor: pointer; }
.is-interactive:hover {
  transform: translate3d(0, -3px, 0);
  border-color: var(--v3-hairline-gold);
  box-shadow: var(--v3-shadow-float);
}
.is-interactive:active { transform: translate3d(0, -1px, 0) scale(0.997); }

.card-glow {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--v3-dur-3) var(--v3-ease-out);
  background: radial-gradient(
    420px circle at var(--v3-mx, 50%) var(--v3-my, 50%),
    var(--v3-glow-gold),
    transparent 62%
  );
}
.is-interactive:hover .card-glow { opacity: 1; }
</style>
