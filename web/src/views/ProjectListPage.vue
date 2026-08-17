<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listProjects, createProject, deleteProject } from '../api/project'
import { useProjectStore } from '../stores/project'
import { useMessageStore } from '../stores/message'
import AppModal from '../components/AppModal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'

const router = useRouter()
const projectStore = useProjectStore()
const message = useMessageStore()

const projects = ref([])
const loading = ref(false)

const showCreate = ref(false)
const saving = ref(false)
const form = reactive({
  name: '',
  intro: '',
  genre: '',
  style: '',
  writingRules: ''
})

const deleteTarget = ref(null)
const deleting = ref(false)

async function load() {
  loading.value = true
  try {
    projects.value = await listProjects()
  } catch (e) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCreate() {
  Object.assign(form, { name: '', intro: '', genre: '', style: '', writingRules: '' })
  showCreate.value = true
}

async function submitCreate() {
  if (!form.name.trim()) {
    message.error('请填写项目名称')
    return
  }
  saving.value = true
  try {
    const created = await createProject({ ...form, name: form.name.trim() })
    message.success('项目已创建')
    showCreate.value = false
    await load()
    enterProject(created)
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

function enterProject(project) {
  projectStore.select(project)
  router.push('/outline')
}

function askDelete(project) {
  deleteTarget.value = project
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await deleteProject(deleteTarget.value.id)
    message.success(`项目「${deleteTarget.value.name}」已删除`)
    if (projectStore.currentId === deleteTarget.value.id) {
      projectStore.clear()
    }
    deleteTarget.value = null
    await load()
  } catch (e) {
    message.error(e.message)
  } finally {
    deleting.value = false
  }
}

function fmtTime(value) {
  if (!value) return ''
  const date = new Date(String(value).replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <section>
    <div class="page-head">
      <h1 class="page-title">创作项目</h1>
      <button class="btn btn-primary" type="button" @click="openCreate">＋ 新建项目</button>
    </div>
    <hr class="ink-divider" />

    <p v-if="loading" class="page-hint">加载中…</p>

    <div v-else-if="projects.length === 0" class="empty-state card">
      <svg viewBox="0 0 120 48" class="empty-art" aria-hidden="true">
        <path d="M6 40 Q30 8 52 34 Q70 12 88 32 Q100 20 114 38" fill="none"
              stroke="var(--color-dai-deep)" stroke-width="2" opacity="0.6" />
        <circle cx="96" cy="14" r="4" fill="var(--color-cinnabar)" opacity="0.8" />
      </svg>
      <p class="empty-title">远山已备好，静候落笔</p>
      <p class="empty-desc">
        创建一个创作项目，录入人物档案、故事设定与剧情蓝图，<br />
        墨笺将按章节为你组装上下文，辅助生成剧情方案与正文。
      </p>
    </div>

    <div v-else class="project-grid">
      <article
        v-for="project in projects"
        :key="project.id"
        class="card project-card"
        tabindex="0"
        @click="enterProject(project)"
        @keydown.enter="enterProject(project)"
      >
        <div class="project-card-head">
          <h2 class="project-name">{{ project.name }}</h2>
          <span v-if="project.genre" class="tag">{{ project.genre }}</span>
        </div>
        <p class="project-intro">{{ project.intro || '暂无简介' }}</p>
        <div class="project-card-foot">
          <span class="project-time">更新于 {{ fmtTime(project.updatedAt) }}</span>
          <button
            class="btn btn-secondary btn-mini"
            type="button"
            @click.stop="askDelete(project)"
          >
            删除
          </button>
        </div>
      </article>
    </div>

    <AppModal :visible="showCreate" title="新建创作项目" @close="showCreate = false">
      <form class="form" @submit.prevent="submitCreate">
        <label class="form-label">
          项目名称 <span class="required">*</span>
          <input v-model.trim="form.name" class="form-input" maxlength="100" placeholder="如：山河故人" />
        </label>
        <label class="form-label">
          题材
          <input v-model.trim="form.genre" class="form-input" maxlength="50" placeholder="如：仙侠 / 悬疑 / 都市" />
        </label>
        <label class="form-label">
          整体文风
          <input v-model.trim="form.style" class="form-input" maxlength="200" placeholder="如：冷峻克制，重意象与留白" />
        </label>
        <label class="form-label">
          简介
          <textarea v-model.trim="form.intro" class="form-input" rows="3" maxlength="1000" placeholder="一句话说明这个故事" />
        </label>
        <label class="form-label">
          默认写作规则
          <textarea v-model.trim="form.writingRules" class="form-input" rows="4" placeholder="全局适用的写作要求，如：避免现代词汇、每章2000字左右" />
        </label>
        <div class="form-actions">
          <button class="btn btn-secondary" type="button" @click="showCreate = false">取消</button>
          <button class="btn btn-primary" type="submit" :disabled="saving">
            {{ saving ? '创建中…' : '创建项目' }}
          </button>
        </div>
      </form>
    </AppModal>

    <ConfirmDialog
      :visible="!!deleteTarget"
      title="删除创作项目"
      :message="`确定删除项目「${deleteTarget?.name}」吗？其人物档案、设定、蓝图、章节与全部版本将一并删除，且不可恢复。`"
      confirm-text="删除项目"
      danger
      :loading="deleting"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-title {
  font-size: 22px;
}

.page-hint {
  color: var(--color-muted);
}

.project-grid {
  margin-top: 20px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.project-card {
  padding: 18px 20px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: border-color 0.15s ease, transform 0.15s ease;
}

.project-card:hover,
.project-card:focus-visible {
  border-color: var(--color-dai-deep);
  transform: translateY(-2px);
}

.project-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.project-name {
  font-size: 17px;
}

.project-intro {
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 44px;
}

.project-card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.project-time {
  font-size: 12px;
  color: var(--color-muted);
}

.btn-mini {
  padding: 4px 12px;
  font-size: 12px;
}

.empty-state {
  margin-top: 40px;
  padding: 56px 24px;
  text-align: center;
}

.empty-art {
  width: 160px;
}

.empty-title {
  margin-top: 16px;
  font-family: var(--font-serif);
  font-size: 16px;
}

.empty-desc {
  margin-top: 10px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--color-muted);
}

.form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: var(--color-muted);
}

.required {
  color: var(--color-danger);
}

.form-input {
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  padding: 8px 10px;
  font-size: 14px;
  font-family: var(--font-sans);
  background: var(--color-panel);
  color: var(--color-ink);
  resize: vertical;
}

.form-input:focus {
  outline: none;
  border-color: var(--color-dai-deep);
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 6px;
}
</style>
