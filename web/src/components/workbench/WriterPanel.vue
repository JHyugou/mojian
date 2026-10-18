<template>
  <div class="wr">
    <!-- ==================== 左：交互区 ==================== -->
    <section class="wr-chat" :style="{ width: chatWidth + 'px' }">
      <header class="wr-chat-head">
        <!-- 章节选择。原来在左栏的章节目录里，左栏与右栏内容重复，已移除 -->
        <div class="wr-chapter">
          <button class="wr-chapter-current" @click="chapterMenuOpen = !chapterMenuOpen">
            <span class="mj-title mj-title-sm">{{ chapter?.title || '选择章节' }}</span>
            <span class="wr-chapter-caret">▾</span>
          </button>
          <div v-if="chapterMenuOpen" class="wr-chapter-menu mj-fade">
            <button
              v-for="(c, i) in chapters"
              :key="c.id"
              class="wr-chapter-item"
              :class="{ active: c.id === chapter?.id }"
              @click="pick(c)"
            >
              <span class="wr-chapter-no">{{ i + 1 }}</span>
              <span class="wr-chapter-name">{{ c.title }}</span>
            </button>
            <div v-if="!chapters.length" class="wr-chapter-empty mj-muted">
              这本书还没有章节
            </div>
            <div class="wr-chapter-new">
              <input
                v-model="newChapterTitle"
                class="mj-input"
                placeholder="新章节标题，回车创建"
                @keydown.enter="createChapter"
                @keydown.esc="chapterMenuOpen = false"
              />
            </div>
          </div>
        </div>

        <span v-if="full" class="mj-tag">{{ text.length }} 字</span>
        <span v-if="full?.currentVersionNo" class="mj-tag">版本 {{ full.currentVersionNo }}</span>
        <span v-if="running" class="mj-tag mj-tag-brown">{{ statusText }}</span>
        <button
          class="mj-btn mj-btn-text wr-req-toggle"
          :class="{ 'is-on': showRequirement }"
          @click="showRequirement = !showRequirement"
        >
          写作要求
        </button>
        <!-- 长期记忆是优点，但得给作者一个重置的出口 -->
        <button
          v-if="messages.length"
          class="mj-btn mj-btn-text wr-clear-btn"
          title="清空它记住的对话（不影响正文与蓝图）"
          @click="clearMemory"
        >
          清空记忆
        </button>
      </header>

      <!-- 写作要求：和蓝图一起作为必备材料，永不裁剪 -->
      <section v-if="showRequirement" class="wr-req mj-rise">
        <div class="mj-muted wr-req-hint">
          「怎么写」——人称、视角、篇幅、禁忌、必须出现的元素。
          它会作为<b>必备材料</b>和蓝图一起交给写作 Agent，不会被裁剪。
        </div>
        <textarea
          v-model="requirement"
          class="mj-textarea"
          rows="4"
          placeholder="例：第三人称，视角锁定在主角身上；篇幅 4000~5000 字；短句、动作与器物细节；不要写『仿佛』『宛如』这类套话；必须出现……"
        ></textarea>
        <div class="wr-req-actions">
          <button class="mj-btn mj-btn-ghost" @click="saveRequirement">保存要求</button>
          <span v-if="requirementSaved" class="mj-muted mj-fade">已保存</span>
        </div>
      </section>

      <!-- 非写作类的小错（读取章节、保存要求等）。写作失败走对话流，不在这里 -->
      <div v-if="error" class="wr-mini-error mj-fade">
        {{ error }}
        <button class="wr-mini-error-x" @click="error = ''">×</button>
      </div>

      <!-- 对话流。这是副 Agent 与作者长期连续的对话，跨章不重来 -->
      <div ref="streamEl" class="wr-stream mj-scroll">
        <div v-if="!messages.length && !running" class="wr-empty mj-rise">
          <div class="mj-title mj-title-sm">和写作 Agent 聊</div>
          <p class="mj-muted">右边是这一章的正文。你可以直接说要写什么、要怎么改。</p>
          <p class="mj-muted">
            也可以<b>框选右边的正文</b>，右键引用到对话里 —— 这样它就确切知道你说的是哪一段。
          </p>
          <p class="mj-muted">
            这段对话<b>跨章连续</b>：你说过的偏好（比如"少写心理描写"），后面每一章它都会记得。
          </p>
        </div>

        <div v-for="(m, i) in messages" :key="i" class="wr-msg mj-rise" :class="['is-' + m.role, { 'is-failed': m.failed }]">
          <div class="wr-msg-tag">{{ m.role === 'user' ? '你' : '写作 Agent' }}</div>
          <div class="wr-bubble">
            <MarkdownText :text="m.text" />
          </div>
        </div>

        <!-- 正在说话：回答逐字出现在对话流里，和右边的正文是两条通道 -->
        <div v-if="running && streamingAnswer" class="wr-msg is-assistant mj-fade">
          <div class="wr-msg-tag">写作 Agent</div>
          <div class="wr-bubble mj-caret">
            <MarkdownText :text="streamingAnswer" />
          </div>
        </div>

        <!-- 还没开口：显示它在干什么 -->
        <div v-else-if="running" class="wr-msg is-assistant mj-fade">
          <div class="wr-msg-tag">写作 Agent</div>
          <div class="wr-bubble wr-thinking">
            <span class="mj-dots"><i></i><i></i><i></i></span>
            <span class="mj-muted">{{ phase }}</span>
            <span v-for="(p, i) in progress" :key="i" class="mj-tag">{{ p }}</span>
          </div>
        </div>
      </div>

      <!-- 引用条 + 输入 -->
      <footer class="wr-input-wrap">
        <div v-if="citations.length" class="wr-cites mj-rise">
          <div v-for="(c, i) in citations" :key="i" class="wr-cite">
            <span class="wr-cite-label">
              {{ c.targetName }}<span v-if="c.locator" class="wr-cite-locator">{{ c.locator }}</span>
            </span>
            <span class="wr-cite-text">{{ citationPreview(c.text) }}</span>
            <button class="wr-cite-x" title="移除" @click="citations.splice(i, 1)">×</button>
          </div>
          <button class="mj-btn mj-btn-text wr-cite-clear" @click="citations = []">全部清除</button>
        </div>

        <div class="wr-input">
          <textarea
            v-model="advice"
            class="mj-textarea"
            rows="2"
            :placeholder="advicePlaceholder"
            @keydown.enter.exact.prevent="start"
          ></textarea>
          <div class="wr-input-side">
            <!-- 跑着的时候这个按钮就是"停下" —— 它比"开始写"更有用 -->
            <button v-if="running" class="mj-btn mj-btn-ghost" @click="stop">
              <Icon name="stop" :size="12" />
              停下
            </button>
            <button
              v-else
              class="mj-btn mj-btn-primary"
              :disabled="!chapter || !canRun"
              @click="start"
            >
              {{ primaryLabel }}
            </button>
          </div>
        </div>

        <!-- 模型选择：写作 Agent 可以单独用另一个模型（策划求稳、写作求发散，往往不是一颗脑子） -->
        <div class="wr-mode">
          <ModelPicker role="WRITER" />
        </div>
      </footer>
    </section>

    <!-- 交互区与内容区之间：拖拽调整。想仔细读正文就往左拖，想多聊几句就往右拖 -->
    <Resizer v-model="chatWidth" :min="320" :max="880" />

    <!-- ==================== 右：章节内容 ==================== -->
    <section class="wr-content">
      <nav class="wr-tabs">
        <button
          v-for="t in tabs"
          :key="t.key"
          class="wr-tab"
          :class="{ active: tab === t.key }"
          @click="tab = t.key"
        >
          {{ t.label }}
        </button>
        <span class="wr-tabs-hint mj-muted">右键引用正文</span>
      </nav>

      <div ref="bodyEl" class="wr-body mj-scroll" @contextmenu="onContextMenu">
        <template v-if="tab === 'text'">
          <div v-if="!text && !running" class="wr-empty mj-muted">
            还没有正文。在左边说一句，Agent 会按本章蓝图与写作要求来写。
          </div>
          <!-- 逐字流出来的正文。光标只在"还在写"时显示 -->
          <div v-else class="wr-text" :class="{ 'mj-caret': running }">{{ text }}</div>
        </template>

        <VersionPanel
          v-else-if="tab === 'versions' && chapter"
          :key="'v-' + chapter.id"
          :chapter-id="String(chapter.id)"
          @refreshed="reload"
        />

        <MemoryPanel
          v-else-if="tab === 'memory' && chapter"
          :key="'m-' + chapter.id"
          :chapter-id="String(chapter.id)"
        />
      </div>
    </section>

    <!-- 右键菜单。Teleport 到 body：内容区有 overflow 滚动，留在里面会被裁掉 -->
    <Teleport to="body">
      <div
        v-if="menu"
        class="wr-menu mj-fade"
        :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
        @click.stop
      >
        <div class="wr-menu-preview">「{{ citationPreview(menu.text, 26) }}」</div>
        <button class="wr-menu-item" @click="cite">添加到对话</button>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getChapter } from '../../api/chapter'
