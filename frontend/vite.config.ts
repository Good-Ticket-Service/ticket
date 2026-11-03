import react from '@vitejs/plugin-react';
import { existsSync, readFileSync } from 'node:fs';
import { defineConfig } from 'vite';

const ROOT = new URL('../', import.meta.url);

function readEnvFile(name: string): Map<string, string> {
  const file = new URL(name, ROOT);
  const values = new Map<string, string>();
  if (!existsSync(file)) return values;
  readFileSync(file, 'utf8')
    .split('\n')
    .forEach((line) => {
      const match = /^([A-Z0-9_]+)=(.*)$/.exec(line.trim());
      if (match) values.set(match[1], match[2]);
    });
  return values;
}

/**
 * 로그인 화면의 테스트 계정 버튼에 쓸 계정. script/seed-accounts.sh와 같은 순서(환경 변수 → 루트 .env → .env.example)로 찾아
 * 실제로 만들어진 계정과 맞춘다. 줄은 있는데 값이 비어 있으면 그 계정은 버튼을 만들지 않는다.
 */
function devAccounts() {
  const env = readEnvFile('.env');
  const example = readEnvFile('.env.example');
  const value = (key: string) => process.env[key] || (env.has(key) ? env.get(key) : example.get(key)) || '';
  return [
    { role: 'USER', email: value('SEED_USER_EMAIL'), password: value('SEED_USER_PASSWORD') },
    { role: 'SELLER', email: value('SEED_SELLER_EMAIL'), password: value('SEED_SELLER_PASSWORD') },
    { role: 'MASTER', email: value('MASTER_EMAIL'), password: value('MASTER_PASSWORD') },
  ].filter((account) => account.email && account.password);
}

// Gateway에 CORS 설정이 없어 개발 서버가 같은 출처로 중계한다.
export default defineConfig(({ command }) => ({
  plugins: [react()],
  define: {
    // 비밀번호가 번들에 들어가므로 개발 서버에서만 넣는다. 빌드본은 빈 배열이라 버튼도 없다.
    __DEV_ACCOUNTS__: JSON.stringify(command === 'serve' ? devAccounts() : []),
  },
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
}));
