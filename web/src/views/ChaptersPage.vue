<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import ProjectGate from '../components/ProjectGate.vue'
import AppModal from '../components/AppModal.vue'
import GenerationPanel from '../components/GenerationPanel.vue'
import MemoryPanel from '../components/MemoryPanel.vue'
import VersionPanel from '../components/VersionPanel.vue'
import { useProjectStore } from '../stores/project'
import { useMessageStore } from '../stores/message'
import {
  listChapters, createChapter, getChapter, saveChapter, deleteChapter
} from '../api/chapter'
import { getOutlineTree } from '../api/outline'

const projectStore = useProjectStore()
const message = useMessageStore()

const chapters = ref([])
const currentId = ref(null)
const chapter = ref(null)
// 顶栏只显示"打开"的章节；× 仅收起不删除，状态记忆在 localStorage（按项目隔离）
const openChapterIds = ref([])
const outlineNodes = ref([])
const loading = ref(false)
const saving = ref(false)
const activeTab = ref('generate')
const selection = ref('')

const showCreate = ref(false)
const creating = ref(false)
const createForm = reactive({ title: '', outlineNodeId: null })
let autoFilledTitle = null
const editorForm = reactive({ title: '', status: 'DRAFT', content: '', createVersion: false, remark: '' })
const editorRef = ref(null)
let editorSnapshot = null
const draftSaving = ref(false)
const showChapterPicker = ref(false)
const chapterDeleteTarget = ref(null)
const chapterDeleting = ref(false)

const visibleChapters = computed(() =>
  chapters.value.filter((c) => openChapterIds.value.includes(c.id))
)

const statusOptions = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'WRITING', label: '创作中' },
  { value: 'COMPLETED', label: '已完成' }
]

