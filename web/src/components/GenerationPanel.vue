<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useMessageStore } from '../stores/message'
import { getAiStatus } from '../api/aiConfig'
import {
  createPlanTask, createGenerationTask, getGenerationTask,
  retryGenerationTask, subscribeTask
} from '../api/generation'

const props = defineProps({
  chapterId: { type: String, required: true },
  selection: { type: String, default: '' }
})

const emit = defineEmits(['refreshed', 'clear-selection'])

const message = useMessageStore()

const instruction = ref('')
const plan = ref('')
const planMode = ref(false)
const streamText = ref('')
const reasoningText = ref('')
const status = ref('')
const error = ref('')
const task = ref(null)
const aiMode = ref(null)

onMounted(async () => {
  try {
    aiMode.value = await getAiStatus()
  } catch (e) {
    // 状态展示失败不影响生成功能
  }
})

async function refreshAiMode() {
  try {
    aiMode.value = await getAiStatus()
  } catch (e) {
    // ignore
  }
}

let eventSource = null
const lastRewriteText = ref('')
const showRefine = ref(false)
const refineFeedback = ref('')

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
  reasoningText.value = ''
  error.value = ''
  status.value = 'CREATED'
  eventSource = subscribeTask(taskId, {
    onStatus: (s) => (status.value = s),
    onDelta: (d) => (streamText.value += d),
    onReasoning: (d) => (reasoningText.value += d),
    onError: (e) => (error.value = e),
    onEnd: async (final) => {
      status.value = final
      if (mode === 'plan' && final === 'SUCCEEDED') {
        const detail = await getGenerationTask(taskId)
        plan.value = detail.result || ''
        planMode.value = true
        message.success('剧情方案已生成，请确认或修改后生成正文')
      }
      if (final === 'SUCCEEDED' && (mode === 'rewrite' || mode === 'expand')) {
        lastRewriteText.value = streamText.value
        showRefine.value = true
      }
      if (final === 'SUCCEEDED') {
        emit('refreshed')
        refreshAiMode()
      }
    }
  })
}

async function refineRewrite() {
  if (!refineFeedback.value.trim()) {
    message.error('Please describe what you want to adjust')
    return
  }
  showRefine.value = false
  const feedback = refineFeedback.value.trim()
  refineFeedback.value = ''
  start({ taskType: 'REWRITE', selection: lastRewriteText.value, instruction: feedback }, 'rewrite')
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
  start({ taskType: 'REWRITE', selection: props.selection, instruction: instruction.value.trim() || null }, 'rewrite')
}

function expandSelection() {
  if (!props.selection) {
    message.error('请先在编辑器中选中要扩写/缩写的文本')
    return
  }
  start({ taskType: 'EXPAND_SHRINK', selection: props.selection, instruction: instruction.value.trim() || null }, 'expand')
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
    <p v-if="aiMode" class="mode-line" :class="aiMode.mode === 'LIVE' ? 'mode-live' : 'mode-demo'">
      <RouterLink to="/ai-settings" class="mode-link">
        {{ aiMode.mode === 'LIVE' ? `已接入模型：${aiMode.model}` : '演示模式 · 内置示例文风，点击接入真模型' }}
      </RouterLink>
    </p>
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
      <button
        v-if="selection"
        class="clear-sel"
        type="button"
        title="清除选中"
        @click="emit('clear-selection')"
      >×</button>
    </div>
    <div v-if="selection" class="sel-preview">
      <span class="sel-label">选中内容</span>
      <span class="sel-text">{{ selection.length > 120 ? selection.slice(0, 120) + '…' : selection }}</span>
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
      <details v-if="reasoningText" class="reasoning-box" open>
        <summary>✦ 模型思考中…（思维链）</summary>
        <div class="reasoning-text">{{ reasoningText }}</div>
      </details>
      <pre v-if="streamText" class="stream-output">{{ streamText }}</pre>
      <p v-if="error" class="error-text">{{ error }}</p>
      <button v-if="status === 'FAILED'" class="btn btn-secondary btn-mini" type="button" @click="retry">重试</button>

      <div v-if="showRefine && status === 'SUCCEEDED'" class="refine-area">
        <p class="refine-title">对这次改写不满意？描述你想怎么调整：</p>
        <textarea v-model="refineFeedback" class="form-input refine-input" rows="2"
          placeholder="如：再简洁一些 / 保留对话但加强心理描写" />
        <div class="refine-actions">
          <button class="btn btn-secondary btn-mini" type="button" @click="refineRewrite">继续修改</button>
          <button class="btn btn-secondary btn-mini" type="button" @click="showRefine = false">收起</button>
        </div>
      </div>
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

.mode-line {
  font-size: 12px;
}

.mode-link {
  color: var(--color-dai-deep);
  text-decoration: none;
  border-bottom: 1px dashed var(--color-dai-deep);
}

.mode-demo .mode-link {
  color: var(--color-cinnabar);
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

.clear-sel {
  border: none;
  background: none;
  cursor: pointer;
  color: var(--color-muted);
  font-size: 14px;
  padding: 0 4px;
  line-height: 1;
}

.clear-sel:hover {
  color: var(--color-danger);
}

.sel-preview {
  border: 1px dashed var(--color-cinnabar);
  border-radius: var(--radius-s);
  padding: 6px 10px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--color-ink);
  background: rgba(163, 63, 48, 0.04);
}

.sel-label {
  font-size: 11px;
  color: var(--color-cinnabar);
  letter-spacing: 0.1em;
  margin-right: 6px;
}

.sel-text {
  letter-spacing: 0.02em;
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

@keyframes pulse-dot {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

.reasoning-box {
  border: 1px dashed var(--color-line);
  border-radius: var(--radius-s);
  padding: 8px 12px;
  max-height: 200px;
  overflow-y: auto;
  background: rgba(255, 255, 255, 0.35);
}

.reasoning-box summary {
  font-size: 12px;
  color: var(--color-muted);
  cursor: pointer;
  letter-spacing: 0.06em;
}

.reasoning-box summary::before {
  content: "";
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--color-cinnabar);
  margin-right: 6px;
  animation: pulse-dot 1.4s ease infinite;
}

.reasoning-text {
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--color-muted);
  white-space: pre-wrap;
  margin-top: 6px;
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

.refine-area {
  border-top: 1px dashed var(--color-line);
  padding-top: 10px;
}

.refine-title {
  font-size: 12.5px;
  color: var(--color-muted);
  margin-bottom: 6px;
}

.refine-input {
  width: 100%;
  margin-bottom: 8px;
}

.refine-actions {
  display: flex;
  gap: 8px;
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
