# 회원 API 명세

- 상태: 작성 중
- 담당:
- 출처: Notion "도메인별 API 명세"의 회원 항목 (2026-10-26 기준). 담당자가 검토하며 고친다.

외부 API(`/api/v1/**`)만 둔다. 공통 헤더·응답 형식은 [shared/conventions.md](../shared/conventions.md)를 따른다.

## 1. 외부 API

| Method | URI | 권한·허용 호출자 | 성공 HTTP | 기능 |
|---|---|---|---|---|
| POST | `/api/v1/auth/signup` | 누구나 | 201 | 회원가입 |
| POST | `/api/v1/auth/login` | 누구나 | 200 | 로그인 |
| GET | `/api/v1/users/me` | 본인 JWT 토큰을 가진 회원 | (미기재) | 내 정보조회 |
| PATCH | `/api/v1/users/me` | 본인 JWT 토큰을 가진 회원 | 200 | 내 정보수정 |
| DELETE | `/api/v1/users/me` | 본인 JWT 토큰을 가진 회원 | (미기재) | 회원탈퇴 |

### POST /api/v1/auth/signup — 회원가입

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

### POST /api/v1/auth/login — 로그인

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

### GET /api/v1/users/me — 내 정보조회

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

### PATCH /api/v1/users/me — 내 정보수정

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

### DELETE /api/v1/users/me — 회원탈퇴

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
