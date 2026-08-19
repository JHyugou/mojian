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

const emptyForm = {
  nodeType: 'VOLUME', name: '', summary: '', plotGoal: '',
  mainCharacters: [], conflict: '', scene: '', mustEvents: '',
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
          以「卷 → 篇章 → 章节」三级规划剧情；章节节点填写的<br />
          剧情目标、冲突、必须情节将作为该章生成的任务书。
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
            <textarea v-model.trim="form.summary" class="form-input" rows="2" />
          </label>
          <label class="form-label">
            剧情目标
            <textarea v-model.trim="form.plotGoal" class="form-input" rows="2" placeholder="这一卷/篇章/章节要达成什么" />
          </label>

          <details v-if="form.nodeType === 'CHAPTER'" class="advanced-group" open>
            <summary>章节任务书（参与上下文组装）</summary>
            <div class="form advanced-form">
              <label class="form-label">
                主要人物（优先注入其档案）
                <select v-model="form.mainCharacters" class="form-input" multiple size="4">
                  <option v-for="c in characters" :key="c.id" :value="c.id">{{ c.name }}</option>
                </select>
              </label>
              <label class="form-label">冲突<input v-model.trim="form.conflict" class="form-input" /></label>
              <label class="form-label">场景<input v-model.trim="form.scene" class="form-input" placeholder="如：雨夜，客栈大堂" /></label>
              <label class="form-label">必须发生的情节<textarea v-model.trim="form.mustEvents" class="form-input" rows="3" /></label>
            </div>
          </details>

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

.advanced-form {
  margin-top: 10px;
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
</style>
