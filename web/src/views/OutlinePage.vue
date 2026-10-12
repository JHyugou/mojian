<script setup>
import { onMounted, reactive, ref } from 'vue'
import ProjectGate from '../components/ProjectGate.vue'
import AppModal from '../components/AppModal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import OutlineTreeNode from '../components/OutlineTreeNode.vue'
import { useProjectStore } from '../stores/project'
import { useMessageStore } from '../stores/message'
import { getOutlineTree, createOutlineNode, updateOutlineNode, deleteOutlineNode } from '../api/outline'
import { listCharacters } from '../api/character'

const projectStore = useProjectStore()
const message = useMessageStore()

const tree = ref([])
const characters = ref([])
const loading = ref(false)
const showForm = ref(false)
const saving = ref(false)
const deleteTarget = ref(null)
const deleting = ref(false)
const editingId = ref(null)
const parentForCreate = ref(null)

// 主要人物选择弹窗
const showCharacterPicker = ref(false)
const pickerSelected = ref([])

const emptyForm = {
  nodeType: 'VOLUME', name: '', summary: '',
  mainCharacters: [],
  sortOrder: 0, status: 'PLANNED'
}
const form = reactive({ ...structuredClone(emptyForm) })

async function load() {
  if (!projectStore.currentId) return
  loading.value = true
  try {
    tree.value = await getOutlineTree(projectStore.currentId)
  } catch (e) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  try {
    characters.value = await listCharacters(projectStore.currentId)
  } catch {
    characters.value = []
  }
})

/** 仅启用人物可被选为"优先注入"对象（与生成侧的启用过滤对齐） */
const enabledCharacters = () => characters.value.filter((c) => c.enabled)

function charNameById(id) {
  const c = characters.value.find((x) => x.id === id)
  return c ? c.name : '未知人物'
}

function openCreate(parentNode, nodeType) {
  Object.assign(form, structuredClone(emptyForm))
  form.nodeType = nodeType || 'VOLUME'
  parentForCreate.value = parentNode || null
  editingId.value = null
  showForm.value = true
}

function openEdit(node) {
  Object.assign(form, structuredClone(emptyForm), {
    ...node,
    mainCharacters: parseMainCharacters(node.mainCharacters)
  })
  editingId.value = node.id
  parentForCreate.value = null
  showForm.value = true
}

function parseMainCharacters(raw) {
  if (!raw) return []
  try {
    return JSON.parse(raw)
  } catch {
    return []
  }
}

function removeMainCharacter(id) {
  form.mainCharacters = form.mainCharacters.filter((x) => x !== id)
}

function openCharacterPicker() {
  pickerSelected.value = [...form.mainCharacters]
  showCharacterPicker.value = true
}

function togglePickerCharacter(id) {
  pickerSelected.value = pickerSelected.value.includes(id)
    ? pickerSelected.value.filter((x) => x !== id)
    : [...pickerSelected.value, id]
}

function confirmCharacterPicker() {
  // 保留 pickerSelected 的勾选顺序，按人物列表顺序归位
  form.mainCharacters = enabledCharacters()
    .filter((c) => pickerSelected.value.includes(c.id))
    .map((c) => c.id)
  showCharacterPicker.value = false
}

