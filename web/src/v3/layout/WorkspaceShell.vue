<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '../../components/Icon.vue'
import V3Ambient from '../../components/v3/V3Ambient.vue'
import V3CommandPalette from '../../components/v3/V3CommandPalette.vue'
import V3Toasts from '../../components/v3/V3Toasts.vue'
import V3Splitter from '../../components/v3/V3Splitter.vue'
import { useV3Theme } from '../theme'
import { useV3Notify } from '../notify'
import { useShellCommands } from '../shellBus'
import { useWidthResize } from '../useWidthResize'
import { useProjectStore } from '../../stores/project'
import { listProjects } from '../../api/project'
import { deleteConversation, listConversations, renameConversation } from '../../api/agent'

/**
 * v3 工作区外壳：左侧导航轨 + 工作区树（项目/会话）+ 顶栏插槽 + 内容区。
 *
 * 设计意图：让"写作"像在一间安静的房间里 —— 外壳尽量退后（窄轨、深色玻璃），
 * 内容和文字站到前面。导航轨悬停才展开标签，不做常驻文字。
 */
const props = defineProps({
  /** 顶栏面包屑；不传则按当前路由自动推导 */
  breadcrumb: { type: String, default: '' }
})

const router = useRouter()
const route = useRoute()
const projectStore = useProjectStore()
const { theme, toggleTheme } = useV3Theme()
const notify = useV3Notify()
const { commands: pageCommands } = useShellCommands()

/**
 * 顶栏标题：外壳常驻，所以标题要跟着路由走，而不是由每个页面各自记一份。
 * 工作台/成书显示当前作品名，其余页面用固定标题。
 */
const routeTitles = { '/': '作品架', '/ai-settings': '模型接入', '/settings': '偏好设置' }
const crumb = computed(() => {
  if (props.breadcrumb) return props.breadcrumb
  if (routeTitles[route.path]) return routeTitles[route.path]
  if (route.path === '/workbench') return projectStore.current?.name || '工作台'
  if (route.path === '/book') {
    const name = projectStore.current?.name
    return name ? `${name} · 成书` : '成书'
  }
  return projectStore.current?.name || '墨笺'
})

const dockOpen = ref(typeof window === 'undefined' ? true : window.innerWidth > 900)
/** 工作区树宽度可拖拽（双击恢复默认） */
const {
  width: dockWidth,
  onPointerDown: onDockResize,
  reset: resetDockWidth
} = useWidthResize({ initial: 264, min: 200, max: 440, storageKey: 'mojian.dockWidth' })
const paletteOpen = ref(false)
const projects = ref([])
const conversations = ref([])
const expanded = ref(new Set())
const loading = ref(true)
const convMenu = ref(null)

const railItems = [
  { path: '/', label: '首页', icon: 'sparkle' },
  { path: '/workbench', label: '工作台', icon: 'quill' },
  { path: '/book', label: '成书', icon: 'book' },
  { path: '/ai-settings', label: '模型接入', icon: 'cpu' },
  { path: '/settings', label: '设置', icon: 'gear' }
]

const conversationsOf = (projectId) =>
  conversations.value.filter((c) => String(c.projectId) === String(projectId))
const isExpanded = (projectId) => expanded.value.has(String(projectId))

function toggleProject(projectId) {
  const next = new Set(expanded.value)
  const key = String(projectId)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expanded.value = next
}

async function load() {
  loading.value = true
  try {
    const [projectList, convList] = await Promise.allSettled([listProjects(), listConversations()])
    if (projectList.status === 'fulfilled') projects.value = projectList.value || []
    if (convList.status === 'fulfilled') conversations.value = convList.value || []
    if (projectStore.currentId) expanded.value = new Set([...expanded.value, String(projectStore.currentId)])
  } catch (e) {
    notify.error('读取工作区失败')
  } finally {
    loading.value = false
  }
}

