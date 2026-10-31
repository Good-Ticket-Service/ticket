# 프런트엔드

공연 티켓 예매 일반 회원 화면이다. React, TypeScript, Vite, TanStack Query를 쓴다.

```bash
npm install
npm run dev
```

http://localhost:5173 에서 열린다. `/api`는 Gateway(8080), `/fake-pg`는 Fake PG(8090)로 중계한다. 주소가 다르면 `VITE_GATEWAY_URL`, `VITE_FAKE_PG_URL`로 바꾼다.

## 현재 API의 제약

- 입장 토큰 발급 API가 없어 기본값은 대기열을 건너뛴다. `VITE_QUEUE_ENABLED=true`면 순번 화면까지 동작한다.
- 예약 응답에 좌석 이름이 없다. 선택한 브라우저에서는 좌석 선택 때 저장한 이름을 보여 주고, 다른 기기에서는 좌석 수만 보여 준다.
- 결제창은 Fake PG 호출로 대신한다. 운영 PG 연동은 별도 작업이다.
