import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * 포트가 겹칠 때를 대비해 환경변수로 덮어쓸 수 있게 둔다.
 *   FRONT_PORT   프론트 dev 서버 포트 (기본 5173)
 *   BACKEND_URL  프록시가 /api 를 넘길 대상 (기본 http://localhost:8080)
 *
 * 예) 백엔드를 8081 에 띄우고 프론트를 5174 로 볼 때
 *   BACKEND_URL=http://localhost:8081 FRONT_PORT=5174 npm run dev
 */
const FRONT_PORT = Number(process.env.FRONT_PORT) || 5173
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: FRONT_PORT,
    // strictPort: 포트가 이미 쓰이면 조용히 다른 포트로 옮겨가지 않고 실패시킨다.
    // 안 그러면 프록시 대상과 실제 접속 포트가 어긋나 원인을 찾기 어려워진다.
    strictPort: true,
    // /api 를 Spring Boot 로 넘긴다. 동일 출처가 되므로 CORS 설정이 필요 없다.
    proxy: {
      '/api': {
        target: BACKEND_URL,
        changeOrigin: true,
      },
    },
  },
})
