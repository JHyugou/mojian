<script setup>
import { nextTick, onMounted, reactive, ref } from 'vue'
import ProjectGate from '../components/ProjectGate.vue'
import AppModal from '../components/AppModal.vue'
import GenerationPanel from '../components/GenerationPanel.vue'
import MemoryPanel from '../components/MemoryPanel.vue'
import VersionPanel from '../components/VersionPanel.vue'
import { useProjectStore } from '../stores/project'
import { useMessageStore } from '../stores/message'
import {
  listChapters, createChapter, getChapter, saveChapter
} from '../api/chapter'
import { getOutlineTree } from '../api/outline'

const projectStore = useProjectStore()
const message = useMessageStore()

const chapters = ref([])
const currentId = ref(null)
const chapter = ref(null)
const outlineNodes = ref([])
const loading = ref(false)
const saving = ref(false)
const activeTab = ref('generate')
const selection = ref('')

const showCreate = ref(false)
const creating = ref(false)
const createForm = reactive({ title: '', outlineNodeId: null })
const editorForm = reactive({ title: '', status: 'DRAFT', content: '', createVersion: false, remark: '' })
const editorRef = ref(null)

const statusOptions = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'WRITING', label: '创作中' },
  { value: 'COMPLETED', label: '已完成' }
]

async function loadChapters(keepCurrent = true) {
  if (!projectStore.currentId) return
  loading.value = true
  try {
    chapters.value = await listChapters(projectStore.currentId)
    const exists = keepCurrent && chapters.value.some((c) => c.id === currentId.value)
    if (!exists && chapters.value.length > 0) {
      await selectChapter(chapters.value[0].id)
    } else if (exists) {
      await loadCurrent()
    }
  } catch (e) {
    message.error(e.message)
  } finally {
    loading.value = false
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
  await loadChapters(false)
  await loadOutlineNodes()
})

async function selectChapter(chapterId) {
  currentId.value = chapterId
  selection.value = ''
  await loadCurrent()
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
  } catch (e) {
    message.error(e.message)
  }
}

function openCreate() {
  Object.assign(createForm, { title: '', outlineNodeId: null })
  showCreate.value = true
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
    await loadChapters(true)
    const created = chapters.value.find((c) => c.title === createForm.title)
    if (created) await selectChapter(created.id)
  } catch (e) {
    message.error(e.message)
  } finally {
    creating.value = false
  }
}

async function saveManually() {
  if (!editorForm.title.trim()) {
    message.error('章节标题不能为空')
    return
  }
  saving.value = true
  try {
    await saveChapter(currentId.value, {
      title: editorForm.title.trim(),
      status: editorForm.status,
      content: editorForm.content,
      outlineNodeId: chapter.value?.outlineNodeId || null,
      createVersion: editorForm.createVersion || null,
      remark: editorForm.remark || null
    })
    message.success(editorForm.createVersion ? '已保存并创建版本快照' : '章节已保存')
    await loadChapters(true)
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
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

async function onRefreshed() {
  await loadChapters(true)
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
        <button class="btn btn-primary" type="button" @click="openCreate">＋ 新建章节</button>
      </div>
      <hr class="ink-divider" />

      <div v-if="loading && chapters.length === 0" class="page-hint">加载中…</div>
      <div v-else-if="chapters.length === 0" class="empty-state card">
        <p class="empty-title">落笔第一章</p>
        <p class="empty-desc">创建章节后，右栏将提供 AI 生成、章节记忆与版本管理。</p>
      </div>

      <div v-else class="workspace">
        <div class="chapter-strip">
          <button
            v-for="c in chapters"
            :key="c.id"
            class="chip"
            :class="{ active: c.id === currentId }"
            type="button"
            @click="selectChapter(c.id)"
          >
            {{ c.title }}
            <span v-if="c.currentVersionNo" class="chip-ver">v{{ c.currentVersionNo }}</span>
          </button>
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
              <button class="btn btn-primary" type="button" :disabled="saving" @click="saveManually">
                {{ saving ? '保存中…' : '保存章节' }}
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

      <AppModal :visible="showCreate" title="新建章节" width="460px" @close="showCreate = false">
        <form class="form" @submit.prevent="submitCreate">
          <label class="form-label">
            章节标题 <span class="required">*</span>
            <input v-model.trim="createForm.title" class="form-input" maxlength="200" placeholder="如：第三章 风雪渡口" />
          </label>
          <label class="form-label">
            关联蓝图节点（可选）
            <select v-model="createForm.outlineNodeId" class="form-input">
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
  border-radius: 999px;
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
</style>
