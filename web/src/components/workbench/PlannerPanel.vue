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

      <div v-for="(msg, i) in messages" :key="i" class="pl-msg mj-rise" :class="['is-' + msg.role, { 'is-cancelled': msg.cancelled }]">
        <div class="pl-msg-tag">
          {{ msg.role === 'user' ? '你' : '策划 Agent' }}
          <!--
            回滚按钮：只有鼠标移到你的消息上才出现。
            常驻会让每条用户消息旁边都挂个图标，很吵；
            但它又是一个"事后才想起来用"的功能，藏进菜单就更找不到了，
            所以折中成"悬停浮现"。
          -->
          <button
            v-if="msg.role === 'user' && !sending"
            class="pl-rollback"
            title="回到这一步（这条及之后的对话会从上下文里删掉，内容填回输入框）"
            @click="rollbackTo(i)"
          >
            <Icon name="undo" :size="12" />
          </button>
        </div>

        <ThinkingBlock
          v-if="msg.role === 'assistant'"
          :text="msg.reasoning"
          :mode="settings.thinkingMode"
        />

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
        <!-- 一次梳理常常起草七八条，逐条点是重复劳动。
             但全收等于跳过逐条审阅 —— 而那正是这套机制存在的意义，
             所以它只是个不起眼的文字按钮，不做成主操作 -->
        <button
          v-if="drafts.length > 1"
          class="pl-accept-all"
          @click="acceptAll"
        >
          <Icon name="checkAll" :size="13" />
          全部采纳（{{ drafts.length }} 条）
        </button>
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

      <!-- 附件：跟着这条消息一起发出去 -->
      <div v-if="attachments.length" class="pl-atts">
        <span v-for="(att, i) in attachments" :key="i" class="pl-att">
          <img v-if="att.isImage && att.dataUrl" :src="att.dataUrl" class="pl-att-thumb" alt="" />
          <span class="pl-att-name">{{ att.name }}</span>
          <button class="pl-att-x" title="移除" @click="removeAttachment(i)">×</button>
        </span>
      </div>

      <div class="pl-input">
        <textarea
          v-model="draftMessage"
          class="mj-textarea"
          rows="2"
          :placeholder="citations.length
            ? '说说这段要怎么改，比如：把这里改成白天，并加上他手上的旧伤（Enter 发送）'
            : '说说你的想法…（Enter 发送，Shift+Enter 换行）'"
          @keydown="onKeydown"
          @paste="onPaste"
        ></textarea>
        <div class="pl-input-side">
          <!-- 跑着的时候，那个按钮就是"停下" —— 它比"发送"更有用 -->
          <button v-if="sending" class="mj-btn mj-btn-ghost" @click="stop">
            <Icon name="stop" :size="12" />
            停下
          </button>
          <button
            v-else
            class="mj-btn mj-btn-primary"
            :disabled="!draftMessage.trim() && !citations.length"
            @click="send"
          >
            发送
          </button>
        </div>
      </div>
      <!--
        底部工具条。从左到右是四类东西：
          ① 加号：附件与会话导出（低频但必要时找不到会很难受）
          ② 采纳模式：我怎么审你
          ③ 发送键：Enter 还是 Shift+Enter
          ④ 模型选择：你用哪颗脑子
        全放在输入框正下方 —— 这些都是一边写一边会想改的设置，
        埋进设置页就是让人来回跳。
      -->
      <div class="pl-mode">
        <!-- ① 加号 -->
        <div class="pl-plus-wrap">
          <button class="pl-plus" :class="{ open: plusOpen }" title="更多" @click.stop="plusOpen = !plusOpen">
            <Icon name="plus" :size="15" />
          </button>

          <div v-if="plusOpen" class="pl-plus-menu mj-pop">
            <button class="pl-mode-item" @click="pickFiles">
              <span class="pl-mode-name">上传文件</span>
              <span class="pl-mode-desc">文本类文件会读进附件，跟着这条消息一并发出去</span>
            </button>
            <button class="pl-mode-item" @click="exportLog">
              <span class="pl-mode-name">导出会话日志</span>
              <span class="pl-mode-desc">把这段对话存成 Markdown，方便归档或贴给别人看</span>
            </button>
          </div>
        </div>

        <!-- 隐藏的文件选择器 -->
        <input
          ref="fileInput"
          type="file"
          class="pl-hidden-input"
          multiple
          accept=".txt,.md,.json,.csv,.log,.yml,.yaml,.xml,.html,.css,.js,.ts,.java,.py"
          @change="onFilesPicked"
        />

        <!-- ② 采纳模式 -->
        <button class="pl-mode-btn" @click="modeMenuOpen = !modeMenuOpen">
          <Icon name="shield" :size="13" />
          采纳模式：{{ autoApprove ? '自动' : '手动' }}
          <span class="pl-mode-caret">▾</span>
        </button>

        <div v-if="modeMenuOpen" class="pl-mode-menu mj-pop">
          <button class="pl-mode-item" :class="{ active: !autoApprove }" @click="setAutoApprove(false)">
            <span class="pl-mode-name">手动采纳</span>
            <span class="pl-mode-desc">起草后逐条让你确认</span>
          </button>
          <button class="pl-mode-item" :class="{ active: autoApprove }" @click="setAutoApprove(true)">
            <span class="pl-mode-name">自动采纳</span>
            <span class="pl-mode-desc">起草后直接写入你的书，不再询问</span>
          </button>
        </div>

        <!-- ③ 发送键 -->
        <button class="pl-mode-btn" @click="sendKeyMenuOpen = !sendKeyMenuOpen">
          <Icon name="enter" :size="13" />
          {{ sendKeyLabel }}
          <span class="pl-mode-caret">▾</span>
        </button>

        <div v-if="sendKeyMenuOpen" class="pl-mode-menu mj-pop">
          <button
            v-for="mode in SEND_KEYS"
            :key="mode.key"
            class="pl-mode-item"
            :class="{ active: settings.sendKey === mode.key }"
            @click="settings.setSendKey(mode.key); sendKeyMenuOpen = false"
          >
            <span class="pl-mode-name">{{ mode.label }}</span>
            <span class="pl-mode-desc">{{ mode.hint }}</span>
          </button>
        </div>

        <span v-if="autoNotice" class="pl-mode-notice mj-fade">{{ autoNotice }}</span>

        <!-- ④ 模型 -->
        <div class="pl-mode-right">
          <ModelPicker role="PLANNER" />
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  acceptAllDrafts,
  acceptDraft,
  getConversationMessages,
  listConversations,
  listPlanOptions,
  listProjectDrafts,
  cancelPlannerRun,
  plannerChat,
  rollbackConversation,
  rejectDraft,
  selectPlanOption
} from '../../api/agent'
import { buildCitedMessage, citationPreview } from '../../utils/citation'
import { buildFixRequest, lintDraft } from '../../utils/draftLint'
import ModelPicker from './ModelPicker.vue'
import ThinkingBlock from './ThinkingBlock.vue'
import { useLibraryStore } from '../../stores/library'
import { useSettingsStore, SEND_KEYS } from '../../stores/settings'
import { useMessageStore } from '../../stores/message'
import Icon from '../Icon.vue'
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
const settings = useSettingsStore()
const message = useMessageStore()