import {
  cancelWritingTask,
  clearWriterHistory,
  getWriterConversation,
  setWritingRequirement,
  startWritingTask,
  subscribeWritingTask
} from '../../api/agent'
import { buildCitedMessage, citationPreview, segmentIndexOf } from '../../utils/citation'
import ModelPicker from './ModelPicker.vue'
import Resizer from './Resizer.vue'
import Icon from '../Icon.vue'
import MarkdownText from '../MarkdownText.vue'
import VersionPanel from '../VersionPanel.vue'
import MemoryPanel from '../MemoryPanel.vue'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  chapter: { type: Object, default: null },
  /** 章节目录：章节选择移到标题栏的下拉里了（左栏与右栏重复，已移除） */
  chapters: { type: Array, default: () => [] },
  blueprint: { type: Object, default: null }
})

const emit = defineEmits(['select-chapter', 'create-chapter'])

const full = ref(null)
const text = ref('')
const requirement = ref('')
const advice = ref('')
const showRequirement = ref(false)
const requirementSaved = ref(false)

const messages = ref([])
const citations = ref([])
const menu = ref(null)
/** 正在流式输出的回答（它说的话）。结束后会被落库的完整版本替换 */
const streamingAnswer = ref('')

const running = ref(false)
const statusText = ref('')
const phase = ref('正在准备材料…')
const progress = ref([])
const error = ref('')
const bodyEl = ref(null)
const streamEl = ref(null)
const tab = ref('text')
const chapterMenuOpen = ref(false)
const newChapterTitle = ref('')

