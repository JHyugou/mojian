<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import Icon from '../components/Icon.vue'
import { useMessageStore } from '../stores/message'
import {
  getAiStatus,
  saveAiConfig,
  testAiConnection,
  resetAiConfig,
  listProviders,
  fetchModels,
  saveEnabledModels
} from '../api/aiConfig'

/**
 * 模型接入。
 *
 * 交互照主流客户端：**选厂家 → 填 Key → 选模型**，而不是让人手填接口地址。
 * 手填地址是件不友好的事 —— 那个地址长得像密码，还得记住后缀是 /v1 还是 /v4，
 * 填错一个字符的表现是"连不上"，而报错不会告诉你少了个斜杠。
 */
const message = useMessageStore()

const status = ref(null)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const showResetConfirm = ref(false)
const resetting = ref(false)

const providers = ref([])
const selectedProvider = ref(null)

/** 服务商返回的模型列表；为空时退回手动输入 */
const models = ref([])
const fetchingModels = ref(false)
/** 模型是不是从公开目录查到的（用来把提示语说准） */
const modelsFromCatalog = ref(false)
/** 手动输入模式：拉不到列表（服务商没实现 /models）或作者想自己填时用 */
const manualModel = ref(false)

// apiKey 输入留空 = 沿用已存密钥；填了值 = 替换
const form = reactive({ baseUrl: '', model: '', apiKey: '' })

const hasStoredKey = computed(() => status.value?.mode === 'LIVE' && status.value?.source === 'DB')
const canFetchModels = computed(() => Boolean(form.baseUrl.trim()))

/** 掩码字段名是 apiKeyMasked（写成 apiKeyMask 会静默拿到 undefined，界面上就是一片空白） */
const keyMask = computed(() => status.value?.apiKeyMasked || '')

function hostOf(url) {
  try {
    return new URL(url).host.toLowerCase()
  } catch {
    return null
  }
}

/**
 * 密钥状态：三种，而不是两种。
 *
 * <p>原来只有"没配过"和"配过"两种，于是出现一个很坑的情况：
 * 已存的是 OpenCode Go 的密钥，用户切到 DeepSeek 官方，
 * 界面照样说「已保存，留空表示沿用」—— 而那个密钥连不上 DeepSeek。
 * 他会以为是自己 Key 填错了，实际上是被界面误导了。
 */
const keyState = computed(() => {
  if (!hasStoredKey.value) return 'none'
  const stored = (status.value?.baseUrl || '').replace(/\/+$/, '')
  const current = (form.baseUrl || '').replace(/\/+$/, '')
  if (!current) return 'same'
  if (stored === current) return 'same'
  // 路径不完全一致（/v1 的有无）不算换家，按域名判断
  const storedHost = hostOf(stored)
  return storedHost && storedHost === hostOf(current) ? 'same' : 'different'
})

const storedHost = computed(() => hostOf(status.value?.baseUrl || '') || status.value?.baseUrl || '')

const keyPlaceholder = computed(() => {
  if (keyState.value === 'none') return '粘贴你的 API Key'
  if (keyState.value === 'same') return `已保存 ${keyMask.value}，留空表示沿用`
  return `换了一家，需要填它自己的 API Key`
})

const keyHint = computed(() => {
  if (keyState.value === 'different') {
    return `已保存的是 ${storedHost.value} 的密钥（${keyMask.value}）。`
      + '它与当前选择的服务商不是同一家，留空会沿用旧密钥 —— 多半连不上。'
  }
  return '密钥经 AES-GCM 加密存入本机数据库；界面与日志里只出现掩码'
})

