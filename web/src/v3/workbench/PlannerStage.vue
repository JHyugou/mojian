<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from '../../components/Icon.vue'
import V3Button from '../../components/v3/V3Button.vue'
import V3Tag from '../../components/v3/V3Tag.vue'
import V3Modal from '../../components/v3/V3Modal.vue'
import V3Thinking from './V3Thinking.vue'
import MarkdownText from '../../components/MarkdownText.vue'
import { useV3Notify } from '../notify'
import { useComposerSend } from '../../composables/useComposerSend'
import { useSettingsStore } from '../../stores/settings'
import { useLibraryStore } from '../../stores/library'
import { buildCitedMessage, citationPreview } from '../../utils/citation'
import { buildFixRequest, lintDraft } from '../../utils/draftLint'
import {
  acceptAllDrafts, acceptDraft, cancelPlannerRun, getConversationMessages,
  listConversations, listPlanOptions, listProjectDrafts, plannerChat,
  rejectDraft, rollbackConversation, selectPlanOption
} from '../../api/agent'

/**
 * 策划舞台（v3）：对话 + 方案卡 + 待确认草稿 + 输入区。
 * 数据与行为沿用旧面板验证过的逻辑（取消/回滚/方案选择/草稿审阅），
 * 只把外观与信息层级重做。
 */
const props = defineProps({
  projectId: { type: [Number, String], required: true },
  conversationId: { type: [String, Number], default: null },
  freshToken: { type: [String, Number], default: null },
  citations: { type: Array, default: () => [] }
})
const emit = defineEmits(['remove-citation', 'clear-citations', 'conversation-changed'])

const notify = useV3Notify()
const settings = useSettingsStore()
const library = useLibraryStore()
const { onKeydown, sendHint } = useComposerSend(() => send())

const messages = ref([])
const draftMessage = ref('')
const conversationId = ref(null)
const conversationTitle = ref('新对话')
const planOptions = ref([])
const drafts = ref([])
const sending = ref(false)
const phase = ref('正在读你的资料…')
const error = ref('')
const scrollEl = ref(null)
const controller = ref(null)
const currentRunId = ref(null)
const openedDrafts = ref(new Set())
const showDrafts = ref(false)

const draftLints = computed(() => {
  const result = {}
  for (const draft of drafts.value) result[draft.id] = lintDraft(payloadOf(draft))
  return result
})

const toolLabels = {
  query_character: '查人物',
  query_setting: '查设定',
  query_outline: '查蓝图',
  query_chapter: '读章节',
  propose_plot_options: '给方案',
  draft_character: '起草人物',
  draft_setting: '起草设定',
  save_blueprint: '起草蓝图',
  update_character: '改人物',
  update_setting: '改设定',
  update_blueprint: '改蓝图',
  save_chapter_draft: '写正文'
}

function payloadOf(draft) {
  if (!draft.payloadJson) return {}
  try {
    return JSON.parse(draft.payloadJson)
  } catch {
    return { raw: draft.payloadJson }
  }
}

const fieldLabels = {
  name: '姓名', title: '名称', roleIdentity: '身份', description: '设定全文',
  content: '内容', tags: '标签', triggerKeywords: '触发词', summary: '概要',
  detail: '详细走向', nodeType: '节点类型', raw: '内容'
}
const fieldLabel = (key) => fieldLabels[key] || key
const draftLength = (draft) => Object.values(payloadOf(draft)).reduce((sum, v) => sum + String(v || '').length, 0)
const preview = citationPreview

function toggleDraft(id) {
  const next = new Set(openedDrafts.value)
  next.has(id) ? next.delete(id) : next.add(id)
  openedDrafts.value = next
}

function scrollToBottom() {
  nextTick(() => {
    if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight
  })
}
watch(messages, scrollToBottom, { deep: true })

// ==================== 会话加载 ====================
watch(
  () => [props.conversationId, props.freshToken, props.projectId],
  async ([id, token]) => {
    if (token) return newConversation()
    if (id) return loadConversationById(id)
    await loadLatestConversation()
  },
  { immediate: true }
)

