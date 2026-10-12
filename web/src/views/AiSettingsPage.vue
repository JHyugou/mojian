<script setup>
import { onMounted, reactive, ref } from 'vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import { useMessageStore } from '../stores/message'
import { getAiStatus, saveAiConfig, testAiConnection, resetAiConfig } from '../api/aiConfig'

const message = useMessageStore()

const status = ref(null)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const showResetConfirm = ref(false)
const resetting = ref(false)

// apiKey 输入留空 = 沿用已存密钥；填了值 = 替换
const form = reactive({ baseUrl: '', model: '', apiKey: '' })

async function load() {
  loading.value = true
  try {
    status.value = await getAiStatus()
    if (status.value?.mode === 'LIVE' && status.value.source === 'DB') {
      form.baseUrl = status.value.baseUrl || ''
      form.model = status.value.model || ''
    }
  } catch (e) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function validate() {
  if (!form.baseUrl.trim()) return '接口地址不能为空'
  if (!/^https?:\/\/\S+$/.test(form.baseUrl.trim())) return '接口地址需以 http(s):// 开头，如 https://api.deepseek.com'
  if (!form.model.trim()) return '模型名称不能为空'
  const editingStoredKey = status.value?.mode === 'LIVE' && status.value?.source === 'DB'
  if (!editingStoredKey && !form.apiKey.trim()) return '请填写 API Key'
  return null
}

async function submit() {
  const problem = validate()
  if (problem) {
    message.error(problem)
    return
  }
  saving.value = true
  try {
    status.value = await saveAiConfig({
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      // 已有本机密钥且未输入新值 → 不传，沿用已存
      apiKey: form.apiKey.trim() || null
    })
    form.apiKey = ''
    message.success('已接入真模型，生成立即生效')
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function runTest() {
  const problem = validate()
  if (problem) {
    message.error(problem)
    return
  }
  testing.value = true
  try {
    const reply = await testAiConnection({
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      apiKey: form.apiKey.trim() || null
    })
    message.success(`连接成功，模型回复：${reply}`)
  } catch (e) {
    message.error(e.message)
  } finally {
    testing.value = false
  }
}

async function confirmReset() {
  resetting.value = true
  try {
    status.value = await resetAiConfig()
    form.baseUrl = ''
    form.model = ''
    form.apiKey = ''
    showResetConfirm.value = false
    message.success('已恢复演示模式')
  } catch (e) {
    message.error(e.message)
  } finally {
    resetting.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">模型接入</h1>
        <p class="page-hint">绑定任意 OpenAI 兼容的模型服务；不绑定时使用内置演示文风</p>
      </div>
      <span
        v-if="status"
        class="mode-badge"
        :class="status.mode === 'LIVE' ? 'mode-live' : 'mode-demo'"
      >
        {{ status.mode === 'LIVE' ? `已接入 · ${status.model}` : '演示模式' }}
      </span>
    </div>

    <div class="card ai-card">
      <div class="section-title">接口配置</div>

      <div class="field">
        <label class="field-label">接口地址 Base URL</label>
        <input
          v-model.trim="form.baseUrl"
          class="form-input"
          placeholder="如 https://api.deepseek.com"
        />
        <p class="field-tip">OpenAI 兼容服务地址，常见后缀 /v1 以服务商文档为准</p>
      </div>

      <div class="field">
        <label class="field-label">模型名称</label>
        <input
          v-model.trim="form.model"
          class="form-input"
          placeholder="如 deepseek-chat、moonshot-v1-32k、glm-4"
        />
      </div>

      <div class="field">
        <label class="field-label">API Key</label>
        <input
          v-model.trim="form.apiKey"
          class="form-input"
          type="password"
          autocomplete="new-password"
          :placeholder="status?.apiKeyMasked ? `已保存 ${status.apiKeyMasked}，留空则不修改` : '在模型服务商后台创建，形如 sk-…'"
        />
        <p class="field-tip">密钥经 AES-GCM 加密存入本机数据库，界面只显示掩码；换机器需重新填写</p>
      </div>

      <div class="actions">
        <button class="btn btn-primary" type="button" :disabled="saving" @click="submit">
          {{ saving ? '保存中…' : '保存并启用' }}
        </button>
        <button class="btn btn-secondary" type="button" :disabled="testing" @click="runTest">
          {{ testing ? '测试中…' : '测试连接' }}
        </button>
        <button
          v-if="status?.source === 'DB'"
          class="btn btn-danger"
          type="button"
          @click="showResetConfirm = true"
        >
          恢复演示模式
        </button>
      </div>
    </div>

    <div class="card help-card">
      <div class="section-title">说明</div>
      <ul class="help-list">
        <li>保存后立即生效，正在进行的生成会用旧配置完成，下一次任务启用新配置。</li>
        <li>「测试连接」会向服务商发送一次极短的验证请求，消耗可忽略的 token。</li>
        <li>恢复演示模式不会删除你的任何创作数据，仅回到内置示例文风。</li>
        <li>密钥永不进入日志与生成快照；数据库泄露也无法直接读出明文。</li>
      </ul>
    </div>

    <ConfirmDialog
      :visible="showResetConfirm"
      title="恢复演示模式"
      message="将清除界面保存的接口配置（含加密密钥），AI 生成回到内置演示文风。确定继续吗？"
      confirm-text="清除并恢复"
      danger
      :loading="resetting"
      @close="showResetConfirm = false"
      @confirm="confirmReset"
    />
  </div>
</template>

<style scoped>
.page {
  padding: 20px 24px;
  overflow-y: auto;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.mode-badge {
  font-size: 13px;
  letter-spacing: 0.08em;
  padding: 5px 14px;
  border-radius: var(--radius-s);
  border: 1px solid var(--color-line);
}

.mode-live {
  color: #2c6e56;
  background: rgba(44, 110, 86, 0.10);
  border-color: rgba(44, 110, 86, 0.38);
}

.mode-demo {
  color: var(--color-muted);
  background: var(--color-dai);
}

.ai-card {
  max-width: 640px;
  padding: 20px;
  margin-bottom: 16px;
}

.help-card {
  max-width: 640px;
  padding: 20px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 14px;
  color: var(--color-ink);
}

.field {
  margin-bottom: 14px;
}

.field-label {
  display: block;
  font-size: 13px;
  color: var(--color-muted);
  margin-bottom: 6px;
}

.field-tip {
  font-size: 12px;
  color: var(--color-muted);
  opacity: 0.8;
  margin-top: 5px;
}

.actions {
  display: flex;
  gap: 10px;
  margin-top: 18px;
}

.help-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 2;
  color: var(--color-muted);
}
</style>
