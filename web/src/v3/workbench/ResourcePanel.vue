<script setup>
import { computed, ref } from 'vue'
import Icon from '../../components/Icon.vue'
import V3Tag from '../../components/v3/V3Tag.vue'
import V3Button from '../../components/v3/V3Button.vue'
import V3Modal from '../../components/v3/V3Modal.vue'
import { citationPreview } from '../../utils/citation'

/**
 * 右栏资料面板（v3）：资料概览、人物、设定、蓝图。
 * 每一条都能一键"引用"进对话 —— 引用比让模型自己猜"你说的是哪一段"可靠得多。
 */
const props = defineProps({
  characters: { type: Array, default: () => [] },
  settings: { type: Array, default: () => [] },
  outlineTree: { type: Array, default: () => [] },
  chapters: { type: Array, default: () => [] },
  reports: { type: Object, default: () => ({ PLANNER: null, WRITER: null }) },
  counts: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['cite', 'close', 'open-chapter'])

const tab = ref('info')
const keyword = ref('')
/** 条目详情弹窗；null 表示关闭 */
const detail = ref(null)

const tabs = [
  { key: 'info', label: '资料' },
  { key: 'characters', label: '人物' },
  { key: 'settings', label: '设定' },
  { key: 'outline', label: '蓝图' },
  { key: 'chapters', label: '章节' }
]

/** 点统计卡：直接跳到对应清单（这就是"从数字进到条目"） */
function jumpTo(key) {
  tab.value = key
  keyword.value = ''
}

const STATUS_TEXT = { DRAFT: '草稿', WRITING: '写作中', COMPLETED: '已完成' }
const NODE_TEXT = { VOLUME: '卷', ARC: '段落', CHAPTER: '章节节点' }

const filteredCharacters = computed(() => {
  const q = keyword.value.trim()
  if (!q) return props.characters
  return props.characters.filter((c) => `${c.name} ${c.roleIdentity || ''}`.includes(q))
})
const filteredSettings = computed(() => {
  const q = keyword.value.trim()
  if (!q) return props.settings
  return props.settings.filter((s) => `${s.title} ${s.tags || ''}`.includes(q))
})

const flatOutline = computed(() => {
  const out = []
  const walk = (nodes, depth = 0) => {
    for (const node of nodes || []) {
      out.push({ ...node, depth })
      walk(node.children, depth + 1)
    }
  }
  walk(props.outlineTree)
  return out
})
const filteredOutline = computed(() => {
  const q = keyword.value.trim()
  if (!q) return flatOutline.value
  return flatOutline.value.filter((n) => `${n.name} ${n.summary || ''}`.includes(q))
})

function citeCharacter(item) {
  emit('cite', {
    kind: '人物',
    targetName: item.name,
    text: item.description || item.roleIdentity || item.name,
    locator: `(记录 ID ${item.id})`,
    targetId: item.id
  })
}
function citeSetting(item) {
  emit('cite', {
    kind: '设定',
    targetName: item.title,
    text: item.content || item.title,
    locator: `(记录 ID ${item.id})`,
    targetId: item.id
  })
}
function citeOutline(node) {
  emit('cite', {
    kind: '蓝图',
    targetName: node.name,
    text: node.summary || node.name,
    locator: `(记录 ID ${node.id})`,
    targetId: node.id
  })
}

// ==================== 条目详情 ====================

function openCharacterDetail(item) {
  detail.value = {
    kind: '人物',
    title: item.name || '未命名人物',
    subtitle: item.roleIdentity || '',
    meta: `记录 ID ${item.id}`,
    content: item.description || '（这张人物卡还没有填写设定全文）',
    cite: () => citeCharacter(item)
  }
}

function openSettingDetail(item) {
  const marks = []
  if (item.tags) marks.push(`标签：${item.tags}`)
  if (item.triggerKeywords) marks.push(`触发词：${item.triggerKeywords}`)
  detail.value = {
    kind: '设定',
    title: item.title || '未命名设定',
    subtitle: marks.join('｜'),
    meta: `记录 ID ${item.id}`,
    content: item.content || '（这条设定还没有正文）',
    cite: () => citeSetting(item)
  }
}

function openOutlineDetail(node) {
  detail.value = {
    kind: '蓝图',
    title: node.name || '未命名节点',
    subtitle: `${NODE_TEXT[node.nodeType] || node.nodeType || '节点'}${node.status ? ` · ${node.status}` : ''}`,
    meta: `记录 ID ${node.id}`,
    content: node.summary || '（这个节点还没有填写内容）',
    cite: () => citeOutline(node)
  }
}

function citeFromDetail() {
  detail.value?.cite?.()
  detail.value = null
}

const fmt = (n) => (n == null ? '—' : n)
const reportRows = computed(() => [
  { role: 'PLANNER', label: '策划 Agent', report: props.reports?.PLANNER },
  { role: 'WRITER', label: '写作 Agent', report: props.reports?.WRITER }
])
</script>

<template>
  <aside class="side">
    <header class="side-head">
      <nav class="side-tabs">
        <button v-for="t in tabs" :key="t.key" class="side-tab" :class="{ active: tab === t.key }" @click="tab = t.key; keyword = ''">
          {{ t.label }}
        </button>
      </nav>
      <button class="side-close" title="收起资料栏" @click="emit('close')">
        <Icon name="close" :size="14" />
      </button>
    </header>

    <div class="side-body v3-scroll">
      <!-- ========== 资料概览 ========== -->
      <template v-if="tab === 'info'">
        <div class="stat-grid">
          <button class="stat" title="查看人物档案" @click="jumpTo('characters')">
            <b>{{ fmt(counts.characters) }}</b><span>人物</span>
          </button>
          <button class="stat" title="查看世界观设定" @click="jumpTo('settings')">
            <b>{{ fmt(counts.settings) }}</b><span>设定</span>
          </button>
          <button class="stat" title="查看剧情蓝图" @click="jumpTo('outline')">
            <b>{{ fmt(counts.outline) }}</b><span>蓝图节点</span>
          </button>
          <button class="stat" title="查看章节" @click="jumpTo('chapters')">
            <b>{{ fmt(counts.chapters) }}</b><span>章节</span>
          </button>
        </div>

        <h3 class="side-title">上下文</h3>
        <div v-for="row in reportRows" :key="row.role" class="report">
          <div class="report-head">
            <span>{{ row.label }}</span>
            <span class="report-pct">{{ row.report?.usagePercent ?? 0 }}%</span>
          </div>
          <div class="bar"><i :style="{ width: Math.min(row.report?.usagePercent || 0, 100) + '%' }"></i></div>
          <p class="report-meta">
            {{ (row.report?.usedTokens || 0).toLocaleString() }} / {{ (row.report?.usableTokens || 0).toLocaleString() }} token
            · 装入 {{ row.report?.keptSections?.length ?? 0 }} 段
            <template v-if="row.report?.trimmedSections?.length">，裁掉 {{ row.report.trimmedSections.length }} 段</template>
          </p>
        </div>
        <p class="side-note">装得下就全塞 —— 筛选必然丢东西。这里显示的是真实装填量。</p>

        <h3 class="side-title">怎么用右栏</h3>
        <p class="side-note">
          点任意条目右下角的引用按钮，它会带着记录 ID 进入对话。改稿时这比"你猜我说的是哪段"可靠得多。
        </p>
      </template>

      <!-- ========== 人物 ========== -->
      <template v-else-if="tab === 'characters'">
        <input v-model="keyword" class="side-search" placeholder="搜姓名或身份…" />
        <div
          v-for="item in filteredCharacters"
          :key="item.id"
          class="entry entry-clickable"
          role="button"
          tabindex="0"
          title="点击查看完整人物卡"
          @click="openCharacterDetail(item)"
          @keydown.enter="openCharacterDetail(item)"
          @keydown.space.prevent="openCharacterDetail(item)"
        >
          <div class="entry-head">
            <span class="entry-title">{{ item.name }}</span>
            <V3Tag v-if="item.roleIdentity" tone="gold">{{ citationPreview(item.roleIdentity, 12) }}</V3Tag>
            <button class="cite-btn" title="引用进对话" @click.stop="citeCharacter(item)">
              <Icon name="quote" :size="13" />
            </button>
          </div>
          <p class="entry-text">{{ citationPreview(item.description, 90) || '暂无设定全文' }}</p>
          <span class="entry-more">查看完整人物卡</span>
        </div>
        <p v-if="!filteredCharacters.length" class="side-note">还没有人物档案。在策划对话里让 Agent 起草，确认后会出现在这里。</p>
      </template>

      <!-- ========== 设定 ========== -->
      <template v-else-if="tab === 'settings'">
        <input v-model="keyword" class="side-search" placeholder="搜标题或标签…" />
        <div
          v-for="item in filteredSettings"
          :key="item.id"
          class="entry entry-clickable"
          role="button"
          tabindex="0"
          title="点击查看完整设定"
          @click="openSettingDetail(item)"
          @keydown.enter="openSettingDetail(item)"
          @keydown.space.prevent="openSettingDetail(item)"
        >
          <div class="entry-head">
            <span class="entry-title">{{ item.title }}</span>
            <button class="cite-btn" title="引用进对话" @click.stop="citeSetting(item)">
              <Icon name="quote" :size="13" />
            </button>
          </div>
          <p class="entry-text">{{ citationPreview(item.content, 100) }}</p>
          <span class="entry-more">查看完整设定</span>
        </div>
        <p v-if="!filteredSettings.length" class="side-note">还没有世界观设定。</p>
      </template>

      <!-- ========== 章节 ========== -->
      <template v-else-if="tab === 'chapters'">
        <div v-for="(item, index) in chapters" :key="item.id" class="chapter-row" @click="emit('open-chapter', item)">
          <span class="chapter-no">{{ String(index + 1).padStart(2, '0') }}</span>
          <span class="entry-title">{{ item.title }}</span>
          <span class="chapter-status" :class="{ done: item.status === 'COMPLETED' }">
            {{ STATUS_TEXT[item.status] || item.status }}
          </span>
          <Icon name="arrowRight" :size="12" class="chapter-arrow" />
        </div>
        <p v-if="!chapters.length" class="side-note">
          还没有章节。在写作模式里新建一章，写完点「标记完成」就会进入成书。
        </p>
      </template>

      <!-- ========== 蓝图 ========== -->
      <template v-else>
        <input v-model="keyword" class="side-search" placeholder="搜节点名或摘要…" />
        <div
          v-for="node in filteredOutline"
          :key="node.id"
          class="outline-row entry-clickable"
          role="button"
          tabindex="0"
          title="点击查看完整蓝图节点"
          :style="{ paddingLeft: 10 + node.depth * 14 + 'px' }"
          @click="openOutlineDetail(node)"
          @keydown.enter="openOutlineDetail(node)"
          @keydown.space.prevent="openOutlineDetail(node)"
        >
          <span class="outline-type">{{ node.nodeType === 'VOLUME' ? '卷' : node.nodeType === 'ARC' ? '段' : '章' }}</span>
          <span class="entry-title">{{ node.name }}</span>
          <button class="cite-btn" title="引用进对话" @click.stop="citeOutline(node)">
            <Icon name="quote" :size="13" />
          </button>
        </div>
        <p v-if="!filteredOutline.length" class="side-note">还没有蓝图。让策划 Agent 把走向落成蓝图节点。</p>
      </template>
    </div>

    <!-- 条目详情（人物/设定/蓝图全文） -->
    <V3Modal
      :visible="!!detail"
      :title="detail?.title || '详情'"
      width="660px"
      @close="detail = null"
    >
      <div v-if="detail" class="detail">
        <div class="detail-meta">
          <V3Tag tone="gold">{{ detail.kind }}</V3Tag>
          <span v-if="detail.subtitle" class="detail-sub">{{ detail.subtitle }}</span>
        </div>
        <p class="detail-id">{{ detail.meta }}</p>
        <div class="detail-body v3-scroll">{{ detail.content }}</div>
      </div>
      <template #footer>
        <V3Button variant="ghost" @click="detail = null">关闭</V3Button>
        <V3Button v-if="detail?.cite" @click="citeFromDetail">
          <template #icon><Icon name="quote" :size="13" /></template>
          引用进对话
        </V3Button>
      </template>
    </V3Modal>
  </aside>
</template>

<style scoped>
.side {
  width: var(--panel-width, 320px);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--v3-hairline);
  background: var(--v3-inset);
}
.side-head { display: flex; align-items: center; gap: 6px; padding: 16px 12px 8px; }
.side-tabs { display: flex; gap: 2px; flex: 1; flex-wrap: wrap; }
.side-tab {
  padding: 5px 10px;
  border: 0;
  border-radius: var(--v3-r-pill);
  background: transparent;
  color: var(--v3-text-faint);
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
  transition: background var(--v3-dur-2), color var(--v3-dur-2);
}
.side-tab:hover { color: var(--v3-text-soft); }
.side-tab.active { color: var(--v3-text); background: var(--v3-surface-2); }
.side-close {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--v3-text-faint);
  cursor: pointer;
}
.side-close:hover { background: var(--v3-surface-2); color: var(--v3-text); }
.side-body { flex: 1; min-height: 0; padding: 6px 14px 24px; }