/** 交互区宽度可拖拽，并记住 */
const chatWidth = ref(Number(localStorage.getItem('mojian.chatWidth')) || 460)
watch(chatWidth, (value) => localStorage.setItem('mojian.chatWidth', String(value)))

const tabs = [
  { key: 'text', label: '正文' },
  { key: 'versions', label: '版本' },
  { key: 'memory', label: '章节记忆' }
]

let source = null
let pendingReplace = false
/** 当前任务的 id，用来终止 */
const currentTaskId = ref(null)

const hasBlueprint = computed(() => Boolean(props.blueprint))
const canRun = computed(() => hasBlueprint.value || Boolean(advice.value.trim()) || citations.value.length > 0)

const primaryLabel = computed(() => {
  if (!advice.value.trim() && !citations.value.length && !text.value) return '开始写'
  return text.value ? '按这个改' : '开始写'
})

const advicePlaceholder = computed(() => {
  if (citations.value.length) return '说说引用的这段要怎么改（Enter 提交，Shift+Enter 换行）'
  if (text.value) return '对现有稿的意见，比如：把开头改得更陡一些（Enter 提交）'
  return '想交代什么就写在这里，留空则按蓝图与写作要求写（Enter 提交）'
})

// ==================== 数据加载 ====================

watch(
  () => props.chapter?.id,
  async (id) => {
    closeStream()
    running.value = false
    progress.value = []
    error.value = ''
    statusText.value = ''
    advice.value = ''
    citations.value = []
    tab.value = 'text'
    full.value = null
    text.value = ''
    requirement.value = ''
    if (!id) return
    try {
      await loadChapter(id)
    } catch (e) {
      error.value = e.message
    }
  },
  { immediate: true }
)

onMounted(loadConversation)

async function loadChapter(id) {
  // 章节列表刻意不返回正文大字段，所以这里单独取一次
  const detail = await getChapter(id)
  full.value = detail
  text.value = detail.content || ''
  requirement.value = detail.writingRequirement || ''
}

/** 版本回滚之后重新取一次正文 —— 否则界面上还停在回滚前的内容 */
async function reload() {
  if (!props.chapter?.id) return
  try {
    await loadChapter(props.chapter.id)
  } catch (e) {
    error.value = e.message
  }
}

/** 拉取长期写作会话的历史。刷新页面后对话还在，靠的就是这一步 */
async function loadConversation() {
  if (!props.projectId) return
  try {
    const data = await getWriterConversation(props.projectId)
    messages.value = (data.messages || []).map((m) => ({
      role: m.role,
      text: m.content
    }))
    scrollStreamToBottom()
  } catch (e) {
    // 会话读不到不该让整页打不开
    console.warn('读取写作会话失败', e)
  }
}