async function load() {
  loading.value = true
  try {
    const [statusResult, providerResult] = await Promise.allSettled([
      getAiStatus(),
      listProviders()
    ])
    if (statusResult.status === 'fulfilled') {
      status.value = statusResult.value
      if (hasStoredKey.value) {
        form.baseUrl = status.value.baseUrl || ''
        form.model = status.value.model || ''
        // 反查当前用的是哪个预设，好把卡片高亮对上
        selectedProvider.value =
          providers.value.find((p) => p.baseUrl && p.baseUrl === form.baseUrl) || null
      }
    } else {
      message.error(statusResult.reason?.message || '读取配置失败')
    }
    if (providerResult.status === 'fulfilled') {
      providers.value = providerResult.value || []
      // 预设到手后再补一次高亮（上面那次可能因为预设还没到而落空）
      if (!selectedProvider.value && form.baseUrl) {
        selectedProvider.value = providers.value.find((p) => p.baseUrl === form.baseUrl) || null
      }
    }
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  // 已经配好了就直接把模型列表拉出来：进页面就该看到"能用哪些、开了哪些"，
  // 而不是先点一次按钮。静默加载，不弹提示。
  if (hasStoredKey.value && form.baseUrl) {
    await loadModels(true)
  }
})

function chooseProvider(provider) {
  selectedProvider.value = provider
  if (provider.baseUrl) {
    form.baseUrl = provider.baseUrl
  } else {
    // 自定义：清空让他自己填，但保留已填的值免得误删
    form.baseUrl = form.baseUrl && !providers.value.some((p) => p.baseUrl === form.baseUrl)
      ? form.baseUrl
      : ''
  }
  /*
   * 先用预设里登记的模型填上，而不是清空。
   *
   * 理由是实测出来的：不是每家都实现了 GET /models（OpenCode Go 就返回 404），
   * 所以"先留空、等拉取"会让这些厂家的用户面对一个空下拉，以为坏了。
   * 预设里的名字是验证过的，先给出来；想拉最新的随时点「获取可用模型」。
   */
  models.value = (provider.models || []).map((id) => ({ id, name: id }))
  modelsFromCatalog.value = false
  manualModel.value = models.value.length === 0
  form.model = models.value.length ? models.value[0].id : ''
}

/**
 * 拉取可用模型。
 *
 * 顺带当"Key 体检"用：拉不到通常就是 Key 不对或地址写错，
 * 而这个反馈比"保存后调不通"早得多、也清楚得多。
 */
async function loadModels(silent = false) {
  if (!canFetchModels.value) {
    if (!silent) {
      message.error('请先填写或选择一个接口地址')
    }
    return
  }
  fetchingModels.value = true
  try {
    const result = await fetchModels({
      baseUrl: form.baseUrl.trim(),
      apiKey: form.apiKey.trim() || null
    })
    // 后端返回 { source, models: [{id, name, contextLimit, ..., enabled}] }
    models.value = result?.models || []
    modelsFromCatalog.value = result?.source === 'catalog-or-provider'

    if (!models.value.length) {
      // 目录里没有、服务商也没实现 /models
      const fallback = (selectedProvider.value?.models || []).map((id) => ({ id, name: id, enabled: true }))
      models.value = fallback
      manualModel.value = fallback.length === 0
      if (!silent) {
        message.info(
          fallback.length
            ? '没查到这一家的模型列表，已用内置的常用模型'
            : '没查到模型列表，请手动填写模型名称'
        )
      }
      if (fallback.length && !form.model) {
        form.model = fallback[0].id
      }
    } else {
      manualModel.value = false
      if (!models.value.some((m) => m.id === form.model)) {
        form.model = models.value[0].id
      }
      if (!silent) {
        message.success(`查到 ${models.value.length} 个可用模型`)
      }
    }
  } catch (e) {
    if (!silent) {
      manualModel.value = true
      message.error(e.message)
    }
  } finally {
    fetchingModels.value = false
  }
}

/** 选中的模型附加信息（保留给旧的单模型用法） */
const selectedModelInfo = computed(() =>
  models.value.find((m) => m.id === form.model) || null
)

/** 已启用的模型数 */
const enabledCount = computed(() => models.value.filter((m) => m.enabled).length)

/**
 * 这个厂商是不是当前正在用的那一家。
 *
 * <p>按域名比较而不是完整地址：带不带 /v1 只是同一家的两种写法。
 */
function isConfigured(provider) {
  if (!provider.baseUrl || !status.value?.baseUrl) return false
  const a = hostOf(provider.baseUrl)
  const b = hostOf(status.value.baseUrl)
  return Boolean(a) && a === b
}

