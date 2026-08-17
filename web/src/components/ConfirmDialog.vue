<script setup>
import AppModal from './AppModal.vue'

defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '确认操作' },
  message: { type: String, default: '' },
  confirmText: { type: String, default: '确认' },
  danger: { type: Boolean, default: false },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['confirm', 'close'])
</script>

<template>
  <AppModal :visible="visible" :title="title" width="400px" @close="emit('close')">
    <p class="confirm-message">{{ message }}</p>
    <div class="confirm-actions">
      <button class="btn btn-secondary" type="button" @click="emit('close')">取消</button>
      <button
        class="btn"
        :class="danger ? 'btn-danger' : 'btn-primary'"
        type="button"
        :disabled="loading"
        @click="emit('confirm')"
      >
        {{ loading ? '处理中…' : confirmText }}
      </button>
    </div>
  </AppModal>
</template>

<style scoped>
.confirm-message {
  color: var(--color-ink);
  line-height: 1.7;
}

.confirm-actions {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
