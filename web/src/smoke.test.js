import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'

/**
 * 冒烟测试：把每个页面真的挂起来。
 *
 * <p>守的是"构建通过、页面全白"这类问题 —— 它只在运行时暴露，
 * 静态检查一律看不见。而这个项目里白屏的代价很实在：
 * 用户打开页面就是一片空白，什么线索都没有。
 *
 * <p>做法：逐个页面单独挂载（而不是挂整个 App）。这样某个页面挂了，
 * 测试会直接告诉我们是哪一个，而不是只报"整个应用挂了"。
 * 网络请求一律打桩 —— 这里测的是"能不能渲染"，不是"数据对不对"。
 */

// 所有接口都返回空数据，避免测试依赖后端
vi.mock('./api/http', () => ({
  default: {
    get: vi.fn(() => Promise.resolve([])),
    post: vi.fn(() => Promise.resolve({})),
    put: vi.fn(() => Promise.resolve({})),
    delete: vi.fn(() => Promise.resolve({}))
  }
}))

const stubs = {
  RouterLink: { template: '<a><slot /></a>' },
  RouterView: { template: '<div />' }
}

async function mountPage(loader) {
  const module = await loader()
  const Page = module.default
  const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', component: Page }] })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(Page, {
    global: {
      plugins: [createPinia(), router],
      stubs
    }
  })
  await new Promise((resolve) => setTimeout(resolve, 0))
  return wrapper
}

const PAGES = {
  '创作工作台': () => import('./views/WorkbenchPage.vue'),
  '创作项目': () => import('./views/ProjectListPage.vue'),
  '成书': () => import('./views/BookPage.vue'),
  '模型接入': () => import('./views/AiSettingsPage.vue'),
  '人物档案': () => import('./views/CharactersPage.vue'),
  '故事设定库': () => import('./views/SettingLibraryPage.vue'),
  '设置': () => import('./views/PreferencesPage.vue'),
  '剧情蓝图': () => import('./views/OutlinePage.vue')
}

describe('页面冒烟：每个页面都要能挂起来', () => {
  for (const [name, loader] of Object.entries(PAGES)) {
    it(`${name} 能渲染且不抛错`, async () => {
      // 捕获 Vue 在渲染期间抛出的警告/错误，它们往往就是白屏的前兆
      const errors = []
      const spy = vi.spyOn(console, 'error').mockImplementation((...args) => errors.push(args.join(' ')))
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation((...args) => errors.push(args.join(' ')))

      const wrapper = await mountPage(loader)

      spy.mockRestore()
      warnSpy.mockRestore()

      expect(wrapper.html(), `${name} 应当渲染出内容`).not.toBe('')
      // Vue 的警告里出现这些词，通常意味着组件内部出错了
      const fatal = errors.filter((line) =>
        /TypeError|is not a function|Cannot read|failed to resolve component/i.test(line)
      )
      expect(fatal, `${name} 渲染期间不应有致命错误:\n${fatal.join('\n')}`).toEqual([])
    })
  }
})

/**
 * 整个 App 的冒烟。
 *
 * 上面逐个挂页面能定位"哪个页面坏了"，但**白屏通常是 App 这一层挂的** ——
 * 外壳（侧栏、工作区树、顶部）自己出错时，路由里的页面再好也显示不出来。
 * 所以这一条必须单独测：它是"打开就是一片白"的直接对应。
 */
describe('外壳冒烟：App 要能挂起来', () => {
  it('App 能渲染出侧栏与工作区', async () => {
    const errors = []
    const spy = vi.spyOn(console, 'error').mockImplementation((...args) => errors.push(args.join(' ')))
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation((...args) => errors.push(args.join(' ')))

    const { default: App } = await import('./App.vue')
    const { default: router } = await import('./router/index.js')

    const wrapper = mount(App, {
      global: { plugins: [createPinia(), router] }
    })
    await router.isReady()
    await new Promise((resolve) => setTimeout(resolve, 0))

    spy.mockRestore()
    warnSpy.mockRestore()

    const html = wrapper.html()
    expect(html, 'App 应当渲染出内容').not.toBe('')
    // 外壳的两个标志性区域：导航项与工作区标题。
    // 它们都不在，说明外壳根本没渲染出来 —— 那就是白屏
    expect(html).toContain('创作项目')
    expect(html).toContain('工作区')

    const fatal = errors.filter((line) =>
      /TypeError|is not a function|Cannot read|failed to resolve component/i.test(line)
    )
    expect(fatal, `App 渲染期间不应有致命错误:\n${fatal.join('\n')}`).toEqual([])
  })
})

/**
 * 静态检查：用了某个 Vue API 却没有 import 它。
 *
 * <p>为什么要单独一条：这类错误构建**不会报**（Vue 的 API 名在运行时才解析），
 * 一挂载就抛 ReferenceError —— 页面直接白屏，一条线索都没有。
 * 2026-10-18 真的踩过一次：给 PlannerPanel 加了 onBeforeUnmount，
 * 但补 import 的判断写错了（用 -notmatch 检查，而注入的代码里已经有这个词），
 * 于是 import 一直没加上。构建通过、冒烟也通过，打开页面全白。
 *
 * <p>这条扫描纯文本，不需要挂载，所以能兜住那一类。
 */
describe('静态检查：Vue API 的 import 是否齐全', () => {
  const VUE_APIS = [
    'ref', 'reactive', 'computed', 'watch', 'watchEffect',
    'onMounted', 'onBeforeMount', 'onUnmounted', 'onBeforeUnmount',
    'onActivated', 'onDeactivated', 'onErrorCaptured',
    'nextTick', 'provide', 'inject', 'defineAsyncComponent'
  ]

  it('每个 .vue 里用到的 Vue API 都要在 import 里', async () => {
    const modules = import.meta.glob('./**/*.vue', { query: '?raw', import: 'default' })
    const problems = []

    for (const [path, load] of Object.entries(modules)) {
      const source = await load()
      const templateAt = source.indexOf('<template>')
      const script = templateAt > 0 ? source.slice(0, templateAt) : source
      const importLine = (source.match(/import \{[^}]*\} from 'vue'/) || [''])[0]

      for (const api of VUE_APIS) {
        // 只查"当函数调用"的用法，避免把注释或字符串里的词算进来
        if (new RegExp(`\\b${api}\\s*\\(`).test(script) && !new RegExp(`\\b${api}\\b`).test(importLine)) {
          problems.push(`${path} 用了 ${api} 却没 import`)
        }
      }
    }

    expect(problems, problems.join('\n')).toEqual([])
  })
})