/**
 * 清空它记住的对话。
 *
 * 只清历史消息，不动正文、蓝图和写作要求 —— 作者想重置的通常是
 * "别再说我早先提过的那些要求了"，而不是想丢掉写好的稿子。
 */
async function clearMemory() {
  if (!props.projectId) return
  if (!window.confirm('清空写作 Agent 记住的对话？\n\n正文、蓝图、写作要求都不会受影响，只是让它忘掉此前聊过的内容。')) {
    return
  }
  try {
    await clearWriterHistory(props.projectId)
    messages.value = []
  } catch (e) {
    error.value = e.message
  }
}

// ==================== 章节切换 ====================

function pick(chapter) {
  chapterMenuOpen.value = false
  if (chapter.id !== props.chapter?.id) {
    emit('select-chapter', chapter)
  }
}

function createChapter() {
  const title = newChapterTitle.value.trim()
  if (!title) return
  newChapterTitle.value = ''
  chapterMenuOpen.value = false
  emit('create-chapter', title)
}

// ==================== 失败也要说话 ====================

/**
 * 把失败显示成对话里的一条回复。
 *
 * <p>之前失败只在面板下方飘一行红字，看起来就像"你说了话、它没理你" ——
 * 而这恰恰是对话式界面最不能有的感觉。失败也是一种回答，
 * 它至少该告诉你为什么没成。
 */
function pushFailure(message) {
  messages.value = [...messages.value, {
    role: 'assistant',
    text: '这次没能完成：' + (message || '未知原因') + '\n\n正文没有被改动，你可以直接再试一次。',
    failed: true
  }]
  scrollStreamToBottom()
}

// ==================== 滚动 ====================

function scrollStreamToBottom() {
  nextTick(() => {
    if (streamEl.value) streamEl.value.scrollTop = streamEl.value.scrollHeight
  })
}

function scrollBodyToBottom() {
  nextTick(() => {
    if (bodyEl.value) bodyEl.value.scrollTop = bodyEl.value.scrollHeight
  })
}

// ==================== 写作要求 ====================

async function saveRequirement() {
  if (!props.chapter?.id) return
  try {
    await setWritingRequirement(props.chapter.id, requirement.value)
    requirementSaved.value = true
    setTimeout(() => { requirementSaved.value = false }, 2000)
  } catch (e) {
    error.value = e.message
  }
}

// ==================== 框选引用 ====================

function onContextMenu(event) {
  // 只在正文页签、且确实选中了文字时才弹菜单。
  // 空引用没有意义，只会让人困惑。
  if (tab.value !== 'text') return
  const selection = window.getSelection()
  const selected = selection ? selection.toString().trim() : ''
  if (!selected) return
  event.preventDefault()
  menu.value = {
    x: Math.min(event.clientX, window.innerWidth - 220),
    y: Math.min(event.clientY, window.innerHeight - 100),
    text: selected,
    // 段号：让模型知道"这里"是正文的哪一段。
    // 只给选中的文字不够 —— 同一句话在整章里可能出现好几次
    segment: segmentIndexOf(bodyEl.value, selection)
  }
}

function closeMenu() {
  menu.value = null
}

function cite() {
  if (!menu.value) return
  const chapterNo = props.chapters.findIndex((c) => c.id === props.chapter?.id) + 1
  const citation = {
    kind: '本章正文',
    // 用章节序号而不是标题：标题会重名（作者数据里已经有两章都叫「坊市奇遇」），
    // 序号是唯一的。标题只作为补充信息附在后面
    targetName: chapterNo > 0 ? `第 ${chapterNo} 章` : '当前章节',
    text: menu.value.text,
    locator: [
      props.full?.title ? `标题「${props.full.title}」` : '',
      menu.value.segment ? `第 ${menu.value.segment} 段` : ''
    ].filter(Boolean).join(' · ')
  }
  // 同一条不要重复加，否则会挂一排一样的引用
  if (!citations.value.some((c) => c.text === citation.text)) {
    citations.value = [...citations.value, citation]
  }
  window.getSelection()?.removeAllRanges()
  closeMenu()
}

function onKeydown(event) {
  if (event.key === 'Escape') closeMenu()
}

/**
 * 点别处关掉章节下拉。
 *
 * 用 mousedown 而不是 click：click 要等鼠标松开，菜单会"多活"一小段时间，
 * 而且按下与松开之间鼠标移到别处时，click 可能落在不相干的元素上。
 */
function onDocumentPointerDown(event) {
  const target = event.target
  if (target instanceof Element && target.closest('.wr-chapter')) {
    return
  }
  chapterMenuOpen.value = false
}