async function submit() {
  if (!form.name.trim()) {
    message.error('请填写节点名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      name: form.name.trim(),
      mainCharacters: form.mainCharacters.length
        ? JSON.stringify(form.mainCharacters)
        : null
    }
    if (editingId.value) {
      await updateOutlineNode(editingId.value, payload)
    } else {
      payload.parentId = parentForCreate.value?.id || null
      await createOutlineNode(projectStore.currentId, payload)
    }
    message.success('蓝图已保存')
    showForm.value = false
    await load()
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  deleting.value = true
  try {
    await deleteOutlineNode(deleteTarget.value.id)
    message.success(`节点「${deleteTarget.value.name}」及其子节点已删除（关联章节保留）`)
    deleteTarget.value = null
    await load()
  } catch (e) {
    message.error(e.message)
  } finally {
    deleting.value = false
  }
}

const typeNames = { VOLUME: '卷', ARC: '篇章', CHAPTER: '章节' }
</script>

<template>
  <ProjectGate>
    <section>
      <div class="page-head">
        <h1 class="page-title">剧情蓝图</h1>
        <button class="btn btn-primary" type="button" @click="openCreate(null, 'VOLUME')">＋ 新建卷</button>
      </div>
      <hr class="ink-divider" />

      <p v-if="loading" class="page-hint">加载中…</p>
      <div v-else-if="tree.length === 0" class="empty-state card">
        <p class="empty-title">山海未名，蓝图未展</p>
        <p class="empty-desc">
          以「卷 → 篇章 → 章节」三级规划剧情；章节节点的摘要<br />
          就是这一章的任务书——剧情、目标、冲突、场景想到什么写什么。
        </p>
      </div>

      <div v-else class="tree-area">
        <OutlineTreeNode
          v-for="node in tree"
          :key="node.id"
          :node="node"
          :depth="0"
          @add-child="openCreate"
          @edit="openEdit"
          @delete="(n) => (deleteTarget = n)"
        />
      </div>

      <AppModal :visible="showForm" :title="(editingId ? '编辑' : '新建') + typeNames[form.nodeType] + '节点'" width="620px" @close="showForm = false">
        <form class="form" @submit.prevent="submit">
          <label class="form-label">
            名称 <span class="required">*</span>
            <input v-model.trim="form.name" class="form-input" maxlength="100"
                   :placeholder="form.nodeType === 'VOLUME' ? '如：第一卷 雪落' : form.nodeType === 'ARC' ? '如：初入江湖' : '如：夜雨客栈'" />
          </label>
          <label class="form-label">
            摘要
            <textarea v-model.trim="form.summary" class="form-input" rows="6"
                      placeholder="这一卷/篇章/章节的任务书：剧情大体走向、要达成的目标、冲突、场景、必须发生的情节……想到什么写什么" />
            <span v-if="form.nodeType === 'CHAPTER'" class="hint">生成本章时，摘要会整体交给 AI 作为任务书</span>
          </label>

          <div v-if="form.nodeType === 'CHAPTER'" class="advanced-group">
            <div class="char-row">
              <span class="char-label">主要人物（优先注入其档案）</span>
              <button class="btn btn-secondary btn-mini" type="button" @click="openCharacterPicker">
                ＋ 选择主要人物
              </button>
            </div>
            <div v-if="form.mainCharacters.length" class="tag-list">
              <span v-for="id in form.mainCharacters" :key="id" class="char-tag">
                {{ charNameById(id) }}
                <button class="tag-x" type="button" :aria-label="`移除 ${charNameById(id)}`" @click="removeMainCharacter(id)">×</button>
              </span>
            </div>
            <p v-else class="hint">未选择时，AI 将按人物启用状态与创建顺序自动补足。</p>
          </div>

          <div class="grid-2">
            <label class="form-label">
              排序
              <input v-model.number="form.sortOrder" class="form-input" type="number" />
            </label>
            <label class="form-label">
              状态
              <select v-model="form.status" class="form-input">
                <option value="PLANNED">规划中</option>
                <option value="WRITING">写作中</option>
                <option value="DONE">已完成</option>
              </select>
            </label>
          </div>

          <div class="form-actions">
            <button class="btn btn-secondary" type="button" @click="showForm = false">取消</button>
            <button class="btn btn-primary" type="submit" :disabled="saving">
              {{ saving ? '保存中…' : '保存节点' }}
            </button>
          </div>
        </form>
      </AppModal>

      <AppModal :visible="showCharacterPicker" title="选择主要人物" width="560px" @close="showCharacterPicker = false">
        <p class="hint picker-hint">勾选本章要优先注入档案的人物（最多建议 6 人）；已选 {{ pickerSelected.length }} 人</p>
        <div v-if="enabledCharacters().length === 0" class="picker-empty">
          暂无启用中的人物——先到「人物档案」创建并启用。
        </div>
        <div v-else class="picker-grid">
          <button
            v-for="c in enabledCharacters()"
            :key="c.id"
            type="button"
            class="picker-card"
            :class="{ picked: pickerSelected.includes(c.id) }"
            @click="togglePickerCharacter(c.id)"
          >
            <span class="picker-check">{{ pickerSelected.includes(c.id) ? '✓' : '' }}</span>
            <span class="picker-name">{{ c.name }}</span>
            <span class="picker-role">{{ c.roleIdentity || '—' }}</span>
          </button>
        </div>
        <div class="form-actions">
          <button class="btn btn-secondary" type="button" @click="showCharacterPicker = false">取消</button>
          <button class="btn btn-primary" type="button" @click="confirmCharacterPicker">确定</button>
        </div>
      </AppModal>

      <ConfirmDialog
        :visible="!!deleteTarget"
        title="删除蓝图节点"
        :message="`确定删除「${deleteTarget?.name}」及其全部子节点吗？关联的章节正文不会被删除，仅解除关联。`"
        confirm-text="删除节点"
        danger
        :loading="deleting"
        @close="deleteTarget = null"
        @confirm="confirmDelete"
      />
    </section>
  </ProjectGate>
</template>

<style scoped>
.tree-area {
  margin-top: 16px;
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

.advanced-group {
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.35);
}

.char-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.char-label {
  font-size: 13px;
  color: var(--color-ink);
  letter-spacing: 0.04em;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.char-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px 3px 12px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  background: rgba(255, 255, 255, 0.45);
  font-size: 13px;
  color: var(--color-ink);
}

.tag-x {
  border: none;
  background: none;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  color: var(--color-muted);
  padding: 0 2px;
}

.tag-x:hover {
  color: var(--color-danger);
}

.picker-hint {
  margin-bottom: 12px;
}

.picker-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
  color: var(--color-muted);
}

.picker-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
  margin-bottom: 16px;
  max-height: 46vh;
  overflow-y: auto;
}

.picker-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 3px;
  text-align: left;
  padding: 10px 12px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  background: var(--color-panel);
  cursor: pointer;
  font-family: inherit;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.picker-card:hover {
  border-color: var(--color-ink);
}

.picker-card.picked {
  border-color: var(--color-cinnabar);
  background: rgba(163, 63, 48, 0.06);
}

.picker-check {
  position: absolute;
  top: 6px;
  right: 8px;
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #f6f1e4;
  background: var(--color-cinnabar);
  border-radius: 50%;
  opacity: 0;
}

.picker-card.picked .picker-check {
  opacity: 1;
}

.picker-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--color-ink);
}

.picker-role {
  font-size: 12px;
  color: var(--color-muted);
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
</style>
