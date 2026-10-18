<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import Icon from '../Icon.vue'

/**
 * v3 命令面板（⌘K / Ctrl+K）。
 *
 * 首页用它搜项目、执行动作；工作台会复用它做导航与操作入口。
 * 键盘优先：↑↓ 选择、Enter 执行、Esc 关闭。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  items: { type: Array, default: () => [] },
  placeholder: { type: String, default: '搜索项目，或输入命令…' }
})
const emit = defineEmits(['close', 'select'])

const query = ref('')
const active = ref(0)
const inputEl = ref(null)
const listEl = ref(null)

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return props.items.slice(0, 9)
  return props.items
    .filter((item) => `${item.label} ${item.hint || ''}`.toLowerCase().includes(q))
    .slice(0, 9)
})

watch(
  () => props.visible,
  async (open) => {
    if (open) {
      query.value = ''
      active.value = 0
      document.addEventListener('keydown', onKeydown, true)
      document.body.style.overflow = 'hidden'
      await nextTick()
      inputEl.value?.focus()
    } else {
      document.removeEventListener('keydown', onKeydown, true)
      document.body.style.overflow = ''
    }
  }
)

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown, true)
  document.body.style.overflow = ''
})

watch(filtered, () => { active.value = 0 })

function onKeydown(event) {
  if (!props.visible) return
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
  } else if (event.key === 'ArrowDown') {
    event.preventDefault()
    active.value = Math.min(active.value + 1, Math.max(filtered.value.length - 1, 0))
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    active.value = Math.max(active.value - 1, 0)
  } else if (event.key === 'Enter') {
    event.preventDefault()
    const item = filtered.value[active.value]
    if (item) emit('select', item)
  }
}

function pick(item, index) {
  active.value = index
  emit('select', item)
}
</script>

<template>
  <Teleport to="body">
    <Transition name="v3-cp">
      <div v-if="visible" class="v3-cp-mask v3-root" @mousedown.self="emit('close')">
        <div class="v3-cp" role="dialog" aria-modal="true" aria-label="命令面板">
          <div class="cp-input-row">
            <Icon name="search" :size="16" />
            <input
              ref="inputEl"
              v-model="query"
              class="cp-input"
              :placeholder="placeholder"
              @keydown="onKeydown"
            />
            <kbd>ESC</kbd>
          </div>
          <div ref="listEl" class="cp-list v3-scroll">
            <button
              v-for="(item, index) in filtered"
              :key="item.id"
              class="cp-item"
              :class="{ active: index === active }"
              @mouseenter="active = index"
              @click="pick(item, index)"
            >
              <Icon v-if="item.icon" :name="item.icon" :size="15" />
              <span class="cp-label">{{ item.label }}</span>
              <span v-if="item.hint" class="cp-hint">{{ item.hint }}</span>
            </button>
            <p v-if="!filtered.length" class="cp-empty">没有匹配的条目</p>
          </div>
          <div class="cp-foot">
            <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span>
            <span><kbd>↵</kbd> 执行</span>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.v3-cp-mask {
  position: fixed;
  inset: 0;
  z-index: 250;
  display: flex;
  justify-content: center;
  padding-top: 14vh;
  background: rgba(3, 4, 5, 0.55);
  backdrop-filter: blur(8px);
}
.v3-cp {
  width: min(640px, calc(100vw - 40px));
  height: fit-content;
  max-height: 64vh;
  display: flex;
  flex-direction: column;
  border-radius: var(--v3-r-lg);
  border: 1px solid var(--v3-hairline-gold);
  background: var(--v3-grad-glass), var(--v3-surface-solid);
  box-shadow: var(--v3-shadow-float);
  overflow: hidden;
}
.cp-input-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--v3-hairline);
  color: var(--v3-text-muted);
}
.cp-input {
  flex: 1;
  border: 0;
  background: transparent;
  color: var(--v3-text);
  font-family: var(--v3-font-sans);
  font-size: 15px;
  outline: none;
}
.cp-input::placeholder { color: var(--v3-text-faint); }
kbd {
  padding: 2px 6px;
  border-radius: 5px;
  border: 1px solid var(--v3-hairline-strong);
  font-family: var(--v3-font-mono);
  font-size: 10px;
  color: var(--v3-text-muted);
}
.cp-list { padding: 8px; }
.cp-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 11px 13px;
  border: 0;
  border-radius: var(--v3-r-xs);
  background: transparent;
  color: var(--v3-text-soft);
  font-family: inherit;
  font-size: 13.5px;
  text-align: left;
  cursor: pointer;
  transition: background var(--v3-dur-1), color var(--v3-dur-1);
}
.cp-item.active { background: var(--v3-glow-gold); color: var(--v3-text); }
.cp-label { flex: 1; }
.cp-hint { font-size: 11px; color: var(--v3-text-faint); }
.cp-empty { padding: 22px; text-align: center; font-size: 12.5px; color: var(--v3-text-faint); }
.cp-foot {
  display: flex;
  gap: 16px;
  padding: 10px 18px;
  border-top: 1px solid var(--v3-hairline);
  font-size: 10.5px;
  color: var(--v3-text-faint);
}
.cp-foot span { display: inline-flex; gap: 4px; align-items: center; }

.v3-cp-enter-active, .v3-cp-leave-active { transition: opacity var(--v3-dur-2) var(--v3-ease-out); }
.v3-cp-enter-active .v3-cp, .v3-cp-leave-active .v3-cp { transition: transform var(--v3-dur-3) var(--v3-ease-spring), opacity var(--v3-dur-2); }
.v3-cp-enter-from, .v3-cp-leave-to { opacity: 0; }
.v3-cp-enter-from .v3-cp, .v3-cp-leave-to .v3-cp { opacity: 0; transform: translate3d(0, -10px, 0) scale(0.98); }
</style>