onMounted(() => {
  document.addEventListener('click', closeMenu)
  document.addEventListener('mousedown', onDocumentPointerDown, true)
  document.addEventListener('scroll', closeMenu, true)
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', closeMenu)
  document.removeEventListener('mousedown', onDocumentPointerDown, true)
  document.removeEventListener('scroll', closeMenu, true)
  document.removeEventListener('keydown', onKeydown)
})

// ==================== 发起写作 ====================

function closeStream() {
  if (source) {
    source.close()
    source = null
  }
}

async function start() {
  if (!props.chapter?.id || running.value || !canRun.value) return

  // 引用与输入拼成一条消息 —— 与策划那边的格式完全一致
  const outgoing = buildCitedMessage(citations.value, advice.value)
    || (text.value
      ? '这是「' + (full.value?.title || '本章') + '」的现有稿，请通读一遍并在不改动剧情的前提下润色文字。'
      : '请按本章蓝图与写作要求写这一章的正文。')
  const revising = Boolean(text.value)

  messages.value = [...messages.value, { role: 'user', text: outgoing }]
  advice.value = ''
  citations.value = []
  running.value = true
  error.value = ''
  progress.value = []
  streamingAnswer.value = ''
  phase.value = '正在准备材料…'
  statusText.value = '写作中'
  scrollStreamToBottom()

  /*
   * 改稿时不要立刻清空现有稿 —— 万一模型调用失败，作者至少还看得见原稿。
   * 但流式 delta 输出的是完整新稿（保存是整章覆盖），所以不能直接追加，
   * 否则会看到"旧稿 + 新稿"两篇叠在一起。做法：等新稿第一个字到了再替换。
   */
  pendingReplace = revising
  if (!revising) {
    text.value = ''
  }

  const timer = setTimeout(() => { phase.value = '正在写正文…' }, 8000)
  let failed = false

  try {
    const { taskId } = await startWritingTask(props.chapter.id, outgoing)
    currentTaskId.value = taskId
    source = subscribeWritingTask(taskId, {
      onStatus: (s) => {
        statusText.value =
          s === 'RUNNING' ? '写作中'
            : s === 'SUCCEEDED' ? '已完成'
              : s === 'CANCELLED' ? '已停止'
                : s === 'FAILED' ? '失败' : s
      },
      onProgress: (p) => {
        progress.value = [...progress.value.slice(-3), p]
      },
      onContent: (delta) => {
        // 正文：逐字出现在右边。它装在工具参数里，走的是单独的 content 通道
        if (pendingReplace) {
          text.value = ''
          pendingReplace = false
        }
        text.value += delta
        scrollBodyToBottom()
      },
      onDelta: (delta) => {
        // 回答：逐字出现在左边对话流。和正文是两回事 ——
        // 混在一条通道里的话，它说的"改好了…"会被拼到正文后面去
        streamingAnswer.value += delta
        scrollStreamToBottom()
      },
      onError: (message) => {
        // 立刻显示，不等 onEnd —— 失败的当下就该看见原因
        failed = true
        pushFailure(message)
      },
      onEnd: async () => {
        running.value = false
        source = null
        currentTaskId.value = null
        clearTimeout(timer)
        await reload()
        // 后端也会把失败落进会话，所以这里统一重载：
        // 保证"当场看到的"和"刷新后看到的"完全一致
        await loadConversation()
        streamingAnswer.value = ''
      }
    })
  } catch (e) {
    // 任务都没起来（比如后端没响应）：同样要在对话里说一声
    running.value = false
    currentTaskId.value = null
    clearTimeout(timer)
    pushFailure(e.message)
  }
}

/**
 * 终止写作。
 *
 * <p>两件事都要做，缺一不可：
 *   1. 关掉前端的 EventSource —— 界面上立刻停住
 *   2. 调后端接口 —— <b>否则后端线程还在跑、模型还在生成、钱还在花</b>
 * 只做第一件，用户按了"停下"账单照样涨。
 */
async function stop() {
  const taskId = currentTaskId.value
  closeStream()
  running.value = false
  currentTaskId.value = null
  statusText.value = '已停止'
  phase.value = ''

  if (taskId) {
    try {
      await cancelWritingTask(taskId)
    } catch (e) {
      // 任务可能刚好自己跑完了，取消失败不算问题
      console.warn('终止任务失败（可能已结束）', e)
    }
  }

  // 在对话里留一条说明，否则界面看起来像"写了一半就没了"
  messages.value = [...messages.value, {
    role: 'assistant',
    text: '（已按你的要求停下。已经写出的部分保留在右边，可继续改。）',
    failed: false
  }]
  scrollStreamToBottom()
}
</script>