/** 采纳模式：手动（默认）/ 自动 */
const autoApprove = ref(localStorage.getItem('mojian.autoApprove') === '1')
const modeMenuOpen = ref(false)
const autoNotice = ref('')
/** 正在跑的请求，用来中止 */
const controller = ref(null)
/** 本次运行的 id：前端生成，用来让「停止」能找到后端正在跑的那一次 */
const currentRunId = ref(null)

// ==================== 底部工具条状态 ====================

const plusOpen = ref(false)
const sendKeyMenuOpen = ref(false)
const fileInput = ref(null)

/**
 * 附件。
 *
 * 文本类文件直接把内容读进来跟着消息发出去 —— 贴一段设定、一份人物表，
 * 比让用户自己复制粘贴靠谱。
 * 图片只记文件名与缩略图：当前接入的模型是纯文本的，看不到图；
 * 但先把东西留在那里，将来换成多模态模型时这条链路就能直接用。
 */
const attachments = ref([])

const sendKeyLabel = computed(() => {
  const mode = SEND_KEYS.find((m) => m.key === settings.sendKey)
  return mode ? mode.label.replace(' 发送', '') : 'Enter'
})

function pickFiles() {
  plusOpen.value = false
  fileInput.value?.click()
}

async function onFilesPicked(event) {
  const files = Array.from(event.target.files || [])
  for (const file of files) {
    // 单个文件设个上限：一份几十兆的日志贴进上下文没有意义，只会把请求撑爆
    if (file.size > 256 * 1024) {
      message.error(`「${file.name}」超过 256KB，太大了一律会挤掉正文，先自己节选吧`)
      continue
    }
    try {
      const text = await file.text()
      attachments.value = [...attachments.value, { name: file.name, text, isImage: false }]
    } catch (e) {
      message.error(`读不了「${file.name}」：${e.message}`)
    }
  }
  // 清空，否则同一个文件连选两次不会触发 change
  if (fileInput.value) fileInput.value.value = ''
}