async function loadChapters() {
  if (!projectStore.currentId) return
  loading.value = true
  try {
    chapters.value = await listChapters(projectStore.currentId)
    // 校验：打开列表与当前章若已被删除则清理
    const ids = new Set(chapters.value.map((c) => c.id))
    openChapterIds.value = openChapterIds.value.filter((id) => ids.has(id))
    if (currentId.value && !ids.has(currentId.value)) {
      currentId.value = null
      chapter.value = null
    } else if (currentId.value) {
      await loadCurrent()
    }
    persistTabs()
  } catch (e) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

function persistTabs() {
  if (!projectStore.currentId) return
  localStorage.setItem(
    `mojian.openChapters.${projectStore.currentId}`,
    JSON.stringify(openChapterIds.value)
  )
  localStorage.setItem(
    `mojian.lastChapter.${projectStore.currentId}`,
    currentId.value || ''
  )
}

function restoreTabs() {
  const pid = projectStore.currentId
  if (!pid) return
  try {
    openChapterIds.value = JSON.parse(localStorage.getItem(`mojian.openChapters.${pid}`) || '[]')
    const last = localStorage.getItem(`mojian.lastChapter.${pid}`)
    currentId.value = last || null
  } catch {
    openChapterIds.value = []
  }
}

async function loadOutlineNodes() {
  try {
    const tree = await getOutlineTree(projectStore.currentId)
    const flat = []
    const walk = (nodes) => {
      nodes.forEach((n) => {
        if (n.nodeType === 'CHAPTER') flat.push(n)
        walk(n.children || [])
      })
    }
    walk(tree)
    outlineNodes.value = flat
  } catch {
    outlineNodes.value = []
  }
}

onMounted(async () => {
  if (!projectStore.currentId) return
  restoreTabs()
  await loadChapters()
  await loadOutlineNodes()
})

/** 打开（或聚焦）一个章节：不在顶栏的先加入顶栏 */
async function selectChapter(chapterId) {
  if (!openChapterIds.value.includes(chapterId)) {
    openChapterIds.value = [...openChapterIds.value, chapterId]
  }
  currentId.value = chapterId
  selection.value = ''
  persistTabs()
  await loadCurrent()
}

/** 收起章节：仅从顶栏移除，不删除；若收起的是当前章则腾出编辑区 */
function closeChapter(chapterId) {
  openChapterIds.value = openChapterIds.value.filter((id) => id !== chapterId)
  if (currentId.value === chapterId) {
    const next = openChapterIds.value[openChapterIds.value.length - 1] || null
    if (next) {
      selectChapter(next)
      return
    }
    currentId.value = null
    chapter.value = null
  }
  persistTabs()
}

async function loadCurrent() {
  if (!currentId.value) return
  try {
    chapter.value = await getChapter(currentId.value)
    Object.assign(editorForm, {
      title: chapter.value.title || '',
      status: chapter.value.status || 'DRAFT',
      content: chapter.value.content || '',
      createVersion: false,
      remark: ''
    })
    editorSnapshot = editorDirtyKey()
  } catch (e) {
    message.error(e.message)
  }
}

function openCreate() {
  Object.assign(createForm, { title: '', outlineNodeId: null })
  autoFilledTitle = null
  showCreate.value = true
}

/** 选择蓝图节点时自动以节点名称填充标题；用户手输过自定义标题则不打扰 */
function onOutlineNodeChange() {
  const node = outlineNodes.value.find((n) => n.id === createForm.outlineNodeId)
  if (!node) return
  if (!createForm.title.trim() || createForm.title === autoFilledTitle) {
    createForm.title = node.name
    autoFilledTitle = node.name
  }
}

async function submitCreate() {
  if (!createForm.title.trim()) {
    message.error('请填写章节标题')
    return
  }
  creating.value = true
  try {
    await createChapter(projectStore.currentId, { ...createForm, title: createForm.title.trim() })
    message.success('章节已创建')
    showCreate.value = false
    await loadChapters()
    const created = chapters.value.find((c) => c.title === createForm.title)
    if (created) await selectChapter(created.id)
  } catch (e) {
    message.error(e.message)
  } finally {
    creating.value = false
  }
}

function editorDirtyKey() {
  return JSON.stringify({
    title: editorForm.title.trim(),
    status: editorForm.status,
    content: editorForm.content
  })
}

function editorDirty() {
  return editorSnapshot !== null && editorDirtyKey() !== editorSnapshot
}

/** 保存编辑器内容。promote=true 时草稿状态自动升为创作中（正常保存即收录成书） */
async function doEditorSave(promote, successText) {
  saving.value = true
  try {
    const status = promote && editorForm.status === 'DRAFT' ? 'WRITING' : editorForm.status
    await saveChapter(currentId.value, {
      title: editorForm.title.trim(),
      status,
      content: editorForm.content,
      outlineNodeId: chapter.value?.outlineNodeId || null,
      createVersion: editorForm.createVersion || null,
      remark: editorForm.remark || null
    })
    if (successText) message.success(successText)
    editorSnapshot = editorDirtyKey()
    await loadChapters()
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function saveManually() {
  if (!editorForm.title.trim()) {
    message.error('章节标题不能为空')
    return
  }
  await doEditorSave(true,
    editorForm.createVersion ? '已保存并创建版本快照' : '章节已保存')
}

/** 存草稿：保存内容但强制草稿状态——不收录成书、不导出 */
async function saveDraft() {
  if (!editorForm.title.trim()) {
    message.error('存草稿前请先填写章节标题')
    return
  }
  draftSaving.value = true
  try {
    await saveChapter(currentId.value, {
      title: editorForm.title.trim(),
      status: 'DRAFT',
      content: editorForm.content,
      outlineNodeId: chapter.value?.outlineNodeId || null,
      createVersion: editorForm.createVersion || null,
      remark: editorForm.remark || null
    })
    editorSnapshot = editorDirtyKey()
    editorForm.status = 'DRAFT'
    message.success('已存草稿（不收录成书）')
    await loadChapters()
  } catch (e) {
    message.error(e.message)
  } finally {
    draftSaving.value = false
  }
}

/** 收起章节：有未保存修改时自动保存（保持当前状态，不误降级），然后回到章节条 */
async function collapseChapter() {
  if (editorDirty()) {
    if (!editorForm.title.trim()) {
      message.error('标题为空，无法自动保存；请先补一个标题')
      return
    }
    await doEditorSave(false,
      editorForm.status === 'DRAFT' ? '已自动存草稿' : '已自动保存')
  }
  currentId.value = null
  chapter.value = null
}

/** 编辑器选区跟踪：供改写/扩缩写使用 */
async function captureSelection() {
  await nextTick()
  const el = editorRef.value
  if (!el) return
  const start = el.selectionStart
  const end = el.selectionEnd
  selection.value = end > start ? editorForm.content.slice(start, end) : ''
}

function askDeleteChapter(c) {
  chapterDeleteTarget.value = c
}

async function confirmChapterDelete(c) {
  if (!c) return
  if (!window.confirm(`确定删除「${c.title}」吗？该章节的正文、记忆与版本将一并删除，且不可恢复。`)) {
    return
  }
  chapterDeleting.value = true
  try {
    await deleteChapter(c.id)
    message.success(`「${c.title}」已删除`)
    if (currentId.value === c.id) {
      currentId.value = null
      chapter.value = null
    }
    chapterDeleteTarget.value = null
    await loadChapters()
  } catch (e) {
    message.error(e.message)
  } finally {
    chapterDeleting.value = false
  }
}

async function onRefreshed() {
  await loadChapters()
  await nextTick()
  const el = editorRef.value
  if (el) el.scrollTop = el.scrollHeight
}

const tabs = [
  { key: 'generate', label: 'AI 生成' },
  { key: 'memory', label: '章节记忆' },
  { key: 'versions', label: '版本历史' }
]
</script>

<template>
  <ProjectGate>
    <section class="chapters-page">
      <div class="page-head">
        <h1 class="page-title">章节创作</h1>
      <div class="head-actions">
        <button class="btn btn-secondary" type="button" :disabled="chapters.length === 0" @click="showChapterPicker = true">
          修改已有章节
        </button>
        <button class="btn btn-primary" type="button" @click="openCreate">＋ 新建章节</button>
      </div>
      </div>
      <hr class="ink-divider" />

      <div v-if="loading && chapters.length === 0" class="page-hint">加载中…</div>
      <div v-else-if="chapters.length === 0" class="empty-state card">
        <p class="empty-title">落笔第一章</p>
        <p class="empty-desc">创建章节后，右栏将提供 AI 生成、章节记忆与版本管理。</p>
      </div>

      <div v-else-if="openChapterIds.length === 0" class="empty-state card">
        <p class="empty-title">章节已全部收起</p>
        <p class="empty-desc">点「修改已有章节」重新打开要创作的章节，或新建章节。</p>
      </div>

      <div v-else class="workspace">
        <div class="chapter-strip">
          <div
            v-for="c in visibleChapters"
            :key="c.id"
            class="chip"
            :class="{ active: c.id === currentId }"
            role="tab"
            :aria-selected="c.id === currentId"
            @click="selectChapter(c.id)"
          >
            <span class="chip-title" @click.stop="selectChapter(c.id)">{{ c.title }}</span>
            <span v-if="c.currentVersionNo" class="chip-ver">v{{ c.currentVersionNo }}</span>
            <button
              class="chip-x"
              type="button"
              :aria-label="`收起 ${c.title}`"
              title="收起（不删除，可在「修改已有章节」重新打开）"
              @click.stop="closeChapter(c.id)"
            >×</button>
          </div>
        </div>

        <div v-if="chapter" class="work-columns">
          <div class="editor-column card">
            <div class="editor-head">
              <input v-model="editorForm.title" class="title-input" maxlength="200" placeholder="章节标题" />
              <select v-model="editorForm.status" class="form-input status-select">
                <option v-for="opt in statusOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
            </div>
            <textarea
              ref="editorRef"
              v-model="editorForm.content"
              class="content-editor"
              placeholder="在此书写正文，或由右侧 AI 生成……"
              @mouseup="captureSelection"
              @keyup="captureSelection"
            />
            <div class="save-bar">
              <label class="version-check">
                <input v-model="editorForm.createVersion" type="checkbox" />
                存为版本
              </label>
              <input
                v-if="editorForm.createVersion"
                v-model.trim="editorForm.remark"
                class="form-input remark-input"
                maxlength="200"
                placeholder="版本备注，如：定稿一稿"
              />
              <button
                class="btn btn-secondary"
                type="button"
                :disabled="draftSaving || saving"
                title="保存当前内容但保持草稿状态：不收录成书、不导出"
                @click="saveDraft"
              >
                {{ draftSaving ? '存草稿中…' : '存草稿' }}
              </button>
              <button class="btn btn-primary" type="button" :disabled="saving" @click="saveManually">
                {{ saving ? '保存中…' : '保存章节' }}
              </button>
              <button
                class="btn btn-secondary"
                type="button"
                :disabled="draftSaving || saving"
                title="收起编辑器；未保存的修改会自动保存（草稿保持草稿，创作中保持创作中）"
                @click="collapseChapter"
              >
                收起章节
              </button>
            </div>
          </div>

          <div class="side-column">
            <div class="tabs">
              <button
                v-for="t in tabs"
                :key="t.key"
                class="tab-item"
                :class="{ active: activeTab === t.key }"
                type="button"
                @click="activeTab = t.key"
              >
                {{ t.label }}
              </button>
            </div>
            <div class="tab-body card">
              <GenerationPanel
                v-if="activeTab === 'generate'"
                :key="'gen-' + currentId"
                :chapter-id="currentId"
                :selection="selection"
                @refreshed="onRefreshed"
                @clear-selection="selection = ''"
              />
              <MemoryPanel v-if="activeTab === 'memory'" :key="'mem-' + currentId" :chapter-id="currentId" />
              <VersionPanel
                v-if="activeTab === 'versions'"
                :key="'ver-' + currentId"
                :chapter-id="currentId"
                @refreshed="onRefreshed"
              />
            </div>
          </div>
        </div>
      </div>

      <AppModal :visible="showChapterPicker" title="修改已有章节" width="520px" @close="showChapterPicker = false">
        <div class="picker-list">
          <div
            v-for="c in chapters"
            :key="c.id"
            class="picker-row"
            :class="{ current: c.id === currentId }"
            role="button"
            tabindex="0"
            @click="selectChapter(c.id); showChapterPicker = false"
            @keydown.enter="selectChapter(c.id); showChapterPicker = false"
          >
            <span class="picker-row-title">{{ c.title }}</span>
            <span class="picker-row-meta">
              <span class="tag" :class="{ 'tag-success': c.status !== 'DRAFT' }">
                {{ c.status === 'DRAFT' ? '草稿' : c.status === 'WRITING' ? '创作中' : '已完成' }}
              </span>
              <span v-if="c.currentVersionNo" class="picker-ver">v{{ c.currentVersionNo }}</span>
              <button
                class="picker-delete"
                type="button"
                title="删除此章节"
                @click.stop="confirmChapterDelete(c)"
              >×</button>
            </span>
          </div>
        </div>
        <p class="hint" style="margin-top: 10px">点击任意章节即可载入编辑器继续修改。</p>
      </AppModal>



      <AppModal :visible="showCreate" title="新建章节" width="460px" @close="showCreate = false">
        <form class="form" @submit.prevent="submitCreate">
          <label class="form-label">
            章节标题 <span class="required">*</span>
            <input v-model.trim="createForm.title" class="form-input" maxlength="200" placeholder="如：第三章 风雪渡口" />
          </label>
          <label class="form-label">
            关联蓝图节点（可选）
            <select v-model="createForm.outlineNodeId" class="form-input" @change="onOutlineNodeChange">
              <option :value="null">不关联</option>
              <option v-for="n in outlineNodes" :key="n.id" :value="n.id">{{ n.name }}</option>
            </select>
          </label>
          <div class="form-actions">
            <button class="btn btn-secondary" type="button" @click="showCreate = false">取消</button>
            <button class="btn btn-primary" type="submit" :disabled="creating">
              {{ creating ? '创建中…' : '创建章节' }}
            </button>
          </div>
        </form>
      </AppModal>
    </section>
  </ProjectGate>
</template>

<style scoped>
.chapters-page {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.empty-title {
  font-family: var(--font-serif);
  font-size: 16px;
}

.empty-desc {
  margin-top: 10px;
  font-size: 13px;
  color: var(--color-muted);
}

.workspace {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  flex: 1;
}

.chapter-strip {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.chip {
  border: 1px solid var(--color-line);
  background: var(--color-panel);
  border-radius: var(--radius-s);
  padding: 5px 14px;
  font-size: 13px;
  font-family: var(--font-sans);
  color: var(--color-ink);
  cursor: pointer;
}

.chip.active {
  background: var(--color-dai);
  border-color: var(--color-dai-deep);
  color: var(--color-dai-deep);
  font-weight: 600;
}

.chip-ver {
  margin-left: 4px;
  font-size: 11px;
  color: var(--color-cinnabar);
}

.work-columns {
  display: flex;
  gap: 16px;
  flex: 1;
  min-height: 0;
}

.editor-column {
  flex: 1;
  min-width: 0;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.editor-head {
  display: flex;
  gap: 10px;
}

.title-input {
  flex: 1;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  padding: 8px 10px;
  font-family: var(--font-serif);
  font-size: 16px;
  background: var(--color-panel);
  color: var(--color-ink);
}

.title-input:focus {
  outline: none;
  border-color: var(--color-dai-deep);
}

.status-select {
  width: 110px;
}

.content-editor {
  flex: 1;
  min-height: 320px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  padding: 14px 16px;
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 2;
  background: var(--color-panel);
  color: var(--color-ink);
  resize: none;
}

.content-editor:focus {
  outline: none;
  border-color: var(--color-dai-deep);
}

.save-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.version-check {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--color-muted);
  white-space: nowrap;
}

.remark-input {
  flex: 1;
}

.side-column {
  width: 380px;
  flex: none;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.tab-body {
  flex: 1;
  padding: 14px;
  overflow-y: auto;
  min-height: 320px;
}

.head-actions {
  display: flex;
  gap: 10px;
}

.picker-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 50vh;
  overflow-y: auto;
}

.picker-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  background: var(--color-panel);
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  transition: border-color 0.15s ease;
}

.picker-row:hover {
  border-color: var(--color-ink);
}

.picker-row.current {
  border-color: var(--color-cinnabar);
}

.picker-row-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-ink);
}

.picker-row-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--color-muted);
}

.picker-ver {
  color: var(--color-cinnabar);
}

.picker-delete {
  border: none;
  background: none;
  cursor: pointer;
  color: var(--color-danger);
  font-size: 16px;
  line-height: 1;
  padding: 0 2px;
  opacity: 0.5;
  transition: opacity 0.12s ease;
}

.picker-delete:hover {
  opacity: 1;
}
</style>