async function loadConversationById(id) {
  try {
    const list = (await listConversations(props.projectId, 'PLANNER')) || []
    const target = list.find((c) => String(c.id) === String(id))
    if (!target) {
      newConversation()
      error.value = '这段会话不属于当前项目的策划对话，已为你新开一段。'
      return
    }
    conversationId.value = id
    conversationTitle.value = target.title || '对话'
    const history = (await getConversationMessages(id)) || []
    messages.value = history.map((m) => ({ role: m.role, text: m.content }))
    await refreshSide()
    scrollToBottom()
  } catch (e) {
    console.warn('读取对话失败', e)
  }
}

async function loadLatestConversation() {
  try {
    const list = (await listConversations(props.projectId, 'PLANNER')) || []
    if (!list.length) return
    const latest = list[0]
    conversationId.value = latest.id
    conversationTitle.value = latest.title || '新对话'
    const history = (await getConversationMessages(latest.id)) || []
    messages.value = history.map((m) => ({ role: m.role, text: m.content }))
    await refreshSide()
    scrollToBottom()
  } catch (e) {
    console.warn('读取对话失败', e)
  }
}

function newConversation() {
  conversationId.value = null
  conversationTitle.value = '新对话'
  messages.value = []
  planOptions.value = []
  drafts.value = []
  error.value = ''
}

async function refreshSide() {
  if (!props.projectId) return
  try {
    const [options, pending] = await Promise.all([
      conversationId.value ? listPlanOptions(conversationId.value) : Promise.resolve([]),
      listProjectDrafts(props.projectId, true)
    ])
    planOptions.value = options || []
    drafts.value = pending || []
  } catch (e) {
    console.warn('刷新侧栏失败', e)
  }
}

// ==================== 发送 ====================
async function send() {
  const text = draftMessage.value.trim()
  if ((!text && !props.citations.length) || sending.value) return

  const outgoing = buildCitedMessage(props.citations, draftMessage.value)
  messages.value.push({ role: 'user', text: outgoing })
  draftMessage.value = ''
  emit('clear-citations')
  sending.value = true
  error.value = ''
  phase.value = '正在读你的资料…'
  scrollToBottom()

  const timer = setTimeout(() => (phase.value = '正在梳理走向…'), 8000)
  controller.value = new AbortController()
  const runId = 'run-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
  currentRunId.value = runId

  try {
    const data = await plannerChat(props.projectId, conversationId.value, outgoing, controller.value.signal, runId)
    const wasNew = !conversationId.value
    conversationId.value = data.conversationId
    if (wasNew) {
      conversationTitle.value = outgoing.replace(/\s+/g, ' ').slice(0, 20)
      emit('conversation-changed', data.conversationId)
    }
    messages.value.push({
      role: 'assistant',
      text: data.answer,
      reasoning: data.reasoning || '',
      trace: data.trace || [],
      steps: data.steps,
      usage: data.usage
    })
    await refreshSide()
  } catch (e) {
    if (e.name !== 'CanceledError' && e.name !== 'AbortError' && !controller.value?.signal.aborted) {
      error.value = e.message || '这次对话失败了'
    }
  } finally {
    clearTimeout(timer)
    controller.value = null
    sending.value = false
    scrollToBottom()
  }
}

async function stop() {
  const runId = currentRunId.value
  controller.value?.abort()
  if (runId) {
    try {
      await cancelPlannerRun(runId)
    } catch (e) {
      console.warn('叫停失败（可能已结束）', e)
    }
  }
  sending.value = false
  phase.value = ''
  messages.value.push({ role: 'assistant', text: '（已按你的要求停下。）', stopped: true })
  scrollToBottom()
}

async function rollbackTo(index) {
  const target = messages.value[index]
  if (!target || target.role !== 'user') return
  const keep = messages.value.slice(0, index).filter((m) => m.role === 'user').length
  try {
    if (conversationId.value) await rollbackConversation(conversationId.value, keep)
    messages.value = messages.value.slice(0, index)
    draftMessage.value = target.text
    emit('clear-citations')
    notify.success('已回滚到这条，内容已填回输入框')
  } catch (e) {
    error.value = e.message
  }
}