function openProject(project) {
  const sameProject = String(projectStore.currentId) === String(project.id)
  projectStore.select(project)
  expanded.value = new Set([...expanded.value, String(project.id)])

  if (route.path !== '/workbench') {
    // 从首页/成书/设置点进项目：进入工作台，由工作台载入该项目最近的会话
    router.push('/workbench')
    return
  }
  if (!sameProject) {
    /*
     * 已经在工作台里换项目：必须清掉上一项目遗留的 conv/fresh 参数。
     * 否则策划面板会拿着"别的项目的会话 id"去当前项目里找，找不到就报
     * "这段会话不属于当前项目" —— 换项目本来就是自由操作，不该被当成错误。
     */
    router.replace({
      path: '/workbench',
      query: { mode: route.query.mode === 'writer' ? 'writer' : 'planner' }
    })
  }
}

function openConversation(project, conv) {
  projectStore.select(project)
  expanded.value = new Set([...expanded.value, String(project.id)])
  router.push({ path: '/workbench', query: { conv: conv.id, mode: conv.role === 'WRITER' ? 'writer' : 'planner' } })
}

function newConversation(project) {
  projectStore.select(project)
  expanded.value = new Set([...expanded.value, String(project.id)])
  router.push({ path: '/workbench', query: { mode: 'planner', fresh: Date.now() } })
}

function openConvMenu(event, conv) {
  event.stopPropagation()
  convMenu.value = convMenu.value?.id === conv.id ? null : conv
}

async function submitRename(conv, event) {
  const title = event.target.value.trim()
  convMenu.value = null
  if (!title || title === conv.title) return
  try {
    const updated = await renameConversation(conv.id, title)
    conv.title = updated.title
    conversations.value = [...conversations.value]
  } catch (e) {
    notify.error(e.message || '改名失败')
  }
}

async function removeConv(conv) {
  convMenu.value = null
  if (!window.confirm(`删除这段对话？\n\n${conv.title}\n\n人物、设定、蓝图、正文都不受影响。`)) return
  try {
    await deleteConversation(conv.id)
    conversations.value = conversations.value.filter((c) => String(c.id) !== String(conv.id))
    if (String(route.query.conv) === String(conv.id)) {
      router.push({ path: '/workbench', query: { mode: 'planner', fresh: Date.now() } })
    }
  } catch (e) {
    notify.error(e.message || '删除失败')
  }
}

// ==================== 命令面板 ====================
const paletteItems = computed(() => [
  { id: 'nav-home', label: '回到首页（作品架）', hint: '导航', icon: 'sparkle', action: () => router.push('/') },
  { id: 'nav-book', label: '成书', hint: '导航', icon: 'book', action: () => router.push('/book') },
  { id: 'nav-ai', label: '模型接入', hint: '导航', icon: 'cpu', action: () => router.push('/ai-settings') },
  { id: 'nav-settings', label: '偏好设置', hint: '主题 / 思维链 / 发送键', icon: 'gear', action: () => router.push('/settings') },
  { id: 'toggle-theme', label: theme.value === 'inksea' ? '切换到素笺（亮色）' : '切换到墨海（暗色）', hint: '主题', icon: theme.value === 'inksea' ? 'sun' : 'moon', action: toggleTheme },
  { id: 'toggle-dock', label: dockOpen.value ? '收起工作区树' : '展开工作区树', hint: '布局', icon: 'layers', action: () => (dockOpen.value = !dockOpen.value) },
  ...pageCommands.value,
  ...projects.value.map((p) => ({
    id: 'project-' + p.id,
    label: p.name,
    hint: p.genre || '切换项目',
    icon: 'book',
    action: () => openProject(p)
  }))
])

function onPaletteSelect(item) {
  paletteOpen.value = false
  item.action?.()
}

function onGlobalKeydown(event) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    paletteOpen.value = true
  }
}

const closeFloating = () => (convMenu.value = null)

onMounted(() => {
  load()
  document.addEventListener('keydown', onGlobalKeydown)
  document.addEventListener('click', closeFloating)
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onGlobalKeydown)
  document.removeEventListener('click', closeFloating)
})

defineExpose({ reload: load })
</script>

