# 좌석 API 명세

- 상태: 작성 중
- 담당: 변정민
- 출처: Notion "도메인별 API 명세"의 좌석 항목과 "전체 API 공통 계약 · 보고서 결정 반영" (2026-10-27 기준)
- 구현 계획: [plan.md](plan.md), 테이블: [tables.md](tables.md)

`reservation-service` seat 모듈이 제공한다. 외부 API(`/api/**`)만 둔다. 공통 헤더·응답 형식은 [shared/conventions.md](../shared/conventions.md)를 따른다.

## 공통 규칙

- 성공 응답은 `ApiResponse`(`status`, `message`, `data`)로 감싸고, 실패 응답은 `ErrorResponse`(`status`, `code`, `message`, `path`, `timestamp`, `errors`)다.
- 회차·좌석·예약 ID는 JSON 숫자다. 회원 ID는 BIGINT이며 Gateway가 `X-User-Id`로 넣는다. 요청 본문으로 회원 ID를 받지 않는다.
- 금액은 원 단위 정수(INT)다.
- 시각은 `+09:00` 오프셋을 포함한 ISO 8601이다(예: `2026-11-01T14:11:00+09:00`). 예매 API는 오프셋 없는 KST를 쓰므로 직렬화 방식은 [plan.md 열린 질문](plan.md#7-열린-질문)에서 맞춘다.
- 공통 오류 코드 `VALIDATION_ERROR`, `SERVICE_UNAVAILABLE`, `FORBIDDEN`, `INVALID_STATUS_TRANSITION`은 `CommonErrorCode`를 재사용한다. 좌석 전용 코드의 enum 반영은 구현 PR에서 한다.
- 입장 토큰은 `X-Queue-Token`이다. 공연(performanceId) 단위이고 TTL은 10분이다. 선점 여부에 따라 TTL을 늘리거나 없애지 않는다. Gateway가 토큰의 회원·공연 범위를 검증한다.

## 1. 외부 API

| Method | URI | 권한·허용 호출자 | 성공 HTTP | 기능 |
|---|---|---|---|---|
| GET | `/api/schedules/{scheduleId}/seats` | 대기열 입장 회원 | 200 | 선택 가능 좌석 조회 |
| GET | `/api/schedules/{scheduleId}/remaining` | 누구나 | 200 | 등급별 잔여석 조회 |
| POST | `/api/schedules/{scheduleId}/holds` | 대기열 입장한 본인 회원 | 201 (같은 선점 재요청 200) | 좌석 선점 |
| DELETE | `/api/schedules/{scheduleId}/holds/{reservationId}` | 선점한 본인 회원 | 204 | 선점 해제 |

지금 Gateway 라우트에는 `/api/schedules/**`가 없다. 공연 서비스의 `GET /api/schedules/{id}`(회차 상세)와 접두사가 같으므로 하위 경로 단위로 나눠야 한다([plan.md 열린 질문](plan.md#7-열린-질문) 1).

### 선택 가능 좌석 판정

조회·잔여석·선점은 같은 판정 메서드를 쓴다.

| 좌석 상태 | 조건 | 선택 가능 |
|---|---|---|
| AVAILABLE | `is_sellable = true` | 가능 |
| HOLD | 만료 전 (`hold_expires_at > DB now()`) | 불가 |
| HOLD | 만료 후, 그 예약이 결제 중(`PAYMENT_IN_PROGRESS`) | 불가 |
| HOLD | 만료 후, 그 예약이 결제 중 아님 | 가능 (선점 시 예전 예약을 정리) |
| SOLD | — | 불가 |
| 판매 제외 | `is_sellable = false` | 불가 |

- 만료된 HOLD 좌석의 예약 ID를 모아 `BookingFacade.findPaymentInProgress(reservationIds)`를 한 번 호출한다. 예약 테이블을 직접 JOIN하지 않는다.
- 좌석과 예약의 `hold_expires_at`은 같은 값이고, 결제 중에도 바꾸지 않는다. 결제 중인 좌석은 예약 상태로 보호한다.

---

### GET /api/schedules/{scheduleId}/seats — 선택 가능 좌석 조회

- 권한·허용 호출자: 대기열 입장 회원
- 필수 헤더: `Authorization`(JWT), `X-Queue-Token`
- 성공 HTTP: 200

좌석 배치도에 회차별 좌석의 선택 가능 여부를 보여 준다. 조회 결과가 선점 성공을 보장하지는 않는다.

요청: path `scheduleId`

응답 (`ApiResponse.data`)

```json
{
  "scheduleId": 201,
  "configVersion": 1,
  "asOf": "2026-11-01T14:01:00+09:00",
  "seats": [
    {
      "scheduleSeatId": 5001,
      "venueSeatId": 1001,
      "section": "A",
      "rowLabel": "1",
      "seatNumber": 1,
      "venueSeatGradeId": 101,
      "gradeName": "VIP",
      "basePrice": 150000,
      "selectable": true
    }
  ]
}
```

처리

1. 회차 좌석을 `INDEX(schedule_id, is_sellable, status)`로 조회한다.
2. 좌석마다 위 표로 `selectable`을 판정한다.
3. 다른 사용자의 `reservationId`·`holdExpiresAt`과 판매 제외 사유는 응답에 넣지 않는다.
4. 구역·열·번호는 좌석 준비 때 `p_schedule_seats`에 복제한 값으로 응답한다.

---

### GET /api/schedules/{scheduleId}/remaining — 등급별 잔여석 조회

- 권한·허용 호출자: 누구나 (입장 토큰 불필요)
- 성공 HTTP: 200

공연 상세·회차 선택 화면에서 등급별 남은 좌석 수를 보여 준다. 몇 초 늦은 값을 허용한다.

요청: path `scheduleId`

응답 (`ApiResponse.data`)

```json
{
  "scheduleId": 201,
  "grades": [
    { "venueSeatGradeId": 101, "gradeName": "VIP", "remaining": 12 }
  ],
  "asOf": "2026-11-01T14:01:00+09:00",
  "cached": true
}
```

처리

1. Redis 캐시 `remaining:{scheduleId}`가 있으면 그대로 반환한다(`cached = true`).
2. 없으면 판매 대상 좌석 중 선택 가능한 좌석(위 표)을 등급별로 집계한다.
3. 결과를 짧은 TTL로 캐시하고 `asOf`에 집계 시각을 넣는다. TTL 값은 아직 정하지 않았다.

---

### POST /api/schedules/{scheduleId}/holds — 좌석 선점

- 권한·허용 호출자: 대기열 입장한 본인 회원
- 필수 헤더: `Authorization`(JWT), `X-Queue-Token`, `X-User-Id`·`X-User-Role`(Gateway 주입)
- 성공 HTTP: 201 (신규 선점), 200 (같은 회원·같은 좌석의 유효 HOLD 재요청)

좌석 배치도에서 "선택 완료"를 누르면 좌석 1장을 10분간 선점하고 예약을 HOLD로 만든다. 예약 생성과 좌석 점유는 같은 로컬 트랜잭션에서 처리한다. 등급·가격·만료 시각은 클라이언트 입력으로 받지 않는다.

요청

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| scheduleSeatId | integer | O | 회차 좌석 ID (`p_schedule_seats.id`) |

```json
{ "scheduleSeatId": 5001 }
```

응답 (`ApiResponse.data`)

| 필드 | 타입 | 설명 |
|---|---|---|
| reservationId | integer | 생성된(재요청이면 기존) 예약 ID |
| scheduleSeatId | integer | 선점한 회차 좌석 ID |
| status | string | 좌석 상태. 선점 성공 시 `HOLD` |
| holdExpiresAt | datetime | 선점 만료 시각 (선점 시각 + 10분) |
| basePrice | integer | 할인 전 좌석 가격 스냅샷 |

```json
{
  "reservationId": 7001,
  "scheduleSeatId": 5001,
  "status": "HOLD",
  "holdExpiresAt": "2026-11-01T14:11:00+09:00",
  "basePrice": 150000
}
```

재요청 200은 본문 형식이 같고 `ApiResponse.status`만 200이다.

처리

1. 좌석 조회(잠금 없음): 좌석이 있고 경로의 `scheduleId` 소속인지 확인한다. 아니면 404 `SEAT_NOT_FOUND`. 다른 회차 좌석으로 판매 기간·매수 제한을 우회하는 것을 막는다.
2. 판매 검증(트랜잭션 밖, 잠금 전): 공연 `POST /internal/schedules/{scheduleId}/sale-validations`에 `configVersion`과 `venueSeatIds`를 보낸다([계약](../shared/internal-api/event.md)). `allowed=false`면 `reasonCode`를 그대로 외부 `code`로 응답한다. 404는 그대로 전달하고, 공연 서비스 장애는 503 `SERVICE_UNAVAILABLE`이다. 판매 허용을 추정하지 않는다.
3. 관람 연령 확인: 미달이면 선점을 막는다. 생년월일·연령 기준·ageRating 전달 계약이 정해지면 오류 코드와 함께 구체화한다.
4. 트랜잭션 시작. 요청 좌석과 이 회원의 다른 만료 HOLD 좌석(있으면)을 **좌석 ID 오름차순**으로 `SELECT ... FOR UPDATE` 잠근다. 예약은 좌석 다음에 바꾼다.
5. 재요청 확인: 요청 좌석이 이 회원의 만료 전 HOLD면 기존 예약을 200으로 반환하고 끝낸다.
6. 본인 만료 HOLD 정리: 다른 좌석에 이 회원의 만료 HOLD가 있으면 예약을 EXPIRED로 바꾸고 그 좌석을 AVAILABLE로 반환한다.
7. `is_sellable = false`면 409 `SEAT_NOT_SELLABLE`. 좌석의 `config_version`·`base_price`가 검증 응답과 다르면 409 `SEAT_CONFIG_VERSION_MISMATCH`.
8. 선택 가능 판정(위 표): 만료 전 HOLD·SOLD면 409 `SEAT_ALREADY_TAKEN`. 만료 HOLD면 `findPaymentInProgress`로 확인해 결제 중이면 409 `SEAT_ALREADY_TAKEN`, 아니면 예전 예약을 조건부로 EXPIRED로 바꾼다(`status = HOLD AND hold_expires_at <= DB now()`). 0행이면 그 사이 결제가 시작된 것이므로 409 `SEAT_ALREADY_TAKEN`.
9. 매수 제한: `BookingFacade.countActiveSeats(userId, scheduleId)`가 1 이상이면 409 `PURCHASE_LIMIT_EXCEEDED`(회원·회차당 1장, 선점 포함).
10. `BookingFacade.hold(...)`로 예약 HOLD와 티켓을 만든다(티켓 가격 = `base_price`). 예약 테이블의 부분 UNIQUE `uq_reservations_active_user_schedule`를 위반하면 제약 이름을 확인해 트랜잭션 전체를 롤백하고 409 `PURCHASE_LIMIT_EXCEEDED`.
11. 좌석을 HOLD로 바꾸고 `reservation_id`, `hold_expires_at`(DB now() + 10분)을 기록한다.
12. 커밋 후 201.

오류

| 상태 | 코드 | 때 |
|---|---|---|
| 400 | VALIDATION_ERROR | 요청 형식 오류 (공통 코드) |
| 400 | PERFORMANCE_NOT_PUBLISHED | 판매 검증 거절: 공연 미공개 |
| 400 | PERFORMANCE_CANCELED | 판매 검증 거절: 공연 취소 |
| 400 | SCHEDULE_CANCELED | 판매 검증 거절: 회차 취소 |
| 400 | SALES_SUSPENDED | 판매 검증 거절: 판매 일시 중지 |
| 400 | SEAT_SETUP_NOT_READY | 판매 검증 거절: 좌석 준비 안 됨 |
| 400 | BEFORE_OPEN | 판매 검증 거절: 예매 오픈 전 |
| 400 | SALES_CLOSED | 판매 검증 거절: 예매 마감 |
| 403 | QUEUE_TOKEN_INVALID | 입장 토큰 없음·만료 (Gateway) |
| 404 | SEAT_NOT_FOUND | 좌석 없음, 다른 회차 좌석 |
| 404 | PERFORMANCE_NOT_FOUND / SCHEDULE_NOT_FOUND | 판매 검증이 404로 응답. 그대로 전달 |
| 409 | SEAT_NOT_SELLABLE | 판매 제외 좌석 (판매 검증 reasonCode 포함) |
| 409 | SEAT_CONFIG_VERSION_MISMATCH | 좌석 스냅샷이 최신 구성과 다름 (판매 검증 reasonCode 포함) |
| 409 | PURCHASE_LIMIT_EXCEEDED | 이 회차에 활성 예약이 있음, 부분 UNIQUE 위반 |
| 409 | SEAT_ALREADY_TAKEN | 다른 예약이 유효하게 선점했거나 결제 중·판매 완료 |
| 503 | SERVICE_UNAVAILABLE | 공연 서비스 판매 검증 불가 (공통 코드) |

판매 검증 거절 코드는 공연 판매 검증 계약의 `reasonCode`를 그대로 쓴다. 관람 연령 미달 오류 코드는 연령 검증 계약이 정해진 뒤 추가한다.

---

### DELETE /api/schedules/{scheduleId}/holds/{reservationId} — 선점 해제

- 권한·허용 호출자: 선점한 본인 회원
- 필수 헤더: `Authorization`(JWT), `X-User-Id`·`X-User-Role`(Gateway 주입)
- 성공 HTTP: 204

결제 전 사용자가 선점한 좌석을 직접 놓고 다른 좌석을 고를 때 쓴다. 1인 1장 제한 때문에 이 API가 없으면 좌석을 바꾸려고 10분 만료를 기다려야 한다. 좌석 담당이 API를 제공하고, 예약 상태 변경은 `BookingFacade`로 요청한다. 결제 중·확정 예약은 대상이 아니다.

요청

| 위치 | 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| Path | scheduleId | integer | O | 회차 ID |
| Path | reservationId | integer | O | 선점 응답으로 받은 예약 ID |

본문 없음. 응답 204, 본문 없음.

처리

1. `BookingFacade`로 예약을 잠금 없이 조회한다(소유 회원, 회차, 상태, 티켓의 좌석 ID). 없거나 경로의 `scheduleId`와 다르면 404, 본인 예약이 아니면 403.
2. 이미 RELEASED·EXPIRED면 아무것도 바꾸지 않고 204를 돌려준다(같은 요청을 반복해도 안전하다).
3. 트랜잭션 시작. 좌석 행을 잠근다(잠금 순서: 좌석 → 예약).
4. 예약이 HOLD이고 만료 전(`hold_expires_at > DB now()`)이면 `BookingFacade.release(reservationId)`로 **RELEASED**, 만료 후면 **EXPIRED**로 정리한다. 둘 다 조건부 변경이다.
5. 0행이면 상태를 다시 확인한다. EXPIRED·RELEASED면 204, PAYMENT_IN_PROGRESS·CONFIRMED·CANCELING·CANCELED면 409 `INVALID_STATUS_TRANSITION`.
6. 좌석을 AVAILABLE로 반환하고 `reservation_id`·`hold_expires_at`을 비운다. `reservation_id`가 이 예약과 일치할 때만 바뀐다.
7. 커밋 후 204. 매수 제한이 풀려 같은 회차의 다른 좌석을 바로 선점할 수 있다.

오류

| 상태 | 코드 | 때 |
|---|---|---|
| 403 | FORBIDDEN | 본인 예약이 아님 (공통 코드) |
| 404 | RESERVATION_NOT_FOUND | 없는 예약, 다른 회차 예약 |
| 409 | INVALID_STATUS_TRANSITION | 결제 중·확정·취소 중·취소된 예약 (공통 코드) |

## 2. 내부 API·모듈 인터페이스

- 제공: 회차 좌석 준비. 공연 서비스가 발행한 `ScheduleSeatSetupRequested`를 Kafka로 소비하고, 결과를 `ScheduleSeatSetupCompleted` / `ScheduleSeatSetupFailed`로 발행한다. 이벤트 형식과 토픽 이름은 공연 담당과 협의 중이라 아직 정하지 않았다. 지금 계약 문서: [shared/internal-api/seat.md](../shared/internal-api/seat.md)
- 사용: 공연 판매 검증 [shared/internal-api/event.md](../shared/internal-api/event.md)
- 같은 서비스 안의 booking 모듈과는 `BookingFacade`·`SeatFacade`로만 통신한다. 메서드는 계약 이슈 #41에서 정한다(초안).