// ==================== 方案与草稿 ====================
async function choose(option) {
  try {
    await selectPlanOption(conversationId.value, option.optionIndex)
    planOptions.value = planOptions.value.map((o) => ({ ...o, selected: o.optionIndex === option.optionIndex }))
    draftMessage.value = `就用方案 ${option.optionIndex}，帮我把它落成蓝图。`
  } catch (e) {
    error.value = e.message
  }
}

async function confirmDraft(draft) {
  try {
    await acceptDraft(draft.id)
    await refreshSide()
    library.touch()
    notify.success('已采纳，内容已写进书里')
  } catch (e) {
    notify.error(e.message)
  }
}

async function discardDraft(draft) {
  try {
    await rejectDraft(draft.id)
    await refreshSide()
  } catch (e) {
    notify.error(e.message)
  }
}

async function acceptAll() {
  try {
    const result = await acceptAllDrafts(props.projectId)
    notify.success(result.failed?.length
      ? `采纳 ${result.accepted} 条，${result.failed.length} 条失败`
      : `已采纳全部 ${result.accepted} 条`)
    await refreshSide()
    library.touch()
  } catch (e) {
    notify.error(e.message)
  }
}

function askFix(draft) {
  draftMessage.value = buildFixRequest(draft.title, draftLints.value[draft.id] || [])
  showDrafts.value = false
}

onMounted(() => refreshSide())
onBeforeUnmount(() => controller.value?.abort())
</script>

