<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '../../components/Icon.vue'
import V3Button from '../../components/v3/V3Button.vue'
import V3Card from '../../components/v3/V3Card.vue'
import V3Field from '../../components/v3/V3Field.vue'
import V3Input from '../../components/v3/V3Input.vue'
import V3Textarea from '../../components/v3/V3Textarea.vue'
import V3Modal from '../../components/v3/V3Modal.vue'
import V3Tag from '../../components/v3/V3Tag.vue'
import V3Skeleton from '../../components/v3/V3Skeleton.vue'
import V3Empty from '../../components/v3/V3Empty.vue'
import { animateCount, vReveal } from '../motion'
import { useV3Notify } from '../notify'
import { useShellCommands } from '../shellBus'
import { useProjectStore } from '../../stores/project'
import { createProject, deleteProject, listProjects } from '../../api/project'
import { exportProject, importProject } from '../../api/share'
import { listChapters } from '../../api/chapter'

const router = useRouter()
const projectStore = useProjectStore()
const notify = useV3Notify()

const projects = ref([])
const loading = ref(true)
const loadError = ref('')
const chapterCounts = reactive({})
const heroChars = computed(() => '把一部小说，写成它该有的样子'.split(''))
// 统计数字用缓动滚上去，比静态数字多一点"作品集"的仪式感
const shownProjects = ref(0)
const shownChapters = ref(0)
const totalChapters = computed(() =>
  Object.values(chapterCounts).reduce((sum, n) => sum + (Number(n) || 0), 0)
)

function animateStats() {
  animateCount(shownProjects.value, projects.value.length, 900, (n) => (shownProjects.value = n))
  animateCount(shownChapters.value, totalChapters.value, 1200, (n) => (shownChapters.value = n))
}

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
    animateStats()
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

// ==================== 主页专属命令（注册进外壳的命令面板） ====================
const { set: setShellCommands, clear: clearShellCommands } = useShellCommands()

const closeCardMenu = () => (menuFor.value = null)
onMounted(() => {
  document.addEventListener('click', closeCardMenu)
  setShellCommands([
    { id: 'new-project', label: '新建创作项目', hint: '开始一本新书', icon: 'plus', action: openCreate },
    { id: 'import-project', label: '导入项目 JSON', hint: '从导出文件恢复', icon: 'upload', action: () => (showImport.value = true) }
  ])
})
onBeforeUnmount(() => {
  document.removeEventListener('click', closeCardMenu)
  clearShellCommands()
})

const fmtTime = (value) => {
  if (!value) return '刚刚'
  const date = new Date(String(value).replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric' })
}
</script>

<template>
  <!--
    首页内容住在外壳的 .ws-content 里（外壳在 App.vue 常驻）。
    这里只负责自己的滚动区、内容与弹窗，顶栏动作 Teleport 到外壳顶栏。
  -->
  <div class="landing-scroll">
    <Teleport to="#shell-topbar">
      <button class="topbar-create" @click="openCreate">
        <Icon name="plus" :size="13" />
        <span>新建作品</span>
      </button>
    </Teleport>

    <main class="ld-main">
      <section class="hero">
        <!-- 装饰层：水墨远山、巨字水印、朱砂印。这里刻意不放跟随鼠标的光效：
             首页是"看作品"的地方，光标动效会持续抢注意力。 -->
        <div class="hero-ink" aria-hidden="true">
          <svg viewBox="0 0 1440 320" preserveAspectRatio="none">
            <path d="M0 320 L150 214 Q236 150 318 216 L420 286 L540 196 Q640 122 742 200 L852 280 L980 190 Q1080 118 1190 198 L1300 262 L1440 214 L1440 320 Z" />
            <path d="M0 320 L210 262 Q320 214 448 272 L620 240 Q748 202 880 258 L1040 240 Q1190 212 1320 264 L1440 250 L1440 320 Z" />
          </svg>
        </div>
        <span class="hero-watermark" aria-hidden="true">墨</span>
        <span class="hero-seal" aria-hidden="true">墨笺</span>

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
            <dd>{{ loading ? '—' : shownProjects }}</dd>
          </div>
          <div class="stat">
            <dt>章节</dt>
            <dd>{{ loading ? '—' : shownChapters }}</dd>
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
            <V3Card class="project-card" material="glass" interactive @click="enterProject(project)">
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
  </div>
</template>

<style scoped>
/*
 * 页面内容住在外壳的 .ws-content 里：自身负责纵向滚动，
 * 高度铺满可用区域，不再用 100vh（那会把顶栏高度也算进去，导致多出一截）。
 */
.landing-scroll {
  position: relative;
  z-index: 1;
  flex: 1;
  min-width: 0;
  height: 100%;
  overflow-x: hidden;
  overflow-y: auto;
  color: var(--v3-text);
}

/* 顶栏里的常驻动作（Teleport 进 #shell-topbar） */
.topbar-create {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--v3-hairline-gold);
  border-radius: var(--v3-r-pill);
  background: var(--v3-glow-gold);
  color: var(--v3-gold-bright);
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
  transition: color var(--v3-dur-2), border-color var(--v3-dur-2), transform var(--v3-dur-2);
}
.topbar-create:hover { color: var(--v3-text); border-color: var(--v3-hairline-gold); transform: translateY(-1px); }

