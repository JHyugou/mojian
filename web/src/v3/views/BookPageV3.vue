<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '../../components/Icon.vue'
import V3Ambient from '../../components/v3/V3Ambient.vue'
import V3Button from '../../components/v3/V3Button.vue'
import V3Empty from '../../components/v3/V3Empty.vue'
import V3Tag from '../../components/v3/V3Tag.vue'
import V3Toasts from '../../components/v3/V3Toasts.vue'
import { useV3Theme } from '../theme'
import { useV3Notify } from '../notify'
import { useProjectStore } from '../../stores/project'
import { getBook } from '../../api/book'

/**
 * 成书页（v3）：沉浸式阅读。
 *
 * 设计意图：这一页不提供编辑，只提供"读"。所以去掉外壳，只留一条极窄的顶栏；
 * 正文用宋体、宽行距、限宽 760px；滚动时顶部有一条墨线进度，左侧目录随滚动高亮。
 */
const router = useRouter()
const projectStore = useProjectStore()
const { theme, toggleTheme } = useV3Theme()
const notify = useV3Notify()

const book = ref(null)
const loading = ref(true)
const error = ref('')
const activeId = ref(null)
const scrollRatio = ref(0)
const showToc = ref(true)

const projectId = computed(() => projectStore.currentId)

async function load() {
  if (!projectId.value) {
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    book.value = await getBook(projectId.value)
  } catch (e) {
    error.value = e.message || '读取成书失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  window.addEventListener('scroll', onScroll, { passive: true })
})
onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))

function onScroll() {
  const doc = document.documentElement
  const max = doc.scrollHeight - window.innerHeight
  scrollRatio.value = max > 0 ? Math.min(1, window.scrollY / max) : 0

  // 目录高亮：找到最后一个标题已经滚过顶部的章节
  const chapters = book.value?.chapters || []
  let current = null
  for (const chapter of chapters) {
    const el = document.getElementById('book-ch-' + chapter.id)
    if (el && el.getBoundingClientRect().top < 140) current = chapter.id
  }
  activeId.value = current || chapters[0]?.id || null
}

