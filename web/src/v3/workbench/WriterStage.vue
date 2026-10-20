<script setup>
import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from '../../components/Icon.vue'
import V3Button from '../../components/v3/V3Button.vue'
import V3Tag from '../../components/v3/V3Tag.vue'
import V3Modal from '../../components/v3/V3Modal.vue'
import V3Thinking from './V3Thinking.vue'
import ModelPicker from './ModelPicker.vue'
import V3Splitter from '../../components/v3/V3Splitter.vue'
import MarkdownText from '../../components/MarkdownText.vue'
import { useV3Notify } from '../notify'
import { useSettingsStore } from '../../stores/settings'
import { useComposerSend } from '../../composables/useComposerSend'
import { useComposerDraft } from '../../composables/useComposerDraft'
import { useWidthResize } from '../useWidthResize'
import {
  compareVersions, createChapter, getChapter, getMemory, getVersion,
  listVersions, rollbackChapter, saveChapter, saveMemory
} from '../../api/chapter'
import {
  approveChapterDeleteRequest, cancelWriterInstruction, cancelWritingTask, clearWriterHistory, getActiveWritingTask,
  getChapterDeleteRequests, getChapterSupplements, getWriterConversation, getWriterInstructions,
  rejectChapterDeleteRequest, returnChapterSupplement, setWritingRequirement, startWritingTask,
  subscribeWritingTask, verifyChapterSupplement
} from '../../api/agent'

/**
 * 写作舞台（v3）：左边和写作 Agent 对话，右边是一张干净的纸。
 * 流式正文用 rAF 批量合并刷新（每个 token 都触发整页渲染会卡），
 * 服务端重连时用 *-snapshot 替换而不是追加（否则正文整章重复）。
 */
const props = defineProps({
  projectId: { type: [Number, String], required: true },
  chapter: { type: Object, default: null },
  chapters: { type: Array, default: () => [] },
  blueprint: { type: Object, default: null },
  /** 可关联的蓝图节点（CHAPTER 类型），由工作台传入 */
  blueprintOptions: { type: Array, default: () => [] }
})
const emit = defineEmits(['select-chapter', 'reload'])

const notify = useV3Notify()
/** 写作页"对话 / 正文"宽度可拖拽（双击恢复默认） */
const {
  width: chatWidth,
  onPointerDown: onChatResize,
  reset: resetChatWidth
} = useWidthResize({ initial: 420, min: 300, max: 680, storageKey: 'mojian.chatWidth' })
const settings = useSettingsStore()
const { onKeydown, sendHint } = useComposerSend(() => start())

const full = ref(null)
const text = ref('')
const requirement = ref('')
const advice = ref('')
// 按“项目 + 章节”保存输入框草稿，页面重载/刷新不会丢
const composerDraft = useComposerDraft(advice,
  () => `${props.projectId}.writer.${props.chapter?.id || 'none'}`)
/** 从对话/正文右键引用的段落（结构化引用，和用户手打内容在视觉上分开） */
const quotedPassages = ref([])
const expandedQuotes = ref(new Set())
const quoteMenu = ref(null)
let quoteSeq = 0
const messages = ref([])
/** 策划 Agent 下发的写作指令（PENDING 待执行 / CONSUMED 已执行） */
const plannerInstructions = ref([])
/** 已执行指令默认折叠，点开才展开全文 */
const expandedInstructions = ref(new Set())
/** 写作 Agent 提交的章节删除申请 */
const deleteRequests = ref([])
/** 待作者确认的删除：点「删除章节」后二次确认 */
const deleteConfirm = ref(null)
const deletingChapter = ref(false)
const streamingAnswer = ref('')
const streamingReasoning = ref('')
const running = ref(false)
const statusText = ref('')
const phase = ref('')
const progress = ref([])
const tab = ref('text')
const error = ref('')
const editing = ref(false)
const draftText = ref('')
const saving = ref(false)
const showRequirement = ref(false)
const requirementDraft = ref('')
/** 策划审稿后追加的补充要求（与写作要求分开） */
const supplements = ref([])
const showSupplements = ref(false)
/** SUBMITTED 状态的补充要求正在核验/打回 */
const supplementBusyId = ref(null)
const chapterMenuOpen = ref(false)
const newChapterTitle = ref('')
const showBlueprintPicker = ref(false)
const blueprintKeyword = ref('')
const blueprintSaving = ref(false)

const versions = ref([])
const versionsLoading = ref(false)
const previewVersion = ref(null)
const previewLoading = ref(false)
const rollbackTarget = ref(null)
const rolling = ref(false)
const compareOpen = ref(false)
const compareBaseId = ref(null)
const compareTargetId = ref(null)
const diffLines = ref([])
const comparing = ref(false)

const memory = ref('')
const memorySnapshot = ref('')
const memorySaving = ref(false)

const chatEl = ref(null)
const paperEl = ref(null)
let source = null
let currentTaskId = null
let runSeq = 0
let contentBuffer = ''
let flushFrame = 0

const hasBlueprint = computed(() => Boolean(props.blueprint))
const canRun = computed(() => hasBlueprint.value || Boolean(advice.value.trim()) || Boolean(text.value)
  || pendingInstructionCount.value > 0 || quotedPassages.value.length > 0)
const primaryLabel = computed(() => (text.value
  ? (quotedPassages.value.length ? '按引用修改' : '按这个改')
  : '开始写'))
const advicePlaceholder = computed(() => {
  if (quotedPassages.value.length) {
    return `已引用 ${quotedPassages.value.length} 段，针对它们想怎么改？（${sendHint()}）`
  }
  if (text.value) return `对现有稿的意见，比如：把开头改得更陡一些（${sendHint()}）`
  return `想交代什么就写在这里，留空则按蓝图与写作要求写（${sendHint()}）`
})
const sourceLabels = { AI: 'AI 生成', MANUAL: '手动保存', ROLLBACK: '回滚' }
const wordCount = computed(() => (text.value || '').replace(/\s/g, '').length)
const activeVersions = computed(() => versions.value)

function scrollChat() {
  nextTick(() => {
    if (chatEl.value) chatEl.value.scrollTop = chatEl.value.scrollHeight
  })
}
function scrollPaper() {
  nextTick(() => {
    if (paperEl.value) paperEl.value.scrollTop = paperEl.value.scrollHeight
  })
}

// ==================== 引用对话/正文 ====================

function closeQuoteMenu() {
  quoteMenu.value = null
}

function onGlobalClickForQuote() {
  closeQuoteMenu()
}

function onGlobalKeydownForQuote(event) {
  if (event.key === 'Escape') closeQuoteMenu()
}

function onQuoteContextMenu(event) {
  const selection = window.getSelection()?.toString().trim() || ''
  const target = event.target.closest?.('.bubble, .paper-text, .ci-content')
  if (!target) return
  let text = selection
  let source = '对话'
  if (target.classList.contains('paper-text')) {
    source = '正文'
    // 正文整章太长，必须由用户先选中一段；聊天消息没有选中时允许引用整条
    if (!text) return
  } else if (target.classList.contains('ci-content')) {
    source = '写作指令'
  } else {
    source = '写作 Agent'
    if (!text) text = target.innerText.trim()
  }
  if (!text) return
  event.preventDefault()
  quoteMenu.value = {
    x: Math.min(event.clientX, window.innerWidth - 150),
    y: Math.min(event.clientY, window.innerHeight - 70),
    text: text.slice(0, 5000),
    source
  }
}

