<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useProjectStore } from '../stores/project'
import { listProjects, updateProject } from '../api/project'
import { listConversations, deleteConversation } from '../api/agent'
import Icon from './Icon.vue'

/**
 * 工作区树（项目 → 多会话）。
 *
 * 交互照 DSH：
 *   - 鼠标移到项目上，右侧浮现「更多」与「新建对话」两个气泡按钮
 *   - 「更多」弹出菜单：修改项目内容 / 删除工作区
 *   - 顶部两个按钮：起草新书（无工作区的会话）、新建工作区（选一个项目打开）
 */
const projectStore = useProjectStore()
const route = useRoute()
const router = useRouter()

const projects = ref([])
const conversations = ref([])
const expanded = ref(new Set())
const hovered = ref(null)
const menuFor = ref(null)
const pickerOpen = ref(false)
const renameFor = ref(null)
const renameText = ref('')

/**
 * 被"关闭"的工作区。
 *
 * "删除工作区"删的是树里的这一项，<b>不是删项目</b> —— 人物、设定、蓝图、
 * 正文一个字都不会动。所以它只是一份本地的显示偏好，不该去动数据库。
 */
const HIDDEN_KEY = 'mojian.hiddenWorkspaces'
const hidden = ref(new Set(JSON.parse(localStorage.getItem(HIDDEN_KEY) || '[]').map(String)))

const visibleProjects = computed(() =>
  projects.value.filter((p) => !hidden.value.has(String(p.id)))
)

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

// ==================== 项目 ====================

/** 点项目名：切到这本书并展开它的会话；再点一次收起 */
function openProject(project) {
  const wasCurrent = String(project.id) === String(projectStore.currentId)
  const wasExpanded = isExpanded(project.id)
  projectStore.select(project)
  if (wasCurrent && wasExpanded) {
    toggleProject(project.id)
  } else {
    expanded.value = new Set([...expanded.value, project.id])
  }
  router.push({ path: '/workbench', query: {} })
}

function toggleMenu(project) {
  menuFor.value = menuFor.value === project.id ? null : project.id
}

function startRename(project) {
  menuFor.value = null
  renameFor.value = project.id
  renameText.value = project.name
}

async function submitRename(project) {
  const name = renameText.value.trim()
  renameFor.value = null
  if (!name || name === project.name) return
  try {
    await updateProject(project.id, { ...project, name })
    project.name = name
    if (String(projectStore.currentId) === String(project.id)) {
      projectStore.select({ ...project, name })
    }
  } catch (e) {
    window.alert(e.message || '改名失败')
  }
}

/**
 * 关闭工作区：把它从树里收起来。
 *
 * <b>不删任何数据</b>——项目本身、它的人物设定蓝图正文全都原样保留，
 * 之后从「新建工作区」里随时能把它加回来。
 */
function closeWorkspace(project) {
  menuFor.value = null
  const ok = window.confirm(
    `把「${project.name}」从工作区里移除？\n\n` +
    '项目本身与里面的内容（人物、设定、蓝图、正文）都会完整保留，\n' +
    '之后可以从「新建工作区」重新加回来。'
  )
  if (!ok) return
  hidden.value = new Set([...hidden.value, String(project.id)])
  localStorage.setItem(HIDDEN_KEY, JSON.stringify([...hidden.value]))
}

/** 新建工作区：从项目里挑一个加回来 */
function openWorkspace(project) {
  pickerOpen.value = false
  const next = new Set(hidden.value)
  next.delete(String(project.id))
  hidden.value = next
  localStorage.setItem(HIDDEN_KEY, JSON.stringify([...next]))
  expanded.value = new Set([...expanded.value, project.id])
  projectStore.select(project)
  router.push({ path: '/workbench', query: {} })
}

// ==================== 会话 ====================

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

function newConversationIn(project) {
  projectStore.select(project)
  expanded.value = new Set([...expanded.value, project.id])
  router.push({ path: '/workbench', query: { mode: 'planner', fresh: Date.now() } })
}

/**
 * 起草新书：一段不属于任何项目的会话。
 *
 * <p><b>暂未开放</b>。它需要一套新的后端能力：会话没有项目就不该去查材料，
 * 还需要一个 create_project 工具让 Agent 真的把项目建出来。
 * 这两样都还没有，所以按钮先不放上来 —— 放一个点了没反应的按钮比不放更糟。
 */
function startDrafting() {
  router.push({ path: '/workbench', query: { mode: 'planner', draft: Date.now() } })
}