<style scoped>
.wr {
  display: flex;
  height: 100%;
  min-height: 0;
}

/* ==================== 左：交互区 ==================== */
.wr-chat {
  /* 宽度由 Resizer 拖拽控制（内联 style） */
  min-width: 0;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.wr-chat-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 12px 18px;
  border-bottom: 1px solid var(--color-line);
  background: var(--color-panel);
  flex-shrink: 0;
}

.wr-stream {
  flex: 1;
  min-height: 0;
  padding: 18px;
}

.wr-req-toggle { margin-left: auto; font-size: 12px; }
.wr-req-toggle.is-on { color: var(--color-brown-deep); }

/* ---------- 章节下拉 ---------- */
.wr-chapter { position: relative; }

.wr-chapter-current {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border: 1px solid transparent;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease-out), background var(--dur) var(--ease-out);
}
.wr-chapter-current:hover {
  border-color: var(--color-line);
  background: var(--color-paper-warm);
}

.wr-chapter-caret { font-size: 10px; color: var(--color-muted); }

.wr-chapter-menu {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  z-index: 60;
  width: 260px;
  max-height: 340px;
  overflow-y: auto;
  padding: 4px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-m);
  background: var(--color-panel);
  box-shadow: var(--shadow-float);
}

.wr-chapter-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
  color: var(--color-ink);
}
.wr-chapter-item:hover { background: var(--color-paper-warm); }
.wr-chapter-item.active { color: var(--color-brown-deep); background: var(--color-paper-warm); }

.wr-chapter-no { width: 18px; text-align: right; color: var(--color-muted); font-size: 12px; }
.wr-chapter-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.wr-chapter-empty { padding: 10px; font-size: 12px; }
.wr-chapter-new {
  margin-top: 4px;
  padding-top: 6px;
  border-top: 1px solid var(--color-line);
}

.wr-mini-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 0 18px;
  padding: 8px 10px;
  border: 1px solid var(--color-danger);
  border-radius: var(--radius-s);
  color: var(--color-danger);
  font-size: 12px;
}
.wr-mini-error-x {
  border: 0;
  background: none;
  cursor: pointer;
  color: inherit;
  font-size: 14px;
  line-height: 1;
}

.wr-clear-btn { font-size: 12px; }

.wr-req {
  padding: 12px 18px;
  border-bottom: 1px solid var(--color-line);
  background: var(--color-paper-warm);
  flex-shrink: 0;
}

.wr-req-hint { font-size: 12px; line-height: 1.8; margin-bottom: 8px; }
.wr-req-actions { display: flex; align-items: center; gap: 10px; margin-top: 8px; }

.wr-empty {
  text-align: center;
  line-height: 2;
  margin-top: 24px;
}
.wr-empty p { margin-top: 8px; font-size: 13px; }

.wr-msg { margin-bottom: 18px; }

.wr-msg-tag {
  font-size: 12px;
  letter-spacing: 0.08em;
  color: var(--color-muted);
  margin-bottom: 6px;
}

.wr-bubble {
  padding: 10px 14px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-m);
  background: var(--color-panel);
  line-height: 1.85;
  word-break: break-word;
}

.wr-msg.is-user .wr-bubble {
  background: var(--color-paper-warm);
  border-left: 2px solid var(--color-brown);
}

/* 失败也是一条回复。用危险色描边，但仍然是对话流里的一条，
   而不是飘在角落的一行提示 —— 对话界面里"没有回复"比"回复说失败了"更糟 */
.wr-msg.is-failed .wr-bubble {
  border-color: var(--color-danger);
  background: rgba(166, 43, 31, 0.04);
}

.wr-thinking { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }

/* ---------- 输入区 ---------- */
.wr-input-wrap {
  border-top: 1px solid var(--color-line);
  background: var(--color-panel);
  flex-shrink: 0;
}

.wr-cites {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 18px 0;
}

.wr-cite {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
  padding: 5px 8px 5px 10px;
  border: 1px solid var(--color-brown-soft);
  border-left: 2px solid var(--color-brown);
  border-radius: var(--radius-s);
  background: var(--color-paper-warm);
  font-size: 12px;
}

.wr-cite-label { color: var(--color-brown-deep); white-space: nowrap; }