.stat-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 20px; }
.stat {
  display: block;
  width: 100%;
  padding: 12px 14px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--v3-dur-2), background var(--v3-dur-2), transform var(--v3-dur-2) var(--v3-ease-spring);
}
.stat:hover {
  border-color: var(--v3-hairline-gold);
  background: var(--v3-surface-2);
  transform: translateY(-1px);
}
.stat:active { transform: translateY(0) scale(0.995); }
.stat b { display: block; font-family: var(--v3-font-serif); font-size: 22px; font-weight: 600; color: var(--v3-text); }
.stat span { font-size: 10.5px; letter-spacing: 0.16em; color: var(--v3-text-faint); }

.chapter-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 10px;
  margin-bottom: 4px;
  border: 1px solid transparent;
  border-radius: var(--v3-r-xs);
  cursor: pointer;
  transition: background var(--v3-dur-2), border-color var(--v3-dur-2);
}
.chapter-row:hover { background: var(--v3-surface-1); border-color: var(--v3-hairline); }
.chapter-no { flex-shrink: 0; width: 20px; font-family: var(--v3-font-mono); font-size: 10px; color: var(--v3-text-faint); }
.chapter-row .entry-title { flex: 1; min-width: 0; }
.chapter-status { flex-shrink: 0; font-size: 9.5px; letter-spacing: 0.08em; color: var(--v3-text-faint); }
.chapter-status.done { color: var(--v3-jade); }
.chapter-arrow { flex-shrink: 0; opacity: 0; transform: translateX(-3px); transition: opacity var(--v3-dur-2), transform var(--v3-dur-2); color: var(--v3-gold); }
.chapter-row:hover .chapter-arrow { opacity: 1; transform: translateX(0); }