function toggleQuote(id) {
  const next = new Set(expandedQuotes.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedQuotes.value = next
}

function isQuoteExpanded(quote) {
  return expandedQuotes.value.has(quote.id)
}

function addQuoteFromMenu() {
  const menu = quoteMenu.value
  if (!menu) return
  const id = ++quoteSeq
  quotedPassages.value.push({ id, text: menu.text, source: menu.source })
  // 短引用直接展开，长引用默认折叠，避免输入区被一大段原文淹没
  if (menu.text.length <= 120) {
    const next = new Set(expandedQuotes.value)
    next.add(id)
    expandedQuotes.value = next
  }
  quoteMenu.value = null
}

function removeQuote(id) {
  quotedPassages.value = quotedPassages.value.filter((quote) => quote.id !== id)
}

function clearQuotes() {
  quotedPassages.value = []
  expandedQuotes.value = new Set()
}

/** 把引用块 + 用户输入拼成一条发给写作 Agent 的指令 */
function buildOutgoing() {
  const typed = advice.value.trim()
  if (!quotedPassages.value.length) {
    return typed || (text.value
      ? `这是「${full.value?.title || '本章'}」的现有稿，请通读一遍并在不改动剧情的前提下润色文字。`
      : '请按本章蓝图与写作要求写这一章的正文。')
  }
  const quoteBlock = quotedPassages.value.map((quote, index) =>
    `【引用 ${index + 1} · 来自${quote.source}】\n${quote.text}`).join('\n\n')
  if (!typed) return `${quoteBlock}\n\n请根据以上引用内容修改这一章；没提到的部分保持不变。`
  return `${quoteBlock}\n\n【我的修改意见】\n${typed}`
}

/** 流式正文批量刷新：一帧最多更新一次，避免每个 token 全文重渲染 */
function pushContent(delta) {
  contentBuffer += delta
  if (flushFrame) return
  flushFrame = requestAnimationFrame(() => {
    flushFrame = 0
    text.value += contentBuffer
    contentBuffer = ''
    scrollPaper()
  })
}
function flushContentNow() {
  if (flushFrame) {
    cancelAnimationFrame(flushFrame)
    flushFrame = 0
  }
  if (contentBuffer) {
    text.value += contentBuffer
    contentBuffer = ''
  }
}

async function loadChapter(id) {
  const detail = await getChapter(id)
  full.value = detail
  text.value = detail.content || ''
  requirement.value = detail.writingRequirement || ''
  editing.value = false
  draftText.value = ''
}

watch(
  () => props.chapter?.id,
  async (id) => {
    // 切章节只"断开这个舞台的观察"，不取消后台任务：只有用户点停下才算中断
    detachRunning()
    runSeq += 1
    running.value = false
    streamingAnswer.value = ''
    streamingReasoning.value = ''
    progress.value = []
    error.value = ''
    statusText.value = ''
    clearQuotes()
    tab.value = 'text'
    full.value = null
    text.value = ''
    versions.value = []
    memory.value = ''
    if (!id) return
    try {
      await loadChapter(id)
      await loadInstructions()
      await loadDeleteRequests()
      await loadSupplements()
      await reconnectActiveTask()
    } catch (e) {
      error.value = e.message
    }
  },
  { immediate: true }
)

watch(tab, async (value) => {
  if (!props.chapter?.id) return
  if (value === 'versions') await loadVersions()
  if (value === 'memory') await loadMemory()
})

onMounted(() => {
  loadConversation()
  loadInstructions()
  loadDeleteRequests()
  loadSupplements()
  document.addEventListener('click', onGlobalClickForQuote)
  document.addEventListener('keydown', onGlobalKeydownForQuote)
})
// 被 KeepAlive 缓存后切回写作模式：策划可能刚下发过新指令，重新拉一次
onActivated(() => {
  loadConversation()
  loadInstructions()
  loadDeleteRequests()
  loadSupplements()
  if (!running.value) reconnectActiveTask()
  // KeepAlive 切回时 DOM 重新插入会把滚动位置重置到顶部；直接回到最后一条消息
  scrollChat()
  scrollPaper()
})
onBeforeUnmount(() => {
  // 离开工作台不取消后台任务：这次运行是用户发起的，只有点"停下"才终止。
  // 只断开事件流，任务继续跑；回来时通过 active-task 重新挂上。
  detachRunning()
  if (flushFrame) cancelAnimationFrame(flushFrame)
  document.removeEventListener('click', onGlobalClickForQuote)
  document.removeEventListener('keydown', onGlobalKeydownForQuote)
})

async function loadConversation() {
  try {
    const data = await getWriterConversation(props.projectId)
    messages.value = (data.messages || []).map((m) => ({
      role: m.role,
      text: m.content,
      createdAt: m.createdAt || '',
      id: m.id
    }))
    scrollChat()
  } catch (e) {
    console.warn('读取写作会话失败', e)
  }
}

async function loadInstructions() {
  try {
    plannerInstructions.value = (await getWriterInstructions(props.projectId)) || []
  } catch (e) {
    console.warn('读取策划分工指令失败', e)
  }
}

async function cancelInstruction(instruction) {
  if (instruction.status !== 'PENDING') return
  try {
    await cancelWriterInstruction(instruction.id)
    notify.info('已撤回这条策划写作指令')
    await loadInstructions()
  } catch (e) {
    notify.error(e.message || '撤回指令失败')
  }
}

async function loadDeleteRequests() {
  try {
    deleteRequests.value = (await getChapterDeleteRequests(props.projectId)) || []
  } catch (e) {
    console.warn('读取章节删除申请失败', e)
  }
}

async function loadSupplements() {
  if (!props.chapter?.id) {
    supplements.value = []
    return
  }
  try {
    supplements.value = (await getChapterSupplements(props.chapter.id)) || []
  } catch (e) {
    console.warn('读取章节补充要求失败', e)
  }
}

const pendingSupplementCount = computed(() =>
  supplements.value.filter((item) => item.status === 'PENDING' || item.status === 'SUBMITTED').length)

async function verifySupplement(item) {
  if (supplementBusyId.value || !props.chapter?.id) return
  supplementBusyId.value = item.id
  try {
    await verifyChapterSupplement(props.chapter.id, item.id)
    notify.success('已确认这条要求落实')
    await loadSupplements()
  } catch (e) {
    notify.error(e.message || '确认失败')
  } finally {
    supplementBusyId.value = null
  }
}

async function returnSupplement(item) {
  if (supplementBusyId.value || !props.chapter?.id) return
  supplementBusyId.value = item.id
  try {
    await returnChapterSupplement(props.chapter.id, item.id)
    notify.info('已打回，下次写作会重新带上这条要求')
    await loadSupplements()
  } catch (e) {
    notify.error(e.message || '操作失败')
  } finally {
    supplementBusyId.value = null
  }
}

function openSupplements() {
  showSupplements.value = true
  loadSupplements()
}

/**
 * 待确认申请必须跨章显示：写作 Agent 只能删"其他章"，如果只按当前章过滤，
 * 它在写第 N 章时申请删第 M 章，作者切到第 N 章反而看不到这条申请。
 * 已经处理过的历史记录则只显示当前章的，避免长期占地方。
 */
const chapterDeleteRequests = computed(() =>
  deleteRequests.value.filter((item) => item.status === 'PENDING'
    || String(item.chapterId) === String(props.chapter?.id)))
const pendingDeleteCount = computed(() =>
  chapterDeleteRequests.value.filter((item) => item.status === 'PENDING').length)

/**
 * 把对话消息与"策划的写作指令"按时间合并成一条时间线。
 *
 * <p>之前指令固定悬浮在聊天顶部，越聊越远；现在它出现在当时的对话位置，
 * 随对话一起滚走。已执行（CONSUMED）默认折叠，点开能看全文。
 */
const timeline = computed(() => {
  const items = messages.value.map((msg, index) => ({
    kind: 'message',
    at: msg.createdAt || '',
    order: index,
    data: msg
  }))
  chapterInstructions.value.forEach((ins, index) => {
    items.push({
      kind: 'instruction',
      at: ins.createdAt || '',
      order: 100000 + index,
      data: ins
    })
  })
  return items.sort((a, b) => {
    const byTime = String(a.at).localeCompare(String(b.at))
    return byTime !== 0 ? byTime : a.order - b.order
  })
})

function isInstructionOpen(ins) {
  if (ins.status === 'PENDING') return true
  return expandedInstructions.value.has(ins.id)
}

function toggleInstruction(ins) {
  if (ins.status === 'PENDING') return
  const next = new Set(expandedInstructions.value)
  if (next.has(ins.id)) next.delete(ins.id)
  else next.add(ins.id)
  expandedInstructions.value = next
}

async function approveDelete(request) {
  deleteConfirm.value = null
  deletingChapter.value = true
  try {
    await approveChapterDeleteRequest(request.id)
    notify.success(`已删除章节《${request.chapterTitle || '未命名'}》`)
    await loadDeleteRequests()
    emit('reload')
  } catch (e) {
    notify.error(e.message || '删除失败')
  } finally {
    deletingChapter.value = false
  }
}

async function rejectDelete(request) {
  try {
    await rejectChapterDeleteRequest(request.id)
    notify.info(`已保留章节《${request.chapterTitle || '未命名'}》`)
    await loadDeleteRequests()
  } catch (e) {
    notify.error(e.message || '操作失败')
  }
}

/** 只显示当前章的指令；策划可能给其他章也下过指令 */
const chapterInstructions = computed(() =>
  plannerInstructions.value.filter((item) => String(item.chapterId) === String(props.chapter?.id)))
const pendingInstructionCount = computed(() =>
  chapterInstructions.value.filter((item) => item.status === 'PENDING').length)

async function loadVersions() {
  versionsLoading.value = true
  try {
    versions.value = await listVersions(props.chapter.id)
  } catch (e) {
    notify.error(e.message)
  } finally {
    versionsLoading.value = false
  }
}

async function loadMemory() {
  try {
    const m = await getMemory(props.chapter.id)
    memory.value = m.memory || ''
    memorySnapshot.value = memory.value
  } catch (e) {
    console.warn('读取章节记忆失败', e)
  }
}

function closeStream() {
  if (source) {
    source.close()
    source = null
  }
}

async function abortRunning() {
  runSeq += 1
  const taskId = currentTaskId
  currentTaskId = null
  closeStream()
  if (taskId) {
    try {
      await cancelWritingTask(taskId)
    } catch {
      /* 可能已结束 */
    }
  }
}

/** 只断开本舞台的订阅，不碰后台任务 */
function detachRunning() {
  runSeq += 1
  currentTaskId = null
  closeStream()
  running.value = false
  statusText.value = ''
  phase.value = ''
  streamingAnswer.value = ''
  streamingReasoning.value = ''
}

/**
 * 切走再切回 / 刷新后重新挂上正在跑的任务。
 *
 * <p>这是"只有用户手动点停下才中断"的关键：任务在后端照跑，
 * 前端回来通过 active-task 找到它并恢复事件流，而不是取消重来。
 */
async function reconnectActiveTask() {
  if (!props.chapter?.id || running.value) return
  try {
    const data = await getActiveWritingTask(props.chapter.id)
    if (!data?.taskId) return
    const seq = ++runSeq
    currentTaskId = data.taskId
    running.value = true
    error.value = ''
    progress.value = []
    phase.value = '写作任务仍在后台运行…'
    statusText.value = '写作中'
    source = subscribeWritingTask(data.taskId, {
      onStatus: (s) => {
        if (seq !== runSeq) return
        statusText.value = s === 'RUNNING' ? '写作中'
          : s === 'SUCCEEDED' ? '已完成'
            : s === 'CANCELLED' ? '已停止'
              : s === 'FAILED' ? '失败' : s
      },
      onProgress: (p) => {
        if (seq !== runSeq) return
        progress.value = [...progress.value.slice(-3), p]
        scrollChat()
      },
      onContent: (delta) => { if (seq === runSeq) pushContent(delta) },
      onContentSnapshot: (snapshot) => {
        if (seq !== runSeq) return
        flushContentNow()
        text.value = snapshot
        scrollPaper()
      },
      onDelta: (delta) => { if (seq === runSeq) { streamingAnswer.value += delta; scrollChat() } },
      onDeltaSnapshot: (snapshot) => { if (seq === runSeq) streamingAnswer.value = snapshot },
      onReasoning: (delta) => { if (seq === runSeq) { streamingReasoning.value += delta; scrollChat() } },
      onReasoningSnapshot: (snapshot) => { if (seq === runSeq) streamingReasoning.value = snapshot },
      onError: (message) => {
        if (seq !== runSeq) return
        running.value = false
        error.value = message
        loadSupplements()
      },
      onEnd: async () => {
        if (seq !== runSeq) return
        flushContentNow()
        running.value = false
        source = null
        currentTaskId = null
        try {
          await loadChapter(props.chapter.id)
          await loadConversation()
          await loadInstructions()
          await loadSupplements()
          emit('reload')
        } catch (e) {
          console.warn('写完后刷新失败', e)
        }
        streamingAnswer.value = ''
        streamingReasoning.value = ''
      }
    })
  } catch (e) {
    console.warn('恢复后台写作任务失败', e)
  }
}

async function start(options = {}) {
  // 模板里 @click="start" 会把 MouseEvent 传进来；这里只认显式 options
  if (options instanceof Event) options = {}
  const supplement = options.supplement || null
  if (!props.chapter?.id || running.value || (!supplement && !canRun.value)) return
  const outgoing = supplement
    ? `请执行本章补充要求（记录 ID ${supplement.id}）：${supplement.content}`
    : buildOutgoing()
  const revising = Boolean(text.value)
  const seq = ++runSeq

  messages.value = [...messages.value, {
    role: 'user',
    text: outgoing,
    createdAt: new Date().toISOString()
  }]
  if (!supplement) {
    composerDraft.clear()
    clearQuotes()
  }
  running.value = true
  error.value = ''
  progress.value = []
  streamingAnswer.value = ''
  streamingReasoning.value = ''
  phase.value = supplement ? '正在处理补充要求…' : '正在准备材料…'
  statusText.value = '写作中'
  scrollChat()

  contentBuffer = ''
  if (!revising) text.value = ''

  const timer = setTimeout(() => (phase.value = '正在写正文…'), 8000)

  try {
    const { taskId } = await startWritingTask(props.chapter.id, outgoing,
      settings.autoApproveDelete, supplement?.id || null)
    await loadSupplements()
    if (seq !== runSeq) {
      // 章节被切走：任务仍在后台跑，不取消；回到该章时会自动重新挂上
      return
    }
    currentTaskId = taskId
    source = subscribeWritingTask(taskId, {
      onStatus: (s) => {
        if (seq !== runSeq) return
        statusText.value = s === 'RUNNING' ? '写作中'
          : s === 'SUCCEEDED' ? '已完成'
            : s === 'CANCELLED' ? '已停止'
              : s === 'FAILED' ? '失败' : s
      },
      onProgress: (p) => {
        if (seq !== runSeq) return
        progress.value = [...progress.value.slice(-3), p]
      },
      onContent: (delta) => { if (seq === runSeq) pushContent(delta) },
      onContentSnapshot: (snapshot) => {
        if (seq !== runSeq) return
        flushContentNow()
        text.value = snapshot
        scrollPaper()
      },
      onDelta: (delta) => { if (seq === runSeq) { streamingAnswer.value += delta; scrollChat() } },
      onDeltaSnapshot: (snapshot) => { if (seq === runSeq) streamingAnswer.value = snapshot },
      onReasoning: (delta) => { if (seq === runSeq) { streamingReasoning.value += delta; scrollChat() } },
      onReasoningSnapshot: (snapshot) => { if (seq === runSeq) streamingReasoning.value = snapshot },
      onError: (message) => {
        if (seq !== runSeq) return
        running.value = false
        error.value = message
        loadSupplements()
        messages.value = [...messages.value, { role: 'assistant', text: `这次没能完成：${message}`, failed: true }]
      },
      onEnd: async () => {
        if (seq !== runSeq) return
        flushContentNow()
        running.value = false
        source = null
        currentTaskId = null
        clearTimeout(timer)
        try {
          await loadChapter(props.chapter.id)
          await loadConversation()
          await loadInstructions()
          await loadSupplements()
          emit('reload')
        } catch (e) {
          console.warn('写完后刷新失败', e)
        }
        streamingAnswer.value = ''
        streamingReasoning.value = ''
      }
    })
  } catch (e) {
    if (seq !== runSeq) return
    running.value = false
    clearTimeout(timer)
    error.value = e.message
    loadSupplements()
    messages.value = [...messages.value, { role: 'assistant', text: `任务没能启动：${e.message}`, failed: true }]
  }
}

function executeSupplement(supplement) {
  if (supplement.status !== 'PENDING') return
  start({ supplement })
}

async function stop() {
  runSeq += 1
  const taskId = currentTaskId
  currentTaskId = null
  closeStream()
  running.value = false
  statusText.value = '已停止'
  phase.value = ''
  if (taskId) {
    try { await cancelWritingTask(taskId) } catch { /* 已结束 */ }
  }
  await loadSupplements()
  // 后端在取消后会异步把 EXECUTING 退回 PENDING；稍后再刷一次，保证按钮恢复
  setTimeout(loadSupplements, 700)
  flushContentNow()
  try {
    await loadChapter(props.chapter.id)
  } catch { /* 忽略 */ }
  streamingAnswer.value = ''
  streamingReasoning.value = ''
  messages.value = [...messages.value, {
    role: 'assistant',
    text: '（已按你的要求停下。正文已刷新为上次保存的版本；未保存的生成内容不会保留。）'
  }]
  scrollChat()
}

// ==================== 编辑 / 保存 / 状态 ====================
function beginEdit() {
  draftText.value = text.value
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  draftText.value = ''
}

async function saveEdit() {
  if (!props.chapter?.id) return
  saving.value = true
  try {
    const saved = await saveChapter(props.chapter.id, {
      title: full.value?.title,
      outlineNodeId: full.value?.outlineNodeId,
      status: full.value?.status || 'DRAFT',
      content: draftText.value,
      createVersion: true,
      remark: '手动编辑'
    })
    full.value = saved
    text.value = saved.content || ''
    editing.value = false
    notify.success(`已保存（版本 ${saved.currentVersionNo}）`)
    emit('reload')
  } catch (e) {
    notify.error(e.message)
  } finally {
    saving.value = false
  }
}

async function markComplete() {
  if (!props.chapter?.id) return
  saving.value = true
  try {
    const saved = await saveChapter(props.chapter.id, {
      title: full.value?.title,
      outlineNodeId: full.value?.outlineNodeId,
      status: 'COMPLETED',
      content: text.value,
      createVersion: true,
      remark: '标记完成'
    })
    full.value = saved
    notify.success('已标记完成，会进入成书')
    emit('reload')
  } catch (e) {
    notify.error(e.message)
  } finally {
    saving.value = false
  }
}

// ==================== 章节目录 ====================
function pickChapter(chapter) {
  chapterMenuOpen.value = false
  if (chapter.id !== props.chapter?.id) emit('select-chapter', chapter)
}

async function createNewChapter() {
  const title = newChapterTitle.value.trim()
  if (!title) return
  try {
    const created = await createChapter(props.projectId, { title, status: 'DRAFT' })
    newChapterTitle.value = ''
    chapterMenuOpen.value = false
    emit('reload')
    emit('select-chapter', created)
    notify.success('章节已创建')
  } catch (e) {
    notify.error(e.message)
  }
}

// ==================== 章节 ↔ 蓝图关联 ====================
const filteredBlueprintOptions = computed(() => {
  const q = blueprintKeyword.value.trim()
  if (!q) return props.blueprintOptions
  return props.blueprintOptions.filter((n) => `${n.name} ${n.summary || ''}`.includes(q))
})

/** 只改关联字段，不产生新版本；正文与状态原样保留 */
async function saveBlueprintLink(outlineNodeId) {
  if (!props.chapter?.id) return
  blueprintSaving.value = true
  try {
    const saved = await saveChapter(props.chapter.id, {
      title: full.value?.title,
      outlineNodeId,
      status: full.value?.status || 'DRAFT',
      content: text.value,
      createVersion: false
    })
    full.value = { ...full.value, ...saved }
    showBlueprintPicker.value = false
    notify.success(outlineNodeId ? '已关联本章蓝图' : '已解除蓝图关联')
    emit('reload')
  } catch (e) {
    notify.error(e.message || '关联失败')
  } finally {
    blueprintSaving.value = false
  }
}

// ==================== 写作要求 ====================
function openRequirement() {
  requirementDraft.value = requirement.value
  showRequirement.value = true
}

async function saveRequirement() {
  try {
    const saved = await setWritingRequirement(props.chapter.id, requirementDraft.value)
    requirement.value = saved.writingRequirement || ''
    showRequirement.value = false
    notify.success('本章写作要求已保存')
  } catch (e) {
    notify.error(e.message)
  }
}

// ==================== 记忆 ====================
async function saveMemoryText() {
  memorySaving.value = true
  try {
    const saved = await saveMemory(props.chapter.id, { memory: memory.value })
    memory.value = saved.memory || ''
    memorySnapshot.value = memory.value
    notify.success('章节记忆已保存')
  } catch (e) {
    notify.error(e.message)
  } finally {
    memorySaving.value = false
  }
}

async function clearWriterMemory() {
  if (!window.confirm('清空写作 Agent 记住的对话？\n\n正文、蓝图、写作要求都不受影响。')) return
  try {
    await clearWriterHistory(props.projectId)
    messages.value = []
    notify.success('写作记忆已清空')
  } catch (e) {
    notify.error(e.message)
  }
}

// ==================== 版本 ====================
async function openPreview(version) {
  previewLoading.value = true
  previewVersion.value = { ...version, content: '' }
  try {
    const detail = await getVersion(version.id)
    previewVersion.value = { ...version, content: detail.content }
  } catch (e) {
    notify.error(e.message)
  } finally {
    previewLoading.value = false
  }
}

async function doRollback() {
  rolling.value = true
  try {
    const target = rollbackTarget.value
    await rollbackChapter(props.chapter.id, target.id)
    rollbackTarget.value = null
    notify.success(`已回滚至 v${target.versionNo}（历史版本保留）`)
    await loadVersions()
    await loadChapter(props.chapter.id)
    emit('reload')
  } catch (e) {
    notify.error(e.message)
  } finally {
    rolling.value = false
  }
}

async function openCompare() {
  if (!compareBaseId.value || !compareTargetId.value) return
  comparing.value = true
  compareOpen.value = true
  try {
    const result = await compareVersions(compareBaseId.value, compareTargetId.value)
    diffLines.value = result.lines || []
  } catch (e) {
    notify.error(e.message)
  } finally {
    comparing.value = false
  }
}

watch(() => props.chapter?.id, () => {
  compareBaseId.value = null
  compareTargetId.value = null
})
</script>

<template>
  <section class="stage writer">
    <header class="stage-head">
      <div class="chapter-picker">
        <button class="chapter-current" @click="chapterMenuOpen = !chapterMenuOpen">
          <span class="chapter-no">{{ full ? (chapters.findIndex(c => String(c.id) === String(chapter?.id)) + 1) : '—' }}</span>
          <span class="chapter-title">{{ chapter?.title || '选择章节' }}</span>
          <Icon name="caret" :size="12" />
        </button>
        <Transition name="menu">
          <div v-if="chapterMenuOpen" class="chapter-menu">
            <button
              v-for="(c, i) in chapters"
              :key="c.id"
              class="chapter-item"
              :class="{ active: c.id === chapter?.id }"
              @click="pickChapter(c)"
            >
              <span class="ci-no">{{ i + 1 }}</span>
              <span class="ci-title">{{ c.title }}</span>
              <span v-if="c.status === 'COMPLETED'" class="ci-done">完</span>
            </button>
            <p v-if="!chapters.length" class="ci-empty">这本书还没有章节</p>
            <div class="chapter-new">
              <input v-model="newChapterTitle" placeholder="新章节标题，回车创建" @keydown.enter="createNewChapter" />
            </div>
          </div>
        </Transition>
      </div>

      <div class="stage-meta">
        <V3Tag>{{ wordCount }} 字</V3Tag>
        <V3Tag v-if="full?.currentVersionNo" tone="gold">v{{ full.currentVersionNo }}</V3Tag>
        <V3Tag v-if="full?.status === 'COMPLETED'" tone="jade">已完成</V3Tag>
        <V3Tag v-if="running" tone="cinnabar">{{ statusText }}</V3Tag>
      </div>

      <div class="stage-actions">
        <button class="head-btn" :class="{ 'is-linked': blueprint }" @click="showBlueprintPicker = true">
          <Icon name="layers" :size="13" />
          {{ blueprint ? '已关联蓝图' : '关联蓝图' }}
        </button>
        <button class="head-btn" @click="openRequirement">
          <Icon name="edit" :size="13" /> 写作要求
        </button>
        <button class="head-btn" @click="openSupplements">
          <Icon name="layers" :size="13" /> 补充要求
          <span v-if="pendingSupplementCount" class="head-count">{{ pendingSupplementCount }}</span>
        </button>
        <button v-if="messages.length" class="head-btn" title="清空写作记忆" @click="clearWriterMemory">
          清空记忆
        </button>
        <V3Button
          v-if="full && full.status !== 'COMPLETED'"
          size="sm"
          variant="outline"
          :loading="saving"
          @click="markComplete"
        >
          标记完成
        </V3Button>
      </div>
    </header>

    <div
      class="writer-body"
      :style="{ '--chat-width': chatWidth + 'px' }"
      @contextmenu="onQuoteContextMenu"
    >
      <!-- ==================== 左：对话 ==================== -->
      <div class="chat-pane">
        <div ref="chatEl" class="writer-chat v3-scroll">
          <!-- 章节删除申请：高风险动作，默认必须作者点确认 -->
          <div v-if="chapterDeleteRequests.length" class="delete-requests">
            <header class="dr-head">
              <span class="dr-sender">写作 Agent</span>
              <span class="dr-title">申请删除章节</span>
              <span v-if="pendingDeleteCount" class="dr-count">{{ pendingDeleteCount }} 条待确认</span>
            </header>
            <article
              v-for="req in chapterDeleteRequests"
              :key="req.id"
              class="delete-request"
              :class="{ pending: req.status === 'PENDING', danger: req.status === 'PENDING' }"
            >
              <div class="dr-meta">
                <V3Tag :tone="req.status === 'PENDING' ? 'cinnabar' : 'line'">
                  {{ req.status === 'PENDING' ? '等待确认' : req.status === 'APPROVED' ? '已删除' : '已保留' }}
                </V3Tag>
                <span class="dr-chapter">《{{ req.chapterTitle || '未命名章节' }}》</span>
              </div>
              <p v-if="req.reason" class="dr-reason">{{ req.reason }}</p>
              <p class="dr-risk">删除后正文、历史版本与章节记忆一并移除，<b>不可恢复</b>。</p>
              <div v-if="req.status === 'PENDING'" class="dr-actions">
                <V3Button variant="ghost" size="sm" @click="rejectDelete(req)">保留章节</V3Button>
                <V3Button variant="danger" size="sm" @click="deleteConfirm = req">删除章节</V3Button>
              </div>
            </article>
          </div>

          <div v-if="!timeline.length && !running" class="chat-empty">
            <span class="empty-seal">文</span>
            <h3>和写作 Agent 聊</h3>
            <p>右边是这一章的正文。你可以直接说要写什么、要怎么改。</p>
            <p class="muted">这段对话跨章连续：你说过的偏好（比如「少写心理描写」），后面每一章它都会记得。</p>
          </div>

          <!--
            对话消息 + 策划写作指令合并成一条时间线：
            指令出现在当时的对话位置，随对话滚走；执行后默认折叠。
          -->
          <template v-for="item in timeline" :key="item.kind + '-' + (item.data.id || item.order)">
            <div
              v-if="item.kind === 'message'"
              class="msg"
              :class="['is-' + item.data.role, { failed: item.data.failed }]"
            >
              <div class="msg-head"><span class="msg-who">{{ item.data.role === 'user' ? '你' : '写作 Agent' }}</span></div>
              <div class="bubble"><MarkdownText :text="item.data.text" /></div>
            </div>

            <article
              v-else
              class="chat-instruction"
              :class="{ pending: item.data.status === 'PENDING' }"
            >
              <header class="ci-head" @click="toggleInstruction(item.data)">
                <span class="ci-sender">策划 Agent</span>
                <span class="ci-title">写作指令</span>
                <V3Tag :tone="item.data.status === 'PENDING' ? 'gold' : 'line'">
                  {{ item.data.status === 'PENDING' ? '待执行'
                    : item.data.status === 'CANCELLED' ? '已撤回' : '已执行' }}
                </V3Tag>
                <span class="ci-time">{{ (item.data.createdAt || '').replace('T', ' ').slice(5, 16) }}</span>
                <button
                  v-if="item.data.status === 'PENDING'"
                  class="ci-cancel"
                  title="撤回这条尚未执行的指令"
                  @click.stop="cancelInstruction(item.data)"
                >
                  撤回
                </button>
                <span class="ci-fold">
                  {{ item.data.status === 'PENDING' ? '' : (isInstructionOpen(item.data) ? '收起' : '展开') }}
                </span>
              </header>
              <template v-if="isInstructionOpen(item.data)">
                <p class="ci-content">{{ item.data.content }}</p>
                <p v-if="item.data.reason" class="ci-reason">策划的理由：{{ item.data.reason }}</p>
              </template>
              <p v-else class="ci-folded">
                {{ item.data.status === 'CANCELLED' ? '已撤回' : '已执行' }} · 点击展开当时的指令
              </p>
            </article>
          </template>

          <div v-if="running" class="msg is-assistant">
            <div class="msg-head"><span class="msg-who">写作 Agent</span></div>
            <V3Thinking v-if="streamingReasoning" :text="streamingReasoning" :mode="settings.thinkingMode" />
            <div v-if="streamingAnswer" class="bubble"><MarkdownText :text="streamingAnswer" /></div>
            <div v-else class="bubble thinking">
              <span class="dots"><i></i><i></i><i></i></span>
              <span class="muted">{{ phase }}</span>
              <span v-for="(p, i) in progress" :key="i" class="mini-chip">{{ p }}</span>
            </div>
          </div>

          <p v-if="error" class="chat-error">{{ error }}</p>
        </div>

        <footer class="composer">
          <!-- 引用托盘：与用户手打的文字用不同底色/边框区分，长引用可折叠 -->
          <div v-if="quotedPassages.length" class="quote-tray">
            <div class="quote-tray-head">
              <span>已引用 {{ quotedPassages.length }} 段</span>
              <button class="quote-clear" @click="clearQuotes">清空引用</button>
            </div>
            <article
              v-for="quote in quotedPassages"
              :key="quote.id"
              class="quote-card"
              :class="{ expanded: isQuoteExpanded(quote) }"
            >
              <header class="quote-card-head" @click="toggleQuote(quote.id)">
                <Icon name="quote" :size="12" />
                <span class="quote-source">引自{{ quote.source }}</span>
                <span v-if="!isQuoteExpanded(quote)" class="quote-preview">
                  {{ quote.text.replace(/\s+/g, ' ').slice(0, 42) }}{{ quote.text.length > 42 ? '…' : '' }}
                </span>
                <span class="quote-fold">{{ isQuoteExpanded(quote) ? '收起' : '展开' }}</span>
                <button class="quote-remove" title="移除引用" @click.stop="removeQuote(quote.id)">×</button>
              </header>
              <p v-if="isQuoteExpanded(quote)" class="quote-text">{{ quote.text }}</p>
            </article>
          </div>
          <p v-if="pendingInstructionCount" class="pending-hint">
            策划 Agent 有 {{ pendingInstructionCount }} 条待执行指令；开始写作时会自动带上。你也可以在下面补充作者要求。
            <button class="pending-run" @click="start()">按策划指令开写</button>
          </p>
          <div class="input-box">
            <textarea
              v-model="advice"
              class="input-area"
              rows="2"
              :placeholder="advicePlaceholder"
              @keydown="onKeydown"
            ></textarea>
            <div class="input-side">
              <button v-if="running" class="round-btn stop" title="停下" @click="stop">
                <Icon name="stop" :size="14" />
              </button>
              <button
                v-else
                class="round-btn send"
                :disabled="!chapter || !canRun"
                :title="primaryLabel"
                @click="start()"
              >
                <Icon name="quill" :size="15" />
              </button>
            </div>
          </div>
          <div class="composer-foot">
            <span class="muted">{{ primaryLabel }} · {{ sendHint() }}</span>
            <!-- 蓝图关联状态已经在舞台头部有按钮显示，这里不再重复一行，留给模型/思考强度 -->
            <ModelPicker role="WRITER" />
          </div>
        </footer>
      </div>

      <!-- 正文/对话右键引用菜单 -->
      <div
        v-if="quoteMenu"
        class="quote-menu"
        :style="{ left: quoteMenu.x + 'px', top: quoteMenu.y + 'px' }"
        @click.stop
        @contextmenu.stop.prevent
      >
        <button class="quote-menu-item" @click="addQuoteFromMenu">
          <Icon name="quote" :size="12" />
          引用这段
        </button>
      </div>

      <!-- 对话与正文之间的拖拽分割线 -->
      <V3Splitter
        class="chat-splitter"
        title="拖拽调整对话区宽度，双击恢复默认"
        @drag-start="onChatResize"
        @reset="resetChatWidth"
      />

      <!-- ==================== 右：纸面 ==================== -->
      <section class="paper-pane">
        <nav class="paper-tabs">
          <button v-for="t in [{ k: 'text', label: '正文' }, { k: 'versions', label: '版本' }, { k: 'memory', label: '章节记忆' }]"
                  :key="t.k" class="paper-tab" :class="{ active: tab === t.k }" @click="tab = t.k">
            {{ t.label }}
          </button>
          <span class="paper-hint">{{ full?.title || '本章' }}</span>
        </nav>

        <div v-if="tab === 'text'" class="paper v3-scroll" ref="paperEl">
          <div v-if="editing" class="editor-wrap">
            <textarea v-model="draftText" class="paper-editor" rows="24"></textarea>
            <div class="editor-actions">
              <V3Button size="sm" :loading="saving" @click="saveEdit">保存并建版本</V3Button>
              <V3Button size="sm" variant="ghost" @click="cancelEdit">取消</V3Button>
            </div>
          </div>
          <template v-else>
            <div v-if="!text && !running" class="paper-empty">
              <p>还没有正文。</p>
              <p class="muted">在左边说一句，Agent 会按本章蓝图与写作要求来写。</p>
            </div>
            <div v-else class="paper-text" :class="{ streaming: running }">{{ text }}<span v-if="running" class="caret"></span></div>
            <div v-if="text && !running" class="paper-tools">
              <button class="paper-tool" @click="beginEdit"><Icon name="edit" :size="13" /> 编辑正文</button>
            </div>
          </template>
        </div>

        <div v-else-if="tab === 'versions'" class="versions v3-scroll">
          <div v-if="versionsLoading" class="muted pad">正在读取版本…</div>
          <template v-else-if="versions.length">
            <div class="version-list">
              <div v-for="v in versions" :key="v.id" class="version-row" :class="{ current: v.versionNo === full?.currentVersionNo }">
                <span class="v-no">v{{ v.versionNo }}</span>
                <V3Tag :tone="v.source === 'AI' ? 'gold' : v.source === 'ROLLBACK' ? 'jade' : 'line'">
                  {{ sourceLabels[v.source] || v.source }}
                </V3Tag>
                <span class="v-remark">{{ v.remark || '—' }}</span>
                <span class="v-time">{{ (v.createdAt || '').replace('T', ' ').slice(5, 16) }}</span>
                <div class="v-actions">
                  <button @click="openPreview(v)">预览</button>
                  <button @click="rollbackTarget = v">回滚</button>
                  <button :class="{ picked: compareBaseId === v.id }" @click="compareBaseId = compareBaseId === v.id ? null : v.id">基</button>
                  <button :class="{ picked: compareTargetId === v.id }" @click="compareTargetId = compareTargetId === v.id ? null : v.id">对</button>
                </div>
              </div>
            </div>
            <div class="compare-bar">
              <span class="muted">选「基」「对」两个版本后对比</span>
              <V3Button size="sm" variant="outline" :disabled="!compareBaseId || !compareTargetId" @click="openCompare">对比</V3Button>
            </div>
          </template>
          <p v-else class="muted pad">还没有版本。写完一章或手动保存后会自动生成版本。</p>
        </div>

        <div v-else class="memory v3-scroll">
          <p class="memory-hint">
            本章记忆会作为「前情」的一部分注入后续章节。建议写清楚：发生了什么、新出现的人与物、未解决的冲突、人物状态变化。
          </p>
          <textarea v-model="memory" class="memory-editor" rows="12" placeholder="本章发生了什么…"></textarea>
          <div class="memory-actions">
            <V3Button size="sm" :loading="memorySaving" :disabled="memory === memorySnapshot" @click="saveMemoryText">
              保存章节记忆
            </V3Button>
            <span class="muted">保存后自动进入后续上下文</span>
          </div>
        </div>
      </section>
    </div>

    <!-- 关联本章蓝图 -->
    <V3Modal :visible="showBlueprintPicker" title="关联本章蓝图" width="620px" @close="showBlueprintPicker = false">
      <p class="modal-hint">
        写作 Agent 的「本章蓝图」取自这里。没有关联时，它只能按写作要求与你的指令自由发挥；
        关联后蓝图会作为<b>必备材料</b>注入，永远不会被裁剪。
      </p>
      <input v-model="blueprintKeyword" class="bp-search" placeholder="搜索蓝图节点…" />
      <div class="bp-list v3-scroll">
        <button
          v-for="node in filteredBlueprintOptions"
          :key="node.id"
          class="bp-item"
          :class="{ active: String(blueprint?.id) === String(node.id) }"
          :disabled="blueprintSaving"
          @click="saveBlueprintLink(node.id)"
        >
          <span class="bp-type">{{ node.nodeType === 'VOLUME' ? '卷' : node.nodeType === 'ARC' ? '段' : '章' }}</span>
          <span class="bp-name">{{ node.name }}</span>
          <span class="bp-summary">{{ (node.summary || '').slice(0, 40) }}</span>
          <Icon v-if="String(blueprint?.id) === String(node.id)" name="check" :size="13" />
        </button>
        <p v-if="!filteredBlueprintOptions.length" class="muted bp-empty">
          还没有可关联的章节蓝图。让策划 Agent 先把走向落成蓝图节点。
        </p>
      </div>
      <template #footer>
        <V3Button variant="ghost" @click="showBlueprintPicker = false">取消</V3Button>
        <V3Button v-if="blueprint" variant="outline" :loading="blueprintSaving" @click="saveBlueprintLink(null)">
          解除关联
        </V3Button>
      </template>
    </V3Modal>

    <!-- 章节补充要求：与写作要求分开，逐条展示、逐条执行 -->
    <V3Modal :visible="showSupplements" title="章节补充要求" width="660px" @close="showSupplements = false">
      <p class="modal-hint">
        策划审稿后逐条追加的修改意见，与「写作要求」分开保存、互不覆盖。
        点单条的「执行」只让写作 Agent 处理这一条；写完后先变成「待核验」，请你看过正文再确认落实或打回。
      </p>
      <p v-if="!supplements.length" class="modal-hint muted">这一章还没有补充要求。</p>
      <div v-else class="supplement-list">
        <article
          v-for="(item, index) in supplements"
          :key="item.id"
          class="supplement-card"
          :class="{ pending: item.status === 'PENDING', executing: item.status === 'EXECUTING', submitted: item.status === 'SUBMITTED' }"
        >
          <header class="supplement-head">
            <span class="supplement-no">#{{ index + 1 }}</span>
            <V3Tag :tone="item.status === 'APPLIED' ? 'jade' : item.status === 'SUBMITTED' ? 'cinnabar' : 'gold'">
              {{ item.status === 'PENDING' ? '待执行' : item.status === 'EXECUTING' ? '执行中' :
                 item.status === 'SUBMITTED' ? '待核验' : '已核验' }}
            </V3Tag>
            <span class="supplement-time">{{ (item.createdAt || '').replace('T', ' ').slice(5, 16) }}</span>
          </header>
          <p class="supplement-content">{{ item.content }}</p>
          <p v-if="item.reason" class="supplement-reason">针对的问题：{{ item.reason }}</p>
          <div class="supplement-actions">
            <V3Button
              v-if="item.status === 'PENDING'"
              size="sm"
              :disabled="running"
              @click="executeSupplement(item)"
            >
              执行
            </V3Button>
            <V3Button
              v-else-if="item.status === 'EXECUTING'"
              size="sm"
              loading
              disabled
            >
              执行中
            </V3Button>
            <template v-else-if="item.status === 'SUBMITTED'">
              <V3Button
                size="sm"
                :loading="supplementBusyId === item.id"
                :disabled="Boolean(supplementBusyId)"
                @click="verifySupplement(item)"
              >
                确认落实
              </V3Button>
              <V3Button
                size="sm"
                variant="ghost"
                :disabled="Boolean(supplementBusyId)"
                @click="returnSupplement(item)"
              >
                打回重做
              </V3Button>
            </template>
            <span v-else class="supplement-done">已核验</span>
          </div>
        </article>
      </div>
      <template #footer>
        <V3Button variant="ghost" @click="showSupplements = false">关闭</V3Button>
      </template>
    </V3Modal>

    <!-- 写作要求 -->
    <V3Modal :visible="showRequirement" title="本章写作要求" width="640px" @close="showRequirement = false">
      <p class="modal-hint">「怎么写」——人称、视角、篇幅、禁忌、必须出现的元素。它会作为必备材料和蓝图一起交给写作 Agent，不会被裁剪。</p>
      <textarea v-model="requirementDraft" class="req-editor" rows="7"
        placeholder="例：第三人称，视角锁定在主角身上；篇幅 4000~5000 字；短句、动作与器物细节；不要写『仿佛』『宛如』这类套话；必须出现……"></textarea>
      <template #footer>
        <V3Button variant="ghost" @click="showRequirement = false">取消</V3Button>
        <V3Button @click="saveRequirement">保存要求</V3Button>
      </template>
    </V3Modal>

    <!-- 版本预览 -->
    <V3Modal :visible="!!previewVersion" :title="`版本预览 v${previewVersion?.versionNo || ''}`" width="760px" @close="previewVersion = null">
      <div v-if="previewLoading" class="muted">读取中…</div>
      <div v-else class="preview-body">{{ previewVersion?.content }}</div>
    </V3Modal>

    <!-- 回滚确认 -->
    <V3Modal :visible="!!rollbackTarget" title="回滚版本" width="460px" @close="rollbackTarget = null">
      <p class="modal-hint">
        以 v{{ rollbackTarget?.versionNo }} 的内容创建一个新版本并设为当前工作内容？历史版本不会删除。
      </p>
      <template #footer>
        <V3Button variant="ghost" @click="rollbackTarget = null">取消</V3Button>
        <V3Button :loading="rolling" @click="doRollback">确认回滚</V3Button>
      </template>
    </V3Modal>

    <!-- 版本对比 -->
    <V3Modal :visible="compareOpen" title="版本对比" width="820px" @close="compareOpen = false">
      <div v-if="comparing" class="muted">对比中…</div>
      <div v-else class="diff-body v3-scroll">
        <div v-for="(line, i) in diffLines" :key="i" class="diff-line" :class="'is-' + line.type">{{ line.text }}</div>
      </div>
    </V3Modal>

    <!-- 删除章节二次确认：高风险动作，不放进请求卡片的单击里 -->
    <V3Modal
      :visible="!!deleteConfirm"
      title="确认删除章节？"
      width="520px"
      @close="deleteConfirm = null"
    >
      <p class="modal-hint">
        写作 Agent 申请删除《{{ deleteConfirm?.chapterTitle || '未命名章节' }}》。
      </p>
      <p class="modal-hint danger-text">
        删除会同时移除这一章的正文、所有历史版本与章节记忆，<b>无法撤销</b>。
        如果只是这一章写得不好，建议用「保留章节」并让它重写，而不是删除。
      </p>
      <p v-if="deleteConfirm?.reason" class="modal-hint">Agent 的理由：{{ deleteConfirm.reason }}</p>
      <template #footer>
        <V3Button variant="ghost" :disabled="deletingChapter" @click="deleteConfirm = null">取消</V3Button>
        <V3Button variant="ghost" :disabled="deletingChapter" @click="rejectDelete(deleteConfirm); deleteConfirm = null">保留章节</V3Button>
        <V3Button variant="danger" :loading="deletingChapter" @click="approveDelete(deleteConfirm)">确认删除</V3Button>
      </template>
    </V3Modal>
  </section>
</template>

<style scoped>
.writer { flex: 1 1 auto; display: flex; flex-direction: column; height: 100%; min-width: 0; }
.stage-head {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 24px 12px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--v3-hairline);
}
.chapter-picker { position: relative; }
.chapter-current {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  height: 38px;
  padding: 0 16px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-pill);
  background: var(--v3-surface-1);
  color: var(--v3-text);
  font-family: var(--v3-font-serif);
  font-size: 14px;
  letter-spacing: 0.04em;
  cursor: pointer;
}
.chapter-current:hover { border-color: var(--v3-hairline-gold); }
.chapter-no { font-size: 11px; color: var(--v3-gold); font-family: var(--v3-font-mono); }
.chapter-menu {
  position: absolute;
  left: 0;
  top: 46px;
  z-index: 60;
  width: 300px;
  max-height: 360px;
  overflow-y: auto;
  padding: 8px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-md);
  background: var(--v3-surface-solid);
  box-shadow: var(--v3-shadow-float);
}
.chapter-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: var(--v3-r-xs);
  background: transparent;
  color: var(--v3-text-soft);
  font-family: inherit;
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}
.chapter-item:hover { background: var(--v3-surface-2); color: var(--v3-text); }
.chapter-item.active { background: var(--v3-glow-gold); color: var(--v3-text); }
.ci-no { width: 20px; font-family: var(--v3-font-mono); font-size: 10.5px; color: var(--v3-text-faint); }
.ci-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ci-done { font-size: 10px; color: var(--v3-jade); }
.ci-empty { padding: 10px; font-size: 11.5px; color: var(--v3-text-faint); }
.chapter-new { margin-top: 6px; padding: 6px; border-top: 1px solid var(--v3-hairline); }
.chapter-new input {
  width: 100%;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-xs);
  background: var(--v3-surface-1);
  color: var(--v3-text);
  font-size: 12px;
  outline: none;
}
.stage-meta { display: flex; align-items: center; gap: 7px; }
.stage-actions { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.head-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 11px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-pill);
  background: var(--v3-surface-1);
  color: var(--v3-text-muted);
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
}
.head-btn:hover { color: var(--v3-text); border-color: var(--v3-hairline-gold); }
.head-btn.is-linked { color: var(--v3-gold-bright); border-color: var(--v3-hairline-gold); background: var(--v3-glow-gold); }

