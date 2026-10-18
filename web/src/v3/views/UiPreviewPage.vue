<script setup>
import { onMounted, ref } from 'vue'
import Icon from '../../components/Icon.vue'
import V3Ambient from '../../components/v3/V3Ambient.vue'
import V3Button from '../../components/v3/V3Button.vue'
import V3Card from '../../components/v3/V3Card.vue'
import V3CommandPalette from '../../components/v3/V3CommandPalette.vue'
import V3Empty from '../../components/v3/V3Empty.vue'
import V3Field from '../../components/v3/V3Field.vue'
import V3Input from '../../components/v3/V3Input.vue'
import V3Modal from '../../components/v3/V3Modal.vue'
import V3Skeleton from '../../components/v3/V3Skeleton.vue'
import V3Tag from '../../components/v3/V3Tag.vue'
import V3Textarea from '../../components/v3/V3Textarea.vue'
import V3Toasts from '../../components/v3/V3Toasts.vue'
import { useV3Theme } from '../theme'
import { animateCount, vReveal } from '../motion'
import { useV3Notify } from '../notify'

const { theme, setTheme } = useV3Theme()
const notify = useV3Notify()

const count = ref(0)
onMounted(() => animateCount(0, 1372, 1400, (n) => (count.value = n)))

const modalOpen = ref(false)
const paletteOpen = ref(false)
const input = ref('')
const text = ref('')

const paletteItems = [
  { id: 'open-workbench', label: '打开创作工作台', hint: '前往工作台', icon: 'quill' },
  { id: 'new-project', label: '新建创作项目', hint: '开始一本新书', icon: 'plus' },
  { id: 'theme', label: theme.value === 'inksea' ? '切换到素笺（亮色）' : '切换到墨海（暗色）', hint: '主题', icon: 'sun' }
]

const colors = [
  { name: '海面 canvas', v: '--v3-canvas' },
  { name: '浮层 surface', v: '--v3-surface-0' },
  { name: '抬起 raised', v: '--v3-surface-raised' },
  { name: '正文 text', v: '--v3-text' },
  { name: '弱化 muted', v: '--v3-text-muted' },
  { name: '鎏金 gold', v: '--v3-gold' },
  { name: '朱砂 cinnabar', v: '--v3-cinnabar' },
  { name: '青瓷 jade', v: '--v3-jade' }
]
</script>