/**
 * 切换某个模型的启用状态。
 *
 * <p>先改本地再调接口：开关是高频轻操作，等一次往返会有明显的延迟感。
 * 失败了就回滚并提示 —— 不能让界面显示"已关闭"而库里还是开着的。
 */
async function toggleModel(model) {
  const next = models.value.map((m) =>
    m.id === model.id ? { ...m, enabled: !m.enabled } : m)
  const enabledIds = next.filter((m) => m.enabled).map((m) => m.id)
  models.value = next
  try {
    await saveEnabledModels(enabledIds)
  } catch (e) {
    models.value = models.value.map((m) =>
      m.id === model.id ? { ...m, enabled: model.enabled } : m)
    message.error('保存失败：' + e.message)
  }
}

function formatContext(tokens) {
  if (!tokens) return ''
  return tokens >= 1000000 ? `${(tokens / 1000000).toFixed(1)}M` : `${Math.round(tokens / 1000)}K`
}

function validate() {
  if (!form.baseUrl.trim()) return '接口地址不能为空'
  if (!/^https?:\/\/\S+$/.test(form.baseUrl.trim())) {
    return '接口地址需以 http(s):// 开头'
  }
  if (!form.model.trim()) return '请选择或填写模型名称'
  if (!hasStoredKey.value && !form.apiKey.trim()) return '请填写 API Key'
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
    message.success(`连接成功：${reply}`)
  } catch (e) {
    message.error(e.message)
  } finally {
    testing.value = false
  }
}

