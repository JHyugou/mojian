<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import Icon from '../../components/Icon.vue'
import V3Button from '../../components/v3/V3Button.vue'
import V3Field from '../../components/v3/V3Field.vue'
import V3Input from '../../components/v3/V3Input.vue'
import V3Modal from '../../components/v3/V3Modal.vue'
import V3Tag from '../../components/v3/V3Tag.vue'
import { useV3Notify } from '../notify'
import {
  fetchModels, getAiStatus, listProviders, resetAiConfig,
  saveAiConfig, saveEnabledModels, testAiConnection
} from '../../api/aiConfig'
import { listAgentProfiles, updateAgentProfile, getAgentUsage } from '../../api/agent'

/**
 * 模型接入（v3）：一个页面管完三件事 ——
 * ① 服务商与密钥（全局） ② 可用模型与启用清单 ③ 策划/写作两个角色的模型配置。
 * 语义沿用旧版：apiKey 留空 = 沿用已存；非空 = 替换；"恢复演示模式"才清除。
 */
const notify = useV3Notify()

const status = ref(null)
const loading = ref(true)
const saving = ref(false)
const testing = ref(false)
const testingReply = ref('')
const providers = ref([])
const selectedProviderId = ref('')
const form = reactive({ baseUrl: '', model: '', apiKey: '' })
const models = ref([])
const modelKeyword = ref('')
const fetchingModels = ref(false)
const savingModels = ref(false)
const profiles = ref([])
const profileDrafts = reactive({})
/** 每个角色单独拉取的服务商模型（独立连接时用） */
const roleModels = reactive({})
const roleFetching = reactive({})
const showReset = ref(false)
const resetting = ref(false)
const usage = ref(null)

const hasStoredKey = computed(() => status.value?.mode === 'LIVE' && status.value?.source === 'DB')
const keyMask = computed(() => status.value?.apiKeyMasked || '')
const liveMode = computed(() => status.value?.mode === 'LIVE')

const enabledModels = computed(() => models.value.filter((m) => m.enabled !== false))
const filteredModels = computed(() => {
  const q = modelKeyword.value.trim().toLowerCase()
  if (!q) return models.value
  return models.value.filter((m) => `${m.id} ${m.name || ''}`.toLowerCase().includes(q))
})
const modelOptions = computed(() => {
  const seen = new Set()
  const list = []
  for (const m of models.value) {
    if (!seen.has(m.id)) { seen.add(m.id); list.push(m) }
  }
  if (form.model && !seen.has(form.model)) list.unshift({ id: form.model, name: form.model, unknown: true })
  return list
})

const fmtContext = (n) => {
  if (!n) return '—'
  return n >= 1_000_000 ? `${Math.round(n / 100_000) / 10}M` : `${Math.round(n / 1000)}K`
}
async function loadUsage() {
  try {
    usage.value = await getAgentUsage(12, 30)
  } catch (e) {
    console.warn('读取用量失败', e)
  }
}

const fmtCost = (n) => (n == null ? '—' : `$${n}`)

