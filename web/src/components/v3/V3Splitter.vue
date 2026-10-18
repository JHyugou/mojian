<script setup>
/**
 * 竖向分割线（拖拽调整两侧宽度）。
 *
 * <p>只负责外观与事件转发：具体宽度计算、持久化在 `useWidthResize`，
 * 这样工作区树 / 右栏 / 对话-正文三处的行为完全一致。
 */
defineProps({
  title: { type: String, default: '拖拽调整宽度，双击恢复默认' }
})
const emit = defineEmits(['drag-start', 'reset'])
</script>

<template>
  <div
    class="v3-splitter"
    role="separator"
    aria-orientation="vertical"
    :title="title"
    @pointerdown="emit('drag-start', $event)"
    @dblclick="emit('reset')"
  ></div>
</template>

<style scoped>
.v3-splitter {
  position: relative;
  z-index: 8;
  flex: 0 0 7px;
  align-self: stretch;
  margin: 0 -3px;
  cursor: col-resize;
  touch-action: none;
}
/* 视觉上只是一根发丝线，但可点击区域比线宽 */
.v3-splitter::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 50%;
  width: 1px;
  transform: translateX(-50%);
  background: var(--v3-hairline);
  transition: background var(--v3-dur-2), width var(--v3-dur-2), box-shadow var(--v3-dur-2);
}
.v3-splitter:hover::before,
.v3-splitter:active::before {
  width: 2px;
  background: var(--v3-gold);
  box-shadow: 0 0 10px 1px var(--v3-glow-gold);
}
</style>