async function removeConversation(conversation) {
  const ok = window.confirm(
    `删除这段对话？\n\n${conversation.title}\n\n` +
    '对话记录会被清掉；人物、设定、蓝图、正文都不受影响。'
  )
  if (!ok) return
  try {
    await deleteConversation(conversation.id)
    conversations.value = conversations.value.filter((c) => String(c.id) !== String(conversation.id))
    if (String(route.query.conv) === String(conversation.id)) {
      router.push({ path: '/workbench', query: { mode: 'planner', fresh: Date.now() } })
    }
  } catch (e) {
    window.alert(e.message || '删除失败')
  }
}

// ==================== 加载 ====================

/** 供外部（工作台）在新建项目后刷新列表 */
async function reload() {
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
  if (!expanded.value.size) {
    const first = projectStore.currentId || projects.value[0]?.id
    if (first) expanded.value = new Set([first])
  }
}

defineExpose({ reload })

function closeAll() {
  menuFor.value = null
  pickerOpen.value = false
}

function onKeydown(event) {
  if (event.key === 'Escape') closeAll()
}

onMounted(() => {
  reload()
  // 点别处关掉浮层；菜单留在屏幕上不走是最烦人的
  document.addEventListener('click', closeAll)
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', closeAll)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <section class="ws" @click.stop>
    <header class="ws-head">
      <span class="ws-title">工作区</span>
      <div class="ws-head-actions">
        <!-- 新建工作区：从项目里挑一个打开 -->
        <button
          class="ws-bubble"
          :class="{ on: pickerOpen }"
          title="新建工作区（选择一个项目）"
          @click="pickerOpen = !pickerOpen; menuFor = null"
        >
          <Icon name="folder" :size="15" />
        </button>
      </div>
    </header>

    <!-- 项目选择器 -->
    <div v-if="pickerOpen" class="ws-picker mj-fade">
      <div class="ws-picker-title">选择一个项目作为工作区</div>
      <button
        v-for="p in projects"
        :key="p.id"
        class="ws-picker-item"
        :class="{ used: !hidden.has(String(p.id)) }"
        @click="openWorkspace(p)"
      >
        <Icon name="folder" :size="14" />
        <span class="ws-picker-name">{{ p.name }}</span>
        <span v-if="!hidden.has(String(p.id))" class="ws-picker-hint">已在工作区</span>
      </button>
      <p v-if="!projects.length" class="ws-empty">
        还没有项目。<br />用上面的 ✦ 起草一本新书。
      </p>
    </div>

    <div class="ws-list">
      <div v-for="p in visibleProjects" :key="p.id" class="ws-project">
        <!-- 悬停浮现操作。menuFor 也让它保持显示，否则鼠标一移到菜单上就消失了 -->
        <div
          class="ws-project-row"
          @mouseenter="hovered = p.id"
          @mouseleave="hovered = null"
        >
          <button
            v-if="renameFor !== p.id"
            class="ws-project-head"
            :class="{ active: String(p.id) === String(projectStore.currentId) }"
            @click="openProject(p)"
          >
            <Icon
              name="caret"
              :size="12"
              class="ws-caret-icon"
              :class="{ open: isExpanded(p.id) }"
            />
            <span class="ws-project-name">{{ p.name }}</span>
          </button>

          <!-- 就地改名：改项目名不需要跳到另一个页面 -->
          <input
            v-else
            v-model="renameText"
            class="ws-rename"
            @keydown.enter="submitRename(p)"
            @keydown.esc="renameFor = null"
            @blur="submitRename(p)"
          />

          <div
            class="ws-actions"
            :class="{ show: hovered === p.id || menuFor === p.id }"
          >
            <button class="ws-bubble" title="更多" @click.stop="toggleMenu(p)">
              <Icon name="dots" :size="15" />
            </button>
            <button class="ws-bubble" title="新建对话" @click.stop="newConversationIn(p)">
              <Icon name="plus" :size="15" />
            </button>
          </div>

          <div v-if="menuFor === p.id" class="ws-menu mj-fade" @click.stop>
            <button class="ws-menu-item" @click="startRename(p)">
              <Icon name="edit" :size="14" />
              <span>修改项目内容</span>
            </button>
            <button class="ws-menu-item is-danger" @click="closeWorkspace(p)">
              <Icon name="trash" :size="14" />
              <span>删除工作区</span>
            </button>
          </div>
        </div>

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
            <span class="ws-conv-del" title="删除这段对话" @click.stop="removeConversation(c)">
              <Icon name="trash" :size="13" />
            </span>
          </button>
          <p v-if="!conversationsOf(p.id).length" class="ws-empty">还没有对话</p>
        </div>
      </div>

      <p v-if="!visibleProjects.length" class="ws-empty">
        工作区是空的。<br />用上面的文件夹图标添加一个。
      </p>
    </div>
  </section>
</template>

<style scoped>
.ws {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--color-line);
  padding: 10px 8px 12px;
  position: relative;
}

.ws-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px 4px 8px 6px;
  flex-shrink: 0;
}