function scrollToChapter(id) {
  const el = document.getElementById('book-ch-' + id)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function buildText(markdown = false) {
  const b = book.value
  if (!b) return ''
  const lines = []
  if (markdown) {
    lines.push(`# 《${b.projectName}》`, '')
    if (b.genre) lines.push(`> 题材：${b.genre}`)
    if (b.intro) lines.push('', b.intro)
  } else {
    lines.push(`《${b.projectName}》`)
    if (b.genre) lines.push(`题材：${b.genre}`)
    if (b.intro) lines.push('', b.intro)
  }
  lines.push('')
  ;(b.chapters || []).forEach((chapter, index) => {
    lines.push(markdown ? `## 第${index + 1}章 ${chapter.title}` : `第${index + 1}章 ${chapter.title}`)
    lines.push('')
    lines.push(chapter.content || '')
    lines.push('')
  })
  return lines.join('\n')
}

function download(ext, mime, markdown) {
  const text = buildText(markdown)
  const blob = new Blob([text], { type: `${mime};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${book.value.projectName}·成书.${ext}`
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 800)
  notify.success(`已导出 ${a.download}`)
}

function openWorkbench() {
  router.push('/workbench')
}

const fmt = (n) => (n || 0).toLocaleString()

/** 章节标题去掉重复的「第 N 章」前缀：页头已经有序号，正文里再带一遍会很怪 */
function chapterTitle(title) {
  const clean = String(title || '').replace(/^第[一二三四五六七八九十百千零〇\d]+章[\s·:：、.\-]*/, '').trim()
  return clean || String(title || '')
}

/**
 * 正文清洗：有些历史章节在正文开头存了 Markdown 标题（如 `# 瀚海·第一章 神宫往事`），
 * 页头已经有了标题，这里把开头连续的标题行与空行去掉，避免读者看到重复标题。
 */
function cleanContent(content) {
  if (!content) return ''
  const lines = String(content).split('\n')
  while (lines.length) {
    const first = lines[0].trim()
    if (first === '' || /^#{1,6}\s/.test(first)) lines.shift()
    else break
  }
  return lines.join('\n').trim()
}
</script>

<template>
  <div class="v3-root book-page">
    <V3Ambient variant="calm" />

    <header class="book-top">
      <button class="top-back" @click="openWorkbench">
        <Icon name="arrowRight" :size="14" class="back-icon" />
        <span>回到工作台</span>
      </button>
      <span class="top-title">{{ book?.projectName || projectStore.current?.name || '成书' }}</span>
      <div class="top-actions">
        <V3Tag v-if="book">{{ book.chapterCount }} 章</V3Tag>
        <V3Tag v-if="book" tone="gold">{{ fmt(book.totalChars) }} 字</V3Tag>
        <button class="icon-btn" :title="theme === 'inksea' ? '切到素笺' : '切到墨海'" @click="toggleTheme">
          <Icon :name="theme === 'inksea' ? 'sun' : 'moon'" :size="16" />
        </button>
        <button class="icon-btn" title="切换目录" @click="showToc = !showToc">
          <Icon name="layers" :size="16" />
        </button>
      </div>
    </header>
    <div class="progress"><i :style="{ transform: `scaleX(${scrollRatio})` }"></i></div>

    <div v-if="!projectId" class="book-center">
      <V3Empty seal="书" title="还没有打开的作品" desc="回首页选一部作品，再来看成书。" action="回到首页" @action="router.push('/')" />
    </div>
    <div v-else-if="loading" class="book-center"><span class="loading-text">正在装订成书…</span></div>
    <div v-else-if="error" class="book-center">
      <V3Empty seal="误" error title="读取成书失败" :desc="error" action="重试" @action="load" />
    </div>
    <div v-else-if="!book?.chapters?.length" class="book-center">
      <V3Empty
        seal="书"
        title="书尚未成"
        desc="章节还没有被标记为完成。写作面板右上角有「标记完成」，标记后就会出现在这里。"
        action="去写作"
        @action="openWorkbench"
      />
    </div>

    <div v-else class="book-body">
      <Transition name="toc">
        <aside v-if="showToc" class="toc v3-scroll">
          <p class="toc-kicker">目 录</p>
          <button
            v-for="(chapter, index) in book.chapters"
            :key="chapter.id"
            class="toc-item"
            :class="{ active: String(activeId) === String(chapter.id) }"
            @click="scrollToChapter(chapter.id)"
          >
            <span class="toc-no">{{ String(index + 1).padStart(2, '0') }}</span>
            <span class="toc-name">{{ chapter.title }}</span>
            <span class="toc-chars">{{ fmt(chapter.chars) }}</span>
          </button>
        </aside>
      </Transition>

      <article class="reader">
        <header class="book-cover">
          <p class="cover-kicker">墨笺 · 成书</p>
          <h1 class="cover-title">{{ book.projectName }}</h1>
          <p v-if="book.genre" class="cover-genre">{{ book.genre }}</p>
          <p v-if="book.intro" class="cover-intro">{{ book.intro }}</p>
          <div class="cover-meta">
            <span>{{ book.chapterCount }} 章</span>
            <span class="dot">·</span>
            <span>{{ fmt(book.totalChars) }} 字</span>
          </div>
          <div class="cover-actions">
            <V3Button size="sm" variant="glass" @click="download('md', 'text/markdown', true)">
              <template #icon><Icon name="download" :size="14" /></template>
              导出 Markdown
            </V3Button>
            <V3Button size="sm" variant="glass" @click="download('txt', 'text/plain', false)">
              <template #icon><Icon name="download" :size="14" /></template>
              导出 TXT
            </V3Button>
          </div>
          <div class="cover-rule"></div>
        </header>

        <section
          v-for="(chapter, index) in book.chapters"
          :key="chapter.id"
          :id="'book-ch-' + chapter.id"
          class="chapter"
        >
          <header class="chapter-head">
            <span class="chapter-no">第 {{ index + 1 }} 章</span>
            <h2 class="chapter-title">{{ chapterTitle(chapter.title) }}</h2>
            <span class="chapter-chars">{{ fmt(chapter.chars) }} 字</span>
          </header>
          <div class="chapter-text">{{ cleanContent(chapter.content) }}</div>
        </section>

        <footer class="book-end">
          <span class="end-seal" aria-hidden="true">完</span>
          <p>全书完</p>
        </footer>
      </article>
    </div>

    <V3Toasts />
  </div>
</template>

<style scoped>
.book-page {
  min-height: 100vh;
  background: var(--v3-canvas);
  color: var(--v3-text);
}
.book-top {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 18px;
  height: 60px;
  padding: 0 26px;
  border-bottom: 1px solid var(--v3-hairline);
  background: color-mix(in srgb, var(--v3-canvas) 78%, transparent);
  backdrop-filter: blur(16px);
}
.top-back {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-pill);
  background: var(--v3-surface-1);
  color: var(--v3-text-muted);
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
  transition: color var(--v3-dur-2), border-color var(--v3-dur-2);
}
.top-back:hover { color: var(--v3-text); border-color: var(--v3-hairline-gold); }
.back-icon { transform: rotate(180deg); }
.top-title { font-family: var(--v3-font-serif); font-size: 14px; letter-spacing: 0.12em; color: var(--v3-text-soft); }
.top-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.icon-btn {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: 1px solid transparent;
  border-radius: 50%;
  background: transparent;
  color: var(--v3-text-faint);
  cursor: pointer;
}
.icon-btn:hover { color: var(--v3-gold-bright); background: var(--v3-surface-2); }
.progress { position: fixed; top: 60px; left: 0; right: 0; z-index: 21; height: 1px; background: transparent; }
.progress i { display: block; height: 100%; transform-origin: left; background: var(--v3-grad-gold); transition: transform var(--v3-dur-2) linear; }

.book-center { position: relative; z-index: 1; display: grid; place-items: center; min-height: 100vh; }
.loading-text { font-family: var(--v3-font-serif); letter-spacing: 0.2em; color: var(--v3-text-muted); }

/* 内容层必须显式抬到环境背景之上：
 * V3Ambient 是 position: fixed; z-index: 0 的定位元素，会盖住同层级的普通块元素。
 * 首页/工作台都有定位过的内容容器，成书页之前漏了 —— 于是正文"渲染了但看不见"。 */
.book-body { position: relative; z-index: 1; display: flex; align-items: flex-start; max-width: 1240px; margin: 0 auto; padding: 92px 32px 80px; gap: 44px; }
.toc {
  position: sticky;
  top: 92px;
  width: 230px;
  flex-shrink: 0;
  max-height: calc(100vh - 130px);
  padding-right: 12px;
}
.toc-kicker { margin: 0 0 16px; font-size: 10px; letter-spacing: 0.42em; color: var(--v3-gold); }
.toc-item {
  display: flex;
  align-items: baseline;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: var(--v3-r-xs);
  background: transparent;
  color: var(--v3-text-muted);
  font-family: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  transition: background var(--v3-dur-2), color var(--v3-dur-2);
}
.toc-item:hover { background: var(--v3-surface-1); color: var(--v3-text-soft); }
.toc-item.active { background: var(--v3-glow-gold); color: var(--v3-text); }
.toc-no { font-family: var(--v3-font-mono); font-size: 10px; color: var(--v3-text-faint); }
.toc-item.active .toc-no { color: var(--v3-gold); }
.toc-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.toc-chars { font-size: 9.5px; color: var(--v3-text-faint); }

.reader { flex: 1; min-width: 0; max-width: 780px; }

.book-cover { padding: 30px 0 54px; text-align: center; }
.cover-kicker { margin: 0 0 22px; font-size: 10px; letter-spacing: 0.5em; color: var(--v3-gold); }
.cover-title {
  margin: 0;
  font-family: var(--v3-font-serif);
  font-size: clamp(38px, 5vw, 58px);
  font-weight: 600;
  letter-spacing: 0.12em;
  color: var(--v3-text);
}
.cover-genre { margin: 16px 0 0; font-size: 11.5px; letter-spacing: 0.28em; color: var(--v3-text-muted); }
.cover-intro { max-width: 520px; margin: 26px auto 0; font-size: 13px; line-height: 2.1; color: var(--v3-text-soft); }
.cover-meta { margin-top: 30px; font-family: var(--v3-font-mono); font-size: 11px; letter-spacing: 0.08em; color: var(--v3-text-faint); }
.cover-meta .dot { margin: 0 8px; }
.cover-actions { display: flex; justify-content: center; gap: 10px; margin-top: 26px; }
.cover-rule {
  width: 120px;
  height: 1px;
  margin: 44px auto 0;
  background: linear-gradient(90deg, transparent, var(--v3-hairline-gold), transparent);
}

.chapter { padding: 34px 0 20px; scroll-margin-top: 90px; }
.chapter-head {
  display: flex;
  align-items: baseline;
  gap: 14px;
  margin-bottom: 26px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--v3-hairline);
}
.chapter-no { font-size: 10.5px; letter-spacing: 0.24em; color: var(--v3-gold); }
.chapter-title {
  flex: 1;
  margin: 0;
  font-family: var(--v3-font-serif);
  font-size: 23px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--v3-text);
}
.chapter-chars { font-family: var(--v3-font-mono); font-size: 10px; color: var(--v3-text-faint); }
.chapter-text {
  font-family: var(--v3-font-serif);
  font-size: 16px;
  line-height: 2.3;
  letter-spacing: 0.015em;
  color: var(--v3-text-soft);
  white-space: pre-wrap;
  word-break: break-word;
}
.chapter-text ::selection { background: var(--v3-selection); }

.book-end { display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 80px 0 40px; }
.end-seal {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  border: 1.5px solid var(--v3-cinnabar);
  border-radius: 9px;
  color: var(--v3-cinnabar);
  font-family: var(--v3-font-serif);
  font-size: 19px;
  transform: rotate(-4deg);
  opacity: 0.85;
}
.book-end p { margin: 0; font-family: var(--v3-font-serif); font-size: 13px; letter-spacing: 0.4em; color: var(--v3-text-faint); }

.toc-enter-active, .toc-leave-active { transition: width var(--v3-dur-3) var(--v3-ease-out), opacity var(--v3-dur-2); }
.toc-enter-from, .toc-leave-to { width: 0; opacity: 0; }

@media (max-width: 900px) {
  .toc { display: none; }
  .book-body { padding: 84px 18px 60px; }
  .chapter-text { font-size: 15px; line-height: 2.15; }
}
</style>
