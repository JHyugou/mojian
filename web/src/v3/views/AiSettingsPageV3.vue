<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import Icon from '../../components/Icon.vue'
import V3Button from '../../components/v3/V3Button.vue'
import V3Field from '../../components/v3/V3Field.vue'
import V3Input from '../../components/v3/V3Input.vue'
import V3Modal from '../../components/v3/V3Modal.vue'
import V3Tag from '../../components/v3/V3Tag.vue'
import WorkspaceShell from '../layout/WorkspaceShell.vue'
import { useV3Notify } from '../notify'
import {
  fetchModels, getAiStatus, listProviders, resetAiConfig,
  saveAiConfig, saveEnabledModels, testAiConnection
} from '../../api/aiConfig'
import { listAgentProfiles, updateAgentProfile } from '../../api/agent'

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
const showReset = ref(false)
const resetting = ref(false)

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
        profileDrafts[profile.role] = {
          model: profile.model || '',
          temperature: profile.temperature ?? 0.8,
          reasoningEffort: profile.reasoningEffort || '',
          maxSteps: profile.maxSteps ?? (profile.role === 'WRITER' ? 6 : 10)
        }
      }
    }
    if (form.baseUrl) await loadModels(true)
  } catch (e) {
    notify.error(e.message || '读取模型设置失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

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
  try {
    const updated = await updateAgentProfile(role, {
      model: draft.model || null,
      temperature: draft.temperature,
      reasoningEffort: draft.reasoningEffort ?? '',
      maxSteps: draft.maxSteps
    })
    profiles.value = profiles.value.map((p) => (p.role === role ? updated : p))
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
  <WorkspaceShell breadcrumb="模型接入">
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

              <V3Field label="模型" required>
                <select v-model="profileDrafts[profile.role].model" class="select">
                  <option value="" disabled>选择一个模型</option>
                  <option v-for="m in modelOptions" :key="m.id" :value="m.id">{{ m.name || m.id }}</option>
                  <option v-if="profile.model && !modelOptions.some(m => m.id === profile.model)" :value="profile.model">
                    {{ profile.model }}
                  </option>
                </select>
              </V3Field>

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
                  <option v-for="level in reasoningLevelsOf(profile.model)" :key="level" :value="level">{{ level }}</option>
                  <option v-if="profileDrafts[profile.role].reasoningEffort && !reasoningLevelsOf(profile.model).includes(profileDrafts[profile.role].reasoningEffort)"
                          :value="profileDrafts[profile.role].reasoningEffort">
                    {{ profileDrafts[profile.role].reasoningEffort }}（自定义）
                  </option>
                </select>
              </V3Field>

              <div class="role-foot">
                <span class="role-hint">
                  {{ profile.baseUrlSource === 'GLOBAL' ? '接口与 Key 沿用全局设置' : '使用本角色单独配置' }}
                </span>
                <V3Button size="sm" @click="saveProfile(profile.role)">保存配置</V3Button>
              </div>
            </div>
          </div>
        </section>

        <section class="block danger-block">
          <header class="block-head">
            <span class="block-no">伍</span>
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
  </WorkspaceShell>
</template>

<style scoped>
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
.range { width: 100%; accent-color: var(--v3-gold); }
.role-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 16px; }
.role-hint { font-size: 10.5px; color: var(--v3-text-faint); }
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
