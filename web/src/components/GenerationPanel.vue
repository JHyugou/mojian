<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { useMessageStore } from '../stores/message'
import {
  createPlanTask, createGenerationTask, getGenerationTask,
  retryGenerationTask, subscribeTask
} from '../api/generation'

const props = defineProps({
  chapterId: { type: String, required: true },
  selection: { type: String, default: '' }
})

const emit = defineEmits(['refreshed'])

const message = useMessageStore()

const instruction = ref('')
const plan = ref('')
const planMode = ref(false)
const streamText = ref('')
const status = ref('')
const error = ref('')
const task = ref(null)
let eventSource = null

const running = computed(() => ['CREATED', 'RUNNING'].includes(status.value))

const contextSummary = computed(() => {
  const ctx = task.value?.context
  if (!ctx) return null
  return {
    characters: (ctx.characters || []).map(c => c.name).join('、') || '无',
    settings: (ctx.settings || []).map(s => s.title).join('、') || '无',
    considered: ctx.settingsConsidered ?? 0,
    memories: (ctx.previousMemories || []).length
  }
})

function cleanup() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

onBeforeUnmount(cleanup)

function track(taskId, mode) {
  cleanup()
  streamText.value = ''
  error.value = ''
  status.value = 'CREATED'
  eventSource = subscribeTask(taskId, {
    onStatus: (s) => (status.value = s),
    onDelta: (d) => (streamText.value += d),
    onError: (e) => (error.value = e),
    onEnd: async (final) => {
      status.value = final
      if (mode === 'plan' && final === 'SUCCEEDED') {
        const detail = await getGenerationTask(taskId)
        plan.value = detail.result || ''
        planMode.value = true
        message.success('剧情方案已生成，请确认或修改后生成正文')
      }
      if (final === 'SUCCEEDED') {
        emit('refreshed')
      }
    }
  })
}

async function start(payload, mode) {
  try {
    task.value = await (mode === 'plan'
      ? createPlanTask(props.chapterId, instruction.value.trim() || null)
      : createGenerationTask(props.chapterId, payload))
    track(task.value.id, mode)
  } catch (e) {
    message.error(e.message)
  }
}

function generatePlan() {
  start(null, 'plan')
}

function generateContent() {
  if (!plan.value.trim()) {
    message.error('请先生成并确认剧情方案（两阶段流程）')
    return
  }
  start({ taskType: 'CONTENT', plan: plan.value, instruction: instruction.value.trim() || null }, 'content')
}

function continueWriting() {
  start({ taskType: 'CONTINUE', instruction: instruction.value.trim() || null }, 'content')
}

function rewriteSelection() {
  if (!props.selection) {
    message.error('请先在编辑器中选中要改写的文本')
    return
  }
  start({ taskType: 'REWRITE', selection: props.selection, instruction: instruction.value.trim() || null }, 'content')
}

function expandSelection() {
  if (!props.selection) {
    message.error('请先在编辑器中选中要扩写/缩写的文本')
    return
  }
  start({ taskType: 'EXPAND_SHRINK', selection: props.selection, instruction: instruction.value.trim() || null }, 'content')
}

async function retry() {
  if (!task.value) return
  try {
    const again = await retryGenerationTask(task.value.id)
    task.value = again
    track(again.id, again.taskType === 'PLOT_PLAN' ? 'plan' : 'content')
  } catch (e) {
    message.error(e.message)
  }
}
</script>

<template>
  <div class="gen-panel">
    <label class="form-label">
      创作指令
      <textarea v-model="instruction" class="form-input" rows="2"
                placeholder="本次的剧情走向 / 写作要求，如：主角识破伪装，但不点破，将计就计" />
    </label>

    <div class="gen-actions">
      <button class="btn btn-primary" type="button" :disabled="running" @click="generatePlan">① 生成剧情方案</button>
      <button class="btn btn-primary" type="button" :disabled="running" @click="generateContent">② 确认方案 → 生成正文</button>
    </div>
    <div class="gen-actions secondary">
      <button class="btn btn-secondary btn-mini" type="button" :disabled="running" @click="continueWriting">续写</button>
      <button class="btn btn-secondary btn-mini" type="button" :disabled="running || !selection" @click="rewriteSelection">
        改写选中
      </button>
      <button class="btn btn-secondary btn-mini" type="button" :disabled="running || !selection" @click="expandSelection">
        扩缩选中
      </button>
      <span v-if="selection" class="selection-hint">已选中 {{ selection.length }} 字</span>
    </div>

    <div v-if="planMode" class="plan-area">
      <p class="area-title">剧情方案（可修改，确认后据此生成正文）</p>
      <textarea v-model="plan" class="form-input plan-text" rows="6" :disabled="running" />
    </div>

    <div v-if="status" class="status-area">
      <p class="area-title">
        生成状态
        <span class="tag" :class="status === 'SUCCEEDED' ? 'tag-success' : status === 'FAILED' ? 'tag-failed' : 'tag-running'">
          {{ status }}
        </span>
      </p>
      <pre v-if="streamText" class="stream-output">{{ streamText }}</pre>
      <p v-if="error" class="error-text">{{ error }}</p>
      <button v-if="status === 'FAILED'" class="btn btn-secondary btn-mini" type="button" @click="retry">重试</button>
    </div>

    <div v-if="contextSummary" class="context-area">
      <p class="area-title">本次上下文（按需注入，非全量）</p>
      <p class="context-line">人物：{{ contextSummary.characters }}</p>
      <p class="context-line">设定：{{ contextSummary.settings }}（候选 {{ contextSummary.considered }} 条）</p>
      <p class="context-line">前文记忆：{{ contextSummary.memories }} 章</p>
    </div>
  </div>
</template>

<style scoped>
.gen-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.gen-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.gen-actions.secondary {
  align-items: center;
}

.selection-hint {
  font-size: 12px;
  color: var(--color-dai-deep);
}

.area-title {
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.plan-text {
  font-size: 13px;
  line-height: 1.7;
}

.status-area {
  border-top: 1px dashed var(--color-line);
  padding-top: 10px;
}

.stream-output {
  background: var(--color-paper);
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  padding: 10px;
  font-family: var(--font-sans);
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  max-height: 260px;
  overflow-y: auto;
}

.error-text {
  color: var(--color-danger);
  font-size: 13px;
  margin: 6px 0;
}

.context-area {
  border-top: 1px dashed var(--color-line);
  padding-top: 10px;
}

.context-line {
  font-size: 12px;
  color: var(--color-muted);
  line-height: 1.8;
}
</style>
