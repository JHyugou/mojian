<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '../../components/Icon.vue'
import V3Ambient from '../../components/v3/V3Ambient.vue'
import V3Button from '../../components/v3/V3Button.vue'
import V3Card from '../../components/v3/V3Card.vue'
import V3Field from '../../components/v3/V3Field.vue'
import V3Input from '../../components/v3/V3Input.vue'
import V3Textarea from '../../components/v3/V3Textarea.vue'
import V3Modal from '../../components/v3/V3Modal.vue'
import V3Tag from '../../components/v3/V3Tag.vue'
import V3Skeleton from '../../components/v3/V3Skeleton.vue'
import V3Empty from '../../components/v3/V3Empty.vue'
import V3Toasts from '../../components/v3/V3Toasts.vue'
import V3CommandPalette from '../../components/v3/V3CommandPalette.vue'
import { useV3Theme } from '../theme'
import { vReveal } from '../motion'
import { useV3Notify } from '../notify'
import { useProjectStore } from '../../stores/project'
import { createProject, deleteProject, listProjects } from '../../api/project'
import { exportProject, importProject } from '../../api/share'
import { listChapters } from '../../api/chapter'

const router = useRouter()
const projectStore = useProjectStore()
const { theme, toggleTheme } = useV3Theme()
const notify = useV3Notify()

const projects = ref([])
const loading = ref(true)
const loadError = ref('')
const chapterCounts = reactive({})
const heroChars = computed(() => '把一部小说，写成它该有的样子'.split(''))

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const list = (await listProjects()) || []
    projects.value = list
    await Promise.allSettled(
      list.map(async (project) => {
        try {
          const chapters = (await listChapters(project.id)) || []
          chapterCounts[project.id] = chapters.length
        } catch {
          chapterCounts[project.id] = null
        }
      })
    )
  } catch (e) {
    loadError.value = e.message || '读取项目失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

function enterProject(project) {
  projectStore.select(project)
  router.push('/workbench')
}

// ==================== 新建项目 ====================
const showCreate = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '', genre: '', style: '', intro: '', writingRules: '' })

function openCreate() {
  Object.assign(createForm, { name: '', genre: '', style: '', intro: '', writingRules: '' })
  showCreate.value = true
}

async function submitCreate() {
  const name = createForm.name.trim()
  if (!name) {
    notify.error('先给这本书起个名字')
    return
  }
  creating.value = true
  try {
    const created = await createProject({ ...createForm, name })
    showCreate.value = false
    notify.success(`《${name}》已建好，可以落笔了`)
    projects.value = [created, ...projects.value]
    chapterCounts[created.id] = 0
    enterProject(created)
  } catch (e) {
    notify.error(e.message || '创建失败')
  } finally {
    creating.value = false
  }
}

// ==================== 导出 / 删除 ====================
const menuFor = ref(null)
const exportingId = ref(null)
const deleteTarget = ref(null)
const deleting = ref(false)

function toggleMenu(event, project) {
  event.stopPropagation()
  menuFor.value = menuFor.value === project.id ? null : project.id
}