.writer-body { flex: 1; min-height: 0; display: flex; }
.chat-pane {
  width: var(--chat-width, 420px);
  flex-shrink: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--v3-hairline);
}
.writer-chat { flex: 1; min-height: 0; padding: 18px 22px; }
.chat-empty { max-width: 400px; margin: 10vh auto 0; text-align: center; }
.head-count {
  min-width: 15px;
  height: 15px;
  padding: 0 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--v3-r-pill);
  background: var(--v3-glow-gold);
  border: 1px solid var(--v3-hairline-gold);
  color: var(--v3-gold-bright);
  font-size: 9px;
}
.supplement-list { display: flex; flex-direction: column; gap: 14px; }
.supplement-card {
  padding: 14px 16px;
  border: 1px solid var(--v3-hairline-strong);
  border-left: 3px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-2);
  box-shadow: 0 1px 0 rgba(0, 0, 0, 0.03);
}
.supplement-card.pending {
  border-color: var(--v3-hairline-gold);
  border-left-color: var(--v3-gold);
  background: var(--v3-glow-gold);
}
.supplement-card.executing {
  border-color: var(--v3-hairline-gold);
  border-left-color: var(--v3-gold-bright);
  background: var(--v3-surface-2);
}
.supplement-card.submitted {
  border-color: rgba(201, 76, 59, 0.35);
  border-left-color: var(--v3-cinnabar);
  background: var(--v3-cinnabar-soft);
}
.supplement-head { display: flex; align-items: center; gap: 8px; }
.supplement-no { font-family: var(--v3-font-mono); font-size: 10px; color: var(--v3-gold); }
.supplement-time { margin-left: auto; font-family: var(--v3-font-mono); font-size: 9.5px; color: var(--v3-text-faint); }
.supplement-content { margin: 9px 0 0; font-size: 12.5px; line-height: 1.85; color: var(--v3-text-soft); white-space: pre-wrap; }
.supplement-reason { margin: 7px 0 0; font-size: 10.5px; line-height: 1.7; color: var(--v3-text-faint); }
.supplement-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 11px; }
.supplement-done { font-size: 10.5px; color: var(--v3-jade); }

