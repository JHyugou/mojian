<script setup>
import { ref, watch } from 'vue'
import { useProjectStore } from './stores/project'
import { useMessageStore } from './stores/message'
import Resizer from './components/workbench/Resizer.vue'
import WorkspaceTree from './components/WorkspaceTree.vue'

const contextOpen = ref(true)
const projectStore = useProjectStore()
const messageStore = useMessageStore()
const workspaceRef = ref(null)

/** 侧栏宽度可拖拽，并记住 —— 调好一次就不用每次再调 */
const sideWidth = ref(Number(localStorage.getItem('mojian.sideWidth')) || 232)
watch(sideWidth, (value) => localStorage.setItem('mojian.sideWidth', String(value)))

/*
 * 导航只剩四页：工作台、项目、成书、模型接入。
 * 「人物档案」「故事设定库」「剧情蓝图」已移除 —— 它们在工作台右栏分屏里都能看，
 * 两处入口是重复的（删掉的是入口，功能一处没少）。
 */
const navItems = [
  { path: '/workbench', label: '创作工作台', num: '壹' },
  { path: '/', label: '创作项目', num: '貳' },
  { path: '/book', label: '成书', num: '叁' },
  { path: '/ai-settings', label: '模型接入', num: '肆' }
]


// 工作区树已抽成组件：工作区树自己的状态（展开、悬停、菜单、改名）
// 和外壳无关，放在 App.vue 里只会让它越来越难读

</script>

<template>
  <div class="app-shell">
    <!-- 水墨背景装饰层：远山、流霭、竖排诗句（纯装饰，不参与交互） -->
    <div class="backdrop" aria-hidden="true">
      <svg class="mountains" viewBox="0 0 1440 300" preserveAspectRatio="none">
        <path d="M0 300 L180 190 Q260 130 340 195 L430 260 L560 170 Q650 105 750 185 L860 262 L990 175 Q1090 110 1190 190 L1290 250 L1440 205 L1440 300 Z" fill="rgba(60,54,44,.06)"/>
        <path d="M0 300 L220 240 Q330 195 450 245 L600 215 Q720 185 850 235 L1010 218 Q1160 192 1300 240 L1440 228 L1440 300 Z" fill="rgba(60,54,44,.10)"/>
      </svg>
      <span class="mist mist-1"></span>
      <span class="mist mist-2"></span>
      <span class="vertical-verse brush">落笔惊风雨 · 诗成泣鬼神</span>
    </div>
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
      <span class="seal-stamp brush" aria-hidden="true">墨笺</span>
    </header>

    <div class="app-body">
      <aside class="app-side" :style="{ width: sideWidth + 'px' }" aria-label="工作区导航">
        <nav class="app-nav">
          <RouterLink
            v-for="item in navItems"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            active-class="active"
          >
            <span class="nav-num">{{ item.num }}</span>
            <span>{{ item.label }}</span>
          </RouterLink>
        </nav>

        <!-- ========== 工作区树 ==========
             项目相当于工作区，展开后是它下面的各段会话。
             悬停项目会浮现操作按钮；顶部可起草新书或添加工作区。
        -->
        <WorkspaceTree ref="workspaceRef" />
      </aside>

      <!-- 左栏与主区之间：拖拽调整侧栏宽度 -->
      <Resizer v-model="sideWidth" :min="180" :max="420" />

      <main class="app-main">
        <RouterView />
      </main>

      <!-- 工作台自带右栏，外壳的上下文栏就不重复了 -->
      <aside
        v-if="$route.path !== '/workbench'"
        class="app-context"
        :class="{ collapsed: !contextOpen }"
        aria-label="上下文辅助"
      >
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
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
}

/* ===== 水墨背景装饰层 ===== */
.backdrop {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
}
.mountains {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  width: 100%;
  height: 32vh;
}
.mist {
  position: absolute;
  width: 520px;
  height: 150px;
  border-radius: 50%;
  filter: blur(52px);
  background: rgba(92, 86, 72, 0.16);
}
.mist-1 { top: 16%; left: -80px; animation: mist-drift 24s ease-in-out infinite alternate; }
.mist-2 { bottom: 18%; right: -100px; animation: mist-drift 30s ease-in-out infinite alternate-reverse; }
.vertical-verse {
  position: absolute;
  top: 96px;
  right: 18px;
  writing-mode: vertical-rl;
  font-size: 19px;
  letter-spacing: 0.4em;
  color: rgba(43, 38, 32, 0.28);
  animation: verse-float 9s ease-in-out infinite alternate;
}
@keyframes mist-drift {
  from { transform: translateX(-46px); }
  to { transform: translateX(46px); }
}
@keyframes verse-float {
  from { transform: translateY(-7px); }
  to { transform: translateY(7px); }
}

