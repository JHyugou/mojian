<script setup>
defineProps({
  title: { type: String, default: '' },
  visible: { type: Boolean, default: false },
  width: { type: String, default: '520px' }
})

const emit = defineEmits(['close'])
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="modal-mask" @click.self="emit('close')">
      <div class="modal-card card" :style="{ maxWidth: width }" role="dialog" :aria-label="title">
        <header class="modal-head">
          <h3 class="modal-title">{{ title }}</h3>
          <button class="modal-close" type="button" aria-label="关闭" @click="emit('close')">×</button>
        </header>
        <div class="modal-body">
          <slot />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(44, 58, 67, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.modal-card {
  width: 90%;
  max-height: 82vh;
  display: flex;
  flex-direction: column;
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--color-line);
}

.modal-title {
  font-size: 16px;
}

.modal-close {
  border: none;
  background: none;
  font-size: 20px;
  color: var(--color-muted);
  cursor: pointer;
  line-height: 1;
}

.modal-close:hover {
  color: var(--color-danger);
}

.modal-body {
  padding: 20px;
  overflow-y: auto;
}
</style>
