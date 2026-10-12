<script setup>
import { onMounted, reactive, ref } from 'vue'
import ProjectGate from '../components/ProjectGate.vue'
import AppModal from '../components/AppModal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import JsonImportDialog from '../components/JsonImportDialog.vue'
import { useProjectStore } from '../stores/project'
import { useMessageStore } from '../stores/message'
import {
  listCharacters, createCharacter, updateCharacter,
  deleteCharacter, draftDialogueExample,
  listCharacterNodes, createCharacterNode,
  updateCharacterNode, deleteCharacterNode
} from '../api/character'
import { exportCharacters, importCharacters } from '../api/share'

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
const importing = ref(false)
const showImportDialog = ref(false)

// 版本节点（起点制）状态
const nodes = ref([])
const nodeMode = ref(false)
const editingNodeId = ref(null)
const chapterFrom = ref(null)
const nodeSaving = ref(false)
const nodeDeleteTarget = ref(null)
const nodeDeleting = ref(false)
const baseFormCopy = ref(null)
const NODE_FIELDS = ['description']

const emptyForm = {
  name: '', roleIdentity: '', description: '', exampleDialogue: '', enabled: true
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
  nodeMode.value = false
  nodes.value = []
  showForm.value = true
}

function openEdit(character) {
  Object.assign(form, emptyForm, character)
  editingId.value = character.id
  nodeMode.value = false
  baseFormCopy.value = { ...form }
  showForm.value = true
  loadNodes()
}

