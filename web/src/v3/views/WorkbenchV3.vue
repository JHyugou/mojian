<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '../../components/Icon.vue'
import V3Button from '../../components/v3/V3Button.vue'
import V3Empty from '../../components/v3/V3Empty.vue'
import WorkspaceShell from '../layout/WorkspaceShell.vue'
import PlannerStage from '../workbench/PlannerStage.vue'
import WriterStage from '../workbench/WriterStage.vue'
import ResourcePanel from '../workbench/ResourcePanel.vue'
import { useProjectStore } from '../../stores/project'
import { useWorkbench } from '../workbench/useWorkbench'

/**
 * v3 创作工作台：外壳（导航/工作区树/顶栏）+ 策划/写作两种模式 + 资料右栏。
 *
 * 模式以 URL 的 mode 为唯一真相：从工作区树点 WRITER 会话进来会带 mode=writer，
 * 刷新也能回到同一个模式。章节选择不进 URL（它是工作台内部状态，刷新回最后一章
 * 是作者最常要写的那一章）。
 */
const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()

const projectId = computed(() => projectStore.currentId)
const state = useWorkbench(projectId)
const mode = ref(route.query.mode === 'writer' ? 'writer' : 'planner')
// 窄屏默认收起资料栏：否则 320px 的面板会盖住整个正文区
const isNarrow = typeof window !== 'undefined' && window.innerWidth < 1100
const rightOpen = ref(!isNarrow)

const breadcrumb = computed(() => {
  const project = projectStore.current?.name || '工作台'
  if (mode.value === 'writer' && state.activeChapter?.title) {
    return `${project} · ${state.activeChapter.title}`
  }
  return project
})

const activeReport = computed(() => (mode.value === 'planner' ? state.reports.PLANNER : state.reports.WRITER))

/** 可关联到章节的蓝图节点：只取 CHAPTER 类型（卷/段是组织层级，不是任务书） */
const chapterBlueprints = computed(() => (state.flatOutline || []).filter((n) => n.nodeType === 'CHAPTER'))

watch(
  () => route.query.mode,
  (value) => {
    const next = value === 'writer' ? 'writer' : 'planner'
    if (mode.value !== next) mode.value = next
  }
)

function setMode(next) {
  if (mode.value === next) return
  mode.value = next
  const query = { ...route.query, mode: next }
  router.replace({ path: '/workbench', query })
}

onMounted(() => {
  if (projectId.value) state.loadAll()
})

watch(projectId, () => {
  state.clearCitations()
  if (projectId.value) state.loadAll()
})

function onCite(citation) {
  state.addCitation(citation)
  setMode('planner')
}

function selectChapter(chapter) {
  state.selectChapter(chapter)
}

async function afterWriterChanged() {
  await state.loadAll()
}

function goHome() {
  router.push('/')
}
</script>