.app-header,
.app-body,
.toast-area {
  position: relative;
  z-index: 1;
}

.app-header {
  height: 58px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: var(--color-panel);
  border-bottom: 1px solid var(--color-line);
}

.current-project {
  font-size: 13px;
  letter-spacing: 0.08em;
  color: var(--color-ink);
  background: rgba(255, 255, 255, 0.42);
  border: 1px solid var(--color-line);
  padding: 5px 14px;
  border-radius: var(--radius-s);
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
  font-family: var(--font-brush);
  font-size: 34px;
  line-height: 1;
  letter-spacing: 3px;
  transform: rotate(-1.5deg);
}

.brand-sub {
  font-size: 12px;
  letter-spacing: 0.22em;
  color: var(--color-muted);
  margin-left: 10px;
}

.seal-stamp {
  flex: none;
  width: 42px;
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  font-size: 16px;
  line-height: 1.05;
  color: #f7efe2;
  background: var(--color-cinnabar);
  border-radius: 3px;
  box-shadow: inset 0 0 0 2px rgba(247, 239, 226, 0.65), 0 3px 12px rgba(163, 63, 48, 0.4);
  transform: rotate(-6deg);
  animation: seal-stamp-in 0.7s 0.35s cubic-bezier(0.3, 1.4, 0.5, 1) both;
}
@keyframes seal-stamp-in {
  0% { opacity: 0; transform: rotate(-12deg) scale(1.9); }
  60% { opacity: 1; transform: rotate(-5deg) scale(0.94); }
  100% { transform: rotate(-6deg) scale(1); }
}

.app-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

/* 侧栏现在装两样东西：导航 + 工作区树。底色统一在这里，两段共用。
   宽度由 Resizer 拖拽控制（内联 style），这里不再写死 */
.app-side {
  background: var(--color-dai);
  border-right: 1px solid var(--color-line);
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex-shrink: 0;
}

.app-nav {
  padding: 16px 10px 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex-shrink: 0;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 10px 12px;
  border-radius: var(--radius-s);
  color: var(--color-ink);
  text-decoration: none;
  border-left: 3px solid transparent;
  transition: transform 0.15s ease, background 0.18s ease;
}

.nav-item:hover {
  background: rgba(43, 38, 32, 0.06);
  transform: translateX(2px);
}

.nav-item.active {
  background: linear-gradient(90deg, rgba(43, 38, 32, 0.12), transparent);
  border-left-color: var(--color-cinnabar);
  color: var(--color-ink);
  font-weight: 700;
}

.nav-item.active::before {
  content: "";
  position: absolute;
  right: 8px;
  top: 50%;
  margin-top: -4px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-ink);
  box-shadow: 0 0 0 2px var(--color-paper), 0 0 0 3px var(--color-ink);
  animation: nav-ink-pulse 2.4s ease infinite;
}
@keyframes nav-ink-pulse {
  0%, 100% { box-shadow: 0 0 0 2px var(--color-paper), 0 0 0 3px var(--color-ink); }
  50% { box-shadow: 0 0 0 2px var(--color-paper), 0 0 0 7px rgba(43, 38, 32, 0); }
}

.nav-num {
  font-family: var(--font-brush);
  font-size: 16px;
  color: var(--color-cinnabar);
  width: 20px;
  text-align: center;
  flex: none;
}

.nav-item.active .nav-num {
  color: var(--color-ink);
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

/* 提示停靠在页面底部居中：避开顶栏、侧边导航与右侧上下文面板的操作区 */
.toast-area {
  position: fixed;
  bottom: 28px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column-reverse;
  align-items: center;
  gap: 8px;
  z-index: 2000;
  pointer-events: none;
}

.toast {
  pointer-events: auto;
}

.toast {
  padding: 10px 20px;
  border-radius: var(--radius-s);
  background: rgba(33, 29, 24, 0.93);
  border: none;
  color: #efe8d6;
  box-shadow: 4px 4px 0 rgba(163, 63, 48, 0.75);
  font-size: 13.5px;
  letter-spacing: 0.06em;
  cursor: pointer;
  max-width: 460px;
  line-height: 1.6;
  animation: toast-in 0.22s ease-out;
}

@keyframes toast-in {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.toast-success {
  border-left: 3px solid #55795c;
}

.toast-error {
  border-left: 3px solid var(--color-danger);
}
</style>