async function doReset() {
  resetting.value = true
  try {
    status.value = await resetAiConfig()
    form.baseUrl = ''
    form.model = ''
    form.apiKey = ''
    models.value = []
    selectedProvider.value = null
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
  <div class="ai-page">
    <header class="ai-head">
      <h1 class="ai-title">模型接入</h1>
      <p class="ai-sub">选择服务商，填入 API Key 即可。任何 OpenAI 兼容的服务都能接。</p>
    </header>

    <!-- ========== 选服务商 ========== -->
    <section class="ai-card">
      <div class="ai-card-head">
        <span class="ai-card-title">选择服务商</span>
        <span class="ai-muted">选中会自动填好接口地址</span>
      </div>

      <div class="ai-providers">
        <button
          v-for="p in providers"
          :key="p.id"
          class="ai-provider"
          :class="{ active: selectedProvider?.id === p.id }"
          @click="chooseProvider(p)"
        >
          <span class="ai-provider-top">
            <!-- 小绿点 = 这个厂商就是当前正在用的那一家 -->
            <span class="ai-provider-dot" :class="{ on: isConfigured(p) }"></span>
            <span class="ai-provider-name">{{ p.name }}</span>
          </span>
          <span class="ai-provider-desc">{{ p.description }}</span>
        </button>
      </div>

      <!-- 选中后把"去哪儿申请 Key"直接给出来，省得作者去搜 -->
      <p v-if="selectedProvider?.consoleUrl" class="ai-console">
        申请 API Key：
        <a :href="selectedProvider.consoleUrl" target="_blank" rel="noreferrer">
          {{ selectedProvider.consoleUrl }}
        </a>
      </p>
    </section>

    <!-- ========== 连接配置 ========== -->
    <section class="ai-card">
      <div class="ai-card-head">
        <span class="ai-card-title">连接配置</span>
      </div>

      <label class="ai-field">
        <span class="ai-label">接口地址 Base URL</span>
        <input v-model="form.baseUrl" class="ai-input" placeholder="https://…/v1" />
        <span class="ai-hint">选服务商后自动填好；自定义服务请自己填，通常以 /v1 结尾</span>
      </label>

      <label class="ai-field">
        <span class="ai-label-row">
          <span class="ai-label">API Key</span>
          <span v-if="keyState === 'none'" class="ai-key-badge">未配置</span>
          <span v-else-if="keyState === 'same'" class="ai-key-badge ok">已配置</span>
          <span v-else class="ai-key-badge warn">需重新填写</span>
        </span>
        <input
          v-model="form.apiKey"
          class="ai-input"
          :class="{ 'is-warn': keyState === 'different' }"
          type="password"
          :placeholder="keyPlaceholder"
        />
        <span class="ai-hint" :class="{ 'is-warn': keyState === 'different' }">{{ keyHint }}</span>
      </label>

      <div class="ai-field">
        <div class="ai-label-row">
          <span class="ai-label">模型</span>
          <button
            class="ai-mini-btn"
            :disabled="!canFetchModels || fetchingModels"
            @click="loadModels"
          >
            <Icon name="search" :size="12" />
            {{ fetchingModels ? '获取中…' : '获取可用模型' }}
          </button>
          <button class="ai-mini-btn" @click="manualModel = !manualModel">
            {{ manualModel ? '改用列表选择' : '手动填写' }}
          </button>
        </div>

        <!-- 有列表就下拉选；没有（或作者要手填）就输入框。
             不给"只能手填"也不给"只能选" —— 不是每家都能查到列表 -->
        <select v-if="!manualModel && models.length" v-model="form.model" class="ai-input">
          <option v-for="m in models" :key="m.id" :value="m.id">
            {{ m.name || m.id }}{{ m.contextLimit ? `（${formatContext(m.contextLimit)} 上下文）` : '' }}
          </option>
        </select>
        <input
          v-else
          v-model="form.model"
          class="ai-input"
          placeholder="例如 deepseek-v4.1-flash"
        />

        <!-- 目录里带着上下文长度与价格，选中的模型直接显示出来：
             作者据此判断"这个模型够不够装下我这本书" -->
        <span v-if="selectedModelInfo" class="ai-model-meta">
          <span v-if="selectedModelInfo.contextLimit" class="mj-tag">
            上下文 {{ formatContext(selectedModelInfo.contextLimit) }}
          </span>
          <span v-if="selectedModelInfo.inputCost != null" class="mj-tag">
            入 ${selectedModelInfo.inputCost}/M
          </span>
          <span v-if="selectedModelInfo.outputCost != null" class="mj-tag">
            出 ${selectedModelInfo.outputCost}/M
          </span>
        </span>

        <span class="ai-hint">
          {{ models.length
            ? (modelsFromCatalog
                ? `从公开模型目录查到 ${models.length} 个模型`
                : '这是内置的常用模型；点「获取可用模型」可以查最新的')
            : '点「获取可用模型」查这一家有哪些模型；也可以直接手填' }}
        </span>
      </div>

      <div class="ai-actions">
        <button class="ai-btn primary" :disabled="saving" @click="submit">
          {{ saving ? '保存中…' : '保存并启用' }}
        </button>
        <button class="ai-btn" :disabled="testing" @click="runTest">
          {{ testing ? '测试中…' : '测试连接' }}
        </button>
        <button class="ai-btn danger" @click="showResetConfirm = true">恢复演示模式</button>
      </div>
    </section>

    <!-- ========== 可用模型：挑出要用的那几个 ========== -->
    <section v-if="models.length" class="ai-card">
      <div class="ai-card-head">
        <span class="ai-card-title">可用模型</span>
        <span class="ai-muted">
          一个网关常有几十个模型，用不上的关掉 —— 输入框下方的模型列表会跟着变短
        </span>
        <span class="ai-enabled-count">
          已启用 {{ enabledCount }} / {{ models.length }}
        </span>
      </div>

      <div class="ai-model-list mj-scroll">
        <div
          v-for="m in models"
          :key="m.id"
          class="ai-model-row"
          :class="{ on: m.enabled }"
          @click="toggleModel(m)"
        >
          <span class="ai-model-dot" :class="{ on: m.enabled }"></span>
          <span class="ai-model-main">
            <span class="ai-model-id">{{ m.id }}</span>
            <span v-if="m.name && m.name !== m.id" class="ai-model-name">{{ m.name }}</span>
          </span>
          <span class="ai-model-meta">
            <span v-if="m.contextLimit" class="mj-tag">{{ formatContext(m.contextLimit) }}</span>
            <span v-if="m.inputCost != null" class="mj-tag">入 ${{ m.inputCost }}/M</span>
          </span>
          <!-- 用一行小开关表达"开/关"，比 checkbox 更符合这套设计的语气 -->
          <span class="ai-switch" :class="{ on: m.enabled }"><i></i></span>
        </div>
      </div>

      <p class="ai-hint">
        关掉的模型不会出现在创作工作台的模型选择里，但随时可以再打开。
      </p>
    </section>

    <!-- ========== 说明 ========== -->
    <section class="ai-card">
      <div class="ai-card-head">
        <span class="ai-card-title">说明</span>
      </div>
      <ul class="ai-notes">
        <li>保存后立即生效；正在进行的生成会用旧配置跑完，下一次任务用新配置。</li>
        <li>
          「获取可用模型」会向服务商要一次模型列表 ——
          它同时也是一次 <b>Key 体检</b>：拉不到通常就是 Key 或地址不对。
        </li>
        <li>「恢复演示模式」不会删除你的任何创作数据，只是回到内置示例文风。</li>
        <li>密钥永不进入日志与生成快照；数据库泄露也无法直接读出明文。</li>
      </ul>
    </section>

    <ConfirmDialog
      v-if="showResetConfirm"
      title="恢复演示模式"
      message="将清除界面里配置的模型接入信息，回到内置演示文风。你的创作数据不受影响。"
      confirm-text="确认恢复"
      :loading="resetting"
      @confirm="doReset"
      @cancel="showResetConfirm = false"
    />
  </div>
</template>

<style scoped>
.ai-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 28px 24px 60px;
}

.ai-head { margin-bottom: 20px; }
.ai-title {
  font-family: var(--font-serif);
  font-size: 26px;
  color: var(--color-brown-deep);
  letter-spacing: 0.06em;
}
.ai-sub { margin-top: 6px; font-size: 13px; color: var(--color-muted); }

.ai-card {
  margin-bottom: 18px;
  padding: 18px 20px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-m);
  background: var(--color-panel);
}

