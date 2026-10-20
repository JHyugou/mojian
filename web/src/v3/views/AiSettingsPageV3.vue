<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import Icon from '../../components/Icon.vue'
import V3Button from '../../components/v3/V3Button.vue'
import V3Field from '../../components/v3/V3Field.vue'
import V3Input from '../../components/v3/V3Input.vue'
import V3Tag from '../../components/v3/V3Tag.vue'
import { useV3Notify } from '../notify'
import {
  deleteProviderConfig, getProviderConfigs, listProviders, refreshProviderModels,
  saveProviderConfig
} from '../../api/aiConfig'
import { getAgentUsage, listAgentProfiles, updateAgentProfile } from '../../api/agent'

const notify = useV3Notify()

const loading = ref(false)
const presets = ref([])
const providerConfigs = ref([])
const profiles = ref([])
const usage = ref(null)
const modelKeyword = ref('')
const savingProvider = ref(false)
const refreshingProviderId = ref(null)
const savingProfile = ref('')

/** 正在编辑的服务商；id=null 表示新增；active=false 表示还没打开编辑区 */
const editing = reactive({
  id: null, name: '', presetKey: '', baseUrl: '', apiKey: '', existing: false, active: false
})
const profileDrafts = reactive({})

const configuredCount = computed(() => providerConfigs.value.length)
const totalModelCount = computed(() =>
  providerConfigs.value.reduce((sum, p) => sum + (p.models?.length || 0), 0))

/** 已配置的预设不再出现在"未配置"列表；自定义配置按 baseUrl 去重 */
const unconfiguredPresets = computed(() => presets.value.filter((preset) => {
  if (preset.id === 'custom') return true
  return !providerConfigs.value.some((config) =>
    config.presetKey === preset.id ||
    (preset.baseUrl && config.baseUrl?.replace(/\/+$/, '') === preset.baseUrl.replace(/\/+$/, '')))
}))

const filteredModelGroups = computed(() => {
  const q = modelKeyword.value.trim().toLowerCase()
  return providerConfigs.value
    .map((provider) => ({
      provider,
      models: (provider.models || []).filter((model) =>
        !q || `${model.id} ${model.name || ''}`.toLowerCase().includes(q))
    }))
    .filter((group) => group.models.length || (!q && group.provider.models?.length === 0))
})

const allModels = computed(() => providerConfigs.value.flatMap((p) =>
  (p.models || []).map((m) => ({ ...m, providerId: p.id, providerName: p.name }))))

function fmtContext(n) {
  if (!n) return '上下文未知'
  return n >= 1000000 ? `${(n / 1000000).toFixed(0)}M` : `${Math.round(n / 1000)}K`
}
function fmtCost(n) {
  return n == null ? '—' : `$${n}`
}
function roleLabel(role) {
  return role === 'PLANNER' ? '策划 Agent' : '写作 Agent'
}
function roleHint(role) {
  return role === 'PLANNER'
    ? '管框架：要稳、要能读完几十万字设定'
    : '写正文：要文笔与发散，通常用更强的模型'
}
function resetProfileDraft(profile) {
  profileDrafts[profile.role] = {
    providerId: profile.providerId || providerConfigs.value[0]?.id || null,
    model: profile.model || '',
    temperature: profile.temperature ?? 0.8,
    reasoningEffort: profile.reasoningEffort || '',
    maxSteps: profile.maxSteps ?? (profile.role === 'WRITER' ? 6 : 10)
  }
}

