<template>
  <div class="pl">
    <!-- 对话标题栏。项目与对话解耦：这里是当前这一段对话，可以随时新开一段重来 -->
    <header class="pl-head">
      <span class="mj-title mj-title-sm">{{ conversationTitle }}</span>
      <span v-if="messages.length" class="mj-muted pl-head-hint">{{ messages.length }} 条</span>
      <button
        v-if="messages.length"
        class="mj-btn mj-btn-text pl-new-btn"
        title="开一段新对话（当前这段不会丢，下次仍可继续）"
        @click="newConversation"
      >
        新对话
      </button>
    </header>

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

        <div class="pl-bubble">
          <MarkdownText :text="msg.text" />
        </div>

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
            <button class="pl-draft-toggle" @click="toggleDraft(draft.id)">
              {{ opened.has(draft.id)
                ? '收起内容'
                : `查看内容（${draftLength(draft)} 字）` }}
            </button>
          </div>

          <!--
            草稿内容默认收起（它可能很长，全展开会把列表撑爆），
            但**必须能展开** —— 让用户在看不到内容的情况下点"采纳"，
            等于让他闭着眼睛签字。他事后发现"这不是我写的"，
            感觉就像模型在编，其实是自己没看见。
          -->
          <div v-if="opened.has(draft.id)" class="pl-draft-body mj-fade">
            <div
              v-for="(value, key) in payloadOf(draft)"
              :key="key"
              class="pl-draft-field"
            >
              <span class="pl-draft-key">{{ fieldLabel(key) }}</span>
              <div class="pl-draft-value">
                <MarkdownText :text="value" />
              </div>
            </div>
          </div>

          <p v-if="draft.agentReason" class="pl-draft-reason">
            <span class="pl-draft-key">为什么起草它</span>
            <span>{{ draft.agentReason }}</span>
          </p>

          <!--
            内容体检。
            模型会产生一类"低级错误"：中英混杂（真实案例：把"正规"写成了 state）、
            重复句子、没写完的占位符。这类错误的特点是
            ——写的人看不见，读的人一眼就撞上。
            作者是最后一道关，但不该靠肉眼逐字检查几千字的草稿。
          -->
          <div v-if="(draftLints[draft.id] || []).length" class="pl-draft-lint">
            <div v-for="(issue, i) in draftLints[draft.id]" :key="i" class="pl-lint-item">
              <span class="pl-lint-label">⚠ {{ issue.label }}</span>
              <span class="pl-lint-detail">{{ issue.detail }}</span>
            </div>
            <!-- 发现问题的当下就该能顺手让它改，不该让作者自己组织语言 -->
            <button class="pl-lint-fix" @click="askFix(draft)">让 Agent 改这些</button>
          </div>

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
          <span class="pl-cite-label">
            {{ c.targetName }}<span v-if="c.locator" class="pl-cite-locator">{{ c.locator }}</span>
          </span>
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
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  acceptDraft,
  getConversationMessages,
  listConversations,
  listPlanOptions,
  listProjectDrafts,
  plannerChat,
  rejectDraft,
  selectPlanOption
} from '../../api/agent'
import { buildCitedMessage, citationPreview } from '../../utils/citation'
import { buildFixRequest, lintDraft } from '../../utils/draftLint'
import { useLibraryStore } from '../../stores/library'
import MarkdownText from '../MarkdownText.vue'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  /** 从左侧工作区树点进来的会话 id；为空表示续最近那段 */
  conversationId: { type: [String, Number], default: null },
  /** 有值表示"新开一段"（值本身只为触发变化，没有别的含义） */
  freshToken: { type: [String, Number], default: null },
  /** 从蓝图分屏框选进来的引用（原文 + 所属节点），由工作台统一持有 */
  citations: { type: Array, default: () => [] }
})

const emit = defineEmits(['remove-citation', 'clear-citations', 'conversation-changed'])

const library = useLibraryStore()

