<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from '../Icon.vue'
import { listAgentProfiles, updateAgentProfile } from '../../api/agent'
import { fetchModels, listProviders } from '../../api/aiConfig'
import { useMessageStore } from '../../stores/message'

/**
 * 模型选择器（输入框下方那一条）。
 *
 * <p>照 DSH 的做法：一个药丸按钮显示"当前模型 + 上下文 + 思考强度"，
 * 点开是两层——先选模型，再选这档模型支持的思考强度。
 *
 * <p>两个设计要点：
 * <ul>
 *   <li><b>可选档位跟着模型走</b>：各家值域不一样（none/low/medium/high 或 xhigh/max），
 *       写死一套就会在别的模型上给出非法值</li>
 *   <li><b>只改当前角色</b>：策划和写作是两个独立角色，各有各的模型配置。
 *       在哪个面板里点，就只改哪个角色 —— 不会一次把两边都改掉</li>
 * </ul>
 */
const props = defineProps({
  /** PLANNER 或 WRITER */
  role: { type: String, default: 'PLANNER' }
})

const message = useMessageStore()

const profiles = ref([])
const models = ref([])
const providers = ref([])
const loading = ref(true)
const saving = ref(false)

const open = ref(false)
/** 面板内部的第二层：'' 主菜单 / 'models' / 'effort' */
const view = ref('')
const keyword = ref('')

const profile = computed(() => profiles.value.find((p) => p.role === props.role) || null)
const currentModel = computed(() => profile.value?.model || '')
const currentEffort = computed(() => profile.value?.reasoningEffort || '')

/*
 * 只留下"用户启用过的"模型。
 *
 * 一个网关常有几十个模型（OpenCode Go 目录里 32 个），全列出来就没法用了 ——
 * 在「模型接入」页勾选的那几个才是他真正会用的。
 * 但当前正在用的那个无论如何要留着：万一它被关掉了，至少还能看见自己在用什么。
 */
const available = computed(() => {
  const list = models.value.filter((m) => m.enabled !== false || m.id === currentModel.value)
  return list.length ? list : models.value
})

const currentInfo = computed(() => models.value.find((m) => m.id === currentModel.value) || null)
const levels = computed(() => currentInfo.value?.reasoningLevels || [])

/**
 * 厂商名：用来给模型列表分组。
 *
 * <p>从接口地址反查预设名 —— 列表本身只带模型信息，不带"这是谁家的"。
 * 现在同时只会接一家，所以只有一组；但分组结构留着，
 * 将来支持多厂商时不用再改这块。
 */
const providerName = computed(() => {
  const host = hostOf(profile.value?.baseUrl)
  if (!host) return '模型'
  const matched = providers.value.find((p) => hostOf(p.baseUrl) === host)
  return matched ? matched.name : host
})

function hostOf(url) {
  try {
    return url ? new URL(url).host.toLowerCase() : null
  } catch {
    return null
  }
}

/** 列表过滤：模型 id 和显示名都参与匹配，否则搜 "flash" 可能漏掉别名 */
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return available.value
  return available.value.filter((m) =>
    m.id.toLowerCase().includes(kw) || (m.name || '').toLowerCase().includes(kw))
})

const shortName = computed(() => {
  const info = currentInfo.value
  const raw = info?.name || currentModel.value
  // 目录里的名字常常带厂商前缀（如 deepseek-ai/DeepSeek-V4.1-Flash），列表里留着，
  // 但收缩状态下只显示最后一段，免得把底部栏撑得很长
  return raw.includes('/') ? raw.slice(raw.lastIndexOf('/') + 1) : raw
})

const contextLabel = computed(() => {
  const limit = currentInfo.value?.contextLimit
  if (!limit) return ''
  return limit >= 1000000
    ? `${Math.round(limit / 100000) / 10}M`
    : `${Math.round(limit / 1000)}K`
})

const EFFORT_NAMES = {
  none: '不思考',
  minimal: '极简',
  low: '低',
  medium: '中',
  high: '高',
  xhigh: '极高',
  max: '最大'
}

const effortLabel = computed(() => {
  if (!currentEffort.value) return ''
  return EFFORT_NAMES[currentEffort.value] || currentEffort.value
})

const effortName = (level) => EFFORT_NAMES[level] || level

