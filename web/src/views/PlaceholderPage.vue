<script setup>
defineProps({
  title: { type: String, required: true }
})
</script>

<template>
  <section>
    <div class="page-head">
      <h1 class="page-title">{{ title }}</h1>
    </div>
    <hr class="ink-divider" />
    <div class="empty-state card">
      <p class="empty-title">{{ title }} · 建设中</p>
      <p class="empty-desc">该模块将在后续里程碑中实现，当前为工程骨架占位页。</p>
    </div>
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-title {
  font-size: 22px;
}

.empty-state {
  margin-top: 40px;
  padding: 48px 24px;
  text-align: center;
}

.empty-title {
  font-family: var(--font-serif);
  font-size: 16px;
}

.empty-desc {
  margin-top: 10px;
  font-size: 13px;
  color: var(--color-muted);
}
</style>
