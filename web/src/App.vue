<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useProjectStore } from './stores/project'
import { useMessageStore } from './stores/message'
import { listProjects } from './api/project'
import { listConversations, deleteConversation } from './api/agent'

const contextOpen = ref(true)
const projectStore = useProjectStore()
const messageStore = useMessageStore()
const route = useRoute()
const router = useRouter()

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

// ==================== 工作区树（项目 → 会话） ====================
/*
 * 结构照 DSH：项目相当于工作区，一个工作区下面可以有多段会话，
 * 折叠展开、点击切换到对应会话。
 */
const projects = ref([])
const conversations = ref([])
const expanded = ref(new Set())

const conversationsOf = (projectId) =>
  conversations.value.filter((c) => String(c.projectId) === String(projectId))

const isExpanded = (projectId) => expanded.value.has(projectId)

function toggleProject(projectId) {
  const next = new Set(expanded.value)
  if (next.has(projectId)) {
    next.delete(projectId)
  } else {
    next.add(projectId)
  }
  expanded.value = next
}

/**
 * 删除一段对话。
 *
 * 只删对话记录，人物、设定、蓝图、正文一律不动 ——
 * 作者想清掉的通常是"这条思路走歪了"，不是"把这些成果也烧了"。
 */
async function removeConversation(conversation) {
  const ok = window.confirm(
    `删除这段对话？\n\n${conversation.title}\n\n` +
    '对话记录会被清掉；人物、设定、蓝图、正文都不受影响。'
  )
  if (!ok) return
  try {
    await deleteConversation(conversation.id)
    conversations.value = conversations.value.filter((c) => String(c.id) !== String(conversation.id))
    // 删掉的正是当前打开的那段：换到一段新的，别停在已经不存在的会话上
    if (String(route.query.conv) === String(conversation.id)) {
      router.push({ path: '/workbench', query: { mode: 'planner', fresh: Date.now() } })
    }
  } catch (e) {
    window.alert(e.message || '删除失败')
  }
}

/** 点项目名：切到这本书并展开它的会话；再点一次收起 */function openProject(project) {
  const wasCurrent = String(project.id) === String(projectStore.currentId)
  const wasExpanded = isExpanded(project.id)
  projectStore.select(project)
  if (wasCurrent && wasExpanded) {
    toggleProject(project.id)
  } else {
    expanded.value = new Set([...expanded.value, project.id])
  }
  // 清掉会话参数：让工作台载入这本书最近的那段对话
  router.push({ path: '/workbench', query: {} })
}

