<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

/**
 * v3 弹窗：Teleport 到 body，玻璃面板 + 模糊背景。
 * 基本的可访问性：Esc 关闭、打开时锁滚动、自动聚焦第一个可交互元素。
 * （焦点陷阱在 P4 的可访问性收口里补全；这里先把"能键盘关闭 + 不滚动背景"做对。）
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '' },
  width: { type: String, default: '560px' },
  closable: { type: Boolean, default: true }
})
const emit = defineEmits(['close'])
const panel = ref(null)

function onKeydown(event) {
  if (!props.visible) return
  if (event.key === 'Escape' && props.closable) {
    event.stopPropagation()
    emit('close')
    return
  }
  // 焦点陷阱：Tab / Shift+Tab 在弹窗内循环，不跑到背后的页面上
  if (event.key === 'Tab' && panel.value) {
    const focusables = panel.value.querySelectorAll(
      'button:not([disabled]), [href], input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
    )
    if (!focusables.length) return
    const first = focusables[0]
    const last = focusables[focusables.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }
}

watch(
  () => props.visible,
  async (open) => {
    if (open) {
      document.addEventListener('keydown', onKeydown, true)
      document.body.style.overflow = 'hidden'
      await nextTick()
      panel.value?.querySelector('input, textarea, button:not(.v3-modal-x)')?.focus()
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
</script>

<template>
  <Teleport to="body">
    <Transition name="v3-modal">
      <div v-if="visible" class="v3-modal-mask v3-root" @mousedown.self="closable && emit('close')">
        <div ref="panel" class="v3-modal" :style="{ width }" role="dialog" aria-modal="true" :aria-label="title">
          <header class="v3-modal-head">
            <h2 v-if="title" class="v3-modal-title">{{ title }}</h2>
            <button v-if="closable" class="v3-modal-x" aria-label="关闭" @click="emit('close')">×</button>
          </header>
          <div class="v3-modal-body v3-scroll"><slot /></div>
          <footer v-if="$slots.footer" class="v3-modal-foot"><slot name="footer" /></footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.v3-modal-mask {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(3, 4, 5, 0.58);
  backdrop-filter: blur(10px);
}

.v3-modal {
  display: flex;
  flex-direction: column;
  max-width: calc(100vw - 48px);
  max-height: min(86vh, 780px);
  border-radius: var(--v3-r-xl);
  border: 1px solid var(--v3-hairline-strong);
  background: var(--v3-grad-glass), var(--v3-surface-solid);
  box-shadow: var(--v3-shadow-float);
  overflow: hidden;
}

.v3-modal-head {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22px 26px 14px;
  flex-shrink: 0;
}
.v3-modal-head::after {
  content: '';
  position: absolute;
  left: 26px;
  right: 26px;
  bottom: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--v3-hairline-strong) 20%, var(--v3-hairline-strong) 80%, transparent);
}
.v3-modal-title {
  margin: 0;
  font-family: var(--v3-font-serif);
  font-size: 19px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--v3-text);
}
.v3-modal-x {
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--v3-text-muted);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  transition: background var(--v3-dur-2), color var(--v3-dur-2);
}
.v3-modal-x:hover { background: var(--v3-surface-2); color: var(--v3-text); }

.v3-modal-body { padding: 20px 26px 24px; }
.v3-modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 26px 22px;
  border-top: 1px solid var(--v3-hairline);
  flex-shrink: 0;
}

/* 过渡：从"更小 + 更暗"浮上来，而不是简单地淡入 */
.v3-modal-enter-active, .v3-modal-leave-active {
  transition: opacity var(--v3-dur-3) var(--v3-ease-out);
}
.v3-modal-enter-active .v3-modal, .v3-modal-leave-active .v3-modal {
  transition: transform var(--v3-dur-3) var(--v3-ease-spring), opacity var(--v3-dur-3) var(--v3-ease-out);
}
.v3-modal-enter-from, .v3-modal-leave-to { opacity: 0; }
.v3-modal-enter-from .v3-modal, .v3-modal-leave-to .v3-modal {
  opacity: 0;
  transform: translate3d(0, 18px, 0) scale(0.97);
}
</style>
