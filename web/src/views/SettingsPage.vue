<script setup>
import { onMounted, reactive, ref } from 'vue'
import ProjectGate from '../components/ProjectGate.vue'
import AppModal from '../components/AppModal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import JsonImportDialog from '../components/JsonImportDialog.vue'
import { useProjectStore } from '../stores/project'
import { useMessageStore } from '../stores/message'
import { listSettings, createSetting, updateSetting, deleteSetting } from '../api/setting'
import { exportSettings, importSettings } from '../api/share'

const projectStore = useProjectStore()
const message = useMessageStore()

const settings = ref([])
const keyword = ref('')
const loading = ref(false)
const showForm = ref(false)
const saving = ref(false)
const deleteTarget = ref(null)
const deleting = ref(false)
const editingId = ref(null)
const importing = ref(false)
const showImportDialog = ref(false)

const emptyForm = {
  title: '', content: '', tags: '', triggerKeywords: '',
  priority: 0, chapterScope: '', enabled: true
}
const form = reactive({ ...emptyForm })

async function load() {
  if (!projectStore.currentId) return
  loading.value = true
  try {
    settings.value = await listSettings(projectStore.currentId, keyword.value.trim() || undefined)
  } catch (e) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCreate() {
  Object.assign(form, emptyForm)
  editingId.value = null
  showForm.value = true
}

function openEdit(setting) {
  Object.assign(form, emptyForm, setting)
  editingId.value = setting.id
  showForm.value = true
}

async function doSave(payload, successText) {
  saving.value = true
  try {
    if (editingId.value) {
      await updateSetting(editingId.value, payload)
    } else {
      await createSetting(projectStore.currentId, payload)
    }
    message.success(successText)
    showForm.value = false
    await load()
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function submit() {
  if (!form.title.trim() || !form.content.trim()) {
    message.error('名称和内容为必填项')
    return
  }
  await doSave({ ...form }, editingId.value ? '设定已更新' : '设定已创建')
}

/** 存草稿：名称与内容是后端必填项；其余留空允许，保存后保持停用 */
async function submitDraft() {
  if (!form.title.trim() || !form.content.trim()) {
    message.error('存草稿需至少填写名称与内容')
    return
  }
  await doSave({ ...form, enabled: false }, '设定已存为草稿（停用状态，不参与生成）')
}

async function doExport() {
  try {
    const data = await exportSettings(projectStore.currentId, null)
    const text = JSON.stringify({ format: 'mojian-settings/v1', version: 1, data }, null, 2)
    const blob = new Blob([text], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = '世界观设定集.json'
    a.click()
    URL.revokeObjectURL(url)
    message.success(`已导出 ${data.length} 条设定`)
  } catch (e) {
    message.error(e.message)
  }
}

async function doImport(payload) {
  importing.value = true
  try {
    const result = await importSettings(projectStore.currentId, payload)
    message.success(`成功导入 ${result.imported} 条设定`)
    showImportDialog.value = false
    await load()
  } catch (e) {
    message.error(e.message)
  } finally {
    importing.value = false
  }
}

async function confirmDelete() {
  deleting.value = true
  try {
    await deleteSetting(deleteTarget.value.id)
    message.success(`设定「${deleteTarget.value.title}」已删除`)
    deleteTarget.value = null
    await load()
  } catch (e) {
    message.error(e.message)
  } finally {
    deleting.value = false
  }
}

function tagList(setting) {
  return (setting.tags || '').split(/[,，]/).map(t => t.trim()).filter(Boolean)
}
</script>

<template>
  <ProjectGate>
    <section>
      <div class="page-head">
        <h1 class="page-title">故事设定库</h1>
        <div class="toolbar">
          <input v-model.trim="keyword" class="form-input search-input" placeholder="搜索名称 / 标签 / 关键词" @keyup.enter="load" />
          <button class="btn btn-secondary" type="button" @click="load">搜索</button>
          <button class="btn btn-secondary" type="button" @click="doExport">导出</button>
          <button class="btn btn-secondary" type="button" @click="showImportDialog = true">导入</button>
          <button class="btn btn-primary" type="button" @click="openCreate">＋ 新建设定</button>
        </div>
      </div>
      <hr class="ink-divider" />

      <p v-if="loading" class="page-hint">加载中…</p>
      <div v-else-if="settings.length === 0" class="empty-state card">
        <p class="empty-title">世界观尚在雾中</p>
        <p class="empty-desc">
          设定条目按「适用章节范围 + 触发关键词 + 优先级」参与上下文组装，<br />
          例如：范围 3-30、关键词"剑冢、雪山"的禁地设定，只会在相关章节注入。
        </p>
      </div>

      <div v-else class="setting-list">
        <article v-for="s in settings" :key="s.id" class="card setting-card">
          <div class="card-head">
            <h2 class="card-name">{{ s.title }}</h2>
            <span class="tag" :class="s.enabled ? 'tag-success' : ''">{{ s.enabled ? '启用' : '停用' }}</span>
          </div>
          <div class="meta-row">
            <span v-if="s.priority" class="meta">优先级 {{ s.priority }}</span>
            <span v-if="s.chapterScope" class="meta">范围 {{ s.chapterScope }}</span>
            <span v-for="tag in tagList(s).slice(0, 4)" :key="tag" class="meta tag-chip">{{ tag }}</span>
          </div>
          <p class="content clamp">{{ s.content }}</p>
          <p v-if="s.triggerKeywords" class="keywords">触发词：{{ s.triggerKeywords }}</p>
          <div class="card-foot">
            <button class="btn btn-secondary btn-mini" type="button" @click="openEdit(s)">编辑</button>
            <button class="btn btn-secondary btn-mini danger-text" type="button" @click="deleteTarget = s">删除</button>
          </div>
        </article>
      </div>

      <AppModal :visible="showForm" :title="editingId ? '编辑设定' : '新建设定'" width="640px" @close="showForm = false">
        <form class="form" @submit.prevent="submit">
          <label class="form-label">
            条目名称 <span class="required">*</span>
            <input v-model.trim="form.title" class="form-input" maxlength="100" placeholder="如：剑冢、北境三大门派" />
          </label>
          <label class="form-label">
            设定内容 <span class="required">*</span>
            <textarea v-model="form.content" class="form-input" rows="5" placeholder="世界规则、地点、组织、历史、术语……" />
          </label>
          <div class="grid-2">
            <label class="form-label">
              标签（逗号分隔）
              <input v-model.trim="form.tags" class="form-input" placeholder="地点, 禁地" />
            </label>
            <label class="form-label">
              触发关键词（逗号分隔）
              <input v-model.trim="form.triggerKeywords" class="form-input" placeholder="剑冢, 雪山" />
            </label>
          </div>
          <div class="grid-2">
            <label class="form-label">
              优先级（大者先入）
              <input v-model.number="form.priority" class="form-input" type="number" min="0" max="100" />
            </label>
            <label class="form-label">
              适用章节范围
              <input v-model.trim="form.chapterScope" class="form-input" placeholder="如 3-30 或 1,5,9-12，留空为全部" />
            </label>
          </div>
          <label class="form-label row">
            启用
            <input v-model="form.enabled" type="checkbox" class="enabled-check" />
          </label>
          <div class="form-actions">
            <button class="btn btn-secondary" type="button" @click="showForm = false">取消</button>
            <button
              class="btn btn-secondary"
              type="button"
              :disabled="saving"
              title="保存条目但保持停用：不参与生成上下文，需填写名称与内容"
              @click.prevent="submitDraft"
            >
              存草稿
            </button>
            <button class="btn btn-primary" type="submit" :disabled="saving">
              {{ saving ? '保存中…' : '保存设定' }}
            </button>
          </div>
        </form>
      </AppModal>

      <ConfirmDialog
        :visible="!!deleteTarget"
        title="删除设定条目"
        :message="`确定删除设定「${deleteTarget?.title}」吗？`"
        confirm-text="删除"
        danger
        :loading="deleting"
        @close="deleteTarget = null"
        @confirm="confirmDelete"
      />
    </section>
  <JsonImportDialog
    :visible="showImportDialog"
    title="导入世界观设定"
    expected-format="mojian-settings/v1"
    format-label="世界观设定集"
    :loading="importing"
    @close="showImportDialog = false"
    @confirm="doImport"
  />
    </ProjectGate>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
}

.search-input {
  width: 240px;
}

.setting-list {
  margin-top: 20px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.setting-card {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-name {
  font-size: 15px;
}

.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.meta {
  font-size: 12px;
  color: var(--color-dai-deep);
  background: var(--color-dai);
  border-radius: var(--radius-s);
  padding: 1px 8px;
}

.tag-chip {
  color: var(--color-muted);
}

.content {
  font-size: 13px;
  line-height: 1.7;
}

.clamp {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.keywords {
  font-size: 12px;
  color: var(--color-muted);
}

.card-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: auto;
}

.danger-text {
  color: var(--color-danger);
}

.empty-title {
  font-family: var(--font-serif);
  font-size: 16px;
}

.empty-desc {
  margin-top: 10px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--color-muted);
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.row {
  flex-direction: row;
  align-items: center;
  gap: 8px;
}

.enabled-check {
  width: 16px;
  height: 16px;
}
</style>
