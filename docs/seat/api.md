# 좌석 API 명세

- 상태: 작성 중
- 담당: 변정민
- 출처: Notion "도메인별 API 명세"의 좌석 항목과 "공연 API 공통 계약" 5.12 (2026-10-26 기준). 담당자가 검토하며 고친다.

`reservation-service` seat 모듈이 제공한다. 공통 헤더·응답 형식은 [shared/conventions.md](../shared/conventions.md)를 따른다.

## 1. 외부 API

| Method | URI | 권한·허용 호출자 | 성공 HTTP | 기능 |
|---|---|---|---|---|
| GET | `/api/schedules/{scheduleId}/seats` | 대기열 입장 회원 (협의) | 200 | 선택 가능 좌석 조회 |
| GET | `/api/schedules/{scheduleId}/remaining` | 누구나 | 200 | 등급별 잔여석 조회 |
| POST | `/api/schedules/{scheduleId}/holds` | 대기열 입장한 본인 회원 | 201 | 좌석 선점 |

공연 공통 계약 5.12에는 `DELETE /api/reservations/{id}/hold`(본인 선점 해제, 204)도 있다. 담당(좌석·예매)은 [예매 API](../reservation/api.md) 합의할 항목 S3에서 정한다.

### GET /api/schedules/{scheduleId}/seats — 선택 가능 좌석 조회

- 권한·허용 호출자: 대기열 입장 회원 (협의)
- 필수 헤더: Authorization(JWT), 입장 토큰(헤더명 협의)
- 성공 HTTP: 200

좌석 배치도에 회차별 좌석의 선택 가능 여부를 보여 준다. 조회 결과가 선점 성공을 보장하지는 않는다.

요청: path `scheduleId`

응답

```json
{
  "scheduleId": "201",
  "configVersion": 1,
  "asOf": "2026-11-01T14:01:00+09:00",
  "seats": [
    {
      "scheduleSeatId": "5001",
      "venueSeatId": "1001",
      "section": "A",
      "rowLabel": "1",
      "seatNumber": 1,
      "venueSeatGradeId": "101",
      "gradeName": "VIP",
      "basePrice": 150000,
      "selectable": true
    }
  ]
}
```

- 구역·열·번호는 공연 좌석 조회와 조합하거나 예매 조회 모델에 복제한다.
- 다른 사용자의 reservationId와 holdExpiresAt은 공개하지 않는다.
- HELD/SOLD 및 판매 제외 좌석은 selectable=false로 반환한다.
- 만료된 HELD는 예매 처리 규칙에 따라 선택 가능으로 판정한다.

### GET /api/schedules/{scheduleId}/remaining — 등급별 잔여석 조회

- 권한·허용 호출자: 누구나
- 성공 HTTP: 200

회차 상세에서 등급별 남은 좌석 수를 보여 준다. 몇 초 늦은 값을 허용한다. 담당 협의가 필요하다.

요청: path `scheduleId`

응답

```json
{
  "scheduleId": "201",
  "grades": [
    {"venueSeatGradeId": "101", "gradeName": "VIP", "remaining": 12}
  ],
  "asOf": "2026-11-01T14:01:00+09:00",
  "cached": true
}
```

- 판매 대상 중 AVAILABLE 또는 만료된 HELD를 집계하는 기본안이다.
- SOLD·유효한 HELD·판매 제외 좌석은 제외한다.
- 결제 중 선점 보호가 있다면 갱신된 만료 시각을 적용한다.

### POST /api/schedules/{scheduleId}/holds — 좌석 선점

- 권한·허용 호출자: 대기열 입장한 본인 회원
- 필수 헤더: Authorization(JWT, Gateway 검증), 입장 토큰(헤더명 협의), X-User-Id(Gateway 주입, 헤더명 협의)
- 성공 HTTP: 201

좌석 배치도에서 "선택 완료"를 누르면 좌석 1장을 10분간 선점하고 예약을 HOLD로 만든다. 예약 생성과 좌석 점유는 예매 서비스의 같은 로컬 트랜잭션으로 처리한다.

요청

```json
{"scheduleSeatId": "5001"}
```

응답

```json
{
  "reservationId": "7001",
  "scheduleSeatId": "5001",
  "status": "HOLD",
  "holdExpiresAt": "2026-11-01T14:11:00+09:00",
  "basePrice": 150000
}
```

- 서버는 좌석의 회차 소속·판매 정책·구성 버전·판매 대상 여부·구매 한도를 확인한다.
- 등급·가격·만료 시각을 클라이언트 입력으로 받지 않는다. 가격은 준비된 스냅샷과 공연 서비스 정책을 검증해 서버가 정한다.
- holdExpiresAt은 DB `hold_expires_at`에 대응한다. 회원·회차당 1장과 선점 10분은 유지안이며 최종 정책 확정이 필요하다.
- Idempotency-Key 적용 방식은 예매 담당자와 합의한다.
- 공연 공통 계약 5.12의 응답 예시는 status를 `HELD`로 적었다. 예매 쪽은 `HOLD`다(예매 합의 항목 T3).

오류 (공연 공통 계약 5.12)

| 상태 | 코드 | 때 |
|---|---|---|
| 403 | QUEUE_TOKEN_INVALID | 입장 권한 없음 |
| 409 | SEAT_ALREADY_TAKEN | 다른 예매가 유효하게 선점했거나 판매 완료 |
| 409 | PURCHASE_LIMIT_EXCEEDED | 선점 포함 구매 한도 초과 |
| 409 | SEAT_NOT_SELLABLE | 판매 제외 좌석 |
| 409 | SEAT_CONFIG_VERSION_MISMATCH | 최신 좌석 구성과 불일치 |
| 409 | RESERVATION_STATE_CONFLICT | 결제 진행·확정 상태에서 선점 해제 요청 |
| 410 | HOLD_EXPIRED | 선점 만료 후 결제 진행 등 유효한 선점이 필요한 요청 |

회차 취소·마감·미준비이면 신규 선점을 허용하지 않는다.

## 2. 내부 API

제공하는 내부 API(회차 좌석 준비)는 [shared/internal-api/seat.md](../shared/internal-api/seat.md)에 둔다. 사용하는 내부 API는 공연의 판매 검증([shared/internal-api/event.md](../shared/internal-api/event.md))이다.
