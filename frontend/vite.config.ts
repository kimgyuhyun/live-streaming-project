/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 개발 서버에서도 배포와 같은 경로를 쓴다. Spring은 IDE에서(8080), MediaMTX는 compose에서(8888) 띄운다.
export default defineConfig({
  plugins: [react()],
  // 화면 상태 로직 테스트(Vitest). 컴포넌트를 렌더하므로 DOM이 필요하다
  test: {
    environment: 'jsdom',
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
      '/hls': {
        target: 'http://localhost:8888',
        rewrite: (path) => path.replace(/^\/hls/, ''),
        // MediaMTX는 첫 요청을 /live/...?cookieCheck=1 로 리다이렉트한다. nginx의 proxy_redirect와 같이 /hls를 붙인다.
        configure: (proxy) => {
          proxy.on('proxyRes', (res) => {
            const location = res.headers.location
            if (location?.startsWith('/')) res.headers.location = `/hls${location}`
          })
        },
      },
    },
  },
})
