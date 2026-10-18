<script setup>
import V3Button from './V3Button.vue'

/** v3 空态 / 错误态：印章字 + 一句解释 + 可选动作 */
defineProps({
  seal: { type: String, default: '空' },
  title: { type: String, default: '这里还没有内容' },
  desc: { type: String, default: '' },
  action: { type: String, default: '' },
  error: { type: Boolean, default: false }
})
const emit = defineEmits(['action'])
</script>

<template>
  <div class="v3-empty" :class="{ 'is-error': error }">
    <span class="seal" aria-hidden="true">{{ seal }}</span>
    <p class="title">{{ title }}</p>
    <p v-if="desc" class="desc">{{ desc }}</p>
    <V3Button v-if="action" variant="outline" size="sm" @click="emit('action')">{{ action }}</V3Button>
    <slot />
  </div>
</template>

<style scoped>
.v3-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 56px 24px;
  text-align: center;
}
.seal {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 10px;
  border: 1px solid var(--v3-cinnabar);
  color: var(--v3-cinnabar);
  font-family: var(--v3-font-serif);
  font-size: 19px;
  letter-spacing: 0;
  transform: rotate(-4deg);
  opacity: 0.82;
}
.is-error .seal { border-color: var(--v3-gold); color: var(--v3-gold); }
.title {
  margin: 6px 0 0;
  font-family: var(--v3-font-serif);
  font-size: 16px;
  letter-spacing: 0.05em;
  color: var(--v3-text);
}
.desc { margin: 0; max-width: 420px; font-size: 12.5px; line-height: 1.9; color: var(--v3-text-muted); }
</style>