.side-title { margin: 18px 0 10px; font-family: var(--v3-font-serif); font-size: 13px; letter-spacing: 0.12em; color: var(--v3-text-soft); }
.report { margin-bottom: 14px; }
.report-head { display: flex; justify-content: space-between; font-size: 11.5px; color: var(--v3-text-muted); margin-bottom: 6px; }
.report-pct { font-family: var(--v3-font-mono); color: var(--v3-gold); }
.bar { height: 4px; border-radius: var(--v3-r-pill); background: var(--v3-surface-2); overflow: hidden; }
.bar i { display: block; height: 100%; border-radius: inherit; background: var(--v3-grad-gold); transition: width var(--v3-dur-4) var(--v3-ease-out); }
.report-meta { margin: 7px 0 0; font-size: 10.5px; line-height: 1.7; color: var(--v3-text-faint); }
.side-note { font-size: 11.5px; line-height: 1.9; color: var(--v3-text-faint); }

.side-search {
  width: 100%;
  height: 34px;
  margin-bottom: 12px;
  padding: 0 12px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  color: var(--v3-text);
  font-size: 12px;
  outline: none;
}
.side-search:focus { border-color: var(--v3-hairline-gold); }

.entry {
  padding: 11px 12px;
  margin-bottom: 8px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  transition: border-color var(--v3-dur-2), background var(--v3-dur-2);
}
.entry:hover { border-color: var(--v3-hairline-gold); background: var(--v3-surface-2); }
.entry-head { display: flex; align-items: center; gap: 8px; }
.entry-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--v3-font-serif);
  font-size: 13.5px;
  letter-spacing: 0.02em;
  color: var(--v3-text);
}
.entry-text { margin: 7px 0 0; font-size: 11.5px; line-height: 1.8; color: var(--v3-text-muted); }
.cite-btn {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border: 1px solid var(--v3-hairline);
  border-radius: 6px;
  background: transparent;
  color: var(--v3-text-faint);
  cursor: pointer;
  transition: color var(--v3-dur-2), border-color var(--v3-dur-2);
}
.cite-btn:hover { color: var(--v3-gold-bright); border-color: var(--v3-hairline-gold); }