/** 相对时间：会话列表里"多久以前"比绝对时间有用 */
function relativeTime(value) {
  if (!value) return ''
  const then = new Date(String(value).replace(' ', 'T')).getTime()
  if (Number.isNaN(then)) return ''
  const minutes = Math.floor((Date.now() - then) / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}小时`
  return `${Math.floor(hours / 24)}天`
}

/** 打开某段会话：切到它所属的项目，再由工作台按 query 载入 */
function openConversation(project, conversation) {
  projectStore.select(project)
  router.push({
    path: '/workbench',
    query: {
      conv: conversation.id,
      mode: conversation.role === 'WRITER' ? 'writer' : 'planner'
    }
  })
}

/** 在当前项目里新开一段对话。真正落库发生在发出第一条消息时 */
function newConversation() {
  const current = projectStore.current || projects.value[0]
  if (!current) return
  projectStore.select(current)
  router.push({ path: '/workbench', query: { mode: 'planner', fresh: Date.now() } })
}

async function loadWorkspace() {
  const [projectResult, conversationResult] = await Promise.allSettled([
    listProjects(),
    listConversations()
  ])
  if (projectResult.status === 'fulfilled') {
    projects.value = projectResult.value || []
  }
  if (conversationResult.status === 'fulfilled') {
    conversations.value = conversationResult.value || []
  }
  // 默认展开当前项目：进来就能看见它的会话
  if (projectStore.currentId) {
    expanded.value = new Set([projectStore.currentId])
  } else if (projects.value.length) {
    expanded.value = new Set([projects.value[0].id])
  }
}

onMounted(loadWorkspace)
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
      <aside class="app-side" aria-label="工作区导航">
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
             结构照 DSH：项目相当于工作区，展开后是它下面的各段会话，
             点击即切到那一段。下面的空白区域以前是浪费的，现在放它。
        -->
        <section class="ws">
          <header class="ws-head">
            <span class="ws-title">工作区</span>
            <button class="ws-new" title="在当前项目里新建对话" @click="newConversation">＋</button>
          </header>

          <div class="ws-list">
            <div v-for="p in projects" :key="p.id" class="ws-project">
              <button
                class="ws-project-head"
                :class="{ active: String(p.id) === String(projectStore.currentId) }"
                @click="openProject(p)"
              >
                <span class="ws-caret">{{ isExpanded(p.id) ? '▾' : '▸' }}</span>
                <span class="ws-project-name">{{ p.name }}</span>
                <span class="ws-count">{{ conversationsOf(p.id).length }}</span>
              </button>

              <div v-if="isExpanded(p.id)" class="ws-convs">
                <button
                  v-for="c in conversationsOf(p.id)"
                  :key="c.id"
                  class="ws-conv"
                  :class="{ active: String(route.query.conv) === String(c.id) }"
                  @click="openConversation(p, c)"
                >
                  <span class="ws-conv-mark">{{ c.role === 'WRITER' ? '文' : '策' }}</span>
                  <span class="ws-conv-title">{{ c.title }}</span>
                  <span class="ws-conv-time">{{ relativeTime(c.updatedAt) }}</span>
                  <span
                    class="ws-conv-del"
                    title="删除这段对话"
                    @click.stop="removeConversation(c)"
                  >×</span>
                </button>
                <p v-if="!conversationsOf(p.id).length" class="ws-empty">
                  还没有对话，点右上角 ＋ 开始
                </p>
              </div>
            </div>

            <p v-if="!projects.length" class="ws-empty">还没有项目</p>
          </div>
        </section>
      </aside>

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

/* 侧栏现在装两样东西：导航 + 工作区树。宽度与底色统一在这里，两段共用 */
.app-side {
  width: var(--nav-width);
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

/* ---------- 工作区树（项目 → 会话） ---------- */
.ws {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--color-line);
  padding: 10px 8px 12px;
}

.ws-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 6px 8px;
  flex-shrink: 0;
}

.ws-title {
  font-family: var(--font-serif);
  font-size: 12px;
  letter-spacing: 0.12em;
  color: var(--color-muted);
}

.ws-new {
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  background: var(--color-panel);
  color: var(--color-brown-deep);
  cursor: pointer;
  font-size: 12px;
  line-height: 1;
  transition: border-color var(--dur) var(--ease-out);
}
.ws-new:hover { border-color: var(--color-brown); }

.ws-list { flex: 1; min-height: 0; overflow-y: auto; }

.ws-project { margin-bottom: 2px; }

.ws-project-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 7px 8px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
  color: var(--color-ink);
  transition: background var(--dur) var(--ease-out);
}
.ws-project-head:hover { background: rgba(255, 255, 255, 0.5); }
.ws-project-head.active { color: var(--color-brown-deep); }

.ws-caret { width: 10px; font-size: 10px; color: var(--color-muted); }

.ws-project-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-serif);
}

.ws-count { font-size: 11px; color: var(--color-muted); }

.ws-convs { padding-left: 12px; }

.ws-conv {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 5px 8px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 12px;
  color: var(--color-muted);
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.ws-conv:hover { background: rgba(255, 255, 255, 0.5); }
.ws-conv.active {
  background: var(--color-panel);
  color: var(--color-brown-deep);
}

/* 「策」「文」一个字区分角色：比图标省地方，也一眼看得出这是哪种对话 */
.ws-conv-mark {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-line);
  border-radius: 2px;
  font-size: 10px;
  font-family: var(--font-serif);
  background: var(--color-panel);
}
.ws-conv.active .ws-conv-mark {
  border-color: var(--color-brown);
  color: var(--color-brown-deep);
}

.ws-conv-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ws-conv-time { flex-shrink: 0; font-size: 11px; opacity: 0.75; }

/* 删除按钮只在悬停时出现：平时不干扰阅读，需要时就在手边 */
.ws-conv-del {
  flex-shrink: 0;
  width: 14px;
  text-align: center;
  font-size: 13px;
  line-height: 1;
  opacity: 0;
  color: var(--color-muted);
  transition: opacity var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.ws-conv:hover .ws-conv-del { opacity: 1; }
.ws-conv-del:hover { color: var(--color-danger); }

.ws-empty {
  padding: 6px 10px;
  font-size: 11px;
  color: var(--color-muted);
  line-height: 1.7;
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
