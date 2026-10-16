<template>
  <div class="pl">
    <!-- ========== 对话流 ========== -->
    <div ref="scrollEl" class="pl-stream mj-scroll">
      <div v-if="!messages.length" class="pl-empty mj-rise">
        <div class="mj-title mj-title-md">说说你想怎么发展</div>
        <p class="mj-muted">比如：「我想让主角在第十三章遇到一个亦敌亦友的剑客」</p>
        <p class="mj-muted">
          Agent 会先读一遍你的人物、设定和前情，再给出几条走向让你挑 ——
          而不是直接替你定下来。
        </p>
      </div>

      <div v-for="(msg, i) in messages" :key="i" class="pl-msg mj-rise" :class="'is-' + msg.role">
        <div class="pl-msg-tag">{{ msg.role === 'user' ? '你' : '策划 Agent' }}</div>

        <div class="pl-bubble">{{ msg.text }}</div>

        <!-- 它做了什么：把工具调用摆出来，而不是藏起来 -->
        <div v-if="msg.trace?.length" class="pl-trace">
          <span
            v-for="(step, k) in msg.trace"
            :key="k"
            class="mj-tag"
            :class="{ 'mj-tag-brown': step.toolName === 'propose_plot_options' }"
          >
            {{ toolLabel(step.toolName) }}
          </span>
          <span class="mj-muted pl-trace-meta">
            {{ msg.steps }} 步 · {{ msg.usage?.promptTokens || 0 }} 入 / {{ msg.usage?.completionTokens || 0 }} 出
          </span>
        </div>
      </div>

      <!-- 等待中：三点呼吸比转圈更安静，也更像"它在想" -->
      <div v-if="sending" class="pl-msg is-assistant mj-fade">
        <div class="pl-msg-tag">策划 Agent</div>
        <div class="pl-bubble pl-thinking">
          <span class="mj-dots"><i></i><i></i><i></i></span>
          <span class="mj-muted">{{ phase }}</span>
        </div>
      </div>

      <div v-if="error" class="pl-error mj-fade">{{ error }}</div>
    </div>

    <!-- ========== 走向方案卡 ========== -->
    <section v-if="planOptions.length" class="pl-block mj-rise">
      <div class="pl-block-head">
        <span class="mj-title mj-title-sm">故事走向</span>
        <span class="mj-muted">选一条，或在下面写你自己的改法</span>
      </div>

      <div class="pl-options mj-stagger">
        <button
          v-for="opt in planOptions"
          :key="opt.id"
          class="pl-option mj-card mj-card-hover"
          :class="{ 'mj-card-selected': opt.selected }"
          :disabled="opt.selected"
          @click="choose(opt)"
        >
          <div class="pl-option-head">
            <span class="pl-option-no">{{ opt.optionIndex }}</span>
            <span class="pl-option-title mj-title">{{ opt.title }}</span>
            <span v-if="opt.selected" class="mj-seal">已选定</span>
          </div>
          <p class="pl-option-summary">{{ opt.summary }}</p>
          <p v-if="opt.detail" class="pl-option-detail">{{ opt.detail }}</p>
        </button>
      </div>
    </section>

    <!-- ========== 待确认草稿（人工确认闸门） ========== -->
    <section v-if="drafts.length" class="pl-block mj-rise">
      <div class="pl-block-head">
        <span class="mj-title mj-title-sm">待你确认</span>
        <span class="mj-muted">
          这些是 Agent 起草的内容。<b>点确认之前，它们不会进入你的书。</b>
        </span>
      </div>

      <div class="pl-drafts mj-stagger">
        <div v-for="draft in drafts" :key="draft.id" class="pl-draft mj-card">
          <div class="pl-draft-head">
            <span class="mj-tag mj-tag-brown">{{ draftTypeLabel(draft.draftType) }}</span>
            <span class="mj-title mj-title-sm">{{ draft.title }}</span>
          </div>
          <p v-if="draft.agentReason" class="pl-draft-reason">
            <span class="mj-muted">理由：</span>{{ draft.agentReason }}
          </p>
          <div class="pl-draft-actions">
            <button class="mj-btn mj-btn-primary" @click="confirmDraft(draft)">确认采纳</button>
            <button class="mj-btn mj-btn-text" @click="discardDraft(draft)">放弃</button>
          </div>
        </div>
      </div>
    </section>

    <!-- ========== 输入区 ========== -->
    <footer class="pl-input-wrap">
      <!-- 引用条：从右栏蓝图里框选进来的原文。
           它会被组装进消息最前面，模型由此知道"你说的是哪一段"。 -->
      <div v-if="citations.length" class="pl-cites mj-rise">
        <div v-for="(c, i) in citations" :key="i" class="pl-cite">
          <span class="pl-cite-label">引用 · {{ c.nodeName }}</span>
          <span class="pl-cite-text">{{ preview(c.text) }}</span>
          <button class="pl-cite-x" title="移除这条引用" @click="$emit('remove-citation', i)">×</button>
        </div>
        <button class="mj-btn mj-btn-text pl-cite-clear" @click="$emit('clear-citations')">
          全部清除
        </button>
      </div>

      <div class="pl-input">
        <textarea
          v-model="draftMessage"
          class="mj-textarea"
          rows="2"
          :placeholder="citations.length
            ? '说说这段要怎么改，比如：把这里改成白天，并加上他手上的旧伤（Enter 发送）'
            : '说说你的想法…（Enter 发送，Shift+Enter 换行）'"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <div class="pl-input-side">
          <button
            class="mj-btn mj-btn-primary"
            :disabled="sending || (!draftMessage.trim() && !citations.length)"
            @click="send"
          >
            {{ sending ? '思考中…' : '发送' }}
          </button>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
