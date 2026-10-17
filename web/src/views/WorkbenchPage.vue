<template>
  <div class="mj-page wb">
    <!-- ==================== 顶栏 ==================== -->
    <header class="wb-top">
      <div class="wb-brand">
        <span class="mj-title mj-title-md">墨笺</span>
        <span class="mj-muted wb-brand-sub">创作工作台</span>
      </div>

      <!-- 项目选择。项目与对话解耦：换项目等于换一本书，
           它下面各自的对话、人物、设定、蓝图都跟着切 -->
      <div class="wb-project">
        <button class="wb-project-current" @click="projectMenuOpen = !projectMenuOpen">
          <span>{{ projectStore.current?.name || '选择项目' }}</span>
          <span class="wb-project-caret">▾</span>
        </button>
        <div v-if="projectMenuOpen" class="wb-project-menu mj-fade">
          <button
            v-for="p in projects"
            :key="p.id"
            class="wb-project-item"
            :class="{ active: p.id === projectId }"
            @click="switchProject(p)"
          >
            {{ p.name }}
          </button>
          <div v-if="!projects.length" class="wb-project-empty mj-muted">还没有项目</div>
        </div>
      </div>

      <nav class="wb-modes">
        <button
          v-for="m in modes"
          :key="m.key"
          class="wb-mode"
          :class="{ active: mode === m.key }"
          @click="mode = m.key"
        >
          {{ m.label }}
          <span class="wb-mode-hint">{{ m.hint }}</span>
        </button>
      </nav>

      <div class="wb-meta">
        <span
          v-if="activeReport"
          class="mj-tag"
          :class="{ 'mj-tag-brown': activeReport.usagePercent > 60 }"
        >
          {{ mode === 'planner' ? '策划' : '写作' }}上下文 {{ activeReport.usagePercent }}%
        </span>
        <button class="mj-btn mj-btn-text" @click="$router.push('/')">返回项目</button>
      </div>
    </header>

    <!-- ==================== 主体三栏 ==================== -->
    <div class="wb-body">
      <!--
        这里原本有一个左栏（策划模式显示蓝图树、写作模式显示章节目录）。
        已移除：它显示的东西右栏分屏里都能看，两处重复。
        章节选择移到了写作面板标题栏的下拉里；蓝图浏览在右栏「蓝图」页签。
        腾出来的宽度给了交互区与正文 —— 那两块才是真正要看内容的地方。
      -->

      <!-- 中：策划 / 写作 -->
      <main class="wb-main">
        <Transition name="mj-swap" mode="out-in">
          <PlannerPanel
            v-if="mode === 'planner'"
            key="planner"
            :project-id="projectId"
            :citations="citations"
            @remove-citation="removeCitation"
            @clear-citations="citations = []"
          />
          <WriterPanel
            v-else
            key="writer"
            :project-id="projectId"
            :chapter="activeChapter"
            :chapters="chapters"
            :blueprint="activeBlueprint"
            @select-chapter="pickChapter"
            @create-chapter="createNewChapterFromPanel"
          />
        </Transition>
      </main>

      <!-- 右：可切换分屏（引用源）。
           四种内容都能浏览并框选引用；中栏始终是纯交互区。
           非「资料」页签时加宽 —— 浏览长文本和框选都需要宽度。 -->
      <aside class="wb-right" :class="{ 'is-wide': rightTab !== 'info' }">
        <nav class="wb-right-tabs">
          <button
            v-for="t in rightTabs"
            :key="t.key"
            class="wb-right-tab"
            :class="{ active: rightTab === t.key }"
            @click="rightTab = t.key"
          >
            {{ t.label }}
          </button>
        </nav>

        <div class="wb-right-body mj-scroll">
          <template v-if="rightTab === 'info'">
            <div class="wb-side-head">
              <span class="mj-title mj-title-sm">本书资料</span>
              <span class="mj-muted">点数字看条目</span>
            </div>

            <!-- 数字本身就是入口：它是"有多少"，最自然的下一步就是"看看都是些什么" -->
            <div class="wb-stats">
              <button
                v-for="s in stats"
                :key="s.label"
                class="wb-stat"
                :class="{ 'is-link': s.tab }"
                :disabled="!s.tab"
                @click="s.tab && (rightTab = s.tab)"
              >
                <span class="wb-stat-num">{{ s.value }}</span>
                <span class="mj-muted">{{ s.label }}</span>
              </button>
            </div>

            <hr class="mj-hr-scroll wb-hr" />

            <div class="wb-side-head">
              <span class="mj-title mj-title-sm">上下文</span>
              <span class="mj-muted">主副各一套</span>
            </div>

            <!-- 主副 Agent 是两套完全不同的上下文（材料侧重不同、预算不同），
                 所以分开显示。合在一起会让作者以为它们共用一个。 -->
            <div v-for="r in agentReports" :key="r.role" class="wb-report">
              <div class="wb-report-head">
                <span class="wb-report-name">{{ r.label }}</span>
                <span class="mj-muted">{{ r.percent }}%</span>
              </div>
              <div class="wb-report-bar">
                <div class="wb-report-fill" :style="{ width: Math.min(r.percent, 100) + '%' }"></div>
              </div>
              <p class="mj-muted wb-report-text">
                {{ r.used }} / {{ r.usable }} token · 装入 {{ r.kept }} 段{{
                  r.trimmed ? `，裁掉 ${r.trimmed} 段` : ''
                }}
              </p>
              <p v-if="r.percent > 80" class="wb-warn">
                已用到 {{ r.percent }}%，该启用检索了 ——
                现在是全部塞进去（更准，但空间有限）。
              </p>
            </div>
            <p v-if="!reports.PLANNER && !reports.WRITER" class="mj-muted wb-report-text">
              正在读取…
            </p>
            <p v-else class="mj-muted wb-report-text">
              装得下就全塞，不做检索 —— 筛选必然丢东西。
            </p>
          </template>

          <ResourceBrowser
            v-else-if="rightTab === 'characters'"
            :items="characterItems"
            kind="人物"
            empty-hint="还没有人物档案。在中间和策划 Agent 聊，它会帮你建。"
            @cite="addCitation"
          />

          <ResourceBrowser
            v-else-if="rightTab === 'settings'"
            :items="settingItems"
            kind="设定"
            empty-hint="还没有世界观设定。在中间和策划 Agent 聊，它会帮你建。"
            @cite="addCitation"
          />

          <ResourceBrowser
            v-else
            :items="outlineItems"
            kind="蓝图节点"
            empty-hint="还没有蓝图。在中间和策划 Agent 聊，它会帮你把走向规划成蓝图节点。"
            @cite="addCitation"
          />
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useProjectStore } from '../stores/project'
import { listProjects } from '../api/project'
import { createChapter, listChapters } from '../api/chapter'
import { listCharacters } from '../api/character'
import { listSettings } from '../api/setting'
import { getOutlineTree } from '../api/outline'
import { getContextReport } from '../api/agent'
import PlannerPanel from '../components/workbench/PlannerPanel.vue'
import WriterPanel from '../components/workbench/WriterPanel.vue'
import ResourceBrowser from '../components/workbench/ResourceBrowser.vue'

