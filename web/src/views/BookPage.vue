<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import ProjectGate from '../components/ProjectGate.vue'
import { useProjectStore } from '../stores/project'
import { useMessageStore } from '../stores/message'
import { getBook } from '../api/book'

const projectStore = useProjectStore()
const message = useMessageStore()

const book = ref(null)
const loading = ref(false)
const expandedId = ref(null)

const chapters = computed(() => book.value?.chapters || [])

async function load() {
  if (!projectStore.currentId) return
  loading.value = true
  try {
    book.value = await getBook(projectStore.currentId)
    expandedId.value = null
  } catch (e) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function download(filename, text, mime) {
  const blob = new Blob([text], { type: `${mime};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

function buildText(markdown) {
  const b = book.value
  const lines = []
  lines.push(markdown ? `# 《${b.projectName}》` : `《${b.projectName}》`)
  if (b.genre) lines.push(markdown ? `> 题材：${b.genre}` : `题材：${b.genre}`)
  if (b.intro) lines.push('', b.intro)
  lines.push('')
  b.chapters.forEach((c, i) => {
    const no = `第${i + 1}章`
    lines.push(markdown ? `## ${no} ${c.title}` : `${no} ${c.title}`)
    lines.push('')
    lines.push(c.content || '（本章暂无内容）')
    lines.push('')
  })
  return lines.join('\n')
}

function exportFile(markdown) {
  if (!book.value) return
  const ext = markdown ? 'md' : 'txt'
  const name = `${book.value.projectName}·成书.${ext}`
  download(name, buildText(markdown), markdown ? 'text/markdown' : 'text/plain')
  message.success(`已导出 ${name}`)
}

/** 展开章节：展开后滚动到章节开头 */
async function expandChapter(id) {
  expandedId.value = id
  await nextTick()
  document.getElementById(`book-ch-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** 收起章节：回到目录 */
function collapseChapter() {
  expandedId.value = null
  document.getElementById('book-toc')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<template>
  <ProjectGate>
    <section class="page">
      <div class="page-head">
        <div>
          <h1 class="page-title">成书</h1>
          <p v-if="book" class="page-hint">
            {{ book.projectName }} · {{ book.chapterCount }} 章 · 全文 {{ book.totalChars }} 字
          </p>
        </div>
        <div class="head-actions">
          <button class="btn btn-secondary" type="button" :disabled="loading" @click="load">
            {{ loading ? '装订中…' : '刷新成书' }}
          </button>
          <button class="btn btn-secondary" type="button" :disabled="!book" @click="exportFile(false)">导出 TXT</button>
          <button class="btn btn-primary" type="button" :disabled="!book" @click="exportFile(true)">
            导出 Markdown
          </button>
        </div>
      </div>

      <!-- 目录：仅章节列表，不展示正文 -->
      <div v-if="book && book.chapterCount > 0" id="book-toc" class="card toc">
        <div class="toc-head">
          <span class="toc-title">目 录</span>
          <span class="toc-hint">点击章节标题展开阅读</span>
        </div>
        <button
          v-for="(c, i) in chapters"
          :key="c.id"
          type="button"
          class="toc-row"
          :class="{ open: expandedId === c.id }"
          @click="expandChapter(c.id)"
        >
          <span class="toc-no">第{{ i + 1 }}章</span>
          <span class="toc-name">{{ c.title }}</span>
          <span class="toc-chars">{{ c.chars }} 字</span>
          <span class="toc-arrow">{{ expandedId === c.id ? '▾' : '▸' }}</span>
        </button>
        <div v-if="chapters.length === 0" class="toc-empty">暂无章节</div>
      </div>

      <div v-if="loading" class="page-hint">装订中…</div>
      <div v-else-if="!book || book.chapterCount === 0" class="empty-state card">
        <p class="empty-title">书尚未成</p>
        <p class="empty-desc">到「章节创作」写下第一章，保存后这里会自动装订成册。</p>
      </div>

      <!-- 展开的章节全文 -->
      <template v-for="(c, i) in chapters" :key="c.id">
        <article v-if="expandedId === c.id" :id="`book-ch-${c.id}`" class="chapter card">
          <h2 class="chapter-title serif">
            <span class="chapter-no">第{{ i + 1 }}章</span>{{ c.title }}
          </h2>
          <div class="chapter-body serif">{{ c.content || '（本章暂无内容）' }}</div>
          <div class="chapter-foot">
            <span class="foot-chars">本章 {{ c.chars }} 字</span>
            <button class="btn btn-secondary" type="button" @click="collapseChapter">收起章节</button>
          </div>
        </article>
      </template>
    </section>
  </ProjectGate>
</template>

<style scoped>
.page {
  padding: 20px 24px;
  overflow-y: auto;
  height: 100%;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 14px;
}

.head-actions {
  display: flex;
  gap: 10px;
}

.toc {
  padding: 6px 0;
  margin-bottom: 16px;
}

.toc-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 10px 20px 6px;
}

.toc-title {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.3em;
  color: var(--color-muted);
}

.toc-hint {
  font-size: 12px;
  color: var(--color-muted);
  opacity: 0.85;
}

.toc-row {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  padding: 11px 20px;
  border: none;
  border-top: 1px dashed var(--color-line);
  background: none;
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  transition: background 0.15s ease;
}

.toc-row:hover {
  background: rgba(163, 63, 48, 0.05);
}

.toc-row.open {
  background: rgba(163, 63, 48, 0.08);
}

.toc-no {
  flex: none;
  font-size: 12.5px;
  color: var(--color-cinnabar);
  letter-spacing: 0.08em;
}

.toc-name {
  flex: 1;
  font-size: 15px;
  color: var(--color-ink);
  font-family: var(--font-kai);
  letter-spacing: 0.05em;
}

.toc-chars {
  flex: none;
  font-size: 12px;
  color: var(--color-muted);
}

.toc-arrow {
  flex: none;
  color: var(--color-cinnabar);
  font-size: 12px;
}

.toc-empty {
  padding: 10px 20px;
  font-size: 13px;
  color: var(--color-muted);
}

.chapter {
  padding: 26px 34px;
  margin-bottom: 18px;
}

.chapter-title {
  font-size: 21px;
  letter-spacing: 0.1em;
  margin-bottom: 16px;
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.chapter-no {
  font-size: 13px;
  color: var(--color-cinnabar);
  letter-spacing: 0.2em;
}

.chapter-body {
  white-space: pre-wrap;
  font-size: 16px;
  line-height: 2.3;
  color: var(--color-ink);
}

.chapter-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--color-line);
}

.foot-chars {
  font-size: 12.5px;
  color: var(--color-muted);
}
</style>
