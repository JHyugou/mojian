<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useMessageStore } from '../stores/message'
import { getMemory, saveMemory } from '../api/chapter'
import { createGenerationTask, getGenerationTask } from '../api/generation'

const props = defineProps({
  chapterId: { type: String, required: true }
})

const message = useMessageStore()

const form = reactive({ summary: '', keyFacts: '', unresolvedConflicts: '', characterChanges: '' })
const loading = ref(false)
const saving = ref(false)
const drafting = ref(false)
const draft = ref('')

async function load() {
  loading.value = true
  try {
    const memory = await getMemory(props.chapterId)
    Object.assign(form, {
      summary: memory.summary || '',
      keyFacts: memory.keyFacts || '',
      unresolvedConflicts: memory.unresolvedConflicts || '',
      characterChanges: memory.characterChanges || ''
    })
    draft.value = ''
  } catch (e) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function save() {
  saving.value = true
  try {
    await saveMemory(props.chapterId, { ...form })
    message.success('章节记忆已保存')
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

/** FR-008：AI 提炼记忆草稿，采用后由用户确认保存 */
async function aiDraft() {
  drafting.value = true
  try {
    const task = await createGenerationTask(props.chapterId, {
      taskType: 'CHAPTER_MEMORY',
      instruction: '依据本章正文提炼记忆'
    })
    for (let i = 0; i < 30; i++) {
      const detail = await getGenerationTask(task.id)
      if (detail.status === 'SUCCEEDED') {
        draft.value = detail.result || ''
        message.success('草稿已生成，点击"采用草稿"后核对保存')
        return
      }
      if (detail.status === 'FAILED') {
        message.error(detail.errorMessage || '提炼失败')
        return
      }
      await new Promise((r) => setTimeout(r, 500))
    }
    message.error('提炼超时，请稍后重试')
  } catch (e) {
    message.error(e.message)
  } finally {
    drafting.value = false
  }
}

function adoptDraft() {
  if (!draft.value) return
  form.summary = extractSection('摘要') || form.summary
  form.unresolvedConflicts = extractSection('未解决冲突') || form.unresolvedConflicts
  form.characterChanges = extractSection('人物状态变化') || form.characterChanges
  form.keyFacts = extractSection('重要事实') || form.keyFacts
  draft.value = ''
  message.success('已采用，请核对后保存')
}

function extractSection(label) {
  const match = draft.value.match(new RegExp(label + '[：:]([^\\n]+)'))
  return match ? match[1].trim() : null
}
</script>

<template>
  <div class="memory-panel">
    <p v-if="loading" class="page-hint">加载中…</p>
    <div v-else class="form">
      <label class="form-label">章节摘要<textarea v-model="form.summary" class="form-input" rows="3" /></label>
      <label class="form-label">重要事实<textarea v-model="form.keyFacts" class="form-input" rows="2" /></label>
      <label class="form-label">未解决冲突<textarea v-model="form.unresolvedConflicts" class="form-input" rows="2" /></label>
      <label class="form-label">人物状态变化<textarea v-model="form.characterChanges" class="form-input" rows="2" /></label>

      <div v-if="draft" class="draft-area">
        <p class="area-title">AI 草稿（FR-008：确认后采用）</p>
        <pre class="draft-text">{{ draft }}</pre>
        <button class="btn btn-secondary btn-mini" type="button" @click="adoptDraft">采用草稿</button>
      </div>

      <div class="form-actions">
        <button class="btn btn-secondary" type="button" :disabled="drafting" @click="aiDraft">
          {{ drafting ? '提炼中…' : '✦ AI 提炼记忆' }}
        </button>
        <button class="btn btn-primary" type="button" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存记忆' }}
        </button>
      </div>
      <p class="hint">前文记忆将作为后续章节生成上下文的一部分注入。</p>
    </div>
  </div>
</template>

<style scoped>
.draft-area {
  border: 1px dashed var(--color-dai-deep);
  border-radius: var(--radius-s);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.area-title {
  font-size: 13px;
  font-weight: 600;
}

.draft-text {
  font-family: var(--font-sans);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  color: var(--color-muted);
}

.hint {
  font-size: 12px;
  color: var(--color-muted);
}
</style>