<template>
  <section class="stage planner">
    <header class="stage-head">
      <div class="stage-title-wrap">
        <span class="stage-kicker">PLANNER</span>
        <h2 class="stage-title">{{ conversationTitle }}</h2>
        <span v-if="messages.length" class="stage-meta">{{ messages.length }} 条</span>
      </div>
      <div class="stage-actions">
        <button v-if="drafts.length" class="head-btn" @click="showDrafts = !showDrafts">
          <span class="badge-dot"></span>
          待确认 {{ drafts.length }}
        </button>
        <button v-if="messages.length" class="head-btn" @click="newConversation">
          <Icon name="plus" :size="13" /> 新对话
        </button>
      </div>
    </header>

    <div ref="scrollEl" class="chat v3-scroll">
      <div v-if="!messages.length && !sending" class="chat-empty">
        <span class="empty-seal" aria-hidden="true">策</span>
        <h3>说说你想怎么发展</h3>
        <p>比如：「我想让主角在第十三章遇到一个亦敌亦友的剑客。」</p>
        <p class="muted">Agent 会先读一遍人物、设定和前情，再给出几条走向让你挑 —— 而不是直接替你定下来。</p>
      </div>

      <div
        v-for="(msg, i) in messages"
        :key="i"
        class="msg"
        :class="['is-' + msg.role, { 'is-stopped': msg.stopped }]"
      >
        <div class="msg-head">
          <span class="msg-who">{{ msg.role === 'user' ? '你' : '策划 Agent' }}</span>
          <button v-if="msg.role === 'user' && !sending" class="msg-rollback" title="回到这一步" @click="rollbackTo(i)">
            <Icon name="undo" :size="12" />
          </button>
        </div>
        <V3Thinking v-if="msg.reasoning" :text="msg.reasoning" :mode="settings.thinkingMode" />
        <div class="bubble">
          <MarkdownText :text="msg.text" />
        </div>
        <div v-if="msg.trace?.length" class="trace">
          <span v-for="(step, k) in msg.trace" :key="k" class="trace-chip">
            {{ toolLabels[step.toolName] || step.toolName }}
          </span>
          <span class="trace-meta">
            {{ msg.steps }} 步 · {{ msg.usage?.promptTokens || 0 }} 入 / {{ msg.usage?.completionTokens || 0 }} 出
          </span>
        </div>
      </div>

      <div v-if="sending" class="msg is-assistant">
        <div class="msg-head"><span class="msg-who">策划 Agent</span></div>
        <div class="bubble thinking">
          <span class="dots"><i></i><i></i><i></i></span>
          <span class="muted">{{ phase }}</span>
        </div>
      </div>

      <p v-if="error" class="chat-error">{{ error }}</p>
    </div>

    <!-- 方案卡 -->
    <section v-if="planOptions.length" class="tray">
      <header class="tray-head">
        <h3>故事走向</h3>
        <span class="muted">选一条，或在下面写你自己的改法</span>
      </header>
      <div class="options">
        <button
          v-for="opt in planOptions"
          :key="opt.id"
          class="option"
          :class="{ selected: opt.selected }"
          :disabled="opt.selected"
          @click="choose(opt)"
        >
          <div class="option-head">
            <span class="option-no">{{ opt.optionIndex }}</span>
            <span class="option-title">{{ opt.title }}</span>
            <span v-if="opt.selected" class="option-seal">已选</span>
          </div>
          <p class="option-summary">{{ opt.summary }}</p>
          <p v-if="opt.detail" class="option-detail">{{ opt.detail }}</p>
        </button>
      </div>
    </section>

    <!-- 输入区 -->
    <footer class="composer">
      <div v-if="citations.length" class="cites">
        <div v-for="(c, i) in citations" :key="i" class="cite">
          <span class="cite-label">{{ c.targetName }}<span v-if="c.locator" class="cite-locator">{{ c.locator }}</span></span>
          <span class="cite-text">{{ preview(c.text) }}</span>
          <button class="cite-x" @click="emit('remove-citation', i)">×</button>
        </div>
        <button class="cite-clear" @click="emit('clear-citations')">全部清除</button>
      </div>

      <div class="input-box">
        <textarea
          v-model="draftMessage"
          class="input-area"
          rows="2"
          :placeholder="citations.length
            ? '说说这段要怎么改（' + sendHint() + '）'
            : '说说你的想法…（' + sendHint() + '）'"
          @keydown="onKeydown"
        ></textarea>
        <div class="input-side">
          <button v-if="sending" class="round-btn stop" title="停下" @click="stop">
            <Icon name="stop" :size="14" />
          </button>
          <button
            v-else
            class="round-btn send"
            :disabled="!draftMessage.trim() && !citations.length"
            title="发送"
            @click="send"
          >
            <Icon name="arrowRight" :size="16" />
          </button>
        </div>
      </div>
      <div class="composer-foot">
        <span class="muted">{{ sendHint() }}</span>
        <span class="muted">上下文 {{ messages.length ? (planOptions.length ? '含待选方案' : '全量注入') : '就绪' }}</span>
      </div>
    </footer>

    <!-- 草稿抽屉 -->
    <V3Modal :visible="showDrafts" title="待你确认" width="640px" @close="showDrafts = false">
      <p class="draft-hint">这些是 Agent 起草的内容，点确认之前它们不会进入你的书。</p>
      <div class="draft-list">
        <div v-for="draft in drafts" :key="draft.id" class="draft-card">
          <header class="draft-head">
            <V3Tag tone="gold">{{ draft.draftType }}</V3Tag>
            <span class="draft-title">{{ draft.title }}</span>
            <button class="draft-toggle" @click="toggleDraft(draft.id)">
              {{ openedDrafts.has(draft.id) ? '收起' : `查看（${draftLength(draft)} 字）` }}
            </button>
          </header>
          <div v-if="openedDrafts.has(draft.id)" class="draft-body">
            <div v-for="(value, key) in payloadOf(draft)" :key="key" class="draft-field">
              <span class="draft-key">{{ fieldLabel(key) }}</span>
              <span class="draft-value">{{ value }}</span>
            </div>
            <p v-if="draft.agentReason" class="draft-reason"><b>为什么起草它</b>{{ draft.agentReason }}</p>
          </div>
          <div v-if="(draftLints[draft.id] || []).length" class="draft-lint">
            <span v-for="(issue, i) in draftLints[draft.id]" :key="i">{{ issue.label }}：{{ issue.detail }}</span>
            <button class="lint-fix" @click="askFix(draft)">让 Agent 改这些</button>
          </div>
          <footer class="draft-actions">
            <V3Button size="sm" @click="confirmDraft(draft)">确认采纳</V3Button>
            <V3Button size="sm" variant="ghost" @click="discardDraft(draft)">放弃</V3Button>
          </footer>
        </div>
      </div>
      <template #footer>
        <V3Button variant="ghost" @click="showDrafts = false">关闭</V3Button>
        <V3Button v-if="drafts.length > 1" @click="acceptAll">全部采纳（{{ drafts.length }}）</V3Button>
      </template>
    </V3Modal>
  </section>