const projectStore = useProjectStore()
const projectId = computed(() => projectStore.currentId)

const modes = [
  { key: 'planner', label: '策划', hint: '管人物、设定、剧情走向' },
  { key: 'writer', label: '写作', hint: '把蓝图写成正文' }
]

const mode = ref('planner')
const projects = ref([])
const projectMenuOpen = ref(false)

/** 换项目 = 换一本书，下面所有内容都要重取 */
async function switchProject(project) {
  projectMenuOpen.value = false
  if (project.id === projectId.value) return
  projectStore.select(project)
  // 清掉旧书的状态，避免换项目瞬间还显示着上一本的章节/人物
  chapters.value = []
  activeChapter.value = null
  outlineTree.value = []
  characters.value = []
  settings.value = []
  reports.value = { PLANNER: null, WRITER: null }
  await loadAll()
}

const rightTab = ref('info')
const rightTabs = [
  { key: 'info', label: '资料' },
  { key: 'characters', label: '人物' },
  { key: 'settings', label: '设定' },
  { key: 'outline', label: '蓝图' }
]

/**
 * 待发送的引用（从蓝图分屏框选进来的原文）。
 *
 * <p>放在工作台这一层而不是策划面板里：引用来自右栏、要进到中栏的输入框，
 * 状态得由它们共同的父级持有。
 */