/* 时间线里的策划指令：未执行展开，已执行折叠 */
.chat-instruction {
  max-width: 640px;
  margin: 0 0 18px;
  padding: 12px 14px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  cursor: pointer;
  transition: border-color var(--v3-dur-2), background var(--v3-dur-2);
}
.chat-instruction:hover { border-color: var(--v3-hairline-gold); }
.chat-instruction.pending { border-color: var(--v3-hairline-gold); background: var(--v3-glow-gold); }
.ci-head { display: flex; align-items: center; gap: 8px; }
.ci-sender { font-size: 10.5px; letter-spacing: 0.14em; color: var(--v3-gold-bright); }
.ci-title { font-family: var(--v3-font-serif); font-size: 12.5px; color: var(--v3-text-soft); }
.ci-time { font-family: var(--v3-font-mono); font-size: 9.5px; color: var(--v3-text-faint); }
.ci-fold { margin-left: auto; font-size: 10px; color: var(--v3-text-faint); }
.ci-content { margin: 9px 0 0; font-size: 12px; line-height: 1.85; color: var(--v3-text-soft); white-space: pre-wrap; }
.ci-reason { margin: 7px 0 0; font-size: 10.5px; line-height: 1.7; color: var(--v3-text-faint); }
.ci-folded { margin: 7px 0 0; font-size: 10.5px; color: var(--v3-text-faint); }
.ci-cancel {
  flex-shrink: 0;
  padding: 1px 7px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-pill);
  background: transparent;
  color: var(--v3-text-faint);
  font-size: 9.5px;
  cursor: pointer;
}
.ci-cancel:hover { color: var(--v3-cinnabar); border-color: var(--v3-cinnabar); }
.planner-notes { margin: 0 0 18px; }
.danger-text { color: #e8a8a0; }
.danger-text b { color: #ffd9d3; }
.delete-requests { margin: 0 0 18px; }
.dr-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.dr-sender { font-size: 11px; letter-spacing: 0.12em; color: #e8a8a0; }
.dr-title { font-family: var(--v3-font-serif); font-size: 12.5px; color: var(--v3-text-soft); }
.dr-count { margin-left: auto; font-size: 10px; color: #e8a8a0; }
.delete-request {
  padding: 11px 13px;
  margin-bottom: 7px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
}
.delete-request.pending { border-color: rgba(201, 76, 59, 0.45); background: var(--v3-cinnabar-soft); }
.dr-meta { display: flex; align-items: center; gap: 8px; }
.dr-chapter { font-family: var(--v3-font-serif); font-size: 12.5px; color: var(--v3-text); }
.dr-reason { margin: 8px 0 0; font-size: 12px; line-height: 1.8; color: var(--v3-text-soft); white-space: pre-wrap; }
.dr-risk { margin: 7px 0 0; font-size: 10.5px; line-height: 1.7; color: #d8a39c; }
.dr-risk b { color: #ffd9d3; }
.dr-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 10px; }
.pn-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.pn-sender { font-size: 11px; letter-spacing: 0.12em; color: var(--v3-gold-bright); }
.pn-title { font-family: var(--v3-font-serif); font-size: 12.5px; color: var(--v3-text-soft); }
.pn-count { margin-left: auto; font-size: 10px; color: var(--v3-text-faint); }
.planner-note {
  padding: 11px 13px;
  margin-bottom: 7px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
}
.planner-note.pending { border-color: var(--v3-hairline-gold); background: var(--v3-glow-gold); }
.pn-meta { display: flex; align-items: center; gap: 8px; }
.pn-time { font-size: 9.5px; color: var(--v3-text-faint); font-family: var(--v3-font-mono); }
.pn-content { margin: 8px 0 0; font-size: 12px; line-height: 1.85; color: var(--v3-text-soft); white-space: pre-wrap; }
.pn-reason { margin: 7px 0 0; font-size: 10.5px; line-height: 1.7; color: var(--v3-text-faint); }
.pending-hint {
  margin: 0 0 8px;
  padding: 7px 11px;
  border: 1px solid var(--v3-hairline-gold);
  border-radius: var(--v3-r-xs);
  background: var(--v3-glow-gold);
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--v3-gold-bright);
}
.pending-run {
  margin-left: 8px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--v3-gold-bright);
  font-size: 10.5px;
  text-decoration: underline;
  text-underline-offset: 2px;
  cursor: pointer;
}
.empty-seal {
  display: inline-grid;
  place-items: center;
  width: 48px;
  height: 48px;
  margin-bottom: 16px;
  border: 1.5px solid var(--v3-cinnabar);
  border-radius: 9px;
  color: var(--v3-cinnabar);
  font-family: var(--v3-font-serif);
  font-size: 20px;
  transform: rotate(-4deg);
}
.chat-empty h3 { margin: 0 0 10px; font-family: var(--v3-font-serif); font-size: 18px; letter-spacing: 0.06em; }
.chat-empty p { margin: 6px 0; font-size: 12.5px; line-height: 1.9; color: var(--v3-text-soft); }
.chat-empty .muted { font-size: 11.5px; color: var(--v3-text-faint); }
.msg { max-width: 640px; margin-bottom: 18px; }
.msg.is-user { display: flex; flex-direction: column; align-items: flex-end; margin-left: auto; }
.msg-head { margin-bottom: 6px; }
.msg.is-user .msg-head { text-align: right; }
.msg-who { font-size: 10px; letter-spacing: 0.18em; color: var(--v3-text-faint); }
.bubble {
  padding: 14px 17px;
  border: 1px solid var(--v3-hairline);
  border-radius: 4px var(--v3-r-md) var(--v3-r-md) var(--v3-r-md);
  background: var(--v3-surface-1);
  font-size: 13px;
  line-height: 1.95;
  color: var(--v3-text-soft);
}
.msg.is-user .bubble { display: inline-block; max-width: 100%; border-radius: var(--v3-r-md) 4px var(--v3-r-md) var(--v3-r-md); background: var(--v3-glow-gold); border-color: var(--v3-hairline-gold); color: var(--v3-text); }
.msg.failed .bubble { border-color: rgba(201, 76, 59, 0.4); background: var(--v3-cinnabar-soft); color: #ffd9d3; }
.bubble.thinking { display: flex; align-items: center; gap: 10px; color: var(--v3-text-faint); }
.dots { display: inline-flex; gap: 4px; }
.dots i { width: 5px; height: 5px; border-radius: 50%; background: var(--v3-gold); animation: blink 1.2s infinite; }
.dots i:nth-child(2) { animation-delay: 0.15s; }
.dots i:nth-child(3) { animation-delay: 0.3s; }
@keyframes blink { 0%, 100% { opacity: 0.25; } 50% { opacity: 1; } }
.mini-chip { padding: 2px 8px; border-radius: var(--v3-r-pill); border: 1px solid var(--v3-hairline); font-size: 10px; color: var(--v3-text-faint); }
.chat-error { margin: 12px 0; padding: 10px 14px; border-radius: var(--v3-r-sm); background: var(--v3-cinnabar-soft); color: #ffd9d3; font-size: 12.5px; }
.muted { color: var(--v3-text-faint); }

.composer { flex-shrink: 0; padding: 12px 20px 16px; border-top: 1px solid var(--v3-hairline); }

/* 右键引用菜单 */
.quote-menu {
  position: fixed;
  z-index: 80;
  padding: 5px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-solid);
  box-shadow: var(--v3-shadow-float);
}
.quote-menu-item {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 12px;
  border: 0;
  border-radius: var(--v3-r-xs);
  background: transparent;
  color: var(--v3-text-soft);
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}
.quote-menu-item:hover { background: var(--v3-glow-gold); color: var(--v3-gold-bright); }

/* 输入区上方的引用托盘：和正常输入分开 */
.quote-tray { margin-bottom: 9px; }
.quote-tray-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 10px;
  letter-spacing: 0.1em;
  color: var(--v3-gold);
}
.quote-clear {
  border: 0;
  background: transparent;
  color: var(--v3-text-faint);
  font-size: 10px;
  cursor: pointer;
}
.quote-clear:hover { color: var(--v3-cinnabar); }
.quote-card {
  margin-bottom: 6px;
  padding: 8px 11px;
  border: 1px solid var(--v3-hairline-gold);
  border-left: 3px solid var(--v3-gold);
  border-radius: var(--v3-r-xs);
  background: var(--v3-glow-gold);
}
.quote-card-head { display: flex; align-items: center; gap: 6px; cursor: pointer; color: var(--v3-gold-bright); }
.quote-source { flex-shrink: 0; font-size: 10px; letter-spacing: 0.06em; }
.quote-preview {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 10.5px;
  color: var(--v3-text-muted);
}
.quote-fold { flex-shrink: 0; font-size: 9.5px; color: var(--v3-text-faint); }
.quote-remove {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--v3-text-faint);
  cursor: pointer;
  font-size: 13px;
  line-height: 1;
}
.quote-remove:hover { color: var(--v3-cinnabar); background: var(--v3-cinnabar-soft); }
.quote-text {
  margin: 7px 0 0;
  max-height: 150px;
  overflow-y: auto;
  padding-top: 7px;
  border-top: 1px dashed var(--v3-hairline-gold);
  font-size: 11.5px;
  line-height: 1.8;
  color: var(--v3-text-soft);
  white-space: pre-wrap;
}
.input-box { display: flex; align-items: flex-end; gap: 8px; padding: 10px 10px 10px 14px; border: 1px solid var(--v3-hairline-strong); border-radius: var(--v3-r-lg); background: var(--v3-surface-1); }
.input-box:focus-within { border-color: var(--v3-hairline-gold); box-shadow: 0 0 0 3px var(--v3-glow-gold); }
.input-area { flex: 1; min-height: 44px; max-height: 150px; padding: 7px 0; border: 0; background: transparent; color: var(--v3-text); font-family: inherit; font-size: 13px; line-height: 1.8; resize: none; outline: none; }
.input-area::placeholder { color: var(--v3-text-faint); }
.round-btn { width: 36px; height: 36px; display: grid; place-items: center; border: 0; border-radius: 50%; cursor: pointer; transition: transform var(--v3-dur-2) var(--v3-ease-spring); }
.round-btn.send { background: var(--v3-grad-gold); color: #241d12; }
.round-btn.send:hover { transform: translateY(-1px) scale(1.03); }
.round-btn.send:disabled { opacity: 0.35; cursor: not-allowed; }
.round-btn.stop { background: var(--v3-cinnabar-soft); color: var(--v3-cinnabar); border: 1px solid rgba(201, 76, 59, 0.45); }
.composer-foot { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 8px; font-size: 10.5px; }
.foot-right { display: flex; align-items: center; gap: 10px; min-width: 0; }

.paper-pane { flex: 1; min-width: 0; display: flex; flex-direction: column; background: var(--v3-inset); }
.paper-tabs { display: flex; align-items: center; gap: 4px; padding: 14px 20px 10px; flex-shrink: 0; }
.paper-tab {
  padding: 6px 13px;
  border: 0;
  border-radius: var(--v3-r-pill);
  background: transparent;
  color: var(--v3-text-faint);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: background var(--v3-dur-2), color var(--v3-dur-2);
}
.paper-tab:hover { color: var(--v3-text-soft); }
.paper-tab.active { color: var(--v3-text); background: var(--v3-surface-2); }
.paper-hint { margin-left: auto; font-size: 11px; color: var(--v3-text-faint); }
.paper { flex: 1; min-height: 0; padding: 8px 26px 40px; }
.paper-empty { margin-top: 18vh; text-align: center; color: var(--v3-text-muted); }
.paper-empty p { margin: 6px 0; font-size: 13px; }
.paper-empty .muted { font-size: 12px; color: var(--v3-text-faint); }
.paper-text {
  max-width: 760px;
  margin: 0 auto;
  padding: 34px 42px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-lg);
  background: var(--v3-surface-solid);
  font-family: var(--v3-font-serif);
  font-size: 15.5px;
  line-height: 2.15;
  letter-spacing: 0.01em;
  color: var(--v3-text);
  white-space: pre-wrap;
  word-break: break-word;
  box-shadow: var(--v3-shadow-card);
}
html[data-v3-theme='sujuan'] .paper-text { background: #fffdf7; }
.paper-text.streaming { border-color: var(--v3-hairline-gold); }
.caret { display: inline-block; width: 2px; height: 1.1em; margin-left: 2px; vertical-align: -0.15em; background: var(--v3-gold); animation: v3-caret 1s steps(1) infinite; }
.paper-tools { display: flex; justify-content: center; margin-top: 18px; }
.paper-tool { display: inline-flex; align-items: center; gap: 7px; height: 30px; padding: 0 14px; border: 1px solid var(--v3-hairline-strong); border-radius: var(--v3-r-pill); background: var(--v3-surface-1); color: var(--v3-text-muted); font-size: 11.5px; cursor: pointer; }
.paper-tool:hover { color: var(--v3-text); border-color: var(--v3-hairline-gold); }
.editor-wrap { max-width: 820px; margin: 0 auto; }
.paper-editor {
  width: 100%;
  min-height: 60vh;
  padding: 30px 38px;
  border: 1px solid var(--v3-hairline-gold);
  border-radius: var(--v3-r-lg);
  background: var(--v3-surface-solid);
  color: var(--v3-text);
  font-family: var(--v3-font-serif);
  font-size: 15px;
  line-height: 2.1;
  resize: vertical;
  outline: none;
}
.editor-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 12px; }

.versions, .memory { flex: 1; min-height: 0; padding: 6px 24px 32px; }
.pad { padding: 24px 4px; font-size: 12.5px; }
.version-list { display: flex; flex-direction: column; gap: 6px; }
.version-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  font-size: 12px;
}
.version-row.current { border-color: var(--v3-hairline-gold); background: var(--v3-glow-gold); }
.v-no { width: 42px; font-family: var(--v3-font-mono); font-size: 11.5px; color: var(--v3-gold); }
.v-remark { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--v3-text-soft); }
.v-time { font-size: 10.5px; color: var(--v3-text-faint); }
.v-actions { display: flex; gap: 2px; }
.v-actions button {
  padding: 4px 8px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--v3-text-muted);
  font-size: 11px;
  cursor: pointer;
}
.v-actions button:hover { background: var(--v3-surface-2); color: var(--v3-text); }
.v-actions button.picked { background: var(--v3-glow-gold); color: var(--v3-gold-bright); }
.compare-bar { display: flex; align-items: center; justify-content: space-between; margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--v3-hairline); font-size: 11.5px; }
.preview-body { max-height: 60vh; padding: 18px 20px; border-radius: var(--v3-r-md); background: var(--v3-surface-1); font-family: var(--v3-font-serif); font-size: 14px; line-height: 2; white-space: pre-wrap; color: var(--v3-text-soft); }
.diff-body { max-height: 60vh; border-radius: var(--v3-r-md); background: var(--v3-surface-1); padding: 10px 0; font-family: var(--v3-font-mono); font-size: 12px; line-height: 1.9; }
.diff-line { padding: 1px 14px; white-space: pre-wrap; }
.diff-line.is-add { background: rgba(127, 168, 161, 0.14); color: #a8d8cf; }
.diff-line.is-del { background: rgba(201, 76, 59, 0.12); color: #e8a59b; }
.diff-line.is-same { color: var(--v3-text-muted); }

.memory-hint { font-size: 12px; line-height: 1.9; color: var(--v3-text-muted); max-width: 720px; }
.memory-editor {
  width: 100%;
  max-width: 820px;
  margin-top: 10px;
  padding: 18px 20px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-md);
  background: var(--v3-surface-1);
  color: var(--v3-text);
  font-family: inherit;
  font-size: 13px;
  line-height: 1.95;
  resize: vertical;
  outline: none;
}
.memory-editor:focus { border-color: var(--v3-hairline-gold); }
.memory-actions { display: flex; align-items: center; gap: 12px; margin-top: 12px; font-size: 11.5px; }
.modal-hint { margin: 0 0 14px; font-size: 12.5px; line-height: 1.9; color: var(--v3-text-muted); }
.bp-search {
  width: 100%;
  height: 38px;
  margin-bottom: 12px;
  padding: 0 12px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  color: var(--v3-text);
  font-size: 12.5px;
  outline: none;
}
.bp-search:focus { border-color: var(--v3-gold); }
.bp-list { display: flex; flex-direction: column; gap: 6px; max-height: 46vh; }
.bp-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 11px 13px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  color: var(--v3-text-soft);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--v3-dur-2), background var(--v3-dur-2);
}
.bp-item:hover:not(:disabled) { border-color: var(--v3-hairline-gold); background: var(--v3-surface-2); }
.bp-item.active { border-color: var(--v3-hairline-gold); background: var(--v3-glow-gold); }
.bp-type {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: 5px;
  font-size: 10px;
  color: var(--v3-text-faint);
}
.bp-name { font-family: var(--v3-font-serif); font-size: 13.5px; color: var(--v3-text); }
.bp-summary { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; color: var(--v3-text-faint); }
.bp-empty { padding: 18px 4px; font-size: 12px; }
.req-editor {
  width: 100%;
  padding: 14px 16px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  color: var(--v3-text);
  font-family: inherit;
  font-size: 13px;
  line-height: 1.95;
  resize: vertical;
  outline: none;
}
.menu-enter-active, .menu-leave-active { transition: opacity var(--v3-dur-1), transform var(--v3-dur-2) var(--v3-ease-spring); }
.menu-enter-from, .menu-leave-to { opacity: 0; transform: translateY(-4px); }

