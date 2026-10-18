import { onBeforeUnmount, onMounted } from 'vue'

/**
 * v3 动效基座。
 *
 * <p>原则：只用 transform / opacity / filter，绝不触发布局重排；
 * 所有监听都在 rAF 里合并，且组件卸载时清理干净。
 * 不引入动画库 —— 这点动效用原生 WAAPI + CSS 足够，包体积更小、可控性更高。
 */

/** 进入视口时追加 is-revealed（配合 tokens.css 的 .v3-reveal） */
export const vReveal = {
  mounted(el, binding) {
    const options = binding.value || {}
    el.classList.add('v3-reveal')
    if (options.delay) el.style.setProperty('--v3-reveal-delay', `${options.delay}ms`)

    if (typeof IntersectionObserver === 'undefined') {
      el.classList.add('is-revealed')
      return
    }
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add('is-revealed')
            observer.unobserve(entry.target)
          }
        }
      },
      { rootMargin: '0px 0px -8% 0px', threshold: 0.05 }
    )
    observer.observe(el)
    el.__v3RevealObserver = observer
  },
  unmounted(el) {
    el.__v3RevealObserver?.disconnect()
  }
}

function prefersReducedMotion() {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

/**
 * 磁吸：鼠标靠近时元素向指针方向轻微位移（幅度很小，克制使用）。
 * 传入 ref 或 DOM 元素。
 */
export function useMagnetic(target, { strength = 0.22, max = 10 } = {}) {
  let frame = 0
  let el = null

  function resolve() {
    el = target?.value || target
    return el instanceof HTMLElement ? el : null
  }

  function onMove(event) {
    if (!el || prefersReducedMotion()) return
    if (frame) cancelAnimationFrame(frame)
    frame = requestAnimationFrame(() => {
      const rect = el.getBoundingClientRect()
      const dx = event.clientX - (rect.left + rect.width / 2)
      const dy = event.clientY - (rect.top + rect.height / 2)
      const x = Math.max(-max, Math.min(max, dx * strength))
      const y = Math.max(-max, Math.min(max, dy * strength))
      el.style.transform = `translate3d(${x}px, ${y}px, 0)`
    })
  }

  function onLeave() {
    if (!el) return
    if (frame) cancelAnimationFrame(frame)
    el.style.transform = 'translate3d(0, 0, 0)'
  }

  onMounted(() => {
    const node = resolve()
    if (!node) return
    node.addEventListener('pointermove', onMove)
    node.addEventListener('pointerleave', onLeave)
    node.__v3Magnetic = { onMove, onLeave }
  })

  onBeforeUnmount(() => {
    const node = resolve()
    if (!node) return
    node.removeEventListener('pointermove', onMove)
    node.removeEventListener('pointerleave', onLeave)
  })
}

/**
 * 3D 微倾斜 + 光标高光：卡片随指针轻微旋转，并用 --v3-mx/--v3-my
 * 驱动一层径向高光。幅度小到"几乎察觉不到，但会觉得精致"。
 */
export function useTilt(target, { maxTilt = 6 } = {}) {
  let frame = 0
  let el = null

  function resolve() {
    el = target?.value || target
    return el instanceof HTMLElement ? el : null
  }

  function onMove(event) {
    if (!el || prefersReducedMotion()) return
    if (frame) cancelAnimationFrame(frame)
    frame = requestAnimationFrame(() => {
      const rect = el.getBoundingClientRect()
      const px = (event.clientX - rect.left) / rect.width
      const py = (event.clientY - rect.top) / rect.height
      const rx = (0.5 - py) * maxTilt
      const ry = (px - 0.5) * maxTilt
      el.style.transform = `perspective(900px) rotateX(${rx.toFixed(2)}deg) rotateY(${ry.toFixed(2)}deg) translate3d(0, -2px, 0)`
      el.style.setProperty('--v3-mx', `${(px * 100).toFixed(1)}%`)
      el.style.setProperty('--v3-my', `${(py * 100).toFixed(1)}%`)
    })
  }

  function onLeave() {
    if (!el) return
    if (frame) cancelAnimationFrame(frame)
    el.style.transform = 'perspective(900px) rotateX(0deg) rotateY(0deg) translate3d(0, 0, 0)'
    el.style.setProperty('--v3-mx', '50%')
    el.style.setProperty('--v3-my', '50%')
  }

  onMounted(() => {
    const node = resolve()
    if (!node) return
    node.addEventListener('pointermove', onMove)
    node.addEventListener('pointerleave', onLeave)
    node.__v3Tilt = { onMove, onLeave }
  })

  onBeforeUnmount(() => {
    const node = resolve()
    if (!node) return
    node.removeEventListener('pointermove', onMove)
    node.removeEventListener('pointerleave', onLeave)
  })
}

/** 滚动视差：元素随滚动做很小幅度的位移。effect 为负时反向。 */
export function useParallax(target, { effect = 20 } = {}) {
  let frame = 0
  let el = null

  function resolve() {
    el = target?.value || target
    return el instanceof HTMLElement ? el : null
  }

  function onScroll() {
    if (!el || prefersReducedMotion()) return
    if (frame) cancelAnimationFrame(frame)
    frame = requestAnimationFrame(() => {
      const rect = el.getBoundingClientRect()
      const progress = (rect.top + rect.height / 2 - window.innerHeight / 2) / window.innerHeight
      el.style.transform = `translate3d(0, ${(progress * effect).toFixed(2)}px, 0)`
    })
  }

  onMounted(() => {
    if (!resolve()) return
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onScroll)
  })

  onBeforeUnmount(() => {
    if (frame) cancelAnimationFrame(frame)
    window.removeEventListener('scroll', onScroll)
    window.removeEventListener('resize', onScroll)
  })
}

/** 数字缓动：用于"章节数/字数"这类统计，从 0 滚到目标值。 */
export function animateCount(from, to, duration, onUpdate) {
  if (prefersReducedMotion()) {
    onUpdate(to)
    return
  }
  const start = performance.now()
  const delta = to - from
  function tick(now) {
    const t = Math.min(1, (now - start) / duration)
    // easeOutExpo
    const eased = t === 1 ? 1 : 1 - Math.pow(2, -10 * t)
    onUpdate(Math.round(from + delta * eased))
    if (t < 1) requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
}
