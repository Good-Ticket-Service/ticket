# 회원 내부 API

- 제공: 회원 (`user-service`)
- 소비: 미정
- 출처: Notion "도메인별 API 명세"의 회원 내부 항목 (2026-10-26 기준)

## 공통

- 응답 본문을 `ApiResponse`로 감싸지 않고, 오류는 `ErrorResponse`다([conventions.md](../conventions.md)).

---

## GET /internal/users/{id} — 회원 정보조회

- 상태: 초안
- 권한·허용 호출자: 내부망에서만
- 멱등성: 조회. 재시도해도 된다.

응답 (Notion 예시)

```json
{
  "userId": 1,
  "email": "fan@ticket.com",
  "role": "USER"
}
```

## 합의할 항목

1. 소비하는 서비스와 용도. 요청 헤더(`X-User-Id`, `X-User-Role`)만으로 충분한 경우에는 이 호출이 필요 없다.
2. 회원 ID 타입. 이 예시는 숫자, 결제 초안은 UUID다([conventions.md](../conventions.md) 값 형식).

## 변경 이력

| 날짜 | 이슈·PR | 내용 |
|---|---|---|
| 2026-10-26 | #3 | 초안. Notion 회원 API 명세에서 옮김 |