@media (max-width: 1100px) {
  .chat-pane { width: 46%; min-width: 280px; }
  .paper-text { padding: 26px 28px; }
}
@media (max-width: 900px) {
  .writer-body { flex-direction: column; }
  .chat-pane { width: 100%; max-width: none; border-right: 0; border-bottom: 1px solid var(--v3-hairline); }
  .chat-splitter { display: none; }
  .paper-pane { min-height: 50vh; }
}
@media (max-width: 760px) {
  /* 窄屏把头部拆成三行：章节 / 状态 / 操作，避免挤成竖排文字 */
  .stage-head { flex-wrap: wrap; gap: 9px; padding: 12px 14px 10px; }
  .chapter-picker { width: 100%; }
  .chapter-current { width: 100%; justify-content: space-between; }
  .chapter-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .stage-meta { order: 3; flex-wrap: wrap; }
  .stage-actions { order: 4; margin-left: 0; width: 100%; overflow-x: auto; padding-bottom: 2px; }
  .head-btn { flex-shrink: 0; }
  .chat-pane { min-height: 46vh; }
  .paper { padding: 8px 12px 30px; }
  .paper-text { padding: 22px 20px; font-size: 14.5px; line-height: 2.05; }
  .writer-chat { padding: 14px; }
  .composer { padding: 10px 14px 14px; }
}
</style>
