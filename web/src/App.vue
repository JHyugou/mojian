<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useProjectStore } from './stores/project'
import { useMessageStore } from './stores/message'
import Resizer from './components/workbench/Resizer.vue'
import WorkspaceTree from './components/WorkspaceTree.vue'

const route = useRoute()
/**
 * v3 全屏页面（首页、设计定稿页）自带画布与导航，不再套旧外壳。
 * 其余页面在迁移完成前继续使用旧外壳。
 */
const isV3Route = computed(() =>
  ['/', '/ui-preview', '/workbench'].includes(route.path)
)

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
  { path: '/ai-settings', label: '模型接入', num: '叁' },
  { path: '/settings', label: '设置', num: '肆' }
]
</script>

<template>
  <!-- v3 页面是全屏画布：首页/设计定稿页自己管导航，不套旧外壳 -->
  <RouterView v-if="isV3Route" />
  <div v-else class="app-shell">
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
            <rect width="32" height="32" rx="10" fill="var(--tint-strong)" />
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
/* ==================================================================== */
/* 外壳：素笺流韵 v2                                                    */
/* 扁平 + 大圆角 + 磨砂 + 大量微动效                                    */
/* ==================================================================== */

.app-shell {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

/* ==================== 背景装饰 ==================== */
/* 极淡的远山 + 两团游走的暖光。扁平设计里，氛围靠"色"不靠"影" */
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
  height: 34vh;
  opacity: 0.55;
}

.mist {
  position: absolute;
  width: 620px;
  height: 200px;
  border-radius: 50%;
  filter: blur(72px);
  background: radial-gradient(circle, rgba(162, 101, 60, 0.16), transparent 68%);
}
.mist-1 { top: 8%; left: -140px; animation: mist-drift 28s ease-in-out infinite alternate; }
.mist-2 { bottom: 10%; right: -160px; animation: mist-drift 36s ease-in-out infinite alternate-reverse; }

@keyframes mist-drift {
  from { transform: translate3d(-60px, 0, 0) scale(1); }
  to   { transform: translate3d(60px, -18px, 0) scale(1.12); }
}

.app-header,
.app-body,
.toast-area { position: relative; z-index: 1; }

/* ==================== 顶栏 ==================== */
/*
 * 磨砂玻璃 + 一条极淡的底边。
 * 不用实底也不用重阴影 —— 让下面的背景隐约透上来，是扁平风里最"贵"的做法。
 */
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  height: 68px;
  padding: 0 24px 0 22px;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(18px) saturate(1.5);
  -webkit-backdrop-filter: blur(18px) saturate(1.5);
  box-shadow: 0 1px 0 var(--color-line-soft);
  flex-shrink: 0;
  z-index: 20;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.brand-mark {
  display: block;
  width: 38px;
  height: 38px;
  flex: none;
  transform: rotate(-4deg);
  transition: transform var(--dur-slow) var(--ease-spring);
}
.brand:hover .brand-mark { transform: rotate(5deg) scale(1.1); }

.brand-text { display: flex; flex-direction: column; min-width: 0; }

/* 品牌字：整页唯一的毛笔行楷，所以它很"值钱" */
.brand-name {
  font-family: var(--font-display);
  font-size: 28px;
  line-height: 1;
  letter-spacing: 0.04em;
  color: var(--color-brown-deep);
  transition: color var(--dur) var(--ease-out);
}
.brand:hover .brand-name { color: var(--color-cinnabar); }

.brand-sub {
  margin-top: 4px;
  font-size: 10.5px;
  letter-spacing: 0.18em;
  color: var(--color-muted);
  white-space: nowrap;
}

/* 当前项目：一颗呼吸的小圆点 + 药丸底 */
.current-project {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  margin-left: auto;
  padding: 7px 17px;
  border-radius: var(--radius-pill);
  font-size: 12.5px;
  letter-spacing: 0.03em;
  color: var(--color-ink-soft);
  background: var(--color-paper-warm);
  max-width: 340px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: background var(--dur) var(--ease-out);
}
.current-project:hover { background: var(--tint-strong); }

.cp-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-brown);
  animation: mj-breathe 2.6s ease-in-out infinite;
}

