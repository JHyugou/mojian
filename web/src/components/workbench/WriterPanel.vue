<template>
  <div class="wr">
    <!-- ========== 顶部 ========== -->
    <header class="wr-head">
      <div class="wr-head-left">
        <span class="mj-title mj-title-md">{{ full?.title || '未选择章节' }}</span>
        <span v-if="full" class="mj-tag">{{ text.length }} 字</span>
        <span v-if="full?.currentVersionNo" class="mj-tag">版本 {{ full.currentVersionNo }}</span>
        <span v-if="statusText" class="mj-tag mj-tag-brown">{{ statusText }}</span>
      </div>
      <div class="wr-head-right">
        <button class="mj-btn mj-btn-text" @click="showRequirement = !showRequirement">
          写作要求
        </button>
        <button
          class="mj-btn mj-btn-primary"
          :disabled="!chapter || running"
          @click="start"
        >
          {{ running ? '写作中…' : (text ? '重写这一章' : '开始写') }}
        </button>
      </div>
    </header>

    <!-- ========== 写作要求 ========== -->
    <section v-if="showRequirement" class="wr-req mj-rise">
      <div class="mj-muted wr-req-hint">
        这一块是「怎么写」——人称、视角、篇幅、禁忌、必须出现的元素。
        它会作为<b>必备材料</b>和蓝图一起交给写作 Agent，不会被裁剪。
      </div>
      <textarea
        v-model="requirement"
        class="mj-textarea"
        rows="5"
        placeholder="例：第三人称，视角锁定在主角身上；篇幅 4000~5000 字；短句、动作与器物细节；不要写『仿佛』『宛如』这类套话；必须出现……"
      ></textarea>
      <div class="wr-req-actions">
        <button class="mj-btn mj-btn-ghost" @click="saveRequirement">保存要求</button>
        <span v-if="requirementSaved" class="mj-muted mj-fade">已保存</span>
      </div>
    </section>

    <!-- ========== 进度 ========== -->
    <section v-if="progress.length || error" class="wr-progress">
      <span v-for="(p, i) in progress" :key="i" class="mj-tag mj-fade">{{ p }}</span>
      <span v-if="running" class="mj-dots"><i></i><i></i><i></i></span>
      <span v-if="error" class="wr-error">{{ error }}</span>
    </section>

    <!-- ========== 正文 ========== -->
    <div ref="bodyEl" class="wr-body mj-scroll">
      <div v-if="!text && !running" class="wr-empty">
        <div class="mj-title mj-title-sm">还没有正文</div>
        <p class="mj-muted">
          点右上角「开始写」，Agent 会按本章蓝图与写作要求来写。
        </p>
        <p class="mj-muted">写好的正文会直接存进这一章，并自动留一个可回滚的版本。</p>
      </div>

      <!-- 逐字流出来的正文。光标只在"还在写"时显示 -->
      <div v-else class="wr-text" :class="{ 'mj-caret': running }">{{ text }}</div>
    </div>
  </div>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
import { getChapter } from '../../api/chapter'
import { setWritingRequirement, startWritingTask, subscribeWritingTask } from '../../api/agent'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  chapter: { type: Object, default: null }
})

const full = ref(null)
const text = ref('')
const requirement = ref('')
const showRequirement = ref(false)
const requirementSaved = ref(false)

const running = ref(false)
const statusText = ref('')
const progress = ref([])
const error = ref('')
const bodyEl = ref(null)

let source = null

const TOOL_LABELS = {
  query_character: '查了人物卡',
  query_chapter: '读了章节原文',
  draft_character: '起草了人物卡',
  save_chapter_draft: '正在保存正文'
}

watch(
  () => props.chapter?.id,
  async (id) => {
    closeStream()
    running.value = false
    progress.value = []
    error.value = ''
    statusText.value = ''
    full.value = null
    text.value = ''
    requirement.value = ''
    if (!id) return
    try {
      // 章节列表刻意不返回正文大字段，所以这里单独取一次
      const detail = await getChapter(id)
      full.value = detail
      text.value = detail.content || ''
      requirement.value = detail.writingRequirement || ''
    } catch (e) {
      error.value = e.message
    }
  },
  { immediate: true }
)

function scrollToBottom() {
  nextTick(() => {
    if (bodyEl.value) bodyEl.value.scrollTop = bodyEl.value.scrollHeight
  })
}

function closeStream() {
  if (source) {
    source.close()
    source = null
  }
}

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

async function start() {
  if (!props.chapter?.id || running.value) return
  running.value = true
  error.value = ''
  progress.value = []
  text.value = '' // 重写时清空，逐字重新出现
  statusText.value = '正在准备材料…'

  try {
    const { taskId } = await startWritingTask(props.chapter.id, null)
    source = subscribeWritingTask(taskId, {
      onStatus: (s) => {
        statusText.value =
          s === 'RUNNING' ? '写作中' : s === 'SUCCEEDED' ? '已完成' : s === 'FAILED' ? '失败' : s
      },
      onProgress: (p) => {
        // 只留最近四条，免得进度条越堆越长把页面顶走
        progress.value = [...progress.value.slice(-3), p]
      },
      onToolCall: (name) => {
        progress.value = [...progress.value.slice(-3), TOOL_LABELS[name] || name]
      },
      onDelta: (delta) => {
        // SSE 会把同一事件里的多个 data 行用换行拼好，这里直接追加
        text.value += delta
        scrollToBottom()
      },
      onResult: (summary) => {
        progress.value = [...progress.value.slice(-3), summary]
      },
      onError: (message) => {
        error.value = message
      },
      onEnd: async () => {
        running.value = false
        source = null
        // 流结束后重新取一次章节：拿版本号与最终字数（以库里的为准）
        try {
          full.value = await getChapter(props.chapter.id)
          if (full.value?.content) {
            text.value = full.value.content
          }
        } catch (e) {
          console.warn('刷新章节失败', e)
        }
      }
    })
  } catch (e) {
    error.value = e.message
    running.value = false
  }
}
</script>

<style scoped>
.wr {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

/* ---------- 顶部 ---------- */
.wr-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 26px;
  border-bottom: 1px solid var(--color-line);
  background: var(--color-panel);
}

.wr-head-left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.wr-head-right { display: flex; align-items: center; gap: 8px; }

/* ---------- 写作要求 ---------- */
.wr-req {
  padding: 14px 26px;
  border-bottom: 1px solid var(--color-line);
  background: var(--color-paper-warm);
}

.wr-req-hint { font-size: 12px; line-height: 1.8; margin-bottom: 8px; }
.wr-req-actions { display: flex; align-items: center; gap: 10px; margin-top: 10px; }

/* ---------- 进度 ---------- */
.wr-progress {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 26px;
  border-bottom: 1px solid var(--color-line);
  min-height: 44px;
}

.wr-error { color: var(--color-danger); font-size: 13px; }

/* ---------- 正文 ---------- */
.wr-body {
  flex: 1;
  min-height: 0;
  padding: 26px;
}

.wr-empty {
  max-width: 520px;
  margin: 48px auto 0;
  text-align: center;
  line-height: 2;
}
.wr-empty p { margin-top: 8px; font-size: 13px; }

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
}
</style>
