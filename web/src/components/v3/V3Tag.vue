<script setup>
/** v3 标签：金线小胶囊 / 朱砂印章两种气质 */
defineProps({
  tone: { type: String, default: 'line' }, // line | gold | cinnabar | jade
  size: { type: String, default: 'md' }
})
</script>

<template>
  <span class="v3-tag" :class="[`is-${tone}`, `is-${size}`]"><slot /></span>
</template>

<style scoped>
.v3-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: var(--v3-r-pill);
  border: 1px solid var(--v3-hairline-strong);
  font-size: 11px;
  letter-spacing: 0.06em;
  line-height: 1.6;
  color: var(--v3-text-muted);
  white-space: nowrap;
}
.is-md { padding: 4px 11px; font-size: 11.5px; }
.is-gold { color: var(--v3-gold-bright); border-color: var(--v3-hairline-gold); background: var(--v3-glow-gold); }
.is-cinnabar { color: #ffd9d3; border-color: rgba(201, 76, 59, 0.45); background: var(--v3-cinnabar-soft); letter-spacing: 0.14em; }
.is-jade { color: var(--v3-jade); border-color: rgba(127, 168, 161, 0.36); background: rgba(127, 168, 161, 0.12); }
</style>