.ai-card-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 14px;
}

.ai-card-title {
  font-family: var(--font-serif);
  font-size: 15px;
  color: var(--color-brown-deep);
  letter-spacing: 0.06em;
}

.ai-muted { font-size: 12px; color: var(--color-muted); }

/* ---------- 服务商卡片 ---------- */
.ai-providers {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 10px;
}

.ai-provider {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 12px 14px;
  border: 1px solid var(--color-line-soft);
  border-radius: var(--radius-m);
  background: var(--color-paper-warm);
  cursor: pointer;
  text-align: left;
  transition: border-color var(--dur) var(--ease-out),
              background var(--dur) var(--ease-out),
              box-shadow var(--dur) var(--ease-out),
              transform var(--dur) var(--ease-out);
}
.ai-provider:hover {
  border-color: var(--color-brown-soft);
  box-shadow: var(--shadow-xs);
  transform: translateY(-1px);
}
.ai-provider.active {
  border-color: var(--color-brown);
  background: var(--color-panel);
  box-shadow: var(--shadow-soft);
}

.ai-provider-top {
  display: flex;
  align-items: center;
  gap: 7px;
}

/* 已接入的绿点：不用"已接入"三个字，一个小圆点就够，也更好扫 */
.ai-provider-dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--color-line);
  transition: background var(--dur) var(--ease-out), box-shadow var(--dur) var(--ease-out);
}
.ai-provider-dot.on {
  background: #57a05c;
  box-shadow: 0 0 0 3px rgba(87, 160, 92, 0.16);
}

.ai-provider-name {
  font-family: var(--font-serif);
  font-size: 14px;
  color: var(--color-ink);
}
.ai-provider.active .ai-provider-name { color: var(--color-brown-deep); }

.ai-provider-desc { font-size: 11px; line-height: 1.6; color: var(--color-muted); }

.ai-console {
  margin-top: 12px;
  font-size: 12px;
  color: var(--color-muted);
}
.ai-console a { color: var(--color-brown); text-decoration: none; }
.ai-console a:hover { text-decoration: underline; }

/* ---------- 表单 ---------- */
.ai-field { display: block; margin-bottom: 16px; }

