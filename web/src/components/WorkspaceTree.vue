<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useProjectStore } from '../stores/project'
import { listProjects, updateProject } from '../api/project'
import { listConversations, deleteConversation, renameConversation } from '../api/agent'
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

/** 会话的右键菜单与就地改名 */
const convMenu = ref(null)
const renamingConv = ref(null)
const convTitle = ref('')

function onConvMenu(event, conversation) {
  event.preventDefault()
  event.stopPropagation()
  // 同时只留一个浮层，否则菜单会叠在一起
  menuFor.value = null
  pickerOpen.value = false
  convMenu.value = {
    x: Math.min(event.clientX, window.innerWidth - 180),
    y: Math.min(event.clientY, window.innerHeight - 110),
    conversation
  }
}

function startConvRename() {
  const conversation = convMenu.value?.conversation
  convMenu.value = null
  if (!conversation) return
  renamingConv.value = conversation.id
  convTitle.value = conversation.title
}

async function submitConvRename(conversation) {
  renamingConv.value = null
  const title = convTitle.value.trim()
  if (!title || title === conversation.title) return
  try {
    const updated = await renameConversation(conversation.id, title)
    conversation.title = updated.title
    // 重建数组触发更新：改的是对象内部字段，Vue 靠引用变化才知道要重渲染
    conversations.value = [...conversations.value]
  } catch (e) {
    window.alert(e.message || '改名失败')
  }
}

function removeFromMenu() {
  const conversation = convMenu.value?.conversation
  convMenu.value = null
  if (conversation) removeConversation(conversation)
}

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
  pickerOpen.value = false
  convMenu.value = null
  menuFor.value = menuFor.value === project.id ? null : project.id
}

function togglePicker() {
  menuFor.value = null
  convMenu.value = null
  pickerOpen.value = !pickerOpen.value
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
  convMenu.value = null
}

/**
 * 点别处关掉浮层。
 *
 * 用 mousedown 而不是 click：click 要等鼠标松开，菜单会"多活"一小段时间；
 * 而且如果按下和松开之间鼠标移到了别处，click 可能落在不相干的元素上。
 *
 * <b>代价是必须显式排除掉所有"浮层内部"</b>。
 * 这里踩过一个很隐蔽的坑：选择工作区的面板没被排除，于是——
 *   按下鼠标 → 关闭面板 → Vue 重渲染把面板（连同你点的那个按钮）从 DOM 里删掉
 *   → 松开鼠标 → click 事件要派发给一个已经不存在的元素 → 点击落空
 * 表现就是"点项目没反应、加不回工作区"。
 * 所以浮层统一带 .ws-pop 标记，按标记排除，而不是一个个列类名。
 */
const POP_SELECTOR = '.ws-pop, .ws-bubble'

function onPointerDown(event) {
  const target = event.target
  if (!(target instanceof Element)) {
    closeAll()
    return
  }
  // 点在浮层或触发按钮里：不关。关了就点不着了
  if (target.closest(POP_SELECTOR)) return
  closeAll()
}

function onKeydown(event) {
  if (event.key === 'Escape') closeAll()
}

onMounted(() => {
  reload()
  // 捕获阶段监听：即使中途有元素 stopPropagation，关闭依然生效
  document.addEventListener('mousedown', onPointerDown, true)
  document.addEventListener('keydown', onKeydown)
})

/*
 * 跟着路由刷新。
 *
 * 有些变化是"外面"发生的，树自己看不见：
 *   - 在项目页新建了项目
 *   - 新开一段对话并发出第一条消息（会说落库，会话 id 才写进 URL）
 * 这些都会改变 fullPath，所以监听它是最省心也最可靠的做法 ——
 * 比在每个可能改数据的地方都记得喊一声 reload 靠谱得多。
 */
