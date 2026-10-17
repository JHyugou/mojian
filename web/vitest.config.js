import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

/**
 * 前端冒烟测试的配置。
 *
 * 存在的理由：这个项目已经出现过一次"构建通过、页面全白"。
 * 构建只做静态检查，而白屏是**运行时**才暴露的 ——
 * 路由是静态导入所有页面的，任何一个模块顶层抛错，整个 App 都挂。
 * 所以需要一条"把整个 App 挂起来"的测试来守它。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.js'],
    // 挂载整个 App 会引入 router / pinia，JSX 与 SFC 都要走一遍转换
    server: { deps: { inline: ['dompurify', 'marked'] } }
  }
})
