<template>
  <div class="wr">
    <!-- ==================== 左：交互区 ==================== -->
    <section class="wr-chat">
      <header class="wr-chat-head">
        <span class="mj-title mj-title-sm">{{ chapter?.title || '未选择章节' }}</span>
        <span v-if="full" class="mj-tag">{{ text.length }} 字</span>
        <span v-if="full?.currentVersionNo" class="mj-tag">版本 {{ full.currentVersionNo }}</span>
        <span v-if="statusText" class="mj-tag mj-tag-brown">{{ statusText }}</span>
        <button
          class="mj-btn mj-btn-text wr-req-toggle"
          :class="{ 'is-on': showRequirement }"
          @click="showRequirement = !showRequirement"
        >
          写作要求
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

        <div v-for="(m, i) in messages" :key="i" class="wr-msg mj-rise" :class="'is-' + m.role">
          <div class="wr-msg-tag">{{ m.role === 'user' ? '你' : '写作 Agent' }}</div>
          <div class="wr-bubble">{{ m.text }}</div>
        </div>

        <div v-if="running" class="wr-msg is-assistant mj-fade">
          <div class="wr-msg-tag">写作 Agent</div>
          <div class="wr-bubble wr-thinking">
            <span class="mj-dots"><i></i><i></i><i></i></span>
            <span class="mj-muted">{{ phase }}</span>
          </div>
        </div>

        <div v-if="error" class="wr-error mj-fade">{{ error }}</div>
      </div>

      <!-- 引用条 + 输入 -->
      <footer class="wr-input-wrap">
        <div v-if="citations.length" class="wr-cites mj-rise">
          <div v-for="(c, i) in citations" :key="i" class="wr-cite">
            <span class="wr-cite-label">{{ c.kind || '正文' }}</span>
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
            <button
              class="mj-btn mj-btn-primary"
              :disabled="!chapter || running || !canRun"
              @click="start"
            >
              {{ running ? '写作中…' : primaryLabel }}
            </button>
          </div>
        </div>
      </footer>
    </section>

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
  getWriterConversation,
  setWritingRequirement,
  startWritingTask,
  subscribeWritingTask
} from '../../api/agent'
import { buildCitedMessage, citationPreview } from '../../utils/citation'
import VersionPanel from '../VersionPanel.vue'
import MemoryPanel from '../MemoryPanel.vue'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  chapter: { type: Object, default: null },
  blueprint: { type: Object, default: null }
})

const full = ref(null)
const text = ref('')
const requirement = ref('')
const advice = ref('')
const showRequirement = ref(false)
const requirementSaved = ref(false)

const messages = ref([])
const citations = ref([])
const menu = ref(null)

const running = ref(false)
const statusText = ref('')
const phase = ref('正在准备材料…')
const progress = ref([])
const error = ref('')
const bodyEl = ref(null)
const streamEl = ref(null)
const tab = ref('text')

const tabs = [
  { key: 'text', label: '正文' },
  { key: 'versions', label: '版本' },
  { key: 'memory', label: '章节记忆' }
]

let source = null
let pendingReplace = false

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
    text: selected
  }
}

function closeMenu() {
  menu.value = null
}

function cite() {
  if (!menu.value) return
  const citation = {
    kind: '本章正文',
    targetName: full.value?.title || '当前章节',
    text: menu.value.text
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

onMounted(() => {
  document.addEventListener('click', closeMenu)
  document.addEventListener('scroll', closeMenu, true)
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', closeMenu)
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

  try {
    const { taskId } = await startWritingTask(props.chapter.id, outgoing)
    source = subscribeWritingTask(taskId, {
      onStatus: (s) => {
        statusText.value =
          s === 'RUNNING' ? '写作中' : s === 'SUCCEEDED' ? '已完成' : s === 'FAILED' ? '失败' : s
      },
      onProgress: (p) => {
        progress.value = [...progress.value.slice(-3), p]
      },
      onDelta: (delta) => {
        if (pendingReplace) {
          text.value = ''
          pendingReplace = false
        }
        // SSE 会把同一事件里的多个 data 行用换行拼好，这里直接追加
        text.value += delta
        scrollBodyToBottom()
      },
      onError: (message) => {
        error.value = message
      },
      onEnd: async () => {
        running.value = false
        source = null
        clearTimeout(timer)
        await reload()
        // 从库里重新读一遍对话：这样 Agent 这轮的说明会进来，
        // 而且格式与刷新页面后看到的完全一致
        await loadConversation()
      }
    })
  } catch (e) {
    error.value = e.message
    running.value = false
    clearTimeout(timer)
  }
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
  flex: 0 0 42%;
  min-width: 320px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--color-line);
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
  font-size: 13.5px;
  line-height: 1.85;
  white-space: pre-wrap;
  word-break: break-word;
}

.wr-msg.is-user .wr-bubble {
  background: var(--color-paper-warm);
  border-left: 2px solid var(--color-brown);
}

.wr-thinking { display: flex; align-items: center; gap: 10px; }

.wr-error {
  padding: 10px 14px;
  border: 1px solid var(--color-danger);
  border-radius: var(--radius-s);
  color: var(--color-danger);
  font-size: 13px;
}

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
</style>