async function load() {
  loading.value = true
  try {
    profiles.value = (await listAgentProfiles()) || []
    // 预设用来反查"当前接的是哪一家"，好给模型列表加分组标题
    try {
      providers.value = (await listProviders()) || []
    } catch (e) {
      console.warn('加载厂商预设失败', e)
    }
    const baseUrl = profile.value?.baseUrl
    if (baseUrl) {
      const result = await fetchModels({ baseUrl })
      models.value = result?.models || []
    }
  } catch (e) {
    // 拿不到目录不该让选择器消失：至少还能显示当前配置
    console.warn('加载模型列表失败', e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function toggle() {
  open.value = !open.value
  view.value = ''
  keyword.value = ''
}

async function pick(model) {
  if (model.id === currentModel.value) {
    view.value = ''
    return
  }
  saving.value = true
  try {
    // 换模型时把思考强度清掉：新模型支持哪几档不一定一样，
    // 留着旧值可能是个非法档位
    profiles.value = await saveProfile({ model: model.id, reasoningEffort: '' })
    view.value = ''
    message.success(`已切换到 ${model.name || model.id}`)
  } finally {
    saving.value = false
  }
}

async function pickEffort(level) {
  if ((level || '') === currentEffort.value) {
    view.value = ''
    return
  }
  saving.value = true
  try {
    profiles.value = await saveProfile({ reasoningEffort: level || '' })
    view.value = ''
    message.success(level ? `思考强度：${effortName(level)}` : '思考强度：服务商默认')
  } finally {
    saving.value = false
  }
}

/** 保存后返回整份新列表，让两个角色都能跟着更新 */
async function saveProfile(patch) {
  await updateAgentProfile(props.role, patch)
  return (await listAgentProfiles()) || profiles.value
}

/** 点面板外面关掉 */
function onDocumentDown(event) {
  const target = event.target
  if (target instanceof Element && target.closest('.mp')) return
  open.value = false
  view.value = ''
}

onMounted(() => document.addEventListener('mousedown', onDocumentDown, true))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocumentDown, true))

/** 搜索框在"切到模型列表"之后才存在，autofocus 属性对动态插入的节点不一定生效 */
const searchRef = ref(null)
watch(view, (value) => {
  if (value !== 'models') return
  nextTick(() => searchRef.value?.focus())
})
</script>

<template>
  <div class="mp">
    <button class="mp-trigger" :class="{ open }" @click="toggle">
      <span class="mp-model">{{ loading ? '加载中…' : (shortName || '未配置模型') }}</span>
      <span v-if="contextLabel" class="mp-badge">{{ contextLabel }}</span>
      <span v-if="effortLabel" class="mp-effort">{{ effortLabel }}</span>
      <Icon name="caret" :size="11" class="mp-caret" />
    </button>

    <div v-if="open" class="mp-panel mj-pop">
      <!-- 第一层：两行入口 -->
      <template v-if="!view">
        <button class="mp-row" @click="view = 'models'">
          <span class="mp-row-label">模型</span>
          <span class="mp-row-value">{{ shortName || '未配置' }}</span>
          <span v-if="contextLabel" class="mp-badge">{{ contextLabel }}</span>
          <Icon name="caret" :size="12" class="mp-chev" />
        </button>

        <button class="mp-row" :disabled="!levels.length" @click="view = 'effort'">
          <span class="mp-row-label">思考强度</span>
          <span class="mp-row-value">{{ effortLabel || (levels.length ? '默认' : '该模型不支持') }}</span>
          <Icon name="caret" :size="12" class="mp-chev" />
        </button>
      </template>

      <!-- 第二层：模型列表 -->
      <div v-else-if="view === 'models'" class="mp-sub">
        <div class="mp-sub-head">
          <button class="mp-back" @click="view = ''">
            <Icon name="caret" :size="12" class="mp-back-icon" />
          </button>
          <input
            ref="searchRef"
            v-model="keyword"
            class="mp-search"
            placeholder="搜索模型…"
          />
        </div>
        <div class="mp-list mj-scroll">
          <!-- 分组标题：现在是单家，但结构留着，将来多厂商时直接就有分组 -->
          <p class="mp-group">{{ providerName }}</p>
          <button
            v-for="m in filtered"
            :key="m.id"
            class="mp-item"
            :class="{ active: m.id === currentModel }"
            :disabled="saving"
            @click="pick(m)"
          >
            <span class="mp-item-name">{{ m.name || m.id }}</span>
            <span v-if="m.contextLimit" class="mp-badge">
              {{ m.contextLimit >= 1000000
                ? `${Math.round(m.contextLimit / 100000) / 10}M`
                : `${Math.round(m.contextLimit / 1000)}K` }}
            </span>
            <Icon v-if="m.id === currentModel" name="check" :size="13" class="mp-check" />
          </button>
          <p v-if="!filtered.length" class="mp-empty">
            {{ available.length
              ? '没有匹配的模型'
              : '还没有可用的模型 —— 先去「模型接入」页保存配置，并勾选要用的模型' }}
          </p>
        </div>
      </div>

      <!-- 第二层：思考强度 -->
      <div v-else class="mp-sub">
        <div class="mp-sub-head">
          <button class="mp-back" @click="view = ''">
            <Icon name="caret" :size="12" class="mp-back-icon" />
          </button>
          <span class="mp-sub-title">思考强度 · {{ shortName }}</span>
        </div>
        <div class="mp-list">
          <button
            class="mp-item"
            :class="{ active: !currentEffort }"
            :disabled="saving"
            @click="pickEffort(null)"
          >
            <span class="mp-item-name">服务商默认</span>
            <span class="mp-item-hint">不传 reasoning_effort</span>
            <Icon v-if="!currentEffort" name="check" :size="13" class="mp-check" />
          </button>
          <button
            v-for="lv in levels"
            :key="lv"
            class="mp-item"
            :class="{ active: lv === currentEffort }"
            :disabled="saving"
            @click="pickEffort(lv)"
          >
            <span class="mp-item-name">{{ effortName(lv) }}</span>
            <span class="mp-item-hint">{{ lv }}</span>
            <Icon v-if="lv === currentEffort" name="check" :size="13" class="mp-check" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mp { position: relative; }

/* ---------- 触发按钮 ---------- */
.mp-trigger {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 12px;
  border: 1px solid var(--color-line-soft);
  border-radius: var(--radius-pill);
  background: var(--color-panel);
  color: var(--color-ink-soft);
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease-out),
              background var(--dur) var(--ease-out),
              box-shadow var(--dur) var(--ease-out);
}

