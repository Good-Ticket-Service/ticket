# 대기열 API 명세

- 상태: 작성 중
- 담당: 김태현
- 출처: Notion "도메인별 API 명세"의 대기열 항목 (2026-10-26 기준). 담당자가 검토하며 고친다.

공통 헤더·응답 형식은 [shared/conventions.md](../shared/conventions.md)를 따른다.

## 1. 외부 API

| Method | URI | 권한·허용 호출자 | 성공 HTTP | 기능 |
|---|---|---|---|---|
| POST | `/api/schedules/{id}/queue` | 인증된 회원 | 200 | 대기열 진입 |
| GET | `/api/schedules/{id}/queue/me` | (미기재) | 200 | 순번 조회 |
| DELETE | `/api/schedules/{id}/queue/me` | (미기재) | 204 | 대기 취소 |

### POST /api/schedules/{id}/queue — 대기열 진입

- 권한·허용 호출자: 인증된 회원
- 필수 헤더: `Authorization: Bearer <token>`
- 성공 HTTP: 200

응답

```json
{
  "position": 1532,
  "aheadCount": 1531,
  "estimatedWaitSeconds": 31
}
```

### GET /api/schedules/{id}/queue/me — 순번 조회

- 필수 헤더: `Authorization: Bearer <token>`
- 성공 HTTP: 200
- 요청·응답: Notion에 미기재

### DELETE /api/schedules/{id}/queue/me — 대기 취소

- 필수 헤더: `Authorization: Bearer <token>`
- 성공 HTTP: 204
- 요청·응답: Notion에 미기재

## 2. 내부 작업 (API 없음)

| 기능 | 실행 | 설명 |
|---|---|---|
| 입장 처리 | 스케줄러 | Notion에 상세 미기재 |
