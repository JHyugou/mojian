<script setup>
import { nextTick, onMounted, ref } from 'vue'
import http from '../api/http'

const props = defineProps({ projectId: { type: String, required: true } })
const message = ref('')
const messages = ref([])
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
  const text = message.value.trim()
  if (!text || loading.value) return
  message.value = ''
  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'assistant', content: '', loading: true })
  scrollToBottom()

  loading.value = true
  try {
    const history = messages.value
      .filter((m) => !m.loading && m.content)
      .slice(0, -1)
      .map((m) => ({ role: m.role, content: m.content }))
    const res = await http.post(`/projects/${props.projectId}/chat`, {
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
  <div class="chat-panel">
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
        v-model="message"
        class="chat-input"
        rows="2"
        placeholder="问剧情走向、起名字、让 AI 生成 JSON…"
        @keydown.enter.exact.prevent="send"
      />
      <button class="btn-send" type="button" :disabled="loading || !message.trim()" @click="send">
        {{ loading ? '…' : '发送' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 300px;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 4px 2px;
  margin-bottom: 10px;
}

.chat-msg {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.msg-role {
  font-size: 11px;
  color: var(--color-muted);
  letter-spacing: 0.1em;
}

.msg-assistant .msg-role {
  color: var(--color-cinnabar);
}

.msg-bubble {
  padding: 8px 12px;
  border-radius: var(--radius-s);
  font-size: 13.5px;
  line-height: 1.9;
  max-width: 95%;
  white-space: pre-wrap;
}

.msg-user .msg-bubble {
  background: var(--color-dai);
  margin-left: auto;
}

.msg-assistant .msg-bubble {
  background: rgba(255, 255, 255, 0.5);
}

.chat-input-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}

.chat-input {
  flex: 1;
  resize: none;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  padding: 8px 10px;
  font-size: 13.5px;
  font-family: var(--font-kai);
  line-height: 1.7;
  background: var(--color-panel);
  color: var(--color-ink);
}

.chat-input:focus {
  outline: none;
  border-color: var(--color-cinnabar);
}

.btn-send {
  border: none;
  background: var(--color-cinnabar);
  color: #f6f1e4;
  padding: 8px 16px;
  border-radius: var(--radius-s);
  cursor: pointer;
  font-family: var(--font-kai);
  font-size: 14px;
  letter-spacing: 0.08em;
  transition: opacity 0.15s ease;
}

.btn-send:disabled {
  opacity: 0.5;
  cursor: default;
}
</style>