<template>
  <div class="v3-root preview">
    <V3Ambient variant="calm" />
    <div class="preview-inner">
      <header class="pv-head">
        <div>
          <p class="pv-eyebrow">DESIGN SYSTEM · V3</p>
          <h1 class="pv-title">墨海 / 素笺</h1>
          <p class="pv-desc">
            这是一页"活的设计定稿"：颜色、字体、组件、动效都来自真实实现，新页面全部从这里取样式。
            切换主题看两套材质。
          </p>
        </div>
        <div class="pv-head-actions">
          <V3Button :variant="theme === 'inksea' ? 'primary' : 'glass'" size="sm" @click="setTheme('inksea')">墨海</V3Button>
          <V3Button :variant="theme === 'sujuan' ? 'primary' : 'glass'" size="sm" @click="setTheme('sujuan')">素笺</V3Button>
          <V3Button variant="ghost" size="sm" @click="$router.push('/')">回到首页</V3Button>
        </div>
      </header>

      <section class="pv-section" v-reveal>
        <h2 class="pv-h2">设计关键词</h2>
        <div class="keywords">
          <span>东方极简</span><i>·</i><span>电影感</span><i>·</i><span>纸墨光</span><i>·</i>
          <span>克制的华丽</span><i>·</i><span>60fps</span>
        </div>
        <p class="pv-p">
          底色是深海一样的墨，不是纯黑；金色只用来画线和点焦点，朱砂只出现在印章与关键状态。
          层次靠 1px 金线、底色差与磨砂，而不是堆阴影。动效只动 transform / opacity，全部支持 reduced-motion。
        </p>
      </section>

      <section class="pv-section" v-reveal>
        <h2 class="pv-h2">颜色令牌</h2>
        <div class="swatches">
          <div v-for="c in colors" :key="c.v" class="swatch">
            <i :style="{ background: `var(${c.v})` }"></i>
            <span class="swatch-name">{{ c.name }}</span>
            <code>{{ c.v }}</code>
          </div>
        </div>
      </section>

      <section class="pv-section" v-reveal>
        <h2 class="pv-h2">排版</h2>
        <p class="type-label">Display · 宋体衬线</p>
        <p class="type-display">把一部小说，写成它该有的样子。</p>
        <p class="type-label">Heading / Body / Mono</p>
        <p class="type-h3">第九章 · 边城破</p>
        <p class="type-body">
          他坐在城墙上，风从西边来，带着沙子的味道。火光在远处一盏一盏亮起来，
          像有人把星星按进了地平线。他没有回头，只是把刀往身侧挪了挪。
        </p>
        <p class="type-mono">mojian / agent-loop · 0142 ms · context 104,595 tokens</p>
      </section>

      <section class="pv-section" v-reveal>
        <h2 class="pv-h2">按钮</h2>
        <div class="row">
          <V3Button>开始创作</V3Button>
          <V3Button variant="glass">次级动作</V3Button>
          <V3Button variant="outline">金线动作</V3Button>
          <V3Button variant="ghost">文字动作</V3Button>
          <V3Button variant="danger">危险动作</V3Button>
          <V3Button loading>生成中</V3Button>
          <V3Button disabled>不可用</V3Button>
        </div>
        <div class="row">
          <V3Button size="sm">小号</V3Button>
          <V3Button size="md">中号</V3Button>
          <V3Button size="lg">大号</V3Button>
        </div>
      </section>

      <section class="pv-section" v-reveal>
        <h2 class="pv-h2">卡片 · 玻璃与纸面</h2>
        <div class="cards">
          <V3Card material="glass" interactive tilt class="demo-card">
            <V3Tag tone="gold">仙侠</V3Tag>
            <h3 class="demo-title">瀚海</h3>
            <p class="demo-text">现世大陆，存在着两股势力，一股是东部的大秦帝国，另一股位于西部…</p>
            <div class="demo-foot">
              <span>13 章</span><span>更新于十月十六日</span>
              <span class="demo-enter">继续写 <Icon name="arrowRight" :size="12" /></span>
            </div>
          </V3Card>
          <V3Card material="paper" class="demo-card">
            <p class="type-label">纸面 · 用于正文与阅读</p>
            <p class="demo-text is-serif">雪落在剑上，没有声音。远处有人在敲钟，一声，两声，第三声的时候他睁开了眼。</p>
          </V3Card>
        </div>
      </section>

      <section class="pv-section" v-reveal>
        <h2 class="pv-h2">表单</h2>
        <div class="forms">
          <V3Field label="书名" required hint="会出现在封面与成书页">
            <V3Input v-model="input" placeholder="如：山河故人" />
          </V3Field>
          <V3Field label="一句话简介">
            <V3Textarea v-model="text" :rows="3" placeholder="一把旧剑与一场大雪。" />
          </V3Field>
        </div>
      </section>

      <section class="pv-section" v-reveal>
        <h2 class="pv-h2">标签与状态</h2>
        <div class="row">
          <V3Tag>默认</V3Tag>
          <V3Tag tone="gold">选中</V3Tag>
          <V3Tag tone="cinnabar">印章</V3Tag>
          <V3Tag tone="jade">已完成</V3Tag>
        </div>
        <div class="states">
          <V3Card class="state-card"><V3Skeleton :lines="4" /></V3Card>
          <V3Card class="state-card"><V3Empty seal="墨" title="还没有作品" desc="建一个项目，先写下第一句话。" /></V3Card>
        </div>
      </section>

      <section class="pv-section" v-reveal>
        <h2 class="pv-h2">动效样例</h2>
        <div class="motion-grid">
          <div class="motion-item">
            <p class="type-label">数字缓动</p>
            <p class="count">{{ count.toLocaleString() }}<small> 字</small></p>
          </div>
          <div class="motion-item">
            <p class="type-label">弹窗 / 命令面板</p>
            <div class="row">
              <V3Button size="sm" variant="outline" @click="modalOpen = true">打开弹窗</V3Button>
              <V3Button size="sm" variant="outline" @click="paletteOpen = true">命令面板</V3Button>
            </div>
          </div>
          <div class="motion-item">
            <p class="type-label">通知</p>
            <V3Button size="sm" variant="glass" @click="notify.success('这是一条示例通知')">弹一条提示</V3Button>
          </div>
        </div>
        <p class="pv-p">
          入场动效：本页每个区块进入视口时上浮 + 去模糊（v-reveal）。卡片悬停有 3D 微倾斜与光标高光；
          按钮悬停有一道克制的光扫；所有无限动画只存在于背景氛围层。
        </p>
      </section>

      <footer class="pv-foot">
        <span>墨笺 v3 设计系统 · 2026 秋</span><span>·</span>
        <span>CSS 变量 + Vue Transition + IntersectionObserver + WAAPI，无动画库</span>
      </footer>
    </div>

    <V3Modal :visible="modalOpen" title="弹窗示例" @close="modalOpen = false">
      <p class="pv-p">弹窗从下方浮起，用 spring 曲线收尾；背景做了模糊压暗，Esc 可关闭。</p>
      <template #footer>
        <V3Button variant="ghost" @click="modalOpen = false">取消</V3Button>
        <V3Button @click="modalOpen = false">知道了</V3Button>
      </template>
    </V3Modal>
    <V3CommandPalette :visible="paletteOpen" :items="paletteItems" @close="paletteOpen = false" @select="paletteOpen = false" />
    <V3Toasts />
  </div>