const citations = ref([])

function addCitation(citation) {
  // 同一条不要重复加，否则输入框上会挂一排一样的引用
  if (citations.value.some((c) => c.nodeId === citation.nodeId && c.text === citation.text)) {
    return
  }
  citations.value = [...citations.value, citation]
  // 引用是为了跟策划 Agent 说话，顺手切回去，省掉一次点击
  mode.value = 'planner'
}

function removeCitation(index) {
  citations.value = citations.value.filter((_, i) => i !== index)
}

const chapters = ref([])
const activeChapter = ref(null)
const outlineTree = ref([])
const characters = ref([])
const settings = ref([])
const counts = ref({ characters: 0, settings: 0, outline: 0, chapters: 0 })
const reports = ref({ PLANNER: null, WRITER: null })
const sideError = ref('')

/** 顶栏徽标跟着当前模式走 —— 策划模式看策划的，写作模式看写作的 */
const activeReport = computed(() =>
  mode.value === 'planner' ? reports.value.PLANNER : reports.value.WRITER
)

/** 右栏把两套上下文并排摆出来 */
const agentReports = computed(() =>
  [
    { role: 'PLANNER', label: '策划 Agent', report: reports.value.PLANNER },
    { role: 'WRITER', label: '写作 Agent', report: reports.value.WRITER }
  ].map(({ role, label, report }) => ({
    role,
    label,
    percent: report?.usagePercent ?? 0,
    used: report?.usedTokens ?? 0,
    usable: report?.usableTokens ?? 0,
    kept: report?.keptSections?.length ?? 0,
    trimmed: report?.trimmedSections?.length ?? 0
  }))
)

/** 数字上绑了页签名：点一下就能过去看条目，而不是只能看到一个数 */
const stats = computed(() => [
  { label: '人物', value: counts.value.characters, tab: 'characters' },
  { label: '设定', value: counts.value.settings, tab: 'settings' },
  { label: '蓝图节点', value: counts.value.outline, tab: 'outline' },
  { label: '章节', value: counts.value.chapters }
])

/*
 * 三种资源转成统一的条目形状（标题 / 标签 / 正文 / 子项），
 * 交给同一个 ResourceBrowser 渲染 —— 它们在结构上本来就是一回事。
 */
const characterItems = computed(() =>
  characters.value.map((c) => ({
    id: c.id,
    title: c.name,
    tag: c.roleIdentity ? shorten(c.roleIdentity, 10) : '',
    body: c.description
  }))
)

const settingItems = computed(() =>
  settings.value.map((s) => ({
    id: s.id,
    title: s.title,
    tag: s.tags ? shorten(s.tags, 10) : '',
    body: s.content
  }))
)

/** 蓝图保留树形：卷 / 章 的层级本身是有信息量的 */
const outlineItems = computed(() => toOutlineItems(outlineTree.value))

function toOutlineItems(nodes) {
  return (nodes || []).map((n) => ({
    id: n.id,
    title: n.name,
    tag: nodeTypeLabel(n.nodeType),
    body: n.summary,
    children: toOutlineItems(n.children)
  }))
}

const shorten = (text, max) => (text.length > max ? text.slice(0, max) + '…' : text)

/**
 * 蓝图树拍平成带缩进层级的列表。
 * 比写递归组件省事，渲染出来一样 —— 这类小地方不值得多一个文件。
 */
function flattenOutline(nodes, depth = 0, out = []) {
  for (const node of nodes || []) {
    out.push({ ...node, _depth: depth })
    flattenOutline(node.children, depth + 1, out)
  }
  return out
}

const flatOutline = computed(() => flattenOutline(outlineTree.value))

/** 当前章节对应的蓝图节点（按 outlineNodeId 找）—— 写作前得看得见"这次要写什么" */
const activeBlueprint = computed(() => {
  const nodeId = activeChapter.value?.outlineNodeId
  if (!nodeId) return null
  return flatOutline.value.find((n) => n.id === nodeId) || null
})

