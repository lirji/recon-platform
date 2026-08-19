import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/recon': {
          target: env.VITE_RECON_API_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
    build: {
      sourcemap: false,
      rollupOptions: {
        output: {
          // 函数式分包: 稳妥捕获 echarts 的按需子模块 (echarts/core、echarts/charts…),
          // 把重型 vendor 拆成独立、可长缓存的 chunk, 从 index(应用) chunk 剥离。
          manualChunks(id) {
            if (!id.includes('node_modules')) return undefined
            if (id.includes('/echarts') || id.includes('echarts-for-react')) return 'echarts'
            if (id.includes('/antd/') || id.includes('@ant-design/')) return 'antd'
            if (id.includes('/@tanstack/') || id.includes('/axios/')) return 'query'
            if (id.includes('/react-router') || id.includes('/react-dom/') || id.includes('/react/') || id.includes('/scheduler/')) {
              return 'react'
            }
            // 其余(多为 antd 生态 rc-* 依赖)交给 Rollup 默认分包: 共享的进公共 chunk,
            // 仅被某懒加载 route 用到的会跟着该 route chunk 一起延后, 不硬塞首屏。
            return undefined
          },
        },
      },
    },
  }
})