.ai-label-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.ai-label {
  display: block;
  font-size: 12px;
  letter-spacing: 0.06em;
  color: var(--color-muted);
}
.ai-label-row .ai-label { margin-bottom: 0; }

.ai-input {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  background: var(--color-paper);
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--color-ink);
  transition: border-color var(--dur) var(--ease-out);
}
.ai-input:focus { outline: none; border-color: var(--color-brown); }

.ai-hint {
  display: block;
  margin-top: 6px;
  font-size: 11px;
  line-height: 1.7;
  color: var(--color-muted);
}
.ai-hint.is-warn { color: var(--color-danger); }

/* 密钥状态徽标：三种状态要一眼分得出来 */
.ai-key-badge {
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 10px;
  letter-spacing: 0.06em;
  background: var(--color-paper-warm);
  color: var(--color-muted);
}
.ai-key-badge.ok { background: rgba(107, 142, 35, 0.12); color: #5a7a1e; }
.ai-key-badge.warn { background: rgba(163, 63, 48, 0.1); color: var(--color-danger); }

.ai-input.is-warn { border-color: var(--color-danger); }

/* 选中模型的上下文与价格：用小标签排一行，不抢标题但看得见 */
.ai-model-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.ai-mini-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 9px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  background: var(--color-panel);
  cursor: pointer;
  font-size: 11px;
  color: var(--color-brown-deep);
  transition: border-color var(--dur) var(--ease-out);
}
.ai-mini-btn:hover:not(:disabled) { border-color: var(--color-brown); }
.ai-mini-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* ---------- 按钮 ---------- */
.ai-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  margin-top: 4px;
}

.ai-btn {
  padding: 8px 18px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  background: var(--color-panel);
  cursor: pointer;
  font-size: 13px;
  color: var(--color-ink);
  transition: border-color var(--dur) var(--ease-out),
              background var(--dur) var(--ease-out);
}
.ai-btn:hover:not(:disabled) { border-color: var(--color-brown); }
.ai-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.ai-btn.primary {
  border-color: var(--color-brown);
  background: var(--color-brown);
  color: #fff;
}
.ai-btn.primary:hover:not(:disabled) { background: var(--color-brown-deep); }

.ai-btn.danger { color: var(--color-danger); }
.ai-btn.danger:hover:not(:disabled) { border-color: var(--color-danger); }

/* ---------- 说明 ---------- */
.ai-notes {
  padding-left: 18px;
  font-size: 12px;
  line-height: 2;
  color: var(--color-muted);
}
.ai-notes li::marker { color: var(--color-brown); }

/* ==================== 可用模型列表 ==================== */

.ai-enabled-count {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--color-brown-deep);
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  background: var(--color-brown-tint);
}

.ai-model-list {
  max-height: 340px;
  overflow-y: auto;
  padding: 2px;
  border-radius: var(--radius-s);
}

.ai-model-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 11px;
  border-radius: var(--radius-xs);
  cursor: pointer;
  transition: background var(--dur) var(--ease-out);
}
.ai-model-row:hover { background: var(--color-brown-tint); }

/* 关闭的行整体降一档饱和度：一眼能看出"这个我没在用" */
.ai-model-row:not(.on) .ai-model-id { color: var(--color-muted); }

.ai-model-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-line);
  transition: background var(--dur) var(--ease-out);
}
.ai-model-dot.on { background: var(--color-brown); }

.ai-model-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.ai-model-id {
  font-size: 13px;
  color: var(--color-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-model-name {
  font-size: 11px;
  color: var(--color-muted);
}

.ai-model-meta {
  display: flex;
  gap: 5px;
  flex: none;
}

/* 小开关：轨道 + 圆点。比 checkbox 轻，符合这套设计的语气 */
.ai-switch {
  flex: none;
  position: relative;
  width: 32px;
  height: 18px;
  border-radius: var(--radius-pill);
  background: var(--color-line);
  transition: background var(--dur) var(--ease-out);
}
.ai-switch.on { background: var(--color-brown); }

.ai-switch i {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(70, 52, 36, 0.2);
  transition: transform var(--dur) var(--ease-spring);
}
.ai-switch.on i { transform: translateX(14px); }
</style>
