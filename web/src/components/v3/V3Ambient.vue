<script setup>
/**
 * v3 环境背景：深海墨色 + 缓慢漂移的墨云 + 极光带 + 浮尘 + 细颗粒。
 *
 * <p>为什么用 CSS 渐变而不是 canvas/WebGL：这层只是"氛围"，用纯 transform
 * 动画就够了；Canvas 反而要处理 DPR、可见性暂停、内存，收益不成正比。
 * 所有动画只动 transform / opacity，reduced-motion 下完全静止。
 */
defineProps({
  /** hero：首页，层次更丰富；calm：工作台，收敛一些 */
  variant: { type: String, default: 'hero' }
})
</script>

<template>
  <div class="v3-ambient v3-root" :class="'is-' + variant" aria-hidden="true">
    <div class="aurora"></div>
    <div class="blob blob-gold"></div>
    <div class="blob blob-cinnabar"></div>
    <div class="blob blob-jade"></div>
    <div class="dust">
      <i v-for="i in 14" :key="i"></i>
    </div>
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
    radial-gradient(120% 90% at 50% -10%, rgba(255, 255, 255, 0.04), transparent 60%),
    var(--v3-canvas);
}

/* 极光带：一条很淡的斜向光带缓慢扫过，给画面"呼吸" */
.aurora {
  position: absolute;
  left: -30%;
  top: -40%;
  width: 160%;
  height: 120%;
  background: linear-gradient(105deg,
    transparent 34%,
    rgba(206, 169, 110, 0.06) 46%,
    rgba(201, 76, 59, 0.045) 52%,
    rgba(127, 168, 161, 0.05) 58%,
    transparent 72%);
  transform: rotate(-8deg);
  animation: v3-drift-3 60s var(--v3-ease-in-out) infinite;
  opacity: 0.9;
}

.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(100px);
  opacity: 0.72;
  will-change: transform;
}

.blob-gold {
  width: 66vw;
  height: 66vw;
  left: -16vw;
  top: -24vw;
  background: radial-gradient(circle at 40% 40%, var(--v3-glow-gold), transparent 62%);
  animation: v3-drift-1 34s var(--v3-ease-in-out) infinite;
}

.blob-cinnabar {
  width: 46vw;
  height: 46vw;
  right: -10vw;
  top: 6vh;
  background: radial-gradient(circle at 55% 45%, var(--v3-glow-cinnabar), transparent 64%);
  animation: v3-drift-2 44s var(--v3-ease-in-out) infinite;
}

.blob-jade {
  width: 52vw;
  height: 52vw;
  left: 14vw;
  bottom: -30vw;
  background: radial-gradient(circle at 50% 50%, rgba(127, 168, 161, 0.14), transparent 66%);
  animation: v3-drift-3 52s var(--v3-ease-in-out) infinite;
}

.is-calm .blob { opacity: 0.42; }
.is-calm .blob-gold { width: 46vw; height: 46vw; }
.is-calm .aurora { opacity: 0.55; }

/* 浮尘：14 个极小金色光点，各自漂移。用 nth-child 分配位置，省掉 JS */
.dust { position: absolute; inset: 0; }
.dust i {
  position: absolute;
  width: 2px;
  height: 2px;
  border-radius: 50%;
  background: var(--v3-gold-bright);
  opacity: 0;
  animation: dust-float linear infinite;
}
.dust i:nth-child(1) { left: 8%; top: 62%; animation-duration: 17s; animation-delay: 1s; }
.dust i:nth-child(2) { left: 16%; top: 38%; animation-duration: 22s; animation-delay: 4s; }
.dust i:nth-child(3) { left: 27%; top: 76%; animation-duration: 19s; animation-delay: 7s; }
.dust i:nth-child(4) { left: 34%; top: 22%; animation-duration: 25s; animation-delay: 2s; }
.dust i:nth-child(5) { left: 42%; top: 56%; animation-duration: 21s; animation-delay: 9s; }
.dust i:nth-child(6) { left: 51%; top: 30%; animation-duration: 18s; animation-delay: 5s; }
.dust i:nth-child(7) { left: 58%; top: 70%; animation-duration: 24s; animation-delay: 3s; }
.dust i:nth-child(8) { left: 66%; top: 44%; animation-duration: 20s; animation-delay: 8s; }
.dust i:nth-child(9) { left: 73%; top: 82%; animation-duration: 23s; animation-delay: 1s; }
.dust i:nth-child(10) { left: 79%; top: 26%; animation-duration: 19s; animation-delay: 6s; }
.dust i:nth-child(11) { left: 85%; top: 58%; animation-duration: 26s; animation-delay: 2s; }
.dust i:nth-child(12) { left: 91%; top: 36%; animation-duration: 21s; animation-delay: 10s; }
.dust i:nth-child(13) { left: 22%; top: 12%; animation-duration: 28s; animation-delay: 3s; }
.dust i:nth-child(14) { left: 68%; top: 12%; animation-duration: 18s; animation-delay: 7s; }

@keyframes dust-float {
  0% { opacity: 0; transform: translate3d(0, 12px, 0) scale(0.8); }
  18% { opacity: 0.55; }
  60% { opacity: 0.3; }
  100% { opacity: 0; transform: translate3d(18px, -46px, 0) scale(1.1); }
}

.grain {
  position: absolute;
  inset: -40%;
  opacity: 0.17;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='180' height='180'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.55'/%3E%3C/svg%3E");
  mix-blend-mode: overlay;
}

.vignette {
  position: absolute;
  inset: 0;
  background: radial-gradient(120% 100% at 50% 40%, transparent 52%, rgba(0, 0, 0, 0.5) 100%);
}

html[data-v3-theme='sujuan'] .grain { opacity: 0.12; mix-blend-mode: multiply; }
html[data-v3-theme='sujuan'] .vignette {
  background: radial-gradient(120% 100% at 50% 40%, transparent 52%, rgba(120, 95, 62, 0.14) 100%);
}
html[data-v3-theme='sujuan'] .aurora { opacity: 0.7; }
</style>