.outline-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--v3-r-xs);
}
.outline-row:hover { background: var(--v3-surface-1); }
.entry-clickable { cursor: pointer; }
.entry-clickable:focus-visible { outline: 2px solid var(--v3-gold); outline-offset: 2px; }
.entry-clickable:hover { border-color: var(--v3-hairline-gold); background: var(--v3-surface-2); }
.entry-clickable .entry-more {
  display: inline-block;
  margin-top: 8px;
  font-size: 10px;
  letter-spacing: 0.12em;
  color: var(--v3-gold);
  opacity: 0;
  transform: translateY(-2px);
  transition: opacity var(--v3-dur-2), transform var(--v3-dur-2);
}
.entry-clickable:hover .entry-more,
.entry-clickable:focus-visible .entry-more { opacity: 1; transform: translateY(0); }

/* ---------- 条目详情 ---------- */
.detail-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.detail-sub { font-size: 11.5px; line-height: 1.7; color: var(--v3-text-faint); }
.detail-id { margin: 10px 0 12px; font-family: var(--v3-font-mono); font-size: 10px; letter-spacing: 0.04em; color: var(--v3-text-faint); }
.detail-body {
  max-height: 56vh;
  overflow-y: auto;
  padding: 16px 18px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  font-family: var(--v3-font-serif);
  font-size: 14px;
  line-height: 2.05;
  color: var(--v3-text-soft);
  white-space: pre-wrap;
  word-break: break-word;
}
.outline-type {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: 4px;
  font-size: 9.5px;
  color: var(--v3-text-faint);
}
@media (max-width: 1200px) {
  .side { width: var(--panel-width, 280px); }
}
@media (max-width: 900px) {
  .side { position: absolute; right: 0; top: 62px; bottom: 0; z-index: 30; background: var(--v3-surface-solid); box-shadow: var(--v3-shadow-float); }
}
</style>
