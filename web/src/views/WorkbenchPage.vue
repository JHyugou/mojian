<template>
  <div class="mj-page wb">
    <!-- ==================== 顶栏 ==================== -->
    <header class="wb-top">
      <div class="wb-brand">
        <span class="mj-title mj-title-md">墨笺</span>
        <span class="mj-muted wb-brand-sub">创作工作台</span>
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
        <span v-if="report" class="mj-tag" :class="{ 'mj-tag-brown': report.usagePercent > 60 }">
          上下文 {{ report.usagePercent }}%
        </span>
        <button class="mj-btn mj-btn-text" @click="$router.push('/')">返回项目</button>
      </div>
    </header>

    <!-- ==================== 主体三栏 ==================== -->
    <div class="wb-body">
      <!-- 左：随模式切换。
           策划管整本书的框架，所以它看的是蓝图树；写作管某一章，所以它看的是章节目录。
           两边共用一个左栏、内容不同，比做两套布局更贴合"两个 Agent 各管一摊"。 -->
      <aside class="wb-left">
        <template v-if="mode === 'planner'">
          <div class="wb-side-head">
            <span class="mj-title mj-title-sm">剧情蓝图</span>
            <span class="mj-muted">{{ counts.outline }} 节点</span>
          </div>
          <div class="wb-left-list mj-scroll">
            <button
              v-for="node in flatOutline"
              :key="node.id"
              class="wb-outline-item"
              :style="{ paddingLeft: 10 + node._depth * 14 + 'px' }"
              :class="{ 'is-volume': node.nodeType === 'VOLUME' }"
              @click="pickOutlineNode(node)"
            >
              <span class="wb-outline-name">{{ node.name || '未命名' }}</span>
              <span class="mj-tag">{{ nodeTypeLabel(node.nodeType) }}</span>
            </button>
            <div v-if="!flatOutline.length" class="wb-empty-hint mj-muted">
              还没有蓝图。<br />
              在右边和策划 Agent 聊，它会帮你把走向规划成蓝图节点。
            </div>
          </div>
        </template>

        <template v-else>
          <div class="wb-side-head">
            <span class="mj-title mj-title-sm">章节目录</span>
            <div class="wb-side-head-right">
              <span class="mj-muted">{{ chapters.length }} 章</span>
              <button class="wb-icon-btn" title="新建章节" @click="toggleNewChapter">＋</button>
            </div>
          </div>

          <!-- 新建章节：原来在「章节创作」页里，那一页被工作台取代后挪到这里 -->
          <div v-if="creatingChapter" class="wb-new-row mj-fade">
            <input
              v-model="newChapterTitle"
              class="mj-input"
              placeholder="章节标题，回车创建"
              @keydown.enter="createNewChapter"
              @keydown.esc="toggleNewChapter"
            />
          </div>
          <p v-if="sideError" class="wb-side-error">{{ sideError }}</p>

          <div class="wb-left-list mj-scroll">
            <button
              v-for="(c, i) in chapters"
              :key="c.id"
              class="wb-chapter"
              :class="{ active: c.id === activeChapter?.id }"
              @click="pickChapter(c)"
            >
              <span class="wb-chapter-no">{{ i + 1 }}</span>
              <span class="wb-chapter-title">{{ c.title }}</span>
              <span v-if="c.status === 'COMPLETED'" class="mj-tag">完成</span>
            </button>
            <div v-if="!chapters.length" class="wb-empty-hint mj-muted">
              这本书还没有章节。<br />先去「剧情蓝图」里规划，或在这里和策划 Agent 聊。
            </div>
          </div>
        </template>
      </aside>

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
            :blueprint="activeBlueprint"
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
            </div>
            <div v-if="report" class="wb-report">
              <div class="wb-report-bar">
                <div
                  class="wb-report-fill"
                  :style="{ width: Math.min(report.usagePercent, 100) + '%' }"
                ></div>
              </div>
              <p class="mj-muted wb-report-text">
                {{ report.usedTokens }} / {{ report.usableTokens }} token
              </p>
              <p class="mj-muted wb-report-text">
                装入 {{ report.keptSections?.length || 0 }} 段{{
                  report.trimmedSections?.length ? `，裁掉 ${report.trimmedSections.length} 段` : ''
                }}
              </p>
              <!-- 到 80% 就该启用检索了；这件事该由数据提醒，不该靠人凭感觉判断 -->
              <p v-if="report.usagePercent > 80" class="wb-warn">
                上下文已用到 {{ report.usagePercent }}%。<br />
                再写下去就该启用检索了 —— 现在还是全部塞进去（更准，但空间有限）。
              </p>
              <p v-else class="mj-muted wb-report-text">
                装得下就全塞，不做检索 —— 筛选必然丢东西。
              </p>
            </div>
            <div v-else class="mj-muted wb-report-text">正在读取…</div>
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
const counts = ref({ characters: 0, settings: 0, outline: 0 })
const report = ref(null)

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

