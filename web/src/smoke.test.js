import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'

/**
 * 冒烟测试（v3）：把每个页面真的挂起来。
 *
 * <p>守的是"构建通过、页面全白"这类问题 —— 它只在运行时暴露，
 * 静态检查一律看不见。做法：逐个页面单独挂载，网络请求一律打桩，
 * 这里测的是"能不能渲染"，不是"数据对不对"。
 */

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
  '作品架首页': () => import('./v3/views/LandingPage.vue'),
  '设计定稿页': () => import('./v3/views/UiPreviewPage.vue'),
  '创作工作台': () => import('./v3/views/WorkbenchV3.vue'),
  '成书阅读': () => import('./v3/views/BookPageV3.vue'),
  '模型接入': () => import('./v3/views/AiSettingsPageV3.vue'),
  '偏好设置': () => import('./v3/views/PreferencesPageV3.vue')
}

describe('页面冒烟：每个页面都要能挂起来', () => {
  for (const [name, loader] of Object.entries(PAGES)) {
    it(`${name} 能渲染且不抛错`, async () => {
      const errors = []
      const spy = vi.spyOn(console, 'error').mockImplementation((...args) => errors.push(args.join(' ')))
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation((...args) => errors.push(args.join(' ')))

      const wrapper = await mountPage(loader)

      spy.mockRestore()
      warnSpy.mockRestore()

      expect(wrapper.html(), `${name} 应当渲染出内容`).not.toBe('')
      const fatal = errors.filter((line) =>
        /TypeError|is not a function|Cannot read|failed to resolve component/i.test(line)
      )
      expect(fatal, `${name} 渲染期间不应有致命错误:\n${fatal.join('\n')}`).toEqual([])
    })
  }
})

describe('入口冒烟：App 要能挂起来', () => {
  it('App 能在首页渲染出作品架', async () => {
    const errors = []
    const spy = vi.spyOn(console, 'error').mockImplementation((...args) => errors.push(args.join(' ')))
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation((...args) => errors.push(args.join(' ')))

    const { default: App } = await import('./App.vue')
    const { default: router } = await import('./router/index.js')
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [createPinia(), router] } })
    await new Promise((resolve) => setTimeout(resolve, 0))

    spy.mockRestore()
    warnSpy.mockRestore()

    const html = wrapper.html()
    expect(html, 'App 应当渲染出内容').not.toBe('')
    expect(html).toContain('墨笺')
    expect(html).toContain('继续创作')

    const fatal = errors.filter((line) =>
      /TypeError|is not a function|Cannot read|failed to resolve component/i.test(line)
    )
    expect(fatal, `App 渲染期间不应有致命错误:\n${fatal.join('\n')}`).toEqual([])
  })
})

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
        if (new RegExp(`\\b${api}\\s*\\(`).test(script) && !new RegExp(`\\b${api}\\b`).test(importLine)) {
          problems.push(`${path} 用了 ${api} 却没 import`)
        }
      }
    }

    expect(problems, problems.join('\n')).toEqual([])
  })
})
