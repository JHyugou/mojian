import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import V3Thinking from './V3Thinking.vue'

describe('V3Thinking 超长思考保护', () => {
  it('展开超长思考时只渲染最近一段，避免整段重排版', async () => {
    const wrapper = mount(V3Thinking, {
      props: { text: '长'.repeat(60000), mode: 'collapsed' }
    })

    await wrapper.find('.think-trigger').trigger('click')
    const shown = wrapper.find('.think-text').text()

    expect(shown).toContain('较早的 48000 字已省略')
    expect(shown.length).toBeLessThan(14000)
  })

  it('等待态显示同款思考条，不渲染正文', () => {
    const wrapper = mount(V3Thinking, {
      props: { text: '正在梳理走向…', mode: 'collapsed', pending: true }
    })

    expect(wrapper.find('.think-trigger.is-pending').exists()).toBe(true)
    expect(wrapper.find('.think-text').exists()).toBe(false)
  })
})
