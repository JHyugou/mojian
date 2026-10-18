<script setup>
import { computed } from 'vue'
import Icon from '../../components/Icon.vue'
import V3Button from '../../components/v3/V3Button.vue'
import V3Tag from '../../components/v3/V3Tag.vue'
import { useV3Theme } from '../theme'
import { useSettingsStore, SEND_KEYS, THINKING_MODES } from '../../stores/settings'

/**
 * 偏好设置（v3）：主题、思维链、发送键。
 * 这些设置"一处改、全局生效"，所以放在同一页；改完立即写入 localStorage。
 */
const { theme, setTheme, themes } = useV3Theme()
const settings = useSettingsStore()

const currentTheme = computed(() => themes.find((t) => t.key === theme.value))
</script>

<template>
  <div class="prefs v3-scroll">
      <header class="prefs-head">
        <p class="kicker">PREFERENCES</p>
        <h1>偏好设置</h1>
        <p class="sub">只影响这台机器上的显示与输入习惯，不会动你的书。</p>
      </header>

      <section class="block">
        <header class="block-head">
          <span class="block-no">壹</span>
          <div>
            <h2>主题</h2>
            <p>墨海是深墨底 + 金色细线，适合夜里写；素笺是暖白纸感，适合白天读。</p>
          </div>
        </header>
        <div class="themes">
          <button
            v-for="t in themes"
            :key="t.key"
            class="theme-card"
            :class="{ active: theme === t.key }"
            @click="setTheme(t.key)"
          >
            <span class="theme-swatches">
              <i v-for="(color, i) in t.swatches" :key="i" :style="{ background: color }"></i>
            </span>
            <span class="theme-name">{{ t.name }}</span>
            <span class="theme-desc">{{ t.desc }}</span>
            <V3Tag v-if="theme === t.key" tone="gold">当前</V3Tag>
          </button>
        </div>
      </section>

      <section class="block">
        <header class="block-head">
          <span class="block-no">貳</span>
          <div>
            <h2>思维链</h2>
            <p>推理模型会先想一段再回答。这里决定那段"思考过程"怎么显示。</p>
          </div>
        </header>
        <div class="options">
          <button
            v-for="mode in THINKING_MODES"
            :key="mode.key"
            class="option"
            :class="{ active: settings.thinkingMode === mode.key }"
            @click="settings.setThinkingMode(mode.key)"
          >
            <span class="option-label">{{ mode.label }}</span>
            <span class="option-hint">{{ mode.hint }}</span>
            <span v-if="settings.thinkingMode === mode.key" class="option-check"><Icon name="check" :size="13" /></span>
          </button>
        </div>
      </section>

      <section class="block">
        <header class="block-head">
          <span class="block-no">叁</span>
          <div>
            <h2>发送键</h2>
            <p>策划与写作两个输入框会同时遵守这里的设置；中文输入法组词时不会误发送。</p>
          </div>
        </header>
        <div class="options two">
          <button
            v-for="mode in SEND_KEYS"
            :key="mode.key"
            class="option"
            :class="{ active: settings.sendKey === mode.key }"
            @click="settings.setSendKey(mode.key)"
          >
            <span class="option-label">{{ mode.label }}</span>
            <span class="option-hint">{{ mode.hint }}</span>
            <span v-if="settings.sendKey === mode.key" class="option-check"><Icon name="check" :size="13" /></span>
          </button>
        </div>
      </section>

      <section class="block">
        <header class="block-head">
          <span class="block-no">肆</span>
          <div>
            <h2>对话回滚</h2>
            <p>回滚到某条用户消息时，策划之后经草稿确认写进书里的人物/设定/蓝图要不要一起撤销。</p>
          </div>
        </header>
        <div class="options two">
          <button
            class="option"
            :class="{ active: settings.rollbackDatabase }"
            @click="settings.setRollbackDatabase(true)"
          >
            <span class="option-label">连带回滚数据库（推荐）</span>
            <span class="option-hint">撤销这段对话之后采纳的结构改动；被撤销的草稿退回「待确认」，正文不动</span>
            <span v-if="settings.rollbackDatabase" class="option-check"><Icon name="check" :size="13" /></span>
          </button>
          <button
            class="option"
            :class="{ active: !settings.rollbackDatabase }"
            @click="settings.setRollbackDatabase(false)"
          >
            <span class="option-label">只回滚对话</span>
            <span class="option-hint">只删消息，书里的数据保持现状；适合「只想重说一遍」</span>
            <span v-if="!settings.rollbackDatabase" class="option-check"><Icon name="check" :size="13" /></span>
          </button>
        </div>
      </section>

      <section class="block">
        <header class="block-head">
          <span class="block-no">伍</span>
          <div>
            <h2>删除章节</h2>
            <p>写作 Agent 可以申请删除章节；默认必须由你在写作面板确认，开启自动批准后它会直接删。</p>
          </div>
        </header>
        <div class="options two">
          <button
            class="option"
            :class="{ active: !settings.autoApproveDelete }"
            @click="settings.setAutoApproveDelete(false)"
          >
            <span class="option-label">每次询问我（推荐）</span>
            <span class="option-hint">Agent 只能提交删除申请，你在写作面板点「删除章节」后才会执行</span>
            <span v-if="!settings.autoApproveDelete" class="option-check"><Icon name="check" :size="13" /></span>
          </button>
          <button
            class="option danger"
            :class="{ active: settings.autoApproveDelete }"
            @click="settings.setAutoApproveDelete(true)"
          >
            <span class="option-label">自动批准删除（高风险）</span>
            <span class="option-hint">Agent 可直接删除章节，不再询问；只适合你明确要求它整理章节时短暂开启</span>
            <span v-if="settings.autoApproveDelete" class="option-check"><Icon name="check" :size="13" /></span>
          </button>
        </div>
        <p v-if="settings.autoApproveDelete" class="risk-warning">
          ⚠ 自动批准已开启：写作 Agent 可以在你不在场时删除章节，正文、历史版本与章节记忆会一并删除，
          <b>无法撤销</b>。请确认你有数据库备份（<code>.local-backup/</code> 或你自己的备份），
          并只在明确要求它整理章节时开启；用完请切回「每次询问我」。
        </p>
      </section>

      <section class="block">
        <header class="block-head">
          <span class="block-no">陆</span>
          <div>
            <h2>快捷键与说明</h2>
            <p>常用入口集中在这里，避免在写作时到处找按钮。</p>
          </div>
        </header>
        <dl class="keys">
          <div><dt><kbd>Ctrl</kbd> / <kbd>⌘</kbd> + <kbd>K</kbd></dt><dd>打开命令面板（切项目、开新对话、换主题、跳页面）</dd></div>
          <div><dt><kbd>Enter</kbd></dt><dd>按发送键设置发送；另一种组合用于换行</dd></div>
          <div><dt><kbd>Esc</kbd></dt><dd>关闭弹窗 / 命令面板</dd></div>
          <div><dt>引用</dt><dd>右栏资料每条右下角有引用按钮，会带上记录 ID 进入对话</dd></div>
        </dl>
      </section>

      <p class="foot-note">
        当前主题：{{ currentTheme?.name }} · 所有偏好存在本机浏览器里；换浏览器需要重设。
      </p>
  </div>
