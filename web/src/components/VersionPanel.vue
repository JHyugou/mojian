<script setup>
import { computed, onMounted, ref } from 'vue'
import AppModal from './AppModal.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import { useMessageStore } from '../stores/message'
import { listVersions, getVersion, rollbackChapter, compareVersions } from '../api/chapter'

const props = defineProps({
  chapterId: { type: String, required: true }
})

const emit = defineEmits(['refreshed'])

const message = useMessageStore()

const versions = ref([])
const loading = ref(false)
const preview = ref(null)
const previewLoading = ref(false)
const rollbackTarget = ref(null)
const rolling = ref(false)

const compareOpen = ref(false)
const compareBaseId = ref(null)
const compareTargetId = ref(null)
const compareLoading = ref(false)
const diffLines = ref([])

const sourceLabels = { AI: 'AI 生成', MANUAL: '手动保存', ROLLBACK: '回滚' }

const compareBase = computed(() => versions.value.find(v => v.id === compareBaseId.value) || null)
const compareTarget = computed(() => versions.value.find(v => v.id === compareTargetId.value) || null)

async function load() {
  loading.value = true
  try {
    versions.value = await listVersions(props.chapterId)
  } catch (e) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function openPreview(version) {
  previewLoading.value = true
  preview.value = { ...version, content: '' }
  try {
    const detail = await getVersion(version.id)
    preview.value = { ...version, content: detail.content }
  } catch (e) {
    message.error(e.message)
  } finally {
    previewLoading.value = false
  }
}

async function confirmRollback() {
  rolling.value = true
  try {
    await rollbackChapter(props.chapterId, rollbackTarget.value.id)
    message.success(`已回滚至 v${rollbackTarget.value.versionNo}（历史版本保留）`)
    rollbackTarget.value = null
    await load()
    emit('refreshed')
  } catch (e) {
    message.error(e.message)
  } finally {
    rolling.value = false
  }
}

async function openCompare() {
  if (versions.value.length < 2) return
  compareBaseId.value = versions.value[versions.value.length - 1].id
  compareTargetId.value = versions.value[0].id
  compareOpen.value = true
  await loadCompare()
}

async function loadCompare() {
  if (!compareBaseId.value || !compareTargetId.value) return
  if (compareBaseId.value === compareTargetId.value) {
    message.error('请选择两个不同的版本')
    return
  }
  compareLoading.value = true
  diffLines.value = []
  try {
    const result = await compareVersions(compareBaseId.value, compareTargetId.value)
    diffLines.value = result.lines || []
  } catch (e) {
    message.error(e.message)
  } finally {
    compareLoading.value = false
  }
}

function closeCompare() {
  compareOpen.value = false
  diffLines.value = []
}

function fmtTime(value) {
  if (!value) return ''
  return new Date(String(value).replace(' ', 'T')).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="version-panel">
    <p v-if="loading" class="page-hint">加载中…</p>
    <p v-else-if="versions.length === 0" class="page-hint">
      暂无版本。生成正文或勾选"存为版本"保存手动修改后，这里会出现快照。
    </p>
    <div v-else class="version-list">
      <div v-for="v in versions" :key="v.id" class="version-row">
        <div class="version-main">
          <span class="version-no">v{{ v.versionNo }}</span>
          <span class="version-source">{{ sourceLabels[v.source] || v.source }}</span>
          <span class="version-remark" :title="v.remark">{{ v.remark || fmtTime(v.createdAt) }}</span>
        </div>
        <div class="version-ops">
          <button class="btn btn-secondary btn-mini" type="button" @click="openPreview(v)">预览</button>
          <button
            v-if="v.versionNo !== versions[0].versionNo || versions.length === 1"
            class="btn btn-secondary btn-mini"
            type="button"
            @click="rollbackTarget = v"
          >
            回滚到此
          </button>
        </div>
      </div>
    </div>
    <div v-if="versions.length >= 2" class="compare-bar">
      <button class="btn btn-secondary btn-mini" type="button" @click="openCompare">对比版本</button>
      <span class="compare-hint">按行标记新增/删除</span>
    </div>
    <p class="hint">回滚会以目标版本内容创建新版本，历史全部保留（FR-017）。</p>

    <AppModal :visible="!!preview" :title="`版本预览 v${preview?.versionNo}`" width="680px" @close="preview = null">
      <pre class="preview-text">{{ previewLoading ? '加载中…' : preview?.content }}</pre>
    </AppModal>

    <AppModal :visible="compareOpen" :title="`版本对比 v${compareBase?.versionNo} → v${compareTarget?.versionNo}`" width="760px" @close="closeCompare">
      <div class="compare-controls">
        <label class="compare-label">
          基准版本（旧）
          <select v-model="compareBaseId" class="form-input">
            <option v-for="v in versions" :key="v.id" :value="v.id">v{{ v.versionNo }} · {{ v.remark || fmtTime(v.createdAt) }}</option>
          </select>
        </label>
        <label class="compare-label">
          对比版本（新）
          <select v-model="compareTargetId" class="form-input">
            <option v-for="v in versions" :key="v.id" :value="v.id">v{{ v.versionNo }} · {{ v.remark || fmtTime(v.createdAt) }}</option>
          </select>
        </label>
        <button class="btn btn-secondary btn-mini" type="button" :disabled="compareLoading" @click="loadCompare">
          {{ compareLoading ? '对比中…' : '刷新对比' }}
        </button>
      </div>
      <div v-if="diffLines.length" class="diff-view">
        <div v-for="(line, i) in diffLines" :key="i" class="diff-line" :class="`diff-${line.type}`">
          <span class="diff-mark">{{ line.type === 'same' ? ' ' : line.type === 'add' ? '+' : '-' }}</span>
          <span class="diff-text">{{ line.text || ' ' }}</span>
        </div>
      </div>
      <p v-else-if="compareLoading" class="page-hint">对比中…</p>
    </AppModal>

    <ConfirmDialog
      :visible="!!rollbackTarget"
      title="回滚章节"
      :message="`以 v${rollbackTarget?.versionNo} 的内容创建新版本并设为当前工作内容？历史版本不会删除。`"
      confirm-text="回滚"
      :loading="rolling"
      @close="rollbackTarget = null"
      @confirm="confirmRollback"
    />
  </div>
</template>

<style scoped>
.version-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.version-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  background: var(--color-panel);
}

.version-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.version-no {
  font-family: var(--font-serif);
  font-weight: 600;
  color: var(--color-dai-deep);
}

.version-source {
  font-size: 12px;
  background: var(--color-dai);
  border-radius: var(--radius-s);
  padding: 1px 8px;
  flex: none;
}

.version-remark {
  font-size: 12px;
  color: var(--color-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.version-ops {
  display: flex;
  gap: 6px;
  flex: none;
}

.preview-text {
  font-family: var(--font-sans);
  font-size: 13px;
  line-height: 1.9;
  white-space: pre-wrap;
  max-height: 55vh;
  overflow-y: auto;
}

.hint {
  margin-top: 10px;
  font-size: 12px;
  color: var(--color-muted);
}

.compare-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.compare-hint {
  font-size: 12px;
  color: var(--color-muted);
}

.compare-controls {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.compare-label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--color-muted);
}

.compare-label .form-input {
  min-width: 220px;
}

.diff-view {
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  max-height: 55vh;
  overflow-y: auto;
  font-family: var(--font-sans);
  font-size: 13px;
  line-height: 1.8;
}

.diff-line {
  display: flex;
  gap: 8px;
  padding: 0 10px;
  white-space: pre-wrap;
}

.diff-mark {
  width: 14px;
  flex: none;
  color: var(--color-muted);
  user-select: none;
}

.diff-text {
  flex: 1;
  min-width: 0;
}

.diff-add {
  background: rgba(46, 160, 67, 0.12);
}

.diff-add .diff-mark {
  color: #2ea043;
}

.diff-del {
  background: rgba(248, 81, 73, 0.12);
}

.diff-del .diff-mark {
  color: #f85149;
}
</style>