const NODE_LABELS = { VOLUME: '卷', ARC: '段', CHAPTER: '章' }
const nodeTypeLabel = (type) => NODE_LABELS[type] || type || '节点'

function pickChapter(chapter) {
  activeChapter.value = chapter
  mode.value = 'writer'
}

/** 写作面板里新建章节：标题由那边输入，这里负责落库与切换 */
async function createNewChapterFromPanel(title) {
  try {
    const created = await createChapter(projectId.value, { title, status: 'DRAFT' })
    chapters.value = [...chapters.value, created]
    counts.value = { ...counts.value, chapters: chapters.value.length }
    // 新建完直接切过去写：点"新建"的人多半就是想马上写这一章
    activeChapter.value = created
    mode.value = 'writer'
    sideError.value = ''
  } catch (e) {
    sideError.value = e.message || '新建章节失败'
  }
}

function countOutline(nodes) {
  if (!Array.isArray(nodes)) return 0
  return nodes.reduce((sum, n) => sum + 1 + countOutline(n.children), 0)
}

/** 把当前项目的所有资料取一遍。换项目时也走它 */
async function loadAll() {
  if (!projectId.value) return

  // 各块互不依赖，一起发；某一块失败不该让整页空白。
  // 结果变量刻意不叫 characters/settings —— 那会和上面的同名单例 ref 撞名
  const [chapterResult, characterResult, settingResult, outlineResult] = await Promise.allSettled([
    listChapters(projectId.value),
    listCharacters(projectId.value),
    listSettings(projectId.value),
    getOutlineTree(projectId.value)
  ])

  if (chapterResult.status === 'fulfilled') {
    chapters.value = chapterResult.value || []
    // 默认落在最后一章：作者最可能要写的就是"下一章"
    activeChapter.value = chapters.value[chapters.value.length - 1] || null
  }
  if (characterResult.status === 'fulfilled') {
    characters.value = characterResult.value || []
  }
  if (settingResult.status === 'fulfilled') {
    settings.value = settingResult.value || []
  }
  if (outlineResult.status === 'fulfilled') {
    outlineTree.value = outlineResult.value || []
  }
  counts.value = {
    characters: characters.value.length,
    settings: settings.value.length,
    outline: countOutline(outlineTree.value),
    chapters: chapters.value.length
  }

  try {
    // 主副两套上下文一起取：它们材料侧重与预算都不同，作者该同时看得到
    const [plannerReport, writerReport] = await Promise.allSettled([
      getContextReport(projectId.value, 'PLANNER'),
      getContextReport(projectId.value, 'WRITER')
    ])
    reports.value = {
      PLANNER: plannerReport.status === 'fulfilled' ? plannerReport.value : null,
      WRITER: writerReport.status === 'fulfilled' ? writerReport.value : null
    }
  } catch (e) {
    console.warn('读取上下文用量失败', e)
  }
}

onMounted(async () => {
  // 项目列表：工作台自己就能换项目，不必回项目页
  try {
    projects.value = (await listProjects()) || []
  } catch (e) {
    console.warn('读取项目列表失败', e)
  }
  await loadAll()
})
</script>

<style scoped>
.wb {
  display: flex;
  flex-direction: column;
  /* 用 100% 而不是 100vh：工作台渲染在外壳的主内容区里，
     用视口高度会把外壳的页头和导航挤出屏幕 */
  height: 100%;
  overflow: hidden;
}

/* ---------- 顶栏 ---------- */
.wb-top {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 22px;
  height: 58px;
  border-bottom: 1px solid var(--color-line);
  background: var(--color-panel);
  flex-shrink: 0;
}

.wb-brand { display: flex; align-items: baseline; gap: 8px; }
.wb-brand-sub { font-size: 12px; letter-spacing: 0.1em; }

/* ---------- 项目选择 ---------- */
.wb-project { position: relative; }