async function doExport(project) {
  menuFor.value = null
  exportingId.value = project.id
  try {
    const payload = await exportProject(project.id, ['characters', 'settings', 'outline', 'chapters'])
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${project.name}·项目导出.json`
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 800)
    notify.success('已导出项目 JSON')
  } catch (e) {
    notify.error(e.message || '导出失败')
  } finally {
    exportingId.value = null
  }
}

function askDelete(project) {
  menuFor.value = null
  deleteTarget.value = project
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    const target = deleteTarget.value
    await deleteProject(target.id)
    projects.value = projects.value.filter((p) => String(p.id) !== String(target.id))
    if (String(projectStore.currentId) === String(target.id)) projectStore.clear()
    notify.success('项目已删除')
    deleteTarget.value = null
  } catch (e) {
    notify.error(e.message || '删除失败')
  } finally {
    deleting.value = false
  }
}

// ==================== 导入项目 ====================
const showImport = ref(false)
const importing = ref(false)
const importText = ref('')
const importFileName = ref('')

function pickImportFile(event) {
  const file = event.target.files?.[0]
  if (!file) return
  file.text().then((text) => {
    importText.value = text
    importFileName.value = file.name
  })
}

async function confirmImport() {
  const raw = importText.value.trim()
  if (!raw) {
    notify.error('请选择 JSON 文件或直接粘贴内容')
    return
  }
  let parsed
  try {
    parsed = JSON.parse(raw)
  } catch {
    notify.error('JSON 格式不正确')
    return
  }
  if (parsed.format !== 'mojian-project/v1') {
    notify.error('这不是墨笺的项目导出文件')
    return
  }
  importing.value = true
  try {
    const result = await importProject(parsed)
    const counts = result?.counts || {}
    notify.success(
      `已导入「${result?.projectName || '未命名项目'}」：` +
      `人物 ${counts.characters ?? 0} / 设定 ${counts.settings ?? 0} / ` +
      `蓝图 ${counts.outline ?? 0} / 章节 ${counts.chapters ?? 0}`
    )
    showImport.value = false
    importText.value = ''
    importFileName.value = ''
    await load()
  } catch (e) {
    notify.error(e.message || '导入失败')
  } finally {
    importing.value = false
  }
}

// ==================== 命令面板 ====================
const paletteOpen = ref(false)
const paletteItems = computed(() => [
  { id: 'new-project', label: '新建创作项目', hint: '开始一本新书', icon: 'plus', action: openCreate },
  { id: 'import-project', label: '导入项目 JSON', hint: '从导出文件恢复', icon: 'upload', action: () => (showImport.value = true) },
  { id: 'toggle-theme', label: theme.value === 'inksea' ? '切换到素笺（亮色）' : '切换到墨海（暗色）', hint: '主题', icon: theme.value === 'inksea' ? 'sun' : 'moon', action: toggleTheme },
  { id: 'settings', label: '偏好设置', hint: '主题 / 思维链 / 发送键', icon: 'gear', action: () => router.push('/settings') },
  { id: 'ai-settings', label: '模型接入', hint: '接口地址与密钥', icon: 'command', action: () => router.push('/ai-settings') },
  ...projects.value.map((p) => ({
    id: 'project-' + p.id,
    label: p.name,
    hint: p.genre || '打开项目',
    icon: 'book',
    action: () => enterProject(p)
  }))
])

function onPaletteSelect(item) {
  paletteOpen.value = false
  item.action?.()
}

function onGlobalKeydown(event) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    paletteOpen.value = true
  }
}

onMounted(() => document.addEventListener('keydown', onGlobalKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onGlobalKeydown))
const closeCardMenu = () => (menuFor.value = null)
onMounted(() => document.addEventListener('click', closeCardMenu))
onBeforeUnmount(() => document.removeEventListener('click', closeCardMenu))

const fmtTime = (value) => {
  if (!value) return '刚刚'
  const date = new Date(String(value).replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric' })
}

const totalChapters = computed(() =>
  Object.values(chapterCounts).reduce((sum, n) => sum + (Number(n) || 0), 0)
)
</script>

<template>
  <div class="v3-root v3-landing">
    <V3Ambient variant="hero" />

    <header class="ld-top">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 32 32">
            <rect width="32" height="32" rx="10" fill="none" stroke="var(--v3-hairline-gold)" />
            <path d="M7 23 L13 12 L18 20 L21.5 15 L26 23 Z" fill="var(--v3-gold)" opacity=".85" />
            <circle cx="23.5" cy="9" r="2.4" fill="var(--v3-cinnabar)" />
          </svg>
        </span>
        <div class="brand-text">
          <span class="brand-name">墨笺</span>
          <span class="brand-sub">AI 长篇小说创作工作台</span>
        </div>
      </div>

      <div class="ld-top-actions">
        <button class="ghost-pill" title="命令面板（Ctrl/⌘ + K）" @click="paletteOpen = true">
          <Icon name="command" :size="14" />
          <span>命令</span>
          <kbd>⌘K</kbd>
        </button>
        <button class="icon-btn" :title="theme === 'inksea' ? '切到素笺' : '切到墨海'" @click="toggleTheme">
          <Icon :name="theme === 'inksea' ? 'sun' : 'moon'" :size="17" />
        </button>
        <button class="icon-btn" title="偏好设置" @click="router.push('/settings')">
          <Icon name="gear" :size="17" />
        </button>
      </div>
    </header>

    <main class="ld-main">
      <section class="hero">
        <p class="hero-eyebrow" v-reveal>写给长篇小说的创作工作台</p>
        <h1 class="hero-title" aria-label="把一部小说，写成它该有的样子">
          <span
            v-for="(char, i) in heroChars"
            :key="i"
            class="hero-char"
            :class="{ 'is-gold': i >= 6 && i <= 9 }"
            :style="{ '--char-delay': 90 + i * 34 + 'ms' }"
            aria-hidden="true"
          >{{ char }}</span>
        </h1>
        <p class="hero-sub" v-reveal="{ delay: 120 }">
          人物、设定、蓝图、前情都在一处。策划 Agent 陪你定走向，写作 Agent 把蓝图写成正文 ——
          你的每一次决定，都留在书里。
        </p>

        <div class="hero-actions" v-reveal="{ delay: 200 }">
          <V3Button size="lg" @click="openCreate">
            开始创作
            <template #icon><Icon name="quill" :size="15" /></template>
          </V3Button>
          <V3Button size="lg" variant="glass" @click="showImport = true">
            导入项目
            <template #icon><Icon name="upload" :size="15" /></template>
          </V3Button>
        </div>

        <dl class="hero-stats" v-reveal="{ delay: 280 }">
          <div class="stat">
            <dt>作品</dt>
            <dd>{{ loading ? '—' : projects.length }}</dd>
          </div>
          <div class="stat">
            <dt>章节</dt>
            <dd>{{ loading ? '—' : totalChapters }}</dd>
          </div>
          <div class="stat">
            <dt>数据位置</dt>
            <dd class="is-text">本机</dd>
          </div>
        </dl>

        <div class="hero-scroll-hint" aria-hidden="true">
          <span>继续往下</span>
          <i></i>
        </div>
      </section>

      <section class="shelf">
        <header class="shelf-head" v-reveal>
          <h2 class="shelf-title">继续创作</h2>
          <span class="shelf-hint">{{ projects.length ? `${projects.length} 部作品` : '' }}</span>
        </header>

        <div v-if="loading" class="grid">
          <V3Card v-for="i in 3" :key="i" class="skeleton-card">
            <V3Skeleton :lines="4" />
          </V3Card>
        </div>

        <V3Empty
          v-else-if="loadError"
          seal="误"
          error
          title="读取作品失败"
          :desc="loadError"
          action="重试"
          @action="load"
        />

        <div v-else class="grid">
          <article
            v-for="(project, index) in projects"
            :key="project.id"
            class="project-card-wrap"
            v-reveal="{ delay: 40 + index * 55 }"
          >
            <V3Card class="project-card" material="glass" interactive tilt @click="enterProject(project)">
              <div class="pc-top">
                <span class="pc-index">{{ String(index + 1).padStart(2, '0') }}</span>
                <V3Tag v-if="project.genre" tone="gold">{{ project.genre }}</V3Tag>
                <span class="pc-spacer"></span>
                <button class="pc-menu-btn" title="更多" @click="toggleMenu($event, project)">
                  <Icon name="dots" :size="15" />
                </button>
              </div>

              <h3 class="pc-title">{{ project.name }}</h3>
              <p class="pc-intro">{{ project.intro || '这本书还没有简介，进去写点什么吧。' }}</p>

              <div class="pc-foot">
                <span class="pc-meta">
                  <Icon name="layers" :size="13" />
                  {{ chapterCounts[project.id] ?? 0 }} 章
                </span>
                <span class="pc-meta">
                  <Icon name="clock" :size="13" />
                  {{ fmtTime(project.updatedAt) }}
                </span>
                <span class="pc-enter">
                  继续写
                  <Icon name="arrowRight" :size="13" />
                </span>
              </div>

              <Transition name="pc-menu">
                <div v-if="menuFor === project.id" class="pc-menu" @click.stop>
                  <button class="pc-menu-item" @click="doExport(project)">
                    <Icon name="download" :size="14" />
                    {{ exportingId === project.id ? '导出中…' : '导出项目' }}
                  </button>
                  <button class="pc-menu-item is-danger" @click="askDelete(project)">
                    <Icon name="trash" :size="14" />
                    删除项目
                  </button>
                </div>
              </Transition>
            </V3Card>
          </article>

          <article class="project-card-wrap" v-reveal="{ delay: 40 + projects.length * 55 }">
            <button class="new-card" @click="openCreate">
              <span class="new-plus"><Icon name="plus" :size="22" /></span>
              <span class="new-title">新建一部作品</span>
              <span class="new-hint">从一句话开始，剩下的交给时间</span>
            </button>
          </article>
        </div>
      </section>

      <footer class="ld-foot" v-reveal>
        <span>墨笺 · 本地优先的单用户创作工作台</span>
        <span class="foot-sep">·</span>
        <span>数据存在你自己的机器上</span>
      </footer>
    </main>

    <V3Modal :visible="showCreate" title="新建创作项目" width="620px" @close="showCreate = false">
      <V3Field label="书名" required>
        <V3Input v-model="createForm.name" placeholder="如：山河故人" :maxlength="100" @enter="submitCreate" />
      </V3Field>
      <V3Field label="题材" hint="可留空">
        <V3Input v-model="createForm.genre" placeholder="仙侠 / 悬疑 / 都市 / 西幻…" :maxlength="50" />
      </V3Field>
      <V3Field label="整体文风" hint="会注入每一次生成">
        <V3Input v-model="createForm.style" placeholder="如：冷峻克制，重意象与留白" :maxlength="200" />
      </V3Field>
      <V3Field label="简介">
        <V3Textarea v-model="createForm.intro" :rows="3" :maxlength="1000" placeholder="一句话说明这个故事" />
      </V3Field>
      <V3Field label="默认写作规则" hint="全局生效，可后改">
        <V3Textarea
          v-model="createForm.writingRules"
          :rows="3"
          placeholder="如：避免现代词汇；每章 2000 字左右；第三人称限知视角"
        />
      </V3Field>
      <template #footer>
        <V3Button variant="ghost" @click="showCreate = false">取消</V3Button>
        <V3Button :loading="creating" @click="submitCreate">创建并进入</V3Button>
      </template>
    </V3Modal>

    <V3Modal :visible="showImport" title="导入项目" width="600px" @close="showImport = false">
      <p class="import-hint">
        选择墨笺导出的项目 JSON，或直接粘贴内容。导入会创建一本新书，不会覆盖现有作品。
      </p>
      <label class="import-file">
        <input type="file" accept=".json,application/json" @change="pickImportFile" />
        <Icon name="upload" :size="18" />
        <span>{{ importFileName || '点击选择 .json 文件' }}</span>
      </label>
      <V3Textarea v-model="importText" :rows="8" placeholder="或在此粘贴导出的 JSON 内容…" :resize="false" />
      <template #footer>
        <V3Button variant="ghost" @click="showImport = false">取消</V3Button>
        <V3Button :loading="importing" @click="confirmImport">导入</V3Button>
      </template>
    </V3Modal>

    <V3Modal :visible="!!deleteTarget" title="删除项目" width="480px" @close="deleteTarget = null">
      <p class="danger-text">
        确定删除「{{ deleteTarget?.name }}」吗？<br />
        人物、设定、蓝图、章节与全部版本会一并删除，<b>不可恢复</b>。
      </p>
      <template #footer>
        <V3Button variant="ghost" @click="deleteTarget = null">取消</V3Button>
        <V3Button variant="danger" :loading="deleting" @click="confirmDelete">确认删除</V3Button>
      </template>
    </V3Modal>

    <V3CommandPalette
      :visible="paletteOpen"
      :items="paletteItems"
      @close="paletteOpen = false"
      @select="onPaletteSelect"
    />
    <V3Toasts />
  </div>
</template>

<style scoped>
.v3-landing {
  position: relative;
  min-height: 100vh;
  background: var(--v3-canvas);
  color: var(--v3-text);
  overflow-x: hidden;
}

.ld-top {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 26px 42px;
}
.brand { display: flex; align-items: center; gap: 12px; }
.brand-mark { width: 34px; height: 34px; display: block; }
.brand-mark svg { width: 100%; height: 100%; }
.brand-text { display: flex; flex-direction: column; gap: 2px; }
.brand-name { font-family: var(--v3-font-serif); font-size: 19px; letter-spacing: 0.2em; color: var(--v3-text); }
.brand-sub { font-size: 10px; letter-spacing: 0.22em; color: var(--v3-text-faint); }
.ld-top-actions { display: flex; align-items: center; gap: 10px; }

.ghost-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 14px;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: var(--v3-r-pill);
  background: var(--v3-surface-1);
  color: var(--v3-text-muted);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  backdrop-filter: blur(12px);
  transition: color var(--v3-dur-2), border-color var(--v3-dur-2), transform var(--v3-dur-2) var(--v3-ease-spring);
}
.ghost-pill:hover { color: var(--v3-text); border-color: var(--v3-hairline-gold); transform: translateY(-1px); }
.ghost-pill kbd {
  padding: 1px 5px;
  border-radius: 4px;
  border: 1px solid var(--v3-hairline-strong);
  font-family: var(--v3-font-mono);
  font-size: 10px;
}
.icon-btn {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border: 1px solid var(--v3-hairline-strong);
  border-radius: 50%;
  background: var(--v3-surface-1);
  color: var(--v3-text-muted);
  cursor: pointer;
  backdrop-filter: blur(12px);
  transition: color var(--v3-dur-2), border-color var(--v3-dur-2), transform var(--v3-dur-2) var(--v3-ease-spring);
}
.icon-btn:hover { color: var(--v3-gold-bright); border-color: var(--v3-hairline-gold); transform: translateY(-1px); }

.ld-main {
  position: relative;
  z-index: 1;
  max-width: 1180px;
  margin: 0 auto;
  padding: 0 42px 64px;
}

.hero { position: relative; padding: 12vh 0 10vh; }
/* 标题上方一团极淡的金色顶光：像展签打在纸上，让首屏有"被照亮"的中心 */
.hero::before {
  content: '';
  position: absolute;
  left: 50%;
  top: -18vh;
  width: 74vw;
  height: 74vh;
  transform: translateX(-50%);
  background: radial-gradient(ellipse at 50% 42%, var(--v3-glow-gold), transparent 62%);
  opacity: 0.5;
  pointer-events: none;
  filter: blur(10px);
}
.hero-eyebrow {
  margin: 0 0 26px;
  font-size: 11.5px;
  letter-spacing: 0.42em;
  color: var(--v3-gold);
  text-transform: uppercase;
}
.hero-title {
  margin: 0;
  font-family: var(--v3-font-serif);
  font-size: clamp(38px, 6.2vw, 76px);
  font-weight: 600;
  line-height: 1.22;
  letter-spacing: 0.01em;
  color: var(--v3-text);
}
.hero-char {
  display: inline-block;
  opacity: 0;
  transform: translate3d(0, 0.5em, 0) rotate(2deg);
  filter: blur(10px);
  animation: hero-char 900ms var(--v3-ease-out) forwards;
  animation-delay: var(--char-delay);
}
.hero-char.is-gold {
  background: var(--v3-grad-gold);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
@keyframes hero-char {
  to { opacity: 1; transform: translate3d(0, 0, 0) rotate(0); filter: blur(0); }
}
.hero-sub {
  max-width: 620px;
  margin: 30px 0 0;
  font-size: 15px;
  line-height: 2.1;
  letter-spacing: 0.02em;
  color: var(--v3-text-soft);
}
.hero-actions { display: flex; align-items: center; gap: 14px; margin-top: 42px; }
.hero-stats {
  display: flex;
  gap: 56px;
  margin: 64px 0 0;
  padding-top: 26px;
  border-top: 1px solid var(--v3-hairline);
}
.stat dt { font-size: 10.5px; letter-spacing: 0.24em; color: var(--v3-text-faint); }
.stat dd {
  margin: 8px 0 0;
  font-family: var(--v3-font-serif);
  font-size: 30px;
  letter-spacing: 0.04em;
  color: var(--v3-text);
}
.stat dd.is-text { font-size: 22px; color: var(--v3-gold-bright); }

.hero-scroll-hint {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 72px;
  font-size: 10.5px;
  letter-spacing: 0.3em;
  color: var(--v3-text-faint);
}
.hero-scroll-hint i {
  display: block;
  width: 46px;
  height: 1px;
  background: linear-gradient(90deg, var(--v3-hairline-strong), transparent);
  position: relative;
}
.hero-scroll-hint i::after {
  content: '';
  position: absolute;
  left: 0;
  top: -2px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--v3-gold);
  animation: scroll-dot 2.4s var(--v3-ease-in-out) infinite;
}
@keyframes scroll-dot {
  0% { transform: translateX(0); opacity: 0; }
  30% { opacity: 1; }
  100% { transform: translateX(46px); opacity: 0; }
}

.shelf { margin-top: 4vh; }
.shelf-head { display: flex; align-items: baseline; gap: 14px; margin-bottom: 24px; }
.shelf-title { margin: 0; font-family: var(--v3-font-serif); font-size: 22px; font-weight: 600; letter-spacing: 0.08em; }
.shelf-hint { font-size: 11.5px; letter-spacing: 0.16em; color: var(--v3-text-faint); }

.grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px; }
.skeleton-card { min-height: 220px; }

.project-card-wrap { min-width: 0; }
.project-card {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 240px;
  padding: 22px !important;
  overflow: visible;
}
.pc-top { display: flex; align-items: center; gap: 10px; }
.pc-index { font-family: var(--v3-font-serif); font-size: 13px; letter-spacing: 0.12em; color: var(--v3-gold); opacity: 0.85; }
.pc-spacer { flex: 1; }
.pc-menu-btn {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--v3-text-faint);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--v3-dur-2), background var(--v3-dur-2), color var(--v3-dur-2);
}
.project-card:hover .pc-menu-btn { opacity: 1; }
.pc-menu-btn:hover { background: var(--v3-surface-2); color: var(--v3-text); }
.pc-title { margin: 26px 0 0; font-family: var(--v3-font-serif); font-size: 24px; font-weight: 600; letter-spacing: 0.04em; color: var(--v3-text); }
.pc-intro {
  margin: 12px 0 0;
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--v3-text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.pc-foot {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: auto;
  padding-top: 18px;
  border-top: 1px solid var(--v3-hairline);
  font-size: 11px;
  color: var(--v3-text-faint);
}
.pc-meta { display: inline-flex; align-items: center; gap: 6px; }
.pc-enter {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-left: auto;
  color: var(--v3-gold);
  letter-spacing: 0.08em;
  opacity: 0;
  transform: translateX(-6px);
  transition: opacity var(--v3-dur-2), transform var(--v3-dur-3) var(--v3-ease-spring);
}
.project-card:hover .pc-enter { opacity: 1; transform: translateX(0); }

.pc-menu {
  position: absolute;
  right: 16px;
  top: 56px;
  z-index: 20;
  min-width: 150px;
  padding: 6px;
  border-radius: var(--v3-r-sm);
  border: 1px solid var(--v3-hairline-strong);
  background: var(--v3-surface-solid);
  box-shadow: var(--v3-shadow-float);
}
.pc-menu-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 9px 11px;
  border: 0;
  border-radius: var(--v3-r-xs);
  background: transparent;
  color: var(--v3-text-soft);
  font-family: inherit;
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}
.pc-menu-item:hover { background: var(--v3-surface-2); color: var(--v3-text); }
.pc-menu-item.is-danger:hover { color: var(--v3-cinnabar); }
.pc-menu-enter-active, .pc-menu-leave-active { transition: opacity var(--v3-dur-1), transform var(--v3-dur-2) var(--v3-ease-spring); }
.pc-menu-enter-from, .pc-menu-leave-to { opacity: 0; transform: translate3d(0, -6px, 0) scale(0.98); }

.new-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  height: 240px;
  border: 1px dashed var(--v3-hairline-strong);
  border-radius: var(--v3-r-lg);
  background: transparent;
  color: var(--v3-text-muted);
  font-family: inherit;
  cursor: pointer;
  transition: border-color var(--v3-dur-3), background var(--v3-dur-3), transform var(--v3-dur-3) var(--v3-ease-spring);
}
.new-card:hover {
  border-color: var(--v3-hairline-gold);
  background: var(--v3-glow-gold);
  transform: translateY(-3px);
  color: var(--v3-gold-bright);
}
.new-plus {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  border: 1px solid currentColor;
  margin-bottom: 4px;
  transition: transform var(--v3-dur-3) var(--v3-ease-spring);
}
.new-card:hover .new-plus { transform: rotate(90deg) scale(1.05); }
.new-title { font-family: var(--v3-font-serif); font-size: 16px; letter-spacing: 0.1em; }
.new-hint { font-size: 11.5px; color: var(--v3-text-faint); }

.ld-foot {
  display: flex;
  gap: 10px;
  justify-content: center;
  margin-top: 84px;
  padding-top: 26px;
  border-top: 1px solid var(--v3-hairline);
  font-size: 10.5px;
  letter-spacing: 0.14em;
  color: var(--v3-text-faint);
}
.foot-sep { opacity: 0.5; }

.import-hint { margin: 0 0 16px; font-size: 12.5px; line-height: 1.9; color: var(--v3-text-muted); }
.import-file {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px;
  margin-bottom: 14px;
  border: 1px dashed var(--v3-hairline-strong);
  border-radius: var(--v3-r-sm);
  color: var(--v3-text-muted);
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color var(--v3-dur-2), color var(--v3-dur-2);
}
.import-file:hover { border-color: var(--v3-hairline-gold); color: var(--v3-gold-bright); }
.import-file input { display: none; }
.danger-text { margin: 0; font-size: 13px; line-height: 2; color: var(--v3-text-soft); }
.danger-text b { color: var(--v3-cinnabar); }

@media (max-width: 1080px) {
  .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .hero-stats { gap: 40px; }
}
@media (max-width: 720px) {
  .ld-top { padding: 20px 22px; }
  .ld-main { padding: 0 22px 48px; }
  .grid { grid-template-columns: 1fr; }
  .hero { padding: 8vh 0 6vh; }
  .hero-stats { gap: 28px; }
  .brand-sub { display: none; }
  .ghost-pill span { display: none; }
}
</style>
