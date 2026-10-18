<script setup>
import { useRoute } from 'vue-router'

/**
 * 应用根组件（v3）。
 *
 * 所有页面现在都是"自带画布"的全屏页面（首页/工作台/成书/设置），
 * 旧的常驻外壳已经退役，这里只负责路由出口与页面转场。
 * 页面切换用一层很短的淡入 + 轻微上浮：动效是"翻页"的暗示，不抢内容。
 */
const route = useRoute()
</script>

<template>
  <RouterView v-slot="{ Component }">
    <Transition name="page" mode="out-in">
      <component :is="Component" :key="route.path" />
    </Transition>
  </RouterView>
</template>

<style>
.page-enter-active,
.page-leave-active {
  transition: opacity 260ms var(--v3-ease-out), transform 320ms var(--v3-ease-out), filter 260ms var(--v3-ease-out);
}
.page-enter-from {
  opacity: 0;
  transform: translate3d(0, 10px, 0);
  filter: blur(6px);
}
.page-leave-to {
  opacity: 0;
  transform: translate3d(0, -6px, 0);
  filter: blur(4px);
}
@media (prefers-reduced-motion: reduce) {
  .page-enter-active,
  .page-leave-active { transition: none; }
}
</style>
