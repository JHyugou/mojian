<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from '../../components/Icon.vue'
import { listAgentProfiles, updateAgentProfile } from '../../api/agent'
import { fetchModels } from '../../api/aiConfig'
import { useV3Notify } from '../notify'

/**
 * 模型 / 思考强度选择器（v3）。
 *
 * <p>它是"边写边调"的东西，所以放在输入框下方而不是设置页：
 * 这一章要写得细一点还是快一点、这个走向要不要让它多想一会儿，
 * 都是临时决定，不该让人跳到另一个页面再回来。
 *
 * <p>两个关键点：
 * <ul>
 *   <li><b>档位跟着模型走</b>：各家思考强度的值域不一样（none/low/medium/high/xhigh/max），
 *       写死一套会在别的模型上给出非法值。</li>
 *   <li><b>只改当前角色</b>：策划和写作是两套独立配置，在哪个面板里点就只改哪个，
 *       不会一次把两边都改掉。</li>
 * </ul>
 */
const props = defineProps({
  role: { type: String, default: 'PLANNER' }
})

const notify = useV3Notify()

const profiles = ref([])
const models = ref([])
const loading = ref(true)
const saving = ref(false)
const open = ref(false)
/** '' 主菜单 / 'models' / 'effort' */
const view = ref('')
const keyword = ref('')
const searchRef = ref(null)

const profile = computed(() => profiles.value.find((p) => p.role === props.role) || null)
const currentModel = computed(() => profile.value?.model || '')
const currentEffort = computed(() => profile.value?.reasoningEffort || '')

/** 只列用户启用过的模型；但当前正在用的无论如何留着，免得"看不见自己在用什么" */
const available = computed(() => {
  const list = models.value.filter((m) => m.enabled !== false || m.id === currentModel.value)
  return list.length ? list : models.value
})

const currentInfo = computed(() => models.value.find((m) => m.id === currentModel.value) || null)
const levels = computed(() => currentInfo.value?.reasoningLevels || [])

const shortName = computed(() => {
  const raw = currentInfo.value?.name || currentModel.value
  return raw.includes('/') ? raw.slice(raw.lastIndexOf('/') + 1) : raw
})

const contextLabel = computed(() => {
  const limit = currentInfo.value?.contextLimit
  if (!limit) return ''
  return limit >= 1_000_000
    ? `${Math.round(limit / 100_000) / 10}M`
    : `${Math.round(limit / 1000)}K`
})

const EFFORT_NAMES = {
  none: '不思考', minimal: '极简', low: '低', medium: '中', high: '高', xhigh: '极高', max: '最大'
}
const effortName = (level) => EFFORT_NAMES[level] || level
const effortLabel = computed(() => (currentEffort.value ? effortName(currentEffort.value) : ''))

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return available.value
  return available.value.filter((m) =>
    m.id.toLowerCase().includes(kw) || (m.name || '').toLowerCase().includes(kw))
})

async function load() {
  loading.value = true
  try {
    profiles.value = (await listAgentProfiles()) || []
    const result = await fetchModels({
      baseUrl: profile.value?.baseUrl || undefined
    })
    models.value = result?.models || []
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

async function saveProfile(patch) {
  await updateAgentProfile(props.role, patch)
  return (await listAgentProfiles()) || profiles.value
}

async function pick(model) {
  if (model.id === currentModel.value) {
    view.value = ''
    return
  }
  saving.value = true
  try {
    // 换模型时清掉思考强度：新模型支持哪几档不一定一样，旧值可能是非法档位
    profiles.value = await saveProfile({ model: model.id, reasoningEffort: '' })
    view.value = ''
    notify.success(`已切换到 ${model.name || model.id}`)
  } catch (e) {
    notify.error(e.message || '切换模型失败')
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
    notify.success(level ? `思考强度：${effortName(level)}` : '思考强度：服务商默认')
  } catch (e) {
    notify.error(e.message || '设置思考强度失败')
  } finally {
    saving.value = false
  }
}

function onDocumentDown(event) {
  const target = event.target
  if (target instanceof Element && target.closest('.mp')) return
  open.value = false
  view.value = ''
}

onMounted(() => document.addEventListener('mousedown', onDocumentDown, true))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocumentDown, true))

watch(view, (value) => {
  if (value !== 'models') return
  nextTick(() => searchRef.value?.focus())
})
</script>

