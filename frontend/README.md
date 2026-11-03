# 프런트엔드

공연 티켓 예매 화면이다. 회원(예매·쿠폰), 판매자(공연 등록·정산), 관리자(공연장·판매 중지·쿠폰·환불·지급 운영) 화면이 있다. React, TypeScript, Vite, TanStack Query를 쓴다.

```bash
npm install
npm run dev
```

http://localhost:5173 에서 열린다. `/api`는 Gateway(8080), `/fake-pg`는 Fake PG(8090)로 중계한다. 주소가 다르면 `VITE_GATEWAY_URL`, `VITE_FAKE_PG_URL`로 바꾼다.

## 역할

- 로그인 응답에 역할이 없어 `GET /api/users/me`로 읽는다. 메뉴와 화면 진입은 역할을 따르지만 길 안내일 뿐이고, 권한 검사는 서버가 한다.
- 예매·쿠폰함·내 예매는 일반 회원(USER)만 쓴다. 판매자는 `/seller/signup`에서 가입한다.
- MASTER 계정은 가입 API가 없다. 루트 README의 초기 계정을 쓴다.
- 개발 서버(`npm run dev`)의 로그인 화면에는 회원·판매자·관리자 버튼이 있어 누르면 그 계정으로 바로 로그인한다. 계정은 `script/seed-accounts.sh`와 같이 루트 `.env`(없으면 `.env.example`)의 `SEED_USER_*`·`SEED_SELLER_*`·`MASTER_*`를 쓰고, 값을 바꿨으면 개발 서버를 다시 띄운다. 비밀번호가 번들에 들어가므로 빌드본에는 넣지 않는다.

## 대기열

- 예매하기를 누르면 항상 대기열 진입을 요청한다. 서버가 `QUEUE_DISABLED`로 답하면 바로 좌석 화면으로 가므로 프런트에는 설정이 없다.
- 차례가 되면 받은 입장 토큰을 공연별로 탭 저장소(sessionStorage)에 두고, 좌석 조회·선점에 `X-Queue-Token`으로 보낸다. 로그아웃하면 지운다.
- 입장 토큰이 만료되거나 맞지 않아 `QUEUE_TOKEN_INVALID`가 오면 대기열로 돌아가 다시 줄을 선다.

## 현재 API의 제약

- 예약 응답에 좌석 이름이 없다. 선택한 브라우저에서는 좌석 선택 때 저장한 이름을 보여 주고, 다른 기기에서는 좌석 수만 보여 준다.
- 결제창은 Fake PG 호출로 대신한다. 운영 PG 연동은 별도 작업이다.
- 판매자 공연·공연장 목록과 단건 조회 API가 없다. 이 브라우저에서 만든 공연·공연장의 version·등급·회차를 localStorage에 기억해 이어서 작업하고, 다른 곳에서 만든 것은 ID와 version을 직접 입력한다. 판매자는 공연장 ID·등급 ID를 관리자에게 받는다.
- 관리자는 회차 version을 읽을 API가 없어 판매 중지에 version을 직접 입력한다. 이 화면에서 바꾼 회차는 응답의 version을 기억해 채운다.
- 회원 정보 수정·탈퇴는 명세가 초안이라 내 정보는 조회만 한다.
