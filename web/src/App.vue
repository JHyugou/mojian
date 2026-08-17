<script setup>
import { ref } from 'vue'
import { useProjectStore } from './stores/project'
import { useMessageStore } from './stores/message'

const contextOpen = ref(true)
const projectStore = useProjectStore()
const messageStore = useMessageStore()

const navItems = [
  { path: '/', label: '创作项目', hint: '项目' },
  { path: '/characters', label: '人物档案', hint: '人物' },
  { path: '/settings', label: '故事设定库', hint: '设定' },
  { path: '/outline', label: '剧情蓝图', hint: '蓝图' },
  { path: '/chapters', label: '章节创作', hint: '章节' }
]
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="brand">
        <svg class="brand-mark" viewBox="0 0 32 32" aria-hidden="true">
          <rect width="32" height="32" rx="6" fill="var(--color-dai)" />
          <path d="M4 24 L12 10 L18 20 L22 14 L28 24 Z" fill="var(--color-dai-deep)" />
          <circle cx="24" cy="8" r="3" fill="var(--color-cinnabar)" />
        </svg>
        <div class="brand-text">
          <span class="brand-name">墨笺</span>
          <span class="brand-sub">AI 长篇小说创作工作台</span>
        </div>
      </div>
      <span v-if="projectStore.current" class="current-project">
        当前项目 · {{ projectStore.current.name }}
      </span>
    </header>

    <div class="app-body">
      <nav class="app-nav" aria-label="工作区导航">
        <RouterLink
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          active-class="active"
        >
          <span class="nav-hint">{{ item.hint }}</span>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <main class="app-main">
        <RouterView />
      </main>

      <aside class="app-context" :class="{ collapsed: !contextOpen }" aria-label="上下文辅助">
        <button
          class="context-toggle btn btn-secondary"
          @click="contextOpen = !contextOpen"
        >
          {{ contextOpen ? '收起 »' : '« 上下文' }}
        </button>
        <div v-if="contextOpen" class="context-body">
          <p class="context-title">本章上下文</p>
          <hr class="ink-divider" />
          <p class="context-empty">尚未选择章节<br />生成时将在此展示实际注入的人物、设定与章节记忆</p>
        </div>
      </aside>
    </div>

    <div class="toast-area" aria-live="polite">
      <div
        v-for="item in messageStore.items"
        :key="item.id"
        class="toast"
        :class="`toast-${item.type}`"
        @click="messageStore.dismiss(item.id)"
      >
        {{ item.text }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.app-header {
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: var(--color-panel);
  border-bottom: 1px solid var(--color-line);
}

.current-project {
  font-size: 13px;
  color: var(--color-dai-deep);
  background: var(--color-dai);
  padding: 4px 12px;
  border-radius: 999px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-mark {
  width: 28px;
  height: 28px;
}

.brand-name {
  font-family: var(--font-serif);
  font-size: 18px;
  letter-spacing: 4px;
}

.brand-sub {
  font-size: 12px;
  color: var(--color-muted);
  margin-left: 8px;
}

.app-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.app-nav {
  width: var(--nav-width);
  padding: 16px 10px;
  background: var(--color-dai);
  border-right: 1px solid var(--color-line);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: var(--radius-s);
  color: var(--color-ink);
  text-decoration: none;
  border-left: 3px solid transparent;
}

.nav-item:hover {
  background: var(--color-panel);
}

.nav-item.active {
  background: var(--color-panel);
  border-left-color: var(--color-cinnabar);
  color: var(--color-dai-deep);
  font-weight: 600;
}

.nav-hint {
  font-family: var(--font-serif);
  color: var(--color-muted);
  font-size: 13px;
}

.nav-item.active .nav-hint {
  color: var(--color-cinnabar);
}

.app-main {
  flex: 1;
  min-width: 0;
  padding: 24px 28px;
  overflow-y: auto;
}

.app-context {
  width: var(--context-width);
  padding: 16px;
  background: var(--color-panel);
  border-left: 1px solid var(--color-line);
  transition: width 0.2s ease;
}

.app-context.collapsed {
  width: 96px;
}

.context-toggle {
  width: 100%;
  font-size: 13px;
}

.context-body {
  margin-top: 16px;
}

.context-title {
  font-family: var(--font-serif);
  font-weight: 600;
}

.context-empty {
  margin-top: 12px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--color-muted);
  text-align: center;
}

.toast-area {
  position: fixed;
  top: 64px;
  right: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 200;
}

.toast {
  padding: 10px 16px;
  border-radius: var(--radius-m);
  background: var(--color-panel);
  border: 1px solid var(--color-line);
  box-shadow: var(--shadow-soft);
  font-size: 13px;
  cursor: pointer;
  max-width: 320px;
}

.toast-success {
  border-left: 3px solid var(--color-dai-deep);
}

.toast-error {
  border-left: 3px solid var(--color-danger);
}
</style>
