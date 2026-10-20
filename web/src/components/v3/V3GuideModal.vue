<script setup>
import Icon from '../Icon.vue'
import V3Button from './V3Button.vue'
import V3Modal from './V3Modal.vue'

defineProps({
  visible: { type: Boolean, default: false }
})
const emit = defineEmits(['close', 'navigate'])

const steps = [
  {
    no: '一',
    title: '配好模型',
    text: '进入「模型接入」，添加一家服务商并填 Key，再分别给策划和写作选模型。没有真模型时只能看演示，Agent 不会真的思考。'
  },
  {
    no: '二',
    title: '新建一部作品',
    text: '回首页点「新建作品」，填书名、题材和文风。不用先写大纲，后面策划会陪你一起搭。'
  },
  {
    no: '三',
    title: '先和策划聊',
    text: '把脑中的故事说给它：主角是谁、想要什么冲突、先写哪几章。它会读资料，再给几条走向，任选一条或写自己的。'
  },
  {
    no: '四',
    title: '采纳草稿，开始有章',
    text: '人物、设定、蓝图先进入「待确认」；你点采纳才会写进书里。采纳一个「章节」蓝图后，会自动创建对应章节。'
  },
  {
    no: '五',
    title: '切到写作，开始写',
    text: '选章节，写一句「这一章怎么写」的要求，然后开写。正文会流式出现在右侧，可随时停下，不会丢版本。'
  },
  {
    no: '六',
    title: '审稿，再改一轮',
    text: '回策划说「看看这一章」。审稿意见会变成一条条补充要求，写作执行后先进入「待核验」，你确认落实才算完成。'
  }
]
</script>

<template>
  <V3Modal :visible="visible" title="使用指南" width="820px" @close="emit('close')">
    <div class="guide">
      <header class="guide-hero">
        <p class="guide-kicker">A QUIET ROOM FOR LONG STORIES</p>
        <h2>把 AI 当成写作搭档，<br />不是自动写书机。</h2>
        <p class="guide-lead">
          墨笺是一个本地 AI 长篇小说工作台：人物、设定、蓝图、正文和版本放在一处，
          策划 Agent 帮你搭框架、审稿，写作 Agent 按蓝图把故事写出来。所有 AI 改动都看得见、可回退。
        </p>
      </header>

      <section class="guide-roles">
        <article class="guide-role">
          <span class="guide-seal is-planner">策</span>
          <div>
            <h3>策划 Agent</h3>
            <p>定方向、起草人物与设定、拆蓝图、审稿、给写作提修改要求。它负责「这本书往哪走」。</p>
          </div>
        </article>
        <article class="guide-role">
          <span class="guide-seal is-writer">文</span>
          <div>
            <h3>写作 Agent</h3>
            <p>读本章蓝图和写作要求，把故事写成正文；收到审稿补充要求后逐条修改。它负责「这一章怎么写」。</p>
          </div>
        </article>
      </section>

      <section class="guide-flow">
        <div class="guide-section-head">
          <span class="guide-no">01</span>
          <div>
            <h3>第一次使用，走这六步</h3>
            <p>不用一次学完；照着走一遍，比读十页说明快。</p>
          </div>
        </div>
        <div class="guide-steps">
          <article v-for="step in steps" :key="step.no" class="guide-step">
            <span class="step-no">{{ step.no }}</span>
            <div>
              <h4>{{ step.title }}</h4>
              <p>{{ step.text }}</p>
            </div>
          </article>
        </div>
      </section>

      <section class="guide-prompt">
        <div>
          <span class="guide-prompt-label">不知道第一句说什么？</span>
          <p>把下面这句改一改，直接发给策划：</p>
        </div>
        <code>我想写一个「主角身份 + 核心冲突」的故事，先帮我把前三章拆成蓝图。</code>
      </section>

      <section class="guide-safety">
        <span class="guide-section-no">02</span>
        <h3>记住三个安全点</h3>
        <div class="safety-grid">
          <div class="safety-item"><Icon name="checkAll" :size="15" /><span>AI 改人物、设定、蓝图，先出草稿，你采纳才生效。</span></div>
          <div class="safety-item"><Icon name="shield" :size="15" /><span>删除人物、设定、蓝图、章节，都要你确认；不会悄悄删。</span></div>
          <div class="safety-item"><Icon name="clock" :size="15" /><span>正文每次保存都有版本，可以回滚；建议定期导出整本备份。</span></div>
        </div>
      </section>

      <footer class="guide-foot">
        <span>开源仓库：<a href="https://github.com/JHyugou/mojian" target="_blank" rel="noreferrer">github.com/JHyugou/mojian</a></span>
        <span class="guide-shortcut"><kbd>⌘K</kbd> 随时打开命令面板</span>
      </footer>
    </div>

    <template #footer>
      <V3Button variant="ghost" @click="emit('close')">先看看</V3Button>
      <V3Button variant="glass" @click="emit('navigate', '/ai-settings')">去配置模型</V3Button>
      <V3Button @click="emit('navigate', '/workbench')">进入工作台</V3Button>
    </template>
  </V3Modal>
</template>