<template>
  <WorkspaceShell :breadcrumb="breadcrumb">
    <template #topbar>
      <div class="mode-switch">
        <button class="mode-btn" :class="{ active: mode === 'planner' }" @click="setMode('planner')">
          <span class="mode-label">策划</span>
          <span class="mode-hint">人物 / 设定 / 走向</span>
        </button>
        <button class="mode-btn" :class="{ active: mode === 'writer' }" @click="setMode('writer')">
          <span class="mode-label">写作</span>
          <span class="mode-hint">把蓝图写成正文</span>
        </button>
      </div>

      <div v-if="activeReport" class="ctx-pill" :class="{ warn: (activeReport.usagePercent || 0) > 70 }">
        上下文 {{ activeReport.usagePercent }}%
      </div>
    </template>

    <div v-if="!projectId" class="wb-empty">
      <V3Empty
        seal="笺"
        title="还没有打开的作品"
        desc="回首页选一部作品，或者先新建一本。"
        action="回到首页"
        @action="goHome"
      />
    </div>

    <div v-else class="wb3">
      <PlannerStage
        v-if="mode === 'planner'"
        :key="'planner-' + projectId"
        :project-id="projectId"
        :conversation-id="route.query.conv"
        :fresh-token="route.query.fresh"
        :citations="state.citations"
        @conversation-changed="(id) => router.replace({ path: '/workbench', query: { ...route.query, conv: id, mode: 'planner' } })"
        @remove-citation="state.removeCitation"
        @clear-citations="state.clearCitations"
      />
      <WriterStage
        v-else
        :key="'writer-' + projectId"
        :project-id="projectId"
        :chapter="state.activeChapter"
        :chapters="state.chapters"
        :blueprint="state.activeBlueprint"
        :blueprint-options="chapterBlueprints"
        @select-chapter="selectChapter"
        @reload="afterWriterChanged"
      />

      <ResourcePanel
        v-if="rightOpen"
        :characters="state.characters"
        :settings="state.settings"
        :outline-tree="state.outlineTree"
        :reports="state.reports"
        :counts="state.counts"
        @cite="onCite"
        @close="rightOpen = false"
      />

      <!-- 资料栏收起后，右缘留一个抓手：不重复顶栏图标，但也不让人"关掉就找不回来" -->
      <button v-if="!rightOpen" class="side-reopen" title="打开资料栏" @click="rightOpen = true">
        <Icon name="layers" :size="14" />
        <span>资料</span>
      </button>
    </div>
  </WorkspaceShell>
</template>

<style scoped>
.wb3 { position: relative; flex: 1; min-width: 0; display: flex; overflow: hidden; }
.wb-empty { flex: 1; display: grid; place-items: center; }

/* 资料栏收起后的右缘抓手：只在栏关闭时出现，不与栏内的关闭按钮重复 */
.side-reopen {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  z-index: 6;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  padding: 13px 6px;
  border: 1px solid var(--v3-hairline);
  border-right: 0;
  border-radius: 10px 0 0 10px;
  background: var(--v3-surface-raised);
  color: var(--v3-text-faint);
  font-family: inherit;
  cursor: pointer;
  box-shadow: var(--v3-shadow-float);
  transition: color var(--v3-dur-2), border-color var(--v3-dur-2), transform var(--v3-dur-2);
}
.side-reopen span {
  writing-mode: vertical-rl;
  font-size: 10px;
  letter-spacing: 0.22em;
}
.side-reopen:hover {
  color: var(--v3-gold-bright);
  border-color: var(--v3-hairline-gold);
}

.mode-switch {
  display: flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-pill);
  background: var(--v3-surface-1);
}
.mode-btn {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 5px 14px;
  border: 0;
  border-radius: var(--v3-r-pill);
  background: transparent;
  color: var(--v3-text-faint);
  font-family: inherit;
  cursor: pointer;
  transition: background var(--v3-dur-2), color var(--v3-dur-2);
}
.mode-btn:hover { color: var(--v3-text-soft); }
.mode-btn.active { background: var(--v3-surface-raised); color: var(--v3-text); box-shadow: 0 2px 10px -4px rgba(0, 0, 0, 0.6); }
.mode-label { font-family: var(--v3-font-serif); font-size: 13px; letter-spacing: 0.08em; white-space: nowrap; }
.mode-hint { font-size: 9px; letter-spacing: 0.04em; opacity: 0.6; }
.ctx-pill {
  padding: 5px 11px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-pill);
  font-size: 10.5px;
  color: var(--v3-text-muted);
  font-family: var(--v3-font-mono);
}
.ctx-pill.warn { color: var(--v3-gold-bright); border-color: var(--v3-hairline-gold); }

@media (max-width: 760px) {
  .mode-hint { display: none; }
  .mode-btn { padding: 6px 12px; white-space: nowrap; }
  .ctx-pill { display: none; }
}
@media (max-width: 520px) {
  .mode-switch { padding: 2px; }
  .mode-btn { padding: 6px 10px; }
  .mode-label { font-size: 12px; }
}
</style>
