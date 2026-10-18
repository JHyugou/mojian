<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import WorkspaceShell from './v3/layout/WorkspaceShell.vue'

/**
 * 应用根组件（v3）。
 *
 * <p>外壳（左轨 + 工作区树 + 顶栏）在这里常驻，而不是每个页面各包一层：
 * 页面之间切换时外壳不重建，点"首页 / 工作台 / 成书 / 设置"不会出现
 * 侧边栏闪一下、工作区树重新加载的割裂感。
 * 页面通过 <Teleport to="#shell-topbar"> 注入自己的顶栏动作。
 *
 * <p>设计定稿页 /ui-preview 是开发用页面，不套外壳。
 */
const route = useRoute()
const shellVisible = computed(() => route.path !== '/ui-preview')
</script>

<template>
  <WorkspaceShell v-if="shellVisible">
    <RouterView v-slot="{ Component }">
      <Transition name="page">
        <component :is="Component" :key="route.path" />
      </Transition>
    </RouterView>
  </WorkspaceShell>
  <RouterView v-else v-slot="{ Component }">
    <Transition name="page">
      <component :is="Component" :key="route.path" />
    </Transition>
  </RouterView>
</template>

<style>
/*
 * 页面转场。
 *
 * <p>不能用 mode="out-in"：RouterView 位于外壳的插槽里时，out-in 的
 * "先离场再入场"会让新页面永远不挂载（实测切页后内容区只剩一个注释节点）。
 * 改成同时过渡 + 让离场页绝对定位，视觉上仍是"淡出淡入"，但不会挤压布局。
 */
.page-enter-active,
.page-leave-active {
  transition: opacity 260ms var(--v3-ease-out), transform 320ms var(--v3-ease-out), filter 260ms var(--v3-ease-out);
}
.page-leave-active {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 0;
}
.page-enter-active { position: relative; z-index: 1; }
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
