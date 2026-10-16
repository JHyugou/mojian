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
      <!-- 左：章节目录 -->
      <aside class="wb-left">
        <div class="wb-side-head">
          <span class="mj-title mj-title-sm">章节目录</span>
          <span class="mj-muted">{{ chapters.length }} 章</span>
        </div>
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
            这本书还没有章节。<br />先去「蓝图」里规划，或直接在这里和策划 Agent 聊。
          </div>
        </div>
      </aside>

      <!-- 中：策划 / 写作 -->
      <main class="wb-main">
        <Transition name="mj-swap" mode="out-in">
          <PlannerPanel
            v-if="mode === 'planner'"
            key="planner"
            :project-id="projectId"
          />
          <WriterPanel
            v-else
            key="writer"
            :project-id="projectId"
            :chapter="activeChapter"
          />
        </Transition>
      </main>

      <!-- 右：资料与用量 -->
      <aside class="wb-right">
        <div class="wb-side-head">
          <span class="mj-title mj-title-sm">本书资料</span>
        </div>

        <div class="wb-stats">
          <div v-for="s in stats" :key="s.label" class="wb-stat">
            <span class="wb-stat-num">{{ s.value }}</span>
            <span class="mj-muted">{{ s.label }}</span>
          </div>
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
          <!-- 到 80% 就该启用检索了；这件事应该由数据提醒，不该靠人凭感觉判断 -->
          <p v-if="report.usagePercent > 80" class="wb-warn">
            上下文已用到 {{ report.usagePercent }}%。<br />
            再写下去就该启用检索了 —— 现在还是全部塞进去（更准，但空间有限）。
          </p>
          <p v-else class="mj-muted wb-report-text">
            装得下就全塞，不做检索 —— 筛选必然丢东西。
          </p>
        </div>
        <div v-else class="mj-muted wb-report-text">正在读取…</div>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useProjectStore } from '../stores/project'
import { listChapters } from '../api/chapter'
import { listCharacters } from '../api/character'
import { listSettings } from '../api/setting'
import { getOutlineTree } from '../api/outline'
import { getContextReport } from '../api/agent'
import PlannerPanel from '../components/workbench/PlannerPanel.vue'
import WriterPanel from '../components/workbench/WriterPanel.vue'

const projectStore = useProjectStore()
const projectId = computed(() => projectStore.currentId)

const modes = [
  { key: 'planner', label: '策划', hint: '管人物、设定、剧情走向' },
  { key: 'writer', label: '写作', hint: '把蓝图写成正文' }
]

const mode = ref('planner')
const chapters = ref([])
const activeChapter = ref(null)
const counts = ref({ characters: 0, settings: 0, outline: 0 })
const report = ref(null)

const stats = computed(() => [
  { label: '人物', value: counts.value.characters },
  { label: '设定', value: counts.value.settings },
  { label: '蓝图节点', value: counts.value.outline },
  { label: '章节', value: chapters.value.length }
])

function pickChapter(chapter) {
  activeChapter.value = chapter
  mode.value = 'writer'
}

function countOutline(nodes) {
  if (!Array.isArray(nodes)) return 0
  return nodes.reduce((sum, n) => sum + 1 + countOutline(n.children), 0)
}

onMounted(async () => {
  if (!projectId.value) return

  // 各块互不依赖，一起发；某一块失败不该让整页空白
  const [chapterList, characters, settings, outline] = await Promise.allSettled([
    listChapters(projectId.value),
    listCharacters(projectId.value),
    listSettings(projectId.value),
    getOutlineTree(projectId.value)
  ])

  if (chapterList.status === 'fulfilled') {
    chapters.value = chapterList.value || []
    // 默认落在最后一章：作者最可能要写的就是"下一章"
    activeChapter.value = chapters.value[chapters.value.length - 1] || null
  }
  counts.value = {
    characters: characters.status === 'fulfilled' ? (characters.value?.length ?? 0) : 0,
    settings: settings.status === 'fulfilled' ? (settings.value?.length ?? 0) : 0,
    outline: outline.status === 'fulfilled' ? countOutline(outline.value) : 0
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

.wb-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }

.wb-right {
  width: 264px;
  border-left: 1px solid var(--color-line);
  padding: 14px 16px 20px;
  overflow-y: auto;
  flex-shrink: 0;
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
}

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