<template>
  <div class="v3-root ws-shell">
    <V3Ambient variant="calm" />

    <div class="ws-layout" :style="{ '--dock-width': dockWidth + 'px' }">
      <!-- ==================== 左侧导航轨 ==================== -->
      <nav class="ws-rail" aria-label="主导航">
        <button class="rail-brand" title="墨笺 · 首页" @click="router.push('/')">
          <svg viewBox="0 0 32 32" aria-hidden="true">
            <rect width="32" height="32" rx="10" fill="none" stroke="var(--v3-hairline-gold)" />
            <path d="M7 23 L13 12 L18 20 L21.5 15 L26 23 Z" fill="var(--v3-gold)" opacity=".9" />
            <circle cx="23.5" cy="9" r="2.3" fill="var(--v3-cinnabar)" />
          </svg>
        </button>

        <div class="rail-items">
          <button
            v-for="item in railItems"
            :key="item.path"
            class="rail-item"
            :class="{ active: route.path === item.path }"
            :title="item.label"
            @click="router.push(item.path)"
          >
            <Icon :name="item.icon" :size="18" />
            <span class="rail-label">{{ item.label }}</span>
          </button>
        </div>

        <div class="rail-bottom">
          <button class="rail-item" :title="theme === 'inksea' ? '切到素笺' : '切到墨海'" @click="toggleTheme">
            <Icon :name="theme === 'inksea' ? 'sun' : 'moon'" :size="18" />
            <span class="rail-label">{{ theme === 'inksea' ? '素笺' : '墨海' }}</span>
          </button>
          <button class="rail-item" :title="dockOpen ? '收起工作区' : '展开工作区'" @click="dockOpen = !dockOpen">
            <Icon name="layers" :size="18" />
            <span class="rail-label">工作区</span>
          </button>
          <button class="rail-item" title="命令面板（Ctrl/⌘ + K）" @click="paletteOpen = true">
            <Icon name="command" :size="18" />
            <span class="rail-label">命令</span>
          </button>
        </div>
      </nav>

      <!-- ==================== 工作区树 ==================== -->
      <Transition name="dock">
        <aside v-if="dockOpen" class="ws-dock" aria-label="工作区">
          <header class="dock-head">
            <span class="dock-title">工作区</span>
            <button class="dock-icon" title="命令面板" @click="paletteOpen = true">
              <Icon name="search" :size="15" />
            </button>
          </header>

          <div class="dock-body v3-scroll">
            <div v-if="loading" class="dock-loading">正在读取…</div>
            <p v-else-if="!projects.length" class="dock-empty">
              还没有项目。<br />回首页新建一部作品吧。
            </p>

            <div v-for="project in projects" :key="project.id" class="dock-project">
              <div class="dock-project-row" :class="{ current: String(projectStore.currentId) === String(project.id) }">
                <button class="dock-caret" :class="{ open: isExpanded(project.id) }" @click="toggleProject(project.id)">
                  <Icon name="caret" :size="12" />
                </button>
                <button class="dock-project-name" @click="openProject(project)">{{ project.name }}</button>
                <button class="dock-mini" title="新建对话" @click="newConversation(project)">
                  <Icon name="plus" :size="14" />
                </button>
              </div>

              <div v-if="isExpanded(project.id)" class="dock-convs">
                <div class="dock-conv-wrap" v-for="conv in conversationsOf(project.id)" :key="conv.id">
                  <button
                    class="dock-conv"
                    :class="{ active: String(route.query.conv) === String(conv.id) }"
                    @click="openConversation(project, conv)"
                    @contextmenu.prevent="openConvMenu($event, conv)"
                  >
                    <span class="conv-mark" :class="conv.role === 'WRITER' ? 'is-writer' : 'is-planner'">
                      {{ conv.role === 'WRITER' ? '文' : '策' }}
                    </span>
                    <span class="conv-title">{{ conv.title }}</span>
                  </button>

                  <Transition name="menu">
                    <div v-if="convMenu?.id === conv.id" class="conv-menu" @click.stop>
                      <input
                        class="conv-rename"
                        :value="conv.title"
                        @keydown.enter="submitRename(conv, $event)"
                        @keydown.esc="convMenu = null"
                        @blur="convMenu = null"
                      />
                      <button class="conv-menu-item is-danger" @click="removeConv(conv)">
                        <Icon name="trash" :size="13" /> 删除对话
                      </button>
                    </div>
                  </Transition>
                </div>

                <p v-if="!conversationsOf(project.id).length" class="dock-conv-empty">还没有对话</p>
              </div>
            </div>
          </div>

          <footer class="dock-foot">
            <span class="dock-hint"><kbd>⌘K</kbd> 命令</span>
            <span class="dock-hint">右键会话可改名/删除</span>
          </footer>
        </aside>
      </Transition>

      <!-- 工作区树与主区之间的拖拽分割线 -->
      <V3Splitter
        v-if="dockOpen"
        class="dock-splitter"
        title="拖拽调整工作区宽度，双击恢复默认"
        @drag-start="onDockResize"
        @reset="resetDockWidth"
      />

      <!-- ==================== 右：顶栏 + 内容 ==================== -->
      <div class="ws-main">
        <header class="ws-topbar">
          <Transition name="crumb" mode="out-in">
            <span :key="crumb" class="topbar-crumb">{{ crumb }}</span>
          </Transition>
          <!-- 页面把顶栏专属动作 Teleport 到这里；外壳常驻，内容随路由替换 -->
          <div id="shell-topbar" class="topbar-slot"></div>
          <slot name="topbar" />
          <div class="topbar-right">
            <button class="topbar-pill" @click="paletteOpen = true">
              <Icon name="command" :size="13" />
              <span>命令</span>
              <kbd>⌘K</kbd>
            </button>
            <button class="topbar-icon" :title="theme === 'inksea' ? '切到素笺' : '切到墨海'" @click="toggleTheme">
              <Icon :name="theme === 'inksea' ? 'sun' : 'moon'" :size="16" />
            </button>
          </div>
        </header>

        <main class="ws-content">
          <slot />
        </main>
      </div>
    </div>

    <V3CommandPalette
      :visible="paletteOpen"
      :items="paletteItems"
      @close="paletteOpen = false"
      @select="onPaletteSelect"
    />
    <V3Toasts />
  </div>
