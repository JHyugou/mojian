<script setup>
// 创作项目首页：项目列表与新建入口。
// 当前为骨架占位，项目 CRUD 接口完成后接入真实数据。
</script>

<template>
  <section>
    <div class="page-head">
      <h1 class="page-title">创作项目</h1>
      <button class="btn btn-primary" type="button">＋ 新建项目</button>
    </div>
    <hr class="ink-divider" />

    <div class="empty-state card">
      <svg viewBox="0 0 120 48" class="empty-art" aria-hidden="true">
        <path d="M6 40 Q30 8 52 34 Q70 12 88 32 Q100 20 114 38" fill="none"
              stroke="var(--color-dai-deep)" stroke-width="2" opacity="0.6" />
        <circle cx="96" cy="14" r="4" fill="var(--color-cinnabar)" opacity="0.8" />
      </svg>
      <p class="empty-title">远山已备好，静候落笔</p>
      <p class="empty-desc">
        创建一个创作项目，录入人物档案、故事设定与剧情蓝图，<br />
        墨笺将按章节为你组装上下文，辅助生成剧情方案与正文。
      </p>
    </div>
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-title {
  font-size: 22px;
}

.empty-state {
  margin-top: 40px;
  padding: 56px 24px;
  text-align: center;
}

.empty-art {
  width: 160px;
}

.empty-title {
  margin-top: 16px;
  font-family: var(--font-serif);
  font-size: 16px;
  color: var(--color-ink);
}

.empty-desc {
  margin-top: 10px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--color-muted);
}
</style>