</template>

<style scoped>
.preview { position: relative; min-height: 100vh; background: var(--v3-canvas); }
.preview-inner { position: relative; z-index: 1; max-width: 1080px; margin: 0 auto; padding: 64px 40px 80px; }
.pv-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 18px; }
.pv-head-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.pv-eyebrow { margin: 0 0 16px; font-size: 10.5px; letter-spacing: 0.42em; color: var(--v3-gold); }
.pv-title { margin: 0; font-family: var(--v3-font-serif); font-size: clamp(44px, 6vw, 72px); font-weight: 600; letter-spacing: 0.04em; }
.pv-desc { max-width: 620px; margin: 18px 0 0; font-size: 13.5px; line-height: 2; color: var(--v3-text-muted); }

.pv-section { margin-top: 56px; padding-top: 26px; border-top: 1px solid var(--v3-hairline); }
.pv-h2 { margin: 0 0 12px; font-family: var(--v3-font-serif); font-size: 19px; letter-spacing: 0.1em; color: var(--v3-text); }
.pv-p { margin: 14px 0 0; max-width: 760px; font-size: 12.5px; line-height: 2; color: var(--v3-text-muted); }

.keywords { display: flex; flex-wrap: wrap; gap: 12px; font-family: var(--v3-font-serif); font-size: 16px; letter-spacing: 0.14em; color: var(--v3-text-soft); }
.keywords i { color: var(--v3-gold); font-style: normal; }

.swatches { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.swatch { display: flex; flex-direction: column; gap: 8px; }
.swatch i { display: block; height: 66px; border-radius: var(--v3-r-sm); border: 1px solid var(--v3-hairline-strong); }
.swatch-name { font-size: 12px; color: var(--v3-text-soft); }
.swatch code { font-family: var(--v3-font-mono); font-size: 10.5px; color: var(--v3-text-faint); }

.type-label { margin: 14px 0 0; font-size: 10.5px; letter-spacing: 0.22em; color: var(--v3-text-faint); text-transform: uppercase; }
.type-display { margin: 6px 0 0; font-family: var(--v3-font-serif); font-size: clamp(28px, 4vw, 44px); letter-spacing: 0.03em; }
.type-h3 { margin: 6px 0 0; font-family: var(--v3-font-serif); font-size: 20px; letter-spacing: 0.08em; color: var(--v3-gold-bright); }
.type-body { margin: 6px 0 0; max-width: 720px; font-family: var(--v3-font-serif); font-size: 15px; line-height: 2.1; color: var(--v3-text-soft); }
.type-mono { margin: 10px 0 0; font-family: var(--v3-font-mono); font-size: 11.5px; color: var(--v3-text-faint); }

.row { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 14px; }

.cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
.demo-card { height: 230px; }
.demo-title { margin: 20px 0 0; font-family: var(--v3-font-serif); font-size: 26px; letter-spacing: 0.06em; }
.demo-text { margin: 12px 0 0; font-size: 12.5px; line-height: 1.95; color: var(--v3-text-muted); }
.demo-text.is-serif { font-family: var(--v3-font-serif); font-size: 15px; line-height: 2.1; color: var(--v3-text-soft); }
.demo-foot { display: flex; gap: 16px; align-items: center; margin-top: 22px; padding-top: 14px; border-top: 1px solid var(--v3-hairline); font-size: 11px; color: var(--v3-text-faint); }
.demo-enter { display: inline-flex; align-items: center; gap: 5px; margin-left: auto; color: var(--v3-gold); }

.forms { max-width: 560px; }
.states { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-top: 18px; }
.state-card { min-height: 200px; display: flex; flex-direction: column; justify-content: center; }
.motion-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px; }
.motion-item { padding: 18px; border-radius: var(--v3-r-md); border: 1px solid var(--v3-hairline); background: var(--v3-surface-1); }
.count { margin: 8px 0 0; font-family: var(--v3-font-serif); font-size: 38px; color: var(--v3-gold-bright); }
.count small { font-size: 13px; color: var(--v3-text-faint); letter-spacing: 0.1em; }

.pv-foot {
  display: flex; gap: 10px; justify-content: center; margin-top: 72px; padding-top: 24px;
  border-top: 1px solid var(--v3-hairline); font-size: 10.5px; letter-spacing: 0.14em; color: var(--v3-text-faint);
}

@media (max-width: 900px) {
  .swatches { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .cards, .states, .motion-grid { grid-template-columns: 1fr; }
  .pv-head { flex-direction: column; align-items: flex-start; }
  .preview-inner { padding: 42px 22px 60px; }
}
</style>