</template>

<style scoped>
.ws-shell {
  position: fixed;
  inset: 0;
  background: var(--v3-canvas);
  color: var(--v3-text);
  overflow: hidden;
}
.ws-layout {
  position: relative;
  z-index: 1;
  display: flex;
  width: 100%;
  height: 100%;
}

/* ==================== 导航轨 ==================== */
.ws-rail {
  width: 70px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 18px 0 18px;
  border-right: 1px solid var(--v3-hairline);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.02), transparent 40%);
}
.rail-brand {
  width: 40px;
  height: 40px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  margin-bottom: 12px;
  transition: transform var(--v3-dur-3) var(--v3-ease-spring);
}
.rail-brand:hover { transform: rotate(-4deg) scale(1.05); }
.rail-brand svg { width: 100%; height: 100%; }

.rail-items { display: flex; flex-direction: column; gap: 4px; flex: 1; }
.rail-bottom { display: flex; flex-direction: column; gap: 4px; }

.rail-item {
  position: relative;
  width: 48px;
  height: 44px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 12px;
  background: transparent;
  color: var(--v3-text-faint);
  cursor: pointer;
  transition: color var(--v3-dur-2), background var(--v3-dur-2), transform var(--v3-dur-2) var(--v3-ease-spring);
}
.rail-item:hover { color: var(--v3-text); background: var(--v3-surface-2); transform: translateY(-1px); }
.rail-item.active { color: var(--v3-gold-bright); background: var(--v3-glow-gold); }
.rail-item.active::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 12px;
  bottom: 12px;
  width: 2px;
  border-radius: 2px;
  background: var(--v3-grad-gold);
  /* 选中条从中间"长出来"，而不是瞬移出现 */
  transform-origin: center;
  animation: rail-mark-in var(--v3-dur-3) var(--v3-ease-spring);
}
@keyframes rail-mark-in {
  from { opacity: 0; transform: scaleY(0.2); }
  to { opacity: 1; transform: scaleY(1); }
}
.rail-label {
  position: absolute;
  left: 56px;
  top: 50%;
  transform: translate3d(-4px, -50%, 0);
  padding: 5px 10px;
  border-radius: var(--v3-r-xs);
  border: 1px solid var(--v3-hairline-strong);
  background: var(--v3-surface-solid);
  color: var(--v3-text-soft);
  font-size: 11.5px;
  white-space: nowrap;
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--v3-dur-2), transform var(--v3-dur-2) var(--v3-ease-out);
  z-index: 40;
  box-shadow: var(--v3-shadow-card);
}
.rail-item:hover .rail-label { opacity: 1; transform: translate3d(0, -50%, 0); }