async function load() {
  loading.value = true
  try {
    const [presetResult, configResult, profileResult, usageResult] = await Promise.allSettled([
      listProviders(), getProviderConfigs(), listAgentProfiles(), getAgentUsage()
    ])
    if (presetResult.status === 'fulfilled') presets.value = presetResult.value || []
    if (configResult.status === 'fulfilled') providerConfigs.value = configResult.value || []
    if (profileResult.status === 'fulfilled') {
      profiles.value = profileResult.value || []
      profiles.value.forEach(resetProfileDraft)
    }
    if (usageResult.status === 'fulfilled') usage.value = usageResult.value
    // 迁移或新加的服务商可能还没有缓存模型；静默补一次，模型库打开就有内容
    await autoFillProviderModels()
  } catch (e) {
    notify.error(e.message || '读取模型设置失败')
  } finally {
    loading.value = false
  }
}

async function autoFillProviderModels() {
  const emptyProviders = providerConfigs.value.filter(
    (provider) => provider.apiKeyConfigured && !(provider.models?.length))
  await Promise.all(emptyProviders.map(async (provider) => {
    try {
      const updated = await refreshProviderModels(provider.id)
      providerConfigs.value = providerConfigs.value.map((p) => p.id === updated.id ? updated : p)
    } catch {
      // 网络/Key 问题不阻塞设置页，用户可手动点刷新看到错误
    }
  }))
}

onMounted(load)

function beginPreset(preset) {
  const existing = providerConfigs.value.find((config) =>
    (config.presetKey && config.presetKey === preset.id) ||
    (preset.baseUrl && config.baseUrl?.replace(/\/+$/, '') === preset.baseUrl.replace(/\/+$/, '')))
  if (existing) {
    editProvider(existing)
    return
  }
  Object.assign(editing, {
    id: null,
    name: preset.name,
    presetKey: preset.id,
    baseUrl: preset.baseUrl || '',
    apiKey: '',
    existing: false,
    active: true
  })
}

function newCustomProvider() {
  Object.assign(editing, {
    id: null, name: '自定义服务商', presetKey: 'custom', baseUrl: '', apiKey: '', existing: false,
    active: true
  })
}

function editProvider(config) {
  Object.assign(editing, {
    id: config.id,
    name: config.name,
    presetKey: config.presetKey || '',
    baseUrl: config.baseUrl,
    apiKey: '',
    existing: true,
    active: true
  })
}

async function saveProvider() {
  if (!editing.baseUrl.trim()) {
    notify.error('接口地址不能为空')
    return
  }
  if (!editing.existing && !editing.apiKey.trim()) {
    notify.error('新服务商必须填写 API Key')
    return
  }
  savingProvider.value = true
  try {
    const saved = await saveProviderConfig({
      id: editing.id,
      name: editing.name,
      presetKey: editing.presetKey,
      baseUrl: editing.baseUrl.trim(),
      apiKey: editing.apiKey.trim() ? editing.apiKey.trim() : null
    })
    providerConfigs.value = await getProviderConfigs()
    editProvider(saved)
    notify.success(`「${saved.name}」已保存，可刷新模型`)
  } catch (e) {
    notify.error(e.message || '保存服务商失败')
  } finally {
    savingProvider.value = false
  }
}

async function refreshModels(config) {
  refreshingProviderId.value = config.id
  try {
    const updated = await refreshProviderModels(config.id)
    providerConfigs.value = providerConfigs.value.map((p) => p.id === updated.id ? updated : p)
    // 角色草稿如果指向这家服务商，模型列表要同步
    profiles.value.forEach((profile) => {
      const draft = profileDrafts[profile.role]
      if (draft && String(draft.providerId) === String(updated.id) &&
          updated.models?.length && !updated.models.some((m) => m.id === draft.model)) {
        draft.model = updated.models[0].id
      }
    })
    notify.success(`「${updated.name}」已更新 ${updated.models?.length || 0} 个模型`)
  } catch (e) {
    notify.error(e.message || '刷新模型失败')
  } finally {
    refreshingProviderId.value = null
  }
}

