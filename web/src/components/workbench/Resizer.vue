<script setup>
import { onBeforeUnmount, ref } from 'vue'

/**
 * 可拖拽的分隔条。
 *
 * 用法：把当前宽度用 v-model 绑进来，拖完会 emit 新宽度。
 *
 * 两个细节值得说：
 *   1. 拖拽期间给 body 加 cursor/user-select —— 否则鼠标划过文字会变成
 *      文本光标、还会把界面选中一片蓝，看着像卡死了
 *   2. 视觉宽度 5px，但命中区域左右各外扩 3px：5px 好看但不好点，
 *      而"看得见的细线 + 点得中的宽区"是可以同时做到的
 */
const props = defineProps({
  modelValue: { type: Number, required: true },
  min: { type: Number, default: 160 },
  max: { type: Number, default: 640 },
  /** 分隔条在目标右侧时传 true：向左拖才是变宽 */
  invert: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue'])

const dragging = ref(false)

function onMouseDown(event) {
  event.preventDefault()
  dragging.value = true

  const startX = event.clientX
  const startWidth = props.modelValue

  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'

  const onMove = (moveEvent) => {
    const delta = (moveEvent.clientX - startX) * (props.invert ? -1 : 1)
    const next = Math.min(props.max, Math.max(props.min, startWidth + delta))
    emit('update:modelValue', Math.round(next))
  }

  const onUp = () => {
    dragging.value = false
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
  }

  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

// 组件在拖拽中途被卸载（比如切换模式）时，别把 body 的样式留在原地
onBeforeUnmount(() => {
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
})
</script>

<template>
  <div
    class="rz"
    :class="{ dragging }"
    role="separator"
    aria-orientation="vertical"
    @mousedown="onMouseDown"
  >
    <span class="rz-grip"></span>
  </div>
</template>

<style scoped>
.rz {
  position: relative;
  width: 5px;
  flex-shrink: 0;
  cursor: col-resize;
  background: transparent;
  transition: background var(--dur) var(--ease-out);
  z-index: 5;
}

/* 命中区域外扩：5px 的线好看但不好点 */
.rz::after {
  content: '';
  position: absolute;
  inset: 0 -3px;
}

.rz:hover,
.rz.dragging {
  background: var(--color-brown-soft);
}

/* 拖拽时中间显一道实色，给出"正在调整"的反馈 */
.rz-grip {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 3px;
  height: 0;
  border-radius: 2px;
  background: var(--color-brown);
  transition: height var(--dur) var(--ease-out);
}
.rz:hover .rz-grip,
.rz.dragging .rz-grip {
  height: 28px;
}
</style>
