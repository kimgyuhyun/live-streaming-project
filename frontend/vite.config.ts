import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 개발 서버에서도 배포와 같은 경로를 쓴다. Spring은 IDE에서(8080), MediaMTX는 compose에서(8888) 띄운다.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
      '/hls': {
        target: 'http://localhost:8888',
        rewrite: (path) => path.replace(/^\/hls/, ''),
      },
    },
  },
})
