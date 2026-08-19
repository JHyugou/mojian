<script setup>
/**
 * 项目守卫：所有项目内的页面共用——未选择项目时给出引导。
 */
import { useProjectStore } from '../stores/project'

const projectStore = useProjectStore()
</script>

<template>
  <div v-if="!projectStore.current" class="empty-state card">
    <p class="gate-title">请先选择一个创作项目</p>
    <p class="gate-desc">
      回到「创作项目」页创建或进入一个项目后，这里的内容才会可用。
    </p>
    <RouterLink to="/" class="btn btn-primary gate-link">前往创作项目</RouterLink>
  </div>
  <slot v-else />
</template>

<style scoped>
.gate-title {
  font-family: var(--font-serif);
  font-size: 16px;
}

.gate-desc {
  margin-top: 10px;
  font-size: 13px;
  color: var(--color-muted);
}

.gate-link {
  margin-top: 18px;
  text-decoration: none;
}
</style>
