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
 * 导航只剩三页。
 * 「创作工作台」已移除 —— 工作台不再是一个"页面入口"，
 * 而是从左栏工作区点项目/会话直接进去的地方，再放一个导航项就是重复。
 * 「人物档案」「故事设定库」「剧情蓝图」也早已移除：它们在工作台右栏分屏里都能看。
 */
const navItems = [
  { path: '/', label: '创作项目', num: '壹' },
  { path: '/book', label: '成书', num: '貳' },
  { path: '/ai-settings', label: '模型接入', num: '叁' }
]
</script>

<template>
  <div class="app-shell">
    <!-- 背景装饰层：远山、流霭（纯装饰，不参与交互） -->
    <div class="backdrop" aria-hidden="true">
      <svg class="mountains" viewBox="0 0 1440 300" preserveAspectRatio="none">
        <path d="M0 300 L180 190 Q260 130 340 195 L430 260 L560 170 Q650 105 750 185 L860 262 L990 175 Q1090 110 1190 190 L1290 250 L1440 205 L1440 300 Z" fill="rgba(107,67,38,.045)"/>
        <path d="M0 300 L220 240 Q330 195 450 245 L600 215 Q720 185 850 235 L1010 218 Q1160 192 1300 240 L1440 228 L1440 300 Z" fill="rgba(107,67,38,.07)"/>
      </svg>
      <span class="mist mist-1"></span>
      <span class="mist mist-2"></span>
    </div>

    <header class="app-header">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 32 32">
            <rect width="32" height="32" rx="10" fill="var(--color-brown-tint-strong)" />
            <path d="M7 23 L13 12 L18 20 L21.5 15 L26 23 Z" fill="var(--color-brown)" opacity=".85" />
            <circle cx="23.5" cy="9" r="2.6" fill="var(--color-cinnabar)" />
          </svg>
        </span>
        <div class="brand-text">
          <span class="brand-name">墨笺</span>
          <span class="brand-sub">AI 长篇小说创作工作台</span>
        </div>
      </div>

      <span v-if="projectStore.current" class="current-project">
        <span class="cp-dot"></span>
        {{ projectStore.current.name }}
      </span>

      <span class="seal-stamp" aria-hidden="true">墨笺</span>
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
            <span class="nav-label">{{ item.label }}</span>
            <span class="nav-dot"></span>
          </RouterLink>
        </nav>

        <!-- ========== 工作区树 ==========
             项目相当于工作区，展开后是它下面的各段会话。
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
        <button class="context-toggle" @click="contextOpen = !contextOpen">
          {{ contextOpen ? '收起 »' : '« 上下文' }}
        </button>
        <div v-if="contextOpen" class="context-body">
          <p class="context-title">本章上下文</p>
          <hr class="mj-hr-scroll" />
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

/* ==================== 背景装饰 ==================== */

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

/* 两团缓慢漂移的雾：给大面积空白一点"活着"的感觉，比纯色底柔和得多 */
.mist {
  position: absolute;
  width: 560px;
  height: 170px;
  border-radius: 50%;
  filter: blur(64px);
  background: rgba(169, 118, 79, 0.13);
}
.mist-1 { top: 12%; left: -100px; animation: mist-drift 26s ease-in-out infinite alternate; }
.mist-2 { bottom: 14%; right: -120px; animation: mist-drift 34s ease-in-out infinite alternate-reverse; }

@keyframes mist-drift {
  from { transform: translateX(-52px) scale(1); }
  to { transform: translateX(52px) scale(1.08); }
}

.app-header,
.app-body,
.toast-area {
  position: relative;
  z-index: 1;
}

/* ==================== 顶栏 ==================== */

/*
 * 顶栏不再用"1px 硬线"分隔，改成"同色底 + 一层很淡的阴影"。
 * 硬线会在大白底上切出一道很锋利的口子，而这套设计要的是柔。
 */
.app-header {
  position: relative;
  height: 66px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 22px;
  background: var(--color-panel);
  box-shadow: 0 1px 0 rgba(236, 227, 215, 0.9), 0 6px 18px rgba(70, 52, 36, 0.035);
  z-index: 3;
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
}

.brand-mark {
  display: block;
  width: 34px;
  height: 34px;
  flex: none;
  transform: rotate(-3deg);
  transition: transform var(--dur-slow) var(--ease-spring);
}
.brand:hover .brand-mark { transform: rotate(3deg) scale(1.06); }

.brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* 品牌字单独一层字族：毛笔行楷。整页只有这里用，所以它很"值钱" */
.brand-name {
  font-family: var(--font-display);
  font-size: 27px;
  line-height: 1;
  letter-spacing: 0.04em;
  color: var(--color-brown-deep);
}

.brand-sub {
  margin-top: 3px;
  font-size: 11px;
  letter-spacing: 0.16em;
  color: var(--color-muted);
  white-space: nowrap;
}

/* 当前项目：一颗小圆点 + 药丸底，比一块方色块轻 */
.current-project {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  padding: 6px 16px;
  border-radius: var(--radius-pill);
  font-size: 12.5px;
  letter-spacing: 0.04em;
  color: var(--color-ink-soft);
  background: var(--color-paper-warm);
  border: 1px solid var(--color-line-soft);
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cp-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-brown);
  box-shadow: 0 0 0 3px var(--color-brown-tint);
}