/**
 * 粘贴。
 *
 * 图片粘贴是很多人的肌肉记忆（截图 → Ctrl+V），
 * 而 textarea 默认会把图片当成"空内容"直接吞掉，什么都不发生 —— 那很让人困惑。
 */
async function onPaste(event) {
  const items = Array.from(event.clipboardData?.items || [])
  const imageItem = items.find((item) => item.type.startsWith('image/'))
  if (!imageItem) return

  event.preventDefault()
  const file = imageItem.getAsFile()
  if (!file) return

  const dataUrl = await new Promise((resolve) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.readAsDataURL(file)
  })

  attachments.value = [...attachments.value, {
    name: file.name || `粘贴的图片-${attachments.value.length + 1}.png`,
    dataUrl,
    isImage: true
  }]
  message.info('图片已添加为附件（当前模型是纯文本的，看不到图，但会记下文件名）')
}

function removeAttachment(index) {
  const next = [...attachments.value]
  next.splice(index, 1)
  attachments.value = next
}

/** 按发送键设置判断这次按键该不该发送 */
function onKeydown(event) {
  if (event.isComposing) return // 中文输入法正在组词，不要抢 Enter
  if (!settings.shouldSendOn(event)) return
  event.preventDefault()
  send()
}

/**
 * 导出这段对话为 Markdown。
 *
 * 用 Blob + 临时链接下载，不经过后端 —— 会话数据本来就在前端手里，
 * 为了一次导出再绕一趟服务端没有意义。
 */