.mp-trigger:hover,
.mp-trigger.open {
  border-color: var(--color-brown-soft);
  background: var(--color-brown-tint);
  box-shadow: var(--shadow-xs);
}

.mp-model {
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 上下文长度的小徽章：淡棕底，跟模型名区分开 */
.mp-badge {
  flex: none;
  padding: 1px 7px;
  border-radius: var(--radius-pill);
  background: var(--color-brown-tint-strong);
  color: var(--color-brown-deep);
  font-size: 10.5px;
  letter-spacing: 0.02em;
}

.mp-effort {
  flex: none;
  color: var(--color-muted);
  font-size: 11.5px;
}

.mp-caret {
  flex: none;
  color: var(--color-muted);
  transition: transform var(--dur) var(--ease-out);
}
.mp-trigger.open .mp-caret { transform: rotate(180deg); }

/* ---------- 面板 ---------- */
.mp-panel {
  position: absolute;
  bottom: calc(100% + 10px);
  left: 0;
  z-index: 200;
  min-width: 320px;
  max-width: 420px;
  padding: 6px;
  border: 1px solid var(--color-line-soft);
  border-radius: var(--radius-m);
  background: var(--color-panel);
  box-shadow: var(--shadow-float);
  transform-origin: bottom left;
}

.mp-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  text-align: left;
  transition: background var(--dur) var(--ease-out);
}
.mp-row:hover:not(:disabled) { background: var(--color-brown-tint); }
.mp-row:disabled { opacity: 0.45; cursor: not-allowed; }

.mp-row-label {
  flex: none;
  width: 66px;
  font-size: 12px;
  color: var(--color-muted);
}

.mp-row-value {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--color-ink);
}

.mp-chev {
  flex: none;
  color: var(--color-muted);
  /* 用同一个箭头图标旋转 90°，保证图标集里不用多一个 chevron */
  transform: rotate(-90deg);
}

/* ---------- 子列表 ---------- */
.mp-sub { display: flex; flex-direction: column; min-height: 0; }

.mp-sub-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 4px 8px;
}

.mp-back {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: var(--radius-xs);
  background: none;
  cursor: pointer;
  color: var(--color-muted);
  transition: background var(--dur) var(--ease-out);
}
.mp-back:hover { background: var(--color-brown-tint); }

.mp-back-icon { transform: rotate(90deg); }

.mp-search {
  flex: 1;
  padding: 6px 12px;
  border: 1px solid var(--color-line-soft);
  border-radius: var(--radius-pill);
  background: var(--color-paper-warm);
  font-family: var(--font-sans);
  font-size: 12.5px;
  color: var(--color-ink);
  transition: border-color var(--dur) var(--ease-out), box-shadow var(--dur) var(--ease-out);
}
.mp-search:focus {
  outline: none;
  border-color: var(--color-brown);
  box-shadow: 0 0 0 3px var(--color-brown-tint);
}

.mp-sub-title {
  font-size: 12px;
  color: var(--color-muted);
}

.mp-list {
  max-height: 300px;
  overflow-y: auto;
  padding: 2px;
}

.mp-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 11px;
  border: 0;
  border-radius: var(--radius-xs);
  background: none;
  cursor: pointer;
  text-align: left;
  transition: background var(--dur) var(--ease-out);
}
.mp-item:hover:not(:disabled) { background: var(--color-brown-tint); }
.mp-item.active { background: var(--color-brown-tint-strong); }
.mp-item:disabled { opacity: 0.5; cursor: not-allowed; }

.mp-item-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--color-ink);
}

.mp-item-hint {
  flex: none;
  font-size: 11px;
  color: var(--color-muted);
}

.mp-check { flex: none; color: var(--color-brown); }

.mp-empty {
  padding: 18px 12px;
  text-align: center;
  font-size: 12px;
  line-height: 1.8;
  color: var(--color-muted);
}

/* 分组标题：小号、拉开字距，只做分隔不抢注意力 */
.mp-group {
  padding: 8px 11px 4px;
  font-size: 11px;
  letter-spacing: 0.1em;
  color: var(--color-muted);
}
</style>
