<script setup>
/** v3 表单字段：标题 + 必填星号 + 说明 + 内容 slot */
defineProps({
  label: { type: String, default: '' },
  required: { type: Boolean, default: false },
  hint: { type: String, default: '' }
})
</script>

<template>
  <label class="v3-field">
    <span class="v3-field-head">
      <span class="v3-field-label">
        {{ label }}<i v-if="required" class="v3-field-required" aria-hidden="true">*</i>
      </span>
      <span v-if="hint" class="v3-field-hint">{{ hint }}</span>
    </span>
    <slot />
  </label>
</template>

<style scoped>
.v3-field { display: flex; flex-direction: column; gap: 8px; }
.v3-field + .v3-field { margin-top: 16px; }
.v3-field-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.v3-field-label {
  font-size: 12.5px;
  letter-spacing: 0.04em;
  color: var(--v3-text-soft);
}
.v3-field-required { margin-left: 3px; font-style: normal; color: var(--v3-cinnabar); }
.v3-field-hint { font-size: 11px; color: var(--v3-text-faint); }
</style>