const messages = ref([])
const draftMessage = ref('')
const conversationId = ref(null)
const planOptions = ref([])
const drafts = ref([])
const sending = ref(false)
const phase = ref('正在读你的资料…')
const error = ref('')
const scrollEl = ref(null)
const conversationTitle = ref('新对话')

/**
 * 载入哪一段对话，由左栏工作区树通过 query 决定：
 *   ?conv=<id>  续那一段
 *   ?fresh=<ts> 新开一段
 *   都没有      续最近那段（刷新页面的默认行为）
 *
 * 刷新页面不该等于"重新开始"—— 策划的上下文是整本书的思路，
 * 每次刷新都从零开始，谈过的东西就白谈了。
 */
watch(
  () => [props.conversationId, props.freshToken],
  async ([id, token]) => {
    if (token) {
      newConversation()
      return
    }
    if (id) {
      await loadConversationById(id)
      return
    }
    await loadLatestConversation()
  },
  { immediate: true }
)

async function loadConversationById(id) {
  try {
    const list = (await listConversations(props.projectId, 'PLANNER')) || []
    const target = list.find((c) => String(c.id) === String(id))
    conversationId.value = id
    conversationTitle.value = target?.title || '对话'
    const history = (await getConversationMessages(id)) || []
    messages.value = history.map((m) => ({ role: m.role, text: m.content }))
    await refreshSide()
    scrollToBottom()
  } catch (e) {
    console.warn('读取指定对话失败', e)
  }
}

async function loadLatestConversation() {
  if (!props.projectId) return
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
    // 读不到历史不该让面板打不开
    console.warn('读取策划对话失败', e)
  }
}

/**
 * 开一段新对话。
 *
 * 旧的那段不会删 —— 它还在会话列表里，下次进来仍可继续。
 * "推倒重来"要的是换个思路重新谈，不是把谈过的都烧掉。
 */
function newConversation() {
  conversationId.value = null
  conversationTitle.value = '新对话'
  messages.value = []
  planOptions.value = []
  drafts.value = []
  error.value = ''
}

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
const preview = citationPreview

// ==================== 草稿内容 ====================

/** 展开的草稿 id */
const opened = ref(new Set())

