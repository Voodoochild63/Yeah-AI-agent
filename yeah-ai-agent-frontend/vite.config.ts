import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      // 后端 server.servlet.context-path 就是 /api，这里直通即可
      '/api': {
        target: 'http://localhost:8123',
        changeOrigin: true,
        // 开发代理默认超时很短，长行程会在模型返回前被切断
        timeout: 120_000,
        proxyTimeout: 120_000,
      },
    },
  },
})
