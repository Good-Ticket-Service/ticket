# 회원 API 명세

- 상태: 작성 중
- 담당:
- 출처: Notion "도메인별 API 명세"의 회원 항목 (2026-10-26 기준). 담당자가 검토하며 고친다.

외부 API(`/api/**`)만 둔다. 회원 경로는 Notion 공통 계약의 외부 기본 경로(`/api`) 결정에 따라 `v1`을 쓰지 않는다(#24). 공통 헤더·응답 형식은 [shared/conventions.md](../shared/conventions.md)를 따른다.

## 1. 외부 API

| Method | URI | 권한·허용 호출자 | 성공 HTTP | 기능 |
|---|---|---|---|---|
| POST | `/api/auth/signup` | 누구나 | 201 | 회원가입 |
| POST | `/api/auth/seller/signup` | 누구나 | 201 | 판매자 회원가입 |
| POST | `/api/auth/login` | 누구나 | 200 | 로그인 |
| GET | `/api/users/me` | 본인 JWT 토큰을 가진 회원 | (미기재) | 내 정보조회 |
| PATCH | `/api/users/me` | 본인 JWT 토큰을 가진 회원 | 200 | 내 정보수정 |
| DELETE | `/api/users/me` | 본인 JWT 토큰을 가진 회원 | (미기재) | 회원탈퇴 |

### POST /api/auth/signup — 회원가입

- 권한·허용 호출자: 누구나
- 필수 헤더: 없음
- 성공 HTTP: 201

요청

```json
{
  "email": "fan@ticket.com",
  "password": "Passw0rd!",
  "name": "김태현",
  "phone": "010-1234-5678"
}
```

응답

```json
{
  "status": 201,
  "message": "회원가입이 완료되었습니다.",
  "data": {
    "userId": 1,
    "email": "fan@ticket.com",
    "name": "김태현",
    "role": "USER",
    "status": "ACTIVE"
  }
}
```

오류

| 상태 | 코드 | 때 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 형식 오류. 필드별 `errors` 포함 |
| 409 | `DUPLICATE_EMAIL` | 이미 쓰는 email. 탈퇴 회원의 email도 포함한다 |

### POST /api/auth/seller/signup — 판매자 회원가입

- 권한·허용 호출자: 누구나
- 필수 헤더: 없음
- 성공 HTTP: 201
- 출처: Notion "판매자 회원가입" (2026-10-27), #49

요청·응답·오류는 회원가입과 같고 응답의 `role`만 `SELLER`다.

- 서버가 역할을 SELLER로 고정한다. 요청 본문의 `role`은 받지 않는다(넣어도 무시한다).
- 승인 절차 없이 가입 즉시 `ACTIVE`인 SELLER가 된다.
- email은 일반 회원과 같은 공간을 쓴다. 1 email = 1 계정이라 일반 회원 email로는 판매자로 가입할 수 없다.

```json
{
  "status": 201,
  "message": "회원가입이 완료되었습니다.",
  "data": {
    "userId": 2,
    "email": "seller@ticket.com",
    "name": "김판매",
    "role": "SELLER",
    "status": "ACTIVE"
  }
}
```

### POST /api/auth/login — 로그인

- 권한·허용 호출자: 누구나
- 필수 헤더: 없음
- 성공 HTTP: 200

요청

```json
{
  "email": "fan@ticket.com",
  "password": "Passw0rd!"
}
```

응답

```json
{
  "status": 200,
  "message": "로그인에 성공했습니다.",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 3600
  }
}
```

### GET /api/users/me — 내 정보조회

- 권한·허용 호출자: 본인 JWT 토큰을 가진 회원
- 필수 헤더: `Authorization: Bearer <token>`
- 성공 HTTP: Notion에 미기재

응답

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "userId": 1,
    "email": "fan@ticket.com",
    "name": "김태현",
    "phone": "010-1234-5678",
    "role": "USER",
    "status": "ACTIVE",
    "createdAt": "2026-10-25T14:30:00",
    "updatedAt": "2026-10-25T14:30:00"
  }
}
```

### PATCH /api/users/me — 내 정보수정

- 권한·허용 호출자: 본인 JWT 토큰을 가진 회원
- 필수 헤더: `Authorization: Bearer <token>`
- 성공 HTTP: 200

요청

```json
{
  "name": "김태현",
  "phone": "010-9876-5432"
}
```

응답

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "userId": 1,
    "email": "fan@ticket.com",
    "name": "김태현",
    "phone": "010-9876-5432",
    "role": "USER",
    "status": "ACTIVE",
    "createdAt": "2026-10-25T14:30:00",
    "updatedAt": "2026-10-25T14:30:00"
  }
}
```

### DELETE /api/users/me — 회원탈퇴

- 권한·허용 호출자: 본인 JWT 토큰을 가진 회원
- 필수 헤더: `Authorization: Bearer <token>`
- 성공 HTTP: Notion에 미기재

응답

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "userId": 1,
    "status": "WITHDRAWN",
    "withdrawnAt": "2026-10-25T15:00:00"
  }
}
```

## 2. 내부 API

제공하는 내부 API는 [shared/internal-api/user.md](../shared/internal-api/user.md)에 둔다.