import {
  acceptDraft,
  listDrafts,
  listPlanOptions,
  plannerChat,
  rejectDraft,
  selectPlanOption
} from '../../api/agent'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  /** 从蓝图分屏框选进来的引用（原文 + 所属节点），由工作台统一持有 */
  citations: { type: Array, default: () => [] }
})

const emit = defineEmits(['remove-citation', 'clear-citations'])

const messages = ref([])
const draftMessage = ref('')
const conversationId = ref(null)
const planOptions = ref([])
const drafts = ref([])
const sending = ref(false)
const phase = ref('正在读你的资料…')
const error = ref('')
const scrollEl = ref(null)

const TOOL_LABELS = {
  query_character: '查了人物卡',
  query_chapter: '读了章节原文',
  propose_plot_options: '给出了走向方案',
  draft_character: '起草了人物卡',
  draft_setting: '起草了设定',
  save_blueprint: '起草了蓝图'
}
const DRAFT_LABELS = {
  CHARACTER: '新人物',
  SETTING: '新设定',
  BLUEPRINT: '蓝图节点'
}

const toolLabel = (name) => TOOL_LABELS[name] || name
const draftTypeLabel = (type) => DRAFT_LABELS[type] || type
const preview = (text) => (text.length > 40 ? text.slice(0, 40) + '…' : text)

/**
 * 组装这一轮真正发出去的消息。
 *
 * <p>引用必须<b>放在最前面并标明出处</b>：模型是靠这段文字知道"你说的是哪一段"的。
 * 用 {@code >} 逐行引用而不是直接拼接，是为了让它在长消息里一眼能看出
 * "哪部分是被引用的原文、哪部分是用户的要求" —— 这两者混在一起最容易改错地方。
 */
function buildMessage() {
  const text = draftMessage.value.trim()
  if (!props.citations.length) {
    return text
  }
  const quoted = props.citations
    .map((c) => {
      const body = String(c.text)
        .split('\n')
        .map((line) => '> ' + line)
        .join('\n')
      return `【引用 · 蓝图节点「${c.nodeName}」】\n${body}`
    })
    .join('\n\n')

  return text
    ? `${quoted}\n\n${text}`
    : `${quoted}\n\n请针对上面引用的内容，给出修改方案。`
}

function scrollToBottom() {
  nextTick(() => {
    if (scrollEl.value) {
      scrollEl.value.scrollTop = scrollEl.value.scrollHeight
    }
  })
}

watch(messages, scrollToBottom, { deep: true })

async function send() {
  const text = draftMessage.value.trim()
  if ((!text && !props.citations.length) || sending.value) return

  // 存的是真正发出去的那份（含引用），这样回看对话时能看出"当时引用的是哪一段"
  const outgoing = buildMessage()
  messages.value.push({ role: 'user', text: outgoing })
  draftMessage.value = ''
  emit('clear-citations')
  sending.value = true
  error.value = ''
  phase.value = '正在读你的资料…'
  scrollToBottom()

  // 阶段提示：真实耗时在十几秒到一分钟，让用户知道它在干什么
  const timer = setTimeout(() => { phase.value = '正在梳理走向…' }, 8000)

  try {
    const data = await plannerChat(props.projectId, conversationId.value, outgoing)
    conversationId.value = data.conversationId
    messages.value.push({
      role: 'assistant',
      text: data.answer,
      trace: data.trace || [],
      steps: data.steps,
      usage: data.usage
    })
    await refreshSide()
  } catch (e) {
    error.value = e.message || '这次对话失败了'
  } finally {
    clearTimeout(timer)
    sending.value = false
    scrollToBottom()
  }
}

async function refreshSide() {
  if (!conversationId.value) return
  try {
    const [options, pending] = await Promise.all([
      listPlanOptions(conversationId.value),
      listDrafts(conversationId.value, true)
    ])
    planOptions.value = options || []
    drafts.value = pending || []
  } catch (e) {
    // 侧栏刷新失败不该盖掉主回答
    console.warn('刷新侧栏失败', e)
  }
}

