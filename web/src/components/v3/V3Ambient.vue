<script setup>
/**
 * v3 环境背景：深海墨色 + 缓慢漂移的墨云（金/朱砂/青瓷三团微光）+ 细颗粒。
 *
 * <p>为什么用 CSS 渐变而不是 canvas/WebGL：这层只是"氛围"，
 * 10 个纯 transform 动画远达不到需要 GPU 渲染的复杂度；
 * Canvas 反而要处理 DPR、可见性暂停、内存，收益不成正比。
 * 所有动画只动 transform，且 reduced-motion 下完全静止。
 */
defineProps({
  /** hero：首页更亮；calm：工作台更收敛 */
  variant: { type: String, default: 'hero' }
})
</script>

<template>
  <div class="v3-ambient v3-root" :class="'is-' + variant" aria-hidden="true">
    <div class="blob blob-gold"></div>
    <div class="blob blob-cinnabar"></div>
    <div class="blob blob-jade"></div>
    <div class="grain"></div>
    <div class="vignette"></div>
  </div>
</template>

<style scoped>
.v3-ambient {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
  background:
    radial-gradient(120% 90% at 50% -10%, rgba(255, 255, 255, 0.035), transparent 60%),
    var(--v3-canvas);
}

.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.68;
  will-change: transform;
}

.blob-gold {
  width: 62vw;
  height: 62vw;
  left: -14vw;
  top: -22vw;
  background: radial-gradient(circle at 40% 40%, var(--v3-glow-gold), transparent 62%);
  animation: v3-drift-1 34s var(--v3-ease-in-out) infinite;
}

.blob-cinnabar {
  width: 44vw;
  height: 44vw;
  right: -10vw;
  top: 8vh;
  background: radial-gradient(circle at 55% 45%, var(--v3-glow-cinnabar), transparent 64%);
  animation: v3-drift-2 44s var(--v3-ease-in-out) infinite;
}

.blob-jade {
  width: 50vw;
  height: 50vw;
  left: 16vw;
  bottom: -28vw;
  background: radial-gradient(circle at 50% 50%, rgba(127, 168, 161, 0.1), transparent 66%);
  animation: v3-drift-3 52s var(--v3-ease-in-out) infinite;
}

.is-calm .blob { opacity: 0.34; }
.is-calm .blob-gold { width: 46vw; height: 46vw; }

.grain {
  position: absolute;
  inset: -40%;
  opacity: 0.16;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='180' height='180'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.55'/%3E%3C/svg%3E");
  mix-blend-mode: overlay;
}

.vignette {
  position: absolute;
  inset: 0;
  background: radial-gradient(120% 100% at 50% 40%, transparent 55%, rgba(0, 0, 0, 0.42) 100%);
}

html[data-v3-theme='sujuan'] .grain { opacity: 0.1; mix-blend-mode: multiply; }
html[data-v3-theme='sujuan'] .vignette {
  background: radial-gradient(120% 100% at 50% 40%, transparent 55%, rgba(120, 95, 62, 0.12) 100%);
}
</style>