/* 定位信息（段号/标题）用等宽小字：它是给模型用的精确坐标 */
.wr-cite-locator {
  margin-left: 4px;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  color: var(--color-muted);
}
.wr-cite-text {
  color: var(--color-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wr-cite-x {
  border: 0;
  background: none;
  cursor: pointer;
  color: var(--color-muted);
  font-size: 14px;
  line-height: 1;
  padding: 0 2px;
}
.wr-cite-x:hover { color: var(--color-danger); }
.wr-cite-clear { font-size: 12px; }

.wr-input {
  display: flex;
  gap: 10px;
  padding: 12px 18px 16px;
}

.wr-input-side { display: flex; align-items: flex-end; }

/* 写作 Agent 的模型选择条：与策划面板同样的位置，位置一致才好找 */
.wr-mode {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 2px 26px 14px;
  position: relative;
}

/* ==================== 右：章节内容 ==================== */
.wr-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.wr-tabs {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 0 18px;
  border-bottom: 1px solid var(--color-line);
  background: var(--color-panel);
  flex-shrink: 0;
}

.wr-tab {
  padding: 9px 14px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: none;
  cursor: pointer;
  font-size: 13px;
  color: var(--color-muted);
  transition: color var(--dur) var(--ease-out), border-color var(--dur) var(--ease-out);
}
.wr-tab:hover { color: var(--color-brown-deep); }
.wr-tab.active {
  color: var(--color-brown-deep);
  border-bottom-color: var(--color-brown);
}

.wr-tabs-hint { margin-left: auto; font-size: 12px; }

.wr-body {
  flex: 1;
  min-height: 0;
  padding: 26px 32px;
  user-select: text;
}

/*
 * 正文阅读区：限宽 + 大行高 + 宋体。
 * 这一块是"读小说"的地方，和界面其它部分的排版要求完全不同 ——
 * 界面求紧凑，阅读求舒缓。
 */
.wr-text {
  max-width: 700px;
  margin: 0 auto;
  font-family: var(--font-serif);
  font-size: 16px;
  line-height: 2.05;
  letter-spacing: 0.02em;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-ink);
  cursor: text;
}

.wr-text::selection { background: rgba(139, 94, 60, 0.22); }
</style>

<style>
/* 右键菜单不能加 scoped：它被 Teleport 到 body，scoped 属性不在那一层 */
.wr-menu {
  position: fixed;
  z-index: 3000;
  min-width: 168px;
  padding: 4px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-m);
  background: var(--color-panel);
  box-shadow: var(--shadow-float);
}

.wr-menu-preview {
  padding: 6px 8px 8px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--color-line);
  font-size: 12px;
  color: var(--color-muted);
  line-height: 1.6;
  max-width: 220px;
}

.wr-menu-item {
  display: block;
  width: 100%;
  padding: 7px 10px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  text-align: left;
  cursor: pointer;
  font-size: 13px;
  color: var(--color-brown-deep);
}
.wr-menu-item:hover { background: var(--color-paper-warm); }

/* ==================================================================== */
/* 视觉打磨（2026-10 重做：圆润、柔和、有层次）                          */
/* ==================================================================== */

/* ---------- 聊天流 ---------- */

.wr-stream { padding: 22px 24px 8px; }

.wr-msg { margin-bottom: 22px; }

.wr-msg-tag {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 8px;
  font-size: 11.5px;
  letter-spacing: 0.1em;
  color: var(--color-muted);
}
.wr-msg-tag::before {
  content: "";
  width: 14px;
  height: 1px;
  background: var(--color-brown-soft);
}

.wr-bubble {
  padding: 14px 18px;
  border: 1px solid var(--color-line-soft);
  border-radius: var(--radius-xs) var(--radius-m) var(--radius-m) var(--radius-m);
  background: var(--color-panel);
  box-shadow: var(--shadow-xs);
  line-height: 1.9;
  word-break: break-word;
}

.wr-msg.is-user .wr-bubble {
  background: var(--color-brown-tint);
  border-color: transparent;
  border-radius: var(--radius-m) var(--radius-m) var(--radius-m) var(--radius-xs);
  box-shadow: none;
}

/* ---------- 章节选择 ---------- */

.wr-chapter-current {
  border-radius: var(--radius-pill);
  border-color: var(--color-line-soft);
  background: var(--color-paper-warm);
  transition: border-color var(--dur) var(--ease-out),
              background var(--dur) var(--ease-out),
              box-shadow var(--dur) var(--ease-out);
}
.wr-chapter-current:hover {
  border-color: var(--color-brown-soft);
  background: var(--color-brown-tint);
  box-shadow: var(--shadow-xs);
}

