# BookingFacade (좌석 → 예매)

- 상태: 합의 (#34)
- 제공: 예매 (`reservation-service` booking 모듈, 지한얼)
- 소비: 좌석 (`reservation-service` seat 모듈, 변정민)
- 짝 계약: [SeatFacade](seat-facade.md) (예매 → 좌석)
- 근거: 좌석 담당 「seat ↔ booking 모듈 Facade 계약 제안 (v1)」, #34 댓글, [reservation/booking-decisions.md](../../reservation/booking-decisions.md) B28·B30·B31·B32·B33

같은 서비스 안의 모듈 간 계약이다. seat는 예약 테이블을 직접 조회·수정하지 않고 이 인터페이스로만 예약을 만들고, 읽고, 바꾼다. 예약 상태를 바꾸는 코드는 booking 한 곳에만 둔다.

## 공통

- **패키지:** 인터페이스와 요청·응답 타입은 `com.sparta.ticket.reservation.booking.application.facade`에 둔다. seat는 booking의 이 패키지만 쓸 수 있다(ArchUnit `SEAT_USES_BOOKING_ONLY_THROUGH_FACADE`). booking의 domain 타입(`ReservationStatus` 등)은 밖에 내보이지 않는다.
- **트랜잭션:** 모든 메서드는 `Propagation.MANDATORY`다. seat의 트랜잭션 안에서만 부를 수 있고, 밖에서 부르면 `IllegalTransactionStateException`이다. 예약과 좌석이 한 트랜잭션에 커밋된다.
- **잠금 순서:** 좌석(id 오름차순) → 예약. seat는 좌석을 먼저 잠근 뒤 예약을 바꾸는 메서드를 부른다.
- **시각:** reservation-service가 함께 쓰는 `Clock` 빈(`reservation.infrastructure.config.ClockConfig`, Asia/Seoul) 하나로 판단한다. 조건부 UPDATE는 DB `now()`를 쓰지 않고 이 Clock으로 구한 `:now`를 받는다. `holdExpiresAt`도 seat가 같은 Clock으로 계산해 넘긴다(B33).
- **0행:** 상태를 바꾸지 못한 경우는 예외가 아니라 반환값으로 알린다. 호출자가 다음 행동을 고른다.
- **Feign 전환:** 서비스를 나누면 이 인터페이스의 구현만 Feign 어댑터로 바꾼다. 이 패키지의 타입이 그대로 요청·응답 DTO가 된다. 다만 그때는 좌석과 예약이 따로 커밋되므로 보상(Saga)이 필요하고, `MANDATORY`와 잠금 순서는 의미가 없어진다.

## 메서드

| 메서드 | 반환 | seat가 부르는 때 | 상태 |
|---|---|---|---|
| `hold(userId, scheduleId, List<SeatLine>, holdExpiresAt)` | 예약 id | 좌석 선점 | 구현 (feat/reservation/15) |
| `getReservation(reservationId)` | `Optional<ReservationView>` | 선점 해제 전 좌석 id 읽기 | 구현 (feat/reservation/15) |
| `release(userId, reservationId)` | `ReleaseResult` | 선점 해제 | 구현 (feat/reservation/15) |
| `countActiveSeats(userId, scheduleId)` | int | 좌석 선점(1인 매수 제한) | 구현 (feat/reservation/59) |
| `findActiveHold(userId, scheduleId)` | `Optional<ReservationView>` | 좌석 선점(같은 좌석 재요청 200, 본인 만료 HOLD 정리) | 구현 (feat/reservation/59) |
| `expireIfDue(reservationId)` | boolean | 좌석 선점·해제 중 만료된 HOLD 정리 | 구현 (feat/reservation/59). 쿠폰 해제는 쿠폰 연동 때 |
| `findPaymentInProgress(Collection<Long> reservationIds)` | `Set<Long>` | 좌석 선점·조회·잔여석 | 구현 (feat/reservation/59) |

### hold

```java
Long hold(Long userId, Long scheduleId, List<SeatLine> seats, LocalDateTime holdExpiresAt);

public record SeatLine(Long scheduleSeatId, int price) {}
```

- HOLD 예약과 좌석마다 티켓(`p_tickets`)을 만든다. 티켓 가격은 넘겨받은 선점 시점 가격이다. 결제의 할인 전 금액(`totalPrice`)은 이 티켓 가격의 합계다(B28). seat에 가격을 다시 묻는 메서드(`priceSnapshot`)는 두지 않는다.
- 1차는 좌석 목록 길이 1이다. 같은 좌석이 두 번 들어오거나 가격이 음수면 `INVALID_REQUEST`.
- 같은 회원이 같은 회차에 진행 중인(HOLD·PAYMENT_IN_PROGRESS·CONFIRMED·CANCELING) 예약을 또 만들면 부분 UNIQUE `uk_p_reservations_active_user_schedule` 위반(`DataIntegrityViolationException`)을 **잡지 않고 그대로 던진다**. `saveAndFlush`로 바로 써서 이 호출 안에서 드러난다(B31).
- 위반 뒤 트랜잭션은 롤백 전용이 된다. seat는 트랜잭션 **밖**에서 제약 이름을 보고 409 `PURCHASE_LIMIT_EXCEEDED`로 바꾼다. 다른 무결성 오류는 바꾸지 않는다.

### getReservation

```java
Optional<ReservationView> getReservation(Long reservationId);

public record ReservationView(
        Long reservationId,
        Long userId,
        Long scheduleId,
        ReservationState state,
        LocalDateTime holdExpiresAt,
        List<Long> scheduleSeatIds) {}   // 오름차순. 이 순서로 잠근다

public enum ReservationState { HOLD, PAYMENT_IN_PROGRESS, CONFIRMED, EXPIRED, RELEASED, CANCELING, CANCELED }
```

- 상태와 상관없이 예약을 읽는다. 없으면 빈 값이다.
- seat는 선점 해제 때 이 결과로 없음(404)·회차 다름(404)·본인 아님(403)을 먼저 판단하고, `scheduleSeatIds`로 좌석을 잠근 뒤 `release`를 부른다.
- 제안 v1의 `getHold`다. HOLD가 아닌 예약도 돌려주므로 이름을 `getReservation`으로 했다(PR #43에서 좌석 담당 확인).

### release

```java
ReleaseResult release(Long userId, Long reservationId);

public enum ReleaseResult { RELEASED, EXPIRED, ALREADY_CLOSED, NOT_RELEASABLE }
```

| 예약 상태 | 결과 | 예약 변화 | seat 응답 | 좌석 |
|---|---|---|---|---|
| HOLD, 만료 전 | `RELEASED` | → RELEASED | 204 | 반환 |
| HOLD, 만료 후 | `EXPIRED` | → EXPIRED | 204 | 반환 |
| RELEASED·EXPIRED | `ALREADY_CLOSED` | 없음 | 204 | 이 예약에 남아 있으면 반환 |
| PAYMENT_IN_PROGRESS·CONFIRMED·CANCELING·CANCELED | `NOT_RELEASABLE` | 없음 | 409 `INVALID_STATUS_TRANSITION` | 그대로 |

- 없는 예약은 `RESERVATION_NOT_FOUND`, 남의 예약은 `FORBIDDEN` 예외다. 그 밖의 경우는 모두 결과로 알린다.
- 결제 시작과 해제가 동시에 와도 둘 다 `status = HOLD` 조건부 UPDATE라 한쪽만 성공한다. 진 쪽은 `NOT_RELEASABLE`이다.
- 쿠폰을 잡아 둔 예약이면 booking이 같은 트랜잭션에서 `CouponUsage.release`를 부른다([CouponUsage](coupon-usage.md)). seat는 쿠폰을 신경 쓰지 않는다.

### countActiveSeats · findActiveHold · expireIfDue · findPaymentInProgress

| 메서드 | 동작 |
|---|---|
| `countActiveSeats(userId, scheduleId)` | HOLD·PAYMENT_IN_PROGRESS·CONFIRMED·CANCELING 예약의 좌석 수 |
| `findActiveHold(userId, scheduleId)` | 이 회원의 이 회차 HOLD 예약(만료 여부 무관). PAYMENT_IN_PROGRESS 이후는 매수 제한과 UNIQUE가 막는다 |
| `expireIfDue(reservationId)` | `status = HOLD AND hold_expires_at <= :now`일 때만 EXPIRED. 바뀌었으면 true. 쿠폰이 있으면 같은 트랜잭션에서 해제한다 |
| `findPaymentInProgress(reservationIds)` | 넘긴 예약 중 PAYMENT_IN_PROGRESS인 것의 id. 다른 모듈 테이블 JOIN 대신 쓴다 |

`findPaymentInProgress`는 조회 최적화다. 판단 근거는 `expireIfDue`와 결제 시작(`hold_expires_at > :now`)의 조건부 UPDATE다. 조회와 만료 사이에 결제가 시작돼도 `expireIfDue`가 false를 돌려준다.

구현에서 정한 것:

- `countActiveSeats`는 예약 수가 아니라 티켓 수를 센다. `CANCELING`은 아직 상태 enum과 `booking.sql` CHECK에 없어 지금은 HOLD·PAYMENT_IN_PROGRESS·CONFIRMED만 센다. 취소를 구현해 상태를 더할 때 집계에도 더한다.
- `expireIfDue`는 만료 스케줄러와 같은 조건부 UPDATE를 쓰고, `updated_at`·`updated_by`를 쿼리에서 채운다. 결제 중이면 선점 시간이 지났어도 false다. 쿠폰 해제는 `CouponUsage`가 생기면 쿠폰 연동 이슈에서 붙인다.
- `findPaymentInProgress`에 빈 목록을 넘기면 쿼리 없이 빈 집합을 돌려준다.

## 오류·예외 책임

| 상황 | 던지는 쪽 | 응답 만드는 쪽 | 코드 |
|---|---|---|---|
| 부분 UNIQUE 위반 | booking(`hold`)이 그대로 올림 | seat, 트랜잭션 밖 | 409 `PURCHASE_LIMIT_EXCEEDED` (제약 이름으로만 변환) |
| 해제 불가·이미 끝남 | 예외 없이 `ReleaseResult` | seat | 409 / 204 |
| 조건부 변경 0행 | 예외 없이 false | 호출자 | 상황에 따라 409·204 |
| 없는 예약·남의 예약 | booking | 공통 예외 처리 | 404 `RESERVATION_NOT_FOUND`, 403 `FORBIDDEN` |
| `MANDATORY` 위반 | Spring | — | 개발 단계 버그. 테스트에서 잡는다 |

`PURCHASE_LIMIT_EXCEEDED`, `SEAT_NOT_FOUND`, `SEAT_ALREADY_TAKEN`, `SEAT_NOT_SELLABLE`, `SEAT_CONFIG_VERSION_MISMATCH`는 함께 쓰는 `ReservationErrorCode`에 좌석 담당이 추가한다.

## 변경 이력

| 날짜 | 이슈·PR | 내용 |
|---|---|---|
| 2026-10-27 | #34 | 초안. 좌석 담당 제안 v1과 #34 합의(시계 통일, release 결과 4개, facade 패키지, 트랜잭션 밖 409 변환)를 반영 |
| 2026-10-27 | #59 | `countActiveSeats`·`findActiveHold`·`expireIfDue`·`findPaymentInProgress` 구현. `getReservation` 이름 확인(PR #43) 반영 |