.ws-title {
  font-family: var(--font-serif);
  font-size: 12px;
  letter-spacing: 0.12em;
  color: var(--color-muted);
}

.ws-head-actions { display: flex; gap: 2px; }

/* ---------- 气泡图标按钮（替代原生按钮）----------
   平时完全隐形，只在悬停时浮出一个圆形底色。
   这样一排图标不会把侧栏切碎，需要时又立刻找得到。 */
.ws-bubble {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--color-muted);
  cursor: pointer;
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.ws-bubble:hover { background: rgba(255, 255, 255, 0.85); color: var(--color-brown-deep); }
.ws-bubble.on { background: var(--color-panel); color: var(--color-brown-deep); }

/* ---------- 项目选择器 ---------- */
.ws-picker {
  margin: 0 4px 8px;
  padding: 6px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-m);
  background: var(--color-panel);
  box-shadow: var(--shadow-soft);
}

.ws-picker-title {
  padding: 4px 8px 8px;
  font-size: 11px;
  color: var(--color-muted);
  letter-spacing: 0.06em;
}

.ws-picker-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
  color: var(--color-ink);
}
.ws-picker-item:hover { background: var(--color-paper-warm); }
.ws-picker-item.used { color: var(--color-muted); }

.ws-picker-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ws-picker-hint { font-size: 10px; color: var(--color-muted); }

/* ---------- 项目行 ---------- */
.ws-list { flex: 1; min-height: 0; overflow-y: auto; }

.ws-project { margin-bottom: 2px; }

.ws-project-row {
  position: relative;
  display: flex;
  align-items: center;
  border-radius: var(--radius-s);
  transition: background var(--dur) var(--ease-out);
}
.ws-project-row:hover { background: rgba(255, 255, 255, 0.55); }

.ws-project-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  padding: 7px 4px 7px 6px;
  border: 0;
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
  color: var(--color-ink);
}

.ws-caret-icon {
  color: var(--color-muted);
  transition: transform var(--dur) var(--ease-out);
}
.ws-caret-icon.open { transform: rotate(90deg); }

.ws-project-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-serif);
}
.ws-project-head.active .ws-project-name { color: var(--color-brown-deep); }

.ws-count { font-size: 11px; color: var(--color-muted); padding-right: 2px; }

.ws-rename {
  flex: 1;
  min-width: 0;
  margin: 3px 4px;
  padding: 4px 6px;
  border: 1px solid var(--color-brown);
  border-radius: var(--radius-s);
  background: var(--color-panel);
  font-family: var(--font-serif);
  font-size: 13px;
  color: var(--color-ink);
  outline: none;
}

/* 悬停才出现；宽度变化用 opacity+width，避免整行抖动 */
.ws-actions {
  display: flex;
  gap: 1px;
  padding-right: 4px;
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--dur) var(--ease-out);
}
.ws-actions.show { opacity: 1; pointer-events: auto; }

/* ---------- 菜单 ---------- */
.ws-menu {
  position: absolute;
  top: calc(100% - 2px);
  right: 6px;
  z-index: 90;
  min-width: 150px;
  padding: 4px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-m);
  background: var(--color-panel);
  box-shadow: var(--shadow-float);
}

.ws-menu-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 7px 9px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
  color: var(--color-ink);
}
.ws-menu-item:hover { background: var(--color-paper-warm); }
.ws-menu-item.is-danger { color: var(--color-danger); }
.ws-menu-item.is-danger:hover { background: rgba(166, 43, 31, 0.06); }

/* ---------- 会话 ---------- */
.ws-convs { padding-left: 14px; }

.ws-conv {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 5px 6px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 12px;
  color: var(--color-muted);
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.ws-conv:hover { background: rgba(255, 255, 255, 0.55); }
.ws-conv.active { background: var(--color-panel); color: var(--color-brown-deep); }

/* 「策」「文」一个字区分角色：比图标省地方，也一眼看得出是哪种对话 */
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

.ws-conv-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ws-conv-time { flex-shrink: 0; font-size: 11px; opacity: 0.75; }

.ws-conv-del {
  flex-shrink: 0;
  display: inline-flex;
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
  line-height: 1.8;
}
</style>