watch(() => route.fullPath, () => reload())

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onPointerDown, true)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <!-- 曾经这里有个 @click.stop，把所有点击都拦在侧栏里，
       导致 document 上的"点别处关闭"永远收不到事件。已移除：
       需要排除的地方由 onPointerDown 精确判断，而不是一刀切地拦住全部 -->
  <section class="ws">
    <header class="ws-head">
      <span class="ws-title">工作区</span>
      <div class="ws-head-actions">
        <!-- 新建工作区：从项目里挑一个打开 -->
        <button
          class="ws-bubble"
          :class="{ on: pickerOpen }"
          title="新建工作区（选择一个项目）"
          @click.stop="togglePicker"
        >
          <Icon name="folder" :size="15" />
        </button>
      </div>
    </header>

    <!-- 项目选择器 -->
    <div v-if="pickerOpen" class="ws-picker ws-pop mj-fade">
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

          <div v-if="menuFor === p.id" class="ws-menu ws-pop mj-fade" @click.stop>
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
            @contextmenu="onConvMenu($event, c)"
          >
            <span class="ws-conv-mark">{{ c.role === 'WRITER' ? '文' : '策' }}</span>

            <!-- 就地改名：改个名字不该跳到别处 -->
            <input
              v-if="renamingConv === c.id"
              v-model="convTitle"
              class="ws-conv-rename"
              @click.stop
              @keydown.enter="submitConvRename(c)"
              @keydown.esc="renamingConv = null"
              @blur="submitConvRename(c)"
            />
            <template v-else>
              <span class="ws-conv-title">{{ c.title }}</span>
              <span class="ws-conv-time">{{ relativeTime(c.updatedAt) }}</span>
            </template>
          </button>
          <p v-if="!conversationsOf(p.id).length" class="ws-empty">还没有对话</p>
        </div>
      </div>

      <p v-if="!visibleProjects.length" class="ws-empty">
        工作区是空的。<br />用上面的文件夹图标添加一个。
      </p>
    </div>

    <!-- 会话右键菜单。Teleport 到 body：侧栏有 overflow 滚动，留在里面会被裁掉 -->
    <Teleport to="body">
      <div
        v-if="convMenu"
        class="ws-menu ws-pop is-floating mj-fade"
        :style="{ left: convMenu.x + 'px', top: convMenu.y + 'px' }"
        @click.stop
      >
        <button class="ws-menu-item" @click="startConvRename">
          <Icon name="edit" :size="14" />
          <span>重命名</span>
        </button>
        <button class="ws-menu-item is-danger" @click="removeFromMenu">
          <Icon name="trash" :size="14" />
          <span>删除这段对话</span>
        </button>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
/* ==================================================================== */
/* 工作区树：素笺流韵 v2                                                */
/* ==================================================================== */

/*
 * 溢出问题的根子在这里：这是一棵树，每层都有「可伸缩的标题 + 右侧操作按钮」。
 * flex 子元素默认 min-width:auto，标题一长就会把整行撑得比容器宽，
 * 白色选中底就"溢出"到侧栏外面去了。
 * 所以每一层都要 min-width:0 + overflow:hidden，容器再兜一层 overflow-x:hidden。
 */
.ws {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 0 10px 12px;
  overflow-x: hidden;
  overflow-y: auto;
}

/* ==================== 头部 ==================== */

.ws-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 6px 14px;
  flex-shrink: 0;
}

.ws-title {
  font-family: var(--font-serif);
  font-size: 11.5px;
  letter-spacing: 0.16em;
  color: var(--color-muted);
}

.ws-head-actions { margin-left: auto; display: flex; gap: 2px; }

/* 悬浮图标按钮：圆形。hover 时染一层雾 + 轻微放大 */
.ws-bubble {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 50%;
  background: none;
  color: var(--color-muted);
  cursor: pointer;
  transition: background var(--dur) var(--ease-out),
              color var(--dur) var(--ease-out),
              transform var(--dur-fast) var(--ease-spring);
}
.ws-bubble:hover { background: var(--tint-white); color: var(--color-brown-deep); transform: scale(1.1); }
.ws-bubble:active { transform: scale(0.94); }
.ws-bubble.on { background: var(--color-panel); color: var(--color-brown-deep); }

/* ==================== 项目选择器 ==================== */

.ws-picker {
  margin: 0 2px 10px;
  padding: 10px;
  border-radius: var(--radius-m);
  background: var(--color-panel);
  box-shadow: var(--shadow-float);
  animation: mj-pop var(--dur) var(--ease-spring) both;
}

.ws-picker-title {
  padding: 2px 8px 9px;
  font-size: 11px;
  letter-spacing: 0.1em;
  color: var(--color-muted);
}

.ws-picker-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  min-width: 0;
  padding: 9px 10px;
  border: 0;
  border-radius: var(--radius-xs);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
  color: var(--color-ink);
  transition: background var(--dur) var(--ease-out);
}
.ws-picker-item:hover { background: var(--tint); }
.ws-picker-item.used { color: var(--color-muted); }

.ws-picker-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ws-picker-hint { flex: none; font-size: 10.5px; color: var(--color-muted); }

/* ==================== 列表 ==================== */

.ws-project { min-width: 0; }

/* 项目行：药丸。选中时白底 + 极淡投影 —— 扁平里最克制的"选中"表达 */
.ws-project-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
  border-radius: var(--radius-pill);
  transition: background var(--dur) var(--ease-out);
}
.ws-project-row:hover { background: var(--tint-white); }