async function choose(option) {
  try {
    await selectPlanOption(conversationId.value, option.optionIndex)
    planOptions.value = planOptions.value.map((o) => ({
      ...o,
      selected: o.optionIndex === option.optionIndex
    }))
    draftMessage.value = `就用方案 ${option.optionIndex}，帮我把它落成蓝图。`
  } catch (e) {
    error.value = e.message
  }
}

async function confirmDraft(draft) {
  try {
    await acceptDraft(draft.id)
    await refreshSide()
  } catch (e) {
    error.value = e.message
  }
}

async function discardDraft(draft) {
  try {
    await rejectDraft(draft.id)
    await refreshSide()
  } catch (e) {
    error.value = e.message
  }
}
</script>

<style scoped>
.pl {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

/* ---------- 对话流 ---------- */
.pl-stream {
  flex: 1;
  min-height: 0;
  padding: 22px 26px 8px;
}

.pl-empty {
  max-width: 560px;
  margin: 40px auto 0;
  text-align: center;
  line-height: 2;
}
.pl-empty p { margin-top: 8px; font-size: 13px; }

.pl-msg { margin-bottom: 22px; max-width: 760px; }

.pl-msg-tag {
  font-size: 12px;
  letter-spacing: 0.08em;
  color: var(--color-muted);
  margin-bottom: 6px;
}

.pl-bubble {
  padding: 12px 16px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-m);
  background: var(--color-panel);
  line-height: 1.85;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 用户说的话用暖白底 + 左棕线，和自己的资料区分开 */
.pl-msg.is-user .pl-bubble {
  background: var(--color-paper-warm);
  border-left: 2px solid var(--color-brown);
}

.pl-thinking { display: flex; align-items: center; gap: 10px; }

.pl-trace {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}
.pl-trace-meta { font-size: 12px; }

.pl-error {
  padding: 10px 14px;
  border: 1px solid var(--color-danger);
  border-radius: var(--radius-s);
  color: var(--color-danger);
  font-size: 13px;
}

/* ---------- 侧块 ---------- */
.pl-block {
  border-top: 1px solid var(--color-line);
  padding: 16px 26px;
  background: var(--color-paper-warm);
  max-height: 42vh;
  overflow-y: auto;
}

.pl-block-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
  font-size: 12px;
}

/* ---------- 方案卡 ---------- */
.pl-options {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}

.pl-option {
  text-align: left;
  cursor: pointer;
  background: var(--color-panel);
}
.pl-option:disabled { cursor: default; }

.pl-option-head { display: flex; align-items: center; gap: 8px; }

.pl-option-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: 1px solid var(--color-brown);
  border-radius: var(--radius-s);
  color: var(--color-brown-deep);
  font-size: 12px;
}

.pl-option-title { flex: 1; }

.pl-option-summary {
  margin-top: 8px;
  font-size: 13px;
  color: var(--color-ink);
  line-height: 1.75;
}

.pl-option-detail {
  margin-top: 8px;
  font-size: 12px;
  color: var(--color-muted);
  line-height: 1.7;
  max-height: 5.1em;
  overflow: hidden;
}

/* ---------- 草稿 ---------- */
.pl-drafts { display: grid; gap: 10px; }

.pl-draft-head { display: flex; align-items: center; gap: 8px; }

.pl-draft-reason {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.75;
}

.pl-draft-actions { display: flex; gap: 8px; margin-top: 12px; }

/* ---------- 输入区 ---------- */
.pl-input-wrap {
  border-top: 1px solid var(--color-line);
  background: var(--color-panel);
}

/* 引用条：一眼能看出"这一条来自哪个蓝图节点" */
.pl-cites {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 26px 0;
}

.pl-cite {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 460px;
  padding: 5px 8px 5px 10px;
  border: 1px solid var(--color-brown-soft);
  border-left: 2px solid var(--color-brown);
  border-radius: var(--radius-s);
  background: var(--color-paper-warm);
  font-size: 12px;
}

.pl-cite-label {
  color: var(--color-brown-deep);
  white-space: nowrap;
}

.pl-cite-text {
  color: var(--color-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pl-cite-x {
  border: 0;
  background: none;
  cursor: pointer;
  color: var(--color-muted);
  font-size: 14px;
  line-height: 1;
  padding: 0 2px;
  transition: color var(--dur) var(--ease-out);
}
.pl-cite-x:hover { color: var(--color-danger); }

.pl-cite-clear { font-size: 12px; }

.pl-input {
  display: flex;
  gap: 10px;
  padding: 12px 26px 18px;
}

.pl-input-side { display: flex; align-items: flex-end; }
</style>