.wr-chapter-menu {
  border-radius: var(--radius-m);
  border-color: var(--color-line-soft);
  box-shadow: var(--shadow-float);
  padding: 6px;
  animation: mj-pop var(--dur) var(--ease-spring) both;
}

.wr-chapter-item {
  border-radius: var(--radius-xs);
  transition: background var(--dur) var(--ease-out);
}
.wr-chapter-item:hover { background: var(--color-brown-tint); }

.wr-chapter-no {
  font-family: var(--font-serif);
  color: var(--color-brown);
}

/* ---------- 输入区 ---------- */

.wr-input-wrap {
  border-top: 0;
  background: linear-gradient(180deg, rgba(253, 251, 247, 0), var(--color-paper) 26px);
  box-shadow: 0 -10px 24px -18px rgba(70, 52, 36, 0.3);
}

.wr-input { padding: 14px 24px 8px; }

.wr-input .mj-textarea {
  padding: 13px 17px;
  border-radius: var(--radius-l);
  border-color: var(--color-line-soft);
  background: var(--color-panel);
  box-shadow: var(--shadow-xs);
  line-height: 1.8;
  transition: border-color var(--dur) var(--ease-out),
              box-shadow var(--dur) var(--ease-out);
}
.wr-input .mj-textarea:hover { border-color: var(--color-line); }
.wr-input .mj-textarea:focus {
  border-color: var(--color-brown);
  box-shadow: 0 0 0 3px var(--color-brown-tint-strong), var(--shadow-soft);
}

.wr-input-side .mj-btn-primary {
  padding: 10px 22px;
  border-radius: var(--radius-pill);
  font-size: 13.5px;
}

.wr-cites { padding: 0 24px 8px; }
.wr-cite {
  border-radius: var(--radius-s);
  border-color: var(--color-brown-soft);
  background: var(--color-brown-tint);
}

/* ---------- 右侧正文区 ---------- */

.wr-content {
  background: var(--color-paper-warm);
  box-shadow: inset 8px 0 16px -12px rgba(70, 52, 36, 0.14);
}

/* 页签做成药丸 */
.wr-tabs {
  gap: 4px;
  padding: 12px 20px 8px;
  border-bottom: 0;
}

.wr-tab {
  padding: 6px 15px;
  border: 0;
  border-radius: var(--radius-pill);
  background: none;
  font-size: 12.5px;
  color: var(--color-muted);
  transition: background var(--dur) var(--ease-out),
              color var(--dur) var(--ease-out),
              box-shadow var(--dur) var(--ease-out);
}
.wr-tab:hover { color: var(--color-brown-deep); }
.wr-tab.active {
  color: var(--color-brown-deep);
  background: var(--color-panel);
  box-shadow: var(--shadow-xs);
}

/* 正文：给一块"纸"的感觉 —— 圆角、留白、极淡阴影 */
.wr-text {
  padding: 26px 32px;
  border-radius: var(--radius-l);
  background: var(--color-panel);
  box-shadow: var(--shadow-xs);
  border: 1px solid var(--color-line-soft);
}

/* ---------- 空态 ---------- */

.wr-empty { line-height: 2.1; }

/* ---------- 写作要求折叠区 ---------- */

.wr-req {
  border-radius: var(--radius-m);
  border-color: var(--color-line-soft);
  background: var(--color-paper-warm);
  box-shadow: var(--shadow-xs);
}


/* ==================================================================== */
/* 输入区：从"贴边的直角色带"改成"浮起的圆角卡片"                        */
/* ==================================================================== */

.wr-input-wrap {
  margin: 0 16px 14px;
  padding-bottom: 2px;
  border: 1px solid var(--color-line-soft);
  border-radius: var(--radius-l);
  background: var(--color-panel);
  box-shadow: var(--shadow-mid);
  transition: box-shadow var(--dur) var(--ease-out);
}

.wr-input-wrap:focus-within {
  box-shadow: var(--shadow-float);
}

.wr-input { padding: 14px 16px 8px; }

.wr-input .mj-textarea {
  border-color: transparent;
  background: transparent;
  box-shadow: none;
  padding: 4px 2px;
}
.wr-input .mj-textarea:hover { border-color: transparent; }
.wr-input .mj-textarea:focus {
  border-color: transparent;
  box-shadow: none;
}

.wr-cites { padding: 0 16px 10px; }

.wr-mode { padding: 0 16px 12px; }

</style>
