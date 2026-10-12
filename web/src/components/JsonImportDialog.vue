<script setup>
import { ref } from 'vue'
import AppModal from './AppModal.vue'
import { useMessageStore } from '../stores/message'

const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '导入 JSON' },
  expectedFormat: { type: String, required: true },
  formatLabel: { type: String, default: '数据' },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['close', 'confirm'])
const message = useMessageStore()

const pasteText = ref('')
const fileText = ref('')
const fileName = ref('')

function onFileChange(event) {
  const file = event.target.files?.[0]
  if (!file) return
  file.text().then((text) => {
    fileText.value = text
    fileName.value = file.name
    pasteText.value = ''
  })
}

function confirm() {
  const raw = (fileText.value || pasteText.value || '').trim()
  if (!raw) {
    message.error('请选择 JSON 文件或直接粘贴 JSON 内容')
    return
  }
  let parsed
  try {
    parsed = JSON.parse(raw)
  } catch {
    message.error('JSON 格式不正确，请检查后重试')
    return
  }
  if (parsed.format !== props.expectedFormat) {
    message.error(`这不是墨笺的${props.formatLabel}导出文件（format=${parsed.format ?? '缺失'}）`)
    return
  }
  emit('confirm', parsed)
}

function reset() {
  pasteText.value = ''
  fileText.value = ''
  fileName.value = ''
}

defineExpose({ reset })
</script>

<template>
  <AppModal :visible="visible" :title="title" width="520px" @close="emit('close')">
    <div class="import-body">
      <!-- 文件选择：虚线框 + 点击触发 -->
      <div
        class="file-zone"
        :class="{ 'has-file': fileName }"
        @click="$refs.fileInput.click()"
      >
        <span class="file-icon">📄</span>
        <span v-if="fileName" class="file-name">{{ fileName }}</span>
        <span v-else class="file-hint">点击选择 .json 文件</span>
        <input ref="fileInput" type="file" accept=".json,application/json" class="file-hidden" @change="onFileChange" />
      </div>

      <!-- 分隔线 -->
      <div class="divider-row">
        <span class="divider-line" />
        <span class="divider-text">或直接粘贴</span>
        <span class="divider-line" />
      </div>

      <!-- 粘贴区 -->
      <textarea
        v-model="pasteText"
        class="paste-area"
        rows="5"
        placeholder='粘贴导出的 JSON 内容…'
      />

      <p class="format-hint">
        导入前会校验文件格式标识，不匹配会被拒绝
      </p>
    </div>

    <div class="import-actions">
      <button class="btn-cancel" type="button" @click="emit('close')">取消</button>
      <button class="btn-import" type="button" @click="confirm">导入</button>
    </div>
  </AppModal>
</template>

<style scoped>
.import-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* 文件选择虚线框 */
.file-zone {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 22px 16px;
  border: 1.5px dashed var(--color-line);
  border-radius: var(--radius-s);
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease;
  background: rgba(255, 255, 255, 0.35);
}

.file-zone:hover {
  border-color: var(--color-cinnabar);
  background: rgba(163, 63, 48, 0.04);
}

.file-zone.has-file {
  border-color: var(--color-cinnabar);
  background: rgba(163, 63, 48, 0.04);
}

.file-hidden {
  position: absolute;
  width: 0;
  height: 0;
  overflow: hidden;
}

.file-icon {
  font-size: 20px;
  flex: none;
}

.file-hint {
  font-size: 13.5px;
  color: var(--color-muted);
  letter-spacing: 0.04em;
}

.file-name {
  font-size: 13.5px;
  color: var(--color-ink);
  font-weight: 600;
  letter-spacing: 0.03em;
}

/* 分隔线 */
.divider-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.divider-line {
  flex: 1;
  height: 1px;
  background: var(--color-line);
}

.divider-text {
  font-size: 12px;
  color: var(--color-muted);
  letter-spacing: 0.12em;
  white-space: nowrap;
}

/* 粘贴区 */
.paste-area {
  width: 100%;
  resize: vertical;
  min-height: 90px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  padding: 10px 12px;
  font-size: 13.5px;
  line-height: 1.8;
  font-family: var(--font-sans);
  background: rgba(255, 255, 255, 0.45);
  color: var(--color-ink);
  transition: border-color 0.18s ease;
}

.paste-area:focus {
  outline: none;
  border-color: var(--color-cinnabar);
}

.format-hint {
  font-size: 12px;
  color: var(--color-muted);
  line-height: 1.6;
}

/* 底部按钮：左取消右导入，水墨风格 */
.import-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}

.btn-cancel {
  border: 1px solid var(--color-line);
  background: none;
  color: var(--color-ink);
  padding: 7px 20px;
  font-family: var(--font-kai);
  font-size: 14px;
  letter-spacing: 0.1em;
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.btn-cancel:hover {
  border-color: var(--color-ink);
}

.btn-import {
  border: none;
  background: var(--color-cinnabar);
  color: #f6f1e4;
  padding: 7px 24px;
  font-family: var(--font-kai);
  font-size: 14px;
  letter-spacing: 0.14em;
  cursor: pointer;
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.btn-import:hover {
  opacity: 0.88;
  transform: translateY(-1px);
}

.btn-import:active {
  transform: translateY(0);
}
</style>