<template>
  <div class="mp">
    <button class="mp-trigger" :class="{ open }" title="切换模型 / 思考强度" @click="toggle">
      <Icon name="cpu" :size="12" />
      <span class="mp-model">{{ loading ? '加载中…' : (shortName || '未配置模型') }}</span>
      <span v-if="contextLabel" class="mp-badge">{{ contextLabel }}</span>
      <span v-if="effortLabel" class="mp-effort">{{ effortLabel }}</span>
      <Icon name="caret" :size="10" class="mp-caret" />
    </button>

    <div v-if="open" class="mp-panel">
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

      <div v-else-if="view === 'models'" class="mp-sub">
        <div class="mp-sub-head">
          <button class="mp-back" title="返回" @click="view = ''">
            <Icon name="caret" :size="12" />
          </button>
          <input ref="searchRef" v-model="keyword" class="mp-search" placeholder="搜索模型…" />
        </div>
        <div class="mp-list v3-scroll">
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

      <div v-else class="mp-sub">
        <div class="mp-sub-head">
          <button class="mp-back" title="返回" @click="view = ''">
            <Icon name="caret" :size="12" />
          </button>
          <span class="mp-sub-title">思考强度 · {{ shortName }}</span>
        </div>
        <div class="mp-list v3-scroll">
          <button class="mp-item" :class="{ active: !currentEffort }" :disabled="saving" @click="pickEffort(null)">
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
.mp-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 26px;
  padding: 0 9px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-pill);
  background: transparent;
  color: var(--v3-text-muted);
  font: inherit;
  font-size: 10.5px;
  cursor: pointer;
  transition: color var(--v3-dur-2), border-color var(--v3-dur-2), background var(--v3-dur-2);
}
.mp-trigger:hover, .mp-trigger.open {
  color: var(--v3-gold-bright);
  border-color: var(--v3-hairline-gold);
  background: var(--v3-glow-gold);
}
.mp-model { max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mp-badge {
  flex: none;
  padding: 1px 6px;
  border-radius: var(--v3-r-pill);
  background: var(--v3-surface-2);
  color: var(--v3-text-faint);
  font-size: 9.5px;
  font-family: var(--v3-font-mono);
}
.mp-effort { flex: none; color: var(--v3-gold); }
/* caret 原始朝向是"下"：向下=默认，向右=-90°，向左=+90° */
.mp-caret { opacity: 0.6; }
.mp-chev { transform: rotate(-90deg); opacity: 0.5; }

.mp-panel {
  position: absolute;
  right: 0;
  bottom: calc(100% + 8px);
  z-index: 70;
  width: 300px;
  padding: 6px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-md);
  background: var(--v3-surface-solid);
  box-shadow: var(--v3-shadow-float);
}
.mp-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 9px 10px;
  border: 0;
  border-radius: var(--v3-r-xs);
  background: transparent;
  color: var(--v3-text);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.mp-row:hover:not(:disabled) { background: var(--v3-surface-1); }
.mp-row:disabled { opacity: 0.45; cursor: not-allowed; }
.mp-row-label { flex: 0 0 62px; font-size: 11px; color: var(--v3-text-faint); }
.mp-row-value { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }

.mp-sub-head { display: flex; align-items: center; gap: 6px; padding: 4px 4px 8px; }
.mp-back {
  width: 24px; height: 24px;
  display: grid; place-items: center;
  border: 0; border-radius: 50%;
  background: transparent; color: var(--v3-text-faint);
  cursor: pointer; transform: rotate(180deg);
}
.mp-back:hover { background: var(--v3-surface-2); color: var(--v3-text); }
/* caret 默认向下，转 +90° 后指向左，才是"返回" */
.mp-back svg { transform: rotate(90deg); }
.mp-search {
  flex: 1; min-width: 0; height: 28px; padding: 0 9px;
  border: 1px solid var(--v3-hairline); border-radius: var(--v3-r-xs);
  background: var(--v3-surface-1); color: var(--v3-text); font: inherit; font-size: 11.5px; outline: none;
}
.mp-search:focus { border-color: var(--v3-hairline-gold); }
.mp-sub-title { font-size: 11.5px; color: var(--v3-text-muted); letter-spacing: 0.04em; }
.mp-list { display: flex; flex-direction: column; gap: 2px; max-height: 280px; overflow-y: auto; }
.mp-item {
  display: flex; align-items: center; gap: 8px;
  width: 100%; padding: 8px 10px;
  border: 0; border-radius: var(--v3-r-xs);
  background: transparent; color: var(--v3-text-soft);
  font: inherit; text-align: left; cursor: pointer;
}
.mp-item:hover:not(:disabled) { background: var(--v3-surface-1); color: var(--v3-text); }
.mp-item.active { background: var(--v3-glow-gold); color: var(--v3-text); }
/*
 * 已选项的 hover 必须"还在选中态"里：之前 .mp-item:hover 的特异性比 .active 高，
 * 鼠标移上去会把金色选中底换成普通 hover 底，看起来像"选中丢了"。
 * 这里让 active 在 hover 时只轻微提亮，而不是换掉选中色。
 */
.mp-item.active:hover:not(:disabled) {
  background: color-mix(in srgb, var(--v3-gold) 24%, transparent);
  color: var(--v3-text);
}
.mp-item:disabled { opacity: 0.5; cursor: wait; }
.mp-item-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11.5px; }
.mp-item-hint { font-size: 9.5px; color: var(--v3-text-faint); font-family: var(--v3-font-mono); }
.mp-check { color: var(--v3-gold-bright); flex-shrink: 0; }
.mp-empty { margin: 8px 10px; font-size: 10.5px; line-height: 1.7; color: var(--v3-text-faint); }
</style>
