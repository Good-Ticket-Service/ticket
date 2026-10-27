# 회원 내부 API

- 제공: 회원 (`user-service`)
- 소비: 미정
- 출처: Notion "도메인별 API 명세"의 회원 내부 항목 (2026-10-26 기준)

## 공통

- 응답 본문을 `ApiResponse`로 감싸지 않고, 오류는 `ErrorResponse`다([conventions.md](../conventions.md)).

---

## GET /internal/users/{id} — 회원 정보조회

- 상태: 합의 (Notion, 2026-10-26)
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

## 변경 이력

| 날짜 | 이슈·PR | 내용 |
|---|---|---|
| 2026-10-26 | #3 | 합의 내용 반영. Notion 회원 API 명세에서 옮김 |