async function removeProvider(config) {
  if (!window.confirm(`确定删除服务商「${config.name}」？正在使用它的角色会无法调用模型。`)) return
  try {
    await deleteProviderConfig(config.id)
    providerConfigs.value = providerConfigs.value.filter((p) => p.id !== config.id)
    if (editing.id === config.id) {
      Object.assign(editing, {
        id: null, name: '', presetKey: '', baseUrl: '', apiKey: '', existing: false, active: false
      })
    }
    profiles.value.forEach(resetProfileDraft)
    notify.success('服务商已删除')
  } catch (e) {
    notify.error(e.message || '删除服务商失败')
  }
}

function providerById(id) {
  return providerConfigs.value.find((p) => String(p.id) === String(id))
}

function modelsOfProvider(providerId) {
  return providerById(providerId)?.models || []
}

function onRoleProviderChange(profile) {
  const draft = profileDrafts[profile.role]
  const models = modelsOfProvider(draft.providerId)
  if (models.length && !models.some((m) => m.id === draft.model)) {
    draft.model = models[0].id
  }
}

function reasoningLevelsOf(modelId) {
  return allModels.value.find((m) => m.id === modelId)?.reasoningLevels || []
}

async function saveProfile(role) {
  const draft = profileDrafts[role]
  if (!draft.providerId) {
    notify.error('先选择一个已配置的服务商')
    return
  }
  if (!draft.model) {
    notify.error('先选择模型')
    return
  }
  savingProfile.value = role
  try {
    const updated = await updateAgentProfile(role, {
      model: draft.model,
      providerId: draft.providerId,
      temperature: draft.temperature,
      reasoningEffort: draft.reasoningEffort ?? '',
      maxSteps: draft.maxSteps
    })
    profiles.value = profiles.value.map((p) => (p.role === role ? updated : p))
    resetProfileDraft(updated)
    notify.success(`${roleLabel(role)} 已使用「${providerById(draft.providerId)?.name || '服务商'}」`)
  } catch (e) {
    notify.error(e.message || '保存角色配置失败')
  } finally {
    savingProfile.value = ''
  }
}
</script>

