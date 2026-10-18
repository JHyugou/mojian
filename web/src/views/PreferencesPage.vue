<script setup>
import { computed } from 'vue'
import { useSettingsStore, THINKING_MODES, SEND_KEYS } from '../stores/settings'

/**
 * 偏好设置。
 *
 * 这里只放「一处设置、全局生效」的东西：主题、思维链、发送键。
 * 模型接入单独一页 —— 它要测连接、要拉模型列表，塞进来会让这页变成大杂烩。
 */
const settings = useSettingsStore()

/**
 * 主题清单。
 *
 * swatches 是每套主题的"名片"：纸底、主色、强调色。
 * 只给名字的话，用户得逐个点开才知道长什么样 —— 那不如直接给色卡。
 */
const THEMES = [
  {
    key: 'sujuan',
    name: '素笺',
    desc: '暖白纸底 + 棕。最接近手写稿的一版',
    swatches: ['#faf8f4', '#a2653c', '#c4553f']
  },
  {
    key: 'qingci',
    name: '青瓷',
    desc: '浅青灰 + 墨绿。清冷安静，适合长时间读稿',
    swatches: ['#f4f7f6', '#4a7a68', '#b8553f']
  },
  {
    key: 'danqing',
    name: '黛青',
    desc: '冷白 + 靛蓝。极简锐利，像文稿纸',
    swatches: ['#f6f7fa', '#4a5f9e', '#c04a3a']
  },
  {
    key: 'moye',
    name: '墨夜',
    desc: '深色。夜里写字用',
    swatches: ['#1a1815', '#c9965f', '#d4644d']
  }
]

const currentSendKey = computed(
  () => SEND_KEYS.find((m) => m.key === settings.sendKey) || SEND_KEYS[0]
)
</script>

<template>
  <div class="st-page">
    <header class="st-head">
      <h1 class="st-title">设置</h1>
      <p class="st-sub">这些偏好只影响界面，不会改动你的创作数据。</p>
    </header>

    <!-- ==================== 主题 ==================== -->
    <section class="st-card">
      <div class="st-card-head">
        <span class="st-card-title">主题</span>
        <span class="st-muted">换一套配色，观感差别比想象中大</span>
      </div>

      <div class="st-themes">
        <button
          v-for="theme in THEMES"
          :key="theme.key"
          class="st-theme"
          :class="{ active: settings.theme === theme.key }"
          @click="settings.setTheme(theme.key)"
        >
          <!-- 色卡：纸底 / 主色 / 强调色。比一个名字直观得多 -->
          <span class="st-swatches">
            <i v-for="(color, i) in theme.swatches" :key="i" :style="{ background: color }"></i>
          </span>
          <span class="st-theme-name">{{ theme.name }}</span>
          <span class="st-theme-desc">{{ theme.desc }}</span>
          <span v-if="settings.theme === theme.key" class="st-check">✓</span>
        </button>
      </div>
    </section>

    <!-- ==================== 思维链 ==================== -->
    <section class="st-card">
      <div class="st-card-head">
        <span class="st-card-title">思维链显示</span>
        <span class="st-muted">模型思考过程怎么给你看</span>
      </div>

      <div class="st-options">
        <button
          v-for="mode in THINKING_MODES"
          :key="mode.key"
          class="st-option"
          :class="{ active: settings.thinkingMode === mode.key }"
          @click="settings.setThinkingMode(mode.key)"
        >
          <span class="st-option-label">{{ mode.label }}</span>
          <span class="st-option-hint">{{ mode.hint }}</span>
        </button>
      </div>

      <!-- 用当前选择渲染一段示意，省得用户靠想象 -->
      <div class="st-preview">
        <p class="st-preview-tag">示意</p>

        <div v-if="settings.thinkingMode === 'hidden'" class="st-preview-body">
          <p class="st-muted">（思考过程不显示，直接给你结果）</p>
        </div>

        <div v-else-if="settings.thinkingMode === 'collapsed'" class="st-preview-body">
          <span class="st-thinking-collapsed">
            <span class="st-dots"><i></i><i></i><i></i></span>
            已思考 12 秒
            <span class="st-caret">▸</span>
          </span>
        </div>

        <div v-else class="st-preview-body">
          <div class="st-thinking-full">
            <p class="st-muted">已思考 12 秒</p>
            <p>
              先看作者给的资料里关于「气」的部分……他刚才说只有大秦本地人能用，
              那西方的圣晖体系就得另找一套说法，不然两边会打架。
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- ==================== 发送键 ==================== -->
    <section class="st-card">
      <div class="st-card-head">
        <span class="st-card-title">发送键</span>
        <span class="st-muted">当前：{{ currentSendKey.label }}（{{ currentSendKey.hint }}）</span>
      </div>

      <div class="st-options">
        <button
          v-for="mode in SEND_KEYS"
          :key="mode.key"
          class="st-option"
          :class="{ active: settings.sendKey === mode.key }"
          @click="settings.setSendKey(mode.key)"
        >
          <span class="st-option-label">{{ mode.label }}</span>
          <span class="st-option-hint">{{ mode.hint }}</span>
        </button>
      </div>
    </section>

    <p class="st-foot">设置保存在本机浏览器里，换一台机器不会跟着走。</p>
  </div>
