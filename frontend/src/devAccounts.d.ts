/** 개발 서버에서만 채워지는 로컬 테스트 계정(vite.config.ts). 빌드본에서는 빈 배열이다. */
declare const __DEV_ACCOUNTS__: { role: 'USER' | 'SELLER' | 'MASTER'; email: string; password: string }[];