function toggleDraft(id) {
  const next = new Set(opened.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  opened.value = next
}

/** 草稿内容存在 payloadJson 里（不同草稿类型字段不同，所以没有固定列） */
function payloadOf(draft) {
  if (!draft.payloadJson) return {}
  try {
    return JSON.parse(draft.payloadJson)
  } catch (e) {
    // 内容坏了不该让整块打不开，退回原文
    return { raw: draft.payloadJson }
  }
}

/**
 * 把字段名翻成人话。
 *
 * 直接显示 name / roleIdentity / triggerKeywords 这种键名，
 * 作者得先在脑子里翻译一遍才知道那是什么 —— 审核的每一秒都该花在内容上。
 */
const FIELD_LABELS = {
  name: '姓名',
  title: '名称',
  roleIdentity: '身份',
  description: '设定全文',
  content: '内容',
  tags: '标签',
  triggerKeywords: '触发词',
  summary: '概要',
  detail: '详细走向',
  nodeType: '节点类型',
  raw: '内容'
}
const fieldLabel = (key) => FIELD_LABELS[key] || key

/** 草稿内容总字数。作者据此判断值不值得点开 —— "查看内容"三个字太含糊了 */
function draftLength(draft) {
  const payload = payloadOf(draft)
  return Object.values(payload).reduce((sum, value) => sum + String(value || '').length, 0)
}

/**
 * 所有待确认草稿的体检结果。
 *
 * 用 computed 而不是在模板里调函数：模板里的函数每次重渲染都会跑一遍，
 * 而体检要逐字扫全文 —— 草稿多的时候会把界面拖卡。
 */
const draftLints = computed(() => {
  const result = {}
  for (const draft of drafts.value) {
    result[draft.id] = lintDraft(payloadOf(draft))
  }
  return result
})

/** 把体检发现填进输入框，作者改不改、怎么改都由他定 */
function askFix(draft) {
  draftMessage.value = buildFixRequest(draft.title, draftLints.value[draft.id] || [])
}

/**
 * 组装这一轮真正发出去的消息。
 *
 * 引用格式由共用的 buildCitedMessage 决定 —— 它和写作面板用的是同一份实现，
 * 格式必须一致：模型是靠这段固定格式认出"哪部分是被引的原文"的。
 */
function buildMessage() {
  return buildCitedMessage(props.citations, draftMessage.value)
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
    const wasNew = !conversationId.value
    conversationId.value = data.conversationId
    if (wasNew) {
      // 标题由后端从首条消息派生，这里跟随一下，免得切走再回来标题不一样
      conversationTitle.value = outgoing.replace(/\s+/g, ' ').slice(0, 20)
      // 通知外层把 URL 上的会话换成新的，好让左侧工作区树高亮对得上
      emit('conversation-changed', data.conversationId)
    }
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
  if (!props.projectId) return
  try {
    const [options, pending] = await Promise.all([
      // 走向方案跟着会话走（它是这段对话的产物）
      conversationId.value ? listPlanOptions(conversationId.value) : Promise.resolve([]),
      // 待确认草稿按**项目**查：写作 Agent 起草的人物卡挂在写作会话上，
      // 只查策划会话就会永远看不到它们
      listProjectDrafts(props.projectId, true)
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
    // 采纳会真的写进人物/设定/蓝图表 —— 喊一声，右栏的数字才会跟着变
    library.touch()
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

/* ---------- 对话标题栏 ---------- */
.pl-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 26px;
  border-bottom: 1px solid var(--color-line);
  background: var(--color-panel);
  flex-shrink: 0;
}

.pl-head-hint { font-size: 12px; }
.pl-new-btn { margin-left: auto; font-size: 12px; }

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

.pl-draft-toggle {
  margin-left: auto;
  border: 0;
  background: none;
  cursor: pointer;
  font-size: 12px;
  color: var(--color-brown);
  padding: 2px 4px;
  transition: color var(--dur) var(--ease-out);
}
.pl-draft-toggle:hover { color: var(--color-brown-deep); text-decoration: underline; }

/* 草稿内容区：限高可滚。它是"待审阅的全文"，不该把整个列表推到屏幕外 */
.pl-draft-body {
  margin-top: 10px;
  padding: 12px;
  border-left: 2px solid var(--color-brown-soft);
  background: var(--color-paper-warm);
  border-radius: 0 var(--radius-s) var(--radius-s) 0;
  max-height: 320px;
  overflow-y: auto;
}

.pl-draft-field + .pl-draft-field { margin-top: 12px; }

.pl-draft-key {
  display: block;
  font-size: 11px;
  letter-spacing: 0.08em;
  color: var(--color-brown-deep);
  margin-bottom: 4px;
}

.pl-draft-value { font-size: 13px; line-height: 1.8; }

.pl-draft-reason {
  margin-top: 10px;
  font-size: 13px;
  line-height: 1.75;
}

/* ---------- 内容体检 ---------- */
.pl-draft-lint {
  margin-top: 10px;
  padding: 9px 11px;
  border: 1px solid var(--color-gold);
  border-radius: var(--radius-s);
  background: rgba(184, 134, 11, 0.05);
}

.pl-lint-item {
  font-size: 12px;
  line-height: 1.75;
  color: #8a6608;
}
.pl-lint-item + .pl-lint-item { margin-top: 4px; }

.pl-lint-label { font-weight: 700; margin-right: 6px; }

.pl-lint-detail { word-break: break-word; }

.pl-lint-fix {
  margin-top: 8px;
  padding: 4px 10px;
  border: 1px solid var(--color-gold);
  border-radius: var(--radius-s);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: #8a6608;
  transition: background var(--dur) var(--ease-out);
}
.pl-lint-fix:hover { background: rgba(184, 134, 11, 0.12); }

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

/* 定位信息（记录ID / 段号）用等宽小字：它是给模型用的精确坐标，
   视觉上要让位给正文内容，但不能看不见 */
.pl-cite-locator {
  margin-left: 4px;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  color: var(--color-muted);
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
