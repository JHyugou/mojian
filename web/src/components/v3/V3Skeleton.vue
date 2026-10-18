<script setup>
/** v3 骨架屏：加载态用"呼吸的金线块"，而不是灰块 */
defineProps({
  lines: { type: Number, default: 3 },
  height: { type: String, default: '14px' }
})
</script>

<template>
  <div class="v3-skeleton" aria-hidden="true">
    <span
      v-for="i in lines"
      :key="i"
      class="sk-line"
      :style="{ height, width: i === lines ? '62%' : '100%' }"
    ></span>
  </div>
</template>

<style scoped>
.v3-skeleton { display: flex; flex-direction: column; gap: 10px; }
.sk-line {
  display: block;
  border-radius: var(--v3-r-xs);
  background: linear-gradient(90deg, var(--v3-surface-2) 20%, var(--v3-surface-raised) 40%, var(--v3-surface-2) 60%);
  background-size: 220% 100%;
  animation: v3-shimmer 1.8s linear infinite;
}
</style>