<template>
  <div class="settings-page">
    <div class="settings v3-scroll">
      <header class="settings-head">
        <p class="kicker">MODELS</p>
        <h1>模型接入</h1>
        <p class="sub">
          先接入服务商，再把不同服务商的模型分配给策划 / 写作。可同时配置多家，不必二选一。
        </p>
        <div class="status-row">
          <V3Tag tone="jade">已配置 {{ configuredCount }} 家服务商</V3Tag>
          <V3Tag tone="line">{{ totalModelCount }} 个可用模型</V3Tag>
        </div>
      </header>

      <div v-if="loading" class="loading">正在读取设置…</div>

      <template v-else>
        <!-- 壹 · 服务商 -->
        <section class="block">
          <header class="block-head">
            <span class="block-no">壹</span>
            <div>
              <h2>服务商</h2>
              <p>已经配置 Key 的放上面；点击未配置的服务商即可接入。可以同时配置多家。</p>
            </div>
          </header>

          <div class="provider-group">
            <h3 class="group-title">已配置 · {{ configuredCount }}</h3>
            <div v-if="providerConfigs.length" class="provider-grid">
              <button
                v-for="config in providerConfigs"
                :key="config.id"
                class="provider-card configured"
                :class="{ active: editing.id === config.id }"
                @click="editProvider(config)"
              >
                <span class="provider-name">{{ config.name }}</span>
                <span class="provider-url">{{ config.baseUrl }}</span>
                <span class="provider-foot">
                  <span class="key-mask">Key {{ config.apiKeyMask || '已配置' }}</span>
                  <span class="model-count-chip">{{ config.models?.length || 0 }} 模型</span>
                </span>
                <span class="provider-check"><Icon name="check" :size="13" /></span>
              </button>
            </div>
            <p v-else class="empty-line">还没有配置任何服务商。从下面的预设开始，或添加自定义服务商。</p>
          </div>

          <div class="provider-group">
            <h3 class="group-title">未配置</h3>
            <div class="provider-grid">
              <button
                v-for="preset in unconfiguredPresets"
                :key="preset.id"
                class="provider-card"
                @click="beginPreset(preset)"
              >
                <span class="provider-name">{{ preset.name }}</span>
                <span class="provider-desc">{{ preset.description }}</span>
                <span class="provider-foot">
                  <span class="provider-url">{{ preset.baseUrl || '手动填写地址' }}</span>
                  <span class="provider-unconfigured">未接入</span>
                </span>
              </button>
            </div>
          </div>
        </section>

        <!-- 贰 · 连接配置 -->
        <section class="block">
          <header class="block-head">
            <span class="block-no">贰</span>
            <div>
              <h2>连接配置</h2>
              <p>每个服务商独立保存 Base URL 与 Key；Key 只以 AES-GCM 密文落库，界面只回显掩码。</p>
            </div>
          </header>

          <div v-if="!editing.active" class="editor-empty">
            从上面选择一个服务商，或点「添加自定义服务商」。
            <V3Button variant="glass" size="sm" @click="newCustomProvider">添加自定义服务商</V3Button>
          </div>

          <template v-else>
            <div class="editor-head">
              <div>
                <strong>{{ editing.existing ? '编辑服务商' : '接入新服务商' }}</strong>
                <span v-if="editing.existing" class="muted">留空 Key = 沿用已存 Key</span>
              </div>
              <button v-if="editing.existing" class="text-btn danger" @click="removeProvider(providerById(editing.id))">
                删除这个服务商
              </button>
            </div>
            <div class="conn-grid">
              <V3Field label="显示名">
                <V3Input v-model="editing.name" placeholder="例如 DeepSeek 官方、OpenCode Go" />
              </V3Field>
              <V3Field label="接口地址（Base URL）" required>
                <V3Input v-model="editing.baseUrl" placeholder="https://api.example.com/v1" />
              </V3Field>
              <V3Field
                label="API Key"
                :hint="editing.existing ? '已保存的 Key 不回显；留空表示沿用' : '新服务商必须填写'"
              >
                <V3Input v-model="editing.apiKey" type="password"
                         :placeholder="editing.existing ? '留空 = 沿用已存 Key' : 'sk-…'" />
              </V3Field>
            </div>
            <div class="conn-actions">
              <V3Button :loading="savingProvider" @click="saveProvider">保存服务商</V3Button>
              <V3Button
                v-if="editing.existing"
                variant="glass"
                :loading="refreshingProviderId === editing.id"
                @click="refreshModels(providerById(editing.id))"
              >
                刷新模型
              </V3Button>
            </div>
          </template>
        </section>

        <!-- 叁 · 模型库 -->
        <section class="block">
          <header class="block-head">
            <span class="block-no">叁</span>
            <div>
              <h2>模型库</h2>
              <p>所有已配置服务商的模型，按服务商分组。选择模型时不再被单一全局服务商限制。</p>
            </div>
          </header>
          <div class="model-toolbar">
            <input v-model="modelKeyword" class="search" placeholder="搜索模型…" />
            <span class="model-count">{{ filteredModelGroups.reduce((n, g) => n + g.models.length, 0) }} / {{ totalModelCount }}</span>
          </div>
          <div v-if="!providerConfigs.some(p => p.models?.length)" class="empty-line">
            还没有模型列表。先在连接配置里保存服务商，再点「刷新模型」。
          </div>
          <div v-else class="model-groups">
            <div v-for="group in filteredModelGroups" :key="group.provider.id" class="model-group">
              <header class="model-group-head">
                <div>
                  <strong>{{ group.provider.name }}</strong>
                  <span class="muted">{{ group.provider.baseUrl }}</span>
                </div>
                <span class="model-count-chip">{{ group.provider.models?.length || 0 }} 个模型</span>
              </header>
              <p v-if="!group.provider.models?.length" class="empty-line small">
                还没有拉取模型。回到连接配置刷新一次。
              </p>
              <div v-else class="model-grid">
                <div v-for="m in group.models" :key="m.id" class="model-row">
                  <span class="model-id">{{ m.id }}</span>
                  <span class="model-meta">{{ fmtContext(m.contextLimit) }} 上下文</span>
                  <span v-if="m.vision" class="model-vision">识图</span>
                  <span v-if="m.inputCost != null" class="model-price">入 {{ fmtCost(m.inputCost) }} / 出 {{ fmtCost(m.outputCost) }}</span>
                  <span v-if="m.reasoningLevels?.length" class="model-effort">思考 {{ m.reasoningLevels.join('/') }}</span>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- 肆 · 双 Agent 模型 -->
        <section class="block">
          <header class="block-head">
            <span class="block-no">肆</span>
            <div>
              <h2>双 Agent 模型</h2>
              <p>从已配置的服务商里为策划和写作分别选模型；两个角色没有绑定同一家服务商。</p>
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

              <V3Field label="服务商" required>
                <select
                  v-model="profileDrafts[profile.role].providerId"
                  class="select"
                  @change="onRoleProviderChange(profile)"
                >
                  <option :value="null" disabled>选择已配置的服务商</option>
                  <option v-for="config in providerConfigs" :key="config.id" :value="config.id">
                    {{ config.name }}（{{ config.models?.length || 0 }} 模型）
                  </option>
                </select>
              </V3Field>

              <V3Field label="模型" required :hint="modelsOfProvider(profileDrafts[profile.role].providerId).length ? '' : '先刷新该服务商的模型'">
                <select v-model="profileDrafts[profile.role].model" class="select">
                  <option value="" disabled>选择模型</option>
                  <option v-for="m in modelsOfProvider(profileDrafts[profile.role].providerId)" :key="m.id" :value="m.id">
                    {{ m.name || m.id }}
                  </option>
                  <option
                    v-if="profileDrafts[profile.role].model && !modelsOfProvider(profileDrafts[profile.role].providerId).some(m => m.id === profileDrafts[profile.role].model)"
                    :value="profileDrafts[profile.role].model"
                  >
                    {{ profileDrafts[profile.role].model }}（当前保存值）
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
                  <option v-for="level in reasoningLevelsOf(profileDrafts[profile.role].model)" :key="level" :value="level">{{ level }}</option>
                </select>
              </V3Field>

              <div class="role-foot">
                <span class="role-hint">
                  {{ profileDrafts[profile.role].providerId
                    ? `使用 ${providerById(profileDrafts[profile.role].providerId)?.name || '已选服务商'}`
                    : '尚未选择服务商' }}
                </span>
                <V3Button size="sm" :loading="savingProfile === profile.role" @click="saveProfile(profile.role)">
                  保存配置
                </V3Button>
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
              <p>每次 Agent 调用的 token 与估算费用；按实际使用的服务商和模型记录。</p>
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
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.settings-page { height: 100%; overflow: hidden; }
.settings { height: 100%; overflow-y: auto; padding: 48px clamp(24px, 7vw, 110px) 80px; }
.settings-head { max-width: 880px; margin-bottom: 36px; }
.kicker { margin: 0 0 14px; font-family: var(--v3-font-mono); font-size: 10px; letter-spacing: 0.24em; color: var(--v3-gold); }
.settings-head h1 { margin: 0 0 12px; font-family: var(--v3-font-serif); font-size: clamp(34px, 4.4vw, 54px); font-weight: 500; letter-spacing: 0.06em; color: var(--v3-text); }
.sub { margin: 0; max-width: 780px; font-size: 13px; line-height: 1.9; color: var(--v3-text-muted); }
.status-row { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 18px; }
.masked { font-family: var(--v3-font-mono); font-size: 10.5px; color: var(--v3-text-faint); }
.loading { padding: 60px 0; color: var(--v3-text-faint); }
.block { max-width: 1180px; margin: 0 auto 34px; padding: 24px 26px 26px; border: 1px solid var(--v3-hairline); border-radius: var(--v3-r-md); background: var(--v3-surface-1); }
.block-head { display: flex; align-items: flex-start; gap: 16px; margin-bottom: 20px; }
.block-no { flex-shrink: 0; width: 30px; padding-top: 2px; font-family: var(--v3-font-serif); font-size: 17px; color: var(--v3-gold); }
.block-head h2 { margin: 0 0 5px; font-family: var(--v3-font-serif); font-size: 22px; font-weight: 500; letter-spacing: 0.06em; }
.block-head p { margin: 0; font-size: 11.5px; line-height: 1.8; color: var(--v3-text-faint); }
.provider-group + .provider-group { margin-top: 24px; }
.group-title { margin: 0 0 11px; font-family: var(--v3-font-mono); font-size: 10px; letter-spacing: 0.16em; color: var(--v3-text-faint); text-transform: uppercase; }
.provider-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; }
.provider-card { position: relative; display: flex; flex-direction: column; align-items: flex-start; gap: 6px; min-height: 100px; padding: 14px 16px; border: 1px solid var(--v3-hairline-strong); border-radius: var(--v3-r-sm); background: var(--v3-surface-2); color: var(--v3-text); text-align: left; cursor: pointer; transition: border-color var(--v3-dur-2), background var(--v3-dur-2), transform var(--v3-dur-2); }
.provider-card:hover { border-color: var(--v3-hairline-gold); transform: translateY(-1px); }
.provider-card.configured { border-color: var(--v3-hairline-gold); background: var(--v3-glow-gold); }
.provider-card.active { box-shadow: 0 0 0 2px var(--v3-glow-gold); }
.provider-name { font-family: var(--v3-font-serif); font-size: 16px; letter-spacing: 0.04em; }
.provider-desc { font-size: 10.5px; line-height: 1.6; color: var(--v3-text-faint); }
.provider-url { font-family: var(--v3-font-mono); font-size: 9.5px; color: var(--v3-text-faint); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.provider-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; margin-top: auto; }
.key-mask { font-family: var(--v3-font-mono); font-size: 9.5px; color: var(--v3-jade); }
.model-count-chip { padding: 2px 7px; border-radius: var(--v3-r-pill); background: var(--v3-surface-solid); font-size: 9.5px; color: var(--v3-text-faint); }
.provider-unconfigured { font-size: 9.5px; color: var(--v3-gold); }
.provider-check { position: absolute; top: 11px; right: 12px; color: var(--v3-gold); }
.empty-line { margin: 4px 0; padding: 14px 0; font-size: 11.5px; line-height: 1.8; color: var(--v3-text-faint); }
.empty-line.small { padding: 6px 0; }
.editor-empty { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 16px; border: 1px dashed var(--v3-hairline-strong); border-radius: var(--v3-r-sm); font-size: 12px; color: var(--v3-text-faint); }
.editor-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; font-size: 13px; }
.editor-head .muted { margin-left: 10px; font-size: 10.5px; color: var(--v3-text-faint); }
.text-btn { border: 0; background: transparent; font-size: 11px; cursor: pointer; }
.text-btn.danger { color: var(--v3-cinnabar); }
.conn-grid { display: grid; grid-template-columns: 1fr 1.35fr 1.2fr; gap: 14px; }
.conn-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-top: 18px; }
.model-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.search { width: min(320px, 100%); height: 38px; padding: 0 12px; border: 1px solid var(--v3-hairline); border-radius: var(--v3-r-sm); background: var(--v3-surface-2); color: var(--v3-text); font-size: 12px; outline: none; }
.search:focus { border-color: var(--v3-gold); box-shadow: 0 0 0 3px var(--v3-glow-gold); }
.model-count { margin-left: auto; font-family: var(--v3-font-mono); font-size: 10px; color: var(--v3-text-faint); }
.model-groups { display: flex; flex-direction: column; gap: 14px; }
.model-group { padding: 12px 14px; border: 1px solid var(--v3-hairline); border-radius: var(--v3-r-sm); background: var(--v3-surface-2); }
.model-group-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.model-group-head .muted { margin-left: 10px; font-family: var(--v3-font-mono); font-size: 9.5px; color: var(--v3-text-faint); }
.model-grid { display: flex; flex-direction: column; gap: 5px; }
.model-row { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: var(--v3-r-xs); background: var(--v3-surface-1); font-size: 11px; }
.model-id { min-width: 190px; font-family: var(--v3-font-mono); color: var(--v3-text-soft); }
.model-meta, .model-price { color: var(--v3-text-faint); font-size: 10px; }
.model-vision { padding: 1px 6px; border: 1px solid var(--v3-hairline-gold); border-radius: var(--v3-r-pill); color: var(--v3-gold-bright); font-size: 9px; }
.model-effort { margin-left: auto; color: var(--v3-gold); font-size: 9.5px; }
.roles { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.role-card { padding: 18px 20px; border: 1px solid var(--v3-hairline); border-radius: var(--v3-r-md); background: var(--v3-surface-2); }
.role-head { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
.role-seal { display: grid; place-items: center; width: 34px; height: 34px; flex-shrink: 0; border: 1px solid var(--v3-cinnabar); border-radius: 7px; color: var(--v3-cinnabar); font-family: var(--v3-font-serif); font-size: 15px; transform: rotate(-3deg); }
.role-head h3 { margin: 0 0 4px; font-family: var(--v3-font-serif); font-size: 15px; letter-spacing: 0.06em; }
.role-head p { margin: 0; font-size: 11px; line-height: 1.7; color: var(--v3-text-faint); }
.role-head :deep(.v3-tag) { margin-left: auto; }
.role-row { display: grid; grid-template-columns: 1fr 110px; gap: 12px; margin-top: 14px; }
.role-row :deep(.v3-field + .v3-field) { margin-top: 0; }
.range { width: 100%; accent-color: var(--v3-gold); }
.role-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 16px; }
.role-hint { font-size: 10.5px; color: var(--v3-text-faint); }
.select, .num { width: 100%; height: 42px; padding: 0 12px; border: 1px solid var(--v3-hairline-strong); border-radius: var(--v3-r-sm); background: var(--v3-surface-1); color: var(--v3-text); font-size: 13px; outline: none; }
.select:focus, .num:focus { border-color: var(--v3-gold); box-shadow: 0 0 0 3px var(--v3-glow-gold); }
.select option { background: var(--v3-surface-solid); color: var(--v3-text); }
.usage-summary { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; max-width: 520px; margin-bottom: 16px; }
.usage-stat { padding: 14px 18px; border: 1px solid var(--v3-hairline); border-radius: var(--v3-r-sm); background: var(--v3-surface-2); }
.usage-stat b { display: block; font-family: var(--v3-font-serif); font-size: 26px; font-weight: 500; color: var(--v3-gold-bright); }
.usage-stat span { font-size: 10px; color: var(--v3-text-faint); }
.usage-list { display: flex; flex-direction: column; border-top: 1px solid var(--v3-hairline); }
.usage-row { display: flex; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px solid var(--v3-hairline); font-size: 10.5px; color: var(--v3-text-muted); }
.usage-model { min-width: 150px; font-family: var(--v3-font-mono); color: var(--v3-text-soft); }
.usage-cost { margin-left: auto; color: var(--v3-gold); }
.usage-time { min-width: 90px; color: var(--v3-text-faint); }
@media (max-width: 900px) {
  .conn-grid { grid-template-columns: 1fr; }
  .roles { grid-template-columns: 1fr; }
}
</style>