/* 印章：圆角加大，投影变柔 —— 原文物感更强 */
.seal-stamp {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 1.05;
  letter-spacing: 0.02em;
  color: #fdf5ea;
  background: linear-gradient(150deg, #c0503c, var(--color-cinnabar));
  border-radius: 11px;
  box-shadow: inset 0 0 0 2px rgba(253, 245, 234, 0.55), 0 4px 14px rgba(179, 68, 51, 0.32);
  transform: rotate(-5deg);
  animation: seal-stamp-in 0.75s 0.3s cubic-bezier(0.3, 1.4, 0.5, 1) both;
}

@keyframes seal-stamp-in {
  0% { opacity: 0; transform: rotate(-14deg) scale(1.85); }
  60% { opacity: 1; transform: rotate(-4deg) scale(0.95); }
  100% { opacity: 1; transform: rotate(-5deg) scale(1); }
}

/* ==================== 主体 ==================== */

.app-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.app-side {
  background: var(--color-dai);
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex-shrink: 0;
  /* 侧栏用内阴影做"凹进去"的层次，而不是画一条硬分隔线 */
  box-shadow: inset -8px 0 16px -12px rgba(70, 52, 36, 0.16);
}

.app-nav {
  padding: 14px 12px 8px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  flex-shrink: 0;
}

/* 导航项做成药丸形：hover 时浮起一点，active 时白底 + 柔和阴影 */
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-radius: var(--radius-pill);
  color: var(--color-ink-soft);
  text-decoration: none;
  transition: background var(--dur) var(--ease-out),
              color var(--dur) var(--ease-out),
              box-shadow var(--dur) var(--ease-out),
              transform var(--dur) var(--ease-spring);
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.55);
  transform: translateX(2px);
}

.nav-item.active {
  background: var(--color-panel);
  color: var(--color-brown-deep);
  box-shadow: var(--shadow-xs);
}

/* 序号用品牌字：小小的一个"壹/貳/叁"，是这页最容易做出文气的地方 */
.nav-num {
  flex: none;
  width: 20px;
  text-align: center;
  font-family: var(--font-display);
  font-size: 17px;
  line-height: 1;
  color: var(--color-brown);
  transition: color var(--dur) var(--ease-out);
}

.nav-item.active .nav-num { color: var(--color-cinnabar); }

.nav-label {
  font-size: 13.5px;
  letter-spacing: 0.04em;
}

/* 选中点：只在 active 时出现，比原来那个一直脉冲的黑点安静 */
.nav-dot {
  margin-left: auto;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--color-brown);
  opacity: 0;
  transform: scale(0);
  transition: opacity var(--dur) var(--ease-out), transform var(--dur) var(--ease-spring);
}
.nav-item.active .nav-dot { opacity: 1; transform: scale(1); }

.app-main {
  flex: 1;
  min-width: 0;
  padding: 26px 30px;
  overflow-y: auto;
}

/* ==================== 右栏 ==================== */

.app-context {
  width: var(--context-width);
  padding: 18px;
  background: var(--color-paper-warm);
  transition: width var(--dur) var(--ease-out);
  overflow: hidden;
}

.app-context.collapsed { width: 108px; }

.context-toggle {
  width: 100%;
  padding: 7px 12px;
  border: 1px solid var(--color-line-soft);
  border-radius: var(--radius-pill);
  background: var(--color-panel);
  color: var(--color-muted);
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.context-toggle:hover { border-color: var(--color-brown-soft); color: var(--color-brown-deep); }

.context-body { margin-top: 18px; }

.context-title {
  font-family: var(--font-serif);
  font-size: 14px;
  font-weight: 600;
  color: var(--color-brown-deep);
}

.mj-hr-scroll { margin: 10px 0 0; }

.context-empty {
  margin-top: 14px;
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--color-muted);
  text-align: center;
}

/* ==================== 提示 ==================== */

.toast-area {
  position: fixed;
  bottom: 30px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column-reverse;
  align-items: center;
  gap: 9px;
  z-index: 2000;
  pointer-events: none;
}

.toast { pointer-events: auto; }

.toast {
  padding: 11px 22px;
  border-radius: var(--radius-pill);
  background: rgba(46, 38, 32, 0.94);
  backdrop-filter: blur(8px);
  color: #f7efe4;
  border: none;
  box-shadow: 0 10px 32px rgba(46, 38, 32, 0.26);
  font-size: 13.5px;
  letter-spacing: 0.03em;
  cursor: pointer;
  max-width: 480px;
  line-height: 1.6;
  animation: toast-in 0.32s var(--ease-spring);
}

@keyframes toast-in {
  from { opacity: 0; transform: translateY(14px) scale(0.95); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

.toast-success { box-shadow: 0 10px 32px rgba(70, 110, 80, 0.3); }
.toast-error { box-shadow: 0 10px 32px rgba(179, 68, 51, 0.34); }

@media (prefers-reduced-motion: reduce) {
  .seal-stamp { animation: none; }
  .mist { animation: none; }
}
</style>
