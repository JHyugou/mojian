import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 开发环境将 /api 代理到本地 Spring Boot，避免跨域
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true
      }
    }
  }
})
