import { readonly, ref } from 'vue'

/**
 * v3 主题（墨海 / 素笺）。
 *
 * <p>为什么不用旧的 settings store：旧主题（素笺/青瓷/丹青/墨夜）服务于旧页面，
 * 令牌完全不同；v3 页面在迁移期与旧页面共存，独立一份主题状态最安全
 * （旧页面的 html[data-theme] 继续由旧 store 管，互不覆盖）。
 * 迁移完成后两者会合并。
 */
const STORAGE_KEY = 'mojian.v3Theme'
export const V3_THEMES = [
  { key: 'inksea', name: '墨海', desc: '深墨底色 · 金色细线 · 电影感', swatches: ['#06070a', '#141821', '#cea96e', '#c94c3b'] },
  { key: 'sujuan', name: '素笺', desc: '暖白纸感 · 墨棕文字 · 长夜之外的选择', swatches: ['#f3eee3', '#fffdf7', '#a9854f', '#b2452f'] }
]

function readStored() {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return V3_THEMES.some((t) => t.key === value) ? value : 'inksea'
  } catch {
    return 'inksea'
  }
}

const theme = ref(readStored())

function apply() {
  document.documentElement.dataset.v3Theme = theme.value
}

export function initV3Theme() {
  apply()
  if (typeof window !== 'undefined' && window.matchMedia) {
    // 首次访问（没有存过选择）跟随系统；存过就尊重用户
    try {
      if (!localStorage.getItem(STORAGE_KEY) && window.matchMedia('(prefers-color-scheme: light)').matches) {
        theme.value = 'sujuan'
        apply()
      }
    } catch {
      /* localStorage 不可用时保持默认 */
    }
  }
}

export function useV3Theme() {
  function setTheme(key) {
    if (!V3_THEMES.some((t) => t.key === key)) return
    theme.value = key
    apply()
    try {
      localStorage.setItem(STORAGE_KEY, key)
    } catch {
      /* 隐私模式下忽略 */
    }
  }

  function toggleTheme() {
    setTheme(theme.value === 'inksea' ? 'sujuan' : 'inksea')
  }

  return { theme: readonly(theme), setTheme, toggleTheme, themes: V3_THEMES }
}