const creatingChapter = ref(false)
const newChapterTitle = ref('')
const sideError = ref('')

function toggleNewChapter() {
  creatingChapter.value = !creatingChapter.value
  newChapterTitle.value = ''
  sideError.value = ''
}

async function createNewChapter() {
  const title = newChapterTitle.value.trim()
  if (!title) return
  try {
    const created = await createChapter(projectId.value, { title, status: 'DRAFT' })
    chapters.value = [...chapters.value, created]
    // 新建完直接切过去写：作者点"新建"多半就是想马上写这一章
    activeChapter.value = created
    mode.value = 'writer'
    creatingChapter.value = false
    newChapterTitle.value = ''
    sideError.value = ''
  } catch (e) {
    sideError.value = e.message || '新建章节失败'
  }
}

function pickOutlineNode(node) {
  // 点蓝图节点 → 找它对应的章节；有就直接切过去写作，没有就提示
  const matched = chapters.value.find((c) => c.outlineNodeId === node.id)
  if (matched) {
    activeChapter.value = matched
    mode.value = 'writer'
  }
}

function countOutline(nodes) {
  if (!Array.isArray(nodes)) return 0
  return nodes.reduce((sum, n) => sum + 1 + countOutline(n.children), 0)
}

onMounted(async () => {
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
    report.value = await getContextReport(projectId.value, 'PLANNER')
  } catch (e) {
    console.warn('读取上下文用量失败', e)
  }
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

.wb-left {
  width: 232px;
  border-right: 1px solid var(--color-line);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.wb-left-list { flex: 1; min-height: 0; padding: 8px; }

.wb-chapter {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
  color: var(--color-ink);
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.wb-chapter:hover { background: var(--color-paper-warm); }
.wb-chapter.active { background: var(--color-paper-warm); color: var(--color-brown-deep); }

.wb-chapter-no {
  width: 18px;
  flex-shrink: 0;
  text-align: right;
  font-size: 12px;
  color: var(--color-muted);
}
.wb-chapter-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wb-empty-hint { padding: 16px 10px; font-size: 12px; line-height: 1.9; }

.wb-side-head-right { display: flex; align-items: center; gap: 6px; }

.wb-icon-btn {
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
.wb-icon-btn:hover { border-color: var(--color-brown); }

.wb-new-row { padding: 0 8px 8px; }
.wb-side-error {
  padding: 0 12px 8px;
  font-size: 12px;
  color: var(--color-danger);
}

/* 蓝图节点：按层级缩进，卷/段/章用不同类型标签区分 */
.wb-outline-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 10px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
  color: var(--color-ink);
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.wb-outline-item:hover { background: var(--color-paper-warm); }
.wb-outline-item.is-volume .wb-outline-name {
  font-family: var(--font-serif);
  color: var(--color-brown-deep);
}

.wb-outline-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

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
.wb-report { padding: 0 4px; }

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