.wb-project-current {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  background: var(--color-paper-warm);
  cursor: pointer;
  font-family: var(--font-serif);
  font-size: 14px;
  color: var(--color-brown-deep);
  transition: border-color var(--dur) var(--ease-out);
}
.wb-project-current:hover { border-color: var(--color-brown); }
.wb-project-caret { font-size: 10px; color: var(--color-muted); }

.wb-project-menu {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  z-index: 80;
  min-width: 200px;
  max-height: 320px;
  overflow-y: auto;
  padding: 4px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-m);
  background: var(--color-panel);
  box-shadow: var(--shadow-float);
}

.wb-project-item {
  display: block;
  width: 100%;
  padding: 7px 10px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
  color: var(--color-ink);
}
.wb-project-item:hover { background: var(--color-paper-warm); }
.wb-project-item.active { color: var(--color-brown-deep); background: var(--color-paper-warm); }
.wb-project-empty { padding: 10px; font-size: 12px; }

.wb-modes {
  display: flex;
  gap: 4px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  padding: 2px;
}

.wb-mode {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 5px 14px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  font-size: 14px;
  color: var(--color-muted);
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.wb-mode.active { background: var(--color-brown); color: #fff; }
.wb-mode-hint { font-size: 10px; letter-spacing: 0.06em; opacity: 0.75; }

.wb-meta { margin-left: auto; display: flex; align-items: center; gap: 10px; }

/* ---------- 三栏 ---------- */
.wb-body { flex: 1; min-height: 0; display: flex; }

.wb-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }

.wb-right {
  width: 264px;
  border-left: 1px solid var(--color-line);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  transition: width var(--dur-slow) var(--ease-out);
}

/* 蓝图浏览器需要宽度：浏览长文本、框选一段，窄栏做不了这两件事 */
.wb-right.is-wide { width: 440px; }

.wb-right-tabs {
  display: flex;
  gap: 2px;
  padding: 8px 12px 0;
  border-bottom: 1px solid var(--color-line);
  flex-shrink: 0;
}

.wb-right-tab {
  padding: 7px 14px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: none;
  cursor: pointer;
  font-size: 13px;
  color: var(--color-muted);
  transition: color var(--dur) var(--ease-out), border-color var(--dur) var(--ease-out);
}
.wb-right-tab:hover { color: var(--color-brown-deep); }
.wb-right-tab.active {
  color: var(--color-brown-deep);
  border-bottom-color: var(--color-brown);
}

.wb-right-body {
  flex: 1;
  min-height: 0;
  padding: 12px 16px 20px;
}

/* ---------- 侧栏通用 ---------- */
.wb-side-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 4px 10px;
  font-size: 12px;
}

.wb-hr { margin: 12px 0; }

.wb-stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.wb-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  background: var(--color-paper-warm);
  font-size: 12px;
  text-align: left;
  font-family: inherit;
  color: inherit;
}

/* 能点的数字给个明确的可点信号：边框转棕 + 悬停微提 */
.wb-stat.is-link {
  cursor: pointer;
  transition: border-color var(--dur) var(--ease-out), transform var(--dur) var(--ease-out);
}
.wb-stat.is-link:hover {
  border-color: var(--color-brown);
  transform: translateY(-1px);
}
.wb-stat:disabled { cursor: default; }

.wb-stat-num {
  font-family: var(--font-serif);
  font-size: 19px;
  color: var(--color-brown-deep);
}

/* ---------- 上下文用量 ---------- */
.wb-report { padding: 0 4px; margin-bottom: 14px; }

.wb-report-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 5px;
}

.wb-report-name {
  font-family: var(--font-serif);
  font-size: 13px;
  color: var(--color-brown-deep);
}

.wb-report-bar {
  height: 4px;
  border-radius: 2px;
  background: var(--color-brown-soft);
  overflow: hidden;
}

.wb-report-fill {
  height: 100%;
  background: var(--color-brown);
  transition: width var(--dur-slow) var(--ease-out);
}

.wb-report-text { font-size: 12px; line-height: 1.9; margin-top: 6px; }

.wb-warn {
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px solid var(--color-gold);
  border-radius: var(--radius-s);
  font-size: 12px;
  line-height: 1.8;
  color: #8a6608;
}
</style>