function exportLog() {
  plusOpen.value = false
  if (!messages.value.length) {
    message.error('这段对话还是空的，没什么可导出的')
    return
  }

  const title = conversationTitle.value || '未命名对话'
  const lines = [`# ${title}`, '', `导出时间：${new Date().toLocaleString('zh-CN')}`, '']
  for (const msg of messages.value) {
    lines.push(msg.role === 'user' ? '## 你' : '## 策划 Agent')
    lines.push('')
    if (msg.reasoning && settings.thinkingMode !== 'hidden') {
      lines.push('> **思考过程**')
      lines.push('>')
      msg.reasoning.split('\n').forEach((line) => lines.push('> ' + line))
      lines.push('')
    }
    lines.push(msg.text || '')
    lines.push('')
  }

  const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${title.replace(/[\\/:*?"<>|]/g, '_')}.md`
  a.click()
  URL.revokeObjectURL(url)
  message.success('已导出')
}
function setAutoApprove(value) {
  autoApprove.value = value
  localStorage.setItem('mojian.autoApprove', value ? '1' : '0')
  modeMenuOpen.value = false
}

/**
 * 中止正在跑的这一轮。
 *
 * <p>中止必须真的传到后端：axios 的 abort 会断开连接，
 * 后端那次模型调用随之结束 —— 否则用户按了"停下"，钱还在花。
 */
async function stop() {
  const runId = currentRunId.value

  /*
   * 先告诉后端停。
   *
   * 只 abort 是不够的 —— 那只是断开了前端的连接，服务端线程照样在跑、
   * 模型照样在生成、钱照样在花。用户按「停止」的意思是「现在停」。
   * 这个接口带的是 runId（前端生成的），所以新开的对话也能叫停。
   */
  if (runId) {
    try {
      await cancelPlannerRun(runId)
    } catch (e) {
      // 可能刚好跑完了，取消失败不算问题
      console.warn('叫停失败（可能已结束）', e)
    }
  }

  controller.value?.abort()
  controller.value = null
  currentRunId.value = null
  sending.value = false
  phase.value = ''

  // 在对话里留一条说明。否则界面就是"你说了话，然后什么都没有" -
  // 那看起来像发送失败，而不是像你主动停的
  messages.value = [...messages.value, {
    role: 'assistant',
    text: '（已按你的要求停下）',
    cancelled: true
  }]
  scrollToBottom()
}

/**
 * 回滚到某条用户消息。
 *
 * <p>把这条<b>以及它后面的一切</b>从上下文里抹掉，同时把这条的内容填回输入框 ——
 * 输错了或者想换个方向重来时用，不用手动重打一遍。
 *
 * <p>后端按「第几条用户消息」定位，不是按消息 id：
 * 中间夹着工具调用与结果，前端的数组下标和数据库行号不是一回事。
 */
async function rollbackTo(index) {
  const target = messages.value[index]
  if (!target || target.role !== 'user') return

  const keep = messages.value.slice(0, index).filter((m) => m.role === 'user').length

  try {
    if (conversationId.value) {
      await rollbackConversation(conversationId.value, keep)
    }
    messages.value = messages.value.slice(0, index)
    draftMessage.value = target.text
    emit('clear-citations')
    message.success('已回滚到这条，内容已填回输入框')
    scrollToBottom()
  } catch (e) {
    error.value = e.message
  }
}

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
  let text = buildCitedMessage(props.citations, draftMessage.value)

  /*
   * 附件拼在正文后面。
   *
   * 为什么不放前面：用户打的字是"要求"，附件是"材料" ——
   * 先说要求再看材料，模型更容易抓住重点。反过来，几十行附件会把要求挤到很后面。
   * 每份附件都套一层代码围栏，避免文件里自带的围栏把整段结构提前闭合。
   */
  for (const att of attachments.value) {
    if (att.isImage) {
      text += `\n\n[图片附件：${att.name}]\n（当前模型是纯文本的，看不到图片内容；` +
        '如果这张图里有需要参考的信息，请用文字描述一下）'
    } else if (att.text) {
      text += `\n\n[附件：${att.name}]\n` + '```' + `\n${att.text}\n` + '```'
    }
  }
  return text
}

/** 发送成功后清空附件；失败时保留，用户不用重新上传一遍 */
function clearAttachments() {
  attachments.value = []
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
  clearAttachments()
  sending.value = true
  error.value = ''
  phase.value = '正在读你的资料…'
  scrollToBottom()

  // 阶段提示：真实耗时在十几秒到一分钟，让用户知道它在干什么
  const timer = setTimeout(() => { phase.value = '正在梳理走向…' }, 8000)

  controller.value = new AbortController()
  const runId = 'run-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
  currentRunId.value = runId

  try {
    const data = await plannerChat(props.projectId, conversationId.value, outgoing,
      controller.value.signal, runId)
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
      // 思考过程：后端原样返回，显不显示、怎么显示交给 ThinkingBlock 按设置决定
      reasoning: data.reasoning || '',
      trace: data.trace || [],
      steps: data.steps,
      usage: data.usage
    })
    await refreshSide()
    await applyAutoApprove()
  } catch (e) {
    // 主动中止不算失败：界面上已经留了"已按你的要求停下"
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

/**
 * 自动采纳模式下，把这一轮起草的内容直接写进书里。
 *
 * <p>这是"跳过审阅"的代价换来的顺畅 —— 开着它的人已经明确表示
 * "别再每条都问我"。但要告诉他收了几条：不声不响地改了他的书，
 * 比打断他更糟。
 */
async function applyAutoApprove() {
  if (!autoApprove.value) return
  try {
    const result = await acceptAllDrafts(props.projectId)
    if (result.accepted > 0) {
      autoNotice.value = `已自动采纳 ${result.accepted} 条`
      if (result.failed?.length) {
        autoNotice.value += `，${result.failed.length} 条失败`
      }
      setTimeout(() => { autoNotice.value = '' }, 5000)
      await refreshSide()
      library.touch()
    }
  } catch (e) {
    autoNotice.value = '自动采纳失败：' + e.message
    setTimeout(() => { autoNotice.value = '' }, 6000)
  }
}

/** 全部采纳。单条失败不影响其余，所以要把结果如实报出来 */
async function acceptAll() {
  try {
    const result = await acceptAllDrafts(props.projectId)
    autoNotice.value = result.failed?.length
      ? `采纳 ${result.accepted} 条，${result.failed.length} 条失败`
      : `已采纳全部 ${result.accepted} 条`
    setTimeout(() => { autoNotice.value = '' }, 5000)
    await refreshSide()
    library.touch()
  } catch (e) {
    error.value = e.message
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
/* ==================================================================== */
/* 策划面板：素笺流韵 v2                                                */
/* ==================================================================== */

.pl {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  overflow: hidden;
}

/* ==================== 头部 ==================== */

.pl-head {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 26px 15px;
  flex-shrink: 0;
}

/*
 * 标题栏与对话流之间的分隔。
 *
 * 用「中间实、两端渐隐」的一条线，而不是贯穿的硬线：
 * 硬直线和这套圆润的语言打架；而完全不给分隔，标题又会和第一条消息粘在一起，
 * 分不清哪是标题哪是内容。渐隐线是这两者之间唯一舒服的解法。
 */
.pl-head::after {
  content: "";
  position: absolute;
  left: 26px;
  right: 26px;
  bottom: 0;
  height: 2px;
  border-radius: var(--radius-pill);
  background: linear-gradient(90deg,
      transparent, var(--color-brown-soft) 16%, var(--color-brown-soft) 84%, transparent);
}

.pl-head-hint {
  font-size: 12px;
  color: var(--color-muted);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 新对话：一个极淡的胶囊，hover 才亮起来 —— 它是次要操作 */
.pl-new-btn {
  margin-left: auto;
  flex: none;
  padding: 6px 15px;
  border: 0;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-muted);
  font-size: 12.5px;
  cursor: pointer;
  transition: background var(--dur) var(--ease-out),
              color var(--dur) var(--ease-out),
              transform var(--dur-fast) var(--ease-spring);
}
.pl-new-btn:hover { background: var(--tint); color: var(--color-brown-deep); }
.pl-new-btn:active { transform: scale(0.96); }

/* ==================== 对话流 ==================== */

.pl-stream {
  flex: 1;
  min-height: 0;
  padding: 16px 30px 12px;
  overflow-y: auto;
}

/* 空态：一段留白里的一小段说明，不要图标堆砌 */
.pl-empty {
  max-width: 500px;
  margin: 64px auto 0;
  text-align: center;
  line-height: 2.15;
  color: var(--color-muted);
  animation: mj-rise var(--dur-slower) var(--ease-out) both;
}
.pl-empty p { margin-top: 10px; font-size: 13px; }

.pl-msg {
  max-width: 780px;
  margin-bottom: 28px;
  animation: mj-rise var(--dur-slow) var(--ease-out) both;
}

/* 说话人：一根小棕线 + 小字。比纯文字有归属感，又不如头像抢眼 */
.pl-msg-tag {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 9px;
  font-size: 11px;
  letter-spacing: 0.12em;
  color: var(--color-muted);
}
.pl-msg-tag::before {
  content: "";
  width: 16px;
  height: 1px;
  background: var(--color-brown-soft);
}

/* 气泡：扁平 —— 一条极淡的边 + 白底，没有投影 */
.pl-bubble {
  padding: 16px 21px;
  border: 1px solid var(--color-line-soft);
  border-radius: var(--radius-m) var(--radius-m) var(--radius-m) var(--radius-xs);
  background: var(--color-panel);
  line-height: 1.92;
  word-break: break-word;
  transition: border-color var(--dur) var(--ease-out);
}
.pl-bubble:hover { border-color: var(--color-line); }

/* 用户消息：整块棕雾。比"左侧一根竖线"柔和得多 */
/*
 * 用户消息靠右。
 *
 * 光有 margin-left:auto 是不够的 —— .pl-msg 是块级元素，
 * 不约束宽度就会撑满到 max-width，里面的气泡跟着撑满，
 * 于是右边空出一大块。必须给 fit-content。
 */
.pl-msg.is-user {
  width: fit-content;
  max-width: min(780px, 82%);
  margin-left: auto;
}

/* 气泡本身按内容收窄，不要跟着父元素撑满 */
.pl-bubble { width: fit-content; max-width: 100%; }
.pl-msg.is-user .pl-msg-tag { justify-content: flex-end; }
.pl-msg.is-user .pl-msg-tag::before { order: 2; }

.pl-msg.is-user .pl-bubble {
  border-color: transparent;
  border-radius: var(--radius-m) var(--radius-m) var(--radius-xs) var(--radius-m);
  background: linear-gradient(0deg, var(--tint), var(--tint)), var(--color-paper-warm);
}

/* 中止提示：虚线、淡字。它不是一个回答 */
.pl-msg.is-cancelled .pl-bubble {
  border-style: dashed;
  background: transparent;
  color: var(--color-muted);
  font-size: 12.5px;
}

/* 思考中：三点 + 一句轻说明 */
.pl-thinking {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 12px 18px;
  border-radius: var(--radius-m);
  background: var(--color-paper-warm);
  font-size: 12.5px;
  color: var(--color-muted);
}

/* 工具调用轨迹 */
.pl-trace {
  margin-top: 10px;
  padding: 9px 13px;
  border-radius: var(--radius-s);
  background: var(--color-paper-warm);
  font-size: 11.5px;
  line-height: 1.85;
  color: var(--color-muted);
}

.pl-trace-meta { color: var(--color-brown-deep); }

.pl-error {
  margin-top: 10px;
  padding: 11px 16px;
  border-radius: var(--radius-s);
  background: var(--tint-accent);
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--color-danger);
}

/* ==================== 待确认 / 方案区 ==================== */
/*
 * 一块独立的浅色面板，四角圆，和上面的对话流之间用留白隔开 —— 不画线。
 */

.pl-block {
  flex-shrink: 0;
  max-height: 44vh;
  overflow-y: auto;
  margin: 0 20px 14px;
  padding: 18px 20px;
  border-radius: var(--radius-l);
  background: var(--color-paper-warm);
}

.pl-block-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 14px;
  font-size: 12px;
}

/* 全部采纳：文字按钮，不做成主操作 —— 全收等于跳过审阅 */
.pl-accept-all {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-left: auto;
  padding: 5px 14px;
  border: 0;
  border-radius: var(--radius-pill);
  background: var(--color-panel);
  cursor: pointer;
  font-size: 12px;
  color: var(--color-brown-deep);
  transition: background var(--dur) var(--ease-out), transform var(--dur-fast) var(--ease-spring);
}
.pl-accept-all:hover { background: var(--tint-strong); transform: translateY(-1px); }
.pl-accept-all:active { transform: scale(0.97); }

/* ---------- 方案卡 ---------- */

.pl-options { display: grid; gap: 10px; }

.pl-option {
  padding: 14px 17px;
  border: 1px solid transparent;
  border-radius: var(--radius-m);
  background: var(--color-panel);
  text-align: left;
  cursor: pointer;
  transition: background var(--dur) var(--ease-out),
              border-color var(--dur) var(--ease-out),
              transform var(--dur) var(--ease-out);
}
.pl-option:not(:disabled):hover {
  border-color: var(--color-brown-soft);
  transform: translateY(-2px);
}
.pl-option:disabled { cursor: default; }

.pl-option-head { display: flex; align-items: center; gap: 9px; }

/* 序号：圆形徽标。方形徽章更像表单编号，圆的更像"选项" */
.pl-option-no {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 23px;
  height: 23px;
  border-radius: 50%;
  background: var(--tint-strong);
  color: var(--color-brown-deep);
  font-family: var(--font-serif);
  font-size: 12px;
}

.pl-option-title { flex: 1; }

.pl-option-summary {
  margin-top: 9px;
  font-size: 13px;
  color: var(--color-ink-soft);
  line-height: 1.85;
}

.pl-option-detail {
  margin-top: 9px;
  font-size: 12px;
  color: var(--color-muted);
  line-height: 1.75;
  max-height: 5.4em;
  overflow: hidden;
}

/* ---------- 草稿卡 ---------- */

.pl-drafts { display: grid; gap: 11px; }

.pl-draft {
  padding: 15px 18px;
  border-radius: var(--radius-m);
  background: var(--color-panel);
}

.pl-draft-head { display: flex; align-items: center; gap: 9px; }

.pl-draft-toggle {
  margin-left: auto;
  padding: 3px 10px;
  border: 0;
  border-radius: var(--radius-pill);
  background: none;
  cursor: pointer;
  font-size: 11.5px;
  color: var(--color-brown);
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.pl-draft-toggle:hover { background: var(--tint); color: var(--color-brown-deep); }

/* 草稿正文：左侧一条棕色竖条 + 略深的底，像"折角的一页" */
.pl-draft-body {
  margin-top: 12px;
  padding: 14px 16px;
  border-left: 3px solid var(--color-brown-soft);
  border-radius: 0 var(--radius-s) var(--radius-s) 0;
  background: var(--color-paper-warm);
  max-height: 330px;
  overflow-y: auto;
}

.pl-draft-field + .pl-draft-field { margin-top: 14px; }

.pl-draft-key {
  display: block;
  margin-bottom: 5px;
  font-size: 10.5px;
  letter-spacing: 0.1em;
  color: var(--color-brown-deep);
}

.pl-draft-value { font-size: 13px; line-height: 1.9; }

.pl-draft-reason { margin-top: 12px; font-size: 12.5px; line-height: 1.85; }

/* ---------- 内容体检 ---------- */

.pl-draft-lint {
  margin-top: 12px;
  padding: 11px 14px;
  border-radius: var(--radius-s);
  background: rgba(208, 154, 46, 0.1);
}

.pl-lint-item { font-size: 11.5px; line-height: 1.8; color: #8a6608; }
.pl-lint-item + .pl-lint-item { margin-top: 5px; }

.pl-lint-label { font-weight: 700; margin-right: 6px; }
.pl-lint-detail { word-break: break-word; }

.pl-lint-fix {
  margin-top: 9px;
  padding: 5px 13px;
  border: 0;
  border-radius: var(--radius-pill);
  background: rgba(208, 154, 46, 0.2);
  cursor: pointer;
  font-size: 11.5px;
  color: #7d5c07;
  transition: background var(--dur) var(--ease-out);
}
.pl-lint-fix:hover { background: rgba(208, 154, 46, 0.34); }

.pl-draft-actions { display: flex; gap: 8px; margin-top: 13px; }

/* ==================== 输入区 ==================== */
/*
 * 一块浮起来的圆角卡片。之前是"贴边直角色带"，是整页最硬的地方。
 */

.pl-input-wrap {
  flex-shrink: 0;
  margin: 0 20px 16px;
  border-radius: var(--radius-l);
  background: var(--color-panel);
  box-shadow: var(--shadow-soft);
  transition: box-shadow var(--dur) var(--ease-out);
}
.pl-input-wrap:focus-within { box-shadow: var(--shadow-mid); }

/* 引用卡片 */
.pl-cites {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 13px 16px 0;
}

.pl-cite {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 9px 13px;
  border-radius: var(--radius-s);
  background: var(--tint);
  animation: mj-pop var(--dur) var(--ease-spring) both;
}

.pl-cite-label {
  flex: none;
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  background: var(--tint-strong);
  font-size: 10.5px;
  color: var(--color-brown-deep);
}

.pl-cite-locator { flex: none; font-size: 10.5px; color: var(--color-muted); }

.pl-cite-text {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--color-ink-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.pl-cite-x {
  flex: none;
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 50%;
  background: none;
  cursor: pointer;
  color: var(--color-muted);
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.pl-cite-x:hover { background: var(--tint-strong); color: var(--color-danger); }

.pl-cite-clear { align-self: flex-end; padding: 2px 8px; }

.pl-input {
  display: flex;
  gap: 12px;
  padding: 13px 16px 10px;
}

/* 输入框：不套边框，嵌在卡片里。焦点反馈交给整个容器（focus-within） */
.pl-input .mj-textarea {
  border-color: transparent;
  background: transparent;
  box-shadow: none;
  padding: 4px 2px;
  min-height: 56px;
}
.pl-input .mj-textarea:focus {
  border-color: transparent;
  background: transparent;
  box-shadow: none;
}

.pl-input-side { display: flex; align-items: flex-end; gap: 8px; }

.pl-input-side .mj-btn-primary {
  padding: 10px 24px;
  font-size: 13.5px;
}

/* ==================== 底部工具条 ==================== */

.pl-mode {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px 13px;
  position: relative;
}

.pl-mode-right { margin-left: auto; display: flex; align-items: center; }

.pl-mode-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 14px;
  border: 0;
  border-radius: var(--radius-pill);
  background: var(--color-paper-warm);
  cursor: pointer;
  font-size: 12px;
  color: var(--color-muted);
  transition: background var(--dur) var(--ease-out),
              color var(--dur) var(--ease-out),
              transform var(--dur-fast) var(--ease-spring);
}
.pl-mode-btn:hover { background: var(--tint-strong); color: var(--color-brown-deep); }
.pl-mode-btn:active { transform: scale(0.97); }

.pl-mode-caret { font-size: 9px; transition: transform var(--dur) var(--ease-out); }
.pl-mode-btn:hover .pl-mode-caret { transform: translateY(1px); }

/* 菜单往上弹：它在页面底部 */
.pl-mode-menu {
  position: absolute;
  bottom: calc(100% - 2px);
  left: 16px;
  z-index: 90;
  min-width: 236px;
  padding: 8px;
  border-radius: var(--radius-m);
  background: var(--color-panel);
  box-shadow: var(--shadow-float);
  animation: mj-pop var(--dur) var(--ease-spring) both;
}

.pl-mode-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  width: 100%;
  padding: 10px 13px;
  border: 0;
  border-radius: var(--radius-xs);
  background: none;
  cursor: pointer;
  text-align: left;
  transition: background var(--dur) var(--ease-out);
}
.pl-mode-item:hover { background: var(--tint); }
.pl-mode-item.active { background: var(--tint-strong); }
.pl-mode-item.active .pl-mode-name { color: var(--color-brown-deep); }

.pl-mode-name { font-size: 13px; color: var(--color-ink); }
.pl-mode-desc { font-size: 11px; color: var(--color-muted); }

.pl-mode-notice { font-size: 11.5px; color: var(--color-brown); animation: mj-fade var(--dur) both; }

/* ==================================================================== */
/* 底部工具条：加号 / 附件 / 发送键                                     */
/* ==================================================================== */

.pl-plus-wrap { position: relative; display: flex; }

/*
 * 加号按钮。
 * 圆形 + 略微放大：它是"更多操作"的通用入口，形状比其他按钮更自由一点，
 * 反而更容易被认出来。
 */
.pl-plus {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 50%;
  background: var(--color-paper-warm);
  color: var(--color-muted);
  cursor: pointer;
  transition: background var(--dur) var(--ease-out),
              color var(--dur) var(--ease-out),
              transform var(--dur) var(--ease-spring);
}
.pl-plus:hover { background: var(--tint-strong); color: var(--color-brown-deep); transform: scale(1.08); }
.pl-plus.open { background: var(--tint-strong); color: var(--color-brown-deep); transform: rotate(45deg); }

/* 加号菜单：往上弹，锚在按钮左侧（它在最左边） */
.pl-plus-menu {
  position: absolute;
  bottom: calc(100% + 10px);
  left: 0;
  z-index: 90;
  min-width: 268px;
  padding: 8px;
  border-radius: var(--radius-m);
  background: var(--color-panel);
  box-shadow: var(--shadow-float);
  animation: mj-pop var(--dur) var(--ease-spring) both;
}

.pl-hidden-input { display: none; }

/* ---------- 附件 ---------- */

.pl-atts {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  padding: 13px 16px 0;
}

.pl-att {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  max-width: 260px;
  padding: 5px 7px 5px 11px;
  border-radius: var(--radius-pill);
  background: var(--tint);
  animation: mj-pop var(--dur) var(--ease-spring) both;
}

.pl-att-thumb {
  width: 22px;
  height: 22px;
  border-radius: var(--radius-xs);
  object-fit: cover;
  flex: none;
}

.pl-att-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11.5px;
  color: var(--color-brown-deep);
}

.pl-att-x {
  flex: none;
  width: 18px;
  height: 18px;
  border: 0;
  border-radius: 50%;
  background: none;
  cursor: pointer;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1;
  transition: background var(--dur) var(--ease-out), color var(--dur) var(--ease-out);
}
.pl-att-x:hover { background: var(--tint-strong); color: var(--color-danger); }


/* ---------- 回到这里（只有一个箭头，悬停气泡才浮现） ---------- */

.pl-rollback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  cursor: pointer;
  color: var(--color-muted);
  /*
   * 用 visibility 一起控制。
   * 只靠 opacity 的话，看不见但仍然占位、仍然能点到 ——
   * "眼睛看不到但鼠标会撞上"是交互里最让人困惑的一种状态。
   */
  visibility: hidden;
  opacity: 0;
  transform: translateX(6px) scale(0.86);
  transition: opacity var(--dur) var(--ease-out),
              transform var(--dur) var(--ease-spring),
              background var(--dur) var(--ease-out),
              color var(--dur) var(--ease-out),
              visibility 0s linear var(--dur);
}

/*
 * 悬停整条消息（含气泡）才浮现。
 * 平时完全不占视线 —— 但它在固定位置，找的时候一眼就能找到，不用翻菜单。
 */
.pl-msg.is-user:hover .pl-rollback {
  visibility: visible;
  opacity: 1;
  transform: none;
  transition-delay: 0s;
}

/* 悬停到箭头本身：再亮一点、微微放大 —— 告诉用户"这个能点" */
.pl-rollback:hover {
  background: var(--tint-strong);
  color: var(--color-brown-deep);
  transform: scale(1.12);
}

.pl-rollback:active { transform: scale(0.94); }
</style>