/* 印章：大圆角 + 柔和投影 + 盖下去的回弹 */
.seal-stamp {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 1.05;
  letter-spacing: 0.02em;
  color: #fff8ee;
  background: linear-gradient(145deg, #d16a55, var(--color-cinnabar));
  border-radius: 13px;
  box-shadow: inset 0 0 0 2px rgba(255, 248, 238, 0.5),
              0 6px 18px -6px rgba(196, 85, 63, 0.6);
  transform: rotate(-5deg);
  animation: seal-stamp-in 0.85s 0.35s cubic-bezier(0.3, 1.5, 0.5, 1) both;
  transition: transform var(--dur-slow) var(--ease-spring);
}
.seal-stamp:hover { transform: rotate(4deg) scale(1.06); }

@keyframes seal-stamp-in {
  0%   { opacity: 0; transform: rotate(-16deg) scale(2); }
  55%  { opacity: 1; transform: rotate(-3deg) scale(0.93); }
  100% { opacity: 1; transform: rotate(-5deg) scale(1); }
}

/* ==================== 主体 ==================== */

.app-body {
  flex: 1;
  display: flex;
  min-height: 0;
  padding: 12px 12px 12px 0;
  gap: 12px;
}

/* 侧栏：一块凹陷的色块，四角全圆，与主区之间留缝 */
.app-side {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex-shrink: 0;
  margin-left: 12px;
  border-radius: var(--radius-l);
  background: var(--color-dai);
  overflow: hidden;
}

.app-nav {
  padding: 14px 12px 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex-shrink: 0;
}

/* 导航项：药丸形。hover 只染一层雾，不位移（位移留给卡片） */
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 15px;
  border-radius: var(--radius-pill);
  color: var(--color-ink-soft);
  text-decoration: none;
  transition: background var(--dur) var(--ease-out),
              color var(--dur) var(--ease-out);
}

.nav-item:hover { background: var(--tint-white); }

.nav-item.active {
  background: var(--color-panel);
  color: var(--color-brown-deep);
}

/* 序号：品牌字。这是这排导航最容易做出文气的地方 */
.nav-num {
  flex: none;
  width: 20px;
  text-align: center;
  font-family: var(--font-display);
  font-size: 18px;
  line-height: 1;
  color: var(--color-brown);
  transition: color var(--dur) var(--ease-out), transform var(--dur) var(--ease-spring);
}
.nav-item:hover .nav-num { transform: scale(1.12); }
.nav-item.active .nav-num { color: var(--color-cinnabar); }

.nav-label { font-size: 13.5px; letter-spacing: 0.03em; }

/* 选中点：active 时才出现，安静地亮一下 */
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

/* 主内容区：一块纯白圆角板，与侧栏同高 */
.app-main {
  flex: 1;
  min-width: 0;
  border-radius: var(--radius-l);
  background: var(--color-panel);
  overflow-y: auto;
  overflow-x: hidden;
}

/* ==================== 右栏 ==================== */

.app-context {
  width: var(--context-width);
  flex-shrink: 0;
  padding: 18px;
  border-radius: var(--radius-l);
  background: var(--color-paper-warm);
  transition: width var(--dur) var(--ease-out);
  overflow: hidden;
}

.app-context.collapsed { width: 112px; }

.context-toggle {
  width: 100%;
  padding: 8px 14px;
  border: 0;
  border-radius: var(--radius-pill);
  background: var(--color-panel);
  color: var(--color-muted);
  font-size: 12.5px;
  cursor: pointer;
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.context-toggle:hover { color: var(--color-brown-deep); background: var(--tint-strong); }

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
  line-height: 1.95;
  color: var(--color-muted);
  text-align: center;
}

/* ==================== 提示 ==================== */

.toast-area {
  position: fixed;
  bottom: 32px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column-reverse;
  align-items: center;
  gap: 10px;
  z-index: 2000;
  pointer-events: none;
}

.toast { pointer-events: auto; }

/* 药丸 + 毛玻璃：轻，但有分量 */
.toast {
  padding: 12px 24px;
  border-radius: var(--radius-pill);
  background: rgba(36, 30, 24, 0.9);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  color: #f8f1e6;
  box-shadow: var(--shadow-mid);
  font-size: 13.5px;
  letter-spacing: 0.02em;
  cursor: pointer;
  max-width: 500px;
  line-height: 1.6;
  animation: toast-in 0.4s var(--ease-spring);
  transition: transform var(--dur) var(--ease-spring);
}
.toast:hover { transform: scale(1.02); }

@keyframes toast-in {
  from { opacity: 0; transform: translateY(18px) scale(0.9); }
  to   { opacity: 1; transform: translateY(0) scale(1); }
}

.toast-success { box-shadow: 0 12px 36px -10px rgba(78, 154, 99, 0.5); }
.toast-error   { box-shadow: 0 12px 36px -10px rgba(196, 85, 63, 0.55); }

@media (prefers-reduced-motion: reduce) {
  .seal-stamp, .mist, .cp-dot { animation: none; }
}
</style>
