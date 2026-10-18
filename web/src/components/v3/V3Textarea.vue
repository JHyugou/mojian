<script setup>
/** v3 多行输入框 */
defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  rows: { type: Number, default: 4 },
  maxlength: { type: [Number, String], default: undefined },
  disabled: { type: Boolean, default: false },
  resize: { type: Boolean, default: true }
})
const emit = defineEmits(['update:modelValue'])
</script>

<template>
  <textarea
    class="v3-textarea"
    :value="modelValue"
    :rows="rows"
    :placeholder="placeholder"
    :maxlength="maxlength"
    :disabled="disabled"
    :style="{ resize: resize ? 'vertical' : 'none' }"
    @input="emit('update:modelValue', $event.target.value)"
  ></textarea>
</template>

<style scoped>
.v3-textarea {
  width: 100%;
  min-height: 90px;
  padding: 13px 15px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  color: var(--v3-text);
  font-family: var(--v3-font-sans);
  font-size: 13.5px;
  line-height: 1.85;
  outline: none;
  transition: border-color var(--v3-dur-2), background var(--v3-dur-2), box-shadow var(--v3-dur-2);
}
.v3-textarea::placeholder { color: var(--v3-text-faint); }
.v3-textarea:hover { border-color: var(--v3-hairline-gold); }
.v3-textarea:focus {
  border-color: var(--v3-gold);
  background: var(--v3-surface-2);
  box-shadow: 0 0 0 3px var(--v3-glow-gold);
}
</style>