.ld-main {
  position: relative;
  z-index: 1;
  max-width: 1180px;
  margin: 0 auto;
  padding: 0 42px 64px;
}

.hero {
  position: relative;
  padding: 12vh 0 10vh;
  isolation: isolate;
}
/* 标题上方一团极淡的金色顶光。
 * 宽度必须是"整页"而不是 hero 的 74vw：hero 限宽居中后两侧会留下一道没有光晕的
 * 竖直色差（左侧尤其明显）。用 100vw 让它铺满内容区，超出部分由 overflow 裁掉。 */
.hero::before {
  content: '';
  position: absolute;
  left: 50%;
  top: -18vh;
  width: 100vw;
  height: 74vh;
  transform: translateX(-50%);
  background: radial-gradient(ellipse at 50% 42%, var(--v3-glow-gold), transparent 62%);
  opacity: 0.45;
  pointer-events: none;
  filter: blur(10px);
}

/* 水墨远山：压在首屏底部，很淡，只提供"纸的纵深" */
.hero-ink {
  position: absolute;
  left: -10%;
  right: -10%;
  bottom: -2%;
  height: 34%;
  z-index: -1;
  opacity: 0.5;
  pointer-events: none;
}
.hero-ink svg { width: 100%; height: 100%; }
.hero-ink path:first-child { fill: rgba(127, 168, 161, 0.1); }
.hero-ink path:last-child { fill: rgba(206, 169, 110, 0.08); }

/* 巨字水印：竖排一个"墨"，极淡 */
.hero-watermark {
  position: absolute;
  right: 4%;
  top: 6%;
  z-index: -1;
  font-family: var(--v3-font-serif);
  font-size: clamp(180px, 24vw, 340px);
  line-height: 1;
  color: transparent;
  -webkit-text-stroke: 1px var(--v3-hairline-strong);
  opacity: 0.5;
  pointer-events: none;
  user-select: none;
  animation: v3-float 12s var(--v3-ease-in-out) infinite;
}

/* 朱砂印章：入场时"盖"下来 */
.hero-seal {
  position: absolute;
  right: 9%;
  bottom: 22%;
  z-index: -1;
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  padding: 6px;
  border-radius: 8px;
  border: 1.5px solid var(--v3-cinnabar);
  color: var(--v3-cinnabar);
  font-family: var(--v3-font-serif);
  font-size: 15px;
  line-height: 1.15;
  letter-spacing: 0.1em;
  writing-mode: vertical-rl;
  opacity: 0;
  transform: rotate(-6deg);
  animation: v3-seal-in 800ms var(--v3-ease-spring) 900ms forwards;
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
.shelf-title { position: relative; display: inline-block; margin: 0; font-family: var(--v3-font-serif); font-size: 22px; font-weight: 600; letter-spacing: 0.08em; }
/* 标题下的金线在进入视口后从左往右长出来 */
.shelf-title::after {
  content: '';
  position: absolute;
  left: 0;
  bottom: -8px;
  width: 100%;
  height: 1px;
  background: linear-gradient(90deg, var(--v3-gold), transparent);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform 1000ms var(--v3-ease-out) 180ms;
}
.shelf-head.is-revealed .shelf-title::after { transform: scaleX(1); }
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
/* 四角金色细线：像装裱画框的角，悬停时轻微外扩 */
.project-card::before,
.project-card::after {
  content: '';
  position: absolute;
  width: 16px;
  height: 16px;
  border: 1px solid var(--v3-hairline-gold);
  opacity: 0.55;
  pointer-events: none;
  transition: opacity var(--v3-dur-3), transform var(--v3-dur-3) var(--v3-ease-out);
}
.project-card::before {
  left: 10px;
  top: 10px;
  border-right: 0;
  border-bottom: 0;
  border-radius: 6px 0 0 0;
}
.project-card::after {
  right: 10px;
  bottom: 10px;
  border-left: 0;
  border-top: 0;
  border-radius: 0 0 6px 0;
}
.project-card:hover::before { transform: translate(-2px, -2px); opacity: 1; }
.project-card:hover::after { transform: translate(2px, 2px); opacity: 1; }
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
  .ld-main { padding: 0 22px 48px; }
  .grid { grid-template-columns: 1fr; }
  .hero { padding: 8vh 0 6vh; }
  .hero-stats { gap: 28px; }
}
</style>
