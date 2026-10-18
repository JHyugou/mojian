<script setup>
/** v3 单行输入框 */
defineProps({
  modelValue: { type: [String, Number], default: '' },
  placeholder: { type: String, default: '' },
  type: { type: String, default: 'text' },
  maxlength: { type: [Number, String], default: undefined },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'enter'])
</script>

<template>
  <input
    class="v3-input"
    :type="type"
    :value="modelValue"
    :placeholder="placeholder"
    :maxlength="maxlength"
    :disabled="disabled"
    @input="emit('update:modelValue', $event.target.value)"
    @keydown.enter="emit('enter', $event)"
  />
</template>

<style scoped>
.v3-input {
  width: 100%;
  height: 42px;
  padding: 0 15px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  color: var(--v3-text);
  font-family: var(--v3-font-sans);
  font-size: 13.5px;
  outline: none;
  transition: border-color var(--v3-dur-2), background var(--v3-dur-2), box-shadow var(--v3-dur-2);
}
.v3-input::placeholder { color: var(--v3-text-faint); }
.v3-input:hover { border-color: var(--v3-hairline-gold); }
.v3-input:focus {
  border-color: var(--v3-gold);
  background: var(--v3-surface-2);
  box-shadow: 0 0 0 3px var(--v3-glow-gold);
}
.v3-input:disabled { opacity: 0.5; }
</style>