</template>

<style scoped>
.prefs { flex: 1; min-width: 0; overflow-y: auto; padding: 40px 48px 80px; }
.prefs-head { max-width: 760px; margin-bottom: 18px; }
.kicker { margin: 0 0 14px; font-size: 10px; letter-spacing: 0.42em; color: var(--v3-gold); }
.prefs-head h1 {
  margin: 0;
  font-family: var(--v3-font-serif);
  font-size: clamp(28px, 3.4vw, 38px);
  font-weight: 600;
  letter-spacing: 0.08em;
}
.sub { margin: 12px 0 0; font-size: 12.5px; line-height: 1.9; color: var(--v3-text-muted); }

.risk-warning {
  max-width: 860px;
  margin: 14px 0 0;
  padding: 12px 14px;
  border: 1px solid rgba(201, 76, 59, 0.4);
  border-radius: var(--v3-r-sm);
  background: var(--v3-cinnabar-soft);
  font-size: 11.5px;
  line-height: 1.9;
  color: #ffd9d3;
}
.risk-warning b { color: #fff; }
.risk-warning code { font-family: var(--v3-font-mono); font-size: 10.5px; }
.option.danger.active { border-color: rgba(201, 76, 59, 0.55); }
.option.danger .option-label { color: #e8a8a0; }

.block { max-width: 860px; margin-top: 40px; padding-top: 28px; border-top: 1px solid var(--v3-hairline); }
.block-head { display: flex; gap: 16px; margin-bottom: 18px; }
.block-no {
  flex-shrink: 0;
  font-family: var(--v3-font-serif);
  font-size: 18px;
  color: var(--v3-gold);
  opacity: 0.85;
}
.block-head h2 { margin: 0 0 6px; font-family: var(--v3-font-serif); font-size: 17px; letter-spacing: 0.08em; }
.block-head p { margin: 0; font-size: 12px; line-height: 1.85; color: var(--v3-text-muted); }

.themes { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.theme-card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 18px 20px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-md);
  background: var(--v3-surface-1);
  color: var(--v3-text-soft);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: transform var(--v3-dur-2) var(--v3-ease-spring), border-color var(--v3-dur-2), background var(--v3-dur-2);
}
.theme-card:hover { transform: translateY(-2px); border-color: var(--v3-hairline-gold); }
.theme-card.active { border-color: var(--v3-hairline-gold); background: var(--v3-glow-gold); }
.theme-swatches { display: flex; gap: 6px; margin-bottom: 4px; }
.theme-swatches i { width: 26px; height: 26px; border-radius: 7px; border: 1px solid var(--v3-hairline); }
.theme-name { font-family: var(--v3-font-serif); font-size: 17px; letter-spacing: 0.12em; color: var(--v3-text); }
.theme-desc { font-size: 11.5px; line-height: 1.7; color: var(--v3-text-faint); }
.theme-card :deep(.v3-tag) { position: absolute; top: 14px; right: 14px; }

.options { display: grid; grid-template-columns: 1fr; gap: 10px; }
.options.two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.option {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
  padding: 14px 18px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-1);
  color: var(--v3-text-soft);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--v3-dur-2), background var(--v3-dur-2), transform var(--v3-dur-2) var(--v3-ease-spring);
}
.option:hover { border-color: var(--v3-hairline-gold); transform: translateY(-1px); }
.option.active { border-color: var(--v3-hairline-gold); background: var(--v3-glow-gold); }
.option-label { font-size: 13.5px; color: var(--v3-text); }
.option-hint { font-size: 11px; line-height: 1.7; color: var(--v3-text-faint); }
.option-check { position: absolute; top: 14px; right: 14px; color: var(--v3-gold-bright); }

.keys { margin: 0; display: flex; flex-direction: column; gap: 12px; }
.keys > div { display: flex; align-items: baseline; gap: 16px; }
.keys dt { flex: 0 0 180px; font-size: 12px; color: var(--v3-text-soft); }
.keys dd { margin: 0; font-size: 12px; line-height: 1.8; color: var(--v3-text-muted); }
kbd {
  padding: 2px 6px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: 5px;
  font-family: var(--v3-font-mono);
  font-size: 10px;
  color: var(--v3-text-soft);
}
.foot-note { max-width: 860px; margin-top: 46px; font-size: 11px; letter-spacing: 0.05em; color: var(--v3-text-faint); }

@media (max-width: 900px) {
  .prefs { padding: 28px 20px 60px; }
  .themes, .options.two { grid-template-columns: 1fr; }
  .keys dt { flex-basis: 130px; }
}
</style>