</template>

<style scoped>
.planner { display: flex; flex-direction: column; height: 100%; min-width: 0; }
.stage-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 28px 14px;
  flex-shrink: 0;
}
.stage-kicker {
  display: block;
  margin-bottom: 6px;
  font-size: 9.5px;
  letter-spacing: 0.36em;
  color: var(--v3-gold);
}
.stage-title {
  margin: 0;
  font-family: var(--v3-font-serif);
  font-size: 21px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--v3-text);
}
.stage-meta { margin-left: 10px; font-size: 11px; color: var(--v3-text-faint); }
.stage-actions { display: flex; align-items: center; gap: 8px; }
.head-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-pill);
  background: var(--v3-surface-1);
  color: var(--v3-text-muted);
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
  transition: color var(--v3-dur-2), border-color var(--v3-dur-2);
}
.head-btn:hover { color: var(--v3-text); border-color: var(--v3-hairline-gold); }
.badge-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--v3-cinnabar); }

.chat { flex: 1; min-height: 0; padding: 8px 28px 20px; }
.chat-empty {
  max-width: 480px;
  margin: 12vh auto 0;
  text-align: center;
}
.empty-seal {
  display: inline-grid;
  place-items: center;
  width: 52px;
  height: 52px;
  margin-bottom: 18px;
  border: 1.5px solid var(--v3-cinnabar);
  border-radius: 10px;
  color: var(--v3-cinnabar);
  font-family: var(--v3-font-serif);
  font-size: 22px;
  transform: rotate(-4deg);
  opacity: 0.85;
}
.chat-empty h3 { margin: 0 0 10px; font-family: var(--v3-font-serif); font-size: 19px; letter-spacing: 0.06em; }
.chat-empty p { margin: 6px 0; font-size: 13px; line-height: 1.9; color: var(--v3-text-soft); }
.chat-empty .muted { font-size: 12px; color: var(--v3-text-faint); }

.msg { max-width: 780px; margin: 0 0 22px; }
.msg.is-user { display: flex; flex-direction: column; align-items: flex-end; margin-left: auto; }
.msg-head { display: flex; align-items: center; gap: 8px; margin-bottom: 7px; }
.msg.is-user .msg-head { justify-content: flex-end; }
.msg-who { font-size: 10.5px; letter-spacing: 0.18em; color: var(--v3-text-faint); }
.msg-rollback {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--v3-text-faint);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--v3-dur-2), background var(--v3-dur-2);
}
.msg:hover .msg-rollback { opacity: 1; }
.msg-rollback:hover { background: var(--v3-surface-2); color: var(--v3-gold-bright); }