/* ==================== 工作区树 ==================== */
.ws-dock {
  width: var(--dock-width, 264px);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--v3-hairline);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.018), transparent 30%);
  overflow: hidden;
}
.dock-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22px 16px 12px;
}
.dock-title {
  font-family: var(--v3-font-serif);
  font-size: 13px;
  letter-spacing: 0.22em;
  color: var(--v3-text-muted);
}
.dock-icon {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--v3-text-faint);
  cursor: pointer;
}
.dock-icon:hover { color: var(--v3-text); background: var(--v3-surface-2); }

.dock-body { flex: 1; padding: 4px 10px 12px; }
.dock-loading, .dock-empty {
  padding: 18px 10px;
  font-size: 12px;
  line-height: 1.9;
  color: var(--v3-text-faint);
}
.dock-project { margin-bottom: 2px; }
.dock-project-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 6px 6px 4px;
  border-radius: 10px;
  transition: background var(--v3-dur-2);
}
.dock-project-row:hover { background: var(--v3-surface-1); }
.dock-project-row.current { background: var(--v3-glow-gold); }
.dock-caret {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--v3-text-faint);
  cursor: pointer;
  transition: transform var(--v3-dur-2) var(--v3-ease-out);
}
.dock-caret.open { transform: rotate(0); }
.dock-caret:not(.open) { transform: rotate(-90deg); }
.dock-project-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 0;
  background: transparent;
  color: var(--v3-text-soft);
  font-family: var(--v3-font-serif);
  font-size: 13.5px;
  letter-spacing: 0.04em;
  text-align: left;
  cursor: pointer;
  padding: 2px 0;
}
.dock-project-row.current .dock-project-name { color: var(--v3-text); }
.dock-mini {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--v3-text-faint);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--v3-dur-2), background var(--v3-dur-2);
}
.dock-project-row:hover .dock-mini { opacity: 1; }
.dock-mini:hover { background: var(--v3-surface-2); color: var(--v3-text); }

.dock-convs { padding: 2px 0 6px 24px; }
.dock-conv-wrap { position: relative; }
.dock-conv {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 9px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--v3-text-muted);
  font-family: inherit;
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
  transition: background var(--v3-dur-2), color var(--v3-dur-2);
}
.dock-conv:hover { background: var(--v3-surface-1); color: var(--v3-text-soft); }
.dock-conv.active { background: var(--v3-surface-2); color: var(--v3-text); }
.conv-mark {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  border-radius: 5px;
  border: 1px solid var(--v3-hairline-strong);
  font-family: var(--v3-font-serif);
  font-size: 10px;
}
.conv-mark.is-writer { color: var(--v3-gold); border-color: var(--v3-hairline-gold); }
.conv-mark.is-planner { color: var(--v3-jade); border-color: rgba(127, 168, 161, 0.4); }
.conv-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dock-conv-empty { padding: 6px 12px 8px; font-size: 11px; color: var(--v3-text-faint); }

