# 프런트엔드

공연 티켓 예매 일반 회원 화면이다. React, TypeScript, Vite, TanStack Query를 쓴다.

```bash
npm install
npm run dev
```

http://localhost:5173 에서 열린다. `/api`는 Gateway(8080), `/fake-pg`는 Fake PG(8090)로 중계한다. 주소가 다르면 `VITE_GATEWAY_URL`, `VITE_FAKE_PG_URL`로 바꾼다.

## 역할

- 로그인 응답에 역할이 없어 `GET /api/users/me`로 읽는다. 메뉴와 화면 진입은 역할을 따르지만 길 안내일 뿐이고, 권한 검사는 서버가 한다.
- 예매·쿠폰함·내 예매는 일반 회원(USER)만 쓴다. 판매자는 `/seller/signup`에서 가입한다.
- MASTER 계정은 가입 API가 없다. 루트 README의 초기 계정을 쓴다.

## 대기열

- 예매하기를 누르면 항상 대기열 진입을 요청한다. 서버가 `QUEUE_DISABLED`로 답하면 바로 좌석 화면으로 가므로 프런트에는 설정이 없다.
- 차례가 되면 받은 입장 토큰을 공연별로 탭 저장소(sessionStorage)에 두고, 좌석 조회·선점에 `X-Queue-Token`으로 보낸다. 로그아웃하면 지운다.
- 입장 토큰이 만료되거나 맞지 않아 `QUEUE_TOKEN_INVALID`가 오면 대기열로 돌아가 다시 줄을 선다.

## 현재 API의 제약

- 예약 응답에 좌석 이름이 없다. 선택한 브라우저에서는 좌석 선택 때 저장한 이름을 보여 주고, 다른 기기에서는 좌석 수만 보여 준다.
- 결제창은 Fake PG 호출로 대신한다. 운영 PG 연동은 별도 작업이다.