async function doSave(payload, successText) {
  saving.value = true
  try {
    const saved = editingId.value
      ? await updateCharacter(editingId.value, payload)
      : await createCharacter(projectStore.currentId, payload)
    message.success(successText)
    showForm.value = false
    await load()
    if (!editingId.value) editingId.value = saved.id
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function submit() {
  if (nodeMode.value) {
    await nodeSubmit()
    return
  }
  if (!form.name.trim()) {
    message.error('请填写人物姓名')
    return
  }
  await doSave({ ...form, name: form.name.trim() },
    editingId.value ? '人物已更新' : '人物已创建')
}

/** 存草稿：立即落库但保持停用（不参与生成上下文），防误关丢稿 */
async function submitDraft() {
  if (!form.name.trim()) {
    message.error('存草稿前请至少填写姓名')
    return
  }
  await doSave({ ...form, name: form.name.trim(), enabled: false },
    '人物已存为草稿（停用状态，列表中可随时启用）')
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

async function doExport() {
  try {
    const data = await exportCharacters(projectStore.currentId, null)
    const text = JSON.stringify({ format: 'mojian-characters/v1', version: 1, data }, null, 2)
    const blob = new Blob([text], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = '人物设定集.json'
    a.click()
    URL.revokeObjectURL(url)
    message.success(`已导出 ${data.length} 个人物`)
  } catch (e) {
    message.error(e.message)
  }
}

async function doImport(payload) {
  importing.value = true
  try {
    const result = await importCharacters(projectStore.currentId, payload)
    message.success(`成功导入 ${result.imported} 个人物`)
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

// ==================== 版本节点（起点制） ====================

async function loadNodes() {
  if (!editingId.value) {
    nodes.value = []
    return
  }
  try {
    nodes.value = await listCharacterNodes(editingId.value)
  } catch (e) {
    message.error(e.message)
  }
}

/** 新增节点：增量补充语义——空白框只写这一阶段的变化，不拷贝基础设定 */
function startNodeCreate() {
  nodeMode.value = true
  editingNodeId.value = null
  chapterFrom.value = null
  form.description = ''
}

function startNodeEdit(node) {
  nodeMode.value = true
  editingNodeId.value = node.id
  chapterFrom.value = node.chapterFrom
  NODE_FIELDS.forEach((f) => { form[f] = node[f] ?? '' })
}

function cancelNodeEdit() {
  nodeMode.value = false
  editingNodeId.value = null
  if (baseFormCopy.value) Object.assign(form, baseFormCopy.value)
}

async function nodeSubmit() {
  const from = Number(chapterFrom.value)
  if (!Number.isInteger(from) || from < 2) {
    message.error('生效起点需为不小于 2 的整数（第 1 章使用基础设定）')
    return
  }
  nodeSaving.value = true
  try {
    const payload = { chapterFrom: from }
    NODE_FIELDS.forEach((f) => { payload[f] = form[f] ?? '' })
    if (editingNodeId.value) {
      await updateCharacterNode(editingNodeId.value, payload)
      message.success(`版本节点已更新：从第 ${from} 章起生效`)
    } else {
      await createCharacterNode(editingId.value, payload)
      message.success(`版本节点已创建：从第 ${from} 章起生效`)
    }
    nodeMode.value = false
    editingNodeId.value = null
    if (baseFormCopy.value) Object.assign(form, baseFormCopy.value)
    await loadNodes()
  } catch (e) {
    message.error(e.message)
  } finally {
    nodeSaving.value = false
  }
}

async function confirmNodeDelete() {
  nodeDeleting.value = true
  try {
    await deleteCharacterNode(nodeDeleteTarget.value.id)
    message.success(`第 ${nodeDeleteTarget.value.chapterFrom} 章起的节点已删除，该区间回落基础设定`)
    nodeDeleteTarget.value = null
    await loadNodes()
  } catch (e) {
    message.error(e.message)
  } finally {
    nodeDeleting.value = false
  }
}
</script>

<template>
  <ProjectGate>
    <section>
      <div class="page-head">
        <h1 class="page-title">人物档案</h1>
        <span class="head-btns">
          <button class="btn btn-secondary" type="button" @click="doExport">导出人物</button>
          <button class="btn btn-secondary" type="button" @click="showImportDialog = true">导入人物</button>
          <button class="btn btn-primary" type="button" @click="openCreate">＋ 新建人物</button>
        </span>
      </div>
      <hr class="ink-divider" />

      <p v-if="loading" class="page-hint">加载中…</p>
      <div v-else-if="characters.length === 0" class="empty-state card">
        <p class="empty-title">尚无人物落墨</p>
        <p class="empty-desc">用一段自然语言描述你的人物——像写角色卡一样，想到什么写什么。</p>
      </div>

      <div v-else class="character-grid">
        <article v-for="c in characters" :key="c.id" class="card character-card">
          <div class="card-head">
            <h2 class="card-name">{{ c.name }}</h2>
            <span class="tag" :class="c.enabled ? 'tag-success' : ''">{{ c.enabled ? '启用' : '停用' }}</span>
          </div>
          <p v-if="c.roleIdentity" class="card-line">{{ c.roleIdentity }}</p>
          <p v-if="c.description" class="card-line muted clamp">{{ c.description }}</p>
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

      <AppModal
        :visible="showForm"
        :title="nodeMode
          ? (editingNodeId ? '编辑版本节点' : '新增版本节点')
          : (editingId ? '编辑人物' : '新建人物')"
        width="640px"
        @close="showForm = false"
      >
        <form class="form" @submit.prevent="submit">
          <label class="form-label">
            姓名 <span class="required">*</span>
            <input v-model.trim="form.name" class="form-input" maxlength="50" :disabled="nodeMode" />
          </label>

          <label v-if="nodeMode" class="form-label">
            生效起点章节 <span class="required">*</span>
            <input v-model.number="chapterFrom" class="form-input" type="number" min="2" placeholder="如：11，表示从第 11 章起生效" />
            <span class="hint">从此章起生效，直至下一个节点；早于起点的章节仍使用基础设定</span>
          </label>

          <label class="form-label">
            身份 / 角色定位
            <input v-model.trim="form.roleIdentity" class="form-input" maxlength="200" placeholder="如：北境剑修、客栈少东家" />
          </label>

          <label class="form-label">
            {{ nodeMode ? '这一阶段的变化' : '人物设定' }}
            <textarea
              v-model="form.description"
              class="form-input"
              :rows="nodeMode ? 6 : 10"
              :placeholder="nodeMode
                ? '只需写变化本身：如 心境由怯懦转为坚毅；觉醒血脉，能驭雷；与顾行舟结为同盟'
              : '用自然语言描述这个人的一切——外貌、性格、能力武功、人物关系、现阶段的目标/矛盾/伤势……想到什么写什么，建议分点，如：\n· 外貌：青衫仗剑，眉眼冷清\n· 性格：外冷内热，重诺\n· 能力：筑基后期，擅御剑与雷法\n· 关系：与林家为世仇；师从无名老者\n· 现状：身中寒毒，一心查清灭门真相'"
            />
            <span class="hint">{{ nodeMode
              ? 'AI 创作时会把它以【自第X章起】的形式追加在基础设定之后'
              : '参考角色卡写法：大段自然语言即可，AI 会整体理解；无需套用任何术语' }}</span>
          </label>

          <div v-if="editingId && !nodeMode" class="advanced-group">
            <div class="node-head">
              <span class="node-title">版本节点（基础设定的补充，当前 {{ nodes.length }} 个）</span>
              <button class="btn btn-secondary btn-mini" type="button" @click="startNodeCreate">
                ＋ 新增变化节点
              </button>
            </div>
            <p class="hint">
              角色随剧情演变？新增节点是基础设定的补充——在空白框写下这一阶段的变化
              （心境、能力、关系、处境……），从指定章节起生效。AI 创作时会连基础设定
              一起读取所有已生效节点；生成早于起点的章节不会看到这些变化（不剧透）。
            </p>
            <div v-for="n in nodes" :key="n.id" class="node-row">
              <span class="node-from">第 {{ n.chapterFrom }} 章起</span>
              <span class="node-brief clamp">{{ n.description || '（未填摘要）' }}</span>
              <span class="ops">
                <button class="btn btn-secondary btn-mini" type="button" @click="startNodeEdit(n)">编辑</button>
                <button class="btn btn-secondary btn-mini danger-text" type="button" @click="nodeDeleteTarget = n">删除</button>
              </span>
            </div>
            <p v-if="nodes.length === 0" class="hint">暂无节点：当前所有章节都使用基础设定。</p>
          </div>

          <div class="form-label">
            对话示例
            <div class="dialogue-row">
              <textarea v-model="form.exampleDialogue" class="form-input" rows="4" placeholder="可选：贴几行这个人物的典型对话，是 AI 模仿声口最有效的素材；也可点击右侧按钮生成草稿" />
              <button class="btn btn-secondary" type="button" :disabled="drafting" @click="draftExample">
                {{ drafting ? '生成中…' : '✦ AI 草稿' }}
              </button>
            </div>
          </div>

          <label v-if="!nodeMode" class="form-label">
            补充要求（可选，仅供 AI 草稿参考）
            <input v-model.trim="form.draftHint" class="form-input" maxlength="200" placeholder="如：口头禅是'无所谓'，提及过去时总在回避" />
          </label>

          <label v-if="!nodeMode" class="form-label row">
            参与上下文组装
            <input v-model="form.enabled" type="checkbox" class="enabled-check" />
            <span class="hint">{{ form.enabled ? '启用中：默认参与生成上下文' : '停用：不参与生成' }}</span>
          </label>

          <div class="form-actions">
            <template v-if="nodeMode">
              <button class="btn btn-secondary" type="button" @click="cancelNodeEdit">返回人物设定</button>
              <button class="btn btn-primary" type="button" :disabled="nodeSaving" @click.prevent="nodeSubmit">
                {{ nodeSaving ? '保存中…' : '保存节点' }}
              </button>
            </template>
            <template v-else>
              <button class="btn btn-secondary" type="button" @click="showForm = false">取消</button>
              <button
                class="btn btn-secondary"
                type="button"
                :disabled="saving"
                title="保存当前内容但保持停用：不参与生成上下文，稍后可在列表中启用"
                @click.prevent="submitDraft"
              >
                存草稿
              </button>
              <button class="btn btn-primary" type="submit" :disabled="saving">
                {{ saving ? '保存中…' : '保存人物' }}
              </button>
            </template>
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

      <ConfirmDialog
        :visible="!!nodeDeleteTarget"
        title="删除版本节点"
        :message="`确定删除「第 ${nodeDeleteTarget?.chapterFrom} 章起」的版本节点吗？该区间将回落到基础设定或上一节点。`"
        confirm-text="删除"
        danger
        :loading="nodeDeleting"
        @close="nodeDeleteTarget = null"
        @confirm="confirmNodeDelete"
      />
    </section>
  <JsonImportDialog
    :visible="showImportDialog"
    title="导入人物"
    expected-format="mojian-characters/v1"
    format-label="人物设定集"
    :loading="importing"
    @close="showImportDialog = false"
    @confirm="doImport"
  />
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

.dialogue-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.dialogue-row textarea {
  flex: 1;
}

.head-btns {
  display: flex;
  gap: 8px;
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
  color: var(--color-muted);
}

.advanced-group {
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.35);
}

.node-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.node-title {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.node-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 2px;
  border-bottom: 1px dashed var(--color-line);
}

.node-from {
  flex: none;
  font-weight: 700;
  color: var(--color-cinnabar);
  font-size: 13px;
}

.node-brief {
  flex: 1;
  font-size: 12.5px;
  color: var(--color-muted);
}
</style>
