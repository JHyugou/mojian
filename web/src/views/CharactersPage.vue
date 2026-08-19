<script setup>
import { onMounted, reactive, ref } from 'vue'
import ProjectGate from '../components/ProjectGate.vue'
import AppModal from '../components/AppModal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import { useProjectStore } from '../stores/project'
import { useMessageStore } from '../stores/message'
import {
  listCharacters, createCharacter, updateCharacter,
  deleteCharacter, draftDialogueExample
} from '../api/character'

const projectStore = useProjectStore()
const message = useMessageStore()

const characters = ref([])
const loading = ref(false)
const showForm = ref(false)
const saving = ref(false)
const drafting = ref(false)
const deleteTarget = ref(null)
const deleting = ref(false)
const editingId = ref(null)

const emptyForm = {
  name: '', roleIdentity: '', appearance: '', personality: '', coreGoal: '',
  mainConflict: '', relationships: '', speechStyle: '', exampleDialogue: '',
  currentStatus: '', characterArc: '', enabled: true
}
const form = reactive({ ...emptyForm })

async function load() {
  if (!projectStore.currentId) return
  loading.value = true
  try {
    characters.value = await listCharacters(projectStore.currentId)
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

function openEdit(character) {
  Object.assign(form, emptyForm, character)
  editingId.value = character.id
  showForm.value = true
}

async function submit() {
  if (!form.name.trim()) {
    message.error('请填写人物姓名')
    return
  }
  saving.value = true
  try {
    const payload = { ...form, name: form.name.trim() }
    const saved = editingId.value
      ? await updateCharacter(editingId.value, payload)
      : await createCharacter(projectStore.currentId, payload)
    message.success(editingId.value ? '人物已更新' : '人物已创建')
    showForm.value = false
    await load()
    if (!editingId.value) editingId.value = saved.id
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(character) {
  try {
    await updateCharacter(character.id, { ...character, enabled: !character.enabled })
    await load()
  } catch (e) {
    message.error(e.message)
  }
}

/** FR-018：AI 生成对话示例草稿，回填表单，用户确认后随保存生效 */
async function draftExample() {
  if (!editingId.value) {
    message.error('请先保存人物（AI 草稿基于已保存的档案生成）')
    return
  }
  drafting.value = true
  try {
    const draft = await draftDialogueExample(editingId.value, form.draftHint)
    form.exampleDialogue = draft
    message.success('草稿已生成，确认后点击保存')
  } catch (e) {
    message.error(e.message)
  } finally {
    drafting.value = false
  }
}

async function confirmDelete() {
  deleting.value = true
  try {
    await deleteCharacter(deleteTarget.value.id)
    message.success(`人物「${deleteTarget.value.name}」已删除`)
    deleteTarget.value = null
    await load()
  } catch (e) {
    message.error(e.message)
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <ProjectGate>
    <section>
      <div class="page-head">
        <h1 class="page-title">人物档案</h1>
        <button class="btn btn-primary" type="button" @click="openCreate">＋ 新建人物</button>
      </div>
      <hr class="ink-divider" />

      <p v-if="loading" class="page-hint">加载中…</p>
      <div v-else-if="characters.length === 0" class="empty-state card">
        <p class="empty-title">尚无人物落墨</p>
        <p class="empty-desc">创建主要人物，档案将参与章节生成的上下文组装。</p>
      </div>

      <div v-else class="character-grid">
        <article v-for="c in characters" :key="c.id" class="card character-card">
          <div class="card-head">
            <h2 class="card-name">{{ c.name }}</h2>
            <span class="tag" :class="c.enabled ? 'tag-success' : ''">{{ c.enabled ? '启用' : '停用' }}</span>
          </div>
          <p v-if="c.roleIdentity" class="card-line">{{ c.roleIdentity }}</p>
          <p v-if="c.personality" class="card-line muted clamp">{{ c.personality }}</p>
          <div class="card-foot">
            <span class="ops">
              <button class="btn btn-secondary btn-mini" type="button" @click="openEdit(c)">编辑</button>
              <button class="btn btn-secondary btn-mini" type="button" @click="toggleEnabled(c)">
                {{ c.enabled ? '停用' : '启用' }}
              </button>
            </span>
            <button class="btn btn-secondary btn-mini danger-text" type="button" @click="deleteTarget = c">删除</button>
          </div>
        </article>
      </div>

      <AppModal :visible="showForm" :title="editingId ? '编辑人物' : '新建人物'" width="640px" @close="showForm = false">
        <form class="form" @submit.prevent="submit">
          <label class="form-label">
            姓名 <span class="required">*</span>
            <input v-model.trim="form.name" class="form-input" maxlength="50" />
          </label>
          <label class="form-label">
            身份 / 角色定位
            <input v-model.trim="form.roleIdentity" class="form-input" maxlength="200" placeholder="如：北境剑修、客栈少东家" />
          </label>

          <details class="advanced-group">
            <summary>进阶设定（外貌 · 性格 · 目标 · 矛盾 · 关系）</summary>
            <div class="form advanced-form">
              <label class="form-label">外貌描述<textarea v-model.trim="form.appearance" class="form-input" rows="2" /></label>
              <label class="form-label">性格特征<textarea v-model.trim="form.personality" class="form-input" rows="2" /></label>
              <label class="form-label">核心目标<input v-model.trim="form.coreGoal" class="form-input" /></label>
              <label class="form-label">主要矛盾<input v-model.trim="form.mainConflict" class="form-input" /></label>
              <label class="form-label">人物关系<textarea v-model.trim="form.relationships" class="form-input" rows="2" placeholder="如：与林家为世仇；师从无名老者" /></label>
            </div>
          </details>

          <details class="advanced-group">
            <summary>声口与状态（语言风格 · 对话示例 · 当前状态 · 弧线）</summary>
            <div class="form advanced-form">
              <label class="form-label">语言风格<input v-model.trim="form.speechStyle" class="form-input" placeholder="如：短句多、爱用反问" /></label>
              <label class="form-label">
                补充要求（可选，供 AI 参考）
                <input v-model.trim="form.draftHint" class="form-input" maxlength="200" placeholder="如：口头禅是'无所谓'，提及过去时总在回避" />
              </label>
              <div class="form-label">
                对话示例
                <div class="dialogue-row">
                  <textarea v-model="form.exampleDialogue" class="form-input" rows="4" placeholder="可点击右侧按钮由 AI 生成草稿，确认后保存" />
                  <button class="btn btn-secondary" type="button" :disabled="drafting" @click="draftExample">
                    {{ drafting ? '生成中…' : '✦ AI 草稿' }}
                  </button>
                </div>
              </div>
              <label class="form-label">当前状态<input v-model.trim="form.currentStatus" class="form-input" placeholder="如：身中寒毒，对同伴疑心渐起" /></label>
              <label class="form-label">人物发展弧线<textarea v-model.trim="form.characterArc" class="form-input" rows="2" /></label>
            </div>
          </details>

          <label class="form-label row">
            参与上下文组装
            <input v-model="form.enabled" type="checkbox" class="enabled-check" />
            <span class="hint">{{ form.enabled ? '启用中：默认参与生成上下文' : '停用：不参与生成' }}</span>
          </label>

          <div class="form-actions">
            <button class="btn btn-secondary" type="button" @click="showForm = false">取消</button>
            <button class="btn btn-primary" type="submit" :disabled="saving">
              {{ saving ? '保存中…' : '保存人物' }}
            </button>
          </div>
        </form>
      </AppModal>

      <ConfirmDialog
        :visible="!!deleteTarget"
        title="删除人物档案"
        :message="`确定删除人物「${deleteTarget?.name}」吗？该操作不可恢复。`"
        confirm-text="删除"
        danger
        :loading="deleting"
        @close="deleteTarget = null"
        @confirm="confirmDelete"
      />
    </section>
  </ProjectGate>
</template>

<style scoped>
.character-grid {
  margin-top: 20px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
}

.character-card {
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
  font-size: 16px;
}

.card-line {
  font-size: 13px;
  line-height: 1.6;
}

.muted {
  color: var(--color-muted);
}

.clamp {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: auto;
}

.ops {
  display: flex;
  gap: 6px;
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
  color: var(--color-muted);
}

.advanced-form {
  margin-top: 10px;
}

.dialogue-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.dialogue-row textarea {
  flex: 1;
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

.hint {
  font-size: 12px;
}
</style>