async function load() {
  loading.value = true
  try {
    const [statusResult, providerResult, profileResult] = await Promise.allSettled([
      getAiStatus(), listProviders(), listAgentProfiles()
    ])
    if (statusResult.status === 'fulfilled') {
      status.value = statusResult.value
      form.baseUrl = statusResult.value.baseUrl || ''
      form.model = statusResult.value.model || ''
      const matched = providers.value.find((p) => p.baseUrl && p.baseUrl.replace(/\/+$/, '') === form.baseUrl.replace(/\/+$/, ''))
      if (matched) selectedProviderId.value = matched.id
    }
    if (providerResult.status === 'fulfilled') {
      providers.value = providerResult.value || []
      if (!selectedProviderId.value && form.baseUrl) {
        const matched = providers.value.find((p) => p.baseUrl && p.baseUrl.replace(/\/+$/, '') === form.baseUrl.replace(/\/+$/, ''))
        if (matched) selectedProviderId.value = matched.id
      }
    }
    if (profileResult.status === 'fulfilled') {
      profiles.value = profileResult.value || []
      for (const profile of profiles.value) {
        resetProfileDraft(profile)
      }
    }
    if (form.baseUrl) await loadModels(true)
    loadUsage()
  } catch (e) {
    notify.error(e.message || '读取模型设置失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

/**
 * 角色配置草稿：保留独立的接口地址/Key 状态。
 * 不能只把 profile.baseUrl 当成“本角色地址”——没有覆盖时它是全局地址，
 * 但界面上必须让用户清楚自己是在沿用全局还是单独配置。
 */
function resetProfileDraft(profile) {
  profileDrafts[profile.role] = {
    model: profile.model || '',
    temperature: profile.temperature ?? 0.8,
    reasoningEffort: profile.reasoningEffort || '',
    maxSteps: profile.maxSteps ?? (profile.role === 'WRITER' ? 6 : 10),
    useOwnConnection: profile.baseUrlSource === 'PROFILE' || profile.apiKeySource === 'PROFILE',
    ownKey: profile.apiKeySource === 'PROFILE',
    baseUrl: profile.baseUrl || form.baseUrl || '',
    apiKey: ''
  }
}

/** 模型候选：全局模型库 + 本角色拉取过的模型 */
function roleModelOptions(role) {
  const seen = new Set()
  const list = []
  for (const m of [...(modelOptions.value || []), ...(roleModels[role] || [])]) {
    if (!seen.has(m.id)) {
      seen.add(m.id)
      list.push(m)
    }
  }
  return list
}

/** 独立连接时，用本角色的地址和 Key 拉取模型，不必先切全局服务商 */
async function fetchRoleModels(profile) {
  const draft = profileDrafts[profile.role]
  if (!draft.baseUrl.trim()) {
    notify.error('先填写本角色的接口地址')
    return
  }
  if (!draft.apiKey.trim()) {
    notify.error('拉取模型需要临时输入该服务商的 API Key（已保存的 Key 不会回显）')
    return
  }
  roleFetching[profile.role] = true
  try {
    const data = await fetchModels({
      baseUrl: draft.baseUrl.trim(),
      apiKey: draft.apiKey.trim() || undefined
    })
    roleModels[profile.role] = Array.isArray(data) ? data : (data?.models || [])
    if (!draft.model && roleModels[profile.role].length) {
      draft.model = roleModels[profile.role][0].id
    }
    notify.success(`查到 ${roleModels[profile.role].length} 个模型`)
  } catch (e) {
    notify.error(e.message || '拉取本角色模型失败')
  } finally {
    roleFetching[profile.role] = false
  }
}

function pickProvider(provider) {
  selectedProviderId.value = provider.id
  if (provider.baseUrl) form.baseUrl = provider.baseUrl
  if (!provider.models?.length) return
  // 预设模型只作候选，不自动设默认（可能已经被服务商下线）
}

async function loadModels(silent = false) {
  if (!form.baseUrl.trim()) {
    if (!silent) notify.error('先选服务商或填写接口地址')
    return
  }
  fetchingModels.value = true
  try {
    // 后端返回 { source, models: [...] }：source 说明是目录还是直接问服务商拿到的
    const data = await fetchModels({
      baseUrl: form.baseUrl.trim(),
      apiKey: form.apiKey.trim() || undefined
    })
    models.value = Array.isArray(data) ? data : (data?.models || [])
    if (!form.model && models.value.length) form.model = models.value[0].id
    if (!silent) notify.success(`查到 ${models.value.length} 个可用模型`)
  } catch (e) {
    if (!silent) notify.error(e.message || '拉取模型失败')
  } finally {
    fetchingModels.value = false
  }
}

async function saveConnection() {
  if (!form.baseUrl.trim() || !form.model.trim()) {
    notify.error('接口地址与模型名称不能为空')
    return
  }
  if (!hasStoredKey.value && !form.apiKey.trim()) {
    notify.error('首次接入需要填写 API Key')
    return
  }
  saving.value = true
  try {
    status.value = await saveAiConfig({
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      apiKey: form.apiKey.trim() ? form.apiKey.trim() : null
    })
    form.apiKey = ''
    notify.success('已保存并立即生效，无需重启')
  } catch (e) {
    notify.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function testConnection() {
  if (!form.baseUrl.trim() || !form.model.trim()) {
    notify.error('先填写接口地址与模型名称')
    return
  }
  if (!hasStoredKey.value && !form.apiKey.trim()) {
    notify.error('还没有可用的 API Key')
    return
  }
  testing.value = true
  testingReply.value = ''
  try {
    testingReply.value = await testAiConnection({
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      apiKey: form.apiKey.trim() ? form.apiKey.trim() : null
    })
    notify.success('连接成功')
  } catch (e) {
    notify.error(e.message || '连接失败')
  } finally {
    testing.value = false
  }
}

async function toggleModel(model) {
  if (savingModels.value) return
  const nextEnabled = model.enabled === false
  const next = models.value.map((m) => (m.id === model.id ? { ...m, enabled: nextEnabled } : m))
  const enabledIds = next.filter((m) => m.enabled !== false).map((m) => m.id)
  if (!enabledIds.length) {
    notify.error('至少要保留一个启用模型')
    return
  }
  savingModels.value = true
  try {
    await saveEnabledModels(enabledIds)
    models.value = next
  } catch (e) {
    notify.error(e.message || '保存启用列表失败')
  } finally {
    savingModels.value = false
  }
}

async function saveProfile(role) {
  const draft = profileDrafts[role]
  if (draft.useOwnConnection && !draft.baseUrl.trim()) {
    notify.error('独立连接需要填写接口地址')
    return
  }
  if (draft.useOwnConnection && !draft.ownKey && !draft.apiKey.trim()) {
    notify.error('独立连接需要填写该服务商的 API Key')
    return
  }
  try {
    const updated = await updateAgentProfile(role, {
      model: draft.model || null,
      temperature: draft.temperature,
      reasoningEffort: draft.reasoningEffort ?? '',
      maxSteps: draft.maxSteps,
      // null = 不改；空字符串 = 清除本角色覆盖、回到全局
      baseUrl: draft.useOwnConnection ? draft.baseUrl.trim() : '',
      apiKey: draft.useOwnConnection
        ? (draft.apiKey.trim() ? draft.apiKey.trim() : null)
        : ''
    })
    profiles.value = profiles.value.map((p) => (p.role === role ? updated : p))
    resetProfileDraft(updated)
    notify.success(`${role === 'PLANNER' ? '策划' : '写作'} Agent 配置已更新`)
  } catch (e) {
    notify.error(e.message || '保存角色配置失败')
  }
}

async function doReset() {
  resetting.value = true
  try {
    status.value = await resetAiConfig()
    models.value = []
    testingReply.value = ''
    form.apiKey = ''
    showReset.value = false
    notify.success('已恢复演示模式（清除界面保存的接入配置）')
  } catch (e) {
    notify.error(e.message || '重置失败')
  } finally {
    resetting.value = false
  }
}

const roleLabel = (role) => (role === 'PLANNER' ? '策划 Agent' : '写作 Agent')
const roleHint = (role) => (role === 'PLANNER'
  ? '管框架：要稳、要能读完几十万字设定'
  : '写正文：要文笔与发散，通常用更强的模型')
const reasoningLevelsOf = (modelId) => {
  const found = models.value.find((m) => m.id === modelId)
  return found?.reasoningLevels || []
}
</script>

<template>
  <div class="settings-page">
    <div class="settings v3-scroll">
      <header class="settings-head">
        <p class="kicker">MODELS</p>
        <h1>模型接入</h1>
        <p class="sub">一个页面管完：服务商与密钥、可用模型、策划/写作两个角色的模型。保存后立即生效。</p>
        <div class="status-row">
          <V3Tag :tone="liveMode ? 'jade' : 'gold'">
            {{ liveMode ? '真实模式' : '演示模式' }}
          </V3Tag>
          <V3Tag v-if="status?.source === 'DB'">来源：设置页</V3Tag>
          <V3Tag v-if="status?.source === 'ENV'">来源：环境变量</V3Tag>
          <span v-if="keyMask" class="masked">Key {{ keyMask }}</span>
        </div>
      </header>

      <div v-if="loading" class="loading">正在读取设置…</div>

      <template v-else>
        <!-- 壹 · 服务商 -->
        <section class="block">
          <header class="block-head">
            <span class="block-no">壹</span>
            <div><h2>服务商</h2><p>选一家，接口地址会自动填好；自定义服务也可以手填地址。</p></div>
          </header>
          <div class="providers">
            <button
              v-for="p in providers"
              :key="p.id"
              class="provider"
              :class="{ active: selectedProviderId === p.id }"
              @click="pickProvider(p)"
            >
              <span class="provider-name">{{ p.name }}</span>
              <span class="provider-desc">{{ p.description }}</span>
              <span v-if="p.consoleUrl" class="provider-link">{{ p.consoleUrl.replace('https://', '') }}</span>
              <span v-if="selectedProviderId === p.id" class="provider-check"><Icon name="check" :size="13" /></span>
            </button>
          </div>
        </section>

        <!-- 贰 · 连接 -->
        <section class="block">
          <header class="block-head">
            <span class="block-no">貳</span>
            <div><h2>连接</h2><p>Key 只会以 AES-GCM 密文落库，界面与接口只回显掩码。</p></div>
          </header>
          <div class="conn-grid">
            <V3Field label="接口地址（Base URL）" required>
              <V3Input v-model="form.baseUrl" placeholder="https://api.deepseek.com/v1" />
            </V3Field>
            <V3Field label="API Key" :hint="hasStoredKey ? `已保存 ${keyMask}，留空表示沿用` : '首次接入必须填写'">
              <V3Input v-model="form.apiKey" type="password" :placeholder="hasStoredKey ? '留空 = 沿用已存 Key' : 'sk-…'" />
            </V3Field>
            <V3Field label="默认模型" required hint="写作角色可以在下面单独覆盖">
              <select v-model="form.model" class="select">
                <option value="" disabled>选择一个模型</option>
                <option v-for="m in modelOptions" :key="m.id" :value="m.id">
                  {{ m.name || m.id }}{{ m.contextLimit ? `（${fmtContext(m.contextLimit)} 上下文）` : '' }}
                </option>
              </select>
            </V3Field>
          </div>
          <div class="conn-actions">
            <V3Button variant="glass" :loading="fetchingModels" @click="loadModels(false)">
              <template #icon><Icon name="search" :size="14" /></template>
              获取可用模型
            </V3Button>
            <V3Button variant="outline" :loading="testing" @click="testConnection">测试连接</V3Button>
            <V3Button :loading="saving" @click="saveConnection">保存并生效</V3Button>
            <span v-if="testingReply" class="test-reply">连接回复：{{ testingReply }}</span>
          </div>
        </section>

        <!-- 叁 · 模型库 -->
        <section class="block">
          <header class="block-head">
            <span class="block-no">叁</span>
            <div>
              <h2>模型库</h2>
              <p>关掉用不上的模型，选择器里就不会翻三十项。至少保留一个。</p>
            </div>
          </header>
          <div class="model-toolbar">
            <input v-model="modelKeyword" class="search" placeholder="搜索模型…" />
            <span class="model-count">{{ enabledModels.length }} / {{ models.length }} 启用</span>
          </div>
          <div v-if="!models.length" class="empty-line">
            还没有模型列表。点上面的「获取可用模型」，或在连接里手动填写模型名。
          </div>
          <div v-else class="model-grid">
            <div v-for="m in filteredModels" :key="m.id" class="model-row" :class="{ off: m.enabled === false }">
              <button class="switch" :class="{ on: m.enabled !== false }" @click="toggleModel(m)" :aria-label="`启用 ${m.id}`">
                <i></i>
              </button>
              <span class="model-id">{{ m.id }}</span>
              <span class="model-meta">{{ fmtContext(m.contextLimit) }} 上下文</span>
              <span v-if="m.vision" class="model-vision">识图</span>
              <span v-if="m.inputCost != null" class="model-price">入 {{ fmtCost(m.inputCost) }} / 出 {{ fmtCost(m.outputCost) }}</span>
              <span v-if="m.reasoningLevels?.length" class="model-effort">思考 {{ m.reasoningLevels.join('/') }}</span>
            </div>
          </div>
        </section>

        <!-- 肆 · 双 Agent 模型 -->
        <section class="block">
          <header class="block-head">
            <span class="block-no">肆</span>
            <div>
              <h2>双 Agent 模型</h2>
              <p>策划求稳、写作求文笔。两个角色可以用同一家服务商的不同模型，也可以各自覆盖地址与 Key。</p>
            </div>
          </header>
          <div class="roles">
            <div v-for="profile in profiles" :key="profile.role" class="role-card">
              <header class="role-head">
                <span class="role-seal">{{ profile.role === 'PLANNER' ? '策' : '文' }}</span>
                <div>
                  <h3>{{ roleLabel(profile.role) }}</h3>
                  <p>{{ roleHint(profile.role) }}</p>
                </div>
                <V3Tag tone="gold">上下文 {{ fmtContext(profile.contextLimit) }}</V3Tag>
              </header>

              <V3Field label="模型" required hint="可直接输入该服务商的模型 ID，也可以拉取本角色服务商的模型">
                <div class="role-model-row">
                  <input
                    v-model="profileDrafts[profile.role].model"
                    class="select"
                    :list="`role-models-${profile.role}`"
                    placeholder="例如 deepseek-v4-flash"
                  />
                  <V3Button
                    v-if="profileDrafts[profile.role].useOwnConnection"
                    variant="glass"
                    size="sm"
                    :loading="roleFetching[profile.role]"
                    @click="fetchRoleModels(profile)"
                  >
                    拉取
                  </V3Button>
                </div>
                <datalist :id="`role-models-${profile.role}`">
                  <option v-for="m in roleModelOptions(profile.role)" :key="m.id" :value="m.id">{{ m.name || m.id }}</option>
                </datalist>
              </V3Field>

              <div class="role-conn" :class="{ active: profileDrafts[profile.role].useOwnConnection }">
                <label class="conn-switch">
                  <input v-model="profileDrafts[profile.role].useOwnConnection" type="checkbox" />
                  <span>独立连接（服务商 / Key）</span>
                </label>
                <span class="conn-state">
                  {{ profileDrafts[profile.role].useOwnConnection
                    ? '本角色使用自己的接口地址与 Key'
                    : '沿用全局连接' }}
                </span>
              </div>
              <template v-if="profileDrafts[profile.role].useOwnConnection">
                <V3Field label="接口地址" required>
                  <V3Input v-model="profileDrafts[profile.role].baseUrl" placeholder="https://api.example.com/v1" />
                </V3Field>
                <V3Field
                  label="API Key"
                  :hint="profileDrafts[profile.role].ownKey
                    ? '本角色已保存 Key，留空表示沿用'
                    : '独立连接必须填写该服务商的 Key'"
                >
                  <V3Input v-model="profileDrafts[profile.role].apiKey" type="password"
                           :placeholder="profileDrafts[profile.role].ownKey ? '留空 = 沿用已存 Key' : 'sk-…'" />
                </V3Field>
              </template>

              <div class="role-row">
                <V3Field label="温度" :hint="String(profileDrafts[profile.role].temperature)">
                  <input v-model.number="profileDrafts[profile.role].temperature" class="range" type="range" min="0" max="1.5" step="0.05" />
                </V3Field>
                <V3Field label="最大步数">
                  <input v-model.number="profileDrafts[profile.role].maxSteps" class="num" type="number" min="1" max="30" />
                </V3Field>
              </div>

              <V3Field label="思考强度" hint="空 = 服务商默认">
                <select v-model="profileDrafts[profile.role].reasoningEffort" class="select">
                  <option value="">服务商默认</option>
                  <option v-for="level in reasoningLevelsOf(profileDrafts[profile.role].model)" :key="level" :value="level">{{ level }}</option>
                  <option v-if="profileDrafts[profile.role].reasoningEffort && !reasoningLevelsOf(profileDrafts[profile.role].model).includes(profileDrafts[profile.role].reasoningEffort)"
                          :value="profileDrafts[profile.role].reasoningEffort">
                    {{ profileDrafts[profile.role].reasoningEffort }}（自定义）
                  </option>
                </select>
              </V3Field>

              <div class="role-foot">
                <span class="role-hint">
                  {{ profileDrafts[profile.role].useOwnConnection
                    ? '独立连接：保存后本角色不再使用全局地址/Key'
                    : '接口与 Key 沿用全局设置' }}
                </span>
                <V3Button size="sm" @click="saveProfile(profile.role)">保存配置</V3Button>
              </div>
            </div>
          </div>
        </section>

        <!-- 伍 · 用量与花销 -->
        <section class="block">
          <header class="block-head">
            <span class="block-no">伍</span>
            <div>
              <h2>用量与花销</h2>
              <p>Agent 的花销没有"提交"动作，是在对话里悄悄累积的。这里把它变成看得见的数字（美元，按公开定价估算）。</p>
            </div>
          </header>
          <div v-if="usage" class="usage-summary">
            <div class="usage-stat">
              <b>{{ usage.summary?.calls ?? 0 }}</b>
              <span>近 {{ usage.summary?.days ?? 30 }} 天调用次数</span>
            </div>
            <div class="usage-stat">
              <b>{{ fmtCost(usage.summary?.cost) }}</b>
              <span>估算花费（{{ usage.currency || 'USD' }}）</span>
            </div>
          </div>
          <div v-if="usage?.recent?.length" class="usage-list">
            <div v-for="row in usage.recent" :key="row.id" class="usage-row">
              <V3Tag :tone="row.agentRole === 'WRITER' ? 'gold' : 'line'">
                {{ row.agentRole === 'WRITER' ? '写作' : '策划' }}
              </V3Tag>
              <span class="usage-model">{{ row.model }}</span>
              <span class="usage-tokens">{{ row.promptTokens }} 入 / {{ row.completionTokens }} 出</span>
              <span class="usage-cost">{{ fmtCost(row.estimatedCost) }}</span>
              <span class="usage-time">{{ (row.createdAt || '').replace('T', ' ').slice(5, 16) }}</span>
            </div>
          </div>
          <p v-else class="empty-line">还没有调用记录。开始写作后，这里会显示每次运行的花销。</p>
          <p class="usage-note">
            历史记录里出现 $0 的，是旧版本对未知模型按零计费留下的；新运行会按兜底单价估算。
          </p>
        </section>

        <section class="block danger-block">
          <header class="block-head">
            <span class="block-no">陆</span>
            <div><h2>演示模式</h2><p>清除界面保存的接入配置，退回环境变量 / 内置演示模式。不会删除你的书。</p></div>
          </header>
          <V3Button variant="danger" size="sm" @click="showReset = true">恢复演示模式</V3Button>
        </section>
      </template>
    </div>

    <V3Modal :visible="showReset" title="恢复演示模式" width="460px" @close="showReset = false">
      <p class="modal-text">确定清除界面保存的接口地址、模型与 Key 吗？之后会回到环境变量或内置演示模式，Agent 将无法调用真实模型。</p>
      <template #footer>
        <V3Button variant="ghost" @click="showReset = false">取消</V3Button>
        <V3Button variant="danger" :loading="resetting" @click="doReset">确认恢复</V3Button>
      </template>
    </V3Modal>
  </div>
</template>

<style scoped>
.settings-page { flex: 1; min-width: 0; display: flex; }
.settings { flex: 1; min-width: 0; overflow-y: auto; padding: 40px 48px 90px; }
.settings-head { max-width: 900px; }
.kicker { margin: 0 0 14px; font-size: 10px; letter-spacing: 0.42em; color: var(--v3-gold); }
.settings-head h1 { margin: 0; font-family: var(--v3-font-serif); font-size: clamp(28px, 3.4vw, 38px); font-weight: 600; letter-spacing: 0.08em; }
.sub { max-width: 720px; margin: 12px 0 0; font-size: 12.5px; line-height: 1.9; color: var(--v3-text-muted); }
.status-row { display: flex; align-items: center; gap: 10px; margin-top: 16px; }
.masked { font-family: var(--v3-font-mono); font-size: 11px; color: var(--v3-text-faint); }
.loading { padding: 40px 0; color: var(--v3-text-muted); font-size: 13px; }

.block { max-width: 980px; margin-top: 42px; padding-top: 26px; border-top: 1px solid var(--v3-hairline); }
.block-head { display: flex; gap: 16px; margin-bottom: 18px; }
.block-no { flex-shrink: 0; font-family: var(--v3-font-serif); font-size: 18px; color: var(--v3-gold); opacity: 0.85; }
.block-head h2 { margin: 0 0 6px; font-family: var(--v3-font-serif); font-size: 17px; letter-spacing: 0.08em; }
.block-head p { margin: 0; font-size: 12px; line-height: 1.85; color: var(--v3-text-muted); }

.providers { display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 10px; }
.provider {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
  padding: 14px 16px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  color: var(--v3-text-soft);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--v3-dur-2), background var(--v3-dur-2), transform var(--v3-dur-2) var(--v3-ease-spring);
}
.provider:hover { border-color: var(--v3-hairline-gold); transform: translateY(-1px); }
.provider.active { border-color: var(--v3-hairline-gold); background: var(--v3-glow-gold); }
.provider-name { font-family: var(--v3-font-serif); font-size: 14.5px; letter-spacing: 0.04em; color: var(--v3-text); }
.provider-desc { font-size: 11px; line-height: 1.65; color: var(--v3-text-faint); }
.provider-link { font-size: 10px; color: var(--v3-gold); opacity: 0.8; }
.provider-check { position: absolute; top: 12px; right: 12px; color: var(--v3-gold-bright); }

.conn-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
/*
 * 网格里两个字段是"同一行"，不该再有纵向兄弟间距。
 * V3Field 自带 `.v3-field + .v3-field { margin-top: 16px }`（竖排表单用），
 * 在 grid 里会把右列的标签与输入框整体压下去 16px，看起来"两个框高度不一致"。
 */
.conn-grid :deep(.v3-field + .v3-field) { margin-top: 0; }
.conn-grid :deep(.v3-field):last-child { grid-column: 1 / -1; }
.select, .num, .search {
  width: 100%;
  height: 42px;
  padding: 0 12px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  color: var(--v3-text);
  font-family: var(--v3-font-sans);
  font-size: 13px;
  outline: none;
}
.select:focus, .num:focus, .search:focus { border-color: var(--v3-gold); box-shadow: 0 0 0 3px var(--v3-glow-gold); }
.select option { background: var(--v3-surface-solid); color: var(--v3-text); }
.conn-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 18px; }
.test-reply { font-size: 11.5px; color: var(--v3-jade); max-width: 420px; }

.model-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.search { max-width: 300px; }
.model-count { font-size: 11px; color: var(--v3-text-faint); }
.empty-line { padding: 18px 0; font-size: 12px; color: var(--v3-text-faint); }
.model-grid { display: flex; flex-direction: column; gap: 6px; }
.model-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 12px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  font-size: 12px;
}
.model-row.off { opacity: 0.5; }
.switch {
  width: 34px;
  height: 18px;
  flex-shrink: 0;
  padding: 2px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-pill);
  background: var(--v3-surface-2);
  cursor: pointer;
  transition: background var(--v3-dur-2), border-color var(--v3-dur-2);
}
.switch i { display: block; width: 12px; height: 12px; border-radius: 50%; background: var(--v3-text-faint); transition: transform var(--v3-dur-2) var(--v3-ease-spring), background var(--v3-dur-2); }
.switch.on { background: var(--v3-glow-gold); border-color: var(--v3-hairline-gold); }
.switch.on i { transform: translateX(15px); background: var(--v3-gold); }
.model-id { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: var(--v3-font-mono); font-size: 11.5px; color: var(--v3-text-soft); }
.model-meta { font-size: 10.5px; color: var(--v3-text-faint); }
.model-vision {
  padding: 1px 6px;
  border: 1px solid var(--v3-hairline-gold);
  border-radius: var(--v3-r-pill);
  color: var(--v3-gold-bright);
  font-size: 9px;
}
.model-price { font-size: 10.5px; color: var(--v3-text-faint); }
.model-effort { font-size: 10px; color: var(--v3-gold); }

.roles { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.role-card { padding: 18px 20px; border: 1px solid var(--v3-hairline); border-radius: var(--v3-r-md); background: var(--v3-surface-1); }
.role-head { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
.role-seal {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  border: 1px solid var(--v3-cinnabar);
  border-radius: 7px;
  color: var(--v3-cinnabar);
  font-family: var(--v3-font-serif);
  font-size: 15px;
  transform: rotate(-3deg);
}
.role-head h3 { margin: 0 0 4px; font-family: var(--v3-font-serif); font-size: 15px; letter-spacing: 0.06em; }
.role-head p { margin: 0; font-size: 11px; line-height: 1.7; color: var(--v3-text-faint); }
.role-head :deep(.v3-tag) { margin-left: auto; }
.role-row { display: grid; grid-template-columns: 1fr 110px; gap: 12px; margin-top: 14px; }
/* 同上：同一行的两个字段不要再叠纵向兄弟间距，否则右边会低 16px */
.role-row :deep(.v3-field + .v3-field) { margin-top: 0; }
.range { width: 100%; accent-color: var(--v3-gold); }
.role-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 16px; }
.role-hint { font-size: 10.5px; color: var(--v3-text-faint); }
.role-conn {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 4px 0 12px;
  padding: 8px 10px;
  border: 1px dashed var(--v3-hairline);
  border-radius: var(--v3-r-xs);
  background: var(--v3-inset);
}
.role-conn.active { border-color: var(--v3-hairline-gold); background: var(--v3-glow-gold); }
.role-model-row { display: flex; align-items: center; gap: 8px; }
.role-model-row .select { flex: 1; min-width: 0; }
.conn-switch { display: flex; align-items: center; gap: 7px; font-size: 12px; color: var(--v3-text-soft); cursor: pointer; }
.conn-switch input { accent-color: var(--v3-gold); }
.conn-state { font-size: 10.5px; color: var(--v3-text-faint); text-align: right; }

.usage-summary { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; max-width: 520px; margin-bottom: 16px; }
.usage-stat { padding: 14px 18px; border: 1px solid var(--v3-hairline); border-radius: var(--v3-r-sm); background: var(--v3-surface-1); }
.usage-stat b { display: block; font-family: var(--v3-font-serif); font-size: 24px; font-weight: 600; color: var(--v3-text); }
.usage-stat span { font-size: 10.5px; letter-spacing: 0.1em; color: var(--v3-text-faint); }
.usage-list { display: flex; flex-direction: column; gap: 5px; }
.usage-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-xs);
  background: var(--v3-surface-1);
  font-size: 11.5px;
  color: var(--v3-text-muted);
}
.usage-model { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: var(--v3-font-mono); font-size: 11px; }
.usage-tokens { font-size: 10.5px; color: var(--v3-text-faint); }
.usage-cost { font-family: var(--v3-font-mono); font-size: 11px; color: var(--v3-gold-bright); }
.usage-time { font-size: 10px; color: var(--v3-text-faint); }
.usage-note { margin: 10px 0 0; font-size: 10.5px; color: var(--v3-text-faint); }
.danger-block { max-width: 980px; }
.modal-text { margin: 0; font-size: 13px; line-height: 1.95; color: var(--v3-text-soft); }

@media (max-width: 1100px) {
  .roles { grid-template-columns: 1fr; }
}
@media (max-width: 900px) {
  .settings { padding: 28px 20px 70px; }
  .conn-grid { grid-template-columns: 1fr; }
  .conn-grid :deep(.v3-field):last-child { grid-column: auto; }
}
</style>