</template>

<style scoped>
.st-page {
  max-width: 760px;
  margin: 0 auto;
  padding: 30px 24px 64px;
}

.st-head { margin-bottom: 22px; }

.st-title {
  font-family: var(--font-serif);
  font-size: 26px;
  color: var(--color-brown-deep);
  letter-spacing: 0.04em;
}

.st-sub { margin-top: 7px; font-size: 12.5px; color: var(--color-muted); }

.st-card {
  margin-bottom: 18px;
  padding: 20px 22px;
  border-radius: var(--radius-l);
  background: var(--color-panel);
  box-shadow: var(--shadow-xs);
}

.st-card-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 16px;
}

.st-card-title {
  font-family: var(--font-serif);
  font-size: 15px;
  color: var(--color-brown-deep);
  letter-spacing: 0.04em;
}

.st-muted { font-size: 12px; color: var(--color-muted); }

/* ---------- 主题卡片 ---------- */

.st-themes {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
  gap: 11px;
}

.st-theme {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 13px 15px;
  border: 1.5px solid var(--color-line-soft);
  border-radius: var(--radius-m);
  background: var(--color-paper-warm);
  cursor: pointer;
  text-align: left;
  transition: border-color var(--dur) var(--ease-out),
              background var(--dur) var(--ease-out),
              transform var(--dur) var(--ease-spring);
}
.st-theme:hover { transform: translateY(-2px); border-color: var(--color-brown-soft); }
.st-theme.active {
  border-color: var(--color-brown);
  background: var(--color-panel);
  box-shadow: var(--shadow-soft);
}

.st-swatches { display: flex; gap: 5px; margin-bottom: 4px; }
.st-swatches i {
  width: 22px;
  height: 22px;
  border-radius: var(--radius-xs);
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.06);
}

.st-theme-name {
  font-family: var(--font-serif);
  font-size: 14px;
  color: var(--color-ink);
}

.st-theme-desc { font-size: 11px; line-height: 1.6; color: var(--color-muted); }

.st-check {
  position: absolute;
  top: 10px;
  right: 12px;
  color: var(--color-brown);
  font-size: 13px;
}

/* ---------- 选项 ---------- */

.st-options { display: grid; gap: 9px; }

.st-option {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px 16px;
  border: 1.5px solid var(--color-line-soft);
  border-radius: var(--radius-m);
  background: var(--color-paper-warm);
  cursor: pointer;
  text-align: left;
  transition: border-color var(--dur) var(--ease-out),
              background var(--dur) var(--ease-out);
}
.st-option:hover { border-color: var(--color-brown-soft); }
.st-option.active { border-color: var(--color-brown); background: var(--color-panel); }

.st-option-label { font-size: 13.5px; color: var(--color-ink); }
.st-option.active .st-option-label { color: var(--color-brown-deep); }
.st-option-hint { font-size: 11.5px; color: var(--color-muted); }

/* ---------- 示意 ---------- */

.st-preview {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: var(--radius-m);
  background: var(--color-paper-warm);
}

.st-preview-tag {
  font-size: 10.5px;
  letter-spacing: 0.12em;
  color: var(--color-muted);
  margin-bottom: 10px;
}

.st-preview-body { min-height: 42px; }

.st-thinking-collapsed {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 8px 16px;
  border-radius: var(--radius-pill);
  background: var(--color-panel);
  font-size: 12.5px;
  color: var(--color-muted);
}

.st-dots { display: inline-flex; gap: 4px; }
.st-dots i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--color-brown);
  animation: mj-breathe 1.4s ease-in-out infinite;
}
.st-dots i:nth-child(2) { animation-delay: 0.16s; }
.st-dots i:nth-child(3) { animation-delay: 0.32s; }

.st-caret { font-size: 9px; }

.st-thinking-full {
  padding: 12px 16px;
  border-left: 2px solid var(--color-brown-soft);
  border-radius: 0 var(--radius-s) var(--radius-s) 0;
  font-size: 12.5px;
  line-height: 1.95;
  color: var(--color-muted);
}
.st-thinking-full p + p { margin-top: 8px; }

.st-foot {
  margin-top: 20px;
  text-align: center;
  font-size: 11.5px;
  color: var(--color-muted);
}
</style>
