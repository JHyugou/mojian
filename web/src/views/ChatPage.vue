<script setup>
import { nextTick, onMounted, ref } from 'vue'
import ProjectGate from '../components/ProjectGate.vue'
import { useProjectStore } from '../stores/project'
import { useMessageStore } from '../stores/message'
import http from '../api/http'

const projectStore = useProjectStore()
const message = useMessageStore()

const messages = ref([])
const input = ref('')
const loading = ref(false)
const listRef = ref(null)

onMounted(() => {
  messages.value = [{ role: 'assistant', content: '我是你的创作助手，熟悉这本小说的全部人物、设定与蓝图。有什么想聊的？' }]
})

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

async function send() {
  const text = input.value.trim()
  if (!text || loading.value) return
  input.value = ''
  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'assistant', content: '', loading: true })
  scrollToBottom()

  loading.value = true
  try {
    const history = messages.value
      .filter((m) => !m.loading && m.content)
      .slice(0, -1)
      .map((m) => ({ role: m.role, content: m.content }))
    const res = await http.post(`/projects/${projectStore.currentId}/chat`, {
      message: text,
      history
    })
    messages.value[messages.value.length - 1] = { role: 'assistant', content: res.reply }
  } catch (e) {
    messages.value[messages.value.length - 1] = { role: 'assistant', content: `出错了：${e.message}` }
  } finally {
    loading.value = false
    scrollToBottom()
  }
}
</script>

<template>
  <ProjectGate>
    <section class="chat-page">
      <div class="chat-head">
        <h1 class="page-title">AI 创作助手</h1>
        <span class="chat-sub">熟悉全书的创作顾问 · 头脑风暴 / 剧情建议 / 起名 / 生成 JSON</span>
      </div>
      <div ref="listRef" class="chat-messages">
        <div
          v-for="(m, i) in messages"
          :key="i"
          class="chat-msg"
          :class="`msg-${m.role}`"
        >
          <span class="msg-role">{{ m.role === 'user' ? '你' : '助手' }}</span>
          <div class="msg-bubble">{{ m.content || (m.loading ? '思考中…' : '') }}</div>
        </div>
      </div>
      <div class="chat-input-row">
        <textarea
          v-model="input"
          class="chat-input"
          rows="3"
          placeholder="问剧情走向、起名字、让 AI 生成 JSON…（Enter 发送）"
          @keydown.enter.exact.prevent="send"
        />
        <button class="btn-send" type="button" :disabled="loading || !input.trim()" @click="send">
          {{ loading ? '…' : '发送' }}
        </button>
      </div>
    </section>
  </ProjectGate>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 20px 28px;
}

.chat-head {
  margin-bottom: 14px;
}

.page-title {
  font-size: 22px;
}

.chat-sub {
  font-size: 13px;
  color: var(--color-muted);
  letter-spacing: 0.04em;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px 4px;
}

.chat-msg {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 72%;
}

.msg-role {
  font-size: 11px;
  color: var(--color-muted);
  letter-spacing: 0.12em;
}

.msg-user {
  align-self: flex-end;
  align-items: flex-end;
}

.msg-assistant {
  align-self: flex-start;
  align-items: flex-start;
}

.msg-bubble {
  padding: 12px 18px;
  border-radius: var(--radius-s);
  font-size: 15px;
  line-height: 2.1;
  white-space: pre-wrap;
}

.msg-user .msg-bubble {
  background: var(--color-dai);
  color: var(--color-ink);
}

.msg-assistant .msg-bubble {
  background: var(--color-panel);
  border: 1px solid var(--color-line);
  color: var(--color-ink);
}

.chat-input-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.chat-input {
  flex: 1;
  resize: none;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  padding: 12px 14px;
  font-size: 15px;
  font-family: var(--font-kai);
  line-height: 1.8;
  background: var(--color-panel);
  color: var(--color-ink);
  transition: border-color 0.18s ease;
}

.chat-input:focus {
  outline: none;
  border-color: var(--color-cinnabar);
}

.btn-send {
  border: none;
  background: var(--color-cinnabar);
  color: #f6f1e4;
  padding: 12px 28px;
  border-radius: var(--radius-s);
  cursor: pointer;
  font-family: var(--font-kai);
  font-size: 15px;
  letter-spacing: 0.14em;
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.btn-send:hover {
  opacity: 0.9;
  transform: translateY(-1px);
}

.btn-send:disabled {
  opacity: 0.45;
  cursor: default;
  transform: none;
}
</style>
