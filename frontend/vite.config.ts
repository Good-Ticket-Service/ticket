import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Gateway에 CORS 설정이 없어 개발 서버가 같은 출처로 중계한다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.VITE_GATEWAY_URL ?? 'http://localhost:8080', changeOrigin: true },
      '/fake-pg': {
        target: process.env.VITE_FAKE_PG_URL ?? 'http://localhost:8090',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/fake-pg/, ''),
      },
    },
  },
});