.conv-menu {
  position: absolute;
  left: 12px;
  right: 4px;
  top: 30px;
  z-index: 30;
  padding: 6px;
  border-radius: var(--v3-r-sm);
  border: 1px solid var(--v3-hairline-strong);
  background: var(--v3-surface-solid);
  box-shadow: var(--v3-shadow-float);
}
.conv-rename {
  width: 100%;
  height: 30px;
  padding: 0 8px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-xs);
  background: var(--v3-surface-1);
  color: var(--v3-text);
  font-size: 12px;
  outline: none;
}
.conv-menu-item {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  margin-top: 4px;
  padding: 7px 8px;
  border: 0;
  border-radius: var(--v3-r-xs);
  background: transparent;
  color: var(--v3-text-soft);
  font-family: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.conv-menu-item:hover { background: var(--v3-surface-2); }
.conv-menu-item.is-danger:hover { color: var(--v3-cinnabar); }

.dock-foot {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 12px 16px 16px;
  border-top: 1px solid var(--v3-hairline);
  font-size: 10px;
  color: var(--v3-text-faint);
}
.dock-hint kbd {
  padding: 1px 5px;
  border-radius: 4px;
  border: 1px solid var(--v3-hairline-strong);
  font-family: var(--v3-font-mono);
  font-size: 9.5px;
}

/* ==================== 主区 ==================== */
.ws-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.ws-topbar {
  display: flex;
  align-items: center;
  gap: 16px;
  height: 62px;
  padding: 0 22px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--v3-hairline);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.02), transparent);
}
.topbar-crumb {
  font-family: var(--v3-font-serif);
  font-size: 14px;
  letter-spacing: 0.1em;
  color: var(--v3-text-soft);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 34%;
}
.topbar-slot { display: flex; align-items: center; gap: 10px; min-width: 0; }
.topbar-right { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.topbar-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-pill);
  background: var(--v3-surface-1);
  color: var(--v3-text-muted);
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
  transition: color var(--v3-dur-2), border-color var(--v3-dur-2);
}
.topbar-pill:hover { color: var(--v3-text); border-color: var(--v3-hairline-gold); }
.topbar-pill kbd {
  padding: 1px 4px;
  border-radius: 4px;
  border: 1px solid var(--v3-hairline-strong);
  font-family: var(--v3-font-mono);
  font-size: 9.5px;
}
.topbar-icon {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: 1px solid transparent;
  border-radius: 50%;
  background: transparent;
  color: var(--v3-text-faint);
  cursor: pointer;
  transition: color var(--v3-dur-2), background var(--v3-dur-2);
}
.topbar-icon:hover { color: var(--v3-gold-bright); background: var(--v3-surface-2); }

.ws-content { position: relative; flex: 1; min-height: 0; display: flex; overflow: hidden; }

/* ==================== 过渡 ==================== */
.dock-enter-active, .dock-leave-active { transition: width var(--v3-dur-3) var(--v3-ease-out), opacity var(--v3-dur-2); }
.dock-enter-from, .dock-leave-to { width: 0; opacity: 0; }
.crumb-enter-active, .crumb-leave-active { transition: opacity var(--v3-dur-2), transform var(--v3-dur-2) var(--v3-ease-out); }
.crumb-enter-from { opacity: 0; transform: translateY(6px); }
.crumb-leave-to { opacity: 0; transform: translateY(-6px); }
.menu-enter-active, .menu-leave-active { transition: opacity var(--v3-dur-1), transform var(--v3-dur-2) var(--v3-ease-spring); }
.menu-enter-from, .menu-leave-to { opacity: 0; transform: translateY(-4px) scale(0.98); }

@media (max-width: 900px) {
  .ws-dock { position: absolute; left: 70px; top: 0; bottom: 0; z-index: 30; background: var(--v3-surface-solid); box-shadow: var(--v3-shadow-float); }
  .dock-splitter { display: none; }
}
@media (max-width: 760px) {
  .ws-topbar { gap: 8px; padding: 0 12px; }
  /* 窄屏只留"模式/主题/资料"这些真正常用的入口 */
  .topbar-crumb { display: none; }
  .topbar-pill { display: none; }
  .rail-label { display: none; }
  .ws-rail { width: 56px; }
  .rail-item { width: 40px; height: 40px; }
  .rail-brand { width: 34px; height: 34px; }
  .ws-dock { left: 56px; }
}
</style>