<style scoped>
.guide { color: var(--v3-text-soft); }
.guide-hero { padding: 4px 0 22px; border-bottom: 1px solid var(--v3-hairline); }
.guide-kicker {
  margin: 0 0 12px;
  font-family: var(--v3-font-mono);
  font-size: 9.5px;
  letter-spacing: 0.24em;
  color: var(--v3-gold);
}
.guide-hero h2 {
  margin: 0 0 12px;
  font-family: var(--v3-font-serif);
  font-size: clamp(26px, 3vw, 36px);
  font-weight: 500;
  line-height: 1.32;
  letter-spacing: 0.04em;
  color: var(--v3-text);
}
.guide-lead { margin: 0; max-width: 680px; font-size: 12.5px; line-height: 1.95; color: var(--v3-text-muted); }
.guide-roles { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 20px 0 24px; }
.guide-role {
  display: flex; align-items: flex-start; gap: 11px;
  padding: 14px 15px;
  border: 1px solid var(--v3-hairline);
  border-radius: var(--v3-r-sm);
  background: var(--v3-surface-2);
}
.guide-seal {
  display: grid; place-items: center; width: 30px; height: 30px; flex-shrink: 0;
  border: 1px solid var(--v3-cinnabar); border-radius: 7px;
  color: var(--v3-cinnabar); font-family: var(--v3-font-serif); font-size: 14px;
  transform: rotate(-3deg);
}
.guide-seal.is-writer { border-color: var(--v3-gold); color: var(--v3-gold); }
.guide-role h3 { margin: 1px 0 4px; font-family: var(--v3-font-serif); font-size: 15px; letter-spacing: 0.06em; color: var(--v3-text); }
.guide-role p { margin: 0; font-size: 11px; line-height: 1.8; color: var(--v3-text-faint); }
.guide-flow { padding-top: 2px; }
.guide-section-head { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 14px; }
.guide-no {
  flex-shrink: 0; padding-top: 3px;
  font-family: var(--v3-font-serif); font-size: 15px; color: var(--v3-gold);
}
.guide-section-head h3 { margin: 0 0 4px; font-family: var(--v3-font-serif); font-size: 18px; font-weight: 500; letter-spacing: 0.04em; color: var(--v3-text); }
.guide-section-head p { margin: 0; font-size: 11px; color: var(--v3-text-faint); }
.guide-steps { display: grid; grid-template-columns: 1fr 1fr; gap: 9px 12px; }
.guide-step {
  display: flex; align-items: flex-start; gap: 9px;
  padding: 10px 12px;
  border-left: 2px solid var(--v3-hairline-gold);
  background: var(--v3-inset);
  border-radius: 0 var(--v3-r-xs) var(--v3-r-xs) 0;
}
.step-no { flex-shrink: 0; font-family: var(--v3-font-serif); font-size: 14px; color: var(--v3-gold); }
.guide-step h4 { margin: 0 0 3px; font-size: 12px; font-weight: 600; color: var(--v3-text); }
.guide-step p { margin: 0; font-size: 10.5px; line-height: 1.75; color: var(--v3-text-faint); }
.guide-prompt {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 16px;
  margin: 22px 0 20px; padding: 13px 15px;
  border: 1px solid var(--v3-hairline-gold);
  border-radius: var(--v3-r-sm);
  background: var(--v3-glow-gold);
}
.guide-prompt-label { font-size: 10.5px; letter-spacing: 0.08em; color: var(--v3-gold); }
.guide-prompt p { margin: 5px 0 0; font-size: 11px; color: var(--v3-text-muted); }
.guide-prompt code {
  flex: 1; max-width: 430px; padding: 8px 10px;
  border: 1px solid var(--v3-hairline); border-radius: var(--v3-r-xs);
  background: var(--v3-surface-1); font-family: var(--v3-font-sans);
  font-size: 10.5px; line-height: 1.7; color: var(--v3-text-soft); white-space: normal;
}
.guide-safety { padding-top: 2px; }
.guide-section-no { font-family: var(--v3-font-mono); font-size: 9px; letter-spacing: 0.18em; color: var(--v3-gold); }
.guide-safety h3 { margin: 5px 0 10px; font-family: var(--v3-font-serif); font-size: 17px; font-weight: 500; color: var(--v3-text); }
.safety-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 9px; }
.safety-item {
  display: flex; gap: 7px; padding: 10px;
  border: 1px solid var(--v3-hairline); border-radius: var(--v3-r-xs);
  background: var(--v3-surface-2);
  font-size: 10px; line-height: 1.7; color: var(--v3-text-faint);
}
.safety-item :deep(.ic) { flex-shrink: 0; margin-top: 1px; color: var(--v3-jade); }
.guide-foot {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  margin-top: 20px; padding-top: 13px; border-top: 1px solid var(--v3-hairline);
  font-size: 10px; color: var(--v3-text-faint);
}
.guide-foot a { color: var(--v3-gold); text-decoration: none; }
.guide-foot a:hover { text-decoration: underline; }
.guide-shortcut kbd {
  margin-right: 5px; padding: 1px 5px;
  border: 1px solid var(--v3-hairline-strong); border-radius: 4px;
  font-family: var(--v3-font-mono); font-size: 9px; color: var(--v3-text-muted);
}
@media (max-width: 760px) {
  .guide-roles, .guide-steps, .safety-grid { grid-template-columns: 1fr; }
  .guide-prompt { flex-direction: column; }
  .guide-prompt code { max-width: 100%; }
  .guide-foot { flex-direction: column; align-items: flex-start; }
}
</style>
