<script setup>
import { useV3Notify } from '../../v3/notify'

/** v3 通知栈：右下角浮出，可点击关闭 */
const { items, dismiss } = useV3Notify()
</script>

<template>
  <Teleport to="body">
    <div class="v3-toasts v3-root" aria-live="polite">
      <TransitionGroup name="v3-toast">
        <div
          v-for="item in items"
          :key="item.id"
          class="v3-toast"
          :class="`is-${item.type}`"
          @click="dismiss(item.id)"
        >
          <span class="dot" aria-hidden="true"></span>
          <span class="text">{{ item.text }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.v3-toasts {
  position: fixed;
  right: 22px;
  bottom: 22px;
  z-index: 300;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: none;
}
.v3-toast {
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: 420px;
  padding: 12px 16px;
  border-radius: var(--v3-r-sm);
  border: 1px solid var(--v3-hairline-strong);
  background: var(--v3-grad-glass), var(--v3-surface-solid);
  box-shadow: var(--v3-shadow-float);
  color: var(--v3-text);
  font-size: 12.5px;
  line-height: 1.7;
  cursor: pointer;
  pointer-events: auto;
  backdrop-filter: blur(16px);
}
.dot { width: 6px; height: 6px; border-radius: 50%; background: var(--v3-gold); flex-shrink: 0; }
.is-success .dot { background: var(--v3-jade); }
.is-error .dot { background: var(--v3-cinnabar); }

.v3-toast-enter-active { transition: all var(--v3-dur-3) var(--v3-ease-spring); }
.v3-toast-leave-active { transition: all var(--v3-dur-2) var(--v3-ease-out); position: absolute; right: 0; }
.v3-toast-enter-from { opacity: 0; transform: translate3d(0, 14px, 0) scale(0.96); }
.v3-toast-leave-to { opacity: 0; transform: translate3d(14px, 0, 0); }
</style>
