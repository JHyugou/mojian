<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { getMemory, saveMemory } from '../api/chapter'
import { createGenerationTask, subscribeTask } from '../api/generation'
import { useMessageStore } from '../stores/message'

const props = defineProps({ chapterId: { type: String, required: true } })
const message = useMessageStore()

const form = reactive({ memory: '' })
const loading = ref(false)
const saving = ref(false)
const refining = ref(false)
let eventSource = null
let savedSnapshot = ''

async function load() {
  loading.value = true
  try {
    const m = await getMemory(props.chapterId)
    form.memory = m.memory || ''
    savedSnapshot = form.memory
  } catch (e) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

onBeforeUnmount(() => {
  if (eventSource) eventSource.close()
})

/** AI 提炼：流式生成，内容直接逐字流入大框，完成后核对保存 */
async function refine() {
  if (refining.value) return
  if (form.memory.trim() && form.memory !== savedSnapshot) {
    message.error('框内有未保存的修改，请先保存记忆再提炼（提炼会覆盖框内内容）')
    return
  }
  refining.value = true
  try {
    const task = await createGenerationTask(props.chapterId, {
      taskType: 'CHAPTER_MEMORY',
      instruction: '依据本章正文提炼记忆'
    })
    form.memory = ''
    eventSource = subscribeTask(task.id, {
      onDelta: (d) => { form.memory += d },
      onError: (e) => message.error(e),
      onEnd: (final) => {
        refining.value = false
        if (final === 'SUCCEEDED') {
          message.success('提炼完成，核对后点击"保存记忆"')
        } else if (form.memory.trim() === '') {
          message.error('提炼失败，请重试')
        }
      }
    })
  } catch (e) {
    refining.value = false
    message.error(e.message)
  }
}

async function save() {
  saving.value = true
  try {
    await saveMemory(props.chapterId, { memory: form.memory })
    savedSnapshot = form.memory
    message.success('章节记忆已保存')
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="memory-panel">
    <label class="form-label">
      章节记忆
      <textarea
        v-model="form.memory"
        class="form-input memory-box"
        rows="10"
        placeholder="这本书到目前的前情，供后续章节生成时参考。建议分点：&#10;【章节摘要】本章发生了什么&#10;【重要事实】新人物/道具/设定的关键信息&#10;【未解决冲突】还没收线的悬念&#10;【人物状态变化】谁的态度/能力/处境变了"
      />
    </label>

    <div class="memory-actions">
      <button
        class="btn btn-secondary"
        type="button"
        :disabled="refining"
        title="由 AI 依据本章正文提炼记忆，流式写入上方文本框（会覆盖未保存内容）"
        @click="refine"
      >
        {{ refining ? '提炼中…' : '✦ AI 提炼记忆' }}
      </button>
      <button class="btn btn-primary" type="button" :disabled="saving" @click="save">
        {{ saving ? '保存中…' : '保存记忆' }}
      </button>
    </div>

    <p class="hint">前文记忆将作为后续章节生成上下文的一部分注入（全部章节，不限章数）。</p>
  </div>
</template>

<style scoped>
.memory-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.memory-box {
  font-size: 13.5px;
  line-height: 2;
}

.memory-actions {
  display: flex;
  gap: 8px;
}

.hint {
  font-size: 12px;
  color: var(--color-muted);
  line-height: 1.7;
}
</style>