.bubble {
  padding: 16px 20px;
  border: 1px solid var(--v3-hairline);
  border-radius: 4px var(--v3-r-lg) var(--v3-r-lg) var(--v3-r-lg);
  background: var(--v3-surface-1);
  font-size: 13.5px;
  line-height: 1.95;
  color: var(--v3-text-soft);
  backdrop-filter: blur(12px);
}
.msg.is-user .bubble {
  display: inline-block;
  max-width: 100%;
  border-radius: var(--v3-r-lg) 4px var(--v3-r-lg) var(--v3-r-lg);
  background: var(--v3-glow-gold);
  border-color: var(--v3-hairline-gold);
  color: var(--v3-text);
}
.msg.is-stopped .bubble { opacity: 0.65; font-style: italic; }
.bubble.thinking { display: flex; align-items: center; gap: 10px; color: var(--v3-text-faint); }
.dots { display: inline-flex; gap: 4px; }
.dots i { width: 5px; height: 5px; border-radius: 50%; background: var(--v3-gold); animation: blink 1.2s infinite; }
.dots i:nth-child(2) { animation-delay: 0.15s; }
.dots i:nth-child(3) { animation-delay: 0.3s; }
@keyframes blink { 0%, 100% { opacity: 0.25; } 50% { opacity: 1; } }

.trace { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; margin-top: 10px; }
.trace-chip {
  padding: 3px 9px;
  border-radius: var(--v3-r-pill);
  border: 1px solid var(--v3-hairline);
  font-size: 10.5px;
  color: var(--v3-text-faint);
  background: var(--v3-surface-1);
}
.trace-meta { font-size: 10.5px; color: var(--v3-text-faint); }
.chat-error { margin: 12px 0; padding: 10px 14px; border-radius: var(--v3-r-sm); background: var(--v3-cinnabar-soft); color: #ffd9d3; font-size: 12.5px; }
.muted { color: var(--v3-text-faint); }

/* 方案卡 */
.tray { flex-shrink: 0; padding: 4px 28px 12px; border-top: 1px solid var(--v3-hairline); background: var(--v3-inset); }
.tray-head { display: flex; align-items: baseline; gap: 12px; padding: 12px 0 10px; }
.tray-head h3 { margin: 0; font-family: var(--v3-font-serif); font-size: 15px; letter-spacing: 0.08em; }
.tray-head .muted { font-size: 11px; }
.options { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; max-height: 240px; overflow-y: auto; }
.option {
  position: relative;
  padding: 14px 16px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-md);
  background: var(--v3-surface-1);
  color: var(--v3-text-soft);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: transform var(--v3-dur-2) var(--v3-ease-spring), border-color var(--v3-dur-2), background var(--v3-dur-2);
}
.option:hover:not(:disabled) { transform: translateY(-2px); border-color: var(--v3-hairline-gold); background: var(--v3-surface-2); }
.option.selected { border-color: var(--v3-hairline-gold); background: var(--v3-glow-gold); cursor: default; }
.option-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.option-no { font-family: var(--v3-font-serif); font-size: 12px; color: var(--v3-gold); }
.option-title { flex: 1; font-family: var(--v3-font-serif); font-size: 15px; letter-spacing: 0.04em; color: var(--v3-text); }
.option-seal {
  padding: 2px 7px;
  border: 1px solid var(--v3-cinnabar);
  border-radius: 4px;
  color: var(--v3-cinnabar);
  font-size: 10px;
  letter-spacing: 0.1em;
  transform: rotate(-3deg);
}
.option-summary { margin: 0; font-size: 12px; line-height: 1.85; }
.option-detail { margin: 8px 0 0; font-size: 11.5px; line-height: 1.85; color: var(--v3-text-faint); display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }

/* 输入区 */
.composer { flex-shrink: 0; padding: 12px 28px 18px; border-top: 1px solid var(--v3-hairline); background: var(--v3-inset); }
.cites { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
.cite {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 420px;
  padding: 5px 10px;
  border: 1px solid var(--v3-hairline-gold);
  border-radius: var(--v3-r-pill);
  background: var(--v3-glow-gold);
  font-size: 11px;
  color: var(--v3-text-soft);
}
.cite-label { color: var(--v3-gold-bright); flex-shrink: 0; }
.cite-locator { margin-left: 4px; color: var(--v3-text-faint); font-size: 10px; }
.cite-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--v3-text-muted); }
.cite-x { border: 0; background: transparent; color: var(--v3-text-faint); cursor: pointer; font-size: 14px; line-height: 1; }
.cite-clear { border: 0; background: transparent; color: var(--v3-text-faint); font-size: 11px; cursor: pointer; }
.input-box {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 10px 12px 10px 16px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-lg);
  background: var(--v3-surface-1);
  transition: border-color var(--v3-dur-2), box-shadow var(--v3-dur-2);
}
.input-box:focus-within { border-color: var(--v3-hairline-gold); box-shadow: 0 0 0 3px var(--v3-glow-gold); }
.input-area {
  flex: 1;
  min-height: 46px;
  max-height: 180px;
  padding: 8px 0;
  border: 0;
  background: transparent;
  color: var(--v3-text);
  font-family: inherit;
  font-size: 13.5px;
  line-height: 1.8;
  resize: none;
  outline: none;
}
.input-area::placeholder { color: var(--v3-text-faint); }
.input-side { padding-bottom: 4px; }
.round-btn {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  cursor: pointer;
  transition: transform var(--v3-dur-2) var(--v3-ease-spring), background var(--v3-dur-2), color var(--v3-dur-2);
}
.round-btn.send { background: var(--v3-grad-gold); color: #241d12; }
.round-btn.send:hover { transform: translateY(-1px) scale(1.03); }
.round-btn.send:disabled { opacity: 0.35; cursor: not-allowed; transform: none; }
.round-btn.stop { background: var(--v3-cinnabar-soft); color: var(--v3-cinnabar); border: 1px solid rgba(201, 76, 59, 0.45); }
.composer-foot { display: flex; justify-content: space-between; margin-top: 8px; font-size: 10.5px; }

/* 草稿 */
.draft-hint { margin: 0 0 14px; font-size: 12px; line-height: 1.8; color: var(--v3-text-muted); }
.draft-list { display: flex; flex-direction: column; gap: 12px; }
.draft-card { padding: 14px 16px; border: 1px solid var(--v3-hairline); border-radius: var(--v3-r-md); background: var(--v3-surface-1); }
.draft-head { display: flex; align-items: center; gap: 10px; }
.draft-title { flex: 1; font-family: var(--v3-font-serif); font-size: 15px; letter-spacing: 0.03em; }
.draft-toggle { border: 0; background: transparent; color: var(--v3-gold); font-size: 11.5px; cursor: pointer; }
.draft-body { margin-top: 12px; }
.draft-field { display: flex; gap: 10px; padding: 5px 0; font-size: 12px; line-height: 1.9; }
.draft-key { flex: 0 0 62px; color: var(--v3-text-faint); }
.draft-value { flex: 1; color: var(--v3-text-soft); white-space: pre-wrap; }
.draft-reason { margin: 10px 0 0; padding-top: 10px; border-top: 1px dashed var(--v3-hairline); font-size: 11.5px; line-height: 1.8; color: var(--v3-text-muted); }
.draft-reason b { margin-right: 6px; color: var(--v3-gold); font-weight: 500; }
.draft-lint { margin-top: 10px; display: flex; flex-direction: column; gap: 5px; font-size: 11.5px; color: #e0b97c; }
.lint-fix { align-self: flex-start; margin-top: 4px; border: 0; background: transparent; color: var(--v3-gold); font-size: 11.5px; cursor: pointer; text-decoration: underline; }
.draft-actions { display: flex; gap: 8px; margin-top: 12px; }

@media (max-width: 900px) {
  .stage-head, .chat, .tray, .composer { padding-left: 16px; padding-right: 16px; }
}
@media (max-width: 760px) {
  .stage-head { flex-wrap: wrap; gap: 8px; padding: 14px 14px 10px; }
  .stage-title { font-size: 17px; }
  .stage-actions { margin-left: 0; }
  .chat { padding: 6px 14px 16px; }
  .bubble { padding: 13px 15px; font-size: 13px; }
  .tray { padding: 4px 14px 10px; }
  .options { grid-template-columns: 1fr; max-height: 200px; }
  .composer { padding: 10px 14px 14px; }
  .composer-foot span:last-child { display: none; }
}
</style>