.ws-project-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  padding: 9px 12px;
  border: 0;
  border-radius: var(--radius-pill);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 13.5px;
  color: var(--color-ink-soft);
  transition: color var(--dur) var(--ease-out), background var(--dur) var(--ease-out);
}

.ws-project-head.active {
  background: var(--color-panel);
  color: var(--color-brown-deep);
  box-shadow: 0 1px 3px -1px rgba(60, 44, 30, 0.12);
}

/* 展开箭头：旋转 90° 表示展开。这是最便宜也最清楚的展开反馈 */
.ws-caret-icon {
  flex: none;
  color: var(--color-muted);
  transform: rotate(-90deg);
  transition: transform var(--dur) var(--ease-spring);
}
.ws-caret-icon.open { transform: rotate(0deg); }

.ws-project-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13.5px;
}

/* 计数：淡棕底药丸 */
.ws-count {
  flex: none;
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  background: var(--tint-strong);
  color: var(--color-brown-deep);
  font-size: 10px;
}

/* 就地改名 */
.ws-rename {
  flex: 1;
  min-width: 0;
  margin: 0 6px;
  padding: 6px 12px;
  border: 0;
  border-radius: var(--radius-pill);
  background: var(--color-panel);
  box-shadow: 0 0 0 2px var(--color-brown);
  font-family: var(--font-sans);
  font-size: 13.5px;
  color: var(--color-ink);
  outline: none;
}

/* 右侧操作按钮：hover 或菜单打开时浮现 */
.ws-actions {
  position: absolute;
  right: 4px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  gap: 1px;
  padding: 2px;
  border-radius: var(--radius-pill);
  background: var(--color-panel);
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--dur) var(--ease-out);
}
.ws-actions.show { opacity: 1; pointer-events: auto; }

/* ---------- 会话 ---------- */

.ws-convs {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 6px 0 8px 14px;
  margin-left: 8px;
  border-left: 1px solid var(--color-line);
  min-width: 0;
  animation: mj-slide-in var(--dur) var(--ease-out) both;
}

.ws-conv {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding: 10px 11px;
  border: 0;
  border-radius: var(--radius-pill);
  background: none;
  cursor: pointer;
  text-align: left;
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.ws-conv:hover { background: var(--tint-white); }
.ws-conv.active { background: var(--color-panel); }

/* 角色标记：正方小徽标（策/文），用衬线更像个印记 */
.ws-conv-mark {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 19px;
  height: 19px;
  border-radius: var(--radius-xs);
  background: var(--tint-strong);
  color: var(--color-brown-deep);
  font-family: var(--font-serif);
  font-size: 10px;
  line-height: 1;
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.ws-conv.active .ws-conv-mark {
  background: linear-gradient(140deg, #cf9f74, var(--color-brown));
  color: #fff;
}

.ws-conv-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
  color: var(--color-ink-soft);
}
.ws-conv.active .ws-conv-title { color: var(--color-brown-deep); }

.ws-conv-time { flex: none; font-size: 10px; color: var(--color-muted); opacity: 0.8; }

.ws-conv-rename {
  flex: 1;
  min-width: 0;
  padding: 3px 10px;
  border: 0;
  border-radius: var(--radius-pill);
  background: var(--color-panel);
  box-shadow: 0 0 0 2px var(--color-brown);
  font-family: var(--font-sans);
  font-size: 12.5px;
  color: var(--color-ink);
  outline: none;
}

.ws-empty {
  padding: 12px 10px;
  font-size: 11.5px;
  line-height: 1.95;
  color: var(--color-muted);
}

/* ==================== 菜单 ==================== */

.ws-menu {
  position: absolute;
  right: 6px;
  top: calc(100% - 4px);
  z-index: 90;
  min-width: 168px;
  padding: 6px;
  border-radius: var(--radius-m);
  background: var(--color-panel);
  box-shadow: var(--shadow-float);
  animation: mj-pop var(--dur-fast) var(--ease-spring) both;
}

/* 右键菜单浮到底层：位置由内联 style 给，宽度必须自己定，
   否则 right:6px 与 left:X 同时生效会把它拉成横贯屏幕的一条 */
.ws-menu.is-floating {
  position: fixed;
  right: auto;
  top: auto;
  min-width: 172px;
  animation: mj-pop var(--dur-fast) var(--ease-spring) both;
}

.ws-menu-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 9px 12px;
  border: 0;
  border-radius: var(--radius-xs);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 12.5px;
  color: var(--color-ink);
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.ws-menu-item:hover { background: var(--tint); }
.ws-menu-item.is-danger { color: var(--color-danger); }
.ws-menu-item.is-danger:hover { background: var(--tint-accent); }
</style>
